package catalog

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"melora/internal/netguard"
)

// 目录加固参数：允许正常浏览的瞬时并发，但把长期平均速率压到很低；
// 上游明确限流时按 Retry-After 冷却，不自动重试，避免加重风控。
const (
	catalogHostBurst       = 12.0
	catalogHostRatePerSec  = 4.0
	catalogMaxHosts        = 64
	catalogDefaultCooldown = 60 * time.Second
	catalogMaxCooldown     = 10 * time.Minute

	catalogCacheTTL        = 45 * time.Second
	catalogCacheMaxEntries = 128
	catalogCacheMaxBytes   = 4 << 20
)

// errCatalogRateLimited 表示本机主动限流或上游限流冷却，语义为暂不可用。
type catalogRateLimitedError struct{}

func (catalogRateLimitedError) Error() string            { return "目录请求频率受限，请稍后重试" }
func (catalogRateLimitedError) Unwrap() error            { return ErrUnavailable }
func (catalogRateLimitedError) CatalogIssueCode() string { return "rate_limited" }

var errCatalogRateLimited error = catalogRateLimitedError{}

type hostBudget struct {
	tokens       float64
	last         time.Time
	blockedUntil time.Time
}

// hostLimiter 是每 host 令牌桶 + 冷却窗口；now 仅测试可替换。
type hostLimiter struct {
	mu    sync.Mutex
	hosts map[string]*hostBudget
	now   func() time.Time
}

func newHostLimiter(now func() time.Time) *hostLimiter {
	if now == nil {
		now = time.Now
	}
	return &hostLimiter{hosts: map[string]*hostBudget{}, now: now}
}

// take 尝试消耗一个令牌；返回 false 时附带预计可再次尝试的等待时长。
func (l *hostLimiter) take(host string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	budget, ok := l.hosts[host]
	if !ok {
		// 目录 host 是固定白名单；重置只是防御未来误加任意主机后的无界增长。
		if len(l.hosts) >= catalogMaxHosts {
			l.hosts = map[string]*hostBudget{}
		}
		budget = &hostBudget{tokens: catalogHostBurst, last: now}
		l.hosts[host] = budget
	}
	if wait := budget.blockedUntil.Sub(now); wait > 0 {
		return false, wait
	}
	if elapsed := now.Sub(budget.last).Seconds(); elapsed > 0 {
		budget.tokens = math.Min(catalogHostBurst, budget.tokens+elapsed*catalogHostRatePerSec)
		budget.last = now
	}
	if budget.tokens < 1 {
		return false, time.Duration((1 - budget.tokens) / catalogHostRatePerSec * float64(time.Second))
	}
	budget.tokens--
	return true, 0
}

func (l *hostLimiter) block(host string, delay time.Duration) {
	if delay <= 0 {
		return
	}
	if delay > catalogMaxCooldown {
		delay = catalogMaxCooldown
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	budget, ok := l.hosts[host]
	if !ok {
		budget = &hostBudget{tokens: catalogHostBurst, last: now}
		l.hosts[host] = budget
	}
	if until := now.Add(delay); until.After(budget.blockedUntil) {
		budget.blockedUntil = until
	}
}

// retryAfterDelay 只读取 429/503 的标准 Retry-After；缺失时仅对 429 使用保守默认冷却。
// 秒数先与冷却上限比较再转换为 Duration，避免超大值在乘法时溢出为负数而绕过冷却。
func retryAfterDelay(resp *http.Response, now time.Time) (time.Duration, bool) {
	if resp == nil || (resp.StatusCode != http.StatusTooManyRequests && resp.StatusCode != http.StatusServiceUnavailable) {
		return 0, false
	}
	maxSeconds := int64(catalogMaxCooldown / time.Second)
	if value := strings.TrimSpace(resp.Header.Get("Retry-After")); value != "" {
		if seconds, err := strconv.ParseInt(value, 10, 64); err == nil && seconds >= 0 {
			if seconds >= maxSeconds {
				return catalogMaxCooldown, true
			}
			return time.Duration(seconds) * time.Second, true
		} else if errors.Is(err, strconv.ErrRange) && value[0] != '-' {
			// 超出 int64 的正数同样按最大冷却处理，不能因解析溢出而失去退避保护。
			return catalogMaxCooldown, true
		}
		if when, err := http.ParseTime(value); err == nil {
			if delay := when.Sub(now); delay > catalogMaxCooldown {
				return catalogMaxCooldown, true
			} else if delay > 0 {
				return delay, true
			}
			return time.Second, true
		}
	}
	if resp.StatusCode == http.StatusTooManyRequests {
		return catalogDefaultCooldown, true
	}
	return 0, false
}

type cachedResponse struct {
	status  int
	header  http.Header
	body    []byte
	expires time.Time
}

// responseCache 只缓存 GET + 200 + JSON；请求键包含 Accept/Referer，避免跨调用方串响应。
type responseCache struct {
	mu      sync.Mutex
	entries map[string]cachedResponse
	bytes   int
	now     func() time.Time
}

func newResponseCache(now func() time.Time) *responseCache {
	if now == nil {
		now = time.Now
	}
	return &responseCache{entries: map[string]cachedResponse{}, now: now}
}

func (c *responseCache) key(request *http.Request) string {
	if request == nil || request.URL == nil || request.Method != http.MethodGet {
		return ""
	}
	var builder strings.Builder
	builder.WriteString(request.URL.String())
	builder.WriteByte('\n')
	builder.WriteString(request.Header.Get("Accept"))
	builder.WriteByte('\n')
	builder.WriteString(request.Header.Get("Referer"))
	return builder.String()
}

func (c *responseCache) get(key string) (*http.Response, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[key]
	if !ok {
		return nil, false
	}
	if !c.now().Before(entry.expires) {
		delete(c.entries, key)
		c.bytes -= len(entry.body)
		return nil, false
	}
	header := make(http.Header, len(entry.header))
	for name, values := range entry.header {
		header[name] = append([]string(nil), values...)
	}
	return &http.Response{
		StatusCode:    entry.status,
		Status:        fmt.Sprintf("%d %s", entry.status, http.StatusText(entry.status)),
		Header:        header,
		Body:          io.NopCloser(bytes.NewReader(entry.body)),
		ContentLength: int64(len(entry.body)),
	}, true
}

func (c *responseCache) put(key string, resp *http.Response, body []byte) {
	if key == "" || len(body) == 0 || len(body) > netguard.MaxResponseBytes {
		return
	}
	if !strings.Contains(strings.ToLower(resp.Header.Get("Content-Type")), "json") {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	for name, entry := range c.entries {
		if !now.Before(entry.expires) {
			delete(c.entries, name)
			c.bytes -= len(entry.body)
		}
	}
	if prior, ok := c.entries[key]; ok {
		c.bytes -= len(prior.body)
	}
	// 容量用尽时整体回收；与WY目录的缓存策略一致，不做逐条 LRU。
	if len(c.entries) >= catalogCacheMaxEntries || c.bytes+len(body) > catalogCacheMaxBytes {
		c.entries = map[string]cachedResponse{}
		c.bytes = 0
	}
	header := make(http.Header, len(resp.Header))
	for name, values := range resp.Header {
		header[name] = append([]string(nil), values...)
	}
	c.entries[key] = cachedResponse{status: resp.StatusCode, header: header, body: append([]byte(nil), body...), expires: now.Add(catalogCacheTTL)}
	c.bytes += len(body)
}

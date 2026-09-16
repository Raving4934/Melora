package catalog

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"melora/internal/netguard"
)

// 封面代理只允许这些固定图片域（含子域）；不接受用户自定义主机。
var coverImageHosts = []string{
	"music.126.net",
	"y.gtimg.cn", "qpic.y.qq.com", "p.qpic.cn", "music-file.y.qq.com",
	"img1.kuwo.cn", "img2.kuwo.cn", "img3.kuwo.cn", "img4.kuwo.cn",
	"kwimg1.kuwo.cn", "kwimg2.kuwo.cn", "kwimg3.kuwo.cn", "kwimg4.kuwo.cn",
	"kwcdn.kuwo.cn", "sycdn.kuwo.cn", "h5s.kuwo.cn",
	"imge.kugou.com", "imgessl.kugou.com", "singerimg.kugou.com", "singerimgss.kugou.com", "kgimg.com",
	"d.musicapp.migu.cn",
}

var (
	// ErrCoverTarget 表示地址不在固定图片域白名单内。
	ErrCoverTarget = errors.New("封面地址不在允许范围")
	// ErrCoverUnavailable 表示上游图片不可用或响应不符合图片合同。
	ErrCoverUnavailable = errors.New("封面暂不可用")
)

const (
	coverCacheTTL        = 6 * time.Hour
	coverCacheMaxEntries = 256
	coverCacheMaxBytes   = 32 << 20
	coverBodyLimit       = 1 << 20
)

// CoverTarget 校验并归一化封面地址：仅允许固定图片域、强制 HTTPS、去掉片段，
// 保留路径与查询串。返回 false 时不发起任何网络请求。
func CoverTarget(raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > 2048 {
		return "", false
	}
	u, err := url.Parse(raw)
	if err != nil || u.User != nil || u.Opaque != "" || u.Port() != "" {
		return "", false
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", false
	}
	host := strings.ToLower(u.Hostname())
	if host == "" || strings.HasSuffix(host, ".") || strings.IndexFunc(host, func(r rune) bool { return r < 0x21 || r > 0x7e }) >= 0 {
		return "", false
	}
	allowed := false
	for _, base := range coverImageHosts {
		if host == base || strings.HasSuffix(host, "."+base) {
			allowed = true
			break
		}
	}
	if !allowed {
		return "", false
	}
	u.Scheme = "https"
	u.Host = host
	u.Fragment = ""
	return u.String(), true
}

func coverMIMEAllowed(value string) bool {
	switch strings.ToLower(strings.TrimSpace(strings.Split(value, ";")[0])) {
	case "image/jpeg", "image/png", "image/webp":
		return true
	default:
		return false
	}
}

type coverCacheEntry struct {
	data    []byte
	mime    string
	expires time.Time
}
type coverCall struct {
	done chan struct{}
	data []byte
	mime string
	err  error
}

// CoverStore 通过固定白名单与公网 Broker 读取封面并做有界缓存、并发合并与按域限流。
// 作用：浏览器只访问同源封面代理，避免客户端无法直连平台 CDN 的 DNS/IPv6 故障。
type CoverStore struct {
	fetch     func(context.Context, string) ([]byte, string, error)
	now       func() time.Time
	limits    *hostLimiter
	mu        sync.Mutex
	entries   map[string]coverCacheEntry
	bytes     int
	inflight  map[string]*coverCall
	close     func()
	closeOnce sync.Once
}

// NewCoverStoreWith 支持注入取图函数，便于测试固定响应。
func NewCoverStoreWith(fetch func(context.Context, string) ([]byte, string, error), now func() time.Time) *CoverStore {
	if now == nil {
		now = time.Now
	}
	return &CoverStore{
		fetch:    fetch,
		now:      now,
		limits:   newHostLimiter(now),
		entries:  map[string]coverCacheEntry{},
		inflight: map[string]*coverCall{},
	}
}

// NewCoverStore 使用与目录相同的公网安全边界读取图片，允许长连接复用。
func NewCoverStore() *CoverStore {
	broker, err := netguard.NewBroker(netguard.Options{
		KeepAlive:                   true,
		MaxRequests:                 1 << 24,
		AllowedRedirectHostSuffixes: append([]string(nil), coverImageHosts...),
	})
	if err != nil {
		return NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
			return nil, "", ErrCoverUnavailable
		}, time.Now)
	}
	fetch := func(ctx context.Context, raw string) ([]byte, string, error) {
		response, err := broker.Do(ctx, netguard.Request{URL: raw, Method: http.MethodGet})
		if err != nil {
			if ctx.Err() != nil {
				return nil, "", ctx.Err()
			}
			return nil, "", ErrCoverUnavailable
		}
		if response.StatusCode != http.StatusOK {
			return nil, "", ErrCoverUnavailable
		}
		contentType := ""
		for name, value := range response.Headers {
			if strings.EqualFold(name, "Content-Type") {
				contentType = value
				break
			}
		}
		return response.Body, contentType, nil
	}
	store := NewCoverStoreWith(fetch, time.Now)
	store.close = broker.Close
	return store
}

// Close 释放封面代理复用的空闲连接；可重复调用。
func (c *CoverStore) Close() {
	if c == nil {
		return
	}
	c.closeOnce.Do(func() {
		if c.close != nil {
			c.close()
		}
	})
}

func (c *CoverStore) evictLocked(now time.Time) {
	for key, entry := range c.entries {
		if !now.Before(entry.expires) {
			delete(c.entries, key)
			c.bytes -= len(entry.data)
		}
	}
}

// Get 读取封面：命中缓存直接返回；同 URL 并发请求只向上游取一次。
func (c *CoverStore) Get(ctx context.Context, raw string) ([]byte, string, error) {
	target, ok := CoverTarget(raw)
	if !ok {
		return nil, "", ErrCoverTarget
	}
	parsed, _ := url.Parse(target)
	hostName := parsed.Host

	for {
		c.mu.Lock()
		now := c.now()
		if entry, exists := c.entries[target]; exists {
			if now.Before(entry.expires) {
				data, mime := append([]byte(nil), entry.data...), entry.mime
				c.mu.Unlock()
				return data, mime, nil
			}
			delete(c.entries, target)
			c.bytes -= len(entry.data)
		}
		if call, exists := c.inflight[target]; exists {
			c.mu.Unlock()
			select {
			case <-call.done:
				if call.err != nil {
					// 首个请求断开不应污染仍在等待的浏览器请求；重新竞争一次共享取图。
					if (errors.Is(call.err, context.Canceled) || errors.Is(call.err, context.DeadlineExceeded)) && ctx.Err() == nil {
						continue
					}
					return nil, "", call.err
				}
				return append([]byte(nil), call.data...), call.mime, nil
			case <-ctx.Done():
				return nil, "", ctx.Err()
			}
		}
		call := &coverCall{done: make(chan struct{})}
		c.inflight[target] = call
		c.mu.Unlock()

		if allowed, _ := c.limits.take(hostName); !allowed {
			call.err = ErrCoverUnavailable
		} else {
			data, mime, err := c.fetch(ctx, target)
			switch {
			case err != nil:
				call.err = err
			case !coverMIMEAllowed(mime):
				call.err = ErrCoverUnavailable
			case len(data) == 0 || len(data) > coverBodyLimit:
				call.err = ErrCoverUnavailable
			default:
				call.data, call.mime = data, mime
			}
		}

		c.mu.Lock()
		delete(c.inflight, target)
		if call.err == nil {
			c.evictLocked(c.now())
			if len(c.entries) >= coverCacheMaxEntries || c.bytes+len(call.data) > coverCacheMaxBytes {
				c.entries = map[string]coverCacheEntry{}
				c.bytes = 0
			}
			c.entries[target] = coverCacheEntry{data: append([]byte(nil), call.data...), mime: call.mime, expires: c.now().Add(coverCacheTTL)}
			c.bytes += len(call.data)
		}
		close(call.done)
		c.mu.Unlock()

		if call.err != nil {
			return nil, "", call.err
		}
		return append([]byte(nil), call.data...), call.mime, nil
	}
}

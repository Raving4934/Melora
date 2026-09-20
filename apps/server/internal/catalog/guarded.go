package catalog

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"time"

	"melora/internal/netguard"
)

// 仅固定平台目录主机；不把用户提交的URL或LX脚本的任意主机加入此表。
// 同一份名单也用于拒绝跨主机的目录重定向。
var catalogHostList = []string{
	"music.163.com",
	"u.y.qq.com", "c.y.qq.com",
	"search.kuwo.cn", "qukudata.kuwo.cn", "kbangserver.kuwo.cn", "wapi.kuwo.cn", "nplserver.kuwo.cn", "m.kuwo.cn", "mobileinterfaces.kuwo.cn",
	"mobiles.kugou.com", "m.kugou.com", "songsearch.kugou.com", "lyrics.kugou.com",
	"app.c.nf.migu.cn", "c.musicapp.migu.cn", "d.musicapp.migu.cn",
}

var catalogHosts = func() map[string]bool {
	hosts := make(map[string]bool, len(catalogHostList))
	for _, host := range catalogHostList {
		hosts[host] = true
	}
	return hosts
}()

// 目录 Broker 是长生命周期实例：进程内连接复用、跨请求限流与缓存共用同一预算。
// 预算足够覆盖数周的正常浏览，仅防脚本化轰炸；LX 沙箱仍使用默认的 12 次。
const catalogBrokerRequests = 1 << 24

// GuardedClient 使用相同公网DNS/重定向/媒体MIME/体积限制，绝不代理音频。
// 在保持逐请求公网校验的前提下复用已验证连接，并加按 host 限流、Retry-After 冷却与短 TTL 缓存。
func GuardedClient() HTTPDoer {
	broker, err := netguard.NewBroker(netguard.Options{
		MaxRequests:          catalogBrokerRequests,
		KeepAlive:            true,
		AllowedRedirectHosts: catalogHostList,
	})
	if err != nil {
		return unavailableDoer{}
	}
	return &guardedClient{broker: broker, limits: newHostLimiter(time.Now), cache: newResponseCache(time.Now)}
}

type guardedClient struct {
	broker brokerDoer
	limits *hostLimiter
	cache  *responseCache
}

// brokerDoer 便于测试注入固定响应；生产实现是 netguard.Broker。
type brokerDoer interface {
	Do(context.Context, netguard.Request) (netguard.Response, error)
}

// unavailableDoer 在 Broker 构造失败时保持“所有目录请求安全失败”的合同。
type unavailableDoer struct{}

func (unavailableDoer) Do(*http.Request) (*http.Response, error) { return nil, ErrUnavailable }

func (c *guardedClient) Do(request *http.Request) (*http.Response, error) {
	if request == nil || request.URL == nil || request.URL.Scheme != "https" || !catalogHosts[request.URL.Host] || request.URL.User != nil {
		return nil, ErrUnavailable
	}
	if request.Method != http.MethodGet {
		return nil, ErrUnavailable
	}
	var body []byte
	if request.Body != nil {
		var err error
		body, err = io.ReadAll(io.LimitReader(request.Body, netguard.MaxRequestBytes+1))
		request.Body.Close()
		if err != nil || len(body) > netguard.MaxRequestBytes {
			return nil, ErrUnavailable
		}
	}
	// 缓存先于限流：相同目录请求在 TTL 内不消耗上游预算。
	key := c.cache.key(request)
	if key != "" {
		if cached, ok := c.cache.get(key); ok {
			return cached, nil
		}
	}
	host := request.URL.Host
	if ok, _ := c.limits.take(host); !ok {
		return nil, errCatalogRateLimited
	}
	headers := map[string]string{}
	for name, values := range request.Header {
		if len(values) > 0 {
			headers[name] = values[0]
		}
	}
	response, err := c.broker.Do(request.Context(), netguard.Request{URL: request.URL.String(), Method: request.Method, Headers: headers, Body: body})
	if err != nil {
		return nil, err
	}
	out := &http.Response{StatusCode: response.StatusCode, Status: response.StatusMessage, Header: make(http.Header), Body: io.NopCloser(bytes.NewReader(response.Body)), Request: request, ContentLength: int64(len(response.Body))}
	for name, value := range response.Headers {
		out.Header.Set(name, value)
	}
	if delay, blocked := retryAfterDelay(out, time.Now()); blocked {
		c.limits.block(host, delay)
	}
	if key != "" && out.StatusCode == http.StatusOK {
		c.cache.put(key, out, response.Body)
	}
	return out, nil
}

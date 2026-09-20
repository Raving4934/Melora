package netguard

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// PublicFetchMaxBytes 是在线导入资源的传输上限，与 LX 脚本大小上限保持一致。
const PublicFetchMaxBytes = 512 << 10

// PublicFetchResult 是通过公网安全边界取得的有限响应。
// FinalURL 和 Headers 仅供服务端内部推导文件名，不直接返回给客户端。
type PublicFetchResult struct {
	StatusCode int
	FinalURL   string
	Headers    map[string]string
	Body       []byte
}

// FetchPublic 通过现有 safeTransport 访问公网 HTTP/HTTPS 资源。
// 它不代理音频，也不接受调用方自定义请求头、HTTP 白名单或私网例外。
func FetchPublic(ctx context.Context, raw string) (PublicFetchResult, error) {
	broker, err := NewBroker(Options{AllowPublicHTTP: true, MaxRequests: 4})
	if err != nil {
		return PublicFetchResult{}, ErrPolicy
	}
	defer broker.Close()
	return broker.fetchPublic(ctx, raw, PublicFetchMaxBytes)
}

// fetchPublic 保留在 Broker 内部，测试可注入 DNS/Transport 验证安全边界，
// 生产调用仍只能通过 FetchPublic 使用默认 safeTransport。
func (b *Broker) fetchPublic(ctx context.Context, raw string, maxBytes int) (PublicFetchResult, error) {
	if maxBytes < 1 || maxBytes > PublicFetchMaxBytes || len(raw) > 8192 {
		return PublicFetchResult{}, ErrLimit
	}
	u, err := url.Parse(raw)
	if err != nil || b.client.Transport.(*safeTransport).validateURL(u) != nil {
		return PublicFetchResult{}, ErrPolicy
	}
	if b.requests.Add(1) > b.maxRequests {
		return PublicFetchResult{}, ErrLimit
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return PublicFetchResult{}, ErrPolicy
	}
	request.Header.Set("Accept", "application/javascript, text/javascript, */*;q=0.8")
	request.Header.Set("Accept-Encoding", "identity")
	request.Header.Set("User-Agent", "Melora-LX-Source-Importer/1")

	// 通用 Broker 允许元数据请求按旧合同在 HTTPS/HTTP 间跳转；在线导入则必须
	// 保持初始 HTTPS 的安全级别，HTTP 初始地址才允许继续 HTTP 或升级 HTTPS。
	client := *b.client
	baseRedirect := client.CheckRedirect
	initialScheme := u.Scheme
	client.CheckRedirect = func(next *http.Request, via []*http.Request) error {
		if initialScheme == "https" && next.URL.Scheme == "http" {
			return ErrPolicy
		}
		if baseRedirect != nil {
			return baseRedirect(next, via)
		}
		return nil
	}
	response, err := client.Do(request)
	if err != nil {
		switch {
		case errors.Is(err, ErrLimit):
			return PublicFetchResult{}, ErrLimit
		case errors.Is(err, ErrPolicy):
			return PublicFetchResult{}, ErrPolicy
		default:
			return PublicFetchResult{}, ErrRequest
		}
	}
	defer response.Body.Close()
	if response.ContentLength > int64(maxBytes) {
		return PublicFetchResult{}, ErrLimit
	}
	encoding := strings.ToLower(strings.TrimSpace(strings.Join(response.Header.Values("Content-Encoding"), ",")))
	if encoding != "" && encoding != "identity" {
		return PublicFetchResult{}, ErrPolicy
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, int64(maxBytes)+1))
	if err != nil {
		return PublicFetchResult{}, ErrRequest
	}
	if len(body) > maxBytes {
		return PublicFetchResult{}, ErrLimit
	}
	headers := make(map[string]string, len(response.Header))
	for key, values := range response.Header {
		headers[strings.ToLower(key)] = strings.Join(values, ", ")
	}
	finalURL := raw
	if response.Request != nil && response.Request.URL != nil {
		finalURL = response.Request.URL.String()
	}
	return PublicFetchResult{StatusCode: response.StatusCode, FinalURL: finalURL, Headers: headers, Body: body}, nil
}

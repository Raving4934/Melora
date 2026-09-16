// Package netguard 为元数据请求提供有界的公网 Broker；绝不代理音频下载。
package netguard

import (
	"bytes"
	"compress/gzip"
	"compress/zlib"
	"context"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"time"
)

const (
	MaxResponseBytes = 1 << 20
	MaxRequestBytes  = 64 << 10
	MaxRequests      = 12
)

type Options struct {
	AllowHTTPHosts []string // 兼容旧配置；仍须通过完整主机名校验。
	// AllowPublicHTTP 仅由 LX Manager 为元数据 Broker 开启，不接受脚本参数。
	// HTTP 可能明文传输；此权限不改变媒体校验或默认 Broker 的 HTTPS 策略。
	AllowPublicHTTP bool
	// MaxRequests 限制单个 Broker 生命周期内实际发出的请求数（含重定向）；<=0 使用默认值 MaxRequests。
	MaxRequests int
	// KeepAlive 允许复用已验证的公网连接；仅固定目录客户端开启，LX 沙箱保持默认关闭。
	KeepAlive bool
	// AllowedRedirectHosts 非空时要求每一跳重定向都落在这些固定主机内；
	// 目录客户端用它落实“仅访问平台目录主机”的出站合同，LX 沙箱默认不限制。
	AllowedRedirectHosts []string
	// AllowedRedirectHostSuffixes 允许受信任的固定 CDN 域及其子域作为重定向目标。
	// 仅由内置客户端配置；脚本与外部请求不能修改。
	AllowedRedirectHostSuffixes []string
}

var (
	ErrPolicy  = errors.New("LX 网络请求不符合公网安全策略")
	ErrRequest = errors.New("LX 元数据请求失败")
	ErrLimit   = errors.New("LX 网络请求数量或大小超过限制")
	ErrMedia   = errors.New("LX 元数据请求拒绝音频或视频响应")
)

// ValidateURL 只验证最终媒体 URL，不下载媒体。即使 opts 配有 HTTP
// 白名单或启用公网 HTTP，这里仍严格要求 HTTPS；播放/下载时仍应防范后续 DNS 或重定向变化。
func ValidateURL(ctx context.Context, raw string, _ Options) error {
	u, err := url.Parse(raw)
	if err != nil {
		return ErrPolicy
	}
	guard := safeTransport{lookup: net.DefaultResolver.LookupNetIP, interfaceAddrs: net.InterfaceAddrs}
	if _, err = guard.target(ctx, u); err != nil {
		return ErrPolicy
	}
	return nil
}

type Request struct {
	URL     string            `json:"url"`
	Method  string            `json:"method"`
	Headers map[string]string `json:"headers,omitempty"`
	Body    []byte            `json:"body,omitempty"`
	Timeout int               `json:"timeout,omitempty"`
}
type Response struct {
	StatusCode    int               `json:"statusCode"`
	StatusMessage string            `json:"statusMessage"`
	Headers       map[string]string `json:"headers"`
	Body          []byte            `json:"body"`
}
type Broker struct {
	client       *http.Client
	closeIdle    func()
	requests     atomic.Int32
	wireRequests atomic.Int32
	maxRequests  int32
}

func NewBroker(opts Options) (*Broker, error) { return newBroker(opts, nil, nil) }

// exactHostSet 只接受有限数量的完整规范公网主机名；HTTP 与重定向白名单
// 共用同一校验，避免其中一条出站路径接受空值、单标签名或非法 DNS label。
func exactHostSet(values []string) (map[string]bool, error) {
	if len(values) > 32 {
		return nil, ErrPolicy
	}
	hosts := make(map[string]bool, len(values))
	for _, host := range values {
		// 不接受通配符、后缀、端口、URL、尾点等价形式或非规范大小写。
		if host != strings.ToLower(host) || strings.ContainsAny(host, "/:*%\\?#@") || strings.HasSuffix(host, ".") {
			return nil, ErrPolicy
		}
		u := &url.URL{Scheme: "https", Host: host}
		if validateURL(u, nil) != nil {
			return nil, ErrPolicy
		}
		hosts[host] = true
	}
	return hosts, nil
}

func newBroker(opts Options, lookup lookupFunc, transport http.RoundTripper) (*Broker, error) {
	hosts, err := exactHostSet(opts.AllowHTTPHosts)
	if err != nil {
		return nil, err
	}
	redirectHosts, err := exactHostSet(opts.AllowedRedirectHosts)
	if err != nil {
		return nil, err
	}
	redirectHostSuffixes, err := exactHostSet(opts.AllowedRedirectHostSuffixes)
	if err != nil {
		return nil, err
	}
	maxRequests := int32(opts.MaxRequests)
	if maxRequests <= 0 {
		maxRequests = MaxRequests
	}
	client, closeIdle := makeClient(hosts, lookup, transport, opts.KeepAlive)
	broker := &Broker{client: client, closeIdle: closeIdle, maxRequests: maxRequests}
	guard := client.Transport.(*safeTransport)
	guard.requests = &broker.wireRequests
	guard.maxRequests = maxRequests
	guard.allowPublicHTTP = opts.AllowPublicHTTP
	if len(redirectHosts) > 0 {
		guard.redirectHosts = redirectHosts
	}
	if len(redirectHostSuffixes) > 0 {
		guard.redirectHostSuffixes = redirectHostSuffixes
	}
	return broker, nil
}
func (b *Broker) Close() { b.closeIdle() }

func (b *Broker) Do(ctx context.Context, request Request) (Response, error) {
	if b.requests.Add(1) > b.maxRequests {
		return Response{}, ErrLimit
	}
	if len(request.URL) > 8192 || len(request.Body) > MaxRequestBytes || len(request.Headers) > 32 {
		return Response{}, ErrLimit
	}
	method := strings.ToUpper(request.Method)
	if method == "" {
		method = "GET"
	}
	if method != "GET" && method != "POST" && method != "HEAD" {
		return Response{}, ErrPolicy
	}
	timeout := 5 * time.Second
	if request.Timeout > 0 && request.Timeout < 5000 {
		timeout = time.Duration(request.Timeout) * time.Millisecond
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	// net/http 创建请求时会抹去空端口，必须先校验原始 URL，防止规范化掩盖违规输入。
	u, err := url.Parse(request.URL)
	if err != nil || b.client.Transport.(*safeTransport).validateURL(u) != nil {
		return Response{}, ErrPolicy
	}
	req, err := http.NewRequestWithContext(ctx, method, request.URL, bytes.NewReader(request.Body))
	if err != nil {
		return Response{}, ErrPolicy
	}
	size := 0
	for name, value := range request.Headers {
		lower := strings.ToLower(name)
		size += len(name) + len(value)
		if size > 16<<10 || len(name) > 128 || strings.ContainsAny(name+value, "\r\n\x00") {
			return Response{}, ErrPolicy
		}
		switch lower {
		case "host", "connection", "upgrade", "proxy-authorization", "proxy-connection", "content-length", "transfer-encoding", "range", "accept-encoding", "te", "trailer":
			return Response{}, ErrPolicy
		}
		req.Header.Set(name, value)
	}
	req.Header.Set("Accept-Encoding", "identity")
	if req.Header.Get("Accept") == "" {
		req.Header.Set("Accept", "*/*")
	}
	if req.Header.Get("User-Agent") == "" {
		req.Header.Set("User-Agent", "Melora-LX-Metadata/1")
	}
	resp, err := b.client.Do(req)
	if err != nil {
		if errors.Is(err, ErrLimit) {
			return Response{}, ErrLimit
		}
		return Response{}, ErrRequest
	}
	defer resp.Body.Close()
	// HEAD 的 Content-Length 表示完整媒体大小，不是传输正文大小。
	// LX 源可能先探测媒体可达性；只返已通过公网逐跳校验的响应头，绝不读取媒体。
	if method == http.MethodHead {
		return metadataResponse(resp, nil), nil
	}
	if resp.ContentLength > MaxResponseBytes {
		return Response{}, ErrLimit
	}
	kind, _, _ := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if strings.HasPrefix(kind, "audio/") || strings.HasPrefix(kind, "video/") || kind == "application/ogg" || kind == "application/vnd.apple.mpegurl" || kind == "application/x-mpegurl" || kind == "application/dash+xml" {
		return Response{}, ErrMedia
	}
	// 多行 Content-Encoding 同样表示编码链，不能只读取第一项绕过多层拒绝。
	encoding := strings.ToLower(strings.TrimSpace(strings.Join(resp.Header.Values("Content-Encoding"), ",")))
	switch encoding {
	case "", "identity", "gzip", "x-gzip", "deflate", "x-deflate":
	default:
		return Response{}, ErrPolicy
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, MaxResponseBytes+1))
	if err != nil {
		return Response{}, ErrRequest
	}
	if len(body) > MaxResponseBytes {
		return Response{}, ErrLimit
	}
	// 压缩前后分别限额；即使服务端忽略 identity，也不会将压缩媒体或炸弹放行。
	if encoding != "" && encoding != "identity" {
		var decoded io.ReadCloser
		if encoding == "gzip" || encoding == "x-gzip" {
			decoded, err = gzip.NewReader(bytes.NewReader(body))
		} else {
			decoded, err = zlib.NewReader(bytes.NewReader(body))
		}
		if err != nil {
			return Response{}, ErrPolicy
		}
		plain, readErr := io.ReadAll(io.LimitReader(decoded, MaxResponseBytes+1))
		_ = decoded.Close()
		if len(plain) > MaxResponseBytes {
			return Response{}, ErrLimit
		}
		if readErr != nil {
			return Response{}, ErrPolicy
		}
		body = plain
	}
	if mediaBody(body) {
		return Response{}, ErrMedia
	}
	return metadataResponse(resp, body), nil
}
func metadataResponse(resp *http.Response, body []byte) Response {
	headers := make(map[string]string)
	for k, v := range resp.Header {
		headers[strings.ToLower(k)] = strings.Join(v, ", ")
	}
	return Response{StatusCode: resp.StatusCode, StatusMessage: http.StatusText(resp.StatusCode), Headers: headers, Body: body}
}
func mediaBody(b []byte) bool {
	t := http.DetectContentType(b)
	if strings.HasPrefix(t, "audio/") || strings.HasPrefix(t, "video/") {
		return true
	}
	return bytes.HasPrefix(b, []byte("ID3")) || bytes.HasPrefix(b, []byte("fLaC")) || bytes.HasPrefix(b, []byte("OggS")) || bytes.HasPrefix(bytes.TrimSpace(b), []byte("#EXTM3U")) ||
		len(b) >= 12 && (bytes.Equal(b[4:8], []byte("ftyp")) || bytes.Equal(b[:4], []byte("RIFF"))) || len(b) >= 2 && b[0] == 0xff && b[1]&0xe0 == 0xe0
}

// ValidatePlaybackURL 只验证浏览器媒体地址，不连接或代理媒体。
// HTTP仅对HTTP页面放行；与LX元数据HTTP兼容策略、服务端下载策略严格分开。
func ValidatePlaybackURL(ctx context.Context, raw, pageScheme string) error {
	if pageScheme != "http" && pageScheme != "https" {
		return ErrPolicy
	}
	return validateMediaURL(ctx, raw, pageScheme == "http")
}

// ValidateDownloadURL 用于用户主动下载的音频，HTTP不受浏览器混合内容限制；
// 与脚本元数据HTTP兼容策略独立，仍拒绝私网、本机、凭据以及所有不安全DNS结果。
func ValidateDownloadURL(ctx context.Context, raw string) error {
	return validateMediaURL(ctx, raw, true)
}
func validateMediaURL(ctx context.Context, raw string, allowHTTP bool) error {
	u, err := url.Parse(raw)
	if err != nil {
		return ErrPolicy
	}
	allowed := map[string]bool{}
	if u.Scheme == "http" && allowHTTP {
		allowed[strings.ToLower(u.Hostname())] = true
	}
	guard := safeTransport{httpHosts: allowed, lookup: net.DefaultResolver.LookupNetIP, interfaceAddrs: net.InterfaceAddrs}
	if _, err = guard.target(ctx, u); err != nil {
		return ErrPolicy
	}
	return nil
}

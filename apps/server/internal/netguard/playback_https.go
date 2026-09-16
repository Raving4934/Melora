package netguard

import (
	"context"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	playbackHTTPSBudget      = 1500 * time.Millisecond
	playbackHTTPSHeaderBytes = 16 << 10
	playbackHTTPSRequests    = 3 // 初次 HEAD 加最多两跳，不借用 LX Broker 的12次预算。
)

var playbackHTTPSConcurrent = make(chan struct{}, 4)

// UpgradePlaybackHTTPS 只验证标准 HTTP 域名目标的原站 HTTPS 候选。
// 全程 HEAD、不读取正文；成功不是音频 GET/编解码或 Range 已验证。
// 不适用于下载、额外请求头、IP/非标准端口，也没有 HTTP/GET 回退。
func UpgradePlaybackHTTPS(ctx context.Context, raw string) (string, error) {
	return (playbackHTTPSProbe{}).verify(ctx, raw)
}

// 替身仅包内可注入，生产使用严格 TLS、全部 DNS/网卡校验及固定 IP dial。
// budget 仅允许测试缩短，不可扩大生产上限。
type playbackHTTPSProbe struct {
	lookup             lookupFunc
	base               http.RoundTripper
	configureTransport func(*http.Transport)
	interfaces         func() ([]net.Addr, error)
	slots              chan struct{}
	budget             time.Duration
}

func (p playbackHTTPSProbe) verify(parent context.Context, raw string) (string, error) {
	if err := parent.Err(); err != nil {
		return "", err
	}
	u, err := playbackProbeURL(raw, "http")
	if err != nil {
		return "", ErrPolicy
	}
	// Go URL 不会像浏览器一样随 scheme 去除旧默认端口，不能向80发TLS。
	u.Scheme = "https"
	if u.Port() == "80" {
		u.Host = u.Hostname()
	}
	budget := p.budget
	if budget <= 0 || budget > playbackHTTPSBudget {
		budget = playbackHTTPSBudget
	}
	ctx, cancel := context.WithTimeout(parent, budget)
	defer cancel()
	slots := p.slots
	if slots == nil {
		slots = playbackHTTPSConcurrent
	}
	select {
	case slots <- struct{}{}:
		defer func() { <-slots }()
	case <-ctx.Done():
		return "", ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	client, closeIdle := makeClient(nil, p.lookup, nil, false)
	defer closeIdle()
	guard := client.Transport.(*safeTransport)
	transport := guard.base.(*http.Transport)
	// 仅此专用client收紧头上限，不改变LX元数据或全局netguard合同。
	transport.MaxResponseHeaderBytes = playbackHTTPSHeaderBytes
	if p.configureTransport != nil {
		p.configureTransport(transport)
	}
	if p.base != nil {
		guard.base = p.base
	}
	if p.interfaces != nil {
		guard.interfaceAddrs = p.interfaces
	}
	// 自动跳转可能设置 Referer 或为复用连接 drain body；这里逐跳重建 HEAD。
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	seen := map[string]bool{}
	for attempt := 0; attempt < playbackHTTPSRequests; attempt++ {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		target := u.String()
		if seen[target] {
			return "", ErrPolicy
		}
		seen[target] = true
		if _, err := playbackProbeURL(target, "https"); err != nil {
			return "", ErrPolicy
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodHead, target, nil)
		if err != nil {
			return "", ErrPolicy
		}
		req.Header = http.Header{
			"Accept":          {"audio/*, application/ogg"},
			"Accept-Encoding": {"identity"},
			"User-Agent":      {"Melora-HTTPS-Check/1"},
		}
		resp, err := client.Do(req)
		if resp != nil && resp.Body != nil {
			_ = resp.Body.Close() // 各状态均不 Read，包括跳转和错误响应。
		}
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		if err != nil || resp == nil {
			return "", ErrRequest // 不泄露包含签名URL的 *url.Error。
		}
		switch resp.StatusCode {
		case http.StatusMovedPermanently, http.StatusFound, http.StatusSeeOther, http.StatusTemporaryRedirect, http.StatusPermanentRedirect:
			locations := resp.Header.Values("Location")
			if attempt+1 >= playbackHTTPSRequests || len(locations) != 1 || locations[0] == "" || !playbackWireURL(locations[0]) {
				return "", ErrPolicy
			}
			// URL标准相对解析，不追加旧query；下一轮逐跳URL/DNS/网卡/TLS验证。
			next, err := u.Parse(locations[0])
			if err != nil {
				return "", ErrPolicy
			}
			u = next
		default:
			if !playbackAudioHEAD(resp) {
				return "", ErrPolicy
			}
			return target, nil
		}
	}
	return "", ErrPolicy
}

// 仅保留无需 legacy 转义的规范原始字节，避免 netguard 的 LX query 兼容
// 转义改写签名。保留重复键、RawPath、RawQuery、ForceQuery，不用 Values.Encode。
func playbackWireURL(raw string) bool {
	if raw == "" || len(raw) > 8192 || strings.ContainsAny(raw, "\"'<>`{}|\\^#") {
		return false
	}
	for i := 0; i < len(raw); i++ {
		if raw[i] <= ' ' || raw[i] > '~' {
			return false
		}
		if raw[i] == '%' {
			if i+2 >= len(raw) || !strings.ContainsRune("0123456789abcdefABCDEF", rune(raw[i+1])) || !strings.ContainsRune("0123456789abcdefABCDEF", rune(raw[i+2])) {
				return false
			}
			i += 2
		}
	}
	return true
}

func playbackProbeURL(raw, scheme string) (*url.URL, error) {
	if !playbackWireURL(raw) {
		return nil, ErrPolicy
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != scheme || u.String() != raw || validateURLPolicy(u, nil, scheme == "http") != nil || strings.HasSuffix(u.Host, ":") {
		return nil, ErrPolicy
	}
	if port := u.Port(); port != "" && port != defaultPort(scheme) {
		return nil, ErrPolicy
	}
	host := u.Hostname()
	if _, err := netip.ParseAddr(host); err == nil || strings.HasSuffix(host, ".") {
		return nil, ErrPolicy
	}
	// WHATWG特殊URL会把数字末标签尝试解释为IPv4，不能让Go DNS与浏览器
	// 对整数/短IPv4/十六进制主机有不同解释。这里只接受普通DNS域名。
	label := strings.ToLower(host[strings.LastIndexByte(host, '.')+1:])
	if strings.Trim(label, "0123456789") == "" || strings.HasPrefix(label, "0x") && strings.Trim(label[2:], "0123456789abcdef") == "" {
		return nil, ErrPolicy
	}
	return u, nil
}

func playbackAudioHEAD(resp *http.Response) bool {
	if resp.StatusCode != http.StatusOK || resp.ContentLength == 0 || resp.ContentLength < -1 {
		return false
	}
	kinds := resp.Header.Values("Content-Type")
	if len(kinds) != 1 {
		return false
	}
	kind, _, err := mime.ParseMediaType(kinds[0])
	if err != nil {
		return false
	}
	switch kind {
	case "audio/mpeg", "audio/mp3", "audio/flac", "audio/x-flac", "audio/ogg", "application/ogg", "audio/opus", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav", "audio/x-wav", "audio/wave":
	default:
		return false
	}
	encodings := resp.Header.Values("Content-Encoding")
	if len(encodings) > 1 || len(encodings) == 1 && !strings.EqualFold(strings.TrimSpace(encodings[0]), "identity") {
		return false
	}
	lengths := resp.Header.Values("Content-Length")
	if len(lengths) > 1 {
		return false
	}
	if len(lengths) == 1 {
		n, err := strconv.ParseInt(lengths[0], 10, 64)
		if err != nil || strings.Trim(lengths[0], "0123456789") != "" || n <= 0 || resp.ContentLength >= 0 && resp.ContentLength != n {
			return false
		}
	}
	return true
}

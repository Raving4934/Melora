package netguard

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

var (
	errUnsafeURL   = errors.New("LX 地址不符合公网安全策略")
	errDNS         = errors.New("LX 地址 DNS 验证失败")
	errNetwork     = errors.New("LX 元数据请求失败")
	errRateLimited = errors.New("音源请求频率受限，请稍后手动重试")
	errRedirect    = errors.New("LX 请求重定向次数超过限制")
)

// 保守拒绝特殊用途网段，包括其中少数可全球路由的特殊服务地址。
var blockedPrefixes = func() []netip.Prefix {
	var out []netip.Prefix
	for _, cidr := range []string{
		"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
		"168.63.129.16/32", // Azure 平台虚拟 IP，不是普通公网元数据服务。
		"169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
		"192.31.196.0/24", "192.52.193.0/24", "192.88.99.0/24", "192.168.0.0/16",
		"192.175.48.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
		"224.0.0.0/4", "240.0.0.0/4", "2001::/23", "2001:db8::/32",
		"2002::/16", "2620:4f:8000::/48", "3ffe::/16", "3fff::/20",
	} {
		out = append(out, netip.MustParsePrefix(cidr))
	}
	return out
}()

// IsPublicIP 判断地址是否可作为服务端网络请求的公网目标。
// 调用方仍须拒绝宿主机自身地址，并把已验证的 DNS 结果固定到实际 Dial，
// 避免 DNS rebinding 绕过此处的地址分类。
func IsPublicIP(ip netip.Addr) bool {
	if !ip.IsValid() || ip.Zone() != "" || ip.Is4In6() || !ip.IsGlobalUnicast() || ip.IsPrivate() {
		return false
	}
	if ip.Is6() && !netip.MustParsePrefix("2000::/3").Contains(ip) {
		return false
	}
	for _, p := range blockedPrefixes {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}

// ValidateDownloadURLSyntax 校验服务端下载 URL 的语法与字面量 IP，允许公网
// HTTP/HTTPS。该函数不解析 DNS；调用方必须在每次请求和实际 Dial 前使用
// IsPublicIP 复核全部解析结果。
func ValidateDownloadURLSyntax(u *url.URL) error {
	if err := validateURLPolicy(u, nil, true); err != nil {
		return ErrPolicy
	}
	return nil
}

func validateURL(u *url.URL, httpHosts map[string]bool) error {
	return validateURLPolicy(u, httpHosts, false)
}

func validateURLPolicy(u *url.URL, httpHosts map[string]bool, allowPublicHTTP bool) error {
	if u == nil || (u.Scheme != "https" && (u.Scheme != "http" || !allowPublicHTTP && !httpHosts[strings.ToLower(u.Hostname())])) || u.Opaque != "" || u.User != nil || u.Fragment != "" || len(u.String()) > 8192 {
		return errUnsafeURL
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if host == "" || strings.ContainsAny(host, "%\\\x00\r\n") {
		return errUnsafeURL
	}
	if port := u.Port(); port != "" {
		n, err := strconv.Atoi(port)
		if err != nil || n < 1 || n > 65535 {
			return errUnsafeURL
		}
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if !IsPublicIP(ip) {
			return errUnsafeURL
		}
		return nil
	}
	if len(host) > 253 || !strings.Contains(host, ".") {
		return errUnsafeURL
	}
	for _, suffix := range []string{".localhost", ".local", ".internal", ".home.arpa"} {
		if strings.HasSuffix(host, suffix) {
			return errUnsafeURL
		}
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return errUnsafeURL
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return errUnsafeURL
			}
		}
	}
	return nil
}

type lookupFunc func(context.Context, string, string) ([]netip.Addr, error)
type dialFunc func(context.Context, string, string) (net.Conn, error)
type pinKey struct{}
type pinnedTarget struct {
	host, port string
	ips        []netip.Addr
}

type safeTransport struct {
	requests             *atomic.Int32 // 每个 Broker 的实际请求预算，包含重定向。
	maxRequests          int32         // <=0 时使用包默认值；由 Broker 按 Options 注入。
	httpHosts            map[string]bool
	redirectHosts        map[string]bool // 非空时要求每一跳仍在固定主机内（目录客户端）。
	redirectHostSuffixes map[string]bool // 固定 CDN 域及其子域；仅受信任内置客户端配置。
	allowPublicHTTP      bool            // 仅父进程创建 LX Broker 时设置；脚本不能修改。
	metadataOnly         bool            // 媒体 URL 校验使用独立契约，不套用元数据端口策略。
	base                 http.RoundTripper
	lookup               lookupFunc
	interfaceAddrs       func() ([]net.Addr, error) // 仅包内测试可替换；生产固定为 net.InterfaceAddrs。
}

// 每次请求重新读取网卡地址，避免网卡更新或逐跳重定向复用旧的本机地址快照。
// 只比较网卡自身的 IP，不把网卡所在的整个公网子网误当作本机。
func localInterfaceIPs(read func() ([]net.Addr, error)) (map[netip.Addr]struct{}, error) {
	if read == nil {
		return nil, errUnsafeURL
	}
	addresses, err := read()
	if err != nil || len(addresses) == 0 {
		return nil, errUnsafeURL // 获取失败不降级放行，也不泄漏底层错误。
	}
	local := make(map[netip.Addr]struct{}, len(addresses))
	for _, address := range addresses {
		var raw net.IP
		switch address := address.(type) {
		case *net.IPNet:
			if address != nil {
				raw = address.IP
			}
		case *net.IPAddr:
			if address != nil {
				raw = address.IP
			}
		}
		ip, ok := netip.AddrFromSlice(raw)
		if !ok {
			return nil, errUnsafeURL // 无法识别的地址同样保守拒绝。
		}
		// net.IP 的 IPv4 常以 16 字节 mapped 形式返回，比较前必须归一化。
		local[ip.Unmap()] = struct{}{}
	}
	return local, nil
}

func defaultPort(scheme string) string {
	if scheme == "http" {
		return "80"
	}
	return "443"
}

func (s *safeTransport) validateURL(u *url.URL) error {
	if err := validateURLPolicy(u, s.httpHosts, s.allowPublicHTTP); err != nil {
		return err
	}
	// 元数据不是任意端口探测代理；拒绝空端口、非规范端口和跨协议端口。
	if s.metadataOnly && (strings.HasSuffix(u.Host, ":") || u.Port() != "" && u.Port() != defaultPort(u.Scheme)) {
		return errUnsafeURL
	}
	return nil
}

func sameOrigin(a, b *url.URL) bool {
	port := func(u *url.URL) string {
		if u.Port() != "" {
			return u.Port()
		}
		return defaultPort(u.Scheme)
	}
	return a.Scheme == b.Scheme && strings.EqualFold(a.Hostname(), b.Hostname()) && port(a) == port(b)
}

func (s *safeTransport) target(ctx context.Context, u *url.URL) (pinnedTarget, error) {
	if ctx.Err() != nil {
		return pinnedTarget{}, errNetwork
	}
	if err := s.validateURL(u); err != nil {
		return pinnedTarget{}, err
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	port := u.Port()
	if port == "" {
		port = defaultPort(u.Scheme)
	}
	var ips []netip.Addr
	if ip, err := netip.ParseAddr(host); err == nil {
		ips = []netip.Addr{ip}
	} else {
		ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		var err error
		ips, err = s.lookup(ctx, "ip", host)
		cancel()
		if err != nil {
			return pinnedTarget{}, errDNS
		}
	}
	if len(ips) == 0 || len(ips) > 64 {
		return pinnedTarget{}, errDNS
	}
	local, err := localInterfaceIPs(s.interfaceAddrs)
	if err != nil {
		return pinnedTarget{}, err
	}
	// 必须在连接任意地址之前验证全部 A/AAAA；私网或本机公网地址任一命中
	// 都拒绝整个请求。字面量 IP 和每一跳重定向也经过同一检查。
	for _, ip := range ips {
		if !IsPublicIP(ip) {
			return pinnedTarget{}, errUnsafeURL
		}
		if _, isLocal := local[ip.Unmap()]; isLocal {
			return pinnedTarget{}, errUnsafeURL
		}
	}
	pin := pinnedTarget{host: host, port: port, ips: append([]netip.Addr(nil), ips...)}
	return pin, nil
}

func (s *safeTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	limit := s.maxRequests
	if limit <= 0 {
		limit = MaxRequests
	}
	if s.requests != nil && s.requests.Add(1) > limit {
		return nil, ErrLimit
	}
	pin, err := s.target(req.Context(), req.URL)
	if err != nil {
		return nil, err
	}
	cloned := req.Clone(context.WithValue(req.Context(), pinKey{}, pin))
	// Node legacy URL（固定 LX Needle）会转义 query 中的字面空格/引号。
	// 不能 ParseQuery/重新排序或解码百分号，否则会改写重复键和已签名参数。
	cloned.URL.RawQuery = lxQueryEscapes.Replace(cloned.URL.RawQuery)
	if len(cloned.URL.String()) > 8192 {
		return nil, ErrLimit
	}
	return s.base.RoundTrip(cloned)
}

var lxQueryEscapes = strings.NewReplacer(
	" ", "%20", "\"", "%22", "'", "%27", "<", "%3C", ">", "%3E", "`", "%60",
	"{", "%7B", "}", "%7D", "|", "%7C", "\\", "%5C", "^", "%5E",
)

func pinnedDial(dial dialFunc) dialFunc {
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		pin, ok := ctx.Value(pinKey{}).(pinnedTarget)
		host, port, err := net.SplitHostPort(address)
		if !ok || err != nil || strings.ToLower(strings.TrimSuffix(host, ".")) != pin.host || port != pin.port {
			return nil, errUnsafeURL
		}
		// 一个主机的全部连接尝试共享预算，而不是每个 DNS 地址各获 10 秒。
		ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		for _, ip := range pin.ips {
			if !IsPublicIP(ip) {
				return nil, errUnsafeURL
			}
			conn, err := dial(ctx, "tcp", net.JoinHostPort(ip.String(), pin.port))
			if err == nil {
				return &idleConn{Conn: conn}, nil
			}
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
		}
		return nil, errNetwork
	}
}

type idleConn struct{ net.Conn }

func (c *idleConn) Read(p []byte) (int, error) {
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	return c.Conn.Read(p)
}
func (c *idleConn) Write(p []byte) (int, error) {
	_ = c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return c.Conn.Write(p)
}

// 测试替身只通过未导出的构造器传入，生产 API 不提供私网开关。
func makeClient(hosts map[string]bool, lookup lookupFunc, base http.RoundTripper, keepAlive bool) (*http.Client, func()) {
	if lookup == nil {
		lookup = net.DefaultResolver.LookupNetIP
	}
	closeIdle := func() {}
	if base == nil {
		d := &net.Dialer{Timeout: 3 * time.Second, KeepAlive: 5 * time.Second}
		idleTimeout := 5 * time.Second
		if keepAlive {
			idleTimeout = 30 * time.Second
		}
		tr := &http.Transport{
			Proxy: nil, DisableKeepAlives: !keepAlive, DialContext: pinnedDial(d.DialContext), TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
			TLSHandshakeTimeout: 3 * time.Second, ResponseHeaderTimeout: 3 * time.Second,
			ExpectContinueTimeout: time.Second, IdleConnTimeout: idleTimeout,
			MaxResponseHeaderBytes: 64 << 10, MaxIdleConns: 6, MaxIdleConnsPerHost: 3, MaxConnsPerHost: 3,
			DisableCompression: true, ForceAttemptHTTP2: false,
		}
		base = tr
		closeIdle = tr.CloseIdleConnections
	}
	guard := &safeTransport{httpHosts: hosts, base: base, lookup: lookup, interfaceAddrs: net.InterfaceAddrs, metadataOnly: true}
	client := &http.Client{Transport: guard, Timeout: 5 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > 3 {
				return errRedirect
			}
			// 目录客户端固定主机白名单：任一跳离开名单即拒绝，不把数据带到任意公网主机。
			host := strings.ToLower(req.URL.Hostname())
			if len(guard.redirectHosts) > 0 || len(guard.redirectHostSuffixes) > 0 {
				allowed := guard.redirectHosts[host]
				if !allowed {
					for suffix := range guard.redirectHostSuffixes {
						if host == suffix || strings.HasSuffix(host, "."+suffix) {
							allowed = true
							break
						}
					}
				}
				if !allowed {
					return errRedirect
				}
			}
			if err := guard.validateURL(req.URL); err != nil {
				return err
			}
			// 307/308 可重放原始请求体。仅全程同 origin 可保留；跨主机、
			// 跨协议（尤其 HTTPS 降级 HTTP）直接拒绝，不能仅清头后泄漏表单密钥。
			if req.Body != nil && req.Body != http.NoBody {
				for _, previous := range via {
					if !sameOrigin(previous.URL, req.URL) {
						return errUnsafeURL
					}
				}
			}
			// 不把任何脚本自定义凭据头带到下一跳。
			req.Header = http.Header{"Accept-Encoding": []string{"identity"}, "Accept": []string{"*/*"}, "User-Agent": []string{"Melora-LX-Metadata/1"}}
			return nil // DNS 检查仍由每次 RoundTrip 执行。
		},
	}
	return client, closeIdle
}

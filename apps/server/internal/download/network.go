package download

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"time"

	"melora/internal/netguard"
)

var (
	errUnsafeURL   = errors.New("下载地址不符合公网地址安全策略")
	errDNS         = errors.New("下载来源 DNS 验证失败")
	errNetwork     = errors.New("无法安全连接下载来源")
	errRateLimited = errors.New("音源请求频率受限，请稍后手动重试")
	errRedirect    = errors.New("下载重定向次数超过限制")
)

func validateURL(u *url.URL) error {
	if err := netguard.ValidateDownloadURLSyntax(u); err != nil {
		return errUnsafeURL
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
	base           http.RoundTripper
	lookup         lookupFunc
	interfaceAddrs func() ([]net.Addr, error) // 仅包内测试可替换；生产固定为 net.InterfaceAddrs。
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

func (s *safeTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if err := validateURL(req.URL); err != nil {
		return nil, err
	}
	host := strings.ToLower(strings.TrimSuffix(req.URL.Hostname(), "."))
	port := req.URL.Port()
	if port == "" {
		port = "443"
		if req.URL.Scheme == "http" {
			port = "80"
		}
	}
	var ips []netip.Addr
	if ip, err := netip.ParseAddr(host); err == nil {
		ips = []netip.Addr{ip}
	} else {
		ctx, cancel := context.WithTimeout(req.Context(), 5*time.Second)
		var err error
		ips, err = s.lookup(ctx, "ip", host)
		cancel()
		if err != nil {
			return nil, errDNS
		}
	}
	if len(ips) == 0 || len(ips) > 64 {
		return nil, errDNS
	}
	local, err := localInterfaceIPs(s.interfaceAddrs)
	if err != nil {
		return nil, err
	}
	// 必须在连接任意地址之前验证全部 A/AAAA；私网或本机公网地址任一命中
	// 都拒绝整个请求。字面量 IP 和每一跳重定向也经过同一检查。
	for _, ip := range ips {
		if !netguard.IsPublicIP(ip) {
			return nil, errUnsafeURL
		}
		if _, isLocal := local[ip.Unmap()]; isLocal {
			return nil, errUnsafeURL
		}
	}
	pin := pinnedTarget{host: host, port: port, ips: append([]netip.Addr(nil), ips...)}
	return s.base.RoundTrip(req.Clone(context.WithValue(req.Context(), pinKey{}, pin)))
}

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
			if !netguard.IsPublicIP(ip) {
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
	_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
	return c.Conn.Read(p)
}
func (c *idleConn) Write(p []byte) (int, error) {
	_ = c.SetWriteDeadline(time.Now().Add(30 * time.Second))
	return c.Conn.Write(p)
}

// 测试替身只通过未导出的构造器传入，生产 API 不提供私网开关。
func makeClient(lookup lookupFunc, base http.RoundTripper) (*http.Client, func()) {
	if lookup == nil {
		lookup = net.DefaultResolver.LookupNetIP
	}
	closeIdle := func() {}
	if base == nil {
		d := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
		tr := &http.Transport{
			Proxy: nil, DialContext: pinnedDial(d.DialContext), TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
			TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 15 * time.Second,
			ExpectContinueTimeout: time.Second, IdleConnTimeout: 30 * time.Second,
			MaxResponseHeaderBytes: 64 << 10, MaxIdleConns: 6, MaxIdleConnsPerHost: 3, MaxConnsPerHost: 3,
			DisableCompression: true, ForceAttemptHTTP2: false,
		}
		base = tr
		closeIdle = tr.CloseIdleConnections
	}
	client := &http.Client{Transport: &safeTransport{base: base, lookup: lookup, interfaceAddrs: net.InterfaceAddrs}, Timeout: 10 * time.Minute,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > 5 {
				return errRedirect
			}
			if err := validateURL(req.URL); err != nil {
				return err
			}
			req.Header.Del("Authorization")
			req.Header.Del("Cookie")
			req.Header.Del("Referer")
			return nil // DNS 检查仍由每次 RoundTrip 执行。
		},
	}
	return client, closeIdle
}

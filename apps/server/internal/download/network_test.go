package download

import (
	"context"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/netguard"
)

func TestPublicIP(t *testing.T) {
	blocked := []string{"", "0.0.0.0", "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.254", "127.0.0.1", "168.63.129.16", "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.9", "192.0.2.1", "192.31.196.1", "192.52.193.1", "192.88.99.1", "192.168.1.1", "192.175.48.1", "198.18.0.1", "198.19.255.255", "198.51.100.3", "203.0.113.1", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "::", "::1", "::ffff:127.0.0.1", "::ffff:8.8.8.8", "::8.8.8.8", "64:ff9b::808:808", "64:ff9b:1::1", "100::1", "2001::1", "2001:2::1", "2001:20::1", "2001:db8::1", "2002:0808:0808::1", "2620:4f:8000::1", "3ffe::1", "3fff::1", "4000::1", "5f00::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "2606:4700:4700::1111%eth0"}
	for _, s := range blocked {
		t.Run(s, func(t *testing.T) {
			ip, _ := netip.ParseAddr(s)
			if netguard.IsPublicIP(ip) {
				t.Fatalf("accepted %s", s)
			}
		})
	}
	for _, s := range []string{"1.1.1.1", "8.8.8.8", "93.184.215.14", "172.32.0.1", "192.1.1.1", "198.20.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888", "2a00:1450:4001::1"} {
		if !netguard.IsPublicIP(netip.MustParseAddr(s)) {
			t.Errorf("blocked public %s", s)
		}
	}
}
func TestURLPolicy(t *testing.T) {
	for _, raw := range []string{"file:///etc/passwd", "ftp://example.com", "https://user:secret@example.com/a", "https://localhost/a", "https://x.local/a", "https://x.internal/a", "https://x.home.arpa/a", "https://127.0.0.1/a", "http://168.63.129.16/a", "https://192.0.2.1/a", "http://[2001:db8::1]/a", "https://[2620:4f:8000::1]/a", "https://[::ffff:8.8.8.8]/a", "https://[fe80::1%25eth0]/a", "https://media.example.com:0/a", "https://media.example.com:65536/a", "https://-bad.example/a", "https://example..com/a", "https://example.com/a#token", "https://2130706433/a", "https:opaque", "https://example.com/" + strings.Repeat("x", 8192)} {
		u, err := url.Parse(raw)
		if err == nil && !errors.Is(validateURL(u), errUnsafeURL) {
			t.Errorf("accepted %q", raw)
		}
	}
	for _, raw := range []string{"http://example.com/audio", "https://example.com/path?token=secret", "https://example.com.:8443/path", "https://8.8.8.8/a", "https://[2606:4700:4700::1111]/a"} {
		u, err := url.Parse(raw)
		if err != nil || validateURL(u) != nil {
			t.Errorf("blocked %s", raw)
		}
	}
}
func TestDNSAllAnswersValidatedBeforeTransport(t *testing.T) {
	for _, ips := range [][]netip.Addr{
		nil, {netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("10.0.0.1")}, {netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("fd00::1")}, {netip.MustParseAddr("::ffff:8.8.8.8")},
	} {
		t.Run("answers", func(t *testing.T) {
			var called atomic.Int32
			client, closeIdle := makeClient(func(context.Context, string, string) ([]netip.Addr, error) { return ips, nil }, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				called.Add(1)
				return response(r, 200, audio(1024)), nil
			}))
			defer closeIdle()
			_, err := client.Get("https://media.example.com/audio?token=SECRET")
			if err == nil || called.Load() != 0 {
				t.Fatal("unsafe DNS reached transport")
			}
		})
	}
}
func TestPinnedDialDoesNotResolveAgain(t *testing.T) {
	var lookups atomic.Int32
	var addresses []string
	lookup := func(context.Context, string, string) ([]netip.Addr, error) {
		if lookups.Add(1) > 1 {
			return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
		}
		return []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("1.1.1.1")}, nil
	}
	dial := pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
		addresses = append(addresses, address)
		if len(addresses) == 1 {
			return nil, errors.New("first unavailable")
		}
		left, right := net.Pipe()
		right.Close()
		return left, nil
	})
	base := roundTripFunc(func(r *http.Request) (*http.Response, error) {
		conn, err := dial(r.Context(), "tcp", "media.example.com:443")
		if err != nil {
			return nil, err
		}
		conn.Close()
		return response(r, 200, audio(1024)), nil
	})
	client, closeIdle := makeClient(lookup, base)
	defer closeIdle()
	resp, err := client.Get("https://media.example.com/audio")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if lookups.Load() != 1 || strings.Join(addresses, ",") != "8.8.8.8:443,1.1.1.1:443" {
		t.Fatalf("not pinned: %d %v", lookups.Load(), addresses)
	}
	if _, err := dial(context.Background(), "tcp", "media.example.com:443"); err == nil {
		t.Fatal("unpinned dial allowed")
	}
	ctx := context.WithValue(context.Background(), pinKey{}, pinnedTarget{host: "media.example.com", port: "443", ips: []netip.Addr{netip.MustParseAddr("8.8.8.8")}})
	if _, err := dial(ctx, "tcp", "other.example.com:443"); err == nil {
		t.Fatal("mismatched host allowed")
	}
}
func TestRedirectRevalidationAndLimits(t *testing.T) {
	for _, target := range []string{"http://127.0.0.1/private", "https://127.0.0.1/private", "https://[::1]/private", "https://private.example.com/path", "https://u:secret@media.example.com/"} {
		t.Run(target, func(t *testing.T) {
			var calls atomic.Int32
			lookup := func(ctx context.Context, network, host string) ([]netip.Addr, error) {
				if host == "private.example.com" {
					return []netip.Addr{netip.MustParseAddr("10.1.1.1")}, nil
				}
				return publicLookup(ctx, network, host)
			}
			client, cleanup := makeClient(lookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls.Add(1)
				resp := response(r, 302, nil)
				resp.Header.Set("Location", target)
				return resp, nil
			}))
			defer cleanup()
			_, err := client.Get("https://media.example.com/a?token=SECRET")
			if err == nil || calls.Load() != 1 {
				t.Fatalf("unsafe redirect followed, %d %v", calls.Load(), err)
			}
		})
	}
	var calls atomic.Int32
	var dns atomic.Int32
	client, cleanup := makeClient(func(ctx context.Context, n, h string) ([]netip.Addr, error) {
		dns.Add(1)
		return publicLookup(ctx, n, h)
	}, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		if r.Header.Get("Referer") != "" {
			t.Error("signed URL leaked as referer")
		}
		res := response(r, 302, nil)
		res.Header.Set("Location", "/loop")
		return res, nil
	}))
	defer cleanup()
	_, err := client.Get("https://media.example.com/audio?token=SECRET")
	if !errors.Is(err, errRedirect) || calls.Load() != 6 || dns.Load() != 6 {
		t.Fatalf("redirect budget not enforced: %v %d %d", err, calls.Load(), dns.Load())
	}
}
func TestProductionTransportHasNoProxyOrTestBypass(t *testing.T) {
	t.Setenv("HTTPS_PROXY", "http://127.0.0.1:12345")
	client, cleanup := makeClient(nil, nil)
	defer cleanup()
	safe := client.Transport.(*safeTransport)
	tr := safe.base.(*http.Transport)
	if tr.Proxy != nil || tr.DialContext == nil || tr.TLSClientConfig.InsecureSkipVerify || !tr.DisableCompression || tr.TLSHandshakeTimeout <= 0 || tr.ResponseHeaderTimeout <= 0 || tr.MaxResponseHeaderBytes <= 0 || client.Timeout <= 0 {
		t.Fatal("unsafe transport defaults")
	}
	if _, err := client.Get("https://127.0.0.1/audio"); !errors.Is(err, errUnsafeURL) {
		t.Fatalf("production private URL accepted: %v", err)
	}
	if _, err := tr.DialContext(context.Background(), "tcp", "8.8.8.8:443"); !errors.Is(err, errUnsafeURL) {
		t.Fatal("production unpinned dial accepted")
	}
}
func TestDNSFailureAndDeadline(t *testing.T) {
	client, cleanup := makeClient(func(context.Context, string, string) ([]netip.Addr, error) {
		return nil, errors.New("dns token=SECRET")
	}, roundTripFunc(func(*http.Request) (*http.Response, error) { t.Fatal("unexpected call"); return nil, nil }))
	defer cleanup()
	_, err := client.Get("https://media.example.com/?token=SECRET")
	if !errors.Is(err, errDNS) || strings.Contains(safeError(err).Error(), "SECRET") {
		t.Fatal("DNS error not safe")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
	defer cancel()
	<-ctx.Done()
	if !errors.Is(safeError(ctx.Err()), errTimeout) {
		t.Fatal("deadline not classified")
	}
}
func TestPublicRedirectDoesNotForwardSensitiveHeaders(t *testing.T) {
	var calls int
	client, cleanup := makeClient(publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			res := response(r, 302, nil)
			res.Header.Set("Location", "https://cdn.example.com/audio")
			return res, nil
		}
		for _, h := range []string{"Referer", "Authorization", "Cookie"} {
			if r.Header.Get(h) != "" {
				t.Errorf("forwarded %s", h)
			}
		}
		return response(r, 200, audio(1024)), nil
	}))
	defer cleanup()
	req, _ := http.NewRequest("GET", "https://media.example.com/a?token=secret", nil)
	req.Header.Set("Authorization", "Bearer secret")
	req.Header.Set("Cookie", "token=secret")
	res, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	io.Copy(io.Discard, res.Body)
	if calls != 2 {
		t.Fatal(calls)
	}
}

func TestPinAllDialAttemptsShareDeadline(t *testing.T) {
	var first time.Time
	var attempts int
	dial := pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > 10*time.Second {
			t.Fatal("no bounded connection budget")
		}
		attempts++
		if attempts == 1 {
			first = deadline
		} else if deadline != first {
			t.Fatal("per-address deadline reset")
		}
		return nil, errors.New("unavailable")
	})
	ctx := context.WithValue(context.Background(), pinKey{}, pinnedTarget{host: "media.example.com", port: "443", ips: []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("8.8.8.8")}})
	if _, err := dial(ctx, "tcp", "media.example.com:443"); !errors.Is(err, errNetwork) || attempts != 2 {
		t.Fatal("bad pin fallback")
	}
}

func TestRealTLSWithPinnedTransport(t *testing.T) {
	payload := audio(8192)
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "example.com" {
			t.Errorf("lost original TLS/HTTP hostname: %q", r.Host)
		}
		if r.URL.Path == "/redirect" {
			http.Redirect(w, r, "/audio", http.StatusFound)
			return
		}
		if r.Header.Get("Referer") != "" {
			t.Error("signed URL leaked to redirected server")
		}
		w.Header().Set("Content-Type", "audio/mpeg")
		w.Write(payload)
	}))
	defer server.Close()
	client, cleanup := makeClient(publicLookup, nil)
	defer cleanup()
	tr := client.Transport.(*safeTransport).base.(*http.Transport)
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	tr.TLSClientConfig.RootCAs = roots
	var dials atomic.Int32
	// 只有本包测试把已校验的公网目标映射到测试 TLS listener；生产构造器无此入口。
	tr.DialContext = pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != "93.184.215.14:443" {
			t.Errorf("unvalidated dial address: %q", address)
		}
		dials.Add(1)
		return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
	})
	res, err := client.Get("https://example.com/redirect?token=SIGNED-SECRET")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	got, err := io.ReadAll(res.Body)
	if err != nil || string(got) != string(payload) || dials.Load() < 1 {
		t.Fatalf("real TLS transfer failed: %v", err)
	}
}

func TestDNSRebindingOnSameHostRedirectIsRejected(t *testing.T) {
	for _, rebound := range []string{"169.254.169.254", "168.63.129.16", "2001:db8::1", "2620:4f:8000::1"} {
		t.Run(rebound, func(t *testing.T) {
			var lookups, requests atomic.Int32
			lookup := func(ctx context.Context, network, host string) ([]netip.Addr, error) {
				if lookups.Add(1) > 1 {
					return []netip.Addr{netip.MustParseAddr(rebound)}, nil
				}
				return publicLookup(ctx, network, host)
			}
			client, cleanup := makeClient(lookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				requests.Add(1)
				res := response(r, 302, nil)
				res.Header.Set("Location", "/second-hop")
				return res, nil
			}))
			defer cleanup()
			_, err := client.Get("https://media.example.com/first-hop?token=SECRET")
			if !errors.Is(err, errUnsafeURL) || lookups.Load() != 2 || requests.Load() != 1 {
				t.Fatalf("rebinding reached transport: %v lookups=%d requests=%d", err, lookups.Load(), requests.Load())
			}
		})
	}
}

func TestPinnedDialRechecksSharedPublicIPPolicy(t *testing.T) {
	for _, blocked := range []string{"168.63.129.16", "192.0.2.1", "2001:db8::1", "2620:4f:8000::1"} {
		t.Run(blocked, func(t *testing.T) {
			var attempts atomic.Int32
			dial := pinnedDial(func(context.Context, string, string) (net.Conn, error) {
				attempts.Add(1)
				return nil, errors.New("unexpected dial")
			})
			ctx := context.WithValue(context.Background(), pinKey{}, pinnedTarget{
				host: "media.example.com",
				port: "443",
				ips:  []netip.Addr{netip.MustParseAddr(blocked)},
			})
			if _, err := dial(ctx, "tcp", "media.example.com:443"); !errors.Is(err, errUnsafeURL) || attempts.Load() != 0 {
				t.Fatalf("blocked target reached actual Dial: err=%v attempts=%d", err, attempts.Load())
			}
		})
	}
}

func TestTransportRejectsHostPublicInterfaceAddresses(t *testing.T) {
	ipv4 := netip.MustParseAddr("93.184.215.14")
	ipv6 := netip.MustParseAddr("2606:4700:4700::1111")
	for _, tc := range []struct {
		name, target string
		local        net.Addr
		answers      []netip.Addr
	}{
		{"dns-ipv4-mixed-answers", "https://media.example.com/audio", &net.IPNet{IP: net.ParseIP(ipv4.String()), Mask: net.CIDRMask(24, 32)}, []netip.Addr{netip.MustParseAddr("8.8.8.8"), ipv4}},
		{"dns-ipv6-mixed-answers", "https://media.example.com/audio", &net.IPNet{IP: net.ParseIP(ipv6.String()), Mask: net.CIDRMask(64, 128)}, []netip.Addr{netip.MustParseAddr("8.8.8.8"), ipv6}},
		{"literal-ipv4", "https://93.184.215.14/audio", &net.IPNet{IP: net.IP{93, 184, 215, 14}, Mask: net.CIDRMask(24, 32)}, nil},
		{"literal-ipv6", "https://[2606:4700:4700::1111]/audio", &net.IPAddr{IP: net.ParseIP(ipv6.String()), Zone: "eth0"}, nil},
		{"mapped-local-ipv4", "https://media.example.com/audio", &net.IPAddr{IP: net.ParseIP("::ffff:93.184.215.14")}, []netip.Addr{ipv4}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var requests atomic.Int32
			client, cleanup := makeClient(func(context.Context, string, string) ([]netip.Addr, error) { return tc.answers, nil }, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				requests.Add(1)
				return response(r, 200, audio(1024)), nil
			}))
			defer cleanup()
			// 未导出的注入点只在测试内赋值；不修改实际机器的网卡配置。
			client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) { return []net.Addr{tc.local}, nil }
			_, err := client.Get(tc.target + "?token=HOST-SECRET")
			if !errors.Is(err, errUnsafeURL) || requests.Load() != 0 {
				t.Fatalf("host address reached transport: requests=%d", requests.Load())
			}
			if strings.Contains(safeError(err).Error(), "SECRET") {
				t.Fatal("unsafe error disclosure")
			}
		})
	}
}

func TestInterfaceEnumerationFailureRejectsBeforeTransport(t *testing.T) {
	for _, tc := range []struct {
		name string
		read func() ([]net.Addr, error)
	}{
		{"error", func() ([]net.Addr, error) { return nil, errors.New("token=INTERFACE-SECRET") }},
		{"partial-results-with-error", func() ([]net.Addr, error) {
			return []net.Addr{&net.IPAddr{IP: net.ParseIP("127.0.0.1")}}, errors.New("token=INTERFACE-SECRET")
		}},
		{"empty", func() ([]net.Addr, error) { return nil, nil }},
		{"invalid-address", func() ([]net.Addr, error) { return []net.Addr{&net.IPNet{}}, nil }},
		{"unknown-type", func() ([]net.Addr, error) { return []net.Addr{&net.TCPAddr{IP: net.ParseIP("127.0.0.1")}}, nil }},
		{"nil-ipnet", func() ([]net.Addr, error) { return []net.Addr{(*net.IPNet)(nil)}, nil }},
		{"nil-ipaddr", func() ([]net.Addr, error) { return []net.Addr{(*net.IPAddr)(nil)}, nil }},
		{"nil-reader", nil},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var requests atomic.Int32
			client, cleanup := makeClient(publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				requests.Add(1)
				return response(r, 200, audio(1024)), nil
			}))
			defer cleanup()
			client.Transport.(*safeTransport).interfaceAddrs = tc.read
			_, err := client.Get("https://media.example.com/audio?token=URL-SECRET")
			if !errors.Is(err, errUnsafeURL) || requests.Load() != 0 {
				t.Fatal("interface enumeration failure did not fail closed")
			}
			if strings.Contains(safeError(err).Error(), "SECRET") {
				t.Fatal("interface error leaked secret")
			}
		})
	}
}

func TestHostAddressCheckDoesNotRejectPublicSubnetPeers(t *testing.T) {
	var requests atomic.Int32
	client, cleanup := makeClient(publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		return response(r, 200, audio(1024)), nil
	}))
	defer cleanup()
	client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) {
		return []net.Addr{&net.IPNet{IP: net.ParseIP("93.184.215.13"), Mask: net.CIDRMask(24, 32)}}, nil
	}
	res, err := client.Get("https://media.example.com/audio")
	if err != nil {
		t.Fatal("public subnet peer incorrectly blocked")
	}
	res.Body.Close()
	if requests.Load() != 1 {
		t.Fatal("public peer was not requested")
	}
}

func TestRedirectRefreshesHostInterfaceAddresses(t *testing.T) {
	var enumerations, requests atomic.Int32
	client, cleanup := makeClient(publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		res := response(r, 302, nil)
		res.Header.Set("Location", "/next")
		return res, nil
	}))
	defer cleanup()
	client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) {
		address := "127.0.0.1"
		if enumerations.Add(1) > 1 {
			address = "93.184.215.14"
		}
		return []net.Addr{&net.IPAddr{IP: net.ParseIP(address)}}, nil
	}
	_, err := client.Get("https://media.example.com/audio")
	if !errors.Is(err, errUnsafeURL) || requests.Load() != 1 || enumerations.Load() != 2 {
		t.Fatalf("redirect used stale host addresses: requests=%d enumerations=%d", requests.Load(), enumerations.Load())
	}
}

func TestHTTPUsesSourceSchemeAndPinnedPort(t *testing.T) {
	for _, tc := range []struct{ url, port string }{{"http://media.example.com/audio?token=SECRET", "80"}, {"http://media.example.com:8080/audio", "8080"}} {
		var connected string
		dial := pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
			connected = address
			left, right := net.Pipe()
			right.Close()
			return left, nil
		})
		client, cleanup := makeClient(publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
			if r.URL.Scheme != "http" {
				t.Error("source HTTP guessed into HTTPS")
			}
			conn, err := dial(r.Context(), "tcp", "media.example.com:"+tc.port)
			if err != nil {
				return nil, err
			}
			conn.Close()
			return response(r, 200, audio(1024)), nil
		}))
		res, err := client.Get(tc.url)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		cleanup()
		if connected != "93.184.215.14:"+tc.port {
			t.Fatal("HTTP dial was not pinned with correct port")
		}
	}
}
func TestHTTPDoesNotRelaxURLOrDNSPolicy(t *testing.T) {
	for _, raw := range []string{"http://127.0.0.1/a", "http://10.0.0.1/a", "http://[::1]/a", "http://[::ffff:8.8.8.8]/a", "http://user:secret@media.example.com/a", "http://media.example.com/a#secret", "http://media.example.com:0/a"} {
		u, err := url.Parse(raw)
		if err == nil && validateURL(u) == nil {
			t.Error("unsafe HTTP URL accepted")
		}
	}
	var requests atomic.Int32
	var lookups atomic.Int32
	client, cleanup := makeClient(func(ctx context.Context, network, host string) ([]netip.Addr, error) {
		if lookups.Add(1) > 1 {
			return []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("169.254.169.254")}, nil
		}
		return publicLookup(ctx, network, host)
	}, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		res := response(r, 302, nil)
		res.Header.Set("Location", "http://media.example.com/next")
		return res, nil
	}))
	defer cleanup()
	if _, err := client.Get("http://media.example.com/first"); !errors.Is(err, errUnsafeURL) || requests.Load() != 1 {
		t.Fatal("HTTP redirect DNS rebind reached transport")
	}
}
func TestMixedHTTPRedirectStripsSecretsAndRevalidates(t *testing.T) {
	var calls int
	client, cleanup := makeClient(publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			res := response(r, 302, nil)
			res.Header.Set("Location", "http://cdn.example.com/audio")
			return res, nil
		}
		for _, key := range []string{"Referer", "Authorization", "Cookie"} {
			if r.Header.Get(key) != "" {
				t.Error("redirect forwarded credentials")
			}
		}
		if r.URL.Scheme != "http" {
			t.Error("redirect scheme changed")
		}
		return response(r, 200, audio(1024)), nil
	}))
	defer cleanup()
	req, _ := http.NewRequest("GET", "https://media.example.com/a?token=SECRET", nil)
	req.Header.Set("Cookie", "secret=1")
	req.Header.Set("Authorization", "Bearer secret")
	res, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if calls != 2 {
		t.Fatal("mixed-scheme redirect not followed safely")
	}
}

func TestHTTPRejectsHostPublicAddress(t *testing.T) {
	var requests atomic.Int32
	client, cleanup := makeClient(publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		requests.Add(1)
		return response(r, 200, audio(1024)), nil
	}))
	defer cleanup()
	client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) { return []net.Addr{&net.IPAddr{IP: net.ParseIP("93.184.215.14")}}, nil }
	if _, err := client.Get("http://media.example.com/audio"); !errors.Is(err, errUnsafeURL) || requests.Load() != 0 {
		t.Fatal("HTTP reached host public address")
	}
}

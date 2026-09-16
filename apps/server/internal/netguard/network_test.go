package netguard

import (
	"bytes"
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
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func publicLookup(context.Context, string, string) ([]netip.Addr, error) {
	return []netip.Addr{netip.MustParseAddr("93.184.215.14")}, nil
}
func response(r *http.Request, status int, body []byte) *http.Response {
	return &http.Response{StatusCode: status, Request: r, Header: http.Header{"Content-Type": []string{"application/json"}}, Body: io.NopCloser(bytes.NewReader(body)), ContentLength: int64(len(body))}
}
func loopbackInterfaces() ([]net.Addr, error) {
	return []net.Addr{&net.IPAddr{IP: net.ParseIP("127.0.0.1")}}, nil
}

func TestPublicIPAndURLPolicy(t *testing.T) {
	for _, s := range []string{"", "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "168.63.129.16", "169.254.169.254", "172.16.0.1", "192.168.1.1", "192.0.0.9", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "::", "::1", "::ffff:8.8.8.8", "64:ff9b::808:808", "2001::1", "2001:db8::1", "2002:0808:0808::1", "2620:4f:8000::1", "3fff::1", "fc00::1", "fe80::1", "ff02::1", "2606:4700:4700::1111%eth0"} {
		ip, _ := netip.ParseAddr(s)
		if IsPublicIP(ip) {
			t.Errorf("accepted %s", s)
		}
	}
	for _, s := range []string{"8.8.8.8", "1.1.1.1", "93.184.215.14", "2606:4700:4700::1111"} {
		if !IsPublicIP(netip.MustParseAddr(s)) {
			t.Errorf("blocked %s", s)
		}
	}
	for _, raw := range []string{"http://example.com/", "file:///etc/passwd", "ftp://example.com/", "https://user:SECRET@example.com/", "https://localhost/", "https://x.local/", "https://x.internal/", "https://x.home.arpa/", "https://127.0.0.1/", "https://[::ffff:8.8.8.8]/", "https://example.com:0/", "https://example.com:65536/", "https://example..com/", "https://-bad.example/", "https://2130706433/", "https:opaque", "https://example.com/#fragment"} {
		u, err := url.Parse(raw)
		if err == nil && validateURL(u, nil) == nil {
			t.Errorf("accepted %s", raw)
		}
	}
}

func TestDownloadURLSyntaxUsesSharedPublicPolicy(t *testing.T) {
	for _, raw := range []string{
		"http://168.63.129.16/audio",
		"https://192.0.2.1/audio",
		"http://[2001:db8::1]/audio",
		"https://[2620:4f:8000::1]/audio",
		"http://user:secret@example.com/audio",
	} {
		u, err := url.Parse(raw)
		if err == nil {
			err = ValidateDownloadURLSyntax(u)
		}
		if !errors.Is(err, ErrPolicy) {
			t.Errorf("accepted unsafe download URL %q: %v", raw, err)
		}
	}
	for _, raw := range []string{
		"http://example.com/audio",
		"https://8.8.8.8/audio",
		"https://[2606:4700:4700::1111]/audio",
	} {
		u, err := url.Parse(raw)
		if err != nil || ValidateDownloadURLSyntax(u) != nil {
			t.Errorf("blocked public download URL %q", raw)
		}
	}
}

func TestDNSAllAnswersAndHostInterfacesAreChecked(t *testing.T) {
	for _, ips := range [][]netip.Addr{nil, {netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("10.0.0.1")}, {netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr("fd00::1")}, {netip.MustParseAddr("93.184.215.14")}} {
		var calls atomic.Int32
		client, closeIdle := makeClient(nil, func(context.Context, string, string) ([]netip.Addr, error) { return ips, nil }, roundTripFunc(func(r *http.Request) (*http.Response, error) { calls.Add(1); return response(r, 200, nil), nil }), false)
		defer closeIdle()
		client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) { return []net.Addr{&net.IPAddr{IP: net.ParseIP("93.184.215.14")}}, nil }
		if _, err := client.Get("https://metadata.example.com/"); err == nil || calls.Load() != 0 {
			t.Fatal("unsafe DNS reached transport")
		}
	}
	for _, read := range []func() ([]net.Addr, error){nil, func() ([]net.Addr, error) { return nil, errors.New("SECRET") }, func() ([]net.Addr, error) { return nil, nil }, func() ([]net.Addr, error) { return []net.Addr{&net.TCPAddr{}}, nil }} {
		guard := safeTransport{lookup: publicLookup, interfaceAddrs: read}
		u, _ := url.Parse("https://metadata.example.com/")
		if _, err := guard.target(context.Background(), u); err == nil {
			t.Fatal("unknown interfaces allowed")
		}
	}
	guard := safeTransport{lookup: publicLookup, interfaceAddrs: func() ([]net.Addr, error) {
		return []net.Addr{&net.IPNet{IP: net.ParseIP("93.184.215.13"), Mask: net.CIDRMask(24, 32)}}, nil
	}}
	u, _ := url.Parse("https://metadata.example.com/")
	if _, err := guard.target(context.Background(), u); err != nil {
		t.Fatal("blocked entire public subnet")
	}
}
func TestDNSPinnedDialAndRedirectRebinding(t *testing.T) {
	var lookups atomic.Int32
	var dialed string
	dial := pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
		dialed = address
		a, b := net.Pipe()
		b.Close()
		return a, nil
	})
	client, closeIdle := makeClient(nil, func(context.Context, string, string) ([]netip.Addr, error) {
		if lookups.Add(1) > 1 {
			return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
		}
		return publicLookup(context.Background(), "", "")
	}, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		conn, err := dial(r.Context(), "tcp", "metadata.example.com:443")
		if err != nil {
			return nil, err
		}
		conn.Close()
		resp := response(r, 302, nil)
		resp.Header.Set("Location", "/next")
		return resp, nil
	}), false)
	defer closeIdle()
	if _, err := client.Get("https://metadata.example.com/"); err == nil || dialed != "93.184.215.14:443" || lookups.Load() != 2 {
		t.Fatalf("DNS not pinned/revalidated: err=%v dial=%s lookups=%d", err, dialed, lookups.Load())
	}
	if _, err := dial(context.Background(), "tcp", "8.8.8.8:443"); err == nil {
		t.Fatal("unbound dial allowed")
	}
}
func TestRedirectBudgetAndSecretHeaders(t *testing.T) {
	var calls atomic.Int32
	client, closeIdle := makeClient(nil, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		n := calls.Add(1)
		if n > 1 {
			for _, key := range []string{"Authorization", "Cookie", "Referer", "X-Private-Key"} {
				if r.Header.Get(key) != "" {
					t.Errorf("forwarded %s", key)
				}
			}
		}
		resp := response(r, 302, nil)
		resp.Header.Set("Location", "https://other.example.com/loop")
		return resp, nil
	}), false)
	defer closeIdle()
	req, _ := http.NewRequest("GET", "https://metadata.example.com/?token=SECRET", nil)
	for _, key := range []string{"Authorization", "Cookie", "X-Private-Key"} {
		req.Header.Set(key, "SECRET")
	}
	if _, err := client.Do(req); !errors.Is(err, errRedirect) || calls.Load() != 4 {
		t.Fatalf("redirect budget: calls=%d err=%v", calls.Load(), err)
	}
}
func TestHTTPAllowlistIsExactAndNeverAppliesToMedia(t *testing.T) {
	for _, host := range []string{"*.example.com", "example.com:80", "https://example.com", "example.com.", "127.0.0.1", "localhost", "Example.com"} {
		if b, err := NewBroker(Options{AllowHTTPHosts: []string{host}}); err == nil {
			b.Close()
			t.Errorf("accepted invalid whitelist %s", host)
		}
	}
	b, err := newBroker(Options{AllowHTTPHosts: []string{"metadata.example.com"}}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) { return response(r, 200, []byte(`{}`)), nil }))
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if _, err = b.Do(context.Background(), Request{URL: "http://metadata.example.com/api"}); err != nil {
		t.Fatal("exact HTTP host rejected")
	}
	for _, raw := range []string{"http://sub.metadata.example.com/", "http://metadata.example.com./", "http://127.0.0.1/"} {
		if _, err = b.Do(context.Background(), Request{URL: raw}); err == nil {
			t.Fatalf("HTTP bypass %s", raw)
		}
	}
	for _, raw := range []string{"http://metadata.example.com/", "https://127.0.0.1/", "https://[::1]/", "https://user:SECRET@8.8.8.8/"} {
		if err := ValidateURL(context.Background(), raw, Options{AllowHTTPHosts: []string{"metadata.example.com"}}); err == nil || strings.Contains(err.Error(), "SECRET") {
			t.Fatalf("unsafe media URL accepted: %v", err)
		}
	}
	if err := ValidateURL(context.Background(), "https://8.8.8.8/authorized.mp3", Options{}); err != nil {
		t.Fatalf("public literal validation should not connect: %v", err)
	}
}
func TestBrokerMediaAndSizeLimits(t *testing.T) {
	for _, test := range []struct {
		name, kind string
		body       []byte
		length     int64
		want       error
	}{
		{"audio", "audio/mpeg", []byte("not audio"), 9, ErrMedia}, {"video", "video/mp4", nil, 0, ErrMedia},
		{"sniff-id3", "text/plain", []byte("ID3\x04\x00\x00\x00\x00\x00\x00"), 10, ErrMedia},
		{"sniff-flac", "application/json", []byte("fLaC...."), 8, ErrMedia},
		{"playlist", "text/plain", []byte("#EXTM3U\nhttps://x/"), -1, ErrMedia},
		{"large-header", "application/json", nil, MaxResponseBytes + 1, ErrLimit},
		{"large-chunked", "application/json", bytes.Repeat([]byte("x"), MaxResponseBytes+1), -1, ErrLimit},
	} {
		t.Run(test.name, func(t *testing.T) {
			b, err := newBroker(Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, test.body)
				resp.ContentLength = test.length
				resp.Header.Set("Content-Type", test.kind)
				return resp, nil
			}))
			if err != nil {
				t.Fatal(err)
			}
			defer b.Close()
			if _, err = b.Do(context.Background(), Request{URL: "https://metadata.example.com/"}); !errors.Is(err, test.want) {
				t.Fatalf("guard: %v", err)
			}
		})
	}
}
func TestBrokerRequestCountCancellationAndSafeErrors(t *testing.T) {
	var calls atomic.Int32
	b, _ := newBroker(Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		return response(r, 200, []byte(`{}`)), nil
	}))
	defer b.Close()
	var wg sync.WaitGroup
	var limited atomic.Int32
	for range 30 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := b.Do(context.Background(), Request{URL: "https://metadata.example.com/"})
			if errors.Is(err, ErrLimit) {
				limited.Add(1)
			} else if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if calls.Load() != MaxRequests || limited.Load() != 30-MaxRequests {
		t.Fatalf("unbounded requests: %d limited=%d", calls.Load(), limited.Load())
	}
	b2, _ := newBroker(Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		<-r.Context().Done()
		return nil, errors.New("https://private?token=SECRET")
	}))
	defer b2.Close()
	started := time.Now()
	_, err := b2.Do(context.Background(), Request{URL: "https://metadata.example.com/?token=SECRET", Timeout: 10})
	if err != ErrRequest || strings.Contains(err.Error(), "SECRET") || time.Since(started) > time.Second {
		t.Fatalf("request timeout: %v", err)
	}
}
func TestProductionClientNoProxyNoReuseAndRealPinnedTLS(t *testing.T) {
	t.Setenv("HTTPS_PROXY", "http://127.0.0.1:1")
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "example.com" {
			t.Error("lost original host")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer server.Close()
	b, _ := newBroker(Options{}, publicLookup, nil)
	defer b.Close()
	tr := b.client.Transport.(*safeTransport).base.(*http.Transport)
	if tr.Proxy != nil || !tr.DisableKeepAlives || !tr.DisableCompression || tr.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("unsafe production transport")
	}
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	tr.TLSClientConfig.RootCAs = roots
	var dialed atomic.Int32
	tr.DialContext = pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != "93.184.215.14:443" {
			t.Error("not DNS pinned")
		}
		dialed.Add(1)
		var d net.Dialer
		return d.DialContext(ctx, network, server.Listener.Addr().String())
	})
	result, err := b.Do(context.Background(), Request{URL: "https://example.com/"})
	if err != nil || string(result.Body) != `{"ok":true}` || dialed.Load() != 1 {
		t.Fatalf("real TLS: %+v %v", result, err)
	}
}

func TestBrokerBudgetCountsRedirectRequests(t *testing.T) {
	var calls atomic.Int32
	b, err := newBroker(Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		if r.URL.Path == "/start" {
			resp := response(r, 302, nil)
			resp.Header.Set("Location", "/finish")
			return resp, nil
		}
		return response(r, 200, []byte(`{}`)), nil
	}))
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	for range MaxRequests / 2 {
		if _, err := b.Do(context.Background(), Request{URL: "https://metadata.example.com/start"}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := b.Do(context.Background(), Request{URL: "https://metadata.example.com/start"}); !errors.Is(err, ErrLimit) || calls.Load() != MaxRequests {
		t.Fatalf("redirect escaped request budget: calls=%d err=%v", calls.Load(), err)
	}
}
func TestRedirectRefreshesHostInterfacesAndPrivateHTTPIsStillDenied(t *testing.T) {
	var reads, calls atomic.Int32
	b, _ := newBroker(Options{AllowHTTPHosts: []string{"metadata.example.com"}}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		resp := response(r, 302, nil)
		resp.Header.Set("Location", "/next")
		return resp, nil
	}))
	defer b.Close()
	b.client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) {
		address := "127.0.0.1"
		if reads.Add(1) > 1 {
			address = "93.184.215.14"
		}
		return []net.Addr{&net.IPAddr{IP: net.ParseIP(address)}}, nil
	}
	if _, err := b.Do(context.Background(), Request{URL: "http://metadata.example.com/start"}); err == nil || calls.Load() != 1 || reads.Load() != 2 {
		t.Fatal("redirect used stale interface snapshot")
	}
	b2, _ := newBroker(Options{AllowHTTPHosts: []string{"metadata.example.com"}}, func(context.Context, string, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("10.0.0.1")}, nil
	}, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		t.Error("private HTTP DNS reached transport")
		return response(r, 200, nil), nil
	}))
	defer b2.Close()
	if _, err := b2.Do(context.Background(), Request{URL: "http://metadata.example.com/"}); err == nil {
		t.Fatal("whitelist bypassed DNS safety")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := ValidateURL(ctx, "https://8.8.8.8/", Options{}); err == nil {
		t.Fatal("media validation ignored cancellation")
	}
}

package netguard

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const v18SignedPath = "/audio/a%2Fb/%41.mp3?b=2&a=%2f&a=%2F&sig=FIXTURE_ONLY+%2B&empty=&bare"

type v18UnreadBody struct {
	t      *testing.T
	closed bool
}

func (b *v18UnreadBody) Read([]byte) (int, error) {
	b.t.Error("HTTPS HEAD read representation body")
	return 0, io.EOF
}
func (b *v18UnreadBody) Close() error { b.closed = true; return nil }

func v18Head(r *http.Request, status int, body io.ReadCloser) *http.Response {
	return &http.Response{StatusCode: status, Request: r, Header: http.Header{"Content-Type": {"audio/mpeg"}}, ContentLength: 7, Body: body}
}
func v18Probe(base http.RoundTripper) playbackHTTPSProbe {
	return playbackHTTPSProbe{lookup: publicLookup, base: base, interfaces: loopbackInterfaces, slots: make(chan struct{}, 4)}
}

func TestV18HTTPSProbePreservesPathQueryAndDefaultPort(t *testing.T) {
	for _, tc := range []struct{ input, want string }{
		{"http://example.com" + v18SignedPath, "https://example.com" + v18SignedPath},
		{"http://EXAMPLE.com:80" + v18SignedPath, "https://EXAMPLE.com" + v18SignedPath},
		{"http://example.com/empty?", "https://example.com/empty?"},
		{"http://example.com", "https://example.com"},
	} {
		t.Run(tc.input, func(t *testing.T) {
			calls := 0
			body := &v18UnreadBody{t: t}
			p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				if r.Method != "HEAD" || r.URL.String() != tc.want || r.Body != nil {
					t.Fatalf("signature/method changed: %s %s", r.Method, r.URL.String())
				}
				return v18Head(r, 200, body), nil
			}))
			out, err := p.verify(t.Context(), tc.input)
			if err != nil || out != tc.want || calls != 1 || !body.closed {
				t.Fatalf("out=%q err=%v calls=%d closed=%v", out, err, calls, body.closed)
			}
		})
	}
}

func TestV18HTTPSProbeRejectsNonstandardTargetsBeforeNetwork(t *testing.T) {
	for _, raw := range []string{
		"https://example.com/a", "http://example.com:8080/a", "http://example.com:443/a", "http://example.com:/a", "http://example.com:080/a", "http://example.com:65536/a",
		"http://8.8.8.8/a", "http://[2606:4700:4700::1111]/a", "http://2130706433/a", "http://127.1/a", "http://0x7f.0.0.1/a", "http://example.0x7f/a",
		"http://example.com./a", "http://localhost/a", "http://a.local/a", "http://a.internal/a", "http://a.home.arpa/a",
		"http://user:FIXTURE@example.com/a", "http://example.com/a#", "http://example.com/a#fragment", "http://example.com/a?sig=%", "http://example.com/a?sig=%zz",
		"http://example.com/a?sig=a b", "http://example.com/a?sig='value'", "http://example.com/a?sig=\"value\"", "http://example.com/a\\b", "http://example.com/中文", "http://example.com/a?x=\x00",
		"http://example.com/" + strings.Repeat("a", 8192),
	} {
		t.Run(raw[:min(len(raw), 70)], func(t *testing.T) {
			p := v18Probe(roundTripFunc(func(*http.Request) (*http.Response, error) {
				t.Fatal("unsafe target reached transport")
				return nil, nil
			}))
			p.lookup = func(context.Context, string, string) ([]netip.Addr, error) {
				t.Fatal("unsafe target reached DNS")
				return nil, nil
			}
			if out, err := p.verify(t.Context(), raw); err == nil || out != "" {
				t.Fatal("unsupported input became verified HTTPS")
			}
		})
	}
}

func TestV18HTTPSProbeChecksAllDNSAnswersAndOwnInterfaces(t *testing.T) {
	for _, bad := range []string{"127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.169.254", "168.63.129.16", "192.168.1.1", "198.18.0.1", "203.0.113.1", "fc00::1", "::ffff:8.8.8.8", "93.184.215.14"} {
		t.Run(bad, func(t *testing.T) {
			p := v18Probe(roundTripFunc(func(*http.Request) (*http.Response, error) { t.Fatal("unsafe DNS reached transport"); return nil, nil }))
			p.lookup = func(context.Context, string, string) ([]netip.Addr, error) {
				return []netip.Addr{netip.MustParseAddr("8.8.8.8"), netip.MustParseAddr(bad)}, nil
			}
			p.interfaces = func() ([]net.Addr, error) { return []net.Addr{&net.IPAddr{IP: net.ParseIP("93.184.215.14")}}, nil }
			if _, err := p.verify(t.Context(), "http://example.com/audio.mp3"); err == nil {
				t.Fatal("unsafe DNS/self IP accepted")
			}
		})
	}
}

func TestV18HTTPSProbeRevalidatesDNSAndInterfacesEveryRedirect(t *testing.T) {
	for _, rebind := range []bool{false, true} {
		t.Run(map[bool]string{false: "new-self-interface", true: "dns-rebind"}[rebind], func(t *testing.T) {
			lookups, interfaces, calls := 0, 0, 0
			p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				resp := v18Head(r, 302, &v18UnreadBody{t: t})
				resp.Header.Set("Location", "https://example.com/redirect")
				return resp, nil
			}))
			p.lookup = func(context.Context, string, string) ([]netip.Addr, error) {
				lookups++
				ip := "93.184.215.14"
				if rebind && lookups > 1 {
					ip = "10.0.0.1"
				}
				return []netip.Addr{netip.MustParseAddr(ip)}, nil
			}
			p.interfaces = func() ([]net.Addr, error) {
				interfaces++
				if !rebind && interfaces > 1 {
					return []net.Addr{&net.IPAddr{IP: net.ParseIP("93.184.215.14")}}, nil
				}
				return loopbackInterfaces()
			}
			if _, err := p.verify(t.Context(), "http://example.com/original"); err == nil || calls != 1 || lookups != 2 || interfaces != 2 {
				t.Fatalf("revalidation: calls=%d lookups=%d interfaces=%d err=%v", calls, lookups, interfaces, err)
			}
		})
	}
}

func TestV18HTTPSProbeRedirectsKeepNoHeadersOrOldQuery(t *testing.T) {
	calls := 0
	bodies := []*v18UnreadBody{}
	p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		for _, name := range []string{"Referer", "Authorization", "Cookie", "Origin", "X-Private-Key", "Range", "Proxy-Authorization"} {
			if r.Header.Get(name) != "" {
				t.Errorf("sent %s", name)
			}
		}
		if r.Method != "HEAD" || r.Header.Get("Accept-Encoding") != "identity" || r.URL.Scheme != "https" {
			t.Fatal("non-HEAD/HTTPS request")
		}
		body := &v18UnreadBody{t: t}
		bodies = append(bodies, body)
		resp := v18Head(r, 302, body)
		resp.Header.Set("Set-Cookie", "secret=FIXTURE_ONLY")
		switch calls {
		case 1:
			resp.Header.Set("Location", "https://other.example/final%2Fpath?own=%2F&own=%2f")
		case 2:
			if r.URL.RawQuery != "own=%2F&own=%2f" || r.URL.EscapedPath() != "/final%2Fpath" {
				t.Fatal("old signature leaked or new signature rewritten")
			}
			resp.Header.Set("Location", "?end=%2B&bare")
		case 3:
			if r.URL.RawQuery != "end=%2B&bare" {
				t.Fatal("query merged")
			}
			resp.StatusCode = 200
		}
		return resp, nil
	}))
	out, err := p.verify(t.Context(), "http://example.com"+v18SignedPath)
	if err != nil || calls != 3 || out != "https://other.example/final%2Fpath?end=%2B&bare" {
		t.Fatalf("out=%q calls=%d err=%v", out, calls, err)
	}
	for _, b := range bodies {
		if !b.closed {
			t.Error("redirect body leaked")
		}
	}
}

func TestV18HTTPSProbeRejectsUnsafeOrUnboundedRedirects(t *testing.T) {
	for _, location := range []string{"http://other.example/a", "https://other.example:8080/a", "https://127.0.0.1/a", "https://8.8.8.8/a", "https://other.example:/a", "https://other.example:0443/a", "https://u:FIXTURE@other.example/a", "https://other.example/a#", "https://other.example/a?sig='%2F'", "https://other.example/a?sig=%xx", "https://example.com/start", "", strings.Repeat("x", 8193)} {
		t.Run(location[:min(len(location), 65)], func(t *testing.T) {
			calls := 0
			p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				resp := v18Head(r, 302, &v18UnreadBody{t: t})
				resp.Header.Set("Location", location)
				return resp, nil
			}))
			if out, err := p.verify(t.Context(), "http://example.com/start"); err == nil || out != "" || calls != 1 {
				t.Fatalf("bad redirect: calls=%d err=%v", calls, err)
			}
		})
	}
	t.Run("fourth-request-forbidden", func(t *testing.T) {
		calls := 0
		p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
			calls++
			resp := v18Head(r, 302, &v18UnreadBody{t: t})
			resp.Header.Set("Location", r.URL.String()+"x")
			return resp, nil
		}))
		if _, err := p.verify(t.Context(), "http://example.com/start"); err == nil || calls != 3 {
			t.Fatalf("redirect budget=%d err=%v", calls, err)
		}
	})
	t.Run("duplicate-location", func(t *testing.T) {
		p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
			resp := v18Head(r, 302, &v18UnreadBody{t: t})
			resp.Header["Location"] = []string{"/one", "/two"}
			return resp, nil
		}))
		if _, err := p.verify(t.Context(), "http://example.com/start"); err == nil {
			t.Fatal("ambiguous Location accepted")
		}
	})
}

func TestV18HTTPSProbeOnlyConfirmsUnambiguousAudioHEAD(t *testing.T) {
	cases := []struct {
		name string
		edit func(*http.Response)
		ok   bool
	}{
		{"large-audio", func(r *http.Response) { r.ContentLength = 1 << 30; r.Header.Set("Content-Length", "1073741824") }, true},
		{"unknown-length", func(r *http.Response) { r.ContentLength = -1 }, true},
		{"flac", func(r *http.Response) { r.Header.Set("Content-Type", "audio/flac") }, true},
		{"ogg", func(r *http.Response) { r.Header.Set("Content-Type", "application/ogg") }, true},
		{"identity", func(r *http.Response) { r.Header.Set("Content-Encoding", "identity") }, true},
		{"html", func(r *http.Response) { r.Header.Set("Content-Type", "text/html") }, false},
		{"json", func(r *http.Response) { r.Header.Set("Content-Type", "application/json") }, false},
		{"octet-stream", func(r *http.Response) { r.Header.Set("Content-Type", "application/octet-stream") }, false},
		{"playlist", func(r *http.Response) { r.Header.Set("Content-Type", "application/vnd.apple.mpegurl") }, false},
		{"missing-type", func(r *http.Response) { r.Header.Del("Content-Type") }, false},
		{"duplicate-type", func(r *http.Response) { r.Header.Add("Content-Type", "audio/flac") }, false},
		{"gzip", func(r *http.Response) { r.Header.Set("Content-Encoding", "gzip") }, false},
		{"encoding-chain", func(r *http.Response) { r.Header["Content-Encoding"] = []string{"identity", "identity"} }, false},
		{"zero", func(r *http.Response) { r.ContentLength = 0 }, false},
		{"bad-length", func(r *http.Response) { r.Header.Set("Content-Length", "+1073741824") }, false},
		{"conflicting-length", func(r *http.Response) { r.Header.Set("Content-Length", "1234") }, false},
		{"duplicate-length", func(r *http.Response) { r.Header["Content-Length"] = []string{"1073741824", "1073741824"} }, false},
	}
	for _, code := range []int{204, 206, 304, 401, 403, 404, 405, 429, 500, 501} {
		cases = append(cases, struct {
			name string
			edit func(*http.Response)
			ok   bool
		}{http.StatusText(code), func(r *http.Response) { r.StatusCode = code }, false})
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			body := &v18UnreadBody{t: t}
			calls := 0
			p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				resp := v18Head(r, 200, body)
				tc.edit(resp)
				return resp, nil
			}))
			out, err := p.verify(t.Context(), "http://example.com/claimed.flac?sig=FIXTURE_ONLY")
			if (err == nil) != tc.ok || !body.closed || calls != 1 || !tc.ok && out != "" {
				t.Fatalf("ok=%v out=%q err=%v closed=%v calls=%d", tc.ok, out, err, body.closed, calls)
			}
		})
	}
}

func TestV18HTTPSProbeBudgetIncludesDNSRedirectAndQueuedSlot(t *testing.T) {
	for _, stage := range []string{"dns", "response", "redirect", "queue"} {
		t.Run(stage, func(t *testing.T) {
			calls := 0
			p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				if stage == "redirect" && calls == 1 {
					resp := v18Head(r, 302, &v18UnreadBody{t: t})
					resp.Header.Set("Location", "/next")
					return resp, nil
				}
				deadline, ok := r.Context().Deadline()
				if !ok || time.Until(deadline) > 50*time.Millisecond {
					t.Error("request reset deadline")
				}
				<-r.Context().Done()
				return nil, r.Context().Err()
			}))
			p.budget = 30 * time.Millisecond
			if stage == "dns" {
				p.lookup = func(ctx context.Context, _, _ string) ([]netip.Addr, error) { <-ctx.Done(); return nil, ctx.Err() }
			}
			if stage == "queue" {
				for range cap(p.slots) {
					p.slots <- struct{}{}
				}
			}
			start := time.Now()
			_, err := p.verify(t.Context(), "http://example.com/start")
			if !errors.Is(err, context.DeadlineExceeded) || time.Since(start) > 500*time.Millisecond {
				t.Fatalf("budget: %v elapsed=%v", err, time.Since(start))
			}
			if (stage == "dns" || stage == "queue") && calls != 0 {
				t.Fatal("sent request after exhausted pre-request budget")
			}
		})
	}
	p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
		deadline, _ := r.Context().Deadline()
		if time.Until(deadline) > 20*time.Millisecond {
			t.Error("ignored short caller")
		}
		<-r.Context().Done()
		return nil, r.Context().Err()
	}))
	ctx, stop := context.WithTimeout(t.Context(), 10*time.Millisecond)
	defer stop()
	if _, err := p.verify(ctx, "http://example.com/start"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal(err)
	}
}

func TestV18HTTPSProbeConcurrencyCancellationAndSafeErrors(t *testing.T) {
	var active, peak atomic.Int32
	entered := make(chan struct{}, 4)
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
		n := active.Add(1)
		defer active.Add(-1)
		for old := peak.Load(); n > old; old = peak.Load() {
			if peak.CompareAndSwap(old, n) {
				break
			}
		}
		entered <- struct{}{}
		<-r.Context().Done()
		return nil, errors.New("raw private URL/signature=FIXTURE_ONLY")
	}))
	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() {
			_, err := p.verify(ctx, "http://example.com/start?sig=FIXTURE_ONLY")
			if err == nil || strings.Contains(err.Error(), "FIXTURE_ONLY") {
				t.Errorf("unsafe error=%v", err)
			}
		})
	}
	for range 4 {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("probes did not acquire slots")
		}
	}
	waiting := p
	waiting.budget = 15 * time.Millisecond
	if _, err := waiting.verify(t.Context(), "http://example.com/wait"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("fifth probe did not wait within its budget", err)
	}
	cancel()
	wg.Wait()
	if active.Load() != 0 || peak.Load() != 4 || len(p.slots) != 0 {
		t.Fatalf("leak/limit active=%d peak=%d slots=%d", active.Load(), peak.Load(), len(p.slots))
	}
	p.base = roundTripFunc(func(r *http.Request) (*http.Response, error) { return v18Head(r, 200, &v18UnreadBody{t: t}), nil })
	if _, err := p.verify(t.Context(), "http://example.com/after"); err != nil {
		t.Fatal("slot was not reusable", err)
	}
}

func TestV18HTTPSProbeProductionTransportPinnedTLSNoProxy(t *testing.T) {
	for _, mode := range []string{"valid", "unknown-ca", "wrong-host", "expired", "oversized-header"} {
		t.Run(mode, func(t *testing.T) {
			var requests, proxyCalls atomic.Int32
			proxy := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { proxyCalls.Add(1) }))
			defer proxy.Close()
			t.Setenv("HTTPS_PROXY", proxy.URL)
			t.Setenv("HTTP_PROXY", proxy.URL)
			srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if r.Method != "HEAD" || r.URL.RequestURI() != v18SignedPath || r.Host != "example.com" {
					t.Error("TLS request/signature changed")
				}
				w.Header().Set("Content-Type", "audio/mpeg")
				w.Header().Set("Content-Length", "1073741824")
				if mode == "oversized-header" {
					w.Header().Set("X-Large", strings.Repeat("x", 17<<10))
				}
				w.WriteHeader(200) // 无音频正文。
			}))
			srv.Config.ErrorLog = log.New(io.Discard, "", 0)
			srv.StartTLS()
			defer srv.Close()
			p := v18Probe(nil)
			p.configureTransport = func(tr *http.Transport) {
				if tr.Proxy != nil || tr.TLSClientConfig.InsecureSkipVerify || tr.TLSClientConfig.MinVersion != tls.VersionTLS12 || !tr.DisableKeepAlives || tr.MaxResponseHeaderBytes != 16<<10 {
					t.Fatal("production TLS/proxy/limits weakened")
				}
				tr.TLSClientConfig = tr.TLSClientConfig.Clone()
				tr.TLSClientConfig.RootCAs = x509.NewCertPool()
				if mode != "unknown-ca" {
					tr.TLSClientConfig.RootCAs.AddCert(srv.Certificate())
				}
				if mode == "expired" {
					tr.TLSClientConfig.Time = func() time.Time { return srv.Certificate().NotAfter.Add(time.Hour) }
				}
				tr.DialContext = pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
					if address != "93.184.215.14:443" {
						t.Errorf("dial was not pinned: %s", address)
						return nil, ErrPolicy
					}
					return (&net.Dialer{}).DialContext(ctx, "tcp", srv.Listener.Addr().String())
				})
			}
			host := "example.com"
			if mode == "wrong-host" {
				host = "other.example"
			}
			out, err := p.verify(t.Context(), "http://"+host+v18SignedPath)
			if mode == "valid" {
				if err != nil || out != "https://example.com"+v18SignedPath || requests.Load() != 1 {
					t.Fatalf("valid TLS control failed: %s %v", out, err)
				}
			} else if err == nil || out != "" {
				t.Fatal("bad TLS/header accepted")
			}
			if proxyCalls.Load() != 0 {
				t.Fatal("environment proxy used")
			}
			if mode != "valid" && mode != "oversized-header" && requests.Load() != 0 {
				t.Fatal("HTTP request sent despite invalid TLS")
			}
		})
	}
}

func TestV18HTTPSProbeDefaultBudgetCapsAndSanitizedTransportError(t *testing.T) {
	if cap(playbackHTTPSConcurrent) != 4 {
		t.Fatal("production global concurrency changed")
	}
	for _, budget := range []time.Duration{0, 10 * time.Second} {
		calls := 0
		p := v18Probe(roundTripFunc(func(r *http.Request) (*http.Response, error) {
			calls++
			deadline, ok := r.Context().Deadline()
			if !ok || time.Until(deadline) > 1500*time.Millisecond {
				t.Error("default/hard probe budget expanded")
			}
			return nil, errors.New("upstream secret query=FIXTURE_ONLY")
		}))
		p.budget = budget
		out, err := p.verify(t.Context(), "http://example.com/audio.mp3?sig=FIXTURE_ONLY")
		if !errors.Is(err, ErrRequest) || out != "" || calls != 1 || strings.Contains(err.Error(), "FIXTURE_ONLY") {
			t.Fatalf("error leaked/retried: %v calls=%d", err, calls)
		}
	}
}

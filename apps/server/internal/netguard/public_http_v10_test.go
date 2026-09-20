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
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// 只注入 DNS/transport，不访问真实域名、媒体或业务服务。
func v10Broker(t *testing.T, opts Options, lookup lookupFunc, transport http.RoundTripper) *Broker {
	t.Helper()
	b, err := newBroker(opts, lookup, transport)
	if err != nil {
		t.Fatal(err)
	}
	b.client.Transport.(*safeTransport).interfaceAddrs = loopbackInterfaces
	t.Cleanup(b.Close)
	return b
}

func TestV10PublicHTTPIsOptInMetadataOnly(t *testing.T) {
	for _, tc := range []struct {
		name    string
		opts    Options
		raw     string
		allowed bool
	}{
		{"default-http", Options{}, "http://metadata.example.com/api", false},
		{"default-https", Options{}, "https://metadata.example.com/api", true},
		{"public-http", Options{AllowPublicHTTP: true}, "http://metadata.example.com/api", true},
		{"public-v4", Options{AllowPublicHTTP: true}, "http://8.8.8.8:80/api", true},
		{"public-v6", Options{AllowPublicHTTP: true}, "http://[2606:4700:4700::1111]/api", true},
		{"public-https", Options{AllowPublicHTTP: true}, "https://metadata.example.com:443/api", true},
		{"legacy-exact", Options{AllowHTTPHosts: []string{"metadata.example.com"}}, "http://metadata.example.com/api", true},
		{"legacy-other", Options{AllowHTTPHosts: []string{"metadata.example.com"}}, "http://other.example.com/api", false},
		{"public-legacy-not-restrictive", Options{AllowPublicHTTP: true, AllowHTTPHosts: []string{"old.example.com"}}, "http://new.example.com/api", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls int
			b := v10Broker(t, tc.opts, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				pin, ok := r.Context().Value(pinKey{}).(pinnedTarget)
				if !ok || pin.host != r.URL.Hostname() || len(pin.ips) != 1 {
					t.Error("lost DNS pin")
				}
				return response(r, 200, []byte(`{"metadata":true}`)), nil
			}))
			got, err := b.Do(t.Context(), Request{URL: tc.raw})
			if tc.allowed {
				if err != nil || calls != 1 || string(got.Body) != `{"metadata":true}` {
					t.Fatalf("metadata compatibility: calls=%d err=%v", calls, err)
				}
			} else if err == nil || calls != 0 {
				t.Fatal("default/legacy policy widened")
			}
		})
	}
	if err := ValidateURL(t.Context(), "http://8.8.8.8/never-fetched.mp3", Options{AllowPublicHTTP: true}); err == nil {
		t.Fatal("metadata permission escaped into media policy")
	}
	if err := ValidatePlaybackURL(t.Context(), "http://8.8.8.8/never-fetched.mp3", "https"); err == nil {
		t.Fatal("mixed content allowed")
	}
	for _, host := range []string{"*.example.com", "http://example.com", "127.0.0.1", "metadata.google.internal"} {
		if b, err := NewBroker(Options{AllowPublicHTTP: true, AllowHTTPHosts: []string{host}}); err == nil {
			b.Close()
			t.Errorf("invalid legacy option accepted: %s", host)
		}
	}
}

func TestV10PublicHTTPRejectsSSRFAndArbitraryPortsEveryHop(t *testing.T) {
	targets := []string{
		"http://127.0.0.1/", "http://10.0.0.1/", "http://172.16.0.1/", "http://192.168.0.1/", "http://0.0.0.0/",
		"http://169.254.169.254/latest/meta-data/", "http://169.254.170.2/", "http://100.100.100.200/", "http://168.63.129.16/",
		"http://[::1]/", "http://[fd00:ec2::254]/", "http://[::ffff:8.8.8.8]/", "http://[64:ff9b::808:808]/",
		"http://metadata.google.internal/", "http://localhost/", "http://nas.local/", "http://x.home.arpa/",
		"http://user:SECRET@metadata.example.com/", "http://metadata.example.com/#SECRET", "file:///etc/passwd",
		"http://metadata.example.com:3780/", "http://metadata.example.com:5174/", "http://metadata.example.com:22/",
		"http://metadata.example.com:443/", "https://metadata.example.com:80/", "https://metadata.example.com:8443/",
		"http://metadata.example.com:080/", "http://metadata.example.com:/", "http://metadata.example.com:0/",
	}
	for _, raw := range targets {
		for _, redirect := range []bool{false, true} {
			t.Run(raw+map[bool]string{true: "/redirect", false: "/direct"}[redirect], func(t *testing.T) {
				calls := 0
				b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
					calls++
					resp := response(r, 302, nil)
					resp.Header.Set("Location", raw)
					return resp, nil
				}))
				start := raw
				wantCalls := 0
				if redirect {
					start = "http://metadata.example.com/start"
					wantCalls = 1
				}
				_, err := b.Do(t.Context(), Request{URL: start})
				if err == nil || calls != wantCalls {
					t.Fatalf("SSRF reached transport: calls=%d want=%d err=%v", calls, wantCalls, err)
				}
				if strings.Contains(err.Error(), "SECRET") {
					t.Fatal("unsafe error leaked credentials")
				}
			})
		}
	}
}

func TestV10PublicHTTPRevalidatesDNSAndInterfacesAndPinsDial(t *testing.T) {
	for _, mode := range []string{"private-answer", "mixed-aaaa", "cloud-metadata", "own-public-ip", "interfaces-unavailable"} {
		t.Run(mode, func(t *testing.T) {
			lookups, calls, reads := 0, 0, 0
			b := v10Broker(t, Options{AllowPublicHTTP: true}, func(context.Context, string, string) ([]netip.Addr, error) {
				lookups++
				ips, _ := publicLookup(t.Context(), "", "")
				if lookups > 1 {
					switch mode {
					case "private-answer":
						ips = []netip.Addr{netip.MustParseAddr("127.0.0.1")}
					case "mixed-aaaa":
						ips = append(ips, netip.MustParseAddr("fd00::1"))
					case "cloud-metadata":
						ips = append(ips, netip.MustParseAddr("168.63.129.16"))
					}
				}
				return ips, nil
			}, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				dial := pinnedDial(func(_ context.Context, network, address string) (net.Conn, error) {
					if network != "tcp" || address != "93.184.215.14:80" {
						t.Error("dial was not pinned")
					}
					a, c := net.Pipe()
					c.Close()
					return a, nil
				})
				conn, err := dial(r.Context(), "tcp", "metadata.example.com:80")
				if err != nil {
					return nil, err
				}
				conn.Close()
				resp := response(r, 302, nil)
				resp.Header.Set("Location", "/next")
				return resp, nil
			}))
			b.client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) {
				reads++
				if reads > 1 {
					switch mode {
					case "own-public-ip":
						return []net.Addr{&net.IPAddr{IP: net.ParseIP("93.184.215.14")}}, nil
					case "interfaces-unavailable":
						return nil, errors.New("SECRET interface failure")
					}
				}
				return loopbackInterfaces()
			}
			_, err := b.Do(t.Context(), Request{URL: "http://metadata.example.com/start"})
			if err == nil || calls != 1 || lookups != 2 {
				t.Fatalf("HTTP hop not revalidated: calls=%d lookups=%d err=%v", calls, lookups, err)
			}
		})
	}
}

func TestV10PublicHTTPRedirectBodyAndCredentials(t *testing.T) {
	for _, status := range []int{301, 302, 303, 307, 308} {
		for _, tc := range []struct {
			name, start, next string
			allowed           bool
		}{
			{"same-origin-http", "http://metadata.example.com/start", "http://metadata.example.com:80/next", true},
			{"same-origin-https", "https://metadata.example.com/start", "https://metadata.example.com/next", true},
			{"cross-origin", "http://metadata.example.com/start", "http://other.example.com/next", false},
			{"downgrade", "https://metadata.example.com/start", "http://metadata.example.com/next", false},
			{"upgrade", "http://metadata.example.com/start", "https://metadata.example.com/next", false},
		} {
			t.Run(tc.name+http.StatusText(status), func(t *testing.T) {
				calls := 0
				b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
					calls++
					var body []byte
					if r.Body != nil {
						body, _ = io.ReadAll(r.Body)
					}
					if calls == 1 {
						resp := response(r, status, nil)
						resp.Header.Set("Location", tc.next)
						return resp, nil
					}
					for _, key := range []string{"Authorization", "Cookie", "Referer", "X-Private-Key", "Origin"} {
						if r.Header.Get(key) != "" {
							t.Errorf("redirect retained %s", key)
						}
					}
					if status == 307 || status == 308 {
						if string(body) != "SECRET_BODY" {
							t.Error("same-origin body lost")
						}
					} else if len(body) != 0 {
						t.Error("redirect replayed dropped body")
					}
					return response(r, 200, []byte(`{}`)), nil
				}))
				_, err := b.Do(t.Context(), Request{URL: tc.start, Method: "POST", Body: []byte("SECRET_BODY"), Headers: map[string]string{"Authorization": "SECRET", "Cookie": "SECRET", "Referer": "SECRET", "X-Private-Key": "SECRET", "Origin": "SECRET"}})
				allowed := tc.allowed || status < 307
				if allowed {
					if err != nil || calls != 2 {
						t.Fatalf("safe redirect rejected: calls=%d err=%v", calls, err)
					}
				} else if err == nil || calls != 1 {
					t.Fatalf("body leaked on cross-origin/downgrade: calls=%d err=%v", calls, err)
				}
			})
		}
	}
	// 无请求体的 HTTPS -> 公网 HTTP 元数据可兼容，但凭据仍清空。
	b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Scheme == "https" {
			resp := response(r, 302, nil)
			resp.Header.Set("Location", "http://other.example.com/next")
			return resp, nil
		}
		if r.Header.Get("Authorization") != "" || r.Header.Get("Referer") != "" {
			t.Error("downgrade leaked header")
		}
		return response(r, 200, []byte(`{}`)), nil
	}))
	if _, err := b.Do(t.Context(), Request{URL: "https://metadata.example.com/start", Headers: map[string]string{"Authorization": "SECRET"}}); err != nil {
		t.Fatal(err)
	}
}

func TestV10PublicHTTPBudgetsAndMediaRejection(t *testing.T) {
	for _, tc := range []struct {
		name           string
		request        Request
		kind, encoding string
		body           []byte
		want           error
	}{
		{name: "request-bytes", request: Request{Body: make([]byte, MaxRequestBytes+1)}, want: ErrLimit},
		{name: "method", request: Request{Method: "CONNECT"}, want: ErrPolicy},
		{name: "headers", request: Request{Headers: map[string]string{"Range": "bytes=0-10"}}, want: ErrPolicy},
		{name: "audio-kind", kind: "audio/mpeg", want: ErrMedia},
		{name: "audio-sniff", kind: "application/json", body: []byte("ID3\x04\x00\x00\x00\x00\x00\x00"), want: ErrMedia},
		{name: "video-kind", kind: "video/mp4", want: ErrMedia},
		{name: "playlist", body: []byte("#EXTM3U\nno-real-media"), want: ErrMedia},
		{name: "response-bytes", body: bytes.Repeat([]byte("x"), MaxResponseBytes+1), want: ErrLimit},
		{name: "compressed", encoding: "gzip", want: ErrPolicy},
	} {
		t.Run(tc.name, func(t *testing.T) {
			b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, tc.body)
				resp.ContentLength = -1
				if tc.kind != "" {
					resp.Header.Set("Content-Type", tc.kind)
				}
				if tc.encoding != "" {
					resp.Header.Set("Content-Encoding", tc.encoding)
				}
				return resp, nil
			}))
			tc.request.URL = "http://metadata.example.com/api"
			if _, err := b.Do(t.Context(), tc.request); !errors.Is(err, tc.want) {
				t.Fatalf("budget/filter: %v want %v", err, tc.want)
			}
		})
	}
	t.Run("wire-budget-concurrent", func(t *testing.T) {
		var calls atomic.Int32
		b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
			calls.Add(1)
			resp := response(r, 302, nil)
			resp.Header.Set("Location", "/loop")
			return resp, nil
		}))
		var wg sync.WaitGroup
		for range 24 {
			wg.Go(func() { _, _ = b.Do(t.Context(), Request{URL: "http://metadata.example.com/start"}) })
		}
		wg.Wait()
		if calls.Load() != MaxRequests {
			t.Fatalf("wire requests %d", calls.Load())
		}
	})
	t.Run("timeout-cannot-be-raised", func(t *testing.T) {
		b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
			deadline, ok := r.Context().Deadline()
			if !ok || time.Until(deadline) > 5*time.Second {
				t.Error("script raised timeout")
			}
			return response(r, 200, []byte(`{}`)), nil
		}))
		if _, err := b.Do(t.Context(), Request{URL: "http://metadata.example.com/api", Timeout: 600000}); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("timeout-and-cancel", func(t *testing.T) {
		b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) { <-r.Context().Done(); return nil, errors.New("SECRET") }))
		started := time.Now()
		_, err := b.Do(t.Context(), Request{URL: "http://metadata.example.com/api", Timeout: 10})
		if err != ErrRequest || time.Since(started) > time.Second {
			t.Fatalf("timeout %v", err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if _, err := b.Do(ctx, Request{URL: "http://metadata.example.com/api"}); err == nil {
			t.Fatal("ignored cancellation")
		}
	})
}

func TestV10PublicHTTPDoesNotDisableTLSVerification(t *testing.T) {
	// 唯一真实 socket 是临时测试 TLS 元数据服务；不访问媒体或业务端口。
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{}`))
	}))
	defer server.Close()
	for _, trusted := range []bool{false, true} {
		t.Run(map[bool]string{true: "trusted", false: "untrusted"}[trusted], func(t *testing.T) {
			b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, nil)
			tr := b.client.Transport.(*safeTransport).base.(*http.Transport)
			if tr.Proxy != nil || tr.TLSClientConfig.InsecureSkipVerify || !tr.DisableKeepAlives || !tr.DisableCompression {
				t.Fatal("transport safeguards changed")
			}
			tr.TLSClientConfig.RootCAs = x509.NewCertPool()
			if trusted {
				tr.TLSClientConfig.RootCAs.AddCert(server.Certificate())
			}
			tr.DialContext = pinnedDial(func(ctx context.Context, network, address string) (net.Conn, error) {
				if address != "93.184.215.14:443" {
					t.Error("TLS dial not pinned")
				}
				return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
			})
			_, err := b.Do(t.Context(), Request{URL: "https://example.com/api"})
			if (err == nil) != trusted {
				t.Fatalf("TLS trust check: trusted=%v err=%v", trusted, err)
			}
		})
	}
}

func TestV10DefaultBrokerNeverInheritsLXRedirectPermission(t *testing.T) {
	for _, allow := range []bool{true, false, true, false} {
		calls := 0
		b := v10Broker(t, Options{AllowPublicHTTP: allow}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
			calls++
			if r.URL.Scheme == "https" {
				resp := response(r, 302, nil)
				resp.Header.Set("Location", "http://metadata.example.com/next")
				return resp, nil
			}
			return response(r, 200, []byte(`{}`)), nil
		}))
		_, err := b.Do(t.Context(), Request{URL: "https://metadata.example.com/start"})
		if allow {
			if err != nil || calls != 2 {
				t.Fatalf("LX HTTP redirect failed: %v", err)
			}
		} else if err == nil || calls != 1 {
			t.Fatal("LX permission leaked into default Broker redirect")
		}
	}
}

func TestV10RedirectChainCannotResurrectCredentialsOrBodies(t *testing.T) {
	for _, firstStatus := range []int{302, 303, 307, 308} {
		for _, withBody := range []bool{false, true} {
			t.Run(http.StatusText(firstStatus)+map[bool]string{true: "/body", false: "/no-body"}[withBody], func(t *testing.T) {
				calls := 0
				b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
					calls++
					var body []byte
					if r.Body != nil {
						body, _ = io.ReadAll(r.Body)
					}
					if calls > 1 {
						if len(body) > 0 {
							t.Error("body replayed after cross-origin hop")
						}
						if r.Header.Get("Authorization") != "" || r.Header.Get("Cookie") != "" || r.Header.Get("X-Key") != "" {
							t.Error("original credentials resurrected")
						}
					}
					switch calls {
					case 1:
						resp := response(r, firstStatus, nil)
						resp.Header.Set("Location", "http://other.example.com/next")
						return resp, nil
					case 2:
						resp := response(r, 307, nil)
						resp.Header.Set("Location", "https://metadata.example.com/back")
						return resp, nil
					default:
						return response(r, 200, []byte(`{}`)), nil
					}
				}))
				req := Request{URL: "https://metadata.example.com/start", Method: "POST", Headers: map[string]string{"Authorization": "SECRET", "Cookie": "SECRET", "X-Key": "SECRET"}}
				if withBody {
					req.Body = []byte("SECRET_BODY")
				}
				_, err := b.Do(t.Context(), req)
				// 只有保留请求体的跨 origin 重定向被拒绝。已丢弃的体不得在后续 307 复活。
				if withBody && firstStatus >= 307 {
					if err == nil || calls != 1 {
						t.Fatalf("body crossed origin: calls=%d err=%v", calls, err)
					}
				} else if err != nil || calls != 3 {
					t.Fatalf("bodyless redirect chain failed: calls=%d err=%v", calls, err)
				}
			})
		}
	}
}

func TestV10PublicHTTPBlocksOwnPublicLiteralWithoutDNS(t *testing.T) {
	calls := 0
	b := v10Broker(t, Options{AllowPublicHTTP: true}, func(context.Context, string, string) ([]netip.Addr, error) {
		t.Error("literal unexpectedly resolved")
		return nil, nil
	}, roundTripFunc(func(r *http.Request) (*http.Response, error) { calls++; return response(r, 200, nil), nil }))
	b.client.Transport.(*safeTransport).interfaceAddrs = func() ([]net.Addr, error) { return []net.Addr{&net.IPAddr{IP: net.ParseIP("8.8.8.8")}}, nil }
	if _, err := b.Do(t.Context(), Request{URL: "http://8.8.8.8/"}); err == nil || calls != 0 {
		t.Fatal("own public interface accepted")
	}
}

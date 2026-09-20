package netguard

import (
	"context"
	"errors"
	"net/http"
	"net/netip"
	"strconv"
	"testing"
	"time"
)

func TestBrokerMaxRequestsOption(t *testing.T) {
	lookup := func(context.Context, string, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("93.184.215.14")}, nil
	}
	transport := roundTripFunc(func(r *http.Request) (*http.Response, error) { return response(r, 200, []byte(`{}`)), nil })
	broker, err := newBroker(Options{MaxRequests: 2}, lookup, transport)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/x", Method: http.MethodGet}); err != nil {
			t.Fatalf("request %d within budget failed: %v", i, err)
		}
	}
	if _, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/x", Method: http.MethodGet}); !errors.Is(err, ErrLimit) {
		t.Fatalf("budget exhausted should be ErrLimit, got %v", err)
	}
	// 默认 Broker 仍保持 LX 的 12 次预算，不因新选项放宽。
	fallback, err := newBroker(Options{}, lookup, transport)
	if err != nil {
		t.Fatal(err)
	}
	if fallback.maxRequests != MaxRequests {
		t.Fatalf("default budget %d, want %d", fallback.maxRequests, MaxRequests)
	}
}

func TestBrokerKeepAliveOptionOnlyForTrustedClients(t *testing.T) {
	longLived, err := NewBroker(Options{KeepAlive: true})
	if err != nil {
		t.Fatal(err)
	}
	defer longLived.Close()
	transport := longLived.client.Transport.(*safeTransport).base.(*http.Transport)
	if transport.DisableKeepAlives {
		t.Fatal("keep-alive requested but transport still disables it")
	}
	if transport.IdleConnTimeout != 30*time.Second {
		t.Fatalf("idle timeout %v", transport.IdleConnTimeout)
	}
	isolated, err := NewBroker(Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer isolated.Close()
	standard := isolated.client.Transport.(*safeTransport).base.(*http.Transport)
	if !standard.DisableKeepAlives || standard.IdleConnTimeout != 5*time.Second {
		t.Fatal("default broker must keep per-request connection isolation")
	}
}

func TestBrokerRedirectHostAllowList(t *testing.T) {
	lookup := func(context.Context, string, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("93.184.215.14")}, nil
	}
	outside := roundTripFunc(func(r *http.Request) (*http.Response, error) {
		resp := response(r, 302, nil)
		resp.Header.Set("Location", "https://outside.example.com/next")
		return resp, nil
	})
	broker, err := newBroker(Options{AllowedRedirectHosts: []string{"metadata.example.com"}}, lookup, outside)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/x", Method: http.MethodGet}); err == nil {
		t.Fatal("cross-host redirect must be rejected")
	}

	sameHost := roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/x" {
			resp := response(r, 302, nil)
			resp.Header.Set("Location", "https://metadata.example.com/next")
			return resp, nil
		}
		return response(r, 200, []byte(`{}`)), nil
	})
	allowed, err := newBroker(Options{AllowedRedirectHosts: []string{"metadata.example.com"}}, lookup, sameHost)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := allowed.Do(context.Background(), Request{URL: "https://metadata.example.com/x", Method: http.MethodGet}); err != nil {
		t.Fatalf("same-host redirect should be allowed: %v", err)
	}

	defaultPort := roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/x" {
			resp := response(r, 302, nil)
			resp.Header.Set("Location", "https://metadata.example.com:443/next")
			return resp, nil
		}
		return response(r, 200, []byte(`{}`)), nil
	})
	withDefaultPort, err := newBroker(Options{AllowedRedirectHosts: []string{"metadata.example.com"}}, lookup, defaultPort)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := withDefaultPort.Do(context.Background(), Request{URL: "https://metadata.example.com/x", Method: http.MethodGet}); err != nil {
		t.Fatalf("default HTTPS port should still match the allowed hostname: %v", err)
	}

	for _, host := range []string{"", "Bad Host", "https://metadata.example.com", "metadata.example.com:443", "metadata.example.com.", "-bad.example", "bad-.example", "localhost"} {
		if _, err := newBroker(Options{AllowedRedirectHosts: []string{host}}, lookup, sameHost); err == nil {
			t.Fatalf("invalid redirect host %q accepted", host)
		}
	}
	tooMany := make([]string, 33)
	for i := range tooMany {
		tooMany[i] = "host" + strconv.Itoa(i) + ".example.com"
	}
	if _, err := newBroker(Options{AllowedRedirectHosts: tooMany}, lookup, sameHost); err == nil {
		t.Fatal("oversized redirect allowlist accepted")
	}
}

func TestBrokerRedirectHostSuffixAllowList(t *testing.T) {
	lookup := func(context.Context, string, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("93.184.215.14")}, nil
	}
	redirect := func(location string) http.RoundTripper {
		return roundTripFunc(func(r *http.Request) (*http.Response, error) {
			if r.URL.Path == "/start" {
				resp := response(r, http.StatusFound, nil)
				resp.Header.Set("Location", location)
				return resp, nil
			}
			return response(r, http.StatusOK, []byte(`{}`)), nil
		})
	}
	allowed, err := newBroker(
		Options{AllowedRedirectHostSuffixes: []string{"music.126.net"}},
		lookup,
		redirect("https://p2.music.126.net/cover.png"),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer allowed.Close()
	if _, err = allowed.Do(context.Background(), Request{URL: "https://p1.music.126.net/start", Method: http.MethodGet}); err != nil {
		t.Fatalf("trusted CDN subdomain redirect should be allowed: %v", err)
	}

	lookalike, err := newBroker(
		Options{AllowedRedirectHostSuffixes: []string{"music.126.net"}},
		lookup,
		redirect("https://music.126.net.evil.example/cover.png"),
	)
	if err != nil {
		t.Fatal(err)
	}
	defer lookalike.Close()
	if _, err = lookalike.Do(context.Background(), Request{URL: "https://p1.music.126.net/start", Method: http.MethodGet}); err == nil {
		t.Fatal("lookalike redirect escaped the trusted CDN suffix")
	}
}

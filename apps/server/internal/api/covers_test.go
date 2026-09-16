package api

import (
	"context"
	"errors"
	"net/url"
	"testing"

	"melora/internal/catalog"
)

func TestCoverProxyServesImagesFromAllowList(t *testing.T) {
	s, _, _ := setup(t, "")
	s.covers = catalog.NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
		return []byte("PNG-DATA"), "image/png", nil
	}, nil)
	raw := "https://img1.kuwo.cn/star/albumcover/120/a.jpg"
	w := request(s, "GET", "/api/v1/covers?url="+url.QueryEscape(raw), nil, nil)
	assertStatus(t, w, 200)
	if w.Body.String() != "PNG-DATA" || w.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("bad cover body/type: %q %q", w.Body.String(), w.Header().Get("Content-Type"))
	}
	if cache := w.Header().Get("Cache-Control"); cache == "" {
		t.Fatal("cover response must be cacheable")
	}
}

func TestCoverProxyRejectsUnknownHostsAndUpstreamFailures(t *testing.T) {
	s, _, _ := setup(t, "")
	refused := 0
	s.covers = catalog.NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
		refused++
		return nil, "", errors.New("upstream down")
	}, nil)
	assertStatus(t, request(s, "GET", "/api/v1/covers", nil, nil), 400)
	assertStatus(t, request(s, "GET", "/api/v1/covers?url="+url.QueryEscape("https://evil.example/a.png"), nil, nil), 400)
	if refused != 0 {
		t.Fatal("invalid target must not reach the fetcher")
	}
	upstream := request(s, "GET", "/api/v1/covers?url="+url.QueryEscape("https://img1.kuwo.cn/a.png"), nil, nil)
	assertStatus(t, upstream, 502)
	if refused != 1 {
		t.Fatalf("allowed target should reach fetcher once, got %d", refused)
	}
}

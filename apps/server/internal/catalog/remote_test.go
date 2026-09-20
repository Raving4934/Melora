package catalog

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

type boundedCatalogDoer func(*http.Request) (*http.Response, error)

func (f boundedCatalogDoer) Do(r *http.Request) (*http.Response, error) { return f(r) }
func TestCatalogRequestBoundsAndSanitizedFailures(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
	}{{403, "upstream secret"}, {200, strings.Repeat("x", (1<<20)+1)}} {
		client := boundedCatalogDoer(func(r *http.Request) (*http.Response, error) {
			if _, ok := r.Context().Deadline(); !ok {
				t.Error("missing budget")
			}
			return &http.Response{StatusCode: tc.status, Body: io.NopCloser(strings.NewReader(tc.body))}, nil
		})
		body, err := catalogRequest(t.Context(), client, "GET", "https://music.163.com/api/test", nil, nil)
		if body != nil || !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), "secret") {
			t.Fatal(body, err)
		}
	}
	if _, err := catalogRequest(context.Background(), nil, "GET", "https://music.163.com/", nil, nil); err == nil {
		t.Fatal("nil client")
	}
}
func TestCatalogImagesCannotEscapePlatformDomains(t *testing.T) {
	for _, raw := range []string{"https://img.qq.com.evil.test/a", "https://user:pass@img.qq.com/a", "https://img.qq.com:8443/a", "file:///etc/passwd", "https://127.0.0.1/a", "https://img.qq.com./a"} {
		if got := catalogImage(raw, "qq.com"); got != "" {
			t.Fatal(raw, got)
		}
	}
	if got := catalogImage("http://img.qq.com/a#fragment", "qq.com"); got != "https://img.qq.com/a" {
		t.Fatal(got)
	}
}

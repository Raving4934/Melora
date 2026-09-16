package catalog

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestTXSafeErrorClassification(t *testing.T) {
	tests := []struct {
		name, code, body string
		status           int
	}{
		{"rate limited", "rate_limited", "do-not-expose-upstream-text", 429},
		{"access denied", "access_restricted", "do-not-expose-upstream-text", 403},
		{"transport server", "upstream_unavailable", "do-not-expose-upstream-text", 503},
		{"not JSON", "invalid_response", "do-not-expose-upstream-text", 200},
		{"missing code", "invalid_response", `{}`, 200},
		{"RPC rejected", "upstream_rejected", `{"code":0,"req":{"code":2001,"message":"do-not-expose-upstream-text"}}`, 200},
		{"root rejected", "upstream_rejected", `{"code":1}`, 200},
		{"nested rejected", "upstream_rejected", `{"code":0,"req":{"code":0,"data":{"code":1}}}`, 200},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
				calls++
				return &http.Response{StatusCode: tc.status, Body: io.NopCloser(strings.NewReader(tc.body))}, nil
			}))
			_, err := q.Playlists(t.Context(), "all", 1)
			var coded interface{ CatalogIssueCode() string }
			if !errors.Is(err, ErrUnavailable) || !errors.As(err, &coded) || coded.CatalogIssueCode() != tc.code {
				t.Fatalf("wrong classification: %v", err)
			}
			if strings.Contains(err.Error(), "do-not-expose") || strings.Contains(err.Error(), "2001") || calls != 1 {
				t.Fatal("unsafe details or automatic retry")
			}
		})
	}
}
func TestTXSearchRestrictionIsNotAnEmptySuccessOrRetry(t *testing.T) {
	for _, field := range []string{"is_filter", "safetyType", "safetyUrl", "ret"} {
		t.Run(field, func(t *testing.T) {
			calls := 0
			q := txTestClient(t, func(txTestRPCRequest) any {
				calls++
				meta := map[string]any{"sum": 0, "ret": 0, "is_filter": 0}
				switch field {
				case "is_filter":
					meta[field] = -12
				case "safetyUrl":
					meta[field] = "do-not-expose"
				default:
					meta[field] = 1
				}
				return map[string]any{"meta": meta, "body": map[string]any{"song": map[string]any{"list": []any{}}}}
			})
			got, err := q.Search(t.Context(), "测试", "track", 1)
			var coded interface{ CatalogIssueCode() string }
			want := "access_restricted"
			if field == "ret" {
				want = "upstream_rejected"
			}
			if err == nil || !errors.As(err, &coded) || coded.CatalogIssueCode() != want || len(got.Tracks) != 0 || calls != 1 {
				t.Fatal("restriction mislabeled or retried")
			}
		})
	}
}
func TestTXContextClassificationAndNewTracksCapability(t *testing.T) {
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) { <-r.Context().Done(); return nil, r.Context().Err() }))
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Millisecond)
	defer cancel()
	if _, err := q.Search(ctx, "测试", "track", 1); !errors.Is(err, context.DeadlineExceeded) || !errors.Is(err, ErrUnavailable) {
		t.Fatal("timeout cause lost")
	}
	calls := 0
	q = NewTX(txTestDoer(func(*http.Request) (*http.Response, error) { calls++; return nil, ErrUnavailable }))
	if _, err := q.NewTracks(t.Context(), "all"); !errors.Is(err, ErrUnsupported) || calls != 0 {
		t.Fatal("new tracks fabricated/probed")
	}
	stopped, stop := context.WithCancel(t.Context())
	stop()
	if _, err := q.NewTracks(stopped, "all"); !errors.Is(err, context.Canceled) {
		t.Fatal("capability ignored cancellation")
	}
	if _, err := q.Search(stopped, "测试", "track", 1); !errors.Is(err, context.Canceled) || calls != 0 {
		t.Fatal("cancelled search sent network request")
	}
}

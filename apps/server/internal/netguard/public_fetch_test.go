package netguard

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
)

func TestPublicFetchUsesBoundedPublicResponse(t *testing.T) {
	const body = "// @name fixture\nconst source = true;"
	b := v10Broker(t, Options{AllowPublicHTTP: true, MaxRequests: 4}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		response := response(r, http.StatusOK, []byte(body))
		response.Header.Set("Content-Type", "text/javascript; charset=utf-8")
		response.Header.Set("Content-Disposition", `attachment; filename="fixture.js"`)
		return response, nil
	}))
	got, err := b.fetchPublic(context.Background(), "https://metadata.example.com/source", PublicFetchMaxBytes)
	if err != nil {
		t.Fatal(err)
	}
	if got.StatusCode != http.StatusOK || string(got.Body) != body || got.FinalURL != "https://metadata.example.com/source" {
		t.Fatalf("unexpected fetch result: %+v", got)
	}
	if got.Headers["content-disposition"] == "" {
		t.Fatal("response headers were not preserved")
	}
}

func TestPublicFetchAcceptsPublicHTTP(t *testing.T) {
	const body = "// @name fixture\nconst source = true;"
	b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Scheme != "http" {
			t.Fatalf("request scheme = %s", r.URL.Scheme)
		}
		return response(r, http.StatusOK, []byte(body)), nil
	}))
	got, err := b.fetchPublic(context.Background(), "http://metadata.example.com/source", PublicFetchMaxBytes)
	if err != nil {
		t.Fatal(err)
	}
	if got.StatusCode != http.StatusOK || string(got.Body) != body || got.FinalURL != "http://metadata.example.com/source" {
		t.Fatalf("unexpected HTTP fetch result: %+v", got)
	}
}

func TestPublicFetchRejectsUnsafeURLsBeforeTransport(t *testing.T) {
	calls := 0
	b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		return response(r, http.StatusOK, []byte("unexpected")), nil
	}))
	for _, raw := range []string{
		"http://user:secret@metadata.example.com/source.js",
		"https://metadata.example.com/source.js#secret",
		"http://localhost/source.js",
		"http://127.0.0.1/source.js",
		"http://169.254.169.254/latest/meta-data/",
		"http://metadata.google.internal/source.js",
	} {
		t.Run(raw, func(t *testing.T) {
			if _, err := b.fetchPublic(context.Background(), raw, PublicFetchMaxBytes); !errors.Is(err, ErrPolicy) {
				t.Fatalf("unsafe URL error = %v", err)
			}
		})
	}
	if calls != 0 {
		t.Fatalf("unsafe URL reached transport %d times", calls)
	}
}

func TestPublicFetchRedirectsNeverDowngradeInitialHTTPS(t *testing.T) {
	for _, tc := range []struct {
		name      string
		start     string
		next      string
		wantErr   error
		wantFinal string
		wantCalls int
	}{
		{name: "https-to-http", start: "https://metadata.example.com/start", next: "http://metadata.example.com/next", wantErr: ErrPolicy, wantCalls: 1},
		{name: "https-to-https", start: "https://metadata.example.com/start", next: "https://metadata.example.com/next", wantFinal: "https://metadata.example.com/next", wantCalls: 2},
		{name: "http-to-http", start: "http://metadata.example.com/start", next: "http://metadata.example.com/next", wantFinal: "http://metadata.example.com/next", wantCalls: 2},
		{name: "http-to-https", start: "http://metadata.example.com/start", next: "https://metadata.example.com/next", wantFinal: "https://metadata.example.com/next", wantCalls: 2},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				if calls == 1 {
					resp := response(r, http.StatusFound, nil)
					resp.Header.Set("Location", tc.next)
					return resp, nil
				}
				return response(r, http.StatusOK, []byte("redirected")), nil
			}))
			got, err := b.fetchPublic(context.Background(), tc.start, PublicFetchMaxBytes)
			if tc.wantErr != nil {
				if !errors.Is(err, tc.wantErr) || calls != tc.wantCalls {
					t.Fatalf("redirect downgrade: calls=%d err=%v", calls, err)
				}
				return
			}
			if err != nil || got.FinalURL != tc.wantFinal || calls != tc.wantCalls {
				t.Fatalf("redirect result: calls=%d final=%q err=%v", calls, got.FinalURL, err)
			}
		})
	}
}

func TestPublicFetchRejectsContentLengthAndActualBodyOverflow(t *testing.T) {
	t.Run("content-length", func(t *testing.T) {
		b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
			resp := response(r, http.StatusOK, []byte("small"))
			resp.ContentLength = PublicFetchMaxBytes + 1
			return resp, nil
		}))
		if _, err := b.fetchPublic(context.Background(), "https://metadata.example.com/source.js", PublicFetchMaxBytes); !errors.Is(err, ErrLimit) {
			t.Fatalf("content-length error = %v", err)
		}
	})
	t.Run("actual-body", func(t *testing.T) {
		b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
			resp := response(r, http.StatusOK, []byte(strings.Repeat("x", PublicFetchMaxBytes+1)))
			resp.ContentLength = -1
			return resp, nil
		}))
		if _, err := b.fetchPublic(context.Background(), "https://metadata.example.com/source.js", PublicFetchMaxBytes); !errors.Is(err, ErrLimit) {
			t.Fatalf("actual-body error = %v", err)
		}
	})
}

package catalog

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

// 2026-09-08T19:45:58Z 固定匿名 GET/POST 的共同响应形态。
// 仅保留业务层级与搜索状态；不携带客户端 IP、追踪 ID、凭据或远端文字。
const txRejectedSearchHTTP = `{"code":0,"req":{"code":2001,"data":{"code":0,"body":{"song":{"list":[]}},"meta":{"ret":0,"sum":0,"estimate_sum":140565885273080,"is_filter":-12,"safetyType":0}}}}`

func txAssertIssue(t *testing.T, err error, want string) {
	t.Helper()
	var coded interface{ CatalogIssueCode() string }
	if !errors.Is(err, ErrUnavailable) || !errors.As(err, &coded) {
		t.Fatalf("missing unavailable issue: %v", err)
	}
	if got := coded.CatalogIssueCode(); got != want {
		t.Fatalf("issue=%q, want %q", got, want)
	}
	if strings.Contains(err.Error(), "2001") || strings.Contains(err.Error(), "do-not-expose") {
		t.Fatal("upstream details leaked")
	}
}

func TestTXSearchRPCRejectionKeepsExplicitRestriction(t *testing.T) {
	for _, kind := range []string{"track", "artist", "album", "playlist"} {
		t.Run(kind, func(t *testing.T) {
			calls := 0
			q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
				calls++
				request := txTestReadRPC(t, r)
				if request.Req.Method != "DoSearchForQQMusicMobile" || request.Req.Module != "music.search.SearchCgiService" {
					t.Fatal("restriction changed the public request protocol")
				}
				return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(txRejectedSearchHTTP))}, nil
			}))
			got, err := q.Search(t.Context(), "孤独", kind, 1)
			txAssertIssue(t, err, "access_restricted")
			if calls != 1 || got.Total != 0 || len(got.Tracks)+len(got.Artists)+len(got.Albums)+len(got.Playlists) != 0 {
				t.Fatal("rejection triggered a retry or fabricated results")
			}
		})
	}
}

func TestTXSearchRPCRejectionMetadataDefense(t *testing.T) {
	for _, tc := range []struct {
		name, data, want string
	}{
		{"filter", `{"meta":{"is_filter":-12}}`, "access_restricted"},
		{"safety type", `{"meta":{"safetyType":1}}`, "access_restricted"},
		{"safety URL not fetched", `{"meta":{"safetyUrl":"https://do-not-expose.invalid/challenge"}}`, "access_restricted"},
		{"unknown rejection", `{}`, "upstream_rejected"},
		{"nonrestricting metadata", `{"meta":{"ret":0,"sum":0,"is_filter":0,"safetyType":0,"safetyUrl":""}}`, "upstream_rejected"},
		{"business failure alone", `{"meta":{"ret":1}}`, "upstream_rejected"},
		{"missing meta", `{"body":{"song":{"list":[]}}}`, "upstream_rejected"},
		{"null meta", `{"meta":null}`, "upstream_rejected"},
		{"null data", `null`, "upstream_rejected"},
		{"array data", `[]`, "upstream_rejected"},
		{"string data", `"do-not-expose"`, "upstream_rejected"},
		{"array meta", `{"meta":[]}`, "upstream_rejected"},
		{"string filter", `{"meta":{"is_filter":"-12"}}`, "upstream_rejected"},
		{"boolean safety type", `{"meta":{"is_filter":-12,"safetyType":true}}`, "upstream_rejected"},
		{"number safety URL", `{"meta":{"is_filter":-12,"safetyUrl":1}}`, "upstream_rejected"},
		{"overflow filter", `{"meta":{"is_filter":-999999999999999999999999}}`, "upstream_rejected"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
				calls++
				txTestReadRPC(t, r)
				return txTestResponse(map[string]any{"code": 0, "req": map[string]any{"code": 2001, "data": json.RawMessage(tc.data)}}), nil
			}))
			got, err := q.Search(t.Context(), "测试", "track", 1)
			txAssertIssue(t, err, tc.want)
			if calls != 1 || len(got.Tracks) != 0 {
				t.Fatal("unsafe success or extra request")
			}
		})
	}
}

func TestTXSearchRPCRejectionNeverAcceptsRowsOrOtherEnvelopes(t *testing.T) {
	for _, tc := range []struct {
		name, want string
		root, req  any
	}{
		{"root rejection wins", "upstream_rejected", 1, 2001},
		{"missing root status", "invalid_response", nil, 2001},
		{"missing RPC status", "invalid_response", 0, nil},
		{"malformed RPC status", "invalid_response", 0, "2001"},
		{"explicit restriction", "access_restricted", 0, 2001},
	} {
		t.Run(tc.name, func(t *testing.T) {
			q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
				data := map[string]any{"meta": map[string]any{"sum": 1, "is_filter": -12}, "body": map[string]any{"song": map[string]any{"list": []any{txTestSong(txTestMID, 97773)}}}}
				return txTestResponse(map[string]any{"code": tc.root, "req": map[string]any{"code": tc.req, "data": data}}), nil
			}))
			got, err := q.Search(t.Context(), "测试", "track", 1)
			txAssertIssue(t, err, tc.want)
			if got.Total != 0 || len(got.Tracks) != 0 {
				t.Fatal("rejected payload was accepted as search data")
			}
		})
	}
	q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(txRejectedSearchHTTP))}, nil
	}))
	_, err := q.Playlists(t.Context(), "all", 1)
	txAssertIssue(t, err, "upstream_rejected") // 不向歌单套用搜索专属语义。
}

func TestTXSearchRPCRejectionPreservesOtherCapabilitiesAndPlatforms(t *testing.T) {
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		request := txTestReadRPC(t, r)
		if request.Req.Method == "get_playlist_by_tag" {
			return txTestResponse(txTestEnvelope(txOnePlaylist())), nil
		}
		return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(txRejectedSearchHTTP))}, nil
	}))
	r := NewRegistry(map[string]Adapter{"tx": q, "wy": &registryStub{prefix: "wy"}})
	got, err := r.SearchFor(t.Context(), "all", "测试", "track", 1)
	failed, partial := IsPartial(err)
	if !partial || len(failed) != 1 || failed[0] != "tx" || len(got.Tracks) != 1 || got.Tracks[0].ProviderID != "wy" {
		t.Fatalf("partial search lost results or TX error: %+v %v", got, err)
	}
	_, err = r.SearchFor(t.Context(), "tx", "测试", "track", 1)
	txAssertIssue(t, err, "access_restricted")
	items, err := q.Playlists(t.Context(), "all", 1)
	if err != nil || len(items) != 1 || items[0].ProviderID != "tx" {
		t.Fatal("search rejection disabled valid TX playlists")
	}
}

func TestTXSearchRPCRejectionRespectsTransportBoundary(t *testing.T) {
	for _, tc := range []struct {
		name, want string
		status     int
		oversize   bool
	}{
		{"unauthorized", "access_restricted", http.StatusUnauthorized, false},
		{"forbidden", "access_restricted", http.StatusForbidden, false},
		{"rate limited", "rate_limited", http.StatusTooManyRequests, false},
		{"server failure", "upstream_unavailable", http.StatusServiceUnavailable, false},
		{"body cap", "invalid_response", http.StatusOK, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls, closed := 0, false
			raw := txRejectedSearchHTTP
			if tc.oversize {
				raw += strings.Repeat(" ", (1<<20)+1-len(raw))
			}
			q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
				calls++
				txTestReadRPC(t, r) // 仍为固定 GET、匿名头与不超过 9 秒的 deadline。
				return &http.Response{StatusCode: tc.status, Body: txRejectionTestBody{Reader: strings.NewReader(raw), closed: &closed}}, nil
			}))
			_, err := q.Search(t.Context(), "测试", "track", 1)
			txAssertIssue(t, err, tc.want)
			if calls != 1 || !closed {
				t.Fatal("transport retried or response body leaked")
			}
		})
	}
}

type txRejectionTestBody struct {
	io.Reader
	closed *bool
}

func (b txRejectionTestBody) Close() error { *b.closed = true; return nil }

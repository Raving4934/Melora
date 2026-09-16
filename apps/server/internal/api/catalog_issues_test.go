package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"

	"melora/internal/catalog"
	"melora/internal/model"
)

const catalogIssuesSecret = "PRIVATE_TOKEN https://private.invalid/user/path?token=secret#fragment"

type catalogIssuesError struct{ code string }

func (e catalogIssuesError) Error() string            { return catalogIssuesSecret }
func (e catalogIssuesError) CatalogIssueCode() string { return e.code }

type catalogIssuesUnreadableError struct{}

func (catalogIssuesUnreadableError) Error() string { panic("raw error text must not be read") }
func (catalogIssuesUnreadableError) CatalogIssueCode() string {
	panic("unrelated platform must not be classified")
}

func catalogIssuesWrite(data any, err error) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	(&Server{}).writeCatalog(w, data, err)
	return w
}

func TestCatalogIssuesSafeCodesPreserveJSONArrays(t *testing.T) {
	cases := []struct {
		name string
		err  error
		code string
	}{
		{"restricted", catalogIssuesError{"access_restricted"}, "access_restricted"},
		{"invalid-response", catalogIssuesError{"invalid_response"}, "invalid_response"},
		{"rejected", catalogIssuesError{"upstream_rejected"}, "upstream_rejected"},
		{"rate-limited", catalogIssuesError{"rate_limited"}, "rate_limited"},
		{"typed-timeout", catalogIssuesError{"timeout"}, "timeout"},
		{"cancelled", fmt.Errorf("private wrapper: %w", context.Canceled), "cancelled"},
		{"deadline", fmt.Errorf("private wrapper: %w", context.DeadlineExceeded), "timeout"},
		{"unsupported", fmt.Errorf("private wrapper: %w", catalog.ErrUnsupported), "unsupported"},
		{"wrapped-code", fmt.Errorf("private wrapper: %w", catalogIssuesError{"access_restricted"}), "access_restricted"},
		{"unknown-error", errors.New(catalogIssuesSecret), "upstream_unavailable"},
		{"missing-cause", nil, "upstream_unavailable"},
	}
	data := []model.Collection{{ID: "wy:chart_1", ProviderID: "wy", Title: "保留原数组"}}
	baseline := httptest.NewRecorder()
	writeJSON(baseline, 200, data)
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			partial := &catalog.PartialError{Sources: []string{"tx"}, Causes: map[string]error{"tx": tc.err}}
			w := catalogIssuesWrite(data, fmt.Errorf("outer wrapper: %w", partial))
			if w.Code != 200 || w.Body.String() != baseline.Body.String() {
				t.Fatal("partial result changed the status or original JSON array", w.Code, w.Body.String())
			}
			if got := w.Header().Get("X-Melora-Unavailable-Sources"); got != "tx" {
				t.Fatal("legacy unavailable header changed", got)
			}
			if got := w.Header().Get("X-Melora-Catalog-Issues"); got != "tx="+tc.code {
				t.Fatalf("safe issue code not propagated: %q", got)
			}
			if strings.Contains(fmt.Sprint(w.Header()), "PRIVATE_TOKEN") || strings.Contains(w.Body.String(), "PRIVATE_TOKEN") {
				t.Fatal("internal cause escaped into HTTP")
			}
		})
	}
}

func TestCatalogIssuesRejectArbitraryAndMaliciousCodes(t *testing.T) {
	for _, code := range []string{"", "invented", "access_restricted\r\nX-Secret: " + catalogIssuesSecret, "timeout,wy=" + catalogIssuesSecret, catalogIssuesSecret} {
		t.Run(fmt.Sprintf("case-%d", len(code)), func(t *testing.T) {
			w := catalogIssuesWrite([]model.Collection{}, &catalog.PartialError{
				Sources: []string{"tx"}, Causes: map[string]error{"tx": catalogIssuesError{code}},
			})
			if got := w.Header().Get("X-Melora-Catalog-Issues"); got != "tx=upstream_unavailable" {
				t.Fatalf("unknown code was not safely generalized: %q", got)
			}
			if strings.Contains(fmt.Sprint(w.Header()), "PRIVATE_TOKEN") || strings.Contains(w.Body.String(), "PRIVATE_TOKEN") {
				t.Fatal("malicious cause or code leaked")
			}
		})
	}
}

func TestCatalogIssuesOnlyFailedKnownPlatformsAndNoDuplicates(t *testing.T) {
	partial := &catalog.PartialError{
		Sources: []string{"tx", "unknown", "kw", "tx", "wy,tx"},
		Causes: map[string]error{
			"tx": catalogIssuesError{"access_restricted"}, "kw": context.DeadlineExceeded,
			"kg":      catalogIssuesUnreadableError{}, // 已知但未失败，不能读取。
			"unknown": catalogIssuesUnreadableError{}, "wy,tx": catalogIssuesUnreadableError{},
			"PRIVATE_SOURCE": catalogIssuesUnreadableError{},
		},
	}
	w := catalogIssuesWrite([]model.Collection{}, partial)
	if got := w.Header().Get("X-Melora-Catalog-Issues"); got != "kw=timeout,tx=access_restricted" {
		t.Fatalf("failed/known platform allowlist or ordering broken: %q", got)
	}
	failed, _ := catalog.IsPartial(partial)
	if w.Header().Get("X-Melora-Unavailable-Sources") != strings.Join(failed, ",") {
		t.Fatal("old Sources header semantics were changed")
	}
	for _, sources := range [][]string{nil, {"unknown"}} {
		w = catalogIssuesWrite([]model.Collection{}, &catalog.PartialError{
			Sources: sources, Causes: map[string]error{"tx": catalogIssuesUnreadableError{}},
		})
		if w.Header().Get("X-Melora-Catalog-Issues") != "" {
			t.Fatal("unrelated cause produced a header")
		}
	}
}

func TestCatalogIssuesLegacySourcesOnlyAndSuccessfulBodies(t *testing.T) {
	for _, data := range []any{[]model.Collection{}, []model.Collection{{ID: "wy:one"}}, catalog.SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}}} {
		baseline := httptest.NewRecorder()
		writeJSON(baseline, 200, data)
		for _, err := range []error{nil, &catalog.PartialError{Sources: []string{"tx"}}} {
			w := catalogIssuesWrite(data, err)
			if w.Code != 200 || w.Body.String() != baseline.Body.String() {
				t.Fatal("original array/object JSON shape changed")
			}
			if err == nil && (w.Header().Get("X-Melora-Catalog-Issues") != "" || w.Header().Get("X-Melora-Unavailable-Sources") != "") {
				t.Fatal("successful request became a partial result")
			}
			if err != nil && w.Header().Get("X-Melora-Catalog-Issues") != "tx=upstream_unavailable" {
				t.Fatal("old Sources-only partial must use the safe generic code")
			}
		}
	}
}

func TestCatalogIssuesNonPartialErrorsKeepOriginalResponse(t *testing.T) {
	for _, err := range []error{catalog.ErrUnavailable, catalog.ErrUnsupported, catalog.ErrInput, context.Canceled, context.DeadlineExceeded, errors.New("opaque failure")} {
		baseline := httptest.NewRecorder()
		(&Server{}).liveError(baseline, err)
		w := catalogIssuesWrite([]model.Collection{{ID: "must-not-leak"}}, err)
		if w.Code != baseline.Code || w.Body.String() != baseline.Body.String() {
			t.Fatal("non-partial/single-source failure behavior changed")
		}
		if w.Header().Get("X-Melora-Catalog-Issues") != "" || w.Header().Get("X-Melora-Unavailable-Sources") != "" {
			t.Fatal("non-partial response acquired partial headers")
		}
	}
}

type catalogIssuesAdapter struct {
	*platformFixture
	cause error
}

func (a *catalogIssuesAdapter) Charts(ctx context.Context) ([]model.Collection, error) {
	if a.cause != nil {
		return nil, a.cause
	}
	return a.platformFixture.Charts(ctx)
}
func (a *catalogIssuesAdapter) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	if a.cause != nil {
		return nil, a.cause
	}
	return a.platformFixture.Playlists(ctx, category, page)
}
func (a *catalogIssuesAdapter) Search(ctx context.Context, q, kind string, page int) (catalog.SearchResult, error) {
	if a.cause != nil {
		return catalog.SearchResult{}, a.cause
	}
	return a.platformFixture.Search(ctx, q, kind, page)
}

func TestCatalogIssuesReachSearchChartsAndPlaylists(t *testing.T) {
	s, _, _ := liveSetup(t, "")
	s.live.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{
		"wy": &catalogIssuesAdapter{platformFixture: &platformFixture{id: "wy"}},
		"tx": &catalogIssuesAdapter{platformFixture: &platformFixture{id: "tx"}, cause: catalogIssuesError{"access_restricted"}},
	})
	for _, endpoint := range []string{"charts", "playlists", "search"} {
		t.Run(endpoint, func(t *testing.T) {
			w := request(s, "GET", "/api/v1/"+endpoint+"?source=all&q=offline&type=track", nil, nil)
			if w.Code != 200 || w.Header().Get("X-Melora-Catalog-Issues") != "tx=access_restricted" || w.Header().Get("X-Melora-Unavailable-Sources") != "tx" {
				t.Fatal("actual catalog endpoint dropped partial capability cause", w.Code, w.Header())
			}
			var payload any
			if err := json.Unmarshal(w.Body.Bytes(), &payload); err != nil {
				t.Fatal(err)
			}
			var items []any
			if endpoint == "search" {
				items, _ = payload.(map[string]any)["tracks"].([]any)
			} else {
				items, _ = payload.([]any)
			}
			if len(items) != 1 || items[0].(map[string]any)["providerId"] != "wy" {
				t.Fatal("successful source data or original array/object shape was lost")
			}
		})
	}
}

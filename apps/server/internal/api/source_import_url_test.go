package api

import (
	"context"
	"net/http"
	"strings"
	"testing"

	"melora/internal/netguard"
)

func sourceURLCookie(t *testing.T, s *Server) *http.Cookie {
	t.Helper()
	login := request(s, http.MethodPost, "/api/v1/auth/session", map[string]string{"token": "source-url-token-01234567890123456789"}, nil)
	assertStatus(t, login, http.StatusOK)
	cookies := login.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("login cookies = %d", len(cookies))
	}
	return cookies[0]
}

func TestSourceImportURLRequiresSessionAndImportsThroughManager(t *testing.T) {
	s, manager, _ := liveSetup(t, "source-url-token-01234567890123456789")
	const code = "// @name online fixture\nconst source = true;"
	calls := 0
	s.fetchSourceURL = func(_ context.Context, raw string) (netguard.PublicFetchResult, error) {
		calls++
		if raw != "https://cdn.example.test/path/source" {
			t.Fatalf("unexpected URL %q", raw)
		}
		return netguard.PublicFetchResult{
			StatusCode: http.StatusOK,
			FinalURL:   "https://cdn.example.test/path/source",
			Headers: map[string]string{
				"content-type":        "text/javascript; charset=utf-8",
				"content-disposition": `attachment; filename="remote-source.js"`,
			},
			Body: []byte(code),
		}, nil
	}
	path := "/api/v1/sources/import-url"
	denied := request(s, http.MethodPost, path, map[string]string{"url": "https://cdn.example.test/path/source"}, nil)
	assertStatus(t, denied, http.StatusUnauthorized)
	cookie := sourceURLCookie(t, s)
	got := request(s, http.MethodPost, path, map[string]string{"url": " https://cdn.example.test/path/source "}, cookie)
	assertStatus(t, got, http.StatusCreated)
	if strings.Contains(got.Body.String(), "cdn.example.test") {
		t.Fatal("response leaked source URL")
	}
	if calls != 1 {
		t.Fatalf("fetch calls = %d", calls)
	}
	state := manager.List()
	if len(state.Items) != 1 || state.Items[0].Filename != "remote-source.js" || state.Items[0].Status != "ready" {
		t.Fatalf("unexpected imported source: %+v", state.Items)
	}
}

func TestSourceImportURLAcceptsHTTP(t *testing.T) {
	s, manager, _ := liveSetup(t, "source-url-token-01234567890123456789")
	const code = "// @name HTTP online fixture\nconst source = true;"
	s.fetchSourceURL = func(_ context.Context, raw string) (netguard.PublicFetchResult, error) {
		if raw != "http://cdn.example.test/path/source" {
			t.Fatalf("unexpected URL %q", raw)
		}
		return netguard.PublicFetchResult{
			StatusCode: http.StatusOK,
			FinalURL:   raw,
			Headers: map[string]string{
				"content-type": "text/javascript; charset=utf-8",
			},
			Body: []byte(code),
		}, nil
	}
	cookie := sourceURLCookie(t, s)
	got := request(s, http.MethodPost, "/api/v1/sources/import-url", map[string]string{"url": " http://cdn.example.test/path/source "}, cookie)
	assertStatus(t, got, http.StatusCreated)
	state := manager.List()
	if len(state.Items) != 1 || state.Items[0].Filename != "source.js" || state.Items[0].Status != "ready" {
		t.Fatalf("unexpected imported HTTP source: %+v", state.Items)
	}
}

func TestSourceImportURLRejectsFetchAndContentFailuresWithoutURLEcho(t *testing.T) {
	s, _, _ := liveSetup(t, "source-url-token-01234567890123456789")
	cookie := sourceURLCookie(t, s)
	const sensitiveURL = "http://user:secret@private.example.test/source.js#token"
	cases := []struct {
		name       string
		fetchError error
		result     netguard.PublicFetchResult
		status     int
		code       string
	}{
		{name: "policy", fetchError: netguard.ErrPolicy, status: http.StatusBadRequest, code: "invalid_source_url"},
		{name: "private", fetchError: netguard.ErrPolicy, status: http.StatusBadRequest, code: "invalid_source_url"},
		{name: "too-large", fetchError: netguard.ErrLimit, status: http.StatusRequestEntityTooLarge, code: "source_too_large"},
		{name: "html", result: netguard.PublicFetchResult{StatusCode: 200, Headers: map[string]string{"content-type": "text/html"}, Body: []byte("<!doctype html><html></html>")}, status: http.StatusBadRequest, code: "invalid_source_url_content"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s.fetchSourceURL = func(context.Context, string) (netguard.PublicFetchResult, error) {
				return tc.result, tc.fetchError
			}
			w := request(s, http.MethodPost, "/api/v1/sources/import-url", map[string]string{"url": sensitiveURL}, cookie)
			assertStatus(t, w, tc.status)
			if !strings.Contains(w.Body.String(), `"code":"`+tc.code+`"`) {
				t.Fatalf("missing error code %s: %s", tc.code, w.Body.String())
			}
			if strings.Contains(w.Body.String(), "secret") || strings.Contains(w.Body.String(), "private.example.test") {
				t.Fatal("sensitive URL leaked in error")
			}
		})
	}
}

func TestSourceImportURLRejectsNonSuccessResponse(t *testing.T) {
	s, _, _ := liveSetup(t, "source-url-token-01234567890123456789")
	cookie := sourceURLCookie(t, s)
	s.fetchSourceURL = func(context.Context, string) (netguard.PublicFetchResult, error) {
		return netguard.PublicFetchResult{StatusCode: http.StatusNotFound, Body: []byte("not found")}, nil
	}
	w := request(s, http.MethodPost, "/api/v1/sources/import-url", map[string]string{"url": "https://cdn.example.test/missing.js"}, cookie)
	assertStatus(t, w, http.StatusUnprocessableEntity)
	if strings.Contains(w.Body.String(), "cdn.example.test") || !strings.Contains(w.Body.String(), `"code":"source_url_fetch_failed"`) {
		t.Fatalf("unsafe fetch failure response: %s", w.Body.String())
	}
}

func TestSourceImportURLRejectsInvalidContent(t *testing.T) {
	s, _, _ := liveSetup(t, "source-url-token-01234567890123456789")
	cookie := sourceURLCookie(t, s)
	for name, body := range map[string][]byte{
		"empty": nil,
		"nul":   []byte("const bad = '\x00';"),
		"utf8":  {0xff, 0xfe},
	} {
		t.Run(name, func(t *testing.T) {
			s.fetchSourceURL = func(context.Context, string) (netguard.PublicFetchResult, error) {
				return netguard.PublicFetchResult{StatusCode: http.StatusOK, Body: body}, nil
			}
			w := request(s, http.MethodPost, "/api/v1/sources/import-url", map[string]string{"url": "https://cdn.example.test/source.js"}, cookie)
			assertStatus(t, w, http.StatusBadRequest)
			if !strings.Contains(w.Body.String(), `"code":"invalid_source_url_content"`) {
				t.Fatalf("unexpected response: %s", w.Body.String())
			}
		})
	}
}

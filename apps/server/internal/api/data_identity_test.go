package api

import (
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"melora/internal/store"
)

func identityResponse(t *testing.T, w *httptest.ResponseRecorder) map[string]json.RawMessage {
	t.Helper()
	var body map[string]json.RawMessage
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("session or denied response may be cached")
	}
	return body
}

func requireSessionIdentity(t *testing.T, w *httptest.ResponseRecorder, resetLegacy bool) string {
	t.Helper()
	assertStatus(t, w, http.StatusOK)
	body := identityResponse(t, w)
	if string(body["authenticated"]) != "true" {
		t.Fatal("identity response must be authenticated")
	}
	var identity map[string]json.RawMessage
	if err := json.Unmarshal(body["dataIdentity"], &identity); err != nil {
		t.Fatalf("authenticated session is missing dataIdentity: %v body=%s", err, w.Body.String())
	}
	var generation string
	if err := json.Unmarshal(identity["generation"], &generation); err != nil || !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(generation) {
		t.Fatalf("invalid generation: %q %v", generation, err)
	}
	want := "false"
	if resetLegacy {
		want = "true"
	}
	if len(identity) != 2 || string(identity["resetLegacy"]) != want {
		t.Fatalf("identity shape/resetLegacy changed: %s", body["dataIdentity"])
	}
	return generation
}

func requireNoSessionIdentity(t *testing.T, w *httptest.ResponseRecorder) map[string]json.RawMessage {
	t.Helper()
	body := identityResponse(t, w)
	if _, present := body["dataIdentity"]; present || strings.Contains(w.Body.String(), `"generation"`) || strings.Contains(w.Body.String(), `"resetLegacy"`) {
		t.Fatalf("response exposed database identity: %s", w.Body.String())
	}
	return body
}

func TestDataIdentityLocalSessionCachedAndReadOnly(t *testing.T) {
	s, db, _ := setup(t, "")
	first := request(s, http.MethodGet, "/api/v1/auth/session", nil, nil)
	generation := requireSessionIdentity(t, first, true)
	body := identityResponse(t, first)
	if len(body) != 5 || string(body["required"]) != "false" {
		t.Fatalf("local auth response fields changed: %s", first.Body.String())
	}
	spoofed := strings.Repeat("f", 32)
	r := httptest.NewRequest(http.MethodGet, "http://localhost/api/v1/auth/session?generation="+spoofed+"&resetLegacy=false", nil)
	r.Header.Set("X-Melora-Data-Identity", spoofed)
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	if got := requireSessionIdentity(t, w, true); got != generation {
		t.Fatal("client-supplied generation changed identity")
	}
	// 关闭临时 DB 后仍可轮询，证明会话读取的是已提交的不可变缓存。
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		if got := requireSessionIdentity(t, request(s, http.MethodGet, "/api/v1/auth/session", nil, nil), true); got != generation {
			t.Fatal("cached identity changed during session polling")
		}
	}
}

func TestDataIdentityTokenSessionAuthBoundaries(t *testing.T) {
	const token = "data-identity-test-only-strong-access-token-1234567890"
	s, _, _ := setup(t, token)
	for _, cookie := range []*http.Cookie{nil, {Name: sessionCookie, Value: "forged"}} {
		w := request(s, http.MethodGet, "/api/v1/auth/session", nil, cookie)
		assertStatus(t, w, http.StatusOK)
		body := requireNoSessionIdentity(t, w)
		if len(body) != 4 || string(body["authenticated"]) != "false" || string(body["required"]) != "true" {
			t.Fatalf("unauthenticated response changed: %s", w.Body.String())
		}
	}
	badLogin := request(s, http.MethodPost, "/api/v1/auth/session", map[string]string{"token": "wrong"}, nil)
	assertStatus(t, badLogin, http.StatusUnauthorized)
	requireNoSessionIdentity(t, badLogin)
	injected := request(s, http.MethodPost, "/api/v1/auth/session", map[string]any{"token": token, "dataIdentity": map[string]any{"generation": strings.Repeat("a", 32), "resetLegacy": false}}, nil)
	assertStatus(t, injected, http.StatusBadRequest)
	requireNoSessionIdentity(t, injected)
	login := request(s, http.MethodPost, "/api/v1/auth/session", map[string]string{"token": token}, nil)
	assertStatus(t, login, http.StatusOK)
	if body := requireNoSessionIdentity(t, login); len(body) != 2 || string(body["authenticated"]) != "true" || string(body["required"]) != "true" {
		t.Fatalf("POST contract changed: %s", login.Body.String())
	}
	cookies := login.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteStrictMode || cookies[0].Path != "/api/v1" {
		t.Fatal("login cookie contract changed")
	}
	cookie := cookies[0]
	first := request(s, http.MethodGet, "/api/v1/auth/session", nil, cookie)
	generation := requireSessionIdentity(t, first, true)
	if body := identityResponse(t, first); len(body) != 5 || string(body["required"]) != "true" {
		t.Fatalf("token GET contract changed: %s", first.Body.String())
	}
	deniedHost := httptest.NewRequest(http.MethodGet, "http://attacker.example/api/v1/auth/session", nil)
	deniedHost.AddCookie(cookie)
	denied := httptest.NewRecorder()
	s.ServeHTTP(denied, deniedHost)
	assertStatus(t, denied, http.StatusForbidden)
	requireNoSessionIdentity(t, denied)
	health := request(s, http.MethodGet, "/api/v1/health", nil, cookie)
	assertStatus(t, health, http.StatusOK)
	requireNoSessionIdentity(t, health)
	logout := request(s, http.MethodDelete, "/api/v1/auth/session", nil, cookie)
	assertStatus(t, logout, http.StatusOK)
	if body := requireNoSessionIdentity(t, logout); len(body) != 2 || string(body["authenticated"]) != "false" || string(body["required"]) != "true" {
		t.Fatalf("DELETE contract changed: %s", logout.Body.String())
	}
	requireNoSessionIdentity(t, request(s, http.MethodGet, "/api/v1/auth/session", nil, cookie))
	login = request(s, http.MethodPost, "/api/v1/auth/session", map[string]string{"token": token}, nil)
	assertStatus(t, login, http.StatusOK)
	cookie = login.Result().Cookies()[0]
	if got := requireSessionIdentity(t, request(s, http.MethodGet, "/api/v1/auth/session", nil, cookie), true); got != generation {
		t.Fatal("login/logout rotated database generation")
	}
	s.auth.mu.Lock()
	s.auth.values[sha256.Sum256([]byte(cookie.Value))] = time.Now().Add(-time.Minute)
	s.auth.mu.Unlock()
	requireNoSessionIdentity(t, request(s, http.MethodGet, "/api/v1/auth/session", nil, cookie))
}

func TestDataIdentityGatewayAdminAndDeniedSessions(t *testing.T) {
	s := gatewayServer(t)
	first := gatewayRequest(s, http.MethodGet, "/app/melora/api/v1/auth/session", "true", "", true)
	generation := requireSessionIdentity(t, first, true)
	body := identityResponse(t, first)
	if len(body) != 6 || string(body["required"]) != "false" || string(body["authMode"]) != `"fnos"` || string(body["username"]) != `"fnOS Admin"` || len(body["csrfToken"]) <= 2 {
		t.Fatalf("gateway response fields changed: %s", first.Body.String())
	}
	for _, test := range []struct {
		name   string
		role   string
		socket bool
		denied int
	}{
		{"missing-identity", "", true, http.StatusUnauthorized},
		{"non-admin", "false", true, http.StatusForbidden},
		{"malformed-role", "TRUE", true, http.StatusUnauthorized},
		{"forged-tcp-headers", "true", false, http.StatusUnauthorized},
	} {
		t.Run(test.name, func(t *testing.T) {
			w := gatewayRequest(s, http.MethodGet, "/app/melora/api/v1/auth/session", test.role, "", test.socket)
			assertStatus(t, w, http.StatusOK)
			body := requireNoSessionIdentity(t, w)
			if len(body) != 5 || string(body["authenticated"]) != "false" || string(body["required"]) != "false" || string(body["authMode"]) != `"fnos"` || string(body["csrfToken"]) != `""` {
				t.Fatalf("gateway denied-session contract changed: %s", w.Body.String())
			}
			w = gatewayRequest(s, http.MethodGet, "/app/melora/api/v1/settings", test.role, "", test.socket)
			assertStatus(t, w, test.denied)
			requireNoSessionIdentity(t, w)
		})
	}
	for _, method := range []string{http.MethodPost, http.MethodDelete} {
		w := gatewayRequest(s, method, "/app/melora/api/v1/auth/session", "true", "", true)
		assertStatus(t, w, http.StatusMethodNotAllowed)
		requireNoSessionIdentity(t, w)
	}
	if got := requireSessionIdentity(t, gatewayRequest(s, http.MethodGet, "/app/melora/api/v1/auth/session", "true", "", true), true); got != generation {
		t.Fatal("gateway auth changes rotated database generation")
	}
}

func TestDataIdentityPreservesGatewayCSRFBootstrap(t *testing.T) {
	f := newCSRFTestFixture(t)
	r := csrfTestRequest(f, http.MethodGet, "/api/v1/auth/session", nil)
	r.Header.Set("X-Melora-Origin", csrfTestExternalOrigin)
	r.Header.Set("Sec-Fetch-Site", "same-origin")
	w := csrfTestServe(f, r)
	requireSessionIdentity(t, w, true)
	session := csrfTestReadSession(t, w)
	if !session.Authenticated || session.AuthMode != "fnos" || session.Token == "" {
		t.Fatal("data identity addition broke CSRF bootstrap")
	}
	r = csrfTestRequest(f, http.MethodDelete, "/api/v1/library/history", nil)
	r.Header.Set("Origin", csrfTestExternalOrigin)
	r.Header.Set(csrfHeader, session.Token)
	assertStatus(t, csrfTestServe(f, r), http.StatusOK)
	r.Header.Set(csrfHeader, "forged")
	w = csrfTestServe(f, r)
	assertStatus(t, w, http.StatusForbidden)
	requireNoSessionIdentity(t, w)
}

func TestDataIdentityLegacySessionKeepsResetLegacyFalse(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	if _, err := raw.Exec("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL); INSERT INTO settings VALUES(1,'{\"concurrency\":2}'); PRAGMA user_version=1;"); err != nil {
		t.Fatal(err)
	}
	if err := raw.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s, _, _ := setup(t, "")
	s.store = db
	generation := requireSessionIdentity(t, request(s, http.MethodGet, "/api/v1/auth/session", nil, nil), false)
	s.cfg.GatewayAuth = "fnos-admin"
	s.cfg.SocketPath = "/tmp/melora-identity-fixture.sock" // 仅作为 httptest 身份上下文，不绑定 Socket。
	s.cfg.BasePath = "/app/melora"
	if got := requireSessionIdentity(t, gatewayRequest(s, http.MethodGet, "/app/melora/api/v1/auth/session", "true", "", true), false); got != generation {
		t.Fatal("auth mode changed legacy generation")
	}
}

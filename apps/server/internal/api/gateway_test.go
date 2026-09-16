package api

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func gatewayServer(t *testing.T) *Server {
	t.Helper()
	s, _, music := setup(t, "")
	s.cfg.SocketPath = filepath.Join(filepath.Dir(music), "app.sock")
	s.cfg.BasePath = "/app/melora"
	s.cfg.GatewayAuth = "fnos-admin"
	// 长测试名可能超过 Unix 地址上限；这些单位测试不绑定实际 Socket。
	s.cfg.SocketPath = "/tmp/melora-unit-gateway.sock"
	if err := os.WriteFile(filepath.Join(s.cfg.WebDir, "index.html"), []byte(`<!doctype html><html><head><base href="/" data-melora-base /></head><body>Melora</body></html>`), 0600); err != nil {
		t.Fatal(err)
	}
	return s
}
func gatewayRequest(s *Server, method, path, role, origin string, socket bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, "http://192.168.88.2:5666"+path, nil)
	if socket {
		r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: s.cfg.SocketPath, Net: "unix"}))
	}
	if role != "" {
		r.Header.Set("X-Trim-Userid", "1000")
		r.Header.Set("X-Trim-Username", "fnOS Admin")
		r.Header.Set("X-Trim-Isadmin", role)
	}
	if origin != "" {
		r.Header.Set("Origin", origin)
	}
	// 常规测试通过公开会话协议等价的签名，不把恶意origin签成合法来源。
	if method != "GET" && method != "HEAD" && method != "OPTIONS" {
		bound := "http://192.168.88.2:5666"
		if origin == "https://192.168.88.2:5666" {
			bound = origin
		}
		r.Header.Set(csrfHeader, s.csrfToken("1000", bound, time.Now().Add(csrfLifetime)))
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}
func TestGatewayAdminIdentityAndTCPForgery(t *testing.T) {
	s := gatewayServer(t)
	assertStatus(t, gatewayRequest(s, "GET", "/app/melora/api/v1/settings", "true", "", false), 401)
	assertStatus(t, gatewayRequest(s, "GET", "/app/melora/api/v1/settings", "", "", true), 401)
	assertStatus(t, gatewayRequest(s, "GET", "/app/melora/api/v1/settings", "false", "", true), 403)
	assertStatus(t, gatewayRequest(s, "GET", "/app/melora/api/v1/settings", "TRUE", "", true), 401)
	assertStatus(t, gatewayRequest(s, "GET", "/app/melora/api/v1/settings", "true", "", true), 200)
	w := gatewayRequest(s, "GET", "/app/melora/api/v1/auth/session", "true", "", true)
	assertStatus(t, w, 200)
	var session map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &session); err != nil {
		t.Fatal(err)
	}
	if session["authenticated"] != true || session["required"] != false || session["authMode"] != "fnos" {
		t.Fatal(session)
	}
	assertStatus(t, gatewayRequest(s, "POST", "/app/melora/api/v1/auth/session", "true", "", true), 405)
}
func TestGatewayPrefixFrameAndStaticBase(t *testing.T) {
	s := gatewayServer(t)
	for _, path := range []string{"/app/melora/", "/app/melora/now-playing", "/app/melora/playlists/demo:coast"} {
		w := gatewayRequest(s, "GET", path, "true", "", true)
		assertStatus(t, w, 200)
		if !strings.Contains(w.Body.String(), `<base href="/app/melora/" data-melora-base />`) {
			t.Fatal("missing runtime base", w.Body.String())
		}
		if w.Header().Get("X-Frame-Options") != "SAMEORIGIN" || !strings.Contains(w.Header().Get("Content-Security-Policy"), "frame-ancestors 'self'") {
			t.Fatal("fnOS iframe blocked")
		}
	}
	for _, path := range []string{"/api/v1/settings", "/app/melora-other/api/v1/settings", "/app/melora/../api/v1/settings", "/app/melora//api/v1/settings"} {
		assertStatus(t, gatewayRequest(s, "GET", path, "true", "", true), 404)
	}
	w := gatewayRequest(s, "GET", "/app/melora", "true", "", true)
	assertStatus(t, w, 308)
	if w.Header().Get("Location") != "/app/melora/" {
		t.Fatal("canonical redirect lost prefix")
	}
	assertStatus(t, gatewayRequest(s, "GET", "/app/melora/health", "", "", true), 200)
}
func TestGatewayHTTPSOriginAndSpoofedForwardedHeaders(t *testing.T) {
	s := gatewayServer(t)
	for _, origin := range []string{"http://192.168.88.2:5666", "https://192.168.88.2:5666"} {
		assertStatus(t, gatewayRequest(s, "DELETE", "/app/melora/api/v1/library/history", "true", origin, true), 200)
	}
	for _, origin := range []string{"https://evil.example", "http://192.168.88.2:3780", "null", "https://192.168.88.2:5666/", "ftp://192.168.88.2:5666"} {
		assertStatus(t, gatewayRequest(s, "DELETE", "/app/melora/api/v1/library/history", "true", origin, true), 403)
	}
	r := httptest.NewRequest("DELETE", "http://192.168.88.2:5666/app/melora/api/v1/library/history", nil)
	r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: s.cfg.SocketPath, Net: "unix"}))
	r.Header.Set("X-Trim-Userid", "1000")
	r.Header.Set("X-Trim-Username", "Admin")
	r.Header.Set("X-Trim-Isadmin", "true")
	r.Header.Set("Origin", "https://evil.example")
	r.Header.Set("X-Forwarded-Host", "evil.example")
	r.Header.Set("X-Forwarded-Proto", "https")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	assertStatus(t, w, 403)
	r.Header.Set("Origin", "http://192.168.88.2:5666")
	r.Header.Add("X-Trim-Userid", "2000")
	w = httptest.NewRecorder()
	s.ServeHTTP(w, r)
	assertStatus(t, w, 401)
}

func TestEntryIsNeverAStale304AfterPackageUpgrade(t *testing.T) {
	s, _, _ := setup(t, "")
	r := httptest.NewRequest("GET", "http://127.0.0.1:3780/", nil)
	r.Header.Set("If-Modified-Since", "Fri, 01 Jan 2100 00:00:00 GMT")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	assertStatus(t, w, 200)
	if w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Last-Modified") != "" {
		t.Fatal("HTML may stay stale across fixed-mtime FPK upgrades")
	}
}

func TestGatewaySessionReportsRevocationWithoutExposingBusinessData(t *testing.T) {
	s := gatewayServer(t)
	for _, role := range []string{"", "false"} {
		w := gatewayRequest(s, "GET", "/app/melora/api/v1/auth/session", role, "", true)
		assertStatus(t, w, 200)
		var body map[string]any
		json.Unmarshal(w.Body.Bytes(), &body)
		if body["authenticated"] != false || body["authMode"] != "fnos" {
			t.Fatal(body)
		}
		denied := gatewayRequest(s, "GET", "/app/melora/api/v1/library/history", role, "", true)
		if denied.Code != 401 && denied.Code != 403 {
			t.Fatal("non-admin read private history")
		}
	}
}

package api

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"melora/internal/config"
)

func mustTrustedProxies(t *testing.T, raw string) []*net.IPNet {
	t.Helper()
	nets, err := config.ParseTrustedProxies(raw)
	if err != nil {
		t.Fatal(err)
	}
	return nets
}

func TestPasswordLoginVerifiesUsernameAndPassword(t *testing.T) {
	s := &Server{cfg: config.Config{DeployMode: config.DeployModeCloud}, auth: newSessions("", "admin", "correct-horse-battery", nil)}
	if s.auth.loginMethod() != "password" {
		t.Fatalf("login method %q; want password", s.auth.loginMethod())
	}
	post := func(body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/api/v1/auth/session", strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.RemoteAddr = "192.0.2.10:1234"
		w := httptest.NewRecorder()
		s.session(w, r)
		return w
	}
	// 密码模式下空的 token 不允许绕过；用户名和密码都必须匹配。
	for _, body := range []string{
		`{"username":"admin","password":"wrong"}`,
		`{"username":"root","password":"correct-horse-battery"}`,
		`{"token":""}`,
		`{}`,
	} {
		if w := post(body); w.Code != 401 {
			t.Fatalf("body %s: status %d; want 401", body, w.Code)
		}
	}
	w := post(`{"username":"admin","password":"correct-horse-battery"}`)
	if w.Code != 200 {
		t.Fatalf("valid credentials rejected: %d %s", w.Code, w.Body.String())
	}
	cookies := w.Result().Cookies()
	if len(cookies) != 1 || cookies[0].Name != sessionCookie || !cookies[0].HttpOnly {
		t.Fatalf("session cookie not issued: %v", cookies)
	}
	if !strings.Contains(w.Body.String(), `"required":true`) {
		t.Fatalf("session response missing required flag: %s", w.Body.String())
	}
}

// 反代终止 TLS 时必须让 Origin 的 https 与后端明文链路对齐，否则所有写操作 403。
func TestProxyHTTPSOriginAcceptedThroughMiddleware(t *testing.T) {
	const token = "test-only-strong-authentication-token-1234567890"
	s, _, _ := setup(t, token)
	s.auth.trusted = mustTrustedProxies(t, "172.17.0.1/32")
	login := func(remote, origin, proto string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "http://127.0.0.1:3780/api/v1/auth/session", strings.NewReader(`{"token":"`+token+`"}`))
		r.Header.Set("Content-Type", "application/json")
		r.RemoteAddr = remote
		r.Header.Set("Origin", origin)
		if proto != "" {
			r.Header.Set("X-Forwarded-Proto", proto)
		}
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		return w
	}
	w := login("172.17.0.1:40000", "https://127.0.0.1:3780", "https")
	assertStatus(t, w, http.StatusOK)
	cookies := w.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].Secure {
		t.Fatalf("proxied https cookie: %v", cookies)
	}
	if spoofed := login("203.0.113.9:40000", "https://127.0.0.1:3780", "https"); spoofed.Code != http.StatusForbidden {
		t.Fatalf("spoofed X-Forwarded-Proto status %d; want 403", spoofed.Code)
	}
	if downgraded := login("172.17.0.1:40000", "https://127.0.0.1:3780", ""); downgraded.Code != http.StatusForbidden {
		t.Fatalf("reverse proxy without https hint status %d; want 403", downgraded.Code)
	}
	if plain := login("127.0.0.1:40000", "http://127.0.0.1:3780", ""); plain.Code != http.StatusOK {
		t.Fatalf("plain loopback status %d; want 200", plain.Code)
	}
}

func TestLoopbackReverseProxyAcceptsExternalHostWithAuthentication(t *testing.T) {
	const token = "test-only-strong-authentication-token-1234567890"
	s, _, _ := setup(t, token)
	s.auth.trusted = mustTrustedProxies(t, "127.0.0.1/32")

	login := func(remote string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, "http://music.example.test/api/v1/auth/session", strings.NewReader(`{"token":"`+token+`"}`))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", "https://music.example.test")
		r.Header.Set("X-Forwarded-Proto", "https")
		r.RemoteAddr = remote
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		return w
	}

	trusted := login("127.0.0.1:45678")
	assertStatus(t, trusted, http.StatusOK)
	cookies := trusted.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].Secure {
		t.Fatalf("trusted loopback proxy cookie: %v", cookies)
	}
	if untrusted := login("192.0.2.15:45678"); untrusted.Code != http.StatusForbidden {
		t.Fatalf("untrusted external Host status %d; want 403", untrusted.Code)
	}

	anonymous := &Server{cfg: config.Config{Addr: "127.0.0.1:3780"}, auth: newSessions("", "admin", "", mustTrustedProxies(t, "127.0.0.1/32"))}
	r := httptest.NewRequest(http.MethodGet, "http://music.example.test/", nil)
	r.RemoteAddr = "127.0.0.1:45678"
	if anonymous.validHost(r) {
		t.Fatal("trusted proxy exposed an unauthenticated loopback service to an external Host")
	}
}

func TestProxiedHTTPSSetsSecureCookie(t *testing.T) {
	s := &Server{auth: newSessions("", "admin", "correct-horse-battery", mustTrustedProxies(t, "172.17.0.1/32,127.0.0.1/32"))}
	login := func(remote, proto string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/api/v1/auth/session", strings.NewReader(`{"username":"admin","password":"correct-horse-battery"}`))
		r.Header.Set("Content-Type", "application/json")
		r.RemoteAddr = remote
		if proto != "" {
			r.Header.Set("X-Forwarded-Proto", proto)
		}
		w := httptest.NewRecorder()
		s.session(w, r)
		return w
	}
	for _, tc := range []struct {
		name, remote, proto string
		secure              bool
	}{
		{"内网反代终结 TLS", "172.17.0.1:45678", "https", true},
		{"本机反代终结 TLS", "127.0.0.1:45678", "HTTPS", true},
		{"未配置的内网来源伪造头不生效", "172.17.0.2:45678", "https", false},
		{"公网直连伪造头不生效", "203.0.113.5:45678", "https", false},
		{"明文反代不带 Secure", "172.17.0.1:45678", "http", false},
	} {
		w := login(tc.remote, tc.proto)
		if w.Code != 200 {
			t.Fatalf("%s: status %d %s", tc.name, w.Code, w.Body.String())
		}
		cookies := w.Result().Cookies()
		if len(cookies) != 1 || cookies[0].Secure != tc.secure {
			t.Fatalf("%s: cookie %v; want secure=%v", tc.name, cookies, tc.secure)
		}
	}
}

func TestTokenLoginStillWorksWhenOnlyTokenConfigured(t *testing.T) {
	s := &Server{auth: newSessions("test-only-strong-authentication-token", "admin", "", nil)}
	if s.auth.loginMethod() != "token" {
		t.Fatalf("login method %q; want token", s.auth.loginMethod())
	}
	r := httptest.NewRequest("POST", "/api/v1/auth/session", strings.NewReader(`{"token":"test-only-strong-authentication-token"}`))
	r.Header.Set("Content-Type", "application/json")
	r.RemoteAddr = "192.0.2.11:1234"
	w := httptest.NewRecorder()
	s.session(w, r)
	if w.Code != 200 {
		t.Fatalf("valid token rejected: %d %s", w.Code, w.Body.String())
	}
}

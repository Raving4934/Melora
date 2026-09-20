package api

import (
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"melora/internal/config"
)

func limiterRequest(remoteAddr, forwardedFor string) *http.Request {
	r := httptest.NewRequest("POST", "http://127.0.0.1:3780/api/v1/auth/session", nil)
	r.RemoteAddr = remoteAddr
	if forwardedFor != "" {
		r.Header.Set("X-Forwarded-For", forwardedFor)
	}
	return r
}

func TestLoginLimiterUsesRemoteIPAndDoesNotTrustForwardedFor(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	s := newSessions("test-only-strong-authentication-token", "admin", "", nil)
	s.now = func() time.Time { return now }
	s.global = loginBucket{tokens: loginGlobalBurst, updated: now}
	for i := 0; i < loginClientBurst; i++ {
		if !s.allowLogin(limiterRequest("192.0.2.10:1234", fmt.Sprintf("198.51.100.%d", i+1))) {
			t.Fatalf("attempt %d unexpectedly rejected", i+1)
		}
	}
	if s.allowLogin(limiterRequest("192.0.2.10:4321", "203.0.113.99")) {
		t.Fatal("X-Forwarded-For bypassed the RemoteAddr client bucket")
	}
	if !s.allowLogin(limiterRequest("192.0.2.11:1234", "192.0.2.10")) {
		t.Fatal("one client exhausted another client's bucket")
	}
	now = now.Add(loginClientRefillInterval)
	if !s.allowLogin(limiterRequest("192.0.2.10:1234", "203.0.113.100")) {
		t.Fatal("client bucket did not refill")
	}
}

// 只有显式配置的可信代理才允许转发头决定限流键，避免共享桶与伪造绕过。
func TestLoginLimiterTrustsForwardedIPOnlyForConfiguredProxy(t *testing.T) {
	_, proxyNet, err := net.ParseCIDR("172.17.0.0/16")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1_800_000_000, 0)
	s := newSessions("test-only-strong-authentication-token", "admin", "", []*net.IPNet{proxyNet})
	s.now = func() time.Time { return now }
	s.global = loginBucket{tokens: loginGlobalBurst, updated: now}
	for i := 0; i < loginClientBurst; i++ {
		if !s.allowLogin(limiterRequest("172.17.0.1:1234", "198.51.100.7")) {
			t.Fatalf("forwarded client attempt %d rejected", i+1)
		}
	}
	if s.allowLogin(limiterRequest("172.17.0.1:1234", "198.51.100.7")) {
		t.Fatal("exhausted forwarded client bucket not enforced")
	}
	if !s.allowLogin(limiterRequest("172.17.0.1:1234", "198.51.100.8")) {
		t.Fatal("one forwarded client exhausted another client's bucket")
	}

	// 未配置可信代理时，公网对端轮换 X-Forwarded-For 不能换桶。
	direct := newSessions("test-only-strong-authentication-token", "admin", "", nil)
	direct.now = func() time.Time { return now }
	direct.global = loginBucket{tokens: loginGlobalBurst, updated: now}
	for i := 0; i < loginClientBurst; i++ {
		if !direct.allowLogin(limiterRequest("203.0.113.5:1234", fmt.Sprintf("198.51.100.%d", i+10))) {
			t.Fatalf("direct attempt %d rejected", i+1)
		}
	}
	if direct.allowLogin(limiterRequest("203.0.113.5:1234", "198.51.100.200")) {
		t.Fatal("untrusted peer rotated its bucket via X-Forwarded-For")
	}
}

func TestLoginLimiterHasGlobalFallback(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	s := newSessions("test-only-strong-authentication-token", "admin", "", nil)
	s.now = func() time.Time { return now }
	s.global = loginBucket{tokens: loginGlobalBurst, updated: now}
	for i := 0; i < loginGlobalBurst; i++ {
		remote := fmt.Sprintf("198.51.%d.%d:1234", i/250, i%250+1)
		if !s.allowLogin(limiterRequest(remote, "")) {
			t.Fatalf("global attempt %d unexpectedly rejected", i+1)
		}
	}
	if s.allowLogin(limiterRequest("203.0.113.1:1234", "")) {
		t.Fatal("global fallback bucket did not cap distributed attempts")
	}
	now = now.Add(loginGlobalRefillInterval)
	if !s.allowLogin(limiterRequest("203.0.113.2:1234", "")) {
		t.Fatal("global fallback bucket did not refill")
	}
}

func TestLoginLimiterClientTableIsBoundedTTLAndLRU(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	s := newSessions("test-only-strong-authentication-token", "admin", "", nil)
	s.now = func() time.Time { return now }
	s.clients = make(map[string]*loginClient, maxLoginClients)
	for i := 0; i < maxLoginClients; i++ {
		key := fmt.Sprintf("client-%04d", i)
		s.clients[key] = &loginClient{bucket: loginBucket{tokens: 1, updated: now}, lastSeen: now}
	}
	s.clients["client-0000"].lastSeen = now.Add(-time.Minute)
	s.allowLogin(limiterRequest("192.0.2.1:1234", ""))
	if len(s.clients) != maxLoginClients {
		t.Fatalf("client table size %d; want %d", len(s.clients), maxLoginClients)
	}
	if _, ok := s.clients["client-0000"]; ok {
		t.Fatal("least recently used client was not evicted")
	}
	s.clients["expired"] = &loginClient{bucket: loginBucket{tokens: 1, updated: now}, lastSeen: now.Add(-loginClientTTL)}
	s.allowLogin(limiterRequest("192.0.2.2:1234", ""))
	if _, ok := s.clients["expired"]; ok {
		t.Fatal("expired client bucket was not removed")
	}
	if len(s.clients) > maxLoginClients {
		t.Fatalf("client table exceeded bound: %d", len(s.clients))
	}
}

func TestGatewaySessionDoesNotConsumeStandaloneLoginLimiter(t *testing.T) {
	s := &Server{cfg: config.Config{GatewayAuth: "fnos-admin"}, auth: newSessions("test-only-strong-authentication-token", "admin", "", nil)}
	before := s.auth.global.tokens
	for i := 0; i < loginGlobalBurst+1; i++ {
		r := limiterRequest(fmt.Sprintf("192.0.2.%d:1234", i%250+1), "")
		w := httptest.NewRecorder()
		s.session(w, r)
		if w.Code != 405 {
			t.Fatalf("gateway POST status %d; want 405", w.Code)
		}
	}
	if s.auth.global.tokens != before || len(s.auth.clients) != 0 {
		t.Fatal("gateway mode consumed standalone login limiter state")
	}
}

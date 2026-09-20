package api

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"melora/internal/config"
)

const sessionCookie = "melora_session"
const sessionLifetime = 12 * time.Hour

const (
	loginClientBurst          = 10
	loginGlobalBurst          = 100
	loginClientRefillInterval = 6 * time.Second
	loginGlobalRefillInterval = time.Second
	loginClientTTL            = 15 * time.Minute
	maxLoginClients           = 1024
)

type loginBucket struct {
	tokens  float64
	updated time.Time
}

func (b *loginBucket) refill(now time.Time, capacity float64, interval time.Duration) {
	if now.After(b.updated) {
		b.tokens = min(capacity, b.tokens+now.Sub(b.updated).Seconds()/interval.Seconds())
		b.updated = now
	}
}

type loginClient struct {
	bucket   loginBucket
	lastSeen time.Time
}

type sessions struct {
	required         bool
	tokenRequired    bool
	tokenHash        [32]byte
	passwordRequired bool
	passwordHash     [32]byte
	adminUser        string
	trusted          []*net.IPNet
	mu               sync.Mutex
	values           map[[32]byte]time.Time
	global           loginBucket
	clients          map[string]*loginClient
	now              func() time.Time
}

func newSessions(token, adminUser, password string, trusted []*net.IPNet) *sessions {
	now := time.Now()
	return &sessions{
		required:         token != "" || password != "",
		tokenRequired:    token != "",
		tokenHash:        sha256.Sum256([]byte(token)),
		passwordRequired: password != "",
		passwordHash:     sha256.Sum256([]byte(password)),
		adminUser:        adminUser,
		trusted:          trusted,
		values:           make(map[[32]byte]time.Time),
		global:           loginBucket{tokens: loginGlobalBurst, updated: now},
		clients:          make(map[string]*loginClient),
		now:              time.Now,
	}
}
func (s *sessions) loginMethod() string {
	if s.passwordRequired {
		return "password"
	}
	return "token"
}
func (s *sessions) authenticated(r *http.Request) bool {
	if !s.required {
		return true
	}
	cookie, err := r.Cookie(sessionCookie)
	if err != nil || len(cookie.Value) > 128 {
		return false
	}
	hash := sha256.Sum256([]byte(cookie.Value))
	s.mu.Lock()
	defer s.mu.Unlock()
	expiry, ok := s.values[hash]
	if ok && !time.Now().Before(expiry) {
		delete(s.values, hash)
		return false
	}
	return ok
}
func ipInAny(nets []*net.IPNet, ip net.IP) bool {
	for _, network := range nets {
		if network.Contains(ip) {
			return true
		}
		if v4 := ip.To4(); v4 != nil && network.Contains(v4) {
			return true
		}
	}
	return false
}

// clientIP 只在 TCP 对端属于显式配置的可信代理时读取转发头；
// X-Forwarded-For 从右往左取第一个非代理地址，兼容多级代理链。
func (s *sessions) clientIP(r *http.Request) net.IP {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = strings.Trim(r.RemoteAddr, "[]")
	}
	peer := net.ParseIP(host)
	if peer == nil || !ipInAny(s.trusted, peer) {
		return peer
	}
	if raw := r.Header.Get("X-Forwarded-For"); raw != "" && len(raw) <= 256 {
		parts := strings.Split(raw, ",")
		if len(parts) <= 8 {
			for i := len(parts) - 1; i >= 0; i-- {
				ip := net.ParseIP(strings.TrimSpace(parts[i]))
				if ip == nil {
					return peer
				}
				if !ipInAny(s.trusted, ip) {
					return ip
				}
			}
		}
	}
	if ip := net.ParseIP(strings.TrimSpace(r.Header.Get("X-Real-IP"))); ip != nil {
		return ip
	}
	return peer
}

func (s *sessions) loginClientKey(r *http.Request) string {
	ip := s.clientIP(r)
	if ip == nil {
		return "unknown"
	}
	return ip.String()
}

func (s *sessions) allowLogin(r *http.Request) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	s.global.refill(now, loginGlobalBurst, loginGlobalRefillInterval)
	for key, client := range s.clients {
		if !now.Before(client.lastSeen.Add(loginClientTTL)) {
			delete(s.clients, key)
		}
	}
	key := s.loginClientKey(r)
	client := s.clients[key]
	if client == nil {
		if len(s.clients) >= maxLoginClients {
			var oldestKey string
			var oldestTime time.Time
			for candidate, entry := range s.clients {
				if oldestKey == "" || entry.lastSeen.Before(oldestTime) {
					oldestKey, oldestTime = candidate, entry.lastSeen
				}
			}
			delete(s.clients, oldestKey)
		}
		client = &loginClient{bucket: loginBucket{tokens: loginClientBurst, updated: now}, lastSeen: now}
		s.clients[key] = client
	}
	client.lastSeen = now
	client.bucket.refill(now, loginClientBurst, loginClientRefillInterval)
	if client.bucket.tokens < 1 || s.global.tokens < 1 {
		return false
	}
	client.bucket.tokens--
	s.global.tokens--
	return true
}
func (s *Server) session(w http.ResponseWriter, r *http.Request) {
	if s.gatewayMode() {
		if r.Method != "GET" {
			fail(w, 405, "fnos_managed_session", "登录与退出由飞牛管理")
			return
		}
		user, valid := s.gatewayIdentity(r)
		csrf := ""
		if origin, ok := s.csrfClientOrigin(r); ok && valid && user.Admin {
			csrf = s.csrfToken(user.UID, origin, time.Now().Add(csrfLifetime))
		}
		response := map[string]any{"authenticated": valid && user.Admin, "required": false, "authMode": "fnos", "username": user.Username, "csrfToken": csrf}
		if valid && user.Admin {
			response["dataIdentity"] = s.store.DataIdentity()
		}
		writeJSON(w, 200, response)
		return
	}
	switch r.Method {
	case http.MethodGet:
		authenticated := s.authenticated(r)
		response := map[string]any{
			"authenticated": authenticated,
			"required":      s.auth.required,
			"loginMethod":   s.auth.loginMethod(),
			"deployMode":    s.cfg.DeployMode,
		}
		if authenticated {
			response["dataIdentity"] = s.store.DataIdentity()
		}
		writeJSON(w, 200, response)
	case http.MethodPost:
		if !s.auth.allowLogin(r) {
			w.Header().Set("Retry-After", "6")
			fail(w, 429, "rate_limited", "登录尝试过于频繁，请稍后重试")
			return
		}
		var body struct {
			Token    string `json:"token"`
			Username string `json:"username"`
			Password string `json:"password"`
		}
		if !decode(w, r, &body) {
			return
		}
		if s.auth.required {
			passwordAttempt := body.Username != "" || body.Password != ""
			switch {
			case s.auth.passwordRequired && passwordAttempt:
				userHash := sha256.Sum256([]byte(body.Username))
				adminHash := sha256.Sum256([]byte(s.auth.adminUser))
				passHash := sha256.Sum256([]byte(body.Password))
				userOK := subtle.ConstantTimeCompare(userHash[:], adminHash[:]) == 1
				passOK := subtle.ConstantTimeCompare(passHash[:], s.auth.passwordHash[:]) == 1
				if !userOK || !passOK {
					fail(w, 401, "invalid_credentials", "用户名或密码错误")
					return
				}
			case s.auth.tokenRequired:
				incoming := sha256.Sum256([]byte(body.Token))
				if subtle.ConstantTimeCompare(incoming[:], s.auth.tokenHash[:]) != 1 {
					fail(w, 401, "invalid_credentials", "访问令牌无效")
					return
				}
			default:
				fail(w, 401, "invalid_credentials", "请使用用户名和密码登录")
				return
			}
		}
		if s.auth.required {
			value := rand.Text()
			hash := sha256.Sum256([]byte(value))
			now := time.Now()
			s.auth.mu.Lock()
			for key, expiry := range s.auth.values {
				if !now.Before(expiry) {
					delete(s.auth.values, key)
				}
			}
			// 有界会话表：淘汰最早到期会话，避免长期运行时内存增长。
			if len(s.auth.values) >= 64 {
				var oldest [32]byte
				earliest := now.Add(2 * sessionLifetime)
				for key, expiry := range s.auth.values {
					if expiry.Before(earliest) {
						oldest = key
						earliest = expiry
					}
				}
				delete(s.auth.values, oldest)
			}
			s.auth.values[hash] = now.Add(sessionLifetime)
			s.auth.mu.Unlock()
			http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: value, Path: s.cfg.BasePath + "/api/v1", HttpOnly: true, Secure: s.proxiedHTTPS(r), SameSite: http.SameSiteStrictMode, MaxAge: int(sessionLifetime.Seconds()), Expires: now.Add(sessionLifetime)})
		}
		writeJSON(w, 200, map[string]bool{"authenticated": true, "required": s.auth.required})
	case http.MethodDelete:
		if cookie, err := r.Cookie(sessionCookie); err == nil {
			hash := sha256.Sum256([]byte(cookie.Value))
			s.auth.mu.Lock()
			delete(s.auth.values, hash)
			s.auth.mu.Unlock()
		}
		http.SetCookie(w, &http.Cookie{Name: sessionCookie, Value: "", Path: s.cfg.BasePath + "/api/v1", HttpOnly: true, Secure: s.proxiedHTTPS(r), SameSite: http.SameSiteStrictMode, MaxAge: -1, Expires: time.Unix(1, 0)})
		writeJSON(w, 200, map[string]bool{"authenticated": !s.auth.required, "required": s.auth.required})
	}
}

func remoteIP(r *http.Request) net.IP {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = strings.Trim(r.RemoteAddr, "[]")
	}
	return net.ParseIP(host)
}

func (s *sessions) trustedProxyPeer(r *http.Request) bool {
	ip := remoteIP(r)
	return ip != nil && ipInAny(s.trusted, ip)
}

// effectiveScheme 是 Cookie Secure 与 Origin 校验共用的协议判定：
// 只允许显式配置的可信代理声明 X-Forwarded-Proto=https。
func (s *Server) effectiveScheme(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	if s.auth.trustedProxyPeer(r) && strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") {
		return "https"
	}
	return "http"
}

func (s *Server) proxiedHTTPS(r *http.Request) bool {
	return s.effectiveScheme(r) == "https"
}
func requestHostname(host string) string {
	if h, _, err := net.SplitHostPort(host); err == nil {
		return strings.TrimSuffix(strings.ToLower(h), ".")
	}
	if strings.ContainsAny(host, "/\\@ \t\r\n") {
		return ""
	}
	return strings.TrimSuffix(strings.ToLower(strings.Trim(host, "[]")), ".")
}
func (s *Server) validHost(r *http.Request) bool {
	host := requestHostname(r.Host)
	if host == "" {
		return false
	}
	bind, _, _ := net.SplitHostPort(s.cfg.Addr)
	// 回环服务默认只接受回环 Host，防止 DNS rebinding。显式配置的本机反向代理
	// 可以保留公网 Host，但服务必须同时启用认证；否则错误的代理配置不能意外公开匿名实例。
	if config.IsLoopbackHost(bind) {
		return config.IsLoopbackHost(host) || (s.auth.required && s.auth.trustedProxyPeer(r))
	}
	if ip := net.ParseIP(bind); ip != nil && !ip.IsUnspecified() {
		return strings.EqualFold(host, bind)
	}
	return true // 泛监听依赖强制 token；不信任 Forwarded/X-Forwarded-*。
}
func validOriginForScheme(r *http.Request, scheme string) bool {
	if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" && site != "none" {
		return false
	}
	raw := r.Header.Get("Origin")
	if raw == "" {
		return true
	} // 同时允许不携带浏览器凭据的本机 CLI。
	origin, err := url.Parse(raw)
	if err != nil || origin.User != nil || origin.Host == "" || origin.Path != "" || origin.RawQuery != "" || origin.Fragment != "" {
		return false
	}
	return origin.Scheme == scheme && strings.EqualFold(origin.Host, r.Host)
}

// validOrigin 保留供网关测试确认后端 Host/TLS 与浏览器来源不匹配；
// standalone 实际校验必须通过 Server.validOrigin，才能读取显式可信代理配置。
func validOrigin(r *http.Request) bool {
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return validOriginForScheme(r, scheme)
}

func (s *Server) validOrigin(r *http.Request) bool {
	return validOriginForScheme(r, s.effectiveScheme(r))
}

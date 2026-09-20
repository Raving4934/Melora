package api

import (
	"net"
	"net/http"
	"strconv"
)

type gatewayUser struct {
	UID      string
	Username string
	Admin    bool
}

func (s *Server) gatewayMode() bool { return s.cfg.GatewayAuth == "fnos-admin" }
func (s *Server) gatewayIdentity(r *http.Request) (gatewayUser, bool) {
	if !s.gatewayMode() {
		return gatewayUser{}, false
	}
	local, ok := r.Context().Value(http.LocalAddrContextKey).(*net.UnixAddr)
	if !ok || local.Name != s.cfg.SocketPath {
		return gatewayUser{}, false
	}
	for _, key := range []string{"X-Trim-Userid", "X-Trim-Username", "X-Trim-Isadmin"} {
		if len(r.Header.Values(key)) != 1 {
			return gatewayUser{}, false
		}
	}
	uid := r.Header.Get("X-Trim-Userid")
	name := r.Header.Get("X-Trim-Username")
	role := r.Header.Get("X-Trim-Isadmin")
	if uid == "" || len(uid) > 10 || name == "" || len(name) > 256 || (role != "true" && role != "false") {
		return gatewayUser{}, false
	}
	for _, c := range uid {
		if c < '0' || c > '9' {
			return gatewayUser{}, false
		}
	}
	if _, err := strconv.ParseUint(uid, 10, 32); err != nil {
		return gatewayUser{}, false
	}
	return gatewayUser{UID: uid, Username: name, Admin: role == "true"}, true
}
func (s *Server) authenticated(r *http.Request) bool {
	if !s.gatewayMode() {
		return s.auth.authenticated(r)
	}
	user, ok := s.gatewayIdentity(r)
	return ok && user.Admin
}
func (s *Server) originAllowed(r *http.Request) bool {
	if !s.gatewayMode() {
		return s.validOrigin(r)
	}
	// fnOS可能改写后端Host或在网关终止TLS，不能把r.Host当作浏览器访问源。
	// 只有可信Unix Socket上的管理员身份 + 绑定该用户及页面origin的令牌可写。
	// 仍拒绝明确的跨站Fetch，不把Forwarded/客户端提示当成身份凭据。
	if len(r.Header.Values("Sec-Fetch-Site")) > 1 {
		return false
	}
	if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" && site != "none" {
		return false
	}
	user, ok := s.gatewayIdentity(r)
	return ok && user.Admin && s.validCSRF(r, user)
}

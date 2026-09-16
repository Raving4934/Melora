package api

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const csrfLifetime = 30 * time.Minute
const csrfHeader = "X-Melora-CSRF"
const clientOriginHeader = "X-Melora-Origin"

func canonicalOrigin(raw string) (string, bool) {
	if raw == "" || len(raw) > 512 || strings.ContainsAny(raw, "\r\n\t ,#") {
		return "", false
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Hostname() == "" || u.User != nil || u.Opaque != "" || u.Path != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return "", false
	}
	if strings.HasSuffix(u.Host, ":") {
		return "", false
	}
	// 方括号只允许合法 IPv6 字面量。不同 Go 补丁版本对 [not-ip]
	// 的 url.Parse 行为不同，不能把解析器的宽松结果当成浏览器 Origin。
	if strings.HasPrefix(u.Host, "[") && net.ParseIP(u.Hostname()) == nil {
		return "", false
	}
	if port := u.Port(); port != "" {
		n, err := strconv.Atoi(port)
		if err != nil || n < 1 || n > 65535 {
			return "", false
		}
	}
	// 浏览器origin不带默认端口；CLI显式填写默认端口视为同一origin。
	host := strings.ToLower(u.Host)
	if (u.Scheme == "http" && u.Port() == "80") || (u.Scheme == "https" && u.Port() == "443") {
		host = strings.TrimSuffix(host, ":"+u.Port())
	}
	return u.Scheme + "://" + host, true
}

// 此提示只用于绑定CSRF令牌，不用于身份/网关信任、路由或发起网络连接。
// 同源页面可设置自定义头；跨域页面无法在没有CORS许可时读出响应中的令牌。
func (s *Server) csrfClientOrigin(r *http.Request) (string, bool) {
	hints, origins := r.Header.Values(clientOriginHeader), r.Header.Values("Origin")
	if len(hints) > 1 || len(origins) > 1 {
		return "", false
	}
	var origin string
	if len(origins) == 1 {
		var ok bool
		origin, ok = canonicalOrigin(origins[0])
		if !ok {
			return "", false
		}
	}
	if len(hints) == 1 {
		hint, ok := canonicalOrigin(hints[0])
		if !ok || (origin != "" && origin != hint) {
			return "", false
		}
		return hint, true
	}
	if origin != "" {
		return origin, true
	}
	scheme := s.effectiveScheme(r)
	return canonicalOrigin(scheme + "://" + r.Host)
}
func (s *Server) csrfToken(uid, origin string, expires time.Time) string {
	payload := []byte(uid + "\n" + origin + "\n" + strconv.FormatInt(expires.Unix(), 10))
	mac := hmac.New(sha256.New, s.csrfSecret[:])
	_, _ = mac.Write(payload)
	return base64.RawURLEncoding.EncodeToString(payload) + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
func (s *Server) validCSRF(r *http.Request, user gatewayUser) bool {
	if len(r.Header.Values(csrfHeader)) != 1 || len(r.Header.Values("Origin")) > 1 {
		return false
	}
	raw := r.Header.Get(csrfHeader)
	if len(raw) > 1024 {
		return false
	}
	parts := strings.Split(raw, ".")
	if len(parts) != 2 {
		return false
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return false
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, s.csrfSecret[:])
	_, _ = mac.Write(payload)
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return false
	}
	fields := strings.Split(string(payload), "\n")
	if len(fields) != 3 || fields[0] != user.UID {
		return false
	}
	expiry, err := strconv.ParseInt(fields[2], 10, 64)
	now := time.Now().Unix()
	if err != nil || expiry <= now || expiry > now+int64(csrfLifetime/time.Second)+30 {
		return false
	}
	expected, valid := canonicalOrigin(fields[1])
	if !valid {
		return false
	}
	if origins := r.Header.Values("Origin"); len(origins) == 1 {
		rawOrigin := origins[0]
		actual, valid := canonicalOrigin(rawOrigin)
		return valid && actual == expected
	}
	return true // 带有效令牌的受信任Socket CLI可省略浏览器Origin。
}

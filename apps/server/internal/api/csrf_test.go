package api

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/config"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/provider"
	"melora/internal/store"
)

// 独立于 gateway_test.go 的 helper；真实 New/SQLite/源管理器，只替换 JS 执行。
type csrfTestRunner struct {
	inspections atomic.Int64
	lastBytes   atomic.Int64
}

func (r *csrfTestRunner) Inspect(_ context.Context, code string, _ lxruntime.Options) (lxruntime.Descriptor, error) {
	r.inspections.Add(1)
	r.lastBytes.Store(int64(len(code)))
	return lxruntime.Descriptor{Status: true, Sources: map[string]lxruntime.Source{"wy": {Name: "CSRF fixture", Type: "music", Actions: []string{"musicUrl"}, Qualitys: []string{"128k"}}}}, nil
}
func (*csrfTestRunner) Invoke(context.Context, string, string, string, map[string]any, lxruntime.Options) (json.RawMessage, error) {
	return nil, errors.New("CSRF tests must not resolve media")
}

type csrfTestFixture struct {
	server  *Server
	sources *lxsource.Manager
	runner  *csrfTestRunner
	root    string
}

func newCSRFTestFixture(t *testing.T) *csrfTestFixture {
	t.Helper()
	// Unix Socket 的配置路径上限为 107 字节，不把冗长子测试名嵌入 Socket 路径。
	root, err := os.MkdirTemp("", "melora-csrf-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := os.RemoveAll(root); err != nil {
			t.Error(err)
		}
	})
	for _, name := range []string{"data", "web", "run"} {
		if err := os.Mkdir(filepath.Join(root, name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	db, err := store.Open(filepath.Join(root, "data", "melora.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := db.Close(); err != nil {
			t.Error(err)
		}
	})
	cfg := config.Config{Addr: "127.0.0.1:3780", DataDir: filepath.Join(root, "data"), WebDir: filepath.Join(root, "web"), SocketPath: filepath.Join(root, "run", "backend.sock"), BasePath: "/app/melora", GatewayAuth: "fnos-admin"}
	server, err := New(cfg, db, provider.NewDemo(), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(server.Close)
	runner := &csrfTestRunner{}
	sources, err := lxsource.New(filepath.Join(root, "data", "sources"), runner)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := sources.Close(); err != nil {
			t.Error(err)
		}
	})
	server.UseLiveSources(sources, nil)
	return &csrfTestFixture{server: server, sources: sources, runner: runner, root: root}
}

const csrfTestBackendHost = "localhost:47631"
const csrfTestUID = "1000"
const csrfTestExternalOrigin = "https://nas.example.test:8443"

type csrfTestReadMeter struct {
	reader *bytes.Reader
	reads  int64
}

func (r *csrfTestReadMeter) Read(p []byte) (int, error) {
	n, err := r.reader.Read(p)
	r.reads += int64(n)
	return n, err
}
func (*csrfTestReadMeter) Close() error { return nil }

func csrfTestRequest(f *csrfTestFixture, method, path string, body []byte) *http.Request {
	r := httptest.NewRequest(method, "http://"+csrfTestBackendHost+f.server.cfg.BasePath+path, bytes.NewReader(body))
	r.Host = csrfTestBackendHost // 模拟 fnOS 将外部 Host 重写成后端 localhost:port。
	r.Header.Set("X-Trim-Userid", csrfTestUID)
	r.Header.Set("X-Trim-Username", "csrf-admin")
	r.Header.Set("X-Trim-Isadmin", "true")
	return r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: f.server.cfg.SocketPath, Net: "unix"}))
}
func csrfTestServe(f *csrfTestFixture, r *http.Request) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	f.server.ServeHTTP(w, r)
	return w
}

type csrfTestSession struct {
	Authenticated bool   `json:"authenticated"`
	AuthMode      string `json:"authMode"`
	Token         string `json:"csrfToken"`
}

func csrfTestReadSession(t *testing.T, w *httptest.ResponseRecorder) csrfTestSession {
	t.Helper()
	if w.Code != http.StatusOK {
		t.Fatalf("session status=%d body=%s", w.Code, w.Body.String())
	}
	var response csrfTestSession
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("CSRF token response may be cached")
	}
	for _, header := range []string{"Access-Control-Allow-Origin", "Access-Control-Allow-Credentials"} {
		if w.Header().Get(header) != "" {
			t.Fatalf("token endpoint exposes cross-origin response via %s", header)
		}
	}
	return response
}
func csrfTestBootstrap(t *testing.T, f *csrfTestFixture, origin string) string {
	t.Helper()
	r := csrfTestRequest(f, http.MethodGet, "/api/v1/auth/session", nil)
	r.Header.Set("X-Melora-Origin", origin)
	r.Header.Set("Sec-Fetch-Site", "same-origin")
	response := csrfTestReadSession(t, csrfTestServe(f, r))
	if !response.Authenticated || response.AuthMode != "fnos" || response.Token == "" {
		t.Fatalf("admin bootstrap did not return token: authenticated=%v authMode=%s", response.Authenticated, response.AuthMode)
	}
	return response.Token
}
func csrfTestMultipart(t *testing.T, code string) ([]byte, string) {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", "csrf-fixture.js")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(part, code); err != nil {
		t.Fatal(err)
	}
	if err := writer.WriteField("allowHTTPHosts", "[]"); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return body.Bytes(), writer.FormDataContentType()
}
func csrfTestLargeScript() string {
	return "/**\n * @name CSRF large upload fixture\n */\n" + strings.Repeat("// padding keeps upload above the old 64 KiB request limit\n", 4096)
}
func csrfTestSeed(t *testing.T, f *csrfTestFixture, label string) lxsource.Source {
	t.Helper()
	source, _, err := f.sources.Import(t.Context(), label+".js", []byte("// trusted test seed "+label), nil)
	if err != nil {
		t.Fatal(err)
	}
	return source
}
func csrfTestJSONMutation(t *testing.T, f *csrfTestFixture, id string) *http.Request {
	t.Helper()
	body, err := json.Marshal(map[string]string{"id": id})
	if err != nil {
		t.Fatal(err)
	}
	r := csrfTestRequest(f, http.MethodPut, "/api/v1/sources/active", body)
	r.Header.Set("Content-Type", "application/json")
	return r
}
func csrfTestImportMutation(t *testing.T, f *csrfTestFixture) *http.Request {
	t.Helper()
	body, mediaType := csrfTestMultipart(t, csrfTestLargeScript())
	r := csrfTestRequest(f, http.MethodPost, "/api/v1/sources/import", body)
	r.Header.Set("Content-Type", mediaType)
	return r
}
func csrfTestSignPayload(s *Server, payload string) string {
	mac := hmac.New(sha256.New, s.csrfSecret[:])
	_, _ = mac.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString([]byte(payload)) + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
func csrfTestAssertDenied(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	var response struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatalf("denial is not JSON: status=%d", w.Code)
	}
	if w.Code != status || response.Error.Code != code {
		t.Fatalf("expected %d/%s, got %d/%s", status, code, w.Code, response.Error.Code)
	}
}

func TestCSRFRewrittenHostBootstrapAndLargeJSImport(t *testing.T) {
	for _, origin := range []string{"http://192.168.50.8:5666", csrfTestExternalOrigin, "https://[fd00::1234]:9443"} {
		t.Run(origin, func(t *testing.T) {
			f := newCSRFTestFixture(t)
			token := csrfTestBootstrap(t, f, origin)
			r := csrfTestImportMutation(t, f)
			r.Header.Set("Origin", origin)
			r.Header.Set("Sec-Fetch-Site", "same-origin")
			r.Header.Set("X-Melora-CSRF", token)
			if r.TLS != nil || r.Host == strings.TrimPrefix(strings.TrimPrefix(origin, "https://"), "http://") || validOrigin(r) {
				t.Fatal("fixture does not reproduce the old backend-Host/TLS comparison failure")
			}
			if r.ContentLength <= 64<<10 || r.ContentLength > lxsource.MaxScriptBytes+(32<<10) {
				t.Fatalf("wrong large upload size: %d", r.ContentLength)
			}
			w := csrfTestServe(f, r)
			if w.Code != http.StatusCreated {
				t.Fatalf("gateway import rejected: %d %s", w.Code, w.Body.String())
			}
			var imported lxsource.Source
			if err := json.Unmarshal(w.Body.Bytes(), &imported); err != nil {
				t.Fatal(err)
			}
			if imported.Status != "ready" || imported.ID == "" || f.runner.inspections.Load() != 1 || f.runner.lastBytes.Load() != int64(len(csrfTestLargeScript())) {
				t.Fatalf("upload did not reach real source manager: status=%s inspections=%d bytes=%d", imported.Status, f.runner.inspections.Load(), f.runner.lastBytes.Load())
			}
			other := csrfTestSeed(t, f, "other")
			request := csrfTestJSONMutation(t, f, other.ID)
			request.Header.Set("Origin", origin)
			request.Header.Set("Sec-Fetch-Site", "same-origin")
			request.Header.Set("X-Melora-CSRF", token)
			if w := csrfTestServe(f, request); w.Code != http.StatusOK || f.sources.List().ActiveID != other.ID {
				t.Fatalf("JSON mutation rejected: %d %s", w.Code, w.Body.String())
			}
			// Forwarded 信息既不能提供身份，也不应把已合法绑定的请求错误重定向到另一 origin。
			request = csrfTestJSONMutation(t, f, imported.ID)
			request.Header.Set("Origin", origin)
			request.Header.Set("X-Melora-CSRF", token)
			request.Header.Set("Forwarded", `for=203.0.113.7;host=attacker.example:9999;proto=http`)
			request.Header.Set("X-Forwarded-Host", "attacker.example:9999")
			request.Header.Set("X-Forwarded-Proto", "http")
			if w := csrfTestServe(f, request); w.Code != http.StatusOK || f.sources.List().ActiveID != imported.ID {
				t.Fatalf("Forwarded changed valid token binding: %d", w.Code)
			}
		})
	}
}

func TestCSRFRejectsInvalidTokensIdentityOriginsAndFetchForJSONAndMultipart(t *testing.T) {
	f := newCSRFTestFixture(t)
	first := csrfTestSeed(t, f, "first")
	second := csrfTestSeed(t, f, "second")
	token := csrfTestBootstrap(t, f, csrfTestExternalOrigin)
	rotated := newCSRFTestFixture(t)
	otherSecretToken := csrfTestBootstrap(t, rotated, csrfTestExternalOrigin)
	if f.server.csrfSecret == rotated.server.csrfSecret {
		t.Fatal("real Server constructors reused the CSRF secret")
	}
	expired := f.server.csrfToken(csrfTestUID, csrfTestExternalOrigin, time.Now().Add(-time.Minute))
	future := f.server.csrfToken(csrfTestUID, csrfTestExternalOrigin, time.Now().Add(2*time.Hour))
	parts := strings.Split(token, ".")
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatal(err)
	}
	signature[0] ^= 0xff
	forged := parts[0] + "." + base64.RawURLEncoding.EncodeToString(signature)
	tamperedPayload := base64.RawURLEncoding.EncodeToString([]byte("1001\n"+csrfTestExternalOrigin+"\n"+strconv.FormatInt(time.Now().Add(30*time.Minute).Unix(), 10))) + "." + parts[1]
	type rejection struct {
		name   string
		status int
		code   string
		mutate func(*http.Request)
	}
	tests := []rejection{
		{"missing-token", 403, "csrf_rejected", func(r *http.Request) { r.Header.Del("X-Melora-CSRF") }},
		{"empty-token", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", "") }},
		{"forged-signature", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", forged) }},
		{"tampered-payload", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", tamperedPayload) }},
		{"expired", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", expired) }},
		{"excessive-lifetime", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", future) }},
		{"malformed-base64", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", "%.invalid") }},
		{"extra-token-fields", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", token+".extra") }},
		{"oversized-token", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", strings.Repeat("x", 1025)) }},
		{"invalid-signed-expiry", 403, "csrf_rejected", func(r *http.Request) {
			r.Header.Set("X-Melora-CSRF", csrfTestSignPayload(f.server, csrfTestUID+"\n"+csrfTestExternalOrigin+"\nnot-a-date"))
		}},
		{"other-uid", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Trim-Userid", "1001") }},
		{"changed-server-secret", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("X-Melora-CSRF", otherSecretToken) }},
		{"duplicate-csrf", 403, "csrf_rejected", func(r *http.Request) { r.Header.Add("x-melora-csrf", token) }},
		{"duplicate-origin", 403, "csrf_rejected", func(r *http.Request) { r.Header.Add("origin", csrfTestExternalOrigin) }},
		{"comma-joined-origin", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", csrfTestExternalOrigin+", https://evil.example") }},
		{"duplicate-fetch-site", 403, "csrf_rejected", func(r *http.Request) { r.Header.Add("Sec-Fetch-Site", "same-origin") }},
		{"non-admin", 403, "fnos_admin_required", func(r *http.Request) { r.Header.Set("X-Trim-Isadmin", "false") }},
		{"missing-identity", 401, "fnos_identity_required", func(r *http.Request) { r.Header.Del("X-Trim-Userid") }},
		{"duplicate-uid", 401, "fnos_identity_required", func(r *http.Request) { r.Header.Add("X-Trim-Userid", csrfTestUID) }},
		{"duplicate-username", 401, "fnos_identity_required", func(r *http.Request) { r.Header.Add("X-Trim-Username", "csrf-admin") }},
		{"duplicate-admin", 401, "fnos_identity_required", func(r *http.Request) { r.Header.Add("X-Trim-Isadmin", "true") }},
		{"tcp-spoofed-trim", 401, "fnos_identity_required", func(r *http.Request) {
			*r = *r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 47631}))
		}},
		{"wrong-unix-socket", 401, "fnos_identity_required", func(r *http.Request) {
			*r = *r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: filepath.Join(f.root, "other.sock"), Net: "unix"}))
		}},
		{"different-origin-host", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", "https://evil.example:8443") }},
		{"different-origin-port", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", "https://nas.example.test:8444") }},
		{"different-origin-scheme", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", "http://nas.example.test:8443") }},
		{"backend-origin-not-page-origin", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", "http://"+csrfTestBackendHost) }},
		{"client-hint-cannot-override-origin", 403, "csrf_rejected", func(r *http.Request) {
			r.Header.Set("Origin", "https://evil.example")
			r.Header.Set("X-Melora-Origin", csrfTestExternalOrigin)
		}},
		{"cross-site-fetch", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "cross-site") }},
		{"same-site-not-same-origin", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "same-site") }},
		{"invalid-fetch-site", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "untrusted") }},
		{"null-origin", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", "null") }},
		{"origin-with-userinfo", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", "https://user:password@nas.example.test:8443") }},
		{"origin-with-path", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", csrfTestExternalOrigin+"/app/melora") }},
		{"origin-with-query", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", csrfTestExternalOrigin+"?") }},
		{"origin-with-fragment", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", csrfTestExternalOrigin+"#fragment") }},
		{"origin-empty-fragment", 403, "csrf_rejected", func(r *http.Request) { r.Header.Set("Origin", csrfTestExternalOrigin+"#") }},
		{"explicit-empty-origin", 403, "csrf_rejected", func(r *http.Request) { r.Header["Origin"] = []string{""} }},
		{"forwarded-cannot-replace-token", 403, "csrf_rejected", func(r *http.Request) {
			r.Header.Del("X-Melora-CSRF")
			r.Header.Set("Forwarded", `for=127.0.0.1;host=nas.example.test:8443;proto=https`)
			r.Header.Set("X-Forwarded-Host", "nas.example.test:8443")
			r.Header.Set("X-Forwarded-Proto", "https")
			r.Header.Set("X-Forwarded-For", "127.0.0.1")
		}},
		{"forwarded-cannot-replace-origin", 403, "csrf_rejected", func(r *http.Request) {
			r.Header.Set("Origin", "https://evil.example")
			r.Header.Set("Forwarded", `for=127.0.0.1;host=nas.example.test:8443;proto=https`)
			r.Header.Set("X-Forwarded-Host", "nas.example.test:8443")
			r.Header.Set("X-Forwarded-Proto", "https")
		}},
	}
	for _, format := range []string{"json", "multipart"} {
		t.Run(format, func(t *testing.T) {
			for _, test := range tests {
				t.Run(test.name, func(t *testing.T) {
					// 即使某条生产回归被放行，下一条仍从同一有副作用的状态开始。
					if err := f.sources.Select(first.ID); err != nil {
						t.Fatal(err)
					}
					var request *http.Request
					if format == "json" {
						request = csrfTestJSONMutation(t, f, second.ID)
					} else {
						request = csrfTestImportMutation(t, f)
					}
					request.Header.Set("Origin", csrfTestExternalOrigin)
					request.Header.Set("Sec-Fetch-Site", "same-origin")
					request.Header.Set("X-Melora-CSRF", token)
					original, err := io.ReadAll(request.Body)
					if err != nil {
						t.Fatal(err)
					}
					_ = request.Body.Close()
					meter := &csrfTestReadMeter{reader: bytes.NewReader(original)}
					request.Body = meter
					test.mutate(request)
					count, calls := len(f.sources.List().Items), f.runner.inspections.Load()
					response := csrfTestServe(f, request)
					if meter.reads != 0 || f.runner.inspections.Load() != calls || len(f.sources.List().Items) != count || f.sources.List().ActiveID != first.ID {
						t.Errorf("rejected request reached body/source mutation: bodyBytes=%d", meter.reads)
					}
					csrfTestAssertDenied(t, response, test.status, test.code)
				})
			}
		})
	}
}

func TestCSRFBootstrapHintsDoNotAuthenticateOrTrustForwarded(t *testing.T) {
	f := newCSRFTestFixture(t)
	tests := []struct {
		name          string
		mutate        func(*http.Request)
		authenticated bool
	}{
		{"tcp-forged-identity", func(r *http.Request) {
			*r = *r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 47631}))
			r.Header.Set("Forwarded", `for=127.0.0.1;host=nas.example.test:8443;proto=https`)
			r.Header.Set("X-Forwarded-Host", "nas.example.test:8443")
			r.Header.Set("X-Forwarded-Proto", "https")
			r.Header.Set("X-Forwarded-For", "127.0.0.1")
		}, false},
		{"non-admin", func(r *http.Request) { r.Header.Set("X-Trim-Isadmin", "false") }, false},
		{"missing-identity", func(r *http.Request) { r.Header.Del("X-Trim-Userid") }, false},
		{"duplicate-identity", func(r *http.Request) { r.Header.Add("X-Trim-Userid", csrfTestUID) }, false},
		{"wrong-socket", func(r *http.Request) {
			*r = *r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: filepath.Join(f.root, "wrong.sock"), Net: "unix"}))
		}, false},
		{"duplicate-client-origin", func(r *http.Request) { r.Header.Add("X-Melora-Origin", csrfTestExternalOrigin) }, true},
		{"duplicate-browser-origin", func(r *http.Request) {
			r.Header.Add("Origin", csrfTestExternalOrigin)
			r.Header.Add("Origin", csrfTestExternalOrigin)
		}, true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			r := csrfTestRequest(f, http.MethodGet, "/api/v1/auth/session", nil)
			r.Header.Set("X-Melora-Origin", csrfTestExternalOrigin)
			test.mutate(r)
			response := csrfTestReadSession(t, csrfTestServe(f, r))
			if response.Authenticated != test.authenticated || response.Token != "" {
				t.Fatalf("hint authenticated user or minted token: authenticated=%v tokenPresent=%v", response.Authenticated, response.Token != "")
			}
		})
	}
}

func TestCSRFInvalidOriginsCannotMintTokens(t *testing.T) {
	f := newCSRFTestFixture(t)
	invalid := []string{"", "null", "file:///etc/passwd", "https://user:secret@nas.example.test:8443", "https://nas.example.test:8443/", "https://nas.example.test:8443?", "https://nas.example.test:8443#", "https://nas.example.test:65536", "https://[not-ip]:8443", csrfTestExternalOrigin + ", https://evil.example", csrfTestExternalOrigin + " ", "https://" + strings.Repeat("x", 513)}
	for _, header := range []string{"X-Melora-Origin", "Origin"} {
		t.Run(header, func(t *testing.T) {
			for _, origin := range invalid {
				t.Run(strconv.Quote(origin), func(t *testing.T) {
					r := csrfTestRequest(f, http.MethodGet, "/api/v1/auth/session", nil)
					r.Header[header] = []string{origin}
					response := csrfTestReadSession(t, csrfTestServe(f, r))
					if response.Token != "" {
						t.Fatalf("invalid %s minted a CSRF token", header)
					}
				})
			}
		})
	}
}

func TestCSRFTokenBindingLifetimeCanonicalOriginsAndTrustedCLI(t *testing.T) {
	f := newCSRFTestFixture(t)
	source := csrfTestSeed(t, f, "cli")
	started := time.Now()
	token := csrfTestBootstrap(t, f, csrfTestExternalOrigin)
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		t.Fatal("invalid minted token envelope")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		t.Fatal(err)
	}
	fields := strings.Split(string(payload), "\n")
	if len(fields) != 3 || fields[0] != csrfTestUID || fields[1] != csrfTestExternalOrigin {
		t.Fatal("token is not bound to UID and external page origin")
	}
	expiry, err := strconv.ParseInt(fields[2], 10, 64)
	if err != nil || expiry < started.Add(30*time.Minute).Unix()-1 || expiry > time.Now().Add(30*time.Minute).Unix()+1 {
		t.Fatal("bootstrap lifetime is not 30 minutes")
	}
	if csrfTestSignPayload(f.server, string(payload)) != token {
		t.Fatal("token was not signed with this Server's HMAC secret")
	}
	request := csrfTestJSONMutation(t, f, source.ID)
	request.Header.Set("X-Melora-CSRF", token)
	if w := csrfTestServe(f, request); w.Code != http.StatusOK {
		t.Fatalf("trusted Unix CLI without Origin rejected: %d", w.Code)
	}
	for _, canonical := range []struct{ hint, origin string }{{"https://NAS.EXAMPLE.TEST:443", "https://nas.example.test"}, {"http://NAS.EXAMPLE.TEST:80", "http://nas.example.test"}} {
		token := csrfTestBootstrap(t, f, canonical.hint)
		request := csrfTestJSONMutation(t, f, source.ID)
		request.Header.Set("Origin", canonical.origin)
		request.Header.Set("X-Melora-CSRF", token)
		if w := csrfTestServe(f, request); w.Code != http.StatusOK {
			t.Fatalf("equivalent canonical origin rejected: %d", w.Code)
		}
	}
	// 缺少页面提示时仍支持可信 CLI；但 Forwarded 不能成为后备 origin 来源。
	request = csrfTestRequest(f, http.MethodGet, "/api/v1/auth/session", nil)
	request.Header.Set("Forwarded", `host=evil.example;proto=https`)
	request.Header.Set("X-Forwarded-Host", "evil.example")
	request.Header.Set("X-Forwarded-Proto", "https")
	fallback := csrfTestReadSession(t, csrfTestServe(f, request))
	parts = strings.Split(fallback.Token, ".")
	if len(parts) != 2 {
		t.Fatal("trusted CLI fallback did not mint token")
	}
	payload, err = base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		t.Fatal(err)
	}
	fields = strings.Split(string(payload), "\n")
	if len(fields) != 3 || fields[1] != "http://"+csrfTestBackendHost {
		t.Fatal("Forwarded was trusted as fallback origin")
	}
}

func TestCSRFUserBindingAllowsFreshTokenForAnotherAdministrator(t *testing.T) {
	f := newCSRFTestFixture(t)
	source := csrfTestSeed(t, f, "another-admin")
	request := csrfTestRequest(f, http.MethodGet, "/api/v1/auth/session", nil)
	request.Header.Set("X-Trim-Userid", "1001")
	request.Header.Set("X-Trim-Username", "another-admin")
	request.Header.Set("X-Melora-Origin", csrfTestExternalOrigin)
	session := csrfTestReadSession(t, csrfTestServe(f, request))
	if !session.Authenticated || session.Token == "" {
		t.Fatal("another trusted administrator cannot bootstrap a token")
	}
	request = csrfTestJSONMutation(t, f, source.ID)
	request.Header.Set("X-Trim-Userid", "1001")
	request.Header.Set("X-Trim-Username", "another-admin")
	request.Header.Set("Origin", csrfTestExternalOrigin)
	request.Header.Set("X-Melora-CSRF", session.Token)
	if response := csrfTestServe(f, request); response.Code != http.StatusOK {
		t.Fatalf("user binding became a hard-coded administrator allowlist: %d", response.Code)
	}
	// 同一个新令牌也不能被先前管理员 UID 复用。
	request = csrfTestJSONMutation(t, f, source.ID)
	request.Header.Set("Origin", csrfTestExternalOrigin)
	request.Header.Set("X-Melora-CSRF", session.Token)
	csrfTestAssertDenied(t, csrfTestServe(f, request), http.StatusForbidden, "csrf_rejected")
}

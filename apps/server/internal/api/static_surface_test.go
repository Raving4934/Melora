package api

import (
	"bytes"
	"context"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"melora/internal/config"
)

// 只用合成 HTML 与临时 Web 目录，通过真实 ServeHTTP/static 读取初始文档。
// 不创建数据库、执行 JS 或监听/请求任何真实网络端口。
const v11SurfaceIndex = `<!doctype html><html lang="zh-CN" data-melora-surface="page"><head><base href="/" data-melora-base><meta name="theme-color" content="#ffffff" /><title>乐屿 fixture</title><meta name="fixture-color" content="#ffffff" /></head><body><template data-melora-surface="page">fixed fixture</template><meta name="theme-color" content="#ffffff" /><script src="assets/fixture.js"></script></body></html>`

func v11SurfaceServer(t *testing.T, gateway bool, index string) *Server {
	t.Helper()
	web := t.TempDir()
	if err := os.WriteFile(filepath.Join(web, "index.html"), []byte(index), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(web, "assets"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(web, "assets", "fixture.js"), []byte(`// data-melora-surface="page" #ffffff; fixture only`), 0600); err != nil {
		t.Fatal(err)
	}
	root, err := os.OpenRoot(web)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = root.Close() })
	s := &Server{cfg: config.Config{Addr: "127.0.0.1:18080", WebDir: web}, webRoot: root, mux: http.NewServeMux(), auth: newSessions("", "admin", "", nil), logger: slog.Default()}
	if gateway {
		s.cfg.BasePath = "/app/melora"
		s.cfg.GatewayAuth = "fnos-admin"
		s.cfg.SocketPath = filepath.Join(web, "synthetic.sock")
	}
	s.mux.HandleFunc("/", s.static)
	return s
}

func v11SurfaceRequest(s *Server, method, target string, headers map[string]string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, "http://127.0.0.1:18080"+s.cfg.BasePath+target, nil)
	if s.gatewayMode() {
		r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: s.cfg.SocketPath, Net: "unix"}))
		r.Header.Set("X-Trim-Userid", "1000")
		r.Header.Set("X-Trim-Username", "synthetic admin")
		r.Header.Set("X-Trim-Isadmin", "true")
	}
	for name, value := range headers {
		r.Header.Set(name, value)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}

func v11SurfaceExpected(index, base string, immersive bool) string {
	expected := strings.Replace(index, `<base href="/" data-melora-base>`, `<base href="`+base+`/" data-melora-base>`, 1)
	if immersive {
		expected = strings.Replace(expected, `data-melora-surface="page"`, `data-melora-surface="immersive"`, 1)
		expected = strings.Replace(expected, `<meta name="theme-color" content="#ffffff" />`, `<meta name="theme-color" content="#171a1e" />`, 1)
	}
	return expected
}

func TestV11StaticSurfaceColdHTMLAndRouteIsolation(t *testing.T) {
	for _, gateway := range []bool{false, true} {
		t.Run(map[bool]string{true: "gateway", false: "standalone"}[gateway], func(t *testing.T) {
			s := v11SurfaceServer(t, gateway, v11SurfaceIndex)
			// now-playing 放首位：第一次无 JS 请求即携带暗色提示；之后普通页面不受污染。
			routes := []string{"/audiobooks", "/audiobooks/ranks/vip", "/audiobooks/albums/book_album_123", "/now-playing", "/", "/discover", "/discover/daily", "/discover/new-tracks", "/charts", "/playlists", "/library", "/search", "/downloads", "/settings", "/player", "/index.html", "/playlists/demo:coast", "/playlist/now-playing", "/charts/now-playing", "/library/playlists/now-playing", "/now-playing"}
			for _, route := range routes {
				t.Run(route, func(t *testing.T) {
					w := v11SurfaceRequest(s, http.MethodGet, route, nil)
					assertStatus(t, w, http.StatusOK)
					want := v11SurfaceExpected(v11SurfaceIndex, s.cfg.BasePath, route == "/now-playing")
					if w.Body.String() != want {
						t.Errorf("initial HTML mismatch for %s: got %q want %q", route, w.Body.String(), want)
					}
					if w.Header().Get("Cache-Control") != "no-store" || !strings.HasPrefix(w.Header().Get("Content-Type"), "text/html") {
						t.Error("HTML cache/type policy changed")
					}
					if w.Header().Get("Content-Length") != strconv.Itoa(len(want)) {
						t.Errorf("wrong transformed byte length: %s", w.Header().Get("Content-Length"))
					}
					ancestors := "'none'"
					frame := "DENY"
					if gateway {
						ancestors = "'self'"
						frame = "SAMEORIGIN"
					}
					csp := "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; media-src https: http: blob:; object-src 'none'; base-uri 'self'; frame-ancestors " + ancestors + "; form-action 'self'"
					if w.Header().Get("Content-Security-Policy") != csp || w.Header().Get("X-Frame-Options") != frame {
						t.Error("CSP/frame policy changed")
					}
				})
			}
			original, err := os.ReadFile(filepath.Join(s.cfg.WebDir, "index.html"))
			if err != nil || string(original) != v11SurfaceIndex {
				t.Fatal("route transformation modified index on disk")
			}
		})
	}
}

func TestV11StaticSurfaceHEADAndUntrustedHints(t *testing.T) {
	for _, gateway := range []bool{false, true} {
		t.Run(map[bool]string{true: "gateway", false: "standalone"}[gateway], func(t *testing.T) {
			s := v11SurfaceServer(t, gateway, v11SurfaceIndex)
			for _, tc := range []struct {
				target    string
				immersive bool
			}{
				{"/now-playing", true},
				{"/now-playing?surface=page&theme=%23ffffff&route=/settings", true},
				{"/?surface=immersive&theme=%23171a1e&route=/now-playing", false},
				{"/settings?data-melora-surface=immersive&path=/now-playing", false},
				{"/index.html?route=/now-playing", false},
				{"/audiobooks", false},
				{"/audiobooks/ranks/vip?category=all", false},
				{"/audiobooks/albums/book_album_123", false},
			} {
				t.Run(tc.target, func(t *testing.T) {
					headers := map[string]string{"X-Melora-Surface": "immersive", "X-Original-URI": "/now-playing", "X-Forwarded-Uri": "/now-playing", "X-Forwarded-Prefix": "/now-playing", "Referer": "http://127.0.0.1:18080/now-playing", "If-Modified-Since": "Wed, 01 Jan 2099 00:00:00 GMT"}
					get := v11SurfaceRequest(s, http.MethodGet, tc.target, headers)
					head := v11SurfaceRequest(s, http.MethodHead, tc.target, headers)
					assertStatus(t, get, http.StatusOK)
					assertStatus(t, head, http.StatusOK)
					want := v11SurfaceExpected(v11SurfaceIndex, s.cfg.BasePath, tc.immersive)
					if get.Body.String() != want {
						t.Error("query/header changed route surface or HTML")
					}
					if head.Body.Len() != 0 {
						t.Error("HEAD returned body")
					}
					for _, key := range []string{"Content-Type", "Content-Length", "Cache-Control", "Content-Security-Policy", "X-Frame-Options"} {
						if head.Header().Get(key) != get.Header().Get(key) {
							t.Errorf("HEAD changed %s", key)
						}
					}
					if head.Header().Get("Content-Length") != strconv.Itoa(len(want)) {
						t.Error("HEAD did not describe transformed HTML")
					}
				})
			}
		})
	}
}

func TestV11StaticSurfacePreservesStaticWhitelistAndRouteSupport(t *testing.T) {
	for _, gateway := range []bool{false, true} {
		t.Run(map[bool]string{true: "gateway", false: "standalone"}[gateway], func(t *testing.T) {
			s := v11SurfaceServer(t, gateway, v11SurfaceIndex)
			for _, target := range []string{"/now-playing/", "/now-playing/child", "/now-playing-extra", "/Now-Playing", "/assets/missing.js", "/assets/missing.css", "/covers/missing.svg", "/missing.html", "/private.json", "/.env", "/api/v1/unknown", "/missing.js?surface=immersive&route=/now-playing"} {
				w := v11SurfaceRequest(s, http.MethodGet, target, nil)
				assertStatus(t, w, http.StatusNotFound)
				if strings.Contains(w.Body.String(), "data-melora-surface") || strings.Contains(w.Body.String(), "<!doctype html>") {
					t.Errorf("unsafe/missing path fell back to index: %s", target)
				}
			}
			asset := v11SurfaceRequest(s, http.MethodGet, "/assets/fixture.js?surface=immersive&route=/now-playing", nil)
			assertStatus(t, asset, http.StatusOK)
			if asset.Body.String() != `// data-melora-surface="page" #ffffff; fixture only` || asset.Header().Get("Cache-Control") != "public, max-age=31536000, immutable" {
				t.Fatal("static asset modified or cache changed")
			}
			w := httptest.NewRecorder()
			s.static(w, httptest.NewRequest(http.MethodPost, "http://127.0.0.1:18080/now-playing", nil))
			assertStatus(t, w, http.StatusMethodNotAllowed)
			if w.Header().Get("Allow") != "GET, HEAD" {
				t.Error("static method boundary changed")
			}
		})
	}
}

func TestV11StaticSurfaceLeavesAbsentMarkersAlone(t *testing.T) {
	// 旧 index 或格式不同的标记不做猜测式 HTML 注入，更不追加 inline JS。
	const legacy = `<!doctype html><html lang="zh-CN"><head><base href="/" data-melora-base><meta name='theme-color' content='#ffffff'></head><body data-example="page">fixture</body></html>`
	for _, gateway := range []bool{false, true} {
		s := v11SurfaceServer(t, gateway, legacy)
		w := v11SurfaceRequest(s, http.MethodGet, "/now-playing", nil)
		assertStatus(t, w, http.StatusOK)
		want := []byte(v11SurfaceExpected(legacy, s.cfg.BasePath, false))
		if !bytes.Equal(w.Body.Bytes(), want) {
			t.Fatal("absent fixed markers caused arbitrary HTML injection")
		}
	}
}

func TestStaticPrecompressedAssetsAndImmutableCache(t *testing.T) {
	s := v11SurfaceServer(t, false, v11SurfaceIndex)
	asset := filepath.Join(s.cfg.WebDir, "assets", "fixture.js")
	if err := os.WriteFile(asset+".br", []byte("synthetic-brotli"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(asset+".gz", []byte("synthetic-gzip"), 0600); err != nil {
		t.Fatal(err)
	}

	for _, tc := range []struct {
		name     string
		header   string
		encoding string
		body     string
	}{
		{name: "brotli preferred", header: "gzip, br", encoding: "br", body: "synthetic-brotli"},
		{name: "gzip fallback", header: "gzip", encoding: "gzip", body: "synthetic-gzip"},
		{name: "quality zero", header: "br;q=0, gzip;q=0", body: `// data-melora-surface="page" #ffffff; fixture only`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := v11SurfaceRequest(s, http.MethodGet, "/assets/fixture.js", map[string]string{"Accept-Encoding": tc.header})
			assertStatus(t, w, http.StatusOK)
			if got := w.Header().Get("Content-Encoding"); got != tc.encoding {
				t.Fatalf("content encoding = %q, want %q", got, tc.encoding)
			}
			if w.Body.String() != tc.body {
				t.Fatalf("body = %q, want %q", w.Body.String(), tc.body)
			}
			if !strings.Contains(w.Header().Get("Vary"), "Accept-Encoding") {
				t.Fatal("compressed asset missing Vary: Accept-Encoding")
			}
			if got := w.Header().Get("Cache-Control"); got != "public, max-age=31536000, immutable" {
				t.Fatalf("asset cache policy = %q", got)
			}
		})
	}

	w := v11SurfaceRequest(s, http.MethodGet, "/assets/fixture.js", map[string]string{
		"Accept-Encoding": "br",
		"Range":           "bytes=0-1",
	})
	if w.Code != http.StatusPartialContent || w.Header().Get("Content-Encoding") != "" || w.Body.String() != "//" {
		t.Fatalf("range request used encoded representation: status=%d encoding=%q body=%q", w.Code, w.Header().Get("Content-Encoding"), w.Body.String())
	}
	assertStatus(t, v11SurfaceRequest(s, http.MethodGet, "/assets/fixture.js.br", nil), http.StatusNotFound)
}

func TestAudiobookSPARoutesDoNotServeMissingAssets(t *testing.T) {
	for _, gateway := range []bool{false, true} {
		s := v11SurfaceServer(t, gateway, v11SurfaceIndex)
		for _, target := range []string{"/audiobooks/unknown/123", "/audiobooks/ranks/missing.js", "/audiobooks/albums/a/b", "/audiobooks/missing.js", "/audiobooks/ranks"} {
			t.Run(strconv.FormatBool(gateway)+target, func(t *testing.T) {
				response := v11SurfaceRequest(s, http.MethodGet, target, nil)
				assertStatus(t, response, http.StatusNotFound)
			})
		}
	}
}

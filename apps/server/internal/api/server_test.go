package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"melora/internal/config"
	"melora/internal/download"
	"melora/internal/model"
	"melora/internal/provider"
	"melora/internal/store"
)

func setup(t *testing.T, token string) (*Server, *store.Store, string) {
	t.Helper()
	root := t.TempDir()
	data := filepath.Join(root, "data")
	web := filepath.Join(root, "web")
	music := filepath.Join(root, "music")
	for _, dir := range []string{data, web, music} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(web, "index.html"), []byte("<!doctype html><h1>Melora</h1>"), 0600); err != nil {
		t.Fatal(err)
	}
	db, err := store.Open(filepath.Join(data, "melora.db"))
	if err != nil {
		t.Fatal(err)
	}
	demo := provider.NewDemo()
	manager, err := download.New("", 1, demo.Resolve, db.SaveDownload, nil)
	if err != nil {
		t.Fatal(err)
	}
	server, err := New(config.Config{Addr: "127.0.0.1:3780", DataDir: data, WebDir: web, DownloadRoot: music, AuthToken: token}, db, demo, manager)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { server.Close(); manager.Close(); db.Close() })
	return server, db, music
}
func request(s http.Handler, method, path string, body any, cookie *http.Cookie) *httptest.ResponseRecorder {
	var raw []byte
	if body != nil {
		raw, _ = json.Marshal(body)
	}
	r := httptest.NewRequest(method, "http://127.0.0.1:3780"+path, bytes.NewReader(raw))
	r.Header.Set("Origin", "http://127.0.0.1:3780")
	if body != nil {
		r.Header.Set("Content-Type", "application/json")
	}
	if cookie != nil {
		r.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}
func assertStatus(t *testing.T, w *httptest.ResponseRecorder, want int) {
	t.Helper()
	if w.Code != want {
		t.Fatalf("status %d want %d: %s", w.Code, want, w.Body.String())
	}
}
func TestCatalogAndDirectPlayback(t *testing.T) {
	s, _, _ := setup(t, "")
	for _, path := range []string{"/api/v1/health", "/api/v1/providers", "/api/v1/charts?source=all", "/api/v1/recommendations/daily", "/api/v1/playlists?category=all", "/api/v1/search?q=Joplin&type=track", "/api/v1/search?q=Scott&type=album", "/api/v1/search?q=Marine&type=artist", "/api/v1/search?q=海岸&type=playlist"} {
		t.Run(path, func(t *testing.T) {
			w := request(s, "GET", path, nil, nil)
			assertStatus(t, w, 200)
			if !json.Valid(w.Body.Bytes()) {
				t.Fatal("not JSON")
			}
		})
	}
	track := s.demo.Tracks()[0]
	w := request(s, "GET", "/api/v1/tracks/"+track.ID+"/play-info?quality=standard", nil, nil)
	assertStatus(t, w, 200)
	var info model.PlayInfo
	if err := json.Unmarshal(w.Body.Bytes(), &info); err != nil {
		t.Fatal(err)
	}
	if !info.Direct || info.TrackID != track.ID || !strings.HasPrefix(info.URL, "https://upload.wikimedia.org/") {
		t.Fatalf("invalid direct contract: %+v", info)
	}
	if len(w.Body.Bytes()) > 2048 {
		t.Fatal("play info should be small metadata only")
	}
	for _, path := range []string{"/stream/123", "/audio/123", "/proxy?url=https://example.org", "/api/v1/tracks/missing", "/api/v1/downloads/missing"} {
		assertStatus(t, request(s, "GET", path, nil, nil), 404)
	}
	assertStatus(t, request(s, "GET", "/api/v1/tracks/"+track.ID+"/play-info?quality=lossless", nil, nil), 400)
	assertStatus(t, request(s, "GET", "/api/v1/charts?source=qq", nil, nil), 400)
}
func TestLibraryAndSettingsPersistence(t *testing.T) {
	s, db, music := setup(t, "")
	track := s.demo.Tracks()[0]
	for i := 0; i < 2; i++ {
		assertStatus(t, request(s, "POST", "/api/v1/library/favorites/tracks/"+track.ID, nil, nil), 200)
		assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{"trackId": track.ID}, nil), 200)
	}
	favorites, err := db.FavoriteTracks(context.Background())
	if err != nil || len(favorites) != 1 {
		t.Fatalf("favorites %+v %v", favorites, err)
	}
	history, err := db.History(context.Background(), "")
	if err != nil || len(history) != 1 {
		t.Fatalf("history %+v %v", history, err)
	}
	assertStatus(t, request(s, "DELETE", "/api/v1/library/history", nil, nil), 200)
	favorites, _ = db.FavoriteTracks(context.Background())
	history, _ = db.History(context.Background(), "")
	if len(favorites) != 1 || len(history) != 0 {
		t.Fatal("history clear affected favorites")
	}
	settings := store.DefaultSettings()
	settings.DownloadRoot = music
	settings.Concurrency = 2
	assertStatus(t, request(s, "PUT", "/api/v1/settings", settings, nil), 200)
	actual, _ := db.Settings(context.Background())
	if actual.DownloadRoot != music || actual.Concurrency != 2 {
		t.Fatal("settings not persisted")
	}
	settings.DownloadRoot = filepath.Dir(music)
	assertStatus(t, request(s, "PUT", "/api/v1/settings", settings, nil), 400)
	settings.DownloadRoot = music
	settings.WriteLyrics = true
	assertStatus(t, request(s, "PUT", "/api/v1/settings", settings, nil), 200)
	settings.WriteLyrics = false
	settings.Concurrency = 4
	assertStatus(t, request(s, "PUT", "/api/v1/settings", settings, nil), 400)
	assertStatus(t, request(s, "POST", "/api/v1/storage/validate", map[string]string{"path": music}, nil), 200)
	assertStatus(t, request(s, "POST", "/api/v1/storage/validate", map[string]string{"path": filepath.Dir(music)}, nil), 400)
}
func TestAuthAndCSRF(t *testing.T) {
	token := "test-only-strong-authentication-token-1234567890"
	s, _, _ := setup(t, token)
	assertStatus(t, request(s, "GET", "/api/v1/health", nil, nil), 200)
	assertStatus(t, request(s, "GET", "/api/v1/settings", nil, nil), 401)
	assertStatus(t, request(s, "POST", "/api/v1/auth/session", map[string]string{"token": "wrong"}, nil), 401)
	login := request(s, "POST", "/api/v1/auth/session", map[string]string{"token": token}, nil)
	assertStatus(t, login, 200)
	cookies := login.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteStrictMode {
		t.Fatal("unsafe session cookie")
	}
	cookie := cookies[0]
	assertStatus(t, request(s, "GET", "/api/v1/settings", nil, cookie), 200)
	for _, origin := range []string{"https://evil.example", "null", "http://localhost:3780"} {
		r := httptest.NewRequest("DELETE", "http://127.0.0.1:3780/api/v1/library/history", nil)
		r.Header.Set("Origin", origin)
		r.AddCookie(cookie)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		assertStatus(t, w, 403)
	}
	r := httptest.NewRequest("GET", "http://attacker.example/api/v1/settings", nil)
	r.AddCookie(cookie)
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	assertStatus(t, w, 403)
	assertStatus(t, request(s, "DELETE", "/api/v1/auth/session", nil, cookie), 200)
	assertStatus(t, request(s, "GET", "/api/v1/settings", nil, cookie), 401)
}
func TestInputLimitsAndStaticBoundary(t *testing.T) {
	s, _, _ := setup(t, "")
	for _, path := range []string{"/", "/now-playing", "/discover", "/playlists/demo:coast", "/settings"} {
		w := request(s, "GET", path, nil, nil)
		assertStatus(t, w, 200)
		if !strings.Contains(w.Header().Get("Content-Type"), "text/html") {
			t.Fatal("SPA route not HTML")
		}
		if !strings.Contains(w.Header().Get("Content-Security-Policy"), "object-src 'none'") {
			t.Fatal("missing CSP")
		}
	}
	for _, path := range []string{"/missing.js", "/api/v1/unknown", "/melora.db", "/.env", "/covers/missing.svg"} {
		assertStatus(t, request(s, "GET", path, nil, nil), 404)
	}
	for _, body := range []string{`{"enabled":true,"command":"evil"}`, `{"enabled":true} {}`, `{"enabled":"yes"}`} {
		r := httptest.NewRequest("PATCH", "http://127.0.0.1:3780/api/v1/providers/demo", strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		assertStatus(t, w, 400)
	}
	assertStatus(t, request(s, "POST", "/api/v1/auth/session", map[string]string{"token": strings.Repeat("a", int(maxBodyBytes))}, nil), 413)
	assertStatus(t, request(s, "POST", "/api/v1/providers", map[string]string{"script": "not executed"}, nil), 405)
}
func TestDisabledProviderAndDownloadRoot(t *testing.T) {
	s, _, _ := setup(t, "")
	track := s.demo.Tracks()[0]
	assertStatus(t, request(s, "POST", "/api/v1/downloads", map[string]string{"trackId": track.ID, "quality": "standard"}, nil), 403)
	assertStatus(t, request(s, "PATCH", "/api/v1/providers/demo", map[string]bool{"enabled": false}, nil), 200)
	assertStatus(t, request(s, "GET", "/api/v1/tracks/"+track.ID+"/play-info", nil, nil), 409)
	w := request(s, "GET", "/api/v1/charts", nil, nil)
	assertStatus(t, w, 200)
	if strings.TrimSpace(w.Body.String()) != "[]" {
		t.Fatal("disabled charts not empty")
	}
	assertStatus(t, request(s, "PATCH", "/api/v1/providers/demo", map[string]bool{"enabled": true}, nil), 200)
	assertStatus(t, request(s, "GET", "/api/v1/charts/"+s.demo.Charts()[0].ID+"/tracks", nil, nil), 200)
}
func TestLoginRateLimit(t *testing.T) {
	s, _, _ := setup(t, "test-long-token-abcdefghijklmnopqrstuvwxyz")
	for i := 0; i < 10; i++ {
		assertStatus(t, request(s, "POST", "/api/v1/auth/session", map[string]string{"token": fmt.Sprint(i)}, nil), 401)
	}
	assertStatus(t, request(s, "POST", "/api/v1/auth/session", map[string]string{"token": "wrong"}, nil), 429)
}

func TestSSEConnectionsAreBounded(t *testing.T) {
	s, _, _ := setup(t, "")
	for i := 0; i < cap(s.eventSlots); i++ {
		s.eventSlots <- struct{}{}
	}
	defer func() {
		for len(s.eventSlots) > 0 {
			<-s.eventSlots
		}
	}()
	w := request(s, "GET", "/api/v1/downloads/events", nil, nil)
	assertStatus(t, w, 429)
	if w.Header().Get("Retry-After") != "10" {
		t.Fatal("missing SSE retry guidance")
	}
}

func TestPanicRecoveryAvoidsDoubleWrite(t *testing.T) {
	s, _, _ := setup(t, "")
	s.mux.HandleFunc("/api/v1/panic-before-write", func(http.ResponseWriter, *http.Request) {
		panic("before write")
	})
	s.mux.HandleFunc("/api/v1/panic-after-write", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
		panic("after write")
	})

	before := request(s, http.MethodGet, "/api/v1/panic-before-write", nil, nil)
	assertStatus(t, before, http.StatusInternalServerError)
	if !strings.Contains(before.Body.String(), `"code":"internal_error"`) {
		t.Fatalf("panic response must use stable error contract: %s", before.Body.String())
	}

	after := request(s, http.MethodGet, "/api/v1/panic-after-write", nil, nil)
	assertStatus(t, after, http.StatusNoContent)
	if after.Body.Len() != 0 {
		t.Fatalf("panic after committed response must not append a second payload: %q", after.Body.String())
	}
}

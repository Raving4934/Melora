package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"melora/internal/model"
	"melora/internal/store"
)

const userPlaylistsPath = "/api/v1/library/playlists"

// 主线尚未接入 routes() 时也能验证真实 ServeHTTP 中间件；接入后不重复注册。
func ensureUserPlaylistTestRoutes(s *Server) {
	_, pattern := s.mux.Handler(httptest.NewRequest(http.MethodGet, userPlaylistsPath, nil))
	if pattern != userPlaylistsPath {
		s.registerUserPlaylistRoutes()
	}
}

func setupUserPlaylists(t *testing.T, token string) (*Server, *store.Store, string) {
	t.Helper()
	s, db, music := setup(t, token)
	ensureUserPlaylistTestRoutes(s)
	return s, db, music
}

func playlistResponse(t *testing.T, w *httptest.ResponseRecorder, status int) model.UserPlaylist {
	t.Helper()
	assertStatus(t, w, status)
	var p model.UserPlaylist
	if err := json.Unmarshal(w.Body.Bytes(), &p); err != nil {
		t.Fatal(err)
	}
	if p.ID == "" || p.ProviderID != "local" || p.Title == "" || p.TrackCount != len(p.Tracks) {
		t.Fatalf("invalid detail response: %s", w.Body.String())
	}
	for _, stamp := range []string{p.CreatedAt, p.UpdatedAt} {
		if _, err := time.Parse(time.RFC3339Nano, stamp); err != nil {
			t.Fatal("invalid timestamp", stamp, err)
		}
	}
	if w.Header().Get("Cache-Control") != "no-store" || !strings.HasPrefix(w.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("missing API headers: %v", w.Header())
	}
	return p
}

func createAPIPlaylist(t *testing.T, s *Server) model.UserPlaylist {
	t.Helper()
	return playlistResponse(t, request(s, "POST", userPlaylistsPath, map[string]string{"title": "我的歌单", "description": "我的描述"}, nil), 201)
}

func playlistErrorCode(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	assertStatus(t, w, status)
	var body errorBody
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil || body.Error.Code != code || body.Error.Message == "" {
		t.Fatalf("error envelope: %s (%v)", w.Body.String(), err)
	}
}

func TestUserPlaylistAPICRUDAndIsolation(t *testing.T) {
	s, db, music := setupUserPlaylists(t, "")
	w := request(s, "GET", userPlaylistsPath, nil, nil)
	assertStatus(t, w, 200)
	if strings.TrimSpace(w.Body.String()) != "[]" {
		t.Fatal("empty collection must be []", w.Body.String())
	}
	p := createAPIPlaylist(t, s)
	path := userPlaylistsPath + "/" + p.ID
	if p.TrackCount != 0 || p.CoverURL != "" || p.CreatedAt != p.UpdatedAt {
		t.Fatalf("new playlist: %+v", p)
	}
	got := playlistResponse(t, request(s, "GET", path, nil, nil), 200)
	if !reflect.DeepEqual(got, p) {
		t.Fatal("read mutated playlist")
	}
	tracks := s.demo.Tracks()
	for _, track := range tracks[:2] {
		p = playlistResponse(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": track.ID}, nil), 200)
	}
	if p.TrackCount != 2 || !reflect.DeepEqual(p.Tracks, tracks[:2]) || p.CoverURL != tracks[0].CoverURL {
		t.Fatal("server track snapshot not retained", p)
	}
	duplicate := playlistResponse(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": tracks[0].ID}, nil), 200)
	if !reflect.DeepEqual(duplicate, p) {
		t.Fatal("duplicate changed playlist")
	}
	updated := playlistResponse(t, request(s, "PUT", path, map[string]string{"title": "  雨天  ", "description": " 放松\n午后 "}, nil), 200)
	if updated.Title != "雨天" || updated.Description != "放松\n午后" || !reflect.DeepEqual(updated.Tracks, p.Tracks) || updated.CreatedAt != p.CreatedAt || updated.UpdatedAt == p.UpdatedAt {
		t.Fatalf("metadata update: %+v", updated)
	}
	p = playlistResponse(t, request(s, "PUT", path+"/tracks", map[string]any{"trackIds": []string{tracks[1].ID, tracks[0].ID}}, nil), 200)
	if p.Tracks[0].ID != tracks[1].ID || p.CoverURL != tracks[1].CoverURL || p.CreatedAt != updated.CreatedAt || p.UpdatedAt == updated.UpdatedAt {
		t.Fatalf("reorder: %+v", p)
	}
	got = playlistResponse(t, request(s, "GET", path, nil, nil), 200)
	if !reflect.DeepEqual(got, p) || got.TrackCount != 2 {
		t.Fatal("GET detail count/snapshot mismatch")
	}
	w = request(s, "GET", userPlaylistsPath, nil, nil)
	assertStatus(t, w, 200)
	var summaries []map[string]json.RawMessage
	if err := json.Unmarshal(w.Body.Bytes(), &summaries); err != nil || len(summaries) != 1 || string(summaries[0]["trackCount"]) != "2" || summaries[0]["tracks"] != nil {
		t.Fatalf("summary contract: %s %v", w.Body.String(), err)
	}
	// 共享收藏、历史和下载记录/文件都不能被移除成员或删除自建歌单影响。
	ctx := context.Background()
	if err := db.FavoriteTrack(ctx, tracks[0], true); err != nil {
		t.Fatal(err)
	}
	if err := db.FavoritePlaylist(ctx, model.Collection{ID: "demo:favorite", Title: "收藏歌单"}, true); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, tracks[0], model.HistoryKindTrack, ""); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(music, "keep.ogg")
	if err := os.WriteFile(file, []byte("existing audio"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := db.SaveDownload(model.DownloadJob{ID: "keep", Track: tracks[0], State: "completed", TargetPath: file}); err != nil {
		t.Fatal(err)
	}
	p = playlistResponse(t, request(s, "DELETE", path+"/tracks/"+tracks[1].ID, nil, nil), 200)
	if p.TrackCount != 1 || p.CoverURL != tracks[0].CoverURL {
		t.Fatal("member removal lost count or cover")
	}
	w = request(s, "DELETE", path, nil, nil)
	assertStatus(t, w, 200)
	if strings.TrimSpace(w.Body.String()) != `{"ok":true}` {
		t.Fatal("delete response", w.Body.String())
	}
	playlistErrorCode(t, request(s, "GET", path, nil, nil), 404, "not_found")
	favorites, err := db.FavoriteTracks(ctx)
	if err != nil || len(favorites) != 1 || favorites[0].ID != tracks[0].ID {
		t.Fatal("favorite track affected", favorites, err)
	}
	collections, err := db.FavoritePlaylists(ctx)
	if err != nil || len(collections) != 1 {
		t.Fatal("favorite playlist affected", collections, err)
	}
	history, err := db.History(ctx, "")
	if err != nil || len(history) != 1 {
		t.Fatal("history affected", history, err)
	}
	jobs, err := db.Downloads(ctx)
	if err != nil || len(jobs) != 1 || jobs[0].TargetPath != file || jobs[0].State != "completed" {
		t.Fatal("download affected", jobs, err)
	}
	if raw, err := os.ReadFile(file); err != nil || string(raw) != "existing audio" {
		t.Fatal("audio affected", err)
	}
}

func TestUserPlaylistAPIInputValidation(t *testing.T) {
	s, _, _ := setupUserPlaylists(t, "")
	p := createAPIPlaylist(t, s)
	path := userPlaylistsPath + "/" + p.ID
	for _, body := range []any{
		map[string]any{}, map[string]any{"title": nil}, map[string]any{"title": " \n\t "},
		map[string]any{"title": strings.Repeat("歌", 81)},
		map[string]any{"title": "合法", "description": strings.Repeat("🎵", 301)},
		map[string]any{"title": "标题\n换行"}, map[string]any{"title": "合法", "description": "a\u0000b"},
		map[string]any{"title": 42}, map[string]any{"title": "合法", "description": []string{}},
		map[string]any{"title": "伪造", "providerId": "remote"}, map[string]any{"title": "伪造", "id": "chosen"},
		map[string]any{"title": "伪造", "tracks": []model.Track{}}, []string{"not an object"},
	} {
		assertStatus(t, request(s, "POST", userPlaylistsPath, body, nil), 400)
		assertStatus(t, request(s, "PUT", path, body, nil), 400)
	}
	got := playlistResponse(t, request(s, "GET", path, nil, nil), 200)
	if !reflect.DeepEqual(got, p) {
		t.Fatal("invalid metadata partially applied")
	}
	playlistResponse(t, request(s, "POST", userPlaylistsPath, map[string]string{"title": strings.Repeat("歌", 80), "description": strings.Repeat("🎵", 300)}, nil), 201)
	for _, raw := range []string{"null", "{", `{"title":"ok"} {}`, `{"title":"ok"}garbage`} {
		r := httptest.NewRequest("POST", "http://127.0.0.1:3780"+userPlaylistsPath, strings.NewReader(raw))
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		assertStatus(t, w, 400)
	}
	track := s.demo.Tracks()[0]
	for _, body := range []any{
		map[string]any{}, map[string]any{"trackId": nil}, map[string]any{"trackId": " \t "},
		map[string]any{"trackId": 1}, map[string]any{"track": track},
		map[string]any{"trackId": track.ID, "title": "伪造标题"},
		map[string]any{"trackId": track.ID, "coverUrl": "file:///private"},
	} {
		assertStatus(t, request(s, "POST", path+"/tracks", body, nil), 400)
	}
	playlistErrorCode(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": "https://attacker.example/audio.ogg"}, nil), 404, "not_found")
	got = playlistResponse(t, request(s, "GET", path, nil, nil), 200)
	if !reflect.DeepEqual(got, p) {
		t.Fatal("untrusted track metadata persisted")
	}
	playlistResponse(t, request(s, "PUT", path+"/tracks", map[string]any{"trackIds": []string{}}, nil), 200)
	for _, body := range []any{map[string]any{}, map[string]any{"trackIds": nil}, map[string]any{"trackIds": "x"}, map[string]any{"trackIds": []int{1}}, map[string]any{"trackIds": []string{}, "extra": true}} {
		assertStatus(t, request(s, "PUT", path+"/tracks", body, nil), 400)
	}
}

func TestUserPlaylistAPIPermutationAndNotFound(t *testing.T) {
	s, db, _ := setupUserPlaylists(t, "")
	p := createAPIPlaylist(t, s)
	path := userPlaylistsPath + "/" + p.ID
	tracks := s.demo.Tracks()
	for _, track := range tracks[:2] {
		p = playlistResponse(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": track.ID}, nil), 200)
	}
	for _, ids := range [][]string{{}, {tracks[0].ID}, {tracks[0].ID, tracks[0].ID}, {tracks[0].ID, "unknown"}, {tracks[0].ID, tracks[1].ID, "extra"}, {tracks[0].ID, ""}, make([]string, 501)} {
		playlistErrorCode(t, request(s, "PUT", path+"/tracks", map[string]any{"trackIds": ids}, nil), 400, "invalid_playlist_order")
		got := playlistResponse(t, request(s, "GET", path, nil, nil), 200)
		if !reflect.DeepEqual(got, p) {
			t.Fatal("invalid permutation mutated order or timestamp")
		}
	}
	missingPath := userPlaylistsPath + "/missing"
	for _, tc := range []struct {
		method, path string
		body         any
	}{
		{"GET", missingPath, nil}, {"PUT", missingPath, map[string]string{"title": "合法"}}, {"DELETE", missingPath, nil},
		{"POST", missingPath + "/tracks", map[string]string{"trackId": tracks[0].ID}},
		{"PUT", missingPath + "/tracks", map[string]any{"trackIds": []string{}}},
		{"DELETE", missingPath + "/tracks/" + tracks[0].ID, nil}, {"DELETE", path + "/tracks/missing", nil},
	} {
		playlistErrorCode(t, request(s, tc.method, tc.path, tc.body, nil), 404, "not_found")
	}
	for _, tc := range []struct{ method, path, allow string }{
		{"DELETE", userPlaylistsPath, "GET, POST"}, {"PATCH", path, "GET, PUT, DELETE"}, {"GET", path + "/tracks", "POST, PUT"}, {"POST", path + "/tracks/" + tracks[0].ID, "DELETE"},
	} {
		w := request(s, tc.method, tc.path, nil, nil)
		playlistErrorCode(t, w, 405, "method_not_allowed")
		if w.Header().Get("Allow") != tc.allow {
			t.Fatal("Allow header missing", w.Header())
		}
	}
	// 与既有收藏保持一致：停用音源不破坏本机歌单管理，仍只接受已知目录对象。
	if err := db.SetProviderEnabled(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	playlistResponse(t, request(s, "DELETE", path+"/tracks/"+tracks[0].ID, nil, nil), 200)
	playlistResponse(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": tracks[0].ID}, nil), 200)
}

func TestUserPlaylistAPILimits(t *testing.T) {
	s, db, _ := setupUserPlaylists(t, "")
	p := createAPIPlaylist(t, s)
	ctx := context.Background()
	for i := 1; i < store.MaxUserPlaylists; i++ {
		if _, err := db.CreateUserPlaylist(ctx, "同名歌单允许", ""); err != nil {
			t.Fatal(err)
		}
	}
	playlistErrorCode(t, request(s, "POST", userPlaylistsPath, map[string]string{"title": "超过上限"}, nil), 409, "playlist_limit")
	tracks := s.demo.Tracks()
	if _, err := db.AddUserPlaylistTrack(ctx, p.ID, tracks[0]); err != nil {
		t.Fatal(err)
	}
	// 仅在 store 中构造受信任的历史目录快照，不扩展或替换生产 Provider。
	for i := 1; i < store.MaxUserPlaylistTracks; i++ {
		track := tracks[0]
		track.ID = fmt.Sprintf("demo:legacy-%d", i)
		if _, err := db.AddUserPlaylistTrack(ctx, p.ID, track); err != nil {
			t.Fatal(err)
		}
	}
	path := userPlaylistsPath + "/" + p.ID
	p = playlistResponse(t, request(s, "GET", path, nil, nil), 200)
	if p.TrackCount != store.MaxUserPlaylistTracks {
		t.Fatal("GET detail count incorrect at limit")
	}
	playlistErrorCode(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": tracks[1].ID}, nil), 409, "playlist_track_limit")
	duplicate := playlistResponse(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": tracks[0].ID}, nil), 200)
	if !reflect.DeepEqual(duplicate, p) {
		t.Fatal("duplicate at capacity changed playlist")
	}
	// 已退出 Provider 目录的旧成员仍可删除，释放容量。
	playlistResponse(t, request(s, "DELETE", path+"/tracks/demo:legacy-1", nil, nil), 200)
	added := playlistResponse(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": tracks[1].ID}, nil), 200)
	if added.TrackCount != store.MaxUserPlaylistTracks || added.Tracks[len(added.Tracks)-1].ID != tracks[1].ID {
		t.Fatal("capacity not recovered")
	}
}

func TestUserPlaylistAPIBodyBoundariesAndDBFailure(t *testing.T) {
	s, db, _ := setupUserPlaylists(t, "")
	p := createAPIPlaylist(t, s)
	path := userPlaylistsPath + "/" + p.ID
	for _, target := range []struct{ method, path string }{{"POST", userPlaylistsPath}, {"PUT", path}, {"POST", path + "/tracks"}, {"PUT", path + "/tracks"}} {
		r := httptest.NewRequest(target.method, "http://127.0.0.1:3780"+target.path, strings.NewReader(`{}`))
		r.Header.Set("Content-Type", "text/plain")
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		playlistErrorCode(t, w, 415, "unsupported_media_type")
		for _, chunked := range []bool{false, true} {
			raw := `{"` + strings.Repeat("x", int(maxBodyBytes)) + `":null}`
			r = httptest.NewRequest(target.method, "http://127.0.0.1:3780"+target.path, strings.NewReader(raw))
			r.Header.Set("Content-Type", "application/json")
			if chunked {
				r.ContentLength = -1
			}
			w = httptest.NewRecorder()
			s.ServeHTTP(w, r)
			playlistErrorCode(t, w, 413, "body_too_large")
		}
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	for _, target := range []string{userPlaylistsPath, path} {
		playlistErrorCode(t, request(s, "GET", target, nil, nil), 500, "internal_error")
	}
}

func TestUserPlaylistAPIAuthenticationAndCSRF(t *testing.T) {
	const token = "user-playlist-test-access-token-123456789"
	s, db, _ := setupUserPlaylists(t, token)
	p, err := db.CreateUserPlaylist(context.Background(), "私有歌单", "")
	if err != nil {
		t.Fatal(err)
	}
	track := s.demo.Tracks()[0]
	path := userPlaylistsPath + "/" + p.ID
	routes := []struct {
		method, path string
		body         any
	}{
		{"GET", userPlaylistsPath, nil}, {"POST", userPlaylistsPath, map[string]string{"title": "测试"}},
		{"GET", path, nil}, {"PUT", path, map[string]string{"title": "测试"}}, {"DELETE", path, nil},
		{"POST", path + "/tracks", map[string]string{"trackId": track.ID}},
		{"PUT", path + "/tracks", map[string]any{"trackIds": []string{}}},
		{"DELETE", path + "/tracks/" + track.ID, nil},
	}
	login := request(s, "POST", "/api/v1/auth/session", map[string]string{"token": token}, nil)
	assertStatus(t, login, 200)
	cookies := login.Result().Cookies()
	if len(cookies) == 0 {
		t.Fatal("no session cookie")
	}
	cookie := cookies[0]
	for _, route := range routes {
		playlistErrorCode(t, request(s, route.method, route.path, route.body, nil), 401, "authentication_required")
		playlistErrorCode(t, request(s, route.method, route.path, route.body, &http.Cookie{Name: sessionCookie, Value: "forged"}), 401, "authentication_required")
		if route.method == "GET" {
			assertStatus(t, request(s, route.method, route.path, route.body, cookie), 200)
			continue
		}
		for _, fetchSite := range []bool{false, true} {
			raw, _ := json.Marshal(route.body)
			r := httptest.NewRequest(route.method, "http://127.0.0.1:3780"+route.path, bytes.NewReader(raw))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("Origin", "https://attacker.example")
			if fetchSite {
				r.Header.Set("Origin", "http://127.0.0.1:3780")
				r.Header.Set("Sec-Fetch-Site", "cross-site")
			}
			r.AddCookie(cookie)
			w := httptest.NewRecorder()
			s.ServeHTTP(w, r)
			playlistErrorCode(t, w, 403, "csrf_rejected")
		}
	}
	playlistResponse(t, request(s, "POST", path+"/tracks", map[string]string{"trackId": track.ID}, cookie), 200)
	playlistResponse(t, request(s, "PUT", path, map[string]string{"title": "已认证"}, cookie), 200)
	playlistResponse(t, request(s, "PUT", path+"/tracks", map[string]any{"trackIds": []string{track.ID}}, cookie), 200)
	playlistResponse(t, request(s, "DELETE", path+"/tracks/"+track.ID, nil, cookie), 200)
	assertStatus(t, request(s, "DELETE", path, nil, cookie), 200)
	playlistResponse(t, request(s, "POST", userPlaylistsPath, map[string]string{"title": "已认证创建"}, cookie), 201)
}

func TestUserPlaylistAPIGatewayAuthorizationAndCSRF(t *testing.T) {
	s := gatewayServer(t)
	ensureUserPlaylistTestRoutes(s)
	p, err := s.store.CreateUserPlaylist(context.Background(), "管理员歌单", "")
	if err != nil {
		t.Fatal(err)
	}
	path := "/app/melora" + userPlaylistsPath + "/" + p.ID
	track := s.demo.Tracks()[0]
	for _, route := range []struct{ method, path string }{
		{"GET", "/app/melora" + userPlaylistsPath}, {"POST", "/app/melora" + userPlaylistsPath},
		{"GET", path}, {"PUT", path}, {"DELETE", path},
		{"POST", path + "/tracks"}, {"PUT", path + "/tracks"}, {"DELETE", path + "/tracks/" + track.ID},
	} {
		playlistErrorCode(t, gatewayRequest(s, route.method, route.path, "", "", true), 401, "fnos_identity_required")
		playlistErrorCode(t, gatewayRequest(s, route.method, route.path, "false", "", true), 403, "fnos_admin_required")
		playlistErrorCode(t, gatewayRequest(s, route.method, route.path, "true", "", false), 401, "fnos_identity_required")
		if route.method == "GET" {
			assertStatus(t, gatewayRequest(s, route.method, route.path, "true", "", true), 200)
		} else {
			playlistErrorCode(t, gatewayRequest(s, route.method, route.path, "true", "https://attacker.example", true), 403, "csrf_rejected")
		}
	}
	// 走真实 BasePath + Unix 网关管理员身份 + HTTPS 同源写入，而非直接调用 handler。
	r := httptest.NewRequest("POST", "http://192.168.88.2:5666"+path+"/tracks", strings.NewReader(fmt.Sprintf(`{"trackId":%q}`, track.ID)))
	r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: s.cfg.SocketPath, Net: "unix"}))
	r.Header.Set("X-Trim-Userid", "1000")
	r.Header.Set("X-Trim-Username", "admin")
	r.Header.Set("X-Trim-Isadmin", "true")
	r.Header.Set("Origin", "https://192.168.88.2:5666")
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set(csrfHeader, s.csrfToken("1000", "https://192.168.88.2:5666", time.Now().Add(csrfLifetime)))
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	got := playlistResponse(t, w, 200)
	if got.TrackCount != 1 || got.Tracks[0].ID != track.ID {
		t.Fatal("gateway write failed")
	}
	playlistResponse(t, gatewayRequest(s, "DELETE", path+"/tracks/"+track.ID, "true", "https://192.168.88.2:5666", true), 200)
	assertStatus(t, gatewayRequest(s, "DELETE", path, "true", "https://192.168.88.2:5666", true), 200)
}

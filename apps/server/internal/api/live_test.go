package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
	"melora/internal/provider"
)

type sourceRunner struct {
	media string
	calls int
}

func (r *sourceRunner) Inspect(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error) {
	return lxruntime.Descriptor{Status: true, Sources: map[string]lxruntime.Source{"wy": {Name: "WY", Type: "music", Actions: []string{"musicUrl"}, Qualitys: []string{"128k", "flac"}}}}, nil
}
func (r *sourceRunner) Invoke(_ context.Context, _ string, platform, action string, info map[string]any, _ lxruntime.Options) (json.RawMessage, error) {
	r.calls++
	b, _ := json.Marshal(r.media)
	return b, nil
}

type catalogFixture struct{ fail bool }

func (f *catalogFixture) Do(r *http.Request) (*http.Response, error) {
	body := `{"code":200}`
	song := `{"id":123,"name":"真实目录测试曲目","dt":180000,"ar":[{"name":"测试歌手"}],"al":{"id":10,"name":"测试专辑"}}`
	switch r.URL.Path {
	case "/api/search/get/web":
		body = `{"code":200,"result":{"songs":[` + song + `],"songCount":21}}`
	case "/api/song/detail":
		body = `{"code":200,"songs":[` + song + `]}`
	case "/api/toplist/detail":
		body = `{"code":200,"list":[{"id":999,"name":"目录榜单","trackCount":1}]}`
	case "/api/playlist/list":
		body = `{"code":200,"playlists":[{"id":999,"name":"目录歌单","trackCount":1}]}`
	case "/api/v6/playlist/detail":
		body = `{"code":200,"playlist":{"id":999,"name":"目录歌单","tracks":[` + song + `],"trackCount":1}}`
	case "/api/v1/discovery/new/songs":
		body = `{"code":200,"data":[` + song + `]}`
	case "/api/playlist/catalogue":
		body = `{"code":200,"all":{"name":"全部"},"categories":{"0":"语种"},"sub":[{"name":"华语","category":0}]}`
	case "/api/song/lyric":
		body = `{"code":200,"lrc":{"lyric":"[00:01.20]测试歌词"}}`
	}
	if f.fail {
		body = `{"code":403,"message":"secret remote body must not escape"}`
	}
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: http.Header{}}, nil
}
func liveSetup(t *testing.T, token string) (*Server, *lxsource.Manager, *sourceRunner) {
	t.Helper()
	s, _, _ := setup(t, token)
	runner := &sourceRunner{media: "https://8.8.8.8/audio.mp3"}
	sources, err := lxsource.New(filepath.Join(t.TempDir(), "sources"), runner)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { sources.Close() })
	s.UseLiveSources(sources, provider.NewLive(sources, catalog.NewWY(&catalogFixture{})))
	return s, sources, runner
}
func importRequest(t *testing.T, s *Server, filename, code string, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", filename)
	if err != nil {
		t.Fatal(err)
	}
	part.Write([]byte(code))
	writer.WriteField("allowHTTPHosts", "[]")
	writer.Close()
	r := httptest.NewRequest("POST", "http://127.0.0.1:3780/api/v1/sources/import", &body)
	r.Header.Set("Origin", "http://127.0.0.1:3780")
	r.Header.Set("Content-Type", writer.FormDataContentType())
	if cookie != nil {
		r.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}
func TestLiveCatalogNeverFallsBackToDemo(t *testing.T) {
	s, _, _ := liveSetup(t, "")
	w := request(s, "GET", "/api/v1/providers", nil, nil)
	assertStatus(t, w, 200)
	if strings.Contains(w.Body.String(), `"demo"`) || !strings.Contains(w.Body.String(), `"id":"wy"`) {
		t.Fatal(w.Body.String())
	}
	for _, p := range []string{"/api/v1/charts", "/api/v1/playlists", "/api/v1/recommendations/daily", "/api/v1/search?q=test", "/api/v1/tracks/wy:123", "/api/v1/tracks/wy:123/lyrics"} {
		w = request(s, "GET", p, nil, nil)
		assertStatus(t, w, 200)
		if strings.Contains(w.Body.String(), `"providerId":"demo"`) {
			t.Fatal("demo fallback", p)
		}
	}
	for _, p := range []string{"/api/v1/search?q=test&source=unregistered", "/api/v1/search?q=test&page=0", "/api/v1/search?q=test&type=unknown", "/api/v1/charts?source=demo"} {
		assertStatus(t, request(s, "GET", p, nil, nil), 400)
	}
	assertStatus(t, request(s, "GET", "/api/v1/tracks/demo-maple-leaf-rag", nil, nil), 404)
	assertStatus(t, request(s, "GET", "/api/v1/tracks/wy:123/play-info", nil, nil), 409)
	s.live.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{"wy": catalog.NewWY(&catalogFixture{fail: true})})
	for _, p := range []string{"/api/v1/charts", "/api/v1/search?q=test", "/api/v1/recommendations/daily"} {
		w = request(s, "GET", p, nil, nil)
		assertStatus(t, w, 502)
		if strings.Contains(w.Body.String(), "secret") {
			t.Fatal("upstream response leaked")
		}
	}
}
func TestLXImportAPIOver64KiBAndSourceLifecycle(t *testing.T) {
	s, sources, _ := liveSetup(t, "")
	code := "/*" + strings.Repeat("x", 100<<10) + "*/"
	w := importRequest(t, s, "source.js", code, nil)
	assertStatus(t, w, 201)
	var source lxsource.Source
	if json.Unmarshal(w.Body.Bytes(), &source) != nil || source.Status != "ready" {
		t.Fatal(w.Body.String())
	}
	if sources.List().ActiveID != source.ID {
		t.Fatal("not auto selected")
	}
	assertStatus(t, importRequest(t, s, "renamed.js", code, nil), 200)
	assertStatus(t, importRequest(t, s, "source.txt", "test", nil), 400)
	assertStatus(t, importRequest(t, s, "large.js", strings.Repeat("x", lxsource.MaxScriptBytes+1), nil), 413)
	if len(sources.List().Items) != 1 {
		t.Fatal("duplicates or invalid files persisted")
	}
	assertStatus(t, request(s, "PATCH", "/api/v1/sources/"+source.ID, map[string]any{"allowHTTPHosts": []string{"api.example.com"}}, nil), 200)
	assertStatus(t, request(s, "POST", "/api/v1/sources/"+source.ID+"/check", nil, nil), 200)
	assertStatus(t, request(s, "PUT", "/api/v1/sources/active", map[string]string{"id": "missing"}, nil), 404)
	assertStatus(t, request(s, "PUT", "/api/v1/sources/active", map[string]string{"id": ""}, nil), 200)
	assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]bool{"autoSwitchSource": false}, nil), 200)
	assertStatus(t, request(s, "GET", "/api/v1/tracks/wy:123/play-info", nil, nil), 409)
	assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]bool{"autoSwitchSource": true}, nil), 200)
	assertStatus(t, request(s, "GET", "/api/v1/tracks/wy:123/play-info", nil, nil), 200)
	assertStatus(t, request(s, "PUT", "/api/v1/sources/active", map[string]string{"id": source.ID}, nil), 200)
	w = request(s, "GET", "/api/v1/tracks/wy:123", nil, nil)
	assertStatus(t, w, 200)
	var track model.Track
	json.Unmarshal(w.Body.Bytes(), &track)
	if !track.CanDownload || len(track.Qualities) != 2 {
		t.Fatal(w.Body.String())
	}
	assertStatus(t, request(s, "DELETE", "/api/v1/sources/"+source.ID, nil, nil), 200)
	if sources.List().ActiveID != "" || len(sources.List().Items) != 0 {
		t.Fatal("source not removed")
	}
	// 切回真实目录后仍可删除旧收藏，不应依赖Demo或远端可用性。
	assertStatus(t, request(s, "DELETE", "/api/v1/library/favorites/tracks/demo-old", nil, nil), 200)
}
func TestLXImportAuthorizationAndCSRF(t *testing.T) {
	s, _, _ := liveSetup(t, "test-secret-token-long-enough")
	assertStatus(t, importRequest(t, s, "source.js", "test", nil), 401)
	assertStatus(t, request(s, "GET", "/api/v1/sources", nil, nil), 401)
	login := request(s, "POST", "/api/v1/auth/session", map[string]string{"token": "test-secret-token-long-enough"}, nil)
	assertStatus(t, login, 200)
	cookies := login.Result().Cookies()
	if len(cookies) == 0 {
		t.Fatal("no cookie")
	}
	assertStatus(t, importRequest(t, s, "source.js", "test", cookies[0]), 201)
	for _, path := range []string{"/api/v1/sources/import", "/api/v1/sources/active"} {
		req := httptest.NewRequest("POST", "http://127.0.0.1:3780"+path, strings.NewReader("bad"))
		req.Header.Set("Origin", "http://evil.invalid")
		req.AddCookie(cookies[0])
		w := httptest.NewRecorder()
		s.ServeHTTP(w, req)
		assertStatus(t, w, 403)
	}
}
func TestLiveResolveQualityAndMediaSafety(t *testing.T) {
	s, _, runner := liveSetup(t, "")
	assertStatus(t, importRequest(t, s, "test.js", "test", nil), 201)
	assertStatus(t, request(s, "GET", "/api/v1/tracks/wy:123/play-info?quality=impossible", nil, nil), 400)
	if runner.calls != 0 {
		t.Fatal("invalid quality reached source")
	}
	for _, url := range []string{"https://127.0.0.1/audio.mp3", "https://192.168.88.2/file", "javascript:alert(1)", "https://user:secret@8.8.8.8/file"} {
		runner.media = url
		assertStatus(t, request(s, "GET", "/api/v1/tracks/wy:123/play-info", nil, nil), 502)
	}
	runner.media = "https://8.8.8.8/audio.mp3"
	w := request(s, "GET", "/api/v1/tracks/wy:123/play-info", nil, nil)
	assertStatus(t, w, 200)
	var play model.PlayInfo
	json.Unmarshal(w.Body.Bytes(), &play)
	if !play.Direct || play.URL != runner.media {
		t.Fatal(w.Body.String())
	}
}

func TestLiveDiscoveryCategoriesAndNavigableChartDetail(t *testing.T) {
	s, _, _ := liveSetup(t, "")
	w := request(s, "GET", "/api/v1/discovery/new-tracks?area=zh", nil, nil)
	assertStatus(t, w, 200)
	var discovery struct {
		Title, Area string
		Tracks      []model.Track
		Areas       []discoveryArea
	}
	if err := json.Unmarshal(w.Body.Bytes(), &discovery); err != nil {
		t.Fatal(err)
	}
	if discovery.Title != "新歌速递" || discovery.Area != "zh" || len(discovery.Tracks) != 1 || len(discovery.Areas) != 5 || discovery.Tracks[0].ProviderID != "wy" {
		t.Fatal(w.Body.String())
	}
	assertStatus(t, request(s, "GET", "/api/v1/discovery/new-tracks?area=wrong", nil, nil), 400)
	w = request(s, "GET", "/api/v1/playlist-categories", nil, nil)
	assertStatus(t, w, 200)
	if !strings.Contains(w.Body.String(), `"id":"华语"`) || !strings.Contains(w.Body.String(), `"group":"语种"`) {
		t.Fatal(w.Body.String())
	}
	w = request(s, "GET", "/api/v1/charts/wy:999", nil, nil)
	assertStatus(t, w, 200)
	var chart model.Collection
	json.Unmarshal(w.Body.Bytes(), &chart)
	if chart.ID != "wy:999" || len(chart.Tracks) != 1 {
		t.Fatal(w.Body.String())
	}
	assertStatus(t, request(s, "GET", "/charts/wy:999", nil, nil), 200)
	s.live.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{"wy": catalog.NewWY(&catalogFixture{fail: true})})
	for _, path := range []string{"/api/v1/discovery/new-tracks", "/api/v1/playlist-categories", "/api/v1/charts/wy:999"} {
		assertStatus(t, request(s, "GET", path, nil, nil), 502)
	}
}

package api

import (
	"context"
	"encoding/json"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
)

type platformFixture struct {
	*catalog.WY
	id   string
	fail bool
}

func (a *platformFixture) Charts(context.Context) ([]model.Collection, error) {
	if a.fail {
		return nil, catalog.ErrUnavailable
	}
	return []model.Collection{{ID: a.id + ":chart_1", ProviderID: a.id, Title: a.id + "榜单"}}, nil
}
func (a *platformFixture) Chart(context.Context, string) (model.Collection, error) {
	return model.Collection{ID: a.id + ":chart_1", ProviderID: a.id, Title: a.id + "榜单", Tracks: []model.Track{{ID: a.id + ":song", ProviderID: a.id, Title: a.id + "歌曲"}}}, nil
}
func (a *platformFixture) Playlist(ctx context.Context, id string) (model.Collection, error) {
	return a.Chart(ctx, id)
}
func (a *platformFixture) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	return a.Charts(ctx)
}
func (a *platformFixture) PlaylistCategories(context.Context) ([]catalog.PlaylistCategory, error) {
	return []catalog.PlaylistCategory{{ID: a.id + "-tag", Name: a.id + "分类", Group: "测试"}}, nil
}
func (a *platformFixture) Track(context.Context, string) (model.Track, error) {
	return model.Track{ID: a.id + ":song", ProviderID: a.id, Title: a.id + "歌曲"}, nil
}
func (a *platformFixture) MusicInfo(context.Context, string) (map[string]any, error) {
	return map[string]any{"source": a.id, "songmid": a.id + "-mid"}, nil
}
func (a *platformFixture) Lyrics(context.Context, string) (catalog.Lyrics, error) {
	return catalog.Lyrics{Source: a.id, Lines: []catalog.LyricLine{}}, nil
}
func (a *platformFixture) Search(context.Context, string, string, int) (catalog.SearchResult, error) {
	if a.fail {
		return catalog.SearchResult{}, catalog.ErrUnavailable
	}
	return catalog.SearchResult{Tracks: []model.Track{{ID: a.id + ":song", ProviderID: a.id, Title: a.id + "歌曲"}}, Total: 1, PageSize: 20, Page: 1}, nil
}

type allPlatformRunner struct {
	mu        sync.Mutex
	platforms []string
	infos     []map[string]any
}

func (*allPlatformRunner) Inspect(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error) {
	sources := map[string]lxruntime.Source{}
	for _, id := range catalog.PlatformOrder {
		sources[id] = lxruntime.Source{Name: id, Type: "music", Actions: []string{"musicUrl"}, Qualitys: []string{"128k"}}
	}
	return lxruntime.Descriptor{Status: true, Sources: sources}, nil
}
func (runner *allPlatformRunner) Invoke(_ context.Context, _ string, platform, action string, args map[string]any, _ lxruntime.Options) (json.RawMessage, error) {
	runner.mu.Lock()
	defer runner.mu.Unlock()
	runner.platforms = append(runner.platforms, platform)
	runner.infos = append(runner.infos, args)
	return json.RawMessage(`"https://8.8.8.8/catalog-test.mp3"`), nil
}
func TestPlatformRoutingAndPartialCatalogFailure(t *testing.T) {
	s, _, _ := liveSetup(t, "")
	adapters := map[string]catalog.Adapter{}
	for _, id := range catalog.PlatformOrder {
		adapters[id] = &platformFixture{id: id}
	}
	s.live.Catalog = catalog.NewRegistry(adapters)
	w := request(s, "GET", "/api/v1/providers", nil, nil)
	assertStatus(t, w, 200)
	for _, id := range catalog.PlatformOrder {
		if !strings.Contains(w.Body.String(), `"id":"`+id+`"`) {
			t.Fatal(w.Body.String())
		}
	}
	adapters["mg"].(*platformFixture).fail = true
	w = request(s, "GET", "/api/v1/charts?source=all", nil, nil)
	assertStatus(t, w, 200)
	if w.Header().Get("X-Melora-Unavailable-Sources") != "mg" || strings.Contains(w.Body.String(), `"providerId":"mg"`) {
		t.Fatal(w.Header(), w.Body.String())
	}
	for _, id := range []string{"wy", "tx", "kw", "kg"} {
		if !strings.Contains(w.Body.String(), `"providerId":"`+id+`"`) {
			t.Fatal(w.Body.String())
		}
	}
	w = request(s, "GET", "/api/v1/charts?source=mg", nil, nil)
	assertStatus(t, w, 502)
	for _, id := range catalog.PlatformOrder {
		w = request(s, "GET", "/api/v1/playlist-categories?source="+id, nil, nil)
		assertStatus(t, w, 200)
		if !strings.Contains(w.Body.String(), `"id":"`+id+`-tag"`) {
			t.Fatal(w.Body.String())
		}
		w = request(s, "GET", "/api/v1/charts/"+id+":chart_1", nil, nil)
		assertStatus(t, w, 200)
		if !strings.Contains(w.Body.String(), `"providerId":"`+id+`"`) {
			t.Fatal(w.Body.String())
		}
	}
	assertStatus(t, request(s, "GET", "/api/v1/playlists?source=all&category=wy-tag", nil, nil), 400)
	assertStatus(t, request(s, "GET", "/api/v1/charts?source=unregistered", nil, nil), 400)
}
func TestEachPlatformUsesItsOwnLXMusicInfo(t *testing.T) {
	s, _, _ := liveSetup(t, "")
	runner := &allPlatformRunner{}
	sources, err := lxsource.New(filepath.Join(t.TempDir(), "sources"), runner)
	if err != nil {
		t.Fatal(err)
	}
	defer sources.Close()
	s.sources = sources
	s.live.Sources = sources
	assertStatus(t, importRequest(t, s, "platform-test.js", "test fixture", nil), http.StatusCreated)
	adapters := map[string]catalog.Adapter{}
	for _, id := range catalog.PlatformOrder {
		adapters[id] = &platformFixture{id: id}
	}
	s.live.Catalog = catalog.NewRegistry(adapters)
	for _, id := range catalog.PlatformOrder {
		w := request(s, "GET", "/api/v1/tracks/"+id+":song/play-info", nil, nil)
		assertStatus(t, w, 200)
		var info model.PlayInfo
		if err := json.Unmarshal(w.Body.Bytes(), &info); err != nil {
			t.Fatal(err)
		}
		if !info.Direct || info.TrackID != id+":song" || !strings.HasPrefix(info.URL, "https://8.8.8.8/") {
			t.Fatal(info)
		}
	}
	runner.mu.Lock()
	defer runner.mu.Unlock()
	if len(runner.platforms) != 5 {
		t.Fatal(runner.platforms)
	}
	for i, id := range catalog.PlatformOrder {
		if runner.platforms[i] != id {
			t.Fatal("wrong LX platform", runner.platforms)
		}
		info, ok := runner.infos[i]["musicInfo"].(map[string]any)
		if !ok || info["source"] != id || info["songmid"] != id+"-mid" {
			t.Fatal("cross-platform metadata", runner.infos[i])
		}
	}
}

package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
)

func TestNewTracksMapsVerifiedAreasAndCachesMetadata(t *testing.T) {
	var calls atomic.Int32
	areas := map[string]string{"all": "0", "zh": "7", "western": "96", "jp": "8", "kr": "16"}
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		if r.URL.Scheme != "https" || r.URL.Host != "music.163.com" || r.URL.Path != "/api/v1/discovery/new/songs" || r.Method != http.MethodGet {
			t.Errorf("unexpected new-song request: %s %s", r.Method, r.URL)
		}
		if len(r.URL.Query()) != 1 || r.Header.Get("Referer") != "https://music.163.com/" || r.Header.Get("Accept") != "application/json" {
			t.Errorf("unexpected request parameters or headers: %s", r.URL)
		}
		area, err := strconv.Atoi(r.URL.Query().Get("areaId"))
		if err != nil {
			t.Fatal(err)
		}
		song := sampleSong()
		song["id"] = area + 1000
		return response(map[string]any{"code": 200, "data": []any{song}}), nil
	}))
	for area, upstream := range areas {
		t.Run(area, func(t *testing.T) {
			before := calls.Load()
			tracks, err := client.NewTracks(t.Context(), area)
			if err != nil || len(tracks) != 1 {
				t.Fatalf("NewTracks: %+v %v", tracks, err)
			}
			n, _ := strconv.Atoi(upstream)
			if tracks[0].ID != id(int64(n+1000)) || tracks[0].ProviderID != "wy" || tracks[0].CanDownload || len(tracks[0].Qualities) != 0 || tracks[0].Duration != 125 {
				t.Fatalf("incorrect metadata conversion: %+v", tracks[0])
			}
			tracks[0].Title = "caller mutation"
			again, err := client.NewTracks(t.Context(), area)
			if err != nil || again[0].Title != "Test song" || calls.Load() != before+1 {
				t.Fatal("metadata cache is missing or aliases caller state", again, err, calls.Load())
			}
			info, err := client.MusicInfo(t.Context(), again[0].ID)
			if err != nil || info["songname"] != "Test song" || calls.Load() != before+1 {
				t.Fatal("new-song metadata unavailable to later one-click lookup", info, err)
			}
		})
	}
	before := calls.Load()
	if tracks, err := client.NewTracks(t.Context(), ""); err != nil || len(tracks) != 1 || tracks[0].ID != "wy:1000" || calls.Load() != before {
		t.Fatal("empty area must reuse the all-area request/cache", tracks, err)
	}
}

func TestNewTracksRejectsInvalidAreaBeforeRequest(t *testing.T) {
	var calls atomic.Int32
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
		calls.Add(1)
		return nil, errors.New("must not request upstream")
	}))
	for _, area := range []string{"0", "7", "ALL", "cn", "../all", "all?areaId=7", " all ", "\xff", strings.Repeat("x", 1000)} {
		tracks, err := client.NewTracks(t.Context(), area)
		if !errors.Is(err, ErrInput) || len(tracks) != 0 {
			t.Fatalf("invalid area %q accepted: %+v %v", area, tracks, err)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid area reached network")
	}
}

func TestNewTracksDeduplicatesAndBoundsValidSongs(t *testing.T) {
	songs := []any{map[string]any{"id": 0, "name": "invalid"}, map[string]any{"id": 5, "name": " "}}
	for n := 1; n <= 110; n++ {
		song := sampleSong()
		song["id"] = n
		songs = append(songs, song, song)
	}
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
		return response(map[string]any{"code": 200, "data": songs}), nil
	}))
	tracks, err := client.NewTracks(t.Context(), "all")
	if err != nil || len(tracks) != 100 {
		t.Fatal(len(tracks), err)
	}
	for i, track := range tracks {
		if track.ID != id(int64(i+1)) {
			t.Fatalf("duplicate, reorder or invalid song at %d: %+v", i, track)
		}
	}
}

func catalogueFixture() map[string]any {
	return map[string]any{
		"code":       200,
		"all":        map[string]any{"name": "全部歌单", "category": 4},
		"categories": map[string]string{"0": "语种", "1": "风格", "4": "主题"},
		"sub": []any{
			map[string]any{"name": "华语", "category": 0},
			map[string]any{"name": "R&B/Soul", "category": 1},
		},
	}
}

func TestPlaylistCategoriesUsesUpstreamNamesGroupsAndFilterIDs(t *testing.T) {
	var catalogueCalls, playlistCalls atomic.Int32
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/api/playlist/catalogue":
			catalogueCalls.Add(1)
			if len(r.URL.Query()) != 0 {
				t.Errorf("unexpected category query: %s", r.URL)
			}
			return response(catalogueFixture()), nil
		case "/api/playlist/list":
			playlistCalls.Add(1)
			category := r.URL.Query().Get("cat")
			if category != "全部" && category != "华语" && category != "R&B/Soul" {
				t.Errorf("category ID did not preserve upstream filter: %q", category)
			}
			return response(map[string]any{"code": 200, "playlists": []any{map[string]any{"id": 99, "name": category}}}), nil
		default:
			t.Fatalf("fabricated recommendation/category request: %s", r.URL)
			return nil, ErrUnavailable
		}
	}))
	got, err := client.PlaylistCategories(t.Context())
	want := []PlaylistCategory{{ID: "all", Name: "全部歌单", Group: ""}, {ID: "华语", Name: "华语", Group: "语种"}, {ID: "R&B/Soul", Name: "R&B/Soul", Group: "风格"}}
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("categories: %+v %v", got, err)
	}
	raw, err := json.Marshal(got[1])
	if err != nil || string(raw) != `{"id":"华语","name":"华语","group":"语种"}` {
		t.Fatalf("public JSON contract changed: %s %v", raw, err)
	}
	for _, category := range got {
		if _, err := client.Playlists(t.Context(), category.ID, 1); err != nil {
			t.Fatal(err)
		}
	}
	got[1].Name = "caller mutation"
	again, err := client.PlaylistCategories(t.Context())
	if err != nil || !reflect.DeepEqual(again, want) || catalogueCalls.Load() != 1 || playlistCalls.Load() != 3 {
		t.Fatal("category cache/filter contract failed", again, err, catalogueCalls.Load(), playlistCalls.Load())
	}
}

func TestPlaylistCategoriesSkipsInvalidDuplicatesWithoutInventedGroups(t *testing.T) {
	fixture := catalogueFixture()
	fixture["sub"] = append(fixture["sub"].([]any),
		map[string]any{"name": "华语", "category": 1},
		map[string]any{"name": "不应发明分组", "category": 999},
		map[string]any{"name": "缺少分组"},
		map[string]any{"name": "空分组", "category": nil},
		map[string]any{"name": "负分组", "category": -1},
		map[string]any{"name": " ", "category": 0},
		map[string]any{"name": "bad\nname", "category": 0},
		map[string]any{"name": strings.Repeat("长", 41), "category": 0},
	)
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) { return response(fixture), nil }))
	got, err := client.PlaylistCategories(t.Context())
	if err != nil || len(got) != 3 || got[1].Group != "语种" {
		t.Fatalf("invalid/duplicate categories leaked or overwrote first value: %+v %v", got, err)
	}
}

func TestPlaylistCategoriesNeverInventsAllAndBoundsOutput(t *testing.T) {
	fixture := catalogueFixture()
	delete(fixture, "all")
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) { return response(fixture), nil }))
	got, err := client.PlaylistCategories(t.Context())
	if err != nil || len(got) != 2 || got[0].ID == "all" {
		t.Fatal("invented an all category absent from upstream", got, err)
	}
	entries := make([]any, 300)
	for i := range entries {
		entries[i] = map[string]any{"name": fmt.Sprintf("分类-%03d", i), "category": 0}
	}
	fixture = catalogueFixture()
	fixture["sub"] = entries
	bounded := NewWY(doerFunc(func(*http.Request) (*http.Response, error) { return response(fixture), nil }))
	got, err = bounded.PlaylistCategories(t.Context())
	if err != nil || len(got) != maxPlaylistCategories || got[0].ID != "all" || got[len(got)-1].ID != "分类-254" {
		t.Fatal("category result is not bounded", len(got), err)
	}
}

func TestDiscoveryFailuresReturnErrorsWithoutDemoOrStaleSuccess(t *testing.T) {
	for _, kind := range []string{"network", "http_429", "http_503", "login", "malformed", "missing", "wrong_shape", "oversized"} {
		for _, categories := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/categories_%v", kind, categories), func(t *testing.T) {
				client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
					switch kind {
					case "network":
						return nil, errors.New("offline https://private.example/?secret=not-public")
					case "http_429", "http_503":
						status := 429
						if kind == "http_503" {
							status = 503
						}
						return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("upstream failure"))}, nil
					case "login":
						return response(map[string]any{"code": 301, "data": []any{sampleSong()}}), nil
					case "malformed":
						return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader("<html>login</html>"))}, nil
					case "missing":
						return response(map[string]any{"code": 200}), nil
					case "wrong_shape":
						return response(map[string]any{"code": 200, "data": map[string]any{}, "categories": []any{}, "sub": map[string]any{}}), nil
					default:
						return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(strings.Repeat("x", maxResponseBytes+1)))}, nil
					}
				}))
				var count int
				var err error
				if categories {
					var items []PlaylistCategory
					items, err = client.PlaylistCategories(t.Context())
					count = len(items)
				} else {
					var tracks []model.Track
					tracks, err = client.NewTracks(t.Context(), "all")
					count = len(tracks)
				}
				if !errors.Is(err, ErrUnavailable) || count != 0 || strings.Contains(err.Error(), "secret") {
					t.Fatalf("upstream failure became fake data/sensitive diagnostic: items=%d err=%v", count, err)
				}
			})
		}
	}
	var failed atomic.Bool
	var calls atomic.Int32
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
		calls.Add(1)
		if failed.Load() {
			return nil, errors.New("offline")
		}
		return response(map[string]any{"code": 200, "data": []any{sampleSong()}}), nil
	}))
	if _, err := client.NewTracks(t.Context(), "all"); err != nil {
		t.Fatal(err)
	}
	client.mu.Lock()
	for key, item := range client.cache {
		item.expires = time.Now().Add(-time.Second)
		client.cache[key] = item
	}
	client.mu.Unlock()
	failed.Store(true)
	if tracks, err := client.NewTracks(t.Context(), "all"); !errors.Is(err, ErrUnavailable) || len(tracks) != 0 || calls.Load() != 2 {
		t.Fatal("expired cache concealed a real network failure", tracks, err)
	}
}

func TestNewTracksDistinguishesEmptyFromUnusablePayload(t *testing.T) {
	for _, invalid := range []bool{false, true} {
		data := []any{}
		if invalid {
			data = append(data, map[string]any{"id": -1, "name": "invalid"})
		}
		client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
			return response(map[string]any{"code": 200, "data": data}), nil
		}))
		tracks, err := client.NewTracks(t.Context(), "all")
		if invalid {
			if !errors.Is(err, ErrUnavailable) || len(tracks) != 0 {
				t.Fatal("invalid songs became successful empty recommendation", tracks, err)
			}
		} else if err != nil || tracks == nil || len(tracks) != 0 {
			t.Fatal("valid upstream empty list is not represented faithfully", tracks, err)
		}
	}
}

func TestConcurrentDiscoveryMetadataCache(t *testing.T) {
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/api/playlist/catalogue" {
			return response(catalogueFixture()), nil
		}
		return response(map[string]any{"code": 200, "data": []any{sampleSong()}}), nil
	}))
	var workers sync.WaitGroup
	results := make(chan error, 16)
	for i := range 16 {
		workers.Add(1)
		go func(i int) {
			defer workers.Done()
			for range 8 {
				if i%2 == 0 {
					tracks, err := client.NewTracks(context.Background(), "zh")
					if err != nil || len(tracks) != 1 || tracks[0].Title != "Test song" {
						results <- fmt.Errorf("new-song cache: count=%d err=%v", len(tracks), err)
						return
					}
					tracks[0].Title = "caller mutation"
				} else {
					categories, err := client.PlaylistCategories(context.Background())
					if err != nil || len(categories) != 3 || categories[0].Name != "全部歌单" {
						results <- fmt.Errorf("category cache: count=%d err=%v", len(categories), err)
						return
					}
					categories[0].Name = "caller mutation"
				}
			}
			results <- nil
		}(i)
	}
	workers.Wait()
	close(results)
	for err := range results {
		if err != nil {
			t.Error(err)
		}
	}
}

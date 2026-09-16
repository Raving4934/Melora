package catalog

import (
	"context"
	"encoding/json"
	"fmt"
	"melora/internal/model"
	"melora/internal/store"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

func TestKGSearchMetadataSurvivesColdEndpointFailureAndRestart(t *testing.T) {
	var cold atomic.Int32
	adapter := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host == "songsearch.kugou.com" {
			return kgTestResponse(map[string]any{"status": 1, "error_code": 0, "data": map[string]any{"lists": []any{kgTestSong(1)}, "total": 1}}), nil
		}
		cold.Add(1)
		return kgTestResponse(map[string]any{"status": 0, "errcode": 1}), nil
	}))
	db, err := store.Open(filepath.Join(t.TempDir(), "private.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	registry := NewRegistry(map[string]Adapter{"kg": adapter})
	registry.SetMetadataStore(db)
	result, err := registry.SearchFor(t.Context(), "kg", "测试", "track", 1)
	if err != nil || len(result.Tracks) != 1 {
		t.Fatal(result, err)
	}
	id := result.Tracks[0].ID
	for _, current := range []*Registry{registry, NewRegistry(map[string]Adapter{"kg": NewKG(nil)})} {
		current.SetMetadataStore(db)
		track, err := current.Track(t.Context(), id)
		if err != nil || track.ID != id {
			t.Fatal(track, err)
		}
		music, err := current.MusicInfo(t.Context(), id)
		if err != nil || fmt.Sprint(music["songmid"]) != "1001" || music["hash"] != kgTestHash(1) {
			t.Fatal(music, err)
		}
		if music["source"] != "kg" || music["url"] != nil {
			t.Fatal("unsafe snapshot", music)
		}
	}
	if cold.Load() != 0 {
		t.Fatalf("unnecessary cold request: %d", cold.Load())
	}
}
func TestMetadataCacheClonesSnapshotsAndIsBounded(t *testing.T) {
	var cache metadataMemory
	item := model.CatalogMetadata{Track: model.Track{ID: "wy:1", ProviderID: "wy", Title: "song", CanDownload: true, Qualities: []string{"flac"}}, MusicInfo: map[string]any{"source": "wy", "songmid": 1, "_types": map[string]any{}}}
	cache.put(item)
	item.MusicInfo["songmid"] = 999
	got, ok := cache.get("wy:1")
	if !ok || fmt.Sprint(got.MusicInfo["songmid"]) != "1" || got.Track.CanDownload || len(got.Track.Qualities) != 0 {
		t.Fatal(got)
	}
	got.MusicInfo["songmid"] = 888
	again, _ := cache.get("wy:1")
	if fmt.Sprint(again.MusicInfo["songmid"]) != "1" {
		t.Fatal("caller mutated cache")
	}
	for i := 2; i < 4100; i++ {
		cache.put(model.CatalogMetadata{Track: model.Track{ID: fmt.Sprintf("wy:%d", i), ProviderID: "wy", Title: "song"}})
	}
	if len(cache.entries) > 4000 || cache.bytes > 16<<20 {
		t.Fatal("unbounded cache")
	}
	cache.put(model.CatalogMetadata{Track: model.Track{ID: "wy:large", ProviderID: "wy", Title: strings.Repeat("a", 40<<10)}})
	if _, ok := cache.get("wy:large"); ok {
		t.Fatal("oversized snapshot accepted")
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 30; j++ {
				cache.put(item)
				cache.get(item.Track.ID)
			}
		}()
	}
	wg.Wait()
}
func TestRealPlaylistPlayCountsAreNotInvented(t *testing.T) {
	for _, v := range []any{nil, -1, "1.2万", 1.5, "", json.RawMessage(`null`), 9007199254740992.0} {
		if publicPlayCount(v) != nil {
			t.Fatalf("invalid count accepted %#v", v)
		}
	}
	for _, v := range []any{0, 1234, "1234", json.RawMessage(`1234`)} {
		if publicPlayCount(v) == nil {
			t.Fatalf("count dropped %#v", v)
		}
	}
	var qq txPlaylistBasic
	if json.Unmarshal([]byte(`{"tid":123,"title":"歌单","play_cnt":123456}`), &qq) != nil {
		t.Fatal("fixture")
	}
	q, ok := txPlaylistCollection(qq, "all")
	if !ok || q.PlayCount == nil || *q.PlayCount != 123456 {
		t.Fatal(q)
	}
	var kg kgObject
	json.Unmarshal([]byte(`{"specialid":1,"specialname":"歌单","playcount":"3210"}`), &kg)
	k, ok := kgCollection(kg, "playlist")
	if !ok || k.PlayCount == nil || *k.PlayCount != 3210 {
		t.Fatal(k)
	}
	var kw kwObject
	json.Unmarshal([]byte(`{"id":1,"name":"歌单","playnum":6789}`), &kw)
	w, ok := kwPlaylistCard(kw, "all")
	if !ok || w.PlayCount == nil || *w.PlayCount != 6789 {
		t.Fatal(w)
	}
	n := collection(playlist{ID: 1, Name: "歌单"}, "all")
	if n.PlayCount != nil {
		t.Fatal("missing became zero")
	}
}

type interleaveSearchFixture struct{ registryStub }

func (f *interleaveSearchFixture) Search(ctx context.Context, q, kind string, page int) (SearchResult, error) {
	f.calls.Add(1)
	return SearchResult{Tracks: []model.Track{{ID: f.prefix + ":one", ProviderID: f.prefix, Title: "one"}, {ID: f.prefix + ":two", ProviderID: f.prefix, Title: "two"}}, Total: 2, PageSize: 2, Page: page}, nil
}
func TestAggregatedSearchShowsAllPlatformsInFirstFiveResults(t *testing.T) {
	adapters := map[string]Adapter{}
	for _, id := range PlatformOrder {
		adapters[id] = &interleaveSearchFixture{registryStub: registryStub{prefix: id}}
	}
	r := NewRegistry(adapters)
	result, err := r.SearchFor(t.Context(), "all", "music", "track", 1)
	if err != nil || len(result.Tracks) != 10 || result.Total != 10 {
		t.Fatal(result, err)
	}
	for i, id := range PlatformOrder {
		if result.Tracks[i].ProviderID != id || adapters[id].(*interleaveSearchFixture).calls.Load() != 1 {
			t.Fatal("aggregation hidden or duplicated", result.Tracks)
		}
	}
}

func TestColdTrackDoesNotRequireSecondIdenticalLookupForLX(t *testing.T) {
	var calls atomic.Int32
	a := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) {
		if calls.Add(1) > 1 {
			return kgTestResponse(map[string]any{"status": 0}), nil
		}
		return kgTestResponse(kgTestColdSong(1)), nil
	}))
	r := NewRegistry(map[string]Adapter{"kg": a})
	id := "kg:" + kgTestHash(1)
	if _, err := r.Track(t.Context(), id); err != nil {
		t.Fatal(err)
	}
	info, err := r.MusicInfo(t.Context(), id)
	if err != nil || fmt.Sprint(info["songmid"]) != "9001" || calls.Load() != 1 {
		t.Fatal(info, err, calls.Load())
	}
}

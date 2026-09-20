package api

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"

	"melora/internal/catalog"
	"melora/internal/model"
	"melora/internal/provider"
)

type bookAlbumCatalog struct {
	catalog.Adapter
	album catalog.BookAlbum
	calls int
}

func (b *bookAlbumCatalog) BookHome(context.Context) (catalog.BookHome, error) {
	return catalog.BookHome{}, catalog.ErrUnsupported
}
func (b *bookAlbumCatalog) BookRanks(context.Context) ([]catalog.BookRankTab, error) {
	return nil, catalog.ErrUnsupported
}
func (b *bookAlbumCatalog) BookRank(context.Context, string, string, int) (catalog.BookRankResult, error) {
	return catalog.BookRankResult{}, catalog.ErrUnsupported
}
func (b *bookAlbumCatalog) BookAlbum(context.Context, string, int) (catalog.BookAlbum, error) {
	b.calls++
	return b.album, nil
}

func TestFavoriteAudiobookAlbumUsesBookResolver(t *testing.T) {
	s, db, _ := setup(t, "")
	album := catalog.BookAlbum{
		Collection: model.Collection{
			ID: "kw:book_album_10250871", ProviderID: "kw", Title: "盗墓笔记",
			TrackCount: 250, Category: "有声专辑",
			Tracks: []model.Track{{ID: "kw:193883468", ProviderID: "kw", Title: "第001集"}},
		},
		Page: 1, PageSize: 100, Total: 250,
	}
	live := &bookAlbumCatalog{album: album}
	s.live = &provider.Live{Catalog: catalog.NewRegistry(map[string]catalog.Adapter{"kw": live})}

	assertStatus(t, request(s, "POST", "/api/v1/library/favorites/playlists/kw:book_album_10250871", nil, nil), 200)
	if live.calls != 1 {
		t.Fatalf("book resolver calls = %d", live.calls)
	}
	var favorites []model.Collection
	recorder := request(s, "GET", "/api/v1/library/favorites/playlists", nil, nil)
	if err := json.Unmarshal(recorder.Body.Bytes(), &favorites); err != nil {
		t.Fatal(err)
	}
	if len(favorites) != 1 || favorites[0].ID != album.ID || favorites[0].Title != album.Title {
		t.Fatalf("favorites %+v", favorites)
	}
	if len(favorites[0].Tracks) != 0 {
		t.Fatalf("收藏不能保留章节 payload: %+v", favorites[0].Tracks)
	}
	// 取消收藏不再解析上游，专辑下架也能移除。
	assertStatus(t, request(s, "DELETE", "/api/v1/library/favorites/playlists/kw:book_album_10250871", nil, nil), 200)
	if live.calls != 1 {
		t.Fatalf("delete must not resolve upstream, calls = %d", live.calls)
	}
	stored, err := db.FavoritePlaylists(context.Background())
	if err != nil || len(stored) != 0 {
		t.Fatalf("favorites after delete %+v %v", stored, err)
	}
}

func TestHistoryKindValidationAndFiltering(t *testing.T) {
	s, _, _ := setup(t, "")
	tracks := s.demo.Tracks()
	if len(tracks) < 2 {
		t.Fatal("demo tracks不足")
	}
	first, second := tracks[0], tracks[1]
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": first.ID, "kind": "playlist", "contextId": "wy:playlist_1",
	}, nil), 200)
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": second.ID, "kind": "audiobook", "contextId": "kw:book_album_10250871",
	}, nil), 200)
	// 缺省仍是单曲，并且忽略多余上下文。
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": first.ID, "kind": "track", "contextId": "wy:playlist_1",
	}, nil), 200)
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": first.ID, "kind": "unknown",
	}, nil), 400)
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": first.ID, "kind": "audiobook", "contextId": "wy:playlist_1",
	}, nil), 400)
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": first.ID, "kind": "playlist", "contextId": "kw:book_album_10250871",
	}, nil), 400)
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": first.ID, "kind": "playlist",
	}, nil), 400)
	assertStatus(t, request(s, "GET", "/api/v1/library/history/entries?kind=nope", nil, nil), 400)

	entries := decodeHistory(t, request(s, "GET", "/api/v1/library/history/entries?kind=track", nil, nil))
	if len(entries) != 1 || entries[0].Kind != model.HistoryKindTrack || entries[0].ContextID != "" || entries[0].Track.ID != first.ID {
		t.Fatalf("track filter %+v", entries)
	}
	playlists := decodeHistory(t, request(s, "GET", "/api/v1/library/history/entries?kind=playlist", nil, nil))
	if len(playlists) != 0 {
		t.Fatalf("重播为单曲后不应残留在歌单分类 %+v", playlists)
	}
	books := decodeHistory(t, request(s, "GET", "/api/v1/library/history/entries?kind=audiobook", nil, nil))
	if len(books) != 1 || books[0].ContextID != "kw:book_album_10250871" || books[0].Track.ID != second.ID {
		t.Fatalf("audiobook filter %+v", books)
	}
	all := decodeHistory(t, request(s, "GET", "/api/v1/library/history/entries", nil, nil))
	if len(all) != 2 {
		t.Fatalf("all history %+v", all)
	}
	legacyResponse := request(s, "GET", "/api/v1/library/history", nil, nil)
	var legacyTracks []model.Track
	if legacyResponse.Code != 200 || json.Unmarshal(legacyResponse.Body.Bytes(), &legacyTracks) != nil || len(legacyTracks) != 2 {
		t.Fatalf("v1 history contract changed: status=%d body=%s", legacyResponse.Code, legacyResponse.Body.String())
	}
}

func decodeHistory(t *testing.T, recorder *httptest.ResponseRecorder) []model.HistoryEntry {
	t.Helper()
	if recorder.Code != 200 {
		t.Fatalf("history status %d %s", recorder.Code, recorder.Body.String())
	}
	var entries []model.HistoryEntry
	if err := json.Unmarshal(recorder.Body.Bytes(), &entries); err != nil {
		t.Fatal(err)
	}
	return entries
}

func TestHistoryOutcomeRecordsCompletionAndSkip(t *testing.T) {
	s, _, _ := setup(t, "")
	track := s.demo.Tracks()[0]
	if track.Duration <= 0 {
		t.Fatal("demo track 缺少时长")
	}
	assertStatus(t, request(s, "POST", "/api/v1/library/history", map[string]string{
		"trackId": track.ID,
	}, nil), 200)
	endpoint := "/api/v1/library/history/" + track.ID + "/outcome"
	assertStatus(t, request(s, "POST", "/api/v1/library/history/demo:missing/outcome", map[string]any{"playedMs": 1000, "completed": false}, nil), 409)
	skipMs := int64(track.Duration) * 1000 / 4
	completeMs := int64(track.Duration) * 1000
	generation := s.store.DataIdentity().Generation
	assertStatus(t, request(s, "POST", endpoint, map[string]any{"playedMs": -1, "completed": false}, nil), 400)
	assertStatus(t, request(s, "POST", endpoint, map[string]any{"eventId": "bad", "dataGeneration": generation, "playedMs": skipMs, "completed": false}, nil), 400)
	assertStatus(t, request(s, "POST", endpoint, map[string]any{"eventId": "outcome_api_event_old", "dataGeneration": strings.Repeat("f", 32), "playedMs": skipMs, "completed": false}, nil), 409)
	const skipEventID = "outcome_api_event_0001"
	assertStatus(t, request(s, "POST", endpoint, map[string]any{"eventId": skipEventID, "dataGeneration": generation, "playedMs": skipMs, "completed": false}, nil), 200)
	assertStatus(t, request(s, "POST", endpoint, map[string]any{"eventId": skipEventID, "dataGeneration": generation, "playedMs": skipMs, "completed": false}, nil), 200)
	assertStatus(t, request(s, "POST", endpoint, map[string]any{"playedMs": completeMs, "completed": true}, nil), 200)
	entries := decodeHistory(t, request(s, "GET", "/api/v1/library/history/entries", nil, nil))
	if len(entries) != 1 {
		t.Fatalf("history %+v", entries)
	}
	if entries[0].SkipCount != 1 || entries[0].CompletedCount != 1 || entries[0].ListenedMs != skipMs+completeMs {
		t.Fatalf("outcome counters %+v", entries[0])
	}
}

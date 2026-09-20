package api

import (
	"context"
	"encoding/json"
	"testing"

	"melora/internal/catalog"
	"melora/internal/model"
)

// bookAdapterStub 只实现听书能力，用于验证 API 边界；其余目录能力明确不支持。
type bookAdapterStub struct {
	home  catalog.BookHome
	ranks []catalog.BookRankTab
	rank  catalog.BookRankResult
	album catalog.BookAlbum
	err   error
}

func (s *bookAdapterStub) Search(context.Context, string, string, int) (catalog.SearchResult, error) {
	return catalog.SearchResult{}, catalog.ErrUnsupported
}
func (s *bookAdapterStub) Charts(context.Context) ([]model.Collection, error) {
	return nil, catalog.ErrUnsupported
}
func (s *bookAdapterStub) Chart(context.Context, string) (model.Collection, error) {
	return model.Collection{}, catalog.ErrUnsupported
}
func (s *bookAdapterStub) Playlists(context.Context, string, int) ([]model.Collection, error) {
	return nil, catalog.ErrUnsupported
}
func (s *bookAdapterStub) Playlist(context.Context, string) (model.Collection, error) {
	return model.Collection{}, catalog.ErrUnsupported
}
func (s *bookAdapterStub) PlaylistCategories(context.Context) ([]catalog.PlaylistCategory, error) {
	return nil, catalog.ErrUnsupported
}
func (s *bookAdapterStub) Track(context.Context, string) (model.Track, error) {
	return model.Track{}, catalog.ErrUnsupported
}
func (s *bookAdapterStub) MusicInfo(context.Context, string) (map[string]any, error) {
	return nil, catalog.ErrUnsupported
}
func (s *bookAdapterStub) Lyrics(context.Context, string) (catalog.Lyrics, error) {
	return catalog.Lyrics{}, catalog.ErrUnsupported
}
func (s *bookAdapterStub) BookHome(context.Context) (catalog.BookHome, error) {
	if s.err != nil {
		return catalog.BookHome{}, s.err
	}
	return s.home, nil
}
func (s *bookAdapterStub) BookRanks(context.Context) ([]catalog.BookRankTab, error) {
	if s.err != nil {
		return nil, s.err
	}
	return s.ranks, nil
}
func (s *bookAdapterStub) BookRank(_ context.Context, tabID, tagID string, page int) (catalog.BookRankResult, error) {
	if s.err != nil {
		return catalog.BookRankResult{}, s.err
	}
	rank := s.rank
	rank.Tab.ID = tabID
	rank.TagID = tagID
	rank.Page = page
	return rank, nil
}
func (s *bookAdapterStub) BookAlbum(_ context.Context, id string, page int) (catalog.BookAlbum, error) {
	if s.err != nil {
		return catalog.BookAlbum{}, s.err
	}
	album := s.album
	album.ID = id
	album.Page = page
	return album, nil
}

func bookSetup(t *testing.T, stub *bookAdapterStub) *Server {
	t.Helper()
	s, _, _ := liveSetup(t, "")
	s.live.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{"kw": stub})
	return s
}

func TestAudiobookHomeAPI(t *testing.T) {
	stub := &bookAdapterStub{home: catalog.BookHome{
		Channels: []catalog.BookChannel{{ID: "kw:book_18", Title: "小说", Sections: []catalog.BookSection{{
			ID: "kw:book_18_1", Title: "热门推荐",
			Items: []model.Collection{{ID: "kw:book_album_102904", ProviderID: "kw", Title: "话说泰山", Category: "有声专辑"}},
		}}}},
		Ranks: []catalog.BookRankTab{
			{ID: "13", Name: "热播榜", Tags: []catalog.BookRankTag{{ID: "27", Name: "总榜"}}},
			{ID: "1", Name: "有声小说", Tags: []catalog.BookRankTag{{ID: "30", Name: "总榜"}, {ID: "44", Name: "悬疑热播榜"}}},
		},
	}}
	s := bookSetup(t, stub)
	w := request(s, "GET", "/api/v1/audiobooks/home", nil, nil)
	assertStatus(t, w, 200)
	var body catalog.BookHome
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Channels) != 1 || body.Channels[0].Title != "小说" || len(body.Channels[0].Sections) != 1 {
		t.Fatalf("bad home %+v", body)
	}
	if body.Channels[0].Sections[0].Items[0].ID != "kw:book_album_102904" || len(body.Ranks) != 2 || body.Ranks[0].Name != "热播榜" {
		t.Fatalf("bad items %+v", body)
	}
	if len(body.Ranks[1].Tags) != 2 || body.Ranks[1].Tags[1].Name != "悬疑热播榜" {
		t.Fatalf("bad rank tags %+v", body.Ranks[1])
	}
}

func TestAudiobookHomeUnsupportedAndFailure(t *testing.T) {
	s := bookSetup(t, &bookAdapterStub{err: catalog.ErrUnavailable})
	assertStatus(t, request(s, "GET", "/api/v1/audiobooks/home", nil, nil), 502)
}

func TestAudiobookRankAPI(t *testing.T) {
	stub := &bookAdapterStub{rank: catalog.BookRankResult{
		Tab:      catalog.BookRankTab{ID: "1", Name: "有声小说", Tags: []catalog.BookRankTag{{ID: "30", Name: "总榜"}, {ID: "44", Name: "悬疑热播榜"}}},
		PageSize: 50,
		Total:    46,
		Items: []model.Collection{{
			ID: "kw:book_album_50927272", ProviderID: "kw", Title: "从赘婿到女帝宠臣", Artist: "玥明珠", TrackCount: 1592, Category: "有声专辑",
		}},
	}}
	s := bookSetup(t, stub)
	w := request(s, "GET", "/api/v1/audiobooks/ranks/1?tagId=44&page=1", nil, nil)
	assertStatus(t, w, 200)
	var body catalog.BookRankResult
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Tab.Name != "有声小说" || body.TagID != "44" || body.Total != 46 || body.Page != 1 {
		t.Fatalf("bad rank %+v", body)
	}
	if len(body.Items) != 1 || body.Items[0].ID != "kw:book_album_50927272" || body.Items[0].TrackCount != 1592 {
		t.Fatalf("bad rank items %+v", body.Items)
	}

	assertStatus(t, request(s, "GET", "/api/v1/audiobooks/ranks/1?page=0", nil, nil), 400)
	assertStatus(t, request(bookSetup(t, &bookAdapterStub{err: catalog.ErrNotFound}), "GET", "/api/v1/audiobooks/ranks/9", nil, nil), 404)
	assertStatus(t, request(bookSetup(t, &bookAdapterStub{err: catalog.ErrUnavailable}), "GET", "/api/v1/audiobooks/ranks/1", nil, nil), 502)
}

func TestAudiobookAlbumAPI(t *testing.T) {
	stub := &bookAdapterStub{album: catalog.BookAlbum{
		Collection: model.Collection{ID: "kw:book_album_1", ProviderID: "kw", Title: "盗墓笔记", Artist: "周建龙", TrackCount: 314, Tracks: []model.Track{{ID: "kw:193883468", ProviderID: "kw", Title: "第001集", Duration: 1500}}},
		Total:      314,
		PageSize:   100,
	}}
	s := bookSetup(t, stub)
	s.live.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{
		"kw": stub,
		"wy": catalog.NewWY(&catalogFixture{}),
	})
	w := request(s, "GET", "/api/v1/audiobooks/albums/kw:book_album_1?page=2", nil, nil)
	assertStatus(t, w, 200)
	var body catalog.BookAlbum
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Page != 2 || body.Total != 314 || len(body.Tracks) != 1 || body.Tracks[0].ID != "kw:193883468" {
		t.Fatalf("bad album page %+v", body)
	}
	if body.Artist != "周建龙" || body.Tracks[0].ProviderID != "kw" {
		t.Fatalf("bad album fields %+v", body)
	}

	assertStatus(t, request(s, "GET", "/api/v1/audiobooks/albums/kw:book_album_1?page=0", nil, nil), 400)
	assertStatus(t, request(s, "GET", "/api/v1/audiobooks/albums/wy:album_1", nil, nil), 422)

	missing := bookSetup(t, &bookAdapterStub{err: catalog.ErrNotFound})
	assertStatus(t, request(missing, "GET", "/api/v1/audiobooks/albums/kw:book_album_1", nil, nil), 404)

	broken := bookSetup(t, &bookAdapterStub{err: catalog.ErrUnavailable})
	assertStatus(t, request(broken, "GET", "/api/v1/audiobooks/albums/kw:book_album_1", nil, nil), 502)
}

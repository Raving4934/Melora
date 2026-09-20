package catalog

import (
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"testing"
)

func kwBookSection(label string, items ...map[string]any) map[string]any {
	rows := make([]any, 0, len(items))
	for _, item := range items {
		rows = append(rows, item)
	}
	return map[string]any{"label": label, "list": rows}
}
func kwBookItemRow(id, digest, name string) map[string]any {
	return map[string]any{"id": id, "digest": digest, "name": name, "img": "http://img1.kuwo.cn/star/albumcover/180/" + id + ".jpg", "desc": "简介 " + name}
}
func kwBookTagList() map[string]any {
	return map[string]any{"code": 200, "data": map[string]any{"total": 6, "list": []any{
		map[string]any{"id": 13, "name": "热播榜", "isShow": 0, "tagList": []any{
			map[string]any{"id": 27, "name": "总榜", "isShow": 1},
		}},
		map[string]any{"id": 20, "name": "VIP会员榜", "isShow": 1, "tagList": []any{
			map[string]any{"id": 128, "name": "总榜", "isShow": 1},
			map[string]any{"id": 137, "name": "VIP新书榜", "isShow": 1},
		}},
		map[string]any{"id": 1, "name": "有声小说", "isShow": 1, "tagList": []any{
			map[string]any{"id": 30, "name": "总榜", "isShow": 1},
			map[string]any{"id": 44, "name": "悬疑热播榜", "isShow": 0},
			map[string]any{"id": 30, "name": "重复总榜", "isShow": 1},
		}},
		map[string]any{"id": 99, "name": "白名单外", "isShow": 1, "tagList": []any{
			map[string]any{"id": 5, "name": "总榜", "isShow": 1},
		}},
		map[string]any{"id": 14, "name": "口碑榜", "isShow": 0, "tagList": []any{
			map[string]any{"id": 28, "name": "总榜", "isShow": 1},
		}},
	}}}
}
func kwBookHomeResponder(t *testing.T) func(*http.Request) *http.Response {
	return func(r *http.Request) *http.Response {
		switch {
		case r.URL.Host == "mobileinterfaces.kuwo.cn" && r.URL.Path == "/er.s":
			q := r.URL.Query()
			if q.Get("type") != "get_pc_qz_data" || q.Get("f") != "web" || q.Get("prod") != "pc" || q.Get("ver") != "1" {
				t.Errorf("bad qz query %s", r.URL.RawQuery)
			}
			switch q.Get("id") {
			case "18":
				return kwTestResponse([]any{
					kwBookSection("热门推荐", kwBookItemRow("102904", "13", "话说泰山"), kwBookItemRow("508097", "5", "孕期胎教"), kwBookItemRow("102904", "13", "话说泰山")),
					kwBookSection("小编推荐", kwBookItemRow("103000", "13", "金庸武侠")),
				})
			}
			t.Errorf("unexpected channel %s", q.Get("id"))
			return kwTestResponse([]any{})
		case r.URL.Host == "wapi.kuwo.cn" && r.URL.Path == "/openapi/v1/album/bang/tagList":
			return kwTestResponse(kwBookTagList())
		}
		t.Errorf("unexpected book request %s", r.URL)
		return kwTestResponse(map[string]any{})
	}
}

func TestKWBookHomeMapsChannelsRanksAndSkipsDeadLinks(t *testing.T) {
	client := kwTestClient(t, kwBookHomeResponder(t))
	home, err := client.BookHome(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(home.Channels) != 1 || home.Channels[0].Title != "小说" {
		t.Fatalf("bad channels %+v", home.Channels)
	}
	novel := home.Channels[0]
	if len(novel.Sections) != 2 || novel.Sections[0].Title != "热门推荐" || novel.Sections[1].Title != "小编推荐" {
		t.Fatalf("bad sections %+v", novel.Sections)
	}
	if len(novel.Sections[0].Items) != 1 || novel.Sections[0].Items[0].ID != "kw:book_album_102904" || novel.Sections[0].Items[0].Category != "有声专辑" {
		t.Fatalf("digest 13 not mapped to album: %+v", novel.Sections[0].Items)
	}
	if novel.Sections[0].Items[0].CoverURL != "https://img1.kuwo.cn/star/albumcover/180/102904.jpg" {
		t.Fatalf("cover not normalized: %q", novel.Sections[0].Items[0].CoverURL)
	}
	if len(novel.Sections[1].Items) != 1 || novel.Sections[1].Items[0].ID != "kw:book_album_103000" {
		t.Fatalf("second section wrong: %+v", novel.Sections[1].Items)
	}
	if novel.Sections[0].Items[0].TrackCount != 0 || novel.Sections[0].Items[0].ProviderID != "kw" {
		t.Fatalf("book item invented catalog fields: %+v", novel.Sections[0].Items[0])
	}
	if len(home.Ranks) != 4 || home.Ranks[0].ID != "13" || home.Ranks[0].Name != "热播榜" || home.Ranks[0].Tags[0].ID != "27" {
		t.Fatalf("bad ranks %+v", home.Ranks)
	}
	if len(home.Ranks[1].Tags) != 2 || home.Ranks[1].Tags[1].Name != "VIP新书榜" || home.Ranks[3].Name != "口碑榜" {
		t.Fatalf("rank tags wrong %+v", home.Ranks)
	}
	if len(home.Ranks[2].Tags) != 1 || home.Ranks[2].Tags[0].ID != "30" {
		t.Fatalf("hidden or duplicate tags not skipped: %+v", home.Ranks[2].Tags)
	}
}

func TestKWBookHomePartialAndTotalFailure(t *testing.T) {
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		if r.URL.Host == "wapi.kuwo.cn" {
			return kwTestResponse(kwBookTagList())
		}
		return &http.Response{StatusCode: 500, Body: io.NopCloser(strings.NewReader("bad")), Header: http.Header{}}
	})
	home, err := client.BookHome(t.Context())
	if err != nil {
		t.Fatalf("partial failure should not fail the page: %v", err)
	}
	if len(home.Channels) != 0 || len(home.Ranks) != 4 {
		t.Fatalf("partial home wrong: %+v", home)
	}

	allFail := kwTestClient(t, func(r *http.Request) *http.Response {
		return &http.Response{StatusCode: 500, Body: io.NopCloser(strings.NewReader("bad")), Header: http.Header{}}
	})
	if _, err := allFail.BookHome(t.Context()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("all-failure should be unavailable, got %v", err)
	}
}

func kwBookAlbumInfo(albumID int, title string, total int, rows []any) map[string]any {
	return map[string]any{"code": 200, "data": map[string]any{
		"albumid": albumID, "album": title, "artist": "一路听天下&周建龙",
		"pic": "http://img1.kuwo.cn/star/albumcover/300/cover.jpg", "total": strconv.Itoa(total), "playCnt": "86932989",
		"albuminfo": "播音：周建龙\n作者：南派三叔",
		"musicList": rows,
	}}
}
func kwBookVerifyList(ids ...string) map[string]any {
	rows := make([]any, 0, len(ids))
	for _, id := range ids {
		rows = append(rows, map[string]any{"albumid": id, "name": "检索结果"})
	}
	return map[string]any{"total": strconv.Itoa(len(rows)), "albumlist": rows}
}
func kwBookAlbumResponder(t *testing.T) func(*http.Request) *http.Response {
	return func(r *http.Request) *http.Response {
		switch {
		case r.URL.Host == "wapi.kuwo.cn" && r.URL.Path == "/api/www/album/albumInfo":
			q := r.URL.Query()
			if q.Get("albumId") != "10250871" || q.Get("rn") != "100" || q.Get("httpsStatus") != "1" {
				t.Errorf("bad album query %s", r.URL.RawQuery)
			}
			rows := []any{
				map[string]any{"rid": 193883468, "name": "【极速30s】盗墓笔记", "artist": "一路听天下", "album": "盗墓笔记", "albumid": 10250871, "duration": "30", "albumpic": "http://img1.kuwo.cn/star/albumcover/chapter.jpg"},
				map[string]any{"musicrid": "MUSIC_193883469", "name": "第001集", "artist": "一路听天下", "album": "盗墓笔记", "albumid": 10250871, "duration": "1500"},
			}
			page, _ := strconv.Atoi(q.Get("pn"))
			total := len(rows)
			if page > 1 {
				total = (page-1)*kwBookAlbumPageSize + len(rows)
			}
			return kwTestResponse(kwBookAlbumInfo(10250871, "盗墓笔记|周建龙演播", total, rows))
		case r.URL.Host == "search.kuwo.cn" && r.URL.Path == "/r.s":
			q := r.URL.Query()
			if q.Get("show_series_listen") != "1" || q.Get("all") != "盗墓笔记|周建龙演播" || q.Get("rn") != "50" {
				t.Errorf("bad verification query %s", r.URL.RawQuery)
			}
			return kwTestResponse(kwBookVerifyList("10250871", "999"))
		}
		t.Errorf("unexpected album request %s", r.URL)
		return kwTestResponse(map[string]any{})
	}
}

func TestKWBookAlbumMapsChaptersAndPaging(t *testing.T) {
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		if r.URL.Host == "wapi.kuwo.cn" && r.URL.Query().Get("albumId") == "10250871" && r.URL.Query().Get("pn") != "3" {
			t.Errorf("page not forwarded: %s", r.URL.RawQuery)
		}
		return kwBookAlbumResponder(t)(r)
	})
	album, err := client.BookAlbum(t.Context(), "kw:book_album_10250871", 3)
	if err != nil {
		t.Fatal(err)
	}
	if album.ID != "kw:book_album_10250871" || album.ProviderID != "kw" || album.Title != "盗墓笔记|周建龙演播" || album.Artist != "一路听天下&周建龙" {
		t.Fatalf("bad album identity %+v", album.Collection)
	}
	if album.Total != 202 || album.TrackCount != 202 || album.Page != 3 || album.PageSize != 100 {
		t.Fatalf("bad album paging %+v", album)
	}
	if album.PlayCount == nil || *album.PlayCount != 86932989 {
		t.Fatalf("play count lost: %+v", album.PlayCount)
	}
	if album.Description != "播音：周建龙\n作者：南派三叔" {
		t.Fatalf("description not cleaned: %q", album.Description)
	}
	if album.CoverURL != "https://img1.kuwo.cn/star/albumcover/300/cover.jpg" {
		t.Fatalf("cover not normalized: %q", album.CoverURL)
	}
	if len(album.Tracks) != 2 || album.Tracks[0].ID != "kw:193883468" || album.Tracks[0].Title != "【极速30s】盗墓笔记" || album.Tracks[0].Duration != 30 {
		t.Fatalf("bad chapters %+v", album.Tracks)
	}
	if album.Tracks[1].ID != "kw:193883469" || album.Tracks[1].Duration != 1500 {
		t.Fatalf("musicrid chapter not mapped: %+v", album.Tracks[1])
	}
	kwRequireNoAuthorization(t, album.Tracks[0])
}

func TestKWBookAlbumRejectsOrdinaryAndMalformedAlbums(t *testing.T) {
	ordinary := kwBookAlbumInfo(14365066, "Mojito", 1, []any{
		map[string]any{"rid": 97773, "name": "Mojito", "artist": "周杰伦", "album": "Mojito", "albumid": 14365066, "duration": "185"},
	})
	tooMany := []any{}
	for i := 0; i < kwBookAlbumPageSize+1; i++ {
		tooMany = append(tooMany, map[string]any{"rid": 1000 + i, "name": "第" + strconv.Itoa(i) + "集", "albumid": 10250871, "duration": "600"})
	}
	onePage := []any{map[string]any{"rid": 1000, "name": "第1集", "albumid": 10250871, "duration": "600"}}
	for _, tc := range []struct {
		name    string
		raw     string
		page    int
		respond func(*http.Request) *http.Response
		want    error
	}{
		{"wrong platform", "wy:book_album_1", 1, kwBookAlbumResponder(t), ErrNotFound},
		{"bad id", "kw:book_album_x", 1, kwBookAlbumResponder(t), ErrNotFound},
		{"ordinary album namespace", "kw:album_10250871", 1, kwBookAlbumResponder(t), ErrNotFound},
		{"page zero", "kw:book_album_10250871", 0, kwBookAlbumResponder(t), ErrInput},
		{"page too large", "kw:book_album_10250871", 51, kwBookAlbumResponder(t), ErrInput},
		{"upstream missing album", "kw:book_album_10250871", 1, func(*http.Request) *http.Response {
			return kwTestResponse(map[string]any{"code": -1, "msg": "没有此专辑"})
		}, ErrNotFound},
		{"upstream generic failure", "kw:book_album_10250871", 1, func(*http.Request) *http.Response {
			return kwTestResponse(map[string]any{"code": -1, "msg": "系统繁忙"})
		}, ErrUnavailable},
		{"album id mismatch", "kw:book_album_10250871", 1, func(*http.Request) *http.Response {
			return kwTestResponse(kwBookAlbumInfo(999, "伪造", 1, []any{}))
		}, ErrNotFound},
		{"missing total", "kw:book_album_10250871", 1, func(*http.Request) *http.Response {
			return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"albumid": 10250871, "album": "缺失总数", "musicList": []any{}}})
		}, ErrUnavailable},
		{"ordinary album fails long-audio verification", "kw:book_album_14365066", 1, func(r *http.Request) *http.Response {
			if r.URL.Host == "wapi.kuwo.cn" {
				return kwTestResponse(ordinary)
			}
			return kwTestResponse(kwBookVerifyList("28424771", "14693800"))
		}, ErrNotFound},
		{"verification search failure is unavailable", "kw:book_album_10250871", 1, func(r *http.Request) *http.Response {
			if r.URL.Host == "search.kuwo.cn" {
				return &http.Response{StatusCode: 500, Body: io.NopCloser(strings.NewReader("bad")), Header: http.Header{}}
			}
			return kwBookAlbumResponder(t)(r)
		}, ErrUnavailable},
		{"chapters exceed page contract", "kw:book_album_10250871", 1, func(r *http.Request) *http.Response {
			if r.URL.Host == "wapi.kuwo.cn" {
				return kwTestResponse(kwBookAlbumInfo(10250871, "盗墓笔记", len(tooMany), tooMany))
			}
			return kwBookAlbumResponder(t)(r)
		}, ErrUnavailable},
		{"total below offset with rows", "kw:book_album_10250871", 2, func(r *http.Request) *http.Response {
			if r.URL.Host == "wapi.kuwo.cn" {
				return kwTestResponse(kwBookAlbumInfo(10250871, "盗墓笔记", 50, onePage))
			}
			return kwBookAlbumResponder(t)(r)
		}, ErrUnavailable},
		{"rows exceed total", "kw:book_album_10250871", 1, func(r *http.Request) *http.Response {
			if r.URL.Host == "wapi.kuwo.cn" {
				return kwTestResponse(kwBookAlbumInfo(10250871, "盗墓笔记", 1, append(append([]any{}, onePage...), map[string]any{"rid": 1001, "name": "第2集", "albumid": 10250871, "duration": "600"})))
			}
			return kwBookAlbumResponder(t)(r)
		}, ErrUnavailable},
		{"truncated non-final page", "kw:book_album_10250871", 1, func(r *http.Request) *http.Response {
			if r.URL.Host == "wapi.kuwo.cn" {
				return kwTestResponse(kwBookAlbumInfo(10250871, "盗墓笔记", 101, onePage))
			}
			return kwBookAlbumResponder(t)(r)
		}, ErrUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := kwTestClient(t, func(r *http.Request) *http.Response { return tc.respond(r) })
			if _, err := client.BookAlbum(t.Context(), tc.raw, tc.page); !errors.Is(err, tc.want) {
				t.Fatalf("want %v, got %v", tc.want, err)
			}
		})
	}
}

func kwBookRankDataList(page, total int, rows []any) map[string]any {
	return map[string]any{"code": 200, "data": map[string]any{"total": strconv.Itoa(total), "pn": page, "rn": 50, "list": rows}}
}
func kwBookRankRows() []any {
	return []any{
		map[string]any{"id": 50927272, "name": "从赘婿到女帝宠臣", "artist": "玥明珠_萌鹿剧场", "pic": "http://img1.kuwo.cn/star/albumcover/300/a.png", "desc": "穿越架空世界", "musicnum": "1592", "listencnt": "15420978"},
		map[string]any{"id": 101176, "name": "单田芳：白眉大侠(320回)", "musicnum": "320"},
		map[string]any{"id": 50927272, "name": "重复条目", "musicnum": "1"},
	}
}
func kwBookRankResponder(t *testing.T, wantTab, wantTag string) func(*http.Request) *http.Response {
	return func(r *http.Request) *http.Response {
		switch r.URL.Path {
		case "/openapi/v1/album/bang/tagList":
			return kwTestResponse(kwBookTagList())
		case "/openapi/v1/album/bang/dataList":
			q := r.URL.Query()
			if q.Get("tabId") != wantTab || q.Get("tagId") != wantTag || q.Get("rn") != "50" {
				t.Errorf("bad rank query %s want tab=%s tag=%s", r.URL.RawQuery, wantTab, wantTag)
			}
			page, _ := strconv.Atoi(q.Get("pn"))
			rows := kwBookRankRows()
			return kwTestResponse(kwBookRankDataList(page, (page-1)*kwBookRankPageSize+len(rows), rows))
		}
		t.Errorf("unexpected rank request %s", r.URL)
		return kwTestResponse(map[string]any{})
	}
}

func TestKWBookRankDefaultsToFirstTagAndMapsAlbums(t *testing.T) {
	client := kwTestClient(t, kwBookRankResponder(t, "1", "30"))
	rank, err := client.BookRank(t.Context(), "1", "", 1)
	if err != nil {
		t.Fatal(err)
	}
	if rank.Tab.ID != "1" || rank.Tab.Name != "有声小说" || rank.TagID != "30" || rank.Page != 1 || rank.PageSize != 50 || rank.Total != 3 {
		t.Fatalf("bad rank meta %+v", rank)
	}
	if len(rank.Items) != 2 {
		t.Fatalf("duplicate or rejected items not filtered: %+v", rank.Items)
	}
	first := rank.Items[0]
	if first.ID != "kw:book_album_50927272" || first.ProviderID != "kw" || first.Title != "从赘婿到女帝宠臣" || first.Artist != "玥明珠_萌鹿剧场" {
		t.Fatalf("bad album item %+v", first)
	}
	if first.TrackCount != 1592 || first.PlayCount == nil || *first.PlayCount != 15420978 || first.Category != "有声专辑" {
		t.Fatalf("bad album counters %+v", first)
	}
	if first.CoverURL != "https://img1.kuwo.cn/star/albumcover/300/a.png" || first.Description != "穿越架空世界" {
		t.Fatalf("bad album cover/desc %+v", first)
	}
	if rank.Items[1].ID != "kw:book_album_101176" || rank.Items[1].TrackCount != 320 || rank.Items[1].PlayCount != nil {
		t.Fatalf("second item wrong %+v", rank.Items[1])
	}
}

func TestKWBookRankExplicitTagAndErrors(t *testing.T) {
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		switch r.URL.Path {
		case "/openapi/v1/album/bang/tagList":
			return kwTestResponse(kwBookTagList())
		case "/openapi/v1/album/bang/dataList":
			if r.URL.Query().Get("pn") != "2" {
				t.Errorf("page not forwarded: %s", r.URL.RawQuery)
			}
			return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"total": "53", "pn": 2, "rn": 50, "list": kwBookRankRows()}})
		}
		t.Errorf("unexpected rank request %s", r.URL)
		return kwTestResponse(map[string]any{})
	})
	rank, err := client.BookRank(t.Context(), "20", "137", 2)
	if err != nil {
		t.Fatal(err)
	}
	if rank.TagID != "137" || rank.Page != 2 || rank.Tab.Name != "VIP会员榜" {
		t.Fatalf("bad explicit tag result %+v", rank)
	}

	tooMany := []any{}
	for i := 0; i < kwBookRankPageSize+1; i++ {
		tooMany = append(tooMany, map[string]any{"id": 1000 + i, "name": "书" + strconv.Itoa(i)})
	}
	for _, tc := range []struct {
		name    string
		tab     string
		tag     string
		page    int
		respond func(*http.Request) *http.Response
		want    error
	}{
		{"bad tab id", "x", "", 1, kwBookRankResponder(t, "1", "30"), ErrNotFound},
		{"tab outside whitelist", "99", "", 1, kwBookRankResponder(t, "1", "30"), ErrNotFound},
		{"unknown tag", "1", "999", 1, kwBookRankResponder(t, "1", "30"), ErrNotFound},
		{"bad tag id", "1", "x", 1, kwBookRankResponder(t, "1", "30"), ErrNotFound},
		{"hidden tag", "1", "44", 1, kwBookRankResponder(t, "1", "44"), ErrNotFound},
		{"page zero", "1", "", 0, kwBookRankResponder(t, "1", "30"), ErrInput},
		{"page too large", "1", "", 51, kwBookRankResponder(t, "1", "30"), ErrInput},
		{"upstream failure", "1", "", 1, func(*http.Request) *http.Response {
			return kwTestResponse(map[string]any{"code": -1, "msg": "系统繁忙"})
		}, ErrUnavailable},
		{"missing total", "1", "", 1, func(*http.Request) *http.Response {
			return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"list": []any{map[string]any{"id": 1, "name": "书"}}}})
		}, ErrUnavailable},
		{"no mappable items", "1", "", 1, func(*http.Request) *http.Response {
			return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"total": "1", "pn": 1, "list": []any{map[string]any{"name": "没有ID"}}}})
		}, ErrUnavailable},
		{"items exceed page contract", "1", "", 1, func(*http.Request) *http.Response {
			return kwTestResponse(kwBookRankDataList(1, len(tooMany), tooMany))
		}, ErrUnavailable},
		{"response page mismatch", "1", "", 1, func(*http.Request) *http.Response {
			return kwTestResponse(kwBookRankDataList(2, len(kwBookRankRows()), kwBookRankRows()))
		}, ErrUnavailable},
		{"total below offset with rows", "1", "", 2, func(*http.Request) *http.Response {
			return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"total": "50", "pn": 2, "list": []any{map[string]any{"id": 1, "name": "书"}}}})
		}, ErrUnavailable},
		{"truncated non-final page", "1", "", 1, func(*http.Request) *http.Response {
			return kwTestResponse(kwBookRankDataList(1, 51, []any{map[string]any{"id": 1, "name": "书"}}))
		}, ErrUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			respond := tc.respond
			c := kwTestClient(t, func(r *http.Request) *http.Response {
				if r.URL.Path == "/openapi/v1/album/bang/dataList" {
					return respond(r)
				}
				return kwBookHomeResponder(t)(r)
			})
			if _, err := c.BookRank(t.Context(), tc.tab, tc.tag, tc.page); !errors.Is(err, tc.want) {
				t.Fatalf("want %v, got %v", tc.want, err)
			}
		})
	}
}

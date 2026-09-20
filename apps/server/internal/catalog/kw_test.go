package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
)

type kwTestDoer func(*http.Request) (*http.Response, error)

func (f kwTestDoer) Do(r *http.Request) (*http.Response, error) { return f(r) }
func kwTestResponse(value any) *http.Response {
	body, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": []string{"application/json"}}, Body: io.NopCloser(strings.NewReader(string(body))), ContentLength: int64(len(body))}
}
func kwTestSong(rid string) map[string]any {
	return map[string]any{"id": rid, "name": "测试歌曲 &amp; 合作", "artist": "测试歌手", "album": "测试专辑", "albumid": "23", "duration": "235", "albumpic": "http://img1.kuwo.cn/star/albumcover/test.jpg", "pay": "16711935", "isdownload": "1", "formats": "MP3128|MP3H|ALFLAC"}
}
func kwTestInfo(rid string) map[string]any {
	song := kwTestSong(rid)
	song["MUSICRID"] = "MUSIC_" + rid
	return map[string]any{"abslist": []any{song}, "total": "1", "pn": "0", "rn": "1"}
}
func kwTestH5(rid string) map[string]any {
	song := kwTestSong(rid)
	song["musicrId"] = "MUSIC_" + rid
	song["songName"] = song["name"]
	song["pic"] = song["albumpic"]
	return map[string]any{"status": 200, "data": map[string]any{"songinfo": song, "lrclist": []any{map[string]any{"time": "2.5", "lineLyric": "第二行（测试文本）"}, map[string]any{"time": "1.25", "lineLyric": "第一行（测试文本）"}}}}
}
func kwTestClient(t *testing.T, respond func(*http.Request) *http.Response) *KW {
	t.Helper()
	return NewKW(kwTestDoer(func(r *http.Request) (*http.Response, error) {
		if r.Method != http.MethodGet || r.URL.Scheme != "https" {
			t.Errorf("unexpected request method/scheme %s %s", r.Method, r.URL.Scheme)
		}
		switch r.URL.Host {
		case "search.kuwo.cn", "qukudata.kuwo.cn", "kbangserver.kuwo.cn", "wapi.kuwo.cn", "nplserver.kuwo.cn", "m.kuwo.cn", "mobileinterfaces.kuwo.cn":
		default:
			t.Errorf("unapproved host %s", r.URL.Host)
		}
		if deadline, ok := r.Context().Deadline(); !ok || time.Until(deadline) > 9*time.Second {
			t.Error("catalog request missing 9-second deadline")
		}
		if r.Header.Get("Cookie") != "" || r.Header.Get("Authorization") != "" {
			t.Error("anonymous catalog sent credentials")
		}
		if r.Header.Get("Referer") != "https://www.kuwo.cn/" || r.Header.Get("User-Agent") == "" {
			t.Error("catalog helper headers missing")
		}
		return respond(r), nil
	}))
}
func kwRequireNoAuthorization(t *testing.T, track model.Track) {
	t.Helper()
	if track.ProviderID != "kw" || track.CanDownload || len(track.Qualities) != 0 {
		t.Fatalf("catalog invented source authorization: %+v", track)
	}
}

func TestKWConstructorAndUnsupportedAreOffline(t *testing.T) {
	var calls atomic.Int32
	client := NewKW(kwTestDoer(func(*http.Request) (*http.Response, error) {
		calls.Add(1)
		return nil, errors.New("should not be called")
	}))
	if calls.Load() != 0 {
		t.Fatal("constructor made a request")
	}
	if _, err := client.NewTracks(t.Context(), "all"); !errors.Is(err, ErrUnsupported) {
		t.Fatal("new tracks not explicitly unsupported")
	}
	if _, err := client.Search(t.Context(), "test", "mv", 1); !errors.Is(err, ErrUnsupported) {
		t.Fatal("unsupported search type returned empty success")
	}
	if _, err := client.Playlists(t.Context(), "kw:zone_211", 1); !errors.Is(err, ErrUnsupported) {
		t.Fatal("unsupported zone returned empty success")
	}
	if calls.Load() != 0 {
		t.Fatal("unsupported capability made a request")
	}
	if _, err := NewKW(nil).Track(t.Context(), "kw:1"); !errors.Is(err, ErrUnavailable) {
		t.Fatal("nil doer did not fail safely")
	}
}
func TestKWSearchAllKindsAndZeroBasedPagination(t *testing.T) {
	for _, kind := range []string{"track", "playlist", "album", "artist", "book"} {
		t.Run(kind, func(t *testing.T) {
			client := kwTestClient(t, func(r *http.Request) *http.Response {
				q := r.URL.Query()
				want := kind
				if kind == "track" {
					want = "music"
				}
				if kind == "book" {
					want = "album"
				}
				if r.URL.Host != "search.kuwo.cn" || r.URL.Path != "/r.s" || q.Get("ft") != want || q.Get("all") != "测试 & 歌手" || q.Get("pn") != "1" || q.Get("rn") != "20" || q.Get("mobi") != "1" || q.Get("newver") != "1" {
					t.Errorf("bad search URL %s", r.URL)
				}
				series := q.Get("show_series_listen")
				if kind == "book" && series != "1" || kind != "book" && series != "" {
					t.Errorf("unexpected show_series_listen=%q for %s", series, kind)
				}
				var row map[string]any
				switch kind {
				case "track":
					row = map[string]any{"MUSICRID": "MUSIC_123", "SONGNAME": "<em>测试</em>&amp;歌曲", "ARTIST": "合作歌手", "ALBUM": "公开专辑", "ALBUMID": "22", "DURATION": "245", "web_albumpic_short": "120/a/b.jpg"}
				case "playlist":
					row = map[string]any{"playlistid": "123", "name": "测试歌单", "songnum": "42", "pic": "http://img2.kuwo.cn/list.jpg", "intro": "<br>介绍"}
				case "album", "book":
					row = map[string]any{"albumid": "123", "name": "测试专辑", "artist": "专辑歌手", "musiccnt": "8", "pic": "120/album.jpg"}
				case "artist":
					row = map[string]any{"ARTISTID": "123", "ARTIST": "测试歌手", "SONGNUM": "91", "PICPATH": "120/artist.jpg"}
				}
				rows := []any{row}
				for i := 0; i < 25; i++ {
					copy := map[string]any{}
					for k, v := range row {
						copy[k] = v
					}
					switch kind {
					case "track":
						copy["MUSICRID"] = fmt.Sprintf("MUSIC_%d", 200+i)
					case "playlist":
						copy["playlistid"] = fmt.Sprint(200 + i)
					case "album", "book":
						copy["albumid"] = fmt.Sprint(200 + i)
					case "artist":
						copy["ARTISTID"] = fmt.Sprint(200 + i)
					}
					rows = append(rows, copy)
				}
				if kind == "album" || kind == "book" {
					return kwTestResponse(map[string]any{"total": "100", "pn": "1", "albumlist": rows, "BASEPICPATH": "http://img1.kuwo.cn/star/albumcover/"})
				}
				return kwTestResponse(map[string]any{"TOTAL": "100", "PN": "1", "abslist": rows, "BASEPICPATH": "http://img1.kuwo.cn/star/starheads/"})
			})
			result, err := client.Search(t.Context(), " 测试 & 歌手 ", kind, 2)
			if err != nil {
				t.Fatal(err)
			}
			if result.Page != 2 || result.PageSize != 20 || result.Total != 100 {
				t.Fatalf("bad pagination %+v", result)
			}
			switch kind {
			case "track":
				if len(result.Tracks) != 20 || result.Tracks[0].ID != "kw:123" || result.Tracks[0].Title != "测试&歌曲" || result.Tracks[0].Duration != 245 || result.Tracks[0].CoverURL != "https://img1.kuwo.cn/star/albumcover/120/a/b.jpg" {
					t.Fatalf("bad tracks %+v", result.Tracks)
				}
				kwRequireNoAuthorization(t, result.Tracks[0])
			case "playlist":
				if len(result.Playlists) != 20 || result.Playlists[0].ID != "kw:playlist_123" || result.Playlists[0].TrackCount != 42 {
					t.Fatal("bad playlist result")
				}
			case "album", "book":
				idPrefix := "kw:album_"
				if kind == "book" {
					idPrefix = "kw:book_album_"
				}
				if len(result.Albums) != 20 || result.Albums[0].ID != idPrefix+"123" || result.Albums[0].TrackCount != 8 || result.Albums[0].Artist != "专辑歌手" || result.Albums[0].CoverURL != "https://img1.kuwo.cn/star/albumcover/120/album.jpg" {
					t.Fatal("bad album result")
				}
			case "artist":
				if len(result.Artists) != 20 || result.Artists[0].ID != "kw:artist_123" || result.Artists[0].TrackCount != 91 || result.Artists[0].Name != "测试歌手" {
					t.Fatal("bad artist result")
				}
			}
		})
	}
}
func TestKWCoverNormalizationReachesTrackAndMusicInfo(t *testing.T) {
	for _, tc := range []struct {
		name string
		raw  string
		want string
	}{
		{"verified", "http://img2.kwcdn.kuwo.cn/star/upload/8/8/1543919795640_.png", "https://img2.kuwo.cn/star/upload/8/8/1543919795640_.png"},
		{"verified_new", "http://img4.kwcdn.kuwo.cn/star/upload/9/9/1543919747769_.png", "https://img4.kuwo.cn/star/upload/9/9/1543919747769_.png"},
		{"verified_douyin", "http://img4.kwcdn.kuwo.cn/star/upload/6/6/1554970547302_.png", "https://img4.kuwo.cn/star/upload/6/6/1554970547302_.png"},
		{"verified_hot", "http://img1.kwcdn.kuwo.cn/star/upload/2/2/1543919658018_.png", "https://img1.kuwo.cn/star/upload/2/2/1543919658018_.png"},
		{"mirrored_fallback", "http://img3.kwcdn.kuwo.cn/star/upload/unverified.png", "https://img3.kuwo.cn/star/upload/unverified.png"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls atomic.Int32
			client := kwTestClient(t, func(r *http.Request) *http.Response {
				calls.Add(1)
				if r.URL.Host != "search.kuwo.cn" {
					t.Fatal("cover normalization must not request images or other endpoints")
				}
				response := kwTestInfo("123")
				response["abslist"].([]any)[0].(map[string]any)["albumpic"] = tc.raw
				return kwTestResponse(response)
			})
			track, err := client.Track(t.Context(), "kw:123")
			if err != nil || track.CoverURL != tc.want || track.ID != "kw:123" {
				t.Fatalf("track cover normalization failed: %v", err)
			}
			kwRequireNoAuthorization(t, track)
			info, err := client.MusicInfo(t.Context(), "kw:123")
			if err != nil || info["img"] != tc.want || info["songmid"] != "123" {
				t.Fatalf("music info cover normalization failed: %v", err)
			}
			if calls.Load() != 2 {
				t.Fatal("cover normalization added network I/O")
			}
		})
	}
}

func TestKWRelativeCoverNormalizationUsesCompleteResourcePath(t *testing.T) {
	const base = "http://img2.kwcdn.kuwo.cn/star/upload/"
	if got := kwPicture("8/8/1543919795640_.png", base); got != "https://img2.kuwo.cn/star/upload/8/8/1543919795640_.png" {
		t.Fatal("relative cover was not joined and mirrored to the same shard")
	}
	if got := kwPicture("unverified.png", base); got != "https://img2.kuwo.cn/star/upload/unverified.png" {
		t.Fatal("relative cover on the trusted CDN must keep its path on the mirror")
	}
	for _, unsafeBase := range []string{"https://evil.example/star/upload/", "https://img2.kwcdn.kuwo.cn:8443/star/upload/"} {
		if got := kwPicture("8/8/1543919795640_.png", unsafeBase); got != "" {
			t.Fatal("normalization bypassed relative cover base validation")
		}
	}
}

func TestKWColdTrackAndMusicInfoRIDContract(t *testing.T) {
	var calls atomic.Int32
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		calls.Add(1)
		if r.URL.Host != "search.kuwo.cn" || r.URL.Path != "/r.s" || r.URL.Query().Get("rid") != "MUSIC_526058813" || r.URL.Query().Get("all") != "" || r.URL.Query().Get("rn") != "1" {
			t.Fatal("cold lookup depended on search")
		}
		return kwTestResponse(kwTestInfo("526058813"))
	})
	track, err := client.Track(t.Context(), "kw:526058813")
	if err != nil {
		t.Fatal(err)
	}
	kwRequireNoAuthorization(t, track)
	if track.Title != "测试歌曲 & 合作" || track.Duration != 235 || track.Album != "测试专辑" || track.CoverURL != "https://img1.kuwo.cn/star/albumcover/test.jpg" {
		t.Fatalf("wrong cold track %+v", track)
	}
	info, err := client.MusicInfo(t.Context(), track.ID)
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"id", "songmid", "rid", "songId", "songid", "musicId"} {
		if info[field] != "526058813" {
			t.Errorf("wrong LX %s: %v", field, info[field])
		}
	}
	for _, field := range []string{"MUSICRID", "musicrid", "musicrId"} {
		if info[field] != "MUSIC_526058813" {
			t.Errorf("wrong MUSIC field %s", field)
		}
	}
	if info["source"] != "kw" || info["interval"] != "03:55" || info["albumId"] != "23" || info["singer"] != "测试歌手" {
		t.Fatal("wrong LX metadata")
	}
	info["songmid"] = "mutated"
	info["_types"].(map[string]any)["flac"] = true
	again, err := client.MusicInfo(t.Context(), track.ID)
	if err != nil || again["songmid"] != "526058813" || len(again["_types"].(map[string]any)) != 0 {
		t.Fatal("MusicInfo returned shared mutable data")
	}
	if calls.Load() != 3 {
		t.Fatal("unexpected constructor/cache requests")
	}
}
func TestKWCrossResourceIDsRejectedBeforeNetwork(t *testing.T) {
	var calls atomic.Int32
	client := NewKW(kwTestDoer(func(*http.Request) (*http.Response, error) { calls.Add(1); return nil, errors.New("unexpected") }))
	for _, id := range []string{"wy:123", "kw:chart_123", "kw:playlist_123", "kw:album_123", "kw:artist_123", "kw:MUSIC_123", "kw:00123", "kw:0", "kw:12/../3", "kw:123?token=x", "kw:" + strings.Repeat("9", 19)} {
		if _, err := client.Track(t.Context(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("Track accepted %s", id)
		}
		if _, err := client.MusicInfo(t.Context(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("MusicInfo accepted %s", id)
		}
		if _, err := client.Lyrics(t.Context(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("Lyrics accepted %s", id)
		}
	}
	for _, id := range []string{"kw:123", "kw:playlist_123", "wy:chart_123", "kw:chart_0", "kw:chart_12/3"} {
		if _, err := client.Chart(t.Context(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("Chart accepted %s", id)
		}
	}
	for _, id := range []string{"kw:123", "kw:chart_123", "wy:playlist_123", "kw:playlist_0"} {
		if _, err := client.Playlist(t.Context(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("Playlist accepted %s", id)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid ID caused network I/O")
	}
}
func TestKWChartsUseSourceIDNotTreeNodeID(t *testing.T) {
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		if r.URL.Host == "wapi.kuwo.cn" && r.URL.Path == "/api/pc/bang/list" {
			return kwTestResponse(map[string]any{"name": "排行榜", "child": []any{
				map[string]any{"name": "官方", "child": []any{
					map[string]any{"id": "80358", "name": "真实飙升榜", "source": "2", "sourceid": "93", "info": "300", "intro": "真实榜单说明", "pic": "http://img3.kwcdn.kuwo.cn/star/upload/8/8/1543919795640_.png"},
					map[string]any{"id": "80359", "name": "重复", "source": "2", "sourceid": "93"},
					map[string]any{"id": "80360", "name": "站外榜", "source": "2", "sourceid": "https://untrusted.example/"},
				}},
			}})
		}
		if r.URL.Host != "wapi.kuwo.cn" || r.URL.Path != "/api/www/bang/bang/musicList" || r.URL.Query().Get("bangId") != "93" || r.URL.Query().Get("rn") != "30" {
			t.Fatal("wrong chart request")
		}
		page, _ := strconv.Atoi(r.URL.Query().Get("pn"))
		items := []any{}
		for i := 0; i < 30; i++ {
			song := kwTestSong(strconv.Itoa(page*1000 + i))
			song["duration"] = "5"
			song["song_duration"] = "235"
			items = append(items, song)
		}
		return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"img": "https://img1.kuwo.cn/star/upload/2/2/1543919658018_.png", "num": "200", "pub": "2026-09-11", "musicList": items}})
	})
	charts, err := client.Charts(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(charts) != 1 || charts[0].ID != "kw:chart_93" || charts[0].Category != "官方" || charts[0].CoverURL != "https://img3.kuwo.cn/star/upload/8/8/1543919795640_.png" || charts[0].TrackCount != 300 {
		t.Fatalf("wrong board IDs %+v", charts)
	}
	chart, err := client.Chart(t.Context(), charts[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if chart.Title != "真实飙升榜" || chart.TrackCount != 200 || len(chart.Tracks) != 100 || chart.Tracks[0].Duration != 235 || !strings.Contains(chart.Description, "当前加载 100 首，共 200 首") {
		t.Fatalf("wrong chart count/duration %+v", chart)
	}
	if chart.CoverURL != "https://img1.kuwo.cn/star/upload/2/2/1543919658018_.png" {
		t.Fatalf("chart cover not taken from music list response: %q", chart.CoverURL)
	}
	kwRequireNoAuthorization(t, chart.Tracks[0])
}
func TestKWPlaylistPagination24AndCategory(t *testing.T) {
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		q := r.URL.Query()
		if q.Get("rn") != "24" || q.Get("loginUid") != "0" {
			t.Errorf("bad playlist page request %s", r.URL)
		}
		page := q.Get("pn")
		start := 100
		if page == "2" {
			start = 124
		}
		if q.Get("id") != "" {
			if q.Get("id") != "1265" || !strings.HasSuffix(r.URL.Path, "/getTagPlayList") {
				t.Error("bad category request")
			}
		} else if !strings.HasSuffix(r.URL.Path, "/getRcmPlayList") {
			t.Error("bad recommended route")
		}
		rows := []any{}
		for i := 0; i < 27; i++ {
			rows = append(rows, map[string]any{"id": fmt.Sprint(start + i), "name": fmt.Sprintf("歌单 %d", start+i), "total": "70", "img": "https://img1.kuwo.cn/playlist.jpg"})
		}
		return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"total": 1000, "pn": page, "rn": 24, "data": rows}})
	})
	one, err := client.Playlists(t.Context(), "all", 1)
	if err != nil {
		t.Fatal(err)
	}
	two, err := client.Playlists(t.Context(), "all", 2)
	if err != nil {
		t.Fatal(err)
	}
	tagged, err := client.Playlists(t.Context(), "kw:category_1265", 1)
	if err != nil {
		t.Fatal(err)
	}
	if len(one) != 24 || len(two) != 24 || len(tagged) != 24 || one[0].ID != "kw:playlist_100" || two[0].ID != "kw:playlist_124" || one[0].TrackCount != 70 {
		t.Fatal("24-item pagination broken")
	}
	if tagged[0].Category != "kw:category_1265" {
		t.Fatal("category binding lost")
	}
}
func TestKWPlaylistDetailCountsAndIdentity(t *testing.T) {
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		q := r.URL.Query()
		if r.URL.Host != "nplserver.kuwo.cn" || q.Get("pid") != "123" || q.Get("rn") != "100" || q.Get("pn") != "0" || q.Get("vipver") != "MUSIC_9.0.5.0_W1" {
			t.Error("wrong metadata protocol")
		}
		rows := []any{}
		for i := 0; i < 110; i++ {
			rows = append(rows, kwTestSong(fmt.Sprint(200+i)))
		}
		return kwTestResponse(map[string]any{"result": "ok", "id": 123, "title": "真实歌单", "total": 379, "musiclist": rows, "pic": "http://img1.kuwo.cn/list.jpg"})
	})
	list, err := client.Playlist(t.Context(), "kw:playlist_123")
	if err != nil {
		t.Fatal(err)
	}
	if list.ID != "kw:playlist_123" || len(list.Tracks) != 100 || list.TrackCount != 100 || !strings.Contains(list.Description, "共 379 首") {
		t.Fatal("playlist cap or disclosure broken")
	}
	for _, track := range list.Tracks {
		kwRequireNoAuthorization(t, track)
	}
	wrong := kwTestClient(t, func(*http.Request) *http.Response {
		return kwTestResponse(map[string]any{"result": "ok", "id": 999, "title": "默认歌单", "total": 0, "musiclist": []any{}})
	})
	if _, err := wrong.Playlist(t.Context(), "kw:playlist_123"); !errors.Is(err, ErrNotFound) {
		t.Fatal("upstream default was relabelled as requested playlist")
	}
}
func TestKWCategoriesExposeOnlySupportedRemoteTags(t *testing.T) {
	client := kwTestClient(t, func(r *http.Request) *http.Response {
		if !strings.HasSuffix(r.URL.Path, "/getTagList") {
			t.Error("wrong category route")
		}
		return kwTestResponse(map[string]any{"code": "200", "data": []any{
			map[string]any{"name": "主题", "data": []any{map[string]any{"id": "1265", "name": "经典", "digest": "10000"}, map[string]any{"id": "1265", "name": "重复", "digest": "10000"}, map[string]any{"id": "211", "name": "专区", "digest": "43"}}},
			map[string]any{"name": "语言", "data": []any{map[string]any{"id": "37", "name": "华语", "digest": "10000"}}},
		}})
	})
	categories, err := client.PlaylistCategories(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	want := []PlaylistCategory{{ID: "kw:category_1265", Name: "经典", Group: "主题"}, {ID: "kw:category_37", Name: "华语", Group: "语言"}}
	if !reflect.DeepEqual(categories, want) {
		t.Fatalf("made-up or unsupported categories %+v", categories)
	}
}
func TestKWLyricsTimesAndMalformedLines(t *testing.T) {
	client := kwTestClient(t, func(*http.Request) *http.Response {
		body := kwTestH5("123")
		body["data"].(map[string]any)["lrclist"] = []any{
			map[string]any{"time": "2.5", "lineLyric": "后一句"}, map[string]any{"time": 1.25, "lineLyric": "前一句"}, map[string]any{"time": "1.25", "lineLyric": "前一句"},
			map[string]any{"time": "1.25", "lineLyric": "同时间翻译"}, map[string]any{"time": "NaN", "lineLyric": "错误"}, map[string]any{"time": "+Inf", "lineLyric": "错误"}, map[string]any{"time": "-1", "lineLyric": "错误"}, map[string]any{"time": "90000", "lineLyric": "错误"}, map[string]any{"time": "3", "lineLyric": " "},
		}
		return kwTestResponse(body)
	})
	lyrics, err := client.Lyrics(t.Context(), "kw:123")
	if err != nil {
		t.Fatal(err)
	}
	if lyrics.Source != "酷沃" || len(lyrics.Lines) != 3 || lyrics.Lines[0].Time != 1.25 || lyrics.Lines[1].Text != "同时间翻译" || lyrics.Lines[2].Time != 2.5 {
		t.Fatalf("bad lyrics %+v", lyrics)
	}
	empty := kwTestClient(t, func(*http.Request) *http.Response {
		body := kwTestH5("123")
		body["data"].(map[string]any)["lrclist"] = []any{}
		return kwTestResponse(body)
	})
	if _, err := empty.Lyrics(t.Context(), "kw:123"); !errors.Is(err, ErrNotFound) {
		t.Fatal("missing lyrics reported as success")
	}
}

func TestKWDenialsNeverBecomeEmptySuccess(t *testing.T) {
	operations := []struct {
		name string
		run  func(*KW) error
	}{
		{"search-track", func(k *KW) error { _, e := k.Search(t.Context(), "test", "track", 1); return e }},
		{"search-playlist", func(k *KW) error { _, e := k.Search(t.Context(), "test", "playlist", 1); return e }},
		{"search-album", func(k *KW) error { _, e := k.Search(t.Context(), "test", "album", 1); return e }},
		{"search-artist", func(k *KW) error { _, e := k.Search(t.Context(), "test", "artist", 1); return e }},
		{"charts", func(k *KW) error { _, e := k.Charts(t.Context()); return e }},
		{"chart", func(k *KW) error { _, e := k.Chart(t.Context(), "kw:chart_93"); return e }},
		{"playlists", func(k *KW) error { _, e := k.Playlists(t.Context(), "all", 1); return e }},
		{"playlist", func(k *KW) error { _, e := k.Playlist(t.Context(), "kw:playlist_123"); return e }},
		{"categories", func(k *KW) error { _, e := k.PlaylistCategories(t.Context()); return e }},
		{"track", func(k *KW) error { _, e := k.Track(t.Context(), "kw:123"); return e }},
		{"music-info", func(k *KW) error { _, e := k.MusicInfo(t.Context(), "kw:123"); return e }},
		{"lyrics", func(k *KW) error { _, e := k.Lyrics(t.Context(), "kw:123"); return e }},
	}
	for _, body := range []map[string]any{
		{"code": 403, "msg": "token=UPSTREAM-SECRET"},
		{"status": "401", "msg": "登录后才能访问 token=UPSTREAM-SECRET"},
		{"code": "not-a-status"},
		{"success": false, "message": "The request is illegal!"},
		{"error_code": 9},
		{"error": "captcha token=UPSTREAM-SECRET"},
		{},
	} {
		for _, op := range operations {
			t.Run(op.name, func(t *testing.T) {
				k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
				err := op.run(k)
				if !errors.Is(err, ErrUnavailable) {
					t.Fatalf("denial became success/wrong error: %v", err)
				}
				if strings.Contains(err.Error(), "SECRET") || strings.Contains(err.Error(), "WY") {
					t.Fatalf("unsafe/misattributed error %v", err)
				}
			})
		}
	}
}
func TestKWMalformedOrContradictoryListsFail(t *testing.T) {
	for _, body := range []any{
		map[string]any{"TOTAL": "1", "abslist": []any{}},
		map[string]any{"TOTAL": "0", "abslist": []any{kwTestSong("123")}},
		map[string]any{"TOTAL": "1"},
		map[string]any{"TOTAL": "1", "abslist": nil},
		map[string]any{"TOTAL": "1", "abslist": []any{map[string]any{"playlistid": "1", "name": "not a song"}}},
		map[string]any{"TOTAL": "1", "PN": "1", "abslist": []any{kwTestSong("123")}},
		map[string]any{"TOTAL": "-1", "abslist": []any{}},
		map[string]any{"TOTAL": "9999999999999999999", "abslist": []any{}},
		map[string]any{"TOTAL": "1", "abslist": map[string]any{}},
		"{'TOTAL':'1','abslist':[]}",
	} {
		k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
		if _, err := k.Search(t.Context(), "test", "track", 1); !errors.Is(err, ErrUnavailable) {
			t.Errorf("malformed search accepted: %v", err)
		}
	}
	k := kwTestClient(t, func(*http.Request) *http.Response {
		return kwTestResponse(map[string]any{"result": "ok", "id": 123, "title": "不是空歌单", "total": 379, "musiclist": []any{}})
	})
	if _, err := k.Playlist(t.Context(), "kw:playlist_123"); !errors.Is(err, ErrUnavailable) {
		t.Fatal("declared nonempty playlist became empty success")
	}
	k = kwTestClient(t, func(*http.Request) *http.Response {
		return kwTestResponse(map[string]any{"name": "不是空榜单", "num": "300", "musiclist": []any{}})
	})
	if _, err := k.Chart(t.Context(), "kw:chart_93"); !errors.Is(err, ErrUnavailable) {
		t.Fatal("declared nonempty chart became empty success")
	}
	for _, body := range []any{
		map[string]any{"code": 200, "data": map[string]any{"total": 24, "pn": 1, "data": []any{}}},
		map[string]any{"code": 0, "data": map[string]any{"total": 0, "pn": 1, "data": []any{}}},
		map[string]any{"code": 200, "data": map[string]any{"total": 0, "pn": 2, "data": []any{}}},
	} {
		k = kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
		if _, err := k.Playlists(t.Context(), "all", 1); !errors.Is(err, ErrUnavailable) {
			t.Fatal("malformed playlist page accepted")
		}
	}
}
func TestKWGenuineEmptySearchAndEndPage(t *testing.T) {
	for _, page := range []int{1, 2} {
		k := kwTestClient(t, func(*http.Request) *http.Response {
			total := 0
			if page == 2 {
				total = 20
			}
			return kwTestResponse(map[string]any{"TOTAL": total, "PN": page - 1, "abslist": []any{}})
		})
		out, err := k.Search(t.Context(), "nonexistent", "track", page)
		if err != nil || len(out.Tracks) != 0 {
			t.Fatal("genuine empty/end page rejected")
		}
	}
	k := kwTestClient(t, func(*http.Request) *http.Response {
		return kwTestResponse(map[string]any{"code": 200, "data": map[string]any{"total": 0, "pn": 1, "data": []any{}}})
	})
	if lists, err := k.Playlists(t.Context(), "all", 1); err != nil || len(lists) != 0 {
		t.Fatal("genuine empty category rejected")
	}
}
func TestKWSongIdentityAndMissingData(t *testing.T) {
	for _, body := range []map[string]any{
		{"abslist": []any{}, "total": "0"}, kwTestInfo("999"),
	} {
		k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
		if _, err := k.Track(t.Context(), "kw:123"); !errors.Is(err, ErrNotFound) {
			t.Fatal("wrong/missing song returned")
		}
	}
	for _, body := range []map[string]any{
		{"total": 1},
		{"total": 1, "abslist": []any{map[string]any{"id": "123", "musicrId": "MUSIC_999", "songName": "contradictory"}}},
		{"total": 1, "abslist": []any{map[string]any{"id": "123", "songName": ""}}},
		{"total": 2, "abslist": []any{kwTestSong("123"), kwTestSong("124")}},
	} {
		k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
		if _, err := k.Track(t.Context(), "kw:123"); !errors.Is(err, ErrUnavailable) {
			t.Fatal("bad song shape accepted")
		}
	}
}
func TestKWInvalidQueriesAndPaginationDoNotRequest(t *testing.T) {
	k := NewKW(kwTestDoer(func(*http.Request) (*http.Response, error) {
		t.Fatal("invalid input requested upstream")
		return nil, nil
	}))
	for _, q := range []string{"", strings.Repeat("音", 201), "abc\nquery", string([]byte{0xff})} {
		if _, err := k.Search(t.Context(), q, "track", 1); !errors.Is(err, ErrInput) {
			t.Fatal("bad query accepted")
		}
	}
	for _, page := range []int{0, -1, 51} {
		if _, err := k.Search(t.Context(), "test", "track", page); !errors.Is(err, ErrInput) {
			t.Fatal("bad search page accepted")
		}
		if _, err := k.Playlists(t.Context(), "all", page); !errors.Is(err, ErrInput) {
			t.Fatal("bad playlist page accepted")
		}
	}
	for _, category := range []string{"wy:category_1", "kw:playlist_1", "kw:category_0", "kw:category_1&token=x", "https://private.example/"} {
		if _, err := k.Playlists(t.Context(), category, 1); !errors.Is(err, ErrInput) {
			t.Fatal("bad category accepted")
		}
	}
}
func TestKWPicturesAndLargeNumericIDs(t *testing.T) {
	for _, raw := range []string{"https://img1.kuwo.cn.evil.example/a", "https://evil.example/album.jpg", "https://user:password@img1.kuwo.cn/a", "https://img1.kuwo.cn:8443/a", "https://img1.kuwo.cn./a", "http://127.0.0.1/a", "file:///etc/passwd"} {
		if kwImage(raw) != "" {
			t.Errorf("unsafe image accepted %s", raw)
		}
	}
	for _, raw := range []string{"//evil.example/a", "../escape", "%2e%2e/escape", "a?x=y", "a\\b"} {
		if kwPicture(raw, "https://img1.kuwo.cn/star/albumcover/") != "" {
			t.Errorf("unsafe relative image accepted %s", raw)
		}
	}
	if kwImage("http://img3.kwcdn.kuwo.cn/star/upload/1/1/1554695862673_.png#fragment") != "https://img3.kuwo.cn/star/upload/1/1/1554695862673_.png" {
		t.Fatal("broken CDN cover was not mirrored on the same shard")
	}
	if kwImage("http://img3.kwcdn.kuwo.cn/a.jpg#fragment") != "https://img3.kuwo.cn/a.jpg" {
		t.Fatal("mirrored CDN cover must keep its path instead of being blanked")
	}
	if kwPicture("120/a.jpg", "https://evil.example/") != "" {
		t.Fatal("untrusted picture base accepted")
	}
	id := "9007199254740993"
	k := kwTestClient(t, func(*http.Request) *http.Response {
		body := kwTestInfo(id)
		body["abslist"].([]any)[0].(map[string]any)["id"] = json.Number(id)
		return kwTestResponse(body)
	})
	info, err := k.MusicInfo(t.Context(), "kw:"+id)
	if err != nil || info["songmid"] != id {
		t.Fatalf("large RID lost precision %v", err)
	}
}
func TestKWHTTPBoundsAndCancellation(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
	}{
		{"http-denied", 403, `{"token":"SECRET"}`},
		{"oversized", 200, strings.Repeat("x", (1<<20)+1)},
		{"html", 200, "<html>login token=SECRET</html>"},
		{"jsonp", 200, `callback({"TOTAL":"0","abslist":[]})`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			k := kwTestClient(t, func(*http.Request) *http.Response {
				return &http.Response{StatusCode: tc.status, Body: io.NopCloser(strings.NewReader(tc.body))}
			})
			if _, err := k.Search(t.Context(), "test", "track", 1); !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), "SECRET") {
				t.Fatal("HTTP boundary failure not sanitized")
			}
		})
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	k := NewKW(kwTestDoer(func(r *http.Request) (*http.Response, error) {
		select {
		case <-r.Context().Done():
			return nil, errors.New("token=TRANSPORT-SECRET")
		default:
			t.Fatal("lost cancellation context")
			return nil, nil
		}
	}))
	if _, err := k.Track(ctx, "kw:123"); !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), "SECRET") {
		t.Fatal("cancel failure leaked raw transport error")
	}
}
func TestKWInjectedHTTPTestServer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/r.s" || r.URL.Query().Get("rid") != "MUSIC_123" {
			t.Error("test server got wrong request")
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(kwTestInfo("123"))
	}))
	defer server.Close()
	base, _ := url.Parse(server.URL)
	k := NewKW(kwTestDoer(func(r *http.Request) (*http.Response, error) {
		// 仅此测试把已固定平台目标映射到独立 httptest listener，生产无此开关。
		if r.URL.Host != "search.kuwo.cn" || r.URL.Scheme != "https" {
			t.Error("adapter target changed")
		}
		copy := r.Clone(r.Context())
		uri := *r.URL
		uri.Scheme = base.Scheme
		uri.Host = base.Host
		copy.URL = &uri
		return server.Client().Do(copy)
	}))
	track, err := k.Track(t.Context(), "kw:123")
	if err != nil || track.ID != "kw:123" {
		t.Fatalf("injected HTTP doer not used: %v", err)
	}
}
func TestKWConcurrentMetadataIsIndependent(t *testing.T) {
	k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(kwTestInfo("123")) })
	var wg sync.WaitGroup
	for range 16 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			info, err := k.MusicInfo(t.Context(), "kw:123")
			if err != nil {
				t.Error(err)
				return
			}
			info["songmid"] = "changed"
			info["_types"].(map[string]any)["changed"] = true
		}()
	}
	wg.Wait()
	info, err := k.MusicInfo(t.Context(), "kw:123")
	if err != nil || info["songmid"] != "123" || len(info["_types"].(map[string]any)) != 0 {
		t.Fatal("shared metadata leaked between calls")
	}
}

// 显式启用才运行实网目录探测；不请求音频、登录接口或用户数据，不依赖 registry。
func TestKWLivePublicCatalog(t *testing.T) {
	if os.Getenv("MELORA_KUWO_LIVE") != "1" {
		t.Skip("set MELORA_KUWO_LIVE=1 to probe anonymous public metadata")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	defer transport.CloseIdleConnections()
	client := NewKW(&http.Client{Transport: transport, Timeout: 9 * time.Second})
	for _, kind := range []string{"track", "playlist", "album", "artist"} {
		t.Run("search-"+kind, func(t *testing.T) {
			out, err := client.Search(t.Context(), "周杰伦", kind, 1)
			if err != nil {
				t.Fatal(err)
			}
			count := len(out.Tracks) + len(out.Playlists) + len(out.Albums) + len(out.Artists)
			if count == 0 || count > kwSearchPageSize || out.Total == 0 {
				t.Fatal("live search missing real data")
			}
			t.Logf("verified kind=%s rows=%d total=%d", kind, count, out.Total)
		})
	}
	charts, err := client.Charts(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(charts) == 0 {
		t.Fatal("no public charts")
	}
	t.Logf("verified charts=%d", len(charts))
	chart, err := client.Chart(t.Context(), "kw:chart_93")
	if err != nil {
		t.Fatal(err)
	}
	if len(chart.Tracks) == 0 {
		t.Fatal("no chart tracks")
	}
	t.Logf("verified chart=%s tracks=%d", chart.ID, len(chart.Tracks))
	first, err := client.Playlists(t.Context(), "all", 1)
	if err != nil {
		t.Fatal(err)
	}
	second, err := client.Playlists(t.Context(), "all", 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(first) != 24 || len(second) != 24 || first[0].ID == second[0].ID {
		t.Fatal("live playlist pagination not distinct 24-item pages")
	}
	t.Logf("verified playlists p1=%d p2=%d first1=%s first2=%s", len(first), len(second), first[0].ID, second[0].ID)
	list, err := client.Playlist(t.Context(), first[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(list.Tracks) == 0 {
		t.Fatal("playlist detail returned no tracks")
	}
	t.Logf("verified playlist=%s loaded=%d", list.ID, len(list.Tracks))
	categories, err := client.PlaylistCategories(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(categories) == 0 {
		t.Fatal("no supported remote categories")
	}
	tagged, err := client.Playlists(t.Context(), categories[0].ID, 1)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("verified categories=%d tag=%s rows=%d", len(categories), categories[0].ID, len(tagged))
	raw := chart.Tracks[0].ID
	// 新构造器直接用 RID，不能依赖前面的搜索或榜单缓存。
	cold := NewKW(&http.Client{Transport: transport, Timeout: 9 * time.Second})
	track, err := cold.Track(t.Context(), raw)
	if err != nil {
		t.Fatal(err)
	}
	kwRequireNoAuthorization(t, track)
	info, err := cold.MusicInfo(t.Context(), raw)
	if err != nil {
		t.Fatal(err)
	}
	if info["songmid"] != strings.TrimPrefix(raw, "kw:") || info["MUSICRID"] != "MUSIC_"+strings.TrimPrefix(raw, "kw:") {
		t.Fatal("bad live LX RID mapping")
	}
	t.Logf("verified cold track=%s duration=%d musicInfo_songmid=%v MUSICRID=%v", raw, track.Duration, info["songmid"], info["MUSICRID"])
	lyrics, err := cold.Lyrics(t.Context(), raw)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("verified cold track=%s duration=%d musicInfo=true lyrics_lines=%d", raw, track.Duration, len(lyrics.Lines))
}

func TestKWLyricsWorkWithIDButIncompleteH5Metadata(t *testing.T) {
	body := kwTestH5("123")
	song := body["data"].(map[string]any)["songinfo"].(map[string]any)
	song["name"] = ""
	song["songName"] = ""
	song["artist"] = ""
	song["duration"] = "0"
	k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
	lyrics, err := k.Lyrics(t.Context(), "kw:123")
	if err != nil || len(lyrics.Lines) != 2 {
		t.Fatal("available lyrics incorrectly depended on missing H5 title")
	}
	failed := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(map[string]any{"status": 301, "data": nil}) })
	if _, err := failed.Lyrics(t.Context(), "kw:123"); !errors.Is(err, ErrUnavailable) {
		t.Fatal("query failure mislabeled as nonexistent song")
	}
}

// 单独验证收藏/重启后的按 RID 重建，不先执行任何搜索或榜单请求。
func TestKWLiveColdTrack(t *testing.T) {
	if os.Getenv("MELORA_KUWO_LIVE") != "1" {
		t.Skip("set MELORA_KUWO_LIVE=1 for anonymous exact-RID lookup")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	defer transport.CloseIdleConnections()
	client := NewKW(&http.Client{Transport: transport, Timeout: 9 * time.Second})
	track, err := client.Track(t.Context(), "kw:526058813")
	if err != nil {
		t.Fatal(err)
	}
	kwRequireNoAuthorization(t, track)
	info, err := client.MusicInfo(t.Context(), track.ID)
	if err != nil {
		t.Fatal(err)
	}
	if track.Title == "" || track.Duration <= 0 || info["songmid"] != "526058813" || info["rid"] != "526058813" || info["MUSICRID"] != "MUSIC_526058813" {
		t.Fatal("exact-RID metadata mismatch")
	}
	t.Logf("verified cache-free track=%s title=%s duration=%d source=%v songmid=%v rid=%v MUSICRID=%v", track.ID, track.Title, track.Duration, info["source"], info["songmid"], info["rid"], info["MUSICRID"])
}

func TestKWPaginationAndPrivatePlaylistFailClosed(t *testing.T) {
	for _, body := range []map[string]any{
		{"TOTAL": "1", "PN": "invalid", "abslist": []any{kwTestSong("123")}},
		{"TOTAL": "1", "abslist": []any{kwTestSong("123"), kwTestSong("124")}},
	} {
		k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
		if _, err := k.Search(t.Context(), "test", "track", 1); !errors.Is(err, ErrUnavailable) {
			t.Fatal("contradictory pagination accepted")
		}
	}
	for _, extra := range []map[string]any{{"ispub": false}, {"state": 1}, {"pn": 2}} {
		body := map[string]any{"result": "ok", "id": 123, "title": "不得公开的歌单", "total": 1, "musiclist": []any{kwTestSong("123")}}
		for key, value := range extra {
			body[key] = value
		}
		k := kwTestClient(t, func(*http.Request) *http.Response { return kwTestResponse(body) })
		if _, err := k.Playlist(t.Context(), "kw:playlist_123"); !errors.Is(err, ErrUnavailable) {
			t.Fatal("private/error/wrong-page playlist accepted")
		}
	}
	if kwListConsistent(20, 20, 1) || !kwListConsistent(20, 24, 0) {
		t.Fatal("end page consistency broken")
	}
}

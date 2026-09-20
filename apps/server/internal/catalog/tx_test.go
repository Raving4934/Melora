package catalog

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

const txTestMID = "0039MnYb0qxYhV"
const txTestAlbumMID = "000MkMni19ClKG"
const txTestSingerMID = "0025NhlN2yWrP4"
const txTestMediaMID = "003Qui1q2u1Zho"

type txTestDoer func(*http.Request) (*http.Response, error)

func (f txTestDoer) Do(r *http.Request) (*http.Response, error) { return f(r) }
func txTestResponse(value any) *http.Response {
	data, _ := json.Marshal(value)
	return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(strings.NewReader(string(data)))}
}
func txTestEnvelope(value any) map[string]any {
	return map[string]any{"code": 0, "req": map[string]any{"code": 0, "data": value}}
}
func txTestSong(mid string, id int64) map[string]any {
	return map[string]any{"id": id, "mid": mid, "title": "<em>测试</em> &amp; 曲目", "name": "测试曲目", "interval": 269,
		"singer": []any{map[string]any{"id": 4558, "mid": txTestSingerMID, "name": "测试歌手"}},
		"album":  map[string]any{"id": 8220, "mid": txTestAlbumMID, "name": "测试专辑"},
		"file":   map[string]any{"media_mid": txTestMediaMID, "size_128mp3": 4317292, "size_320mp3": 10792943, "size_flac": 55397039, "url": "https://not-allowed.example/audio.flac"},
		"pay":    map[string]any{"pay_play": 1, "pay_down": 1},
	}
}

type txTestRPCRequest struct {
	Comm map[string]any `json:"comm"`
	Req  struct {
		Module string         `json:"module"`
		Method string         `json:"method"`
		Param  map[string]any `json:"param"`
	} `json:"req"`
}

func txTestReadRPC(t *testing.T, r *http.Request) txTestRPCRequest {
	t.Helper()
	if r.Method != "GET" || r.URL.Host != "u.y.qq.com" || r.URL.Scheme != "https" || r.URL.Path != "/cgi-bin/musicu.fcg" {
		t.Errorf("unexpected endpoint %s %s", r.Method, r.URL)
	}
	if r.Header.Get("Referer") != "https://y.qq.com/" || r.Header.Get("Cookie") != "" || r.Header.Get("Authorization") != "" {
		t.Errorf("unexpected headers %v", r.Header)
	}
	deadline, ok := r.Context().Deadline()
	if !ok || time.Until(deadline) > 9*time.Second {
		t.Error("missing bounded deadline")
	}
	var request txTestRPCRequest
	if err := json.Unmarshal([]byte(r.URL.Query().Get("data")), &request); err != nil {
		t.Fatal(err)
	}
	if request.Comm["uin"] != float64(0) {
		t.Error("not a public guest request")
	}
	return request
}
func txTestClient(t *testing.T, handler func(txTestRPCRequest) any) *TX {
	t.Helper()
	return NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		return txTestResponse(txTestEnvelope(handler(txTestReadRPC(t, r)))), nil
	}))
}
func txTestPlaylist(id any, title string) map[string]any {
	return map[string]any{"tid": id, "title": title, "desc": "<p>真实目录元数据</p>", "song_ids": []int{1, 2}, "cover_url_medium": "http://qpic.y.qq.com/music_cover/test/300?n=1"}
}

func TestTXConstructorAndColdTrackMusicInfo(t *testing.T) {
	calls := 0
	q := txTestClient(t, func(r txTestRPCRequest) any {
		calls++
		if r.Req.Module != "music.pf_song_detail_svr" || r.Req.Method != "get_song_detail_yqq" || r.Req.Param["song_mid"] != txTestMID || r.Req.Param["song_type"] != float64(0) {
			t.Errorf("detail request: %+v", r.Req)
		}
		return map[string]any{"track_info": txTestSong(txTestMID, 97773)}
	})
	if calls != 0 {
		t.Fatal("constructor accessed network")
	}
	track, err := q.Track(context.Background(), "tx:"+txTestMID)
	if err != nil {
		t.Fatal(err)
	}
	if track.ID != "tx:"+txTestMID || track.ProviderID != "tx" || track.Title != "测试 & 曲目" || track.Artist != "测试歌手" || track.Album != "测试专辑" || track.Duration != 269 {
		t.Fatalf("track: %+v", track)
	}
	if track.CanDownload || len(track.Qualities) != 0 {
		t.Fatal("metadata incorrectly grants playback/download rights")
	}
	if !strings.HasPrefix(track.CoverURL, "https://y.gtimg.cn/") {
		t.Fatal(track.CoverURL)
	}
	info, err := q.MusicInfo(context.Background(), "tx:"+txTestMID)
	if err != nil {
		t.Fatal(err)
	}
	expected := map[string]any{"source": "tx", "songmid": txTestMID, "songId": int64(97773), "albumId": txTestAlbumMID, "albumMid": txTestAlbumMID, "albumNumericId": int64(8220), "albumName": "测试专辑", "strMediaMid": txTestMediaMID, "interval": "04:29", "duration": 269}
	for key, value := range expected {
		if !reflect.DeepEqual(info[key], value) {
			t.Errorf("musicInfo %s = %#v, want %#v", key, info[key], value)
		}
	}
	if calls != 2 {
		t.Fatalf("cold details should not require a search cache: %d", calls)
	}
	// 返回值不共享，脚本不能通过 mutation 污染后续 metadata。
	info["songmid"] = "polluted"
	again, err := q.MusicInfo(context.Background(), "tx:"+txTestMID)
	if err != nil || again["songmid"] != txTestMID {
		t.Fatal(again, err)
	}
	encoded, _ := json.Marshal(again)
	if strings.Contains(string(encoded), "not-allowed.example") {
		t.Fatal("upstream audio URL leaked")
	}
}

func TestTXResourceNamespacesRejectBeforeNetwork(t *testing.T) {
	q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
		t.Error("invalid ID reached network")
		return nil, errors.New("unexpected")
	}))
	for _, id := range []string{"wy:97773", "tx:chart_26", "tx:playlist_26", "tx:album_" + txTestMID, "tx:artist_" + txTestMID, "tx:../etc/passwd", "tx:97773", "tx:0039MnYb0qxYhV?x=1", ""} {
		t.Run("track/"+id, func(t *testing.T) {
			if _, err := q.Track(context.Background(), id); !errors.Is(err, ErrNotFound) {
				t.Fatal(err)
			}
		})
		if _, err := q.MusicInfo(context.Background(), id); !errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
		if _, err := q.Lyrics(context.Background(), id); !errors.Is(err, ErrNotFound) {
			t.Fatal(err)
		}
	}
	for _, id := range []string{"tx:" + txTestMID, "tx:playlist_26", "wy:26", "tx:chart_-1", "tx:chart_026", "tx:chart_0", "tx:chart_9223372036854775808"} {
		if _, err := q.Chart(context.Background(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("chart %q: %v", id, err)
		}
	}
	for _, id := range []string{"tx:" + txTestMID, "tx:chart_26", "wy:26", "tx:playlist_0", "tx:playlist_1/../../data"} {
		if _, err := q.Playlist(context.Background(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("playlist %q: %v", id, err)
		}
	}
	if _, err := q.Chart(context.Background(), "tx:chart_201"); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	if _, err := q.NewTracks(context.Background(), "all"); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
}

func TestTXSearchAllKindsAndPaging(t *testing.T) {
	for _, kind := range []string{"track", "playlist", "artist", "album"} {
		t.Run(kind, func(t *testing.T) {
			q := txTestClient(t, func(r txTestRPCRequest) any {
				if r.Req.Param["query"] != "测试 & 曲目" || r.Req.Param["page_num"] != float64(2) || r.Req.Param["num_per_page"] != float64(24) {
					t.Errorf("search parameters %+v", r.Req.Param)
				}
				expected := map[string]float64{"track": 0, "artist": 1, "album": 2, "playlist": 3}
				if r.Req.Param["search_type"] != expected[kind] {
					t.Error(r.Req.Param)
				}
				method := "DoSearchForQQMusicMobile"
				if r.Req.Method != method {
					t.Error(r.Req.Method)
				}
				items := []any{}
				// 多给 30 条，证明本地也执行 24 项上限，不信任上游按参数分页。
				for i := 0; i < 30; i++ {
					mid := fmt.Sprintf("%014d", i+100)
					switch kind {
					case "track":
						items = append(items, txTestSong(mid, int64(i+100)))
					case "playlist":
						items = append(items, map[string]any{"dissid": fmt.Sprint(i + 100), "dissname": fmt.Sprintf("歌单 %d", i), "logo": "https://evil.example/cover.jpg", "songnum": 11, "description": "移动端简介"})
					case "artist":
						items = append(items, map[string]any{"singerMID": mid, "singerName": fmt.Sprintf("歌手 %d", i), "singerPic": "http://y.gtimg.cn/music/a.jpg", "songNum": 5})
					case "album":
						items = append(items, map[string]any{"albummid": mid, "name": "<em>专辑</em>", "singer": "演奏者", "pic": "http://y.gtimg.cn/music/a.jpg", "song_num": 12})
					}
				}
				key := map[string]string{"track": "item_song", "playlist": "item_songlist", "artist": "singer", "album": "item_album"}[kind]
				body := map[string]any{key: items}
				return map[string]any{"body": body, "meta": map[string]any{"sum": 96, "estimate_sum": 96, "ret": 0}}
			})
			result, err := q.Search(context.Background(), "测试 & 曲目", kind, 2)
			if err != nil {
				t.Fatal(err)
			}
			if result.Total != 96 || result.Page != 2 || result.PageSize != 24 || len(result.Tracks)+len(result.Playlists)+len(result.Albums)+len(result.Artists) != 24 {
				t.Fatalf("result %+v", result)
			}
			if result.Tracks == nil || result.Playlists == nil || result.Albums == nil || result.Artists == nil {
				t.Fatal("JSON arrays must not be null")
			}
			switch kind {
			case "track":
				if result.Tracks[0].ID != "tx:00000000000100" || result.Tracks[0].CanDownload {
					t.Fatal(result.Tracks[0])
				}
			case "playlist":
				if result.Playlists[0].ID != "tx:playlist_100" || result.Playlists[0].CoverURL != "" || result.Playlists[0].TrackCount != 11 || result.Playlists[0].Description != "移动端简介" {
					t.Fatal(result.Playlists[0])
				}
			case "artist":
				if result.Artists[0].ID != "tx:artist_00000000000100" || result.Artists[0].TrackCount != 5 {
					t.Fatal(result.Artists[0])
				}
			case "album":
				if result.Albums[0].ID != "tx:album_00000000000100" || result.Albums[0].Title != "专辑" || result.Albums[0].TrackCount != 12 {
					t.Fatal(result.Albums[0])
				}
			}
		})
	}
}

func TestTXSearchDoesNotForgeEmptySuccess(t *testing.T) {
	for _, tc := range []struct {
		name string
		body any
		want error
	}{
		{"actual empty", map[string]any{"body": map[string]any{"song": map[string]any{"list": []any{}}}, "meta": map[string]any{"sum": 0}}, nil},
		{"missing list", map[string]any{"body": map[string]any{"song": map[string]any{}}, "meta": map[string]any{"sum": 0}}, ErrUnavailable},
		{"missing body", map[string]any{"meta": map[string]any{"sum": 0}}, ErrUnavailable},
		{"missing total", map[string]any{"body": map[string]any{"song": map[string]any{"list": []any{}}}, "meta": map[string]any{}}, ErrUnavailable},
		{"contradictory empty", map[string]any{"body": map[string]any{"song": map[string]any{"list": []any{}}}, "meta": map[string]any{"sum": 42}}, ErrUnavailable},
		{"malformed item", map[string]any{"body": map[string]any{"song": map[string]any{"list": []any{map[string]any{"id": 1, "mid": "bad", "name": "broken"}}}}, "meta": map[string]any{"sum": 1}}, ErrUnavailable},
		{"upstream filtered", map[string]any{"body": map[string]any{"song": map[string]any{"list": []any{}}}, "meta": map[string]any{"sum": 0, "estimate_sum": 7, "is_filter": -12}}, ErrUnavailable},
		{"captcha", map[string]any{"body": map[string]any{"song": map[string]any{"list": []any{}}}, "meta": map[string]any{"sum": 0, "safetyUrl": "https://captcha.example/"}}, ErrUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			q := txTestClient(t, func(txTestRPCRequest) any { return tc.body })
			got, err := q.Search(context.Background(), "test", "track", 1)
			if !errors.Is(err, tc.want) {
				t.Fatalf("%+v %v", got, err)
			}
		})
	}
	q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) { t.Fatal("invalid input called network"); return nil, nil }))
	for _, query := range []string{"", strings.Repeat("歌", 201), "test\nsecret", string([]byte{0xff})} {
		if _, err := q.Search(context.Background(), query, "track", 1); !errors.Is(err, ErrInput) {
			t.Errorf("%q %v", query, err)
		}
	}
	for _, page := range []int{-1, 0, 51} {
		if _, err := q.Search(context.Background(), "test", "track", page); !errors.Is(err, ErrInput) {
			t.Fatal(err)
		}
	}
	if _, err := q.Search(context.Background(), "test", "video", 1); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
}

func TestTXChartsAndDistinctResourceIDs(t *testing.T) {
	q := txTestClient(t, func(r txTestRPCRequest) any {
		chart := map[string]any{"topId": 26, "title": "热歌榜", "intro": "第一行<br>第二行", "frontPicUrl": "http://y.gtimg.cn/chart.jpg", "totalNum": 2}
		if r.Req.Method == "GetAll" {
			return map[string]any{"group": []any{map[string]any{"groupName": "巅峰榜", "toplist": []any{chart, chart, map[string]any{"topId": 201, "title": "MV榜"}}}}}
		}
		if r.Req.Method != "GetDetail" || r.Req.Param["topid"] != float64(26) || r.Req.Param["offset"] != float64(0) || r.Req.Param["num"] != float64(300) {
			t.Error(r.Req)
		}
		return map[string]any{"data": chart, "songInfoList": []any{txTestSong(txTestMID, 97773), txTestSong("00000000000002", 2)}}
	})
	charts, err := q.Charts(context.Background())
	if err != nil || len(charts) != 1 {
		t.Fatal(charts, err)
	}
	if charts[0].ID != "tx:chart_26" || charts[0].Category != "巅峰榜" || charts[0].TrackCount != 2 || charts[0].Description != "第一行\n第二行" {
		t.Fatal(charts)
	}
	chart, err := q.Chart(context.Background(), charts[0].ID)
	if err != nil || len(chart.Tracks) != 2 || chart.Tracks[0].ID != "tx:"+txTestMID {
		t.Fatal(chart, err)
	}
	if chart.ID == "tx:playlist_26" {
		t.Fatal("chart and playlist IDs overlap")
	}
}

func TestTXPlaylistPagingAndCategories(t *testing.T) {
	var params []txTestRPCRequest
	q := txTestClient(t, func(r txTestRPCRequest) any {
		params = append(params, r)
		switch r.Req.Method {
		case "get_playlist_by_tag":
			items := []any{}
			for i := 0; i < 30; i++ {
				items = append(items, txTestPlaylist(fmt.Sprint(100+i), fmt.Sprintf("歌单 %d", i)))
			}
			return map[string]any{"total": 100, "v_playlist": items}
		case "get_category_content":
			basic := txTestPlaylist(9607499637, "官方歌单")
			delete(basic, "cover_url_medium")
			basic["song_cnt"] = 200
			basic["cover"] = map[string]any{"medium_url": "https://music-file.y.qq.com/list/cover.jpg"}
			return map[string]any{"content": map[string]any{"total_cnt": 48, "v_item": []any{map[string]any{"basic": basic}}}}
		case "get_all_categories":
			return map[string]any{"v_group": []any{map[string]any{"group_name": "主题", "v_item": []any{map[string]any{"id": 3317, "name": "官方歌单", "status": 0}, map[string]any{"id": 3317, "name": "重复", "status": 0}, map[string]any{"id": 44, "name": "关闭", "status": 1}}}}}
		default:
			t.Fatal(r.Req.Method)
			return nil
		}
	})
	result, err := q.Playlists(context.Background(), "all", 2)
	if err != nil || len(result) != 24 {
		t.Fatal(result, err)
	}
	first := params[0].Req.Param
	for k, v := range map[string]float64{"size": 24, "sin": 24, "cur_page": 2, "order": 5} {
		if first[k] != v {
			t.Errorf("%s: %v", k, first)
		}
	}
	if result[0].ID != "tx:playlist_100" || result[0].TrackCount != 2 {
		t.Fatal(result[0])
	}
	filtered, err := q.Playlists(context.Background(), "tx:category_3317", 2)
	if err != nil || len(filtered) != 1 || filtered[0].TrackCount != 200 || filtered[0].Category != "tx:category_3317" {
		t.Fatal(filtered, err)
	}
	category := params[1].Req.Param
	if category["page"] != float64(1) || category["category_id"] != float64(3317) || category["size"] != float64(24) {
		t.Fatal(category)
	}
	categories, err := q.PlaylistCategories(context.Background())
	if err != nil || len(categories) != 1 || categories[0].ID != "tx:category_3317" || categories[0].Group != "主题" {
		t.Fatal(categories, err)
	}
	before := len(params)
	for _, category := range []string{"tx:chart_26", "-1", "0001", "华语", "1&token=x"} {
		if _, err := q.Playlists(context.Background(), category, 1); !errors.Is(err, ErrInput) {
			t.Fatal(category, err)
		}
	}
	for _, page := range []int{0, 51} {
		if _, err := q.Playlists(context.Background(), "all", page); !errors.Is(err, ErrInput) {
			t.Fatal(err)
		}
	}
	if before != len(params) {
		t.Fatal("invalid paging sent request")
	}
}

func TestTXPlaylistDetailFetchesAllPublicPages(t *testing.T) {
	calls := 0
	q := txTestClient(t, func(r txTestRPCRequest) any {
		calls++
		if r.Req.Module != "music.srfDissInfo.aiDissInfo" || r.Req.Method != "uniform_get_Dissinfo" || r.Req.Param["disstid"] != float64(7039749142) || r.Req.Param["song_num"] != float64(100) {
			t.Fatal(r.Req)
		}
		offset := int(r.Req.Param["song_begin"].(float64))
		if offset != (calls-1)*100 {
			t.Fatal(offset, calls)
		}
		items := []any{}
		for i := offset; i < min(offset+100, 125); i++ {
			items = append(items, txTestSong(fmt.Sprintf("%014d", i+1), int64(i+1)))
		}
		more := 0
		if offset == 0 {
			more = 1
		}
		return map[string]any{"code": 0, "subcode": 0, "dirinfo": map[string]any{"id": "7039749142", "title": "公开歌单", "picurl": "https://qpic.y.qq.com/a.jpg", "songnum": 125}, "songlist": items, "total_song_num": 125, "hasmore": more}
	})
	result, err := q.Playlist(context.Background(), "tx:playlist_7039749142")
	if err != nil || calls != 2 || len(result.Tracks) != 125 || result.TrackCount != 125 {
		t.Fatalf("calls=%d count=%d err=%v", calls, len(result.Tracks), err)
	}
	if result.Tracks[0].ID != "tx:00000000000001" || result.Tracks[124].ID != "tx:00000000000125" {
		t.Fatal("order lost")
	}
}

func TestTXPlaylistDoesNotReturnPartialSuccessAfterFailure(t *testing.T) {
	calls := 0
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		txTestReadRPC(t, r)
		calls++
		if calls == 2 {
			return txTestResponse(map[string]any{"code": 0, "req": map[string]any{"code": 2001}}), nil
		}
		return txTestResponse(txTestEnvelope(map[string]any{"dirinfo": map[string]any{"id": 26, "title": "public"}, "songlist": []any{txTestSong(txTestMID, 1)}, "total_song_num": 2, "hasmore": 1})), nil
	}))
	got, err := q.Playlist(context.Background(), "tx:playlist_26")
	if !errors.Is(err, ErrUnavailable) || len(got.Tracks) != 0 {
		t.Fatal(got, err)
	}
	// 如果服务器忽略 offset 重复返回同一页，应终止而非无限翻页。
	q = txTestClient(t, func(txTestRPCRequest) any {
		return map[string]any{"dirinfo": map[string]any{"id": 26, "title": "public"}, "songlist": []any{txTestSong(txTestMID, 1)}, "total_song_num": 2, "hasmore": 1}
	})
	if _, err = q.Playlist(context.Background(), "tx:playlist_26"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestTXBusinessErrorsMissingSchemasAndHTTPFailures(t *testing.T) {
	cases := []string{`{}`, `null`, `{"code":0}`, `{"code":2001}`, `{"code":0,"req":{}}`, `{"code":0,"req":{"code":2001,"data":{}}}`, `{"code":0,"req":{"code":0}}`, `{"code":0,"req":{"code":0,"data":null}}`, `{"code":0,"req":{"code":0,"data":{"code":0,"subcode":4000,"msg":"private token=SECRET"}}}`, `{"code":0,"req":{"code":0,"data":{}}}`, `{"code":0,"req":{"code":0,"data":[]}}`, `<html>captcha</html>`}
	for _, raw := range cases {
		t.Run(raw, func(t *testing.T) {
			q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(raw))}, nil
			}))
			checks := []func() error{
				func() error { _, err := q.Search(context.Background(), "test", "track", 1); return err },
				func() error { _, err := q.Charts(context.Background()); return err },
				func() error { _, err := q.Chart(context.Background(), "tx:chart_26"); return err },
				func() error { _, err := q.Playlists(context.Background(), "all", 1); return err },
				func() error { _, err := q.Playlists(context.Background(), "tx:category_3317", 1); return err },
				func() error { _, err := q.PlaylistCategories(context.Background()); return err },
				func() error { _, err := q.Playlist(context.Background(), "tx:playlist_26"); return err },
				func() error { _, err := q.Track(context.Background(), "tx:"+txTestMID); return err },
			}
			for _, check := range checks {
				err := check()
				if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), "SECRET") || strings.Contains(err.Error(), "WY") {
					t.Errorf("unsafe success/error: %v", err)
				}
			}
		})
	}
	for _, status := range []int{302, 401, 403, 404, 429, 500} {
		q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("{}"))}, nil
		}))
		if _, err := q.Charts(context.Background()); !errors.Is(err, ErrUnavailable) {
			t.Fatal(status, err)
		}
	}
	if _, err := NewTX(nil).Charts(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestTXLyricsPlainBase64AndErrors(t *testing.T) {
	lyric := "[00:01.50]测试 &amp; 歌词\n[00:03.00]第二行"
	for _, encoded := range []string{lyric, base64.StdEncoding.EncodeToString([]byte(lyric))} {
		q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
			if r.Method != "GET" || r.URL.Host != "c.y.qq.com" || r.URL.Query().Get("songmid") != txTestMID || r.URL.Query().Get("nobase64") != "1" {
				t.Fatal(r.URL)
			}
			return txTestResponse(map[string]any{"code": 0, "retcode": 0, "subcode": 0, "lyric": encoded}), nil
		}))
		got, err := q.Lyrics(context.Background(), "tx:"+txTestMID)
		if err != nil || len(got.Lines) != 2 || got.Lines[0].Time != 1.5 || got.Lines[0].Text != "测试 & 歌词" || got.Source != "扣扣" {
			t.Fatal(got, err)
		}
	}
	for _, value := range []any{map[string]any{"code": 0}, map[string]any{"code": 0, "subcode": 4000, "lyric": ""}, map[string]any{"code": 2001, "lyric": ""}, map[string]any{"code": 0, "lyric": "invalid-base64"}, map[string]any{"code": 0, "lyric": strings.Repeat("[", (256<<10)+1)}} {
		q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) { return txTestResponse(value), nil }))
		if _, err := q.Lyrics(context.Background(), "tx:"+txTestMID); !errors.Is(err, ErrUnavailable) {
			t.Fatal(value, err)
		}
	}
	q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
		return txTestResponse(map[string]any{"code": 0, "lyric": ""}), nil
	}))
	if result, err := q.Lyrics(context.Background(), "tx:"+txTestMID); err != nil || result.Lines == nil || len(result.Lines) != 0 {
		t.Fatal(result, err)
	}
	q = NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
		return txTestResponse(map[string]any{"code": 0, "lyric": "[ti:没有同步歌词]"}), nil
	}))
	if _, err := q.Lyrics(context.Background(), "tx:"+txTestMID); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
}

func TestTXImageValidationAndConversion(t *testing.T) {
	for _, value := range []string{"https://evil.example/a.jpg", "https://y.gtimg.cn.evil.test/a.jpg", "https://y.gtimg.cn:443/a.jpg", "https://user@y.gtimg.cn/a.jpg", "http://127.0.0.1/a.jpg", "https://y.gtimg.cn./a.jpg", "data:image/svg+xml,secret"} {
		if txImage(value) != "" {
			t.Errorf("untrusted cover %q", value)
		}
	}
	for _, value := range []string{"http://y.gtimg.cn/a.jpg", "//qpic.y.qq.com/a.jpg", "https://p.qpic.cn/a.jpg", "https://music-file.y.qq.com/a.jpg"} {
		if !strings.HasPrefix(txImage(value), "https://") {
			t.Error(value)
		}
	}
	raw, _ := json.Marshal(txTestSong(txTestMID, 97773))
	var song txSong
	_ = json.Unmarshal(raw, &song)
	song.Album.MID = "../../etc"
	song.Interval = -12
	track, ok := txTrack(song)
	if !ok || track.Duration != 0 || track.CoverURL != txCover(txTestSingerMID, true) {
		t.Fatal(track)
	}
	song.MID = "../../etc"
	if _, ok := txTrack(song); ok {
		t.Fatal("unsafe songmid")
	}
}

// 使用本地 httptest transport 验证 HTTP 边界，不修改生产端点或生产 GuardedClient。
func TestTXInjectedHTTPBoundary(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/cgi-bin/musicu.fcg" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(txTestEnvelope(map[string]any{"track_info": txTestSong(txTestMID, 97773)}))
	}))
	defer server.Close()
	target, _ := url.Parse(server.URL)
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		txTestReadRPC(t, r)
		copy := r.Clone(r.Context())
		u := *r.URL
		u.Scheme = target.Scheme
		u.Host = target.Host
		copy.URL = &u
		copy.Host = target.Host
		return server.Client().Do(copy)
	}))
	if _, err := q.Track(context.Background(), "tx:"+txTestMID); err != nil {
		t.Fatal(err)
	}
	q = NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		txTestReadRPC(t, r)
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(strings.Repeat(" ", 1<<20) + "{}"))}, nil
	}))
	if _, err := q.Charts(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatal("oversized response accepted", err)
	}
	q = NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) { <-r.Context().Done(); return nil, r.Context().Err() }))
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Millisecond)
	defer cancel()
	start := time.Now()
	if _, err := q.Charts(ctx); !errors.Is(err, ErrUnavailable) || time.Since(start) > time.Second {
		t.Fatal("caller cancellation ignored", err)
	}
}

func TestTXConcurrentColdReads(t *testing.T) {
	q := txTestClient(t, func(txTestRPCRequest) any { return map[string]any{"track_info": txTestSong(txTestMID, 97773)} })
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Go(func() {
			track, err := q.Track(context.Background(), "tx:"+txTestMID)
			if err != nil || track.ID != "tx:"+txTestMID {
				t.Error(track, err)
			}
		})
	}
	wg.Wait()
}

// 可选：回放本轮独立实网证据。默认测试不依赖证据文件或公网。
func TestTXPublicProbeReplay(t *testing.T) {
	dir := os.Getenv("MELORA_TX_PROBE_DIR")
	if dir == "" {
		t.Skip("set MELORA_TX_PROBE_DIR to replay independently recorded TX responses")
	}
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		name := "lyrics"
		if r.URL.Host == "u.y.qq.com" {
			request := txTestReadRPC(t, r)
			switch request.Req.Method {
			case "get_song_detail_yqq":
				name = "track"
			case "GetAll":
				name = "charts"
			case "GetDetail":
				name = "chart-full"
			case "get_all_categories":
				name = "categories"
			case "get_playlist_by_tag":
				name = "playlists"
				if request.Req.Param["cur_page"] == float64(2) {
					name = "playlists-page2"
				}
			case "get_category_content":
				name = "playlists-category"
			case "uniform_get_Dissinfo":
				name = "playlist-modern-public"
				if request.Req.Param["song_begin"] == float64(100) {
					name = "playlist-modern-public-page2"
				}
			case "DoSearchForQQMusicDesktop":
				name = map[float64]string{0: "search-desktop", 1: "search-artist", 3: "search-playlist"}[request.Req.Param["search_type"].(float64)]
			case "DoSearchForQQMusicMobile":
				name = "search-album-mobile-artist"
			default:
				t.Fatalf("unrecorded method %s", request.Req.Method)
			}
		}
		raw, err := os.ReadFile(filepath.Join(dir, "qq-"+name+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var evidence struct {
			Status   int             `json:"status"`
			Response json.RawMessage `json:"response"`
		}
		if err = json.Unmarshal(raw, &evidence); err != nil {
			t.Fatal(err)
		}
		// 证据外层缩进不属于实际 HTTP body，回放前压缩，保留 1 MiB 网络响应约束。
		var compact bytes.Buffer
		if err := json.Compact(&compact, evidence.Response); err != nil {
			t.Fatal(err)
		}
		return &http.Response{StatusCode: evidence.Status, Body: io.NopCloser(&compact)}, nil
	}))
	for _, kind := range []string{"track", "artist", "album", "playlist"} {
		result, err := q.Search(context.Background(), "周杰伦", kind, 1)
		if err != nil || result.Total == 0 {
			t.Fatalf("%s search: %v", kind, err)
		}
	}
	if got, err := q.Track(context.Background(), "tx:"+txTestMID); err != nil || got.Title != "晴天" || got.Duration != 269 {
		t.Fatal(got, err)
	}
	if got, err := q.MusicInfo(context.Background(), "tx:"+txTestMID); err != nil || got["songId"] != int64(97773) || got["strMediaMid"] != txTestMediaMID {
		t.Fatal(got, err)
	}
	if got, err := q.Charts(context.Background()); err != nil || len(got) < 10 {
		t.Fatal(len(got), err)
	}
	if got, err := q.Chart(context.Background(), "tx:chart_26"); err != nil || len(got.Tracks) != 300 {
		t.Fatal(len(got.Tracks), err)
	}
	if got, err := q.PlaylistCategories(context.Background()); err != nil || len(got) < 10 {
		t.Fatal(len(got), err)
	}
	for _, page := range []int{1, 2} {
		if got, err := q.Playlists(context.Background(), "all", page); err != nil || len(got) != 24 {
			t.Fatal(len(got), err)
		}
	}
	if got, err := q.Playlists(context.Background(), "tx:category_3317", 1); err != nil || len(got) != 24 {
		t.Fatal(len(got), err)
	}
	if got, err := q.Playlist(context.Background(), "tx:playlist_9607499637"); err != nil || len(got.Tracks) != 200 {
		t.Fatal(len(got.Tracks), err)
	}
	if got, err := q.Lyrics(context.Background(), "tx:"+txTestMID); err != nil || len(got.Lines) == 0 {
		t.Fatal(err)
	}
}

func TestTXMismatchedDetailsAndMediaMID(t *testing.T) {
	q := txTestClient(t, func(txTestRPCRequest) any { return map[string]any{"track_info": txTestSong("00000000000002", 2)} })
	if _, err := q.Track(context.Background(), "tx:"+txTestMID); !errors.Is(err, ErrNotFound) {
		t.Fatal("different song returned", err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any {
		song := txTestSong(txTestMID, 97773)
		song["file"] = map[string]any{}
		return map[string]any{"track_info": song}
	})
	if _, err := q.Track(context.Background(), "tx:"+txTestMID); err != nil {
		t.Fatal("metadata should not require media capability", err)
	}
	if _, err := q.MusicInfo(context.Background(), "tx:"+txTestMID); !errors.Is(err, ErrUnavailable) {
		t.Fatal("missing media_mid forged", err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any {
		return map[string]any{"data": map[string]any{"topId": 27, "title": "different"}, "songInfoList": []any{txTestSong(txTestMID, 1)}}
	})
	if _, err := q.Chart(context.Background(), "tx:chart_26"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any {
		return map[string]any{"dirinfo": map[string]any{"id": 27, "title": "different"}, "songlist": []any{}, "total_song_num": 0, "hasmore": 0}
	})
	if _, err := q.Playlist(context.Background(), "tx:playlist_26"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	for _, level := range []string{"top", "data"} {
		q = NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
			data := map[string]any{"track_info": txTestSong(txTestMID, 97773)}
			envelope := txTestEnvelope(data)
			if level == "top" {
				envelope["subcode"] = 4000
			} else {
				data["subcode"] = 4000
			}
			return txTestResponse(envelope), nil
		}))
		if _, err := q.Track(context.Background(), "tx:"+txTestMID); !errors.Is(err, ErrUnavailable) {
			t.Fatal("nonzero subcode accepted", level, err)
		}
	}
}

func TestTXListEmptyVersusMalformed(t *testing.T) {
	q := txTestClient(t, func(txTestRPCRequest) any { return map[string]any{"total": 0, "v_playlist": []any{}} })
	if got, err := q.Playlists(context.Background(), "all", 2); err != nil || got == nil || len(got) != 0 {
		t.Fatal(got, err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any { return map[string]any{"total": 24, "v_playlist": []any{}} })
	if _, err := q.Playlists(context.Background(), "all", 1); !errors.Is(err, ErrUnavailable) {
		t.Fatal("contradictory total accepted", err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any {
		return map[string]any{"total": 1, "v_playlist": []any{map[string]any{"tid": 1}}}
	})
	if _, err := q.Playlists(context.Background(), "all", 1); !errors.Is(err, ErrUnavailable) {
		t.Fatal("malformed playlist accepted", err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any {
		return map[string]any{"dirinfo": map[string]any{"id": 26, "title": "真实空歌单"}, "songlist": []any{}, "total_song_num": 0, "hasmore": 0}
	})
	if got, err := q.Playlist(context.Background(), "tx:playlist_26"); err != nil || got.Tracks == nil || got.TrackCount != 0 {
		t.Fatal(got, err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any {
		return map[string]any{"dirinfo": map[string]any{"id": 26, "title": "incomplete"}, "songlist": []any{}, "total_song_num": 99, "hasmore": 0}
	})
	if _, err := q.Playlist(context.Background(), "tx:playlist_26"); !errors.Is(err, ErrUnavailable) {
		t.Fatal("fake empty playlist", err)
	}
	q = txTestClient(t, func(txTestRPCRequest) any {
		return map[string]any{"data": map[string]any{"topId": 26, "title": "partial", "totalNum": 30}, "songInfoList": []any{txTestSong(txTestMID, 1)}}
	})
	if got, err := q.Chart(context.Background(), "tx:chart_26"); err != nil || got.TrackCount != 1 || !strings.Contains(got.Description, "1 首，共 30 首") {
		t.Fatal("partial list must be disclosed", got, err)
	}
}

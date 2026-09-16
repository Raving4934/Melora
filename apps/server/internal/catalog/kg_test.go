package catalog

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type kgTestDoer func(*http.Request) (*http.Response, error)

func (f kgTestDoer) Do(r *http.Request) (*http.Response, error) { return f(r) }
func kgTestResponse(value any) *http.Response {
	data, _ := json.Marshal(value)
	return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(strings.NewReader(string(data)))}
}
func kgTestHash(n int) string { return fmt.Sprintf("%032X", n) }
func kgTestSong(n int) map[string]any {
	return map[string]any{"hash": kgTestHash(n), "filename": "测试歌手 - 测试歌曲 " + strconv.Itoa(n), "songname": "测试歌曲 " + strconv.Itoa(n), "audio_id": n + 1000, "duration": 125, "album_id": "77", "filesize": 2097152,
		"320hash": kgTestHash(n + 100), "320filesize": 4194304, "sqhash": kgTestHash(n + 200), "sqfilesize": 16777216, "hash_high": kgTestHash(n + 300), "filesize_high": 33554432,
		"trans_param": map[string]any{"union_cover": "http://imge.kugou.com/stdmusic/{size}/test.jpg"}}
}
func kgTestColdSong(n int) map[string]any {
	return map[string]any{"status": 1, "errcode": 0, "hash": kgTestHash(n), "req_hash": kgTestHash(n), "audio_id": strconv.Itoa(9000 + n), "album_audio_id": 123456, "albumid": "77", "songName": "测试歌曲", "singerName": "测试歌手", "timeLength": 125, "fileSize": 2097152,
		"imgUrl": "http://imge.kugou.com/stdmusic/{size}/test.jpg", "url": "https://audio.example.test/private.mp3?token=never-export", "albumname": "测试专辑",
		"extra": map[string]any{"128hash": kgTestHash(n), "128filesize": 2097152, "128timelength": 125000, "320hash": kgTestHash(n + 100), "320filesize": 4194304, "sqhash": kgTestHash(n + 200), "sqfilesize": 16777216, "highhash": kgTestHash(n + 300), "highfilesize": 33554432}}
}
func kgTestPlaylist(n int) map[string]any {
	return map[string]any{"specialid": n, "specialname": "公开歌单 " + strconv.Itoa(n), "songcount": 79, "intro": "真实接口形状的固定测试数据", "imgurl": "http://imgessl.kugou.com/collection/{size}/cover.jpg"}
}
func kgTestAPI(data any) map[string]any {
	return map[string]any{"status": 1, "errcode": 0, "data": data}
}

func TestKGConstructorAndInvalidResourceIDsNeverUseNetwork(t *testing.T) {
	var calls atomic.Int32
	k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) {
		calls.Add(1)
		return nil, errors.New("unexpected request")
	}))
	if calls.Load() != 0 {
		t.Fatal("constructor performed network request")
	}
	for _, raw := range []string{"wy:1", "kg:chart_8888", "kg:playlist_7", "kg:album_7", "kg:artist_7", "kg:../private", "kg:" + strings.Repeat("0", 32), "kg:" + kgTestHash(1) + "?token=x"} {
		if _, err := k.Track(context.Background(), raw); !errors.Is(err, ErrInput) {
			t.Fatal(raw, err)
		}
		if _, err := k.MusicInfo(context.Background(), raw); !errors.Is(err, ErrInput) {
			t.Fatal(raw, err)
		}
		if _, err := k.Lyrics(context.Background(), raw); !errors.Is(err, ErrInput) {
			t.Fatal(raw, err)
		}
	}
	for _, raw := range []string{"kg:chart_7", "kg:playlist_0", "kg:playlist_01", "kg:playlist_../1", "wy:playlist_1"} {
		if _, err := k.Playlist(context.Background(), raw); !errors.Is(err, ErrInput) {
			t.Fatal(raw, err)
		}
	}
	if _, err := k.Chart(context.Background(), "kg:playlist_8888"); !errors.Is(err, ErrInput) {
		t.Fatal(err)
	}
	if _, err := k.PlaylistCategories(context.Background()); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	if _, err := k.Playlists(context.Background(), "2005", 1); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	for _, page := range []int{0, 51} {
		if _, err := k.Playlists(context.Background(), "all", page); !errors.Is(err, ErrInput) {
			t.Fatal(err)
		}
	}
	for _, input := range []struct {
		query, kind string
		page        int
	}{{"", "track", 1}, {"x", "unknown", 1}, {"x\n", "track", 0}, {strings.Repeat("界", 201), "track", 1}, {"x", "track", 51}, {"a\x00b", "track", 1}} {
		if _, err := k.Search(context.Background(), input.query, input.kind, input.page); !errors.Is(err, ErrInput) {
			t.Fatal(input, err)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid identifiers or unsupported capabilities made network requests", calls.Load())
	}
	if _, err := NewKG(nil).Charts(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestKGTrackSearchRealFieldsAndInputEscaping(t *testing.T) {
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		if r.Method != "GET" || r.URL.Host != "songsearch.kugou.com" || r.URL.Path != "/song_search_v2" || r.URL.Query().Get("keyword") != "歌手 & ?" || r.URL.Query().Get("page") != "2" || r.URL.Query().Get("pagesize") != "24" {
			t.Errorf("wrong metadata request %s", r.URL)
		}
		rows := []any{}
		for n := 1; n <= 27; n++ {
			rows = append(rows, map[string]any{"FileHash": kgTestHash(n), "SongName": "<em>测试</em> &amp; 名称", "SingerName": "公开艺人", "Duration": 125, "Audioid": n + 1000, "AlbumID": "77", "AlbumName": "真实专辑字段", "Image": "http://imge.kugou.com/{size}/cover.jpg", "FileSize": 1000})
		}
		return kgTestResponse(map[string]any{"status": 1, "error_code": 0, "data": map[string]any{"lists": rows, "total": 77}}), nil
	}))
	got, err := k.Search(context.Background(), " 歌手 & ? ", "track", 2)
	if err != nil || len(got.Tracks) != 24 || got.Total != 77 || got.Page != 2 || got.PageSize != 24 {
		t.Fatal(got, err)
	}
	track := got.Tracks[0]
	if track.ID != "kg:"+kgTestHash(1) || track.ProviderID != "kg" || track.Title != "测试 & 名称" || track.Artist != "公开艺人" || track.Album != "真实专辑字段" || track.Duration != 125 || track.CoverURL != "https://imge.kugou.com/400/cover.jpg" || track.CanDownload || len(track.Qualities) != 0 {
		t.Fatal(track)
	}
}

func TestKGSearchAlbumPlaylistArtistNamespacesAndArtistPaging(t *testing.T) {
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host != "mobiles.kugou.com" || r.Method != http.MethodGet {
			t.Fatal(r.URL)
		}
		switch r.URL.Path {
		case "/api/v3/search/album":
			return kgTestResponse(kgTestAPI(map[string]any{"total": 1, "info": []any{map[string]any{"albumid": 7, "albumname": "专辑", "singername": "歌手", "songcount": 12, "imgurl": "http://c1.kgimg.com/cover.jpg"}}})), nil
		case "/api/v3/search/special":
			return kgTestResponse(kgTestAPI(map[string]any{"total": 1, "info": []any{kgTestPlaylist(7)}})), nil
		case "/api/v3/search/singer":
			if r.URL.Query().Get("page") != "1" {
				t.Fatal("non-paginating upstream should be sliced locally")
			}
			rows := []any{}
			for n := 1; n <= 50; n++ {
				rows = append(rows, map[string]any{"singerid": n, "singername": "歌手 " + strconv.Itoa(n)})
			}
			return kgTestResponse(kgTestAPI(rows)), nil
		default:
			t.Fatal("unexpected endpoint", r.URL)
			return nil, nil
		}
	}))
	album, err := k.Search(context.Background(), "q", "album", 1)
	if err != nil || len(album.Albums) != 1 || album.Albums[0].ID != "kg:album_7" || album.Albums[0].TrackCount != 12 {
		t.Fatal(album, err)
	}
	playlist, err := k.Search(context.Background(), "q", "playlist", 1)
	if err != nil || len(playlist.Playlists) != 1 || playlist.Playlists[0].ID != "kg:playlist_7" {
		t.Fatal(playlist, err)
	}
	for page, want := range map[int]int{1: 24, 2: 24, 3: 2, 4: 0} {
		got, err := k.Search(context.Background(), "q", "artist", page)
		if err != nil || len(got.Artists) != want || got.Total != 50 || got.PageSize != 24 {
			t.Fatal(page, got, err)
		}
		if want > 0 && got.Artists[0].ID != "kg:artist_"+strconv.Itoa((page-1)*24+1) {
			t.Fatal(got.Artists[0])
		}
	}
}

func TestKGColdTrackAndMusicInfoHashQualityMapping(t *testing.T) {
	var calls atomic.Int32
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		if r.URL.Host != "m.kugou.com" || r.URL.Path != "/app/i/getSongInfo.php" || r.URL.Query().Get("cmd") != "playInfo" || r.URL.Query().Get("hash") != kgTestHash(10) {
			t.Fatal(r.URL)
		}
		return kgTestResponse(kgTestColdSong(10)), nil
	}))
	raw := "kg:" + strings.ToLower(kgTestHash(10))
	track, err := k.Track(context.Background(), raw)
	if err != nil || track.ID != "kg:"+kgTestHash(10) || track.Title != "测试歌曲" || track.Duration != 125 || track.CanDownload {
		t.Fatal(track, err)
	}
	info, err := k.MusicInfo(context.Background(), raw)
	if err != nil {
		t.Fatal(err)
	}
	if info["songmid"] != int64(9010) || info["songmid"] == int64(123456) || info["hash"] != kgTestHash(10) || info["source"] != "kg" || info["albumId"] != int64(77) || info["interval"] != "02:05" {
		t.Fatal(info)
	}
	quality := info["_types"].(map[string]any)
	for typ, n := range map[string]int{"128k": 10, "320k": 110, "flac": 210, "flac24bit": 310} {
		if quality[typ].(map[string]any)["hash"] != kgTestHash(n) {
			t.Fatal(typ, quality)
		}
	}
	encoded, _ := json.Marshal(info)
	if strings.Contains(string(encoded), "never-export") || strings.Contains(string(encoded), "private.mp3") {
		t.Fatal("audio URL escaped metadata boundary")
	}
	quality["320k"].(map[string]any)["hash"] = "mutated"
	again, err := k.MusicInfo(context.Background(), raw)
	if err != nil || again["_types"].(map[string]any)["320k"].(map[string]any)["hash"] != kgTestHash(110) {
		t.Fatal(again, err)
	}
	if calls.Load() != 3 {
		t.Fatal("cold track must not depend on search state", calls.Load())
	}
}

func TestKGZeroQualityHashesAndWrongReturnedIdentityRejected(t *testing.T) {
	row := kgTestColdSong(1)
	extra := row["extra"].(map[string]any)
	extra["320hash"] = strings.Repeat("0", 32)
	extra["sqfilesize"] = 0
	k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) { return kgTestResponse(row), nil }))
	info, err := k.MusicInfo(context.Background(), "kg:"+kgTestHash(1))
	if err != nil {
		t.Fatal(err)
	}
	qualities := info["_types"].(map[string]any)
	if _, ok := qualities["320k"]; ok {
		t.Fatal("zero sentinel hash became a quality")
	}
	if _, ok := qualities["flac"]; ok {
		t.Fatal("zero sized file became a quality")
	}
	row["hash"] = kgTestHash(2)
	if _, err := k.Track(context.Background(), "kg:"+kgTestHash(1)); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	row["hash"] = kgTestHash(1)
	row["audio_id"] = 0
	if _, err := k.MusicInfo(context.Background(), "kg:"+kgTestHash(1)); !errors.Is(err, ErrUnavailable) {
		t.Fatal("missing songmid was faked", err)
	}
}

func TestKGPlaylistsTwentyFourItemsLogicalPagesOverThirtyItemSource(t *testing.T) {
	var requested []int
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host != "m.kugou.com" || r.URL.Path != "/plist/index" || r.URL.Query().Get("json") != "true" || r.URL.Query().Get("pagesize") != "30" {
			t.Fatal(r.URL)
		}
		p, _ := strconv.Atoi(r.URL.Query().Get("page"))
		requested = append(requested, p)
		rows := []any{}
		for n := (p-1)*30 + 1; n <= min(p*30, 65); n++ {
			rows = append(rows, kgTestPlaylist(n))
		}
		return kgTestResponse(map[string]any{"pagesize": 30, "plist": map[string]any{"pagesize": 30, "list": map[string]any{"total": 65, "info": rows}}}), nil
	}))
	for _, test := range []struct {
		page, count, first int
		requests           []int
	}{{1, 24, 1, []int{1}}, {2, 24, 25, []int{1, 2}}, {3, 17, 49, []int{2, 3}}, {4, 0, 0, []int{3}}} {
		requested = nil
		got, err := k.Playlists(context.Background(), "all", test.page)
		if err != nil || len(got) != test.count || !reflect.DeepEqual(requested, test.requests) {
			t.Fatal(test, got, requested, err)
		}
		for i, item := range got {
			if item.ID != "kg:playlist_"+strconv.Itoa(test.first+i) {
				t.Fatal("pagination overlapped/skipped", item.ID)
			}
		}
	}
}

func TestKGPartialOrDuplicateRecommendationPagesAreErrors(t *testing.T) {
	for _, which := range []string{"partial", "duplicate", "size_changed"} {
		t.Run(which, func(t *testing.T) {
			rows := []any{}
			for n := 1; n <= 30; n++ {
				rows = append(rows, kgTestPlaylist(n))
			}
			size := 30
			switch which {
			case "partial":
				rows = rows[:3]
			case "duplicate":
				rows[1] = rows[0]
			case "size_changed":
				size = 20
			}
			k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) {
				return kgTestResponse(map[string]any{"plist": map[string]any{"pagesize": size, "list": map[string]any{"total": 60, "info": rows}}}), nil
			}))
			if _, err := k.Playlists(context.Background(), "", 1); !errors.Is(err, ErrUnavailable) {
				t.Fatal(err)
			}
		})
	}
}

func TestKGChartsPlaylistDetailAndTruncationAreHonest(t *testing.T) {
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/rank/list":
			return kgTestResponse(map[string]any{"rank": map[string]any{"total": 1, "list": []any{map[string]any{"rankid": 8888, "rankname": "TOP500", "intro": "上游说明", "imgurl": "http://imge.kugou.com/{size}/rank.png"}}}}), nil
		case "/api/v3/rank/song":
			if r.URL.Query().Get("rankid") != "8888" || r.URL.Query().Get("pagesize") != "100" {
				t.Fatal(r.URL)
			}
			rows := []any{}
			for n := 1; n <= 100; n++ {
				rows = append(rows, kgTestSong(n))
			}
			return kgTestResponse(kgTestAPI(map[string]any{"total": 500, "info": rows})), nil
		case "/api/v3/special/info":
			return kgTestResponse(kgTestAPI(kgTestPlaylist(8888))), nil
		case "/api/v3/special/song":
			if r.URL.Query().Get("specialid") != "8888" {
				t.Fatal(r.URL)
			}
			return kgTestResponse(kgTestAPI(map[string]any{"total": 1, "info": []any{kgTestSong(8)}})), nil
		default:
			t.Fatal(r.URL)
			return nil, nil
		}
	}))
	charts, err := k.Charts(context.Background())
	if err != nil || len(charts) != 1 || charts[0].ID != "kg:chart_8888" {
		t.Fatal(charts, err)
	}
	chart, err := k.Chart(context.Background(), "kg:chart_8888")
	if err != nil || len(chart.Tracks) != 100 || chart.TrackCount != 500 || !strings.Contains(chart.Description, "当前加载 100 首，共 500 首") {
		t.Fatal(chart, err)
	}
	playlist, err := k.Playlist(context.Background(), "kg:playlist_8888")
	if err != nil || len(playlist.Tracks) != 1 || playlist.TrackCount != 1 || playlist.ID == chart.ID {
		t.Fatal(playlist, err)
	}
	if _, err := k.Chart(context.Background(), "kg:chart_9999"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestKGNonzeroErrorsAndMissingPayloadNeverBecomeEmptySuccess(t *testing.T) {
	for _, payload := range []any{
		map[string]any{"status": 0, "errcode": 0, "data": map[string]any{"total": 0, "info": []any{}}},
		map[string]any{"status": 1, "error_code": 20006, "data": []any{}},
		map[string]any{"status": 1, "errcode": "denied", "data": []any{}},
		map[string]any{"status": 1, "errcode": 0},
		map[string]any{"status": 1, "data": map[string]any{"total": 100, "lists": []any{}, "info": []any{}}},
	} {
		k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) { return kgTestResponse(payload), nil }))
		if _, err := k.Search(context.Background(), "q", "track", 1); !errors.Is(err, ErrUnavailable) {
			t.Fatal(payload, err)
		}
		if _, err := k.Playlists(context.Background(), "all", 1); !errors.Is(err, ErrUnavailable) {
			t.Fatal(payload, err)
		}
		if _, err := k.Charts(context.Background()); !errors.Is(err, ErrUnavailable) {
			t.Fatal(payload, err)
		}
	}
	paid := kgTestColdSong(1)
	paid["status"] = 0
	k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) { return kgTestResponse(paid), nil }))
	if _, err := k.Track(context.Background(), "kg:"+kgTestHash(1)); !errors.Is(err, ErrUnavailable) {
		t.Fatal("paywall response treated as success", err)
	}
	for _, which := range []string{"html", "http403", "over1MiB"} {
		t.Run(which, func(t *testing.T) {
			k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) {
				r := &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader("Access Deny ! No Actions !"))}
				if which == "http403" {
					r.StatusCode = 403
				}
				if which == "over1MiB" {
					r.Body = io.NopCloser(strings.NewReader(strings.Repeat(" ", (1<<20)+1)))
				}
				return r, nil
			}))
			if _, err := k.Charts(context.Background()); !errors.Is(err, ErrUnavailable) {
				t.Fatal(err)
			}
		})
	}
}

func TestKGRealEmptySearchDiffersFromMalformedOrUnavailable(t *testing.T) {
	k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) {
		return kgTestResponse(map[string]any{"status": 1, "error_code": 0, "data": map[string]any{"total": 0, "lists": []any{}}}), nil
	}))
	got, err := k.Search(context.Background(), "no match", "track", 1)
	if err != nil || got.Total != 0 || got.Tracks == nil || len(got.Tracks) != 0 {
		t.Fatal(got, err)
	}
}

func TestKGLyricsProtocolAndMismatchedCandidateProtection(t *testing.T) {
	var downloads atomic.Int32
	matched := true
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/app/i/getSongInfo.php":
			return kgTestResponse(kgTestColdSong(1)), nil
		case "/search":
			if r.URL.Host != "lyrics.kugou.com" || r.URL.Query().Get("hash") != kgTestHash(1) {
				t.Fatal(r.URL)
			}
			rows := []any{map[string]any{"id": "88", "song": "错误的歌曲", "singer": "错误的歌手", "duration": 125000, "accesskey": "unused"}}
			if matched {
				rows = append(rows, map[string]any{"id": "99", "song": "测试歌曲", "singer": "测试歌手", "duration": 125000, "accesskey": "public-candidate-key"})
			}
			return kgTestResponse(map[string]any{"status": 200, "errcode": 200, "candidates": rows}), nil
		case "/download":
			downloads.Add(1)
			if r.URL.Query().Get("id") != "99" || r.URL.Query().Get("fmt") != "lrc" || r.URL.Query().Get("accesskey") != "public-candidate-key" {
				t.Fatal("wrong lyric request")
			}
			return kgTestResponse(map[string]any{"status": 200, "fmt": "lrc", "content": base64.StdEncoding.EncodeToString([]byte("[00:01.25]这是为测试独立编写的一行\n[00:02.50]第二行"))}), nil
		default:
			t.Fatal(r.URL)
			return nil, nil
		}
	}))
	lyrics, err := k.Lyrics(context.Background(), "kg:"+kgTestHash(1))
	if err != nil || len(lyrics.Lines) != 2 || lyrics.Lines[0].Time != 1.25 || lyrics.Source != "酷购" {
		t.Fatal(lyrics, err)
	}
	matched = false
	if got, err := k.Lyrics(context.Background(), "kg:"+kgTestHash(1)); !errors.Is(err, ErrNotFound) || len(got.Lines) != 0 {
		t.Fatal(got, err)
	}
	if downloads.Load() != 1 {
		t.Fatal("downloaded unrelated lyrics")
	}
}

func TestKGInjectedTransportDeadlineAndBodyClosureWithHTTPTestServer(t *testing.T) {
	var called atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(kgTestColdSong(1))
	}))
	defer server.Close()
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		deadline, ok := r.Context().Deadline()
		if !ok || time.Until(deadline) > 9*time.Second {
			t.Error("request lacks 9-second cap")
		}
		if r.Method != http.MethodGet || r.Header.Get("Cookie") != "" || r.Header.Get("Authorization") != "" || r.Header.Get("User-Agent") == "" {
			t.Error("unexpected headers/method")
		}
		clone := r.Clone(r.Context())
		target := *r.URL
		clone.URL = &target
		clone.URL.Scheme = "http"
		clone.URL.Host = strings.TrimPrefix(server.URL, "http://")
		return server.Client().Do(clone)
	}))
	if _, err := k.Track(context.Background(), "kg:"+kgTestHash(1)); err != nil || called.Load() != 1 {
		t.Fatal(err, called.Load())
	}
	parent, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	blocked := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) { <-r.Context().Done(); return nil, r.Context().Err() }))
	begin := time.Now()
	if _, err := blocked.Track(parent, "kg:"+kgTestHash(1)); !errors.Is(err, ErrUnavailable) || time.Since(begin) > time.Second {
		t.Fatal(err)
	}
}

func TestKGCoverHostBoundaryAndConcurrentStatelessCalls(t *testing.T) {
	for _, raw := range []string{"http://127.0.0.1/cover.jpg", "https://imge.kugou.com.evil.test/a", "https://user@imge.kugou.com/a", "https://imge.kugou.com:8443/a", "javascript:alert(1)"} {
		if kgCover(raw) != "" {
			t.Fatal(raw)
		}
	}
	k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) { return kgTestResponse(kgTestColdSong(1)), nil }))
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 5 {
				if info, err := k.MusicInfo(context.Background(), "kg:"+kgTestHash(1)); err != nil || info["songmid"] != int64(9001) {
					t.Error(info, err)
				}
			}
		}()
	}
	wg.Wait()
}

func TestKGGlobalPlaylistKeepsRealIDButDoesNotInventDetailSupport(t *testing.T) {
	var calls atomic.Int32
	k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) {
		calls.Add(1)
		return kgTestResponse(kgTestAPI(map[string]any{"total": 1, "info": []any{map[string]any{
			"specialid": 0, "gid": "collection_3_509001524_10_0", "specialname": "全局歌单", "songcount": 16,
		}}})), nil
	}))
	got, err := k.Search(context.Background(), "q", "playlist", 1)
	if err != nil || len(got.Playlists) != 1 || got.Playlists[0].ID != "kg:playlist_collection_3_509001524_10_0" || !strings.Contains(got.Playlists[0].Description, "详情暂不支持") {
		t.Fatal(got, err)
	}
	if _, err := k.Playlist(context.Background(), got.Playlists[0].ID); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatal("unsupported detail attempted network access")
	}
}

type kgTestClosingBody struct {
	io.Reader
	closed *atomic.Bool
}

func (b kgTestClosingBody) Close() error { b.closed.Store(true); return nil }
func TestKGResponseBodyClosedOnOversizeAndApplicationError(t *testing.T) {
	for _, body := range []string{strings.Repeat(" ", (1<<20)+1), `{"status":0,"error_code":1001}`} {
		var closed atomic.Bool
		k := NewKG(kgTestDoer(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: kgTestClosingBody{strings.NewReader(body), &closed}}, nil
		}))
		if _, err := k.Charts(context.Background()); !errors.Is(err, ErrUnavailable) || !closed.Load() {
			t.Fatal(err, closed.Load())
		}
	}
}

// 显式开启；只读公开目录，不使用登录 Cookie、签名密钥或任何音频 URL。
func TestKGLivePublicCatalog(t *testing.T) {
	if os.Getenv("MELORA_KG_LIVE") != "1" {
		t.Skip("set MELORA_KG_LIVE=1 for explicit real-network metadata evidence")
	}
	client := &http.Client{Timeout: 9 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error {
		return errors.New("live probe does not follow redirects")
	}}
	k := NewKG(kgTestDoer(func(r *http.Request) (*http.Response, error) {
		allowed := map[string]bool{"songsearch.kugou.com": true, "mobiles.kugou.com": true, "m.kugou.com": true, "lyrics.kugou.com": true}
		if r.Method != http.MethodGet || r.URL.Scheme != "https" || !allowed[r.URL.Host] {
			return nil, errors.New("unexpected live probe target")
		}
		return client.Do(r)
	}))
	for _, kind := range []string{"track", "playlist", "album", "artist"} {
		t.Run("search_"+kind, func(t *testing.T) {
			got, err := k.Search(context.Background(), "Mozart", kind, 1)
			count := len(got.Tracks) + len(got.Playlists) + len(got.Albums) + len(got.Artists)
			if err != nil || count == 0 {
				t.Fatal("public search unavailable", kind, err)
			}
			t.Logf("kind=%s count=%d total=%d pageSize=%d", kind, count, got.Total, got.PageSize)
		})
	}
	t.Run("charts_and_chart", func(t *testing.T) {
		list, err := k.Charts(context.Background())
		if err != nil || len(list) == 0 {
			t.Fatal(err)
		}
		chart, err := k.Chart(context.Background(), list[0].ID)
		if err != nil || len(chart.Tracks) == 0 {
			t.Fatal(err)
		}
		t.Logf("charts=%d chart=%s loaded=%d total=%d", len(list), chart.ID, len(chart.Tracks), chart.TrackCount)
	})
	t.Run("recommendation_paging_and_playlist", func(t *testing.T) {
		seen := map[string]bool{}
		for page := 1; page <= 2; page++ {
			items, err := k.Playlists(context.Background(), "all", page)
			if err != nil || len(items) != 24 {
				t.Fatal(page, len(items), err)
			}
			for _, item := range items {
				if seen[item.ID] {
					t.Fatal("cross-page repeated ID", item.ID)
				}
				seen[item.ID] = true
			}
			t.Logf("logical_page=%d count=%d", page, len(items))
		}
		playlist, err := k.Playlist(context.Background(), "kg:playlist_725670")
		if err != nil || len(playlist.Tracks) == 0 {
			t.Fatal(err)
		}
		t.Logf("playlist=%s loaded=%d total=%d", playlist.ID, len(playlist.Tracks), playlist.TrackCount)
	})
	t.Run("cold_track_and_lx_music_info", func(t *testing.T) {
		raw := "kg:50D65FA58F45F5C46486BD29809BD471"
		track, err := k.Track(context.Background(), raw)
		if err != nil || track.ID != raw || track.Duration <= 0 {
			t.Fatal(err)
		}
		info, err := k.MusicInfo(context.Background(), raw)
		if err != nil || info["hash"] != "50D65FA58F45F5C46486BD29809BD471" || info["songmid"].(int64) <= 0 {
			t.Fatal(err)
		}
		t.Logf("track=%s songmid=%v seconds=%d metadata_qualities=%d canDownload=%t", track.ID, info["songmid"], track.Duration, len(info["_types"].(map[string]any)), track.CanDownload)
	})
}

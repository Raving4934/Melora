package catalog

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

func modernSong(n int) map[string]any {
	return map[string]any{
		"id": n, "name": fmt.Sprintf("真实曲目-%d", n), "dt": 90000,
		"ar": []any{map[string]any{"id": 22, "name": "V6 artist"}},
		"al": map[string]any{"id": 33, "name": "V6 album", "picUrl": "http://p1.music.126.net/cover.jpg"},
	}
}

func TestPlaylistColdOneClickUsesBoundedV6AndPopulatesTrackMetadata(t *testing.T) {
	var calls atomic.Int32
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		if r.URL.Path != "/api/v6/playlist/detail" || r.URL.Query().Get("id") != "99" || r.URL.Query().Get("n") != "100" || r.URL.Query().Get("s") != "0" || len(r.URL.Query()) != 3 {
			t.Errorf("unbounded or incorrect detail request: %s", r.URL)
		}
		return response(map[string]any{"code": 200, "playlist": map[string]any{
			"id": 99, "name": "真实 V6 歌单", "trackCount": 200, "description": "来自上游",
			"tracks": []any{modernSong(123)},
		}}), nil
	}))
	// 无需先搜索、打开榜单列表或预热歌曲缓存，一次点击即可取得详情。
	list, err := client.Playlist(t.Context(), "wy:99")
	if err != nil || list.ID != "wy:99" || list.Title != "真实 V6 歌单" || len(list.Tracks) != 1 || list.TrackCount != 1 || !strings.Contains(list.Description, "共 200 首") {
		t.Fatalf("cold playlist detail: %+v %v", list, err)
	}
	track := list.Tracks[0]
	if track.ID != "wy:123" || track.ProviderID != "wy" || track.Artist != "V6 artist" || track.Album != "V6 album" || track.Duration != 90 || track.CoverURL != "https://p1.music.126.net/cover.jpg" || track.CanDownload {
		t.Fatalf("v6 ar/al/dt conversion failed: %+v", track)
	}
	if cached, err := client.Track(t.Context(), "wy:123"); err != nil || cached.Title != "真实曲目-123" {
		t.Fatal("detail did not populate track cache", cached, err)
	}
	if info, err := client.MusicInfo(t.Context(), "wy:123"); err != nil || info["albumName"] != "V6 album" || info["singer"] != "V6 artist" {
		t.Fatal("detail did not populate LX metadata", info, err)
	}
	list.Tracks[0].Title = "caller mutation"
	again, err := client.Playlist(t.Context(), "wy:99")
	if err != nil || again.Tracks[0].Title != "真实曲目-123" || calls.Load() != 1 {
		t.Fatal("detail cache is missing or aliases caller state", again, err, calls.Load())
	}
}

func TestPlaylistBoundsAndDeduplicatesEvenIfUpstreamIgnoresLimit(t *testing.T) {
	songs := []any{map[string]any{"id": 0, "name": "invalid"}}
	for i := 1; i <= 120; i++ {
		song := modernSong(i)
		songs = append(songs, song, song)
	}
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
		return response(map[string]any{"code": 200, "playlist": map[string]any{"id": 99, "name": "大歌单", "trackCount": 120, "tracks": songs}}), nil
	}))
	list, err := client.Playlist(t.Context(), "wy:99")
	if err != nil || len(list.Tracks) != 100 || list.TrackCount != 100 {
		t.Fatal("detail output is unbounded", len(list.Tracks), err)
	}
	for i, track := range list.Tracks {
		if track.ID != id(int64(i+1)) {
			t.Fatalf("invalid/duplicate/reordered track at %d: %+v", i, track)
		}
	}
}

func TestPlaylistRejectsInvalidIDsBeforeRequest(t *testing.T) {
	var calls atomic.Int32
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
		calls.Add(1)
		return nil, ErrUnavailable
	}))
	for _, raw := range []string{"", "99", "demo:99", "wy:", "wy:0", "wy:-1", "wy:+99", "wy:1.0", "wy:99?secret=x", "wy:1/../2", "wy:1\\2", "wy:١", "wy:1%00", "wy:" + strings.Repeat("9", 17)} {
		list, err := client.Playlist(t.Context(), raw)
		if !errors.Is(err, ErrNotFound) || list.ID != "" || len(list.Tracks) != 0 {
			t.Fatalf("invalid ID %q accepted: %+v %v", raw, list, err)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid playlist ID reached network")
	}
}

func TestPlaylistChecksResponseIDBeforeConvertingTracks(t *testing.T) {
	for _, root := range []string{"playlist", "result"} {
		client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
			return response(map[string]any{"code": 200, root: map[string]any{"id": 100, "name": "另一个歌单", "trackCount": 1, "tracks": []any{modernSong(321)}}}), nil
		}))
		list, err := client.Playlist(t.Context(), "wy:99")
		if !errors.Is(err, ErrNotFound) || list.ID != "" || len(client.tracks) != 0 || len(client.music) != 0 {
			t.Fatal("mismatched ID returned or poisoned track cache", root, list, err)
		}
	}
}

func TestPlaylistUnavailableShapesDoNotBecomeFakeEmptyDetails(t *testing.T) {
	for _, kind := range []string{"missing", "null", "wrong_id", "blank_name", "missing_tracks", "unavailable_tracks", "invalid_tracks", "upstream_denied", "network"} {
		t.Run(kind, func(t *testing.T) {
			client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
				if kind == "network" {
					return nil, errors.New("upstream offline")
				}
				p := map[string]any{"id": 99, "name": "真实歌单", "trackCount": 1, "tracks": []any{modernSong(1)}}
				body := map[string]any{"code": 200, "playlist": p}
				switch kind {
				case "missing":
					delete(body, "playlist")
				case "null":
					body["playlist"] = nil
				case "wrong_id":
					p["id"] = 101
				case "blank_name":
					p["name"] = " "
				case "missing_tracks":
					delete(p, "tracks")
				case "unavailable_tracks":
					p["tracks"] = []any{}
				case "invalid_tracks":
					p["tracks"] = []any{map[string]any{"id": -1, "name": "invalid"}}
				case "upstream_denied":
					body["code"] = 301
				}
				return response(body), nil
			}))
			list, err := client.Playlist(t.Context(), "wy:99")
			if err == nil || list.ID != "" || len(list.Tracks) != 0 {
				t.Fatal("unavailable detail became a successful collection", list, err)
			}
		})
	}
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
		return response(map[string]any{"code": 200, "playlist": map[string]any{"id": 99, "name": "真正的空歌单", "trackCount": 0, "tracks": []any{}}}), nil
	}))
	if list, err := client.Playlist(t.Context(), "wy:99"); err != nil || list.ID != "wy:99" || list.Tracks == nil || len(list.Tracks) != 0 {
		t.Fatal("valid upstream empty playlist rejected", list, err)
	}
}

type countingBody struct {
	reader io.Reader
	read   int
	closed bool
}

func (b *countingBody) Read(p []byte) (int, error) {
	n, err := b.reader.Read(p)
	b.read += n
	return n, err
}
func (b *countingBody) Close() error { b.closed = true; return nil }

func TestPlaylistResponseReadIsBoundedAndBodyIsClosed(t *testing.T) {
	payload := `{"code":200,"playlist":{"id":99,"name":"empty","trackCount":0,"tracks":[]}}`
	for _, extra := range []int{0, 1, 4096} {
		t.Run(strconv.Itoa(extra), func(t *testing.T) {
			body := &countingBody{reader: strings.NewReader(payload + strings.Repeat(" ", maxResponseBytes+extra-len(payload)))}
			client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 200, Body: body, Header: http.Header{"Content-Type": {"application/json"}}}, nil
			}))
			list, err := client.Playlist(t.Context(), "wy:99")
			if extra == 0 {
				if err != nil || list.ID != "wy:99" {
					t.Fatal("exact parser limit rejected", list, err)
				}
			} else if !errors.Is(err, ErrUnavailable) || list.ID != "" || len(client.cache) != 0 {
				t.Fatal("oversized response accepted or cached", list, err)
			}
			if body.read > maxResponseBytes+1 || !body.closed {
				t.Fatalf("unbounded read or leaked body: read=%d closed=%v", body.read, body.closed)
			}
		})
	}
}

func TestCollectionListsDeduplicateUpstreamIDsBeforeLimit(t *testing.T) {
	items := []any{map[string]any{"id": 0, "name": "invalid"}, map[string]any{"id": 1, "name": " "}}
	for i := 1; i <= 45; i++ {
		p := map[string]any{"id": i, "name": fmt.Sprintf("上游歌单-%d", i)}
		items = append(items, p, p)
	}
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/api/toplist/detail":
			return response(map[string]any{"code": 200, "list": items}), nil
		case "/api/playlist/list":
			if r.URL.Query().Get("offset") != "24" || r.URL.Query().Get("limit") != "24" {
				t.Error("playlist paging changed", r.URL)
			}
			return response(map[string]any{"code": 200, "playlists": items}), nil
		default:
			return response(map[string]any{"code": 200, "result": map[string]any{"playlists": items, "playlistCount": 90}}), nil
		}
	}))
	charts, err := client.Charts(t.Context())
	if err != nil || len(charts) != 40 || charts[39].ID != "wy:40" {
		t.Fatal("chart duplicate/limit regression", len(charts), err)
	}
	playlists, err := client.Playlists(t.Context(), "all", 2)
	if err != nil || len(playlists) != 24 || playlists[23].ID != "wy:24" {
		t.Fatal("playlist duplicate/limit regression", len(playlists), err)
	}
	search, err := client.Search(t.Context(), "query", "playlist", 1)
	if err != nil || len(search.Playlists) != PageSize || search.Playlists[PageSize-1].ID != "wy:20" || search.Total != 90 {
		t.Fatal("search duplicate/limit regression", search, err)
	}
}

func TestDirectoryMetadataCacheStaysWithinExistingBudgets(t *testing.T) {
	for _, padding := range []int{0, 512 << 10} {
		t.Run(strconv.Itoa(padding), func(t *testing.T) {
			client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
				return response(map[string]any{"code": 200, "padding": strings.Repeat("x", padding)}), nil
			}))
			for i := range 70 {
				var out struct{ Code int }
				if err := client.get(t.Context(), "/api/playlist/catalogue", url.Values{"fixture": {strconv.Itoa(i)}}, &out); err != nil {
					t.Fatal(err)
				}
				actual := 0
				for _, item := range client.cache {
					actual += len(item.data)
				}
				if len(client.cache) > 64 || client.cacheBytes > 8<<20 || actual != client.cacheBytes {
					t.Fatalf("cache accounting/budget: entries=%d bytes=%d actual=%d", len(client.cache), client.cacheBytes, actual)
				}
			}
		})
	}
}

func TestGuardedClientRejectsNonCatalogRequestsWithoutNetwork(t *testing.T) {
	for _, raw := range []string{"http://music.163.com/api/playlist/detail", "https://example.test/api/playlist/detail", "https://music.163.com:8443/api/playlist/detail", "file:///etc/passwd"} {
		request, err := http.NewRequestWithContext(t.Context(), http.MethodGet, raw, nil)
		if err != nil {
			t.Fatal(err)
		}
		if response, err := GuardedClient().Do(request); !errors.Is(err, ErrUnavailable) || response != nil {
			t.Fatalf("non-catalog request accepted: %s %v", raw, err)
		}
	}
	request, err := http.NewRequestWithContext(t.Context(), http.MethodPost, "https://music.163.com/api/playlist/detail", nil)
	if err != nil {
		t.Fatal(err)
	}
	if response, err := GuardedClient().Do(request); !errors.Is(err, ErrUnavailable) || response != nil {
		t.Fatal("catalog transport accepted POST", err)
	}
}

package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

type doerFunc func(*http.Request) (*http.Response, error)

func (f doerFunc) Do(r *http.Request) (*http.Response, error) { return f(r) }
func response(value any) *http.Response {
	raw, _ := json.Marshal(value)
	return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(string(raw))), Header: http.Header{"Content-Type": {"application/json"}}}
}
func sampleSong() map[string]any {
	return map[string]any{"id": 123, "name": "Test song", "duration": 125000, "artists": []any{map[string]any{"id": 2, "name": "Test artist"}}, "album": map[string]any{"id": 3, "name": "Album", "picUrl": "http://p1.music.126.net/cover.jpg"}}
}
func TestSearchUsesRealProviderShapesAndCachesWithoutAudio(t *testing.T) {
	var calls atomic.Int32
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		if r.URL.Host != "music.163.com" || r.URL.Path != "/api/search/get/web" || r.URL.Query().Get("s") != "歌手 & ?" || r.URL.Query().Get("offset") != "20" {
			t.Errorf("unexpected metadata request %s", r.URL)
		}
		return response(map[string]any{"code": 200, "result": map[string]any{"songs": []any{sampleSong()}, "songCount": 41}}), nil
	}))
	for range 2 {
		result, err := client.Search(context.Background(), " 歌手 & ? ", "track", 2)
		if err != nil || len(result.Tracks) != 1 || result.Total != 41 || result.Page != 2 {
			t.Fatal(result, err)
		}
		track := result.Tracks[0]
		if track.ID != "wy:123" || track.Duration != 125 || track.CanDownload || len(track.Qualities) != 0 || track.CoverURL != "https://p1.music.126.net/cover.jpg" {
			t.Fatal(track)
		}
	}
	if calls.Load() != 1 {
		t.Fatal("metadata cache missed", calls.Load())
	}
	info, err := client.MusicInfo(context.Background(), "wy:123")
	if err != nil || info["name"] != "Test song" || info["singer"] != "Test artist" {
		t.Fatal(info, err)
	}
	info["name"] = "mutated"
	again, _ := client.MusicInfo(context.Background(), "wy:123")
	if again["name"] != "Test song" {
		t.Fatal("script changed cache")
	}
}
func TestDirectoryFailureDoesNotSubstituteDemo(t *testing.T) {
	client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) {
		return response(map[string]any{"code": 301, "message": "login required"}), nil
	}))
	charts, err := client.Charts(context.Background())
	if !errors.Is(err, ErrUnavailable) || len(charts) != 0 {
		t.Fatal(charts, err)
	}
	for _, raw := range []string{"demo:123", "wy:../etc", "wy:0", "wy:1?secret=x", "wy:NaN"} {
		if _, err := client.Track(context.Background(), raw); !errors.Is(err, ErrNotFound) {
			t.Fatal(raw, err)
		}
	}
	for _, kind := range []string{"unknown", ""} {
		if _, err := client.Search(context.Background(), "q", kind, 1); !errors.Is(err, ErrInput) {
			t.Fatal(kind, err)
		}
	}
}
func TestPlaylistAndChartsUseUpstreamIDsAndBoundTrackCount(t *testing.T) {
	songs := make([]any, 0, 105)
	for n := range 105 {
		s := sampleSong()
		s["id"] = n + 1
		songs = append(songs, s)
	}
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/api/toplist/detail" {
			return response(map[string]any{"code": 200, "list": []any{map[string]any{"id": 99, "name": "真实榜单", "trackCount": 105}}}), nil
		}
		return response(map[string]any{"code": 200, "result": map[string]any{"id": 99, "name": "真实榜单", "trackCount": 105, "tracks": songs}}), nil
	}))
	charts, err := client.Charts(context.Background())
	if err != nil || len(charts) != 1 || charts[0].ID != "wy:99" {
		t.Fatal(charts, err)
	}
	list, err := client.Playlist(context.Background(), "wy:99")
	if err != nil || len(list.Tracks) != 100 || list.TrackCount != 100 || !strings.Contains(list.Description, "共 105 首") {
		t.Fatal(list.TrackCount, err)
	}
}
func TestAlbumArtistPlaylistSearchShapes(t *testing.T) {
	client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
		result := map[string]any{}
		switch r.URL.Query().Get("type") {
		case "10":
			result["albums"] = []any{map[string]any{"id": 1, "name": "Album", "size": 5, "artist": map[string]any{"name": "Artist"}}}
			result["albumCount"] = 2
		case "100":
			result["artists"] = []any{map[string]any{"id": 2, "name": "Artist", "musicSize": 42}}
			result["artistCount"] = 3
		case "1000":
			result["playlists"] = []any{map[string]any{"id": 3, "name": "Playlist", "trackCount": 4}}
			result["playlistCount"] = 8
		}
		return response(map[string]any{"code": 200, "result": result}), nil
	}))
	albums, err := client.Search(context.Background(), "a", "album", 1)
	if err != nil || len(albums.Albums) != 1 || albums.Albums[0].Artist != "Artist" {
		t.Fatal(albums, err)
	}
	artists, err := client.Search(context.Background(), "a", "artist", 1)
	if err != nil || len(artists.Artists) != 1 || artists.Artists[0].TrackCount != 42 {
		t.Fatal(artists, err)
	}
	lists, err := client.Search(context.Background(), "a", "playlist", 1)
	if err != nil || len(lists.Playlists) != 1 || lists.Total != 8 {
		t.Fatal(lists, err)
	}
}
func TestCoverAndLyricsParsing(t *testing.T) {
	for _, s := range []string{"file:///etc/passwd", "https://evil.example/image", "https://user:pass@p1.music.126.net/a", "javascript:alert(1)"} {
		if image(s) != "" {
			t.Fatal("unsafe image accepted", s)
		}
	}
	lines := ParseLRC("[ar:Artist]\n[00:03.50]third\n[00:01][00:02]two lines\n[00:NaN]no\n[00:99]invalid")
	if len(lines) != 3 || lines[0].Time != 1 || lines[1].Time != 2 || lines[2].Time != 3.5 || lines[0].Text != "two lines" {
		t.Fatal(lines)
	}
}
func TestResponseSizeAndHTTPStatusAreBounded(t *testing.T) {
	for _, r := range []*http.Response{{StatusCode: 429, Body: io.NopCloser(strings.NewReader("secret"))}, {StatusCode: 200, Body: io.NopCloser(strings.NewReader(strings.Repeat("x", maxResponseBytes+1)))}} {
		client := NewWY(doerFunc(func(*http.Request) (*http.Response, error) { return r, nil }))
		if _, err := client.Charts(context.Background()); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
	}
}

func TestSearchBatchesMissingCoversWithoutDiscardingResults(t *testing.T) {
	for _, failDetails := range []bool{false, true} {
		var calls atomic.Int32
		client := NewWY(doerFunc(func(r *http.Request) (*http.Response, error) {
			calls.Add(1)
			if r.URL.Path == "/api/search/get/web" {
				song := sampleSong()
				song["album"] = map[string]any{"id": 3, "name": "Album"}
				return response(map[string]any{"code": 200, "result": map[string]any{"songs": []any{song}, "songCount": 1}}), nil
			}
			if r.URL.Path != "/api/song/detail" || r.URL.Query().Get("ids") != "[123]" {
				t.Fatal("not a batch detail request", r.URL)
			}
			if failDetails {
				return nil, errors.New("network failed")
			}
			return response(map[string]any{"code": 200, "songs": []any{sampleSong()}}), nil
		}))
		out, err := client.Search(context.Background(), "song", "track", 1)
		if err != nil || len(out.Tracks) != 1 || calls.Load() != 2 {
			t.Fatal(out, err, calls.Load())
		}
		if !failDetails && out.Tracks[0].CoverURL == "" {
			t.Fatal("missing real cover")
		}
		if failDetails && out.Tracks[0].CoverURL != "" {
			t.Fatal("must not invent a cover")
		}
	}
}

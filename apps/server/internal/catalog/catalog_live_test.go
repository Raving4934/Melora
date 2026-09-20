package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"testing"
	"time"

	"melora/internal/netguard"
)

// 显式开启才访问公网。只落盘状态、体积、结构和公开目录 ID，绝不保存原始响应、
// Cookie、用户资料、媒体地址或凭据。普通单测/race 默认跳过此探测。
type catalogProbeRecord struct {
	Case             string            `json:"case"`
	Path             string            `json:"path"`
	Params           url.Values        `json:"params,omitempty"`
	At               string            `json:"at"`
	ElapsedMS        int64             `json:"elapsedMs"`
	HTTPStatus       int               `json:"httpStatus,omitempty"`
	BodyBytes        int               `json:"bodyBytes,omitempty"`
	ContentType      string            `json:"contentType,omitempty"`
	APICode          int               `json:"apiCode,omitempty"`
	Error            string            `json:"error,omitempty"`
	RootFields       []string          `json:"rootFields,omitempty"`
	SongFields       []string          `json:"songFields,omitempty"`
	IDs              []int64           `json:"sampleIds,omitempty"`
	Items            int               `json:"items,omitempty"`
	UniqueIDs        int               `json:"uniqueIds,omitempty"`
	PlaylistID       int64             `json:"playlistId,omitempty"`
	TrackCount       int               `json:"trackCount,omitempty"`
	Tracks           int               `json:"tracks,omitempty"`
	TrackIDs         int               `json:"trackIds,omitempty"`
	CategoryGroups   map[string]string `json:"categoryGroups,omitempty"`
	CategoryExamples []probeCategory   `json:"categoryExamples,omitempty"`
	AllCategory      *probeCategory    `json:"allCategory,omitempty"`
}

type probeCategory struct {
	Name     string `json:"name"`
	Category int    `json:"category"`
}

func openCatalogProbeReport(t *testing.T) *json.Encoder {
	t.Helper()
	if os.Getenv("MELORA_CATALOG_LIVE") != "1" {
		t.Skip("公网目录探测需显式设置 MELORA_CATALOG_LIVE=1，并指定脱敏报告路径")
	}
	path := os.Getenv("MELORA_CATALOG_LIVE_REPORT")
	if !filepath.IsAbs(path) || filepath.Base(path)[:min(len(filepath.Base(path)), 8)] != "catalog-" {
		t.Fatal("公网测试必须提供绝对路径 MELORA_CATALOG_LIVE_REPORT，文件名以 catalog- 开头")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := file.Sync(); err != nil {
			t.Error(err)
		}
		if err := file.Close(); err != nil {
			t.Error(err)
		}
	})
	return json.NewEncoder(file)
}

func probeCatalogRequest(t *testing.T, report *json.Encoder, label, path string, params url.Values) map[string]json.RawMessage {
	t.Helper()
	record := catalogProbeRecord{Case: label, Path: path, Params: params, At: time.Now().UTC().Format(time.RFC3339)}
	started := time.Now()
	defer func() {
		record.ElapsedMS = time.Since(started).Milliseconds()
		if err := report.Encode(record); err != nil {
			t.Fatal(err)
		}
		t.Logf("%s: HTTP=%d code=%d bytes=%d tracks=%d items=%d error=%s", label, record.HTTPStatus, record.APICode, record.BodyBytes, record.Tracks, record.Items, record.Error)
	}()
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://music.163.com"+path+"?"+params.Encode(), nil)
	if err != nil {
		record.Error = "request_build_failed"
		return nil
	}
	request.Header.Set("User-Agent", "Mozilla/5.0 (compatible; Melora/0.3)")
	request.Header.Set("Referer", "https://music.163.com/")
	request.Header.Set("Accept", "application/json")
	response, err := GuardedClient().Do(request)
	if err != nil {
		switch {
		case errors.Is(err, netguard.ErrLimit):
			record.Error = "guard_limit_exceeded"
		case errors.Is(err, netguard.ErrPolicy):
			record.Error = "guard_policy_rejected"
		case errors.Is(err, netguard.ErrMedia):
			record.Error = "guard_media_rejected"
		default:
			record.Error = "guard_request_failed"
		}
		return nil
	}
	defer response.Body.Close()
	record.HTTPStatus = response.StatusCode
	record.ContentType = response.Header.Get("Content-Type")
	body, err := io.ReadAll(io.LimitReader(response.Body, (8<<20)+1))
	record.BodyBytes = len(body)
	if err != nil || len(body) > 8<<20 {
		record.Error = "probe_read_failed_or_oversized"
		return nil
	}
	var root map[string]json.RawMessage
	if json.Unmarshal(body, &root) != nil {
		record.Error = "invalid_json"
		return nil
	}
	_ = json.Unmarshal(root["code"], &record.APICode)
	for key := range root {
		record.RootFields = append(record.RootFields, key)
	}
	sort.Strings(record.RootFields)
	for _, key := range []string{"result", "playlist"} {
		var value struct {
			ID         int64             `json:"id"`
			TrackCount int               `json:"trackCount"`
			Tracks     []json.RawMessage `json:"tracks"`
			TrackIDs   []json.RawMessage `json:"trackIds"`
		}
		if json.Unmarshal(root[key], &value) == nil && value.ID > 0 {
			record.PlaylistID, record.TrackCount = value.ID, value.TrackCount
			record.Tracks, record.TrackIDs = len(value.Tracks), len(value.TrackIDs)
			if len(value.Tracks) > 0 {
				var first map[string]json.RawMessage
				_ = json.Unmarshal(value.Tracks[0], &first)
				for key := range first {
					record.SongFields = append(record.SongFields, key)
				}
			}
		}
	}
	for _, key := range []string{"list", "playlists", "data"} {
		var values []map[string]json.RawMessage
		if json.Unmarshal(root[key], &values) == nil && values != nil {
			record.Items = len(values)
			seen := map[int64]bool{}
			for _, value := range values {
				var id int64
				_ = json.Unmarshal(value["id"], &id)
				if id > 0 && !seen[id] {
					seen[id] = true
					if len(record.IDs) < 5 {
						record.IDs = append(record.IDs, id)
					}
				}
			}
			record.UniqueIDs = len(seen)
			if key == "data" && len(values) > 0 {
				for key := range values[0] {
					record.SongFields = append(record.SongFields, key)
				}
			}
		}
	}
	sort.Strings(record.SongFields)
	if len(root["sub"]) > 0 {
		var entries []probeCategory
		_ = json.Unmarshal(root["sub"], &entries)
		record.Items = len(entries)
		if len(entries) > 10 {
			entries = entries[:10]
		}
		record.CategoryExamples = entries
		_ = json.Unmarshal(root["categories"], &record.CategoryGroups)
		var all probeCategory
		if json.Unmarshal(root["all"], &all) == nil {
			record.AllCategory = &all
		}
	}
	return root
}

func TestLiveCatalogProbe(t *testing.T) {
	report := openCatalogProbeReport(t)
	probeCatalogRequest(t, report, "charts", "/api/toplist/detail", nil)
	popular := probeCatalogRequest(t, report, "popular_page_1", "/api/playlist/list", url.Values{"cat": {"全部"}, "limit": {"24"}, "offset": {"0"}, "order": {"hot"}})
	probeCatalogRequest(t, report, "popular_page_2", "/api/playlist/list", url.Values{"cat": {"全部"}, "limit": {"24"}, "offset": {"24"}, "order": {"hot"}})
	ids := []int64{19723756, 3779629, 2884035, 3778678}
	var playlists []struct {
		ID int64 `json:"id"`
	}
	_ = json.Unmarshal(popular["playlists"], &playlists)
	for _, playlist := range playlists[:min(3, len(playlists))] {
		ids = append(ids, playlist.ID)
	}
	for _, id := range ids {
		probeCatalogRequest(t, report, "detail_"+strconv.FormatInt(id, 10), "/api/playlist/detail", url.Values{"id": {strconv.FormatInt(id, 10)}, "n": {"100"}})
	}
	for _, area := range []string{"0", "7", "96", "8", "16"} {
		probeCatalogRequest(t, report, "new_songs_area_"+area, "/api/v1/discovery/new/songs", url.Values{"areaId": {area}})
	}
	probeCatalogRequest(t, report, "playlist_catalogue", "/api/playlist/catalogue", nil)
	// 对同一榜单测试 n/s 与新版响应形状，避免未经测量就放大所有请求上限。
	for _, candidate := range []struct {
		label, path, n string
	}{
		{"old_detail_n1", "/api/playlist/detail", "1"},
		{"v6_detail_n100", "/api/v6/playlist/detail", "100"},
		{"v6_detail_n0", "/api/v6/playlist/detail", "0"},
	} {
		probeCatalogRequest(t, report, candidate.label, candidate.path, url.Values{"id": {"3778678"}, "n": {candidate.n}, "s": {"0"}})
	}
}

func TestLiveCatalogLargeDetails(t *testing.T) {
	report := openCatalogProbeReport(t)
	charts := probeCatalogRequest(t, report, "volume_chart_candidates", "/api/toplist/detail", nil)
	var candidates []playlist
	_ = json.Unmarshal(charts["list"], &candidates)
	for _, category := range []string{"全部", "华语"} {
		root := probeCatalogRequest(t, report, "volume_popular_"+category, "/api/playlist/list", url.Values{"cat": {category}, "limit": {"24"}, "offset": {"0"}, "order": {"hot"}})
		var items []playlist
		_ = json.Unmarshal(root["playlists"], &items)
		candidates = append(candidates, items...)
	}
	sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].TrackCount > candidates[j].TrackCount })
	seen := map[int64]bool{}
	for _, candidate := range candidates {
		if candidate.ID <= 0 || seen[candidate.ID] {
			continue
		}
		seen[candidate.ID] = true
		for _, version := range []string{"", "/v6"} {
			label := "large" + version + "_" + strconv.FormatInt(candidate.ID, 10) + "_declared_" + strconv.Itoa(candidate.TrackCount)
			probeCatalogRequest(t, report, label, "/api"+version+"/playlist/detail", url.Values{"id": {strconv.FormatInt(candidate.ID, 10)}, "n": {"100"}, "s": {"0"}})
		}
		if len(seen) == 4 {
			break
		}
	}
}

// 最终只复测固定的新方法与两次冷启动详情，不扩大远端扫描范围。
func TestLiveCatalogOneClickRegression(t *testing.T) {
	report := openCatalogProbeReport(t)
	transport := GuardedClient()
	calls := 0
	client := NewWY(doerFunc(func(request *http.Request) (*http.Response, error) {
		started := time.Now()
		response, err := transport.Do(request)
		calls++
		record := catalogProbeRecord{Case: "client_transport", Path: request.URL.Path, Params: request.URL.Query(), At: started.UTC().Format(time.RFC3339), ElapsedMS: time.Since(started).Milliseconds()}
		if err != nil {
			record.Error = "guard_request_failed"
			if errors.Is(err, netguard.ErrLimit) {
				record.Error = "guard_limit_exceeded"
			}
		} else {
			record.HTTPStatus = response.StatusCode
			record.BodyBytes = int(response.ContentLength)
			record.ContentType = response.Header.Get("Content-Type")
		}
		if writeErr := report.Encode(record); writeErr != nil {
			if response != nil {
				_ = response.Body.Close()
			}
			t.Fatal(writeErr)
		}
		return response, err
	}))
	finish := func(record catalogProbeRecord, err error) {
		record.At = time.Now().UTC().Format(time.RFC3339)
		if err != nil {
			record.Error = "catalog_request_failed"
			t.Errorf("%s: %v", record.Case, err)
		} else {
			record.APICode = 200
		}
		if err := report.Encode(record); err != nil {
			t.Fatal(err)
		}
	}
	tracks, err := client.NewTracks(t.Context(), "all")
	finish(catalogProbeRecord{Case: "client_new_tracks_all", Path: "/api/v1/discovery/new/songs", Items: len(tracks)}, err)
	if err == nil {
		if len(tracks) == 0 || len(tracks) > 100 {
			t.Errorf("unexpected real new-song count: %d", len(tracks))
		}
		for _, track := range tracks {
			if track.ProviderID != "wy" || track.Title == "" || track.CanDownload {
				t.Errorf("invalid real new-song metadata: id=%s provider=%s", track.ID, track.ProviderID)
			}
		}
	}
	categories, err := client.PlaylistCategories(t.Context())
	finish(catalogProbeRecord{Case: "client_playlist_categories", Path: "/api/playlist/catalogue", Items: len(categories)}, err)
	if err == nil && len(categories) == 0 {
		t.Error("real category response was empty")
	}
	for _, raw := range []string{"wy:3778678", "wy:18271967084"} {
		list, err := client.Playlist(t.Context(), raw)
		n, _ := number(raw)
		finish(catalogProbeRecord{Case: "client_cold_detail_" + raw, Path: "/api/v6/playlist/detail", PlaylistID: n, Tracks: len(list.Tracks), TrackCount: list.TrackCount}, err)
		if err != nil {
			continue
		}
		if list.ID != raw || list.Title == "" || len(list.Tracks) == 0 || len(list.Tracks) > 100 || list.TrackCount != len(list.Tracks) {
			t.Errorf("real one-click detail invalid: id=%s tracks=%d", list.ID, len(list.Tracks))
		}
		before := calls
		if _, err := client.Playlist(t.Context(), raw); err != nil {
			t.Error(err)
		}
		if len(list.Tracks) > 0 {
			if _, err := client.MusicInfo(t.Context(), list.Tracks[0].ID); err != nil {
				t.Error(err)
			}
		}
		if calls != before {
			t.Errorf("one-click cache unexpectedly fetched %d extra responses", calls-before)
		}
	}
}

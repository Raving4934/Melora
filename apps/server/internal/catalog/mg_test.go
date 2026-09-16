package catalog

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"melora/internal/netguard"
)

// 实网探测显式开启，并只保存脱敏状态、体积、结构与公开资源标识。
// 不保存正文、账户信息、Cookie、接口签名、播放地址或歌词正文。
type mgProbeRecord struct {
	Case        string              `json:"case"`
	At          string              `json:"at"`
	Method      string              `json:"method"`
	Host        string              `json:"host"`
	Path        string              `json:"path"`
	Status      int                 `json:"httpStatus,omitempty"`
	Bytes       int                 `json:"bodyBytes,omitempty"`
	ElapsedMS   int64               `json:"elapsedMs"`
	ContentType string              `json:"contentType,omitempty"`
	Error       string              `json:"error,omitempty"`
	Shape       any                 `json:"shape,omitempty"`
	PublicIDs   map[string][]string `json:"publicIds,omitempty"`
	AssetHosts  map[string][]string `json:"assetHosts,omitempty"`
}

func mgProbeShape(value any, key string, depth int) any {
	if depth > 7 {
		return "<nested>"
	}
	switch v := value.(type) {
	case map[string]any:
		out := make(map[string]any, len(v))
		for k, child := range v {
			out[k] = mgProbeShape(child, k, depth+1)
		}
		return out
	case []any:
		out := map[string]any{"length": len(v)}
		if len(v) > 0 {
			out["first"] = mgProbeShape(v[0], key, depth+1)
		}
		return out
	case string:
		if key == "code" || key == "returnCode" || key == "retCode" || key == "resultCode" || key == "resType" || key == "resourceType" {
			if len(v) <= 20 {
				return v
			}
		}
		return map[string]any{"type": "string", "length": len(v)}
	case json.Number:
		if key == "code" || key == "status" || key == "totalCount" || key == "total" || key == "count" || key == "pageNo" || key == "pageSize" {
			return v
		}
		return "<number>"
	case bool, nil:
		return v
	default:
		return "<value>"
	}
}

func mgProbeIDs(value any, ids, hosts map[string][]string) {
	switch v := value.(type) {
	case map[string]any:
		for key, child := range v {
			text, ok := child.(string)
			if n, number := child.(json.Number); number {
				text, ok = n.String(), true
			}
			if ok {
				switch key {
				case "copyrightId", "songId", "playlistId", "columnId", "resId", "tagId":
					if len(ids[key]) < 5 && len(text) <= 96 {
						ids[key] = append(ids[key], text)
					}
				}
				lower := strings.ToLower(key)
				if strings.Contains(lower, "img") || strings.Contains(lower, "pic") || strings.Contains(lower, "lrc") {
					parsed, err := url.Parse(text)
					if err == nil && parsed.Hostname() != "" && parsed.User == nil && len(hosts[key]) < 5 {
						hosts[key] = append(hosts[key], parsed.Hostname())
					}
				}
			}
			mgProbeIDs(child, ids, hosts)
		}
	case []any:
		for _, child := range v {
			mgProbeIDs(child, ids, hosts)
		}
	}
}

func mgProbeOpen(t *testing.T) *json.Encoder {
	t.Helper()
	if os.Getenv("MELORA_MG_LIVE") != "1" {
		t.Skip("MG公网探测需显式设置 MELORA_MG_LIVE=1 与独立 mg-* 报告路径")
	}
	path := os.Getenv("MELORA_MG_REPORT")
	if !filepath.IsAbs(path) || !strings.HasPrefix(filepath.Base(path), "mg-") {
		t.Fatal("MELORA_MG_REPORT 必须是绝对路径且文件名以 mg- 开头")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = file.Sync(); _ = file.Close() })
	return json.NewEncoder(file)
}

type mgProbeDoer struct {
	t      *testing.T
	report *json.Encoder
	label  string
}

func (d mgProbeDoer) Do(request *http.Request) (*http.Response, error) {
	start := time.Now()
	record := mgProbeRecord{Case: d.label, At: start.UTC().Format(time.RFC3339), Method: request.Method, Host: request.URL.Host, Path: request.URL.Path}
	defer func() {
		record.ElapsedMS = time.Since(start).Milliseconds()
		if err := d.report.Encode(record); err != nil {
			d.t.Fatal(err)
		}
		d.t.Logf("%s: %s %s%s HTTP=%d bytes=%d error=%s", d.label, record.Method, record.Host, record.Path, record.Status, record.Bytes, record.Error)
	}()
	broker, err := netguard.NewBroker(netguard.Options{})
	if err != nil {
		record.Error = "guard_initialization_failed"
		return nil, err
	}
	defer broker.Close()
	headers := map[string]string{}
	for key := range request.Header {
		headers[key] = request.Header.Get(key)
	}
	var body []byte
	if request.Body != nil {
		body, err = io.ReadAll(io.LimitReader(request.Body, (64<<10)+1))
		if err != nil {
			return nil, err
		}
	}
	response, err := broker.Do(request.Context(), netguard.Request{URL: request.URL.String(), Method: request.Method, Headers: headers, Body: body})
	if err != nil {
		record.Error = "guard_request_failed"
		if errors.Is(err, netguard.ErrLimit) {
			record.Error = "guard_limit_exceeded"
		} else if errors.Is(err, netguard.ErrPolicy) {
			record.Error = "guard_policy_rejected"
		}
		return nil, err
	}
	record.Status, record.Bytes = response.StatusCode, len(response.Body)
	record.ContentType = response.Headers["content-type"]
	var document any
	decoder := json.NewDecoder(bytes.NewReader(response.Body))
	decoder.UseNumber()
	if decoder.Decode(&document) == nil {
		record.Shape = mgProbeShape(document, "", 0)
		record.PublicIDs, record.AssetHosts = map[string][]string{}, map[string][]string{}
		mgProbeIDs(document, record.PublicIDs, record.AssetHosts)
		for key, values := range record.AssetHosts {
			sort.Strings(values)
			record.AssetHosts[key] = values
		}
	}
	out := &http.Response{StatusCode: response.StatusCode, Body: io.NopCloser(bytes.NewReader(response.Body)), Header: make(http.Header), ContentLength: int64(len(response.Body))}
	for key, value := range response.Headers {
		out.Header.Set(key, value)
	}
	return out, nil
}

func mgProbe(t *testing.T, report *json.Encoder, label, method, endpoint string, params url.Values) map[string]any {
	t.Helper()
	headers := http.Header{"Accept": {"application/json"}, "Referer": {"https://music.migu.cn/"}}
	var body []byte
	if method == http.MethodPost {
		headers.Set("Content-Type", "application/x-www-form-urlencoded")
		body = []byte(params.Encode())
	} else if len(params) > 0 {
		endpoint += "?" + params.Encode()
	}
	data, err := catalogRequest(t.Context(), mgProbeDoer{t: t, report: report, label: label}, method, endpoint, headers, body)
	if err != nil {
		return nil
	}
	var out map[string]any
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	_ = decoder.Decode(&out)
	return out
}

func TestMGLiveProbe(t *testing.T) {
	report := mgProbeOpen(t)
	switches := `{"song":1,"album":0,"singer":0,"tagSong":0,"mvSong":0,"songlist":0,"bestShow":0}`
	mgProbe(t, report, "search_legacy", http.MethodGet, "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do", url.Values{"isCopyright": {"1"}, "isCorrect": {"1"}, "pageNo": {"1"}, "pageSize": {"20"}, "searchSwitch": {switches}, "sort": {"0"}, "text": {"周杰伦"}})
	mgProbe(t, report, "search_public_mobile", http.MethodGet, "https://m.music.migu.cn/migu/remoting/scr_search_tag", url.Values{"keyword": {"周杰伦"}, "type": {"2"}, "pgc": {"1"}, "rows": {"20"}})
	mgProbe(t, report, "search_unsigned_v3", http.MethodGet, "https://jadeite.migu.cn/music_search/v3/search/searchAll", url.Values{"isCopyright": {"1"}, "isCorrect": {"1"}, "pageNo": {"1"}, "pageSize": {"20"}, "searchSwitch": {switches}, "sort": {"0"}, "text": {"周杰伦"}, "sid": {"USS"}})
	mgProbe(t, report, "track_cold_resource", http.MethodPost, "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType=2", url.Values{"resourceId": {"60054701937"}})
	mgProbe(t, report, "track_public_data", http.MethodGet, "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/getMusicData.do", url.Values{"copyrightId": {"60054701937"}})
	mgProbe(t, report, "lyrics_public", http.MethodGet, "https://music.migu.cn/v3/api/music/audioPlayer/getLyric", url.Values{"copyrightId": {"60054701937"}})
	mgProbe(t, report, "charts", http.MethodGet, "https://app.c.nf.migu.cn/pc/bmw/rank/rank-index/v1.0", nil)
	mgProbe(t, report, "chart_detail", http.MethodGet, "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do", url.Values{"columnId": {"27186466"}, "needAll": {"0"}})
	mgProbe(t, report, "playlists", http.MethodGet, "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0", url.Values{"templateVersion": {"2"}, "pageNo": {"1"}})
	mgProbe(t, report, "playlist_categories", http.MethodGet, "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-taglist/release", nil)
}

func mgProbeText(v any) string {
	switch v := v.(type) {
	case string:
		return v
	case json.Number:
		return v.String()
	}
	return ""
}
func mgProbeContents(value any, key string) []map[string]any {
	out := []map[string]any{}
	switch v := value.(type) {
	case []any:
		for _, child := range v {
			out = append(out, mgProbeContents(child, key)...)
		}
	case map[string]any:
		if mgProbeText(v[key]) != "" {
			out = append(out, v)
		}
		out = append(out, mgProbeContents(v["contents"], key)...)
	}
	return out
}

func TestMGLiveContracts(t *testing.T) {
	report := mgProbeOpen(t)
	for _, kind := range []string{"song", "album", "singer", "songlist"} {
		switches := map[string]int{"song": 0, "album": 0, "singer": 0, "tagSong": 0, "mvSong": 0, "songlist": 0, "bestShow": 0}
		switches[kind] = 1
		encoded, _ := json.Marshal(switches)
		mgProbe(t, report, "search_kind_"+kind, http.MethodGet, "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do", url.Values{"isCopyright": {"1"}, "isCorrect": {"1"}, "pageNo": {"1"}, "pageSize": {"20"}, "searchSwitch": {string(encoded)}, "sort": {"0"}, "text": {"周杰伦"}})
	}
	for _, copyright := range []string{"60054704965", "60054701923"} {
		root := mgProbe(t, report, "cold_post_"+copyright, http.MethodPost, "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType=2", url.Values{"resourceId": {copyright}})
		resources, _ := root["resource"].([]any)
		if len(resources) > 0 {
			item, _ := resources[0].(map[string]any)
			lyric := mgProbeText(item["lrcUrl"])
			parsed, err := url.Parse(lyric)
			if err == nil && parsed.Hostname() == "d.musicapp.migu.cn" && parsed.User == nil && parsed.Port() == "" {
				parsed.Scheme = "https"
				mgProbe(t, report, "plain_lrc_"+copyright, http.MethodGet, parsed.String(), nil)
			}
		}
	}
	mgProbe(t, report, "cold_get", http.MethodGet, "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do", url.Values{"resourceType": {"2"}, "resourceId": {"60054704965"}})
	mgProbe(t, report, "playlist_metadata", http.MethodGet, "https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0", url.Values{"playlistId": {"233542261"}})
	mgProbe(t, report, "playlist_songs", http.MethodGet, "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0", url.Values{"playlistId": {"233542261"}, "pageNo": {"1"}, "pageSize": {"100"}})
	for _, page := range []string{"1", "2"} {
		root := mgProbe(t, report, "recommend_page_"+page, http.MethodGet, "https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0", url.Values{"templateVersion": {"2"}, "pageNo": {page}, "pageSize": {"24"}})
		data, _ := root["data"].(map[string]any)
		items := mgProbeContents(data["contents"], "resId")
		ids := []string{}
		for _, item := range items {
			if mgProbeText(item["resType"]) == "2021" {
				ids = append(ids, mgProbeText(item["resId"]))
			}
		}
		_ = report.Encode(mgProbeRecord{Case: "recommend_ids_" + page, PublicIDs: map[string][]string{"playlistIds": ids}})
	}
	root := mgProbe(t, report, "chart_index_ids", http.MethodGet, "https://app.c.nf.migu.cn/pc/bmw/rank/rank-index/v1.0", nil)
	data, _ := root["data"].(map[string]any)
	items := mgProbeContents(data["contents"], "rankId")
	ids := []string{}
	for _, item := range items {
		ids = append(ids, mgProbeText(item["rankId"]))
	}
	_ = report.Encode(mgProbeRecord{Case: "chart_rank_ids", PublicIDs: map[string][]string{"rankIds": ids}})
}

func TestMGLiveColdAndPaging(t *testing.T) {
	report := mgProbeOpen(t)
	for _, entry := range []struct {
		label, endpoint string
		params          url.Values
	}{
		{"cold_app_resource", "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do", url.Values{"resourceType": {"2"}, "resourceId": {"60054701923"}}},
		{"cold_copyright_param", "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do", url.Values{"resourceType": {"2"}, "copyrightId": {"60054701923"}}},
		{"cold_v3_song", "https://app.c.nf.migu.cn/MIGUM3.0/resource/song/v2.0", url.Values{"copyrightId": {"60054701923"}}},
		{"cold_song_internal", "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do", url.Values{"resourceType": {"2"}, "resourceId": {"3790007"}}},
		{"cold_resource_v3", "https://c.musicapp.migu.cn/MIGUM3.0/resource/song/v2.0", url.Values{"copyrightId": {"60054701923"}}},
	} {
		root := mgProbe(t, report, entry.label, http.MethodGet, entry.endpoint, entry.params)
		resources, _ := root["resource"].([]any)
		if len(resources) > 0 {
			item, _ := resources[0].(map[string]any)
			lyric := mgProbeText(item["lrcUrl"])
			parsed, err := url.Parse(lyric)
			if err == nil && parsed.Hostname() == "d.musicapp.migu.cn" && parsed.User == nil && parsed.Port() == "" {
				parsed.Scheme = "https"
				mgProbe(t, report, "cold_plain_lrc", http.MethodGet, parsed.String(), nil)
			}
		}
	}
	root := mgProbe(t, report, "paging_tags", http.MethodGet, "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-taglist/release", nil)
	groups, _ := root["data"].([]any)
	if len(groups) > 0 {
		group, _ := groups[0].(map[string]any)
		content, _ := group["content"].([]any)
		if len(content) > 0 {
			item, _ := content[0].(map[string]any)
			texts, _ := item["texts"].([]any)
			if len(texts) > 1 {
				for _, page := range []string{"1", "2"} {
					mgProbe(t, report, "tag_page_"+page, http.MethodGet, "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-listbytag/release", url.Values{"tagId": {mgProbeText(texts[1])}, "templateVersion": {"2"}, "pageNumber": {page}, "pageSize": {"24"}})
				}
			}
		}
	}
}

func TestMGLiveShapes(t *testing.T) {
	report := mgProbeOpen(t)
	root := mgProbe(t, report, "shape_categories", http.MethodGet, "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-taglist/release", nil)
	groups, _ := root["data"].([]any)
	if len(groups) > 0 {
		group, _ := groups[0].(map[string]any)
		rows, _ := group["content"].([]any)
		if len(rows) > 0 {
			row, _ := rows[0].(map[string]any)
			texts, _ := row["texts"].([]any)
			if len(texts) > 1 {
				root = mgProbe(t, report, "shape_tag_list", http.MethodGet, "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-listbytag/release", url.Values{"tagId": {mgProbeText(texts[1])}, "templateVersion": {"2"}, "pageNumber": {"1"}, "pageSize": {"24"}})
				data, _ := root["data"].(map[string]any)
				items, _ := data["contentItemList"].([]any)
				for _, item := range items {
					_ = report.Encode(mgProbeRecord{Case: "shape_tag_item", Shape: mgProbeShape(item, "", 0)})
				}
			}
		}
	}
	root = mgProbe(t, report, "shape_cold", http.MethodGet, "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do", url.Values{"resourceType": {"2"}, "copyrightId": {"60054701923"}})
	items, _ := root["resource"].([]any)
	if len(items) > 0 {
		item, _ := items[0].(map[string]any)
		_ = report.Encode(mgProbeRecord{Case: "shape_cold_duration", Shape: map[string]any{"length": item["length"], "duration": item["duration"]}})
	}
}

// 以下离线夹具只刻画公开响应结构，不是实网结果或正式目录数据。
type mgDoerFunc func(*http.Request) (*http.Response, error)

func (f mgDoerFunc) Do(r *http.Request) (*http.Response, error) { return f(r) }
func mgJSONResponse(value any) *http.Response {
	data, _ := json.Marshal(value)
	return &http.Response{StatusCode: http.StatusOK, Header: http.Header{"Content-Type": {"application/json"}}, Body: io.NopCloser(bytes.NewReader(data))}
}
func mgSongFixture(id string) map[string]any {
	return map[string]any{"copyrightId": id, "songId": "3790007", "songName": "<em>测试曲</em>", "singer": "测试歌手", "album": "测试专辑", "albumId": "100", "length": "00:04:30", "albumImgs": []any{map[string]any{"img": "http://d.musicapp.migu.cn/public/test.jpg"}}, "lrcUrl": "http://d.musicapp.migu.cn/data/oss/resource/00/5b/o7/70896c574ad040789a981d164fe24ff9", "isDownload": "1", "newRateFormats": []any{map[string]any{"formatType": "PQ"}}}
}

func TestMGColdLookupAndMusicInfo(t *testing.T) {
	calls := 0
	client := mgDoerFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.Method != http.MethodGet || r.URL.Host != "c.musicapp.migu.cn" || r.URL.Path != "/MIGUM2.0/v1.0/content/resourceinfo.do" || r.URL.Query().Get("copyrightId") != "600547Y0514" || r.URL.Query().Get("resourceType") != "2" || r.URL.Query().Has("resourceId") {
			t.Fatalf("unexpected cold lookup: %s %s", r.Method, r.URL.Redacted())
		}
		return mgJSONResponse(map[string]any{"code": "000000", "resource": []any{mgSongFixture("600547Y0514")}}), nil
	})
	track, err := NewMG(client).Track(t.Context(), "mg:600547Y0514")
	if err != nil || track.ID != "mg:600547Y0514" || track.ProviderID != "mg" || track.Title != "测试曲" || track.Duration != 270 || track.CoverURL != "https://d.musicapp.migu.cn/public/test.jpg" || track.CanDownload || len(track.Qualities) != 0 {
		t.Fatalf("track: %+v err=%v", track, err)
	}
	info, err := NewMG(client).MusicInfo(t.Context(), track.ID)
	if err != nil || info["copyrightId"] != "600547Y0514" || info["songmid"] != "3790007" || info["source"] != "mg" || info["albumId"] != "100" || info["interval"] != "04:30" || calls != 2 {
		t.Fatalf("musicInfo fields/cold lookup mismatch: %#v %v calls=%d", info, err, calls)
	}
	for _, key := range []string{"url", "lrcUrl", "mrcUrl", "newRateFormats", "isDownload"} {
		if _, ok := info[key]; ok {
			t.Fatalf("unexpected forwarded upstream field %s", key)
		}
	}
}

func TestMGInputAndUnsupportedNeverRequest(t *testing.T) {
	m := NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
		t.Fatal("invalid/unsupported operation made a request")
		return nil, ErrUnavailable
	}))
	for _, id := range []string{"60054701923", "tx:60054701923", "mg:playlist:1", "mg:chart:1", "mg:1/2", "mg:../", "mg:", "mg:0", "mg:123\r\n", "mg:123?x=1", "mg:123%2f", "mg:Ａ123", "mg:" + strings.Repeat("1", 33)} {
		if _, err := m.Track(t.Context(), id); !errors.Is(err, ErrNotFound) {
			t.Errorf("%q: %v", id, err)
		}
	}
	if _, err := m.Chart(t.Context(), "mg:playlist:1"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := m.Playlist(t.Context(), "mg:chart:1"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	for _, page := range []int{0, 51} {
		if _, err := m.Search(t.Context(), "x", "track", page); !errors.Is(err, ErrInput) {
			t.Fatal(err)
		}
	}
	if _, err := m.Search(t.Context(), "x", "unknown", 1); !errors.Is(err, ErrInput) {
		t.Fatal(err)
	}
	if _, err := m.Search(t.Context(), strings.Repeat("歌", 201), "track", 1); !errors.Is(err, ErrInput) {
		t.Fatal(err)
	}
	if _, err := m.Search(t.Context(), string([]byte{0xff}), "track", 1); !errors.Is(err, ErrInput) {
		t.Fatal(err)
	}
	if _, err := m.Playlists(t.Context(), "wy:tag:1", 1); !errors.Is(err, ErrInput) {
		t.Fatal(err)
	}
	if _, err := m.Playlists(t.Context(), "all", 2); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	if _, err := m.NewTracks(t.Context(), "all"); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
}

func TestMGSearchContracts(t *testing.T) {
	for _, kind := range []string{"track", "artist", "album", "playlist"} {
		t.Run(kind, func(t *testing.T) {
			m := NewMG(mgDoerFunc(func(r *http.Request) (*http.Response, error) {
				q := r.URL.Query()
				if r.Method != "GET" || r.URL.Host != "app.c.nf.migu.cn" || q.Get("pageNo") != "2" || q.Get("pageSize") != "20" || q.Get("text") != "A & 周" {
					t.Fatal("search contract mismatch")
				}
				var switches map[string]int
				if json.Unmarshal([]byte(q.Get("searchSwitch")), &switches) != nil {
					t.Fatal("invalid switch")
				}
				remote := map[string]string{"track": "song", "artist": "singer", "album": "album", "playlist": "songlist"}[kind]
				for key, value := range switches {
					if (key == remote && value != 1) || (key != remote && value != 0) {
						t.Fatal("incorrect search switch")
					}
				}
				result := map[string]any{"totalCount": "21"}
				if kind == "track" {
					result["resultList"] = []any{[]any{mgSongFixture("600547Y0514")}}
				} else {
					result["result"] = []any{map[string]any{"id": "123", "name": "<em>测试</em>", "singer": "歌手", "musicNum": "15"}}
				}
				key := map[string]string{"track": "songResultData", "artist": "singerResultData", "album": "albumResultData", "playlist": "songListResultData"}[kind]
				return mgJSONResponse(map[string]any{"code": "000000", key: result}), nil
			}))
			out, err := m.Search(t.Context(), "A & 周", kind, 2)
			if err != nil || out.Total != 21 || out.Page != 2 || out.PageSize != 20 || len(out.Tracks)+len(out.Artists)+len(out.Albums)+len(out.Playlists) != 1 {
				t.Fatalf("%+v %v", out, err)
			}
		})
	}
}

func TestMGFailureIsNotEmptySuccess(t *testing.T) {
	for name, body := range map[string]string{"html": "<html>blocked</html>", "business": `{"code":"411"}`, "missing": `{"code":"000000"}`, "invalid_resource": `{"code":"000000","resource":{}}`, "mismatch": `{"code":"000000","resource":[{"copyrightId":"999","songName":"other"}]}`, "trailing": `{"code":"000000","resource":[]} {}`} {
		t.Run(name, func(t *testing.T) {
			m := NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
			}))
			if _, err := m.Track(t.Context(), "mg:60054701923"); !errors.Is(err, ErrUnavailable) {
				t.Fatalf("expected unavailable, got %v", err)
			}
		})
	}
	m := NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
		return mgJSONResponse(map[string]any{"code": "000000", "resource": []any{}}), nil
	}))
	if _, err := m.Track(t.Context(), "mg:60054701923"); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
	m = NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
		return mgJSONResponse(map[string]any{"code": "000000", "songResultData": map[string]any{"totalCount": "0", "resultList": []any{}}}), nil
	}))
	out, err := m.Search(t.Context(), "不存在的查询", "track", 1)
	if err != nil || out.Total != 0 || len(out.Tracks) != 0 {
		t.Fatal(err)
	}
}

func TestMGLyricsBoundaries(t *testing.T) {
	for _, raw := range []string{"http://127.0.0.1/a.lrc", "http://evil.d.musicapp.migu.cn/a.lrc", "http://d.musicapp.migu.cn.evil.test/a.lrc", "http://u:p@d.musicapp.migu.cn/a.lrc", "http://d.musicapp.migu.cn:443/a.lrc", "https://d.musicapp.migu.cn/a.mp3", "https://d.musicapp.migu.cn/a.lrc?redirect=1", "https://d.musicapp.migu.cn/a.lrc#x", "ftp://d.musicapp.migu.cn/a.lrc"} {
		t.Run(raw, func(t *testing.T) {
			calls := 0
			m := NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
				calls++
				song := mgSongFixture("60054701923")
				song["lrcUrl"] = raw
				return mgJSONResponse(map[string]any{"code": "000000", "resource": []any{song}}), nil
			}))
			if _, err := m.Lyrics(t.Context(), "mg:60054701923"); !errors.Is(err, ErrUnavailable) || calls != 1 {
				t.Fatalf("err=%v calls=%d", err, calls)
			}
		})
	}
	calls := 0
	m := NewMG(mgDoerFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return mgJSONResponse(map[string]any{"code": "000000", "resource": []any{mgSongFixture("60054701923")}}), nil
		}
		if r.URL.Scheme != "https" || r.URL.Host != "d.musicapp.migu.cn" || r.Method != "GET" {
			t.Fatal("unsafe lyric fetch")
		}
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader("\ufeff[00:01.00]测试歌词\n[00:02.50]第二句"))}, nil
	}))
	out, err := m.Lyrics(t.Context(), "mg:60054701923")
	if err != nil || len(out.Lines) != 2 || out.Lines[1].Time != 2.5 || out.Source != "米咕" {
		t.Fatalf("%+v %v", out, err)
	}
}

func TestMGLiveAdapter(t *testing.T) {
	report := mgProbeOpen(t)
	adapter := func(t *testing.T, label string) *MG {
		return NewMG(mgProbeDoer{t: t, report: report, label: label})
	}
	for _, kind := range []string{"track", "artist", "album", "playlist"} {
		t.Run("search_"+kind, func(t *testing.T) {
			out, err := adapter(t, "adapter_search_"+kind).Search(t.Context(), "周杰伦", kind, 1)
			if err != nil || len(out.Tracks)+len(out.Albums)+len(out.Artists)+len(out.Playlists) == 0 {
				t.Fatalf("no usable results: %v", err)
			}
			t.Logf("total=%d tracks=%d artists=%d albums=%d playlists=%d", out.Total, len(out.Tracks), len(out.Artists), len(out.Albums), len(out.Playlists))
		})
	}
	t.Run("charts", func(t *testing.T) {
		m := adapter(t, "adapter_charts")
		out, err := m.Charts(t.Context())
		if err != nil || len(out) == 0 {
			t.Fatalf("chart index: %v", err)
		}
		t.Logf("charts=%d", len(out))
		for _, id := range []string{out[0].ID, "mg:chart:27186466"} {
			detail, err := m.Chart(t.Context(), id)
			if err != nil || len(detail.Tracks) == 0 {
				t.Errorf("chart %s: %v", id, err)
			} else {
				t.Logf("chart=%s tracks=%d total=%d", id, len(detail.Tracks), detail.TrackCount)
			}
		}
	})
	t.Run("playlists", func(t *testing.T) {
		m := adapter(t, "adapter_playlists")
		out, err := m.Playlists(t.Context(), "all", 1)
		if err != nil || len(out) == 0 {
			t.Fatalf("recommend: %v", err)
		}
		categories, err := m.PlaylistCategories(t.Context())
		if err != nil || len(categories) == 0 {
			t.Fatalf("categories: %v", err)
		}
		p1, err := m.Playlists(t.Context(), categories[0].ID, 1)
		if err != nil || len(p1) == 0 {
			t.Fatalf("tag page1: %v", err)
		}
		p2, err := m.Playlists(t.Context(), categories[0].ID, 2)
		if err != nil || len(p2) == 0 {
			t.Fatalf("tag page2: %v", err)
		}
		if p1[0].ID == p2[0].ID {
			t.Fatal("tag pages repeat")
		}
		t.Logf("recommend=%d categories=%d tag-page1=%d tag-page2=%d", len(out), len(categories), len(p1), len(p2))
		detail, err := m.Playlist(t.Context(), "mg:playlist:233542261")
		if err != nil || len(detail.Tracks) == 0 {
			t.Fatalf("playlist detail: %v", err)
		}
		t.Logf("playlist tracks=%d total=%d", len(detail.Tracks), detail.TrackCount)
	})
	t.Run("cold_and_lyrics", func(t *testing.T) {
		m := adapter(t, "adapter_cold")
		for _, id := range []string{"mg:60054701923", "mg:6902739Z01T"} {
			track, err := m.Track(t.Context(), id)
			if err != nil || track.ID != id {
				t.Fatalf("cold track %s: %v", id, err)
			}
			info, err := m.MusicInfo(t.Context(), id)
			if err != nil || info["copyrightId"] != strings.TrimPrefix(id, "mg:") {
				t.Fatalf("cold musicInfo: %v", err)
			}
			lyrics, err := m.Lyrics(t.Context(), id)
			if err != nil || len(lyrics.Lines) == 0 {
				t.Fatalf("lyrics %s: %v", id, err)
			}
			t.Logf("track=%s duration=%d lyricLines=%d", id, track.Duration, len(lyrics.Lines))
		}
	})
}

func TestMGLiveIdentity(t *testing.T) {
	report := mgProbeOpen(t)
	root := mgProbe(t, report, "identity_search", http.MethodGet, "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do", url.Values{"text": {"周杰伦"}, "pageNo": {"1"}, "pageSize": {"20"}, "searchSwitch": {`{"song":1,"album":0,"singer":0,"songlist":0,"tagSong":0,"mvSong":0,"bestShow":0}`}, "isCopyright": {"1"}, "isCorrect": {"1"}, "sort": {"0"}})
	data, _ := root["songResultData"].(map[string]any)
	groups, _ := data["resultList"].([]any)
	if len(groups) == 0 {
		t.Fatal("no search samples")
	}
	samples := 0
	for _, group := range groups {
		variants, _ := group.([]any)
		if len(variants) == 0 {
			continue
		}
		row, _ := variants[0].(map[string]any)
		id, copyright := mgProbeText(row["id"]), mgProbeText(row["copyrightId"])
		if samples >= 2 && strings.Trim(copyright, "0123456789") == "" {
			continue
		}
		_ = report.Encode(mgProbeRecord{Case: "identity_sample", PublicIDs: map[string][]string{"songId": {id}, "copyrightId": {copyright}}})
		for _, key := range []string{"copyrightId", "resourceId"} {
			value := copyright
			if key == "resourceId" {
				value = id
			}
			result := mgProbe(t, report, "identity_lookup_"+key, http.MethodGet, "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do", url.Values{"resourceType": {"2"}, key: {value}})
			items, _ := result["resource"].([]any)
			match := false
			for _, item := range items {
				song, _ := item.(map[string]any)
				if mgProbeText(song["songId"]) == id && mgProbeText(song["copyrightId"]) == copyright {
					match = true
				}
			}
			if !match {
				t.Errorf("cold identity mismatch via %s, songId=%s copyrightId=%s resources=%d", key, id, copyright, len(items))
			}
		}
		samples++
		if samples >= 5 {
			break
		}
	}
	t.Logf("identity samples=%d", samples)
}

func TestMGChartsAndCategories(t *testing.T) {
	m := NewMG(mgDoerFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/pc/bmw/rank/rank-index/v1.0":
			return mgJSONResponse(map[string]any{"code": "000000", "data": map[string]any{"contents": []any{map[string]any{"contents": []any{map[string]any{"rankId": "12", "rankName": "真实结构榜单"}, map[string]any{"rankId": "12", "rankName": "重复展示"}}}}}}), nil
		case "/MIGUM2.0/v1.0/content/querycontentbyId.do":
			if r.URL.Query().Get("columnId") != "12" || r.URL.Query().Get("needAll") != "0" {
				t.Fatal("chart request")
			}
			return mgJSONResponse(map[string]any{"code": "000000", "columnInfo": map[string]any{"columnId": "12", "columnTitle": "测试榜", "contentsCount": "1", "contents": []any{map[string]any{"objectInfo": mgSongFixture("60054701923")}}}}), nil
		case "/pc/v1.0/template/musiclistplaza-taglist/release":
			return mgJSONResponse(map[string]any{"code": "000000", "data": []any{map[string]any{"header": map[string]any{"title": "风格"}, "content": []any{map[string]any{"texts": []any{"流行", "123", "other"}}}}}}), nil
		default:
			t.Fatalf("unexpected path %s", r.URL.Path)
			return nil, ErrUnavailable
		}
	}))
	charts, err := m.Charts(t.Context())
	if err != nil || len(charts) != 1 || charts[0].ID != "mg:chart:12" {
		t.Fatalf("%+v %v", charts, err)
	}
	chart, err := m.Chart(t.Context(), charts[0].ID)
	if err != nil || chart.ID != charts[0].ID || chart.TrackCount != 1 || len(chart.Tracks) != 1 || chart.Tracks[0].ProviderID != "mg" {
		t.Fatalf("%+v %v", chart, err)
	}
	categories, err := m.PlaylistCategories(t.Context())
	if err != nil || len(categories) != 1 || categories[0].ID != "mg:tag:123" || categories[0].Group != "风格" {
		t.Fatalf("%+v %v", categories, err)
	}
}

func TestMGPlaylistPagingAndNamespace(t *testing.T) {
	calls := 0
	m := NewMG(mgDoerFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.URL.Host != "app.c.nf.migu.cn" || r.URL.Path != "/pc/v1.0/template/musiclistplaza-listbytag/release" || r.URL.Query().Get("pageNumber") != "2" || r.URL.Query().Get("tagId") != "123" {
			t.Fatal("tag page contract")
		}
		return mgJSONResponse(map[string]any{"code": "000000", "data": map[string]any{"contentItemList": []any{map[string]any{"template": "space"}, map[string]any{"itemList": []any{map[string]any{"title": "测试歌单", "actionUrl": "http://127.0.0.1/do-not-follow", "logEvent": map[string]any{"contentId": "456", "contentType": "2021"}}}}}}}), nil
	}))
	out, err := m.Playlists(t.Context(), "mg:tag:123", 2)
	if err != nil || calls != 1 || len(out) != 1 || out[0].ID != "mg:playlist:456" {
		t.Fatalf("%+v %v calls=%d", out, err, calls)
	}
}

func TestMGPlaylistDetailPages(t *testing.T) {
	for _, repeat := range []bool{false, true} {
		t.Run(map[bool]string{false: "real_pages", true: "repeat_rejected"}[repeat], func(t *testing.T) {
			requests := 0
			m := NewMG(mgDoerFunc(func(r *http.Request) (*http.Response, error) {
				requests++
				if r.URL.Query().Get("playlistId") != "123" {
					t.Fatal("playlist identity")
				}
				if r.URL.Host == "c.musicapp.migu.cn" {
					return mgJSONResponse(map[string]any{"code": "000000", "data": map[string]any{"musicListId": "123", "title": "测试歌单", "musicNum": "51"}}), nil
				}
				if r.URL.Host != "app.c.nf.migu.cn" || r.URL.Query().Get("pageSize") != "50" {
					t.Fatal("song page contract")
				}
				rows := []any{}
				if r.URL.Query().Get("pageNo") == "1" {
					for i := 1; i <= 50; i++ {
						song := mgSongFixture(strconv.FormatInt(60054700000+int64(i), 10))
						rows = append(rows, song)
					}
				} else {
					id := "60054700051"
					if repeat {
						id = "60054700001"
					}
					rows = append(rows, mgSongFixture(id))
				}
				return mgJSONResponse(map[string]any{"code": "000000", "data": map[string]any{"totalCount": 51, "songList": rows}}), nil
			}))
			out, err := m.Playlist(t.Context(), "mg:playlist:123")
			if repeat {
				if !errors.Is(err, ErrUnavailable) {
					t.Fatal(err)
				}
				return
			}
			if err != nil || requests != 3 || out.TrackCount != 51 || len(out.Tracks) != 51 {
				t.Fatalf("count=%d/%d requests=%d err=%v", len(out.Tracks), out.TrackCount, requests, err)
			}
		})
	}
}

func TestMGHTTPAndMissingFields(t *testing.T) {
	for _, status := range []int{302, 403, 429, 500} {
		m := NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader("private upstream error"))}, nil
		}))
		if _, err := m.Track(t.Context(), "mg:60054701923"); !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), "private") {
			t.Fatal(err)
		}
	}
	for _, body := range []string{strings.Repeat("x", (1<<20)+1), `{"code":"000000","songResultData":{"totalCount":"3","resultList":[]}}`, `{"code":"000000","songResultData":{"totalCount":"3"}}`} {
		m := NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body))}, nil
		}))
		if _, err := m.Search(t.Context(), "test", "track", 1); !errors.Is(err, ErrUnavailable) {
			t.Fatal(err)
		}
	}
	m := NewMG(nil)
	if _, err := m.Track(t.Context(), "mg:60054701923"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	m = NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
		song := mgSongFixture("60054701923")
		delete(song, "songId")
		return mgJSONResponse(map[string]any{"code": "000000", "resource": []any{song}}), nil
	}))
	if _, err := m.MusicInfo(t.Context(), "mg:60054701923"); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
}

func TestMGLiveSearchPaging(t *testing.T) {
	report := mgProbeOpen(t)
	for _, kind := range []string{"track", "artist", "album", "playlist"} {
		t.Run(kind, func(t *testing.T) {
			m := NewMG(mgProbeDoer{t: t, report: report, label: "paging_search_" + kind})
			first, err := m.Search(t.Context(), "周杰伦", kind, 1)
			if err != nil {
				t.Fatal(err)
			}
			second, err := m.Search(t.Context(), "周杰伦", kind, 2)
			if err != nil {
				t.Fatal(err)
			}
			ids := func(result SearchResult) []string {
				out := []string{}
				for _, x := range result.Tracks {
					out = append(out, x.ID)
				}
				for _, x := range result.Artists {
					out = append(out, x.ID)
				}
				for _, x := range result.Albums {
					out = append(out, x.ID)
				}
				for _, x := range result.Playlists {
					out = append(out, x.ID)
				}
				return out
			}
			a, b := ids(first), ids(second)
			if first.Total > 20 && (len(b) == 0 || len(a) > 0 && a[0] == b[0]) {
				t.Fatal("repeated or missing second page")
			}
			if first.Total <= 20 && len(b) > 0 {
				t.Fatal("unexpected rows beyond total")
			}
			t.Logf("page1=%d page2=%d total=%d", len(a), len(b), first.Total)
		})
	}
	t.Run("rare_artist_query", func(t *testing.T) {
		m := NewMG(mgProbeDoer{t: t, report: report, label: "rare_artist_search"})
		out, err := m.Search(t.Context(), "MeloraNoSuchArtist793158617462", "artist", 1)
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("total=%d rows=%d", out.Total, len(out.Artists))
	})
}

func TestMGSearchOmittedResultsOnlyBeyondTotal(t *testing.T) {
	m := NewMG(mgDoerFunc(func(*http.Request) (*http.Response, error) {
		return mgJSONResponse(map[string]any{"code": "000000", "singerResultData": map[string]any{"totalCount": "4"}}), nil
	}))
	if _, err := m.Search(t.Context(), "测试", "artist", 1); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	out, err := m.Search(t.Context(), "测试", "artist", 2)
	if err != nil || out.Total != 4 || len(out.Artists) != 0 {
		t.Fatalf("%+v %v", out, err)
	}
}

func TestMGLiveProductionGuard(t *testing.T) {
	report := mgProbeOpen(t)
	m := NewMG(GuardedClient())
	track, err := m.Track(t.Context(), "mg:6902739Z01T")
	if err != nil || track.ID != "mg:6902739Z01T" {
		t.Fatalf("production cold lookup: %v", err)
	}
	_ = report.Encode(mgProbeRecord{Case: "production_track", Method: "GET", Host: "c.musicapp.migu.cn", Path: "/MIGUM2.0/v1.0/content/resourceinfo.do", Shape: map[string]any{"preciseIdentity": true}})
	lyrics, err := m.Lyrics(t.Context(), track.ID)
	if !catalogHosts["d.musicapp.migu.cn"] {
		if !errors.Is(err, ErrUnavailable) {
			t.Fatalf("missing lyric allowlist should block: %v", err)
		}
		_ = report.Encode(mgProbeRecord{Case: "production_lyrics", Method: "GET", Host: "d.musicapp.migu.cn", Error: "catalog_host_not_allowed"})
		t.Log("生产冷查询通过；歌词被当前 catalog Host 白名单拒绝，不能计为生产歌词成功")
		return
	}
	if err != nil || len(lyrics.Lines) == 0 {
		t.Fatalf("production lyrics: %v", err)
	}
	_ = report.Encode(mgProbeRecord{Case: "production_lyrics", Method: "GET", Host: "d.musicapp.migu.cn", Shape: map[string]any{"lyricLineCount": len(lyrics.Lines)}})
}

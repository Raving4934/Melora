package catalog

import (
	"context"
	"encoding/json"
	"fmt"
	"html"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

// KW 只读取匿名公开目录，不解析媒体地址、不声明播放或下载授权。
// 协议字段依据KW公开响应及 LX 上游字段约定独立实现，不依赖搜索缓存。
// 所有 I/O 经 catalogRequest 和注入的 HTTPDoer；构造器没有隐式请求。
type KW struct {
	http     HTTPDoer
	metadata metadataMemory
}

var _ Adapter = (*KW)(nil)
var _ DiscoveryAdapter = (*KW)(nil)

func NewKW(client HTTPDoer) *KW { return &KW{http: client} }

const (
	kwSearchPageSize   = 20
	kwPlaylistPageSize = 24
	kwTrackLimit       = 100
	kwMaxPage          = 50
)

// 保留 errors.Is(err, ErrUnavailable)，但不把其他平台名字或上游错误正文泄漏给 UI。
type kwUnavailableError struct{}

func (kwUnavailableError) Error() string { return "酷沃目录暂不可用，请稍后重试" }
func (kwUnavailableError) Unwrap() error { return ErrUnavailable }

var kwErrUnavailable error = kwUnavailableError{}

type kwObject map[string]json.RawMessage

var kwHTMLTags = regexp.MustCompile(`(?s)<[^>]*>`)
var kwHTMLBreaks = regexp.MustCompile(`(?i)<br\s*/?>`)
var kwImageHosts = []string{
	"img1.kuwo.cn", "img2.kuwo.cn", "img3.kuwo.cn", "img4.kuwo.cn",
	"kwimg1.kuwo.cn", "kwimg2.kuwo.cn", "kwimg3.kuwo.cn", "kwimg4.kuwo.cn",
	"kwcdn.kuwo.cn", "sycdn.kuwo.cn", "h5s.kuwo.cn",
}

func kwClean(raw string, limit int) string {
	raw = kwHTMLBreaks.ReplaceAllString(raw, "\n")
	return clean(html.UnescapeString(kwHTMLTags.ReplaceAllString(raw, "")), limit)
}
func kwScalar(raw json.RawMessage) (string, bool) {
	if len(raw) == 0 || string(raw) == "null" {
		return "", false
	}
	var text string
	if json.Unmarshal(raw, &text) == nil {
		return strings.TrimSpace(text), true
	}
	var number json.Number
	if json.Unmarshal(raw, &number) == nil {
		return number.String(), true
	}
	return "", false
}
func kwText(object kwObject, names ...string) string {
	for _, name := range names {
		if value, ok := kwScalar(object[name]); ok && value != "" {
			return value
		}
	}
	return ""
}
func kwDecimal(text string) (int64, bool) {
	if text == "" || len(text) > 18 {
		return 0, false
	}
	for _, r := range text {
		if r < '0' || r > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseInt(text, 10, 64)
	return n, err == nil
}
func kwCount(object kwObject, names ...string) (int, bool) {
	for _, name := range names {
		if raw, exists := object[name]; exists {
			text, ok := kwScalar(raw)
			if !ok {
				return 0, false
			}
			n, ok := kwDecimal(text)
			if !ok || n > 1<<31-1 {
				return 0, false
			}
			return int(n), true
		}
	}
	return 0, false
}
func kwRemoteID(raw string) (string, bool) {
	n, ok := kwDecimal(raw)
	if !ok || n <= 0 {
		return "", false
	}
	return strconv.FormatInt(n, 10), true
}
func kwParseID(raw, kind string) (string, error) {
	prefix := "kw:" + kind
	if !strings.HasPrefix(raw, prefix) {
		return "", ErrNotFound
	}
	suffix := strings.TrimPrefix(raw, prefix)
	n, ok := kwDecimal(suffix)
	if !ok || n <= 0 || strconv.FormatInt(n, 10) != suffix {
		return "", ErrNotFound
	}
	return suffix, nil
}
func kwObjects(object kwObject, name string) ([]kwObject, error) {
	raw, ok := object[name]
	if !ok || string(raw) == "null" {
		return nil, kwErrUnavailable
	}
	var out []kwObject
	if json.Unmarshal(raw, &out) != nil || out == nil || len(out) > 4096 {
		return nil, kwErrUnavailable
	}
	return out, nil
}
func kwChild(object kwObject, name string) (kwObject, error) {
	var out kwObject
	if json.Unmarshal(object[name], &out) != nil || out == nil {
		return nil, kwErrUnavailable
	}
	return out, nil
}
func kwCheckError(object kwObject) error {
	if len(object) == 0 {
		return kwErrUnavailable
	}
	if value, ok := object["success"]; ok {
		var success bool
		if json.Unmarshal(value, &success) != nil || !success {
			return kwErrUnavailable
		}
	}
	for _, key := range []string{"code", "status", "error_code", "errorCode", "retcode"} {
		if _, ok := object[key]; ok {
			n, valid := kwCount(object, key)
			if !valid || n != 0 && n != 200 {
				return kwErrUnavailable
			}
		}
	}
	for _, key := range []string{"error", "error_msg"} {
		if raw, ok := object[key]; ok && string(raw) != "null" && string(raw) != `""` && string(raw) != "0" {
			return kwErrUnavailable
		}
	}
	return nil
}
func kwStatus(object kwObject, key string) error {
	n, ok := kwCount(object, key)
	if !ok || n != 200 {
		return kwErrUnavailable
	}
	return nil
}
func (k *KW) kwGet(ctx context.Context, host, path string, params url.Values) (kwObject, error) {
	headers := http.Header{"Accept": []string{"application/json"}, "Referer": []string{"https://www.kuwo.cn/"}}
	body, err := catalogRequest(ctx, k.http, http.MethodGet, "https://"+host+path+"?"+params.Encode(), headers, nil)
	if err != nil {
		return nil, catalogIssueError(err)
	}
	// 不 eval JSONP/JavaScript，不靠替换引号解析代码；search 必须请求 mobi=1 的 JSON。
	var response kwObject
	if json.Unmarshal(body, &response) != nil || response == nil {
		return nil, kwErrUnavailable
	}
	return response, nil
}
func kwImage(raw string) string {
	// 先保留目录的图片域白名单校验，再修复盲升级 HTTPS 后证书失配的已知 CDN。
	return NormalizeCoverURL(catalogImage(raw, kwImageHosts...))
}
func kwPicture(raw, base string) string {
	if image := kwImage(raw); image != "" {
		return image
	}
	if raw == "" || strings.HasPrefix(raw, "/") || strings.ContainsAny(raw, `\?#`) {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil || u.IsAbs() || u.Host != "" {
		return ""
	}
	for _, part := range strings.Split(u.Path, "/") {
		if part == "." || part == ".." {
			return ""
		}
	}
	// base 只是目录前缀，先做安全校验，待完整资源路径拼好后再归一化。
	if catalogImage(base, kwImageHosts...) == "" {
		return ""
	}
	return kwImage(strings.TrimRight(base, "/") + "/" + raw)
}

func kwSongID(object kwObject) (string, bool) {
	rid := ""
	for _, field := range []string{"MUSICRID", "musicrId", "musicrid", "rid", "id"} {
		text := kwText(object, field)
		if text == "" {
			continue
		}
		candidate, ok := kwRemoteID(strings.TrimPrefix(text, "MUSIC_"))
		if !ok || rid != "" && candidate != rid {
			return "", false
		}
		rid = candidate
	}
	return rid, rid != ""
}
func kwSong(object kwObject) (model.Track, map[string]any, bool) {
	rid, valid := kwSongID(object)
	if !valid {
		return model.Track{}, nil, false
	}

	name := kwClean(kwText(object, "SONGNAME", "songName", "name", "NAME"), 200)
	if rid == "" || name == "" {
		return model.Track{}, nil, false
	}
	duration, _ := kwCount(object, "song_duration", "DURATION", "duration")
	if duration > 24*60*60 {
		duration = 0
	}
	cover := kwImage(kwText(object, "pic", "albumpic", "hts_pic"))
	if cover == "" {
		cover = kwPicture(kwText(object, "web_albumpic_short"), "https://img1.kuwo.cn/star/albumcover/")
	}
	track := model.Track{ID: "kw:" + rid, ProviderID: "kw", Title: name, Artist: kwClean(kwText(object, "ARTIST", "artist"), 200),
		Album: kwClean(kwText(object, "ALBUM", "album"), 200), Duration: duration, CoverURL: cover, Qualities: []string{}, CanDownload: false}
	albumID := kwText(object, "ALBUMID", "albumId", "albumid")
	if n, ok := kwDecimal(albumID); ok {
		albumID = strconv.FormatInt(n, 10)
	} else {
		albumID = ""
	}
	// LX 的 songmid/rid 是纯数字 RID；只有 MUSICRID/musicrId 带 MUSIC_ 前缀。
	// 不把带 kw: 的 UI ID 交给脚本，也不把 pay/formats 当作下载授权或可用音质。
	music := map[string]any{
		"id": rid, "songmid": rid, "rid": rid, "songId": rid, "songid": rid, "musicId": rid,
		"MUSICRID": "MUSIC_" + rid, "musicrid": "MUSIC_" + rid, "musicrId": "MUSIC_" + rid,
		"source": "kw", "name": track.Title, "songname": track.Title, "songName": track.Title,
		"singer": track.Artist, "artist": track.Artist, "albumName": track.Album, "albumId": albumID,
		"duration": duration, "interval": fmt.Sprintf("%02d:%02d", duration/60, duration%60), "img": cover,
		"types": []any{}, "_types": map[string]any{}, "typeUrl": map[string]any{},
	}
	return track, music, true
}
func kwSongs(rows []kwObject, limit int) ([]model.Track, error) {
	tracks := []model.Track{}
	seen := map[string]bool{}
	for _, row := range rows {
		track, _, ok := kwSong(row)
		if !ok {
			continue
		}
		if seen[track.ID] {
			continue
		}
		seen[track.ID] = true
		tracks = append(tracks, track)
		if len(tracks) == limit {
			break
		}
	}
	if len(rows) > 0 && len(tracks) == 0 {
		return nil, kwErrUnavailable
	}
	return tracks, nil
}
func kwPlaylistCard(row kwObject, category string) (model.Collection, bool) {
	rid, ok := kwRemoteID(kwText(row, "playlistid", "id"))
	if !ok {
		return model.Collection{}, false
	}
	name := kwClean(kwText(row, "name", "title"), 200)
	if name == "" {
		return model.Collection{}, false
	}
	count, _ := kwCount(row, "songnum", "total", "num")
	return model.Collection{ID: "kw:playlist_" + rid, ProviderID: "kw", Title: name,
		Description: kwClean(kwText(row, "intro", "info", "desc"), 1000), CoverURL: kwImage(kwText(row, "hts_pic", "img", "pic")), TrackCount: count, PlayCount: rawPlayCount(row, "playnum", "playcnt", "playcount"), Category: category}, true
}
func kwPlaylistCards(rows []kwObject, category string, limit int) ([]model.Collection, error) {
	out := []model.Collection{}
	seen := map[string]bool{}
	for _, row := range rows {
		card, ok := kwPlaylistCard(row, category)
		if !ok || seen[card.ID] {
			continue
		}
		out = append(out, card)
		seen[card.ID] = true
		if len(out) == limit {
			break
		}
	}
	if len(rows) > 0 && len(out) == 0 {
		return nil, kwErrUnavailable
	}
	return out, nil
}
func kwPageValid(page int) bool { return page >= 1 && page <= kwMaxPage }
func kwListConsistent(total, offset, rawCount int) bool {
	if total < offset {
		return rawCount == 0
	}
	return rawCount <= total-offset && !(total > offset && rawCount == 0)
}
func kwPageMatches(object kwObject, expected int, names ...string) bool {
	for _, name := range names {
		if _, exists := object[name]; exists {
			actual, valid := kwCount(object, name)
			if !valid || actual != expected {
				return false
			}
		}
	}
	return true
}

func (k *KW) Search(ctx context.Context, query, kind string, page int) (SearchResult, error) {
	out := SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}, Artists: []SearchArtist{}, Albums: []SearchAlbum{}, Page: page, PageSize: kwSearchPageSize}
	query = strings.TrimSpace(query)
	if !kwPageValid(page) || query == "" || !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 || strings.IndexFunc(query, unicode.IsControl) >= 0 {
		return out, ErrInput
	}
	kinds := map[string]string{"track": "music", "playlist": "playlist", "album": "album", "artist": "artist", "book": "album"}
	ft, ok := kinds[kind]
	if !ok {
		return out, ErrUnsupported
	}
	params := url.Values{"all": {query}, "ft": {ft}, "pn": {strconv.Itoa(page - 1)}, "rn": {strconv.Itoa(kwSearchPageSize)}, "rformat": {"json"}, "encoding": {"utf8"}, "client": {"kt"}, "mobi": {"1"}, "newver": {"1"}}
	if kind == "book" {
		// show_series_listen 是KW听书客户端的同款过滤：只返回长音频/有声专辑。
		params.Set("show_series_listen", "1")
	}
	response, err := k.kwGet(ctx, "search.kuwo.cn", "/r.s", params)
	if err != nil {
		return out, err
	}
	if err = kwCheckError(response); err != nil {
		return out, err
	}
	total, ok := kwCount(response, "TOTAL", "total")
	if !ok {
		return out, kwErrUnavailable
	}
	field := "abslist"
	if kind == "album" || kind == "book" {
		field = "albumlist"
	}
	rows, err := kwObjects(response, field)
	if err != nil {
		return out, err
	}
	if !kwListConsistent(total, (page-1)*kwSearchPageSize, len(rows)) {
		return out, kwErrUnavailable
	}
	if !kwPageMatches(response, page-1, "PN", "pn") {
		return out, kwErrUnavailable
	}
	out.Total = total
	switch kind {
	case "track":
		out.Tracks, err = k.cachedTracks(rows, kwSearchPageSize)
	case "playlist":
		out.Playlists, err = kwPlaylistCards(rows, "歌单", kwSearchPageSize)
	case "artist":
		seen := map[string]bool{}
		for _, row := range rows {
			rid, ok := kwRemoteID(kwText(row, "ARTISTID", "artistid", "id"))
			name := kwClean(kwText(row, "ARTIST", "artist", "name"), 200)
			if !ok || name == "" || seen[rid] {
				continue
			}
			seen[rid] = true
			count, _ := kwCount(row, "SONGNUM", "songnum")
			pic := kwPicture(kwText(row, "hts_PICPATH", "PICPATH"), kwText(response, "BASEPICPATH"))
			out.Artists = append(out.Artists, SearchArtist{ID: "kw:artist_" + rid, Name: name, CoverURL: pic, TrackCount: count})
			if len(out.Artists) == kwSearchPageSize {
				break
			}
		}
		if len(rows) > 0 && len(out.Artists) == 0 {
			err = kwErrUnavailable
		}
	case "album", "book":
		idPrefix := "kw:album_"
		if kind == "book" {
			// 有声书使用独立命名空间，普通音乐专辑搜索结果不会被当成听书条目。
			idPrefix = "kw:book_album_"
		}
		seen := map[string]bool{}
		for _, row := range rows {
			rid, ok := kwRemoteID(kwText(row, "albumid", "id"))
			name := kwClean(kwText(row, "name", "title"), 200)
			if !ok || name == "" || seen[rid] {
				continue
			}
			seen[rid] = true
			count, _ := kwCount(row, "musiccnt", "songnum")
			pic := kwPicture(kwText(row, "hts_img", "img", "pic"), kwText(response, "BASEPICPATH"))
			out.Albums = append(out.Albums, SearchAlbum{ID: idPrefix + rid, Title: name, Artist: kwClean(kwText(row, "artist"), 200), CoverURL: pic, TrackCount: count})
			if len(out.Albums) == kwSearchPageSize {
				break
			}
		}
		if len(rows) > 0 && len(out.Albums) == 0 {
			err = kwErrUnavailable
		}
	}
	return out, err
}

// kwChartNode 是公开榜单目录中的单个榜单条目；info 为歌曲数（部分目录节点为更新时间）。
type kwChartNode struct {
	ID       string
	Name     string
	Intro    string
	Category string
	CoverURL string
	Count    int
}

// kwChartWalk 遍历公开榜单目录两级结构（分组 → 榜单），不跟随站外专题链接。
func kwChartWalk(response kwObject, visit func(kwChartNode) bool) {
	groups, err := kwObjects(response, "child")
	if err != nil {
		return
	}
	for _, group := range groups {
		category := kwClean(kwText(group, "name"), 100)
		leaves, err := kwObjects(group, "child")
		if err != nil {
			continue
		}
		for _, leaf := range leaves {
			id, ok := kwRemoteID(kwText(leaf, "sourceid"))
			name := kwClean(kwText(leaf, "name", "disname"), 200)
			if !ok || name == "" {
				continue
			}
			node := kwChartNode{ID: id, Name: name, Category: category, CoverURL: kwImage(kwText(leaf, "pic")), Intro: kwClean(kwText(leaf, "intro"), 1000)}
			if n, ok := kwCount(leaf, "info"); ok {
				node.Count = n
			}
			if !visit(node) {
				return
			}
		}
	}
}

func (k *KW) Charts(ctx context.Context) ([]model.Collection, error) {
	response, err := k.kwGet(ctx, "wapi.kuwo.cn", "/api/pc/bang/list", nil)
	if err != nil {
		return nil, err
	}
	if err = kwCheckError(response); err != nil {
		return nil, err
	}
	out := []model.Collection{}
	seen := map[string]bool{}
	kwChartWalk(response, func(node kwChartNode) bool {
		if seen[node.ID] {
			return true
		}
		seen[node.ID] = true
		category := node.Category
		if category == "" {
			category = "排行榜"
		}
		out = append(out, model.Collection{ID: "kw:chart_" + node.ID, ProviderID: "kw", Title: node.Name, Description: node.Intro, CoverURL: node.CoverURL, TrackCount: node.Count, Category: category})
		return len(out) < 200
	})
	if len(out) == 0 {
		return nil, kwErrUnavailable
	}
	return out, nil
}

// kwChartMeta 读取单个榜单的名称、分组与简介；目录缺失时详情仍可降级展示。
func (k *KW) kwChartMeta(ctx context.Context, rid string) (kwChartNode, bool) {
	response, err := k.kwGet(ctx, "wapi.kuwo.cn", "/api/pc/bang/list", nil)
	if err != nil || kwCheckError(response) != nil {
		return kwChartNode{}, false
	}
	found := kwChartNode{}
	ok := false
	kwChartWalk(response, func(node kwChartNode) bool {
		if node.ID == rid {
			found = node
			ok = true
			return false
		}
		return true
	})
	return found, ok
}

// kwChartPageSize 为公开榜单接口接受的最大页大小（实测 rn>=50 返回 code=-1）。
const kwChartPageSize = 30

func (k *KW) Chart(ctx context.Context, raw string) (model.Collection, error) {
	rid, err := kwParseID(raw, "chart_")
	if err != nil {
		return model.Collection{}, err
	}
	meta, hasMeta := k.kwChartMeta(ctx, rid)
	tracks := []model.Track{}
	seen := map[string]bool{}
	cover := ""
	total := 0
	maxPages := (kwTrackLimit + kwChartPageSize - 1) / kwChartPageSize
	for page := 1; page <= maxPages; page++ {
		response, err := k.kwGet(ctx, "wapi.kuwo.cn", "/api/www/bang/bang/musicList", url.Values{"bangId": {rid}, "pn": {strconv.Itoa(page)}, "rn": {strconv.Itoa(kwChartPageSize)}, "httpsStatus": {"1"}})
		if err != nil {
			return model.Collection{}, err
		}
		if err = kwCheckError(response); err != nil {
			return model.Collection{}, err
		}
		if err = kwStatus(response, "code"); err != nil {
			return model.Collection{}, err
		}
		data, err := kwChild(response, "data")
		if err != nil {
			return model.Collection{}, err
		}
		pageTotal, ok := kwCount(data, "num")
		if !ok || page != 1 && pageTotal != total {
			return model.Collection{}, kwErrUnavailable
		}
		total = pageTotal
		rows, err := kwObjects(data, "musicList")
		if err != nil {
			return model.Collection{}, err
		}
		if len(rows) > kwChartPageSize || !kwListConsistent(total, (page-1)*kwChartPageSize, len(rows)) {
			return model.Collection{}, kwErrUnavailable
		}
		if cover == "" {
			cover = kwImage(kwText(data, "img"))
		}
		pageTracks, err := k.cachedTracks(rows, kwChartPageSize)
		if err != nil {
			return model.Collection{}, err
		}
		for _, track := range pageTracks {
			if !seen[track.ID] {
				seen[track.ID] = true
				tracks = append(tracks, track)
			}
		}
		if len(rows) == 0 || len(tracks) >= kwTrackLimit || len(tracks) >= total {
			break
		}
	}
	if len(tracks) > kwTrackLimit {
		tracks = tracks[:kwTrackLimit]
	}
	title, category, description := "排行榜", "排行榜", ""
	if hasMeta {
		if meta.Name != "" {
			title = meta.Name
		}
		if meta.Category != "" {
			category = meta.Category
		}
		description = meta.Intro
	}
	if total > len(tracks) {
		description = fmt.Sprintf("当前加载 %d 首，共 %d 首。", len(tracks), total) + description
	}
	if cover == "" && hasMeta {
		cover = meta.CoverURL
	}
	if cover == "" && len(tracks) > 0 {
		cover = tracks[0].CoverURL
	}
	return model.Collection{ID: raw, ProviderID: "kw", Title: title, Description: description, CoverURL: cover, TrackCount: total, Category: category, Tracks: tracks}, nil
}
func (k *KW) trackCollection(response kwObject, raw, category, nameKey, countKey string) (model.Collection, error) {
	name := kwClean(kwText(response, nameKey), 200)
	total, valid := kwCount(response, countKey)
	if name == "" || !valid {
		return model.Collection{}, kwErrUnavailable
	}
	rows, err := kwObjects(response, "musiclist")
	if err != nil {
		return model.Collection{}, err
	}
	if !kwListConsistent(total, 0, len(rows)) {
		return model.Collection{}, kwErrUnavailable
	}
	tracks, err := k.cachedTracks(rows, kwTrackLimit)
	if err != nil {
		return model.Collection{}, err
	}
	description := kwClean(kwText(response, "info"), 1000)
	if total > len(tracks) {
		description = fmt.Sprintf("当前加载 %d 首，共 %d 首。", len(tracks), total) + description
	}
	cover := kwImage(kwText(response, "v9_pic2", "pic"))
	if cover == "" && len(tracks) > 0 {
		cover = tracks[0].CoverURL
	}
	return model.Collection{ID: raw, ProviderID: "kw", Title: name, Description: description, CoverURL: cover, TrackCount: len(tracks), PlayCount: rawPlayCount(response, "playnum", "playcnt", "playcount"), Category: category, Tracks: tracks}, nil
}

func (k *KW) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	if !kwPageValid(page) {
		return nil, ErrInput
	}
	params := url.Values{"loginUid": {"0"}, "loginSid": {"0"}, "pn": {strconv.Itoa(page)}, "rn": {strconv.Itoa(kwPlaylistPageSize)}}
	path := "/api/pc/classify/playlist/getRcmPlayList"
	if category == "" || category == "all" {
		category = "all"
		params.Set("order", "hot")
	} else {
		if strings.HasPrefix(category, "kw:zone_") {
			return nil, ErrUnsupported
		}
		rid, err := kwParseID(category, "category_")
		if err != nil {
			return nil, ErrInput
		}
		path = "/api/pc/classify/playlist/getTagPlayList"
		params.Set("id", rid)
	}
	response, err := k.kwGet(ctx, "wapi.kuwo.cn", path, params)
	if err != nil {
		return nil, err
	}
	if err = kwCheckError(response); err != nil {
		return nil, err
	}
	if err = kwStatus(response, "code"); err != nil {
		return nil, err
	}
	data, err := kwChild(response, "data")
	if err != nil {
		return nil, err
	}
	if err = kwCheckError(data); err != nil {
		return nil, err
	}
	rows, err := kwObjects(data, "data")
	if err != nil {
		return nil, err
	}
	total, ok := kwCount(data, "total")
	if !ok || !kwListConsistent(total, (page-1)*kwPlaylistPageSize, len(rows)) {
		return nil, kwErrUnavailable
	}
	if !kwPageMatches(data, page, "pn") {
		return nil, kwErrUnavailable
	}
	return kwPlaylistCards(rows, category, kwPlaylistPageSize)
}
func (k *KW) Playlist(ctx context.Context, raw string) (model.Collection, error) {
	rid, err := kwParseID(raw, "playlist_")
	if err != nil {
		return model.Collection{}, err
	}
	// vipver 为匿名旧客户端的数据格式版本，不是账号 VIP 状态；这里只取目录。
	response, err := k.kwGet(ctx, "nplserver.kuwo.cn", "/pl.svc", url.Values{"op": {"getlistinfo"}, "pid": {rid}, "pn": {"0"}, "rn": {strconv.Itoa(kwTrackLimit)}, "encode": {"utf8"}, "keyset": {"pl2012"}, "identity": {"kuwo"}, "pcmp4": {"1"}, "newver": {"1"}, "vipver": {"MUSIC_9.0.5.0_W1"}})
	if err != nil {
		return model.Collection{}, err
	}
	if err = kwCheckError(response); err != nil {
		return model.Collection{}, err
	}
	if kwText(response, "result") != "ok" {
		return model.Collection{}, kwErrUnavailable
	}
	if !kwPageMatches(response, 0, "state", "pn") {
		return model.Collection{}, kwErrUnavailable
	}
	if raw, exists := response["ispub"]; exists {
		var public bool
		if json.Unmarshal(raw, &public) != nil || !public {
			return model.Collection{}, kwErrUnavailable
		}
	}
	actual, ok := kwRemoteID(kwText(response, "id"))
	if !ok {
		return model.Collection{}, kwErrUnavailable
	}
	// KW无效 pid 曾返回其他歌单的默认结果，不能以请求 ID 重新标记那份数据。
	if actual != rid {
		return model.Collection{}, ErrNotFound
	}
	return k.trackCollection(response, raw, "歌单", "title", "total")
}
func (k *KW) PlaylistCategories(ctx context.Context) ([]PlaylistCategory, error) {
	response, err := k.kwGet(ctx, "wapi.kuwo.cn", "/api/pc/classify/playlist/getTagList", url.Values{"cmd": {"rcm_keyword_playlist"}, "user": {"0"}, "prod": {"kwplayer_pc_9.0.5.0"}, "loginUid": {"0"}, "loginSid": {"0"}})
	if err != nil {
		return nil, err
	}
	if err = kwCheckError(response); err != nil {
		return nil, err
	}
	if err = kwStatus(response, "code"); err != nil {
		return nil, err
	}
	groups, err := kwObjects(response, "data")
	if err != nil {
		return nil, err
	}
	out := []PlaylistCategory{}
	seen := map[string]bool{}
	for _, group := range groups {
		name := kwClean(kwText(group, "name"), 100)
		rows, err := kwObjects(group, "data")
		if err != nil {
			return nil, err
		}
		for _, row := range rows {
			// 仅暴露 getTagPlayList 支持的普通分页分类；digest=43 的专区不是此能力。
			if kwText(row, "digest") != "10000" {
				continue
			}
			rid, ok := kwRemoteID(kwText(row, "id"))
			title := kwClean(kwText(row, "name"), 100)
			if !ok || title == "" || name == "" || seen[rid] {
				continue
			}
			seen[rid] = true
			out = append(out, PlaylistCategory{ID: "kw:category_" + rid, Name: title, Group: name})
			if len(out) == 256 {
				return out, nil
			}
		}
	}
	if len(out) == 0 {
		return nil, kwErrUnavailable
	}
	return out, nil
}

// 按精确 RID 读取单曲资料：不是关键词搜索，也不读取之前的搜索结果或缓存。
// 公开 r.s 在 rid=MUSIC_<id> 时返回该条目的 abslist，真实不存在时 total=0。
func (k *KW) kwSongInfo(ctx context.Context, raw string) (model.Track, map[string]any, error) {
	rid, err := kwParseID(raw, "")
	if err != nil {
		return model.Track{}, nil, err
	}
	response, err := k.kwGet(ctx, "search.kuwo.cn", "/r.s", url.Values{
		"client": {"kt"}, "ft": {"music"}, "rid": {"MUSIC_" + rid}, "mobi": {"1"}, "newver": {"1"},
		"rformat": {"json"}, "encoding": {"utf8"}, "pn": {"0"}, "rn": {"1"},
	})
	if err != nil {
		return model.Track{}, nil, err
	}
	if err = kwCheckError(response); err != nil {
		return model.Track{}, nil, err
	}
	rows, err := kwObjects(response, "abslist")
	if err != nil {
		return model.Track{}, nil, err
	}
	total, ok := kwCount(response, "total", "TOTAL")
	if !ok {
		return model.Track{}, nil, kwErrUnavailable
	}
	if total == 0 && len(rows) == 0 {
		return model.Track{}, nil, ErrNotFound
	}
	if total != 1 || len(rows) != 1 {
		return model.Track{}, nil, kwErrUnavailable
	}
	track, music, ok := kwSong(rows[0])
	if !ok {
		return model.Track{}, nil, kwErrUnavailable
	}
	if track.ID != raw {
		return model.Track{}, nil, ErrNotFound
	}
	return track, music, nil
}
func (k *KW) Track(ctx context.Context, raw string) (model.Track, error) {
	track, music, err := k.kwSongInfo(ctx, raw)
	if err == nil {
		k.metadata.put(model.CatalogMetadata{Track: track, MusicInfo: music})
	}
	return track, err
}
func (k *KW) MusicInfo(ctx context.Context, raw string) (map[string]any, error) {
	_, music, err := k.kwSongInfo(ctx, raw)
	return music, err
}
func (k *KW) kwLyricData(ctx context.Context, raw string) (kwObject, error) {
	rid, err := kwParseID(raw, "")
	if err != nil {
		return nil, err
	}
	response, err := k.kwGet(ctx, "m.kuwo.cn", "/newh5/singles/songinfoandlrc", url.Values{"musicId": {rid}})
	if err != nil {
		return nil, err
	}
	// status=301 也会出现在已知存在的 RID 上，只能表示查询失败，不能谎称歌曲不存在。
	if err = kwCheckError(response); err != nil {
		return nil, err
	}
	if err = kwStatus(response, "status"); err != nil {
		return nil, err
	}
	data, err := kwChild(response, "data")
	if err != nil {
		return nil, err
	}
	if err = kwCheckError(data); err != nil {
		return nil, err
	}
	song, err := kwChild(data, "songinfo")
	if err != nil {
		return nil, err
	}
	actual, ok := kwSongID(song)
	if !ok {
		return nil, kwErrUnavailable
	}
	if actual != rid {
		return nil, ErrNotFound
	}
	// H5 有时返回空歌名但仍有正确 RID 和真实歌词；歌词不依赖其缺失的目录字段。
	return data, nil
}
func (k *KW) Lyrics(ctx context.Context, raw string) (Lyrics, error) {
	out := Lyrics{Lines: []LyricLine{}, Source: "酷沃"}
	data, err := k.kwLyricData(ctx, raw)
	if err != nil {
		return out, err
	}
	rows, err := kwObjects(data, "lrclist")
	if err != nil {
		return out, err
	}
	if len(rows) == 0 {
		return out, ErrNotFound
	}
	seen := map[string]bool{}
	for _, row := range rows {
		seconds, err := strconv.ParseFloat(kwText(row, "time"), 64)
		text := kwClean(kwText(row, "lineLyric"), 500)
		if err != nil || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds < 0 || seconds > 24*60*60 || text == "" {
			continue
		}
		key := strconv.FormatFloat(seconds, 'g', -1, 64) + "\x00" + text
		if seen[key] {
			continue
		}
		seen[key] = true
		out.Lines = append(out.Lines, LyricLine{Time: seconds, Text: text})
	}
	if len(out.Lines) == 0 {
		return out, kwErrUnavailable
	}
	sort.SliceStable(out.Lines, func(i, j int) bool { return out.Lines[i].Time < out.Lines[j].Time })
	return out, nil
}
func (*KW) NewTracks(context.Context, string) ([]model.Track, error) { return nil, ErrUnsupported }

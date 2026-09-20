package catalog

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"melora/internal/model"
)

// MG 只读取匿名公开目录。所有 I/O 经注入的 HTTPDoer，不缓存搜索结果作为冷查询的前提，
// 不解析媒体地址，不把元数据中的音质、付费标志当作播放或下载授权。
type MG struct {
	http     HTTPDoer
	metadata metadataMemory
}

func NewMG(client HTTPDoer) *MG { return &MG{http: client} }

var _ Adapter = (*MG)(nil)
var _ DiscoveryAdapter = (*MG)(nil)

const mgApp = "https://app.c.nf.migu.cn"
const mgContent = "https://c.musicapp.migu.cn"

var mgMarkup = regexp.MustCompile(`<[^>]*>`)

// resourceinfo 的 lrcUrl 也有无扩展名 OSS 路径，不能只接受 .lrc 文件。
var mgLyricPath = regexp.MustCompile(`^(/([A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\.lrc|/data/oss/resource/([A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+)$`)

type mgObject = map[string]any

func mgText(v any) string {
	switch v := v.(type) {
	case string:
		return v
	case json.Number:
		return v.String()
	default:
		return ""
	}
}
func mgClean(v any, limit int) string {
	return clean(html.UnescapeString(mgMarkup.ReplaceAllString(mgText(v), "")), limit)
}
func mgFirst(o mgObject, fields ...string) string {
	for _, field := range fields {
		if text := mgText(o[field]); text != "" {
			return text
		}
	}
	return ""
}
func mgNumber(v any) (int, bool) {
	s := mgText(v)
	if s == "" || len(s) > 10 {
		return 0, false
	}
	for _, ch := range s {
		if ch < '0' || ch > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseUint(s, 10, 32)
	if err != nil || n > 1_000_000_000 {
		return 0, false
	}
	return int(n), true
}
func mgValidID(id string, alpha bool) bool {
	if len(id) == 0 || len(id) > 32 || strings.Trim(id, "0") == "" {
		return false
	}
	for _, ch := range id {
		if ch >= '0' && ch <= '9' {
			continue
		}
		if alpha && ch >= 'A' && ch <= 'Z' {
			continue
		}
		return false
	}
	return true
}
func mgLocalID(raw, kind string) (string, error) {
	prefix := "mg:"
	if kind != "" {
		prefix += kind + ":"
	}
	if !strings.HasPrefix(raw, prefix) {
		return "", ErrNotFound
	}
	id := strings.TrimPrefix(raw, prefix)
	if !mgValidID(id, kind == "") {
		return "", ErrNotFound
	}
	return id, nil
}
func mgRows(v any) ([]mgObject, bool) {
	items, ok := v.([]any)
	if !ok {
		return nil, false
	}
	out := make([]mgObject, 0, len(items))
	for _, item := range items {
		row, ok := item.(mgObject)
		if !ok {
			return nil, false
		}
		out = append(out, row)
	}
	return out, true
}
func (m *MG) get(ctx context.Context, host, path string, params url.Values) (mgObject, error) {
	endpoint := host + path
	if len(params) > 0 {
		endpoint += "?" + params.Encode()
	}
	data, err := catalogRequest(ctx, m.http, http.MethodGet, endpoint, http.Header{"Referer": {"https://music.migu.cn/"}, "Accept": {"application/json"}}, nil)
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var root mgObject
	if decoder.Decode(&root) != nil || mgText(root["code"]) != "000000" {
		return nil, ErrUnavailable
	}
	var trailing any
	if decoder.Decode(&trailing) != io.EOF {
		return nil, ErrUnavailable
	}
	return root, nil
}
func mgImage(raw string) string {
	// 精确公开图片域，不允许任意 migu.cn 子域、端口或外部跳转参数被用作歌词请求。
	u, err := url.Parse(raw)
	if err != nil || strings.ToLower(u.Hostname()) != "d.musicapp.migu.cn" {
		return ""
	}
	return catalogImage(raw, "d.musicapp.migu.cn")
}
func mgCover(o mgObject) string {
	for _, name := range []string{"imageUrl", "originalImgUrl", "columnPicUrl", "musicListPicUrl", "img", "img1", "img2"} {
		if cover := mgImage(mgText(o[name])); cover != "" {
			return cover
		}
	}
	for _, name := range []string{"imgItems", "albumImgs"} {
		rows, _ := mgRows(o[name])
		for _, row := range rows {
			if cover := mgImage(mgText(row["img"])); cover != "" {
				return cover
			}
		}
	}
	if item, ok := o["imgItem"].(mgObject); ok {
		return mgImage(mgFirst(item, "img", "imgUrl"))
	}
	return ""
}
func mgNames(o mgObject, keys ...string) string {
	for _, key := range keys {
		rows, ok := mgRows(o[key])
		if !ok {
			continue
		}
		names := []string{}
		for _, row := range rows {
			if name := mgClean(row["name"], 200); name != "" {
				names = append(names, name)
			}
		}
		if len(names) > 0 {
			return clean(strings.Join(names, " / "), 200)
		}
	}
	return mgClean(mgFirst(o, "singer", "artist"), 200)
}
func mgDuration(o mgObject) int {
	if n, ok := mgNumber(o["duration"]); ok && n <= 86400 {
		return n
	}
	parts := strings.Split(mgText(o["length"]), ":")
	if len(parts) != 3 {
		return 0
	}
	h, e1 := strconv.Atoi(parts[0])
	min, e2 := strconv.Atoi(parts[1])
	s, e3 := strconv.Atoi(parts[2])
	if e1 != nil || e2 != nil || e3 != nil || h < 0 || h > 23 || min < 0 || min > 59 || s < 0 || s > 59 {
		return 0
	}
	return h*3600 + min*60 + s
}
func mgSong(o mgObject) (model.Track, bool) {
	id := mgText(o["copyrightId"])
	name := mgClean(mgFirst(o, "songName", "name"), 200)
	if !mgValidID(id, true) || name == "" {
		return model.Track{}, false
	}
	album := mgClean(o["album"], 200)
	if rows, ok := mgRows(o["albums"]); ok && len(rows) > 0 {
		album = mgClean(rows[0]["name"], 200)
	}
	return model.Track{ID: "mg:" + id, ProviderID: "mg", Title: name, Artist: mgNames(o, "singers", "singerList", "artists"), Album: album, Duration: mgDuration(o), CoverURL: mgCover(o), Qualities: []string{}, CanDownload: false}, true
}
func mgSongs(rows []mgObject, limit int) ([]model.Track, error) {
	out := []model.Track{}
	seen := map[string]bool{}
	for _, row := range rows {
		track, ok := mgSong(row)
		if !ok {
			return nil, ErrUnavailable
		}
		if !seen[track.ID] {
			seen[track.ID] = true
			out = append(out, track)
		}
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func (m *MG) Search(ctx context.Context, query, kind string, page int) (SearchResult, error) {
	out := SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}, Artists: []SearchArtist{}, Albums: []SearchAlbum{}, Page: page, PageSize: PageSize}
	kinds := map[string]string{"track": "song", "album": "album", "artist": "singer", "playlist": "songlist"}
	remoteKind, ok := kinds[kind]
	if !ok || !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 || page < 1 || page > 50 {
		return out, ErrInput
	}
	query = strings.TrimSpace(query)
	if query == "" {
		return out, nil
	}
	switches := map[string]int{"song": 0, "album": 0, "singer": 0, "tagSong": 0, "mvSong": 0, "songlist": 0, "bestShow": 0}
	switches[remoteKind] = 1
	encoded, _ := json.Marshal(switches)
	root, err := m.get(ctx, mgApp, "/MIGUM2.0/v1.0/content/search_all.do", url.Values{"text": {query}, "pageNo": {strconv.Itoa(page)}, "pageSize": {strconv.Itoa(PageSize)}, "searchSwitch": {string(encoded)}, "isCopyright": {"1"}, "isCorrect": {"1"}, "sort": {"0"}})
	if err != nil {
		return out, err
	}
	keys := map[string]string{"track": "songResultData", "album": "albumResultData", "artist": "singerResultData", "playlist": "songListResultData"}
	data, ok := root[keys[kind]].(mgObject)
	if !ok {
		return out, ErrUnavailable
	}
	out.Total, ok = mgNumber(data["totalCount"])
	if !ok {
		return out, ErrUnavailable
	}
	// 上游在明确 totalCount=0 或翻过尾页时会省略 result/resultList。
	// 仅在总量已验证且当前偏移确实越界时接纳；第一页缺字段但总量非零仍为失败。
	resultField := "result"
	if kind == "track" {
		resultField = "resultList"
	}
	if _, exists := data[resultField]; !exists && (page-1)*PageSize >= out.Total {
		return out, nil
	}
	var rows []mgObject
	if kind == "track" {
		groups, valid := data["resultList"].([]any)
		if !valid {
			return out, ErrUnavailable
		}
		// 搜索结果是按歌曲分组的二维数组，不把同组的多个版本计为多条分页结果。
		for _, group := range groups {
			variants, valid := mgRows(group)
			if !valid || len(variants) == 0 {
				return out, ErrUnavailable
			}
			rows = append(rows, variants[0])
		}
	} else {
		rows, ok = mgRows(data["result"])
		if !ok {
			return out, ErrUnavailable
		}
	}
	if len(rows) > PageSize || out.Total == 0 && len(rows) > 0 || len(rows) == 0 && (page-1)*PageSize < out.Total {
		return out, ErrUnavailable
	}
	if kind == "track" {
		out.Tracks, err = m.cachedTracks(rows, PageSize)
		return out, err
	}
	for _, row := range rows {
		id, title := mgText(row["id"]), mgClean(row["name"], 200)
		if !mgValidID(id, false) || title == "" {
			return SearchResult{}, ErrUnavailable
		}
		count, _ := mgNumber(row["musicNum"])
		switch kind {
		case "artist":
			out.Artists = append(out.Artists, SearchArtist{ID: "mg:artist:" + id, Name: title, CoverURL: mgCover(row), TrackCount: count})
		case "album":
			out.Albums = append(out.Albums, SearchAlbum{ID: "mg:album:" + id, Title: title, Artist: mgNames(row, "singers"), CoverURL: mgCover(row), TrackCount: count})
		case "playlist":
			out.Playlists = append(out.Playlists, model.Collection{ID: "mg:playlist:" + id, ProviderID: "mg", Title: title, CoverURL: mgCover(row), TrackCount: count, PlayCount: publicPlayCount(row["playNum"])})
		}
	}
	return out, nil
}

// 只沿上游已验证的 contents 布局遍历，不将任意嵌套广告/歌曲标识当成歌单或榜单。
func mgContents(value any, key string, depth int) []mgObject {
	if depth > 10 {
		return nil
	}
	out := []mgObject{}
	switch v := value.(type) {
	case []any:
		for _, item := range v {
			out = append(out, mgContents(item, key, depth+1)...)
		}
	case mgObject:
		if mgText(v[key]) != "" {
			out = append(out, v)
		}
		out = append(out, mgContents(v["contents"], key, depth+1)...)
	}
	return out
}
func (m *MG) Charts(ctx context.Context) ([]model.Collection, error) {
	root, err := m.get(ctx, mgApp, "/pc/bmw/rank/rank-index/v1.0", nil)
	if err != nil {
		return nil, err
	}
	data, _ := root["data"].(mgObject)
	rows := mgContents(data["contents"], "rankId", 0)
	out := []model.Collection{}
	seen := map[string]bool{}
	for _, row := range rows {
		id, title := mgText(row["rankId"]), mgClean(row["rankName"], 200)
		if !mgValidID(id, false) || title == "" {
			return nil, ErrUnavailable
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, model.Collection{ID: "mg:chart:" + id, ProviderID: "mg", Title: title, CoverURL: mgCover(row), Category: "榜单"})
		if len(out) >= 100 {
			break
		}
	}
	if len(out) == 0 {
		return nil, ErrUnavailable
	}
	return out, nil
}
func (m *MG) Chart(ctx context.Context, raw string) (model.Collection, error) {
	id, err := mgLocalID(raw, "chart")
	if err != nil {
		return model.Collection{}, err
	}
	root, err := m.get(ctx, mgApp, "/MIGUM2.0/v1.0/content/querycontentbyId.do", url.Values{"columnId": {id}, "needAll": {"0"}})
	if err != nil {
		return model.Collection{}, err
	}
	data, ok := root["columnInfo"].(mgObject)
	if !ok || mgText(data["columnId"]) != id {
		return model.Collection{}, ErrUnavailable
	}
	title := mgClean(data["columnTitle"], 200)
	rows, ok := mgRows(data["contents"])
	if !ok || title == "" {
		return model.Collection{}, ErrUnavailable
	}
	songs := []mgObject{}
	for _, row := range rows {
		song, ok := row["objectInfo"].(mgObject)
		if !ok {
			return model.Collection{}, ErrUnavailable
		}
		songs = append(songs, song)
	}
	tracks, err := m.cachedTracks(songs, 100)
	if err != nil {
		return model.Collection{}, err
	}
	count, ok := mgNumber(data["contentsCount"])
	if !ok || count < len(tracks) || count > 0 && len(tracks) == 0 {
		return model.Collection{}, ErrUnavailable
	}
	return model.Collection{ID: raw, ProviderID: "mg", Title: title, Description: mgClean(data["columnDes"], 2000), CoverURL: mgCover(data), Category: "榜单", TrackCount: count, Tracks: tracks}, nil
}
func (m *MG) PlaylistCategories(ctx context.Context) ([]PlaylistCategory, error) {
	root, err := m.get(ctx, mgApp, "/pc/v1.0/template/musiclistplaza-taglist/release", nil)
	if err != nil {
		return nil, err
	}
	groups, ok := mgRows(root["data"])
	if !ok {
		return nil, ErrUnavailable
	}
	out := []PlaylistCategory{}
	seen := map[string]bool{}
	for _, group := range groups {
		header, _ := group["header"].(mgObject)
		rows, ok := mgRows(group["content"])
		if !ok {
			return nil, ErrUnavailable
		}
		for _, row := range rows {
			texts, ok := row["texts"].([]any)
			if !ok || len(texts) < 2 {
				return nil, ErrUnavailable
			}
			id, name := mgText(texts[1]), mgClean(texts[0], 100)
			if !mgValidID(id, false) || name == "" {
				return nil, ErrUnavailable
			}
			if seen[id] {
				continue
			}
			seen[id] = true
			out = append(out, PlaylistCategory{ID: "mg:tag:" + id, Name: name, Group: mgClean(header["title"], 100)})
			if len(out) >= maxPlaylistCategories {
				return out, nil
			}
		}
	}
	if len(out) == 0 {
		return nil, ErrUnavailable
	}
	return out, nil
}
func (m *MG) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	if page < 1 || page > 50 {
		return nil, ErrInput
	}
	all := category == "" || category == "all" || category == "全部"
	// 推荐入口返回整页推荐模块，pageNo 不构成可信分页。只开放首页，不制造重复的下一页。
	if all && page != 1 {
		return nil, ErrUnsupported
	}
	var rows []mgObject
	if all {
		root, err := m.get(ctx, mgApp, "/pc/bmw/page-data/playlist-square-recommend/v1.0", url.Values{"templateVersion": {"2"}, "pageNo": {"1"}})
		if err != nil {
			return nil, err
		}
		data, _ := root["data"].(mgObject)
		for _, row := range mgContents(data["contents"], "resId", 0) {
			if mgText(row["resType"]) == "2021" {
				rows = append(rows, mgObject{"id": row["resId"], "title": row["txt"], "img": row["img"]})
			}
		}
	} else {
		id, err := mgLocalID(category, "tag")
		if err != nil {
			return nil, ErrInput
		}
		root, err := m.get(ctx, mgApp, "/pc/v1.0/template/musiclistplaza-listbytag/release", url.Values{"tagId": {id}, "templateVersion": {"2"}, "pageNumber": {strconv.Itoa(page)}, "pageSize": {"30"}})
		if err != nil {
			return nil, err
		}
		data, _ := root["data"].(mgObject)
		blocks, ok := mgRows(data["contentItemList"])
		if !ok {
			return nil, ErrUnavailable
		}
		found := false
		for _, block := range blocks {
			if _, exists := block["itemList"]; !exists {
				continue
			}
			items, ok := mgRows(block["itemList"])
			if !ok {
				return nil, ErrUnavailable
			}
			found = true
			for _, item := range items {
				event, _ := item["logEvent"].(mgObject)
				if mgText(event["contentType"]) != "2021" {
					return nil, ErrUnavailable
				}
				rows = append(rows, mgObject{"id": event["contentId"], "title": item["title"], "imageUrl": item["imageUrl"], "playNum": item["playNum"]})
			}
		}
		if !found {
			return nil, ErrUnavailable
		}
	}
	out := []model.Collection{}
	seen := map[string]bool{}
	for _, row := range rows {
		id, title := mgText(row["id"]), mgClean(row["title"], 200)
		if !mgValidID(id, false) || title == "" {
			return nil, ErrUnavailable
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, model.Collection{ID: "mg:playlist:" + id, ProviderID: "mg", Title: title, CoverURL: mgCover(row), Category: category, PlayCount: publicPlayCount(row["playNum"])})
		if all && len(out) >= 24 {
			break
		}
	}
	if all && len(out) == 0 {
		return nil, ErrUnavailable
	}
	return out, nil
}
func (m *MG) Playlist(ctx context.Context, raw string) (model.Collection, error) {
	id, err := mgLocalID(raw, "playlist")
	if err != nil {
		return model.Collection{}, err
	}
	root, err := m.get(ctx, mgContent, "/MIGUM3.0/resource/playlist/v2.0", url.Values{"playlistId": {id}})
	if err != nil {
		return model.Collection{}, err
	}
	data, ok := root["data"].(mgObject)
	if !ok || mgText(data["musicListId"]) != id {
		return model.Collection{}, ErrUnavailable
	}
	title := mgClean(data["title"], 200)
	count, ok := mgNumber(data["musicNum"])
	if !ok || title == "" {
		return model.Collection{}, ErrUnavailable
	}
	out := model.Collection{ID: raw, ProviderID: "mg", Title: title, Description: mgClean(data["summary"], 2000), CoverURL: mgCover(data), TrackCount: count, PlayCount: publicPlayCount(data["playNum"]), Tracks: []model.Track{}}
	if count == 0 {
		return out, nil
	}
	// 此端点实测每页至多 50 首，不能把 pageSize=100 当作确实返回 100。与共享目录一致只暴露前 100 首。
	for page := 1; page <= 2; page++ {
		root, err := m.get(ctx, mgApp, "/MIGUM3.0/resource/playlist/song/v2.0", url.Values{"playlistId": {id}, "pageNo": {strconv.Itoa(page)}, "pageSize": {"50"}})
		if err != nil {
			return model.Collection{}, err
		}
		data, ok := root["data"].(mgObject)
		if !ok {
			return model.Collection{}, ErrUnavailable
		}
		total, ok := mgNumber(data["totalCount"])
		if !ok || total != count {
			return model.Collection{}, ErrUnavailable
		}
		rows, ok := mgRows(data["songList"])
		if !ok || len(rows) == 0 || len(rows) > 50 {
			return model.Collection{}, ErrUnavailable
		}
		tracks, err := m.cachedTracks(rows, 50)
		if err != nil {
			return model.Collection{}, err
		}
		for _, track := range tracks {
			for _, old := range out.Tracks {
				if old.ID == track.ID {
					return model.Collection{}, ErrUnavailable
				}
			}
		}
		out.Tracks = append(out.Tracks, tracks...)
		if len(out.Tracks) > count {
			return model.Collection{}, ErrUnavailable
		}
		if len(out.Tracks) == count || len(out.Tracks) >= 100 {
			return out, nil
		}
		if len(rows) < 50 {
			return model.Collection{}, ErrUnavailable
		}
	}
	return model.Collection{}, ErrUnavailable
}

func (m *MG) songData(ctx context.Context, raw string) (mgObject, model.Track, error) {
	id, err := mgLocalID(raw, "")
	if err != nil {
		return nil, model.Track{}, err
	}
	// resourceId 接受内部 songId；UI 持久化的是 copyrightId，必须使用对应参数，不能依赖搜索缓存。
	root, err := m.get(ctx, mgContent, "/MIGUM2.0/v1.0/content/resourceinfo.do", url.Values{"resourceType": {"2"}, "copyrightId": {id}})
	if err != nil {
		return nil, model.Track{}, err
	}
	rows, ok := mgRows(root["resource"])
	if !ok {
		return nil, model.Track{}, ErrUnavailable
	}
	if len(rows) == 0 {
		return nil, model.Track{}, ErrNotFound
	}
	for _, row := range rows {
		if mgText(row["copyrightId"]) != id {
			continue
		}
		track, ok := mgSong(row)
		if !ok {
			return nil, model.Track{}, ErrUnavailable
		}
		return row, track, nil
	}
	return nil, model.Track{}, ErrUnavailable
}
func (m *MG) Track(ctx context.Context, raw string) (model.Track, error) {
	row, track, err := m.songData(ctx, raw)
	if err == nil {
		m.metadata.put(model.CatalogMetadata{Track: track, MusicInfo: mgMusicInfo(row, track)})
	}
	return track, err
}
func (m *MG) MusicInfo(ctx context.Context, raw string) (map[string]any, error) {
	row, track, err := m.songData(ctx, raw)
	if err != nil {
		return nil, err
	}
	info := mgMusicInfo(row, track)
	if info == nil {
		return nil, ErrUnavailable
	}
	return info, nil
}
func mgMusicInfo(row mgObject, track model.Track) map[string]any {
	songID, copyrightID := mgText(row["songId"]), mgText(row["copyrightId"])
	if !mgValidID(songID, false) {
		return nil
	}
	albumID := mgText(row["albumId"])
	if !mgValidID(albumID, false) {
		albumID = ""
	}
	return map[string]any{
		"id": songID, "songmid": songID, "songId": songID, "copyrightId": copyrightID,
		"source": "mg", "name": track.Title, "songname": track.Title, "songName": track.Title,
		"singer": track.Artist, "artist": track.Artist, "albumName": track.Album, "albumId": albumID,
		"duration": track.Duration, "interval": fmt.Sprintf("%02d:%02d", track.Duration/60, track.Duration%60), "img": track.CoverURL,
		"types": []any{}, "_types": map[string]any{}, "typeUrl": map[string]any{},
	}
}
func (m *MG) Lyrics(ctx context.Context, raw string) (Lyrics, error) {
	out := Lyrics{Lines: []LyricLine{}, Source: "米咕"}
	row, _, err := m.songData(ctx, raw)
	if err != nil {
		return out, err
	}
	lyric := mgText(row["lrcUrl"])
	if lyric == "" {
		return out, ErrUnsupported
	}
	u, err := url.Parse(lyric)
	if err != nil || u.User != nil || u.Host != "d.musicapp.migu.cn" || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "http" && u.Scheme != "https") || (u.RawPath != "" || !mgLyricPath.MatchString(u.Path)) || len(lyric) > 2048 {
		return out, ErrUnavailable
	}
	u.Scheme = "https"
	data, err := catalogRequest(ctx, m.http, http.MethodGet, u.String(), http.Header{"Accept": {"text/plain"}}, nil)
	if err != nil {
		return out, err
	}
	if len(data) > 256<<10 || !utf8.Valid(data) {
		return out, ErrUnavailable
	}
	out.Lines = ParseLRC(strings.TrimPrefix(string(data), "\ufeff"))
	if len(out.Lines) == 0 {
		return out, ErrUnavailable
	}
	return out, nil
}
func (m *MG) NewTracks(context.Context, string) ([]model.Track, error) {
	// 尚无经过验证且独立于热榜的按地区新歌契约，不以榜单伪装新歌。
	return nil, ErrUnsupported
}

// 公开推荐目录不是可翻页列表；分类歌单按上游固定30项分页。
func (m *MG) PlaylistHasMore(category string, page int, items []model.Collection) bool {
	return category != "" && category != "all" && category != "全部" && len(items) >= 30 && page < 50
}

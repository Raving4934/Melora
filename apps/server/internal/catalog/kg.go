package catalog

// KG公开目录 Adapter。协议字段经上游文档/源码与实网响应独立核对；不移植 GPL 模块，
// 不使用签名密钥、登录 Cookie、验证码绕过或音频地址。目录信息不代表播放/下载授权。
import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

const (
	kgPageSize       = 24
	kgMobilePageSize = 30 // m.kugou.com 实网忽略 pagesize，固定每页 30 条。
	kgDetailLimit    = 100
)

type KG struct {
	http     HTTPDoer
	metadata metadataMemory
}

var _ Adapter = (*KG)(nil)

func NewKG(client HTTPDoer) *KG { return &KG{http: client} }

type kgObject map[string]json.RawMessage

type kgRemoteError struct{}

func (kgRemoteError) Error() string { return "酷购目录暂不可用，请稍后重试" }
func (kgRemoteError) Unwrap() error { return ErrUnavailable }

func kgText(s string, limit int) string {
	s = html.UnescapeString(s)
	s = strings.NewReplacer("<em>", "", "</em>", "", "<b>", "", "</b>", "").Replace(s)
	return clean(s, limit)
}
func kgString(o kgObject, keys ...string) string {
	for _, key := range keys {
		var s string
		if json.Unmarshal(o[key], &s) == nil && strings.TrimSpace(s) != "" {
			return s
		}
	}
	return ""
}
func kgInteger(o kgObject, key string) (int64, bool) {
	raw, exists := o[key]
	if !exists {
		return 0, false
	}
	value := strings.TrimSpace(string(raw))
	if strings.HasPrefix(value, "\"") {
		if json.Unmarshal(raw, &value) != nil {
			return 0, false
		}
	}
	n, err := strconv.ParseInt(value, 10, 64)
	return n, err == nil
}
func kgNumber(o kgObject, keys ...string) int64 {
	for _, key := range keys {
		if n, ok := kgInteger(o, key); ok && n > 0 {
			return n
		}
	}
	return 0
}
func kgCount(o kgObject, key string) (int, bool) {
	n, ok := kgInteger(o, key)
	return int(n), ok && n >= 0 && n <= 1_000_000_000
}
func kgObjectAt(o kgObject, key string) kgObject {
	var nested kgObject
	if json.Unmarshal(o[key], &nested) != nil {
		return nil
	}
	return nested
}
func kgRows(raw json.RawMessage) ([]kgObject, bool) {
	var rows []kgObject
	if json.Unmarshal(raw, &rows) != nil || rows == nil {
		return nil, false
	}
	return rows, true
}
func kgCover(raw string) string {
	raw = strings.NewReplacer("{size}", "400", "%7Bsize%7D", "400", "%7bsize%7d", "400").Replace(raw)
	return catalogImage(raw, "imge.kugou.com", "imgessl.kugou.com", "singerimg.kugou.com", "singerimgss.kugou.com", "kgimg.com")
}
func kgHash(raw string) string {
	if len(raw) != 32 || strings.Trim(raw, "0") == "" {
		return ""
	}
	for _, r := range raw {
		if !strings.ContainsRune("0123456789abcdefABCDEF", r) {
			return ""
		}
	}
	return strings.ToUpper(raw)
}
func kgTrackHash(raw string) (string, error) {
	if !strings.HasPrefix(raw, "kg:") {
		return "", ErrInput
	}
	h := kgHash(strings.TrimPrefix(raw, "kg:"))
	if h == "" {
		return "", ErrInput
	}
	return h, nil
}

// 部分搜索结果只有公开的全局歌单 gid，没有旧 specialid；保留真实 ID，但不伪造其详情能力。
func kgGlobalPlaylistID(raw string) bool {
	parts := strings.Split(raw, "_")
	if len(raw) > 96 || len(parts) != 5 || parts[0] != "collection" {
		return false
	}
	for _, part := range parts[1:] {
		if part == "" || len(part) > 20 {
			return false
		}
		for _, c := range part {
			if c < '0' || c > '9' {
				return false
			}
		}
	}
	return true
}

func kgResourceNumber(raw, kind string) (string, error) {
	prefix := "kg:" + kind + "_"
	if !strings.HasPrefix(raw, prefix) {
		return "", ErrInput
	}
	n := strings.TrimPrefix(raw, prefix)
	if n == "" || len(n) > 18 || n[0] == '0' {
		return "", ErrInput
	}
	for _, c := range n {
		if c < '0' || c > '9' {
			return "", ErrInput
		}
	}
	return n, nil
}

// 每个公开方法共用一个 9 秒预算；多次调用 catalogRequest 不能把单次操作扩张成 N×9 秒。
func (k *KG) kgGet(ctx context.Context, host, path string, query url.Values) (kgObject, error) {
	data, err := catalogRequest(ctx, k.http, http.MethodGet, "https://"+host+path+"?"+query.Encode(), http.Header{"Accept": {"application/json"}}, nil)
	if err != nil {
		return nil, catalogIssueError(err)
	}
	var out kgObject
	if json.Unmarshal(data, &out) != nil || out == nil {
		return nil, kgRemoteError{}
	}
	if host == "lyrics.kugou.com" {
		status, ok := kgInteger(out, "status")
		code, valid := kgInteger(out, "errcode")
		if !ok || status != 200 || (out["errcode"] != nil && (!valid || (code != 0 && code != 200))) {
			return nil, kgRemoteError{}
		}
	} else {
		status, exists := kgInteger(out, "status")
		mobileDocument := host == "m.kugou.com" && path != "/app/i/getSongInfo.php"
		if (!mobileDocument && (!exists || status != 1)) || (out["status"] != nil && (!exists || status != 1)) {
			return nil, kgRemoteError{}
		}
		for _, key := range []string{"error_code", "errcode", "err_code"} {
			if out[key] != nil {
				if code, ok := kgInteger(out, key); !ok || code != 0 {
					return nil, kgRemoteError{}
				}
			}
		}
	}
	return out, nil
}

func kgSong(o kgObject) (model.Track, map[string]any, bool) {
	hash := kgHash(kgString(o, "FileHash", "hash"))
	name := kgText(kgString(o, "SongName", "songName", "songname"), 200)
	artist := kgText(kgString(o, "SingerName", "singerName", "singername", "author_name"), 300)
	filename := kgText(kgString(o, "FileName", "fileName", "filename"), 600)
	if name == "" {
		parts := strings.SplitN(filename, " - ", 2)
		if len(parts) == 2 {
			if artist == "" {
				artist = parts[0]
			}
			name = kgText(parts[1], 200)
		} else {
			name = kgText(filename, 200)
		}
	}
	if artist == "" {
		for _, key := range []string{"Singers", "authors"} {
			if rows, ok := kgRows(o[key]); ok {
				names := []string{}
				for _, row := range rows {
					if s := kgText(kgString(row, "name", "author_name"), 100); s != "" {
						names = append(names, s)
					}
				}
				artist = kgText(strings.Join(names, " / "), 300)
				if artist != "" {
					break
				}
			}
		}
	}
	if hash == "" || name == "" {
		return model.Track{}, nil, false
	}
	extra := kgObjectAt(o, "extra")
	duration := kgNumber(o, "Duration", "duration", "timeLength")
	if duration == 0 {
		duration = kgNumber(extra, "128timelength") / 1000
	}
	if duration > 86400 {
		duration = 0
	}
	cover := kgString(o, "Image", "AlbumImage", "album_sizable_cover", "album_img", "imgurl", "imgUrl")
	if cover == "" {
		cover = kgString(kgObjectAt(o, "trans_param"), "union_cover")
	}
	album := kgText(kgString(o, "AlbumName", "albumName", "albumname", "album_name"), 200)
	track := model.Track{ID: "kg:" + hash, ProviderID: "kg", Title: name, Artist: artist, Album: album,
		Duration: int(duration), CoverURL: kgCover(cover), Qualities: []string{}, CanDownload: false}
	// Audioid/audio_id 才是 LX 的 songmid；不能将 FileHash 或 MixSongID/album_audio_id 冒充它。
	audioID := kgNumber(o, "Audioid", "audio_id")
	albumID := kgNumber(o, "AlbumID", "album_id", "albumid")
	qualities := []struct {
		name               string
		hashKeys, sizeKeys []string
	}{
		{"128k", []string{"FileHash", "hash", "128hash"}, []string{"FileSize", "filesize", "fileSize", "128filesize"}},
		{"320k", []string{"HQFileHash", "320hash"}, []string{"HQFileSize", "320filesize"}},
		{"flac", []string{"SQFileHash", "sqhash"}, []string{"SQFileSize", "sqfilesize"}},
		{"flac24bit", []string{"ResFileHash", "hash_high", "highhash"}, []string{"ResFileSize", "filesize_high", "highfilesize"}},
	}
	types := []any{}
	typeMap := map[string]any{}
	for _, q := range qualities {
		h := kgHash(kgString(o, q.hashKeys...))
		size := kgNumber(o, q.sizeKeys...)
		if h == "" {
			h = kgHash(kgString(extra, q.hashKeys...))
		}
		if size == 0 {
			size = kgNumber(extra, q.sizeKeys...)
		}
		if h == "" || size <= 0 {
			continue
		}
		displaySize := fmt.Sprintf("%.2fMB", float64(size)/(1024*1024))
		types = append(types, map[string]any{"type": q.name, "hash": h, "size": displaySize})
		typeMap[q.name] = map[string]any{"hash": h, "size": displaySize}
	}
	music := map[string]any{"source": "kg", "hash": hash, "songmid": audioID, "id": audioID,
		"name": name, "songname": name, "singer": artist, "albumName": album, "albumId": albumID,
		"interval": fmt.Sprintf("%02d:%02d", duration/60, duration%60), "_interval": int(duration), "duration": int(duration),
		"img": track.CoverURL, "types": types, "_types": typeMap, "typeUrl": map[string]any{}}
	return track, music, true
}
func kgTracks(rows []kgObject, limit int) ([]model.Track, error) {
	out := []model.Track{}
	seen := map[string]bool{}
	for _, row := range rows {
		track, _, ok := kgSong(row)
		if !ok || seen[track.ID] {
			continue
		}
		seen[track.ID] = true
		out = append(out, track)
		if len(out) == limit {
			break
		}
	}
	if len(rows) != 0 && len(out) == 0 {
		return nil, kgRemoteError{}
	}
	return out, nil
}
func kgCollection(row kgObject, kind string) (model.Collection, bool) {
	idKey, nameKey := "specialid", "specialname"
	if kind == "chart" {
		idKey, nameKey = "rankid", "rankname"
	}
	n := kgNumber(row, idKey)
	name := kgText(kgString(row, nameKey), 200)
	resourceID := strconv.FormatInt(n, 10)
	description := kgText(kgString(row, "intro"), 1000)
	if n <= 0 {
		gid := kgString(row, "gid", "global_specialid")
		if kind != "playlist" || !kgGlobalPlaylistID(gid) {
			return model.Collection{}, false
		}
		resourceID = gid
		description = "公开目录可检索；此类全局歌单的详情暂不支持。" + description
	}
	if name == "" {
		return model.Collection{}, false
	}
	return model.Collection{ID: "kg:" + kind + "_" + resourceID, ProviderID: "kg", Title: name,
		Description: description, CoverURL: kgCover(kgString(row, "imgurl")), PlayCount: rawPlayCount(row, "playcount", "play_count"),
		TrackCount: int(min(kgNumber(row, "songcount"), int64(1_000_000_000)))}, true
}
func kgCollections(rows []kgObject, kind string, limit int) ([]model.Collection, error) {
	out := []model.Collection{}
	seen := map[string]bool{}
	for _, row := range rows {
		if item, ok := kgCollection(row, kind); ok && !seen[item.ID] {
			seen[item.ID] = true
			out = append(out, item)
			if len(out) == limit {
				break
			}
		}
	}
	if len(rows) > 0 && len(out) == 0 {
		return nil, kgRemoteError{}
	}
	return out, nil
}

func (k *KG) Search(ctx context.Context, query, kind string, page int) (SearchResult, error) {
	out := SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}, Artists: []SearchArtist{}, Albums: []SearchAlbum{}, Page: page, PageSize: kgPageSize}
	query = strings.TrimSpace(query)
	if query == "" || !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 || strings.IndexFunc(query, unicode.IsControl) >= 0 || page < 1 || page > 50 {
		return out, ErrInput
	}
	paths := map[string]string{"track": "song", "playlist": "special", "album": "album", "artist": "singer"}
	typ, ok := paths[kind]
	if !ok {
		return out, ErrInput
	}
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	params := url.Values{"keyword": {query}, "page": {strconv.Itoa(page)}, "pagesize": {strconv.Itoa(kgPageSize)}}
	host, path := "mobiles.kugou.com", "/api/v3/search/"+typ
	if kind == "track" {
		host, path = "songsearch.kugou.com", "/song_search_v2"
		for key, value := range map[string]string{"userid": "0", "clientver": "", "platform": "WebFilter", "filter": "2", "iscorrection": "1", "privilege_filter": "0", "area_code": "1"} {
			params.Set(key, value)
		}
	}
	// 此公开 singer 接口经实网验证忽略 page/pagesize，返回有限候选数组；本地切出 24 条逻辑页。
	if kind == "artist" {
		params.Set("page", "1")
	}
	root, err := k.kgGet(ctx, host, path, params)
	if err != nil {
		return out, err
	}
	if kind == "artist" {
		rows, ok := kgRows(root["data"])
		if !ok {
			return out, kgRemoteError{}
		}
		all := []SearchArtist{}
		seen := map[int64]bool{}
		for _, row := range rows {
			n := kgNumber(row, "singerid")
			name := kgText(kgString(row, "singername"), 200)
			if n > 0 && name != "" && !seen[n] {
				seen[n] = true
				all = append(all, SearchArtist{ID: "kg:artist_" + strconv.FormatInt(n, 10), Name: name, CoverURL: kgCover(kgString(row, "imgurl")), TrackCount: int(min(kgNumber(row, "songcount"), int64(1_000_000_000)))})
			}
		}
		if len(rows) > 0 && len(all) == 0 {
			return out, kgRemoteError{}
		}
		out.Total = len(all)
		start := min((page-1)*kgPageSize, len(all))
		out.Artists = append(out.Artists, all[start:min(start+kgPageSize, len(all))]...)
		return out, nil
	}
	data := kgObjectAt(root, "data")
	key := "info"
	if kind == "track" {
		key = "lists"
	}
	rows, ok := kgRows(data[key])
	total, valid := kgCount(data, "total")
	if !ok || !valid || total < len(rows) || (len(rows) == 0 && total > (page-1)*kgPageSize) {
		return out, kgRemoteError{}
	}
	out.Total = total
	switch kind {
	case "track":
		out.Tracks, err = k.cachedTracks(rows, kgPageSize)
	case "playlist":
		out.Playlists, err = kgCollections(rows, "playlist", kgPageSize)
	case "album":
		for _, row := range rows {
			n := kgNumber(row, "albumid")
			name := kgText(kgString(row, "albumname"), 200)
			if n > 0 && name != "" {
				out.Albums = append(out.Albums, SearchAlbum{ID: "kg:album_" + strconv.FormatInt(n, 10), Title: name, Artist: kgText(kgString(row, "singername"), 300), CoverURL: kgCover(kgString(row, "imgurl")), TrackCount: int(min(kgNumber(row, "songcount"), int64(1_000_000_000)))})
			}
			if len(out.Albums) == kgPageSize {
				break
			}
		}
		if len(rows) > 0 && len(out.Albums) == 0 {
			err = kgRemoteError{}
		}
	}
	return out, err
}

func (k *KG) Charts(ctx context.Context) ([]model.Collection, error) {
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	root, err := k.kgGet(ctx, "m.kugou.com", "/rank/list", url.Values{"json": {"true"}})
	if err != nil {
		return nil, err
	}
	rank := kgObjectAt(root, "rank")
	rows, ok := kgRows(rank["list"])
	if !ok || len(rows) == 0 {
		return nil, kgRemoteError{}
	}
	// 子榜同样使用榜单资源 ID；不把曲目推荐伪装成额外榜单。
	all := append([]kgObject{}, rows...)
	for _, row := range rows {
		if children, ok := kgRows(row["children"]); ok {
			all = append(all, children...)
		}
	}
	return kgCollections(all, "chart", 256)
}
func (k *KG) Chart(ctx context.Context, raw string) (model.Collection, error) {
	n, err := kgResourceNumber(raw, "chart")
	if err != nil {
		return model.Collection{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	charts, err := k.Charts(ctx)
	if err != nil {
		return model.Collection{}, err
	}
	var out model.Collection
	for _, chart := range charts {
		if chart.ID == raw {
			out = chart
			break
		}
	}
	if out.ID == "" {
		return out, ErrNotFound
	}
	return k.kgCollectionTracks(ctx, out, "rank", "rankid", n)
}
func (k *KG) Playlist(ctx context.Context, raw string) (model.Collection, error) {
	if strings.HasPrefix(raw, "kg:playlist_") && kgGlobalPlaylistID(strings.TrimPrefix(raw, "kg:playlist_")) {
		return model.Collection{}, ErrUnsupported
	}
	n, err := kgResourceNumber(raw, "playlist")
	if err != nil {
		return model.Collection{}, err
	}
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	root, err := k.kgGet(ctx, "mobiles.kugou.com", "/api/v3/special/info", url.Values{"specialid": {n}})
	if err != nil {
		return model.Collection{}, err
	}
	out, ok := kgCollection(kgObjectAt(root, "data"), "playlist")
	if !ok {
		return model.Collection{}, kgRemoteError{}
	}
	if out.ID != raw {
		return model.Collection{}, ErrNotFound
	}
	return k.kgCollectionTracks(ctx, out, "special", "specialid", n)
}
func (k *KG) kgCollectionTracks(ctx context.Context, out model.Collection, kind, key, n string) (model.Collection, error) {
	root, err := k.kgGet(ctx, "mobiles.kugou.com", "/api/v3/"+kind+"/song", url.Values{key: {n}, "page": {"1"}, "pagesize": {strconv.Itoa(kgDetailLimit)}})
	if err != nil {
		return model.Collection{}, err
	}
	data := kgObjectAt(root, "data")
	rows, ok := kgRows(data["info"])
	total, valid := kgCount(data, "total")
	if !ok || !valid || total < len(rows) || (len(rows) == 0 && total > 0) {
		return model.Collection{}, kgRemoteError{}
	}
	out.Tracks, err = k.cachedTracks(rows, kgDetailLimit)
	if err != nil {
		return model.Collection{}, err
	}
	out.TrackCount = total
	if total > len(out.Tracks) {
		out.Description = fmt.Sprintf("当前加载 %d 首，共 %d 首。", len(out.Tracks), total) + out.Description
	}
	return out, nil
}

func (k *KG) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	if page < 1 || page > 50 {
		return nil, ErrInput
	}
	if category != "" && category != "all" {
		return nil, ErrUnsupported
	}
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	start := (page - 1) * kgPageSize
	remotePage := start/kgMobilePageSize + 1
	offset := start % kgMobilePageSize
	out := []model.Collection{}
	seen := map[string]bool{}
	for request := 0; request < 2 && len(out) < kgPageSize; request++ {
		root, err := k.kgGet(ctx, "m.kugou.com", "/plist/index", url.Values{"json": {"true"}, "page": {strconv.Itoa(remotePage)}, "pagesize": {strconv.Itoa(kgMobilePageSize)}})
		if err != nil {
			return nil, err
		}
		plist := kgObjectAt(root, "plist")
		data := kgObjectAt(plist, "list")
		rows, ok := kgRows(data["info"])
		total, valid := kgCount(data, "total")
		size, sizeOK := kgCount(plist, "pagesize")
		if !sizeOK {
			size, sizeOK = kgCount(root, "pagesize")
		}
		if !ok || !valid || !sizeOK || size != kgMobilePageSize || len(rows) != min(size, max(0, total-(remotePage-1)*size)) {
			return nil, kgRemoteError{}
		}
		if start >= total {
			return out, nil
		}
		end := min(len(rows), offset+kgPageSize-len(out))
		for _, row := range rows[min(offset, len(rows)):end] {
			item, valid := kgCollection(row, "playlist")
			if !valid {
				return nil, kgRemoteError{}
			}
			if seen[item.ID] {
				return nil, kgRemoteError{}
			}
			seen[item.ID] = true
			out = append(out, item)
			if len(out) == kgPageSize {
				break
			}
		}
		if remotePage*size >= total {
			break
		}
		remotePage++
		offset = 0
	}
	if len(out) == 0 {
		return nil, kgRemoteError{}
	}
	return out, nil
}
func (k *KG) PlaylistCategories(context.Context) ([]PlaylistCategory, error) {
	// 公开标签端点只返回无 children 的门户分组；对应可分页过滤的 special/list 实网明确拒绝访问。
	// 不把无效分类选项展示给用户，也不绕过拒绝/借热门歌单标签伪造分类目录。
	return nil, ErrUnsupported
}
func (k *KG) kgTrackInfo(ctx context.Context, raw string) (model.Track, map[string]any, error) {
	hash, err := kgTrackHash(raw)
	if err != nil {
		return model.Track{}, nil, err
	}
	root, err := k.kgGet(ctx, "m.kugou.com", "/app/i/getSongInfo.php", url.Values{"cmd": {"playInfo"}, "hash": {hash}})
	if err != nil {
		return model.Track{}, nil, err
	}
	track, music, ok := kgSong(root)
	if !ok || track.ID != "kg:"+hash {
		return model.Track{}, nil, ErrNotFound
	}
	return track, music, nil
}
func (k *KG) Track(ctx context.Context, raw string) (model.Track, error) {
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	track, music, err := k.kgTrackInfo(ctx, raw)
	if err == nil {
		if n, ok := music["songmid"].(int64); !ok || n <= 0 {
			music = nil
		}
		k.metadata.put(model.CatalogMetadata{Track: track, MusicInfo: music})
	}
	return track, err
}
func (k *KG) MusicInfo(ctx context.Context, raw string) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	_, music, err := k.kgTrackInfo(ctx, raw)
	if err != nil {
		return nil, err
	}
	if music["songmid"].(int64) <= 0 {
		return nil, kgRemoteError{}
	}
	return music, nil
}
func kgLyricName(s string) string {
	return strings.ToLower(strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) {
			return -1
		}
		return r
	}, kgText(s, 300)))
}
func (k *KG) Lyrics(ctx context.Context, raw string) (Lyrics, error) {
	out := Lyrics{Lines: []LyricLine{}, Source: "酷购"}
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	track, _, err := k.kgTrackInfo(ctx, raw)
	if err != nil {
		return out, err
	}
	hash, _ := kgTrackHash(raw)
	root, err := k.kgGet(ctx, "lyrics.kugou.com", "/search", url.Values{"ver": {"1"}, "man": {"yes"}, "client": {"pc"}, "hash": {hash}, "keyword": {track.Title}, "timelength": {strconv.Itoa(track.Duration * 1000)}, "lrctxt": {"1"}})
	if err != nil {
		return out, err
	}
	rows, ok := kgRows(root["candidates"])
	if !ok {
		return out, kgRemoteError{}
	}
	var candidate kgObject
	for _, row := range rows {
		// 哈希检索仍可能回退到无关歌词；不盲取 candidates[0]。
		if kgLyricName(kgString(row, "song")) != kgLyricName(track.Title) {
			continue
		}
		if singer := kgString(row, "singer"); singer != "" && track.Artist != "" && kgLyricName(singer) != kgLyricName(track.Artist) {
			continue
		}
		if duration := kgNumber(row, "duration"); duration > 0 && track.Duration > 0 && (duration-int64(track.Duration*1000) > 5000 || int64(track.Duration*1000)-duration > 5000) {
			continue
		}
		candidate = row
		break
	}
	if candidate == nil {
		return out, ErrNotFound
	}
	id := kgString(candidate, "id")
	if id == "" {
		if n := kgNumber(candidate, "id"); n > 0 {
			id = strconv.FormatInt(n, 10)
		}
	}
	key := kgString(candidate, "accesskey")
	if _, err := kgResourceNumber("kg:lyric_"+id, "lyric"); err != nil || key == "" || len(key) > 256 || strings.IndexFunc(key, unicode.IsControl) >= 0 {
		return out, kgRemoteError{}
	}
	root, err = k.kgGet(ctx, "lyrics.kugou.com", "/download", url.Values{"ver": {"1"}, "client": {"pc"}, "id": {id}, "accesskey": {key}, "fmt": {"lrc"}, "charset": {"utf8"}})
	if err != nil {
		return out, err
	}
	if kgString(root, "fmt") != "lrc" {
		return out, ErrUnsupported
	}
	data, err := base64.StdEncoding.DecodeString(kgString(root, "content"))
	if err != nil || len(data) > 256<<10 || !utf8.Valid(data) {
		return out, kgRemoteError{}
	}
	out.Lines = ParseLRC(string(data))
	if len(out.Lines) == 0 {
		return out, ErrNotFound
	}
	return out, nil
}

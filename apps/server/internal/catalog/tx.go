package catalog

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

// TX 只读取公开目录；不获取播放 URL、登录态、签名或下载授权。
// 按公开接口响应独立实现；LX 字段语义参考上游 tx/musicInfo.js。
// 注入的 HTTPDoer 必须由调用方配置公网/域名保护；构造器不发起网络请求。
type TX struct {
	client    HTTPDoer
	metadata  metadataMemory
	playlists txPlaylistMemory
}

var _ Adapter = (*TX)(nil)

func NewTX(client HTTPDoer) *TX { return &TX{client: client} }

const txPageSize = 24
const txMaxDetailTracks = 1000
const txRPCURL = "https://u.y.qq.com/cgi-bin/musicu.fcg"

var txTags = regexp.MustCompile(`<[^>]*>`)

func txText(value string, limit int) string {
	value = strings.NewReplacer("<br>", "\n", "<br/>", "\n", "<br />", "\n").Replace(value)
	return clean(html.UnescapeString(txTags.ReplaceAllString(value, "")), limit)
}
func txMID(value string) bool {
	if len(value) != 14 {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
			return false
		}
	}
	return true
}
func txSongMID(id string) (string, error) {
	if !strings.HasPrefix(id, "tx:") || !txMID(strings.TrimPrefix(id, "tx:")) {
		return "", ErrNotFound
	}
	return strings.TrimPrefix(id, "tx:"), nil
}
func txResourceNumber(id, kind string) (int64, error) {
	prefix := "tx:" + kind + "_"
	if !strings.HasPrefix(id, prefix) {
		return 0, ErrNotFound
	}
	number := strings.TrimPrefix(id, prefix)
	n, ok := txPositiveNumber(number)
	if !ok {
		return 0, ErrNotFound
	}
	return n, nil
}
func txPositiveNumber(value string) (int64, bool) {
	if value == "" || len(value) > 19 || value[0] == '0' {
		return 0, false
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseInt(value, 10, 64)
	return n, err == nil && n > 0
}
func txNumber(number json.Number) int64 {
	n, err := number.Int64()
	if err != nil || n < 0 {
		return 0
	}
	return n
}
func txCount(n int) int { return min(max(n, 0), 10000000) }
func txImage(raw string) string {
	return catalogImage(raw, "y.gtimg.cn", "qpic.y.qq.com", "p.qpic.cn", "music-file.y.qq.com")
}
func txCover(mid string, artist bool) string {
	if !txMID(mid) {
		return ""
	}
	kind := "T002"
	if artist {
		kind = "T001"
	}
	return txImage("https://y.gtimg.cn/music/photo_new/" + kind + "R500x500M000" + mid + ".jpg")
}
func txHeaders() http.Header {
	return http.Header{"Referer": {"https://y.qq.com/"}, "Accept": {"application/json"}}
}

// 每层 code 必须显式成功；特别不能把缺失 code 或 req.code=2001 当作零值成功。
func (q *TX) txRPC(ctx context.Context, module, method string, param any, out any) error {
	payload := map[string]any{"comm": map[string]any{"ct": 19, "cv": 1859, "uin": 0, "format": "json"}, "req": map[string]any{"module": module, "method": method, "param": param}}
	body, err := json.Marshal(payload)
	if err != nil {
		return ErrInput
	}
	raw, err := q.txRequest(ctx, txRPCURL+"?"+url.Values{"data": {string(body)}}.Encode())
	if err != nil {
		return err
	}
	var envelope struct {
		Code    *int `json:"code"`
		Subcode *int `json:"subcode"`
		Retcode *int `json:"retcode"`
		Req     *struct {
			Code *int            `json:"code"`
			Data json.RawMessage `json:"data"`
		} `json:"req"`
	}
	if json.Unmarshal(raw, &envelope) != nil || envelope.Code == nil {
		return txUnavailable
	}
	if *envelope.Code != 0 {
		return txRemoteError{code: "upstream_rejected"}
	}
	if envelope.Req == nil || envelope.Req.Code == nil {
		return txUnavailable
	}
	if *envelope.Req.Code != 0 {
		return txRPCRejection(module, method, envelope.Req.Data)
	}
	for _, code := range []*int{envelope.Subcode, envelope.Retcode} {
		if code != nil && *code != 0 {
			return txRemoteError{code: "upstream_rejected"}
		}
	}
	data := envelope.Req.Data
	if len(data) == 0 || string(data) == "null" {
		return txUnavailable
	}
	var business struct {
		Code    *int `json:"code"`
		Subcode *int `json:"subcode"`
		Retcode *int `json:"retcode"`
	}
	if json.Unmarshal(data, &business) != nil {
		return txUnavailable
	}
	for _, code := range []*int{business.Code, business.Subcode, business.Retcode} {
		if code != nil && *code != 0 {
			return txRemoteError{code: "upstream_rejected"}
		}
	}
	if json.Unmarshal(data, out) != nil {
		return txUnavailable
	}
	return nil
}

type txSinger struct {
	ID   json.Number `json:"id"`
	MID  string      `json:"mid"`
	Name string      `json:"name"`
}
type txSong struct {
	ID       json.Number `json:"id"`
	MID      string      `json:"mid"`
	Name     string      `json:"name"`
	Title    string      `json:"title"`
	Interval int         `json:"interval"`
	Singer   []txSinger  `json:"singer"`
	Album    struct {
		ID    json.Number `json:"id"`
		MID   string      `json:"mid"`
		Name  string      `json:"name"`
		Title string      `json:"title"`
	} `json:"album"`
	File struct {
		MediaMID string `json:"media_mid"`
	} `json:"file"`
}

func txTrack(song txSong) (model.Track, bool) {
	title := txText(song.Title, 200)
	if title == "" {
		title = txText(song.Name, 200)
	}
	if txNumber(song.ID) <= 0 || !txMID(song.MID) || title == "" {
		return model.Track{}, false
	}
	names := []string{}
	for _, singer := range song.Singer {
		if name := txText(singer.Name, 100); name != "" {
			names = append(names, name)
		}
		if len(names) >= 20 {
			break
		}
	}
	album := txText(song.Album.Name, 200)
	if album == "" {
		album = txText(song.Album.Title, 200)
	}
	cover := txCover(song.Album.MID, false)
	if cover == "" && len(song.Singer) > 0 {
		cover = txCover(song.Singer[0].MID, true)
	}
	duration := song.Interval
	if duration < 0 || duration > 86400 {
		duration = 0
	}
	// 文件大小和会员标记不等同于复制权；真实授权由主线的能力层处理。
	return model.Track{ID: "tx:" + song.MID, ProviderID: "tx", Title: title, Artist: strings.Join(names, " / "), Album: album, Duration: duration, CoverURL: cover, Qualities: []string{}, CanDownload: false}, true
}
func txTracks(songs []txSong, limit int) ([]model.Track, error) {
	if songs == nil {
		return nil, txUnavailable
	}
	tracks := make([]model.Track, 0, min(len(songs), limit))
	seen := map[string]bool{}
	for _, song := range songs {
		t, ok := txTrack(song)
		if !ok || seen[t.ID] {
			continue
		}
		seen[t.ID] = true
		tracks = append(tracks, t)
		if len(tracks) == limit {
			break
		}
	}
	if len(songs) > 0 && len(tracks) == 0 {
		return nil, txUnavailable
	}
	return tracks, nil
}
func (q *TX) txDetail(ctx context.Context, id string) (txSong, error) {
	mid, err := txSongMID(id)
	if err != nil {
		return txSong{}, err
	}
	var result struct {
		Track *txSong `json:"track_info"`
	}
	if err = q.txRPC(ctx, "music.pf_song_detail_svr", "get_song_detail_yqq", map[string]any{"song_type": 0, "song_mid": mid}, &result); err != nil {
		return txSong{}, err
	}
	if result.Track == nil {
		return txSong{}, txUnavailable
	}
	if result.Track.MID != mid {
		return txSong{}, ErrNotFound
	}
	if _, ok := txTrack(*result.Track); !ok {
		return txSong{}, txUnavailable
	}
	return *result.Track, nil
}
func (q *TX) Track(ctx context.Context, id string) (model.Track, error) {
	song, err := q.txDetail(ctx, id)
	if err != nil {
		return model.Track{}, err
	}
	track, _ := txTrack(song)
	music, _ := txMusicInfo(song)
	q.metadata.put(model.CatalogMetadata{Track: track, MusicInfo: music})
	return track, nil
}
func (q *TX) MusicInfo(ctx context.Context, id string) (map[string]any, error) {
	song, err := q.txDetail(ctx, id)
	if err != nil {
		return nil, err
	}
	return txMusicInfo(song)
}
func txMusicInfo(song txSong) (map[string]any, error) {
	track, valid := txTrack(song)
	if !valid {
		return nil, txUnavailable
	}
	if !txMID(song.File.MediaMID) {
		return nil, txUnavailable
	}
	// LX tx 的 albumId/albumMid 都是专辑 MID；数字专辑 ID 单独保留。
	return map[string]any{
		"id": txNumber(song.ID), "songId": txNumber(song.ID), "songid": txNumber(song.ID), "songmid": song.MID,
		"name": track.Title, "songname": track.Title, "songName": track.Title, "singer": track.Artist, "artist": track.Artist,
		"source": "tx", "albumId": song.Album.MID, "albumMid": song.Album.MID, "albummid": song.Album.MID,
		"albumid": txNumber(song.Album.ID), "albumNumericId": txNumber(song.Album.ID), "albumName": track.Album,
		"duration": track.Duration, "interval": fmt.Sprintf("%02d:%02d", track.Duration/60, track.Duration%60),
		"strMediaMid": song.File.MediaMID, "img": track.CoverURL, "types": []any{}, "_types": map[string]any{}, "typeUrl": map[string]any{},
	}, nil
}

type txSearchPlaylist struct {
	ListenNum         json.RawMessage `json:"listennum"`
	ID                json.Number     `json:"dissid"`
	Title             string          `json:"dissname"`
	Description       string          `json:"introduction"`
	DescriptionMobile string          `json:"description"`
	Cover             string          `json:"imgurl"`
	CoverMobile       string          `json:"logo"`
	Count             int             `json:"song_count"`
	CountMobile       int             `json:"songnum"`
}
type txSearchArtist struct {
	MID   string `json:"singerMID"`
	Name  string `json:"singerName"`
	Cover string `json:"singerPic"`
	Count int    `json:"songNum"`
}
type txSearchAlbum struct {
	MID    string `json:"albummid"`
	Name   string `json:"name"`
	Artist string `json:"singer"`
	Cover  string `json:"pic"`
	Count  int    `json:"song_num"`
}

func txEmptySearch(page int) SearchResult {
	return SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}, Artists: []SearchArtist{}, Albums: []SearchAlbum{}, Page: page, PageSize: txPageSize}
}
func (q *TX) Search(ctx context.Context, query, kind string, page int) (SearchResult, error) {
	out := txEmptySearch(page)
	types := map[string]int{"track": 0, "artist": 1, "album": 2, "playlist": 3}
	typ, supported := types[kind]
	if !supported {
		return out, ErrUnsupported
	}
	query = strings.TrimSpace(query)
	if query == "" || !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 || strings.IndexFunc(query, unicode.IsControl) >= 0 || page < 1 || page > 50 {
		return out, ErrInput
	}
	var response struct {
		Body *struct {
			Song *struct {
				List []txSong `json:"list"`
			} `json:"song"`
			ItemSong  []txSong `json:"item_song"`
			Playlists *struct {
				List []txSearchPlaylist `json:"list"`
			} `json:"songlist"`
			ItemSonglist []txSearchPlaylist `json:"item_songlist"`
			Artists      json.RawMessage    `json:"singer"`
			Albums       []txSearchAlbum    `json:"item_album"`
		} `json:"body"`
		Meta *struct {
			Sum        *int   `json:"sum"`
			Estimate   *int   `json:"estimate_sum"`
			Ret        int    `json:"ret"`
			Filter     int    `json:"is_filter"`
			SafetyType int    `json:"safetyType"`
			SafetyURL  string `json:"safetyUrl"`
		} `json:"meta"`
	}
	// 2026-09 实测：桌面方法对匿名请求整体返回 req.code=2001/is_filter=-12，
	// 官方 App 的移动方法四类目录均正常；统一走移动方法，不为绕过风控改签名或登录。
	method := "DoSearchForQQMusicMobile"
	methodParams := map[string]any{"search_type": typ, "query": query, "page_num": page, "num_per_page": txPageSize, "highlight": 0}
	err := q.txRPC(ctx, "music.search.SearchCgiService", method, methodParams, &response)
	if err != nil {
		return out, err
	}
	if response.Body == nil || response.Meta == nil {
		return out, txUnavailable
	}
	meta := response.Meta
	if meta.Filter < 0 || meta.SafetyType != 0 || meta.SafetyURL != "" {
		return out, txRemoteError{code: "access_restricted"}
	}
	if meta.Ret != 0 {
		return out, txRemoteError{code: "upstream_rejected"}
	}
	if meta.Sum == nil && meta.Estimate == nil {
		return out, txUnavailable
	}
	total := 0
	if meta.Sum != nil {
		total = *meta.Sum
	}
	if meta.Estimate != nil && *meta.Estimate > total {
		total = *meta.Estimate
	}
	if total < 0 {
		return out, txUnavailable
	}
	out.Total = txCount(total)
	rawCount := 0
	seen := map[string]bool{}
	switch kind {
	case "track":
		songs := response.Body.ItemSong
		if songs == nil && response.Body.Song != nil {
			songs = response.Body.Song.List
		}
		if songs == nil {
			return out, txUnavailable
		}
		rawCount = len(songs)
		out.Tracks, err = q.cachedTracks(songs, txPageSize)
		if err != nil {
			return out, err
		}
	case "playlist":
		items := response.Body.ItemSonglist
		if items == nil && response.Body.Playlists != nil {
			items = response.Body.Playlists.List
		}
		if items == nil {
			return out, txUnavailable
		}
		rawCount = len(items)
		for _, item := range items {
			id := txNumber(item.ID)
			title := txText(item.Title, 200)
			if id == 0 || title == "" {
				continue
			}
			key := "tx:playlist_" + strconv.FormatInt(id, 10)
			if seen[key] {
				continue
			}
			seen[key] = true
			description := item.Description
			if description == "" {
				description = item.DescriptionMobile
			}
			cover := item.Cover
			if cover == "" {
				cover = item.CoverMobile
			}
			count := item.Count
			if count == 0 {
				count = item.CountMobile
			}
			out.Playlists = append(out.Playlists, model.Collection{ID: key, ProviderID: "tx", Title: title, Description: txText(description, 1000), CoverURL: txImage(cover), TrackCount: txCount(count), PlayCount: publicPlayCount(item.ListenNum)})
			if len(out.Playlists) == txPageSize {
				break
			}
		}
	case "artist":
		// 移动搜索的 singer 是数组，桌面歌手响应是 {list:[]}；只解码当前资源。
		var artists []txSearchArtist
		if json.Unmarshal(response.Body.Artists, &artists) != nil || artists == nil {
			var wrapped struct {
				List []txSearchArtist `json:"list"`
			}
			if json.Unmarshal(response.Body.Artists, &wrapped) != nil || wrapped.List == nil {
				return out, txUnavailable
			}
			artists = wrapped.List
		}
		rawCount = len(artists)
		for _, item := range artists {
			name := txText(item.Name, 200)
			if !txMID(item.MID) || name == "" || seen[item.MID] {
				continue
			}
			seen[item.MID] = true
			cover := txImage(item.Cover)
			if cover == "" {
				cover = txCover(item.MID, true)
			}
			out.Artists = append(out.Artists, SearchArtist{ID: "tx:artist_" + item.MID, Name: name, CoverURL: cover, TrackCount: txCount(item.Count)})
			if len(out.Artists) == txPageSize {
				break
			}
		}
	case "album":
		if response.Body.Albums == nil {
			return out, txUnavailable
		}
		rawCount = len(response.Body.Albums)
		for _, item := range response.Body.Albums {
			title := txText(item.Name, 200)
			if !txMID(item.MID) || title == "" || seen[item.MID] {
				continue
			}
			seen[item.MID] = true
			cover := txImage(item.Cover)
			if cover == "" {
				cover = txCover(item.MID, false)
			}
			out.Albums = append(out.Albums, SearchAlbum{ID: "tx:album_" + item.MID, Title: title, Artist: txText(item.Artist, 200), CoverURL: cover, TrackCount: txCount(item.Count)})
			if len(out.Albums) == txPageSize {
				break
			}
		}
	}
	count := len(out.Tracks) + len(out.Playlists) + len(out.Artists) + len(out.Albums)
	if count == 0 && (rawCount > 0 || (page-1)*txPageSize < out.Total) {
		return txEmptySearch(page), txUnavailable
	}
	out.Total = max(out.Total, count)
	return out, nil
}

type txChartInfo struct {
	ID    json.Number `json:"topId"`
	Title string      `json:"title"`
	Intro string      `json:"intro"`
	Cover string      `json:"frontPicUrl"`
	Count int         `json:"totalNum"`
}

func txChartCollection(item txChartInfo) (model.Collection, bool) {
	id := txNumber(item.ID)
	title := txText(item.Title, 200)
	if id == 0 || id == 201 || title == "" {
		return model.Collection{}, false
	}
	return model.Collection{ID: "tx:chart_" + strconv.FormatInt(id, 10), ProviderID: "tx", Title: title, Description: txText(item.Intro, 1000), CoverURL: txImage(item.Cover), TrackCount: txCount(item.Count)}, true
}
func (q *TX) Charts(ctx context.Context) ([]model.Collection, error) {
	var response struct {
		Groups []struct {
			Name   string        `json:"groupName"`
			Charts []txChartInfo `json:"toplist"`
		} `json:"group"`
	}
	if err := q.txRPC(ctx, "musicToplist.ToplistInfoServer", "GetAll", struct{}{}, &response); err != nil {
		return nil, err
	}
	if response.Groups == nil {
		return nil, txUnavailable
	}
	out := []model.Collection{}
	seen := map[string]bool{}
	for _, group := range response.Groups {
		for _, item := range group.Charts {
			entry, ok := txChartCollection(item)
			if !ok || seen[entry.ID] {
				continue
			}
			seen[entry.ID] = true
			entry.Category = txText(group.Name, 80)
			out = append(out, entry)
			if len(out) >= 100 {
				return out, nil
			}
		}
	}
	if len(out) == 0 {
		return nil, txUnavailable
	}
	return out, nil
}
func (q *TX) Chart(ctx context.Context, id string) (model.Collection, error) {
	n, err := txResourceNumber(id, "chart")
	if err != nil {
		return model.Collection{}, err
	}
	if n == 201 {
		return model.Collection{}, ErrUnsupported
	}
	var response struct {
		Data  *txChartInfo `json:"data"`
		Songs []txSong     `json:"songInfoList"`
	}
	if err = q.txRPC(ctx, "musicToplist.ToplistInfoServer", "GetDetail", map[string]any{"topid": n, "offset": 0, "num": 300}, &response); err != nil {
		return model.Collection{}, err
	}
	if response.Data == nil {
		return model.Collection{}, txUnavailable
	}
	item, ok := txChartCollection(*response.Data)
	if !ok {
		return model.Collection{}, txUnavailable
	}
	if item.ID != id {
		return model.Collection{}, ErrNotFound
	}
	item.Tracks, err = q.cachedTracks(response.Songs, 300)
	if err != nil {
		return model.Collection{}, err
	}
	if len(item.Tracks) == 0 && item.TrackCount > 0 {
		return model.Collection{}, txUnavailable
	}
	if item.TrackCount > len(item.Tracks) {
		item.Description = fmt.Sprintf("当前公开接口返回 %d 首，共 %d 首。", len(item.Tracks), item.TrackCount) + item.Description
	}
	item.TrackCount = len(item.Tracks)
	return item, nil
}

type txPlaylistBasic struct {
	PlayCount   json.RawMessage `json:"play_cnt"`
	AccessNum   json.RawMessage `json:"access_num"`
	ID          json.Number     `json:"tid"`
	Title       string          `json:"title"`
	Description string          `json:"desc"`
	Count       int             `json:"song_cnt"`
	CoverURL    string          `json:"cover_url_medium"`
	CoverBig    string          `json:"cover_url_big"`
	CoverSmall  string          `json:"cover_url_small"`
	SongIDs     []json.Number   `json:"song_ids"`
	Cover       struct {
		Medium  string `json:"medium_url"`
		Default string `json:"default_url"`
	} `json:"cover"`
}

func txPlaylistCollection(item txPlaylistBasic, category string) (model.Collection, bool) {
	n := txNumber(item.ID)
	title := txText(item.Title, 200)
	if n == 0 || title == "" {
		return model.Collection{}, false
	}
	cover := txImage(item.CoverURL)
	if cover == "" {
		cover = txImage(item.CoverBig)
	}
	if cover == "" {
		cover = txImage(item.CoverSmall)
	}
	if cover == "" {
		cover = txImage(item.Cover.Medium)
	}
	if cover == "" {
		cover = txImage(item.Cover.Default)
	}
	count := item.Count
	if count == 0 && item.SongIDs != nil {
		count = len(item.SongIDs)
	}
	return model.Collection{ID: "tx:playlist_" + strconv.FormatInt(n, 10), ProviderID: "tx", Title: title, Description: txText(item.Description, 1000), CoverURL: cover, TrackCount: txCount(count), PlayCount: firstPlayCount(item.PlayCount, item.AccessNum), Category: category}, true
}
func (q *TX) publicPlaylists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	if page < 1 || page > 50 {
		return nil, ErrInput
	}
	if category == "" {
		category = "all"
	}
	var items []txPlaylistBasic
	total := 0
	if category == "all" {
		var response struct {
			Items []txPlaylistBasic `json:"v_playlist"`
			Total *int              `json:"total"`
		}
		err := q.txRPC(ctx, "playlist.PlayListPlazaServer", "get_playlist_by_tag", map[string]any{"id": 10000000, "sin": (page - 1) * txPageSize, "size": txPageSize, "order": 5, "cur_page": page}, &response)
		if err != nil {
			return nil, err
		}
		if response.Total == nil {
			return nil, txUnavailable
		}
		items = response.Items
		total = *response.Total
	} else {
		n, err := txResourceNumber(category, "category")
		if err != nil {
			return nil, ErrInput
		}
		var response struct {
			Content *struct {
				Total *int `json:"total_cnt"`
				Items []struct {
					Basic txPlaylistBasic `json:"basic"`
				} `json:"v_item"`
			} `json:"content"`
		}
		err = q.txRPC(ctx, "playlist.PlayListCategoryServer", "get_category_content", map[string]any{"titleid": n, "caller": "0", "category_id": n, "size": txPageSize, "page": page - 1, "use_page": 1}, &response)
		if err != nil {
			return nil, err
		}
		if response.Content == nil || response.Content.Total == nil || response.Content.Items == nil {
			return nil, txUnavailable
		}
		total = *response.Content.Total
		items = make([]txPlaylistBasic, 0, len(response.Content.Items))
		for _, item := range response.Content.Items {
			items = append(items, item.Basic)
		}
	}
	if items == nil || total < 0 {
		return nil, txUnavailable
	}
	out := make([]model.Collection, 0, min(len(items), txPageSize))
	seen := map[string]bool{}
	for _, item := range items {
		entry, ok := txPlaylistCollection(item, category)
		if !ok || seen[entry.ID] {
			continue
		}
		seen[entry.ID] = true
		out = append(out, entry)
		if len(out) == txPageSize {
			break
		}
	}
	if len(out) == 0 && (len(items) > 0 || (page-1)*txPageSize < total) {
		return nil, txUnavailable
	}
	return out, nil
}
func (q *TX) PlaylistCategories(ctx context.Context) ([]PlaylistCategory, error) {
	var response struct {
		Groups []struct {
			Name  string `json:"group_name"`
			Items []struct {
				ID     json.Number `json:"id"`
				Name   string      `json:"name"`
				Status int         `json:"status"`
			} `json:"v_item"`
		} `json:"v_group"`
	}
	if err := q.txRPC(ctx, "playlist.PlaylistAllCategoriesServer", "get_all_categories", map[string]string{"qq": ""}, &response); err != nil {
		return nil, err
	}
	if response.Groups == nil {
		return nil, txUnavailable
	}
	out := []PlaylistCategory{}
	seen := map[string]bool{}
	for _, group := range response.Groups {
		name := txText(group.Name, 80)
		if name == "" {
			continue
		}
		for _, item := range group.Items {
			n := txNumber(item.ID)
			title := txText(item.Name, 80)
			if n == 0 || title == "" || item.Status != 0 {
				continue
			}
			id := "tx:category_" + strconv.FormatInt(n, 10)
			if seen[id] {
				continue
			}
			seen[id] = true
			out = append(out, PlaylistCategory{ID: id, Name: title, Group: name})
			if len(out) == 256 {
				return out, nil
			}
		}
	}
	if len(out) == 0 {
		return nil, txUnavailable
	}
	return out, nil
}
func (q *TX) Playlist(ctx context.Context, id string) (model.Collection, error) {
	n, err := txResourceNumber(id, "playlist")
	if err != nil {
		return model.Collection{}, err
	}
	// 分批取元数据，单页 100 首；整个详情操作同样限制 9 秒，最多 1000 首。
	ctx, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	var out model.Collection
	seen := map[string]bool{}
	offset := 0
	for batch := 0; batch < txMaxDetailTracks/100 && offset < txMaxDetailTracks; batch++ {
		var response struct {
			Dir *struct {
				ListenNum   json.RawMessage `json:"listennum"`
				ID          json.Number     `json:"id"`
				Title       string          `json:"title"`
				Description string          `json:"desc"`
				Cover       string          `json:"picurl"`
				Count       int             `json:"songnum"`
			} `json:"dirinfo"`
			Songs   []txSong `json:"songlist"`
			Total   *int     `json:"total_song_num"`
			HasMore *int     `json:"hasmore"`
		}
		err = q.txRPC(ctx, "music.srfDissInfo.aiDissInfo", "uniform_get_Dissinfo", map[string]any{"disstid": n, "userinfo": 1, "tag": 1, "orderlist": 1, "song_begin": offset, "song_num": 100}, &response)
		if err != nil {
			return model.Collection{}, err
		}
		if response.Dir == nil || response.Total == nil || *response.Total < 0 || response.HasMore == nil || (*response.HasMore != 0 && *response.HasMore != 1) || len(response.Songs) > 100 {
			return model.Collection{}, txUnavailable
		}
		if txNumber(response.Dir.ID) != n {
			return model.Collection{}, ErrNotFound
		}
		if offset == 0 {
			title := txText(response.Dir.Title, 200)
			if title == "" {
				return model.Collection{}, txUnavailable
			}
			out = model.Collection{ID: id, ProviderID: "tx", Title: title, Description: txText(response.Dir.Description, 1000), CoverURL: txImage(response.Dir.Cover), PlayCount: publicPlayCount(response.Dir.ListenNum), Tracks: []model.Track{}}
		}
		tracks, err := q.cachedTracks(response.Songs, 100)
		if err != nil {
			return model.Collection{}, err
		}
		before := len(out.Tracks)
		for _, t := range tracks {
			if !seen[t.ID] {
				seen[t.ID] = true
				out.Tracks = append(out.Tracks, t)
			}
		}
		if len(response.Songs) == 0 && offset < *response.Total {
			return model.Collection{}, txUnavailable
		}
		if *response.HasMore != 0 && len(out.Tracks) == before {
			return model.Collection{}, txUnavailable
		}
		offset += len(response.Songs)
		if *response.HasMore == 0 || offset >= txMaxDetailTracks || batch == txMaxDetailTracks/100-1 {
			if *response.Total > len(out.Tracks) {
				out.Description = fmt.Sprintf("当前公开接口返回 %d 首，共 %d 首。", len(out.Tracks), *response.Total) + out.Description
			}
			out.TrackCount = len(out.Tracks)
			return out, nil
		}
	}
	return model.Collection{}, txUnavailable
}
func (q *TX) Lyrics(ctx context.Context, id string) (Lyrics, error) {
	out := Lyrics{Lines: []LyricLine{}, Source: "扣扣"}
	mid, err := txSongMID(id)
	if err != nil {
		return out, err
	}
	params := url.Values{"songmid": {mid}, "format": {"json"}, "nobase64": {"1"}, "g_tk": {"5381"}}
	raw, err := catalogRequest(ctx, q.client, http.MethodGet, "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?"+params.Encode(), txHeaders(), nil)
	if err != nil {
		return out, txUnavailable
	}
	var response struct {
		Code    *int    `json:"code"`
		Retcode *int    `json:"retcode"`
		Subcode *int    `json:"subcode"`
		Lyric   *string `json:"lyric"`
	}
	if json.Unmarshal(raw, &response) != nil || response.Code == nil || *response.Code != 0 || response.Lyric == nil {
		return out, txUnavailable
	}
	for _, code := range []*int{response.Retcode, response.Subcode} {
		if code != nil && *code != 0 {
			return out, txUnavailable
		}
	}
	text := html.UnescapeString(*response.Lyric)
	// 旧接口有时忽略 nobase64；仅接受明文 LRC 或标准 Base64 LRC，不实现 QRC 解密。
	if text != "" && !strings.Contains(text, "[") {
		decoded, err := base64.StdEncoding.DecodeString(text)
		if err != nil {
			return out, txUnavailable
		}
		text = html.UnescapeString(string(decoded))
	}
	if len(text) > 256<<10 || !utf8.ValidString(text) {
		return out, txUnavailable
	}
	out.Lines = ParseLRC(text)
	if text != "" && len(out.Lines) == 0 {
		return out, ErrUnsupported
	}
	return out, nil
}

// 可选新歌速递尚未纳入此 Adapter，不拿榜单/歌单冒充新歌结果。
func (q *TX) NewTracks(ctx context.Context, _ string) ([]model.Track, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return nil, ErrUnsupported
}

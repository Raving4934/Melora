// Package catalog 读取WY公开元数据；不解析、代理或下载音频，不绕过登录/验证码。
package catalog

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

var (
	ErrUnavailable = errors.New("音乐目录暂不可用，请稍后重试")
	ErrNotFound    = errors.New("未找到该歌曲或歌单")
	ErrInput       = errors.New("目录参数无效")
)

const maxResponseBytes = 2 << 20
const maxTrackCache = 4000
const PageSize = 20

type HTTPDoer interface {
	Do(*http.Request) (*http.Response, error)
}
type cached struct {
	data    []byte
	expires time.Time
}
type WY struct {
	metadata   metadataMemory
	http       HTTPDoer
	base       string
	mu         sync.Mutex
	cache      map[string]cached
	cacheBytes int
	tracks     map[string]model.Track
	music      map[string]map[string]any
}

func NewWY(client HTTPDoer) *WY {
	return &WY{http: client, base: "https://music.163.com", cache: map[string]cached{}, tracks: map[string]model.Track{}, music: map[string]map[string]any{}}
}
func (c *WY) get(ctx context.Context, path string, params url.Values, out any) error {
	if c.http == nil {
		return ErrUnavailable
	}
	key := path + "?" + params.Encode()
	c.mu.Lock()
	item, ok := c.cache[key]
	c.mu.Unlock()
	if ok && time.Now().Before(item.expires) {
		if json.Unmarshal(item.data, out) == nil {
			return nil
		}
	}
	bounded, cancel := context.WithTimeout(ctx, 9*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(bounded, http.MethodGet, c.base+key, nil)
	if err != nil {
		return ErrUnavailable
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; Melora/0.3)")
	req.Header.Set("Referer", "https://music.163.com/")
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return catalogIssueError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ErrUnavailable
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes+1))
	if err != nil || len(data) > maxResponseBytes {
		return ErrUnavailable
	}
	var envelope struct {
		Code int `json:"code"`
	}
	if json.Unmarshal(data, &envelope) != nil || envelope.Code != 200 {
		return ErrUnavailable
	}
	if json.Unmarshal(data, out) != nil {
		return ErrUnavailable
	}
	c.mu.Lock()
	if prior, exists := c.cache[key]; exists {
		c.cacheBytes -= len(prior.data)
	}
	if len(c.cache) >= 64 || c.cacheBytes+len(data) > 8<<20 {
		c.cache = map[string]cached{}
		c.cacheBytes = 0
	}
	c.cache[key] = cached{data: data, expires: time.Now().Add(2 * time.Minute)}
	c.cacheBytes += len(data)
	c.mu.Unlock()
	return nil
}
func clean(s string, max int) string {
	s = strings.TrimSpace(strings.Map(func(r rune) rune {
		if unicode.IsControl(r) && r != '\n' {
			return ' '
		}
		return r
	}, s))
	r := []rune(s)
	if len(r) > max {
		return string(r[:max])
	}
	return s
}
func image(s string) string {
	u, err := url.Parse(s)
	if err != nil || u.User != nil {
		return ""
	}
	host := strings.ToLower(u.Hostname())
	if !strings.HasSuffix(host, ".music.126.net") && host != "music.126.net" {
		return ""
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return ""
	}
	u.Scheme = "https"
	return u.String()
}
func id(number int64) string { return "wy:" + strconv.FormatInt(number, 10) }
func number(raw string) (int64, error) {
	if !strings.HasPrefix(raw, "wy:") {
		return 0, ErrNotFound
	}
	value := strings.TrimPrefix(raw, "wy:")
	if value == "" || len(value) > 16 {
		return 0, ErrNotFound
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			return 0, ErrNotFound
		}
	}
	n, err := strconv.ParseInt(value, 10, 64)
	if err != nil || n <= 0 {
		return 0, ErrNotFound
	}
	return n, nil
}

type Artist struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	PicURL    string `json:"picUrl"`
	Img1v1URL string `json:"img1v1Url"`
	MusicSize int    `json:"musicSize"`
}
type Album struct {
	ID     int64  `json:"id"`
	Name   string `json:"name"`
	PicURL string `json:"picUrl"`
	Size   int    `json:"size"`
	Artist Artist `json:"artist"`
}
type Song struct {
	ID       int64    `json:"id"`
	Name     string   `json:"name"`
	Duration int      `json:"duration"`
	DT       int      `json:"dt"`
	Artists  []Artist `json:"artists"`
	AR       []Artist `json:"ar"`
	Album    Album    `json:"album"`
	AL       Album    `json:"al"`
}

func (c *WY) convert(song Song) (model.Track, bool) {
	if song.ID <= 0 || strings.TrimSpace(song.Name) == "" {
		return model.Track{}, false
	}
	artists := song.Artists
	if len(artists) == 0 {
		artists = song.AR
	}
	names := []string{}
	for _, artist := range artists {
		if n := clean(artist.Name, 100); n != "" {
			names = append(names, n)
		}
	}
	album := song.Album
	if album.ID == 0 {
		album = song.AL
	}
	duration := song.Duration
	if duration == 0 {
		duration = song.DT
	}
	if duration < 0 || duration > 24*60*60*1000 {
		duration = 0
	}
	track := model.Track{ID: id(song.ID), ProviderID: "wy", Title: clean(song.Name, 200), Artist: strings.Join(names, " / "), Album: clean(album.Name, 200), Duration: duration / 1000, CoverURL: image(album.PicURL), Qualities: []string{}, CanDownload: false}
	music := map[string]any{"id": song.ID, "songmid": song.ID, "songId": song.ID, "songid": song.ID, "name": track.Title, "songname": track.Title, "songName": track.Title, "singer": track.Artist, "artist": track.Artist, "albumId": album.ID, "albumName": track.Album, "duration": track.Duration, "source": "wy", "interval": fmt.Sprintf("%02d:%02d", track.Duration/60, track.Duration%60), "img": track.CoverURL, "typeUrl": map[string]any{}, "types": []any{}, "_types": map[string]any{}}
	c.mu.Lock()
	if len(c.tracks) >= maxTrackCache {
		c.tracks = map[string]model.Track{}
		c.music = map[string]map[string]any{}
	}
	c.tracks[track.ID] = track
	c.music[track.ID] = music
	c.mu.Unlock()
	c.metadata.put(model.CatalogMetadata{Track: track, MusicInfo: music})
	return track, true
}
func (c *WY) convertAll(songs []Song, limit int) []model.Track {
	result := []model.Track{}
	seen := map[string]bool{}
	for _, song := range songs {
		if len(result) >= limit {
			break
		}
		track, ok := c.convert(song)
		if ok && !seen[track.ID] {
			result = append(result, track)
			seen[track.ID] = true
		}
	}
	return result
}
func (c *WY) Track(ctx context.Context, raw string) (model.Track, error) {
	n, err := number(raw)
	if err != nil {
		return model.Track{}, err
	}
	c.mu.Lock()
	track, ok := c.tracks[raw]
	c.mu.Unlock()
	if ok {
		return track, nil
	}
	var response struct {
		Songs []Song `json:"songs"`
	}
	if err = c.get(ctx, "/api/song/detail", url.Values{"ids": {fmt.Sprintf("[%d]", n)}}, &response); err != nil {
		return model.Track{}, err
	}
	for _, song := range response.Songs {
		if song.ID == n {
			if t, ok := c.convert(song); ok {
				return t, nil
			}
		}
	}
	return model.Track{}, ErrNotFound
}
func (c *WY) MusicInfo(ctx context.Context, raw string) (map[string]any, error) {
	if _, err := c.Track(ctx, raw); err != nil {
		return nil, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	music, ok := c.music[raw]
	if !ok {
		return nil, ErrNotFound
	}
	// JSON复制不给脚本共享目录缓存对象引用。
	data, _ := json.Marshal(music)
	out := map[string]any{}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	_ = decoder.Decode(&out)
	return out, nil
}

type playlist struct {
	PlayCount   json.RawMessage `json:"playCount"`
	ID          int64           `json:"id"`
	Name        string          `json:"name"`
	Description string          `json:"description"`
	CoverImgURL string          `json:"coverImgUrl"`
	TrackCount  int             `json:"trackCount"`
	Tags        []string        `json:"tags"`
	Tracks      []Song          `json:"tracks"`
}

func collection(p playlist, category string) model.Collection {
	description := clean(p.Description, 1000)
	count := p.TrackCount
	if count < 0 {
		count = 0
	}
	return model.Collection{ID: id(p.ID), ProviderID: "wy", Title: clean(p.Name, 200), Description: description, CoverURL: image(p.CoverImgURL), TrackCount: count, PlayCount: publicPlayCount(p.PlayCount), Category: category}
}

// 只对同一上游响应中的 ID 去重并保留顺序；不跨分页/分类偷偷删条目。
func collections(items []playlist, category string, limit int) []model.Collection {
	out := []model.Collection{}
	seen := map[int64]bool{}
	for _, item := range items {
		if item.ID <= 0 || seen[item.ID] || clean(item.Name, 200) == "" {
			continue
		}
		seen[item.ID] = true
		out = append(out, collection(item, category))
		if len(out) == limit {
			break
		}
	}
	return out
}

func (c *WY) Charts(ctx context.Context) ([]model.Collection, error) {
	var response struct {
		List []playlist `json:"list"`
	}
	if err := c.get(ctx, "/api/toplist/detail", nil, &response); err != nil {
		return nil, err
	}
	return collections(response.List, "排行榜", 40), nil
}
func (c *WY) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	if category == "" || category == "all" {
		category = "全部"
	}
	if !utf8.ValidString(category) || utf8.RuneCountInString(category) > 40 || page < 1 || page > 50 {
		return nil, ErrInput
	}
	var response struct {
		Playlists []playlist `json:"playlists"`
	}
	if err := c.get(ctx, "/api/playlist/list", url.Values{"cat": {category}, "limit": {"24"}, "offset": {strconv.Itoa((page - 1) * 24)}, "order": {"hot"}}, &response); err != nil {
		return nil, err
	}
	return collections(response.Playlists, category, 24), nil
}
func (c *WY) Playlist(ctx context.Context, raw string) (model.Collection, error) {
	n, err := number(raw)
	if err != nil {
		return model.Collection{}, err
	}
	var response struct {
		Result   playlist `json:"result"`
		Playlist playlist `json:"playlist"`
	}
	// 旧 /api/playlist/detail 实测会忽略 n，取完整榜单/歌单后再截断已太晚。
	// v6 的 n 在服务端生效，s=0 不请求订阅者资料；继续使用原有网络体积限制。
	if err = c.get(ctx, "/api/v6/playlist/detail", url.Values{"id": {strconv.FormatInt(n, 10)}, "n": {"100"}, "s": {"0"}}, &response); err != nil {
		return model.Collection{}, err
	}
	p := response.Result
	if p.ID == 0 {
		p = response.Playlist
	}
	if p.ID != n {
		return model.Collection{}, ErrNotFound
	}
	if clean(p.Name, 200) == "" {
		return model.Collection{}, ErrUnavailable
	}
	out := collection(p, "歌单")
	out.Tracks = c.convertAll(p.Tracks, 100)
	if len(out.Tracks) == 0 && (p.TrackCount > 0 || len(p.Tracks) > 0) {
		return model.Collection{}, ErrUnavailable
	}
	if p.TrackCount > len(out.Tracks) {
		out.Description = fmt.Sprintf("当前加载 %d 首，共 %d 首。", len(out.Tracks), p.TrackCount) + out.Description
	}
	out.TrackCount = len(out.Tracks)
	return out, nil
}

type SearchArtist struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	CoverURL   string `json:"coverUrl"`
	TrackCount int    `json:"trackCount"`
}
type SearchAlbum struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	CoverURL   string `json:"coverUrl"`
	TrackCount int    `json:"trackCount"`
}
type SearchResult struct {
	Tracks    []model.Track      `json:"tracks"`
	Playlists []model.Collection `json:"playlists"`
	Artists   []SearchArtist     `json:"artists"`
	Albums    []SearchAlbum      `json:"albums"`
	Total     int                `json:"total"`
	Page      int                `json:"page"`
	PageSize  int                `json:"pageSize"`
}

func (c *WY) Search(ctx context.Context, query, kind string, page int) (SearchResult, error) {
	out := SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}, Artists: []SearchArtist{}, Albums: []SearchAlbum{}, Page: page, PageSize: PageSize}
	query = strings.TrimSpace(query)
	types := map[string]string{"track": "1", "album": "10", "artist": "100", "playlist": "1000"}
	typ, ok := types[kind]
	if !ok || !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 || page < 1 || page > 50 {
		return out, ErrInput
	}
	if query == "" {
		return out, nil
	}
	var response struct {
		Result struct {
			Songs         []Song     `json:"songs"`
			SongCount     int        `json:"songCount"`
			Playlists     []playlist `json:"playlists"`
			PlaylistCount int        `json:"playlistCount"`
			Artists       []Artist   `json:"artists"`
			ArtistCount   int        `json:"artistCount"`
			Albums        []Album    `json:"albums"`
			AlbumCount    int        `json:"albumCount"`
		} `json:"result"`
	}
	if err := c.get(ctx, "/api/search/get/web", url.Values{"s": {query}, "type": {typ}, "limit": {strconv.Itoa(PageSize)}, "offset": {strconv.Itoa((page - 1) * PageSize)}}, &response); err != nil {
		return out, err
	}
	r := response.Result
	switch kind {
	case "track":
		out.Tracks = c.withSearchCovers(ctx, c.convertAll(r.Songs, PageSize))
		out.Total = r.SongCount
	case "playlist":
		out.Playlists = collections(r.Playlists, "歌单", PageSize)
		out.Total = r.PlaylistCount
	case "artist":
		for _, a := range r.Artists {
			cover := a.PicURL
			if cover == "" {
				cover = a.Img1v1URL
			}
			if a.ID > 0 {
				out.Artists = append(out.Artists, SearchArtist{id(a.ID), clean(a.Name, 200), image(cover), max(a.MusicSize, 0)})
			}
			if len(out.Artists) == PageSize {
				break
			}
		}
		out.Total = r.ArtistCount
	case "album":
		for _, a := range r.Albums {
			if a.ID > 0 {
				out.Albums = append(out.Albums, SearchAlbum{id(a.ID), clean(a.Name, 200), clean(a.Artist.Name, 200), image(a.PicURL), max(a.Size, 0)})
			}
			if len(out.Albums) == PageSize {
				break
			}
		}
		out.Total = r.AlbumCount
	}
	if out.Total < 0 {
		out.Total = 0
	}
	return out, nil
}

// 搜索轻量响应常省略专辑封面。批量补全最多一页的详情，避免逐首请求；
// 图片补全失败不丢弃已经取得的真实搜索结果，也不使用伪造唱片封面。
func (c *WY) withSearchCovers(ctx context.Context, tracks []model.Track) []model.Track {
	ids := []int64{}
	wanted := map[string]bool{}
	for _, track := range tracks {
		if track.CoverURL != "" {
			continue
		}
		n, err := number(track.ID)
		if err == nil {
			ids = append(ids, n)
			wanted[track.ID] = true
		}
	}
	if len(ids) == 0 {
		return tracks
	}
	encoded, _ := json.Marshal(ids)
	var response struct {
		Songs []Song `json:"songs"`
	}
	bounded, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if c.get(bounded, "/api/song/detail", url.Values{"ids": {string(encoded)}}, &response) != nil {
		return tracks
	}
	replacements := map[string]model.Track{}
	for _, song := range response.Songs {
		if !wanted[id(song.ID)] {
			continue
		}
		if track, ok := c.convert(song); ok && track.CoverURL != "" {
			replacements[track.ID] = track
		}
	}
	for i, track := range tracks {
		if full, ok := replacements[track.ID]; ok {
			tracks[i] = full
		}
	}
	return tracks
}

type LyricLine struct {
	Time float64 `json:"time"`
	Text string  `json:"text"`
}
type Lyrics struct {
	Lines  []LyricLine `json:"lines"`
	Source string      `json:"source"`
}

func (c *WY) Lyrics(ctx context.Context, raw string) (Lyrics, error) {
	out := Lyrics{Lines: []LyricLine{}, Source: "网抑云"}
	n, err := number(raw)
	if err != nil {
		return out, err
	}
	var response struct {
		LRC struct {
			Lyric string `json:"lyric"`
		} `json:"lrc"`
	}
	if err = c.get(ctx, "/api/song/lyric", url.Values{"id": {strconv.FormatInt(n, 10)}, "lv": {"-1"}, "kv": {"-1"}, "tv": {"-1"}}, &response); err != nil {
		return out, err
	}
	out.Lines = ParseLRC(response.LRC.Lyric)
	return out, nil
}
func ParseLRC(raw string) []LyricLine {
	lines := []LyricLine{}
	if len(raw) > 256<<10 {
		return lines
	}
	for _, line := range strings.Split(raw, "\n") {
		stamps := []float64{}
		for strings.HasPrefix(line, "[") {
			end := strings.IndexByte(line, ']')
			if end < 0 {
				break
			}
			stamp := line[1:end]
			line = line[end+1:]
			parts := strings.SplitN(stamp, ":", 2)
			if len(parts) != 2 {
				continue
			}
			minute, e1 := strconv.Atoi(parts[0])
			second, e2 := strconv.ParseFloat(parts[1], 64)
			if e1 == nil && e2 == nil && minute >= 0 && minute <= 1440 && second >= 0 && second < 60 {
				stamps = append(stamps, float64(minute)*60+second)
			}
		}
		text := clean(line, 1000)
		if text == "" {
			continue
		}
		for _, stamp := range stamps {
			lines = append(lines, LyricLine{stamp, text})
			if len(lines) >= 3000 {
				break
			}
		}
		if len(lines) >= 3000 {
			break
		}
	}
	sort.SliceStable(lines, func(i, j int) bool { return lines[i].Time < lines[j].Time })
	return lines
}

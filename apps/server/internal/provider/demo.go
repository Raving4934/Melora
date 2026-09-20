// Package provider 只提供明确授权的演示目录；不加载、不执行用户音源脚本。
package provider

import (
	"context"
	"errors"
	"strings"

	"melora/internal/model"
)

type Capabilities struct {
	Search          bool `json:"search"`
	Charts          bool `json:"charts"`
	Playlists       bool `json:"playlists"`
	Recommendations bool `json:"recommendations"`
	Play            bool `json:"play"`
	Download        bool `json:"download"`
}
type Info struct {
	ID           string       `json:"id"`
	Name         string       `json:"name"`
	Description  string       `json:"description"`
	Enabled      bool         `json:"enabled"`
	IsDemo       bool         `json:"isDemo"`
	Capabilities Capabilities `json:"capabilities"`
	Status       string       `json:"status"`
}
type Artist struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	CoverURL   string `json:"coverUrl"`
	TrackCount int    `json:"trackCount"`
}
type Album struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	CoverURL   string `json:"coverUrl"`
	TrackCount int    `json:"trackCount"`
}
type SearchResult struct {
	Tracks    []model.Track      `json:"tracks"`
	Playlists []model.Collection `json:"playlists"`
	Artists   []Artist           `json:"artists"`
	Albums    []Album            `json:"albums"`
	Total     int                `json:"total"`
}
type Recommendations struct {
	Tracks    []model.Track      `json:"tracks"`
	Playlists []model.Collection `json:"playlists"`
}
type LyricLine struct {
	Time float64 `json:"time"`
	Text string  `json:"text"`
}
type Lyrics struct {
	Lines  []LyricLine `json:"lines"`
	Source string      `json:"source"`
}

type recording struct {
	track model.Track
	url   string
}
type Demo struct {
	recordings []recording
	playlists  []model.Collection
	charts     []model.Collection
}

func NewDemo() *Demo {
	d := &Demo{recordings: []recording{
		{model.Track{ID: "demo:maple-leaf-rag-1906", ProviderID: "demo", Title: "Maple Leaf Rag · 1906 年录音", Artist: "United States Marine Band", Album: "Scott Joplin · 公有领域录音", Duration: 115, CoverURL: "/covers/paper.svg", Qualities: []string{"standard"}, CanDownload: true}, "https://upload.wikimedia.org/wikipedia/commons/3/3a/1906_-_Scott_Joplin%27s_Maple_Leaf_Rag_%281899%29_played_by_the_United_States_Marine_Band.ogg"},
		{model.Track{ID: "demo:the-entertainer-2007", ProviderID: "demo", Title: "The Entertainer · 2007 年键盘演奏", Artist: "IE (Wikimedia contributor)", Album: "Scott Joplin · 公有领域键盘演奏", Duration: 234, CoverURL: "/covers/dusk.svg", Qualities: []string{"standard"}, CanDownload: true}, "https://upload.wikimedia.org/wikipedia/commons/1/1b/The_Entertainer_-_Scott_Joplin.ogg"},
	}}
	for _, seed := range []struct{ id, title, cover, category string }{
		{"coast", "海岸慢听", "coast", "轻松"}, {"dusk", "日落琴声", "dusk", "器乐"},
		{"forest", "林间旧时光", "forest", "轻松"}, {"bloom", "春日拉格泰姆", "bloom", "古典"},
		{"moon", "月光下的琴键", "moon", "器乐"}, {"city", "城市留声机", "city", "古典"},
		{"waves", "随海浪摇摆", "waves", "轻松"}, {"paper", "纸上的百年旋律", "paper", "古典"},
	} {
		d.playlists = append(d.playlists, model.Collection{ID: "demo:" + seed.id, ProviderID: "demo", Title: seed.title, CoverURL: "/covers/" + seed.cover + ".svg", Category: seed.category, TrackCount: len(d.recordings), Description: "公开授权演示歌单 · 收录同两首 Scott Joplin 公有领域录音；仅为主题编排，不代表商业歌单或流行榜单。"})
	}
	for _, seed := range []struct{ id, title, cover string }{
		{"chart-ragtime", "拉格泰姆精选", "paper"}, {"chart-piano", "键盘时光", "dusk"}, {"chart-archive", "百年留声", "city"},
	} {
		d.charts = append(d.charts, model.Collection{ID: "demo:" + seed.id, ProviderID: "demo", Title: seed.title, Description: "演示精选顺序 · 同两首公开授权录音，非实时热度榜。", CoverURL: "/covers/" + seed.cover + ".svg", TrackCount: len(d.recordings), Category: "演示精选"})
	}
	return d
}
func (d *Demo) Info(enabled, downloadsEnabled bool) Info {
	status := "ok"
	if !enabled {
		status = "disabled"
	}
	return Info{ID: "demo", Name: "公开授权演示", Description: "Wikimedia Commons 的两首公有领域录音；Scott Joplin 作曲，分别由美国海军陆战队乐队（1906）和 IE（2007）演奏。主题歌单重复使用这两首录音；无商业平台接入。", Enabled: enabled, IsDemo: true, Capabilities: Capabilities{Search: true, Charts: true, Playlists: true, Recommendations: true, Play: true, Download: downloadsEnabled && enabled}, Status: status}
}
func (d *Demo) Tracks() []model.Track {
	out := make([]model.Track, 0, len(d.recordings))
	for _, r := range d.recordings {
		t := r.track
		t.Qualities = append([]string{}, t.Qualities...)
		out = append(out, t)
	}
	return out
}
func (d *Demo) Track(id string) (model.Track, bool) {
	for _, t := range d.Tracks() {
		if t.ID == id {
			return t, true
		}
	}
	return model.Track{}, false
}
func (d *Demo) Playlists(category string) []model.Collection {
	out := make([]model.Collection, 0)
	for _, p := range d.playlists {
		if category == "" || category == "all" || p.Category == category {
			out = append(out, p)
		}
	}
	return out
}
func (d *Demo) Charts() []model.Collection { return append([]model.Collection{}, d.charts...) }
func (d *Demo) Playlist(id string) (model.Collection, bool) {
	for _, p := range d.playlists {
		if p.ID == id {
			p.Tracks = d.Tracks()
			return p, true
		}
	}
	return model.Collection{}, false
}
func (d *Demo) Chart(id string) (model.Collection, bool) {
	for _, p := range d.charts {
		if p.ID == id {
			p.Tracks = d.Tracks()
			if strings.HasSuffix(id, "piano") {
				p.Tracks[0], p.Tracks[1] = p.Tracks[1], p.Tracks[0]
			}
			return p, true
		}
	}
	return model.Collection{}, false
}
func (d *Demo) Daily() Recommendations {
	return Recommendations{Tracks: d.Tracks(), Playlists: d.Playlists("all")[:4]}
}
func EmptySearch() SearchResult {
	return SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}, Artists: []Artist{}, Albums: []Album{}}
}
func (d *Demo) Search(query, kind string) SearchResult {
	out := EmptySearch()
	q := strings.ToLower(strings.TrimSpace(query))
	matches := func(fields ...string) bool { return strings.Contains(strings.ToLower(strings.Join(fields, " ")), q) }
	if kind == "track" {
		for _, t := range d.Tracks() {
			if matches(t.Title, t.Artist, t.Album) {
				out.Tracks = append(out.Tracks, t)
			}
		}
		out.Total = len(out.Tracks)
	}
	if kind == "playlist" {
		for _, p := range d.playlists {
			if matches(p.Title, p.Description, p.Category) {
				out.Playlists = append(out.Playlists, p)
			}
		}
		out.Total = len(out.Playlists)
	}
	for i, t := range d.Tracks() {
		if kind == "artist" && matches(t.Artist) {
			out.Artists = append(out.Artists, Artist{ID: []string{"demo:marine-band", "demo:ie"}[i], Name: t.Artist, CoverURL: t.CoverURL, TrackCount: 1})
		}
		if kind == "album" && matches(t.Album, t.Artist) {
			out.Albums = append(out.Albums, Album{ID: []string{"demo:archive-1906", "demo:keyboard-2007"}[i], Title: t.Album, Artist: t.Artist, CoverURL: t.CoverURL, TrackCount: 1})
		}
	}
	if kind == "artist" {
		out.Total = len(out.Artists)
	}
	if kind == "album" {
		out.Total = len(out.Albums)
	}
	return out
}
func (d *Demo) PlayInfo(id, quality string) (model.PlayInfo, error) {
	if quality != "standard" {
		return model.PlayInfo{}, errors.New("演示音源仅提供 standard（原始 Ogg Vorbis），无 FLAC 或码率保证")
	}
	for _, r := range d.recordings {
		if r.track.ID == id {
			return model.PlayInfo{TrackID: id, URL: r.url, MIMEType: "audio/ogg", Direct: true}, nil
		}
	}
	return model.PlayInfo{}, errors.New("曲目不存在")
}
func (d *Demo) Resolve(ctx context.Context, track model.Track, quality string) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	if track.ProviderID != "demo" {
		return "", errors.New("未知音源")
	}
	info, err := d.PlayInfo(track.ID, quality)
	return info.URL, err
}
func (d *Demo) Lyrics() Lyrics {
	return Lyrics{Lines: []LyricLine{}, Source: "器乐录音，无歌词；不生成或冒充授权歌词。"}
}

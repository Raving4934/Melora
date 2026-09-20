package catalog

import (
	"context"
	"errors"
	"melora/internal/model"
)

// Adapter 只读取固定平台的公开元数据；播放地址仍交给受限LX源。
// 各平台ID必须带平台前缀，榜单与歌单ID需要区分其资源类型。
type Adapter interface {
	Search(context.Context, string, string, int) (SearchResult, error)
	Charts(context.Context) ([]model.Collection, error)
	Chart(context.Context, string) (model.Collection, error)
	Playlists(context.Context, string, int) ([]model.Collection, error)
	Playlist(context.Context, string) (model.Collection, error)
	PlaylistCategories(context.Context) ([]PlaylistCategory, error)
	Track(context.Context, string) (model.Track, error)
	MusicInfo(context.Context, string) (map[string]any, error)
	Lyrics(context.Context, string) (Lyrics, error)
}

type DiscoveryAdapter interface {
	NewTracks(context.Context, string) ([]model.Track, error)
}

// BookAdapter 是可选的听书目录能力（当前仅KW）：专区橱窗、排行榜与有声专辑章节。
// 与 Adapter 相同，只读取公开元数据，不解析媒体地址、不声明播放授权。
type BookAdapter interface {
	BookHome(context.Context) (BookHome, error)
	BookRanks(context.Context) ([]BookRankTab, error)
	BookRank(context.Context, string, string, int) (BookRankResult, error)
	BookAlbum(context.Context, string, int) (BookAlbum, error)
}

var ErrUnsupported = errors.New("此平台暂不支持该目录能力")

func (c *WY) Chart(ctx context.Context, id string) (model.Collection, error) {
	return c.Playlist(ctx, id)
}

var _ Adapter = (*WY)(nil)

// PlaylistPager 是可选能力，适用于公开推荐目录不可翻页等平台差异。
type PlaylistPager interface {
	PlaylistHasMore(category string, page int, items []model.Collection) bool
}

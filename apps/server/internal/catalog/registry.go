package catalog

import (
	"context"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"melora/internal/model"
)

var PlatformOrder = []string{"wy", "tx", "kw", "kg", "mg"}
var PlatformNames = map[string]string{"wy": "网抑云", "tx": "扣扣", "kw": "酷沃", "kg": "酷购", "mg": "米咕"}

type PartialError struct {
	Sources []string
	// 可选的原始平台失败原因，仅供安全分类；不改变既有 Error/Unwrap/IsPartial 语义。
	Causes map[string]error
}

func (e *PartialError) Error() string { return "部分音乐平台暂不可用" }
func (e *PartialError) Unwrap() error { return ErrUnavailable }

type Registry struct {
	metadata      metadataMemory
	metadataStore MetadataStore
	adapters      map[string]Adapter
	ids           []string
	lyricsCache   lyricsCache
}

func NewRegistry(adapters map[string]Adapter) *Registry {
	r := &Registry{adapters: map[string]Adapter{}}
	for _, id := range PlatformOrder {
		if adapter := adapters[id]; adapter != nil {
			r.adapters[id] = adapter
			r.ids = append(r.ids, id)
		}
	}
	return r
}
func (r *Registry) IDs() []string { return append([]string(nil), r.ids...) }
func (r *Registry) Adapter(source string) (Adapter, error) {
	if source == "" {
		source = "wy"
	}
	if a := r.adapters[source]; a != nil {
		return a, nil
	}
	return nil, ErrInput
}
func (r *Registry) selected(source string) ([]string, error) {
	if source == "all" {
		if len(r.ids) == 0 {
			return nil, ErrUnavailable
		}
		return r.IDs(), nil
	}
	if source == "" {
		source = "wy"
	}
	if _, err := r.Adapter(source); err != nil {
		return nil, err
	}
	return []string{source}, nil
}
func (r *Registry) byID(id string) (Adapter, error) {
	source, local, ok := strings.Cut(id, ":")
	if !ok || local == "" || len(id) > 200 || strings.ContainsAny(local, "/\\\x00\r\n") {
		return nil, ErrNotFound
	}
	a, err := r.Adapter(source)
	if err != nil {
		return nil, ErrNotFound
	}
	return a, nil
}

// fanout 最大并行3个平台，整个聚合有11秒预算；按注册顺序合并而非返回竞争顺序。
func fanout[T any](ctx context.Context, r *Registry, source string, call func(context.Context, Adapter) (T, error)) ([]T, error) {
	ids, err := r.selected(source)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, 11*time.Second)
	defer cancel()
	results := make([]T, len(ids))
	failures := make([]error, len(ids))
	sem := make(chan struct{}, 3)
	var wg sync.WaitGroup
	for i, id := range ids {
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				failures[i] = ctx.Err()
				return
			}
			results[i], failures[i] = call(ctx, r.adapters[id])
		}()
	}
	wg.Wait()
	good := []T{}
	failed := []string{}
	causes := map[string]error{}
	for i, result := range results {
		if failures[i] != nil {
			failed = append(failed, ids[i])
			causes[ids[i]] = failures[i]
		} else {
			good = append(good, result)
		}
	}
	if len(failed) == 0 {
		return good, nil
	}
	if len(ids) == 1 {
		return nil, failures[0]
	}
	if len(good) == 0 {
		return nil, ErrUnavailable
	}
	return good, &PartialError{Sources: failed, Causes: causes}
}
func combine[T any](parts [][]T) []T {
	out := []T{}
	for _, part := range parts {
		out = append(out, part...)
	}
	return out
}
func (r *Registry) ChartsFor(ctx context.Context, source string) ([]model.Collection, error) {
	parts, err := fanout(ctx, r, source, func(ctx context.Context, a Adapter) ([]model.Collection, error) { return a.Charts(ctx) })
	return combine(parts), err
}
func (r *Registry) PlaylistsFor(ctx context.Context, source, category string, page int) ([]model.Collection, error) {
	if page < 1 || page > 50 || len(category) > 256 || !utf8.ValidString(category) {
		return nil, ErrInput
	}
	// 分类ID属于具体平台，不能拿WY分类误请求TX/KW等。全部平台仅聚合全部歌单。
	if source == "all" && category != "" && category != "all" && category != "全部" {
		return nil, ErrInput
	}
	parts, err := fanout(ctx, r, source, func(ctx context.Context, a Adapter) ([]model.Collection, error) {
		return a.Playlists(ctx, category, page)
	})
	return combine(parts), err
}
func (r *Registry) CategoriesFor(ctx context.Context, source string) ([]PlaylistCategory, error) {
	if source == "all" {
		return []PlaylistCategory{}, nil
	}
	a, err := r.Adapter(source)
	if err != nil {
		return nil, err
	}
	return a.PlaylistCategories(ctx)
}
func (r *Registry) SearchFor(ctx context.Context, source, query, kind string, page int) (SearchResult, error) {
	out := SearchResult{Tracks: []model.Track{}, Playlists: []model.Collection{}, Artists: []SearchArtist{}, Albums: []SearchAlbum{}, Page: page, PageSize: PageSize}
	if kind == "" {
		kind = "track"
	}
	if page < 1 || page > 50 || !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 {
		return out, ErrInput
	}
	switch kind {
	case "track", "playlist", "artist", "album", "book":
	default:
		return out, ErrInput
	}
	// 有声书目前只有KW目录支持；all 直接落到KW，不向其它平台发起无效搜索。
	if kind == "book" && (source == "" || source == "all") {
		source = "kw"
	}
	ids, err := r.selected(source)
	if err != nil {
		return out, err
	}
	out.PageSize = PageSize * len(ids)
	query = strings.TrimSpace(query)
	if query == "" {
		return out, nil
	}
	results, err := fanout(ctx, r, source, func(ctx context.Context, a Adapter) (SearchResult, error) { return a.Search(ctx, query, kind, page) })
	if len(results) > 0 {
		out.PageSize = 0
	}
	for _, part := range results {
		size := part.PageSize
		if size <= 0 {
			size = PageSize
		}
		out.PageSize += min(size, 100)
		out.Tracks = append(out.Tracks, part.Tracks...)
		out.Playlists = append(out.Playlists, part.Playlists...)
		out.Artists = append(out.Artists, part.Artists...)
		out.Albums = append(out.Albums, part.Albums...)
		out.Total += min(max(part.Total, 0), 1000000)
	}
	// 每个平台第一页的首曲轮流出现，避免首屏全是WY而看起来没有聚合。
	if source == "all" {
		out.Tracks = interleaveResults(results, func(p SearchResult) []model.Track { return p.Tracks })
		out.Playlists = interleaveResults(results, func(p SearchResult) []model.Collection { return p.Playlists })
		out.Artists = interleaveResults(results, func(p SearchResult) []SearchArtist { return p.Artists })
		out.Albums = interleaveResults(results, func(p SearchResult) []SearchAlbum { return p.Albums })
	}
	r.rememberTracks(ctx, out.Tracks)
	return out, err
}
func (r *Registry) NewTracksFor(ctx context.Context, source, area string) ([]model.Track, error) {
	if source == "all" {
		source = "wy"
	} // 发现页不把其它目录伪装成新歌。
	a, err := r.Adapter(source)
	if err != nil {
		return nil, err
	}
	if d, ok := a.(DiscoveryAdapter); ok {
		tracks, err := d.NewTracks(ctx, area)
		if err == nil {
			r.rememberTracks(ctx, tracks)
		}
		return tracks, err
	}
	return nil, ErrUnsupported
}

// BookHome 当前只读取KW听书目录；平台缺失时返回能力不支持而不是伪造空橱窗。
func (r *Registry) BookHome(ctx context.Context) (BookHome, error) {
	a, err := r.Adapter("kw")
	if err != nil {
		return BookHome{}, ErrUnsupported
	}
	books, ok := a.(BookAdapter)
	if !ok {
		return BookHome{}, ErrUnsupported
	}
	return books.BookHome(ctx)
}

// IsBookAlbumID 判断集合 ID 是否属于听书专辑命名空间；当前仅 KW 提供听书目录。
func IsBookAlbumID(id string) bool {
	return strings.HasPrefix(id, "kw:book_album_")
}

func (r *Registry) BookAlbum(ctx context.Context, id string, page int) (BookAlbum, error) {
	a, e := r.byID(id)
	if e != nil {
		return BookAlbum{}, e
	}
	books, ok := a.(BookAdapter)
	if !ok {
		return BookAlbum{}, ErrUnsupported
	}
	item, err := books.BookAlbum(ctx, id, page)
	if err == nil {
		r.rememberTracks(ctx, item.Tracks)
	}
	return item, err
}

func (r *Registry) BookRank(ctx context.Context, tabID, tagID string, page int) (BookRankResult, error) {
	a, err := r.Adapter("kw")
	if err != nil {
		return BookRankResult{}, ErrUnsupported
	}
	books, ok := a.(BookAdapter)
	if !ok {
		return BookRankResult{}, ErrUnsupported
	}
	return books.BookRank(ctx, tabID, tagID, page)
}
func (r *Registry) Track(ctx context.Context, id string) (model.Track, error) {
	a, e := r.byID(id)
	if e != nil {
		return model.Track{}, e
	}
	if cached, ok := r.cachedMetadata(ctx, id); ok {
		return cached.Track, nil
	}
	track, err := a.Track(ctx, id)
	if err == nil {
		r.rememberTracks(ctx, []model.Track{track})
	}
	return track, err
}
func (r *Registry) MusicInfo(ctx context.Context, id string) (map[string]any, error) {
	a, e := r.byID(id)
	if e != nil {
		return nil, e
	}
	if cached, ok := r.cachedMetadata(ctx, id); ok && len(cached.MusicInfo) > 0 {
		return cached.MusicInfo, nil
	}
	music, err := a.MusicInfo(ctx, id)
	if err == nil {
		track, e := r.Track(ctx, id)
		if e == nil {
			item := model.CatalogMetadata{Track: track, MusicInfo: music}
			r.metadata.put(item)
			if r.metadataStore != nil {
				_ = r.metadataStore.SaveCatalogMetadata(ctx, []model.CatalogMetadata{item})
			}
		}
	}
	return music, err
}
func (r *Registry) Playlist(ctx context.Context, id string) (model.Collection, error) {
	a, e := r.byID(id)
	if e != nil {
		return model.Collection{}, e
	}
	item, err := a.Playlist(ctx, id)
	if err == nil {
		r.rememberTracks(ctx, item.Tracks)
	}
	return item, err
}
func (r *Registry) Chart(ctx context.Context, id string) (model.Collection, error) {
	a, e := r.byID(id)
	if e != nil {
		return model.Collection{}, e
	}
	item, err := a.Chart(ctx, id)
	if err == nil {
		r.rememberTracks(ctx, item.Tracks)
	}
	return item, err
}
func (r *Registry) Lyrics(ctx context.Context, id string) (Lyrics, error) {
	a, e := r.byID(id)
	if e != nil {
		return Lyrics{}, e
	}
	return a.Lyrics(ctx, id)
}
func (r *Registry) Charts(ctx context.Context) ([]model.Collection, error) {
	return r.ChartsFor(ctx, "wy")
}
func (r *Registry) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	return r.PlaylistsFor(ctx, "wy", category, page)
}
func (r *Registry) Search(ctx context.Context, query, kind string, page int) (SearchResult, error) {
	return r.SearchFor(ctx, "wy", query, kind, page)
}
func (r *Registry) NewTracks(ctx context.Context, area string) ([]model.Track, error) {
	return r.NewTracksFor(ctx, "wy", area)
}
func (r *Registry) PlaylistCategories(ctx context.Context) ([]PlaylistCategory, error) {
	return r.CategoriesFor(ctx, "wy")
}
func IsPartial(err error) ([]string, bool) {
	var partial *PartialError
	if !errors.As(err, &partial) {
		return nil, false
	}
	ids := append([]string(nil), partial.Sources...)
	sort.Strings(ids)
	return ids, true
}

// PlaylistsHaveMore 尊重平台自身的分页能力，不把聚合后的总条数误当成单页大小。
func (r *Registry) PlaylistsHaveMore(category string, page int, items []model.Collection) bool {
	groups := map[string][]model.Collection{}
	for _, item := range items {
		groups[item.ProviderID] = append(groups[item.ProviderID], item)
	}
	for source, items := range groups {
		if pager, ok := r.adapters[source].(PlaylistPager); ok {
			if pager.PlaylistHasMore(category, page, items) {
				return true
			}
		} else if len(items) >= 24 {
			return true
		}
	}
	return false
}

func interleaveResults[T any](parts []SearchResult, items func(SearchResult) []T) []T {
	out := []T{}
	for row := 0; ; row++ {
		found := false
		for _, part := range parts {
			list := items(part)
			if row < len(list) {
				out = append(out, list[row])
				found = true
			}
		}
		if !found {
			return out
		}
	}
}

package catalog

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"sync"

	"melora/internal/model"
)

// KW听书目录只读取公开橱窗与专辑章节；音频直链仍由 LX 音源解析。
// 上游把有声内容和普通单曲混在同一 RID 空间，这里不把它伪装成音乐专辑或新歌。
const (
	kwBookAlbumPageSize = 100
	kwBookRankPageSize  = 50
	kwBookItemLimit     = 30
	kwBookSectionLimit  = 30
	kwBookTagLimit      = 32
	kwBookVerifyPage    = 50
)

// KW听书当前只保留小说专区；评书与儿童专区不再展示。
var kwBookChannels = []struct {
	ID    string
	Title string
}{
	{"18", "小说"},
}

// KW听书榜单当前展示的分类；顺序固定，内容来自 album/bang/tagList。
var kwBookRankIDs = []string{"13", "20", "1", "14", "15"}

type BookSection struct {
	ID    string             `json:"id"`
	Title string             `json:"title"`
	Items []model.Collection `json:"items"`
}
type BookChannel struct {
	ID       string        `json:"id"`
	Title    string        `json:"title"`
	Sections []BookSection `json:"sections"`
}
type BookRankTag struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}
type BookRankTab struct {
	ID   string        `json:"id"`
	Name string        `json:"name"`
	Tags []BookRankTag `json:"tags"`
}
type BookHome struct {
	Channels []BookChannel `json:"channels"`
	Ranks    []BookRankTab `json:"ranks"`
}

// BookRankResult 是单个子榜的分页专辑列表；TagID 缺省时使用该榜第一个子榜。
type BookRankResult struct {
	Tab      BookRankTab        `json:"tab"`
	TagID    string             `json:"tagId"`
	Page     int                `json:"page"`
	PageSize int                `json:"pageSize"`
	Total    int                `json:"total"`
	Items    []model.Collection `json:"items"`
}

// BookAlbum 在专辑详情上追加章节分页；Collection.Tracks 只放当前页。
type BookAlbum struct {
	model.Collection
	Page     int `json:"page"`
	PageSize int `json:"pageSize"`
	Total    int `json:"total"`
}

// kwGetList 读取 JSON 数组响应；KW专区橱窗不是对象，不能复用 kwGet。
func (k *KW) kwGetList(ctx context.Context, host, path string, params url.Values) ([]kwObject, error) {
	headers := http.Header{"Accept": []string{"application/json"}, "Referer": []string{"https://www.kuwo.cn/"}}
	body, err := catalogRequest(ctx, k.http, http.MethodGet, "https://"+host+path+"?"+params.Encode(), headers, nil)
	if err != nil {
		return nil, catalogIssueError(err)
	}
	var response []kwObject
	if json.Unmarshal(body, &response) != nil || response == nil || len(response) > 64 {
		return nil, kwErrUnavailable
	}
	return response, nil
}

// BookHome 聚合固定听书专区橱窗与听书榜单；单个专区失败不拖垮整页。
// 上游请求并发执行，整页最坏等待一次目录超时而不是叠加。
func (k *KW) BookHome(ctx context.Context) (BookHome, error) {
	out := BookHome{Channels: []BookChannel{}, Ranks: []BookRankTab{}}
	channels := make([]BookChannel, len(kwBookChannels))
	found := make([]bool, len(kwBookChannels))
	var ranks []BookRankTab
	var wg sync.WaitGroup
	for i, channel := range kwBookChannels {
		wg.Add(1)
		go func() {
			defer wg.Done()
			sections, err := k.bookChannelSections(ctx, channel.ID)
			if err != nil || len(sections) == 0 {
				return
			}
			channels[i] = BookChannel{ID: "kw:book_" + channel.ID, Title: channel.Title, Sections: sections}
			found[i] = true
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		ranks, _ = k.BookRanks(ctx)
	}()
	wg.Wait()
	for i := range channels {
		if found[i] {
			out.Channels = append(out.Channels, channels[i])
		}
	}
	out.Ranks = ranks
	if len(out.Channels) == 0 && len(out.Ranks) == 0 {
		return out, kwErrUnavailable
	}
	return out, nil
}

func (k *KW) bookChannelSections(ctx context.Context, channelID string) ([]BookSection, error) {
	rows, err := k.kwGetList(ctx, "mobileinterfaces.kuwo.cn", "/er.s", url.Values{
		"type": {"get_pc_qz_data"}, "f": {"web"}, "id": {channelID}, "prod": {"pc"}, "ver": {"1"},
	})
	if err != nil {
		return nil, err
	}
	sections := []BookSection{}
	seen := map[string]bool{}
	for _, row := range rows {
		title := kwClean(kwText(row, "label"), 100)
		items, err := kwObjects(row, "list")
		if err != nil || title == "" || len(items) == 0 {
			continue
		}
		section := BookSection{Title: title, Items: []model.Collection{}}
		for _, item := range items {
			collection, ok := kwBookItem(item)
			if !ok || seen[collection.ID] {
				continue
			}
			seen[collection.ID] = true
			section.Items = append(section.Items, collection)
			if len(section.Items) == kwBookItemLimit {
				break
			}
		}
		if len(section.Items) == 0 {
			continue
		}
		section.ID = "kw:book_" + channelID + "_" + strconv.Itoa(len(sections)+1)
		sections = append(sections, section)
		if len(sections) == kwBookSectionLimit {
			break
		}
	}
	if len(sections) == 0 {
		return nil, kwErrUnavailable
	}
	return sections, nil
}

// kwBookItem 把专区条目映射成通用 Collection：digest=13 是有声专辑。
// 其它类型没有稳定的详情接口，直接跳过而不是给出死链。
func kwBookItem(row kwObject) (model.Collection, bool) {
	id, ok := kwRemoteID(kwText(row, "id"))
	if !ok {
		return model.Collection{}, false
	}
	title := kwClean(kwText(row, "name"), 200)
	if title == "" || kwText(row, "digest") != "13" {
		return model.Collection{}, false
	}
	return model.Collection{
		ID:          "kw:book_album_" + id,
		ProviderID:  "kw",
		Title:       title,
		Description: kwClean(kwText(row, "desc"), 500),
		CoverURL:    kwImage(kwText(row, "img")),
		Category:    "有声专辑",
	}, true
}

// BookRanks 读取听书排行榜分类与子榜；只保留固定白名单，顺序稳定。
func (k *KW) BookRanks(ctx context.Context) ([]BookRankTab, error) {
	response, err := k.kwGet(ctx, "wapi.kuwo.cn", "/openapi/v1/album/bang/tagList", nil)
	if err != nil {
		return nil, err
	}
	if err = kwCheckError(response); err != nil {
		return nil, err
	}
	if err = kwStatus(response, "code"); err != nil {
		return nil, err
	}
	ranks := kwBookRanks(response)
	if len(ranks) == 0 {
		return nil, kwErrUnavailable
	}
	return ranks, nil
}

// kwBookRanks 只解析白名单内的榜，并跳过 isShow=0 的子榜和重复子榜。
func kwBookRanks(response kwObject) []BookRankTab {
	data, err := kwChild(response, "data")
	if err != nil {
		return nil
	}
	rows, err := kwObjects(data, "list")
	if err != nil {
		return nil
	}
	index := map[string]kwObject{}
	for _, row := range rows {
		if id, ok := kwRemoteID(kwText(row, "id")); ok {
			index[id] = row
		}
	}
	out := []BookRankTab{}
	for _, id := range kwBookRankIDs {
		row, ok := index[id]
		if !ok {
			continue
		}
		name := kwClean(kwText(row, "name"), 100)
		if name == "" {
			continue
		}
		tags := []BookRankTag{}
		seen := map[string]bool{}
		tagRows, err := kwObjects(row, "tagList")
		if err != nil {
			continue
		}
		for _, tag := range tagRows {
			tagID, ok := kwRemoteID(kwText(tag, "id"))
			if !ok || seen[tagID] {
				continue
			}
			tagName := kwClean(kwText(tag, "name"), 100)
			if tagName == "" {
				continue
			}
			if show, ok := kwCount(tag, "isShow"); ok && show == 0 {
				continue
			}
			seen[tagID] = true
			tags = append(tags, BookRankTag{ID: tagID, Name: tagName})
			if len(tags) == kwBookTagLimit {
				break
			}
		}
		if len(tags) == 0 {
			continue
		}
		out = append(out, BookRankTab{ID: id, Name: name, Tags: tags})
	}
	return out
}

// kwBookPageConsistent 严格核对固定页大小接口的原始行数。非末页必须填满，
// 末页必须等于剩余条目数；超过末页时只接受空列表。
func kwBookPageConsistent(total, offset, pageSize, rawCount int) bool {
	if total < 0 || offset < 0 || pageSize <= 0 || rawCount < 0 {
		return false
	}
	if offset >= total {
		return rawCount == 0
	}
	expected := min(pageSize, total-offset)
	return rawCount == expected
}

// BookRank 读取单个子榜的专辑分页。tagID 缺省取该榜第一个子榜。
func (k *KW) BookRank(ctx context.Context, tabID, tagID string, page int) (BookRankResult, error) {
	out := BookRankResult{Page: page, PageSize: kwBookRankPageSize}
	tab, ok := kwRemoteID(tabID)
	if !ok {
		return out, ErrNotFound
	}
	if page < 1 || page > kwMaxPage {
		return out, ErrInput
	}
	ranks, err := k.BookRanks(ctx)
	if err != nil {
		return out, err
	}
	var meta *BookRankTab
	for i := range ranks {
		if ranks[i].ID == tab {
			meta = &ranks[i]
			break
		}
	}
	if meta == nil {
		return out, ErrNotFound
	}
	tag := meta.Tags[0].ID
	if tagID != "" {
		if tag, ok = kwRemoteID(tagID); !ok {
			return out, ErrNotFound
		}
		match := false
		for _, item := range meta.Tags {
			if item.ID == tag {
				match = true
				break
			}
		}
		if !match {
			return out, ErrNotFound
		}
	}
	response, err := k.kwGet(ctx, "wapi.kuwo.cn", "/openapi/v1/album/bang/dataList", url.Values{
		"tabId": {tab}, "tagId": {tag}, "pn": {strconv.Itoa(page)}, "rn": {strconv.Itoa(kwBookRankPageSize)},
	})
	if err != nil {
		return out, err
	}
	if err = kwCheckError(response); err != nil {
		return out, err
	}
	if err = kwStatus(response, "code"); err != nil {
		return out, err
	}
	data, err := kwChild(response, "data")
	if err != nil {
		return out, err
	}
	total, ok := kwCount(data, "total")
	if !ok {
		return out, kwErrUnavailable
	}
	rows, err := kwObjects(data, "list")
	if err != nil {
		return out, err
	}
	// 榜单声明每页 50 项；上游给更多时按异常响应拒绝，不静默截断。
	if len(rows) > kwBookRankPageSize {
		return out, kwErrUnavailable
	}
	if !kwBookPageConsistent(total, (page-1)*kwBookRankPageSize, kwBookRankPageSize, len(rows)) || !kwPageMatches(data, page, "pn") {
		return out, kwErrUnavailable
	}
	items := []model.Collection{}
	seen := map[string]bool{}
	for _, row := range rows {
		item, ok := kwBookRankItem(row)
		if !ok || seen[item.ID] {
			continue
		}
		seen[item.ID] = true
		items = append(items, item)
	}
	if len(rows) > 0 && len(items) == 0 {
		return out, kwErrUnavailable
	}
	out.Tab = *meta
	out.TagID = tag
	out.Total = total
	out.Items = items
	return out, nil
}

// kwBookRankItem 把榜单条目映射成通用 Collection；album 详情仍走 BookAlbum。
func kwBookRankItem(row kwObject) (model.Collection, bool) {
	id, ok := kwRemoteID(kwText(row, "id"))
	if !ok {
		return model.Collection{}, false
	}
	title := kwClean(kwText(row, "name"), 200)
	if title == "" {
		return model.Collection{}, false
	}
	item := model.Collection{
		ID: "kw:book_album_" + id, ProviderID: "kw", Title: title,
		Artist:      kwClean(kwText(row, "artist"), 200),
		Description: kwClean(kwText(row, "desc"), 500),
		CoverURL:    kwImage(kwText(row, "pic")),
		Category:    "有声专辑",
	}
	if n, ok := kwCount(row, "musicnum"); ok {
		item.TrackCount = n
	}
	if n, err := strconv.ParseInt(kwText(row, "listencnt"), 10, 64); err == nil && n >= 0 {
		item.PlayCount = &n
	}
	return item, true
}

// BookAlbum 读取有声专辑的章节分页；目录字段与搜索接口共用 kwSong/kwSongs 映射。
// 独立 book_album_ 命名空间之外，还要用长音频检索确认该专辑确实属于有声目录，
// 防止伪造 ID 或历史链接把普通音乐专辑标记成有声专辑。
func (k *KW) BookAlbum(ctx context.Context, raw string, page int) (BookAlbum, error) {
	out := BookAlbum{Page: page, PageSize: kwBookAlbumPageSize}
	rid, err := kwParseID(raw, "book_album_")
	if err != nil {
		return out, err
	}
	if page < 1 || page > kwMaxPage {
		return out, ErrInput
	}
	response, err := k.kwGet(ctx, "wapi.kuwo.cn", "/api/www/album/albumInfo", url.Values{
		"albumId": {rid}, "pn": {strconv.Itoa(page)}, "rn": {strconv.Itoa(kwBookAlbumPageSize)}, "httpsStatus": {"1"},
	})
	if err != nil {
		return out, err
	}
	// code=-1 且 msg=没有此专辑 表示条目已下架，不是平台故障；其它错误仍按不可用处理。
	if kwText(response, "code") == "-1" && kwText(response, "msg") == "没有此专辑" {
		return out, ErrNotFound
	}
	if err = kwCheckError(response); err != nil {
		return out, err
	}
	if err = kwStatus(response, "code"); err != nil {
		return out, err
	}
	data, err := kwChild(response, "data")
	if err != nil {
		return out, err
	}
	actual, ok := kwRemoteID(kwText(data, "albumid", "albumId"))
	if !ok || actual != rid {
		return out, ErrNotFound
	}
	title := kwClean(kwText(data, "album"), 200)
	if title == "" {
		return out, kwErrUnavailable
	}
	total, ok := kwCount(data, "total")
	if !ok {
		return out, kwErrUnavailable
	}
	rows, err := kwObjects(data, "musicList")
	if err != nil {
		return out, err
	}
	// 章节同样声明每页 100 条；多余行或与 total/page 不一致的响应按异常拒绝。
	if len(rows) > kwBookAlbumPageSize {
		return out, kwErrUnavailable
	}
	if !kwBookPageConsistent(total, (page-1)*kwBookAlbumPageSize, kwBookAlbumPageSize, len(rows)) {
		return out, kwErrUnavailable
	}
	if err := k.kwBookAlbumClassified(ctx, rid, title); err != nil {
		return out, err
	}
	tracks, err := kwSongs(rows, kwBookAlbumPageSize)
	if err != nil {
		return out, err
	}
	out.Collection = model.Collection{
		ID: raw, ProviderID: "kw", Title: title,
		Artist:      kwClean(kwText(data, "artist"), 200),
		Description: kwClean(kwText(data, "albuminfo"), 2000),
		CoverURL:    kwImage(kwText(data, "pic", "albumpic", "img")),
		TrackCount:  total,
		PlayCount:   rawPlayCount(data, "playCnt", "playcnt", "playnum"),
		Category:    "有声专辑",
		Tracks:      tracks,
	}
	out.Total = total
	return out, nil
}

// kwBookAlbumClassified 用长音频过滤检索同名专辑并要求同 ID 命中；
// 普通音乐专辑不会出现在 show_series_listen 结果中，因此返回 ErrNotFound。
func (k *KW) kwBookAlbumClassified(ctx context.Context, rid, title string) error {
	response, err := k.kwGet(ctx, "search.kuwo.cn", "/r.s", url.Values{
		"all": {title}, "ft": {"album"}, "show_series_listen": {"1"},
		"pn": {"0"}, "rn": {strconv.Itoa(kwBookVerifyPage)}, "rformat": {"json"},
		"encoding": {"utf8"}, "client": {"kt"}, "mobi": {"1"}, "newver": {"1"},
	})
	if err != nil {
		return err
	}
	if err = kwCheckError(response); err != nil {
		return err
	}
	rows, err := kwObjects(response, "albumlist")
	if err != nil {
		return err
	}
	for _, row := range rows {
		if id, ok := kwRemoteID(kwText(row, "albumid", "id")); ok && id == rid {
			return nil
		}
	}
	return ErrNotFound
}

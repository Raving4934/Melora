package catalog

import (
	"context"
	"net/url"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

const maxNewTracks = 100
const maxPlaylistCategories = 256

// PlaylistCategory 的普通 ID 是WY分类名，可直接作为 Playlists 的 category。
// all 对应上游 all 节点，沿用 Playlists 已有的全部分类别名；不自建分类或分组。
type PlaylistCategory struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Group string `json:"group"`
}

// NewTracks 读取真实的新歌目录，不把热门歌单、榜单或 Demo 伪装成新歌推荐。
// 地区编号已经通过公开接口验证；仍由同一安全客户端和有界缓存负责读取。
func (c *WY) NewTracks(ctx context.Context, area string) ([]model.Track, error) {
	if area == "" {
		area = "all"
	}
	areas := map[string]string{"all": "0", "zh": "7", "western": "96", "jp": "8", "kr": "16"}
	areaID, ok := areas[area]
	if !ok {
		return nil, ErrInput
	}
	var response struct {
		Data []Song `json:"data"`
	}
	if err := c.get(ctx, "/api/v1/discovery/new/songs", url.Values{"areaId": {areaID}}, &response); err != nil {
		return nil, err
	}
	if response.Data == nil {
		return nil, ErrUnavailable
	}
	tracks := c.convertAll(response.Data, maxNewTracks)
	if len(response.Data) > 0 && len(tracks) == 0 {
		return nil, ErrUnavailable
	}
	return tracks, nil
}

func categoryName(raw string, limit int) string {
	value := strings.TrimSpace(raw)
	if value == "" || !utf8.ValidString(value) || utf8.RuneCountInString(value) > limit || strings.IndexFunc(value, unicode.IsControl) >= 0 || clean(value, limit) != value {
		return ""
	}
	return value
}

func (c *WY) PlaylistCategories(ctx context.Context) ([]PlaylistCategory, error) {
	type entry struct {
		Name     string `json:"name"`
		Category *int   `json:"category"`
	}
	var response struct {
		All        *entry            `json:"all"`
		Categories map[string]string `json:"categories"`
		Sub        []entry           `json:"sub"`
	}
	if err := c.get(ctx, "/api/playlist/catalogue", nil, &response); err != nil {
		return nil, err
	}
	if response.Sub == nil || response.Categories == nil {
		return nil, ErrUnavailable
	}
	out := make([]PlaylistCategory, 0, min(len(response.Sub)+1, maxPlaylistCategories))
	seen := map[string]bool{}
	if response.All != nil {
		if name := categoryName(response.All.Name, 40); name != "" {
			out = append(out, PlaylistCategory{ID: "all", Name: name})
			seen["all"] = true
		}
	}
	for _, item := range response.Sub {
		name := categoryName(item.Name, 40)
		if name == "" || seen[name] || item.Category == nil {
			continue
		}
		group := categoryName(response.Categories[strconv.Itoa(*item.Category)], 80)
		if group == "" {
			continue
		}
		seen[name] = true
		out = append(out, PlaylistCategory{ID: name, Name: name, Group: group})
		if len(out) == maxPlaylistCategories {
			break
		}
	}
	if len(out) == 0 {
		return nil, ErrUnavailable
	}
	return out, nil
}

package provider

import (
	"context"
	"melora/internal/model"
)

// Provider 是受信任、编译期 Adapter 的业务边界，不是任意代码沙箱。
// 首版仅注册 Demo。新平台还需要扩展注册表、授权配置与对应能力检查，
// 不得直接把用户上传的脚本加载进本机进程。
type Provider interface {
	Info(enabled, downloadsEnabled bool) Info
	Tracks() []model.Track
	Track(id string) (model.Track, bool)
	Playlists(category string) []model.Collection
	Playlist(id string) (model.Collection, bool)
	Charts() []model.Collection
	Chart(id string) (model.Collection, bool)
	Daily() Recommendations
	Search(query, kind string) SearchResult
	PlayInfo(id, quality string) (model.PlayInfo, error)
	Resolve(context.Context, model.Track, string) (string, error)
	Lyrics() Lyrics
}

var _ Provider = (*Demo)(nil)

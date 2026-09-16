package provider

import (
	"context"
	"errors"
	"sync/atomic"

	"melora/internal/catalog"
	"melora/internal/lxsource"
	"melora/internal/model"
)

var ErrQuality = errors.New("当前音源不支持所选音质，请重新选择音质或切换音源")
var ErrMediaURL = errors.New("音源未返回有效的公网媒体地址")

type Live struct {
	Catalog *catalog.Registry
	Sources *lxsource.Manager
	// 包内测试可替换；零值使用netguard严格HTTPS HEAD，不能由API/脚本配置。
	verifyPlaybackHTTPS func(context.Context, string) (string, error)
	// 零值即开启，兼容构造器与已有结构体字面量，并与 Settings 默认值一致。
	autoSwitchDisabled atomic.Bool
}

func NewLive(sources *lxsource.Manager, client *catalog.WY) *Live {
	return &Live{Sources: sources, Catalog: catalog.NewAllRegistry(client)}
}

// SetAutoSwitch 更新能力展示与旧便捷解析方法的默认行为；WithOptions 使用 API 的请求快照。
func (l *Live) SetAutoSwitch(enabled bool) { l.autoSwitchDisabled.Store(!enabled) }
func (l *Live) AutoSwitchEnabled() bool    { return !l.autoSwitchDisabled.Load() }
func (l *Live) Qualities(platform string) []string {
	if l.Sources == nil {
		return []string{}
	}
	ordered := orderedSources(l.Sources.List(), ResolveOptions{AutoSwitch: l.AutoSwitchEnabled()})
	out := []string{}
	seen := map[string]bool{}
	for _, source := range ordered {
		for _, quality := range sourceQualities(source, platform) {
			if !seen[quality] {
				out = append(out, quality)
				seen[quality] = true
			}
		}
	}
	return out
}
func (l *Live) Enrich(track model.Track) model.Track {
	track.CoverURL = catalog.NormalizeCoverURL(track.CoverURL)
	if track.ProviderID == "demo" {
		track.CanDownload = false
		track.Qualities = []string{}
		return track
	}
	track.Qualities = l.Qualities(track.ProviderID)
	track.CanDownload = len(track.Qualities) > 0
	return track
}
func (l *Live) EnrichTracks(tracks []model.Track) []model.Track {
	out := make([]model.Track, len(tracks))
	for i, t := range tracks {
		out[i] = l.Enrich(t)
	}
	return out
}
func (l *Live) EnrichCollection(p model.Collection) model.Collection {
	p.CoverURL = catalog.NormalizeCoverURL(p.CoverURL)
	if p.Tracks != nil {
		p.Tracks = l.EnrichTracks(p.Tracks)
	}
	return p
}
func (l *Live) Info(downloads bool) Info { return l.InfoFor("wy", downloads) }
func (l *Live) InfoFor(platform string, downloads bool) Info {
	playable := len(l.Qualities(platform)) > 0
	return Info{ID: platform, Name: catalog.PlatformNames[platform], Description: "平台真实音乐目录；播放地址由当前LX音源解析", Enabled: true, Status: "catalog", Capabilities: Capabilities{Search: true, Charts: true, Playlists: true, Recommendations: platform == "wy", Play: playable, Download: playable && downloads}}
}
func (l *Live) Infos(downloads bool) []Info {
	out := []Info{}
	for _, id := range l.Catalog.IDs() {
		out = append(out, l.InfoFor(id, downloads))
	}
	return out
}
func (l *Live) Track(ctx context.Context, id string) (model.Track, error) {
	if l.Catalog == nil {
		return model.Track{}, catalog.ErrUnavailable
	}
	t, e := l.Catalog.Track(ctx, id)
	if ctx.Err() != nil {
		return model.Track{}, ctx.Err()
	}
	if e != nil {
		if cache, ok := any(l.Catalog).(trustedTrackCache); ok {
			if cached, found := cache.CachedTrack(id); found && cached.ID == id {
				return l.Enrich(cached), nil
			}
		}
		return t, e
	}
	return l.Enrich(t), nil
}
func (l *Live) ValidateQuality(track model.Track, quality string) (string, error) {
	qualities := l.Qualities(track.ProviderID)
	if len(qualities) == 0 {
		if l.Sources == nil {
			return "", lxsource.ErrNoActive
		}
		if _, ok := l.Sources.Active(); !ok {
			return "", lxsource.ErrNoActive
		}
		return "", lxsource.ErrUnsupported
	}
	if quality == "" || quality == "standard" {
		for _, q := range qualities {
			if q == "128k" {
				return q, nil
			}
		}
		return qualities[0], nil
	}
	for _, q := range qualities {
		if quality == q {
			return q, nil
		}
	}
	return "", ErrQuality
}

// Resolve 保留下载接口的 URL-only 契约；新接口额外返回实际音质与单次源选择。
func (l *Live) Resolve(ctx context.Context, track model.Track, quality string) (string, error) {
	out, err := l.ResolveWithOptions(ctx, track, quality, ResolveOptions{AutoSwitch: l.AutoSwitchEnabled()})
	return out.URL, err
}
func (l *Live) PlayInfo(ctx context.Context, track model.Track, quality string) (model.PlayInfo, error) {
	return l.PlayInfoWithOptions(ctx, track, quality, ResolveOptions{AutoSwitch: l.AutoSwitchEnabled()})
}

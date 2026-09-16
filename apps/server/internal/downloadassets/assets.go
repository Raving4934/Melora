// Package downloadassets 在用户下载时获取有界歌词/封面，不解析或代理音频。
package downloadassets

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"math"
	"melora/internal/catalog"
	"melora/internal/download"
	"melora/internal/model"
	"melora/internal/netguard"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
	"unicode"
)

type lyricGetter func(context.Context, string) (catalog.Lyrics, error)
type coverGetter func(context.Context, string) ([]byte, error)
type trackGetter func(context.Context, string) (model.Track, error)

const assetFetchBudget = 8 * time.Second

var (
	errCoverHTTP   = errors.New("封面使用未获准的 HTTP 地址（cover_http_not_allowed）")
	errCoverPolicy = errors.New("封面地址不符合安全策略（cover_policy_rejected）")
)

// 给 download.fetchAssets 的父 deadline 留余量，不让一份慢素材拖垮已成功的另一份。
func assetContext(parent context.Context) (context.Context, context.CancelFunc) {
	budget := assetFetchBudget
	if deadline, ok := parent.Deadline(); ok {
		remaining := time.Until(deadline)
		reserve := min(time.Second, remaining/4)
		budget = min(budget, remaining-reserve)
	}
	return context.WithTimeout(parent, budget)
}

func Fetcher(registry *catalog.Registry) download.MetadataFetcher {
	var lyrics lyricGetter
	var track trackGetter
	if registry != nil {
		lyrics, track = registry.Lyrics, registry.Track
	}
	return func(ctx context.Context, job model.DownloadJob) (download.MetadataAssets, error) {
		return fetchWithTrack(ctx, job, lyrics, cover, track)
	}
}
func cover(ctx context.Context, raw string) ([]byte, error) {
	if raw == "" {
		return nil, errors.New("歌曲未提供封面（cover_missing）")
	}
	// 保留 Broker 默认禁止明文的策略；不猜测任意旧 URL 的 HTTPS 镜像。
	if parsed, err := url.Parse(raw); err == nil && parsed.Scheme == "http" {
		return nil, errCoverHTTP
	}
	broker, err := netguard.NewBroker(netguard.Options{})
	if err != nil {
		return nil, errors.New("封面请求不可用")
	}
	defer broker.Close()
	response, err := broker.Do(ctx, netguard.Request{URL: raw, Method: http.MethodGet, Headers: map[string]string{"Accept": "image/jpeg,image/png"}, Timeout: 5000})
	if errors.Is(err, netguard.ErrPolicy) {
		return nil, errCoverPolicy
	}
	if err != nil || response.StatusCode != 200 {
		return nil, errors.New("封面下载失败（cover_request_failed）")
	}
	return response.Body, nil
}
func fetch(ctx context.Context, job model.DownloadJob, lyrics lyricGetter, getCover coverGetter) (download.MetadataAssets, error) {
	return fetchWithTrack(ctx, job, lyrics, getCover, nil)
}

func fetchWithTrack(ctx context.Context, job model.DownloadJob, lyrics lyricGetter, getCover coverGetter, getTrack trackGetter) (download.MetadataAssets, error) {
	if !job.WriteLyrics && !job.WriteCover && !job.EmbedTags {
		return download.MetadataAssets{}, nil
	}
	ctx, cancel := assetContext(ctx)
	defer cancel()
	if ctx.Err() != nil {
		return download.MetadataAssets{}, errors.New("素材获取已取消或超时（assets_deadline）")
	}
	var out download.MetadataAssets
	var lyricErr, coverErr error
	var wg sync.WaitGroup
	if job.WriteLyrics || job.EmbedTags {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if lyrics == nil {
				lyricErr = errors.New("歌词服务不可用（lyrics_unavailable）")
				return
			}
			value, err := lyrics(ctx, job.Track.ID)
			if ctx.Err() != nil {
				lyricErr = errors.New("歌词获取已取消或超时（lyrics_deadline）")
				return
			}
			if err != nil || len(value.Lines) == 0 {
				lyricErr = errors.New("歌词获取失败或暂无歌词（lyrics_unavailable）")
				return
			}
			var text strings.Builder
			for _, line := range value.Lines {
				if ctx.Err() != nil {
					lyricErr = errors.New("歌词处理已取消或超时（lyrics_deadline）")
					return
				}
				if math.IsNaN(line.Time) || math.IsInf(line.Time, 0) || line.Time < 0 || line.Time > 86400 || len(line.Text) > 8192 {
					continue
				}
				// 每个目录歌词行只写一条LRC记录，不允许内容注入控制字符或额外时间行。
				content := strings.Map(func(r rune) rune {
					if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
						return ' '
					}
					return r
				}, line.Text)
				centiseconds := int(line.Time * 100)
				fmt.Fprintf(&text, "[%02d:%02d.%02d]%s\n", centiseconds/6000, (centiseconds/100)%60, centiseconds%100, content)
				if text.Len() > 1<<20 {
					lyricErr = errors.New("歌词超过大小限制")
					return
				}
			}
			if text.Len() == 0 {
				lyricErr = errors.New("没有可保存的歌词")
				return
			}
			out.Lyrics = text.String()
		}()
	}
	if job.WriteCover || job.EmbedTags {
		wg.Add(1)
		go func() {
			defer wg.Done()
			rawURL := job.Track.CoverURL
			// 仅任务快照确实缺图时补一次同 ID 详情；不刷新所有任务、不跨平台按歌名搜索。
			if rawURL == "" && job.Track.ID != "" && getTrack != nil {
				track, err := getTrack(ctx, job.Track.ID)
				if ctx.Err() != nil {
					coverErr = errors.New("封面详情获取已取消或超时（cover_deadline）")
					return
				}
				if err != nil || track.ID != job.Track.ID {
					coverErr = errors.New("无法取得同一歌曲的封面详情（cover_detail_unavailable）")
					return
				}
				rawURL = track.CoverURL
			}
			normalized := catalog.NormalizeCoverURL(rawURL)
			// 已知失效且未核验替代资源时不访问原地址；保留部分成功语义。
			if rawURL != "" && normalized == "" {
				coverErr = errors.New("封面地址暂不可用")
				return
			}
			if getCover == nil {
				coverErr = errors.New("封面服务不可用（cover_unavailable）")
				return
			}
			raw, err := getCover(ctx, normalized)
			if ctx.Err() != nil {
				coverErr = errors.New("封面获取已取消或超时（cover_deadline）")
				return
			}
			if err != nil {
				switch {
				case errors.Is(err, errCoverHTTP):
					coverErr = errCoverHTTP
				case errors.Is(err, errCoverPolicy):
					coverErr = errCoverPolicy
				default:
					coverErr = errors.New("封面获取失败（cover_request_failed）")
				}
				return
			}
			if len(raw) == 0 || len(raw) > 1<<20 {
				coverErr = errors.New("封面为空或超过大小限制（cover_size_limit）")
				return
			}
			config, format, err := image.DecodeConfig(bytes.NewReader(raw))
			if err != nil || (format != "jpeg" && format != "png") || config.Width < 1 || config.Height < 1 || config.Width > 4096 || config.Height > 4096 {
				coverErr = errors.New("封面不是有效的JPEG/PNG图片")
				return
			}
			out.Cover = append([]byte(nil), raw...)
			out.CoverMIME = "image/" + format
		}()
	}
	wg.Wait()
	return out, errors.Join(lyricErr, coverErr)
}

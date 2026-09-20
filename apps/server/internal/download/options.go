package download

import (
	"context"
	"errors"

	"melora/internal/model"
)

type MetadataAssets struct {
	Lyrics    string
	Cover     []byte
	CoverMIME string
}

// 回调只获取有界素材，不写文件；必须响应 context，不返回音频或依赖管理器锁。
type MetadataFetcher func(context.Context, model.DownloadJob) (MetadataAssets, error)

type downloadOptions struct {
	format              string
	lyrics, cover, tags bool
}

func validNameFormat(format string) bool {
	return format == "title-artist" || format == "artist-title" || format == "title"
}
func (m *Manager) SetOptions(settings model.Settings) error {
	format := settings.FileNameFormat
	if format == "" {
		format = "title-artist"
	}
	if !validNameFormat(format) {
		return errors.New("文件命名方式无效")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closing {
		return errClosed
	}
	m.options = downloadOptions{format, settings.WriteLyrics, settings.WriteCover, settings.EmbedTags}
	return nil
}
func (m *Manager) SetMetadataFetcher(fetch MetadataFetcher) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closing {
		return errClosed
	}
	m.metadataFetcher = fetch
	return nil
}
func baseStem(job model.DownloadJob) string {
	switch job.FileNameFormat {
	case "title-artist":
		return safeName(job.Track.Title) + " - " + safeName(job.Track.Artist)
	case "artist-title":
		return safeName(job.Track.Artist) + " - " + safeName(job.Track.Title)
	case "title":
		return safeName(job.Track.Title)
	default:
		return safeName(job.Track.Artist) + " - " + safeName(job.Track.Title) + " [" + shortQuality(job.Quality) + "] - " + job.ID
	}
}

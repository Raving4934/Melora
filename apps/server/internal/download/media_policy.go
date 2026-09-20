package download

import (
	"context"
	"os"
	"strings"
)

// 仅持久化白名单音频格式类别，不保存上游原始MIME、URL或任意参数。
func rememberMediaType(formats []string, mediaType string) []string {
	for _, extension := range []string{".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav"} {
		if !matchingMIME(mediaType, extension) {
			continue
		}
		// 通用二进制同时匹配所有格式，不把它当成来源对格式的声明。
		if matchingMIME(mediaType, ".mp3") && matchingMIME(mediaType, ".flac") {
			return formats
		}
		for _, old := range formats {
			if old == extension {
				return formats
			}
		}
		return append(formats, extension)
	}
	return formats
}
func mimeConflict(meta partialMeta) bool {
	for _, extension := range meta.ReportedFormats {
		if extension != meta.Extension {
			return true
		}
	}
	return false
}
func prepareMedia(ctx context.Context, file *os.File, meta *partialMeta) error {
	info, err := file.Stat()
	if err != nil {
		return errFile
	}
	if info.Size() != meta.Total || info.Size() <= 0 {
		return errSize
	}
	actual, offset, err := fileFormat(file, meta.Total)
	if err != nil {
		return err
	}
	// 允许单个有界ID3标签之后的真实FLAC/MP3纠正初始猜测，其他变型不盲目扩展。
	changed := actual != meta.Extension
	probe := *meta
	probe.Extension = actual
	if changed || mimeConflict(probe) || offset > 0 && actual == ".flac" {
		if err := confirmMedia(ctx, file, meta.Total, actual); err != nil {
			return err
		}
	}
	meta.Extension = actual
	return nil
}
func mediaWarning(meta partialMeta) string {
	if !mimeConflict(meta) {
		return ""
	}
	switch meta.Extension {
	case ".mp3", ".flac":
		return "来源 MIME 声明与音频内容不一致，已通过结构核验并按 " + strings.ToUpper(strings.TrimPrefix(meta.Extension, ".")) + " 格式保存；文件格式不代表实测音质（media_mime_corrected）"
	default:
		return ""
	}
}

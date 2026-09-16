// Package audiotags 有界重写文件级标签，音频帧逐字节复制，不解码/转码音频。
// 调用方必须使用不同的源/目标文件，失败时丢弃目标而保留源文件。
package audiotags

import (
	"bytes"
	"context"
	"errors"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"mime"
	"unicode"
	"unicode/utf8"
)

const MaxLyricsBytes = 2 << 20
const MaxCoverBytes = 10 << 20
const MaxMetadataBytes = 32 << 20

var ErrUnsupported = errors.New("不支持此音频容器或标签结构")
var ErrInvalid = errors.New("音频或标签数据无效")
var ErrLimit = errors.New("标签数据超出大小限制")

type Tags struct {
	Title, Artist, Album, Lyrics string
	Cover                        []byte
	CoverMIME                    string
}
type Result struct{ Size int64 }

type output struct {
	ctx  context.Context
	dst  io.Writer
	size int64
}

func (o *output) Write(p []byte) (int, error) {
	if err := o.ctx.Err(); err != nil {
		return 0, err
	}
	n, err := o.dst.Write(p)
	o.size += int64(n)
	if err == nil && n != len(p) {
		err = io.ErrShortWrite
	}
	return n, err
}
func readAt(src io.ReaderAt, offset int64, n int) ([]byte, error) {
	if offset < 0 || n < 0 || n > MaxMetadataBytes {
		return nil, ErrLimit
	}
	b := make([]byte, n)
	if _, err := src.ReadAt(b, offset); err != nil {
		return nil, ErrInvalid
	}
	return b, nil
}
func copyRange(out *output, src io.ReaderAt, start, n int64) error {
	if start < 0 || n < 0 {
		return ErrInvalid
	}
	reader := io.NewSectionReader(src, start, n)
	buf := make([]byte, 64<<10)
	copied, err := io.CopyBuffer(out, reader, buf)
	if err != nil {
		return err
	}
	if copied != n {
		return ErrInvalid
	}
	return nil
}
func validText(s string, limit int, lines bool) bool {
	if len(s) > limit || !utf8.ValidString(s) {
		return false
	}
	for _, r := range s {
		if r == 0 || unicode.IsControl(r) && !(lines && (r == '\n' || r == '\r' || r == '\t')) || unicode.Is(unicode.Cf, r) {
			return false
		}
	}
	return true
}

// ValidateCover 只接受匹配真实文件头的 JPEG/PNG，限制压缩大小与像素数，不解码整张图片。
func ValidateCover(data []byte, rawMIME string) (string, image.Config, error) {
	if len(data) == 0 || len(data) > MaxCoverBytes {
		return "", image.Config{}, ErrLimit
	}
	config, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || config.Width <= 0 || config.Height <= 0 || config.Width > 8192 || config.Height > 8192 || int64(config.Width)*int64(config.Height) > 32_000_000 {
		return "", image.Config{}, ErrInvalid
	}
	actual := "image/" + format
	if format != "jpeg" && format != "png" {
		return "", image.Config{}, ErrUnsupported
	}
	requested, _, err := mime.ParseMediaType(rawMIME)
	if err != nil || requested != actual {
		return "", image.Config{}, ErrInvalid
	}
	return actual, config, nil
}
func Write(ctx context.Context, src io.ReaderAt, size int64, dst io.Writer, format string, tags Tags) (Result, error) {
	if src == nil || dst == nil || size < 4 {
		return Result{}, ErrInvalid
	}
	for _, s := range []string{tags.Title, tags.Artist, tags.Album} {
		if !validText(s, 4096, false) {
			return Result{}, ErrInvalid
		}
	}
	if tags.Title == "" || !validText(tags.Lyrics, MaxLyricsBytes, true) {
		return Result{}, ErrInvalid
	}
	if len(tags.Cover) > 0 {
		if _, _, err := ValidateCover(tags.Cover, tags.CoverMIME); err != nil {
			return Result{}, err
		}
	}
	out := &output{ctx: ctx, dst: dst}
	var err error
	switch format {
	case ".mp3":
		err = writeMP3(out, src, size, tags)
	case ".flac":
		err = writeFLAC(out, src, size, tags)
	case ".m4a":
		err = writeM4A(out, src, size, tags)
	default:
		err = ErrUnsupported
	}
	if err != nil {
		return Result{}, err
	}
	return Result{Size: out.size}, nil
}

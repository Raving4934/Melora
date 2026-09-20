package audiotags

import (
	"bytes"
	"encoding/binary"
	"image/color"
	"io"
	"strings"
	"unicode/utf8"
)

type flacBlock struct {
	kind byte
	data []byte
}

func leField(data []byte) ([]byte, []byte, error) {
	if len(data) < 4 {
		return nil, nil, ErrInvalid
	}
	n := int64(binary.LittleEndian.Uint32(data))
	if n > int64(len(data)-4) {
		return nil, nil, ErrInvalid
	}
	return data[4 : 4+n], data[4+n:], nil
}
func leAppend(out *bytes.Buffer, value []byte) {
	_ = binary.Write(out, binary.LittleEndian, uint32(len(value)))
	out.Write(value)
}
func flacComment(old []byte, t Tags) ([]byte, error) {
	vendor := []byte("Melora")
	existing := [][]byte{}
	if old != nil {
		v, rest, err := leField(old)
		if err != nil || !utf8.Valid(v) || len(rest) < 4 {
			return nil, ErrInvalid
		}
		vendor = v
		count := binary.LittleEndian.Uint32(rest)
		rest = rest[4:]
		if count > 65536 {
			return nil, ErrLimit
		}
		for range count {
			entry, next, err := leField(rest)
			if err != nil || !utf8.Valid(entry) {
				return nil, ErrInvalid
			}
			existing = append(existing, entry)
			rest = next
		}
		if len(rest) != 0 {
			return nil, ErrInvalid
		}
	}
	updates := []string{"TITLE=" + t.Title}
	replace := map[string]bool{"TITLE": true}
	for _, f := range []struct{ key, value string }{{"ARTIST", t.Artist}, {"ALBUM", t.Album}, {"LYRICS", t.Lyrics}} {
		if f.value != "" {
			updates = append(updates, f.key+"="+f.value)
			replace[f.key] = true
		}
	}
	fields := [][]byte{}
	for _, entry := range existing {
		key, _, ok := strings.Cut(string(entry), "=")
		if !ok {
			return nil, ErrInvalid
		}
		if !replace[strings.ToUpper(key)] {
			fields = append(fields, entry)
		}
	}
	for _, v := range updates {
		fields = append(fields, []byte(v))
	}
	var out bytes.Buffer
	leAppend(&out, vendor)
	_ = binary.Write(&out, binary.LittleEndian, uint32(len(fields)))
	for _, v := range fields {
		leAppend(&out, v)
	}
	if out.Len() >= 1<<24 {
		return nil, ErrLimit
	}
	return out.Bytes(), nil
}
func flacPicture(t Tags) ([]byte, error) {
	mime, c, err := ValidateCover(t.Cover, t.CoverMIME)
	if err != nil {
		return nil, err
	}
	depth, colors := uint32(24), uint32(0)
	if mime == "image/png" {
		channels := uint32(1)
		switch t.Cover[25] {
		case 2:
			channels = 3
		case 4:
			channels = 2
		case 6:
			channels = 4
		}
		depth = uint32(t.Cover[24]) * channels
		if palette, ok := c.ColorModel.(color.Palette); ok {
			colors = uint32(len(palette))
		}
	} else if c.ColorModel == color.GrayModel {
		depth = 8
	}
	var out bytes.Buffer
	for _, v := range []uint32{3, uint32(len(mime))} {
		_ = binary.Write(&out, binary.BigEndian, v)
	}
	out.WriteString(mime)
	for _, v := range []uint32{0, uint32(c.Width), uint32(c.Height), depth, colors, uint32(len(t.Cover))} {
		_ = binary.Write(&out, binary.BigEndian, v)
	}
	out.Write(t.Cover)
	return out.Bytes(), nil
}
func writeFLAC(out *output, src io.ReaderAt, size int64, t Tags) error {
	marker, err := readAt(src, 0, 4)
	if err != nil || string(marker) != "fLaC" {
		return ErrInvalid
	}
	offset := int64(4)
	blocks := []flacBlock{}
	var comment []byte
	metadataSize := 0
	for i := 0; ; i++ {
		if i >= 4096 {
			return ErrLimit
		}
		header, err := readAt(src, offset, 4)
		if err != nil {
			return err
		}
		n := int(header[1])<<16 | int(header[2])<<8 | int(header[3])
		kind := header[0] & 127
		if kind == 127 || kind == 0 && (i != 0 || n != 34) || i == 0 && (kind != 0 || n != 34) || offset+4+int64(n) > size {
			return ErrInvalid
		}
		metadataSize += n + 4
		if metadataSize > MaxMetadataBytes {
			return ErrLimit
		}
		data, err := readAt(src, offset+4, n)
		if err != nil {
			return err
		}
		offset += int64(n) + 4
		switch kind {
		case 4:
			if comment != nil {
				return ErrInvalid
			}
			comment = data
		case 1: // 丢弃非音频 padding，为新标签腾出空间；其余非目标元数据保持原样。
		case 6:
			if len(data) < 4 {
				return ErrInvalid
			}
			if len(t.Cover) == 0 || binary.BigEndian.Uint32(data) != 3 {
				blocks = append(blocks, flacBlock{kind, data})
			}
		default:
			blocks = append(blocks, flacBlock{kind, data})
		}
		if header[0]&128 != 0 {
			break
		}
	}
	frame, err := readAt(src, offset, 2)
	if err != nil || frame[0] != 255 || frame[1]&252 != 248 {
		return ErrInvalid
	}
	comments, err := flacComment(comment, t)
	if err != nil {
		return err
	}
	blocks = append(blocks, flacBlock{4, comments})
	if len(t.Cover) > 0 {
		picture, err := flacPicture(t)
		if err != nil {
			return err
		}
		blocks = append(blocks, flacBlock{6, picture})
	}
	total := 0
	for _, b := range blocks {
		total += len(b.data) + 4
	}
	if total > MaxMetadataBytes {
		return ErrLimit
	}
	if _, err := out.Write([]byte("fLaC")); err != nil {
		return err
	}
	for i, b := range blocks {
		n := len(b.data)
		if n >= 1<<24 {
			return ErrLimit
		}
		flag := b.kind
		if i == len(blocks)-1 {
			flag |= 128
		}
		if _, err := out.Write([]byte{flag, byte(n >> 16), byte(n >> 8), byte(n)}); err != nil {
			return err
		}
		if _, err := out.Write(b.data); err != nil {
			return err
		}
	}
	return copyRange(out, src, offset, size-offset)
}

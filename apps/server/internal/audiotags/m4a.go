package audiotags

import (
	"bytes"
	"encoding/binary"
	"io"
)

type mp4Box struct {
	kind              string
	start, size, head int64
}

func mp4Header(src io.ReaderAt, start, end int64) (mp4Box, error) {
	b, err := readAt(src, start, 8)
	if err != nil {
		return mp4Box{}, err
	}
	size := int64(binary.BigEndian.Uint32(b))
	head := int64(8)
	if size == 1 {
		extra, err := readAt(src, start+8, 8)
		if err != nil {
			return mp4Box{}, err
		}
		n := binary.BigEndian.Uint64(extra)
		if n > uint64(end-start) {
			return mp4Box{}, ErrInvalid
		}
		size = int64(n)
		head = 16
	} else if size == 0 {
		size = end - start
	}
	if size < head || start+size > end {
		return mp4Box{}, ErrInvalid
	}
	return mp4Box{string(b[4:8]), start, size, head}, nil
}
func mp4Boxes(data []byte) ([]mp4Box, error) {
	var boxes []mp4Box
	src := bytes.NewReader(data)
	for pos := int64(0); pos < int64(len(data)); {
		b, err := mp4Header(src, pos, int64(len(data)))
		if err != nil {
			return nil, err
		}
		// 嵌套 size=0 会把后续新增标签吞入旧 box；不猜测其文件末尾语义。
		// 顶层 mdat 仍可 size=0，由 writeM4A 原样复制，不经过这里。
		if binary.BigEndian.Uint32(data[pos:pos+4]) == 0 {
			return nil, ErrUnsupported
		}
		boxes = append(boxes, b)
		if len(boxes) > 65536 {
			return nil, ErrLimit
		}
		pos += b.size
	}
	return boxes, nil
}
func mp4Atom(kind string, payload []byte) []byte {
	out := make([]byte, 8+len(payload))
	binary.BigEndian.PutUint32(out, uint32(len(out)))
	copy(out[4:8], kind)
	copy(out[8:], payload)
	return out
}
func mp4Tag(kind string, data []byte, typ uint32) []byte {
	value := make([]byte, 8+len(data))
	binary.BigEndian.PutUint32(value, typ)
	copy(value[8:], data)
	return mp4Atom(kind, mp4Atom("data", value))
}
func mp4ILST(old []byte, t Tags) ([]byte, error) {
	updates := []byte{}
	replace := map[string]bool{}
	for _, entry := range []struct{ key, value string }{{"\xa9nam", t.Title}, {"\xa9ART", t.Artist}, {"\xa9alb", t.Album}, {"\xa9lyr", t.Lyrics}} {
		if entry.value != "" {
			updates = append(updates, mp4Tag(entry.key, []byte(entry.value), 1)...)
			replace[entry.key] = true
		}
	}
	if len(t.Cover) > 0 {
		typ := uint32(13)
		if t.CoverMIME == "image/png" {
			typ = 14
		}
		updates = append(updates, mp4Tag("covr", t.Cover, typ)...)
		replace["covr"] = true
	}
	boxes, err := mp4Boxes(old)
	if err != nil {
		return nil, err
	}
	for _, b := range boxes {
		if !replace[b.kind] {
			updates = append(updates, old[b.start:b.start+b.size]...)
		}
	}
	if len(updates) > MaxMetadataBytes {
		return nil, ErrLimit
	}
	return mp4Atom("ilst", updates), nil
}
func mp4Meta(old []byte, t Tags) ([]byte, error) {
	var boxes []mp4Box
	var err error
	if old != nil {
		if len(old) < 4 || !bytes.Equal(old[:4], []byte{0, 0, 0, 0}) {
			return nil, ErrUnsupported
		}
		old = old[4:]
		boxes, err = mp4Boxes(old)
		if err != nil {
			return nil, err
		}
	}
	payload := []byte{0, 0, 0, 0}
	var ilst []byte
	hasHandler := false
	seenILST := false
	for _, b := range boxes {
		body := old[b.start+b.head : b.start+b.size]
		switch b.kind {
		case "hdlr":
			if hasHandler || len(body) < 12 || string(body[8:12]) != "mdir" {
				return nil, ErrUnsupported
			}
			hasHandler = true
			payload = append(payload, old[b.start:b.start+b.size]...)
		case "ilst":
			if seenILST {
				return nil, ErrInvalid
			}
			seenILST = true
			ilst = body
		default:
			payload = append(payload, old[b.start:b.start+b.size]...)
		}
	}
	if !hasHandler {
		handler := make([]byte, 25)
		copy(handler[8:12], "mdir")
		copy(handler[12:16], "appl")
		payload = append(payload, mp4Atom("hdlr", handler)...)
	}
	encoded, err := mp4ILST(ilst, t)
	if err != nil {
		return nil, err
	}
	payload = append(payload, encoded...)
	return mp4Atom("meta", payload), nil
}
func mp4UDTA(old []byte, t Tags) ([]byte, error) {
	boxes, err := mp4Boxes(old)
	if err != nil {
		return nil, err
	}
	payload := []byte{}
	seen := false
	for _, b := range boxes {
		if b.kind == "meta" {
			if seen {
				return nil, ErrInvalid
			}
			seen = true
			meta, err := mp4Meta(old[b.start+b.head:b.start+b.size], t)
			if err != nil {
				return nil, err
			}
			payload = append(payload, meta...)
		} else {
			payload = append(payload, old[b.start:b.start+b.size]...)
		}
	}
	if !seen {
		meta, err := mp4Meta(nil, t)
		if err != nil {
			return nil, err
		}
		payload = append(payload, meta...)
	}
	return mp4Atom("udta", payload), nil
}
func mp4Moov(old []byte, t Tags) ([]byte, error) {
	boxes, err := mp4Boxes(old)
	if err != nil {
		return nil, err
	}
	payload := []byte{}
	seen := false
	for _, b := range boxes {
		if b.kind == "mvex" {
			return nil, ErrUnsupported
		} // 分片/加密/多 mdat 不猜测偏移。
		if b.kind == "udta" {
			if seen {
				return nil, ErrInvalid
			}
			seen = true
			data, err := mp4UDTA(old[b.start+b.head:b.start+b.size], t)
			if err != nil {
				return nil, err
			}
			payload = append(payload, data...)
		} else {
			payload = append(payload, old[b.start:b.start+b.size]...)
		}
	}
	if !seen {
		data, err := mp4UDTA(nil, t)
		if err != nil {
			return nil, err
		}
		payload = append(payload, data...)
	}
	if len(payload) > MaxMetadataBytes {
		return nil, ErrLimit
	}
	return mp4Atom("moov", payload), nil
}

// moov 扩展位于 mdat 前时更新每个 stco/co64；mdat 原始字节完全不动。
func mp4Offsets(data []byte, delta, start, end int64, depth int, hasAudio *bool) (int, error) {
	if depth > 12 {
		return 0, ErrLimit
	}
	boxes, err := mp4Boxes(data)
	if err != nil {
		return 0, err
	}
	tables := 0
	for _, b := range boxes {
		body := data[b.start+b.head : b.start+b.size]
		switch b.kind {
		case "trak", "mdia", "minf", "stbl":
			n, err := mp4Offsets(body, delta, start, end, depth+1, hasAudio)
			if err != nil {
				return 0, err
			}
			tables += n
		case "stco", "co64":
			if len(body) < 8 || !bytes.Equal(body[:4], []byte{0, 0, 0, 0}) {
				return 0, ErrInvalid
			}
			count := int64(binary.BigEndian.Uint32(body[4:8]))
			width := int64(4)
			if b.kind == "co64" {
				width = 8
			}
			if count <= 0 || count*width != int64(len(body)-8) {
				return 0, ErrInvalid
			}
			for i := int64(0); i < count; i++ {
				p := 8 + i*width
				var value uint64
				if width == 4 {
					value = uint64(binary.BigEndian.Uint32(body[p:]))
				} else {
					value = binary.BigEndian.Uint64(body[p:])
				}
				if value < uint64(start) || value >= uint64(end) {
					return 0, ErrUnsupported
				}
				next := int64(value) + delta
				if next < 0 || width == 4 && uint64(next) > 1<<32-1 {
					return 0, ErrLimit
				}
				if width == 4 {
					binary.BigEndian.PutUint32(body[p:], uint32(next))
				} else {
					binary.BigEndian.PutUint64(body[p:], uint64(next))
				}
			}
			tables++
		case "stsd":
			if len(body) < 8 {
				return 0, ErrInvalid
			}
			samples, err := mp4Boxes(body[8:])
			if err != nil {
				return 0, err
			}
			if len(samples) != int(binary.BigEndian.Uint32(body[4:8])) {
				return 0, ErrInvalid
			}
			for _, sample := range samples {
				if sample.size-sample.head < 28 {
					return 0, ErrInvalid
				}
				*hasAudio = true
				if sample.kind != "mp4a" && sample.kind != "alac" {
					return 0, ErrUnsupported
				}
			}
		}
	}
	return tables, nil
}
func writeM4A(out *output, src io.ReaderAt, size int64, t Tags) error {
	var boxes []mp4Box
	var moov, mdat *mp4Box
	hasFTYP := false
	for pos := int64(0); pos < size; {
		b, err := mp4Header(src, pos, size)
		if err != nil {
			return err
		}
		boxes = append(boxes, b)
		if len(boxes) > 4096 {
			return ErrLimit
		}
		switch b.kind {
		case "ftyp":
			hasFTYP = true
		case "moov":
			if moov != nil {
				return ErrUnsupported
			}
			copy := b
			moov = &copy
		case "mdat":
			if mdat != nil {
				return ErrUnsupported
			}
			copy := b
			mdat = &copy
		case "moof", "sidx", "mfra":
			return ErrUnsupported
		}
		pos += b.size
	}
	if !hasFTYP || moov == nil || mdat == nil || mdat.size <= mdat.head {
		return ErrInvalid
	}
	if moov.size > MaxMetadataBytes {
		return ErrLimit
	}
	old, err := readAt(src, moov.start+moov.head, int(moov.size-moov.head))
	if err != nil {
		return err
	}
	encoded, err := mp4Moov(old, t)
	if err != nil {
		return err
	}
	delta := int64(0)
	if moov.start < mdat.start {
		delta = int64(len(encoded)) - moov.size
	}
	hasAudio := false
	tables, err := mp4Offsets(encoded[8:], delta, mdat.start+mdat.head, mdat.start+mdat.size, 0, &hasAudio)
	if err != nil {
		return err
	}
	if tables == 0 || !hasAudio {
		return ErrUnsupported
	}
	for _, b := range boxes {
		if b.kind == "moov" {
			if _, err := out.Write(encoded); err != nil {
				return err
			}
		} else if err := copyRange(out, src, b.start, b.size); err != nil {
			return err
		}
	}
	return nil
}

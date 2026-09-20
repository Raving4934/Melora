package audiotags

import (
	"bytes"
	"encoding/binary"
	"io"
	"unicode/utf16"
)

type id3Frame struct {
	id   string
	data []byte
}

func syncInt(b []byte) (int, bool) {
	if len(b) != 4 {
		return 0, false
	}
	n := 0
	for _, v := range b {
		if v&128 != 0 {
			return 0, false
		}
		n = n<<7 | int(v)
	}
	return n, true
}
func syncBytes(n int) []byte {
	return []byte{byte(n>>21) & 127, byte(n>>14) & 127, byte(n>>7) & 127, byte(n) & 127}
}
func unsync(b []byte) []byte {
	out := make([]byte, 0, len(b))
	for i, v := range b {
		out = append(out, v)
		if v == 255 && (i+1 == len(b) || b[i+1] == 0 || b[i+1] >= 224) {
			out = append(out, 0)
		}
	}
	return out
}
func deunsync(b []byte) []byte {
	out := make([]byte, 0, len(b))
	for i := 0; i < len(b); i++ {
		out = append(out, b[i])
		if b[i] == 255 && i+1 < len(b) && b[i+1] == 0 {
			i++
		}
	}
	return out
}
func id3Text(s string, version byte) []byte {
	if version == 4 {
		return append([]byte{3}, []byte(s)...)
	}
	units := utf16.Encode([]rune(s))
	out := []byte{1, 255, 254}
	for _, u := range units {
		out = append(out, byte(u), byte(u>>8))
	}
	return out
}
func frameID(id []byte) bool {
	if len(id) != 4 {
		return false
	}
	for _, b := range id {
		if !(b >= 'A' && b <= 'Z' || b >= '0' && b <= '9') {
			return false
		}
	}
	return true
}
func readID3(body []byte, version byte) ([]id3Frame, error) {
	out := []id3Frame{}
	for len(body) > 0 {
		if body[0] == 0 {
			for _, b := range body {
				if b != 0 {
					return nil, ErrInvalid
				}
			}
			break
		}
		if len(body) < 10 || !frameID(body[:4]) {
			return nil, ErrInvalid
		}
		size := int(binary.BigEndian.Uint32(body[4:8]))
		if version == 4 {
			var ok bool
			size, ok = syncInt(body[4:8])
			if !ok {
				return nil, ErrInvalid
			}
		}
		if size <= 0 || size > len(body)-10 {
			return nil, ErrInvalid
		}
		payload := append([]byte(nil), body[10:10+size]...)
		if body[8] != 0 {
			return nil, ErrUnsupported
		}
		if version == 3 && body[9] != 0 || version == 4 && body[9]&^byte(2) != 0 {
			return nil, ErrUnsupported
		}
		if version == 4 && body[9]&2 != 0 {
			payload = deunsync(payload)
		}
		out = append(out, id3Frame{string(body[:4]), payload})
		if len(out) > 4096 {
			return nil, ErrLimit
		}
		body = body[10+size:]
	}
	return out, nil
}
func writeMP3(out *output, src io.ReaderAt, size int64, t Tags) error {
	header, err := readAt(src, 0, 10)
	if err != nil {
		return err
	}
	version := byte(3)
	offset := int64(0)
	frames := []id3Frame{}
	if string(header[:3]) == "ID3" {
		version = header[3]
		if (version != 3 && version != 4) || header[4] != 0 || header[5]&^byte(128) != 0 || version == 4 && header[5] != 0 {
			return ErrUnsupported
		}
		n, ok := syncInt(header[6:10])
		if !ok || n > MaxMetadataBytes || int64(n)+10 > size {
			return ErrInvalid
		}
		body, err := readAt(src, 10, n)
		if err != nil {
			return err
		}
		if header[5]&128 != 0 {
			body = deunsync(body)
		}
		frames, err = readID3(body, version)
		if err != nil {
			return err
		}
		offset = int64(n) + 10
	}
	audio, err := readAt(src, offset, 4)
	if err != nil {
		return err
	}
	if audio[0] != 255 || audio[1]&224 != 224 || audio[1]&6 == 0 || (audio[1]>>3)&3 == 1 || audio[2]>>4 == 0 || audio[2]>>4 == 15 || (audio[2]>>2)&3 == 3 {
		return ErrInvalid
	}
	// 统一输出 ID3v2.4：UTF-8 可完整表示 Unicode，逐帧 unsync 的帧长
	// 明确包含编码后的字节，避免旧播放器对 v2.3 全标签 unsync 的歧义。
	version = 4
	replaced := map[string]bool{"TIT2": true}
	fresh := []id3Frame{{"TIT2", id3Text(t.Title, version)}}
	for _, field := range []struct{ id, value string }{{"TPE1", t.Artist}, {"TALB", t.Album}} {
		if field.value != "" {
			fresh = append(fresh, id3Frame{field.id, id3Text(field.value, version)})
			replaced[field.id] = true
		}
	}
	if t.Lyrics != "" {
		text := id3Text(t.Lyrics, version)
		payload := []byte{text[0], 'u', 'n', 'd', 0}
		if version == 3 {
			payload = append(payload, 0)
		}
		payload = append(payload, text[1:]...)
		fresh = append(fresh, id3Frame{"USLT", payload})
		replaced["USLT"] = true
	}
	if len(t.Cover) > 0 {
		data := append([]byte{0}, []byte(t.CoverMIME)...)
		data = append(data, 0, 3, 0)
		data = append(data, t.Cover...)
		fresh = append(fresh, id3Frame{"APIC", data})
		replaced["APIC"] = true
	}
	for _, f := range frames {
		if !replaced[f.id] {
			fresh = append(fresh, f)
		}
	}
	var body bytes.Buffer
	for _, f := range fresh {
		data := f.data
		flags := byte(0)
		if version == 4 {
			encoded := unsync(data)
			if len(encoded) != len(data) {
				data = encoded
				flags = 2
			}
		}
		h := make([]byte, 10)
		copy(h, f.id)
		if version == 4 {
			copy(h[4:8], syncBytes(len(data)))
		} else {
			binary.BigEndian.PutUint32(h[4:8], uint32(len(data)))
		}
		h[9] = flags
		body.Write(h)
		body.Write(data)
		if body.Len() > MaxMetadataBytes {
			return ErrLimit
		}
	}
	encoded := body.Bytes()
	flags := byte(0)
	if version == 3 {
		encoded = unsync(encoded)
		if len(encoded) != body.Len() {
			flags = 128
		}
	}
	if len(encoded) > MaxMetadataBytes {
		return ErrLimit
	}
	tag := []byte{'I', 'D', '3', version, 0, flags}
	tag = append(tag, syncBytes(len(encoded))...)
	if _, err := out.Write(tag); err != nil {
		return err
	}
	if _, err := out.Write(encoded); err != nil {
		return err
	}
	return copyRange(out, src, offset, size-offset)
}

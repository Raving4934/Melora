package audiotags

import (
	"bytes"
	"encoding/binary"
	"errors"
	"testing"
)

// 零长度 box 表示延伸到文件末尾，不能把新增标签悄悄追加到该 box 内。
func TestM4ARejectsNestedUnboundedBoxesBeforeWriting(t *testing.T) {
	ftyp := mp4Atom("ftyp", []byte("M4A \x00\x00\x00\x00M4A "))
	payload := []byte("unchanged audio packets")
	stco := make([]byte, 12)
	binary.BigEndian.PutUint32(stco[4:8], 1)
	binary.BigEndian.PutUint32(stco[8:], uint32(len(ftyp)+8))
	stsd := mp4Atom("stsd", append([]byte{0, 0, 0, 0, 0, 0, 0, 1}, mp4Atom("mp4a", make([]byte, 28))...))
	trak := mp4Atom("trak", mp4Atom("mdia", mp4Atom("minf", mp4Atom("stbl", append(mp4Atom("stco", stco), stsd...)))))
	unbounded := []byte{0, 0, 0, 0, 'f', 'r', 'e', 'e'}
	for name, tail := range map[string][]byte{
		"moov": unbounded,
		"udta": mp4Atom("udta", unbounded),
		"meta": mp4Atom("udta", mp4Atom("meta", append([]byte{0, 0, 0, 0}, unbounded...))),
	} {
		t.Run(name, func(t *testing.T) {
			src := append(append(append([]byte(nil), ftyp...), mp4Atom("mdat", payload)...), mp4Atom("moov", append(append([]byte(nil), trak...), tail...))...)
			before := append([]byte(nil), src...)
			var dst bytes.Buffer
			_, err := Write(t.Context(), bytes.NewReader(src), int64(len(src)), &dst, ".m4a", Tags{Title: "must not disappear"})
			if !errors.Is(err, ErrUnsupported) || dst.Len() != 0 {
				t.Fatalf("unbounded nested box accepted or partially published: %v, bytes=%d", err, dst.Len())
			}
			if !bytes.Equal(src, before) {
				t.Fatal("rejected source changed")
			}
		})
	}
}

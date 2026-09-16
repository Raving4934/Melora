package download

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"net/http"
	"path/filepath"
	"sync/atomic"
	"testing"

	"melora/internal/model"
)

// 标签在音频容器内；.melora-{ID}.json 仅是恢复检查点，不是歌词/封面标签文件。
func v10EmbedOnlyDownload(t *testing.T, ext string, perJob bool) (original, tagged []byte, assets MetadataAssets) {
	t.Helper()
	original, assets = v9MIMEFixture(t, ext), v5Assets(t)
	store := &recordingStore{}
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		res := response(r, 200, original)
		if ext == ".flac" {
			res.Header.Set("Content-Type", "audio/flac")
		}
		return res, nil
	}, nil, store, nil, nil)
	var fetched atomic.Int32
	if err := m.SetMetadataFetcher(func(_ context.Context, j model.DownloadJob) (MetadataAssets, error) {
		fetched.Add(1)
		if !j.EmbedTags || j.WriteLyrics || j.WriteCover {
			t.Errorf("only-embed snapshot lost: %+v", j)
		}
		return assets, nil
	}); err != nil {
		t.Fatal(err)
	}
	settings := model.Settings{EmbedTags: true, WriteLyrics: false, WriteCover: false}
	var j model.DownloadJob
	var err error
	if perJob {
		j, err = m.CreateWithOptions(testTrack("embed-only"), "standard", settings)
	} else {
		if err := m.SetOptions(settings); err != nil {
			t.Fatal(err)
		}
		j, err = m.Create(testTrack("embed-only"), "standard")
	}
	if err != nil {
		t.Fatal(err)
	}
	done := rangeTerminal(t, m, j.ID)
	if done.State != "completed" || !done.TagsWritten || !done.EmbedTags || done.WriteLyrics || done.WriteCover || done.WriteMetadata || done.Warning != "" || done.Error != "" {
		t.Fatalf("only-embed did not succeed: %+v", done)
	}
	if done.LyricsPath != "" || done.CoverPath != "" || done.MetadataPath != "" || done.BytesDone != int64(len(original)) || done.BytesTotal != int64(len(original)) || fetched.Load() != 1 {
		t.Fatalf("external sidecars/fake counters/missing assets: %+v calls=%d", done, fetched.Load())
	}
	saved := store.get(j.ID)
	if !saved.TagsWritten || saved.TargetPath != done.TargetPath || saved.LyricsPath != "" || saved.CoverPath != "" || saved.MetadataPath != "" {
		t.Fatalf("only-embed result not persisted: %+v", saved)
	}
	tagged = readTarget(t, done)
	for _, body := range [][]byte{original, tagged} {
		// 沿用 v9 的帧边界/FLAC 首帧 CRC-8 与元数据有界结构确认，不声称做完整解码。
		if err := confirmMedia(t.Context(), bytes.NewReader(body), int64(len(body)), ext); err != nil {
			t.Fatal(err)
		}
	}
	meta, err := loadMeta(m.files, j.ID)
	if err != nil || !meta.Tagged || meta.Final != filepath.Base(done.TargetPath) || meta.FinalBytes != int64(len(tagged)) || meta.SHA256 != fmt.Sprintf("%x", sha256.Sum256(tagged)) || meta.Lyrics.State != "" || meta.Cover.State != "" {
		t.Fatalf("invalid private recovery checkpoint: %+v %v", meta, err)
	}
	dir, err := m.files.Open(".")
	if err != nil {
		t.Fatal(err)
	}
	defer dir.Close()
	names, err := dir.Readdirnames(-1) // 只枚举本测试专用临时目录。
	if err != nil || len(names) != 2 {
		t.Fatalf("expected audio + private checkpoint only: %v %v", names, err)
	}
	for _, name := range names {
		if name != filepath.Base(done.TargetPath) && name != metaName(j.ID) {
			t.Fatalf("unexpected external .lrc/.jpg/.png/user .json or staging file: %q", name)
		}
	}
	return original, tagged, assets
}

func TestV10EmbedOnlyMP3CreateWithOptions(t *testing.T) {
	original, tagged, assets := v10EmbedOnlyDownload(t, ".mp3", true)
	payload := v5Payload(t, tagged)
	if !bytes.Equal(payload, v5Payload(t, original)) || len(tagged) < 10 || string(tagged[:3]) != "ID3" || tagged[3] != 4 {
		t.Fatal("MP3 audio frames changed or ID3v2.4 absent")
	}
	frames := make(map[string][]byte)
	for body := tagged[10 : len(tagged)-len(payload)]; len(body) != 0; {
		if len(body) < 10 || body[8] != 0 || body[9]&^byte(2) != 0 {
			t.Fatal("invalid ID3 frame header/flags")
		}
		n := 0
		for _, b := range body[4:8] {
			if b > 127 {
				t.Fatal("invalid syncsafe ID3 frame size")
			}
			n = n<<7 | int(b)
		}
		if n > len(body)-10 {
			t.Fatal("truncated ID3 frame")
		}
		data := body[10 : 10+n]
		if body[9]&2 != 0 {
			data = bytes.ReplaceAll(data, []byte{255, 0}, []byte{255})
		}
		frames[string(body[:4])] = data
		body = body[10+n:]
	}
	if !bytes.Equal(frames["USLT"], append([]byte("\x03und\x00"), []byte(assets.Lyrics)...)) {
		t.Fatal("embedded USLT does not contain actual lyrics")
	}
	wantCover := append([]byte("\x00"+assets.CoverMIME+"\x00\x03\x00"), assets.Cover...)
	if !bytes.Equal(frames["APIC"], wantCover) {
		t.Fatal("embedded front-cover APIC does not contain actual PNG")
	}
	if len(frames["TIT2"]) == 0 || len(frames["TPE1"]) == 0 {
		t.Fatal("title/artist tags missing")
	}
}

func v10FLACBlocks(t *testing.T, data []byte) (map[byte][]byte, []byte) {
	t.Helper()
	if len(data) < 4 || string(data[:4]) != "fLaC" {
		t.Fatal("missing FLAC signature")
	}
	blocks := make(map[byte][]byte)
	for offset := 4; ; {
		if len(data)-offset < 4 {
			t.Fatal("truncated FLAC metadata header")
		}
		h := data[offset : offset+4]
		size := int(h[1])<<16 | int(h[2])<<8 | int(h[3])
		offset += 4
		if size > len(data)-offset {
			t.Fatal("truncated FLAC metadata")
		}
		kind := h[0] & 127
		if _, duplicate := blocks[kind]; duplicate {
			t.Fatal("unexpected duplicate fixture metadata block")
		}
		blocks[kind] = data[offset : offset+size]
		offset += size
		if h[0]&128 != 0 {
			return blocks, data[offset:]
		}
	}
}

func v10TagField(t *testing.T, data *[]byte, order binary.ByteOrder) []byte {
	t.Helper()
	if len(*data) < 4 {
		t.Fatal("truncated metadata field size")
	}
	n := uint64(order.Uint32((*data)[:4]))
	*data = (*data)[4:]
	if n > uint64(len(*data)) {
		t.Fatal("truncated metadata field")
	}
	value := (*data)[:int(n)]
	*data = (*data)[int(n):]
	return value
}

func TestV10EmbedOnlyFLACSetOptions(t *testing.T) {
	original, tagged, assets := v10EmbedOnlyDownload(t, ".flac", false)
	before, framesBefore := v10FLACBlocks(t, original)
	after, framesAfter := v10FLACBlocks(t, tagged)
	if len(before[0]) != 34 || !bytes.Equal(before[0], after[0]) || !bytes.Equal(framesBefore, framesAfter) {
		t.Fatal("FLAC STREAMINFO/MD5 or audio frames (including CRC bytes) changed")
	}
	for kind, block := range before {
		if kind != 1 && kind != 4 && kind != 6 && !bytes.Equal(block, after[kind]) {
			t.Fatalf("protected FLAC metadata %d changed", kind)
		}
	}
	comment := after[4]
	_ = v10TagField(t, &comment, binary.LittleEndian) // vendor
	if len(comment) < 4 {
		t.Fatal("missing Vorbis comment count")
	}
	count := binary.LittleEndian.Uint32(comment[:4])
	comment = comment[4:]
	if uint64(count) > uint64(len(comment)/4) {
		t.Fatal("unbounded Vorbis comments")
	}
	foundLyrics := false
	for range count {
		field := v10TagField(t, &comment, binary.LittleEndian)
		if string(field) == "LYRICS="+assets.Lyrics {
			foundLyrics = true
		}
	}
	if !foundLyrics || len(comment) != 0 {
		t.Fatal("actual lyrics absent from FLAC Vorbis comment")
	}
	picture := after[6]
	if len(picture) < 4 || binary.BigEndian.Uint32(picture[:4]) != 3 {
		t.Fatal("missing front-cover PICTURE block")
	}
	picture = picture[4:]
	if string(v10TagField(t, &picture, binary.BigEndian)) != assets.CoverMIME {
		t.Fatal("embedded cover MIME mismatch")
	}
	_ = v10TagField(t, &picture, binary.BigEndian) // description
	if len(picture) < 16 || binary.BigEndian.Uint32(picture[:4]) != 2 || binary.BigEndian.Uint32(picture[4:8]) != 2 {
		t.Fatal("embedded PNG dimensions lost")
	}
	picture = picture[16:] // width/height/depth/palette count
	if !bytes.Equal(v10TagField(t, &picture, binary.BigEndian), assets.Cover) || len(picture) != 0 {
		t.Fatal("PICTURE does not contain actual PNG bytes")
	}
}

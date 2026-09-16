package audiotags

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func testCover(t *testing.T) []byte {
	t.Helper()
	im := image.NewNRGBA(image.Rect(0, 0, 2, 2))
	im.Set(0, 0, color.NRGBA{R: 255, A: 255})
	var b bytes.Buffer
	if err := png.Encode(&b, im); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}
func testTags(t *testing.T) Tags {
	return Tags{Title: "标题 \U0001f30a", Artist: "测试歌手", Album: "测试专辑", Lyrics: "[00:00.00]测试歌词\n[00:01.00]第二句\n", Cover: testCover(t), CoverMIME: "image/png"}
}
func testMP3() []byte {
	b := make([]byte, 417*3)
	for n := 0; n < 3; n++ {
		copy(b[n*417:], []byte{255, 251, 144, 100})
		for i := 4; i < 417; i++ {
			b[n*417+i] = byte(i + n)
		}
	}
	return b
}
func testFLAC() []byte {
	b := append([]byte("fLaC\x80\x00\x00\x22"), make([]byte, 34)...)
	return append(b, []byte{255, 248, 1, 2, 3, 4, 5, 6, 7, 8, 9}...)
}
func mp3Audio(t *testing.T, b []byte) []byte {
	t.Helper()
	if !bytes.HasPrefix(b, []byte("ID3")) {
		return b
	}
	if len(b) < 10 {
		t.Fatal("short ID3")
	}
	n := 0
	for _, v := range b[6:10] {
		if v > 127 {
			t.Fatal("bad syncsafe")
		}
		n = n<<7 | int(v)
	}
	if n+10 > len(b) {
		t.Fatal("truncated ID3")
	}
	return b[n+10:]
}
func flacAudio(t *testing.T, b []byte) []byte {
	t.Helper()
	for p := 4; ; {
		if p+4 > len(b) {
			t.Fatal("bad FLAC blocks")
		}
		n := int(b[p+1])<<16 | int(b[p+2])<<8 | int(b[p+3])
		last := b[p]&128 != 0
		p += 4 + n
		if p > len(b) {
			t.Fatal("bad FLAC length")
		}
		if last {
			return b[p:]
		}
	}
}
func mdatBytes(t *testing.T, b []byte) []byte {
	t.Helper()
	for p := 0; p < len(b); {
		if p+8 > len(b) {
			t.Fatal("bad MP4")
		}
		n := int(binary.BigEndian.Uint32(b[p:]))
		h := 8
		if n == 1 {
			n = int(binary.BigEndian.Uint64(b[p+8:]))
			h = 16
		}
		if n == 0 {
			n = len(b) - p
		}
		if n < h || p+n > len(b) {
			t.Fatal("bad box length")
		}
		if string(b[p+4:p+8]) == "mdat" {
			return b[p+h : p+n]
		}
		p += n
	}
	t.Fatal("missing mdat")
	return nil
}
func TestMP3AndFLACPreserveAudioAndUnknownMetadata(t *testing.T) {
	for _, format := range []string{".mp3", ".flac"} {
		t.Run(format, func(t *testing.T) {
			src := testMP3()
			extract := mp3Audio
			if format == ".flac" {
				src = testFLAC()
				extract = flacAudio
			}
			original := append([]byte(nil), src...)
			var out bytes.Buffer
			r, err := Write(t.Context(), bytes.NewReader(src), int64(len(src)), &out, format, testTags(t))
			if err != nil {
				t.Fatal(err)
			}
			if int64(out.Len()) != r.Size || !bytes.Equal(original, src) || !bytes.Equal(extract(t, src), extract(t, out.Bytes())) {
				t.Fatal("changed audio frames or source")
			}
			var again bytes.Buffer
			tags := testTags(t)
			tags.Title = "新标题"
			if _, err := Write(t.Context(), bytes.NewReader(out.Bytes()), r.Size, &again, format, tags); err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(extract(t, src), extract(t, again.Bytes())) {
				t.Fatal("retag corrupted frames")
			}
			if format == ".flac" && !bytes.Equal(src[8:42], again.Bytes()[8:42]) {
				t.Fatal("STREAMINFO including MD5 changed")
			}
		})
	}
	// 非目标 ID3 TXXX 帧须保留，不能用新标签抹掉原始元数据。
	f := id3Frame{"TXXX", []byte{0, 'k', 0, 'v'}}
	var body bytes.Buffer
	body.WriteString(f.id)
	binary.Write(&body, binary.BigEndian, uint32(len(f.data)))
	body.Write([]byte{0, 0})
	body.Write(f.data)
	src := append(append([]byte{'I', 'D', '3', 3, 0, 0}, syncBytes(body.Len())...), body.Bytes()...)
	src = append(src, testMP3()...)
	var out bytes.Buffer
	if _, err := Write(t.Context(), bytes.NewReader(src), int64(len(src)), &out, ".mp3", Tags{Title: "title"}); err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(out.Bytes(), f.data) || !bytes.Contains(out.Bytes(), []byte("TXXX")) {
		t.Fatal("unknown frame lost")
	}
}
func TestM4AOffsetsBeforeAndAfterMdat(t *testing.T) {
	for _, wide := range []bool{false, true} {
		for _, front := range []bool{false, true} {
			ftyp := mp4Atom("ftyp", []byte("M4A \x00\x00\x00\x00M4A "))
			payload := []byte("original AAC packet bytes")
			table := func(offset uint64) []byte {
				var b bytes.Buffer
				b.Write(make([]byte, 4))
				binary.Write(&b, binary.BigEndian, uint32(1))
				if wide {
					binary.Write(&b, binary.BigEndian, offset)
					return mp4Atom("co64", b.Bytes())
				}
				binary.Write(&b, binary.BigEndian, uint32(offset))
				return mp4Atom("stco", b.Bytes())
			}
			moov := func(offset uint64) []byte {
				return mp4Atom("moov", mp4Atom("trak", mp4Atom("mdia", mp4Atom("minf", mp4Atom("stbl", append(table(offset), mp4Atom("stsd", append([]byte{0, 0, 0, 0, 0, 0, 0, 1}, mp4Atom("mp4a", make([]byte, 28))...))...))))))
			}
			offset := uint64(len(ftyp) + 8)
			if front {
				offset += uint64(len(moov(0)))
			}
			src := append([]byte(nil), ftyp...)
			if front {
				src = append(src, moov(offset)...)
				src = append(src, mp4Atom("mdat", payload)...)
			} else {
				src = append(src, mp4Atom("mdat", payload)...)
				src = append(src, moov(offset)...)
			}
			var out bytes.Buffer
			if _, err := Write(t.Context(), bytes.NewReader(src), int64(len(src)), &out, ".m4a", testTags(t)); err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(mdatBytes(t, src), mdatBytes(t, out.Bytes())) {
				t.Fatal("mdat changed")
			}
			kind := []byte("stco")
			width := 4
			if wide {
				kind = []byte("co64")
				width = 8
			}
			at := bytes.Index(out.Bytes(), kind)
			if at < 0 {
				t.Fatal("lost offset table")
			}
			var chunk uint64
			if width == 4 {
				chunk = uint64(binary.BigEndian.Uint32(out.Bytes()[at+12:]))
			} else {
				chunk = binary.BigEndian.Uint64(out.Bytes()[at+12:])
			}
			if chunk+uint64(len(payload)) > uint64(out.Len()) || !bytes.Equal(out.Bytes()[chunk:chunk+uint64(len(payload))], payload) {
				t.Fatal("chunk offsets were not relocated correctly")
			}
		}
	}
}
func TestRejectMalformedUnsupportedAndBounds(t *testing.T) {
	cases := []struct {
		format string
		data   []byte
	}{{".mp3", []byte("ID3\x03\x00\x00\x7f\x7f\x7f\x7f")}, {".mp3", append([]byte("ID3\x03\x00\x40\x00\x00\x00\x00"), testMP3()...)}, {".flac", []byte("fLaC\x80\xff\xff\xff")}, {".m4a", mp4Atom("ftyp", []byte("M4A "))}, {".wav", []byte("RIFF0000WAVE")}}
	for _, tc := range cases {
		var out bytes.Buffer
		if _, err := Write(t.Context(), bytes.NewReader(tc.data), int64(len(tc.data)), &out, tc.format, Tags{Title: "safe"}); err == nil {
			t.Fatal("malformed/unsupported accepted")
		}
	}
	for _, tags := range []Tags{{Title: "bad\x00"}, {Title: "safe", Lyrics: strings.Repeat("x", MaxLyricsBytes+1)}, {Title: "safe", Cover: make([]byte, MaxCoverBytes+1), CoverMIME: "image/png"}, {Title: "safe", Cover: []byte("<svg/>"), CoverMIME: "image/png"}} {
		var out bytes.Buffer
		src := testMP3()
		if _, err := Write(t.Context(), bytes.NewReader(src), int64(len(src)), &out, ".mp3", tags); err == nil {
			t.Fatal("bad metadata accepted")
		}
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	src := testMP3()
	var out bytes.Buffer
	if _, err := Write(ctx, bytes.NewReader(src), int64(len(src)), &out, ".mp3", Tags{Title: "safe"}); !errors.Is(err, context.Canceled) {
		t.Fatal("lost cancellation")
	}
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, io.ErrClosedPipe }
func TestWriterFailureDoesNotModifySource(t *testing.T) {
	src := testMP3()
	before := sha256.Sum256(src)
	if _, err := Write(t.Context(), bytes.NewReader(src), int64(len(src)), failingWriter{}, ".mp3", Tags{Title: "safe"}); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatal(err)
	}
	if before != sha256.Sum256(src) {
		t.Fatal("source changed")
	}
}
func TestGeneratedAudioFFprobeAndDecodedSamples(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}
	for _, format := range []string{"mp3", "flac", "m4a", "m4a-faststart", "m4a-alac", "m4a-alac-faststart", "mp3-jpeg", "flac-jpeg", "m4a-jpeg"} {
		t.Run(format, func(t *testing.T) {
			dir := t.TempDir()
			extension := strings.SplitN(format, "-", 2)[0]
			srcPath := filepath.Join(dir, "source."+extension)
			dstPath := filepath.Join(dir, "tagged."+extension)
			args := []string{"-v", "error", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=0.15", "-metadata", "title=Previous", "-metadata", "comment=Preserve me"}
			switch extension {
			case "mp3":
				args = append(args, "-c:a", "libmp3lame", "-write_xing", "0", "-id3v2_version", "3")
			case "flac":
				args = append(args, "-c:a", "flac")
			case "m4a":
				codec := "aac"
				if strings.Contains(format, "alac") {
					codec = "alac"
				}
				args = append(args, "-c:a", codec)
			}
			if strings.HasSuffix(format, "faststart") {
				args = append(args, "-movflags", "+faststart")
			}
			args = append(args, srcPath)
			if result, err := exec.Command("ffmpeg", args...).CombinedOutput(); err != nil {
				t.Fatalf("generate: %v %s", err, result)
			}
			src, err := os.ReadFile(srcPath)
			if err != nil {
				t.Fatal(err)
			}
			tags := testTags(t)
			if strings.HasSuffix(format, "jpeg") {
				var cover bytes.Buffer
				if err := jpeg.Encode(&cover, image.NewRGBA(image.Rect(0, 0, 2, 2)), nil); err != nil {
					t.Fatal(err)
				}
				tags.Cover, tags.CoverMIME = cover.Bytes(), "image/jpeg"
			}
			var output bytes.Buffer
			if _, err := Write(t.Context(), bytes.NewReader(src), int64(len(src)), &output, "."+extension, tags); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(dstPath, output.Bytes(), 0600); err != nil {
				t.Fatal(err)
			}
			raw, err := exec.Command("ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "json", dstPath).Output()
			if err != nil {
				t.Fatal(err)
			}
			var probe struct {
				Format struct {
					Tags map[string]string `json:"tags"`
				} `json:"format"`
			}
			if json.Unmarshal(raw, &probe) != nil {
				t.Fatal("bad ffprobe JSON")
			}
			got := map[string]string{}
			for k, v := range probe.Format.Tags {
				got[strings.ToLower(k)] = v
			}
			for key, value := range map[string]string{"title": tags.Title, "artist": tags.Artist, "album": tags.Album, "comment": "Preserve me"} {
				if got[key] != value {
					t.Fatalf("tag %s: %q, wanted %q (%s)", key, got[key], value, raw)
				}
			}
			lyricsOK := false
			for key, value := range got {
				if strings.HasPrefix(key, "lyrics") && value == tags.Lyrics {
					lyricsOK = true
				}
			}
			if !lyricsOK {
				t.Fatalf("lyrics not parsed by ffprobe: %s", raw)
			}
			coverJSON, err := exec.Command("ffprobe", "-v", "error", "-show_entries", "stream=codec_name:stream_disposition=attached_pic", "-of", "json", dstPath).Output()
			if err != nil || !bytes.Contains(coverJSON, []byte(`"attached_pic": 1`)) {
				t.Fatalf("cover not parsed: %s %v", coverJSON, err)
			}
			pcm := func(path string) []byte {
				b, err := exec.Command("ffmpeg", "-v", "error", "-i", path, "-map", "0:a:0", "-f", "s16le", "-").Output()
				if err != nil {
					t.Fatal(err)
				}
				return b
			}
			if !bytes.Equal(pcm(srcPath), pcm(dstPath)) {
				t.Fatal("decoded samples changed")
			}
			var before, after []byte
			switch extension {
			case "mp3":
				before, after = mp3Audio(t, src), mp3Audio(t, output.Bytes())
			case "flac":
				before, after = flacAudio(t, src), flacAudio(t, output.Bytes())
			default:
				before, after = mdatBytes(t, src), mdatBytes(t, output.Bytes())
			}
			if !bytes.Equal(before, after) {
				t.Fatal("encoded audio frames changed")
			}
		})
	}
}
func FuzzTagWriterRejectsCorruptContainers(f *testing.F) {
	f.Add([]byte("ID3\x03\x00\x00\x00\x00\x00\x00"))
	f.Add(testFLAC())
	f.Add([]byte("M4A "))
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) > 65536 {
			return
		}
		before := append([]byte(nil), data...)
		for _, format := range []string{".mp3", ".flac", ".m4a"} {
			var out bytes.Buffer
			_, _ = Write(t.Context(), bytes.NewReader(data), int64(len(data)), &out, format, Tags{Title: "safe"})
		}
		if !bytes.Equal(before, data) {
			t.Fatal("modified source")
		}
	})
}

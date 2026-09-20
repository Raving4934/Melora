package download

import (
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func id3Fixture(body []byte, size int, footer bool) []byte {
	tag := make([]byte, 10+size)
	copy(tag, "ID3\x04\x00\x00")
	if footer {
		tag[5] = 0x10
	}
	tag[6], tag[7], tag[8], tag[9] = byte(size>>21)&127, byte(size>>14)&127, byte(size>>7)&127, byte(size)&127
	if size >= 20 {
		copy(tag[10:], "fLaC-MISLEADING-TAG")
	}
	if footer {
		foot := append([]byte(nil), tag[:10]...)
		copy(foot, "3DI")
		tag = append(tag, foot...)
	}
	return append(tag, body...)
}

func TestMediaV9ID3IsNotAnMP3Signature(t *testing.T) {
	for _, tc := range []struct {
		name, extension, mime string
		size                  int
		footer, warning       bool
	}{
		{"flac-small-matching", ".flac", "audio/flac", 20, false, false},
		{"flac-large-matching", ".flac", "audio/flac", 4096, false, false},
		{"flac-large-conflict", ".flac", "audio/mpeg", 4096, false, true},
		{"mp3-large-matching", ".mp3", "audio/mpeg", 4096, false, false},
		{"mp3-large-conflict", ".mp3", "audio/flac", 4096, false, true},
		{"flac-footer", ".flac", "audio/flac", 100, true, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			body := id3Fixture(v9MIMEFixture(t, tc.extension), tc.size, tc.footer)
			initial := detectFormat(body[:min(512, len(body))])
			if tc.size > 512 && initial != ".audio" {
				t.Fatalf("large tag was guessed as %s", initial)
			}
			m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, body)
				resp.Header.Set("Content-Type", tc.mime)
				return resp, nil
			}, nil, nil, nil, nil)
			j := createJob(t, m, tc.name)
			got := rangeTerminal(t, m, j.ID)
			if got.State != "completed" || filepath.Ext(got.TargetPath) != tc.extension || !bytes.Equal(readTarget(t, got), body) {
				t.Fatalf("ID3 media not preserved: %+v", got)
			}
			if strings.Contains(got.Warning, "media_mime_corrected") != tc.warning {
				t.Fatalf("provisional guess caused false MIME warning: %s", got.Warning)
			}
		})
	}
}

func TestMediaV9MalformedID3AndWrappedFakeFLACRejected(t *testing.T) {
	real := v9MIMEFixture(t, ".flac")
	badFooter := id3Fixture(real, 20, true)
	badFooter[30] = 'x'
	invalidSize := id3Fixture(real, 0, false)
	invalidSize[9] = 128
	unsupportedVersion := id3Fixture(real, 0, false)
	unsupportedVersion[3] = 5
	overLimit := id3Fixture(real, 0, false)
	overLimit[6] = 127
	onlyHeader := id3Fixture(nil, 0, false)
	for _, body := range [][]byte{badFooter, invalidSize, unsupportedVersion, overLimit, onlyHeader, id3Fixture([]byte("fLaC-not-an-audio-stream"), 20, false)} {
		m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
			resp := response(r, 200, body)
			resp.Header.Set("Content-Type", "audio/flac")
			return resp, nil
		}, nil, nil, nil, nil)
		j := createJob(t, m, "invalid-id3")
		got := rangeTerminal(t, m, j.ID)
		if got.State != "failed" {
			t.Fatalf("malformed ID3 published: %+v", got)
		}
		if _, err := os.Stat(got.TargetPath); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("unexpected published audio")
		}
	}
}

type limitedProbeReader struct {
	body           []byte
	reads, maxRead int
}

func (r *limitedProbeReader) ReadAt(p []byte, offset int64) (int, error) {
	r.reads++
	r.maxRead = max(r.maxRead, len(p))
	if len(p) > 64 || offset < 0 {
		return 0, errors.New("unbounded probe")
	}
	return bytes.NewReader(r.body).ReadAt(p, offset)
}
func TestMediaV9ProbeBoundsAndCRC(t *testing.T) {
	real := v9MIMEFixture(t, ".flac")
	source := &limitedProbeReader{body: real}
	if err := confirmMedia(t.Context(), source, int64(len(real)), ".flac"); err != nil {
		t.Fatal(err)
	}
	if source.maxRead > 64 || source.reads > 30 {
		t.Fatalf("unexpected reads=%d size=%d", source.reads, source.maxRead)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if !errors.Is(confirmMedia(ctx, source, int64(len(real)), ".flac"), context.Canceled) {
		t.Fatal("cancellation ignored")
	}
	// 从元数据末尾定位第一帧，改变CRC-8不改变伪造者可见的fLaC魔数。
	offset := 4
	for {
		n := int(real[offset+1])<<16 | int(real[offset+2])<<8 | int(real[offset+3])
		last := real[offset]&128 != 0
		offset += 4 + n
		if last {
			break
		}
	}
	broken := bytes.Clone(real)
	broken[offset+4] ^= 1
	if err := confirmMedia(t.Context(), bytes.NewReader(broken), int64(len(broken)), ".flac"); err == nil {
		t.Fatal("invalid frame header CRC accepted")
	}
	if _, err := readProbe(source, -1, 64, int64(len(real))); err == nil {
		t.Fatal("negative offset accepted")
	}
	if _, err := readProbe(source, 0, 65, int64(len(real))); err == nil {
		t.Fatal("unbounded read accepted")
	}
	if _, _, err := fileFormat(bytes.NewReader(nil), 10); !errors.Is(err, io.EOF) {
		t.Fatalf("short reader: %v", err)
	}
}

func TestMediaV9DiagnosticUsesOnlyFormatWhitelist(t *testing.T) {
	got := safeError(&mediaResponseError{reason: mediaMIMEMismatch, declared: ".flac", detected: ".m4a"})
	if !strings.Contains(got.Error(), "FLAC") || !strings.Contains(got.Error(), "M4A") {
		t.Fatal(got)
	}
	unsafe := safeError(&mediaResponseError{reason: mediaMIMEMismatch, declared: "token=SECRET", detected: "https://private.example"})
	if strings.Contains(unsafe.Error(), "SECRET") || strings.Contains(unsafe.Error(), "https:") {
		t.Fatal("untrusted format reached error message")
	}
}

// 只读独立审查发现的带正确CRC的非法帧头，必须仍拒绝。
func TestMediaV9FLACRejectsForbiddenHeaderValues(t *testing.T) {
	for _, encoded := range []string{
		"664c614380000022001000100000000000000ac440f00000001000000000000000000000000000000000fff8790800ffff4500000003de",
		"664c614380000022001000100000000000000ac440f00000001000000000000000000000000000000000fff86c08000f007d00000032e3",
		"664c614380000022001000100000000000000ac440f00000001000000000000000000000000000000000fff86d08000f00005d0000003378",
		"664c614380000022001000100000000000000ac440f00000001000000000000000000000000000000000fff86e08000f000026000000cf50",
		"664c614380000022001000100000000000000ac440f00000001000000000000000000000000000000000fff86908010f25000000ba85",
		"664c614380000022001000100000000000000ac440f00000001000000000000000000000000000000000fff96908010f4700000094f4",
	} {
		data, err := hex.DecodeString(encoded)
		if err != nil {
			t.Fatal(err)
		}
		if err := confirmMedia(t.Context(), bytes.NewReader(data), int64(len(data)), ".flac"); err == nil {
			t.Fatalf("accepted forbidden FLAC header: %s", encoded)
		}
	}
}

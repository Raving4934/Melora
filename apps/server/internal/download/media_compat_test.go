package download

import (
	"bytes"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 独立响应夹具，不访问音源；无类型声明仍须实际音频魔数、完整字节和文件哈希。
func TestV8GenericAudioMIMECompatibility(t *testing.T) {
	for _, mediaType := range []string{"", "application/octet-stream", "binary/octet-stream", "application/x-octet-stream", "audio/x-mp3", "audio/mpeg3", "audio/x-mpeg-3"} {
		t.Run(mediaType, func(t *testing.T) {
			body := v5MP3()
			m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, body)
				if mediaType == "" {
					resp.Header.Del("Content-Type")
				} else {
					resp.Header.Set("Content-Type", mediaType)
				}
				return resp, nil
			}, nil, nil, nil, nil)
			j := createJob(t, m, "generic")
			got := waitJob(t, m, j.ID, "completed")
			data, err := os.ReadFile(got.TargetPath)
			if err != nil || !bytes.Equal(data, body) || filepath.Ext(got.TargetPath) != ".mp3" || got.BytesDone != int64(len(body)) {
				t.Fatalf("invalid output: %+v err=%v", got, err)
			}
		})
	}
}

func TestV8MediaFailureClassification(t *testing.T) {
	for _, tc := range []struct {
		name, mime, encoding, code string
		body                       []byte
		duplicate                  bool
	}{
		{name: "html-body", mime: "application/octet-stream", body: []byte("<!DOCTYPE html><html>token=BODY-SECRET</html>"), code: "media_not_audio"},
		{name: "json-body", body: []byte(`{"token":"BODY-SECRET"}`), code: "media_not_audio"},
		{name: "missing-mime-bad-magic", body: []byte("garbage BODY-SECRET"), code: "media_magic_unknown"},
		{name: "explicit-text", mime: "text/html", body: v5MP3(), code: "media_mime_unsupported"},
		{name: "invalid-mime", mime: "audio/mpeg; secret=\"BROKEN", body: v5MP3(), code: "media_mime_invalid"},
		{name: "mismatch-unverified", mime: "audio/flac", body: audio(1024), code: "media_structure_invalid"},
		{name: "encoding", mime: "audio/mpeg", encoding: "gzip", body: v5MP3(), code: "media_encoding_unsupported"},
		{name: "duplicated", mime: "audio/mpeg", duplicate: true, body: v5MP3(), code: "media_header_duplicate"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, tc.body)
				if tc.mime == "" {
					resp.Header.Del("Content-Type")
				} else {
					resp.Header.Set("Content-Type", tc.mime)
				}
				if tc.encoding != "" {
					resp.Header.Set("Content-Encoding", tc.encoding)
				}
				if tc.duplicate {
					resp.Header.Add("Content-Type", "text/html")
				}
				return resp, nil
			}, nil, nil, nil, nil)
			job := createJob(t, m, tc.name)
			failed := waitJob(t, m, job.ID, "failed")
			waitIdle(t, m, job.ID)
			if !strings.Contains(failed.Error, tc.code) || strings.Contains(failed.Error, "SECRET") || strings.Contains(failed.Error, "BROKEN") {
				t.Fatalf("unsafe or ambiguous diagnostic: %+v", failed)
			}
			if _, err := os.Stat(failed.TargetPath); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("invalid final file: %v", err)
			}
		})
	}
}

func TestV8MediaDiagnosticSanitizesWrappedErrors(t *testing.T) {
	err := fmt.Errorf("token=WRAPPED-SECRET: %w", mediaDiagnostic(mediaMIMEMismatch))
	got := safeError(err)
	if !errors.Is(got, errMedia) || !strings.Contains(got.Error(), "media_mime_mismatch") || strings.Contains(got.Error(), "SECRET") {
		t.Fatalf("%v", got)
	}
	if safeError(fmt.Errorf("%w: %w", errSpace, err)) != errSpace {
		t.Fatal("disk error lost priority")
	}
}

func TestV8MissingMIMEFLACAndSplitContinuation(t *testing.T) {
	for _, split := range []bool{false, true} {
		t.Run(fmt.Sprint(split), func(t *testing.T) {
			body := rangeFLACFixture(12 << 10)
			m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
				var resp *http.Response
				if split {
					offset := rangeOffset(t, r)
					resp = rangePart(r, body, offset, min(offset+2048, len(body))-1)
				} else {
					resp = response(r, 200, body)
				}
				resp.Header.Del("Content-Type")
				return resp, nil
			}, nil, nil, nil, nil)
			job := createJob(t, m, "flac")
			got := waitJob(t, m, job.ID, "completed")
			data, err := os.ReadFile(got.TargetPath)
			if err != nil || !bytes.Equal(data, body) || filepath.Ext(got.TargetPath) != ".flac" {
				t.Fatalf("%+v %v", got, err)
			}
		})
	}
}

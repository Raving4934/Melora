package download

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"unicode"

	"melora/internal/model"
)

// V9 只经 Manager 注入独立 HTTP 响应，不访问真实媒体或 LX 脚本。
// 固定夹具来自本机 ffmpeg lavfi sine：44100 Hz、单声道、0.18 秒；
// FLAC 为 523.25 Hz、compression_level=5、metadata_header_padding=0；
// MP3 为 659.25 Hz、libmp3lame 128k、id3v2_version=0、write_xing=0。
// 两者均使用 map_metadata=-1、fflags=+bitexact、flags:a=+bitexact。
// ffprobe 已识别 FLAC 的 2 个包与 MP3 的 8 个包；此处只验证固定 bytes、
// 结构与下载行为，不声称验证来源授权或逐帧完整解码。测试运行不依赖 ffmpeg。
func v9MIMEFixture(t *testing.T, extension string) []byte {
	t.Helper()
	var digest string
	switch extension {
	case ".flac":
		digest = "84da91eddcab91f908fa46c664fa4c41209a6c2e9b2dec94f2b393d926527ab7"
	case ".mp3":
		digest = "231f3c8bab834cf4a60b9f00e1f1b4317d6590f7634a46581bae9876a1689270"
	default:
		t.Fatalf("unexpected fixture extension %q", extension)
	}
	body, err := os.ReadFile(filepath.Join("testdata", "mime-v9", "synthetic"+extension))
	if err != nil {
		t.Fatal(err)
	}
	if got := fmt.Sprintf("%x", sha256.Sum256(body)); got != digest {
		t.Fatalf("fixture %s changed: sha256=%s, want %s", extension, got, digest)
	}
	return body
}

func v9MIMERequireCompleted(t *testing.T, root string, job model.DownloadJob, body []byte, extension, quality string, corrected bool, store *recordingStore) {
	t.Helper()
	if job.State != "completed" || job.Error != "" {
		t.Fatalf("real audio rejected: state=%s bytes=%d error=%q warning=%q", job.State, job.BytesDone, job.Error, job.Warning)
	}
	if filepath.Ext(job.TargetPath) != extension || !bytes.Equal(readTarget(t, job), body) {
		t.Fatalf("output must retain actual format and exact fixture bytes: path=%q want extension=%s", job.TargetPath, extension)
	}
	if job.BytesDone != int64(len(body)) || job.BytesTotal != int64(len(body)) || job.Quality != quality {
		t.Fatalf("incorrect byte counts or mutated requested quality: done=%d total=%d quality=%q; want %d/%d/%s", job.BytesDone, job.BytesTotal, job.Quality, len(body), len(body), quality)
	}
	if corrected {
		// 不锁定主线中文文案，只要求 warning 明确涉及 MIME 且不是纯英文/错误码。
		if !strings.Contains(strings.ToUpper(job.Warning), "MIME") || !strings.ContainsFunc(job.Warning, func(r rune) bool { return unicode.Is(unicode.Han, r) }) {
			t.Fatalf("MIME correction must have a Chinese MIME warning: %q", job.Warning)
		}
	} else if job.Warning != "" {
		t.Fatalf("matching MIME must not acquire a correction warning: %q", job.Warning)
	}
	if strings.Contains(job.Warning, "SECRET") || strings.Contains(job.Warning, "https://") {
		t.Fatalf("warning leaked response/resolver details: %q", job.Warning)
	}
	persisted := store.get(job.ID)
	if persisted.State != "completed" || persisted.Warning != job.Warning || persisted.Quality != quality || persisted.TargetPath != job.TargetPath || persisted.BytesDone != job.BytesDone || persisted.BytesTotal != job.BytesTotal {
		t.Fatalf("completed result, warning or requested quality was not persisted: %+v", persisted)
	}
	// 完成后的 JSON 恢复收据可保留；只要求原始音频 part 已发布/清理。
	if _, err := os.Lstat(filepath.Join(root, "Singles", partName(job.ID))); !os.IsNotExist(err) {
		t.Fatalf("completed audio part remains: %v", err)
	}
}

func v9MIMERequireRejected(t *testing.T, root string, job model.DownloadJob) {
	t.Helper()
	if job.State != "failed" || job.Error == "" {
		t.Fatalf("invalid response was not rejected: state=%s bytes=%d error=%q", job.State, job.BytesDone, job.Error)
	}
	if strings.Contains(job.Error+job.Warning, "SECRET") || strings.Contains(job.Error+job.Warning, "https://") {
		t.Fatalf("unsafe failure diagnostic: error=%q warning=%q", job.Error, job.Warning)
	}
	// 同时找两种扩展名，不能只检查可能尚未修正的 job.TargetPath。
	if err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() && (filepath.Ext(path) == ".flac" || filepath.Ext(path) == ".mp3") {
			t.Errorf("rejected response published an audio file: %s", path)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestV9MIMEConflictRealAudioCompletes(t *testing.T) {
	for _, audio := range []struct{ name, extension, mime string }{
		{"flac_declared_mpeg", ".flac", "audio/mpeg"},
		{"mp3_declared_flac", ".mp3", "audio/flac"},
	} {
		for _, mode := range []string{"200", "200_unknown_length", "206_full", "206_bounded"} {
			t.Run(audio.name+"/"+mode, func(t *testing.T) {
				t.Parallel()
				root := t.TempDir()
				body := v9MIMEFixture(t, audio.extension)
				store := &recordingStore{}
				var calls atomic.Int32
				m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
					calls.Add(1)
					resp := response(r, http.StatusOK, body)
					switch mode {
					case "200_unknown_length":
						resp.ContentLength = -1
					case "206_full":
						resp.StatusCode = http.StatusPartialContent
						resp.Header.Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", len(body)-1, len(body)))
					case "206_bounded":
						offset := rangeOffset(t, r)
						if offset < 0 || offset >= len(body) {
							t.Errorf("out-of-bounds request offset %d", offset)
							return response(r, http.StatusBadRequest, nil), nil
						}
						if offset > 0 && r.Header.Get("If-Range") != `"v1"` {
							t.Error("MIME correction must preserve strong If-Range on continuation")
						}
						resp = rangePart(r, body, offset, min(offset+2048, len(body))-1)
					}
					resp.Header.Set("Content-Type", audio.mime)
					return resp, nil
				}, nil, store, nil, nil)
				// MP3 实体也故意请求 lossless；MIME 修正不是音质升级或降级。
				created, err := m.Create(testTrack("mime-v9-"+audio.name), "lossless")
				if err != nil {
					t.Fatal(err)
				}
				job := rangeTerminal(t, m, created.ID)
				v9MIMERequireCompleted(t, root, job, body, audio.extension, "lossless", true, store)
				wantCalls := int32(1)
				if mode == "206_bounded" {
					wantCalls = int32((len(body) + 2047) / 2048)
				}
				if calls.Load() != wantCalls {
					t.Fatalf("unexpected retries or range count: got=%d want=%d", calls.Load(), wantCalls)
				}
			})
		}
	}
}

func TestV9MIMEConflictRejectsNonAudioAndMagicOnly(t *testing.T) {
	id3 := []byte("ID3\x04\x00\x00\x00\x00\x00\x00")
	// 有真实 STREAMINFO 也不等于有音频帧；不涉及 ID3 前置 FLAC 的未定契约。
	streamInfoOnly := bytes.Clone(v9MIMEFixture(t, ".flac")[:42])
	streamInfoOnly[4] |= 0x80 // 标记此 STREAMINFO 为最后一个 metadata block。
	for _, tc := range []struct {
		name, mime string
		body       []byte
	}{
		{"html_declared_mpeg", "audio/mpeg", []byte("<!DOCTYPE html><html>BODY-SECRET</html>")},
		{"html_declared_flac", "audio/flac", []byte("<!DOCTYPE html><html>BODY-SECRET</html>")},
		{"json_declared_mpeg", "audio/mpeg", []byte(`{"error":"BODY-SECRET"}`)},
		{"json_declared_flac", "audio/flac", []byte(`{"error":"BODY-SECRET"}`)},
		{"flac_magic_only", "audio/mpeg", []byte("fLaC")},
		{"flac_magic_padded", "audio/mpeg", append([]byte("fLaC"), bytes.Repeat([]byte{0x59}, 4092)...)},
		{"flac_magic_html", "audio/mpeg", []byte("fLaC<!DOCTYPE html><html>BODY-SECRET</html>")},
		{"flac_streaminfo_without_frames", "audio/mpeg", streamInfoOnly},
		{"id3_magic_only", "audio/flac", []byte("ID3")},
		{"id3_header_only", "audio/flac", id3},
		{"id3_header_padded", "audio/flac", append(bytes.Clone(id3), bytes.Repeat([]byte{0x11}, 4086)...)},
		{"id3_header_json", "audio/flac", append(bytes.Clone(id3), []byte(`{"error":"BODY-SECRET"}`)...)},
		{"real_flac_declared_html", "text/html", v9MIMEFixture(t, ".flac")},
		{"real_mp3_declared_json", "application/json", v9MIMEFixture(t, ".mp3")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			root := t.TempDir()
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				resp := response(r, http.StatusOK, tc.body)
				resp.Header.Set("Content-Type", tc.mime)
				return resp, nil
			}, nil, nil, nil, nil)
			created := createJob(t, m, "mime-v9-"+tc.name)
			v9MIMERequireRejected(t, root, rangeTerminal(t, m, created.ID))
		})
	}
}

func TestV9MIMEConflictPreservesLengthChecks(t *testing.T) {
	for _, audio := range []struct{ extension, matched, conflict string }{
		{".flac", "audio/flac", "audio/mpeg"},
		{".mp3", "audio/mpeg", "audio/flac"},
	} {
		for _, mediaType := range []string{audio.matched, audio.conflict} {
			for _, delta := range []int{-1, 1} {
				t.Run(fmt.Sprintf("%s/%s/declared_delta_%d", audio.extension, mediaType, delta), func(t *testing.T) {
					t.Parallel()
					root := t.TempDir()
					body := v9MIMEFixture(t, audio.extension)
					m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
						resp := response(r, http.StatusOK, body)
						resp.Header.Set("Content-Type", mediaType)
						resp.ContentLength += int64(delta)
						return resp, nil
					}, nil, nil, nil, func(d *dependencies) { d.limits.attempts = 1 })
					created := createJob(t, m, "mime-v9-length")
					job := rangeTerminal(t, m, created.ID)
					v9MIMERequireRejected(t, root, job)
					// 防止测试仅被旧 MIME 冲突挡住而没有走到实际长度校验。
					if !strings.Contains(job.Error, "长度") || strings.Contains(job.Error, "media_mime_mismatch") {
						t.Fatalf("length mismatch must remain a length failure: %q", job.Error)
					}
				})
			}
		}
	}
}

func TestV9MIMEConflictPreservesRangeChecks(t *testing.T) {
	for _, audio := range []struct{ extension, mime string }{
		{".flac", "audio/mpeg"},
		{".mp3", "audio/flac"},
	} {
		for _, scenario := range []string{"missing", "nonzero_start", "unknown_total", "end_outside_total", "declared_length", "duplicate", "partial_200"} {
			t.Run(audio.extension+"/"+scenario, func(t *testing.T) {
				t.Parallel()
				root := t.TempDir()
				body := v9MIMEFixture(t, audio.extension)
				m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
					resp := rangePart(r, body, 0, len(body)-1)
					resp.Header.Set("Content-Type", audio.mime)
					switch scenario {
					case "missing":
						resp.Header.Del("Content-Range")
					case "nonzero_start":
						resp.Header.Set("Content-Range", fmt.Sprintf("bytes 1-%d/%d", len(body)-1, len(body)))
					case "unknown_total":
						resp.Header.Set("Content-Range", fmt.Sprintf("bytes 0-%d/*", len(body)-1))
					case "end_outside_total":
						resp.Header.Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", len(body), len(body)))
					case "declared_length":
						resp.ContentLength--
					case "duplicate":
						resp.Header.Add("Content-Range", resp.Header.Get("Content-Range"))
					case "partial_200":
						resp.StatusCode = http.StatusOK
						resp.Header.Set("Content-Range", fmt.Sprintf("bytes 0-%d/%d", len(body)-1, len(body)+1))
					}
					return resp, nil
				}, nil, nil, nil, nil)
				created := createJob(t, m, "mime-v9-range")
				job := rangeTerminal(t, m, created.ID)
				v9MIMERequireRejected(t, root, job)
				if !strings.Contains(job.Error, "range_") || job.BytesDone != 0 {
					t.Fatalf("invalid range must fail before accepting bytes: done=%d error=%q", job.BytesDone, job.Error)
				}
			})
		}
	}
}

func TestV9MIMEConflictPreservesContinuationChecks(t *testing.T) {
	const split = 2048
	for _, audio := range []struct{ extension, mime string }{
		{".flac", "audio/mpeg"},
		{".mp3", "audio/flac"},
	} {
		for _, scenario := range []string{"start", "total", "etag", "missing_etag", "length"} {
			t.Run(audio.extension+"/"+scenario, func(t *testing.T) {
				t.Parallel()
				root := t.TempDir()
				body := v9MIMEFixture(t, audio.extension)
				var calls atomic.Int32
				m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
					if calls.Add(1) == 1 {
						resp := rangePart(r, body, 0, split-1)
						resp.Header.Set("Content-Type", audio.mime)
						return resp, nil
					}
					if r.Header.Get("Range") != fmt.Sprintf("bytes=%d-", split) || r.Header.Get("If-Range") != `"v1"` {
						t.Error("MIME correction changed continuation offset or validator")
					}
					resp := rangePart(r, body, split, len(body)-1)
					resp.Header.Set("Content-Type", audio.mime)
					switch scenario {
					case "start":
						resp.Header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", split-1, len(body)-2, len(body)))
					case "total":
						resp.Header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", split, len(body)-1, len(body)+1))
					case "etag":
						resp.Header.Set("ETag", `"changed"`)
					case "missing_etag":
						resp.Header.Del("ETag")
					case "length":
						resp.ContentLength--
					}
					return resp, nil
				}, nil, nil, nil, nil)
				created := createJob(t, m, "mime-v9-continuation")
				job := rangeTerminal(t, m, created.ID)
				v9MIMERequireRejected(t, root, job)
				if calls.Load() != 2 || job.BytesDone != split || !strings.Contains(job.Error, "range_") {
					t.Fatalf("invalid continuation must not replace/append verified bytes: calls=%d done=%d error=%q", calls.Load(), job.BytesDone, job.Error)
				}
				part, err := os.ReadFile(filepath.Join(root, "Singles", partName(job.ID)))
				if err != nil || !bytes.Equal(part, body[:split]) {
					t.Fatalf("invalid continuation changed the verified prefix: %v", err)
				}
			})
		}
	}
}

func TestV9MIMEConflictMatchingMIMEUnchanged(t *testing.T) {
	for _, audio := range []struct{ extension, mime string }{
		{".flac", "audio/flac"},
		{".flac", "audio/x-flac"},
		{".mp3", "audio/mpeg"},
		{".mp3", "audio/mp3"},
	} {
		t.Run(audio.extension+"/"+audio.mime, func(t *testing.T) {
			t.Parallel()
			root := t.TempDir()
			body := v9MIMEFixture(t, audio.extension)
			store := &recordingStore{}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				resp := response(r, http.StatusOK, body)
				resp.Header.Set("Content-Type", audio.mime)
				return resp, nil
			}, nil, store, nil, nil)
			created := createJob(t, m, "mime-v9-matched")
			v9MIMERequireCompleted(t, root, rangeTerminal(t, m, created.ID), body, audio.extension, created.Quality, false, store)
		})
	}
}

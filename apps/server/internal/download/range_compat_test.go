package download

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
	"melora/internal/storage"
)

// 只验证下载器已有的 MIME/魔数/字节/SHA 检查，不冒称执行了音频解码。
func rangeFLACFixture(size int) []byte {
	data := bytes.Repeat([]byte{0x59}, size)
	copy(data, "fLaC")
	return data
}

func rangeTerminal(t *testing.T, m *Manager, id string) model.DownloadJob {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		for _, job := range m.List() {
			if job.ID == id && (job.State == "completed" || job.State == "failed") {
				waitIdle(t, m, id)
				return job
			}
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("range fixture did not terminate within its budget")
	return model.DownloadJob{}
}

func rangeOffset(t *testing.T, request *http.Request) int {
	t.Helper()
	value := request.Header.Get("Range")
	if value == "" {
		return 0
	}
	if !strings.HasPrefix(value, "bytes=") || !strings.HasSuffix(value, "-") {
		t.Error("unexpected range request shape")
		return 0
	}
	offset, err := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(value, "bytes="), "-"))
	if err != nil {
		t.Error("range offset is not an integer")
	}
	return offset
}

func rangePart(request *http.Request, data []byte, start, end int) *http.Response {
	result := response(request, http.StatusPartialContent, data[start:end+1])
	result.Header.Set("Content-Type", "audio/flac")
	result.Header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
	return result
}

func TestRangeCompatBounded206FLAC(t *testing.T) {
	for _, chunked := range []bool{false, true} {
		t.Run(fmt.Sprint(chunked), func(t *testing.T) {
			data := rangeFLACFixture(12 << 10)
			var calls atomic.Int32
			store := &recordingStore{}
			m := testManager(t, t.TempDir(), func(request *http.Request) (*http.Response, error) {
				calls.Add(1)
				offset := rangeOffset(t, request)
				if offset > 0 && request.Header.Get("If-Range") != `"v1"` {
					t.Error("bounded continuation lacks strong If-Range")
				}
				result := rangePart(request, data, offset, min(offset+2048, len(data))-1)
				if chunked {
					result.ContentLength = -1
				}
				return result, nil
			}, nil, store, nil, nil)
			created := createJob(t, m, "bounded-flac")
			job := rangeTerminal(t, m, created.ID)
			if job.State != "completed" {
				t.Fatalf("legal bounded 206 rejected: state=%s error=%s bytes=%d", job.State, job.Error, job.BytesDone)
			}
			if calls.Load() != 6 || job.BytesDone != int64(len(data)) || job.BytesTotal != int64(len(data)) || filepath.Ext(job.TargetPath) != ".flac" {
				t.Fatal("range continuation produced incorrect count, length or extension")
			}
			if !bytes.Equal(readTarget(t, job), data) || store.get(job.ID).State != "completed" {
				t.Fatal("range download was not byte-exact and persisted")
			}
		})
	}
}

func TestRangeCompatFullResponsesAnd200ContentRange(t *testing.T) {
	data := rangeFLACFixture(4096)
	for _, tc := range []struct {
		name      string
		status    int
		cr        string
		chunked   bool
		duplicate bool
		pass      bool
	}{
		{"ordinary_200", 200, "", false, false, true},
		{"complete_206", 206, "bytes 0-4095/4096", false, false, true},
		{"complete_206_case_insensitive_unit", 206, "Bytes 0-4095/4096", false, false, true},
		{"complete_206_unknown_content_length", 206, "bytes 0-4095/4096", true, false, true},
		{"full_200_redundant_range", 200, "bytes 0-4095/4096", false, false, true},
		{"full_200_case_insensitive_unit", 200, "bYtEs 0-4095/4096", false, false, true},
		{"full_200_redundant_range_chunked", 200, "bytes 0-4095/4096", true, false, true},
		{"partial_200_not_complete", 200, "bytes 0-4095/8192", false, false, false},
		{"nonzero_200_not_complete", 200, "bytes 4096-8191/8192", false, false, false},
		{"unknown_total_200", 200, "bytes 0-4095/*", false, false, false},
		{"unknown_total_206", 206, "bytes 0-4095/*", false, false, false},
		{"malformed_200", 200, "bytes invalid", false, false, false},
		{"duplicate_206_range", 206, "bytes 0-4095/4096", false, true, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m := testManager(t, t.TempDir(), func(request *http.Request) (*http.Response, error) {
				result := response(request, tc.status, data)
				result.Header.Set("Content-Type", "audio/flac")
				if tc.cr != "" {
					result.Header.Set("Content-Range", tc.cr)
				}
				if tc.duplicate {
					result.Header.Add("Content-Range", "bytes 4096-8191/8192")
				}
				if tc.chunked {
					result.ContentLength = -1
				}
				return result, nil
			}, nil, nil, nil, nil)
			created := createJob(t, m, tc.name)
			job := rangeTerminal(t, m, created.ID)
			if (job.State == "completed") != tc.pass {
				t.Fatalf("response acceptance mismatch: state=%s error=%s", job.State, job.Error)
			}
			if tc.pass && !bytes.Equal(readTarget(t, job), data) {
				t.Fatal("accepted response changed body bytes")
			}
			if !tc.pass {
				if _, err := os.Stat(job.TargetPath); !os.IsNotExist(err) {
					t.Fatal("ambiguous response published an audio file")
				}
			}
		})
	}
}

func TestRangeCompatNoValidatorUsesOneIndependentReplacement(t *testing.T) {
	for _, stillPartial := range []bool{false, true} {
		t.Run(fmt.Sprint(stillPartial), func(t *testing.T) {
			first := rangeFLACFixture(8192)
			second := append([]byte(nil), first...)
			for i := 4; i < len(second); i++ {
				second[i] = 0x27
			}
			var calls atomic.Int32
			m := testManager(t, t.TempDir(), func(request *http.Request) (*http.Response, error) {
				n := calls.Add(1)
				data, end := first, 2047
				if n > 1 {
					if n != 2 || request.Header.Get("Range") != "bytes=0-" || request.Header.Get("If-Range") != "" {
						t.Error("unknown identity must get exactly one independent from-zero request")
					}
					data = second
					if !stillPartial {
						end = len(second) - 1
					}
				}
				result := rangePart(request, data, 0, end)
				result.Header.Del("ETag")
				return result, nil
			}, nil, nil, nil, nil)
			created := createJob(t, m, "unknown-validator")
			job := rangeTerminal(t, m, created.ID)
			if calls.Load() != 2 {
				t.Fatalf("independent replacement count=%d, want 2", calls.Load())
			}
			if stillPartial {
				if job.State != "failed" || job.BytesDone != 0 {
					t.Fatal("unidentified partial responses must not be concatenated or published")
				}
			} else if job.State != "completed" || !bytes.Equal(readTarget(t, job), second) {
				t.Fatalf("safe from-zero replacement failed: state=%s error=%s", job.State, job.Error)
			}
		})
	}
}

func TestRangeCompatSameETagDifferentResourceNeverAppends(t *testing.T) {
	root := t.TempDir()
	first := audio(8192)
	second := append([]byte(nil), first...)
	for i := 14; i < len(second); i++ {
		second[i] = 0x36
	}
	job := seedPartial(t, root, first[:4096], int64(len(first)))
	var calls atomic.Int32
	m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
		if calls.Add(1) == 2 {
			if request.Header.Get("Range") != "bytes=0-" || request.Header.Get("If-Range") != "" {
				t.Error("changed resource did not get an independent from-zero request")
			}
			return response(request, 200, second), nil
		}
		result := response(request, 206, second[4096:])
		result.Header.Set("Content-Range", "bytes 4096-8191/8192")
		return result, nil
	}, func(context.Context, model.Track, string) (string, error) {
		return "https://other-resource.example/representation", nil
	}, nil, []model.DownloadJob{job}, nil)
	if _, err := m.Action(job.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	finished := rangeTerminal(t, m, job.ID)
	if finished.State != "completed" || calls.Load() != 2 || !bytes.Equal(readTarget(t, finished), second) {
		t.Fatal("identical ETag from a different resource was not replaced with one independent object")
	}
}

func TestRangeCompatResumedBounded206(t *testing.T) {
	root := t.TempDir()
	data := audio(16 << 10)
	created := seedPartial(t, root, data[:4096], int64(len(data)))
	var calls atomic.Int32
	m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
		calls.Add(1)
		offset := rangeOffset(t, request)
		end := min(offset+2048, len(data))
		result := response(request, 206, data[offset:end])
		result.Header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", offset, end-1, len(data)))
		return result, nil
	}, nil, nil, []model.DownloadJob{created}, nil)
	if _, err := m.Action(created.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	job := rangeTerminal(t, m, created.ID)
	if job.State != "completed" || calls.Load() != 6 || !bytes.Equal(readTarget(t, job), data) {
		t.Fatal("restored partial did not complete through bounded ranges")
	}
}

func TestRangeCompatRejectsInvalidFollowingSegmentBeforeWrite(t *testing.T) {
	data := rangeFLACFixture(8192)
	for _, scenario := range []string{"start", "end", "total", "etag", "missing-etag", "weak-etag", "length", "duplicate-etag", "mime", "encoding"} {
		t.Run(scenario, func(t *testing.T) {
			root := t.TempDir()
			var calls atomic.Int32
			m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
				if calls.Add(1) == 1 {
					return rangePart(request, data, 0, 2047), nil
				}
				result := rangePart(request, data, 2048, 4095)
				switch scenario {
				case "start":
					result.Header.Set("Content-Range", "bytes 2047-4094/8192")
				case "end":
					result.Header.Set("Content-Range", "bytes 2048-8192/8192")
				case "total":
					result.Header.Set("Content-Range", "bytes 2048-4095/8193")
				case "etag":
					result.Header.Set("ETag", `"v2"`)
				case "missing-etag":
					result.Header.Del("ETag")
				case "weak-etag":
					result.Header.Set("ETag", `W/"v1"`)
				case "length":
					result.ContentLength--
				case "duplicate-etag":
					result.Header.Add("ETag", `"v2"`)
				case "mime":
					result.Header.Set("Content-Type", "text/html")
				case "encoding":
					result.Header.Set("Content-Encoding", "gzip")
				}
				return result, nil
			}, nil, nil, nil, nil)
			created := createJob(t, m, scenario)
			job := rangeTerminal(t, m, created.ID)
			if job.State != "failed" || calls.Load() != 2 || job.BytesDone != 2048 {
				t.Fatalf("invalid continuation was not rejected: state=%s bytes=%d calls=%d", job.State, job.BytesDone, calls.Load())
			}
			part, err := os.ReadFile(filepath.Join(root, "Singles", partName(job.ID)))
			if err != nil || !bytes.Equal(part, data[:2048]) {
				t.Fatal("invalid continuation changed validated prefix")
			}
			if _, err := os.Stat(job.TargetPath); !os.IsNotExist(err) {
				t.Fatal("invalid continuation was published")
			}
		})
	}
}

func TestRangeCompatRequestBudget(t *testing.T) {
	data := rangeFLACFixture((maxRangeResponses + 1) * 512)
	var calls atomic.Int32
	m := testManager(t, t.TempDir(), func(request *http.Request) (*http.Response, error) {
		calls.Add(1)
		offset := rangeOffset(t, request)
		return rangePart(request, data, offset, min(offset+512, len(data))-1), nil
	}, nil, nil, nil, nil)
	created := createJob(t, m, "range-budget")
	job := rangeTerminal(t, m, created.ID)
	if job.State != "failed" || job.Error != errRangeLimit.Error() || calls.Load() != maxRangeResponses || job.BytesDone != maxRangeResponses*512 {
		t.Fatalf("range budget not enforced: state=%s calls=%d bytes=%d", job.State, calls.Load(), job.BytesDone)
	}
}

func TestRangeCompatLastModifiedRequiresStrongEvidence(t *testing.T) {
	const modified = "Mon, 07 Sep 2026 01:00:00 GMT"
	const date = "Mon, 07 Sep 2026 01:01:00 GMT"
	for _, tc := range []struct {
		name, etag, date string
		strong           bool
	}{
		{"strong_date", "", date, true},
		{"no_date", "", "", false},
		{"same_second", "", modified, false},
		{"weak_etag_cannot_use_date", `W/"weak"`, date, false},
		{"invalid_etag", `"bad space"`, date, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			data := rangeFLACFixture(4096)
			var calls atomic.Int32
			m := testManager(t, t.TempDir(), func(request *http.Request) (*http.Response, error) {
				n := calls.Add(1)
				start, end := 0, 2047
				if n == 2 {
					end = 4095
					if tc.strong {
						start = 2048
						if request.Header.Get("If-Range") != modified {
							t.Error("strong date was not sent as If-Range")
						}
					} else if request.Header.Get("Range") != "bytes=0-" || request.Header.Get("If-Range") != "" {
						t.Error("weak or missing date evidence was used to append")
					}
				}
				result := rangePart(request, data, start, end)
				result.Header.Del("ETag")
				if tc.etag != "" {
					result.Header.Set("ETag", tc.etag)
				}
				result.Header.Set("Last-Modified", modified)
				if tc.date != "" {
					result.Header.Set("Date", tc.date)
				}
				return result, nil
			}, nil, nil, nil, nil)
			created := createJob(t, m, tc.name)
			job := rangeTerminal(t, m, created.ID)
			if job.State != "completed" || calls.Load() != 2 || !bytes.Equal(readTarget(t, job), data) {
				t.Fatalf("date validator handling failed: state=%s calls=%d", job.State, calls.Load())
			}
		})
	}
}

func TestRangeCompatLegacyCheckpointRestartsInsteadOfGuessingIdentity(t *testing.T) {
	root := t.TempDir()
	data := audio(8192)
	job := seedPartial(t, root, data[:4096], int64(len(data)))
	files, _, err := openStorage(root)
	if err != nil {
		t.Fatal(err)
	}
	if err := saveMeta(files, job.ID, partialMeta{Version: 1, ETag: `"v1"`, Total: int64(len(data)), Extension: ".mp3"}); err != nil {
		t.Fatal(err)
	}
	files.Close()
	var calls atomic.Int32
	m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
		calls.Add(1)
		if request.Header.Get("Range") != "" || request.Header.Get("If-Range") != "" {
			t.Error("legacy metadata without resource identity was blindly resumed")
		}
		return response(request, 200, data), nil
	}, nil, nil, []model.DownloadJob{job}, nil)
	if _, err := m.Action(job.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	done := rangeTerminal(t, m, job.ID)
	if done.State != "completed" || calls.Load() != 1 || !bytes.Equal(readTarget(t, done), data) {
		t.Fatal("old checkpoint could not safely restart")
	}
}

func TestRangeCompatFull200WithRangeReplacesSavedPrefix(t *testing.T) {
	root := t.TempDir()
	oldData := audio(8192)
	newData := append([]byte(nil), oldData...)
	for i := 14; i < len(newData); i++ {
		newData[i] = 0x46
	}
	job := seedPartial(t, root, oldData[:4096], int64(len(oldData)))
	m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
		result := response(request, 200, newData)
		result.Header.Set("Content-Range", "bytes 0-8191/8192")
		return result, nil
	}, nil, nil, []model.DownloadJob{job}, nil)
	if _, err := m.Action(job.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	done := rangeTerminal(t, m, job.ID)
	if done.State != "completed" || !bytes.Equal(readTarget(t, done), newData) {
		t.Fatal("full 200 with redundant range did not replace prior representation")
	}
}

func TestRangeCompatInvalidBodyNeverPublishes(t *testing.T) {
	for _, tc := range []struct {
		name string
		body []byte
	}{
		{"too_long_for_segment", rangeFLACFixture(2049)},
		{"zero_progress", nil},
		{"html_with_audio_mime", []byte("<html>not audio</html>")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls atomic.Int32
			m := testManager(t, t.TempDir(), func(request *http.Request) (*http.Response, error) {
				calls.Add(1)
				result := response(request, 206, tc.body)
				result.ContentLength = -1
				result.Header.Set("Content-Type", "audio/flac")
				result.Header.Set("Content-Range", "bytes 0-2047/8192")
				return result, nil
			}, nil, nil, nil, nil)
			created := createJob(t, m, tc.name)
			job := rangeTerminal(t, m, created.ID)
			if job.State != "failed" || calls.Load() > 3 || job.BytesDone != 0 {
				t.Fatal("invalid body was treated as progress or completion")
			}
			if _, err := os.Stat(job.TargetPath); !os.IsNotExist(err) {
				t.Fatal("invalid body published a file")
			}
		})
	}
}

func TestRangeCompatPauseBetweenSegmentsKeepsActualPrefix(t *testing.T) {
	root := t.TempDir()
	data := rangeFLACFixture(8192)
	entered := make(chan struct{})
	var calls atomic.Int32
	m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
		if calls.Add(1) == 1 {
			return rangePart(request, data, 0, 2047), nil
		}
		close(entered)
		<-request.Context().Done()
		return nil, request.Context().Err()
	}, nil, nil, nil, nil)
	created := createJob(t, m, "pause-between-ranges")
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("next segment was not requested")
	}
	if _, err := m.Action(created.ID, "pause"); err != nil {
		t.Fatal(err)
	}
	job := waitJob(t, m, created.ID, "paused")
	waitIdle(t, m, created.ID)
	part, err := os.ReadFile(filepath.Join(root, "Singles", partName(job.ID)))
	if err != nil || job.BytesDone != 2048 || !bytes.Equal(part, data[:2048]) || calls.Load() != 2 {
		t.Fatal("pause did not retain the actual validated prefix")
	}
}

func TestRangeCompatCheckpointOnlyStoresFingerprint(t *testing.T) {
	root := t.TempDir()
	data := rangeFLACFixture(4096)
	m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
		offset := rangeOffset(t, request)
		return rangePart(request, data, offset, min(offset+2048, len(data))-1), nil
	}, nil, nil, nil, nil)
	created := createJob(t, m, "private-identity")
	job := rangeTerminal(t, m, created.ID)
	if job.State != "completed" {
		t.Fatal("identity fixture did not complete")
	}
	serialized, err := os.ReadFile(filepath.Join(root, "Singles", metaName(job.ID)))
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{"https:", "media.example.com", "SECRET", "token="} {
		if bytes.Contains(serialized, []byte(forbidden)) || strings.Contains(job.Error, forbidden) {
			t.Fatal("resource identity leaked a URI or credential")
		}
	}
	files, _, err := openStorage(root)
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	meta, err := loadMeta(files, job.ID)
	if err != nil || len(meta.ResourceHash) != 64 || len(meta.SHA256) != 64 || meta.Total != int64(len(data)) {
		t.Fatal("private resource/content fingerprints were not preserved")
	}
}

func TestRangeCompatMalformedCheckpointIdentityRejected(t *testing.T) {
	files, _, err := openStorage(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	for _, tc := range []struct {
		name, identity, date string
	}{
		{"short_hash", "abcd", ""},
		{"nonhex_hash", strings.Repeat("z", 64), ""},
		{"bad_date_control", "", "bad\r\ndate"},
		{"oversized_date", "", strings.Repeat("a", 129)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := saveMeta(files, tc.name, partialMeta{Version: 1, Extension: ".mp3", Total: 4096, ResourceHash: tc.identity, Date: tc.date}); err != nil {
				t.Fatal(err)
			}
			if _, err := loadMeta(files, tc.name); err == nil {
				t.Fatal("malformed private identity accepted")
			}
		})
	}
}

func TestRangeCompatIndependentReplacementPreservesPrefixOnPreflightFailure(t *testing.T) {
	for _, reason := range []string{"space", "magic"} {
		t.Run(reason, func(t *testing.T) {
			root := t.TempDir()
			data := audio(8192)
			created := seedPartial(t, root, data[:4096], int64(len(data)))
			var calls atomic.Int32
			m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
				if calls.Add(1) == 1 {
					result := response(request, 206, data[4096:])
					result.Header.Set("Content-Range", "bytes 4096-8191/8192")
					return result, nil
				}
				if reason == "magic" {
					return response(request, 200, []byte("<html>not an independent audio object</html>")), nil
				}
				return response(request, 200, data), nil
			}, func(context.Context, model.Track, string) (string, error) {
				return "https://other-resource.example/representation", nil
			}, nil, []model.DownloadJob{created}, func(d *dependencies) {
				d.probe = func(*os.Root) (storage.Info, error) {
					if reason == "space" && calls.Load() > 1 {
						return capacity(0), nil
					}
					return capacity(1 << 30), nil
				}
			})
			if _, err := m.Action(created.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			job := rangeTerminal(t, m, created.ID)
			part, err := os.ReadFile(filepath.Join(root, "Singles", partName(job.ID)))
			if job.State != "failed" || calls.Load() != 2 || err != nil || !bytes.Equal(part, data[:4096]) {
				t.Fatal("failed independent preflight destroyed the saved prefix")
			}
		})
	}
}

func TestRangeCompat416CaseInsensitiveUnit(t *testing.T) {
	for _, unit := range []string{"Bytes", "bYtEs"} {
		t.Run(unit, func(t *testing.T) {
			data := audio(8192)
			root := t.TempDir()
			created := seedPartial(t, root, data, int64(len(data)))
			var calls atomic.Int32
			m := testManager(t, root, func(request *http.Request) (*http.Response, error) {
				calls.Add(1)
				result := response(request, 416, nil)
				result.Header.Set("Content-Range", unit+" */8192")
				return result, nil
			}, nil, nil, []model.DownloadJob{created}, nil)
			if _, err := m.Action(created.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			job := rangeTerminal(t, m, created.ID)
			if job.State != "completed" || calls.Load() != 1 || !bytes.Equal(readTarget(t, job), data) {
				t.Fatal("case-insensitive 416 unit did not confirm the complete local representation")
			}
		})
	}
	for _, value := range []string{"Bytes */+1", "Bytes */-1", "Bytes */1, */2", "Bytes  */1", "Bytes */9223372036854775808", "Bits */1"} {
		if _, ok := unsatisfiedRange(value); ok {
			t.Fatal("case-insensitive unit matching loosened numeric or single-value validation")
		}
	}
}

func TestRangeCompatSafeDiagnosticsSurviveWrapping(t *testing.T) {
	for _, reason := range []rangeFailureReason{rangeHeaderMissing, rangeSyntaxInvalid, rangeTotalUnknown,
		rangeOffsetMismatch, rangeTotalChanged, rangeLengthMismatch, rangeValidatorUnavailable,
		rangeValidatorMismatch, rangeHeaderDuplicate, rangeUnsatisfiedUnconfirmed, rangeSizeLimit} {
		status := http.StatusPartialContent
		if reason == rangeUnsatisfiedUnconfirmed {
			status = http.StatusRequestedRangeNotSatisfiable
		}
		detail := rangeDiagnostic(status, reason)
		wrapped := fmt.Errorf("untrusted wrapper must not surface: %w", &attemptError{cause: detail})
		got := safeError(wrapped)
		if got.Error() != detail.Error() || got.Error() == errRange.Error() || !errors.Is(got, errRange) {
			t.Fatal("safeError erased controlled range diagnostic")
		}
		if strings.Contains(got.Error(), "untrusted") || !strings.Contains(got.Error(), "HTTP ") || !strings.Contains(got.Error(), "range_") {
			t.Fatal("range diagnostic exposed wrapper or lost its fixed code/status")
		}
	}
	for _, detail := range []error{errRangeIdentity, errRangeStatus, errRangeLimit} {
		if got := safeError(fmt.Errorf("untrusted wrapper: %w", detail)); got.Error() != detail.Error() || !strings.Contains(got.Error(), "range_") {
			t.Fatal("fixed replacement/status/budget diagnostic was erased")
		}
	}
	if got := safeError(rangeDiagnostic(999, rangeFailureReason(255))); got.Error() != errRange.Error() {
		t.Fatal("diagnostic accepted an unknown status or reason")
	}
}

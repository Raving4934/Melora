package download

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
)

func TestCompletedDownloadPersistsRealBytesAndSnapshots(t *testing.T) {
	root := t.TempDir()
	data := audio(90000)
	store := &recordingStore{}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, data), nil }, nil, store, nil, nil)
	updates, unsubscribe := m.Updates()
	defer unsubscribe()
	if len(<-updates) != 0 {
		t.Fatal("initial snapshot not empty")
	}
	track := testTrack("one")
	job, err := m.Create(track, "standard")
	if err != nil {
		t.Fatal(err)
	}
	track.Qualities[0] = "mutated"
	completed := waitJob(t, m, job.ID, "completed")
	waitIdle(t, m, job.ID)
	if completed.BytesDone != int64(len(data)) || completed.BytesTotal != int64(len(data)) || completed.Speed != 0 {
		t.Fatalf("fake counters: %+v", completed)
	}
	if !bytes.Equal(readTarget(t, completed), data) {
		t.Fatal("download bytes differ")
	}
	if store.get(job.ID).State != "completed" {
		t.Fatal("completion not persisted")
	}
	all := strings.Join(store.states(), ",")
	for _, state := range []string{"queued", "resolving", "downloading", "verifying", "finalizing", "completed"} {
		if !strings.Contains(all, state) {
			t.Errorf("missing %s in %s", state, all)
		}
	}
	snapshot := <-updates
	if len(snapshot) != 1 || snapshot[0].State != "completed" {
		t.Fatalf("stale snapshot: %+v", snapshot)
	}
	snapshot[0].Track.Qualities[0] = "changed"
	snapshot[0].State = "corrupt"
	list := m.List()
	list[0].Track.Qualities[0] = "changed again"
	if m.List()[0].Track.Qualities[0] != "standard" {
		t.Fatal("snapshot exposed mutable state")
	}
	if strings.Contains(fmt.Sprintf("%+v", m.List()), "SECRET") {
		t.Fatal("URL leaked into job")
	}
	if _, err := os.Stat(filepath.Join(root, "Singles", partName(job.ID))); !os.IsNotExist(err) {
		t.Fatal("part not renamed")
	}
	if _, err := m.Action(job.ID, "cancel"); err == nil {
		t.Fatal("completed cancellation accepted")
	}
	if !bytes.Equal(readTarget(t, completed), data) {
		t.Fatal("cancel altered completed file")
	}
}

func TestRange206ResumeAnd200Restart(t *testing.T) {
	for _, status := range []int{200, 206} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			root := t.TempDir()
			data := audio(65536)
			offset := int64(4096)
			job := seedPartial(t, root, data[:offset], int64(len(data)))
			store := &recordingStore{}
			var calls atomic.Int32
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				calls.Add(1)
				if r.Header.Get("Range") != "bytes=4096-" || r.Header.Get("If-Range") != `"v1"` {
					t.Errorf("missing resume headers: %v", r.Header)
				}
				if status == 206 {
					res := response(r, 206, data[offset:])
					res.Header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", offset, len(data)-1, len(data)))
					return res, nil
				}
				return response(r, 200, data), nil
			}, nil, store, []model.DownloadJob{job}, nil)
			recovered := m.List()[0]
			if recovered.State != "paused" || recovered.BytesDone != offset || store.get(job.ID).State != "paused" {
				t.Fatalf("not recovered safely: %+v", recovered)
			}
			if _, err := m.Action(job.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			finished := waitJob(t, m, job.ID, "completed")
			if !bytes.Equal(readTarget(t, finished), data) || calls.Load() != 1 {
				t.Fatal("corrupt resumed download")
			}
		})
	}
}

func TestRejectMalformedRangeAndValidatorMismatch(t *testing.T) {
	cases := []struct {
		name, cr, etag string
		length         int64
	}{
		{"missing", "", `"v1"`, 4096}, {"wrong-start", "bytes 4095-8191/8192", `"v1"`, 4096},
		{"wrong-end", "bytes 4096-8192/8192", `"v1"`, 4096},
		{"unknown-total", "bytes 4096-8191/*", `"v1"`, 4096}, {"different-total", "bytes 4096-8999/9000", `"v1"`, 4904},
		{"bad-length", "bytes 4096-8191/8192", `"v1"`, 4095}, {"changed-object", "bytes 4096-8191/8192", `"v2"`, 4096},
		{"missing-validator", "bytes 4096-8191/8192", "", 4096}, {"overflow", "bytes 4096-9223372036854775808/9223372036854775809", `"v1"`, 4096},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			data := audio(8192)
			job := seedPartial(t, root, data[:4096], 8192)
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				res := response(r, 206, data[4096:])
				res.Header.Set("Content-Range", tc.cr)
				res.Header.Set("ETag", tc.etag)
				res.ContentLength = tc.length
				return res, nil
			}, nil, nil, []model.DownloadJob{job}, nil)
			if _, err := m.Action(job.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			failed := waitJob(t, m, job.ID, "failed")
			waitIdle(t, m, job.ID)
			reasons := map[string]rangeFailureReason{
				"missing": rangeHeaderMissing, "wrong-start": rangeOffsetMismatch,
				"wrong-end": rangeSyntaxInvalid, "unknown-total": rangeTotalUnknown,
				"different-total": rangeTotalChanged, "bad-length": rangeLengthMismatch,
				"changed-object": rangeValidatorMismatch, "missing-validator": rangeValidatorUnavailable,
				"overflow": rangeSyntaxInvalid,
			}
			if failed.Error != rangeDiagnostic(206, reasons[tc.name]).Error() {
				t.Fatalf("unexpected controlled range diagnostic: %s", failed.Error)
			}
			part, err := os.ReadFile(filepath.Join(root, "Singles", partName(job.ID)))
			if err != nil || !bytes.Equal(part, data[:4096]) {
				t.Fatal("invalid range changed part")
			}
			if _, err := os.Stat(failed.TargetPath); !os.IsNotExist(err) {
				t.Fatal("invalid range published file")
			}
		})
	}
}
func TestRangeParsing(t *testing.T) {
	for _, s := range []string{"bytes 0-0/1", "bytes 5-9/10", "Bytes 0-1/2", "bYtEs 5-9/10"} {
		if _, _, _, ok := parseContentRange(s); !ok {
			t.Error(s)
		}
	}
	for _, s := range []string{"bytes -1-2/3", "bytes +1-2/3", "bytes 0-1/1", "bytes 2-1/3", "bytes 0-0/0", "bytes 0-1/*", "bytes 0-1/3, 4-5/6", "bytes  0-1/2", "bytes 0-1/2 ", "bytes 0-1/9223372036854775808"} {
		if _, _, _, ok := parseContentRange(s); ok {
			t.Error(s)
		}
	}
}
func TestRange416VerifiedCompleteOrRestart(t *testing.T) {
	for _, matching := range []bool{true, false} {
		t.Run(fmt.Sprint(matching), func(t *testing.T) {
			root := t.TempDir()
			data := audio(8192)
			job := seedPartial(t, root, data, int64(len(data)))
			var calls atomic.Int32
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				n := calls.Add(1)
				if n == 1 {
					res := response(r, 416, nil)
					res.Header.Set("Content-Range", "bytes */8192")
					if !matching {
						res.Header.Set("ETag", `"v2"`)
					}
					return res, nil
				}
				if r.Header.Get("Range") != "" {
					t.Error("416 did not restart")
				}
				return response(r, 200, data), nil
			}, nil, nil, []model.DownloadJob{job}, nil)
			if _, err := m.Action(job.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			done := waitJob(t, m, job.ID, "completed")
			if !bytes.Equal(readTarget(t, done), data) {
				t.Fatal("416 completion corrupt")
			}
			want := int32(2)
			if matching {
				want = 1
			}
			if calls.Load() != want {
				t.Fatal(calls.Load())
			}
		})
	}
}
func TestNoValidatorRestartsInsteadOfJoiningObjects(t *testing.T) {
	root := t.TempDir()
	data := audio(8192)
	job := seedPartial(t, root, data[:4096], 8192)
	if err := os.Remove(filepath.Join(root, "Singles", metaName(job.ID))); err != nil {
		t.Fatal(err)
	}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		if r.Header.Get("Range") != "" {
			t.Error("unverified resume")
		}
		return response(r, 200, data), nil
	}, nil, nil, []model.DownloadJob{job}, nil)
	if _, err := m.Action(job.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	done := waitJob(t, m, job.ID, "completed")
	if !bytes.Equal(readTarget(t, done), data) {
		t.Fatal("restart corrupt")
	}
}
func TestURLRefreshAndBoundedRetries(t *testing.T) {
	root := t.TempDir()
	data := audio(2048)
	var resolves, requests atomic.Int32
	resolver := func(context.Context, model.Track, string) (string, error) {
		resolves.Add(1)
		return "https://media.example.com/audio?token=REFRESH-SECRET", nil
	}
	store := &recordingStore{}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		if requests.Add(1) == 1 {
			return response(r, 403, nil), nil
		}
		return response(r, 200, data), nil
	}, resolver, store, nil, nil)
	job := createJob(t, m, "refresh")
	waitJob(t, m, job.ID, "completed")
	if resolves.Load() != 2 || !strings.Contains(strings.Join(store.states(), ","), "waiting_for_url_refresh") {
		t.Fatal("URL not refreshed")
	}
	var failures atomic.Int32
	bad := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		failures.Add(1)
		return response(r, 503, []byte("token=SERVER-SECRET")), nil
	}, nil, nil, nil, nil)
	j := createJob(t, bad, "failure")
	failed := waitJob(t, bad, j.ID, "failed")
	if failures.Load() != 3 || strings.Contains(failed.Error, "SECRET") {
		t.Fatalf("unbounded or leaked: %d %+v", failures.Load(), failed)
	}
}
func TestSizeAndMediaLimits(t *testing.T) {
	for _, tc := range []struct {
		name, mime, encoding string
		body                 []byte
		length               int64
	}{
		{"length", "audio/mpeg", "", audio(2048), 2048},
		{"chunked", "audio/mpeg", "", audio(2048), -1},
		{"html-spoof", "audio/mpeg", "", []byte("<html>token=SECRET</html>"), -1},
		{"json-spoof", "application/octet-stream", "", []byte(`{"token":"SECRET"}`), -1},
		{"wrong-mime", "text/html", "", audio(1024), 1024},
		{"compressed", "audio/mpeg", "gzip", audio(1024), 1024},
		{"mismatched-audio", "audio/flac", "", audio(1024), 1024},
	} {
		t.Run(tc.name, func(t *testing.T) {
			m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
				res := response(r, 200, tc.body)
				res.ContentLength = tc.length
				res.Header.Set("Content-Type", tc.mime)
				if tc.encoding != "" {
					res.Header.Set("Content-Encoding", tc.encoding)
				}
				return res, nil
			}, nil, nil, nil, func(d *dependencies) { d.limits.maxBytes = 1024 })
			j := createJob(t, m, tc.name)
			failed := waitJob(t, m, j.ID, "failed")
			waitIdle(t, m, j.ID)
			if failed.BytesDone > 1024 || strings.Contains(failed.Error, "SECRET") {
				t.Fatalf("bad failure: %+v", failed)
			}
			if _, err := os.Stat(failed.TargetPath); !os.IsNotExist(err) {
				t.Fatal("bad response finalized")
			}
		})
	}
}
func TestUnknownLengthAndPartialBodyRetry(t *testing.T) {
	t.Run("unknown-length", func(t *testing.T) {
		data := audio(4096)
		m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
			res := response(r, 200, data)
			res.ContentLength = -1
			return res, nil
		}, nil, nil, nil, nil)
		j := createJob(t, m, "chunked")
		done := waitJob(t, m, j.ID, "completed")
		if done.BytesTotal != 4096 || !bytes.Equal(readTarget(t, done), data) {
			t.Fatal("unknown length failed")
		}
	})
	t.Run("disconnect", func(t *testing.T) {
		data := audio(8192)
		var calls atomic.Int32
		m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
			if calls.Add(1) == 1 {
				res := response(r, 200, data[:4096])
				res.ContentLength = 8192
				return res, nil
			}
			if r.Header.Get("Range") != "bytes=4096-" {
				t.Errorf("missing retry range: %s", r.Header.Get("Range"))
			}
			res := response(r, 206, data[4096:])
			res.Header.Set("Content-Range", "bytes 4096-8191/8192")
			return res, nil
		}, nil, nil, nil, nil)
		j := createJob(t, m, "disconnect")
		done := waitJob(t, m, j.ID, "completed")
		if calls.Load() != 2 || !bytes.Equal(readTarget(t, done), data) {
			t.Fatal("retry corrupted file")
		}
	})
}

type blockingBody struct {
	ctx     context.Context
	first   []byte
	once    sync.Once
	blocked chan struct{}
}

func (b *blockingBody) Read(p []byte) (int, error) {
	if len(b.first) > 0 {
		n := copy(p, b.first)
		b.first = b.first[n:]
		return n, nil
	}
	b.once.Do(func() { close(b.blocked) })
	<-b.ctx.Done()
	return 0, b.ctx.Err()
}
func (*blockingBody) Close() error { return nil }
func blockingResponse(r *http.Request, data []byte, blocked chan struct{}) *http.Response {
	res := response(r, 200, nil)
	res.ContentLength = int64(len(data))
	res.Body = &blockingBody{ctx: r.Context(), first: data[:4096], blocked: blocked}
	return res
}
func TestPauseCloseRecoveryResumeAndCancel(t *testing.T) {
	for _, action := range []string{"pause", "close", "cancel"} {
		t.Run(action, func(t *testing.T) {
			root := t.TempDir()
			data := audio(8192)
			blocked := make(chan struct{})
			store := &recordingStore{}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return blockingResponse(r, data, blocked), nil }, nil, store, nil, nil)
			j := createJob(t, m, action)
			select {
			case <-blocked:
			case <-time.After(3 * time.Second):
				t.Fatal("did not receive actual bytes")
			}
			if err := m.Configure(t.TempDir(), 1); err == nil {
				t.Fatal("root changed while active")
			}
			if err := m.Configure(root, 2); err == nil {
				t.Fatal("concurrency changed while active")
			}
			if action == "close" {
				m.Close()
			} else {
				if _, err := m.Action(j.ID, action); err != nil {
					t.Fatal(err)
				}
			}
			state := "paused"
			if action == "cancel" {
				state = "cancelled"
			}
			saved := store.get(j.ID)
			if saved.State != state || saved.BytesDone != 4096 || saved.Speed != 0 {
				t.Fatalf("bad stop persistence: %+v", saved)
			}
			if action == "cancel" {
				requirePartAbsent(t, root, j.ID)
				return
			}
			part, err := os.ReadFile(filepath.Join(root, "Singles", partName(j.ID)))
			if err != nil || !bytes.Equal(part, data[:4096]) {
				t.Fatal("part not retained")
			}
			m.Close()
			m2 := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				if r.Header.Get("Range") != "bytes=4096-" {
					t.Error("recovery did not resume")
				}
				res := response(r, 206, data[4096:])
				res.Header.Set("Content-Range", "bytes 4096-8191/8192")
				return res, nil
			}, nil, store, []model.DownloadJob{saved}, nil)
			if _, err := m2.Action(j.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			done := waitJob(t, m2, j.ID, "completed")
			if !bytes.Equal(readTarget(t, done), data) {
				t.Fatal("recovery corrupted bytes")
			}
		})
	}
}
func TestTotalTimeBudgetAndSafeErrors(t *testing.T) {
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) { <-r.Context().Done(); return nil, r.Context().Err() }, nil, nil, nil, func(d *dependencies) { d.limits.totalTime = 20 * time.Millisecond })
	j := createJob(t, m, "timeout")
	failed := waitJob(t, m, j.ID, "failed")
	if failed.Error != errTimeout.Error() {
		t.Fatalf("timeout not surfaced: %+v", failed)
	}
	m2 := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		return nil, errors.New("https://user:password@example.com/?token=TRANSPORT-SECRET")
	}, nil, nil, nil, nil)
	j = createJob(t, m2, "transport")
	failed = waitJob(t, m2, j.ID, "failed")
	if failed.Error != errNetwork.Error() {
		t.Fatal("raw transport error surfaced")
	}
	m3 := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		t.Error("resolver error made request")
		return nil, io.EOF
	}, func(context.Context, model.Track, string) (string, error) {
		return "https://example.com/?token=URL-SECRET", errors.New("token=RESOLVE-SECRET")
	}, nil, nil, nil)
	j = createJob(t, m3, "resolve")
	failed = waitJob(t, m3, j.ID, "failed")
	if failed.Error != errResolve.Error() {
		t.Fatal("resolver error leaked")
	}
}

func TestMediaFormatSniffing(t *testing.T) {
	cases := []struct {
		name, mime, extension string
		data                  []byte
	}{
		{"mp3", "audio/mpeg", ".mp3", audio(1024)},
		{"mp3-frame", "audio/mpeg", ".mp3", []byte{0xff, 0xfb, 0x90, 0x00}},
		{"flac", "audio/flac", ".flac", []byte("fLaC000000000")},
		{"ogg", "audio/ogg", ".ogg", []byte("OggS000000000")},
		{"wav", "audio/wav", ".wav", []byte("RIFF0000WAVE000")},
		{"m4a", "audio/mp4", ".m4a", []byte("0000ftypM4A 000")},
		{"aac", "audio/aac", ".aac", []byte{0xff, 0xf1, 0x50, 0x80}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ext := detectFormat(tc.data)
			if ext != tc.extension || !matchingMIME(tc.mime, ext) {
				t.Fatalf("bad detection %q", ext)
			}
			if !matchingMIME("application/octet-stream", ext) {
				t.Fatal("octet stream with valid magic rejected")
			}
		})
	}
	for _, data := range [][]byte{[]byte("<html>"), []byte(`{"error":"unauthorized"}`), []byte("MZ000000"), []byte("0000ftypBAD!"), {}, {0xff, 0xff, 0xff, 0xff}} {
		if detectFormat(data) != "" {
			t.Errorf("unsupported magic accepted %x", data)
		}
	}
}
func TestLastModifiedAndWeakETagValidation(t *testing.T) {
	date := "Mon, 07 Sep 2026 01:00:00 GMT"
	meta := partialMeta{LastModified: date, Date: "Mon, 07 Sep 2026 01:01:00 GMT"}
	if resumeValidator(meta) != date || !matchingValidator(meta, http.Header{"Last-Modified": []string{date}, "Date": []string{meta.Date}}) {
		t.Fatal("strong Last-Modified validator rejected")
	}
	if resumeValidator(partialMeta{ETag: `W/"weak"`, LastModified: date, Date: meta.Date}) != "" {
		t.Fatal("weak ETag incorrectly fell back to Last-Modified")
	}
	if resumeValidator(partialMeta{LastModified: date}) != "" {
		t.Fatal("Last-Modified without strength evidence accepted")
	}
	if resumeValidator(partialMeta{ETag: `W/"weak"`}) != "" {
		t.Fatal("weak ETag allowed in If-Range")
	}
	if resumeValidator(partialMeta{ETag: "\"bad\r\nheader\""}) != "" {
		t.Fatal("header injection allowed")
	}
}

func TestRateLimitStopsAutomaticRetries(t *testing.T) {
	var calls atomic.Int32
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		res := response(r, http.StatusTooManyRequests, []byte("token=SECRET must not leak"))
		res.Header.Set("Retry-After", "120")
		return res, nil
	}, nil, nil, nil, nil)
	job := createJob(t, m, "rate-limit")
	failed := waitJob(t, m, job.ID, "failed")
	waitIdle(t, m, job.ID)
	if calls.Load() != 1 {
		t.Fatalf("retried within upstream limit window: %d", calls.Load())
	}
	if failed.Error != errRateLimited.Error() || failed.BytesDone != 0 {
		t.Fatalf("unexpected error: %+v", failed)
	}
}

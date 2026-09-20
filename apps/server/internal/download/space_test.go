package download

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"melora/internal/model"
	"melora/internal/storage"
)

func capacity(available uint64) storage.Info {
	return storage.Info{TotalBytes: available + 1<<30, AvailableBytes: available, FreeBytes: available + 1<<20}
}

func TestSpacePreflightStopsBeforeResolverAndNetwork(t *testing.T) {
	for _, test := range []struct {
		name           string
		info           storage.Info
		probeErr, want error
	}{
		{"low", capacity(spaceReserve - 1), nil, errSpace},
		{"zero", storage.Info{}, nil, errSpace},
		{"error", capacity(1 << 30), errors.New("/private/path?token=PROBE-SECRET"), errSpaceProbe},
		{"unsupported", storage.Info{}, storage.ErrUnsupported, errSpaceProbe},
		{"invalid", storage.Info{TotalBytes: 1, AvailableBytes: 1 << 30}, nil, errSpaceProbe},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			var resolved, requests atomic.Int32
			m := testManager(t, root, func(*http.Request) (*http.Response, error) { requests.Add(1); return nil, errNetwork },
				func(context.Context, model.Track, string) (string, error) { resolved.Add(1); return "", errResolve }, nil, nil,
				func(d *dependencies) {
					d.probe = func(*os.Root) (storage.Info, error) { return test.info, test.probeErr }
				})
			job := createJob(t, m, "low")
			failed := waitJob(t, m, job.ID, "failed")
			waitIdle(t, m, job.ID)
			if resolved.Load() != 0 || requests.Load() != 0 || failed.Error != test.want.Error() {
				t.Fatalf("preflight: %+v resolve=%d requests=%d", failed, resolved.Load(), requests.Load())
			}
			part, err := os.Stat(filepath.Join(root, "Singles", partName(job.ID)))
			if err != nil || part.Size() != 0 || failed.BytesDone != 0 {
				t.Fatalf("unexpected writes: %v %v", part, err)
			}
		})
	}
}

func TestResumeSpaceUsesRemainingNotFullContentLength(t *testing.T) {
	for _, fullResponse := range []bool{false, true} {
		name := "range"
		if fullResponse {
			name = "full-response-preserves-part"
		}
		t.Run(name, func(t *testing.T) {
			root := t.TempDir()
			data := audio(4096)
			job := seedPartial(t, root, data[:2048], 4096)
			var requests atomic.Int32
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				requests.Add(1)
				if r.Header.Get("Range") != "bytes=2048-" {
					t.Error("missing resume range")
				}
				if fullResponse {
					return response(r, 200, data), nil
				}
				resp := response(r, 206, data[2048:])
				resp.Header.Set("Content-Range", "bytes 2048-4095/4096")
				return resp, nil
			}, nil, nil, []model.DownloadJob{job}, func(d *dependencies) {
				d.probe = func(*os.Root) (storage.Info, error) { return capacity(spaceReserve + 2048), nil }
			})
			if _, err := m.Action(job.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			if fullResponse {
				failed := waitJob(t, m, job.ID, "failed")
				part, err := os.ReadFile(filepath.Join(root, "Singles", partName(job.ID)))
				if err != nil || !bytes.Equal(part, data[:2048]) || failed.Error != errSpace.Error() || failed.BytesDone != 2048 {
					t.Fatalf("full response destroyed partial: %+v %v", failed, err)
				}
			} else {
				done := waitJob(t, m, job.ID, "completed")
				if !bytes.Equal(readTarget(t, done), data) {
					t.Fatal("incorrect resumed audio")
				}
			}
			if requests.Load() != 1 {
				t.Fatalf("requests=%d", requests.Load())
			}
		})
	}
}

type countedBody struct {
	reader *bytes.Reader
	read   atomic.Int64
	closed atomic.Bool
}

func (b *countedBody) Read(p []byte) (int, error) {
	n, err := b.reader.Read(p)
	b.read.Add(int64(n))
	return n, err
}
func (b *countedBody) Close() error { b.closed.Store(true); return nil }

func TestSpaceFromHeadersFailsBeforeReadingBody(t *testing.T) {
	root := t.TempDir()
	body := &countedBody{reader: bytes.NewReader(audio(2048))}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		resp := response(r, 200, audio(2048))
		resp.Body = body
		return resp, nil
	}, nil, nil, nil, func(d *dependencies) {
		d.probe = func(*os.Root) (storage.Info, error) { return capacity(spaceReserve + 1024), nil }
	})
	job := createJob(t, m, "header")
	failed := waitJob(t, m, job.ID, "failed")
	if failed.Error != errSpace.Error() || body.read.Load() != 0 || !body.closed.Load() {
		t.Fatalf("body consumed without room: %+v reads=%d", failed, body.read.Load())
	}
}

func TestPeriodicSpaceCheckStopsCopyAndRetainsActualOffset(t *testing.T) {
	for _, unknown := range []bool{false, true} {
		for _, probeFailure := range []bool{false, true} {
			name := "known"
			if unknown {
				name = "unknown"
			}
			if probeFailure {
				name += "-probe-failure"
			}
			t.Run(name, func(t *testing.T) {
				root := t.TempDir()
				body := &countedBody{reader: bytes.NewReader(audio(3 << 20))}
				var requests, probes atomic.Int32
				store := &recordingStore{}
				m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
					requests.Add(1)
					resp := response(r, 200, nil)
					resp.Body = body
					resp.ContentLength = 3 << 20
					if unknown {
						resp.ContentLength = -1
					}
					return resp, nil
				}, nil, store, nil, func(d *dependencies) {
					d.limits.maxBytes = 4 << 20
					d.probe = func(*os.Root) (storage.Info, error) {
						if probes.Add(1) >= 3 {
							if probeFailure {
								return storage.Info{}, errors.New("/private?token=PROBE-SECRET")
							}
							return capacity(spaceReserve - 1), nil
						}
						return capacity(1 << 30), nil
					}
				})
				job := createJob(t, m, "stream")
				failed := waitJob(t, m, job.ID, "failed")
				waitIdle(t, m, job.ID)
				want := errSpace
				if probeFailure {
					want = errSpaceProbe
				}
				part, err := os.Stat(filepath.Join(root, "Singles", partName(job.ID)))
				if err != nil || part.Size() <= 0 || part.Size() > spaceInterval || failed.BytesDone != part.Size() || store.get(job.ID).BytesDone != part.Size() || failed.Error != want.Error() {
					t.Fatalf("copy failure/offset not persisted: %+v part=%v err=%v", failed, part, err)
				}
				if requests.Load() != 1 || body.read.Load() >= 3<<20 || !body.closed.Load() {
					t.Fatal("did not stop bounded copy")
				}
				m.Close()
				m2 := testManager(t, root, nil, nil, nil, []model.DownloadJob{store.get(job.ID)}, func(d *dependencies) { d.limits.maxBytes = 4 << 20 })
				if restored := m2.List()[0]; restored.State != "paused" || restored.BytesDone != part.Size() {
					t.Fatalf("lost offset on restart: %+v", restored)
				}
			})
		}
	}
}

func TestUnknownLengthCannotConsumeReserveBetweenProbes(t *testing.T) {
	root := t.TempDir()
	var id atomic.Value
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		resp := response(r, 200, audio(256<<10))
		resp.ContentLength = -1
		return resp, nil
	}, nil, &recordingStore{reject: func(job model.DownloadJob) bool { id.Store(job.ID); return false }}, nil, func(d *dependencies) {
		d.probe = func(files *os.Root) (storage.Info, error) {
			var done uint64
			if value := id.Load(); value != nil {
				if info, err := files.Lstat(partName(value.(string))); err == nil {
					done = uint64(info.Size())
				}
			}
			return capacity(spaceReserve + (64 << 10) - done), nil
		}
	})
	job := createJob(t, m, "unknown-budget")
	failed := waitJob(t, m, job.ID, "failed")
	if failed.Error != errSpace.Error() || failed.BytesDone != 64<<10 {
		t.Fatalf("consumed reserve: %+v", failed)
	}
}

func TestSpaceGuardTimeCheckAndENOSPCSanitization(t *testing.T) {
	m := &Manager{probe: func(*os.Root) (storage.Info, error) { return capacity(spaceReserve - 1), nil }}
	guard := spaceGuard{manager: m, checked: time.Now().Add(-2 * time.Second), available: 1 << 30}
	if err := guard.check(1, -1, 1); !errors.Is(err, errSpace) {
		t.Fatalf("time check: %v", err)
	}
	for _, cause := range []error{syscall.ENOSPC, syscall.EDQUOT} {
		wrapped := &os.PathError{Op: "write", Path: "/private?token=SECRET", Err: cause}
		if diskError(wrapped) != errSpace || safeError(wrapped) != errSpace || strings.Contains(diskError(wrapped).Error(), "SECRET") {
			t.Fatal("space error not sanitized")
		}
		var retry *attemptError
		if errors.As(diskError(wrapped), &retry) {
			t.Fatal("ENOSPC should not retry")
		}
	}
	if diskError(io.ErrShortWrite) != errFile {
		t.Fatal("short writes ignored")
	}
}

func TestInjectedProbeDoesNotBypassNetworkSafety(t *testing.T) {
	m, err := newManager(t.TempDir(), 1, func(context.Context, model.Track, string) (string, error) { return "https://127.0.0.1/private", nil }, func(model.DownloadJob) error { return nil }, nil,
		dependencies{probe: func(*os.Root) (storage.Info, error) { return capacity(1 << 30), nil }})
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	job := createJob(t, m, "ssrf")
	failed := waitJob(t, m, job.ID, "failed")
	if failed.Error != errUnsafeURL.Error() {
		t.Fatalf("probe bypassed SSRF: %+v", failed)
	}
}

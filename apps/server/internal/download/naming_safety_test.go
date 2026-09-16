package download

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"melora/internal/model"
)

func TestV10NamingLegacyCollisionUsesNumbersEvenForNumericID(t *testing.T) {
	for _, id := range []string{"fb725364c3366da197ce4e269167d6b0", "12345", "000123"} {
		t.Run(id, func(t *testing.T) {
			root, clean, err := openStorage(t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			defer root.Close()
			j := model.DownloadJob{ID: id, Track: testTrack(id), FileNameFormat: "title-artist", State: "finalizing"}
			base := baseStem(j)
			legacy := base + " (" + id + ")"
			j.TargetPath = filepath.Join(clean, "Singles", legacy+".audio")
			if err := root.WriteFile(legacy+".flac", []byte("user-file"), 0600); err != nil {
				t.Fatal(err)
			}
			f, err := openRegular(root, partName(id), true)
			if err != nil {
				t.Fatal(err)
			}
			defer f.Close()
			if _, err := f.Write(v5MP3()); err != nil {
				t.Fatal(err)
			}
			e := &entry{job: j, root: clean}
			m := &Manager{jobs: map[string]*entry{id: e}, persist: func(model.DownloadJob) error { return nil }}
			meta := partialMeta{Version: 1, Extension: ".mp3", Total: int64(len(v5MP3())), SHA256: strings.Repeat("0", 64)}
			m.mu.Lock()
			err = m.publishNamedLocked(context.Background(), e, root, f, partName(id), &j, &meta)
			m.mu.Unlock()
			if err != nil || filepath.Base(j.TargetPath) != base+" (2).mp3" {
				t.Fatalf("legacy conflict must allocate short number: %q %v", j.TargetPath, err)
			}
			b, err := root.ReadFile(legacy + ".flac")
			if err != nil || string(b) != "user-file" {
				t.Fatal("legacy user's file changed")
			}
		})
	}
}

func TestV10NamingLateCollisionBoundKeepsSource(t *testing.T) {
	root, clean, err := openStorage(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	j := model.DownloadJob{ID: "bounded-publish", Track: testTrack("bound"), FileNameFormat: "title-artist", State: "finalizing"}
	base := baseStem(j)
	j.TargetPath = filepath.Join(clean, "Singles", base+" (9999).audio")
	f, err := openRegular(root, partName(j.ID), true)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := f.Write(v5MP3()); err != nil {
		t.Fatal(err)
	}
	e := &entry{job: j, root: clean}
	calls := 0
	m := &Manager{jobs: map[string]*entry{j.ID: e}, persist: func(next model.DownloadJob) error {
		calls++
		return root.WriteFile(filepath.Base(next.TargetPath), []byte("user-file"), 0600)
	}}
	meta := partialMeta{Version: 1, Extension: ".mp3", Total: int64(len(v5MP3())), SHA256: strings.Repeat("0", 64)}
	m.mu.Lock()
	err = m.publishNamedLocked(context.Background(), e, root, f, partName(j.ID), &j, &meta)
	m.mu.Unlock()
	if !errors.Is(err, errCollision) || calls != 2 {
		t.Fatalf("unbounded retry: calls=%d err=%v", calls, err)
	}
	b, err := root.ReadFile(partName(j.ID))
	if err != nil || !bytes.Equal(b, v5MP3()) || !sameOpenFile(root, partName(j.ID), f) {
		t.Fatal("exhaustion removed/replaced source")
	}
	for _, n := range []int{9999, 10000} {
		b, err := root.ReadFile(v10Name(base, n) + ".mp3")
		if err != nil || string(b) != "user-file" {
			t.Fatal("exhaustion overwrote user file")
		}
	}
	if _, err := root.Lstat(base + " (10001).mp3"); !os.IsNotExist(err) {
		t.Fatal("exceeded candidate bound")
	}
}

func TestV10NamingPinnedDirectorySurvivesPathSwap(t *testing.T) {
	root := t.TempDir()
	replacement := t.TempDir()
	gate := make(chan struct{})
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, func(ctx context.Context, tr model.Track, q string) (string, error) {
		select {
		case <-gate:
			return fixedResolver(ctx, tr, q)
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}, nil, nil, nil)
	j := createJob(t, m, "pinned")
	base := baseStem(j)
	if err := m.files.WriteFile(base+".flac", []byte("original-dir-file"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(replacement, "sentinel"), []byte("untouched"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(root, "Singles"), filepath.Join(root, "original-Singles")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(replacement, filepath.Join(root, "Singles")); err != nil {
		t.Fatal(err)
	}
	close(gate)
	done := rangeTerminal(t, m, j.ID)
	if done.State != "completed" || filepath.Base(done.TargetPath) != base+" (2).mp3" {
		t.Fatalf("lost pinned directory: %+v", done)
	}
	b, err := m.files.ReadFile(base + " (2).mp3")
	if err != nil || !bytes.Equal(b, v5MP3()) {
		t.Fatal("not published via original FD")
	}
	entries, err := os.ReadDir(replacement)
	if err != nil || len(entries) != 1 || entries[0].Name() != "sentinel" {
		t.Fatal("followed replacement directory symlink")
	}
}

func TestV10NamingPublishRejectsReplacedSource(t *testing.T) {
	for _, tagged := range []bool{false, true} {
		t.Run(fmt.Sprint(tagged), func(t *testing.T) {
			root := t.TempDir()
			var swapped atomic.Bool
			var source, kept string
			store := &recordingStore{reject: func(j model.DownloadJob) bool {
				if j.State == "finalizing" && swapped.CompareAndSwap(false, true) {
					name := partName(j.ID)
					if tagged {
						name = tagPartName(j.ID)
					}
					source = filepath.Join(root, "Singles", name)
					kept = source + "-kept"
					if err := os.Rename(source, kept); err != nil {
						t.Error(err)
					}
					if err := os.WriteFile(source, []byte("foreign-source"), 0600); err != nil {
						t.Error(err)
					}
				}
				return false
			}}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, store, nil, nil)
			j, err := m.CreateWithOptions(testTrack("source-swap"), "standard", model.Settings{FileNameFormat: "title-artist", EmbedTags: tagged})
			if err != nil {
				t.Fatal(err)
			}
			failed := rangeTerminal(t, m, j.ID)
			if failed.State != "failed" || failed.Error != errFile.Error() {
				t.Fatalf("replaced source published: %+v", failed)
			}
			if _, err := os.Lstat(failed.TargetPath); !os.IsNotExist(err) {
				t.Fatal("published unverified source")
			}
			b, err := os.ReadFile(source)
			if err != nil || string(b) != "foreign-source" {
				t.Fatal("cleanup removed foreign source")
			}
			b, err = os.ReadFile(kept)
			if err != nil || !bytes.Equal(v5Payload(t, b), v5MP3()) {
				t.Fatal("lost original opened source")
			}
		})
	}
}

func TestV10NamingLegacyFailedRetryRetainsPartAndName(t *testing.T) {
	root := t.TempDir()
	body := v5MP3()
	j := seedPartial(t, root, body[:417], int64(len(body)))
	j.FileNameFormat = "title-artist"
	j.TargetPath = filepath.Join(root, "Singles", baseStem(j)+" ("+j.ID+").mp3")
	var allow atomic.Bool
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		if r.Header.Get("Range") != "bytes=417-" {
			t.Errorf("retry lost existing part: %q", r.Header.Get("Range"))
		}
		res := response(r, 206, body[417:])
		res.Header.Set("Content-Range", fmt.Sprintf("bytes 417-%d/%d", len(body)-1, len(body)))
		return res, nil
	}, func(ctx context.Context, tr model.Track, q string) (string, error) {
		if !allow.Load() {
			return "", errResolve
		}
		return fixedResolver(ctx, tr, q)
	}, nil, []model.DownloadJob{j}, nil)
	base := baseStem(j)
	if err := m.files.WriteFile(base+".mp3", []byte("user-file"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Action(j.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	failed := rangeTerminal(t, m, j.ID)
	if failed.State != "failed" || failed.TargetPath != j.TargetPath {
		t.Fatalf("failed legacy snapshot renamed: %+v", failed)
	}
	before, err := m.files.ReadFile(metaName(j.ID))
	if err != nil {
		t.Fatal(err)
	}
	b, err := m.files.ReadFile(partName(j.ID))
	if err != nil || !bytes.Equal(b, body[:417]) {
		t.Fatal("failure discarded checkpoint-bound part")
	}
	allow.Store(true)
	queued, err := m.Action(j.ID, "retry")
	if err != nil || queued.TargetPath != j.TargetPath {
		t.Fatalf("retry silently shortened legacy name: %+v %v", queued, err)
	}
	done := rangeTerminal(t, m, j.ID)
	if done.State != "completed" || done.TargetPath != j.TargetPath || !bytes.Equal(readTarget(t, done), body) {
		t.Fatalf("legacy retry lost binding: %+v", done)
	}
	if _, err := m.Create(j.Track, j.Quality); err == nil {
		t.Fatal("duplicate completed task allowed")
	}
	if !bytes.Equal(readTarget(t, done), body) {
		t.Fatal("deduplication removed final file")
	}
	b, err = m.files.ReadFile(base + ".mp3")
	if err != nil || string(b) != "user-file" || len(before) == 0 {
		t.Fatal("retry/deduplication touched user's file")
	}
}

package download

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"melora/internal/model"
	"melora/internal/storage"
)

func TestMetadataJSONWhitelistBoundsAndSafeText(t *testing.T) {
	job := model.DownloadJob{ID: "safe", Track: testTrack("track"), Quality: "standard", BytesDone: 2048,
		TargetPath: "/private/audio.mp3", MetadataPath: "/private/audio.json", Error: "token=ERROR-SECRET", Warning: "token=WARNING-SECRET"}
	job.Track.Album = "唱片 <2026> & AC/DC"
	job.Track.CoverURL = "https://cover.invalid/?token=COVER-SECRET"
	data, err := metadataJSON(job)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(data, &got); err != nil {
		t.Fatal(err)
	}
	if len(got) != 8 || got["version"] != float64(1) || got["bytes"] != float64(2048) || got["album"] != job.Track.Album || got["providerId"] != job.Track.ProviderID || got["trackId"] != job.Track.ID || got["title"] != job.Track.Title || got["artist"] != job.Track.Artist || got["quality"] != job.Quality {
		t.Fatalf("not exact whitelist: %s", data)
	}
	for _, forbidden := range []string{"http", "url", "token", "SECRET", "/private", "cover", "TargetPath", "Warning"} {
		if bytes.Contains(bytes.ToLower(data), []byte(strings.ToLower(forbidden))) {
			t.Fatalf("leaked %s: %s", forbidden, data)
		}
	}
	for _, bad := range []string{strings.Repeat("a", 4097), "https://private/?token=SECRET", "file:///private/key", "/private/key", "path=/private/key", `C:\private\key`, `\\nas\private`, "token=SECRET", "Authorization: Bearer SECRET", "bad\x00title", "bad\u202etitle", string([]byte{0xff})} {
		job.Track.Album = bad
		if _, err := metadataJSON(job); !errors.Is(err, errMetadataText) {
			t.Fatalf("unsafe text accepted %q: %v", bad, err)
		}
	}
	job.Track.Album = strings.Repeat("<", 4096)
	job.Track.Artist = job.Track.Album
	job.Track.Title = job.Track.Album
	if _, err := metadataJSON(job); !errors.Is(err, errMetadataText) {
		t.Fatalf("unbounded JSON escaping: %v", err)
	}
}

func TestSidecarNeverOverwritesFilesOrLinks(t *testing.T) {
	for _, kind := range []string{"file", "symlink", "hardlink", "directory"} {
		t.Run(kind, func(t *testing.T) {
			base := t.TempDir()
			root, _, err := openStorage(base)
			if err != nil {
				t.Fatal(err)
			}
			defer root.Close()
			victim := filepath.Join(base, "victim")
			original := []byte("DO NOT CHANGE")
			if err := os.WriteFile(victim, original, 0600); err != nil {
				t.Fatal(err)
			}
			target := filepath.Join(base, "Singles", "track.json")
			switch kind {
			case "file":
				err = os.WriteFile(target, original, 0600)
			case "symlink":
				err = os.Symlink(victim, target)
			case "hardlink":
				err = os.Link(victim, target)
			case "directory":
				err = os.Mkdir(target, 0700)
			}
			if err != nil {
				t.Fatal(err)
			}
			before, _ := os.Lstat(target)
			if err := writeSidecar(root, "track.json", []byte(`{"version":1}`)); !errors.Is(err, errCollision) {
				t.Fatalf("overwrote %s: %v", kind, err)
			}
			after, _ := os.Lstat(target)
			if !os.SameFile(before, after) {
				t.Fatal("replaced existing inode")
			}
			got, _ := os.ReadFile(victim)
			if !bytes.Equal(got, original) {
				t.Fatal("modified link victim")
			}
		})
	}
}

func TestSidecarConcurrentAtomicPublish(t *testing.T) {
	root, storagePath, err := openStorage(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	data := bytes.Repeat([]byte("x"), maxMetadataBytes)
	var wg sync.WaitGroup
	var successes atomic.Int32
	for range 16 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			err := writeSidecar(root, "track.json", data)
			if err == nil {
				successes.Add(1)
			} else if !errors.Is(err, errCollision) {
				t.Errorf("publish: %v", err)
			}
		}()
	}
	wg.Wait()
	if successes.Load() != 1 {
		t.Fatalf("%d successful publishers", successes.Load())
	}
	got, err := root.ReadFile("track.json")
	if err != nil || !bytes.Equal(got, data) {
		t.Fatal("partial published JSON")
	}
	// os.Root.Name 的绝对/相对形式在 Go 1.25 补丁版本间有差异；
	// 用 openStorage 返回的规范路径验证真实 Singles 目录。
	entries, err := os.ReadDir(filepath.Join(storagePath, "Singles"))
	if err != nil || len(entries) != 1 {
		t.Fatalf("leaked temporary files: %v %v", entries, err)
	}
	for _, name := range []string{"../escape.json", "/escape.json", `..\escape.json`, "not-json"} {
		if err := writeSidecar(root, name, data); err == nil {
			t.Fatalf("unsafe name %q", name)
		}
	}
	if err := writeSidecar(root, "large.json", append(data, 'x')); err == nil {
		t.Fatal("unbounded output")
	}
}

func TestSidecarIdentityRejectsSourceSubstitutionAndHardlinks(t *testing.T) {
	for _, kind := range []string{"symlink", "hardlink", "replacement"} {
		t.Run(kind, func(t *testing.T) {
			root, _, err := openStorage(t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			defer root.Close()
			file, err := openRegular(root, "temp", true)
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			if !sameOpenFile(root, "temp", file) {
				t.Fatal("original identity rejected")
			}
			if kind == "hardlink" {
				err = root.Link("temp", "extra")
			} else {
				if err := root.Rename("temp", "original"); err != nil {
					t.Fatal(err)
				}
				if kind == "symlink" {
					err = root.Symlink("original", "temp")
				} else {
					err = root.WriteFile("temp", []byte("replacement"), 0600)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			if sameOpenFile(root, "temp", file) {
				t.Fatal("unsafe source accepted")
			}
		})
	}
}

func TestNewJobsNeverWriteJSONAfterDeprecatedSetting(t *testing.T) {
	root := t.TempDir()
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, audio(2048)), nil }, nil, nil, nil, nil)
	m.SetWriteMetadata(true)
	job := createJob(t, m, "new-json-disabled")
	done := waitJob(t, m, job.ID, "completed")
	if job.WriteMetadata || done.WriteMetadata || done.MetadataPath != "" {
		t.Fatal("new job opted into removed JSON option")
	}
	if _, err := os.Stat(metadataPath(done)); !os.IsNotExist(err) {
		t.Fatal("new job wrote user JSON")
	}
}

// 模拟由旧版本持久化而来的 paused 任务，不能通过新 Create 或全局设置制造旧快照。
func legacyMetadataJob(t *testing.T, m *Manager, track model.Track) model.DownloadJob {
	t.Helper()
	id, err := randomID()
	if err != nil {
		t.Fatal(err)
	}
	job := model.DownloadJob{ID: id, Track: track, Quality: "standard", State: "paused", WriteMetadata: true, CreatedAt: timestamp(), UpdatedAt: timestamp()}
	m.mu.Lock()
	job.TargetPath = targetPath(m.root, job, ".audio")
	m.jobs[id] = &entry{job: cloneJob(job), root: m.root}
	m.order = append(m.order, id)
	err = m.persist(cloneJob(job))
	m.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Action(id, "resume"); err != nil {
		t.Fatal(err)
	}
	return job
}

func TestSidecarWarningsPreserveAudioAndRecovery(t *testing.T) {
	for _, status := range []string{"written", "exists", "text", "space", "probe"} {
		t.Run(status, func(t *testing.T) {
			root := t.TempDir()
			var finalizing atomic.Bool
			store := &recordingStore{reject: func(job model.DownloadJob) bool {
				if job.State == "finalizing" {
					finalizing.Store(true)
					if status == "exists" {
						if err := os.WriteFile(metadataPath(job), []byte("user JSON"), 0600); err != nil {
							t.Error(err)
						}
					}
				}
				return job.State == "completed" // 模拟音频/sidecar 已发布，DB 完成提交失败。
			}}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, audio(2048)), nil }, nil, store, nil, func(d *dependencies) {
				d.probe = func(*os.Root) (storage.Info, error) {
					if finalizing.Load() && status == "space" {
						return capacity(spaceReserve - 1), nil
					}
					if finalizing.Load() && status == "probe" {
						return storage.Info{}, errors.New("token=PRIVATE-PROBE")
					}
					return capacity(1 << 30), nil
				}
			})
			m.SetWriteMetadata(true)
			track := testTrack("warn")
			if status == "text" {
				track.Album = strings.Repeat("a", 4097)
			}
			job := legacyMetadataJob(t, m, track)
			done := waitJob(t, m, job.ID, "completed")
			waitIdle(t, m, job.ID)
			if done.Error != errPersist.Error() || done.Warning != sidecarWarning(status) || (done.MetadataPath != "") != (status == "written") {
				t.Fatalf("incorrect completion: %+v", done)
			}
			if !bytes.Equal(readTarget(t, done), audio(2048)) {
				t.Fatal("lost audio")
			}
			m.Close()
			saved := store.get(job.ID)
			if saved.State != "finalizing" {
				t.Fatalf("checkpoint not finalizing: %+v", saved)
			}
			var before os.FileInfo
			if status == "written" || status == "exists" {
				before, _ = os.Lstat(metadataPath(done))
			}
			restoredStore := &recordingStore{}
			m2 := testManager(t, root, func(*http.Request) (*http.Response, error) { t.Error("recovery used network"); return nil, errNetwork }, nil, restoredStore, []model.DownloadJob{saved}, nil)
			if _, err := m2.Action(job.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			restored := waitJob(t, m2, job.ID, "completed")
			if restored.Error != "" || restored.MetadataPath != done.MetadataPath || restored.Warning != done.Warning {
				t.Fatalf("inconsistent recovery: before=%+v after=%+v", done, restored)
			}
			if before != nil {
				after, _ := os.Lstat(metadataPath(done))
				if !os.SameFile(before, after) || !before.ModTime().Equal(after.ModTime()) {
					t.Fatal("recovery changed existing sidecar")
				}
			}
			m2.Close()
			m3 := testManager(t, root, nil, nil, nil, []model.DownloadJob{restoredStore.get(job.ID)}, nil)
			if m3.List()[0].Warning != restored.Warning || m3.List()[0].MetadataPath != restored.MetadataPath {
				t.Fatal("completed restart inconsistent")
			}
		})
	}
}

func TestMetadataRecoveryRejectsArbitraryDBPaths(t *testing.T) {
	root := t.TempDir()
	for _, invalid := range []string{"../../private.json", "/tmp/private.json", filepath.Join(root, "Singles", "arbitrary.json")} {
		job := model.DownloadJob{ID: "restore", Track: testTrack("restore"), Quality: "standard", State: "completed", WriteMetadata: true, MetadataPath: invalid}
		job.TargetPath = targetPath(root, job, ".mp3")
		m, err := New(root, 1, fixedResolver, func(model.DownloadJob) error { t.Error("persisted invalid metadata path"); return nil }, []model.DownloadJob{job})
		if m != nil {
			m.Close()
		}
		if !errors.Is(err, errMetadataPath) {
			t.Fatalf("accepted arbitrary path %q: %v", invalid, err)
		}
	}
}

func TestMetadataRestoreDoesNotTrustLinksOrChangedJSON(t *testing.T) {
	for _, kind := range []string{"symlink", "hardlink", "changed", "oversize", "missing", "other-root"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			store := &recordingStore{}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, audio(2048)), nil }, nil, store, nil, nil)
			m.SetWriteMetadata(true)
			job := legacyMetadataJob(t, m, testTrack("restore"))
			done := waitJob(t, m, job.ID, "completed")
			m.Close()
			data, _ := os.ReadFile(done.MetadataPath)
			if err := os.Remove(done.MetadataPath); err != nil {
				t.Fatal(err)
			}
			victim := filepath.Join(t.TempDir(), "private.json")
			if err := os.WriteFile(victim, data, 0600); err != nil {
				t.Fatal(err)
			}
			var err error
			switch kind {
			case "symlink":
				err = os.Symlink(victim, done.MetadataPath)
			case "hardlink":
				err = os.Link(victim, done.MetadataPath)
			case "changed":
				err = os.WriteFile(done.MetadataPath, []byte(`{"url":"https://private/?token=SECRET"}`), 0600)
			case "oversize":
				err = os.WriteFile(done.MetadataPath, bytes.Repeat([]byte("x"), maxMetadataBytes+1), 0600)
			case "other-root":
				root = t.TempDir()
			}
			if err != nil {
				t.Fatal(err)
			}
			restoredStore := &recordingStore{}
			m2 := testManager(t, root, nil, nil, restoredStore, []model.DownloadJob{store.get(job.ID)}, nil)
			restored := m2.List()[0]
			if restored.State != "completed" || restored.MetadataPath != "" || restored.Warning == "" || restoredStore.get(job.ID).MetadataPath != "" {
				t.Fatalf("trusted bad metadata: %+v", restored)
			}
			got, _ := os.ReadFile(victim)
			if !bytes.Equal(got, data) {
				t.Fatal("restore modified external file")
			}
		})
	}
}

func TestLegacyJobDoesNotOptInAfterRestart(t *testing.T) {
	root := t.TempDir()
	original := seedPartial(t, root, audio(1024), 2048)
	encoded, _ := json.Marshal(original)
	var object map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &object); err != nil {
		t.Fatal(err)
	}
	delete(object, "writeMetadata")
	encoded, _ = json.Marshal(object)
	var legacy model.DownloadJob
	if err := json.Unmarshal(encoded, &legacy); err != nil {
		t.Fatal(err)
	}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		resp := response(r, 206, audio(2048)[1024:])
		resp.Header.Set("Content-Range", "bytes 1024-2047/2048")
		return resp, nil
	}, nil, nil, []model.DownloadJob{legacy}, nil)
	m.SetWriteMetadata(true)
	if _, err := m.Action(legacy.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	done := waitJob(t, m, legacy.ID, "completed")
	if done.WriteMetadata || done.MetadataPath != "" || done.Warning != "" {
		t.Fatalf("legacy opt-in: %+v", done)
	}
	if _, err := os.Lstat(metadataPath(done)); !os.IsNotExist(err) {
		t.Fatal("retroactively wrote sidecar")
	}
}

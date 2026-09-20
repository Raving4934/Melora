package download

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"melora/internal/model"
)

func TestV10NamingCheckpointAheadOfSnapshotRecovery(t *testing.T) {
	for _, kind := range []string{"numbered", "numbered-snapshot", "legacy-ID", "legacy-empty-format", "tampered", "invalid-number", "wrong-ID"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			files, _, err := openStorage(root)
			if err != nil {
				t.Fatal(err)
			}
			defer files.Close()
			body := v9MIMEFixture(t, ".flac")
			j := model.DownloadJob{ID: "fb725364c3366da197ce4e269167d6b0", Track: testTrack("recover"), Quality: "lossless", FileNameFormat: "title-artist", State: "finalizing", CreatedAt: timestamp()}
			j.Track.Title, j.Track.Artist = "明天见", "世界之外"
			if kind == "legacy-empty-format" {
				j.FileNameFormat = ""
			}
			base := baseStem(j)
			final := base + " (3).flac"
			switch kind {
			case "legacy-ID":
				final = base + " (" + j.ID + ").flac"
			case "legacy-empty-format":
				final = base + ".flac"
			case "invalid-number":
				final = base + " (03).flac"
			case "wrong-ID":
				final = base + " (another-task).flac"
			}
			j.TargetPath = filepath.Join(root, "Singles", base+".audio")
			if kind == "numbered-snapshot" || kind == "legacy-empty-format" {
				j.TargetPath = filepath.Join(root, "Singles", final)
			}
			sum := sha256.Sum256(body)
			meta := partialMeta{Version: 1, Total: int64(len(body)), Extension: ".flac", Final: final, SHA256: fmt.Sprintf("%x", sum)}
			if err := saveMeta(files, j.ID, meta); err != nil {
				t.Fatal(err)
			}
			diskBody := append([]byte(nil), body...)
			if kind == "tampered" {
				diskBody[len(diskBody)-1] ^= 1
			}
			if err := files.WriteFile(final, diskBody, 0600); err != nil {
				t.Fatal(err)
			}
			before, err := files.Lstat(final)
			if err != nil {
				t.Fatal(err)
			}
			m := testManager(t, root, nil, func(context.Context, model.Track, string) (string, error) {
				t.Error("published checkpoint must not resolve/download")
				return "", errResolve
			}, nil, []model.DownloadJob{j}, nil)
			if _, err := m.Action(j.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			got := rangeTerminal(t, m, j.ID)
			bad := kind == "tampered" || kind == "invalid-number" || kind == "wrong-ID"
			if bad {
				if got.State != "failed" {
					t.Fatalf("invalid checkpoint accepted: %+v", got)
				}
			} else if got.State != "completed" || got.TargetPath != filepath.Join(root, "Singles", final) {
				t.Fatalf("valid checkpoint not recovered: %+v", got)
			}
			after, err := files.Lstat(final)
			b, readErr := files.ReadFile(final)
			if err != nil || readErr != nil || !os.SameFile(before, after) || !bytes.Equal(b, diskBody) {
				t.Fatal("recovery modified published file")
			}
			if _, err := files.Lstat(partName(j.ID)); !os.IsNotExist(err) {
				t.Fatal("recovery created unexpected part")
			}
			if !bad {
				m.Close()
				// 完成快照第二次加载仍保持长/短路径原样，不批量改名。
				again := testManager(t, root, nil, nil, nil, []model.DownloadJob{got}, nil)
				if again.List()[0].TargetPath != got.TargetPath {
					t.Fatal("completed path renamed on restart")
				}
			}
		})
	}
}

func TestV10NamingCrashWindowsPreservePartAndSidecarBinding(t *testing.T) {
	for _, tagged := range []bool{false, true} {
		for _, stage := range []string{"finalizing", "finalizing-reserved", "completed"} {
			t.Run(fmt.Sprintf("tags=%v/%s", tagged, stage), func(t *testing.T) {
				root := t.TempDir()
				store := &recordingStore{}
				base := "测试歌曲 - 演示艺术家"
				var collided atomic.Bool
				store.reject = func(j model.DownloadJob) bool {
					if j.State == "finalizing" && collided.CompareAndSwap(false, true) {
						if err := os.WriteFile(j.TargetPath, []byte("user-audio"), 0600); err != nil {
							t.Error(err)
						}
					}
					return j.State == strings.SplitN(stage, "-", 2)[0] && filepath.Base(j.TargetPath) == base+" (2).mp3"
				}
				assets := v5Assets(t)
				m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, store, nil, nil)
				fetch := func(context.Context, model.DownloadJob) (MetadataAssets, error) { return assets, nil }
				if err := m.SetMetadataFetcher(fetch); err != nil {
					t.Fatal(err)
				}
				j, err := m.CreateWithOptions(testTrack("crash"), "standard", model.Settings{FileNameFormat: "title-artist", WriteLyrics: true, WriteCover: true, EmbedTags: tagged})
				if err != nil {
					t.Fatal(err)
				}
				got := rangeTerminal(t, m, j.ID)
				expectedState := "failed"
				if stage == "completed" {
					expectedState = "completed"
				}
				if got.State != expectedState || got.Error != errPersist.Error() {
					t.Fatalf("fault injection not reached: %+v", got)
				}
				saved := store.get(j.ID)
				meta, err := loadMeta(m.files, j.ID)
				if err != nil || meta.Final != base+" (2).mp3" {
					t.Fatalf("checkpoint did not lead snapshot: %+v %v", meta, err)
				}
				if stage != "completed" {
					b, err := m.files.ReadFile(partName(j.ID))
					if err != nil || !bytes.Equal(b, v5MP3()) {
						t.Fatal("failed commit destroyed source part")
					}
				}
				var published os.FileInfo
				if stage == "completed" {
					published, err = m.files.Lstat(meta.Final)
					if err != nil {
						t.Fatal(err)
					}
				}
				m.Close()
				var requests atomic.Int32
				recovered := testManager(t, root, func(r *http.Request) (*http.Response, error) {
					requests.Add(1)
					if stage == "completed" {
						t.Error("published audio requested again")
					}
					if r.Header.Get("Range") != fmt.Sprintf("bytes=%d-", len(v5MP3())) {
						t.Errorf("lost part offset: %q", r.Header.Get("Range"))
					}
					res := response(r, 416, nil)
					res.Header.Set("Content-Range", fmt.Sprintf("bytes */%d", len(v5MP3())))
					return res, nil
				}, nil, nil, []model.DownloadJob{saved}, nil)
				if err := recovered.SetMetadataFetcher(fetch); err != nil {
					t.Fatal(err)
				}
				wantFinal := base + " (2).mp3"
				if stage == "finalizing-reserved" {
					// checkpoint 领先的短号被另一暂停任务预留，原 part 仍在时不能抢名。
					other := cloneJob(saved)
					other.ID, other.Track.ID, other.State = "contender", "contender", "paused"
					other.TargetPath = filepath.Join(root, "Singles", base+" (2).audio")
					recovered.mu.Lock()
					recovered.jobs[other.ID] = &entry{job: other, root: root}
					recovered.order = append(recovered.order, other.ID)
					recovered.mu.Unlock()
					wantFinal = base + " (3).mp3"
				}
				if _, err := recovered.Action(j.ID, "resume"); err != nil {
					t.Fatal(err)
				}
				done := rangeTerminal(t, recovered, j.ID)
				if done.State != "completed" || filepath.Base(done.TargetPath) != wantFinal || !bytes.Equal(v5Payload(t, readTarget(t, done)), v5MP3()) {
					t.Fatalf("recovery lost numbered binding: %+v", done)
				}
				if stage == "completed" {
					after, err := recovered.files.Lstat(meta.Final)
					if err != nil || !os.SameFile(published, after) || requests.Load() != 0 {
						t.Fatal("recovery republished audio")
					}
				} else if requests.Load() != 1 {
					t.Fatal("expected bounded 416 verification")
				}
				for ext, path := range map[string]string{".lrc": done.LyricsPath, ".png": done.CoverPath} {
					if path != filepath.Join(root, "Singles", strings.TrimSuffix(wantFinal, ".mp3")+ext) {
						t.Fatalf("recovered sidecar detached: %q", path)
					}
				}
				b, err := recovered.files.ReadFile(base + ".mp3")
				if err != nil || string(b) != "user-audio" {
					t.Fatal("retry replaced original user file")
				}
			})
		}
	}
}

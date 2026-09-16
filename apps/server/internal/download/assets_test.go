package download

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
	"melora/internal/storage"
)

func v5MP3() []byte {
	b := make([]byte, 417*4)
	for i := 0; i < 4; i++ {
		copy(b[i*417:], []byte{255, 251, 144, 100})
		for n := 4; n < 417; n++ {
			b[i*417+n] = byte(n + i)
		}
	}
	return b
}
func v5Cover(t *testing.T) []byte {
	t.Helper()
	im := image.NewNRGBA(image.Rect(0, 0, 2, 2))
	im.Set(1, 1, color.NRGBA{B: 255, A: 255})
	var out bytes.Buffer
	if err := png.Encode(&out, im); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}
func v5Assets(t *testing.T) MetadataAssets {
	return MetadataAssets{Lyrics: "[00:00.00]独立测试歌词\n[00:01.00]下一句\n", Cover: v5Cover(t), CoverMIME: "image/png"}
}
func v5Payload(t *testing.T, b []byte) []byte {
	t.Helper()
	if len(b) < 10 || string(b[:3]) != "ID3" {
		return b
	}
	n := 0
	for _, v := range b[6:10] {
		if v > 127 {
			t.Fatal("invalid ID3 syncsafe size")
		}
		n = n<<7 | int(v)
	}
	if n+10 > len(b) {
		t.Fatal("bad ID3 size")
	}
	return b[n+10:]
}
func TestV5OptionsSnapshotsAndPerJobOverride(t *testing.T) {
	gate := make(chan struct{})
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, func(ctx context.Context, tr model.Track, q string) (string, error) {
		select {
		case <-gate:
			return fixedResolver(ctx, tr, q)
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}, nil, nil, nil)
	if err := m.SetOptions(model.Settings{FileNameFormat: "title", WriteLyrics: true}); err != nil {
		t.Fatal(err)
	}
	first := createJob(t, m, "one")
	second, err := m.CreateWithOptions(testTrack("two"), "standard", model.Settings{FileNameFormat: "artist-title", WriteCover: true, EmbedTags: true})
	if err != nil {
		t.Fatal(err)
	}
	third := createJob(t, m, "three")
	if first.FileNameFormat != "title" || !first.WriteLyrics || second.FileNameFormat != "artist-title" || second.WriteLyrics || !second.WriteCover || !second.EmbedTags || third.FileNameFormat != "title" || !third.WriteLyrics {
		t.Fatal("override altered defaults or snapshots")
	}
	if err := m.SetOptions(model.Settings{FileNameFormat: "invalid", EmbedTags: true}); err == nil {
		t.Fatal("invalid options accepted")
	}
	fourth := createJob(t, m, "four")
	if fourth.FileNameFormat != "title" || fourth.EmbedTags {
		t.Fatal("partial option update")
	}
	if _, err := m.CreateWithOptions(testTrack("invalid"), "standard", model.Settings{FileNameFormat: "../escape"}); err == nil {
		t.Fatal("invalid override accepted")
	}
	for _, j := range []model.DownloadJob{first, second, third, fourth} {
		if _, err := m.Action(j.ID, "cancel"); err != nil {
			t.Fatal(err)
		}
	}
	close(gate)
}
func TestV5ThreeNamesSanitizationAndConflicts(t *testing.T) {
	for format, want := range map[string]string{"title-artist": "测试歌曲 - 演示艺术家", "artist-title": "演示艺术家 - 测试歌曲", "title": "测试歌曲"} {
		t.Run(format, func(t *testing.T) {
			root := t.TempDir()
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, nil, nil, nil)
			if err := m.SetOptions(model.Settings{FileNameFormat: format}); err != nil {
				t.Fatal(err)
			}
			one := createJob(t, m, "a")
			done := waitJob(t, m, one.ID, "completed")
			if filepath.Base(done.TargetPath) != want+".mp3" {
				t.Fatal(done.TargetPath)
			}
			two := createJob(t, m, "b")
			done2 := waitJob(t, m, two.ID, "completed")
			if filepath.Base(done2.TargetPath) != want+" (2).mp3" {
				t.Fatal("collision name not unique", done2.TargetPath)
			}
			if !bytes.Equal(readTarget(t, done), v5MP3()) || !bytes.Equal(readTarget(t, done2), v5MP3()) {
				t.Fatal("name collision overwrote audio")
			}
			track := testTrack("unsafe")
			track.Title = "../../CON\\\x00:<>"
			track.Artist = "/etc/passwd"
			job, err := m.Create(track, "standard")
			if err != nil {
				t.Fatal(err)
			}
			safe := waitJob(t, m, job.ID, "completed")
			if filepath.Dir(safe.TargetPath) != filepath.Join(root, "Singles") || strings.ContainsAny(filepath.Base(safe.TargetPath), `\:<>`) {
				t.Fatal("unsafe output filename")
			}
		})
	}
}
func TestV5LateNameCollisionPreservesExistingFile(t *testing.T) {
	root := t.TempDir()
	store := &recordingStore{}
	var created atomic.Bool
	store.reject = func(j model.DownloadJob) bool {
		if j.State == "finalizing" && created.CompareAndSwap(false, true) {
			if err := os.WriteFile(j.TargetPath, []byte("existing file"), 0600); err != nil {
				t.Error(err)
			}
		}
		return false
	}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, store, nil, nil)
	job := createJob(t, m, "late")
	done := waitJob(t, m, job.ID, "completed")
	expected := filepath.Join(root, "Singles", baseStem(job)+".mp3")
	b, err := os.ReadFile(expected)
	if err != nil || string(b) != "existing file" || filepath.Base(done.TargetPath) != baseStem(job)+" (2).mp3" {
		t.Fatal("late conflict overwrote existing file")
	}
}
func TestV5RealSidecarsTagsAndRecovery(t *testing.T) {
	root := t.TempDir()
	assets := v5Assets(t)
	store := &recordingStore{}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, store, nil, nil)
	if err := m.SetOptions(model.Settings{FileNameFormat: "title-artist", WriteLyrics: true, WriteCover: true, EmbedTags: true, WriteMetadata: true}); err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	if err := m.SetMetadataFetcher(func(ctx context.Context, j model.DownloadJob) (MetadataAssets, error) {
		calls.Add(1)
		m.List()
		if !j.WriteLyrics || !j.WriteCover || !j.EmbedTags {
			t.Error("fetcher lost snapshot")
		}
		j.Track.Qualities[0] = "mutated"
		if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > 9*time.Second {
			t.Error("fetcher missing deadline")
		}
		return assets, nil
	}); err != nil {
		t.Fatal(err)
	}
	job := createJob(t, m, "full")
	done := waitJob(t, m, job.ID, "completed")
	waitIdle(t, m, job.ID)
	if !done.TagsWritten || done.Warning != "" || done.BytesDone != int64(len(v5MP3())) || done.BytesTotal != done.BytesDone || done.WriteMetadata || done.MetadataPath != "" {
		t.Fatalf("bad completion %+v", done)
	}
	tagged := readTarget(t, done)
	if len(tagged) < 10 || tagged[3] != 4 || !bytes.Equal(v5Payload(t, tagged), v5MP3()) {
		t.Fatal("audio frames changed")
	}
	for _, frame := range []string{"TIT2", "TPE1", "APIC", "USLT"} {
		if !bytes.Contains(tagged, []byte(frame)) {
			t.Fatalf("missing tag %s", frame)
		}
	}
	stem := strings.TrimSuffix(done.TargetPath, ".mp3")
	if done.LyricsPath != stem+".lrc" || done.CoverPath != stem+".png" {
		t.Fatal("sidecars do not share final stem")
	}
	lrc, _ := os.ReadFile(done.LyricsPath)
	cover, _ := os.ReadFile(done.CoverPath)
	if string(lrc) != assets.Lyrics || !bytes.Equal(cover, assets.Cover) {
		t.Fatal("sidecars not real assets")
	}
	if calls.Load() != 1 || m.List()[0].Track.Qualities[0] != "standard" {
		t.Fatal("callback state mutation or duplicate fetch")
	}
	if _, err := os.Stat(metadataPath(done)); !os.IsNotExist(err) {
		t.Fatal("new task wrote JSON")
	}
	for _, p := range []string{partName(job.ID), tagPartName(job.ID)} {
		if _, err := os.Stat(filepath.Join(root, "Singles", p)); !os.IsNotExist(err) {
			t.Fatal("part remains", p)
		}
	}
	m.Close()
	m2 := testManager(t, root, nil, nil, nil, []model.DownloadJob{store.get(job.ID)}, nil)
	again := m2.List()[0]
	if !again.TagsWritten || again.LyricsPath != done.LyricsPath || again.CoverPath != done.CoverPath || again.TargetPath != done.TargetPath {
		t.Fatalf("restart changed results %+v", again)
	}
	if err := m2.SetOptions(model.Settings{FileNameFormat: "artist-title"}); err != nil {
		t.Fatal(err)
	}
	if m2.List()[0].TargetPath != done.TargetPath {
		t.Fatal("new options renamed history")
	}
}
func TestV5TagFailuresPreserveOriginalAudio(t *testing.T) {
	for _, kind := range []string{"unsupported", "invalid", "limit", "space", "hardlink-stage"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			var metadata atomic.Bool
			data := v5MP3()
			mime := "audio/mpeg"
			if kind == "invalid" {
				// 有合法音频头，但ID3内帧结构无效：不能用非音频测试标签回退。
				data = append([]byte("ID3\x04\x00\x00\x00\x00\x00\x0a"), bytes.Repeat([]byte{0x11}, 10)...)
				data = append(data, v5MP3()...)
			}
			if kind == "unsupported" {
				data = append([]byte("RIFF0000WAVE"), make([]byte, 1024)...)
				mime = "audio/wav"
			}
			store := &recordingStore{reject: func(j model.DownloadJob) bool {
				if j.State == "writing_metadata" {
					metadata.Store(true)
				}
				return false
			}}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, data)
				resp.Header.Set("Content-Type", mime)
				return resp, nil
			}, nil, store, nil, func(d *dependencies) {
				if kind == "limit" {
					d.limits.maxBytes = int64(len(data))
				}
				d.probe = func(r *os.Root) (storage.Info, error) {
					if metadata.Load() && kind == "space" {
						return capacity(spaceReserve - 1), nil
					}
					return capacity(1 << 30), nil
				}
			})
			if err := m.SetOptions(model.Settings{EmbedTags: true}); err != nil {
				t.Fatal(err)
			}
			assets := v5Assets(t)
			m.SetMetadataFetcher(func(_ context.Context, j model.DownloadJob) (MetadataAssets, error) {
				if kind == "hardlink-stage" {
					victim := filepath.Join(root, "victim")
					os.WriteFile(victim, []byte("untouched"), 0600)
					os.Link(victim, filepath.Join(root, "Singles", tagPartName(j.ID)))
				}
				return assets, nil
			})
			j := createJob(t, m, kind)
			done := waitJob(t, m, j.ID, "completed")
			if done.TagsWritten || done.Warning == "" || !bytes.Equal(readTarget(t, done), data) {
				t.Fatalf("metadata failure lost audio: %+v", done)
			}
			if kind == "hardlink-stage" {
				b, _ := os.ReadFile(filepath.Join(root, "victim"))
				if string(b) != "untouched" {
					t.Fatal("hardlinked victim changed")
				}
			}
		})
	}
}
func TestV5SidecarFailuresAndUnsafeAssets(t *testing.T) {
	for _, kind := range []string{"file", "symlink", "hardlink", "space", "probe", "bad-cover", "oversize", "secret"} {
		t.Run(kind, func(t *testing.T) {
			root := t.TempDir()
			assets := v5Assets(t)
			var finalizing atomic.Bool
			store := &recordingStore{reject: func(j model.DownloadJob) bool {
				if j.State == "finalizing" {
					finalizing.Store(true)
					dest := strings.TrimSuffix(j.TargetPath, ".mp3") + ".lrc"
					victim := filepath.Join(root, "victim")
					os.WriteFile(victim, []byte("keep"), 0600)
					switch kind {
					case "file":
						os.WriteFile(dest, []byte("keep"), 0600)
					case "symlink":
						os.Symlink(victim, dest)
					case "hardlink":
						os.Link(victim, dest)
					}
				}
				return false
			}}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, store, nil, func(d *dependencies) {
				d.probe = func(*os.Root) (storage.Info, error) {
					if finalizing.Load() {
						if kind == "space" {
							return capacity(spaceReserve - 1), nil
						}
						if kind == "probe" {
							return storage.Info{}, errors.New("token=PROBE-SECRET")
						}
					}
					return capacity(1 << 30), nil
				}
			})
			m.SetOptions(model.Settings{WriteLyrics: true, WriteCover: true})
			m.SetMetadataFetcher(func(context.Context, model.DownloadJob) (MetadataAssets, error) {
				switch kind {
				case "bad-cover":
					assets.Cover = []byte("<html>not a cover</html>")
				case "oversize":
					assets.Lyrics = strings.Repeat("x", MaxLyricsBytes+1)
					assets.Cover = make([]byte, MaxCoverBytes+1)
				case "secret":
					return MetadataAssets{Lyrics: "https://host/?token=PRIVATE"}, errors.New("token=FETCH-SECRET")
				}
				return assets, nil
			})
			job := createJob(t, m, kind)
			done := waitJob(t, m, job.ID, "completed")
			if done.Warning == "" || done.TagsWritten || !bytes.Equal(readTarget(t, done), v5MP3()) || strings.Contains(done.Warning, "SECRET") || strings.Contains(done.Warning, "PRIVATE") {
				t.Fatalf("unsafe failure: %+v", done)
			}
			if kind == "file" || kind == "symlink" || kind == "hardlink" {
				if done.LyricsPath != "" {
					t.Fatal("reported colliding sidecar as written")
				}
				b, _ := os.ReadFile(filepath.Join(root, "victim"))
				if string(b) != "keep" {
					t.Fatal("victim overwritten")
				}
			}
			if kind == "oversize" || kind == "secret" || kind == "space" || kind == "probe" {
				if done.LyricsPath != "" || done.CoverPath != "" {
					t.Fatal("reported failed paths")
				}
			}
		})
	}
}
func TestV5PauseDuringFetcherAndCloseKeepOriginalPart(t *testing.T) {
	for _, closeManager := range []bool{false, true} {
		root := t.TempDir()
		entered := make(chan struct{})
		store := &recordingStore{}
		m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, store, nil, nil)
		m.SetOptions(model.Settings{EmbedTags: true, WriteLyrics: true})
		m.SetMetadataFetcher(func(ctx context.Context, _ model.DownloadJob) (MetadataAssets, error) {
			close(entered)
			<-ctx.Done()
			return MetadataAssets{}, ctx.Err()
		})
		j := createJob(t, m, "pause")
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("fetcher not started")
		}
		if closeManager {
			m.Close()
		} else {
			if _, err := m.Action(j.ID, "pause"); err != nil {
				t.Fatal(err)
			}
		}
		if store.get(j.ID).State != "paused" || store.get(j.ID).TagsWritten {
			t.Fatal("metadata fetch pause was not persisted")
		}
		b, _ := os.ReadFile(filepath.Join(root, "Singles", partName(j.ID)))
		if !bytes.Equal(b, v5MP3()) {
			t.Fatal("pause damaged original part")
		}
		if !closeManager {
			if _, err := m.Action(j.ID, "cancel"); err != nil {
				t.Fatal(err)
			}
			requirePartAbsent(t, root, j.ID)
		}
	}
}
func TestV5CompletedCommitFailureRecoversWithoutRetagging(t *testing.T) {
	root := t.TempDir()
	assets := v5Assets(t)
	store := &recordingStore{reject: func(j model.DownloadJob) bool { return j.State == "completed" }}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, store, nil, nil)
	m.SetOptions(model.Settings{WriteLyrics: true, WriteCover: true, EmbedTags: true})
	m.SetMetadataFetcher(func(context.Context, model.DownloadJob) (MetadataAssets, error) { return assets, nil })
	job := createJob(t, m, "recovery")
	done := waitJob(t, m, job.ID, "completed")
	waitIdle(t, m, job.ID)
	if !done.TagsWritten || done.Error != errPersist.Error() {
		t.Fatalf("bad completion failure %+v", done)
	}
	before := sha256.Sum256(readTarget(t, done))
	info, _ := os.Stat(done.TargetPath)
	saved := store.get(job.ID)
	m.Close()
	m2 := testManager(t, root, func(*http.Request) (*http.Response, error) {
		t.Error("recovery downloaded audio again")
		return nil, errNetwork
	}, nil, nil, []model.DownloadJob{saved}, nil)
	m2.SetMetadataFetcher(func(context.Context, model.DownloadJob) (MetadataAssets, error) { return assets, nil })
	if _, err := m2.Action(job.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	restored := waitJob(t, m2, job.ID, "completed")
	after, _ := os.Stat(restored.TargetPath)
	if !restored.TagsWritten || restored.LyricsPath != done.LyricsPath || restored.CoverPath != done.CoverPath || before != sha256.Sum256(readTarget(t, restored)) || !os.SameFile(info, after) {
		t.Fatalf("recovery changed finalized audio or results %+v", restored)
	}
}
func TestV5PendingSidecarReceiptRecoversOnlyMatchingBytes(t *testing.T) {
	root := t.TempDir()
	files, _, err := openStorage(root)
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	j := model.DownloadJob{ID: "intent", Track: testTrack("intent"), Quality: "standard", FileNameFormat: "title", WriteLyrics: true}
	j.TargetPath = targetPath(root, j, ".mp3")
	data := []byte("[00:00]intent\n")
	hash := sha256.Sum256(data)
	meta := partialMeta{Version: 1, Extension: ".mp3", Final: jobStem(j) + ".mp3", Total: 10, SHA256: strings.Repeat("0", 64), Lyrics: assetReceipt{State: "pending", Ext: ".lrc", Bytes: int64(len(data)), SHA256: hexString(hash[:])}}
	files.WriteFile(jobStem(j)+".lrc", data, 0600)
	m := testManager(t, root, nil, nil, nil, nil, nil)
	path := m.completeAsset(files, j, &meta, &meta.Lyrics, ".lrc", nil)
	if path == "" || meta.Lyrics.State != "written" {
		t.Fatal("did not recover pending published asset")
	}
	meta.Lyrics.State = "pending"
	files.WriteFile(jobStem(j)+".lrc", []byte("user data"), 0600)
	if p := m.completeAsset(files, j, &meta, &meta.Lyrics, ".lrc", data); p != "" || meta.Lyrics.State != "exists" {
		t.Fatal("mismatched receipt overwrote user data")
	}
}
func hexString(b []byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, len(b)*2)
	for i, v := range b {
		out[2*i] = digits[v>>4]
		out[2*i+1] = digits[v&15]
	}
	return string(out)
}
func TestV5ConcurrentSettingsAndJobOverrides(t *testing.T) {
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, nil, nil, nil, nil)
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			m.SetOptions(model.Settings{FileNameFormat: "title"})
			track := testTrack("concurrent-" + string(rune('a'+i)))
			job, err := m.CreateWithOptions(track, "standard", model.Settings{FileNameFormat: "artist-title"})
			if err != nil {
				t.Error(err)
				return
			}
			if job.FileNameFormat != "artist-title" {
				t.Error("torn option snapshot")
			}
		}(i)
	}
	wg.Wait()
	m.Close()
	if err := m.SetOptions(model.Settings{}); !errors.Is(err, errClosed) {
		t.Fatal("SetOptions accepted after Close")
	}
	if err := m.SetMetadataFetcher(nil); !errors.Is(err, errClosed) {
		t.Fatal("SetMetadataFetcher accepted after Close")
	}
}
func TestV5ReceiptBoundsAndResultPaths(t *testing.T) {
	if receiptValid(assetReceipt{State: "written", Ext: ".png", Bytes: MaxCoverBytes + 1, SHA256: strings.Repeat("0", 64)}, true) || receiptValid(assetReceipt{State: "pending", Ext: "../x", Bytes: 1, SHA256: strings.Repeat("0", 64)}, false) {
		t.Fatal("unsafe receipt accepted")
	}
	j := model.DownloadJob{ID: "x", Track: testTrack("x"), FileNameFormat: "title", State: "completed", WriteLyrics: true, EmbedTags: true, TagsWritten: true}
	j.TargetPath = "/trusted/Singles/" + baseStem(j) + ".mp3"
	j.LyricsPath = "/outside.lyrics"
	if validateExtrasPath(j) == nil {
		t.Fatal("arbitrary result path accepted")
	}
}

func TestV5HTTPAudioDownloadDoesNotGuessHTTPS(t *testing.T) {
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		if r.URL.Scheme != "http" {
			t.Error("explicit HTTP URL upgraded unexpectedly")
		}
		pin, ok := r.Context().Value(pinKey{}).(pinnedTarget)
		if !ok || pin.port != "80" {
			t.Error("HTTP request lost fixed-IP transport context")
		}
		return response(r, 200, v5MP3()), nil
	}, func(context.Context, model.Track, string) (string, error) {
		return "http://media.example.com/audio?token=SECRET", nil
	}, nil, nil, nil)
	job := createJob(t, m, "http-source")
	done := waitJob(t, m, job.ID, "completed")
	if !bytes.Equal(readTarget(t, done), v5MP3()) || done.Error != "" {
		t.Fatal("public HTTP audio did not complete safely")
	}
}

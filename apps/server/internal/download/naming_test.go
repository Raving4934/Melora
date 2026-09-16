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
	"sync"
	"testing"

	"melora/internal/model"
)

// 编号契约直接写期望值，不能使用生产命名函数生成期望而掩盖回归。
func v10Name(base string, ordinal int) string {
	if ordinal == 1 {
		return base
	}
	return fmt.Sprintf("%s (%d)", base, ordinal)
}

func TestV10NamingConcurrentReservationsSurviveRestart(t *testing.T) {
	root := t.TempDir()
	store := &recordingStore{}
	resolve := func(ctx context.Context, _ model.Track, _ string) (string, error) { <-ctx.Done(); return "", ctx.Err() }
	m := testManager(t, root, nil, resolve, store, nil, nil)
	if err := m.Configure(root, 3); err != nil {
		t.Fatal(err)
	}
	const count = 16
	var wg sync.WaitGroup
	for i := range count {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			track := testTrack(fmt.Sprint(i))
			track.Title = "明天见"
			track.Artist = "世界之外"
			if _, err := m.Create(track, "standard"); err != nil {
				t.Error(err)
			}
		}(i)
	}
	wg.Wait()
	m.Close()
	store.mu.Lock()
	ordinal := 0
	for _, job := range store.records {
		if job.State != "queued" {
			continue
		}
		ordinal++
		want := v10Name("明天见 - 世界之外", ordinal) + ".audio"
		if filepath.Base(job.TargetPath) != want {
			t.Errorf("persisted reservation #%d: got %q, want %q", ordinal, filepath.Base(job.TargetPath), want)
		}
	}
	store.mu.Unlock()
	if ordinal != count {
		t.Fatalf("reservations=%d", ordinal)
	}
	before := m.List()
	// 故意反转输入，验证排序与编号各自稳定，恢复不能重新规划名称。
	initial := append([]model.DownloadJob(nil), before...)
	for i, j := 0, len(initial)-1; i < j; i, j = i+1, j-1 {
		initial[i], initial[j] = initial[j], initial[i]
	}
	recovered := testManager(t, root, nil, resolve, nil, initial, nil)
	after := recovered.List()
	for i := range before {
		if after[i].ID != before[i].ID || after[i].TargetPath != before[i].TargetPath {
			t.Fatalf("restart reordered/renumbered: before=%+v after=%+v", before[i], after[i])
		}
	}
}

func TestV10NamingCreationChecksEveryExtensionAndLeafType(t *testing.T) {
	extensions := []string{".audio", ".mp3", ".flac", ".ogg", ".m4a", ".aac", ".wav", ".lrc", ".jpg", ".png", ".json", "symlink", "directory"}
	for _, ext := range extensions {
		t.Run(ext, func(t *testing.T) {
			root := t.TempDir()
			m := testManager(t, root, nil, func(ctx context.Context, _ model.Track, _ string) (string, error) { <-ctx.Done(); return "", ctx.Err() }, nil, nil, nil)
			base := "测试歌曲 - 演示艺术家"
			switch ext {
			case "symlink":
				// dangling symlink 也占位；只能 Lstat 叶子，不能跟随到任何外部目录。
				if err := m.files.Symlink("missing", base+".flac"); err != nil {
					t.Fatal(err)
				}
			case "directory":
				if err := m.files.Mkdir(base+".flac", 0700); err != nil {
					t.Fatal(err)
				}
			default:
				if err := m.files.WriteFile(base+ext, []byte("user-owned"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if err := m.files.WriteFile(base+" (2).png", []byte("user-cover"), 0600); err != nil {
				t.Fatal(err)
			}
			job := createJob(t, m, "ext")
			if want := base + " (3).audio"; filepath.Base(job.TargetPath) != want {
				t.Fatalf("got %q, want %q", filepath.Base(job.TargetPath), want)
			}
			if _, err := m.Action(job.ID, "cancel"); err != nil {
				t.Fatal(err)
			}
			cover, err := m.files.ReadFile(base + " (2).png")
			if err != nil || string(cover) != "user-cover" {
				t.Fatal("cancel removed user's cover")
			}
		})
	}
}

func TestV10NamingBoundAndCanonicalStems(t *testing.T) {
	job := model.DownloadJob{ID: "fb725364c3366da197ce4e269167d6b0", Track: testTrack("bounds"), FileNameFormat: "title-artist"}
	base := baseStem(job)
	for _, suffix := range []string{"", " (2)", " (3)", " (10000)", " (" + job.ID + ")"} {
		if !validStem(job, base+suffix) {
			t.Errorf("valid stem rejected: %q", suffix)
		}
	}
	for _, suffix := range []string{" (0)", " (1)", " (02)", " (+2)", " (-2)", " (2) ", " (2).mp3", " (10001)", " (99999999999999999999)", " (other-job)", " (２)", "/../escape", `\escape`} {
		if validStem(job, base+suffix) {
			t.Errorf("noncanonical/unsafe stem accepted: %q", suffix)
		}
	}
	root, clean, err := openStorage(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	m := &Manager{root: clean, files: root, jobs: make(map[string]*entry)}
	// 耗尽内存预留即可验证硬上限，不生成一万个磁盘文件，不扫目录。
	for i := 1; i <= 10000; i++ {
		other := job
		other.ID = fmt.Sprintf("reserved-%d", i)
		other.TargetPath = filepath.Join(clean, "Singles", v10Name(base, i)+".audio")
		other.State = "paused"
		m.jobs[other.ID] = &entry{job: other, root: clean}
	}
	if err := m.planName(&job); !errors.Is(err, errCollision) {
		t.Fatalf("exhaustion must fail closed, got %v / %q", err, job.TargetPath)
	}
	if job.TargetPath != "" {
		t.Fatal("failed allocation mutated snapshot")
	}
	// cancelled 只释放内存预留；若用户文件占位，仍不可复用。
	e := m.jobs["reserved-2"]
	e.job.State = "cancelled"
	if err := root.WriteFile(base+" (2).flac", []byte("user-file"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := m.planName(&job); !errors.Is(err, errCollision) {
		t.Fatalf("cancelled file overwritten/reused: %v", err)
	}
	m.jobs["reserved-3"].job.State = "cancelled"
	if err := m.planName(&job); err != nil {
		t.Fatal(err)
	}
	if filepath.Base(job.TargetPath) != base+" (3).audio" {
		t.Fatal(job.TargetPath)
	}
}

func TestV10NamingLateCollisionsRespectQueueAndSidecars(t *testing.T) {
	for _, tagged := range []bool{false, true} {
		t.Run(fmt.Sprint(tagged), func(t *testing.T) {
			root := t.TempDir()
			release := make(chan struct{})
			store := &recordingStore{}
			base := "测试歌曲 - 演示艺术家"
			// 在持久化回调中制造 Lstat 之后的冲突：音频和不同扩展均不应覆盖。
			store.reject = func(j model.DownloadJob) bool {
				if j.State != "finalizing" {
					return false
				}
				name := filepath.Base(j.TargetPath)
				ext := ""
				if name == base+" (5).mp3" {
					ext = ".mp3"
				}
				if name == base+" (6).mp3" {
					ext = ".flac"
				}
				if ext != "" {
					path := strings.TrimSuffix(j.TargetPath, ".mp3") + ext
					if err := os.WriteFile(path, []byte("late-user-file"), 0600); err != nil {
						t.Error(err)
					}
				}
				return false
			}
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }, func(ctx context.Context, tr model.Track, q string) (string, error) {
				select {
				case <-release:
					return fixedResolver(ctx, tr, q)
				case <-ctx.Done():
					return "", ctx.Err()
				}
			}, store, nil, nil)
			assets := v5Assets(t)
			if err := m.SetMetadataFetcher(func(context.Context, model.DownloadJob) (MetadataAssets, error) { return assets, nil }); err != nil {
				t.Fatal(err)
			}
			settings := model.Settings{FileNameFormat: "title-artist", WriteLyrics: true, WriteCover: true, EmbedTags: tagged}
			first, err := m.CreateWithOptions(testTrack("first"), "standard", settings)
			if err != nil {
				t.Fatal(err)
			}
			second := createJob(t, m, "second")
			if _, err := m.Action(second.ID, "pause"); err != nil {
				t.Fatal(err)
			}
			for _, name := range []string{base + ".flac", base + " (3).ogg", base + " (4).lrc"} {
				if err := m.files.WriteFile(name, []byte("user-file"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			close(release)
			done := rangeTerminal(t, m, first.ID)
			if done.State != "completed" || filepath.Base(done.TargetPath) != base+" (7).mp3" {
				t.Fatalf("late conflict/queue reservation ignored: %+v", done)
			}
			if !bytes.Equal(v5Payload(t, readTarget(t, done)), v5MP3()) {
				t.Fatal("audio changed")
			}
			for ext, path := range map[string]string{".lrc": done.LyricsPath, ".png": done.CoverPath} {
				if path != filepath.Join(root, "Singles", base+" (7)"+ext) {
					t.Fatalf("sidecar not bound to final name: %q", path)
				}
			}
			for name, want := range map[string]string{base + ".flac": "user-file", base + " (3).ogg": "user-file", base + " (4).lrc": "user-file", base + " (5).mp3": "late-user-file", base + " (6).flac": "late-user-file"} {
				b, err := m.files.ReadFile(name)
				if err != nil || string(b) != want {
					t.Fatalf("changed user file %q: %v", name, err)
				}
			}
			meta, err := loadMeta(m.files, first.ID)
			if err != nil || meta.Final != base+" (7).mp3" || meta.Lyrics.State != "written" || meta.Cover.State != "written" {
				t.Fatalf("checkpoint lost final binding: %+v %v", meta, err)
			}
		})
	}
}

func TestV10NamingConcurrentDetectedFormatsUseFriendlyNames(t *testing.T) {
	root := t.TempDir()
	flac := v9MIMEFixture(t, ".flac")
	mp3 := v9MIMEFixture(t, ".mp3")
	gate := make(chan struct{})
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		body := mp3
		if strings.HasSuffix(r.URL.Path, "flac") {
			body = flac
		}
		// FLAC 故意使用 MP3 MIME，依赖既有 v9 结构识别，不改探测策略。
		return response(r, 200, body), nil
	}, func(ctx context.Context, tr model.Track, _ string) (string, error) {
		select {
		case <-gate:
			return "https://media.example.com/" + tr.ID, nil
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}, nil, nil, nil)
	if err := m.Configure(root, 3); err != nil {
		t.Fatal(err)
	}
	const count = 6
	jobs := make([]model.DownloadJob, count)
	base := "明天见 - 世界之外"
	for i := range jobs {
		ext := ".flac"
		if i%2 == 1 {
			ext = ".mp3"
		}
		tr := testTrack(fmt.Sprintf("%d%s", i, ext))
		tr.Title, tr.Artist = "明天见", "世界之外"
		j, err := m.Create(tr, "lossless")
		if err != nil {
			t.Fatal(err)
		}
		jobs[i] = j
		if filepath.Base(j.TargetPath) != v10Name(base, i+1)+".audio" {
			t.Fatalf("unstable reservation: %q", j.TargetPath)
		}
	}
	// 在未知扩展的预留之后出现不同扩展的用户文件，不能等到最后才只查 .flac。
	if err := m.files.WriteFile(base+".mp3", []byte("user-mp3"), 0600); err != nil {
		t.Fatal(err)
	}
	close(gate)
	for i, j := range jobs {
		ext, body := ".flac", flac
		if i%2 == 1 {
			ext, body = ".mp3", mp3
		}
		ordinal := i + 1
		if i == 0 {
			ordinal = count + 1
		}
		done := rangeTerminal(t, m, j.ID)
		if done.State != "completed" || filepath.Base(done.TargetPath) != v10Name(base, ordinal)+ext || strings.Contains(done.TargetPath, j.ID) {
			t.Fatalf("format detection lost short reservation: %+v", done)
		}
		if !bytes.Equal(readTarget(t, done), body) {
			t.Fatal("detected media bytes changed")
		}
		if strings.Contains(done.Warning, "media_mime_corrected") != (ext == ".flac") {
			t.Fatalf("MIME policy changed: %+v", done)
		}
		meta, err := loadMeta(m.files, j.ID)
		if err != nil || meta.Extension != ext || meta.Final != filepath.Base(done.TargetPath) {
			t.Fatalf("detected extension checkpoint mismatch: %+v %v", meta, err)
		}
	}
	b, err := m.files.ReadFile(base + ".mp3")
	if err != nil || string(b) != "user-mp3" {
		t.Fatal("overwrote user's original MP3")
	}
}

package download

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

func TestSafeNameAndPathPlanning(t *testing.T) {
	for _, name := range []string{"../../escape", "..\\..\\escape", "CON", "NUL.txt", "COM1", "LPT9", " . ", "a / b\\c:*?\"<>|\x00\r\n\t", strings.Repeat("歌", 200), "evil\u202efile.mp3", "  many    spaces  "} {
		got := safeName(name)
		if got == "" || got == "." || got == ".." || len(got) > 64 || !utf8.ValidString(got) || strings.ContainsAny(got, `/\:*?"<>|`) {
			t.Errorf("unsafe name: %q -> %q", name, got)
		}
		for _, r := range got {
			if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
				t.Errorf("control survived %q", got)
			}
		}
	}
	if safeName("CON") != "_CON" || safeName("NUL.txt") != "_NUL.txt" {
		t.Fatal("reserved device name survived")
	}
	root := t.TempDir()
	j := model.DownloadJob{ID: strings.Repeat("x", 80), Track: testTrack("safe"), Quality: strings.Repeat("音", 64)}
	j.Track.Artist = strings.Repeat("作", 200)
	j.Track.Title = "../../" + strings.Repeat("歌", 200)
	target := targetPath(root, j, ".flac")
	if filepath.Dir(target) != filepath.Join(root, "Singles") || len(filepath.Base(target)) > 255 {
		t.Fatalf("bad planned path %q", target)
	}
}
func TestValidateRootRejectsLinksRelativeAndMissing(t *testing.T) {
	base := t.TempDir()
	real := filepath.Join(base, "real")
	if err := os.Mkdir(real, 0700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "link")
	if err := os.Symlink(real, link); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"", ".", "relative/path", filepath.Join(base, "missing"), link, filepath.Join(link, "sub"), "/tmp/\x00bad"} {
		if _, err := ValidateRoot(p); err == nil {
			t.Errorf("accepted invalid root %q", p)
		}
	}
	cleaned, err := ValidateRoot(real + "/.")
	if err != nil || cleaned != real {
		t.Fatalf("valid root failed %q %v", cleaned, err)
	}
	items, err := os.ReadDir(real)
	if err != nil || len(items) != 0 {
		t.Fatal("probe was not cleaned")
	}
	if err := os.Symlink(t.TempDir(), filepath.Join(real, "Singles")); err != nil {
		t.Fatal(err)
	}
	if files, _, err := openStorage(real); err == nil {
		files.Close()
		t.Fatal("symlink Singles accepted")
	}
}
func TestRootBlocksSymlinkEscapeAndHardlinkedParts(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(t.TempDir(), "untouched")
	original := []byte("do not overwrite")
	if err := os.WriteFile(outside, original, 0600); err != nil {
		t.Fatal(err)
	}
	files, _, err := openStorage(root)
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	if err := files.Symlink(outside, "escape.part"); err != nil {
		t.Fatal(err)
	}
	if _, err := openRegular(files, "escape.part", true); err == nil {
		t.Fatal("opened symlink part")
	}
	if _, err := files.OpenFile("escape.part", os.O_WRONLY, 0); err == nil {
		t.Fatal("os.Root escape succeeded")
	}
	if err := os.Link(outside, filepath.Join(root, "Singles", "hardlink.part")); err != nil {
		t.Fatal(err)
	}
	if _, err := openRegular(files, "hardlink.part", true); err == nil {
		t.Fatal("opened hardlinked part")
	}
	for _, name := range []string{"../escape", "/absolute", ".", ".."} {
		if _, err := openRegular(files, name, true); err == nil {
			t.Errorf("accepted %s", name)
		}
	}
	actual, err := os.ReadFile(outside)
	if err != nil || !bytes.Equal(actual, original) {
		t.Fatal("outside file changed")
	}
}
func TestAtomicNoReplaceIncludingExistingSymlink(t *testing.T) {
	files, _, err := openStorage(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	for _, name := range []string{"existing.mp3", "link.mp3", "directory"} {
		if err := files.WriteFile("source.part", audio(1024), 0600); err != nil {
			t.Fatal(err)
		}
		switch name {
		case "existing.mp3":
			err = files.WriteFile(name, []byte("existing"), 0600)
		case "link.mp3":
			err = files.Symlink("existing.mp3", name)
		case "directory":
			err = files.Mkdir(name, 0700)
		}
		if err != nil {
			t.Fatal(err)
		}
		if err := renameNoReplace(files, "source.part", name); !errors.Is(err, errCollision) {
			t.Fatalf("replacement not rejected: %s %v", name, err)
		}
		b, err := files.ReadFile("existing.mp3")
		if err != nil || string(b) != "existing" {
			t.Fatal("overwrote existing file")
		}
	}
	if err := renameNoReplace(files, "source.part", "new.mp3"); err != nil {
		t.Fatal(err)
	}
	if _, err := files.Stat("source.part"); !os.IsNotExist(err) {
		t.Fatal("source not renamed")
	}
}
func TestDownloadCollisionDoesNotOverwrite(t *testing.T) {
	root := t.TempDir()
	data := audio(8192)
	job := seedPartial(t, root, data[:4096], 8192)
	if err := os.WriteFile(job.TargetPath, []byte("existing music"), 0600); err != nil {
		t.Fatal(err)
	}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		res := response(r, 206, data[4096:])
		res.Header.Set("Content-Range", "bytes 4096-8191/8192")
		return res, nil
	}, nil, nil, []model.DownloadJob{job}, nil)
	if _, err := m.Action(job.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	failed := waitJob(t, m, job.ID, "failed")
	waitIdle(t, m, job.ID)
	if failed.Error != errCollision.Error() {
		t.Fatal(failed.Error)
	}
	if string(readTarget(t, failed)) != "existing music" {
		t.Fatal("overwrote music")
	}
	if _, err := m.Action(job.ID, "cancel"); err != nil {
		t.Fatal(err)
	}
	requirePartAbsent(t, root, job.ID)
	if string(readTarget(t, failed)) != "existing music" {
		t.Fatal("cancel removed completed target")
	}
}
func TestRecoveryRejectsUnsafePersistedPaths(t *testing.T) {
	for _, path := range []string{"/etc/passwd", "../outside", "/tmp/Singles/../../escape.mp3", "/tmp/Singles/unrelated.mp3"} {
		t.Run(path, func(t *testing.T) {
			j := model.DownloadJob{ID: "id", Track: testTrack("x"), Quality: "standard", State: "downloading", TargetPath: path}
			m, err := New(t.TempDir(), 1, fixedResolver, func(model.DownloadJob) error { return nil }, []model.DownloadJob{j})
			if err == nil {
				m.Close()
				t.Fatal("unsafe persisted path accepted")
			}
		})
	}
	for _, id := range []string{"../../escape", "", "bad.id", "a/b", "a\\b", strings.Repeat("a", 81)} {
		j := model.DownloadJob{ID: id}
		m, err := New(t.TempDir(), 1, fixedResolver, func(model.DownloadJob) error { return nil }, []model.DownloadJob{j})
		if err == nil {
			m.Close()
			t.Errorf("unsafe ID accepted %q", id)
		}
	}
}
func TestCancelRecoveryRemovesOnlyPartAndSidecar(t *testing.T) {
	root := t.TempDir()
	data := audio(8192)
	job := seedPartial(t, root, data[:4096], 8192)
	job.State = "cancelled"
	if err := os.WriteFile(job.TargetPath, []byte("completed elsewhere"), 0600); err != nil {
		t.Fatal(err)
	}
	m := testManager(t, root, func(*http.Request) (*http.Response, error) {
		t.Fatal("cancelled recovery started network")
		return nil, nil
	}, nil, nil, []model.DownloadJob{job}, nil)
	requirePartAbsent(t, root, job.ID)
	if m.List()[0].State != "cancelled" || string(readTarget(t, job)) != "completed elsewhere" {
		t.Fatal("cancel recovery damaged final")
	}
}
func TestRecoverRenameBeforeCompletionCommit(t *testing.T) {
	for _, tamper := range []bool{false, true} {
		t.Run(map[bool]string{false: "valid", true: "tampered"}[tamper], func(t *testing.T) {
			root := t.TempDir()
			data := audio(8192)
			job := seedPartial(t, root, data, 8192)
			files, _, err := openStorage(root)
			if err != nil {
				t.Fatal(err)
			}
			sum := sha256.Sum256(data)
			meta := partialMeta{Version: 1, ETag: `"v1"`, Total: 8192, Extension: ".mp3", Final: filepath.Base(job.TargetPath), SHA256: hex.EncodeToString(sum[:])}
			if err := saveMeta(files, job.ID, meta); err != nil {
				t.Fatal(err)
			}
			if err := renameNoReplace(files, partName(job.ID), meta.Final); err != nil {
				t.Fatal(err)
			}
			files.Close()
			if tamper {
				data[512] = 0xff
				if err := os.WriteFile(job.TargetPath, data, 0600); err != nil {
					t.Fatal(err)
				}
			}
			m := testManager(t, root, func(*http.Request) (*http.Response, error) { t.Fatal("should recover locally"); return nil, nil }, nil, nil, []model.DownloadJob{job}, nil)
			if _, err := m.Action(job.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			state := "completed"
			if tamper {
				state = "failed"
			}
			done := waitJob(t, m, job.ID, state)
			if !bytes.Equal(readTarget(t, done), data) {
				t.Fatal("recovery altered final")
			}
		})
	}
}

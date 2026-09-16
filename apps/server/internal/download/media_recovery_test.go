package download

import (
	"bytes"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"melora/internal/model"
)

func TestMediaV9ConflictWarningSurvivesFinalizeRecovery(t *testing.T) {
	root := t.TempDir()
	body := v9MIMEFixture(t, ".flac")
	store := &recordingStore{reject: func(j model.DownloadJob) bool { return j.State == "completed" }}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		resp := response(r, 200, body)
		resp.Header.Set("Content-Type", "audio/mpeg")
		return resp, nil
	}, nil, store, nil, nil)
	j := createJob(t, m, "mime-crash")
	done := rangeTerminal(t, m, j.ID)
	if done.State != "completed" || !strings.Contains(done.Warning, "media_mime_corrected") {
		t.Fatalf("%+v", done)
	}
	saved := store.get(j.ID)
	before, err := os.Stat(done.TargetPath)
	if err != nil {
		t.Fatal(err)
	}
	m.Close()
	recovered := testManager(t, root, func(*http.Request) (*http.Response, error) {
		t.Error("already published audio fetched again")
		return nil, errNetwork
	}, nil, nil, []model.DownloadJob{saved}, nil)
	if _, err := recovered.Action(j.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	got := rangeTerminal(t, recovered, j.ID)
	after, err := os.Stat(got.TargetPath)
	if err != nil || !os.SameFile(before, after) || got.Warning != done.Warning || !bytes.Equal(readTarget(t, got), body) {
		t.Fatalf("recovery lost proof/warning or changed audio: %+v", got)
	}
}

func TestMediaV9ResumeRetainsButReplacementResetsMIMEHistory(t *testing.T) {
	for _, status := range []int{200, 206} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			root := t.TempDir()
			body := v9MIMEFixture(t, ".mp3")
			offset := 1000
			job := seedPartial(t, root, body[:offset], int64(len(body)))
			files, _, err := openStorage(root)
			if err != nil {
				t.Fatal(err)
			}
			meta, err := loadMeta(files, job.ID)
			if err != nil {
				t.Fatal(err)
			}
			meta.ReportedFormats = []string{".flac"}
			if err := saveMeta(files, job.ID, meta); err != nil {
				t.Fatal(err)
			}
			files.Close()
			m := testManager(t, root, func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, body)
				if status == 206 {
					resp = response(r, 206, body[offset:])
					resp.Header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", offset, len(body)-1, len(body)))
				}
				return resp, nil
			}, nil, nil, []model.DownloadJob{job}, nil)
			if _, err := m.Action(job.ID, "resume"); err != nil {
				t.Fatal(err)
			}
			got := rangeTerminal(t, m, job.ID)
			if got.State != "completed" || !bytes.Equal(readTarget(t, got), body) || strings.Contains(got.Warning, "media_mime_corrected") != (status == 206) {
				t.Fatalf("MIME history confused between append and replacement: %+v", got)
			}
			raw, err := os.ReadFile(filepath.Join(root, "Singles", metaName(job.ID)))
			if err != nil {
				t.Fatal(err)
			}
			if bytes.Contains(raw, []byte("https:")) || bytes.Contains(raw, []byte("SECRET")) {
				t.Fatal("response credentials persisted")
			}
		})
	}
}

func TestMediaV9CheckpointReportedFormatsAreBoundedWhitelist(t *testing.T) {
	files, _, err := openStorage(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	for i, formats := range [][]string{nil, {".flac"}, {"token=SECRET"}, {".audio"}, {".mp3", ".mp3", ".mp3", ".mp3", ".mp3", ".mp3", ".mp3"}} {
		id := fmt.Sprintf("format-%d", i)
		if err := saveMeta(files, id, partialMeta{Version: 1, Extension: ".mp3", Total: 100, ReportedFormats: formats}); err != nil {
			t.Fatal(err)
		}
		_, err := loadMeta(files, id)
		if (err == nil) != (i < 2) {
			t.Fatalf("checkpoint format guard %d: %v", i, err)
		}
	}
}

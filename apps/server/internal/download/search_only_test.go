//go:build linux

package download

import (
	"bytes"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// 真实 worker 和目录 FD 写入；仅上游音频传输为离线夹具，不访问公网。
func TestDownloadWritesUnderSearchOnlyAncestor(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root bypasses directory permissions")
	}
	parent := filepath.Join(t.TempDir(), "cannot-list")
	first, second := filepath.Join(parent, "下载"), filepath.Join(parent, "Music")
	for _, root := range []string{first, second} {
		if err := os.MkdirAll(root, 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Chmod(parent, 0111); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(parent, 0700) })
	if _, err := os.ReadDir(parent); !errors.Is(err, os.ErrPermission) {
		t.Fatalf("fixture not search-only: %v", err)
	}
	data := audio(90000)
	m := testManager(t, first, func(r *http.Request) (*http.Response, error) { return response(r, 200, data), nil }, nil, nil, nil, nil)
	for i, root := range []string{first, second} {
		if i > 0 {
			if err := m.Configure(root, 1); err != nil {
				t.Fatal(err)
			}
		}
		job := createJob(t, m, filepath.Base(root))
		done := waitJob(t, m, job.ID, "completed")
		waitIdle(t, m, job.ID)
		if !bytes.Equal(readTarget(t, done), data) {
			t.Fatal("real worker bytes differ")
		}
		if info, err := os.Stat(filepath.Join(root, "Singles")); err != nil || !info.IsDir() {
			t.Fatal("missing rooted download directory", err)
		}
	}
	if _, err := os.ReadDir(parent); !errors.Is(err, os.ErrPermission) {
		t.Fatal("worker changed ancestor permissions", err)
	}
}

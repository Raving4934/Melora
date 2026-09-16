//go:build linux

package api

import (
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

// fnOS已授予两个叶目录读写权限，祖先仅允许搜索而不能列目录。
func TestStorageGrantedRootsBelowSearchOnlyAncestor(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root bypasses permissions")
	}
	s, _, initial := setup(t, "")
	parent := filepath.Join(filepath.Dir(initial), "search-only")
	a, b := filepath.Join(parent, "下载"), filepath.Join(parent, "Music")
	for _, p := range []string{a, b, filepath.Join(a, "专辑")} {
		if err := os.MkdirAll(p, 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Chmod(parent, 0111); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(parent, 0700) })
	if _, err := os.ReadDir(parent); !errors.Is(err, os.ErrPermission) {
		t.Fatalf("not a search-only fixture: %v", err)
	}
	s.cfg.DownloadAuthorization = "fnos"
	s.cfg.DownloadRoot = ""
	s.cfg.DownloadRoots = []string{a, b}
	w := request(s, "GET", "/api/v1/storage/directories", nil, nil)
	assertStatus(t, w, 200)
	var roots directoryListing
	if err := json.Unmarshal(w.Body.Bytes(), &roots); err != nil {
		t.Fatal(err)
	}
	if len(roots.Directories) != 2 {
		t.Fatalf("granted roots hidden: %s", w.Body.String())
	}
	w = request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(a), nil, nil)
	assertStatus(t, w, 200)
	var children directoryListing
	if err := json.Unmarshal(w.Body.Bytes(), &children); err != nil {
		t.Fatal(err)
	}
	if len(children.Directories) != 1 || children.Directories[0].Name != "专辑" {
		t.Fatal(w.Body.String())
	}
	w = request(s, "PATCH", "/api/v1/settings", map[string]any{"downloadRoot": a}, nil)
	assertStatus(t, w, 200)
	if info, err := os.Stat(filepath.Join(a, "Singles")); err != nil || !info.IsDir() {
		t.Fatalf("download destination not configured: %v", err)
	}
	w = request(s, "POST", "/api/v1/storage/validate", map[string]string{"path": filepath.Join(a, "专辑")}, nil)
	assertStatus(t, w, 200)
	w = request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(parent), nil, nil)
	assertStatus(t, w, 400)
	if _, err := os.ReadDir(parent); !errors.Is(err, os.ErrPermission) {
		t.Fatal("ancestor permissions changed")
	}
	s.cfg.DownloadRoots = nil
	w = request(s, "GET", "/api/v1/storage/directories", nil, nil)
	assertStatus(t, w, 403)
}

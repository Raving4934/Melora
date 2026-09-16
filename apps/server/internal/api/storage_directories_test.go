package api

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStorageDirectoryBrowserUsesMultipleGrantedRoots(t *testing.T) {
	s, _, a := setup(t, "")
	b := t.TempDir()
	s.cfg.DownloadRoot = ""
	s.cfg.DownloadRoots = []string{a, b}
	s.cfg.DownloadAuthorization = "fnos"
	selected := filepath.Join(b, "专辑 文件夹")
	if err := os.Mkdir(selected, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(b, "private.mp3"), []byte("no file enumeration"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(a, filepath.Join(b, "linked")); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(b, ".hidden"), 0700); err != nil {
		t.Fatal(err)
	}
	w := request(s, "GET", "/api/v1/storage/directories", nil, nil)
	assertStatus(t, w, 200)
	var result directoryListing
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Directories) != 2 || result.Path != "" || len(result.Roots) != 2 {
		t.Fatal(w.Body.String())
	}
	w = request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(b), nil, nil)
	assertStatus(t, w, 200)
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Path != b || result.Root != b || result.Parent != "" || len(result.Directories) != 1 || result.Directories[0].Path != selected {
		t.Fatal(w.Body.String())
	}
	w = request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(selected), nil, nil)
	assertStatus(t, w, 200)
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Parent != b || len(result.Directories) != 0 {
		t.Fatal(w.Body.String())
	}
	w = request(s, "POST", "/api/v1/storage/validate", map[string]string{"path": selected}, nil)
	assertStatus(t, w, 200)
	for _, path := range []string{filepath.Dir(b), filepath.Join(b, "linked"), "relative"} {
		assertStatus(t, request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(path), nil, nil), 400)
	}
	assertStatus(t, request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(filepath.Join(b, "missing")), nil, nil), 404)
	before, _ := os.ReadDir(selected)
	if len(before) != 0 {
		t.Fatal("GET or probe left files")
	}
	s.cfg.DownloadRoots = nil
	assertStatus(t, request(s, "GET", "/api/v1/storage/directories", nil, nil), 403)
	assertStatus(t, request(s, "POST", "/api/v1/storage/validate", map[string]string{"path": selected}, nil), 403)
}
func TestStorageBrowserHasAuthAndBoundedDirectoryListing(t *testing.T) {
	guarded, _, _ := setup(t, strings.Repeat("x", 32))
	assertStatus(t, request(guarded, "GET", "/api/v1/storage/directories", nil, nil), 401)
	s, _, root := setup(t, "")
	for i := 0; i < 270; i++ {
		if err := os.Mkdir(filepath.Join(root, fmt.Sprintf("Album-%03d", i)), 0700); err != nil {
			t.Fatal(err)
		}
	}
	w := request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(root), nil, nil)
	assertStatus(t, w, 200)
	var result directoryListing
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Directories) != 256 || !result.Truncated {
		t.Fatal("unbounded list", len(result.Directories), result.Truncated)
	}
	for i := 1; i < len(result.Directories); i++ {
		if result.Directories[i].Name < result.Directories[i-1].Name {
			t.Fatal("unstable order")
		}
	}
}

func TestStorageValidationRejectsExistingUnsafeSingles(t *testing.T) {
	s, _, root := setup(t, "")
	target := filepath.Join(root, "new-root")
	if err := os.Mkdir(target, 0700); err != nil {
		t.Fatal(err)
	}
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(target, "Singles")); err != nil {
		t.Fatal(err)
	}
	w := request(s, "POST", "/api/v1/storage/validate", map[string]string{"path": target}, nil)
	assertStatus(t, w, 400)
	if !strings.Contains(w.Body.String(), "invalid_download_storage") {
		t.Fatal(w.Body.String())
	}
	entries, err := os.ReadDir(outside)
	if err != nil || len(entries) != 0 {
		t.Fatal("validation followed Singles symlink", err)
	}
}

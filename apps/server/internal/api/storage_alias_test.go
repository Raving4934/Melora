package api

import (
	"context"
	"encoding/json"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"melora/internal/store"
)

func TestStorageAliasesOnlyListSelectableDirectories(t *testing.T) {
	s, db, physical := setup(t, "")
	alias := filepath.Join(filepath.Dir(physical), "用户下载目录")
	if err := os.Symlink(physical, alias); err != nil {
		t.Fatal(err)
	}
	s.cfg.DownloadAuthorization = "fnos"
	s.cfg.DownloadRoot = ""
	s.cfg.DownloadRoots = []string{alias}
	outside := t.TempDir()
	for _, name := range []string{"可选 中文", "只读", "无权限", "坏工作目录"} {
		if err := os.Mkdir(filepath.Join(physical, name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	for name, target := range map[string]string{
		"相对入口": "可选 中文", "显示绝对入口": filepath.Join(alias, "可选 中文"),
		"越界入口": outside, "悬空入口": "不存在", "循环": "循环",
	} {
		if err := os.Symlink(target, filepath.Join(physical, name)); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Symlink(outside, filepath.Join(physical, "坏工作目录", "Singles")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(physical, "用户音频.mp3"), []byte("fixture only"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Join(physical, "只读"), 0500); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Join(physical, "无权限"), 0000); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		os.Chmod(filepath.Join(physical, "只读"), 0700)
		os.Chmod(filepath.Join(physical, "无权限"), 0700)
	})
	w := request(s, "GET", "/api/v1/storage/directories", nil, nil)
	assertStatus(t, w, 200)
	var roots directoryListing
	if err := json.Unmarshal(w.Body.Bytes(), &roots); err != nil {
		t.Fatal(err)
	}
	if len(roots.Directories) != 1 || roots.Directories[0].Path != alias || roots.Directories[0].CanonicalPath != physical {
		t.Fatal(w.Body.String())
	}
	w = request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(alias), nil, nil)
	assertStatus(t, w, 200)
	var result directoryListing
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Path != alias || result.Root != alias || result.Parent != "" || result.CanonicalPath != physical {
		t.Fatal(w.Body.String())
	}
	got := []string{}
	for _, child := range result.Directories {
		got = append(got, child.Name)
		// 列表路径就是 POST 能验证的路径；物理结果与浏览提供的 canonicalPath 一致。
		validated := request(s, "POST", "/api/v1/storage/validate", map[string]string{"path": child.Path}, nil)
		assertStatus(t, validated, 200)
		var response struct {
			Path string `json:"path"`
		}
		if err := json.Unmarshal(validated.Body.Bytes(), &response); err != nil || response.Path != child.CanonicalPath {
			t.Fatal(validated.Body.String(), err)
		}
	}
	want := []string{"可选 中文", "显示绝对入口", "相对入口"}
	if os.Geteuid() == 0 {
		want = append(want, "只读", "无权限")
	}
	sort.Strings(got)
	sort.Strings(want)
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatal("unselectable directory offered", got, want)
	}
	entries, err := os.ReadDir(outside)
	if err != nil || len(entries) != 0 {
		t.Fatal("outside target touched", err)
	}
	selected := filepath.Join(physical, "可选 中文")
	entries, err = os.ReadDir(selected)
	if err != nil || len(entries) != 0 {
		t.Fatal("GET or validation left probe files", err)
	}
	settings := store.DefaultSettings()
	settings.DownloadRoot = selected
	if err := db.SaveSettings(context.Background(), settings); err != nil {
		t.Fatal(err)
	}
	w = request(s, "GET", "/api/v1/storage/status", nil, nil)
	assertStatus(t, w, 200)
	var status struct {
		CanonicalPath string `json:"canonicalPath"`
		DisplayPath   string `json:"displayPath"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &status); err != nil || status.CanonicalPath != selected || status.DisplayPath != filepath.Join(alias, "可选 中文") {
		t.Fatal(w.Body.String(), err)
	}
}

func TestStoragePhysicalGrantAcceptsUserAlias(t *testing.T) {
	s, _, physical := setup(t, "")
	s.cfg.DownloadAuthorization = "fnos"
	s.cfg.DownloadRoot = ""
	s.cfg.DownloadRoots = []string{physical}
	alias := filepath.Join(filepath.Dir(physical), "显示入口")
	if err := os.Symlink(physical, alias); err != nil {
		t.Fatal(err)
	}
	child := filepath.Join(physical, "下载")
	if err := os.Mkdir(child, 0700); err != nil {
		t.Fatal(err)
	}
	w := request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(filepath.Join(alias, "下载")), nil, nil)
	assertStatus(t, w, 200)
	var result directoryListing
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Root != alias || result.Parent != alias || result.CanonicalPath != child {
		t.Fatal(w.Body.String())
	}
}

func TestStorageDirectoryErrorsAreActionable(t *testing.T) {
	s, _, root := setup(t, "")
	locked := filepath.Join(root, "无权限")
	if err := os.Mkdir(locked, 0000); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(locked, 0700) })
	outside := t.TempDir()
	if err := os.Symlink(filepath.Join(outside, "not-existing"), filepath.Join(root, "越界")); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path          string
		status        int
		code, message string
	}{
		{filepath.Join(root, "missing"), 404, "directory_missing", "目录不存在"},
		{filepath.Join(root, "越界"), 400, "directory_outside_authorization", "超出已授权范围"},
		{locked, 403, "directory_permission", "无访问权限"},
	} {
		if tc.path == locked && os.Geteuid() == 0 {
			continue
		}
		w := request(s, "GET", "/api/v1/storage/directories?path="+url.QueryEscape(tc.path), nil, nil)
		assertStatus(t, w, tc.status)
		if !strings.Contains(w.Body.String(), tc.code) || !strings.Contains(w.Body.String(), tc.message) {
			t.Fatal(w.Body.String())
		}
		if strings.Contains(w.Body.String(), outside) {
			t.Fatal("outside path leaked", w.Body.String())
		}
	}
	// 已声明根本身缺失时，状态和浏览都保留准确原因，不变成“尚未授权”。
	s.cfg.DownloadAuthorization = "fnos"
	s.cfg.DownloadRoot = ""
	s.cfg.DownloadRoots = []string{filepath.Join(root, "missing-root")}
	w := request(s, "GET", "/api/v1/storage/status", nil, nil)
	assertStatus(t, w, 200)
	if !strings.Contains(w.Body.String(), "directory_missing") {
		t.Fatal(w.Body.String())
	}
	assertStatus(t, request(s, "GET", "/api/v1/storage/directories", nil, nil), 404)
}

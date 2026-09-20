//go:build linux

package config

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"melora/internal/storage"
)

// 应用可访问已知安装/私有目录，并不代表可列出NAS上所有其它应用的父目录。
func TestRuntimePathsAllowSearchOnlyAncestors(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root bypasses directory permissions")
	}
	root, err := os.MkdirTemp("", "melora-traverse-")
	if err != nil {
		t.Fatal(err)
	}
	parent := filepath.Join(root, "search-only")
	t.Cleanup(func() { _ = os.Chmod(parent, 0700); _ = os.RemoveAll(root) })
	app, data := filepath.Join(parent, "app"), filepath.Join(parent, "private", "data")
	web := filepath.Join(app, "web")
	for _, dir := range []string{web, data} {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	link := filepath.Join(parent, "link")
	if err := os.Symlink(app, link); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(parent, "file")
	if err := os.WriteFile(file, []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(parent, 0111); err != nil {
		t.Fatal(err)
	}
	if _, err := os.ReadDir(parent); !errors.Is(err, os.ErrPermission) {
		t.Fatalf("fixture must forbid directory listing: %v", err)
	}
	if _, err := os.Stat(app); err != nil {
		t.Fatalf("known child must be traversable: %v", err)
	}
	cfg := Config{DataDir: data, WebDir: web, SocketPath: filepath.Join(app, "app.sock"), BasePath: "/app/melora", GatewayAuth: "fnos-admin"}
	t.Run("gateway", func(t *testing.T) {
		if err := cfg.ValidateGateway(); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("private-and-web", func(t *testing.T) {
		if err := cfg.ValidateRuntimePaths(); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("metadata-validation", func(t *testing.T) {
		if err := NoSymlinks(data); err != nil {
			t.Fatal(err)
		}
	})
	for _, item := range []struct {
		name, path string
		want       error
	}{
		{"link-remains-rejected", link, storage.ErrDirectoryLink},
		{"missing-is-not-permission", filepath.Join(parent, "missing"), storage.ErrDirectoryMissing},
		{"file-is-not-directory", file, storage.ErrDirectoryNotDir},
	} {
		t.Run(item.name, func(t *testing.T) {
			if err := NoSymlinks(item.path); !errors.Is(err, item.want) {
				t.Fatalf("got %v; want %v", err, item.want)
			}
		})
	}
	t.Run("no-search-remains-rejected", func(t *testing.T) {
		if err := os.Chmod(parent, 0600); err != nil {
			t.Fatal(err)
		}
		defer os.Chmod(parent, 0111)
		if err := NoSymlinks(app); !errors.Is(err, storage.ErrDirectoryPermission) {
			t.Fatalf("got %v", err)
		}
	})
}

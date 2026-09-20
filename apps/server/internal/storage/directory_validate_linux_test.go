//go:build linux

package storage

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestValidateDirectoryPathBoundsAndDescriptorCleanup(t *testing.T) {
	root := t.TempDir()
	nested := filepath.Join(root, "child")
	if err := os.Mkdir(nested, 0700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(nested, link); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"", "relative", root + "\n", root + "\x00"} {
		if err := ValidateDirectoryPath(path); !errors.Is(err, ErrDirectoryInvalid) {
			t.Fatalf("invalid path returned %v", err)
		}
	}
	if err := ValidateDirectoryPath("/"); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 128; i++ {
		if err := ValidateDirectoryPath(nested); err != nil {
			t.Fatal(err)
		}
		if err := ValidateDirectoryPath(link); !errors.Is(err, ErrDirectoryLink) {
			t.Fatal(err)
		}
		if err := ValidateDirectoryPath(filepath.Join(root, "missing")); !errors.Is(err, ErrDirectoryMissing) {
			t.Fatal(err)
		}
	}
	after, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatal(err)
	}
	if len(after) > len(before) {
		t.Fatalf("directory validation leaked descriptors: %d => %d", len(before), len(after))
	}
}

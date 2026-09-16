package storage

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func TestDirectoryErrorsPreserveCauseWithoutPath(t *testing.T) {
	for _, tc := range []struct{ cause, kind error }{
		{syscall.ENOENT, ErrDirectoryMissing}, {syscall.EACCES, ErrDirectoryPermission},
		{syscall.ENOTDIR, ErrDirectoryNotDir}, {syscall.ELOOP, ErrDirectoryLoop}, {syscall.EROFS, ErrDirectoryReadOnly},
	} {
		err := DirectoryError(&os.PathError{Op: "open", Path: "/private-user-path", Err: tc.cause})
		if !errors.Is(err, tc.kind) || !errors.Is(err, tc.cause) || strings.Contains(err.Error(), "/private-user-path") {
			t.Fatal(err)
		}
	}
	if err := WriteDirectoryError(syscall.EACCES); !errors.Is(err, ErrDirectoryNotWritable) {
		t.Fatal(err)
	}
}
func TestReadOnlyDirectoryPreflightDoesNotWrite(t *testing.T) {
	path := t.TempDir()
	root, err := OpenDirectory(path)
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	if err := CheckWritableDirectory(root); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(path)
	if err != nil || len(entries) != 0 {
		t.Fatal("permission preflight created a file", err)
	}
	if os.Geteuid() == 0 {
		return
	}
	if err := os.Chmod(path, 0500); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(path, 0700)
	if err := CheckWritableDirectory(root); !errors.Is(err, ErrDirectoryNotWritable) {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(path, "probe")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal(err)
	}
}

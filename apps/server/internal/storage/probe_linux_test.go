//go:build linux

package storage

import (
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"syscall"
	"testing"
)

func TestStatfsInfoUsesUserAvailableAndChecksCounters(t *testing.T) {
	stat := syscall.Statfs_t{Blocks: 100, Bfree: 30, Bavail: 20, Bsize: 8192, Frsize: 4096}
	info, err := statfsInfo(stat)
	if err != nil || info != (Info{TotalBytes: 409600, FreeBytes: 122880, AvailableBytes: 81920}) {
		t.Fatalf("reserved blocks or units incorrect: %+v %v", info, err)
	}
	data, _ := json.Marshal(info)
	if string(data) != `{"totalBytes":409600,"availableBytes":81920,"freeBytes":122880}` {
		t.Fatalf("JSON contract: %s", data)
	}
	stat.Frsize = 0
	if info, err := statfsInfo(stat); err != nil || info.TotalBytes != 819200 {
		t.Fatalf("block-size fallback: %+v %v", info, err)
	}
	for _, invalid := range []syscall.Statfs_t{
		{Blocks: 100, Bfree: 101, Bavail: 10, Bsize: 4096},
		{Blocks: 100, Bfree: 10, Bavail: 11, Bsize: 4096},
		{Blocks: 100, Bfree: 10, Bavail: math.MaxUint64, Bsize: 4096},
		{Blocks: math.MaxUint64, Bsize: 4096},
		{Blocks: 100, Bsize: -1}, {Blocks: 100},
	} {
		if got, err := statfsInfo(invalid); !errors.Is(err, ErrProbe) || got != (Info{}) {
			t.Fatalf("fabricated capacity for %+v: %+v %v", invalid, got, err)
		}
	}
}

func TestProbeRealCapacityWithoutWriting(t *testing.T) {
	path := t.TempDir()
	if err := os.Chmod(path, 0500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(path, 0700) })
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	info, err := Probe(path)
	if err != nil {
		t.Fatal(err)
	}
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err != nil {
		t.Fatal(err)
	}
	expected, err := statfsInfo(stat)
	if err != nil || info.TotalBytes == 0 || info.TotalBytes != expected.TotalBytes || info.AvailableBytes > info.FreeBytes || info.FreeBytes > info.TotalBytes {
		t.Fatalf("not real capacity: %+v expected %+v %v", info, expected, err)
	}
	after, _ := os.Stat(path)
	entries, err := os.ReadDir(path)
	if err != nil || len(entries) != 0 || !after.ModTime().Equal(before.ModTime()) {
		t.Fatal("probe wrote to directory")
	}
}

func TestProbeFailureNeverInventsCapacity(t *testing.T) {
	path := filepath.Join(t.TempDir(), "absent")
	if info, err := Probe(path); !errors.Is(err, ErrProbe) || info != (Info{}) {
		t.Fatalf("missing path: %+v %v", info, err)
	}
	if err := os.WriteFile(path, []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	if info, err := Probe(path); !errors.Is(err, ErrProbe) || info != (Info{}) {
		t.Fatalf("regular file: %+v %v", info, err)
	}
	root, err := os.OpenRoot(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	root.Close()
	for _, root := range []*os.Root{nil, root} {
		if info, err := ProbeRoot(root); !errors.Is(err, ErrProbe) || info != (Info{}) {
			t.Fatalf("invalid root: %+v %v", info, err)
		}
	}
}

func TestProbeRootFollowsPinnedDirectoryNotReplacement(t *testing.T) {
	base := t.TempDir()
	path := filepath.Join(base, "original")
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	root, err := os.OpenRoot(path)
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	if err := os.Rename(path, filepath.Join(base, "moved")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("replacement"), 0600); err != nil {
		t.Fatal(err)
	}
	if info, err := ProbeRoot(root); err != nil || info.TotalBytes == 0 {
		t.Fatalf("lost pinned directory: %+v %v", info, err)
	}
	if info, err := Probe(path); err == nil || info != (Info{}) {
		t.Fatal("accepted replacement")
	}
}

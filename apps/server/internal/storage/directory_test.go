package storage

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestDirectoryFDStaysBoundToAuthorizedObject(t *testing.T) {
	base := t.TempDir()
	authorized := filepath.Join(base, "authorized")
	outside := t.TempDir()
	if err := os.MkdirAll(filepath.Join(authorized, "albums"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(outside, "outside-marker"), []byte("outside"), 0600); err != nil {
		t.Fatal(err)
	}
	root, err := OpenDirectory(authorized)
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	if err = os.Rename(authorized, authorized+"-old"); err != nil {
		t.Fatal(err)
	}
	if err = os.Symlink(outside, authorized); err != nil {
		t.Fatal(err)
	}
	if reopened, e := OpenDirectory(authorized); e == nil {
		reopened.Close()
		t.Fatal("followed replacement root symlink")
	}
	album, err := OpenRelativeDirectory(root, "albums")
	if err != nil {
		t.Fatal(err)
	}
	defer album.Close()
	if _, err = album.Stat("outside-marker"); err == nil {
		t.Fatal("FD escaped to replacement directory")
	}
	for _, path := range []string{"../outside", outside} {
		if opened, e := OpenRelativeDirectory(root, path); e == nil {
			opened.Close()
			t.Fatal("escaped relative root", path)
		}
	}
}
func TestDirectoryReplacementRaceCannotOpenExternalTarget(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	path := filepath.Join(root, "albums")
	held := filepath.Join(root, "held")
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(outside, "outside-marker"), []byte("must not be opened"), 0600); err != nil {
		t.Fatal(err)
	}
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
			}
			if os.Rename(path, held) != nil {
				continue
			}
			if os.Symlink(outside, path) == nil {
				os.Remove(path)
			}
			os.Rename(held, path)
		}
	}()
	defer func() { close(stop); wg.Wait() }()
	successes := 0
	for i := 0; i < 1200; i++ {
		opened, err := OpenDirectory(path)
		if err != nil {
			continue
		}
		successes++
		_, escape := opened.Stat("outside-marker")
		file, writeErr := opened.OpenFile("probe", os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if writeErr == nil {
			file.Close()
			opened.Remove("probe")
		}
		opened.Close()
		if escape == nil {
			t.Fatal("concurrent replacement escaped authorized FD")
		}
	}
	if _, err := os.Stat(filepath.Join(outside, "probe")); err == nil {
		t.Fatal("probe written outside authorization")
	}
	t.Logf("verified %d safe FD opens during 1200 replacement attempts", successes)
}

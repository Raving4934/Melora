//go:build linux

package uninstall

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/unix"
)

// 仅设置测试自己的临时权限，不改变执行身份，也不依赖真实 NAS 目录。
func searchOnlyLayout(t *testing.T, bucket string) (string, []string) {
	t.Helper()
	if os.Geteuid() == 0 {
		t.Skip("需要真实非 root 身份验证 +x/-r，而不是借用权限绕过")
	}
	volume := filepath.Join(t.TempDir(), "volX")
	ancestor := filepath.Join(volume, bucket)
	root := filepath.Join(ancestor, "melora")
	for _, path := range []string{volume, ancestor, root} {
		if err := os.Mkdir(path, 0700); err != nil {
			t.Fatal(err)
		}
	}
	privateFile(t, filepath.Join(root, "known.dat"), []byte("KNOWN_PATH_API_ACCESS"))
	ancestors := []string{volume, ancestor}
	// 必须先恢复外层，才能清理测试中故意设为不可搜索的内层。
	t.Cleanup(func() {
		for _, path := range ancestors {
			if err := os.Chmod(path, 0700); err != nil {
				t.Errorf("restore owned fixture: %v", err)
			}
		}
	})
	for _, path := range ancestors {
		if err := os.Chmod(path, 0111); err != nil {
			t.Fatal(err)
		}
	}
	return root, ancestors
}

func assertAncestorModes(t *testing.T, paths []string, mode os.FileMode) {
	t.Helper()
	for _, path := range paths {
		info, err := os.Stat(path)
		if err != nil || info.Mode().Perm() != mode {
			t.Fatalf("ancestor permissions changed: %s: %v %v", path, info, err)
		}
	}
}

func TestSearchOnlyAncestorsAllowKnownPathAndAnchoredOpen(t *testing.T) {
	for _, bucket := range []string{"@appcenter", "@appdata", "@appconf"} {
		t.Run(bucket, func(t *testing.T) {
			root, ancestors := searchOnlyLayout(t, bucket)
			for _, path := range ancestors {
				if _, err := os.ReadDir(path); !errors.Is(err, os.ErrPermission) {
					t.Fatalf("fixture must be genuinely unlistable, uid=%d: %v", os.Geteuid(), err)
				}
			}
			// 普通标准库已知路径访问成功，不要求列举任何祖先。
			data, err := os.ReadFile(filepath.Join(root, "known.dat"))
			if err != nil || string(data) != "KNOWN_PATH_API_ACCESS" {
				t.Fatalf("known-path ReadFile failed: %v", err)
			}
			normal, err := os.OpenRoot(root)
			if err != nil {
				t.Fatalf("standard OpenRoot failed: %v", err)
			}
			defer normal.Close()
			file, err := normal.Open("known.dat")
			if err != nil {
				t.Fatal(err)
			}
			data, err = io.ReadAll(file)
			file.Close()
			if err != nil || string(data) != "KNOWN_PATH_API_ACCESS" {
				t.Fatalf("standard Root.Open failed: %v", err)
			}
			t.Logf("uid=%d: 0111 ancestors reject ReadDir; known-path ReadFile/OpenRoot/Root.Open succeed; package root=0700", os.Geteuid())
			fd, err := openAbsoluteDirectory(root)
			if err != nil {
				t.Fatalf("uninstall traversal rejects a legal searchable layout: %v", err)
			}
			defer unix.Close(fd)
			flags, err := unix.FcntlInt(uintptr(fd), unix.F_GETFL, 0)
			if err != nil || flags&(unix.O_PATH|unix.O_DIRECTORY|unix.O_NOFOLLOW) != unix.O_PATH|unix.O_DIRECTORY|unix.O_NOFOLLOW {
				t.Fatalf("directory anchor lost required flags: %#x %v", flags, err)
			}
			fdFlags, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0)
			if err != nil || fdFlags&unix.FD_CLOEXEC == 0 {
				t.Fatalf("directory anchor must remain CLOEXEC: %v", err)
			}
			var info unix.Stat_t
			if err := unix.Fstat(fd, &info); err != nil || info.Mode&unix.S_IFMT != unix.S_IFDIR || info.Uid != uint32(os.Geteuid()) {
				t.Fatalf("invalid anchored directory identity: %v", err)
			}
			assertAncestorModes(t, ancestors, 0111)
			assertAncestorModes(t, []string{root}, 0700)
		})
	}
}

func TestSearchOnlyAncestorsRootRevalidationHonorsRevokedSearch(t *testing.T) {
	root, ancestors := searchOnlyLayout(t, "@appdata")
	t.Setenv("TRIM_PKGVAR", root)
	s := &session{uid: uint32(os.Geteuid())}
	defer s.close()
	if _, err := s.root("TRIM_PKGVAR", true); err != nil {
		t.Fatalf("private-root initialization must support +x ancestors: %v", err)
	}
	if err := s.validateDirectories(); err != nil {
		t.Fatalf("private-root revalidation must support +x ancestors: %v", err)
	}
	if err := os.Chmod(ancestors[1], 0000); err != nil {
		t.Fatal(err)
	}
	if _, err := os.ReadFile(filepath.Join(root, "known.dat")); !errors.Is(err, os.ErrPermission) {
		t.Fatalf("fixture must genuinely lack search permission: %v", err)
	}
	if fd, err := openAbsoluteDirectory(root); err == nil {
		unix.Close(fd)
		t.Fatal("O_PATH must not bypass missing ancestor search permission")
	}
	if err := s.validateDirectories(); err == nil {
		t.Fatal("held dirfd must not bypass search revocation on revalidation")
	}
	assertAncestorModes(t, []string{ancestors[1]}, 0000)
}

func TestSearchOnlyAncestorsStillRejectSymlinkComponentsAndNonDirectories(t *testing.T) {
	root, ancestors := searchOnlyLayout(t, "@appconf")
	real := filepath.Join(root, "real")
	if err := os.Mkdir(real, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(real, "child"), 0700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(real, link); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{link, filepath.Join(link, "child"), filepath.Join(root, "known.dat")} {
		if fd, err := openAbsoluteDirectory(path); err == nil {
			unix.Close(fd)
			t.Fatalf("not a physical directory path: %s", path)
		}
	}
	data, err := os.ReadFile(filepath.Join(root, "known.dat"))
	if err != nil || string(data) != "KNOWN_PATH_API_ACCESS" {
		t.Fatal("rejection changed a known file")
	}
	assertAncestorModes(t, ancestors, 0111)
}

//go:build linux

package download

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"unsafe"
)

func safeOpenFlags() int { return syscall.O_NOFOLLOW | syscall.O_NONBLOCK }
func singleLink(info os.FileInfo) bool {
	st, ok := info.Sys().(*syscall.Stat_t)
	return ok && st.Nlink == 1
}

// Go 标准库的 Root.Rename 会覆盖已有文件。仅对 Root 内已固定目录 FD 的
// 两个叶子名调用 renameat2(NOREPLACE)，同时获得原子发布与不覆盖语义。
func renameNoReplace(root *os.Root, old, next string) error {
	if filepath.Base(old) != old || filepath.Base(next) != next || old == "." || next == "." || old == ".." || next == ".." {
		return errFile
	}
	var number uintptr
	switch runtime.GOARCH {
	case "amd64":
		number = 316
	case "arm64", "riscv64", "loong64":
		number = 276
	case "386":
		number = 353
	case "arm":
		number = 382
	case "ppc64", "ppc64le":
		number = 357
	case "s390x":
		number = 347
	default:
		return errors.New("当前架构不支持安全原子下载落盘")
	}
	dir, err := root.Open(".")
	if err != nil {
		return errFile
	}
	defer dir.Close()
	a, err := syscall.BytePtrFromString(old)
	if err != nil {
		return errFile
	}
	b, err := syscall.BytePtrFromString(next)
	if err != nil {
		return errFile
	}
	_, _, errno := syscall.Syscall6(number, dir.Fd(), uintptr(unsafe.Pointer(a)), dir.Fd(), uintptr(unsafe.Pointer(b)), 1, 0)
	runtime.KeepAlive(dir)
	runtime.KeepAlive(a)
	runtime.KeepAlive(b)
	if errno == syscall.EEXIST || errno == syscall.ENOTEMPTY {
		return errCollision
	}
	if errno != 0 {
		return diskError(errno)
	}
	return nil
}

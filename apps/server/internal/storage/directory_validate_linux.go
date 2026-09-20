//go:build linux

package storage

import (
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/sys/unix"
)

// ValidateDirectoryPath 仅检查目录路径的类型和身份，不读取目录内容。
// NAS上安装目录的祖先可只授予搜索(x)权限；O_PATH不额外索取列目录(r)权限。
// 每段通过固定FD和O_NOFOLLOW打开，再核对inode，仍拒绝链接/检查期间的目录替换。
// 本函数不授予下载权限，也不替代调用方实际打开文件或绑定Socket时的内核权限检查。
func ValidateDirectoryPath(path string) error {
	fd, err := openDirectoryPathHandle(path)
	if err != nil {
		return err
	}
	return unix.Close(fd)
}

// 最终目录需要真实可读权限，但已知路径的祖先只需要搜索权限。
// 先固定严格无链接的O_PATH句柄，再打开最终Root并比较同一对象，不能只检查后盲目重新打开。
// Root保留真实Name而非已关闭的/proc/self/fd路径，所有后续操作仍由Root FD约束。
func openStrictDirectory(path string) (*os.Root, error) {
	fd, err := openDirectoryPathHandle(path)
	if err != nil {
		return nil, err
	}
	held := os.NewFile(uintptr(fd), filepath.Clean(path))
	defer held.Close()
	before, err := held.Stat()
	if err != nil {
		return nil, DirectoryError(err)
	}
	root, err := os.OpenRoot(filepath.Clean(path))
	if err != nil {
		return nil, DirectoryError(err)
	}
	actual, err := root.Stat(".")
	if err != nil {
		root.Close()
		return nil, DirectoryError(err)
	}
	if !os.SameFile(before, actual) {
		root.Close()
		return nil, directoryFailure(ErrDirectoryChanged)
	}
	return root, nil
}

func openDirectoryPathHandle(path string) (int, error) {
	if !validAbsoluteDirectory(path) {
		return -1, directoryFailure(ErrDirectoryInvalid)
	}
	flags := unix.O_PATH | unix.O_DIRECTORY | unix.O_CLOEXEC | unix.O_NOFOLLOW
	fd, err := unix.Open(string(filepath.Separator), flags, 0)
	if err != nil {
		return -1, DirectoryError(err)
	}
	keep := false
	defer func() {
		if !keep {
			_ = unix.Close(fd)
		}
	}()
	for _, name := range strings.Split(strings.TrimPrefix(filepath.Clean(path), string(filepath.Separator)), string(filepath.Separator)) {
		if name == "" || name == "." {
			continue
		}
		var before, actual unix.Stat_t
		if err := unix.Fstatat(fd, name, &before, unix.AT_SYMLINK_NOFOLLOW); err != nil {
			return -1, DirectoryError(err)
		}
		switch before.Mode & unix.S_IFMT {
		case unix.S_IFLNK:
			return -1, directoryFailure(ErrDirectoryLink)
		case unix.S_IFDIR:
		default:
			return -1, directoryFailure(ErrDirectoryNotDir)
		}
		next, err := unix.Openat(fd, name, flags, 0)
		if err != nil {
			return -1, DirectoryError(err)
		}
		if err := unix.Fstat(next, &actual); err != nil {
			_ = unix.Close(next)
			return -1, DirectoryError(err)
		}
		if actual.Mode&unix.S_IFMT != unix.S_IFDIR || before.Dev != actual.Dev || before.Ino != actual.Ino {
			_ = unix.Close(next)
			return -1, directoryFailure(ErrDirectoryChanged)
		}
		_ = unix.Close(fd)
		fd = next
	}
	keep = true
	return fd, nil
}

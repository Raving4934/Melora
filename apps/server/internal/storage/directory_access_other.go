//go:build !linux

package storage

import "os"

// 非 Linux 仅检查 FD 仍有效；最终写探针始终是权限依据，不能猜测其他平台 ACL。
func CheckWritableDirectory(root *os.Root) error {
	if root == nil {
		return directoryFailure(ErrDirectoryInvalid)
	}
	_, err := root.Stat(".")
	return DirectoryError(err)
}

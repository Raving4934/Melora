//go:build linux

package storage

import (
	"os"
	"runtime"
	"syscall"
)

// CheckWritableDirectory 只做内核权限预检，不创建探针。fd 下的 . 不会重新解析用户路径；
// faccessat(flags=0) 使用内核 ACL，包服务真实/有效用户应一致，避免手工按 mode 猜权限。
// 保存目录时仍必须执行实际写入探针，不能把预检当成永久写入授权。
func CheckWritableDirectory(root *os.Root) error {
	if root == nil {
		return directoryFailure(ErrDirectoryInvalid)
	}
	if os.Getuid() != os.Geteuid() {
		return directoryFailure(ErrDirectoryPermission)
	}
	file, err := root.Open(".")
	if err != nil {
		return DirectoryError(err)
	}
	defer file.Close()
	err = syscall.Faccessat(int(file.Fd()), ".", 3, 0) // W_OK | X_OK，真实 fnOS ACL 由内核判断。
	runtime.KeepAlive(file)
	return WriteDirectoryError(err)
}

//go:build !linux

package download

import (
	"errors"
	"os"
)

func safeOpenFlags() int          { return 0 }
func singleLink(os.FileInfo) bool { return false }
func renameNoReplace(*os.Root, string, string) error {
	return errors.New("当前平台不支持安全原子下载落盘，请使用 Linux")
}

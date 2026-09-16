//go:build !linux

package storage

import (
	"os"
	"path/filepath"
	"strings"
)

func openStrictDirectory(path string) (*os.Root, error) {
	if !validAbsoluteDirectory(path) {
		return nil, directoryFailure(ErrDirectoryInvalid)
	}
	base, err := os.OpenRoot(string(filepath.Separator))
	if err != nil {
		return nil, DirectoryError(err)
	}
	defer base.Close()
	rel := strings.TrimPrefix(filepath.Clean(path), string(filepath.Separator))
	if rel == "" {
		rel = "."
	}
	return OpenRelativeDirectory(base, rel)
}

// 非Linux沿用既有严格目录校验；正式FPK只支持Linux amd64/arm64。
func ValidateDirectoryPath(path string) error {
	root, err := OpenDirectory(path)
	if err != nil {
		return err
	}
	return root.Close()
}

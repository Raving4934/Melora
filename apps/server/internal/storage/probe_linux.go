//go:build linux

package storage

import (
	"math"
	"os"
	"runtime"
	"syscall"
)

func probeFile(file *os.File) (Info, error) {
	var stat syscall.Statfs_t
	err := syscall.Fstatfs(int(file.Fd()), &stat)
	runtime.KeepAlive(file)
	if err != nil {
		return Info{}, ErrProbe
	}
	return statfsInfo(stat)
}

func statfsInfo(stat syscall.Statfs_t) (Info, error) {
	unit := int64(stat.Frsize)
	if unit == 0 {
		unit = int64(stat.Bsize)
	}
	if unit <= 0 || stat.Blocks > math.MaxUint64/uint64(unit) || stat.Bfree > stat.Blocks || stat.Bavail > stat.Bfree {
		return Info{}, ErrProbe
	}
	return Info{
		TotalBytes:     stat.Blocks * uint64(unit),
		AvailableBytes: stat.Bavail * uint64(unit),
		FreeBytes:      stat.Bfree * uint64(unit),
	}, nil
}

// Package storage 只读探测已授权目录的实际文件系统容量，不创建测试文件。
package storage

import (
	"errors"
	"os"
)

// Info 的 availableBytes 排除文件系统为特权用户保留的空间。
type Info struct {
	TotalBytes     uint64 `json:"totalBytes"`
	AvailableBytes uint64 `json:"availableBytes"`
	FreeBytes      uint64 `json:"freeBytes"`
}

var (
	ErrProbe       = errors.New("无法读取存储设备容量")
	ErrUnsupported = errors.New("当前平台不支持存储容量探测")
)

// Probe 的调用方必须先完成目录授权和路径校验；失败时不返回猜测容量。
func Probe(path string) (Info, error) {
	root, err := OpenDirectory(path)
	if err != nil {
		return Info{}, ErrProbe
	}
	defer root.Close()
	return ProbeRoot(root)
}

// ProbeRoot 探测已固定的目录 FD，避免下载目录重命名后探测到另一个设备。
func ProbeRoot(root *os.Root) (Info, error) {
	if root == nil {
		return Info{}, ErrProbe
	}
	file, err := root.Open(".")
	if err != nil {
		return Info{}, ErrProbe
	}
	defer file.Close()
	return probeFile(file)
}

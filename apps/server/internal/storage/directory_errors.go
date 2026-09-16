package storage

import (
	"errors"
	"os"
	"syscall"
)

var (
	ErrDirectory            = errors.New("目录访问失败，请检查存储连接后重试")
	ErrDirectoryMissing     = errors.New("目录不存在或尚未挂载，请检查路径与存储状态")
	ErrDirectoryPermission  = errors.New("目录无访问权限，请在飞牛应用设置为乐屿授权")
	ErrDirectoryNotWritable = errors.New("目录不可写，请为乐屿授予该目录的写入权限")
	ErrDirectoryReadOnly    = errors.New("目录所在存储为只读，请检查挂载状态或选择其它目录")
	ErrDirectoryNotDir      = errors.New("所选路径不是目录，请选择文件夹")
	ErrDirectoryInvalid     = errors.New("请输入有效的绝对目录路径")
	ErrDirectoryLink        = errors.New("目录包含不允许的链接，请选择真实目录")
	ErrDirectoryOutside     = errors.New("目录或链接超出已授权范围，请选择授权目录内的位置")
	ErrDirectoryChanged     = errors.New("授权目录或链接已变更，请确认飞牛授权后重启乐屿")
	ErrDirectoryLoop        = errors.New("目录链接循环或层级过多，请选择真实目录")
)

// Error 只返回可操作的短消息；底层 PathError 保留用于 errors.Is，不向客户端展开路径。
type directoryError struct {
	kind  error
	cause error
}

func (e *directoryError) Error() string        { return e.kind.Error() }
func (e *directoryError) Unwrap() error        { return e.cause }
func (e *directoryError) Is(target error) bool { return target == ErrDirectory || target == e.kind }

func DirectoryError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, ErrDirectory) {
		return err
	}
	kind := ErrDirectory
	switch {
	case errors.Is(err, os.ErrNotExist):
		kind = ErrDirectoryMissing
	case errors.Is(err, os.ErrPermission):
		kind = ErrDirectoryPermission
	case errors.Is(err, syscall.EROFS):
		kind = ErrDirectoryReadOnly
	case errors.Is(err, syscall.ENOTDIR):
		kind = ErrDirectoryNotDir
	case errors.Is(err, syscall.ELOOP):
		kind = ErrDirectoryLoop
	}
	return &directoryError{kind: kind, cause: err}
}

func directoryFailure(kind error) error { return &directoryError{kind: kind} }

// WriteDirectoryError 用于真正写入探针与只读的权限预检；不把磁盘只读误报为不存在。
func WriteDirectoryError(err error) error {
	if errors.Is(err, os.ErrPermission) {
		return &directoryError{kind: ErrDirectoryNotWritable, cause: err}
	}
	return DirectoryError(err)
}

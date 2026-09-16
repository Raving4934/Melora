//go:build !linux

package uninstall

import "errors"

func execute(operation) (int, error) {
	return 0, errors.New("拒绝清理：仅支持可核验 /proc 与继承生命周期锁的 Linux 环境")
}

//go:build !linux

package lxruntime

import "os/exec"

func configureProcess(*exec.Cmd) {}
func killProcess(cmd *exec.Cmd) {
	if cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

// 没有硬资源限制的实现时不声称安全支持，也不在主进程降级运行。
func workerLimits() error { return ErrUnsupported }

func workerShouldRetire() bool { return true }

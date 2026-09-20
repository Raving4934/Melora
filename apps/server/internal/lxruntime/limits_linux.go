//go:build linux

package lxruntime

import (
	"os"
	"os/exec"
	"runtime"
	"runtime/debug"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

func configureProcess(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
}
func killProcess(cmd *exec.Cmd) {
	if cmd.Process != nil {
		_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		_ = cmd.Process.Kill()
	}
}
func workerLimits() error {
	// AS 是硬上限，Go 内存限制是软目标；watchdog 在 HeapAlloc 越界时直接退出。
	// 不设置按 UID 全局计数的 NPROC，以免影响同用户运行的 NAS 服务。
	for _, limit := range []struct {
		resource int
		cur, max uint64
	}{
		{unix.RLIMIT_CORE, 0, 0}, {unix.RLIMIT_FSIZE, 0, 0}, {unix.RLIMIT_NOFILE, 64, 64},
		{unix.RLIMIT_CPU, 3, 4}, {unix.RLIMIT_AS, 2 << 30, 2 << 30}, {unix.RLIMIT_DATA, 192 << 20, 192 << 20},
	} {
		if err := unix.Setrlimit(limit.resource, &unix.Rlimit{Cur: limit.cur, Max: limit.max}); err != nil {
			return ErrResource
		}
	}
	if unix.Prctl(unix.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != nil {
		return ErrResource
	}
	debug.SetMemoryLimit(96 << 20)
	debug.SetGCPercent(50)
	debug.SetMaxStack(4 << 20)
	runtime.GOMAXPROCS(1)
	go func() {
		ticker := time.NewTicker(10 * time.Millisecond)
		defer ticker.Stop()
		for range ticker.C {
			var stats runtime.MemStats
			runtime.ReadMemStats(&stats)
			if stats.HeapAlloc > 128<<20 {
				os.Exit(72)
			}
		}
	}()
	return nil
}

// RLIMIT_CPU 是整个进程累计值，不能按调用清零。保留原 3/4 秒 soft/hard，
// 成功操作累计达到 2 秒后主动退休；单次超额仍由内核终止，父进程必须 Wait。
func workerShouldRetire() bool {
	var usage unix.Rusage
	if unix.Getrusage(unix.RUSAGE_SELF, &usage) != nil {
		return true
	}
	cpu := time.Duration(usage.Utime.Sec+usage.Stime.Sec)*time.Second + time.Duration(usage.Utime.Usec+usage.Stime.Usec)*time.Microsecond
	return cpu >= 2*time.Second
}

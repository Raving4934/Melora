//go:build linux

package uninstall

import (
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type process struct {
	start  uint64
	uid    uint32
	exe    string
	alive  bool
	exists bool
}

// /proc stat 的 comm 可含空格/括号；starttime 位于最后一个 ") " 之后的第 20 项。
func parseProcessStat(raw string) (uint64, bool, error) {
	end := strings.LastIndex(raw, ") ")
	if end < 0 {
		return 0, false, errors.New("无法解析进程身份")
	}
	fields := strings.Fields(raw[end+2:])
	if len(fields) < 20 {
		return 0, false, errors.New("无法解析进程身份")
	}
	start, err := strconv.ParseUint(fields[19], 10, 64)
	return start, fields[0] != "Z" && fields[0] != "X" && fields[0] != "x", err
}

func processUID(status string) (uint32, error) {
	for _, line := range strings.Split(status, "\n") {
		fields := strings.Fields(line)
		if len(fields) == 5 && fields[0] == "Uid:" {
			uid, err := strconv.ParseUint(fields[2], 10, 32)
			return uint32(uid), err
		}
	}
	return 0, errors.New("无法解析进程用户")
}

func (s *session) processAt(procRoot string, pid int) (process, error) {
	p := process{}
	base := filepath.Join(procRoot, strconv.Itoa(pid))
	status, err := os.ReadFile(filepath.Join(base, "status"))
	if errors.Is(err, os.ErrNotExist) {
		return p, nil
	}
	if err != nil {
		return p, errors.New("无法枚举进程身份")
	}
	p.exists = true
	p.uid, err = processUID(string(status))
	if err != nil || p.uid != s.uid {
		return p, err
	}
	stat, err := os.ReadFile(filepath.Join(base, "stat"))
	if errors.Is(err, os.ErrNotExist) {
		return p, nil
	}
	if err != nil {
		return p, errors.New("无法读取本包进程状态")
	}
	p.start, p.alive, err = parseProcessStat(string(stat))
	if err != nil || !p.alive {
		return p, err
	}
	p.exe, err = os.Readlink(filepath.Join(base, "exe"))
	if err != nil {
		// 退出竞态只能在明确已经消失/变成 zombie 时视为退出；权限错误不能当停服。
		now, e := os.ReadFile(filepath.Join(base, "stat"))
		if errors.Is(e, os.ErrNotExist) {
			p.alive = false
			return p, nil
		}
		if e == nil {
			_, alive, parseErr := parseProcessStat(string(now))
			if parseErr == nil && !alive {
				p.alive = false
				return p, nil
			}
		}
		return p, errors.New("无法核实本包进程的可执行文件，拒绝按已停服处理")
	}
	// 同一次扫描中也拒绝 PID 复用，不能把旧 UID/starttime 与新 exe 拼成一个实例。
	latest, err := os.ReadFile(filepath.Join(base, "stat"))
	if errors.Is(err, os.ErrNotExist) {
		p.alive = false
		return p, nil
	}
	if err != nil {
		return p, errors.New("进程复核失败")
	}
	start, alive, err := parseProcessStat(string(latest))
	if err != nil || start != p.start {
		return p, errors.New("进程身份在核验期间发生变化")
	}
	p.alive = alive
	return p, nil
}

func matchesExecutable(exe, server string) bool {
	return exe == server || exe == server+" (deleted)"
}

func (s *session) stopped(procRoot string, self int) error {
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return errors.New("无法枚举进程，不能证明服务已停止")
	}
	for _, entry := range entries {
		pid, e := strconv.Atoi(entry.Name())
		if e != nil || pid <= 0 || pid == self {
			continue
		}
		p, e := s.processAt(procRoot, pid)
		if e != nil {
			return e
		}
		// 主服务与 --lx-worker 都是精确同一 exe；不依赖 PID 文件、健康状态或 argv[0]。
		if p.alive && p.uid == s.uid && matchesExecutable(p.exe, s.server) {
			return errors.New("同包服务或音源 worker 仍存活；未执行清理")
		}
	}
	return nil
}

func (s *session) validatePID() error {
	t, err := s.inspect(s.run, "melora.pid")
	if err != nil {
		return errors.New("PID 文件不可信")
	}
	if t.stat.Ino == 0 {
		return nil // 缺失只表示没有停服目标，之后仍必须扫描本包进程。
	}
	data, err := s.read(t, 128)
	fields := strings.Fields(string(data))
	if err != nil || len(fields) != 2 {
		return errors.New("PID 文件损坏，拒绝猜测停服目标")
	}
	pid, e1 := strconv.Atoi(fields[0])
	start, e2 := strconv.ParseUint(fields[1], 10, 64)
	if e1 != nil || e2 != nil || pid <= 0 || start == 0 || pid == os.Getpid() || fields[0] != strconv.Itoa(pid) || fields[1] != strconv.FormatUint(start, 10) {
		return errors.New("PID 文件身份无效")
	}
	p, err := s.processAt("/proc", pid)
	if err != nil {
		return err
	}
	// 其它 UID 的进程即使未读取 exe，也不能被误认为不存在。
	if p.exists && p.uid != s.uid {
		return errors.New("PID 文件指向其它用户进程")
	}
	if !p.alive {
		return nil
	}
	if p.uid != s.uid || p.start != start || !matchesExecutable(p.exe, s.server) {
		return errors.New("PID 文件指向陌生或已复用的进程，拒绝停止或清理")
	}
	cmdline, err := os.ReadFile(filepath.Join("/proc", strconv.Itoa(pid), "cmdline"))
	if err != nil || len(strings.Split(strings.TrimSuffix(string(cmdline), "\x00"), "\x00")) != 1 {
		return errors.New("PID 文件不是受管主服务实例")
	}
	return nil
}

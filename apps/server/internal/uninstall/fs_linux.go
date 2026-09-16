//go:build linux

package uninstall

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
)

var errUnsafe = errors.New("私有路径、文件身份或权限校验失败")

type directory struct {
	fd     int
	path   string
	parent *directory
	name   string
	stat   unix.Stat_t
}

type target struct {
	dir  *directory
	name string
	stat unix.Stat_t
}

type session struct {
	uid      uint32
	app, etc *directory
	variable *directory
	run      *directory
	dirs     []*directory
	server   string
	lock     unix.Stat_t
	files    []target
	lockFD   int
}

// 每层以目录 FD + O_NOFOLLOW 打开，不能因子目录软链转向用户文件。
// 仅系统提供的根别名允许事先 EvalSymlinks；以下操作只使用其物理路径。
func openAbsoluteDirectory(path string) (int, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path || path == "/" {
		return -1, errUnsafe
	}
	// 祖先只需可搜索 (+x)，不需可列举 (+r)。仅作路径锚定的逐层打开使用
	// O_PATH，避免 O_RDONLY 错误拒绝 0111 祖先；每层仍检查父目录搜索权限，
	// 且保留 DIRECTORY/NOFOLLOW/CLOEXEC，后续包根/文件/锁校验不变。
	const directoryFlags = unix.O_PATH | unix.O_DIRECTORY | unix.O_NOFOLLOW | unix.O_CLOEXEC
	fd, err := unix.Open("/", directoryFlags, 0)
	if err != nil {
		return -1, err
	}
	for _, part := range strings.Split(strings.TrimPrefix(path, "/"), "/") {
		next, e := unix.Openat(fd, part, directoryFlags, 0)
		unix.Close(fd)
		if e != nil {
			return -1, e
		}
		fd = next
	}
	return fd, nil
}

func (s *session) root(env string, private bool) (*directory, error) {
	raw := os.Getenv(env)
	if !filepath.IsAbs(raw) || filepath.Clean(raw) == "/" {
		return nil, errUnsafe
	}
	path, err := filepath.EvalSymlinks(raw)
	if err != nil {
		return nil, errUnsafe
	}
	fd, err := openAbsoluteDirectory(path)
	if err != nil {
		return nil, errUnsafe
	}
	d := &directory{fd: fd, path: path}
	s.dirs = append(s.dirs, d)
	if unix.Fstat(fd, &d.stat) != nil || (private && (d.stat.Uid != s.uid || d.stat.Mode&0022 != 0)) {
		return nil, errUnsafe
	}
	return d, nil
}

// optionalRoot 只用于卸载 callback：平台可能已先删除一个空的私有根。
// 如果路径仍存在，则沿用 root 的全部物理路径、属主与权限校验；如果确实
// 不存在，保留一个 fd=-1 的哨兵，使后续清单把其中所有文件视为已缺失。
func (s *session) optionalRoot(env string, private bool) (*directory, error) {
	raw := os.Getenv(env)
	if !filepath.IsAbs(raw) || filepath.Clean(raw) != raw || raw == "/" {
		return nil, errUnsafe
	}
	if _, err := os.Lstat(raw); errors.Is(err, os.ErrNotExist) {
		d := &directory{fd: -1, path: raw}
		s.dirs = append(s.dirs, d)
		return d, nil
	} else if err != nil {
		return nil, errUnsafe
	}
	return s.root(env, private)
}

func (s *session) child(parent *directory, name string, optional bool) (*directory, error) {
	d := &directory{fd: -1, parent: parent, name: name, path: filepath.Join(parent.path, name)}
	s.dirs = append(s.dirs, d)
	if parent.fd < 0 && optional {
		return d, nil
	}
	fd, err := unix.Openat(parent.fd, name, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
	if optional && errors.Is(err, unix.ENOENT) {
		return d, nil
	}
	if err != nil {
		return nil, errUnsafe
	}
	d.fd = fd
	if unix.Fstat(fd, &d.stat) != nil || d.stat.Uid != s.uid || d.stat.Mode&0022 != 0 || d.stat.Dev != parent.stat.Dev {
		return nil, errUnsafe
	}
	return d, nil
}

func (s *session) close() {
	for i := len(s.dirs) - 1; i >= 0; i-- {
		if s.dirs[i].fd >= 0 {
			unix.Close(s.dirs[i].fd)
		}
	}
}

func sameNode(a, b unix.Stat_t) bool {
	return a.Dev == b.Dev && a.Ino == b.Ino && a.Mode == b.Mode && a.Uid == b.Uid && a.Gid == b.Gid
}

func sameFile(a, b unix.Stat_t) bool {
	return sameNode(a, b) && a.Nlink == b.Nlink && a.Size == b.Size && a.Mtim == b.Mtim && a.Ctim == b.Ctim
}

func (s *session) inspect(d *directory, name string) (target, error) {
	t := target{dir: d, name: name}
	if d.fd < 0 {
		return t, nil
	}
	err := unix.Fstatat(d.fd, name, &t.stat, unix.AT_SYMLINK_NOFOLLOW)
	if errors.Is(err, unix.ENOENT) {
		return t, nil
	}
	if err != nil || t.stat.Mode&unix.S_IFMT != unix.S_IFREG || t.stat.Uid != s.uid || t.stat.Nlink != 1 || t.stat.Mode&0022 != 0 {
		return t, errUnsafe
	}
	return t, nil
}

func (s *session) read(t target, limit int64) ([]byte, error) {
	if t.stat.Ino == 0 {
		return nil, nil
	}
	if t.stat.Size < 0 || t.stat.Size > limit {
		return nil, errUnsafe
	}
	fd, err := unix.Openat(t.dir.fd, t.name, unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_NONBLOCK|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, errUnsafe
	}
	file := os.NewFile(uintptr(fd), "private-uninstall-input")
	defer file.Close()
	var before, after unix.Stat_t
	if unix.Fstat(fd, &before) != nil || !sameFile(t.stat, before) {
		return nil, errUnsafe
	}
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil || int64(len(data)) > limit || unix.Fstat(fd, &after) != nil || !sameFile(before, after) {
		return nil, errUnsafe
	}
	return data, nil
}

func (s *session) validateDirectories() error {
	for _, d := range s.dirs {
		var now unix.Stat_t
		if d.parent != nil {
			if d.parent.fd < 0 && d.fd < 0 {
				continue
			}
			err := unix.Fstatat(d.parent.fd, d.name, &now, unix.AT_SYMLINK_NOFOLLOW)
			if d.fd < 0 && errors.Is(err, unix.ENOENT) {
				continue
			}
			if err != nil || d.fd < 0 || !sameNode(now, d.stat) {
				return errUnsafe
			}
		} else {
			if d.fd < 0 {
				if _, err := os.Lstat(d.path); errors.Is(err, os.ErrNotExist) {
					continue
				}
				return errUnsafe
			}
			fd, err := openAbsoluteDirectory(d.path)
			if err != nil {
				return errUnsafe
			}
			err = unix.Fstat(fd, &now)
			unix.Close(fd)
			if err != nil || !sameNode(now, d.stat) {
				return errUnsafe
			}
		}
	}
	return nil
}

func (s *session) validateLock() error {
	var inherited unix.Stat_t
	if unix.Fstat(s.lockFD, &inherited) != nil {
		return errors.New("必须继承生命周期 FD9 锁，禁止未持锁的手工清理")
	}
	t, err := s.inspect(s.run, "control.lock")
	if err != nil || t.stat.Ino == 0 || !sameNode(t.stat, inherited) || inherited.Nlink != 1 {
		return errors.New("FD9 不是本包同一个 control.lock")
	}
	if s.lock.Ino != 0 && !sameNode(s.lock, inherited) {
		return errors.New("生命周期锁身份发生变化")
	}
	// fdinfo 只列出该 open-file-description 持有的锁；不能用 flock(LOCK_EX)
	// “验证”，否则手工 CLI 的一个未锁 FD 也会在这里取得锁后绕过前置约束。
	data, err := os.ReadFile("/proc/self/fdinfo/" + strconv.Itoa(s.lockFD))
	if err != nil {
		return errors.New("无法核实继承的生命周期锁")
	}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 9 || fields[0] != "lock:" || fields[2] != "FLOCK" || fields[3] != "ADVISORY" || fields[4] != "WRITE" || fields[7] != "0" || fields[8] != "EOF" {
			continue
		}
		identity := strings.Split(fields[6], ":")
		if len(identity) != 3 {
			continue
		}
		major, e1 := strconv.ParseUint(identity[0], 16, 32)
		minor, e2 := strconv.ParseUint(identity[1], 16, 32)
		inode, e3 := strconv.ParseUint(identity[2], 10, 64)
		if e1 == nil && e2 == nil && e3 == nil && major == uint64(unix.Major(inherited.Dev)) && minor == uint64(unix.Minor(inherited.Dev)) && inode == inherited.Ino {
			s.lock = inherited
			return nil
		}
	}
	return errors.New("FD9 未持有本包 control.lock 的独占锁")
}

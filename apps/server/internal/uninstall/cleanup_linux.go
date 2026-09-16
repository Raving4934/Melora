//go:build linux

package uninstall

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"melora/internal/lxsource"

	"golang.org/x/sys/unix"
)

const (
	deferredHelperName = "uninstall-helper"
	deferredMarkerName = "uninstall-server.path"
)

func execute(op operation) (int, error) {
	s := &session{uid: uint32(os.Geteuid()), lockFD: 9}
	deferred := op == operationDeferredCleanup
	defer s.close()
	if err := s.initialize(deferred); err != nil {
		return 0, &Failure{Reason: err.Error()}
	}
	if err := s.validatePID(); err != nil {
		return 0, &Failure{Reason: err.Error()}
	}
	if op != operationPreflight {
		if err := s.stopped("/proc", os.Getpid()); err != nil {
			return 0, &Failure{Reason: err.Error()}
		}
	}
	if op == operationStopCheck {
		return 0, nil
	}
	if err := s.plan(); err != nil {
		return 0, &Failure{Reason: err.Error()}
	}
	if deferred {
		if err := s.planDeferredArtifacts(); err != nil {
			return 0, &Failure{Reason: err.Error()}
		}
	}
	if err := s.revalidate(); err != nil {
		return 0, &Failure{Reason: err.Error()}
	}
	if op == operationPreflight {
		return 0, nil
	}
	// 预检期间可能仍有退出中的 worker；从未以“不健康/没有 PID”代替退出证明。
	if err := s.stopped("/proc", os.Getpid()); err != nil {
		return 0, &Failure{Reason: err.Error()}
	}
	return s.apply(func(fd int, name string) error { return unix.Unlinkat(fd, name, 0) })
}

func (s *session) initialize(deferred bool) error {
	if s.uid == 0 || os.Getuid() != os.Geteuid() || os.Getenv("TRIM_UID") != strconv.Itoa(os.Geteuid()) {
		return errors.New("执行身份不是系统指定的非 root 包用户")
	}
	if deferred {
		return s.initializeDeferred()
	}
	var err error
	if s.app, err = s.root("TRIM_APPDEST", false); err != nil {
		return err
	}
	if s.etc, err = s.root("TRIM_PKGETC", true); err != nil {
		return err
	}
	if s.variable, err = s.root("TRIM_PKGVAR", true); err != nil {
		return err
	}
	if err := s.validateRootSeparation(s.app, s.etc, s.variable); err != nil {
		return err
	}
	s.server = filepath.Join(s.app.path, "bin", "melora")
	if err := s.validateSelf(s.server, false); err != nil {
		return err
	}
	if s.run, err = s.child(s.variable, "run", false); err != nil {
		return err
	}
	return s.validateLock()
}

func (s *session) initializeDeferred() error {
	var err error
	// callback 发生在 fnOS 清理安装文件之后；etc 可能已经被系统移除，var 必须
	// 仍存在，因为受限辅助快照与阶段证明都保存在 var/run 中。
	if s.etc, err = s.optionalRoot("TRIM_PKGETC", true); err != nil {
		return err
	}
	if s.variable, err = s.root("TRIM_PKGVAR", true); err != nil {
		return err
	}
	if err := s.validateRootSeparation(s.etc, s.variable); err != nil {
		return err
	}
	if s.run, err = s.child(s.variable, "run", false); err != nil {
		return err
	}
	helper := filepath.Join(s.run.path, deferredHelperName)
	if err := s.validateSelf(helper, true); err != nil {
		return errors.New("延后清理辅助快照身份不可信")
	}
	marker, err := s.inspect(s.run, deferredMarkerName)
	if err != nil || marker.stat.Ino == 0 {
		return errors.New("卸载前停服证明缺失或不可信")
	}
	raw, err := s.read(marker, 4096)
	if err != nil || len(raw) < 2 || raw[len(raw)-1] != '\n' || strings.Count(string(raw), "\n") != 1 {
		return errors.New("卸载前停服证明损坏")
	}
	s.server = strings.TrimSuffix(string(raw), "\n")
	if !filepath.IsAbs(s.server) || filepath.Clean(s.server) != s.server || s.server == "/" ||
		!strings.HasSuffix(s.server, string(filepath.Separator)+"bin"+string(filepath.Separator)+"melora") ||
		s.server == helper || pathsOverlap(s.server, s.variable.path) || (s.etc.fd >= 0 && pathsOverlap(s.server, s.etc.path)) {
		return errors.New("卸载前停服证明中的服务路径无效")
	}
	return s.validateLock()
}

func (s *session) validateRootSeparation(roots ...*directory) error {
	for i, a := range roots {
		for _, b := range roots[i+1:] {
			if a == nil || b == nil {
				continue
			}
			if (a.fd >= 0 && b.fd >= 0 && a.stat.Dev == b.stat.Dev && a.stat.Ino == b.stat.Ino) || pathsOverlap(a.path, b.path) {
				return errors.New("安装、配置与私有数据根必须独立且不得嵌套")
			}
		}
	}
	return nil
}

func pathsOverlap(a, b string) bool {
	return a == b || strings.HasPrefix(a, b+string(filepath.Separator)) || strings.HasPrefix(b, a+string(filepath.Separator))
}

func (s *session) validateSelf(expectedPath string, requirePackageOwner bool) error {
	self, err := os.Readlink("/proc/self/exe")
	if err != nil || !matchesExecutable(self, expectedPath) {
		return errors.New("清理辅助不是本包精确可执行实例")
	}
	bin, err := openAbsoluteDirectory(filepath.Dir(expectedPath))
	if err != nil {
		return errUnsafe
	}
	defer unix.Close(bin)
	var expected, actual unix.Stat_t
	if unix.Fstatat(bin, filepath.Base(expectedPath), &expected, unix.AT_SYMLINK_NOFOLLOW) != nil ||
		unix.Stat("/proc/self/exe", &actual) != nil || expected.Mode&unix.S_IFMT != unix.S_IFREG ||
		(requirePackageOwner && (expected.Uid != s.uid || expected.Nlink != 1)) || expected.Mode&0022 != 0 || !sameNode(expected, actual) {
		return errors.New("清理辅助与预期可执行文件身份不一致")
	}
	return nil
}

func (s *session) plan() error {
	data, err := s.child(s.variable, "data", true)
	if err != nil {
		return err
	}
	logs, err := s.child(s.variable, "log", true)
	if err != nil {
		return err
	}
	sources, err := s.child(data, "lx-sources", true)
	if err != nil {
		return err
	}
	// 固定名单，不遍历私有目录；日志只含当前代码明确创建的轮转代数。
	for _, group := range []struct {
		dir   *directory
		names []string
	}{
		{s.etc, []string{"melora.env"}},
		{data, []string{"melora.db", "melora.db-wal", "melora.db-shm", "melora.db-journal"}},
		{logs, []string{"server.log", "server.log.1", "server.log.2", "server.log.3", "lifecycle.log", "lifecycle.log.1"}},
	} {
		for _, name := range group.names {
			t, err := s.inspect(group.dir, name)
			if err != nil {
				return err
			}
			s.files = append(s.files, t)
		}
	}
	registry, err := s.inspect(sources, "registry.json")
	if err != nil {
		return errors.New("音源登记文件不可信")
	}
	if registry.stat.Ino != 0 {
		raw, err := s.read(registry, 2<<20)
		if err != nil {
			return errors.New("无法安全读取音源登记文件")
		}
		entries, err := parseRegistry(raw)
		if err != nil {
			return errors.New("音源登记损坏或不可信，拒绝猜测脚本归属")
		}
		for _, entry := range entries {
			t, err := s.inspect(sources, entry.id+".js")
			if err != nil {
				return errors.New("已登记音源脚本文件不可信")
			}
			if t.stat.Ino != 0 {
				code, err := s.read(t, 512<<10)
				sum := sha256.Sum256(code)
				if err != nil || len(code) == 0 || hex.EncodeToString(sum[:]) != entry.hash {
					return errors.New("已登记音源脚本完整性校验失败")
				}
			}
			// 已缺失的脚本可跳过，支持执行期部分清理后的安全重试。
			s.files = append(s.files, t)
		}
	}
	// registry 最后删除，发生中途 I/O 错误时尽量保留剩余脚本的可信清单。
	s.files = append(s.files, registry)
	return nil
}

func (s *session) planDeferredArtifacts() error {
	// callback 成功清理业务文件后，Shell 才移除这些阶段文件并尝试 rmdir。
	// 此处只验证、不加入业务删除计数；发生业务部分失败时保留辅助快照以便重试。
	for _, name := range []string{deferredMarkerName, deferredHelperName, "control.lock"} {
		t, err := s.inspect(s.run, name)
		if err != nil || t.stat.Ino == 0 {
			return errors.New("延后清理辅助文件缺失或不可信")
		}
	}
	return nil
}

type sourceEntry struct{ id, hash string }

func parseRegistry(raw []byte) ([]sourceEntry, error) {
	if !utf8.Valid(raw) {
		return nil, errUnsafe
	}
	if err := uniqueJSON(raw); err != nil {
		return nil, err
	}
	var envelope map[string]json.RawMessage
	if json.Unmarshal(raw, &envelope) != nil || envelope == nil || envelope["items"] == nil {
		return nil, errUnsafe
	}
	for key := range envelope {
		if key != "items" && key != "activeSourceId" {
			return nil, errUnsafe
		}
	}
	// 只复用持久化类型，不调用 lxsource.New/Inspect 或创建音源运行实例。
	// 先拒绝歧义，再与 Manager 使用相同 typed decoding，避免手工 map 挑选不同脚本。
	var state lxsource.State
	if json.Unmarshal(raw, &state) != nil || state.Items == nil || len(state.Items) > 20 {
		return nil, errUnsafe
	}
	seen := map[string]bool{}
	entries := make([]sourceEntry, 0, len(state.Items))
	for _, item := range state.Items {
		id, hash := item.ID, item.SHA256
		if len(id) != 24 || len(hash) != 64 || hash != strings.ToLower(hash) || id != hash[:24] || seen[id] {
			return nil, errUnsafe
		}
		if decoded, err := hex.DecodeString(hash); err != nil || len(decoded) != sha256.Size {
			return nil, errUnsafe
		}
		seen[id] = true
		entries = append(entries, sourceEntry{id: id, hash: hash})
	}
	if rawID, exists := envelope["activeSourceId"]; exists {
		if !strings.HasPrefix(strings.TrimSpace(string(rawID)), "\"") || (state.ActiveID != "" && !seen[state.ActiveID]) {
			return nil, errUnsafe
		}
	}
	return entries, nil
}

// 按 Unicode SimpleFold 等价类归一，匹配 encoding/json 的 EqualFold 语义；
// 仅 ToLower/ToUpper 无法覆盖长 s、Kelvin sign 等别名。每个键线性处理。
func foldJSONKey(key string) string {
	return strings.Map(func(r rune) rune {
		lowest := r
		for next := unicode.SimpleFold(r); next != r; next = unicode.SimpleFold(next) {
			if next < lowest {
				lowest = next
			}
		}
		return lowest
	}, key)
}

func nonCanonicalCriticalKey(name, folded string) bool {
	switch folded {
	case "ID":
		return name != "id"
	case "SHA256":
		return name != "sha256"
	case "ITEMS":
		return name != "items"
	case "ACTIVESOURCEID":
		return name != "activeSourceId"
	}
	return false
}

// encoding/json 默认接受重复键；清理清单必须拒绝这种歧义，且限制递归深度。
func uniqueJSON(raw []byte) error {
	d := json.NewDecoder(strings.NewReader(string(raw)))
	d.UseNumber()
	var visit func(int) error
	visit = func(depth int) error {
		if depth > 64 {
			return errUnsafe
		}
		token, err := d.Token()
		if err != nil {
			return err
		}
		delim, container := token.(json.Delim)
		if !container {
			return nil
		}
		if delim != '{' && delim != '[' {
			return errUnsafe
		}
		seen := map[string]bool{}
		for d.More() {
			if delim == '{' {
				key, err := d.Token()
				name, ok := key.(string)
				if err != nil || !ok {
					return errUnsafe
				}
				folded := foldJSONKey(name)
				if seen[folded] || nonCanonicalCriticalKey(name, folded) {
					return errUnsafe
				}
				seen[folded] = true
			}
			if err := visit(depth + 1); err != nil {
				return err
			}
		}
		_, err = d.Token()
		return err
	}
	if err := visit(0); err != nil {
		return err
	}
	if _, err := d.Token(); !errors.Is(err, io.EOF) {
		return errUnsafe
	}
	return nil
}

func (s *session) revalidate() error {
	if err := s.validateDirectories(); err != nil {
		return err
	}
	if err := s.validateLock(); err != nil {
		return err
	}
	for _, t := range s.files {
		now, err := s.inspect(t.dir, t.name)
		if err != nil || !sameFile(now.stat, t.stat) {
			return errors.New("清理目标在预检期间发生变化")
		}
		// 执行身份的 real/effective UID 已相同。可预见的目录写权限问题先拒绝，
		// 但执行期间的磁盘/权限变化仍可能产生部分失败，不能宣称事务删除。
		if t.stat.Ino != 0 && unix.Faccessat(t.dir.fd, ".", unix.W_OK|unix.X_OK, 0) != nil {
			return errors.New("私有目录不可写，拒绝开始清理")
		}
	}
	return nil
}

// 边界：受管服务/worker 必须停服并由同一生命周期锁串行化。清理期间勿手工
// 修改私有文件。FD/身份复核不是 inode 条件原子 unlink，更不是对同 UID 任意
// 写入者的 OS 沙箱；不承诺抵抗绕过锁的持续 rename，也不承诺事务回滚。
func (s *session) apply(remove func(int, string) error) (int, error) {
	removed := 0
	for _, t := range s.files {
		if err := s.validateDirectories(); err != nil {
			return removed, &Failure{Removed: removed, Reason: "清理期间目录身份发生变化"}
		}
		if err := s.validateLock(); err != nil {
			return removed, &Failure{Removed: removed, Reason: "清理期间生命周期锁校验失败"}
		}
		now, err := s.inspect(t.dir, t.name)
		if err != nil || !sameFile(now.stat, t.stat) {
			return removed, &Failure{Removed: removed, Reason: "清理期间文件身份发生变化"}
		}
		if t.stat.Ino == 0 {
			continue
		}
		if err := remove(t.dir.fd, t.name); err != nil {
			return removed, &Failure{Removed: removed, Reason: "删除已知文件失败，请检查私有目录权限或磁盘状态"}
		}
		removed++
	}
	return removed, nil
}

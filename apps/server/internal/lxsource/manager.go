// Package lxsource 管理私有LX脚本与选择状态；所有JS执行委托独立受限运行器。
package lxsource

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode"
	"unicode/utf8"

	"melora/internal/config"
	"melora/internal/lxruntime"
	"melora/internal/netguard"
)

const MaxScriptBytes = 512 << 10
const maxSources = 20

var (
	ErrMissing       = errors.New("音源不存在")
	ErrNoActive      = errors.New("尚未选择可用的 LX 音源，请先在设置中导入并选择音源")
	ErrUnsupported   = errors.New("当前 LX 音源不支持该平台或操作")
	ErrInvalidScript = errors.New("请选择有效的 UTF-8 JavaScript 文件，最大 512 KiB")
	ErrLimit         = errors.New("最多导入 20 个音源，请先删除不需要的音源")
	ErrStorage       = errors.New("无法保存音源配置，请检查应用私有目录")
	ErrHosts         = errors.New("HTTP 白名单仅接受主机名或公网 IP，不含协议、路径或凭据，最多 10 项")
)

type Source struct {
	ID             string                      `json:"id"`
	Name           string                      `json:"name"`
	Version        string                      `json:"version"`
	Author         string                      `json:"author"`
	Description    string                      `json:"description"`
	Filename       string                      `json:"filename"`
	SHA256         string                      `json:"sha256"`
	Status         string                      `json:"status"`
	Error          string                      `json:"error,omitempty"`
	Platforms      map[string]lxruntime.Source `json:"platforms"`
	AllowHTTPHosts []string                    `json:"allowHTTPHosts"`
	ImportedAt     string                      `json:"importedAt"`
	CheckedAt      string                      `json:"checkedAt,omitempty"`
}
type State struct {
	Items    []Source `json:"items"`
	ActiveID string   `json:"activeSourceId"`
}
type Runner interface {
	Inspect(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error)
	Invoke(context.Context, string, string, string, map[string]any, lxruntime.Options) (json.RawMessage, error)
}
type ProcessRunner struct{}

func (ProcessRunner) Inspect(ctx context.Context, code string, opts lxruntime.Options) (lxruntime.Descriptor, error) {
	return lxruntime.Inspect(ctx, code, opts)
}
func (ProcessRunner) Invoke(ctx context.Context, code, platform, action string, info map[string]any, opts lxruntime.Options) (json.RawMessage, error) {
	return lxruntime.Invoke(ctx, code, platform, action, info, opts)
}

type Manager struct {
	mu       sync.Mutex
	root     *os.Root
	state    State
	runner   Runner
	checking map[string]bool
}

func New(dir string, runner Runner) (*Manager, error) {
	if err := config.NoSymlinks(filepath.Dir(dir)); err != nil {
		return nil, ErrStorage
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, ErrStorage
	}
	if err := config.NoSymlinks(dir); err != nil {
		return nil, ErrStorage
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return nil, ErrStorage
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		return nil, ErrStorage
	}
	m := &Manager{root: root, runner: runner, state: State{Items: []Source{}}, checking: map[string]bool{}}
	raw, err := m.readPrivate("registry.json", 2<<20)
	if errors.Is(err, os.ErrNotExist) {
		if m.runner == nil {
			m.runner = lxruntime.NewPool()
		}
		return m, nil
	}
	if err != nil {
		root.Close()
		return nil, ErrStorage
	}
	if json.Unmarshal(raw, &m.state) != nil || len(m.state.Items) > maxSources {
		root.Close()
		return nil, ErrStorage
	}
	seen := map[string]bool{}
	for i := range m.state.Items {
		source := &m.state.Items[i]
		if !validID(source.ID) || seen[source.ID] || len(source.SHA256) != 64 {
			root.Close()
			return nil, ErrStorage
		}
		seen[source.ID] = true
		if _, err := validateHosts(source.AllowHTTPHosts); err != nil {
			root.Close()
			return nil, ErrStorage
		}
		code, err := m.readCode(*source)
		if err != nil || len(code) == 0 {
			source.Status = "error"
			source.Error = "音源文件缺失或校验失败"
		}
		if source.Status == "checking" {
			source.Status = "error"
			source.Error = "上次初始化未完成，请重新检查"
		}
	}
	if m.state.ActiveID != "" && !seen[m.state.ActiveID] {
		m.state.ActiveID = ""
	}
	// 仅在源目录/注册状态校验成功后创建池，失败的 New 不遗留会话清理 goroutine。
	if m.runner == nil {
		m.runner = lxruntime.NewPool()
	}
	return m, nil
}
func (m *Manager) Close() error {
	var runnerErr error
	if closer, ok := m.runner.(interface{ Close() error }); ok {
		runnerErr = closer.Close()
	}
	return errors.Join(runnerErr, m.root.Close())
}
func validID(id string) bool { return regexp.MustCompile(`^[a-f0-9]{24}$`).MatchString(id) }
func clone(source Source) Source {
	data, _ := json.Marshal(source)
	var out Source
	_ = json.Unmarshal(data, &out)
	return out
}
func (m *Manager) List() State {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := State{Items: make([]Source, 0, len(m.state.Items)), ActiveID: m.state.ActiveID}
	for _, s := range m.state.Items {
		out.Items = append(out.Items, clone(s))
	}
	return out
}
func (m *Manager) find(id string) int {
	for i, s := range m.state.Items {
		if s.ID == id {
			return i
		}
	}
	return -1
}
func (m *Manager) readPrivate(name string, limit int64) ([]byte, error) {
	info, err := m.root.Lstat(name)
	if err != nil {
		return nil, err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.Mode().IsRegular() || stat.Nlink != 1 || stat.Uid != uint32(os.Geteuid()) || info.Size() > limit {
		return nil, ErrStorage
	}
	file, err := m.root.Open(name)
	if err != nil {
		return nil, ErrStorage
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil || !os.SameFile(info, opened) {
		return nil, ErrStorage
	}
	data, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil || int64(len(data)) > limit {
		return nil, ErrStorage
	}
	return data, nil
}
func (m *Manager) readCode(source Source) (string, error) {
	if !validID(source.ID) {
		return "", ErrStorage
	}
	data, err := m.readPrivate(source.ID+".js", MaxScriptBytes)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(data)
	if hex.EncodeToString(sum[:]) != source.SHA256 {
		return "", ErrStorage
	}
	return string(data), nil
}
func (m *Manager) save() error {
	data, err := json.Marshal(m.state)
	if err != nil {
		return ErrStorage
	}
	name := ".registry-" + rand.Text()
	f, err := m.root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return ErrStorage
	}
	defer m.root.Remove(name)
	// 默认ACL可能忽略umask，显式限制敏感源配置权限。
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		return ErrStorage
	}
	if err = m.root.Rename(name, "registry.json"); err != nil {
		return ErrStorage
	}
	return nil
}
func cleanText(text string, max int) string {
	text = strings.TrimSpace(strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, text))
	runes := []rune(text)
	if len(runes) > max {
		text = string(runes[:max])
	}
	return text
}
func header(code, key string, max int) string {
	if len(code) > 8192 {
		code = code[:8192]
	}
	re := regexp.MustCompile(`(?m)^\s*\*?\s*@` + key + `[ \t]+([^\r\n]*)`)
	parts := re.FindStringSubmatch(code)
	if len(parts) < 2 {
		return ""
	}
	return cleanText(parts[1], max)
}
func validateHosts(hosts []string) ([]string, error) {
	if len(hosts) > 10 {
		return nil, ErrHosts
	}
	result := []string{}
	seen := map[string]bool{}
	for _, raw := range hosts {
		h := strings.ToLower(strings.TrimSpace(raw))
		if h == "" {
			continue
		}
		if len(h) > 253 || strings.ContainsAny(h, "/:@?#\\ \t\r\n") || strings.Contains(h, "..") || strings.HasSuffix(h, ".") {
			return nil, ErrHosts
		}
		if net.ParseIP(h) == nil && !regexp.MustCompile(`^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$`).MatchString(h) {
			return nil, ErrHosts
		}
		if !seen[h] {
			result = append(result, h)
			seen[h] = true
		}
	}
	sort.Strings(result)
	// 与运行器使用同一静态策略，拒绝明知不可能放行的私网/本地主机，
	// 不将无效策略保存后再让每一次运行都失败。此处不解析DNS、不发请求。
	broker, err := netguard.NewBroker(netguard.Options{AllowHTTPHosts: result})
	if err != nil {
		return nil, ErrHosts
	}
	broker.Close()
	return result, nil
}
func (m *Manager) Import(ctx context.Context, filename string, data []byte, hosts []string) (Source, bool, error) {
	data = bytes.TrimPrefix(data, []byte{0xef, 0xbb, 0xbf})
	if len(data) == 0 || len(data) > MaxScriptBytes || !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 || !strings.EqualFold(filepath.Ext(filename), ".js") {
		return Source{}, false, ErrInvalidScript
	}
	allowed, err := validateHosts(hosts)
	if err != nil {
		return Source{}, false, err
	}
	sum := sha256.Sum256(data)
	hash := hex.EncodeToString(sum[:])
	id := hash[:24]
	m.mu.Lock()
	if i := m.find(id); i >= 0 {
		s := clone(m.state.Items[i])
		m.mu.Unlock()
		return s, false, nil
	}
	if len(m.state.Items) >= maxSources {
		m.mu.Unlock()
		return Source{}, false, ErrLimit
	}
	f, err := m.root.OpenFile(id+".js", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		existing, e := m.readPrivate(id+".js", MaxScriptBytes)
		if e != nil || !bytes.Equal(existing, data) {
			m.mu.Unlock()
			return Source{}, false, ErrStorage
		}
	} else {
		if err = f.Chmod(0600); err == nil {
			_, err = f.Write(data)
		}
		if err == nil {
			err = f.Sync()
		}
		closeErr := f.Close()
		if err != nil || closeErr != nil {
			_ = m.root.Remove(id + ".js")
			m.mu.Unlock()
			return Source{}, false, ErrStorage
		}
	}
	source := Source{ID: id, SHA256: hash, Filename: cleanText(filepath.Base(filename), 160), Name: header(string(data), "name", 80), Author: header(string(data), "author", 80), Version: header(string(data), "version", 40), Description: header(string(data), "description", 240), Status: "checking", AllowHTTPHosts: allowed, Platforms: map[string]lxruntime.Source{}, ImportedAt: time.Now().UTC().Format(time.RFC3339)}
	if source.Name == "" {
		source.Name = source.Filename
	}
	m.state.Items = append(m.state.Items, source)
	if err = m.save(); err != nil {
		m.state.Items = m.state.Items[:len(m.state.Items)-1]
		m.mu.Unlock()
		return Source{}, false, err
	}
	m.mu.Unlock()
	source, err = m.Check(ctx, id)
	return source, true, err
}
func (m *Manager) Check(ctx context.Context, id string) (Source, error) {
	m.mu.Lock()
	i := m.find(id)
	if i < 0 {
		m.mu.Unlock()
		return Source{}, ErrMissing
	}
	if m.checking[id] {
		s := clone(m.state.Items[i])
		m.mu.Unlock()
		return s, nil
	}
	source := clone(m.state.Items[i])
	m.checking[id] = true
	m.mu.Unlock()
	code, err := m.readCode(source)
	var descriptor lxruntime.Descriptor
	if err == nil {
		descriptor, err = m.runner.Inspect(ctx, code, lxruntime.Options{AllowHTTPHosts: source.AllowHTTPHosts, AllowPublicHTTP: true})
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.checking, id)
	i = m.find(id)
	if i < 0 {
		return Source{}, ErrMissing
	}
	source = m.state.Items[i]
	source.CheckedAt = time.Now().UTC().Format(time.RFC3339)
	source.Error = ""
	if err != nil {
		source.Status = "error"
		source.Error = publicError(err)
	} else if !descriptor.Status || len(descriptor.Sources) == 0 {
		source.Status = "error"
		source.Error = "脚本未声明可用平台，请检查LX接口兼容性"
	} else {
		source.Status = "ready"
		source.Platforms = descriptor.Sources
	}
	before, active := m.state.Items[i], m.state.ActiveID
	m.state.Items[i] = source
	if source.Status == "ready" && m.state.ActiveID == "" {
		m.state.ActiveID = id
	}
	if err = m.save(); err != nil {
		m.state.Items[i] = before
		m.state.ActiveID = active
		return Source{}, err
	}
	return clone(source), nil
}

// publicError 不回显运行器的任意文本；即使短错误也可能包含脚本密钥。
func publicError(err error) string {
	if errors.Is(err, context.Canceled) {
		return "检查已取消，可重新检查"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "音源初始化超时"
	}
	for _, kind := range []error{lxruntime.ErrScript, lxruntime.ErrTimeout, lxruntime.ErrResource, lxruntime.ErrLimit, lxruntime.ErrProtocol, lxruntime.ErrUnsupported, lxruntime.ErrNetwork} {
		if errors.Is(err, kind) {
			return kind.Error()
		}
	}
	return "音源运行失败，请检查接口兼容性或远端服务状态"
}

func (m *Manager) Configure(id string, hosts []string) (Source, error) {
	allowed, err := validateHosts(hosts)
	if err != nil {
		return Source{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	i := m.find(id)
	if i < 0 {
		return Source{}, ErrMissing
	}
	if m.checking[id] {
		return Source{}, errors.New("音源正在检查，请稍后修改")
	}
	before := m.state.Items[i]
	m.state.Items[i].AllowHTTPHosts = allowed
	if err = m.save(); err != nil {
		m.state.Items[i] = before
		return Source{}, err
	}
	return clone(m.state.Items[i]), nil
}
func (m *Manager) Select(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if id != "" {
		i := m.find(id)
		if i < 0 {
			return ErrMissing
		}
		if m.state.Items[i].Status != "ready" {
			return errors.New("该音源尚未初始化成功，请先重新检查")
		}
	}
	before := m.state.ActiveID
	m.state.ActiveID = id
	if err := m.save(); err != nil {
		m.state.ActiveID = before
		return err
	}
	return nil
}

// Export 仅供用户主动导出原文件；初始化失败或未选择的源同样可导出。
// 不执行脚本、不改变选择；锁内完成有界读取，避免与 Delete 的状态/文件删除交错。
func (m *Manager) Export(id string) (filename string, code string, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	i := m.find(id)
	if i < 0 {
		return "", "", ErrMissing
	}
	source := m.state.Items[i]
	code, err = m.readCode(source)
	if err != nil {
		return "", "", ErrStorage // 不向调用方返回文件路径、底层错误或部分代码。
	}
	filename = cleanText(filepath.Base(strings.ReplaceAll(source.Filename, "\\", "/")), 160)
	if filename == "" || filename == "." || filename == ".." || filename == "/" {
		filename = source.ID + ".js"
	}
	// 旧 Filename 是最多160字符的展示名，导入时截断可能只剩 .j 或没有扩展名。
	// 导出保留完整可重新导入的后缀；不改登记名称、源文件内容或当前选择。
	if !strings.EqualFold(filepath.Ext(filename), ".js") {
		filename = cleanText(filename, 157) + ".js"
	}
	return filename, code, nil
}

func (m *Manager) Delete(id string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	i := m.find(id)
	if i < 0 {
		return "", ErrMissing
	}
	if m.checking[id] {
		return "", errors.New("音源正在检查，请稍后删除")
	}
	before, active := append([]Source(nil), m.state.Items...), m.state.ActiveID
	m.state.Items = append(m.state.Items[:i], m.state.Items[i+1:]...)
	if active == id {
		m.state.ActiveID = ""
	}
	if err := m.save(); err != nil {
		m.state.Items = before
		m.state.ActiveID = active
		return "", err
	}
	if err := m.root.Remove(id + ".js"); err != nil && !errors.Is(err, os.ErrNotExist) {
		return "音源已移除，但脚本文件清理失败，请检查私有目录", nil
	}
	return "", nil
}
func (m *Manager) Active() (Source, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	i := m.find(m.state.ActiveID)
	if i < 0 || m.state.Items[i].Status != "ready" {
		return Source{}, false
	}
	return clone(m.state.Items[i]), true
}

// Invoke 保留旧的当前源契约；InvokeSource 为单次解析指定源，不改变选择状态。
func (m *Manager) Invoke(ctx context.Context, platform, action string, info map[string]any) (json.RawMessage, error) {
	source, ok := m.Active()
	if !ok {
		return nil, ErrNoActive
	}
	return m.InvokeSource(ctx, source.ID, platform, action, info)
}

// InvokeSource 每次重新核验源状态、能力与脚本哈希。只在短临界区复制配置，
// 子进程执行期间不持锁、不 Select，也不把一次远端失败写成全局不可用状态。
func (m *Manager) InvokeSource(ctx context.Context, id, platform, action string, info map[string]any) (json.RawMessage, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	m.mu.Lock()
	i := m.find(id)
	if i < 0 {
		m.mu.Unlock()
		return nil, ErrMissing
	}
	source := clone(m.state.Items[i])
	m.mu.Unlock()
	if source.Status != "ready" {
		return nil, ErrUnsupported
	}
	capability, ok := source.Platforms[platform]
	if !ok {
		return nil, ErrUnsupported
	}
	permitted := false
	for _, name := range capability.Actions {
		if name == action {
			permitted = true
			break
		}
	}
	if !permitted {
		return nil, ErrUnsupported
	}
	code, err := m.readCode(source)
	if err != nil {
		return nil, ErrStorage
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	result, err := m.runner.Invoke(ctx, code, platform, action, info, lxruntime.Options{AllowHTTPHosts: source.AllowHTTPHosts, AllowPublicHTTP: true})
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		kind := error(lxruntime.ErrScript)
		for _, candidate := range []error{context.Canceled, context.DeadlineExceeded, lxruntime.ErrScript, lxruntime.ErrTimeout, lxruntime.ErrResource, lxruntime.ErrLimit, lxruntime.ErrProtocol, lxruntime.ErrUnsupported, lxruntime.ErrNetwork} {
			if errors.Is(err, candidate) {
				kind = candidate
				break
			}
		}
		return nil, &sourceInvokeError{kind: kind, message: "音源解析失败：" + publicError(err)}
	}
	return result, nil
}

type sourceInvokeError struct {
	kind    error
	message string
}

func (e *sourceInvokeError) Error() string { return e.message }
func (e *sourceInvokeError) Unwrap() error { return e.kind }

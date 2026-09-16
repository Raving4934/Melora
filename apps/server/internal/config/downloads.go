package config

import (
	"crypto/rand"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"melora/internal/storage"
)

func (c *Config) loadDownloadAuthorization(getenv func(string) string) {
	if c.GatewayAuth != "fnos-admin" {
		c.DownloadAuthorization = "none"
		if c.DownloadRoot != "" {
			c.DownloadAuthorization = "environment"
		}
	} else {
		c.DownloadAuthorization = "fnos"
		c.DownloadRoots = []string{}
		for _, raw := range strings.Split(getenv("TRIM_DATA_ACCESSIBLE_PATHS"), ":") {
			if len(c.DownloadRoots) >= 64 {
				break
			}
			if !validDownloadInput(raw) {
				continue
			}
			c.DownloadRoots = append(c.DownloadRoots, filepath.Clean(raw))
		}
	}
	c.downloadGrants = c.makeDownloadGrants()
}

// AuthorizedDownloadRoots 返回可访问的展示根；真实物理范围由不可变授权快照约束。
func (c Config) AuthorizedDownloadRoots() []string {
	out := []string{}
	seen := map[string]bool{}
	for _, grant := range c.downloadGrantList() {
		root, err := grant.open()
		if err != nil {
			continue
		}
		root.Close()
		if seen[grant.canonical] {
			continue
		}
		seen[grant.canonical] = true
		out = append(out, grant.displayRoots()[0])
	}
	return out
}
func (c Config) HasDownloadAuthorization() bool { return len(c.AuthorizedDownloadRoots()) > 0 }

// DownloadAuthorizationError 保留已声明根的具体故障，区分根缺失/无权限与从未授权。
func (c Config) DownloadAuthorizationError() error {
	var first error
	for _, grant := range c.downloadGrantList() {
		root, err := grant.open()
		if err == nil {
			root.Close()
			return nil
		}
		if first == nil {
			first = err
		}
	}
	if first != nil {
		return first
	}
	return errDownloadNotAuthorized
}

type DownloadDirectory struct {
	Path          string
	CanonicalPath string
	Root          string
}

// OpenDownloadDirectoryInfo 先以已授权的展示/物理前缀匹配，再在固定授权FD中解析内层链接。
// fnOS 的另一显示 alias 只做元数据解析；canonical 命中既有授权后仍从该根 FD 打开，不产生新授权。
func (c Config) OpenDownloadDirectoryInfo(path string) (*os.Root, DownloadDirectory, error) {
	if !validDownloadInput(path) {
		return nil, DownloadDirectory{}, storage.ErrDirectoryInvalid
	}
	path = filepath.Clean(path)
	var first error
	grants := c.downloadGrantList()
	for _, grant := range grants {
		displayRoot, rel, match := grant.match(path)
		if !match {
			continue
		}
		root, err := grant.open()
		if err != nil {
			if first == nil {
				first = err
			}
			continue
		}
		selected, canonical, err := storage.OpenContainedDirectory(root, grant.canonical, rel, grant.displayRoots())
		root.Close()
		if err != nil {
			if first == nil {
				first = err
			}
			continue
		}
		return selected, DownloadDirectory{Path: path, CanonicalPath: canonical, Root: displayRoot}, nil
	}
	if first != nil {
		return nil, DownloadDirectory{}, first
	}
	if c.DownloadAuthorization == "fnos" && len(grants) > 0 {
		selected, directory, aliasErr := c.openAlternateDownloadAlias(path, grants)
		if aliasErr == nil {
			return selected, directory, nil
		}
		if !errors.Is(aliasErr, storage.ErrDirectoryOutside) {
			return nil, DownloadDirectory{}, aliasErr
		}
	}
	if len(grants) == 0 {
		return nil, DownloadDirectory{}, errDownloadNotAuthorized
	}
	// 授权根或管理员限制整体失效时报告该可信配置的真实故障，不能把旧物理路径泛化为越界。
	if err := c.DownloadAuthorizationError(); err != nil {
		return nil, DownloadDirectory{}, err
	}
	return nil, DownloadDirectory{}, storage.ErrDirectoryOutside
}

// 旧调用契约保留：第二返回值是与输入同一展示域的授权根，用于浏览面包屑，不是 worker 路径。
func (c Config) OpenDownloadDirectory(path string) (*os.Root, string, error) {
	root, selected, err := c.OpenDownloadDirectoryInfo(path)
	return root, selected.Root, err
}
func (c Config) DownloadRootFor(path string) (string, error) {
	root, selected, err := c.OpenDownloadDirectoryInfo(path)
	if err != nil {
		return "", err
	}
	root.Close()
	return selected.Root, nil
}

// ValidateDownloadPath/OpenWritable 的路径结果是 canonical 物理路径，供持久化与严格 worker 使用。
func (c Config) ValidateDownloadPath(path string) (string, error) {
	root, canonical, err := c.OpenWritableDownloadDirectory(path)
	if err != nil {
		return "", err
	}
	root.Close()
	return canonical, nil
}
func (c Config) OpenWritableDownloadDirectory(path string) (*os.Root, string, error) {
	root, selected, err := c.OpenDownloadDirectoryInfo(path)
	if err != nil {
		return nil, "", err
	}
	if err = probeDownloadDirectory(root); err != nil {
		root.Close()
		return nil, "", err
	}
	return root, selected.CanonicalPath, nil
}

func probeDownloadDirectory(root *os.Root) error {
	name := ".melora-write-probe-" + rand.Text()
	file, err := root.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return storage.WriteDirectoryError(err)
	}
	_, writeErr := file.Write([]byte("Melora directory validation\n"))
	syncErr := file.Sync()
	closeErr := file.Close()
	removeErr := root.Remove(name)
	for _, err := range []error{writeErr, syncErr, closeErr, removeErr} {
		if err != nil {
			return storage.WriteDirectoryError(err)
		}
	}
	return nil
}

// DisplayDownloadPath 仅反向映射已授权 canonical 路径，便于 UI 显示 fnOS 用户熟悉的根名。
func (c Config) DisplayDownloadPath(canonical string) string {
	for _, grant := range c.downloadGrantList() {
		if grant.problem != nil || grant.canonical == "" || !Within(grant.canonical, canonical) {
			continue
		}
		rel, _ := filepath.Rel(grant.canonical, canonical)
		return filepath.Join(grant.displayRoots()[0], rel)
	}
	return canonical
}

// fnOS 授权变量可能采用物理路径，用户输入却是系统的另一显示路径。
// 只解析该输入的链接元数据；不对未授权 canonical 路径 OpenRoot/探测空间/读写文件。
// 未授权路径的 ENOENT/EACCES 不向客户端泄露；已匹配授权域的错误由上方准确报告。
func (c Config) openAlternateDownloadAlias(path string, grants []downloadGrant) (*os.Root, DownloadDirectory, error) {
	bounded := false
	for _, grant := range grants {
		if grant.problem == nil && grant.canonical != "" {
			bounded = true
			break
		}
	}
	if !bounded {
		return nil, DownloadDirectory{}, storage.ErrDirectoryOutside
	}
	canonical, err := storage.CanonicalDirectoryPath(path)
	if err != nil || canonical == path {
		return nil, DownloadDirectory{}, storage.ErrDirectoryOutside
	}
	for _, grant := range grants {
		if grant.problem != nil || grant.canonical == "" || !Within(grant.canonical, canonical) {
			continue
		}
		root, err := grant.open()
		if err != nil {
			return nil, DownloadDirectory{}, err
		}
		rel, _ := filepath.Rel(grant.canonical, canonical)
		selected, actualCanonical, err := storage.OpenContainedDirectory(root, grant.canonical, rel, grant.displayRoots())
		root.Close()
		if err != nil {
			return nil, DownloadDirectory{}, err
		}
		actual, statErr := selected.Stat(".")
		expected, pathErr := os.Stat(path)
		if statErr != nil || pathErr != nil || !os.SameFile(actual, expected) {
			selected.Close()
			return nil, DownloadDirectory{}, storage.ErrDirectoryChanged
		}
		// 面包屑只退到仍映射在同一授权范围的显示祖先，不跳到未授权的用户根目录。
		displayRoot := path
		for i := 0; i < 64; i++ {
			parent := filepath.Dir(displayRoot)
			if parent == displayRoot {
				break
			}
			resolved, err := storage.CanonicalDirectoryPath(parent)
			if err != nil || !Within(grant.canonical, resolved) {
				break
			}
			displayRoot = parent
		}
		return selected, DownloadDirectory{Path: path, CanonicalPath: actualCanonical, Root: displayRoot}, nil
	}
	return nil, DownloadDirectory{}, storage.ErrDirectoryOutside
}

package config

import (
	"errors"
	"os"
	"path/filepath"
	"strings"

	"melora/internal/storage"
)

var (
	errDownloadNotAuthorized = errors.New("目录尚未获得授权，请在飞牛应用设置授权后重启乐屿")
	errDownloadRestricted    = errors.New("目录不在管理员限定范围内，请选择已授权的下载目录")
	errDownloadPrivate       = errors.New("该目录属于应用私有区域，请选择独立的音乐目录")
)

// reference 只由进程授权环境构造。alias 初次解析的物理对象被记录，运行期不能重定向扩权。
type downloadReference struct {
	path       string
	canonical  string
	identity   os.FileInfo
	allowAlias bool
	problem    error
}

type downloadGrant struct {
	source    downloadReference
	limit     *downloadReference
	canonical string
	identity  os.FileInfo
	problem   error
}

func captureDownloadReference(path string, allowAlias bool) downloadReference {
	ref := downloadReference{path: filepath.Clean(path), allowAlias: allowAlias}
	root, canonical, err := openDownloadReferencePath(ref.path, allowAlias)
	if err != nil {
		ref.problem = err
		return ref
	}
	defer root.Close()
	ref.canonical = canonical
	ref.identity, ref.problem = root.Stat(".")
	ref.problem = storage.DirectoryError(ref.problem)
	return ref
}

func openDownloadReferencePath(path string, alias bool) (*os.Root, string, error) {
	if alias {
		return storage.OpenAuthorizedDirectory(path)
	}
	root, err := storage.OpenDirectory(path)
	return root, filepath.Clean(path), err
}

func (ref downloadReference) open() (*os.Root, error) {
	var root *os.Root
	var canonical string
	var err error
	if ref.allowAlias && ref.canonical != "" {
		root, canonical, err = storage.ReopenAuthorizedDirectory(ref.path, ref.canonical)
	} else {
		root, canonical, err = openDownloadReferencePath(ref.path, ref.allowAlias)
	}
	if err != nil {
		return nil, err
	}
	actual, err := root.Stat(".")
	if err != nil {
		root.Close()
		return nil, storage.DirectoryError(err)
	}
	// 启动时无法建立物理授权对象的根，恢复后也需受控重启刷新，而非首次请求暗中扩权。
	if ref.problem != nil || canonical != ref.canonical || !os.SameFile(ref.identity, actual) {
		root.Close()
		return nil, storage.ErrDirectoryChanged
	}
	return root, nil
}

func (c Config) safeDownloadCanonical(root string) bool {
	if root == "" || root == string(filepath.Separator) || !filepath.IsAbs(root) {
		return false
	}
	for _, private := range []string{c.DataDir, c.WebDir} {
		if private != "" && overlaps(root, private) {
			return false
		}
	}
	return c.SocketPath == "" || !Within(root, c.SocketPath)
}

func (c Config) makeDownloadGrants() []downloadGrant {
	fnos := c.DownloadAuthorization == "fnos"
	paths := c.DownloadRoots
	if !fnos && len(paths) == 0 && c.DownloadRoot != "" {
		paths = []string{c.DownloadRoot}
	}
	var limit *downloadReference
	if fnos && c.DownloadRoot != "" {
		ref := captureDownloadReference(c.DownloadRoot, true)
		limit = &ref
	}
	out := []downloadGrant{}
	for _, path := range paths {
		if len(out) >= 64 {
			break
		}
		grant := downloadGrant{source: captureDownloadReference(path, fnos), limit: limit}
		if grant.source.problem != nil {
			grant.problem = grant.source.problem
		} else if limit != nil && limit.problem != nil {
			grant.problem = limit.problem
		} else {
			grant.canonical, grant.identity = grant.source.canonical, grant.source.identity
			if limit != nil {
				if Within(grant.canonical, limit.canonical) {
					grant.canonical, grant.identity = limit.canonical, limit.identity
				} else if !Within(limit.canonical, grant.canonical) {
					grant.problem = errDownloadRestricted
				}
			}
			if !c.safeDownloadCanonical(grant.canonical) {
				grant.problem = errDownloadPrivate
			}
		}
		out = append(out, grant)
	}
	return out
}

func (c Config) downloadGrantList() []downloadGrant {
	if c.downloadGrants != nil {
		return c.downloadGrants
	}
	// 兼容嵌入式部署/测试直接构造 Config；正式 FromEnv 在启动时已建立不可变快照。
	return c.makeDownloadGrants()
}

func (g downloadGrant) open() (*os.Root, error) {
	// 即便启动时失败，也保留具体的当前 ENOENT/EACCES，而不是统一报未授权。
	source, err := g.source.open()
	if err != nil {
		return nil, err
	}
	defer source.Close()
	if g.limit != nil {
		limited, err := g.limit.open()
		if err != nil {
			return nil, err
		}
		limited.Close()
	}
	if g.problem != nil {
		return nil, g.problem
	}
	rel, err := filepath.Rel(g.source.canonical, g.canonical)
	if err != nil || !Within(g.source.canonical, g.canonical) {
		return nil, errDownloadRestricted
	}
	root, err := storage.OpenRelativeDirectory(source, rel)
	if err != nil {
		return nil, err
	}
	actual, err := root.Stat(".")
	if err != nil {
		root.Close()
		return nil, storage.DirectoryError(err)
	}
	if !os.SameFile(g.identity, actual) {
		root.Close()
		return nil, storage.ErrDirectoryChanged
	}
	return root, nil
}

func (g downloadGrant) displayRoots() []string {
	if g.canonical == "" {
		return nil
	}
	out := []string{}
	refs := []downloadReference{g.source}
	if g.limit != nil {
		refs = append(refs, *g.limit)
	}
	for _, ref := range refs {
		if ref.canonical == "" || !Within(ref.canonical, g.canonical) {
			continue
		}
		rel, _ := filepath.Rel(ref.canonical, g.canonical)
		out = append(out, filepath.Join(ref.path, rel))
	}
	return append(out, g.canonical)
}

func (g downloadGrant) match(path string) (displayRoot, relative string, ok bool) {
	for _, root := range g.displayRoots() {
		if Within(root, path) {
			rel, _ := filepath.Rel(root, path)
			return root, rel, true
		}
	}
	// 未能解析的已授权根仍能向用户报告准确故障；不会因此进行文件操作。
	if g.problem != nil && Within(g.source.path, path) {
		return g.source.path, "", true
	}
	return "", "", false
}

func validDownloadInput(path string) bool {
	return path != "" && filepath.IsAbs(path) && len(path) <= 4096 && !strings.ContainsAny(path, "\x00\r\n")
}

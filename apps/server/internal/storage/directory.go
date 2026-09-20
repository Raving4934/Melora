package storage

import (
	"os"
	"path/filepath"
	"strings"
)

func validAbsoluteDirectory(path string) bool {
	return filepath.IsAbs(path) && len(path) <= 4096 && !strings.ContainsAny(path, "\x00\r\n")
}

// OpenDirectory 严格逐级固定目录FD。保留给下载 worker/私有目录使用，不隐式接受链接。
func OpenDirectory(path string) (*os.Root, error) {
	return openStrictDirectory(path)
}

// OpenRelativeDirectory 在固定根内严格逐级打开；检查与打开之间替换对象会被 SameFile 拒绝。
func OpenRelativeDirectory(base *os.Root, relative string) (*os.Root, error) {
	opened, err := openRelativeDirectory(base, "", relative, nil, false)
	return opened.root, err
}

// OpenAuthorizedDirectory 仅用于调用方已明确授权的根。将系统 alias 解析成物理目录，
// 再严格固定该物理对象；调用方仍须检查 canonical 授权交集与私有目录边界。
// 不得直接把任意 HTTP 路径交给此函数作为新授权。
func OpenAuthorizedDirectory(path string) (*os.Root, string, error) {
	return openAuthorizedDirectory(path, "")
}

// ReopenAuthorizedDirectory 在打开 FD 前核对初次授权的物理根，重定向到其他根不会被打开。
func ReopenAuthorizedDirectory(path, expectedCanonical string) (*os.Root, string, error) {
	return openAuthorizedDirectory(path, expectedCanonical)
}

// CanonicalDirectoryPath 只解析路径元数据，不打开目录内容、不授予访问权限。
// 对请求 alias 使用时，调用方必须先将返回值约束到既有授权根，随后从该根 FD 打开。
func CanonicalDirectoryPath(path string) (string, error) {
	if !validAbsoluteDirectory(path) {
		return "", directoryFailure(ErrDirectoryInvalid)
	}
	canonical, err := filepath.EvalSymlinks(filepath.Clean(path))
	if err != nil {
		return "", DirectoryError(err)
	}
	return filepath.Clean(canonical), nil
}

func openAuthorizedDirectory(path, expectedCanonical string) (*os.Root, string, error) {
	canonical, err := CanonicalDirectoryPath(path)
	if err != nil {
		return nil, "", err
	}
	if expectedCanonical != "" && canonical != expectedCanonical {
		return nil, "", directoryFailure(ErrDirectoryChanged)
	}
	root, err := OpenDirectory(canonical)
	if err != nil {
		return nil, "", err
	}
	actual, err := root.Stat(".")
	if err != nil {
		root.Close()
		return nil, "", DirectoryError(err)
	}
	// 重新核对逻辑路径对应的对象；返回 FD 始终是刚才固定的 canonical 对象。
	checked, err := filepath.EvalSymlinks(filepath.Clean(path))
	if err != nil {
		root.Close()
		return nil, "", DirectoryError(err)
	}
	if filepath.Clean(checked) != canonical {
		root.Close()
		return nil, "", directoryFailure(ErrDirectoryChanged)
	}
	expected, err := os.Stat(path)
	if err != nil {
		root.Close()
		return nil, "", DirectoryError(err)
	}
	if !os.SameFile(expected, actual) {
		root.Close()
		return nil, "", directoryFailure(ErrDirectoryChanged)
	}
	return root, canonical, nil
}

// OpenContainedDirectory 允许固定授权根内的链接。链接只通过 Root.Readlink 读取；
// 相对/绝对目标均先换算到该物理根内，越界目标不打开、不 Stat。
// aliases 是调用方已确认映射到同一物理根的展示路径，不产生任何新授权。
func OpenContainedDirectory(base *os.Root, canonicalRoot, relative string, aliases []string) (*os.Root, string, error) {
	if !validAbsoluteDirectory(canonicalRoot) {
		return nil, "", directoryFailure(ErrDirectoryInvalid)
	}
	opened, err := openRelativeDirectory(base, filepath.Clean(canonicalRoot), relative, aliases, true)
	if err != nil {
		return nil, "", err
	}
	// Name 不是物理路径；解析结果由下方的有界逐段解析产生，不能使用 EvalSymlinks 猜测。
	return opened.root, opened.canonical, nil
}

type openedDirectory struct {
	root      *os.Root
	canonical string
}

func withinDirectory(root, path string) bool {
	rel, err := filepath.Rel(filepath.Clean(root), filepath.Clean(path))
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func openRelativeDirectory(base *os.Root, canonicalRoot, relative string, aliases []string, allowLinks bool) (openedDirectory, error) {
	if base == nil || filepath.IsAbs(relative) || len(relative) > 4096 || strings.ContainsAny(relative, "\x00\r\n") {
		return openedDirectory{}, directoryFailure(ErrDirectoryInvalid)
	}
	relative = filepath.Clean(relative)
	if relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return openedDirectory{}, directoryFailure(ErrDirectoryOutside)
	}
	current, err := base.OpenRoot(".")
	if err != nil {
		return openedDirectory{}, DirectoryError(err)
	}
	fail := func(err error) (openedDirectory, error) {
		current.Close()
		return openedDirectory{}, err
	}
	pending := strings.Split(relative, string(filepath.Separator))
	resolved := []string{}
	links, steps := 0, 0
	for len(pending) > 0 {
		steps++
		if steps > 1024 {
			return fail(directoryFailure(ErrDirectoryLoop))
		}
		component := pending[0]
		pending = pending[1:]
		if component == "" || component == "." {
			continue
		}
		info, err := current.Lstat(component)
		if err != nil {
			return fail(DirectoryError(err))
		}
		if info.Mode()&os.ModeSymlink != 0 {
			if !allowLinks {
				return fail(directoryFailure(ErrDirectoryLink))
			}
			links++
			if links > 40 {
				return fail(directoryFailure(ErrDirectoryLoop))
			}
			target, err := current.Readlink(component)
			if err != nil {
				return fail(DirectoryError(err))
			}
			if len(target) > 4096 || strings.ContainsAny(target, "\x00\r\n") {
				return fail(directoryFailure(ErrDirectoryInvalid))
			}
			var nextPath string
			if filepath.IsAbs(target) {
				accepted := false
				for _, display := range append([]string{canonicalRoot}, aliases...) {
					if display != "" && withinDirectory(display, target) {
						nextPath, _ = filepath.Rel(display, filepath.Clean(target))
						accepted = true
						break
					}
				}
				if !accepted {
					return fail(directoryFailure(ErrDirectoryOutside))
				}
			} else {
				nextPath = filepath.Join(filepath.Join(resolved...), target)
			}
			if nextPath == ".." || strings.HasPrefix(nextPath, ".."+string(filepath.Separator)) {
				return fail(directoryFailure(ErrDirectoryOutside))
			}
			nextPath = filepath.Join(nextPath, filepath.Join(pending...))
			if len(nextPath) > 4096 {
				return fail(directoryFailure(ErrDirectoryInvalid))
			}
			// 重启解析仍从同一个固定的 base FD 开始，不从链接的绝对文本重新打开。
			current.Close()
			current, err = base.OpenRoot(".")
			if err != nil {
				return openedDirectory{}, DirectoryError(err)
			}
			resolved = nil
			pending = strings.Split(nextPath, string(filepath.Separator))
			continue
		}
		if !info.IsDir() {
			return fail(directoryFailure(ErrDirectoryNotDir))
		}
		next, err := current.OpenRoot(component)
		if err != nil {
			return fail(DirectoryError(err))
		}
		actual, err := next.Stat(".")
		if err != nil {
			next.Close()
			return fail(DirectoryError(err))
		}
		if !os.SameFile(info, actual) {
			next.Close()
			return fail(directoryFailure(ErrDirectoryChanged))
		}
		current.Close()
		current = next
		resolved = append(resolved, component)
	}
	canonical := ""
	if canonicalRoot != "" {
		canonical = filepath.Join(canonicalRoot, filepath.Join(resolved...))
	}
	return openedDirectory{root: current, canonical: canonical}, nil
}

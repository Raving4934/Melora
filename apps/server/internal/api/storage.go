package api

import (
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"melora/internal/storage"
)

// storageStatus 只探测已授权目录，GET 不创建探针文件，也不接受查询路径覆盖。
func (s *Server) storageStatus(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	s.settingsMu.Lock()
	defer s.settingsMu.Unlock()
	current, err := s.store.Settings(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	roots := s.cfg.AuthorizedDownloadRoots()
	source := s.cfg.DownloadAuthorization
	if source == "" {
		source = "none"
		if len(roots) > 0 {
			source = "environment"
		}
	}
	result := map[string]any{"authorized": len(roots) > 0, "configured": current.DownloadRoot != "", "authorizedRoots": roots, "authorizationSource": source}
	if len(roots) == 0 {
		if cause := s.cfg.DownloadAuthorizationError(); cause != nil {
			_, code := storageFailure(cause)
			result["error"], result["errorCode"] = cause.Error(), code
		}
		writeJSON(w, 200, result)
		return
	}
	result["authorizedRoot"] = roots[0] // 兼容旧客户端；新客户端按多根目录选择。
	path := current.DownloadRoot
	if path == "" {
		path = roots[0]
	}
	opened, directory, openErr := s.cfg.OpenDownloadDirectoryInfo(path)
	if openErr != nil {
		_, code := storageFailure(openErr)
		result["error"], result["errorCode"] = openErr.Error(), code
		writeJSON(w, 200, result)
		return
	}
	defer opened.Close()
	capacity, err := storage.ProbeRoot(opened)
	if err != nil {
		result["error"] = "暂时无法读取磁盘空间，请检查挂载状态与目录权限"
	} else {
		result["path"] = directory.Path
		result["canonicalPath"] = directory.CanonicalPath
		result["displayPath"] = s.cfg.DisplayDownloadPath(directory.CanonicalPath)
		result["capacity"] = capacity
	}
	writeJSON(w, 200, result)
}

type storageDirectory struct {
	Name          string `json:"name"`
	Path          string `json:"path"`
	CanonicalPath string `json:"canonicalPath,omitempty"`
}
type directoryListing struct {
	CanonicalPath string             `json:"canonicalPath,omitempty"`
	Path          string             `json:"path"`
	Parent        string             `json:"parent"`
	Root          string             `json:"root"`
	Roots         []string           `json:"roots"`
	Directories   []storageDirectory `json:"directories"`
	Truncated     bool               `json:"truncated"`
}

// storageDirectories 只返回通过相同授权解析与可写权限预检的目录；不返回文件，不写探针。
func (s *Server) storageDirectories(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	roots := s.cfg.AuthorizedDownloadRoots()
	if len(roots) == 0 {
		cause := s.cfg.DownloadAuthorizationError()
		status, code := storageFailure(cause)
		fail(w, status, code, cause.Error())
		return
	}
	result := directoryListing{Roots: roots, Directories: []storageDirectory{}}
	selected := r.URL.Query().Get("path")
	if selected == "" {
		result.Roots = []string{}
		var first error
		for _, path := range roots {
			if r.Context().Err() != nil {
				return
			}
			root, directory, err := s.cfg.OpenDownloadDirectoryInfo(path)
			if err == nil {
				err = selectableStorageDirectory(root)
				root.Close()
			}
			if err != nil {
				if first == nil {
					first = err
				}
				continue
			}
			result.Roots = append(result.Roots, directory.Path)
			result.Directories = append(result.Directories, storageDirectory{Name: filepath.Base(path), Path: directory.Path, CanonicalPath: directory.CanonicalPath})
		}
		if len(result.Directories) == 0 && first != nil {
			status, code := storageFailure(first)
			fail(w, status, code, first.Error())
			return
		}
		writeJSON(w, 200, result)
		return
	}
	scoped, directory, err := s.cfg.OpenDownloadDirectoryInfo(selected)
	if err != nil {
		status, code := storageFailure(err)
		fail(w, status, code, err.Error())
		return
	}
	defer scoped.Close()
	selected = directory.Path
	dir, err := scoped.Open(".")
	if err != nil {
		cause := storage.DirectoryError(err)
		status, code := storageFailure(cause)
		fail(w, status, code, cause.Error())
		return
	}
	defer dir.Close()
	result.Path = selected
	result.Root = directory.Root
	result.CanonicalPath = directory.CanonicalPath
	if selected != directory.Root {
		result.Parent = filepath.Dir(selected)
	}
	// 最多检查4096个目录项、返回256个目录，避免扫描大型共享目录耗尽内存/阻塞请求。
	examined := 0
	for examined < 4096 {
		if r.Context().Err() != nil {
			return
		}
		entries, readErr := dir.ReadDir(min(256, 4096-examined))
		examined += len(entries)
		if readErr != nil && readErr != io.EOF {
			cause := storage.DirectoryError(readErr)
			status, code := storageFailure(cause)
			fail(w, status, code, cause.Error())
			return
		}
		for _, entry := range entries {
			name := entry.Name()
			if (!entry.IsDir() && entry.Type()&os.ModeSymlink == 0) || strings.HasPrefix(name, ".") || strings.ContainsAny(name, "\x00\r\n") {
				continue
			}
			if r.Context().Err() != nil {
				return
			}
			candidate, child, openErr := s.cfg.OpenDownloadDirectoryInfo(filepath.Join(selected, name))
			if openErr != nil {
				continue
			}
			selectErr := selectableStorageDirectory(candidate)
			candidate.Close()
			if selectErr != nil {
				continue
			}
			if len(result.Directories) == 256 {
				result.Truncated = true
				break
			}
			result.Directories = append(result.Directories, storageDirectory{Name: name, Path: child.Path, CanonicalPath: child.CanonicalPath})
		}
		if result.Truncated || readErr == io.EOF || len(entries) == 0 {
			break
		}
		if examined == 4096 {
			result.Truncated = true
		}
	}
	sort.Slice(result.Directories, func(i, j int) bool { return result.Directories[i].Name < result.Directories[j].Name })
	writeJSON(w, 200, result)
}

// GET 只做内核权限预检和已有 Singles 安全检查；最终选择必须再经过 POST 写探针。
func selectableStorageDirectory(root *os.Root) error {
	if err := storage.CheckWritableDirectory(root); err != nil {
		return err
	}
	info, err := root.Lstat("Singles")
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return storage.DirectoryError(err)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return storage.ErrDirectoryLink
	}
	if !info.IsDir() {
		return storage.ErrDirectoryNotDir
	}
	singles, err := storage.OpenRelativeDirectory(root, "Singles")
	if err != nil {
		return err
	}
	defer singles.Close()
	return storage.CheckWritableDirectory(singles)
}

func storageFailure(err error) (int, string) {
	switch {
	case errors.Is(err, storage.ErrDirectoryMissing):
		return 404, "directory_missing"
	case errors.Is(err, storage.ErrDirectoryPermission), errors.Is(err, storage.ErrDirectoryNotWritable):
		return 403, "directory_permission"
	case errors.Is(err, storage.ErrDirectoryReadOnly):
		return 403, "directory_readonly"
	case errors.Is(err, storage.ErrDirectoryChanged):
		return 409, "directory_changed"
	case errors.Is(err, storage.ErrDirectoryOutside):
		return 400, "directory_outside_authorization"
	case errors.Is(err, storage.ErrDirectoryLink), errors.Is(err, storage.ErrDirectoryLoop):
		return 400, "directory_link_invalid"
	case errors.Is(err, storage.ErrDirectoryNotDir), errors.Is(err, storage.ErrDirectoryInvalid):
		return 400, "invalid_download_root"
	default:
		return 403, "downloads_disabled"
	}
}

package api

import (
	"bytes"
	"io"
	"mime"
	"net/http"
	"path"
	"strconv"
	"strings"
	"time"
)

func spaRoute(p string) bool {
	switch p {
	case "/", "/audiobooks", "/discover", "/charts", "/playlists", "/library", "/search", "/downloads", "/settings", "/player", "/now-playing":
		return true
	}
	parts := strings.Split(strings.Trim(p, "/"), "/")
	if len(parts) == 3 && parts[2] != "" && !strings.Contains(parts[2], ".") {
		return (parts[0] == "library" && parts[1] == "playlists") ||
			(parts[0] == "audiobooks" && (parts[1] == "ranks" || parts[1] == "albums"))
	}
	return len(parts) == 2 && (parts[0] == "playlists" || parts[0] == "playlist" || parts[0] == "charts" || parts[0] == "discover") && parts[1] != "" && !strings.Contains(parts[1], ".")
}

func acceptsEncoding(header, encoding string) bool {
	for _, item := range strings.Split(header, ",") {
		parts := strings.Split(item, ";")
		name := strings.TrimSpace(strings.ToLower(parts[0]))
		if name != encoding && name != "*" {
			continue
		}
		quality := 1.0
		for _, parameter := range parts[1:] {
			key, value, ok := strings.Cut(strings.TrimSpace(parameter), "=")
			if ok && strings.EqualFold(key, "q") {
				parsed, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
				if err != nil {
					quality = 0
				} else {
					quality = parsed
				}
			}
		}
		return quality > 0
	}
	return false
}

func appendVary(header http.Header, value string) {
	for _, current := range header.Values("Vary") {
		for _, item := range strings.Split(current, ",") {
			if strings.EqualFold(strings.TrimSpace(item), value) {
				return
			}
		}
	}
	header.Add("Vary", value)
}

func (s *Server) static(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/api") || r.URL.Path == "/health" {
		missing(w)
		return
	}
	if r.Method != "GET" && r.Method != "HEAD" {
		w.Header().Set("Allow", "GET, HEAD")
		fail(w, 405, "method_not_allowed", "静态资源仅支持 GET 或 HEAD")
		return
	}
	if s.webRoot == nil {
		missing(w)
		return
	}
	for _, part := range strings.Split(r.URL.Path, "/") {
		if strings.HasPrefix(part, ".") || strings.Contains(part, "\\") {
			missing(w)
			return
		}
	}
	name := strings.TrimPrefix(r.URL.Path, "/")
	isSPA := spaRoute(r.URL.Path)
	if isSPA {
		name = "index.html"
	} else {
		switch strings.ToLower(path.Ext(name)) {
		case ".js", ".css", ".svg", ".png", ".jpg", ".jpeg", ".webp", ".avif", ".ico", ".woff", ".woff2", ".ttf", ".webmanifest":
		case ".html":
			if name != "index.html" {
				missing(w)
				return
			}
		default:
			missing(w)
			return
		}
	}
	file, err := s.webRoot.Open(name)
	if err != nil {
		missing(w)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		missing(w)
		return
	}
	// SPA 路由只允许明确列出的客户端页面；缺失 JS/图片/API 永不回退 index。
	w.Header().Set("Content-Type", mime.TypeByExtension(path.Ext(name)))
	if name == "index.html" {
		w.Header().Set("Cache-Control", "no-store")
	} else if strings.HasPrefix(name, "assets/") {
		// Vite 的 assets 文件名含内容哈希；允许永久缓存，升级后 URL 会自然变化。
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	} else {
		w.Header().Set("Cache-Control", "public, max-age=86400")
	}
	ancestors := "'none'"
	if s.gatewayMode() {
		ancestors = "'self'"
	}
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; media-src https: http: blob:; object-src 'none'; base-uri 'self'; frame-ancestors "+ancestors+"; form-action 'self'")
	if name == "index.html" {
		data, err := io.ReadAll(io.LimitReader(file, 2<<20))
		if err != nil {
			internalError(w)
			return
		}
		// ServeHTTP 已去掉网关前缀；只按实际 SPA 路径提供首帧画布提示，
		// 不读取查询参数/请求头，不扩大路由白名单或注入可执行脚本。
		if r.URL.Path == "/now-playing" {
			data = bytes.Replace(data, []byte(`data-melora-surface="page"`), []byte(`data-melora-surface="immersive"`), 1)
			data = bytes.Replace(data, []byte(`<meta name="theme-color" content="#ffffff" />`), []byte(`<meta name="theme-color" content="#171a1e" />`), 1)
		}
		base := s.cfg.BasePath + "/"
		for _, marker := range []string{`<base href="/" data-melora-base>`, `<base href="/" data-melora-base />`} {
			if bytes.Contains(data, []byte(marker)) {
				replacement := strings.Replace(marker, `href="/"`, `href="`+base+`"`, 1)
				data = bytes.Replace(data, []byte(marker), []byte(replacement), 1)
				break
			}
		}
		w.Header().Set("Content-Length", strconv.Itoa(len(data)))
		// FPK 可固定 mtime，不能据旧时间戳返回 304 导致升级后引用已移除的资源。
		http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(data))
		return
	}

	// Range 请求必须基于原文件字节；普通文本资产优先使用构建期 Brotli/gzip 版本。
	appendVary(w.Header(), "Accept-Encoding")
	if r.Header.Get("Range") == "" {
		for _, encoding := range []string{"br", "gzip"} {
			if !acceptsEncoding(r.Header.Get("Accept-Encoding"), encoding) {
				continue
			}
			suffix := map[string]string{"br": ".br", "gzip": ".gz"}[encoding]
			compressed, openErr := s.webRoot.Open(name + suffix)
			if openErr != nil {
				continue
			}
			compressedInfo, statErr := compressed.Stat()
			if statErr != nil || !compressedInfo.Mode().IsRegular() {
				_ = compressed.Close()
				continue
			}
			defer compressed.Close()
			w.Header().Set("Content-Encoding", encoding)
			http.ServeContent(w, r, name, compressedInfo.ModTime(), compressed)
			return
		}
	}
	http.ServeContent(w, r, name, info.ModTime(), file)
}

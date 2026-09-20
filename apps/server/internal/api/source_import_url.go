package api

import (
	"bytes"
	"errors"
	"mime"
	"net/http"
	"net/url"
	"path"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"melora/internal/netguard"
)

func (s *Server) sourceImportURL(w http.ResponseWriter, r *http.Request) {
	if !s.sourceAvailable(w) {
		return
	}
	var body struct {
		URL string `json:"url"`
	}
	if !decode(w, r, &body) {
		return
	}
	raw := strings.TrimSpace(body.URL)
	if raw == "" || len(raw) > 8192 {
		fail(w, http.StatusBadRequest, "invalid_source_url", "请输入不含凭据或片段的公网 HTTP/HTTPS 音源链接")
		return
	}
	fetch := s.fetchSourceURL
	if fetch == nil {
		fetch = netguard.FetchPublic
	}
	result, err := fetch(r.Context(), raw)
	if err != nil {
		s.sourceURLFetchError(w, err)
		return
	}
	if result.StatusCode < http.StatusOK || result.StatusCode >= http.StatusMultipleChoices {
		fail(w, http.StatusUnprocessableEntity, "source_url_fetch_failed", "无法读取在线音源，请检查链接是否可访问")
		return
	}
	if err := validateOnlineSourceBody(result); err != nil {
		fail(w, http.StatusBadRequest, "invalid_source_url_content", err.Error())
		return
	}
	filename := onlineSourceFilename(result)
	source, created, err := s.sources.Import(r.Context(), filename, result.Body, nil)
	if err != nil {
		s.sourceError(w, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, source)
}

func (s *Server) sourceURLFetchError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, netguard.ErrLimit):
		fail(w, http.StatusRequestEntityTooLarge, "source_too_large", "在线音源最大 512 KiB")
	case errors.Is(err, netguard.ErrPolicy):
		fail(w, http.StatusBadRequest, "invalid_source_url", "仅支持不含凭据或片段的公网 HTTP/HTTPS 音源链接")
	default:
		fail(w, http.StatusUnprocessableEntity, "source_url_fetch_failed", "无法读取在线音源，请检查链接是否可访问")
	}
}

func validateOnlineSourceBody(result netguard.PublicFetchResult) error {
	body := bytes.TrimPrefix(result.Body, []byte{0xef, 0xbb, 0xbf})
	if len(body) == 0 {
		return errors.New("在线音源内容为空")
	}
	if !utf8.Valid(body) || bytes.IndexByte(body, 0) >= 0 {
		return errors.New("在线音源不是有效的 UTF-8 JavaScript 文件")
	}
	kind, _, _ := mime.ParseMediaType(result.Headers["content-type"])
	if kind == "text/html" || kind == "application/xhtml+xml" || looksLikeHTML(body) {
		return errors.New("链接返回的是网页而不是 JavaScript 文件，请使用 Raw 原始文件链接")
	}
	return nil
}

func looksLikeHTML(body []byte) bool {
	trimmed := bytes.ToLower(bytes.TrimSpace(body))
	for _, prefix := range [][]byte{[]byte("<!doctype html"), []byte("<html"), []byte("<head"), []byte("<body")} {
		if bytes.HasPrefix(trimmed, prefix) {
			return true
		}
	}
	return false
}

func onlineSourceFilename(result netguard.PublicFetchResult) string {
	if name := contentDispositionFilename(result.Headers["content-disposition"]); name != "" {
		if cleaned := cleanOnlineFilename(name); cleaned != "" {
			return cleaned
		}
	}
	if parsed, err := url.Parse(result.FinalURL); err == nil {
		if name := cleanOnlineFilename(path.Base(parsed.Path)); name != "" {
			return name
		}
	}
	return "online-source.js"
}

func contentDispositionFilename(header string) string {
	if header == "" {
		return ""
	}
	_, params, err := mime.ParseMediaType(header)
	if err != nil {
		return ""
	}
	if value := params["filename*"]; value != "" {
		if separator := strings.Index(value, "''"); separator >= 0 {
			value = value[separator+2:]
		}
		if decoded, err := url.PathUnescape(value); err == nil {
			value = decoded
		}
		return value
	}
	return params["filename"]
}

func cleanOnlineFilename(name string) string {
	name = strings.TrimSpace(strings.ReplaceAll(name, "\\", "/"))
	name = path.Base(name)
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return '_'
		}
		return r
	}, name)
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\r\n\x00") {
		return ""
	}
	if !strings.EqualFold(filepath.Ext(name), ".js") {
		name += ".js"
	}
	runes := []rune(name)
	if len(runes) > 160 {
		name = string(runes[:157]) + ".js"
	}
	return name
}

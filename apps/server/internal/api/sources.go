package api

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"melora/internal/lxsource"
)

func (s *Server) registerSourceRoutes() {
	s.mux.HandleFunc("/api/v1/sources", method(s.sourceList, "GET"))
	s.mux.HandleFunc("/api/v1/sources/import", method(s.sourceImport, "POST"))
	s.mux.HandleFunc("/api/v1/sources/active", method(s.sourceActive, "PUT"))
	s.mux.HandleFunc("/api/v1/sources/{id}", method(s.sourceByID, "PATCH", "DELETE"))
	s.mux.HandleFunc("/api/v1/sources/{id}/check", method(s.sourceCheck, "POST"))
	s.mux.HandleFunc("/api/v1/sources/{id}/export", method(s.sourceExport, "GET"))
}
func (s *Server) sourceAvailable(w http.ResponseWriter) bool {
	if s.sources == nil {
		fail(w, 503, "lx_unavailable", "LX音源模块未启用")
		return false
	}
	return true
}
func (s *Server) sourceError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, lxsource.ErrMissing):
		missing(w)
	case errors.Is(err, lxsource.ErrInvalidScript), errors.Is(err, lxsource.ErrHosts):
		fail(w, 400, "invalid_source", err.Error())
	case errors.Is(err, lxsource.ErrLimit):
		fail(w, 409, "source_limit", err.Error())
	case errors.Is(err, lxsource.ErrStorage):
		fail(w, 500, "source_storage_failed", err.Error())
	default:
		fail(w, 409, "source_not_ready", "音源尚未就绪或正在检查，请刷新状态后重试")
	}
}
func (s *Server) sourceList(w http.ResponseWriter, r *http.Request) {
	if s.sources == nil {
		writeJSON(w, 200, map[string]any{"items": []any{}, "activeSourceId": "", "available": false, "catalogs": []string{}})
		return
	}
	state := s.sources.List()
	catalogs := []string{}
	if s.live != nil {
		catalogs = s.live.Catalog.IDs()
	}
	writeJSON(w, 200, map[string]any{"items": state.Items, "activeSourceId": state.ActiveID, "available": true, "catalogs": catalogs, "maxScriptBytes": lxsource.MaxScriptBytes, "demoMode": s.live == nil})
}
func (s *Server) sourceImport(w http.ResponseWriter, r *http.Request) {
	if !s.sourceAvailable(w) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, lxsource.MaxScriptBytes+(32<<10))
	reader, err := r.MultipartReader()
	if err != nil {
		fail(w, 400, "invalid_source_upload", "请以multipart/form-data上传.js文件")
		return
	}
	var code []byte
	filename := ""
	hosts := []string{}
	fields := 0
	for {
		part, err := reader.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			fail(w, 400, "invalid_source_upload", "上传内容无效或超过大小限制")
			return
		}
		fields++
		if fields > 2 {
			part.Close()
			fail(w, 400, "invalid_source_upload", "上传字段过多")
			return
		}
		switch part.FormName() {
		case "file":
			if filename != "" {
				part.Close()
				fail(w, 400, "invalid_source_upload", "只允许一个音源文件")
				return
			}
			filename = part.FileName()
			code, err = io.ReadAll(io.LimitReader(part, lxsource.MaxScriptBytes+1))
			if err != nil || len(code) > lxsource.MaxScriptBytes {
				part.Close()
				fail(w, 413, "source_too_large", "音源文件最大512 KiB")
				return
			}
		case "allowHTTPHosts":
			data, e := io.ReadAll(io.LimitReader(part, 4097))
			if e != nil || len(data) > 4096 || json.Unmarshal(data, &hosts) != nil {
				part.Close()
				fail(w, 400, "invalid_source_hosts", "HTTP白名单格式无效")
				return
			}
		default:
			part.Close()
			fail(w, 400, "invalid_source_upload", "不支持的上传字段")
			return
		}
		part.Close()
	}
	source, created, err := s.sources.Import(r.Context(), filename, code, hosts)
	if err != nil {
		s.sourceError(w, err)
		return
	}
	status := 200
	if created {
		status = 201
	}
	writeJSON(w, status, source)
}
func (s *Server) sourceActive(w http.ResponseWriter, r *http.Request) {
	if !s.sourceAvailable(w) {
		return
	}
	var body struct {
		ID string `json:"id"`
	}
	if !decode(w, r, &body) {
		return
	}
	if err := s.sources.Select(body.ID); err != nil {
		s.sourceError(w, err)
		return
	}
	writeJSON(w, 200, s.sources.List())
}
func (s *Server) sourceCheck(w http.ResponseWriter, r *http.Request) {
	if !s.sourceAvailable(w) {
		return
	}
	source, err := s.sources.Check(r.Context(), r.PathValue("id"))
	if err != nil {
		s.sourceError(w, err)
		return
	}
	writeJSON(w, 200, source)
}
func (s *Server) sourceByID(w http.ResponseWriter, r *http.Request) {
	if !s.sourceAvailable(w) {
		return
	}
	if r.Method == "DELETE" {
		warning, err := s.sources.Delete(r.PathValue("id"))
		if err != nil {
			s.sourceError(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "warning": warning})
		return
	}
	var body struct {
		AllowHTTPHosts []string `json:"allowHTTPHosts"`
	}
	if !decode(w, r, &body) {
		return
	}
	source, err := s.sources.Configure(r.PathValue("id"), body.AllowHTTPHosts)
	if err != nil {
		s.sourceError(w, err)
		return
	}
	writeJSON(w, 200, source)
}

// 仅此已鉴权、由用户主动触发的接口返回原脚本；列表和日志不含代码。
func (s *Server) sourceExport(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if !s.sourceAvailable(w) {
		return
	}
	filename, code, err := s.sources.Export(r.PathValue("id"))
	if err != nil {
		s.sourceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"filename": filename, "content": code})
}

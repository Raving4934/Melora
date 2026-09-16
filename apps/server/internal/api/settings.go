package api

import (
	"encoding/json"
	"errors"
	"net/http"

	"melora/internal/config"
	"melora/internal/download"
	"melora/internal/model"
	"melora/internal/storage"
)

func (s *Server) settings(w http.ResponseWriter, r *http.Request) {
	s.settingsMu.Lock()
	defer s.settingsMu.Unlock()
	current, err := s.store.Settings(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	if r.Method == "GET" {
		writeJSON(w, 200, current)
		return
	}
	next := current
	var patch map[string]json.RawMessage
	if !decode(w, r, &patch) {
		return
	}
	if len(patch) == 0 {
		fail(w, 400, "invalid_settings", "请提供要修改的设置")
		return
	}
	allowed := map[string]bool{"downloadRoot": true, "concurrency": true, "writeLyrics": true, "writeCover": true, "embedTags": true, "fileNameFormat": true, "defaultQuality": true, "autoSwitchSource": true, "writeMetadata": true, "showDirect": true}
	downloadOnly := map[string]bool{"downloadRoot": true, "concurrency": true, "writeLyrics": true, "writeCover": true, "embedTags": true, "fileNameFormat": true, "writeMetadata": true, "showDirect": true}
	for key, value := range patch {
		if !allowed[key] || string(value) == "null" {
			fail(w, 400, "invalid_settings", "设置项或值无效")
			return
		}
		if s.cfg.DeployMode == config.DeployModeCloud && downloadOnly[key] {
			fail(w, 404, "downloads_disabled", "当前部署不提供下载功能")
			return
		}
	}
	raw, _ := json.Marshal(patch)
	if json.Unmarshal(raw, &next) != nil {
		fail(w, 400, "invalid_settings", "设置值类型无效")
		return
	}
	// 旧客户端仍可发送已退役字段，但新任务不再生成JSON或显示网络路径提示。
	next.WriteMetadata = false
	next.ShowDirect = false
	if next.FileNameFormat == "" {
		next.FileNameFormat = "title-artist"
	}
	if !validateSettings(w, next) {
		return
	}
	if _, supplied := patch["downloadRoot"]; supplied && next.DownloadRoot != "" {
		next.DownloadRoot, err = s.cfg.ValidateDownloadPath(next.DownloadRoot)
		if err != nil {
			fail(w, 400, "invalid_download_root", err.Error())
			return
		}
	}
	changed := next.DownloadRoot != current.DownloadRoot || next.Concurrency != current.Concurrency
	if changed && s.downloads != nil {
		if err = s.downloads.Configure(next.DownloadRoot, next.Concurrency); err != nil {
			if errors.Is(err, download.ErrStorageUnavailable) {
				fail(w, 400, "invalid_download_storage", "保存目录的 Singles 子目录不可写或不是安全目录，请选择其它目录")
				return
			}
			fail(w, 409, "downloads_active", "无法重新配置下载器，请暂停或结束任务并确认目录可写")
			return
		}
	}
	if s.downloads != nil {
		if err = s.downloads.SetOptions(next); err != nil {
			if changed {
				_ = s.downloads.Configure(current.DownloadRoot, current.Concurrency)
			}
			fail(w, 503, "downloads_unavailable", "下载管理器已关闭，请重启应用后重试")
			return
		}
	}
	if err = s.store.SaveSettings(r.Context(), next); err != nil {
		if s.downloads != nil {
			_ = s.downloads.SetOptions(current)
		}
		if changed && s.downloads != nil {
			if rollbackErr := s.downloads.Configure(current.DownloadRoot, current.Concurrency); rollbackErr != nil {
				s.logger.Error("download configuration rollback failed", "error", rollbackErr)
			}
		}
		s.dbError(w, err)
		return
	}
	if s.downloads != nil {
		s.downloads.SetWriteMetadata(false)
	}
	if s.live != nil {
		s.live.SetAutoSwitch(next.AutoSwitchSource)
	}
	writeJSON(w, 200, next)
}
func validateSettings(w http.ResponseWriter, settings model.Settings) bool {
	if settings.Concurrency < 1 || settings.Concurrency > 3 {
		fail(w, 400, "invalid_concurrency", "下载并发数必须为 1–3")
		return false
	}
	switch settings.DefaultQuality {
	case "standard", "128k", "320k", "flac", "flac24bit", "ape", "wav":
	default:
		fail(w, 400, "unsupported_quality", "请选择有效的默认音质")
		return false
	}
	switch settings.FileNameFormat {
	case "", "title-artist", "artist-title", "title":
	default:
		fail(w, 400, "invalid_file_name_format", "请选择歌曲名-艺术家、艺术家-歌曲名或歌曲名")
		return false
	}
	return true
}
func (s *Server) validateStorage(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	var body struct {
		Path string `json:"path"`
	}
	if !decode(w, r, &body) {
		return
	}
	root, path, err := s.cfg.OpenWritableDownloadDirectory(body.Path)
	if err != nil {
		status := 400
		code := "invalid_download_root"
		if !s.cfg.HasDownloadAuthorization() {
			status = 403
			code = "downloads_disabled"
		}
		fail(w, status, code, err.Error())
		return
	}
	defer root.Close()
	if err = download.ValidateExistingDestination(root); err != nil {
		fail(w, 400, "invalid_download_storage", "保存目录的 Singles 子目录不可写或不是安全目录，请选择其它目录")
		return
	}
	result := map[string]any{"valid": true, "path": path}
	if capacity, probeErr := storage.ProbeRoot(root); probeErr == nil {
		result["capacity"] = capacity
	} else {
		result["warning"] = "读写验证通过，但暂时无法读取可用空间"
	}
	writeJSON(w, 200, result)
}

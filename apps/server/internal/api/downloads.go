package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"melora/internal/config"
	"melora/internal/model"
)

// cloud 部署仅提供在线播放：不落盘、不排队，任务 API 直接拒绝。
func (s *Server) serverDownloadsDisabled(w http.ResponseWriter) bool {
	if s.cfg.DeployMode != config.DeployModeCloud {
		return false
	}
	fail(w, 404, "downloads_disabled", "当前部署不提供下载功能")
	return true
}

func (s *Server) downloadListCreate(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	if s.downloads == nil {
		fail(w, 503, "downloads_unavailable", "下载管理器尚未初始化")
		return
	}
	if r.Method == "GET" {
		jobs := s.downloads.List()
		if jobs == nil {
			jobs = []model.DownloadJob{}
		}
		state := r.URL.Query().Get("state")
		if state != "" {
			allowed := map[string]bool{"retry_wait": true, "queued": true, "resolving": true, "downloading": true, "paused": true, "waiting_for_url_refresh": true, "verifying": true, "finalizing": true, "completed": true, "failed": true, "cancelled": true}
			if !allowed[state] {
				fail(w, 400, "invalid_download_state", "未知下载状态")
				return
			}
			filtered := []model.DownloadJob{}
			for _, job := range jobs {
				if job.State == state {
					filtered = append(filtered, job)
				}
			}
			jobs = filtered
		}
		writeJSON(w, 200, jobs)
		return
	}
	var body struct {
		TrackID     string `json:"trackId"`
		Quality     string `json:"quality"`
		WriteLyrics *bool  `json:"writeLyrics"`
		WriteCover  *bool  `json:"writeCover"`
		EmbedTags   *bool  `json:"embedTags"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.TrackID == "" {
		fail(w, 400, "invalid_track", "trackId 不能为空")
		return
	}
	track, exists := s.knownTrack(w, body.TrackID, r.Context())
	if !exists || !s.activeProvider(w, r) {
		return
	}
	s.settingsMu.Lock()
	defer s.settingsMu.Unlock()
	settings, err := s.store.Settings(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	if body.Quality == "" {
		body.Quality = settings.DefaultQuality
	}
	if s.live != nil {
		if body.Quality, err = s.live.ValidateQuality(track, body.Quality); err != nil {
			s.liveError(w, err)
			return
		}
	} else if _, err = s.demo.PlayInfo(track.ID, body.Quality); err != nil {
		fail(w, 400, "unsupported_quality", "当前演示音源仅支持standard原始音质")
		return
	}
	if !track.CanDownload {
		fail(w, 403, "download_not_allowed", "音源未授权下载此曲目")
		return
	}
	if !s.cfg.HasDownloadAuthorization() || settings.DownloadRoot == "" {
		fail(w, 403, "downloads_disabled", "下载未启用，请由管理员授权并在设置中选择下载目录")
		return
	}
	if _, err = s.cfg.ValidateDownloadPath(settings.DownloadRoot); err != nil {
		fail(w, 400, "invalid_download_root", err.Error())
		return
	}
	options := settings
	if body.WriteLyrics != nil {
		options.WriteLyrics = *body.WriteLyrics
	}
	if body.WriteCover != nil {
		options.WriteCover = *body.WriteCover
	}
	if body.EmbedTags != nil {
		options.EmbedTags = *body.EmbedTags
	}
	options.WriteMetadata = false
	job, err := s.downloads.CreateWithOptions(track, body.Quality, options)
	if err != nil {
		s.logger.Warn("download create rejected", "error", err)
		fail(w, 409, "download_rejected", "无法创建下载任务，请检查下载队列、目录权限及存储状态")
		return
	}
	writeJSON(w, 201, job)
}
func (s *Server) downloadAction(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	if s.downloads == nil {
		fail(w, 503, "downloads_unavailable", "下载管理器尚未初始化")
		return
	}
	action := r.PathValue("action")
	if action != "pause" && action != "resume" && action != "retry" && action != "cancel" {
		missing(w)
		return
	}
	s.settingsMu.Lock()
	defer s.settingsMu.Unlock()
	exists := false
	for _, job := range s.downloads.List() {
		if job.ID == r.PathValue("id") {
			exists = true
			break
		}
	}
	if !exists {
		missing(w)
		return
	}
	if action == "resume" || action == "retry" {
		if !s.activeProvider(w, r) {
			return
		}
		settings, err := s.store.Settings(r.Context())
		if err != nil {
			s.dbError(w, err)
			return
		}
		if !s.cfg.HasDownloadAuthorization() || settings.DownloadRoot == "" {
			fail(w, 403, "downloads_disabled", "下载目录尚未授权或已停用")
			return
		}
		if _, err = s.cfg.ValidateDownloadPath(settings.DownloadRoot); err != nil {
			fail(w, 400, "invalid_download_root", err.Error())
			return
		}
	}
	job, err := s.downloads.Action(r.PathValue("id"), action)
	if err != nil {
		s.logger.Warn("download action rejected", "action", action, "error", err)
		fail(w, 409, "invalid_download_state", "当前任务状态不支持此操作，或持久化失败")
		return
	}
	writeJSON(w, 200, job)
}
func (s *Server) downloadEvents(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	if s.downloads == nil {
		fail(w, 503, "downloads_unavailable", "下载管理器尚未初始化")
		return
	}
	select {
	case s.eventSlots <- struct{}{}:
		defer func() { <-s.eventSlots }()
	default:
		w.Header().Set("Retry-After", "10")
		fail(w, 429, "too_many_event_connections", "下载事件连接过多，请关闭部分页面后重试")
		return
	}
	updates, unsubscribe := s.downloads.Updates()
	defer unsubscribe()
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache, no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	controller := http.NewResponseController(w)
	send := func(jobs []model.DownloadJob) error {
		if jobs == nil {
			jobs = []model.DownloadJob{}
		}
		data, err := json.Marshal(jobs)
		if err != nil {
			return err
		}
		_ = controller.SetWriteDeadline(time.Now().Add(20 * time.Second))
		if _, err = fmt.Fprintf(w, "event: downloads\ndata: %s\n\n", data); err != nil {
			return err
		}
		return controller.Flush()
	}
	if err := send(s.downloads.List()); err != nil {
		return
	}
	// fnOS 会话由网关持有，本服务无法查询撤销状态；定期结束连接，
	// 让浏览器重连时重新经过网关认证。不会让旧 Header 无限续订会话。
	var lease <-chan time.Time
	if s.gatewayMode() {
		expiry := time.NewTimer(2 * time.Minute)
		defer expiry.Stop()
		lease = expiry.C
	}
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-lease:
			return
		case <-s.done:
			return
		case <-r.Context().Done():
			return
		case jobs, open := <-updates:
			if !open {
				return
			}
			if err := send(jobs); err != nil {
				return
			}
		case <-ticker.C:
			// 长连接重新检查会话有效性，注销或到期后不继续泄露下载状态。
			if !s.authenticated(r) {
				return
			}
			_ = controller.SetWriteDeadline(time.Now().Add(20 * time.Second))
			if _, err := fmt.Fprint(w, ": heartbeat\n\n"); err != nil {
				return
			}
			if err := controller.Flush(); err != nil {
				return
			}
		}
	}
}

func (s *Server) downloadByID(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	if s.downloads == nil {
		fail(w, 503, "downloads_unavailable", "下载管理器尚未初始化")
		return
	}
	for _, job := range s.downloads.List() {
		if job.ID == r.PathValue("id") {
			writeJSON(w, 200, job)
			return
		}
	}
	missing(w)
}

// 可选注入保持既有 Downloads mocks 与 download.New 签名兼容。
func (s *Server) configureDownloadRecordRemoval() error {
	if manager, ok := s.downloads.(interface {
		SetRecordRemover(func(context.Context, []string) error) error
	}); ok {
		return manager.SetRecordRemover(s.store.DeleteDownloadRecords)
	}
	return nil
}

func (s *Server) clearDownloadRecords(w http.ResponseWriter, r *http.Request) {
	if s.serverDownloadsDisabled(w) {
		return
	}
	manager, ok := s.downloads.(interface {
		ClearRecords(context.Context) (int, int, error)
	})
	if !ok {
		fail(w, 503, "download_records_unavailable", "下载记录清理暂不可用")
		return
	}
	// 无路径、ID 或状态参数；允许无请求体或空 JSON 对象。
	if r.URL.RawQuery != "" {
		fail(w, 400, "invalid_clear_records_request", "清理下载记录不接受查询参数")
		return
	}
	if r.ContentLength != 0 {
		var body struct{}
		if !decode(w, r, &body) {
			return
		}
	}
	// 不走 activeProvider/目录授权/Configure；停用下载或来源不影响记录管理。
	cleared, remaining, err := manager.ClearRecords(r.Context())
	if err != nil {
		s.logger.Warn("download records clear failed", "error", err)
		fail(w, 500, "download_records_clear_failed", "无法清理下载记录，请稍后重试")
		return
	}
	writeJSON(w, 200, struct {
		Cleared   int `json:"cleared"`
		Remaining int `json:"remaining"`
	}{cleared, remaining})
}

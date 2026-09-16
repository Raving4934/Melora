package api

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"unicode/utf8"

	"melora/internal/catalog"
	"melora/internal/model"
	"melora/internal/provider"
)

func (s *Server) providers(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		settings, err := s.store.Settings(r.Context())
		if err != nil {
			s.dbError(w, err)
			return
		}
		writeJSON(w, 200, s.live.Infos(s.cfg.HasDownloadAuthorization() && settings.DownloadRoot != "" && s.downloads != nil))
		return
	}
	enabled, ok := s.enabled(w, r)
	if !ok {
		return
	}
	settings, err := s.store.Settings(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	writeJSON(w, 200, []provider.Info{s.demo.Info(enabled, s.cfg.HasDownloadAuthorization() && settings.DownloadRoot != "" && s.downloads != nil)})
}
func (s *Server) updateProvider(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		fail(w, 405, "managed_by_lx_source", "请通过LX音源管理选择解析源")
		return
	}
	if r.PathValue("id") != "demo" {
		missing(w)
		return
	}
	var body struct {
		Enabled *bool `json:"enabled"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.Enabled == nil {
		fail(w, 400, "invalid_provider", "enabled 为必填布尔值")
		return
	}
	if err := s.store.SetProviderEnabled(r.Context(), *body.Enabled); err != nil {
		s.dbError(w, err)
		return
	}
	settings, err := s.store.Settings(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	writeJSON(w, 200, s.demo.Info(*body.Enabled, s.cfg.HasDownloadAuthorization() && settings.DownloadRoot != "" && s.downloads != nil))
}
func (s *Server) providerHealth(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		platform := r.PathValue("id")
		adapter, err := s.live.Catalog.Adapter(platform)
		if err != nil {
			missing(w)
			return
		}
		_, err = adapter.Charts(r.Context())
		if err != nil {
			s.liveError(w, err)
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ok", "message": "平台目录可访问；播放由当前LX源提供"})
		return
	}
	if r.PathValue("id") != "demo" {
		missing(w)
		return
	}
	if !s.activeProvider(w, r) {
		return
	}
	writeJSON(w, 200, map[string]string{"status": "ok", "message": "演示目录已加载（2 首公有领域录音）；此检查不探测远端音频 CDN。"})
}
func (s *Server) charts(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		s.liveCharts(w, r)
		return
	}
	if !s.source(w, r) {
		return
	}
	enabled, ok := s.enabled(w, r)
	if !ok {
		return
	}
	charts := []model.Collection{}
	if enabled {
		charts = s.demo.Charts()
	}
	writeJSON(w, 200, charts)
}
func (s *Server) chartTracks(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		s.liveChartTracks(w, r)
		return
	}
	p, exists := s.demo.Chart(r.PathValue("id"))
	if !exists {
		missing(w)
		return
	}
	if !s.activeProvider(w, r) {
		return
	}
	writeJSON(w, 200, p.Tracks)
}
func (s *Server) daily(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		s.liveDaily(w, r)
		return
	}
	enabled, ok := s.enabled(w, r)
	if !ok {
		return
	}
	result := provider.Recommendations{Tracks: []model.Track{}, Playlists: []model.Collection{}}
	if enabled {
		result = s.demo.Daily()
	}
	writeJSON(w, 200, discoveryFeed{Tracks: result.Tracks, Playlists: result.Playlists, Reason: "公开音乐推荐"})
}
func (s *Server) playlists(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		s.livePlaylists(w, r)
		return
	}
	if !s.source(w, r) {
		return
	}
	enabled, ok := s.enabled(w, r)
	if !ok {
		return
	}
	playlists := []model.Collection{}
	if enabled {
		playlists = s.demo.Playlists(r.URL.Query().Get("category"))
	}
	writeJSON(w, 200, playlists)
}
func (s *Server) playlist(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		s.livePlaylist(w, r)
		return
	}
	p, exists := s.demo.Playlist(r.PathValue("id"))
	if !exists {
		missing(w)
		return
	}
	if !s.activeProvider(w, r) {
		return
	}
	writeJSON(w, 200, p)
}
func (s *Server) search(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		s.liveSearch(w, r)
		return
	}
	if !s.source(w, r) {
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 {
		fail(w, 400, "invalid_query", "搜索词最长 200 个字符，必须是有效 UTF-8")
		return
	}
	kind := r.URL.Query().Get("type")
	if kind == "" {
		kind = "track"
	}
	if kind != "track" && kind != "playlist" && kind != "artist" && kind != "album" {
		fail(w, 400, "invalid_search_type", "type 必须为 track、playlist、artist 或 album")
		return
	}
	enabled, ok := s.enabled(w, r)
	if !ok {
		return
	}
	result := provider.EmptySearch()
	if enabled {
		result = s.demo.Search(query, kind)
	}
	writeJSON(w, 200, result)
}
func (s *Server) knownTrack(w http.ResponseWriter, id string, contexts ...context.Context) (model.Track, bool) {
	if s.live != nil {
		ctx := context.Background()
		if len(contexts) > 0 {
			ctx = contexts[0]
		}
		track, err := s.live.Track(ctx, id)
		if err != nil {
			s.liveError(w, err)
			return model.Track{}, false
		}
		return track, true
	}
	track, exists := s.demo.Track(id)
	if !exists {
		missing(w)
		return model.Track{}, false
	}
	return track, true
}
func (s *Server) track(w http.ResponseWriter, r *http.Request) {
	track, ok := s.knownTrack(w, r.PathValue("id"), r.Context())
	if ok {
		writeJSON(w, 200, track)
	}
}
func (s *Server) playInfo(w http.ResponseWriter, r *http.Request) {
	// 只在此 handler 提供诊断；目录失败之前不能虚构设置值或解析尝试。
	if s.live != nil {
		w.Header().Set("X-Melora-Resolve-Stage", "catalog")
		w.Header().Set("X-Melora-Resolve-Attempts", "0")
		w.Header().Del("X-Melora-Auto-Switch")
	}
	track, ok := s.knownTrack(w, r.PathValue("id"), r.Context())
	if !ok || !s.activeProvider(w, r) {
		return
	}
	quality := r.URL.Query().Get("quality")
	settings, err := s.store.Settings(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	if quality == "" {
		quality = settings.DefaultQuality
	}
	if s.live != nil {
		w.Header().Set("X-Melora-Auto-Switch", strconv.FormatBool(settings.AutoSwitchSource))
		w.Header().Set("X-Melora-Resolve-Stage", "resolve")
		excluded := []string{}
		if value := r.URL.Query().Get("excludeSources"); value != "" {
			if len(value) > 1024 {
				fail(w, 400, "invalid_resolve_options", "已尝试音源列表过长")
				return
			}
			excluded = strings.Split(value, ",")
		}
		info, err := s.live.PlayInfoWithOptions(r.Context(), track, quality, provider.ResolveOptions{AutoSwitch: settings.AutoSwitchSource, ExcludeSources: excluded, PageScheme: s.playbackPageScheme(r)})
		// 记录实际尝试过的去重音源数，不是同一脚本跨平台的总调用次数。
		w.Header().Set("X-Melora-Resolve-Attempts", strconv.Itoa(len(info.AttemptedSources)))
		if len(info.AttemptedSources) == 0 && (errors.Is(err, catalog.ErrUnavailable) || errors.Is(err, catalog.ErrNotFound) || errors.Is(err, catalog.ErrInput) || errors.Is(err, catalog.ErrUnsupported)) {
			w.Header().Set("X-Melora-Resolve-Stage", "catalog")
		}
		if err != nil {
			s.liveError(w, err)
			return
		}
		writeJSON(w, 200, info)
		return
	}
	info, err := s.demo.PlayInfo(track.ID, quality)
	if err != nil {
		fail(w, 400, "unsupported_quality", "演示音源仅提供 standard（原始 Ogg Vorbis），不提供无损转码")
		return
	}
	writeJSON(w, 200, info)
}
func (s *Server) lyrics(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.knownTrack(w, r.PathValue("id"), r.Context()); !ok {
		return
	}
	if !s.activeProvider(w, r) {
		return
	}
	if s.live != nil {
		lines, err := s.live.Catalog.LyricsWithFallback(r.Context(), r.PathValue("id"))
		if err != nil {
			s.liveError(w, err)
			return
		}
		writeJSON(w, 200, lines)
		return
	}
	writeJSON(w, 200, s.demo.Lyrics())
}

// 页面协议提示只限制响应中的媒体地址，不用于鉴权或发起代理请求。
// TLS请求永不降级；网关缺少页面提示时保守按HTTPS，避免把内部socket的HTTP当外部页面协议。
func (s *Server) playbackPageScheme(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	if len(r.Header.Values(clientOriginHeader)) == 1 {
		if origin, ok := s.csrfClientOrigin(r); ok {
			u, _ := url.Parse(origin)
			return u.Scheme
		}
	}
	if s.gatewayMode() {
		return "https"
	}
	return "http"
}

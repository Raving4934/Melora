package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
	"melora/internal/provider"
)

// UseLiveSources 仅在服务器开始接受请求前由main调用；不提供HTTP切换测试模式入口。
func (s *Server) UseLiveSources(sources *lxsource.Manager, live *provider.Live) {
	s.sources = sources
	s.live = live
	if live != nil {
		live.Catalog.SetMetadataStore(s.store)
		settings, err := s.store.Settings(context.Background())
		live.SetAutoSwitch(err == nil && settings.AutoSwitchSource)
	}
}
func (s *Server) liveError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, catalog.ErrNotFound):
		missing(w)
	case errors.Is(err, catalog.ErrUnsupported):
		fail(w, 422, "catalog_capability_unsupported", "该平台当前未提供此类目录，请切换平台或搜索类型")
	case errors.Is(err, catalog.ErrInput):
		fail(w, 400, "invalid_catalog_request", err.Error())
	case errors.Is(err, lxsource.ErrNoActive):
		fail(w, 409, "lx_source_required", err.Error())
	case errors.Is(err, lxsource.ErrUnsupported):
		fail(w, 422, "lx_platform_unsupported", err.Error())
	case errors.Is(err, provider.ErrQuality):
		fail(w, 400, "unsupported_quality", err.Error())
	case errors.Is(err, provider.ErrResolveOptions):
		fail(w, 400, "invalid_resolve_options", err.Error())
	case errors.Is(err, provider.ErrMixedContent), errors.Is(err, provider.ErrHTTPMedia), errors.Is(err, provider.ErrMediaHeaders), errors.Is(err, provider.ErrNoCandidate):
		fail(w, 502, "media_unavailable", err.Error())
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, lxruntime.ErrTimeout):
		fail(w, 504, "resolve_timeout", "音源响应超时，请重试或切换音源")
	case errors.Is(err, provider.ErrMediaURL):
		fail(w, 502, "invalid_media_url", err.Error())
	case errors.Is(err, catalog.ErrUnavailable):
		fail(w, 502, "catalog_unavailable", err.Error())
	case errors.Is(err, context.Canceled):
		fail(w, 408, "request_cancelled", "请求已取消")
	case errors.Is(err, lxruntime.ErrNetwork):
		fail(w, 502, "lx_resolve_failed", "音源元数据请求失败或被安全策略拒绝，请检查网络或切换音源")
	case errors.Is(err, lxruntime.ErrUnsupported):
		fail(w, 502, "lx_resolve_failed", "音源脚本使用了当前运行环境不支持的接口，请切换兼容的音源")
	case errors.Is(err, lxruntime.ErrResource):
		fail(w, 502, "lx_resolve_failed", "音源进程达到资源限制或异常退出，请重试或切换音源")
	case errors.Is(err, lxruntime.ErrLimit):
		fail(w, 502, "lx_resolve_failed", "音源请求的大小或数量超过安全限制，请切换音源")
	case errors.Is(err, lxruntime.ErrProtocol):
		fail(w, 502, "lx_resolve_failed", "音源运行进程通信异常，请重试或切换音源")
	case errors.Is(err, lxruntime.ErrScript):
		fail(w, 502, "lx_resolve_failed", "音源脚本未能返回可用播放地址，请切换音源或检查脚本兼容性")
	default:
		fail(w, 502, "lx_resolve_failed", "LX 音源解析失败，请检查脚本兼容性、网络或远端服务状态")
	}
}
func liveSource(w http.ResponseWriter, r *http.Request) bool {
	source := r.URL.Query().Get("source")
	if source != "" && source != "all" && catalog.PlatformNames[source] == "" {
		fail(w, 400, "catalog_unsupported", "请选择已接入的音乐平台")
		return false
	}
	return true
}
func (s *Server) writeCatalog(w http.ResponseWriter, data any, err error) {
	if failed, partial := catalog.IsPartial(err); partial {
		w.Header().Set("X-Melora-Unavailable-Sources", strings.Join(failed, ","))
		var details *catalog.PartialError
		if errors.As(err, &details) && details != nil {
			issues := []string{}
			seen := map[string]bool{}
			for _, source := range failed {
				// 仅为本次失败的已知平台补充能力原因，不遍历 Causes 或输出原始错误。
				switch source {
				case "wy", "tx", "kw", "kg", "mg":
				default:
					continue
				}
				if seen[source] {
					continue
				}
				seen[source] = true
				// 旧 Sources-only 构造、缺失或未知原因均使用安全 generic code。
				issues = append(issues, source+"="+discoveryIssueCode(details.Causes[source]))
			}
			if len(issues) > 0 {
				w.Header().Set("X-Melora-Catalog-Issues", strings.Join(issues, ","))
			}
		}
	} else if err != nil {
		s.liveError(w, err)
		return
	}
	writeJSON(w, 200, data)
}
func catalogPage(w http.ResponseWriter, r *http.Request) int {
	value := r.URL.Query().Get("page")
	if value == "" {
		return 1
	}
	n, err := strconv.Atoi(value)
	if err != nil || n < 1 || n > 50 {
		fail(w, 400, "invalid_page", "page必须为1–50")
		return 0
	}
	return n
}
func (s *Server) liveCharts(w http.ResponseWriter, r *http.Request) {
	if !liveSource(w, r) {
		return
	}
	items, err := s.live.Catalog.ChartsFor(r.Context(), r.URL.Query().Get("source"))
	s.writeCatalog(w, items, err)
}
func (s *Server) liveChartTracks(w http.ResponseWriter, r *http.Request) {
	p, err := s.live.Catalog.Chart(r.Context(), r.PathValue("id"))
	if err != nil {
		s.liveError(w, err)
		return
	}
	writeJSON(w, 200, s.live.EnrichTracks(p.Tracks))
}
func (s *Server) livePlaylists(w http.ResponseWriter, r *http.Request) {
	if !liveSource(w, r) {
		return
	}
	page := catalogPage(w, r)
	if page == 0 {
		return
	}
	items, err := s.live.Catalog.PlaylistsFor(r.Context(), r.URL.Query().Get("source"), r.URL.Query().Get("category"), page)
	w.Header().Set("X-Melora-Has-More", strconv.FormatBool(s.live.Catalog.PlaylistsHaveMore(r.URL.Query().Get("category"), page, items)))
	s.writeCatalog(w, items, err)
}
func (s *Server) livePlaylist(w http.ResponseWriter, r *http.Request) {
	p, err := s.live.Catalog.Playlist(r.Context(), r.PathValue("id"))
	if err != nil {
		s.liveError(w, err)
		return
	}
	writeJSON(w, 200, s.live.EnrichCollection(p))
}
func (s *Server) liveDaily(w http.ResponseWriter, r *http.Request) { s.liveRecommendations(w, r) }

func (s *Server) liveSearch(w http.ResponseWriter, r *http.Request) {
	if !liveSource(w, r) {
		return
	}
	page := catalogPage(w, r)
	if page == 0 {
		return
	}
	kind := r.URL.Query().Get("type")
	if kind == "" {
		kind = "track"
	}
	result, err := s.live.Catalog.SearchFor(r.Context(), r.URL.Query().Get("source"), r.URL.Query().Get("q"), kind, page)
	result.Tracks = s.live.EnrichTracks(result.Tracks)
	s.writeCatalog(w, result, err)
}
func (s *Server) enrichUserPlaylist(p model.UserPlaylist) model.UserPlaylist {
	if s.live != nil {
		p.Tracks = s.live.EnrichTracks(p.Tracks)
	}
	return p
}

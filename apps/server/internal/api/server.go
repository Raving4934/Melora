// Package api 使用标准 net/http 暴露契约 API，与下载实现通过小接口解耦。
package api

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"melora/internal/catalog"
	"melora/internal/config"
	"melora/internal/lxsource"
	"melora/internal/model"
	"melora/internal/provider"
	"melora/internal/store"
	"melora/internal/version"
)

const responseWriteTimeout = 30 * time.Second

type responseState struct {
	http.ResponseWriter
	wroteHeader bool
}

func (w *responseState) WriteHeader(status int) {
	if w.wroteHeader {
		return
	}
	w.wroteHeader = true
	w.ResponseWriter.WriteHeader(status)
}

func (w *responseState) Write(data []byte) (int, error) {
	if !w.wroteHeader {
		w.WriteHeader(http.StatusOK)
	}
	return w.ResponseWriter.Write(data)
}

func (w *responseState) Unwrap() http.ResponseWriter { return w.ResponseWriter }

type Downloads interface {
	Create(model.Track, string) (model.DownloadJob, error)
	CreateWithOptions(model.Track, string, model.Settings) (model.DownloadJob, error)
	SetOptions(model.Settings) error
	List() []model.DownloadJob
	Action(string, string) (model.DownloadJob, error)
	Updates() (<-chan []model.DownloadJob, func())
	Configure(string, int) error
	SetWriteMetadata(bool)
}

type Server struct {
	csrfSecret             [32]byte
	live                   *provider.Live
	sources                *lxsource.Manager
	cfg                    config.Config
	store                  *store.Store
	demo                   provider.Provider
	downloads              Downloads
	covers                 *catalog.CoverStore
	mux                    *http.ServeMux
	auth                   *sessions
	settingsMu             sync.Mutex
	recommendationMu       sync.Mutex
	recommendationRevision uint64
	recommendationCache    map[string]recommendationCacheEntry
	recommendationFlights  map[string]*recommendationFlight
	recommendationSlots    chan struct{}
	eventSlots             chan struct{}
	webRoot                *os.Root
	done                   chan struct{}
	closeOnce              sync.Once
	logger                 *slog.Logger
}

func New(cfg config.Config, db *store.Store, demo provider.Provider, downloads Downloads) (*Server, error) {
	if cfg.SocketPath == "" {
		if err := config.ValidateAddress(cfg.Addr, cfg.AuthToken, cfg.AdminPassword); err != nil {
			return nil, err
		}
	}
	if err := cfg.ValidateGateway(); err != nil {
		return nil, err
	}
	if db == nil || demo == nil {
		return nil, errors.New("API 需要数据库与 Provider")
	}
	if err := cfg.ValidateRuntimePaths(); err != nil {
		return nil, err
	}
	s := &Server{cfg: cfg, store: db, demo: demo, downloads: downloads, mux: http.NewServeMux(), auth: newSessions(cfg.AuthToken, cfg.AdminUser, cfg.AdminPassword, cfg.TrustedProxyNets), done: make(chan struct{}), logger: slog.Default()}
	if _, err := rand.Read(s.csrfSecret[:]); err != nil {
		return nil, err
	}
	if err := s.configureDownloadRecordRemoval(); err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(cfg.WebDir)
	if err == nil {
		s.webRoot = root
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	s.eventSlots = make(chan struct{}, 32)
	s.recommendationSlots = make(chan struct{}, 2)
	s.covers = catalog.NewCoverStore()
	s.routes()
	return s, nil
}
func (s *Server) Close() {
	s.closeOnce.Do(func() {
		close(s.done)
		s.covers.Close()
		if s.webRoot != nil {
			_ = s.webRoot.Close()
		}
	})
}
func (s *Server) routes() {
	s.registerSourceRoutes()
	s.registerDiscoveryRoutes()
	s.registerAudiobookRoutes()
	s.registerUserPlaylistRoutes()
	s.mux.HandleFunc("/health", method(s.health, "GET", "HEAD"))
	s.mux.HandleFunc("/api/v1/health", method(s.health, "GET", "HEAD"))
	s.mux.HandleFunc("/api/v1/auth/session", method(s.session, "GET", "POST", "DELETE"))
	s.mux.HandleFunc("/api/v1/providers", method(s.providers, "GET"))
	s.mux.HandleFunc("/api/v1/providers/{id}", method(s.updateProvider, "PATCH", "PUT"))
	s.mux.HandleFunc("/api/v1/providers/{id}/health-check", method(s.providerHealth, "POST"))
	s.mux.HandleFunc("/api/v1/charts", method(s.charts, "GET"))
	s.mux.HandleFunc("/api/v1/charts/featured", method(s.chartShowcase, "GET"))
	s.mux.HandleFunc("/api/v1/charts/{id}/tracks", method(s.chartTracks, "GET"))
	s.mux.HandleFunc("/api/v1/recommendations/daily", method(s.daily, "GET"))
	s.mux.HandleFunc("/api/v1/playlists", method(s.playlists, "GET"))
	s.mux.HandleFunc("/api/v1/playlists/{id}", method(s.playlist, "GET"))
	s.mux.HandleFunc("/api/v1/search", method(s.search, "GET"))
	s.mux.HandleFunc("/api/v1/covers", method(s.cover, "GET"))
	s.mux.HandleFunc("/api/v1/tracks/{id}", method(s.track, "GET"))
	s.mux.HandleFunc("/api/v1/tracks/{id}/play-info", method(s.playInfo, "GET"))
	s.mux.HandleFunc("/api/v1/tracks/{id}/play", method(s.playInfo, "GET"))
	s.mux.HandleFunc("/api/v1/tracks/{id}/lyrics", method(s.lyrics, "GET"))
	s.mux.HandleFunc("/api/v1/library/summary", method(s.librarySummary, "GET"))
	s.mux.HandleFunc("/api/v1/library/favorites/tracks", method(s.favoriteTracks, "GET"))
	s.mux.HandleFunc("/api/v1/library/favorites/tracks/{id}", method(s.favoriteTrack, "POST", "DELETE"))
	s.mux.HandleFunc("/api/v1/library/favorites/playlists", method(s.favoritePlaylists, "GET"))
	s.mux.HandleFunc("/api/v1/library/favorites/playlists/{id}", method(s.favoritePlaylist, "POST", "DELETE"))
	s.mux.HandleFunc("/api/v1/library/history", method(s.history, "GET", "POST", "DELETE"))
	s.mux.HandleFunc("/api/v1/library/history/entries", method(s.historyEntries, "GET"))
	s.mux.HandleFunc("/api/v1/library/history/{id}/outcome", method(s.historyOutcome, "POST"))
	s.mux.HandleFunc("/api/v1/settings", method(s.settings, "GET", "PUT", "PATCH"))
	s.mux.HandleFunc("/api/v1/storage/status", method(s.storageStatus, "GET"))
	s.mux.HandleFunc("/api/v1/storage/directories", method(s.storageDirectories, "GET"))
	s.mux.HandleFunc("/api/v1/storage/validate", method(s.validateStorage, "POST"))
	s.mux.HandleFunc("/api/v1/downloads/clear-records", method(s.clearDownloadRecords, "POST"))
	s.mux.HandleFunc("/api/v1/downloads", method(s.downloadListCreate, "GET", "POST"))
	s.mux.HandleFunc("/api/v1/downloads/{id}", method(s.downloadByID, "GET"))
	s.mux.HandleFunc("/api/v1/downloads/events", method(s.downloadEvents, "GET"))
	s.mux.HandleFunc("/api/v1/downloads/{id}/{action}", method(s.downloadAction, "POST"))
	s.mux.HandleFunc("/", s.static)
}
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	response := &responseState{ResponseWriter: w}
	w = response
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	if s.gatewayMode() {
		w.Header().Set("X-Frame-Options", "SAMEORIGIN")
	} else {
		w.Header().Set("X-Frame-Options", "DENY")
	}
	defer func() {
		if recovered := recover(); recovered != nil {
			attributes := []any{"method", r.Method, "path", r.URL.Path, "stack", string(debug.Stack())}
			switch value := recovered.(type) {
			case error:
				attributes = append(attributes, "error", value)
			case string:
				attributes = append(attributes, "panic", value)
			default:
				attributes = append(attributes, "panicType", fmt.Sprintf("%T", recovered))
			}
			s.logger.Error("API request panic", attributes...)
			if !response.wroteHeader {
				fail(w, 500, "internal_error", "服务暂时无法完成请求")
			}
		}
	}()
	if len(r.URL.RequestURI()) > 8192 {
		fail(w, 414, "uri_too_long", "请求 URL 过长")
		return
	}

	if s.cfg.BasePath != "" {
		if r.URL.Path == s.cfg.BasePath {
			http.Redirect(w, r, s.cfg.BasePath+"/", http.StatusPermanentRedirect)
			return
		}
		if !strings.HasPrefix(r.URL.Path, s.cfg.BasePath+"/") {
			missing(w)
			return
		}
		r = r.Clone(r.Context())
		r.URL.Path = strings.TrimPrefix(r.URL.Path, s.cfg.BasePath)
		if r.URL.RawPath != "" {
			if !strings.HasPrefix(r.URL.RawPath, s.cfg.BasePath+"/") {
				missing(w)
				return
			}
			r.URL.RawPath = strings.TrimPrefix(r.URL.RawPath, s.cfg.BasePath)
		}
	}
	if r.URL.Path != "/api/v1/downloads/events" {
		controller := http.NewResponseController(w)
		if err := controller.SetWriteDeadline(time.Now().Add(responseWriteTimeout)); err == nil {
			defer func() { _ = controller.SetWriteDeadline(time.Time{}) }()
		}
	}
	// 健康探针匿名；不让其成为其它 API 的鉴权旁路。
	health := (r.URL.Path == "/health" || r.URL.Path == "/api/v1/health") && (r.Method == "GET" || r.Method == "HEAD")
	if s.gatewayMode() && !health && !(r.URL.Path == "/api/v1/auth/session" && r.Method == http.MethodGet) {
		user, valid := s.gatewayIdentity(r)
		if !valid {
			fail(w, 401, "fnos_identity_required", "请从飞牛桌面登录后打开乐屿；无法验证网关身份")
			return
		}
		if !user.Admin {
			fail(w, 403, "fnos_admin_required", "当前版本使用共享音乐数据，仅允许飞牛管理员访问")
			return
		}
	}
	if !health && !s.gatewayMode() && !s.validHost(r) {
		fail(w, 403, "invalid_host", "请求 Host 不在服务允许范围内")
		return
	}
	if r.Method != "GET" && r.Method != "HEAD" && r.Method != "OPTIONS" && !s.originAllowed(r) {
		fail(w, 403, "csrf_rejected", "写操作校验未通过，请刷新页面后重试")
		return
	}
	limit := maxBodyBytes
	if r.Method == http.MethodPost && r.URL.Path == "/api/v1/sources/import" {
		limit = lxsource.MaxScriptBytes + (32 << 10) // 文件加 multipart 边界；其它 API 仍限制 64 KiB。
	}
	if r.ContentLength > limit {
		fail(w, 413, "body_too_large", "请求体超过该接口的大小限制")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	if strings.HasPrefix(r.URL.Path, "/api/") && !health && r.URL.Path != "/api/v1/auth/session" && !s.authenticated(r) {
		fail(w, 401, "authentication_required", "请先登录后再试")
		return
	}
	if strings.HasPrefix(r.URL.Path, "/api/") && r.URL.Path != "/api/v1/downloads/events" {
		ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
		defer cancel()
		r = r.WithContext(ctx)
	}
	if s.cfg.BasePath != "" {
		for _, part := range strings.Split(r.URL.Path, "/") {
			if part == "." || part == ".." {
				missing(w)
				return
			}
		}
		if strings.Contains(r.URL.Path, "//") {
			missing(w)
			return
		}
	}
	s.mux.ServeHTTP(w, r)
}
func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]any{"status": "ok", "name": "Melora", "version": version.Value, "demo": s.live == nil})
}
func (s *Server) dbError(w http.ResponseWriter, err error) {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		s.logger.Debug("SQLite operation interrupted", "error", err)
	} else {
		s.logger.Error("SQLite operation failed", "error", err)
	}
	internalError(w)
}
func (s *Server) enabled(w http.ResponseWriter, r *http.Request) (bool, bool) {
	enabled, err := s.store.ProviderEnabled(r.Context())
	if err != nil {
		s.dbError(w, err)
		return false, false
	}
	return enabled, true
}
func (s *Server) activeProvider(w http.ResponseWriter, r *http.Request) bool {
	if s.live != nil {
		return true
	}
	enabled, ok := s.enabled(w, r)
	if !ok {
		return false
	}
	if !enabled {
		fail(w, 409, "provider_disabled", "演示音源已停用，请先在设置中启用")
		return false
	}
	return true
}
func (s *Server) source(w http.ResponseWriter, r *http.Request) bool {
	source := r.URL.Query().Get("source")
	if source != "" && source != "all" && source != "demo" {
		fail(w, 400, "invalid_source", "只支持 all 或 demo 音源")
		return false
	}
	return true
}

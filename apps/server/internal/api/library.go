package api

import (
	"errors"
	"net/http"
	"strings"
	"unicode"

	"melora/internal/catalog"
	"melora/internal/model"
	"melora/internal/store"
)

func (s *Server) librarySummary(w http.ResponseWriter, r *http.Request) {
	summary, err := s.store.LibrarySummary(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	writeJSON(w, 200, summary)
}

func (s *Server) favoriteTracks(w http.ResponseWriter, r *http.Request) {
	tracks, err := s.store.FavoriteTracks(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	if s.live != nil {
		tracks = s.live.EnrichTracks(tracks)
	}
	writeJSON(w, 200, tracks)
}
func (s *Server) favoriteTrack(w http.ResponseWriter, r *http.Request) {
	if s.live != nil && r.Method == "DELETE" {
		if err := s.store.FavoriteTrack(r.Context(), model.Track{ID: r.PathValue("id")}, false); err != nil {
			s.dbError(w, err)
			return
		}
		s.invalidateRecommendations()
		ok(w)
		return
	}
	track, exists := s.knownTrack(w, r.PathValue("id"), r.Context())
	if !exists {
		return
	}
	if err := s.store.FavoriteTrack(r.Context(), track, r.Method == "POST"); err != nil {
		s.dbError(w, err)
		return
	}
	s.invalidateRecommendations()
	ok(w)
}
func (s *Server) favoritePlaylists(w http.ResponseWriter, r *http.Request) {
	playlists, err := s.store.FavoritePlaylists(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	writeJSON(w, 200, playlists)
}
func (s *Server) favoritePlaylist(w http.ResponseWriter, r *http.Request) {
	if s.live != nil && r.Method == "DELETE" {
		if err := s.store.FavoritePlaylist(r.Context(), model.Collection{ID: r.PathValue("id")}, false); err != nil {
			s.dbError(w, err)
			return
		}
		s.invalidateRecommendations()
		ok(w)
		return
	}
	if s.live != nil {
		id := r.PathValue("id")
		var playlist model.Collection
		if catalog.IsBookAlbumID(id) {
			album, err := s.live.Catalog.BookAlbum(r.Context(), id, 1)
			if err != nil {
				s.liveError(w, err)
				return
			}
			playlist = album.Collection
		} else {
			resolved, err := s.live.Catalog.Playlist(r.Context(), id)
			if err != nil {
				s.liveError(w, err)
				return
			}
			playlist = resolved
		}
		if err := s.store.FavoritePlaylist(r.Context(), playlist, r.Method == "POST"); err != nil {
			s.dbError(w, err)
			return
		}
		s.invalidateRecommendations()
		ok(w)
		return
	}
	playlist, exists := s.demo.Playlist(r.PathValue("id"))
	if !exists {
		missing(w)
		return
	}
	if err := s.store.FavoritePlaylist(r.Context(), playlist, r.Method == "POST"); err != nil {
		s.dbError(w, err)
		return
	}
	s.invalidateRecommendations()
	ok(w)
}
func (s *Server) loadHistoryEntries(w http.ResponseWriter, r *http.Request) ([]model.HistoryEntry, bool) {
	kind := r.URL.Query().Get("kind")
	if kind != "" && !model.HistoryKindValid(kind) {
		fail(w, 400, "invalid_kind", "kind 只支持 track、playlist 或 audiobook")
		return nil, false
	}
	entries, err := s.store.History(r.Context(), kind)
	if err != nil {
		s.dbError(w, err)
		return nil, false
	}
	if s.live != nil {
		tracks := make([]model.Track, len(entries))
		for i := range entries {
			tracks[i] = entries[i].Track
		}
		tracks = s.live.EnrichTracks(tracks)
		for i := range entries {
			entries[i].Track = tracks[i]
		}
	}
	return entries, true
}

// historyEntries 提供带来源与播放统计的新结构；旧 history GET 继续保持 Track[] 契约。
func (s *Server) historyEntries(w http.ResponseWriter, r *http.Request) {
	entries, ok := s.loadHistoryEntries(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, entries)
}

func (s *Server) history(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "GET":
		entries, ok := s.loadHistoryEntries(w, r)
		if !ok {
			return
		}
		tracks := make([]model.Track, len(entries))
		for i := range entries {
			tracks[i] = entries[i].Track
		}
		writeJSON(w, http.StatusOK, tracks)
	case "POST":
		var body struct {
			TrackID   string `json:"trackId"`
			Kind      string `json:"kind"`
			ContextID string `json:"contextId"`
		}
		if !decode(w, r, &body) {
			return
		}
		if body.TrackID == "" {
			fail(w, 400, "invalid_track", "trackId 不能为空")
			return
		}
		kind := body.Kind
		if kind == "" {
			kind = model.HistoryKindTrack
		}
		if !model.HistoryKindValid(kind) {
			fail(w, 400, "invalid_kind", "kind 只支持 track、playlist 或 audiobook")
			return
		}
		contextID := strings.TrimSpace(body.ContextID)
		if kind != model.HistoryKindTrack {
			if contextID == "" || len(contextID) > 200 || strings.ContainsFunc(contextID, unicode.IsControl) {
				fail(w, 400, "invalid_context", "contextId 必须是 1–200 字节的集合 ID")
				return
			}
			if catalog.IsBookAlbumID(contextID) != (kind == model.HistoryKindAudiobook) {
				fail(w, 400, "invalid_context", "contextId 与来源类型不匹配")
				return
			}
		}
		track, exists := s.knownTrack(w, body.TrackID, r.Context())
		if !exists {
			return
		}
		if err := s.store.AddHistory(r.Context(), track, kind, contextID); err != nil {
			s.dbError(w, err)
			return
		}
		s.invalidateRecommendations()
		ok(w)
	case "DELETE":
		if err := s.store.ClearHistory(r.Context()); err != nil {
			s.dbError(w, err)
			return
		}
		s.invalidateRecommendations()
		ok(w)
	}
}
func (s *Server) historyOutcome(w http.ResponseWriter, r *http.Request) {
	var body struct {
		EventID        string `json:"eventId"`
		DataGeneration string `json:"dataGeneration"`
		PlayedMs       int64  `json:"playedMs"`
		Completed      bool   `json:"completed"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.PlayedMs < 0 || body.PlayedMs > 6*60*60*1000 {
		fail(w, 400, "invalid_progress", "playedMs 必须在 0–6 小时内")
		return
	}
	if body.EventID != "" {
		if len(body.EventID) < 16 || len(body.EventID) > 80 || strings.IndexFunc(body.EventID, func(r rune) bool {
			return !(r >= 'a' && r <= 'z') && !(r >= 'A' && r <= 'Z') && !(r >= '0' && r <= '9') && r != '-' && r != '_'
		}) >= 0 {
			fail(w, 400, "invalid_outcome_event", "eventId 格式无效")
			return
		}
		identity := s.store.DataIdentity()
		if len(body.DataGeneration) != 32 || strings.IndexFunc(body.DataGeneration, func(r rune) bool {
			return !(r >= 'a' && r <= 'f') && !(r >= '0' && r <= '9')
		}) >= 0 {
			fail(w, 400, "invalid_data_generation", "dataGeneration 格式无效")
			return
		}
		if body.DataGeneration != identity.Generation {
			fail(w, http.StatusConflict, "data_identity_changed", "数据库已切换，旧播放结局已拒绝")
			return
		}
	}
	id := r.PathValue("id")
	if id == "" || len(id) > 200 {
		fail(w, 400, "invalid_track", "trackId 无效")
		return
	}
	if err := s.store.RecordHistoryOutcome(r.Context(), id, body.EventID, body.PlayedMs, body.Completed); err != nil {
		if errors.Is(err, store.ErrOutcomeEventConflict) {
			fail(w, http.StatusConflict, "outcome_event_conflict", "eventId 已用于不同的播放结局")
			return
		}
		if errors.Is(err, store.ErrHistoryNotFound) {
			fail(w, http.StatusConflict, "history_not_ready", "播放记录尚未建立，请稍后重试")
			return
		}
		s.dbError(w, err)
		return
	}
	s.invalidateRecommendations()
	ok(w)
}

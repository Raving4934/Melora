package api

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"

	"melora/internal/store"
)

func (s *Server) registerUserPlaylistRoutes() {
	s.mux.HandleFunc("/api/v1/library/playlists", method(s.userPlaylists, "GET", "POST"))
	s.mux.HandleFunc("/api/v1/library/playlists/{id}", method(s.userPlaylist, "GET", "PUT", "DELETE"))
	s.mux.HandleFunc("/api/v1/library/playlists/{id}/tracks", method(s.userPlaylistTracks, "POST", "PUT"))
	s.mux.HandleFunc("/api/v1/library/playlists/{id}/tracks/{trackId}", method(s.removeUserPlaylistTrack, "DELETE"))
}

type userPlaylistBody struct {
	Title       string `json:"title"`
	Description string `json:"description"`
}

func (s *Server) userPlaylists(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		playlists, err := s.store.UserPlaylists(r.Context())
		if err != nil {
			s.userPlaylistError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, playlists)
		return
	}
	var body userPlaylistBody
	if !decode(w, r, &body) {
		return
	}
	playlist, err := s.store.CreateUserPlaylist(r.Context(), body.Title, body.Description)
	if err != nil {
		s.userPlaylistError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, s.enrichUserPlaylist(playlist))
}

func (s *Server) userPlaylist(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	switch r.Method {
	case http.MethodGet:
		playlist, err := s.store.UserPlaylist(r.Context(), id)
		if err != nil {
			s.userPlaylistError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, s.enrichUserPlaylist(playlist))
	case http.MethodPut:
		var body userPlaylistBody
		if !decode(w, r, &body) {
			return
		}
		playlist, err := s.store.UpdateUserPlaylist(r.Context(), id, body.Title, body.Description)
		if err != nil {
			s.userPlaylistError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, s.enrichUserPlaylist(playlist))
	case http.MethodDelete:
		if err := s.store.DeleteUserPlaylist(r.Context(), id); err != nil {
			s.userPlaylistError(w, err)
			return
		}
		ok(w)
	}
}

func (s *Server) userPlaylistTracks(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if r.Method == http.MethodPost {
		var body struct {
			TrackID string `json:"trackId"`
		}
		if !decode(w, r, &body) {
			return
		}
		if strings.TrimSpace(body.TrackID) == "" {
			fail(w, http.StatusBadRequest, "invalid_track", "trackId 不能为空")
			return
		}
		// 只持久化服务端已知对象；decode 拒绝客户端夹带 Track 元数据。
		track, exists := s.knownTrack(w, body.TrackID, r.Context())
		if !exists {
			return
		}
		playlist, err := s.store.AddUserPlaylistTrack(r.Context(), id, track)
		if err != nil {
			s.userPlaylistError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, s.enrichUserPlaylist(playlist))
		return
	}
	var body struct {
		TrackIDs []string `json:"trackIds"`
	}
	if !decode(w, r, &body) {
		return
	}
	playlist, err := s.store.ReorderUserPlaylistTracks(r.Context(), id, body.TrackIDs)
	if err != nil {
		s.userPlaylistError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, s.enrichUserPlaylist(playlist))
}

func (s *Server) removeUserPlaylistTrack(w http.ResponseWriter, r *http.Request) {
	// 删除仅验证已有成员，不要求音源仍在线或目录中仍包含该曲目。
	playlist, err := s.store.RemoveUserPlaylistTrack(r.Context(), r.PathValue("id"), r.PathValue("trackId"))
	if err != nil {
		s.userPlaylistError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, s.enrichUserPlaylist(playlist))
}

func (s *Server) userPlaylistError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, sql.ErrNoRows):
		missing(w)
	case errors.Is(err, store.ErrInvalidUserPlaylist):
		fail(w, http.StatusBadRequest, "invalid_playlist", err.Error())
	case errors.Is(err, store.ErrInvalidUserPlaylistTrack):
		fail(w, http.StatusBadRequest, "invalid_track", err.Error())
	case errors.Is(err, store.ErrInvalidUserPlaylistOrder):
		fail(w, http.StatusBadRequest, "invalid_playlist_order", err.Error())
	case errors.Is(err, store.ErrUserPlaylistLimit):
		fail(w, http.StatusConflict, "playlist_limit", err.Error())
	case errors.Is(err, store.ErrUserPlaylistTrackLimit):
		fail(w, http.StatusConflict, "playlist_track_limit", err.Error())
	default:
		s.dbError(w, err)
	}
}

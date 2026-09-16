package api

import (
	"net/http"

	"melora/internal/catalog"
	"melora/internal/model"
)

// 听书目录当前由KW目录适配器提供；API 只暴露只读发现、排行榜与章节分页。
func (s *Server) registerAudiobookRoutes() {
	s.mux.HandleFunc("/api/v1/audiobooks/home", method(s.audiobookHome, "GET"))
	s.mux.HandleFunc("/api/v1/audiobooks/ranks/{tabId}", method(s.audiobookRank, "GET"))
	s.mux.HandleFunc("/api/v1/audiobooks/albums/{id}", method(s.audiobookAlbum, "GET"))
}
func (s *Server) audiobookHome(w http.ResponseWriter, r *http.Request) {
	if s.live == nil {
		s.liveError(w, catalog.ErrUnsupported)
		return
	}
	home, err := s.live.Catalog.BookHome(r.Context())
	if err != nil {
		s.liveError(w, err)
		return
	}
	for i := range home.Channels {
		for j := range home.Channels[i].Sections {
			items := home.Channels[i].Sections[j].Items
			for k := range items {
				items[k] = s.live.EnrichCollection(items[k])
			}
			home.Channels[i].Sections[j].Items = items
		}
	}
	writeJSON(w, 200, home)
}
func (s *Server) audiobookRank(w http.ResponseWriter, r *http.Request) {
	if s.live == nil {
		s.liveError(w, catalog.ErrUnsupported)
		return
	}
	page := catalogPage(w, r)
	if page == 0 {
		return
	}
	result, err := s.live.Catalog.BookRank(r.Context(), r.PathValue("tabId"), r.URL.Query().Get("tagId"), page)
	if err != nil {
		s.liveError(w, err)
		return
	}
	items := make([]model.Collection, len(result.Items))
	for i, item := range result.Items {
		items[i] = s.live.EnrichCollection(item)
	}
	result.Items = items
	writeJSON(w, 200, result)
}
func (s *Server) audiobookAlbum(w http.ResponseWriter, r *http.Request) {
	if s.live == nil {
		s.liveError(w, catalog.ErrUnsupported)
		return
	}
	page := catalogPage(w, r)
	if page == 0 {
		return
	}
	album, err := s.live.Catalog.BookAlbum(r.Context(), r.PathValue("id"), page)
	if err != nil {
		s.liveError(w, err)
		return
	}
	album.Collection = s.live.EnrichCollection(album.Collection)
	writeJSON(w, 200, album)
}

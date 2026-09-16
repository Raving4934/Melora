package api

import (
	"net/http"
	"sort"

	"melora/internal/catalog"
	"melora/internal/model"
)

type discoveryArea struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

func (s *Server) registerDiscoveryRoutes() {
	s.mux.HandleFunc("/api/v1/discovery/new-tracks", method(s.newTracks, "GET"))
	s.mux.HandleFunc("/api/v1/playlist-categories", method(s.playlistCategories, "GET"))
	s.mux.HandleFunc("/api/v1/charts/{id}", method(s.chartDetail, "GET"))
}
func (s *Server) newTracks(w http.ResponseWriter, r *http.Request) {
	area := r.URL.Query().Get("area")
	if area == "" {
		area = "all"
	}
	areas := []discoveryArea{{"all", "全部"}}
	tracks := []model.Track{}
	if s.live != nil {
		if !liveSource(w, r) {
			return
		}
		result, err := s.live.Catalog.NewTracksFor(r.Context(), r.URL.Query().Get("source"), area)
		if err != nil {
			s.liveError(w, err)
			return
		}
		tracks = s.live.EnrichTracks(result)
		areas = append(areas, discoveryArea{"zh", "华语"}, discoveryArea{"western", "欧美"}, discoveryArea{"jp", "日语"}, discoveryArea{"kr", "韩语"})
	} else {
		if area != "all" {
			s.liveError(w, catalog.ErrInput)
			return
		}
		enabled, ok := s.enabled(w, r)
		if !ok {
			return
		}
		if enabled {
			tracks = s.demo.Tracks()
		}
	}
	writeJSON(w, 200, map[string]any{"title": "新歌速递", "area": area, "areas": areas, "tracks": tracks})
}
func (s *Server) playlistCategories(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		if !liveSource(w, r) {
			return
		}
		categories, err := s.live.Catalog.CategoriesFor(r.Context(), r.URL.Query().Get("source"))
		if err != nil {
			s.liveError(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"categories": categories})
		return
	}
	enabled, ok := s.enabled(w, r)
	if !ok {
		return
	}
	categories := []map[string]string{}
	if enabled {
		seen := map[string]bool{}
		for _, p := range s.demo.Playlists("all") {
			if p.Category != "" && !seen[p.Category] {
				seen[p.Category] = true
				categories = append(categories, map[string]string{"id": p.Category, "name": p.Category, "group": "分类"})
			}
		}
		sort.Slice(categories, func(i, j int) bool { return categories[i]["id"] < categories[j]["id"] })
	}
	writeJSON(w, 200, map[string]any{"categories": categories})
}
func (s *Server) chartDetail(w http.ResponseWriter, r *http.Request) {
	if s.live != nil {
		item, err := s.live.Catalog.Chart(r.Context(), r.PathValue("id"))
		if err != nil {
			s.liveError(w, err)
			return
		}
		writeJSON(w, 200, s.live.EnrichCollection(item))
		return
	}
	item, ok := s.demo.Chart(r.PathValue("id"))
	if !ok {
		missing(w)
		return
	}
	if !s.activeProvider(w, r) {
		return
	}
	writeJSON(w, 200, item)
}

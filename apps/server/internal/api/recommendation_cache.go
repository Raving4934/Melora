package api

import (
	"bytes"
	"fmt"
	"net/http"
	"strconv"
	"time"
)

const (
	recommendationCacheLimit      = 32
	recommendationFlightLimit     = 8
	recommendationHealthyCacheTTL = 2 * time.Minute
)

type recommendationCacheEntry struct {
	status    int
	header    http.Header
	body      []byte
	createdAt time.Time
	expiresAt time.Time
}

type recommendationFlight struct {
	done     chan struct{}
	response recommendationCacheEntry
}

type recommendationRecorder struct {
	header http.Header
	body   bytes.Buffer
	status int
}

func newRecommendationRecorder() *recommendationRecorder {
	return &recommendationRecorder{header: make(http.Header)}
}

func (w *recommendationRecorder) Header() http.Header { return w.header }
func (w *recommendationRecorder) WriteHeader(status int) {
	if w.status == 0 {
		w.status = status
	}
}
func (w *recommendationRecorder) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.body.Write(data)
}
func (w *recommendationRecorder) response(now time.Time) recommendationCacheEntry {
	status := w.status
	if status == 0 {
		status = http.StatusOK
	}
	return recommendationCacheEntry{
		status: status, header: w.header.Clone(), body: bytes.Clone(w.body.Bytes()),
		createdAt: now, expiresAt: now.Add(recommendationHealthyCacheTTL),
	}
}

func writeRecommendationResponse(w http.ResponseWriter, response recommendationCacheEntry) {
	for key, values := range response.header {
		for _, value := range values {
			w.Header().Add(key, value)
		}
	}
	w.WriteHeader(response.status)
	_, _ = w.Write(response.body)
}

func recommendationBatch(w http.ResponseWriter, r *http.Request) (int, bool) {
	batch := 0
	if raw := r.URL.Query().Get("batch"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 0 || parsed > 10_000 {
			fail(w, http.StatusBadRequest, "invalid_batch", "batch 必须是 0–10000 的整数")
			return 0, false
		}
		batch = parsed
	}
	return batch, true
}

func (s *Server) liveRecommendations(w http.ResponseWriter, r *http.Request) {
	batch, valid := recommendationBatch(w, r)
	if !valid {
		return
	}
	now := time.Now()
	s.recommendationMu.Lock()
	if s.recommendationCache == nil {
		s.recommendationCache = make(map[string]recommendationCacheEntry)
	}
	if s.recommendationFlights == nil {
		s.recommendationFlights = make(map[string]*recommendationFlight)
	}
	revision := s.recommendationRevision
	key := fmt.Sprintf("%d:%d", revision, batch)
	if cached, ok := s.recommendationCache[key]; ok && now.Before(cached.expiresAt) {
		s.recommendationMu.Unlock()
		writeRecommendationResponse(w, cached)
		return
	}
	if flight, ok := s.recommendationFlights[key]; ok {
		s.recommendationMu.Unlock()
		select {
		case <-flight.done:
			writeRecommendationResponse(w, flight.response)
		case <-r.Context().Done():
			fail(w, http.StatusRequestTimeout, "request_cancelled", "请求已取消")
		}
		return
	}
	if len(s.recommendationFlights) >= recommendationFlightLimit {
		s.recommendationMu.Unlock()
		fail(w, http.StatusTooManyRequests, "recommendation_busy", "推荐生成繁忙，请稍后重试")
		return
	}
	flight := &recommendationFlight{done: make(chan struct{})}
	s.recommendationFlights[key] = flight
	s.recommendationMu.Unlock()

	finished := false
	defer func() {
		if finished {
			return
		}
		// 构建异常由外层 HTTP 恢复器记录；这里必须先释放等待者，避免同批请求永久挂起。
		fallback := newRecommendationRecorder()
		internalError(fallback)
		s.finishRecommendationFlight(key, revision, flight, fallback.response(time.Now()))
	}()
	select {
	case s.recommendationSlots <- struct{}{}:
		defer func() { <-s.recommendationSlots }()
	case <-r.Context().Done():
		recorder := newRecommendationRecorder()
		fail(recorder, http.StatusRequestTimeout, "request_cancelled", "请求已取消")
		response := recorder.response(time.Now())
		s.finishRecommendationFlight(key, revision, flight, response)
		finished = true
		writeRecommendationResponse(w, response)
		return
	}
	recorder := newRecommendationRecorder()
	s.buildLiveRecommendations(recorder, r, batch)
	response := recorder.response(time.Now())
	s.finishRecommendationFlight(key, revision, flight, response)
	finished = true
	writeRecommendationResponse(w, response)
}

func (s *Server) finishRecommendationFlight(key string, revision uint64, flight *recommendationFlight, response recommendationCacheEntry) {
	s.recommendationMu.Lock()
	defer s.recommendationMu.Unlock()
	flight.response = response
	if s.recommendationFlights[key] == flight {
		delete(s.recommendationFlights, key)
	}
	// 构建期间若画像已经变化，当前请求仍可返回，但不能污染新代际缓存。
	if response.status == http.StatusOK && response.header.Get("X-Melora-Unavailable-Sources") == "" && s.recommendationRevision == revision {
		s.recommendationCache[key] = response
		for len(s.recommendationCache) > recommendationCacheLimit {
			oldestKey := ""
			var oldest time.Time
			for candidateKey, candidate := range s.recommendationCache {
				if oldestKey == "" || candidate.createdAt.Before(oldest) {
					oldestKey, oldest = candidateKey, candidate.createdAt
				}
			}
			delete(s.recommendationCache, oldestKey)
		}
	}
	close(flight.done)
}

func (s *Server) invalidateRecommendations() {
	s.recommendationMu.Lock()
	s.recommendationRevision++
	clear(s.recommendationCache)
	s.recommendationMu.Unlock()
}

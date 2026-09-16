package catalog

import (
	"context"
	"errors"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

// 本源歌词受版权限制或临时不可用时，按此顺序尝试其他平台。
// TX/WY/MG 歌词覆盖较稳，KG 作为最后兜底。
var lyricsFallbackOrder = []string{"tx", "wy", "mg", "kg"}

const (
	lyricsFallbackBudget   = 9 * time.Second
	lyricsSourceTimeout    = 4 * time.Second
	lyricsCacheTTL         = 6 * time.Hour
	lyricsNegativeCacheTTL = 30 * time.Second
	lyricsCacheMax         = 256
)

type lyricsCacheEntry struct {
	lyrics  Lyrics
	err     error
	expires time.Time
}

type lyricsFlight struct {
	done   chan struct{}
	lyrics Lyrics
	err    error
}

type lyricsCache struct {
	mu      sync.Mutex
	entries map[string]lyricsCacheEntry
	flights map[string]*lyricsFlight
}

func cloneLyrics(value Lyrics) Lyrics {
	value.Lines = append([]LyricLine(nil), value.Lines...)
	return value
}

func (c *lyricsCache) get(id string) (Lyrics, error, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[id]
	if !ok || !time.Now().Before(entry.expires) {
		if ok {
			delete(c.entries, id)
		}
		return Lyrics{}, nil, false
	}
	return cloneLyrics(entry.lyrics), entry.err, true
}

// begin 保证同一歌曲同时只有一个上游兜底流程；其余请求等待 leader 的结果。
func (c *lyricsCache) begin(id string) (*lyricsFlight, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.flights == nil {
		c.flights = map[string]*lyricsFlight{}
	}
	if flight := c.flights[id]; flight != nil {
		return flight, false
	}
	flight := &lyricsFlight{done: make(chan struct{})}
	c.flights[id] = flight
	return flight, true
}

func (c *lyricsCache) wait(ctx context.Context, flight *lyricsFlight) (Lyrics, error) {
	select {
	case <-ctx.Done():
		return Lyrics{}, ctx.Err()
	case <-flight.done:
		return cloneLyrics(flight.lyrics), flight.err
	}
}

func (c *lyricsCache) finish(id string, flight *lyricsFlight, lyrics Lyrics, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	flight.lyrics, flight.err = cloneLyrics(lyrics), err
	if c.flights[id] == flight {
		delete(c.flights, id)
	}
	// 客户端取消/超时不形成负缓存；其它缺失或上游故障短暂缓存，避免重复放大请求。
	if !errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
		if c.entries == nil {
			c.entries = map[string]lyricsCacheEntry{}
		}
		now := time.Now()
		for key, entry := range c.entries {
			if !now.Before(entry.expires) {
				delete(c.entries, key)
			}
		}
		for len(c.entries) >= lyricsCacheMax {
			oldestKey, oldest := "", time.Time{}
			for key, entry := range c.entries {
				if oldestKey == "" || entry.expires.Before(oldest) {
					oldestKey, oldest = key, entry.expires
				}
			}
			delete(c.entries, oldestKey)
		}
		ttl := lyricsCacheTTL
		if err != nil || len(lyrics.Lines) == 0 {
			ttl = lyricsNegativeCacheTTL
		}
		c.entries[id] = lyricsCacheEntry{lyrics: cloneLyrics(lyrics), err: err, expires: now.Add(ttl)}
	}
	close(flight.done)
}

// LyricsWithFallback 先取本源歌词；缺失或被版权限制时到其他平台按
// 标题/歌手/时长匹配同名曲目再取歌词。匹配低于阈值宁可放弃，不给错歌词。
func (r *Registry) LyricsWithFallback(ctx context.Context, id string) (Lyrics, error) {
	return r.lyricsWithFallback(ctx, id, lyricsFallbackBudget, lyricsSourceTimeout)
}

func (r *Registry) lyricsWithFallback(ctx context.Context, id string, budget, sourceTimeout time.Duration) (Lyrics, error) {
	if cached, err, ok := r.lyricsCache.get(id); ok {
		return cached, err
	}
	flight, leader := r.lyricsCache.begin(id)
	if !leader {
		return r.lyricsCache.wait(ctx, flight)
	}
	lyrics, err := r.fetchLyricsWithFallback(ctx, id, budget, sourceTimeout)
	r.lyricsCache.finish(id, flight, lyrics, err)
	return lyrics, err
}

func (r *Registry) fetchLyricsWithFallback(ctx context.Context, id string, budget, sourceTimeout time.Duration) (Lyrics, error) {
	fallbackCtx, cancelFallback := context.WithTimeout(ctx, budget)
	defer cancelFallback()
	own, ownErr := r.Lyrics(fallbackCtx, id)
	if ownErr == nil && len(own.Lines) > 0 {
		return own, nil
	}
	track, err := r.Track(fallbackCtx, id)
	if err != nil {
		return own, ownErr
	}
	query := strings.TrimSpace(track.Title + " " + track.Artist)
	if query == "" {
		return own, ownErr
	}
	for _, source := range lyricsFallbackOrder {
		if fallbackCtx.Err() != nil {
			break
		}
		if source == track.ProviderID || r.adapters[source] == nil {
			continue
		}
		sourceCtx, cancelSource := context.WithTimeout(fallbackCtx, sourceTimeout)
		result, err := r.fallbackLyrics(sourceCtx, source, track, query)
		cancelSource()
		if err == nil && len(result.Lines) > 0 {
			return result, nil
		}
	}
	return own, ownErr
}

func (r *Registry) fallbackLyrics(ctx context.Context, source string, want model.Track, query string) (Lyrics, error) {
	adapter := r.adapters[source]
	found, err := adapter.Search(ctx, query, "track", 1)
	if err != nil || len(found.Tracks) == 0 {
		return Lyrics{}, ErrNotFound
	}
	var best model.Track
	bestScore := 0
	for _, candidate := range found.Tracks {
		if score := lyricsMatchScore(want, candidate); score > bestScore {
			best, bestScore = candidate, score
		}
	}
	if bestScore < 6 {
		return Lyrics{}, ErrNotFound
	}
	out, err := adapter.Lyrics(ctx, best.ID)
	if err != nil {
		return Lyrics{}, err
	}
	if len(out.Lines) == 0 {
		return Lyrics{}, ErrNotFound
	}
	return out, nil
}

// lyricsMatchScore 以标题为主、歌手与时长加权；阈值由调用方把控。
func lyricsMatchScore(want, candidate model.Track) int {
	wantTitle := normalizeLyricsTitle(want.Title)
	candidateTitle := normalizeLyricsTitle(candidate.Title)
	if wantTitle == "" || candidateTitle == "" {
		return -1
	}
	score := 0
	switch {
	case wantTitle == candidateTitle:
		score += 5
	case strings.Contains(candidateTitle, wantTitle) || strings.Contains(wantTitle, candidateTitle):
		score += 3
	default:
		return -1
	}
	if lyricsArtistOverlap(want.Artist, candidate.Artist) {
		score += 3
	}
	if want.Duration > 0 && candidate.Duration > 0 {
		delta := want.Duration - candidate.Duration
		if delta < 0 {
			delta = -delta
		}
		switch {
		case delta <= 3:
			score += 3
		case delta <= 10:
			score += 1
		default:
			score--
		}
	}
	return score
}

// normalizeLyricsTitle 去掉括号版本后缀与标点空白，只留可比较的主标题。
func normalizeLyricsTitle(raw string) string {
	raw = strings.ToLower(strings.TrimSpace(raw))
	var b strings.Builder
	depth := 0
	for _, r := range raw {
		switch r {
		case '(', '（', '[', '【':
			depth++
		case ')', '）', ']', '】':
			if depth > 0 {
				depth--
			}
		default:
			if depth > 0 {
				continue
			}
			if unicode.IsLetter(r) || unicode.IsNumber(r) {
				b.WriteRune(r)
			}
		}
	}
	return b.String()
}

func lyricsArtistOverlap(want, candidate string) bool {
	want = strings.ToLower(strings.TrimSpace(want))
	candidate = strings.ToLower(candidate)
	if want == "" || candidate == "" {
		return false
	}
	for _, token := range strings.FieldsFunc(want, func(r rune) bool {
		return r == '/' || r == ',' || r == '、' || r == '&' || r == ';' || r == ' ' || r == '，'
	}) {
		if utf8.RuneCountInString(token) >= 2 && strings.Contains(candidate, token) {
			return true
		}
	}
	return false
}

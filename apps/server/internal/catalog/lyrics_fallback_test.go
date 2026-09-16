package catalog

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
)

type lyricsStub struct {
	*WY
	provider   string
	track      model.Track
	candidates []model.Track
	lyrics     map[string]Lyrics
	trackErr   error
	searchErr  error
	searchWait time.Duration
	lyricsWait time.Duration
	searches   atomic.Int32
	fetches    atomic.Int32
}

func (s *lyricsStub) Search(ctx context.Context, _ string, _ string, _ int) (SearchResult, error) {
	s.searches.Add(1)
	if s.searchWait > 0 {
		timer := time.NewTimer(s.searchWait)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return SearchResult{}, ctx.Err()
		case <-timer.C:
		}
	}
	return SearchResult{Tracks: s.candidates}, s.searchErr
}

func (s *lyricsStub) Track(context.Context, string) (model.Track, error) {
	return s.track, s.trackErr
}

func (s *lyricsStub) Lyrics(ctx context.Context, id string) (Lyrics, error) {
	s.fetches.Add(1)
	if s.lyricsWait > 0 {
		timer := time.NewTimer(s.lyricsWait)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return Lyrics{}, ctx.Err()
		case <-timer.C:
		}
	}
	if lyrics, ok := s.lyrics[id]; ok && len(lyrics.Lines) > 0 {
		return lyrics, nil
	}
	return Lyrics{}, ErrNotFound
}

func wantTrack() model.Track {
	return model.Track{ID: "kw:367887397", ProviderID: "kw", Title: "蜗牛", Artist: "周杰伦", Duration: 216}
}

func TestLyricsFallbackUsesMatchedSourceWhenOwnUnavailable(t *testing.T) {
	want := wantTrack()
	own := &lyricsStub{provider: "kw", track: want, lyrics: map[string]Lyrics{}}
	tx := &lyricsStub{
		provider:   "tx",
		candidates: []model.Track{{ID: "tx:003k9yEZ0qxF0R", ProviderID: "tx", Title: "蜗牛", Artist: "周杰伦", Duration: 216}},
		lyrics:     map[string]Lyrics{"tx:003k9yEZ0qxF0R": {Lines: []LyricLine{{Time: 0, Text: "该不该搁下重重的壳"}}, Source: "扣扣"}},
	}
	registry := NewRegistry(map[string]Adapter{"kw": own, "tx": tx})

	got, err := registry.LyricsWithFallback(context.Background(), want.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Lines) != 1 || got.Source != "扣扣" {
		t.Fatalf("fallback lyrics = %+v", got)
	}
	if own.searches.Load() != 0 {
		t.Fatal("own source should not be searched")
	}
	if tx.searches.Load() != 1 || tx.fetches.Load() != 1 {
		t.Fatalf("tx calls search=%d fetch=%d; want 1/1", tx.searches.Load(), tx.fetches.Load())
	}
}

func TestLyricsFallbackRejectsWeakMatches(t *testing.T) {
	want := wantTrack()
	own := &lyricsStub{provider: "kw", track: want, lyrics: map[string]Lyrics{}}
	// 标题仅包含关系且歌手、时长都对不上，不足以认定同一首歌。
	kg := &lyricsStub{
		provider:   "kg",
		candidates: []model.Track{{ID: "kg:other", ProviderID: "kg", Title: "蜗牛与黄鹂鸟", Artist: "银霞", Duration: 163}},
		lyrics:     map[string]Lyrics{"kg:other": {Lines: []LyricLine{{Time: 0, Text: "阿门阿前一棵葡萄树"}}}},
	}
	registry := NewRegistry(map[string]Adapter{"kw": own, "kg": kg})

	_, err := registry.LyricsWithFallback(context.Background(), want.ID)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("weak match err = %v; want ErrNotFound", err)
	}
	if kg.fetches.Load() != 0 {
		t.Fatal("weak candidate must not be fetched")
	}
}

func TestLyricsFallbackCachesMatches(t *testing.T) {
	want := wantTrack()
	own := &lyricsStub{provider: "kw", track: want, lyrics: map[string]Lyrics{}}
	tx := &lyricsStub{
		provider:   "tx",
		candidates: []model.Track{{ID: "tx:1", ProviderID: "tx", Title: "蜗牛 (Live)", Artist: "周杰伦", Duration: 238}},
		lyrics:     map[string]Lyrics{"tx:1": {Lines: []LyricLine{{Time: 0, Text: "line"}}, Source: "扣扣"}},
	}
	registry := NewRegistry(map[string]Adapter{"kw": own, "tx": tx})

	for i := 0; i < 2; i++ {
		if _, err := registry.LyricsWithFallback(context.Background(), want.ID); err != nil {
			t.Fatal(err)
		}
	}
	if tx.searches.Load() != 1 {
		t.Fatalf("tx searched %d times; want 1 (cached)", tx.searches.Load())
	}
}

func TestLyricsFallbackHonorsTotalBudget(t *testing.T) {
	want := wantTrack()
	own := &lyricsStub{provider: "kw", track: want, lyrics: map[string]Lyrics{}}
	tx := &lyricsStub{provider: "tx", searchWait: 200 * time.Millisecond}
	wy := &lyricsStub{provider: "wy", searchWait: 200 * time.Millisecond}
	mg := &lyricsStub{provider: "mg", searchWait: 200 * time.Millisecond}
	registry := NewRegistry(map[string]Adapter{"kw": own, "tx": tx, "wy": wy, "mg": mg})

	started := time.Now()
	_, err := registry.lyricsWithFallback(context.Background(), want.ID, 100*time.Millisecond, 90*time.Millisecond)
	elapsed := time.Since(started)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("fallback error = %v; want ErrNotFound", err)
	}
	if elapsed > 160*time.Millisecond {
		t.Fatalf("fallback exceeded total budget: %v", elapsed)
	}
	if tx.searches.Load() != 1 || wy.searches.Load() != 1 || mg.searches.Load() != 0 {
		t.Fatalf("unexpected fallback calls tx=%d wy=%d mg=%d", tx.searches.Load(), wy.searches.Load(), mg.searches.Load())
	}
}

func TestLyricsFallbackCoalescesConcurrentRequests(t *testing.T) {
	want := wantTrack()
	own := &lyricsStub{provider: "kw", track: want, lyrics: map[string]Lyrics{}}
	tx := &lyricsStub{
		provider:   "tx",
		searchWait: 50 * time.Millisecond,
		candidates: []model.Track{{ID: "tx:1", ProviderID: "tx", Title: "蜗牛", Artist: "周杰伦", Duration: 216}},
		lyrics:     map[string]Lyrics{"tx:1": {Lines: []LyricLine{{Time: 0, Text: "line"}}, Source: "扣扣"}},
	}
	registry := NewRegistry(map[string]Adapter{"kw": own, "tx": tx})

	const callers = 8
	start := make(chan struct{})
	errs := make(chan error, callers)
	var wg sync.WaitGroup
	for range callers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			lyrics, err := registry.LyricsWithFallback(context.Background(), want.ID)
			if err == nil && len(lyrics.Lines) != 1 {
				err = errors.New("missing coalesced lyrics")
			}
			errs <- err
		}()
	}
	close(start)
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	if own.fetches.Load() != 1 || tx.searches.Load() != 1 || tx.fetches.Load() != 1 {
		t.Fatalf("duplicate upstream work own=%d search=%d lyrics=%d", own.fetches.Load(), tx.searches.Load(), tx.fetches.Load())
	}
}

func TestLyricsFallbackNegativeCacheAvoidsRepeatedMisses(t *testing.T) {
	want := wantTrack()
	own := &lyricsStub{provider: "kw", track: want, lyrics: map[string]Lyrics{}}
	tx := &lyricsStub{provider: "tx"}
	registry := NewRegistry(map[string]Adapter{"kw": own, "tx": tx})

	for range 2 {
		if _, err := registry.LyricsWithFallback(context.Background(), want.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("fallback error = %v; want ErrNotFound", err)
		}
	}
	if own.fetches.Load() != 1 || tx.searches.Load() != 1 {
		t.Fatalf("negative result was not cached: own=%d search=%d", own.fetches.Load(), tx.searches.Load())
	}
}

func TestLyricsMatchScore(t *testing.T) {
	want := wantTrack()
	cases := []struct {
		name      string
		candidate model.Track
		ok        bool
	}{
		{"完全一致", model.Track{Title: "蜗牛", Artist: "周杰伦", Duration: 216}, true},
		{"版本后缀但歌手时长一致", model.Track{Title: "蜗牛 (Live)", Artist: "周杰伦", Duration: 238}, true},
		{"标题一致但翻唱不同歌手同时长", model.Track{Title: "蜗牛", Artist: "其他歌手", Duration: 216}, true},
		{"仅标题包含", model.Track{Title: "蜗牛与黄鹂鸟", Artist: "银霞", Duration: 163}, false},
		{"标题无关", model.Track{Title: "稻香", Artist: "周杰伦", Duration: 223}, false},
	}
	for _, tc := range cases {
		if got := lyricsMatchScore(want, tc.candidate); (got >= 6) != tc.ok {
			t.Fatalf("%s: score=%d ok=%v", tc.name, got, tc.ok)
		}
	}
}

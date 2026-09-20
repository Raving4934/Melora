package catalog

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func txOnePlaylist() any {
	return map[string]any{"total": 1, "v_playlist": []any{map[string]any{"tid": 26, "title": "公开推荐", "access_num": 42}}}
}
func expireTXFresh(q *TX) {
	q.playlists.mu.Lock()
	defer q.playlists.mu.Unlock()
	for _, e := range q.playlists.entries {
		e.freshUntil = time.Now().Add(-time.Second)
		e.retryAt = time.Time{}
	}
}
func TestTXPlaylistCacheSuccessTTLBoundsAndClone(t *testing.T) {
	calls := 0
	q := txTestClient(t, func(r txTestRPCRequest) any {
		calls++
		if r.Req.Method == "get_category_content" {
			return map[string]any{"content": map[string]any{"total_cnt": 1, "v_item": []any{map[string]any{"basic": map[string]any{"tid": 26, "title": "公开推荐"}}}}}
		}
		return txOnePlaylist()
	})
	items, err := q.Playlists(t.Context(), "", 1)
	if err != nil || len(items) != 1 {
		t.Fatal("missing success")
	}
	items[0].Title = "caller mutation"
	*items[0].PlayCount = 999
	again, err := q.Playlists(t.Context(), "all", 1)
	if err != nil || again[0].Title == items[0].Title || *again[0].PlayCount != 42 || calls != 1 {
		t.Fatal("cache alias or redundant fetch")
	}
	q.playlists.mu.Lock()
	e := q.playlists.entries["all:1"]
	if time.Until(e.freshUntil) > txPlaylistFreshTTL || time.Until(e.freshUntil) < 4*time.Minute || time.Until(e.expiresAt) > txPlaylistRetainTTL {
		t.Error("unbounded TTL")
	}
	q.playlists.mu.Unlock()
	for i := 1; i <= txPlaylistCacheLimit+5; i++ {
		if _, err := q.Playlists(t.Context(), fmt.Sprintf("tx:category_%d", i), 1); err != nil {
			t.Fatal(err)
		}
	}
	q.playlists.mu.Lock()
	size := len(q.playlists.entries)
	q.playlists.mu.Unlock()
	if size != txPlaylistCacheLimit {
		t.Fatalf("cache size=%d", size)
	}
}
func TestTXPlaylistRefreshFailureRetainsSuccessAndBacksOff(t *testing.T) {
	var calls atomic.Int32
	q := NewTX(txTestDoer(func(*http.Request) (*http.Response, error) {
		if calls.Add(1) == 1 {
			return txTestResponse(txTestEnvelope(txOnePlaylist())), nil
		}
		return txTestResponse(map[string]any{"code": 0, "req": map[string]any{"code": 2001}}), nil
	}))
	if _, err := q.Playlists(t.Context(), "all", 1); err != nil {
		t.Fatal(err)
	}
	expireTXFresh(q)
	var retainedUntil time.Time
	q.playlists.mu.Lock()
	retainedUntil = q.playlists.entries["all:1"].expiresAt
	q.playlists.mu.Unlock()
	for i := 0; i < 5; i++ {
		items, err := q.Playlists(t.Context(), "all", 1)
		if len(items) != 1 || !errors.Is(err, ErrUnavailable) {
			t.Fatal("partial cleared or failure hidden")
		}
	}
	if calls.Load() != 2 {
		t.Fatal("failure request storm")
	}
	q.playlists.mu.Lock()
	e := q.playlists.entries["all:1"]
	if !e.expiresAt.Equal(retainedUntil) || time.Until(e.retryAt) > txPlaylistBackoff {
		t.Error("failure extended success lifetime")
	}
	e.expiresAt = time.Now().Add(-time.Second)
	q.playlists.mu.Unlock()
	items, err := q.Playlists(t.Context(), "all", 1)
	if len(items) != 0 || err == nil || calls.Load() != 3 {
		t.Fatal("served expired stale data")
	}
}
func TestTXPlaylistColdFailureAndValidEmptyAreCached(t *testing.T) {
	for _, empty := range []bool{false, true} {
		t.Run(fmt.Sprint(empty), func(t *testing.T) {
			calls := 0
			q := txTestClient(t, func(txTestRPCRequest) any {
				calls++
				if empty {
					return map[string]any{"total": 0, "v_playlist": []any{}}
				}
				return map[string]any{"total": 1, "v_playlist": []any{}}
			})
			for i := 0; i < 5; i++ {
				got, err := q.Playlists(t.Context(), "all", 1)
				if (err == nil) != empty || len(got) != 0 || (empty && got == nil) {
					t.Fatal("empty/failure conflated")
				}
			}
			if calls != 1 {
				t.Fatal("cold failure retried within backoff")
			}
		})
	}
}
func TestTXPlaylistConcurrentCoalescingAndWaiterCancellation(t *testing.T) {
	var calls atomic.Int32
	started, release := make(chan struct{}), make(chan struct{})
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		if calls.Add(1) == 1 {
			close(started)
		}
		select {
		case <-release:
			return txTestResponse(txTestEnvelope(txOnePlaylist())), nil
		case <-r.Context().Done():
			return nil, r.Context().Err()
		}
	}))
	var wg sync.WaitGroup
	wg.Go(func() {
		if _, err := q.Playlists(t.Context(), "all", 1); err != nil {
			t.Error(err)
		}
	})
	<-started
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Millisecond)
	defer cancel()
	if _, err := q.Playlists(ctx, "all", 1); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("waiter cancellation ignored")
	}
	for i := 0; i < 20; i++ {
		wg.Go(func() {
			if got, err := q.Playlists(t.Context(), "all", 1); err != nil || len(got) != 1 {
				t.Error("concurrent read failed")
			}
		})
	}
	close(release)
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatal("same key not coalesced")
	}
	cancelled, stop := context.WithCancel(t.Context())
	stop()
	if got, err := q.Playlists(cancelled, "all", 1); got != nil || !errors.Is(err, context.Canceled) {
		t.Fatal("cache ignored cancellation")
	}
}
func TestTXPlaylistCancelledLeaderDoesNotPoisonSuccessOrBackoff(t *testing.T) {
	var calls atomic.Int32
	started := make(chan struct{})
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		if calls.Add(1) == 2 {
			close(started)
			<-r.Context().Done()
			return nil, r.Context().Err()
		}
		return txTestResponse(txTestEnvelope(txOnePlaylist())), nil
	}))
	if _, err := q.Playlists(t.Context(), "all", 1); err != nil {
		t.Fatal(err)
	}
	expireTXFresh(q)
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() {
		items, err := q.Playlists(ctx, "all", 1)
		if len(items) != 1 {
			t.Error("cancelled refresh discarded still-valid partial data")
		}
		done <- err
	}()
	<-started
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatal("leader cancellation swallowed")
	}
	q.playlists.mu.Lock()
	retained := len(q.playlists.entries["all:1"].items)
	q.playlists.mu.Unlock()
	if retained != 1 {
		t.Fatal("cancellation cleared success")
	}
	if got, err := q.Playlists(t.Context(), "all", 1); err != nil || len(got) != 1 || calls.Load() != 3 {
		t.Fatal("cancellation cached as failure")
	}
}
func TestTXPlaylistCacheCapsInFlightKeys(t *testing.T) {
	var calls atomic.Int32
	started, release := make(chan struct{}, txPlaylistCacheLimit), make(chan struct{})
	q := NewTX(txTestDoer(func(r *http.Request) (*http.Response, error) {
		calls.Add(1)
		started <- struct{}{}
		select {
		case <-release:
			return txTestResponse(txTestEnvelope(txOnePlaylist())), nil
		case <-r.Context().Done():
			return nil, r.Context().Err()
		}
	}))
	var wg sync.WaitGroup
	for i := 1; i <= txPlaylistCacheLimit; i++ {
		wg.Go(func() {
			if _, err := q.Playlists(t.Context(), "all", i); err != nil {
				t.Error(err)
			}
		})
	}
	for i := 0; i < txPlaylistCacheLimit; i++ {
		<-started
	}
	if _, err := q.Playlists(t.Context(), "all", txPlaylistCacheLimit+1); !errors.Is(err, ErrUnavailable) {
		t.Error("inflight capacity not enforced")
	}
	close(release)
	wg.Wait()
	if calls.Load() != txPlaylistCacheLimit {
		t.Fatal("unbounded concurrent cache keys")
	}
}
func TestTXPlaylistObservedCoverVariants(t *testing.T) {
	for _, field := range []string{"cover_url_big", "cover_url_small"} {
		t.Run(field, func(t *testing.T) {
			q := txTestClient(t, func(txTestRPCRequest) any {
				return map[string]any{"total": 1, "v_playlist": []any{map[string]any{"tid": 26, "title": "公开歌单", "cover_url_medium": "https://evil.invalid/image.jpg", field: "https://qpic.y.qq.com/cover.jpg"}}}
			})
			got, err := q.Playlists(t.Context(), "all", 1)
			if err != nil || len(got) != 1 || got[0].CoverURL != "https://qpic.y.qq.com/cover.jpg" {
				t.Fatal("cover field compatibility failed")
			}
		})
	}
}

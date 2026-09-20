package catalog

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
)

type registryStub struct {
	*WY
	prefix string
	fail   error
	active *atomic.Int32
	peak   *atomic.Int32
	delay  time.Duration
	calls  atomic.Int32
}

func (s *registryStub) Charts(ctx context.Context) ([]model.Collection, error) {
	s.calls.Add(1)
	if s.active != nil {
		n := s.active.Add(1)
		defer s.active.Add(-1)
		for {
			p := s.peak.Load()
			if n <= p || s.peak.CompareAndSwap(p, n) {
				break
			}
		}
	}
	if s.delay > 0 {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(s.delay):
		}
	}
	if s.fail != nil {
		return nil, s.fail
	}
	return []model.Collection{{ID: s.prefix + ":chart_1", ProviderID: s.prefix, Title: s.prefix}}, nil
}
func (s *registryStub) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	return s.Charts(ctx)
}
func (s *registryStub) PlaylistCategories(context.Context) ([]PlaylistCategory, error) {
	return []PlaylistCategory{{ID: s.prefix + "-tag", Name: s.prefix, Group: "测试"}}, nil
}
func (s *registryStub) Track(context.Context, string) (model.Track, error) {
	return model.Track{ID: s.prefix + ":song", ProviderID: s.prefix}, s.fail
}
func (s *registryStub) MusicInfo(context.Context, string) (map[string]any, error) {
	return map[string]any{"source": s.prefix, "songmid": "song"}, s.fail
}
func (s *registryStub) Search(ctx context.Context, q, kind string, page int) (SearchResult, error) {
	s.calls.Add(1)
	if s.fail != nil {
		return SearchResult{}, s.fail
	}
	return SearchResult{Tracks: []model.Track{{ID: s.prefix + ":song", ProviderID: s.prefix}}, Total: 20, Page: page, PageSize: 20}, nil
}
func TestRegistryBookSearchRoutesAllToKuwoCatalog(t *testing.T) {
	kw := &registryStub{prefix: "kw"}
	wy := &registryStub{prefix: "wy"}
	r := NewRegistry(map[string]Adapter{"kw": kw, "wy": wy})
	result, err := r.SearchFor(t.Context(), "all", "盗墓笔记", "book", 1)
	if err != nil {
		t.Fatal(err)
	}
	if result.Total != 20 || kw.calls.Load() != 1 || wy.calls.Load() != 0 {
		t.Fatalf("book search did not route to kw only: total=%d kw=%d wy=%d", result.Total, kw.calls.Load(), wy.calls.Load())
	}
	if _, err = r.SearchFor(t.Context(), "all", "query", "mv", 1); !errors.Is(err, ErrInput) {
		t.Fatalf("unknown kind must stay invalid, got %v", err)
	}
}
func TestRegistryAggregatesPartialFailuresInPlatformOrder(t *testing.T) {
	r := NewRegistry(map[string]Adapter{"mg": &registryStub{prefix: "mg", fail: ErrUnavailable}, "tx": &registryStub{prefix: "tx"}, "wy": &registryStub{prefix: "wy"}, "kw": &registryStub{prefix: "kw", fail: ErrUnavailable}, "kg": &registryStub{prefix: "kg"}})
	items, err := r.ChartsFor(t.Context(), "all")
	if len(items) != 3 || items[0].ProviderID != "wy" || items[1].ProviderID != "tx" || items[2].ProviderID != "kg" {
		t.Fatal(items, err)
	}
	failed, partial := IsPartial(err)
	if !partial || len(failed) != 2 || failed[0] != "kw" || failed[1] != "mg" {
		t.Fatal(failed, err)
	}
	result, err := r.SearchFor(t.Context(), "all", "test", "track", 2)
	if len(result.Tracks) != 3 || result.Total != 60 || result.PageSize != 60 || result.Page != 2 {
		t.Fatal(result, err)
	}
	if !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	for _, a := range r.adapters {
		a.(*registryStub).fail = ErrUnavailable
	}
	items, err = r.ChartsFor(t.Context(), "all")
	if len(items) != 0 || !errors.Is(err, ErrUnavailable) {
		t.Fatal("all failures became fake empty success", items, err)
	}
}
func TestRegistrySourceAndIdentityBoundaries(t *testing.T) {
	wy := &registryStub{prefix: "wy"}
	tx := &registryStub{prefix: "tx"}
	r := NewRegistry(map[string]Adapter{"wy": wy, "tx": tx})
	items, err := r.ChartsFor(t.Context(), "tx")
	if err != nil || len(items) != 1 || items[0].ProviderID != "tx" || wy.calls.Load() != 0 {
		t.Fatal(items, err)
	}
	track, err := r.Track(t.Context(), "tx:song")
	if err != nil || track.ProviderID != "tx" {
		t.Fatal(track, err)
	}
	info, err := r.MusicInfo(t.Context(), "tx:song")
	if err != nil || info["source"] != "tx" {
		t.Fatal(info, err)
	}
	for _, id := range []string{"demo:song", "tx:", "wy:../etc", "https://example.com", "unknown:song"} {
		if _, err = r.Track(t.Context(), id); err == nil {
			t.Fatal("invalid identity accepted", id)
		}
	}
	if _, err = r.ChartsFor(t.Context(), "__proto__"); !errors.Is(err, ErrInput) {
		t.Fatal(err)
	}
	categories, err := r.CategoriesFor(t.Context(), "all")
	if err != nil || len(categories) != 0 {
		t.Fatal("all platforms inherited one native category list", categories, err)
	}
	categories, err = r.CategoriesFor(t.Context(), "tx")
	if err != nil || categories[0].ID != "tx-tag" {
		t.Fatal(categories, err)
	}
	if _, err = r.PlaylistsFor(t.Context(), "all", "WY分类", 1); !errors.Is(err, ErrInput) {
		t.Fatal("foreign category forwarded", err)
	}
	if _, err = r.SearchFor(t.Context(), "all", "query", "invalid", 1); !errors.Is(err, ErrInput) {
		t.Fatal(err)
	}
}
func TestRegistryFanoutIsBoundedAndCancellationWorks(t *testing.T) {
	var active, peak atomic.Int32
	adapters := map[string]Adapter{}
	for _, id := range PlatformOrder {
		adapters[id] = &registryStub{prefix: id, active: &active, peak: &peak, delay: 10 * time.Millisecond}
	}
	r := NewRegistry(adapters)
	items, err := r.ChartsFor(t.Context(), "all")
	if err != nil || len(items) != 5 || peak.Load() > 3 {
		t.Fatal(items, err, peak.Load())
	}
	for _, a := range adapters {
		a.(*registryStub).delay = time.Second
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	start := time.Now()
	_, err = r.ChartsFor(ctx, "all")
	if err == nil || time.Since(start) > 200*time.Millisecond {
		t.Fatal("cancellation ignored", err)
	}
}

func TestRecommendationPageDoesNotInventNextPage(t *testing.T) {
	items := make([]model.Collection, 30)
	for i := range items {
		items[i].ProviderID = "mg"
	}
	r := NewRegistry(map[string]Adapter{"mg": NewMG(nil)})
	if r.PlaylistsHaveMore("all", 1, items) {
		t.Fatal("non-paged migu recommendation showed next page")
	}
	if !r.PlaylistsHaveMore("mg:category_x", 1, items) {
		t.Fatal("category pagination lost")
	}
}

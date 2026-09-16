package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"melora/internal/catalog"
	"melora/internal/model"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type recommendationFixture struct{ platformFixture }

func (f *recommendationFixture) NewTracks(context.Context, string) ([]model.Track, error) {
	if f.fail {
		return nil, catalog.ErrUnavailable
	}
	return []model.Track{{ID: f.id + ":fresh", ProviderID: f.id, Title: "公开新歌", Artist: "另一位歌手"}}, nil
}
func (f *recommendationFixture) Search(_ context.Context, q, kind string, page int) (catalog.SearchResult, error) {
	if f.fail {
		return catalog.SearchResult{}, catalog.ErrUnavailable
	}
	return catalog.SearchResult{Tracks: []model.Track{{ID: f.id + ":related", ProviderID: f.id, Title: "同歌手新作品", Artist: q}, {ID: f.id + ":wrong", ProviderID: f.id, Title: "不应假称相关", Artist: "无关歌手"}}, Total: 2, PageSize: 20}, nil
}
func setupRecommendations(t *testing.T) (*Server, map[string]catalog.Adapter) {
	s, _, _ := liveSetup(t, "")
	adapters := map[string]catalog.Adapter{}
	for _, id := range catalog.PlatformOrder {
		adapters[id] = &recommendationFixture{platformFixture: platformFixture{id: id}}
	}
	s.live.Catalog = catalog.NewRegistry(adapters)
	return s, adapters
}
func TestDiscoveryNoHistoryUsesRealPublicRecommendations(t *testing.T) {
	s, _ := setupRecommendations(t)
	w := request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	assertStatus(t, w, 200)
	var feed discoveryFeed
	if json.Unmarshal(w.Body.Bytes(), &feed) != nil {
		t.Fatal(w.Body.String())
	}
	if feed.Personalized || feed.Reason != "公开音乐推荐" || len(feed.Tracks) != 2 || len(feed.Playlists) != 5 {
		t.Fatal(feed)
	}
	for i, id := range catalog.PlatformOrder {
		if feed.Playlists[i].ProviderID != id {
			t.Fatal("platforms not interleaved", feed.Playlists)
		}
	}
	if strings.Contains(w.Body.String(), `"providerId":"demo"`) {
		t.Fatal("demo fallback")
	}
}
func TestDiscoveryUsesActualFavoritesAndDoesNotPretendUnrelatedSearchIsPersonalized(t *testing.T) {
	s, _ := setupRecommendations(t)
	if err := s.store.FavoriteTrack(t.Context(), model.Track{ID: "wy:old", ProviderID: "wy", Title: "听过的曲目", Artist: "喜欢的歌手"}, true); err != nil {
		t.Fatal(err)
	}
	w := request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	assertStatus(t, w, 200)
	var feed discoveryFeed
	json.Unmarshal(w.Body.Bytes(), &feed)
	if !feed.Personalized || len(feed.Tracks) != 3 || feed.Tracks[0].Title != "同歌手新作品" {
		t.Fatal(feed)
	}
	for _, track := range feed.Tracks {
		if track.ID == "wy:wrong" || track.ID == "wy:old" {
			t.Fatal("unrelated or heard track recommended", track)
		}
	}
}
func TestDiscoveryPartialFailureKeepsOtherPlatformsAndAllFailureErrors(t *testing.T) {
	s, adapters := setupRecommendations(t)
	adapters["tx"].(*recommendationFixture).fail = true
	w := request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	assertStatus(t, w, 200)
	if w.Header().Get("X-Melora-Unavailable-Sources") != "tx" {
		t.Fatal(w.Header())
	}
	var feed discoveryFeed
	json.Unmarshal(w.Body.Bytes(), &feed)
	if len(feed.Playlists) != 4 || len(feed.Tracks) == 0 {
		t.Fatal(feed)
	}
	for _, adapter := range adapters {
		adapter.(*recommendationFixture).fail = true
	}
	w = request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	assertStatus(t, w, 502)
}
func TestRecommendationFilterKeepsVersionsAndArtistIdentityDistinct(t *testing.T) {
	seed := discoverySeed{name: "歌手", source: "wy"}
	related := []model.Track{{ID: "wy:1", Title: "新曲", Artist: "歌手"}, {ID: "wy:2", Title: "新曲", Artist: "歌手"}, {ID: "wy:3", Title: "新曲Live", Artist: "歌手"}, {ID: "wy:4", Title: "新曲", Artist: "歌手B"}}
	tracks, personal := selectRecommendedTracks(related, nil, nil, &seed)
	if !personal || len(tracks) != 2 || tracks[1].Title != "新曲Live" {
		t.Fatal(tracks)
	}
}

// 独立替身，避免更改其他代理共用的 platformFixture。
type scopedRecommendationFixture struct {
	recommendationFixture
	search func(context.Context, string) (catalog.SearchResult, error)
	fresh  func(context.Context) ([]model.Track, error)
	lists  func(context.Context) ([]model.Collection, error)
	detail func(context.Context) (model.Collection, error)
}

func (f *scopedRecommendationFixture) Search(ctx context.Context, q, kind string, page int) (catalog.SearchResult, error) {
	if f.search != nil {
		return f.search(ctx, q)
	}
	return f.recommendationFixture.Search(ctx, q, kind, page)
}
func (f *scopedRecommendationFixture) NewTracks(ctx context.Context, _ string) ([]model.Track, error) {
	if f.fresh != nil {
		return f.fresh(ctx)
	}
	return f.recommendationFixture.NewTracks(ctx, "all")
}
func (f *scopedRecommendationFixture) Playlists(ctx context.Context, _ string, _ int) ([]model.Collection, error) {
	if f.lists != nil {
		return f.lists(ctx)
	}
	return f.recommendationFixture.Playlists(ctx, "all", 1)
}
func (f *scopedRecommendationFixture) Playlist(ctx context.Context, id string) (model.Collection, error) {
	if f.detail != nil {
		return f.detail(ctx)
	}
	return f.recommendationFixture.Playlist(ctx, id)
}
func scopedRecommendations(t *testing.T) (*Server, map[string]*scopedRecommendationFixture) {
	t.Helper()
	s, _, _ := liveSetup(t, "")
	adapters := map[string]catalog.Adapter{}
	fixtures := map[string]*scopedRecommendationFixture{}
	for _, id := range catalog.PlatformOrder {
		f := &scopedRecommendationFixture{recommendationFixture: recommendationFixture{platformFixture: platformFixture{id: id}}}
		adapters[id], fixtures[id] = f, f
	}
	s.live.Catalog = catalog.NewRegistry(adapters)
	return s, fixtures
}
func discoveryTestFeed(t *testing.T, s *Server) discoveryFeed {
	t.Helper()
	w := request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	assertStatus(t, w, 200)
	var feed discoveryFeed
	if err := json.Unmarshal(w.Body.Bytes(), &feed); err != nil {
		t.Fatal(err)
	}
	return feed
}
func discoveryFavorite(t *testing.T, s *Server, artist, source string) {
	t.Helper()
	if err := s.store.FavoriteTrack(t.Context(), model.Track{ID: source + ":old-" + artist, ProviderID: source, Title: "已收藏", Artist: artist}, true); err != nil {
		t.Fatal(err)
	}
}
func TestRecommendationHealthyResponseCacheAndInvalidation(t *testing.T) {
	s, fixtures := scopedRecommendations(t)
	var freshCalls atomic.Int32
	fixtures["wy"].fresh = func(context.Context) ([]model.Track, error) {
		freshCalls.Add(1)
		return []model.Track{{ID: "wy:cached", ProviderID: "wy", Title: "缓存新歌", Artist: "公开歌手"}}, nil
	}
	first := request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	second := request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	assertStatus(t, first, http.StatusOK)
	assertStatus(t, second, http.StatusOK)
	if freshCalls.Load() != 1 || first.Body.String() != second.Body.String() {
		t.Fatalf("healthy feed was not cached: calls=%d", freshCalls.Load())
	}
	// batch 是显式探索维度，必须拥有独立缓存键。
	assertStatus(t, request(s, "GET", "/api/v1/recommendations/daily?batch=1", nil, nil), http.StatusOK)
	if freshCalls.Load() != 2 {
		t.Fatalf("batch cache key collision: calls=%d", freshCalls.Load())
	}
	// 任意画像写入都应切换缓存代际；删除不存在收藏也属于已确认的用户动作。
	assertStatus(t, request(s, "DELETE", "/api/v1/library/favorites/tracks/tx:missing", nil, nil), http.StatusOK)
	assertStatus(t, request(s, "GET", "/api/v1/recommendations/daily", nil, nil), http.StatusOK)
	if freshCalls.Load() != 3 {
		t.Fatalf("profile mutation did not invalidate cache: calls=%d", freshCalls.Load())
	}
}

func TestRecommendationConcurrentRequestsShareOneBuild(t *testing.T) {
	s, fixtures := scopedRecommendations(t)
	var calls atomic.Int32
	var once sync.Once
	started := make(chan struct{})
	release := make(chan struct{})
	fixtures["wy"].fresh = func(ctx context.Context) ([]model.Track, error) {
		calls.Add(1)
		once.Do(func() { close(started) })
		select {
		case <-release:
			return []model.Track{{ID: "wy:shared", ProviderID: "wy", Title: "共享构建", Artist: "公开歌手"}}, nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	const parallel = 6
	responses := make(chan *httptest.ResponseRecorder, parallel)
	for range parallel {
		go func() {
			w := httptest.NewRecorder()
			s.liveRecommendations(w, httptest.NewRequest("GET", "/api/v1/recommendations/daily", nil))
			responses <- w
		}()
	}
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("recommendation build did not start")
	}
	close(release)
	for range parallel {
		select {
		case response := <-responses:
			assertStatus(t, response, http.StatusOK)
		case <-time.After(2 * time.Second):
			t.Fatal("coalesced request did not finish")
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("concurrent requests started %d builds", calls.Load())
	}
}

func TestRecommendationListenedDepthAndSkipsAffectRanking(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	entry := model.HistoryEntry{
		Track:    model.Track{ID: "tx:depth", ProviderID: "tx", Artist: "深听歌手", Duration: 100},
		PlayedAt: now.UnixNano(), PlayCount: 2,
	}
	low := buildPreferenceProfile(nil, []model.HistoryEntry{{Track: entry.Track, PlayedAt: entry.PlayedAt, PlayCount: entry.PlayCount, ListenedMs: 20_000}}, nil, now, 0)
	high := buildPreferenceProfile(nil, []model.HistoryEntry{{Track: entry.Track, PlayedAt: entry.PlayedAt, PlayCount: entry.PlayCount, ListenedMs: 180_000}}, nil, now, 0)
	artist := normalizedMusicName(entry.Track.Artist)
	if high.artistScores[artist] <= low.artistScores[artist] {
		t.Fatalf("listened depth did not strengthen preference: low=%f high=%f", low.artistScores[artist], high.artistScores[artist])
	}

	profile := preferenceProfile{
		seeds:        []scoredSeed{{name: "保留歌手", source: "tx", score: 1}},
		artistScores: map[string]float64{"保留歌手": 1, "高跳过歌手": 1},
		artistPlays:  map[string]int{"保留歌手": 10, "高跳过歌手": 10},
		artistSkips:  map[string]int{"保留歌手": 0, "高跳过歌手": 10},
	}
	kept := candidateScore(profile, model.Track{ProviderID: "tx", Artist: "保留歌手"}, 0, 1)
	skipped := candidateScore(profile, model.Track{ProviderID: "tx", Artist: "高跳过歌手"}, 0, 1)
	if skipped >= kept {
		t.Fatalf("skip signal did not demote candidate: kept=%f skipped=%f", kept, skipped)
	}
}

func TestRecommendationWorkerPanicIsScopedFailure(t *testing.T) {
	s, fixtures := scopedRecommendations(t)
	fixtures["wy"].fresh = func(context.Context) ([]model.Track, error) {
		panic("adapter bug must not terminate the server")
	}
	feed := discoveryTestFeed(t, s)
	if len(feed.Playlists) == 0 {
		t.Fatal("one worker panic discarded healthy platform results")
	}
	found := false
	for _, issue := range feed.Issues {
		if issue.SourceID == "wy" && issue.Scope == "new-tracks" && issue.Code == "upstream_unavailable" {
			found = true
		}
	}
	if !found {
		t.Fatalf("worker panic was not converted to scoped issue: %+v", feed.Issues)
	}
}

func TestDiscoveryTXSearchSuccessPlaylistFailureIsScoped(t *testing.T) {
	s, f := scopedRecommendations(t)
	discoveryFavorite(t, s, "真实歌手", "tx")
	f["tx"].lists = func(context.Context) ([]model.Collection, error) { return nil, catalog.ErrUnavailable }
	// 未请求的 TX 新歌不得被探测，也不得报为失败。
	f["tx"].fresh = func(context.Context) ([]model.Track, error) {
		t.Error("TX new tracks was not requested by feed")
		return nil, catalog.ErrUnsupported
	}
	feed := discoveryTestFeed(t, s)
	if !feed.Personalized || len(feed.Issues) != 1 || feed.Issues[0] != (discoveryIssue{SourceID: "tx", Scope: "playlists", Code: "upstream_unavailable"}) {
		t.Fatal(feed.Issues, feed.Personalized)
	}
	if len(feed.UnavailableSources) != 1 || feed.UnavailableSources[0] != "tx" {
		t.Fatal("legacy compatibility lost")
	}
	found := false
	for _, track := range feed.Tracks {
		if track.ProviderID == "tx" {
			found = true
		}
	}
	if !found || strings.Contains(feed.Reason, "不可用") {
		t.Fatal("TX search success discarded or whole platform condemned")
	}
}
func TestDiscoveryKeepsPartialPlaylistsAndFreshTracks(t *testing.T) {
	s, f := scopedRecommendations(t)
	f["tx"].lists = func(context.Context) ([]model.Collection, error) {
		return []model.Collection{{ID: "tx:playlist_26", ProviderID: "tx", Title: "真实缓存推荐"}}, catalog.ErrUnavailable
	}
	f["wy"].fresh = func(context.Context) ([]model.Track, error) {
		return []model.Track{{ID: "wy:partial", ProviderID: "wy", Title: "已取得新歌", Artist: "公开歌手"}}, context.DeadlineExceeded
	}
	feed := discoveryTestFeed(t, s)
	if len(feed.Playlists) != 5 || len(feed.Tracks) < 1 || feed.Tracks[0].ID != "wy:partial" {
		t.Fatal("partial cleared existing data")
	}
	expected := map[discoveryIssue]bool{{SourceID: "wy", Scope: "new-tracks", Code: "timeout"}: true, {SourceID: "tx", Scope: "playlists", Code: "upstream_unavailable"}: true}
	if len(feed.Issues) != 2 {
		t.Fatal(feed.Issues)
	}
	for _, issue := range feed.Issues {
		if !expected[issue] {
			t.Fatal(issue)
		}
	}
}
func TestDiscoveryUsesAtMostThreeSeedsAndPublicFill(t *testing.T) {
	s, f := scopedRecommendations(t)
	names := []string{"歌手甲", "歌手乙", "歌手丙"}
	for _, name := range names {
		discoveryFavorite(t, s, name, "tx")
	}
	var mu sync.Mutex
	queries := []string{}
	f["tx"].search = func(_ context.Context, name string) (catalog.SearchResult, error) {
		mu.Lock()
		queries = append(queries, name)
		mu.Unlock()
		tracks := []model.Track{}
		for i := 0; i < 24; i++ {
			tracks = append(tracks, model.Track{ID: fmt.Sprintf("tx:%s-%d", name, i), ProviderID: "tx", Title: fmt.Sprintf("相关作品%d", i), Artist: name})
		}
		return catalog.SearchResult{Tracks: tracks, Total: 24, PageSize: 24}, nil
	}
	f["wy"].fresh = func(context.Context) ([]model.Track, error) {
		tracks := []model.Track{}
		for i := 0; i < 18; i++ {
			tracks = append(tracks, model.Track{ID: fmt.Sprintf("wy:public-%d", i), ProviderID: "wy", Title: fmt.Sprintf("公开作品%d", i), Artist: fmt.Sprintf("其他歌手%d", i)})
		}
		return tracks, nil
	}
	feed := discoveryTestFeed(t, s)
	if len(queries) != 3 || len(feed.Tracks) != 18 || !feed.Personalized || len(feed.Issues) != 0 {
		t.Fatal("seed/public feed failed", len(queries), len(feed.Tracks), feed.Issues)
	}
	counts := map[string]int{}
	for index, track := range feed.Tracks {
		counts[track.Artist]++
		// V2：前 10 首同歌手 ≤2，整体同歌手 ≤4。
		if index < 10 && counts[track.Artist] > 2 {
			t.Fatalf("前 10 首同歌手超过 2 首: %s at %d", track.Artist, index)
		}
		if counts[track.Artist] > 4 {
			t.Fatalf("同歌手超过 4 首: %s", track.Artist)
		}
	}
	txCount := 0
	for _, track := range feed.Tracks {
		if track.ProviderID == "tx" {
			txCount++
		}
	}
	if txCount > 7 {
		t.Fatalf("个性化候选单平台超过 40%%: %d", txCount)
	}
	for _, name := range names {
		if counts[name] == 0 || counts[name] > 4 {
			t.Fatalf("seed %s count=%d", name, counts[name])
		}
	}
}
func TestRecommendationDiversityVersionsKnownIDsAndCollaborations(t *testing.T) {
	seed := discoverySeed{name: "真实歌手", source: "tx"}
	related := []model.Track{}
	for i := 0; i < 24; i++ {
		related = append(related, model.Track{ID: fmt.Sprintf("tx:%d", i), Title: fmt.Sprintf("作品%d", i), Artist: seed.name})
	}
	fresh := []model.Track{}
	for i := 0; i < 18; i++ {
		fresh = append(fresh, model.Track{ID: fmt.Sprintf("wy:%d", i), Title: fmt.Sprintf("公开%d", i), Artist: fmt.Sprintf("歌手%d", i)})
	}
	out, personal := selectRecommendedTracks(related, fresh, nil, &seed)
	count := 0
	for _, track := range out {
		if track.Artist == seed.name {
			count++
		}
	}
	if len(out) != 18 || !personal || count != discoveryArtistLimit {
		t.Fatal("single artist monopolized target feed")
	}
	known := []model.Track{{ID: "tx:known", Title: "代表作", Artist: seed.name}}
	versions := []model.Track{
		{ID: "tx:version1", Title: "代表作 (Live)", Artist: seed.name},
		{ID: "tx:version2", Title: "代表作（现场版）", Artist: seed.name},
		{ID: "tx:version3", Title: "代表作 - Remastered 2024", Artist: seed.name},
		{ID: "tx:known", Title: "元数据标题变更", Artist: seed.name},
		{ID: "tx:real", Title: "独立作品（第一章）", Artist: seed.name},
	}
	out, personal = selectRecommendedTracks(versions, nil, known, &seed)
	if len(out) != 1 || out[0].ID != "tx:real" || !personal {
		t.Fatal("known/version filtering failed", out)
	}
	// 合作歌手顺序不应形成重复；不同真实歌手的同名作品可共存。
	tracks := []model.Track{{ID: "tx:1", Title: "歌", Artist: "甲 / 乙"}, {ID: "tx:2", Title: "歌", Artist: "乙 / 甲"}, {ID: "wy:1", Title: "歌", Artist: "丙"}}
	out, personal = selectRecommendedTracks(nil, tracks, nil, nil)
	if len(out) != 2 || personal {
		t.Fatal("artist identity/version dedup wrong")
	}
}
func TestDiscoveryPublicPlaylistFillDoesNotReintroduceKnownOrPretendPersonal(t *testing.T) {
	s, f := scopedRecommendations(t)
	discoveryFavorite(t, s, "真实歌手", "tx")
	f["tx"].search = func(context.Context, string) (catalog.SearchResult, error) {
		return catalog.SearchResult{Tracks: []model.Track{{ID: "tx:wrong", Title: "相关词但不同歌手", Artist: "其他歌手"}}}, nil
	}
	// 多平台召回的后备搜索也必须只返回无关歌手，接口不得假称个性化。
	for id, fixture := range f {
		if id == "tx" {
			continue
		}
		fixture.search = func(context.Context, string) (catalog.SearchResult, error) {
			return catalog.SearchResult{Tracks: []model.Track{{ID: id + ":wrong", Title: "后备平台也无关", Artist: "无关歌手"}}}, nil
		}
	}
	f["wy"].fresh = func(context.Context) ([]model.Track, error) { return nil, catalog.ErrUnsupported }
	f["wy"].detail = func(context.Context) (model.Collection, error) {
		return model.Collection{Tracks: []model.Track{{ID: "tx:heard-live", Title: "已收藏 (Live)", Artist: "真实歌手"}, {ID: "wy:public", ProviderID: "wy", Title: "公开补位", Artist: "另一歌手"}}}, nil
	}
	feed := discoveryTestFeed(t, s)
	if len(feed.Tracks) != 1 || feed.Tracks[0].ID != "wy:public" || feed.Personalized || feed.Reason != "公开音乐推荐" {
		t.Fatal("fill fabricated personalization or reintroduced known music")
	}
	if len(feed.Issues) != 1 || feed.Issues[0].Scope != "new-tracks" || feed.Issues[0].Code != "unsupported" {
		t.Fatal(feed.Issues)
	}
}
func TestDiscoverySeedFailureKeepsOtherSeedAndDeduplicatesIssues(t *testing.T) {
	s, f := scopedRecommendations(t)
	for _, name := range []string{"甲", "乙", "丙"} {
		discoveryFavorite(t, s, name, "tx")
	}
	f["tx"].search = func(_ context.Context, name string) (catalog.SearchResult, error) {
		if name != "丙" {
			return catalog.SearchResult{}, catalog.ErrUnavailable
		}
		return catalog.SearchResult{Tracks: []model.Track{{ID: "tx:good", Title: "真实相关作品", Artist: name}}, PageSize: 24}, nil
	}
	feed := discoveryTestFeed(t, s)
	if !feed.Personalized || len(feed.Issues) != 1 || feed.Issues[0].Scope != "tracks" {
		t.Fatal("one failure cleared other seeds or duplicated issues")
	}
}
func TestDiscoveryFetchConcurrencyAndCancellationAreBounded(t *testing.T) {
	s, f := scopedRecommendations(t)
	for _, name := range []string{"甲", "乙", "丙"} {
		discoveryFavorite(t, s, name, "tx")
	}
	var active, peak atomic.Int32
	started := make(chan struct{}, 10)
	block := func(ctx context.Context) error {
		n := active.Add(1)
		defer active.Add(-1)
		for old := peak.Load(); n > old && !peak.CompareAndSwap(old, n); old = peak.Load() {
		}
		if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > 8*time.Second {
			t.Error("missing fetch deadline")
		}
		started <- struct{}{}
		<-ctx.Done()
		return ctx.Err()
	}
	for _, fixture := range f {
		fixture.fresh = func(ctx context.Context) ([]model.Track, error) { return nil, block(ctx) }
		fixture.search = func(ctx context.Context, _ string) (catalog.SearchResult, error) {
			return catalog.SearchResult{}, block(ctx)
		}
		fixture.lists = func(ctx context.Context) ([]model.Collection, error) { return nil, block(ctx) }
	}
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	req := httptest.NewRequest("GET", "/api/v1/recommendations/daily", nil).WithContext(ctx)
	w := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { s.liveRecommendations(w, req); close(done) }()
	for i := 0; i < 3; i++ {
		select {
		case <-started:
		case <-time.After(time.Second):
			t.Fatal("fetches failed to start")
		}
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("request cancellation did not stop fetches")
	}
	if peak.Load() != 3 || active.Load() != 0 {
		t.Fatal("unbounded concurrency or leaked work")
	}
}

type unsafeDiscoveryError struct{}

func (unsafeDiscoveryError) Error() string            { return "do-not-expose-business-text" }
func (unsafeDiscoveryError) CatalogIssueCode() string { return "do-not-expose-business-text" }
func TestDiscoveryIssueCodesAreSafeAndErrorsWrapped(t *testing.T) {
	for _, tc := range []struct {
		err  error
		code string
	}{
		{fmt.Errorf("wrapped: %w", context.Canceled), "cancelled"},
		{fmt.Errorf("wrapped: %w", context.DeadlineExceeded), "timeout"},
		{fmt.Errorf("wrapped: %w", catalog.ErrUnsupported), "unsupported"},
		{unsafeDiscoveryError{}, "upstream_unavailable"},
		{errors.New("do-not-expose-business-text"), "upstream_unavailable"},
	} {
		if got := discoveryIssueCode(tc.err); got != tc.code {
			t.Fatal(got)
		}
	}
}

func TestRecommendationSeedsAreRealUniqueAndBounded(t *testing.T) {
	favorites := []model.Track{
		{ProviderID: "tx", Artist: "甲 / 甲"},
		{ProviderID: "tx", Artist: "乙"},
		{ProviderID: "wy", Artist: "丙"},
		{ProviderID: "wy", Artist: "丁"},
		{ProviderID: "demo", Artist: "不能作为真实目录 seed"},
	}
	for _, name := range []string{"", "未知歌手", "Various Artists", "群星", "名字\n控制符"} {
		for i := 0; i < 5; i++ {
			favorites = append(favorites, model.Track{ProviderID: "tx", Artist: name})
		}
	}
	seeds := recommendationSeeds(favorites, []model.Track{{ProviderID: "wy", Artist: "丙"}})
	if len(seeds) != 3 || seeds[0].name != "丙" || seeds[0].weight != 5 {
		t.Fatal("invalid weighting or unbounded seeds", seeds)
	}
	for _, seed := range seeds {
		if seed.name != "丙" && seed.weight != 3 {
			t.Fatal("duplicate credits overcounted", seeds)
		}
	}
}
func TestRecommendationKnownRelatedCannotClaimPersonalization(t *testing.T) {
	seed := discoverySeed{name: "歌手", source: "tx"}
	known := []model.Track{{ID: "tx:known", Title: "听过的歌", Artist: seed.name}}
	related := []model.Track{{ID: "tx:live", Title: "听过的歌 (Live)", Artist: seed.name}}
	fresh := []model.Track{{ID: "wy:public", Title: "公开作品", Artist: "其他歌手"}}
	out, personal := selectRecommendedTracks(related, fresh, known, &seed)
	if len(out) != 1 || personal {
		t.Fatal("filtered related tracks falsely claimed personalization")
	}
}
func TestDiscoveryHealthyFeedOmitsOptionalIssues(t *testing.T) {
	s, _ := setupRecommendations(t)
	w := request(s, "GET", "/api/v1/recommendations/daily", nil, nil)
	assertStatus(t, w, 200)
	if strings.Contains(w.Body.String(), `"issues"`) || strings.Contains(w.Body.String(), `"unavailableSources"`) || w.Header().Get("X-Melora-Unavailable-Sources") != "" {
		t.Fatal("healthy response changed optional field contract")
	}
}
func TestDiscoveryPartialDetailPreservesEarlierSelection(t *testing.T) {
	s, f := scopedRecommendations(t)
	discoveryFavorite(t, s, "歌手", "tx")
	f["wy"].detail = func(context.Context) (model.Collection, error) {
		return model.Collection{Tracks: []model.Track{{ID: "wy:partial-detail", ProviderID: "wy", Title: "已获得公开曲目", Artist: "补位歌手"}}}, context.DeadlineExceeded
	}
	feed := discoveryTestFeed(t, s)
	found := map[string]bool{}
	for _, track := range feed.Tracks {
		found[track.ID] = true
	}
	if !found["tx:related"] || !found["wy:fresh"] || !found["wy:partial-detail"] || !feed.Personalized {
		t.Fatal("partial detail cleared earlier feed")
	}
	if len(feed.Issues) != 1 || feed.Issues[0] != (discoveryIssue{SourceID: "wy", Scope: "tracks", Code: "timeout"}) {
		t.Fatal(feed.Issues)
	}
}

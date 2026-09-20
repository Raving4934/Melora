package api

import (
	"context"
	"errors"
	"fmt"
	"melora/internal/catalog"
	"melora/internal/model"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
)

type discoveryFeed struct {
	Tracks             []model.Track      `json:"tracks"`
	Playlists          []model.Collection `json:"playlists"`
	Personalized       bool               `json:"personalized"`
	Reason             string             `json:"reason"`
	Issues             []discoveryIssue   `json:"issues,omitempty"`
	UnavailableSources []string           `json:"unavailableSources,omitempty"`
}

// scope 只表示本次实际请求失败的能力，不是整个平台健康状态。
type discoveryIssue struct {
	SourceID string `json:"sourceId"`
	Scope    string `json:"scope"`
	Code     string `json:"code"`
}

func discoveryIssueCode(err error) string {
	switch {
	case errors.Is(err, context.Canceled):
		return "cancelled"
	case errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, catalog.ErrUnsupported):
		return "unsupported"
	}
	var coded interface{ CatalogIssueCode() string }
	if errors.As(err, &coded) {
		// 不把任意适配器错误文本或远端业务原文带给客户端。
		switch code := coded.CatalogIssueCode(); code {
		case "invalid_response", "upstream_rejected", "access_restricted", "rate_limited", "timeout":
			return code
		}
	}
	return "upstream_unavailable"
}

type discoverySeed struct {
	name, source string
	weight       int
}

func normalizedMusicName(value string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) || unicode.IsPunct(r) {
			return -1
		}
		return unicode.ToLower(r)
	}, value)
}
func recommendationSeeds(favorites, history []model.Track) []discoverySeed {
	scores := map[string]discoverySeed{}
	add := func(track model.Track, weight int) {
		if catalog.PlatformNames[track.ProviderID] == "" {
			return
		}
		credited := map[string]bool{}
		for _, artist := range strings.Split(track.Artist, " / ") {
			name := strings.TrimSpace(artist)
			key := normalizedMusicName(name)
			if key == "" || len([]rune(name)) > 80 || strings.IndexFunc(name, unicode.IsControl) >= 0 || credited[key] {
				continue
			}
			switch key {
			case "未知歌手", "未知艺术家", "unknownartist", "variousartists", "群星":
				continue
			}
			credited[key] = true
			seed := scores[key]
			if seed.name == "" {
				seed.name = name
				seed.source = track.ProviderID
			}
			seed.weight += weight
			scores[key] = seed
		}
	}
	for _, track := range favorites[:min(len(favorites), 80)] {
		add(track, 3)
	}
	for _, track := range history[:min(len(history), 40)] {
		add(track, 2)
	}
	out := []discoverySeed{}
	for _, seed := range scores {
		out = append(out, seed)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].weight == out[j].weight {
			return out[i].name < out[j].name
		}
		return out[i].weight > out[j].weight
	})
	return out[:min(len(out), 3)]
}

const discoveryTrackLimit = 18
const discoveryArtistLimit = 6

// 只折叠有明确分隔符的版本后缀，不删除歌曲名中有语义的括号或相似歌手名。
var discoveryVersionSuffix = regexp.MustCompile(`(?i)(?:\s*[-–—]\s*|\s*[（(\[【])(?:live|现场(?:版)?|演唱会(?:版)?|伴奏(?:版)?|instrumental|remix|remastered|remaster|重制(?:版)?|acoustic|不插电(?:版)?|demo)(?:[\s:：-][^()（）\[\]【】]*)?[）)\]】]?\s*$`)

func recommendationArtists(artist string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, name := range strings.Split(artist, " / ") {
		key := normalizedMusicName(name)
		if key != "" && !seen[key] {
			out = append(out, key)
			seen[key] = true
		}
	}
	sort.Strings(out)
	return out
}
func recommendationTrackKey(track model.Track) string {
	title := strings.TrimSpace(track.Title)
	for i := 0; i < 3; i++ {
		shorter := strings.TrimSpace(discoveryVersionSuffix.ReplaceAllString(title, ""))
		if shorter == "" || shorter == title {
			break
		}
		title = shorter
	}
	return normalizedMusicName(title) + "|" + strings.Join(recommendationArtists(track.Artist), "/")
}
func recommendationMatches(track model.Track, seed discoverySeed) bool {
	for _, artist := range recommendationArtists(track.Artist) {
		if artist == normalizedMusicName(seed.name) {
			return true
		}
	}
	return false
}

// 保留旧的单 seed 选择入口；实际发现页使用下方多 seed 轮转。
func selectRecommendedTracks(related, fresh, known []model.Track, seed *discoverySeed) ([]model.Track, bool) {
	if seed == nil {
		return selectDiverseRecommendations(nil, fresh, known, nil)
	}
	return selectDiverseRecommendations([][]model.Track{related}, fresh, known, []discoverySeed{*seed})
}
func selectDiverseRecommendations(related [][]model.Track, fresh, known []model.Track, seeds []discoverySeed) ([]model.Track, bool) {
	seen, ids := map[string]bool{}, map[string]bool{}
	for _, track := range known {
		seen[recommendationTrackKey(track)] = true
		ids[track.ID] = true
	}
	out := []model.Track{}
	counts := map[string]int{}
	personalized := false
	add := func(track model.Track, personal bool) bool {
		key := recommendationTrackKey(track)
		if track.ID == "" || strings.TrimSpace(track.Title) == "" || ids[track.ID] || seen[key] || len(out) >= discoveryTrackLimit {
			return false
		}
		artists := recommendationArtists(track.Artist)
		if len(artists) == 0 {
			artists = []string{""}
		}
		for _, artist := range artists {
			if counts[artist] >= discoveryArtistLimit {
				return false
			}
		}
		seen[key], ids[track.ID] = true, true
		for _, artist := range artists {
			counts[artist]++
		}
		out = append(out, track)
		personalized = personalized || personal
		return true
	}
	// 每轮每位真实 seed 至多取一首；至少为公开补位留出 6 个位置。
	n := min(len(seeds), len(related), 3)
	offsets := make([]int, n)
	for len(out) < discoveryTrackLimit-discoveryArtistLimit {
		added := false
		for i := 0; i < n && len(out) < discoveryTrackLimit-discoveryArtistLimit; i++ {
			for offsets[i] < len(related[i]) {
				track := related[i][offsets[i]]
				offsets[i]++
				if recommendationMatches(track, seeds[i]) && add(track, true) {
					added = true
					break
				}
			}
		}
		if !added {
			break
		}
	}
	for _, track := range fresh {
		add(track, false)
	}
	return out, personalized
}
func interleavePlaylists(items []model.Collection, limit int) []model.Collection {
	groups := map[string][]model.Collection{}
	for _, item := range items {
		groups[item.ProviderID] = append(groups[item.ProviderID], item)
	}
	out := []model.Collection{}
	seen := map[string]bool{}
	for row := 0; len(out) < limit; row++ {
		found := false
		for _, source := range catalog.PlatformOrder {
			list := groups[source]
			if row >= len(list) {
				continue
			}
			found = true
			item := list[row]
			if !seen[item.ID] {
				out = append(out, item)
				seen[item.ID] = true
			}
			if len(out) == limit {
				break
			}
		}
		if !found {
			break
		}
	}
	return out
}
func (s *Server) buildLiveRecommendations(w http.ResponseWriter, r *http.Request, batch int) {
	favorites, err := s.store.FavoriteTracksWithTime(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	historyEntries, err := s.store.History(r.Context(), "")
	if err != nil {
		s.dbError(w, err)
		return
	}
	favoritePlaylists, err := s.store.FavoritePlaylistsWithTime(r.Context())
	if err != nil {
		s.dbError(w, err)
		return
	}
	profile := buildPreferenceProfile(favorites, historyEntries, favoritePlaylists, time.Now(), batch)
	seeds := profile.seeds
	known := make([]model.Track, 0, len(favorites)+len(historyEntries))
	for _, item := range favorites {
		known = append(known, item.Track)
	}
	for _, entry := range historyEntries {
		if entry.Kind != model.HistoryKindAudiobook {
			known = append(known, entry.Track)
		}
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	// 共享 8 秒取数预算、最多 3 个并发，给公开歌单补位保留最多 3 秒。
	fetchCtx, stopFetch := context.WithTimeout(ctx, 8*time.Second)
	defer stopFetch()
	sources := s.live.Catalog.IDs()
	type result struct {
		source, scope string
		tracks        []model.Track
		playlists     []model.Collection
		err           error
	}
	results := make([]result, 1+len(seeds)+len(sources))
	results[0] = result{source: "wy", scope: "new-tracks"}
	for i, seed := range seeds {
		results[1+i] = result{source: seed.source, scope: "tracks"}
	}
	for i, source := range sources {
		results[1+len(seeds)+i] = result{source: source, scope: "playlists"}
	}
	var wg sync.WaitGroup
	sem := make(chan struct{}, 3)
	run := func(part *result, work func() error) {
		defer wg.Done()
		defer func() {
			if recover() == nil {
				return
			}
			part.err = fmt.Errorf("recommendation worker panic")
			if s.logger != nil {
				s.logger.Error("recommendation worker panic", "source", part.source, "scope", part.scope)
			}
		}()
		select {
		case <-fetchCtx.Done():
			part.err = fetchCtx.Err()
			return
		case sem <- struct{}{}:
			defer func() { <-sem }()
		}
		if fetchCtx.Err() != nil {
			part.err = fetchCtx.Err()
			return
		}
		part.err = work()
	}
	for i := range results {
		wg.Add(1)
		part := &results[i]
		if part.scope == "new-tracks" {
			go run(part, func() error {
				part.tracks, part.err = s.live.Catalog.NewTracksFor(fetchCtx, part.source, "all")
				return part.err
			})
			continue
		}
		if part.scope == "tracks" {
			seed := seeds[i-1]
			go run(part, func() error {
				found, e := s.live.Catalog.SearchFor(fetchCtx, part.source, seed.name, "track", 1)
				part.tracks, part.err = found.Tracks, e
				return part.err
			})
			continue
		}
		go run(part, func() error {
			// Registry 的 fanout 会丢弃带 err 的结果；这里保留真实缓存/partial 数据及各自错误。
			adapter, e := s.live.Catalog.Adapter(part.source)
			if e != nil {
				return e
			}
			part.playlists, part.err = adapter.Playlists(fetchCtx, "all", 1)
			return part.err
		})
	}
	wg.Wait()
	related := make([][]model.Track, len(seeds))
	for i := range seeds {
		related[i] = results[1+i].tracks
	}
	issues := []discoveryIssue{}
	issueSeen := map[discoveryIssue]bool{}
	addIssue := func(source, scope string, err error) {
		if err == nil {
			return
		}
		issue := discoveryIssue{SourceID: source, Scope: scope, Code: discoveryIssueCode(err)}
		if !issueSeen[issue] {
			issues = append(issues, issue)
			issueSeen[issue] = true
		}
	}
	// 多平台召回：主平台搜索失败或命中不足时，最多补一个后备平台的搜索。
	type fallbackTask struct {
		seedIndex int
		source    string
	}
	fallbacks := []fallbackTask{}
	if fetchCtx.Err() == nil {
		for i, seed := range seeds {
			matched := 0
			for _, track := range related[i] {
				if recommendationMatches(track, discoverySeed{name: seed.name, source: seed.source}) {
					matched++
				}
			}
			if matched >= 4 {
				continue
			}
			fallback := fallbackPlatform(seed.source)
			if fallback == seed.source || catalog.PlatformNames[fallback] == "" {
				continue
			}
			fallbacks = append(fallbacks, fallbackTask{seedIndex: i, source: fallback})
		}
	}
	if len(fallbacks) > 0 {
		fallbackResults := make([]result, len(fallbacks))
		for i, task := range fallbacks {
			fallbackResults[i] = result{source: task.source, scope: "tracks"}
			wg.Add(1)
			part := &fallbackResults[i]
			seed := seeds[task.seedIndex]
			go run(part, func() error {
				found, e := s.live.Catalog.SearchFor(fetchCtx, part.source, seed.name, "track", 1)
				part.tracks, part.err = found.Tracks, e
				return part.err
			})
		}
		wg.Wait()
		for i, task := range fallbacks {
			addIssue(fallbackResults[i].source, "tracks", fallbackResults[i].err)
			related[task.seedIndex] = append(related[task.seedIndex], fallbackResults[i].tracks...)
		}
	}
	stopFetch()
	fresh := results[0].tracks
	playlists := []model.Collection{}
	for _, part := range results {
		playlists = append(playlists, part.playlists...)
		addIssue(part.source, part.scope, part.err)
	}
	tracks, personalized := selectDiscoveryV2(profile, related, fresh, nil, known)
	// 不足时至多读取一张已取得的真实公开歌单；不把热门曲目假称新歌或个性化。
	if len(tracks) < discoveryTrackLimit && len(playlists) > 0 && ctx.Err() == nil {
		fillCtx, stopFill := context.WithTimeout(ctx, 3*time.Second)
		item, e := s.live.Catalog.Playlist(fillCtx, playlists[0].ID)
		stopFill()
		addIssue(playlists[0].ProviderID, "tracks", e)
		// 即便详情返回 partial 也不清空之前的新歌/个性化数据；继续排除已听曲目。
		if len(item.Tracks) > 0 {
			tracks, personalized = selectDiscoveryV2(profile, related, fresh, item.Tracks, known)
		}
	}
	if len(tracks) == 0 && len(playlists) == 0 {
		s.liveError(w, catalog.ErrUnavailable)
		return
	}
	// 排序让并发完成顺序不影响 UI；旧字段仍是有失败能力的来源集合，不表示整站故障。
	sort.SliceStable(issues, func(i, j int) bool {
		if issues[i].SourceID != issues[j].SourceID {
			for _, source := range catalog.PlatformOrder {
				if issues[i].SourceID == source {
					return true
				}
				if issues[j].SourceID == source {
					return false
				}
			}
		}
		if issues[i].Scope != issues[j].Scope {
			return issues[i].Scope < issues[j].Scope
		}
		return issues[i].Code < issues[j].Code
	})
	failed := map[string]bool{}
	for _, issue := range issues {
		failed[issue.SourceID] = true
	}
	unavailable := []string{}
	for _, source := range catalog.PlatformOrder {
		if failed[source] {
			unavailable = append(unavailable, source)
		}
	}
	reason := "公开音乐推荐"
	if personalized {
		reason = "根据最近播放和收藏的歌手推荐"
	}
	if len(unavailable) > 0 {
		w.Header().Set("X-Melora-Unavailable-Sources", strings.Join(unavailable, ","))
	}
	writeJSON(w, 200, discoveryFeed{Tracks: s.live.EnrichTracks(tracks), Playlists: rankDiscoveryPlaylists(playlists, profile.categories, 12), Personalized: personalized, Reason: reason, Issues: issues, UnavailableSources: unavailable})
}

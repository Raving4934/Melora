package api

import (
	"context"
	"fmt"
	"testing"
	"time"

	"melora/internal/catalog"
	"melora/internal/model"
	"melora/internal/store"
)

func timedFavorite(track model.Track, ageDays float64) store.TimedTrack {
	return store.TimedTrack{Track: track, At: time.Now().Add(-time.Duration(ageDays * 24 * float64(time.Hour))).UnixNano()}
}

func historyEntry(track model.Track, ageDays float64, plays, completed, skips int) model.HistoryEntry {
	return model.HistoryEntry{
		Track:          track,
		Kind:           model.HistoryKindTrack,
		PlayedAt:       time.Now().Add(-time.Duration(ageDays * 24 * float64(time.Hour))).UnixNano(),
		PlayCount:      plays,
		CompletedCount: completed,
		SkipCount:      skips,
	}
}

func TestPreferenceProfileDecayStrengthAndSeeds(t *testing.T) {
	favorites := []store.TimedTrack{
		timedFavorite(model.Track{ID: "tx:new", ProviderID: "tx", Artist: "最近收藏"}, 1),
		timedFavorite(model.Track{ID: "tx:old", ProviderID: "tx", Artist: "很久收藏"}, 900),
	}
	history := []model.HistoryEntry{
		historyEntry(model.Track{ID: "wy:loop", ProviderID: "wy", Artist: "循环歌手"}, 2, 20, 18, 0),
		historyEntry(model.Track{ID: "wy:once", ProviderID: "wy", Artist: "偶尔歌手"}, 2, 1, 0, 1),
		historyEntry(model.Track{ID: "wy:stale", ProviderID: "wy", Artist: "过期歌手"}, 400, 50, 50, 0),
	}
	profile := buildPreferenceProfile(favorites, history, nil, time.Now(), 0)
	if len(profile.seeds) != 3 {
		t.Fatalf("seeds %+v", profile.seeds)
	}
	score := func(name string) float64 { return profile.artistScores[normalizedMusicName(name)] }
	if score("最近收藏") <= score("很久收藏") {
		t.Fatal("收藏未按时间衰减")
	}
	// 半衰期 120 天：900 天前的收藏衰减到约 1/185，但保留 35% 权重下限。
	floor := 3 * 0.35
	if got := score("很久收藏"); got < floor-0.01 || got > 3.0 {
		t.Fatalf("收藏权重下限失效: %f", got)
	}
	if score("循环歌手") <= score("偶尔歌手") {
		t.Fatal("重复播放未提升偏好强度")
	}
	if score("偶尔歌手") <= score("过期歌手") {
		t.Fatal("播放未按时间衰减或跳过未惩罚")
	}
	found := false
	for _, seed := range profile.seeds {
		if seed.name == "循环歌手" {
			found = true
		}
	}
	if !found {
		t.Fatalf("高播放强度歌手未进入种子: %+v", profile.seeds)
	}
}

func TestSelectDiscoveryV2CapsAndMmr(t *testing.T) {
	profile := preferenceProfile{
		seeds: []scoredSeed{
			{name: "甲", source: "tx", score: 10},
			{name: "乙", source: "tx", score: 9},
			{name: "丙", source: "tx", score: 8},
		},
		artistScores: map[string]float64{"甲": 10, "乙": 9, "丙": 8},
		artistPlays:  map[string]int{},
		artistSkips:  map[string]int{},
		categories:   map[string]float64{},
	}
	related := [][]model.Track{}
	for _, name := range []string{"甲", "乙", "丙"} {
		tracks := []model.Track{}
		for i := 0; i < 24; i++ {
			tracks = append(tracks, model.Track{ID: name + string(rune('a'+i)), ProviderID: "tx", Title: name + "作品" + string(rune('a'+i)), Artist: name, Album: name + "专辑" + string(rune('a'+i))})
		}
		related = append(related, tracks)
	}
	fresh := []model.Track{}
	for i := 0; i < 18; i++ {
		fresh = append(fresh, model.Track{ID: "wy:f" + string(rune('a'+i)), ProviderID: "wy", Title: "公开" + string(rune('a'+i)), Artist: "公开歌手" + string(rune('a'+i))})
	}
	out, personalized := selectDiscoveryV2(profile, related, fresh, nil, nil)
	if len(out) != discoveryTrackLimit || !personalized {
		t.Fatalf("out=%d personalized=%v", len(out), personalized)
	}
	artistCounts := map[string]int{}
	platforms := map[string]int{}
	for index, track := range out {
		artistCounts[track.Artist]++
		platforms[track.ProviderID]++
		if index < discoveryFrontWindow && artistCounts[track.Artist] > discoveryFrontArtistCap {
			t.Fatalf("前 10 首同歌手超过 %d: %s", discoveryFrontArtistCap, track.Artist)
		}
		if artistCounts[track.Artist] > discoveryArtistCap {
			t.Fatalf("同歌手超过 %d: %s", discoveryArtistCap, track.Artist)
		}
	}
	if platforms["tx"] > discoveryPersonalPlatforms {
		t.Fatalf("个性化单平台超过 40%%: %d", platforms["tx"])
	}
	for _, name := range []string{"甲", "乙", "丙"} {
		if artistCounts[name] == 0 {
			t.Fatalf("种子 %s 被完全排除", name)
		}
	}
}

func TestSelectDiscoveryV2ExplorationIsDeterministicPerBatch(t *testing.T) {
	profile := preferenceProfile{
		artistScores: map[string]float64{},
		artistPlays:  map[string]int{},
		artistSkips:  map[string]int{},
		categories:   map[string]float64{},
		day:          1234,
		batch:        0,
	}
	fresh := []model.Track{}
	for i := 0; i < 12; i++ {
		fresh = append(fresh, model.Track{ID: "wy:" + string(rune('a'+i)), ProviderID: "wy", Title: "公开" + string(rune('a'+i)), Artist: "歌手" + string(rune('a'+i))})
	}
	first, _ := selectDiscoveryV2(profile, nil, fresh, nil, nil)
	again, _ := selectDiscoveryV2(profile, nil, fresh, nil, nil)
	if len(first) != len(again) {
		t.Fatal("同批次结果长度不一致")
	}
	for i := range first {
		if first[i].ID != again[i].ID {
			t.Fatal("同批次结果不确定")
		}
	}
	profile.batch = 1
	other, _ := selectDiscoveryV2(profile, nil, fresh, nil, nil)
	same := len(first) == len(other)
	if same {
		for i := range first {
			if first[i].ID != other[i].ID {
				same = false
				break
			}
		}
	}
	if same {
		t.Fatal("换一批没有改变探索序列")
	}
}

func TestRankDiscoveryPlaylistsPrefersFavoriteCategory(t *testing.T) {
	items := []model.Collection{
		{ID: "wy:1", ProviderID: "wy", Category: "流行"},
		{ID: "wy:2", ProviderID: "wy", Category: "摇滚"},
		{ID: "tx:1", ProviderID: "tx", Category: "流行"},
		{ID: "tx:2", ProviderID: "tx", Category: "电子"},
	}
	ranked := rankDiscoveryPlaylists(items, map[string]float64{"摇滚": 5}, 4)
	if len(ranked) != 4 {
		t.Fatalf("ranked %+v", ranked)
	}
	// 平台轮询保持：首两首仍是 wy、tx；wy 内偏好分类摇滚优先。
	if ranked[0].ID != "wy:2" || ranked[1].ProviderID != "tx" {
		t.Fatalf("分类偏好或平台轮询失效: %+v", ranked)
	}
}

func TestDiscoveryFallsBackToAnotherPlatformWhenPrimaryFails(t *testing.T) {
	s, f := scopedRecommendations(t)
	discoveryFavorite(t, s, "后备歌手", "tx")
	f["tx"].search = func(context.Context, string) (catalog.SearchResult, error) {
		return catalog.SearchResult{}, catalog.ErrUnavailable
	}
	fallback := fallbackPlatform("tx")
	f[fallback].search = func(_ context.Context, name string) (catalog.SearchResult, error) {
		return catalog.SearchResult{Tracks: []model.Track{{ID: fallback + ":match", ProviderID: fallback, Title: "后备平台真作品", Artist: name}}}, nil
	}
	feed := discoveryTestFeed(t, s)
	if !feed.Personalized {
		t.Fatalf("后备平台召回未生效: %+v", feed)
	}
	found := false
	for _, track := range feed.Tracks {
		if track.ID == fallback+":match" {
			found = true
		}
	}
	if !found {
		t.Fatalf("后备平台命中未进入结果: %+v", feed.Tracks)
	}
}

func TestPreferenceProfileExcludesAudiobookSignals(t *testing.T) {
	now := time.Now()
	history := []model.HistoryEntry{
		historyEntry(model.Track{ID: "wy:music", ProviderID: "wy", Artist: "音乐歌手"}, 1, 3, 2, 0),
		{
			Track:     model.Track{ID: "kw:book", ProviderID: "kw", Artist: "听书演播者"},
			Kind:      model.HistoryKindAudiobook,
			PlayedAt:  now.Add(-time.Hour).UnixNano(),
			PlayCount: 20,
		},
	}
	playlists := []store.TimedCollection{
		{Collection: model.Collection{ID: "wy:playlist_1", Category: "摇滚"}, At: now.UnixNano()},
		{Collection: model.Collection{ID: "kw:book_album_1", Category: "有声专辑"}, At: now.UnixNano()},
	}
	profile := buildPreferenceProfile(nil, history, playlists, now, 0)
	if profile.artistScores[normalizedMusicName("听书演播者")] != 0 {
		t.Fatalf("听书演播者污染音乐画像: %+v", profile.artistScores)
	}
	if profile.categories["有声专辑"] != 0 {
		t.Fatalf("听书分类污染音乐歌单画像: %+v", profile.categories)
	}
	if profile.artistScores[normalizedMusicName("音乐歌手")] == 0 || profile.categories["摇滚"] == 0 {
		t.Fatalf("音乐信号被错误过滤: artists=%+v categories=%+v", profile.artistScores, profile.categories)
	}
}

func TestPreferenceProfileAppliesLimitsAfterExcludingAudiobooks(t *testing.T) {
	now := time.Now()
	history := make([]model.HistoryEntry, 0, 41)
	for i := 0; i < 40; i++ {
		history = append(history, model.HistoryEntry{
			Track:     model.Track{ID: fmt.Sprintf("kw:book-%d", i), ProviderID: "kw", Artist: "听书演播者"},
			Kind:      model.HistoryKindAudiobook,
			PlayedAt:  now.Add(-time.Duration(i) * time.Minute).UnixNano(),
			PlayCount: 1,
		})
	}
	history = append(history, historyEntry(
		model.Track{ID: "wy:music-after-books", ProviderID: "wy", Artist: "有效音乐歌手"},
		1, 1, 0, 0,
	))
	playlists := make([]store.TimedCollection, 0, 81)
	for i := 0; i < 80; i++ {
		playlists = append(playlists, store.TimedCollection{
			Collection: model.Collection{ID: fmt.Sprintf("kw:book_album_%d", i), Category: "有声专辑"},
			At:         now.UnixNano(),
		})
	}
	playlists = append(playlists, store.TimedCollection{
		Collection: model.Collection{ID: "wy:playlist_music", Category: "民谣"},
		At:         now.UnixNano(),
	})

	profile := buildPreferenceProfile(nil, history, playlists, now, 0)
	if profile.artistScores[normalizedMusicName("有效音乐歌手")] == 0 {
		t.Fatalf("听书记录占满窗口后，有效音乐历史不应被截断: %+v", profile.artistScores)
	}
	if profile.categories["民谣"] == 0 {
		t.Fatalf("听书收藏占满窗口后，有效音乐分类不应被截断: %+v", profile.categories)
	}
}

func TestSelectDiscoveryV2CanReachTwelvePersonalTracks(t *testing.T) {
	profile := preferenceProfile{
		seeds: []scoredSeed{
			{name: "甲", source: "tx", score: 10},
			{name: "乙", source: "tx", score: 9},
			{name: "丙", source: "tx", score: 8},
		},
		artistScores: map[string]float64{"甲": 10, "乙": 9, "丙": 8},
		artistPlays:  map[string]int{},
		artistSkips:  map[string]int{},
		categories:   map[string]float64{},
		day:          1234,
	}
	related := make([][]model.Track, 3)
	for i, artist := range []string{"甲", "乙", "丙"} {
		for n := 0; n < 4; n++ {
			source := "tx"
			if n%2 == 1 {
				source = "wy"
			}
			related[i] = append(related[i], model.Track{
				ID: source + ":personal-" + artist + string(rune('a'+n)), ProviderID: source,
				Title: artist + "作品" + string(rune('a'+n)), Artist: artist,
				Album: artist + "专辑" + string(rune('a'+n)),
			})
		}
	}
	fresh := make([]model.Track, 0, 12)
	for i := 0; i < 12; i++ {
		fresh = append(fresh, model.Track{
			ID: "kw:public-" + string(rune('a'+i)), ProviderID: "kw",
			Title: "公开" + string(rune('a'+i)), Artist: "公开歌手" + string(rune('a'+i)),
		})
	}
	out, personalized := selectDiscoveryV2(profile, related, fresh, nil, nil)
	if len(out) != discoveryTrackLimit || !personalized {
		t.Fatalf("out=%d personalized=%v", len(out), personalized)
	}
	personalCount := 0
	artistCounts := map[string]int{}
	personalPlatforms := map[string]int{}
	for index, track := range out {
		if track.Artist == "甲" || track.Artist == "乙" || track.Artist == "丙" {
			personalCount++
			personalPlatforms[track.ProviderID]++
		}
		artistCounts[track.Artist]++
		if index < discoveryFrontWindow && artistCounts[track.Artist] > discoveryFrontArtistCap {
			t.Fatalf("前十首歌手上限失效: index=%d track=%+v", index, track)
		}
	}
	if personalCount != discoveryPersonalLimit {
		t.Fatalf("个性化配额不可达: got %d want %d; out=%+v", personalCount, discoveryPersonalLimit, out)
	}
	for source, count := range personalPlatforms {
		if count > discoveryPersonalPlatforms {
			t.Fatalf("实际候选平台 %s 超过上限: %d", source, count)
		}
	}
}

func TestSelectDiscoveryV2UsesDynamicMaxSimilarity(t *testing.T) {
	profile := preferenceProfile{
		artistScores: map[string]float64{},
		artistPlays:  map[string]int{},
		artistSkips:  map[string]int{},
		categories:   map[string]float64{},
		day:          99,
	}
	fresh := []model.Track{
		{ID: "wy:first", ProviderID: "wy", Title: "第一首", Artist: "同一歌手", Album: "同一专辑"},
		{ID: "wy:similar", ProviderID: "wy", Title: "相似版本", Artist: "同一歌手", Album: "同一专辑"},
		{ID: "tx:diverse", ProviderID: "tx", Title: "不同作品", Artist: "不同歌手", Album: "不同专辑"},
		{ID: "kw:other", ProviderID: "kw", Title: "其它作品", Artist: "其它歌手", Album: "其它专辑"},
	}
	out, _ := selectDiscoveryV2(profile, nil, fresh, nil, nil)
	if len(out) < 2 || out[0].ID != "wy:first" || out[1].ID == "wy:similar" {
		t.Fatalf("MMR 未压低与首项高度相似的候选: %+v", out)
	}
}

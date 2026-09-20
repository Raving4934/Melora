package api

import (
	"math"
	"math/rand"
	"sort"
	"strings"
	"time"

	"melora/internal/catalog"
	"melora/internal/model"
	"melora/internal/store"
)

// 推荐 V2 硬限制：前 10 首同歌手 ≤2、整体同歌手 ≤4、同专辑 ≤2、个性化候选单平台 ≤40%。
const (
	discoveryPersonalLimit     = 12
	discoveryArtistCap         = 4
	discoveryFrontArtistCap    = 2
	discoveryFrontWindow       = 10
	discoveryAlbumCap          = 2
	discoveryPersonalPlatforms = 7
	discoveryExploreCount      = 2
)

const (
	favoriteHalfLifeSeconds = 120 * 24 * 60 * 60
	playHalfLifeSeconds     = 14 * 24 * 60 * 60
	categoryHalfLifeSeconds = 120 * 24 * 60 * 60
)

type scoredSeed struct {
	name   string
	source string
	score  float64
}

// preferenceProfile 是本地行为画像：歌手偏好、分类偏好与播放强度，不含任何远端账号数据。
type preferenceProfile struct {
	seeds        []scoredSeed
	artistScores map[string]float64
	artistPlays  map[string]int
	artistSkips  map[string]int
	categories   map[string]float64
	day          int64
	batch        int
}

// decayFactor 使用半衰期指数衰减；未来时间戳按 0 龄处理。
func decayFactor(ageSeconds, halfLifeSeconds float64) float64 {
	if ageSeconds <= 0 {
		return 1
	}
	return math.Pow(0.5, ageSeconds/halfLifeSeconds)
}

func playStrength(playCount int) float64 {
	if playCount <= 1 {
		return 1
	}
	return 1 + math.Min(1.5, 0.5*math.Log2(float64(playCount)))
}

func ratio(part, total int) float64 {
	if total <= 0 || part <= 0 {
		return 0
	}
	return math.Min(1, float64(part)/float64(total))
}

func buildPreferenceProfile(
	favorites []store.TimedTrack,
	history []model.HistoryEntry,
	playlists []store.TimedCollection,
	now time.Time,
	batch int,
) preferenceProfile {
	profile := preferenceProfile{
		artistScores: map[string]float64{},
		artistPlays:  map[string]int{},
		artistSkips:  map[string]int{},
		categories:   map[string]float64{},
		day:          now.Unix() / 86400,
		batch:        batch,
	}
	musicHistory := make([]model.HistoryEntry, 0, min(len(history), 40))
	for _, entry := range history {
		if entry.Kind == model.HistoryKindAudiobook {
			continue
		}
		musicHistory = append(musicHistory, entry)
		if len(musicHistory) == 40 {
			break
		}
	}
	musicPlaylists := make([]store.TimedCollection, 0, min(len(playlists), 80))
	for _, item := range playlists {
		if catalog.IsBookAlbumID(item.Collection.ID) {
			continue
		}
		musicPlaylists = append(musicPlaylists, item)
		if len(musicPlaylists) == 80 {
			break
		}
	}
	addArtists := func(artist string, weight float64) {
		credited := map[string]bool{}
		for _, name := range strings.Split(artist, " / ") {
			name = strings.TrimSpace(name)
			key := normalizedMusicName(name)
			if key == "" || len([]rune(name)) > 80 || strings.IndexFunc(name, func(r rune) bool { return r < 0x20 }) >= 0 || credited[key] {
				continue
			}
			switch key {
			case "未知歌手", "未知艺术家", "unknownartist", "variousartists", "群星":
				continue
			}
			credited[key] = true
			profile.artistScores[key] += weight
		}
	}
	for _, item := range favorites[:min(len(favorites), 80)] {
		age := now.Sub(time.Unix(0, item.At)).Seconds()
		// 主动收藏保留权重下限，避免很久以前的收藏被完全遗忘。
		addArtists(item.Track.Artist, 3*math.Max(0.35, decayFactor(age, favoriteHalfLifeSeconds)))
	}
	for _, entry := range musicHistory {
		age := now.Sub(time.Unix(0, entry.PlayedAt)).Seconds()
		plays := max(entry.PlayCount, 1)
		strength := playStrength(plays)
		skipPenalty := 1 - 0.6*ratio(entry.SkipCount, plays)
		completionBonus := 1 + 0.25*ratio(entry.CompletedCount, plays)
		listeningDepth := 1.0
		if entry.ListenedMs > 0 && entry.Track.Duration > 0 {
			expectedMs := int64(entry.Track.Duration) * 1000 * int64(plays)
			depth := math.Min(1, float64(entry.ListenedMs)/float64(expectedMs))
			// 仅在已有 outcome 信号时轻量修正，旧数据缺失 listened_ms 不被误判为低兴趣。
			listeningDepth = 0.85 + 0.3*depth
		}
		addArtists(entry.Track.Artist, 2*decayFactor(age, playHalfLifeSeconds)*strength*skipPenalty*completionBonus*listeningDepth)
		credited := map[string]bool{}
		for _, name := range strings.Split(entry.Track.Artist, " / ") {
			key := normalizedMusicName(strings.TrimSpace(name))
			if key != "" && !credited[key] {
				credited[key] = true
				profile.artistPlays[key] += max(entry.PlayCount, 1)
				profile.artistSkips[key] += entry.SkipCount
			}
		}
	}
	for _, item := range musicPlaylists {
		category := strings.TrimSpace(item.Collection.Category)
		if category == "" {
			continue
		}
		age := now.Sub(time.Unix(0, item.At)).Seconds()
		profile.categories[category] += 2 * math.Max(0.4, decayFactor(age, categoryHalfLifeSeconds))
	}
	type ranked struct {
		name  string
		score float64
	}
	rankedSeeds := make([]ranked, 0, len(profile.artistScores))
	for key, score := range profile.artistScores {
		rankedSeeds = append(rankedSeeds, ranked{name: key, score: score})
	}
	sort.Slice(rankedSeeds, func(i, j int) bool {
		if rankedSeeds[i].score == rankedSeeds[j].score {
			return rankedSeeds[i].name < rankedSeeds[j].name
		}
		return rankedSeeds[i].score > rankedSeeds[j].score
	})
	// 种子需要保留原始显示名与来源平台；从收藏/历史里回找。
	display := map[string]discoverySeed{}
	remember := func(track model.Track, weight int) {
		for _, name := range strings.Split(track.Artist, " / ") {
			name = strings.TrimSpace(name)
			key := normalizedMusicName(name)
			if key == "" || display[key].name != "" {
				continue
			}
			if _, ok := profile.artistScores[key]; !ok {
				continue
			}
			display[key] = discoverySeed{name: name, source: track.ProviderID, weight: weight}
		}
	}
	for _, item := range favorites[:min(len(favorites), 80)] {
		remember(item.Track, 3)
	}
	for _, entry := range musicHistory {
		remember(entry.Track, 2)
	}
	for _, item := range rankedSeeds[:min(len(rankedSeeds), 3)] {
		seed, ok := display[item.name]
		if !ok || catalog.PlatformNames[seed.source] == "" {
			continue
		}
		profile.seeds = append(profile.seeds, scoredSeed{name: seed.name, source: seed.source, score: item.score})
	}
	return profile
}

func platformQuality(source string) float64 {
	for i, id := range catalog.PlatformOrder {
		if id == source {
			return 1 - float64(i)*0.05
		}
	}
	return 0.6
}

func fallbackPlatform(source string) string {
	for i, id := range catalog.PlatformOrder {
		if id == source {
			return catalog.PlatformOrder[(i+1)%len(catalog.PlatformOrder)]
		}
	}
	return catalog.PlatformOrder[0]
}

// candidateScore 综合歌手亲和、新鲜度、新颖度与平台质量，供排序与 MMR 使用。
func candidateScore(profile preferenceProfile, track model.Track, order, total int) float64 {
	artists := recommendationArtists(track.Artist)
	affinity := 0.0
	for _, artist := range artists {
		if score := profile.artistScores[artist]; score > 0 {
			affinity = math.Max(affinity, score)
		}
	}
	if affinity > 0 {
		maxScore := 0.0
		for _, seed := range profile.seeds {
			maxScore = math.Max(maxScore, seed.score)
		}
		if maxScore > 0 {
			affinity /= maxScore
		}
	}
	freshness := 1.0
	if total > 1 {
		freshness = 1 - float64(order)/float64(total-1)
	}
	novelty := 1.0
	plays, skips := 0, 0
	for _, artist := range artists {
		plays += profile.artistPlays[artist]
		skips += profile.artistSkips[artist]
	}
	if plays > 0 {
		novelty = 1 / (1 + 0.2*float64(plays))
	}
	skipPenalty := 0.12 * ratio(skips, plays)
	return math.Max(0, 0.55*affinity+0.2*freshness+0.15*novelty+0.1*platformQuality(track.ProviderID)-skipPenalty)
}

type discoveryCandidate struct {
	track model.Track
	score float64
	order int
}

func rankCandidates(profile preferenceProfile, tracks []model.Track) []discoveryCandidate {
	candidates := make([]discoveryCandidate, 0, len(tracks))
	for index, track := range tracks {
		candidates = append(candidates, discoveryCandidate{
			track: track,
			score: candidateScore(profile, track, index, len(tracks)),
			order: index,
		})
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].score == candidates[j].score {
			return candidates[i].order < candidates[j].order
		}
		return candidates[i].score > candidates[j].score
	})
	return candidates
}

func candidateSimilarity(a, b model.Track) float64 {
	similarity := 0.0
	aArtists := recommendationArtists(a.Artist)
	bArtists := recommendationArtists(b.Artist)
	for _, artist := range aArtists {
		for _, other := range bArtists {
			if artist == other {
				similarity = math.Max(similarity, 0.8)
			}
		}
	}
	if a.Album != "" && normalizedMusicName(a.Album) == normalizedMusicName(b.Album) {
		similarity = math.Max(similarity, 0.4)
	}
	if a.ProviderID == b.ProviderID {
		similarity = math.Max(similarity, 0.15)
	}
	return similarity
}

// selectDiscoveryV2：歌手轮转保证种子公平，MMR 重排公开补位并保留探索配额。
func selectDiscoveryV2(
	profile preferenceProfile,
	related [][]model.Track,
	fresh, fill, known []model.Track,
) ([]model.Track, bool) {
	seen := map[string]bool{}
	ids := map[string]bool{}
	for _, track := range known {
		seen[recommendationTrackKey(track)] = true
		ids[track.ID] = true
	}
	out := []model.Track{}
	artistCounts := map[string]int{}
	albumCounts := map[string]int{}
	personalPlatformCounts := map[string]int{}
	personalized := false
	personalCount := 0

	type decision uint8
	const (
		acceptCandidate decision = iota
		rejectCandidate
		waitForBackWindow
	)
	evaluate := func(track model.Track) ([]string, string, decision) {
		if track.ID == "" || strings.TrimSpace(track.Title) == "" || ids[track.ID] {
			return nil, "", rejectCandidate
		}
		key := recommendationTrackKey(track)
		if seen[key] {
			return nil, "", rejectCandidate
		}
		artists := recommendationArtists(track.Artist)
		if len(artists) == 0 {
			artists = []string{""}
		}
		for _, artist := range artists {
			if artistCounts[artist] >= discoveryArtistCap {
				return nil, "", rejectCandidate
			}
			if len(out) < discoveryFrontWindow && artistCounts[artist] >= discoveryFrontArtistCap {
				return nil, "", waitForBackWindow
			}
		}
		album := normalizedMusicName(track.Album)
		if album != "" && albumCounts[album] >= discoveryAlbumCap {
			return nil, "", rejectCandidate
		}
		return artists, album, acceptCandidate
	}
	commit := func(track model.Track, artists []string, album string, personal bool) {
		seen[recommendationTrackKey(track)] = true
		ids[track.ID] = true
		for _, artist := range artists {
			artistCounts[artist]++
		}
		if album != "" {
			albumCounts[album]++
		}
		out = append(out, track)
		if personal {
			personalPlatformCounts[track.ProviderID]++
			personalCount++
			personalized = true
		}
	}

	// 个性化与公开候选在前 10 首交错：先满足歌手上限，再在后半段继续补足最多 12 首个性化。
	rankedRelated := make([][]discoveryCandidate, len(related))
	for i := range related {
		rankedRelated[i] = rankCandidates(profile, related[i])
	}
	offsets := make([]int, len(rankedRelated))
	seedCount := min(len(profile.seeds), len(rankedRelated), 3)
	nextSeed := 0
	tryPersonal := func() bool {
		if personalCount >= discoveryPersonalLimit || seedCount == 0 {
			return false
		}
		for checked := 0; checked < seedCount; checked++ {
			i := (nextSeed + checked) % seedCount
			seed := profile.seeds[i]
			for offsets[i] < len(rankedRelated[i]) {
				candidate := rankedRelated[i][offsets[i]]
				if !recommendationMatches(candidate.track, discoverySeed{name: seed.name, source: seed.source}) {
					offsets[i]++
					continue
				}
				// 平台上限必须检查实际候选平台，后备召回不能借用 seed 平台绕过限制。
				if personalPlatformCounts[candidate.track.ProviderID] >= discoveryPersonalPlatforms {
					offsets[i]++
					continue
				}
				artists, album, result := evaluate(candidate.track)
				switch result {
				case rejectCandidate:
					offsets[i]++
					continue
				case waitForBackWindow:
					// 同一召回列表都包含 seed 歌手；保留当前位置，进入第 11 首后再继续。
					break
				default:
					offsets[i]++
					commit(candidate.track, artists, album, true)
					nextSeed = (i + 1) % seedCount
					return true
				}
				break
			}
		}
		return false
	}

	publicTracks := append(append([]model.Track(nil), fresh...), fill...)
	publicCandidates := rankCandidates(profile, publicTracks)
	publicUsed := make([]bool, len(publicCandidates))
	exploreReserve := min(discoveryExploreCount, max(0, len(publicCandidates)-1))

	// 每一轮都按当前已选集合重新计算最大相似度，并可为探索阶段保留若干候选。
	tryPublicMMR := func(leave int) bool {
		type eligibleCandidate struct {
			index   int
			artists []string
			album   string
			mmr     float64
		}
		eligible := []eligibleCandidate{}
		waiting := 0
		for i, candidate := range publicCandidates {
			if publicUsed[i] {
				continue
			}
			artists, album, result := evaluate(candidate.track)
			if result == rejectCandidate {
				publicUsed[i] = true
				continue
			}
			if result == waitForBackWindow {
				waiting++
				continue
			}
			maxSimilarity := 0.0
			for _, chosen := range out {
				maxSimilarity = math.Max(maxSimilarity, candidateSimilarity(candidate.track, chosen))
			}
			eligible = append(eligible, eligibleCandidate{
				index: i, artists: artists, album: album,
				mmr: 0.75*candidate.score - 0.25*maxSimilarity,
			})
		}
		if len(eligible)+waiting <= leave || len(eligible) == 0 {
			return false
		}
		best := eligible[0]
		for _, candidate := range eligible[1:] {
			if candidate.mmr > best.mmr || candidate.mmr == best.mmr && publicCandidates[candidate.index].order < publicCandidates[best.index].order {
				best = candidate
			}
		}
		publicUsed[best.index] = true
		commit(publicCandidates[best.index].track, best.artists, best.album, false)
		return true
	}

	// 前十首目标为 6 个性化 + 4 个公开候选；不足时由另一侧补位，但不突破硬上限。
	frontPersonalSlots := map[int]bool{0: true, 1: true, 2: true, 4: true, 5: true, 6: true}
	normalTarget := discoveryTrackLimit - exploreReserve
	for len(out) < normalTarget {
		preferPersonal := len(out) >= discoveryFrontWindow || frontPersonalSlots[len(out)]
		added := false
		if preferPersonal {
			added = tryPersonal()
			if !added {
				added = tryPublicMMR(exploreReserve)
			}
		} else {
			added = tryPublicMMR(exploreReserve)
			if !added {
				added = tryPersonal()
			}
		}
		if !added {
			break
		}
	}

	// 探索位只从正常阶段留下的候选中产生；按日稳定洗牌，再按 batch 轮转保证换批可见。
	exploreOrder := make([]int, 0, len(publicCandidates))
	for i := range publicCandidates {
		if !publicUsed[i] {
			exploreOrder = append(exploreOrder, i)
		}
	}
	shuffle := rand.New(rand.NewSource(profile.day * 1_000_003))
	shuffle.Shuffle(len(exploreOrder), func(i, j int) { exploreOrder[i], exploreOrder[j] = exploreOrder[j], exploreOrder[i] })
	if len(exploreOrder) > 1 {
		offset := profile.batch % len(exploreOrder)
		exploreOrder = append(append([]int(nil), exploreOrder[offset:]...), exploreOrder[:offset]...)
	}
	explored := 0
	for _, index := range exploreOrder {
		if len(out) >= discoveryTrackLimit || explored >= exploreReserve {
			break
		}
		if publicUsed[index] {
			continue
		}
		artists, album, result := evaluate(publicCandidates[index].track)
		if result != acceptCandidate {
			if result == rejectCandidate {
				publicUsed[index] = true
			}
			continue
		}
		publicUsed[index] = true
		commit(publicCandidates[index].track, artists, album, false)
		explored++
	}

	// 探索项可能将结果推进到第 11 首；此后继续补足个性化与公开候选。
	for len(out) < discoveryTrackLimit {
		added := false
		if personalCount < discoveryPersonalLimit {
			added = tryPersonal()
		}
		if !added {
			added = tryPublicMMR(0)
		}
		if !added {
			break
		}
	}
	return out, personalized
}

// rankDiscoveryPlaylists：收藏分类命中的歌单优先，然后按平台轮询保持跨平台多样性。
func rankDiscoveryPlaylists(items []model.Collection, categories map[string]float64, limit int) []model.Collection {
	groups := map[string][]model.Collection{}
	for _, item := range items {
		groups[item.ProviderID] = append(groups[item.ProviderID], item)
	}
	for source := range groups {
		list := groups[source]
		sort.SliceStable(list, func(i, j int) bool {
			return categories[strings.TrimSpace(list[i].Category)] > categories[strings.TrimSpace(list[j].Category)]
		})
		groups[source] = list
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

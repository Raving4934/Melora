package provider

import (
	"context"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"melora/internal/lxsource"
	"melora/internal/model"
)

const crossSearchLimit = 2
const crossSearchBudget = 2 * time.Second
const crossMetadataBudget = 2 * time.Second

type crossPlatformPlan struct{ platform string }

// matchKey 只标准化大小写、全角 ASCII 和空白，不删括号/版本后缀、不作拼音或子串匹配。
func matchKey(raw string) string {
	return strings.Join(strings.Fields(strings.Map(func(r rune) rune {
		if r >= 0xff01 && r <= 0xff5e {
			r -= 0xfee0
		}
		if r == 0x3000 {
			r = ' '
		}
		return unicode.ToLower(r)
	}, raw)), " ")
}
func versionKey(raw string) string {
	text := matchKey(raw)
	words := " " + strings.Map(func(r rune) rune {
		if !unicode.IsLetter(r) && !unicode.IsDigit(r) {
			return ' '
		}
		return r
	}, text) + " "
	markers := []string{}
	for _, word := range []string{"live", "remix", "remaster", "remastered", "acoustic", "instrumental", "karaoke", "cover", "demo", "sped", "slowed"} {
		if strings.Contains(words, " "+word+" ") {
			markers = append(markers, word)
		}
	}
	for _, word := range []string{"现场", "演唱会", "伴奏", "纯音乐", "翻唱", "重制", "加速", "慢速", "dj版"} {
		if strings.Contains(text, word) {
			markers = append(markers, word)
		}
	}
	return strings.Join(markers, "|")
}
func strictSameTrack(original, candidate model.Track) bool {
	if !validTrackIdentity(candidate) || candidate.ProviderID == original.ProviderID {
		return false
	}
	name, artist := matchKey(original.Title), matchKey(original.Artist)
	if name == "" || artist == "" || name != matchKey(candidate.Title) || artist != matchKey(candidate.Artist) {
		return false
	}
	if versionKey(original.Title) != versionKey(candidate.Title) || versionKey(original.Album) != versionKey(candidate.Album) {
		return false
	}
	if original.Duration <= 0 || candidate.Duration <= 0 || original.Duration > 86400 || candidate.Duration > 86400 {
		return false
	}
	difference := original.Duration - candidate.Duration
	if difference < 0 {
		difference = -difference
	}
	longer := max(original.Duration, candidate.Duration)
	return difference <= 3 && difference*100 <= longer*2
}
func orderedSources(state lxsource.State, opts ResolveOptions) []lxsource.Source {
	out := []lxsource.Source{}
	for _, source := range state.Items {
		if source.ID == state.ActiveID {
			out = append(out, source)
			break
		}
	}
	if opts.AutoSwitch {
		for _, source := range state.Items {
			if source.ID != state.ActiveID {
				out = append(out, source)
			}
		}
	}
	return out
}
func (l *Live) crossPlans(original model.Track, quality string, opts ResolveOptions) ([]crossPlatformPlan, string) {
	if !opts.AutoSwitch || l.Sources == nil || l.Catalog == nil {
		return nil, quality
	}
	ordered := orderedSources(l.Sources.List(), opts)
	out := []crossPlatformPlan{}
	for _, platform := range l.Catalog.IDs() {
		if platform == original.ProviderID {
			continue
		}
		for _, source := range ordered {
			if containsQuality(opts.ExcludeSources, source.ID) {
				continue
			}
			qualities := sourceQualities(source, platform)
			if len(qualities) == 0 {
				continue
			}
			if quality == "" || quality == "standard" {
				quality = qualities[0]
				if containsQuality(qualities, "128k") {
					quality = "128k"
				}
			}
			if containsQuality(qualities, quality) {
				out = append(out, crossPlatformPlan{platform: platform})
				break
			}
		}
		if len(out) >= crossSearchLimit {
			break
		}
	}
	return out, quality
}

// trustedMatchTrack 不使用客户端可编辑字段作为跨平台匹配依据，缺可信完整记录即放弃。
func (l *Live) trustedMatchTrack(ctx context.Context, id string) (model.Track, bool) {
	ctx, cancel := context.WithTimeout(ctx, crossMetadataBudget)
	defer cancel()
	track, err := l.Catalog.Track(ctx, id)
	return track, err == nil && track.ID == id && validTrackIdentity(track) && matchKey(track.Title) != "" && matchKey(track.Artist) != "" && track.Duration > 0 && track.Duration <= 86400
}
func (l *Live) uniqueCrossMatch(ctx context.Context, original model.Track, platform string) (model.Track, bool) {
	query := strings.TrimSpace(original.Title + " " + original.Artist)
	if !utf8.ValidString(query) || utf8.RuneCountInString(query) > 200 {
		return model.Track{}, false
	}
	ctx, cancel := context.WithTimeout(ctx, crossSearchBudget)
	defer cancel()
	result, err := l.Catalog.SearchFor(ctx, platform, query, "track", 1)
	if err != nil {
		return model.Track{}, false
	}
	var match model.Track
	found := false
	for _, track := range result.Tracks {
		if track.ProviderID != platform || !strictSameTrack(original, track) {
			continue
		}
		if found && track.ID != match.ID {
			return model.Track{}, false
		} // 多个严格结果也视为歧义，绝不取第一首。
		match, found = track, true
	}
	return match, found
}

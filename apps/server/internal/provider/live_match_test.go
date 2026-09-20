package provider

import (
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
)

func TestLiveV5StrictSameTrack(t *testing.T) {
	original := model.Track{ID: "wy:123", ProviderID: "wy", Title: "Song Name", Artist: "Artist Name", Album: "Studio Album", Duration: 200}
	candidate := original
	candidate.ID = "tx:abc"
	candidate.ProviderID = "tx"
	candidate.Title = "ＳＯＮＧ  Name"
	candidate.Duration = 202
	if !strictSameTrack(original, candidate) {
		t.Fatal("safe full-width/space normalization failed")
	}
	for name, alter := range map[string]func(*model.Track){
		"live_title":         func(x *model.Track) { x.Title += " (Live)" },
		"live_album":         func(x *model.Track) { x.Album = "Live Concert" },
		"remix":              func(x *model.Track) { x.Title += " Remix" },
		"featured_artist":    func(x *model.Track) { x.Artist += " / Other" },
		"substring":          func(x *model.Track) { x.Title = "Song" },
		"unknown_duration":   func(x *model.Track) { x.Duration = 0 },
		"different_duration": func(x *model.Track) { x.Duration = 210 },
		"identity_mismatch":  func(x *model.Track) { x.ID = "kw:123" },
	} {
		t.Run(name, func(t *testing.T) {
			x := candidate
			alter(&x)
			if strictSameTrack(original, x) {
				t.Fatal("non-identical song accepted")
			}
		})
	}
}

type crossFixtureAdapter struct {
	catalog.Adapter
	track    model.Track
	rows     []model.Track
	failInfo bool
	searches int
}

func (a *crossFixtureAdapter) Track(context.Context, string) (model.Track, error) {
	return a.track, nil
}
func (a *crossFixtureAdapter) MusicInfo(context.Context, string) (map[string]any, error) {
	if a.failInfo {
		return nil, catalog.ErrUnavailable
	}
	return map[string]any{"source": a.track.ProviderID, "songmid": strings.TrimPrefix(a.track.ID, a.track.ProviderID+":"), "name": a.track.Title}, nil
}
func (a *crossFixtureAdapter) Search(_ context.Context, q, kind string, page int) (catalog.SearchResult, error) {
	a.searches++
	if q != "Song Name Artist Name" || kind != "track" || page != 1 {
		return catalog.SearchResult{}, catalog.ErrInput
	}
	return catalog.SearchResult{Tracks: a.rows, Total: len(a.rows)}, nil
}

type crossFixtureRunner struct {
	calls        []string
	quality      string
	crossQuality string
}

func (r *crossFixtureRunner) Inspect(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error) {
	q := "128k"
	if r.crossQuality != "" {
		q = r.crossQuality
	}
	return lxruntime.Descriptor{Status: true, Sources: map[string]lxruntime.Source{"wy": {Name: "WY", Type: "music", Actions: []string{"musicUrl"}, Qualitys: []string{"128k"}}, "tx": {Name: "TX", Type: "music", Actions: []string{"musicUrl"}, Qualitys: []string{q}}}}, nil
}
func (r *crossFixtureRunner) Invoke(_ context.Context, _, platform, _ string, info map[string]any, _ lxruntime.Options) (json.RawMessage, error) {
	r.calls = append(r.calls, platform)
	r.quality, _ = info["type"].(string)
	if platform == "wy" {
		return nil, lxruntime.ErrNetwork
	}
	if info["musicInfo"].(map[string]any)["songmid"] != "abc" {
		return nil, lxruntime.ErrScript
	}
	return json.RawMessage(`"https://8.8.8.8/public.mp3"`), nil
}
func crossFixture(t *testing.T, ambiguous, coldFail bool, crossQuality string) (*Live, *crossFixtureRunner, *crossFixtureAdapter) {
	t.Helper()
	original := model.Track{ID: "wy:123", ProviderID: "wy", Title: "Song Name", Artist: "Artist Name", Album: "Studio Album", Duration: 200}
	candidate := original
	candidate.ID = "tx:abc"
	candidate.ProviderID = "tx"
	candidate.Duration = 201
	wy := &crossFixtureAdapter{track: original, failInfo: coldFail}
	tx := &crossFixtureAdapter{track: candidate, rows: []model.Track{candidate}}
	if ambiguous {
		second := candidate
		second.ID = "tx:def"
		tx.rows = append(tx.rows, second)
	}
	r := &crossFixtureRunner{crossQuality: crossQuality}
	m, err := lxsource.New(filepath.Join(t.TempDir(), "sources"), r)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = m.Close() })
	if _, _, err := m.Import(t.Context(), "multi.js", []byte("// multi-platform fixture"), nil); err != nil {
		t.Fatal(err)
	}
	return &Live{Sources: m, Catalog: catalog.NewRegistry(map[string]catalog.Adapter{"wy": wy, "tx": tx})}, r, tx
}
func TestLiveV5SingleScriptStrictCrossPlatform(t *testing.T) {
	for _, coldFail := range []bool{false, true} {
		t.Run(map[bool]string{false: "parse_failed", true: "metadata_failed"}[coldFail], func(t *testing.T) {
			l, r, tx := crossFixture(t, false, coldFail, "")
			before := l.Sources.List().ActiveID
			// 客户端可编辑字段与可信记录不同：匹配必须以 Registry 的记录为准。
			input := model.Track{ID: "wy:123", ProviderID: "wy", Title: "UNTRUSTED CLIENT TITLE"}
			out, err := l.PlayInfoWithOptions(t.Context(), input, "128k", ResolveOptions{AutoSwitch: true, PageScheme: "https"})
			if err != nil || out.TrackID != "wy:123" || out.ResolvedTrack == nil || out.ResolvedTrack.ID != "tx:abc" || out.Quality != "128k" || r.quality != "128k" || tx.searches != 1 {
				t.Fatalf("strict cross-platform fallback failed: %v", err)
			}
			if l.Sources.List().ActiveID != before || out.SourceID != before {
				t.Fatal("cross-platform switched global active")
			}
			if coldFail && len(r.calls) != 1 || !coldFail && len(r.calls) != 2 {
				t.Fatal("unexpected invocation count")
			}
		})
	}
}
func TestLiveV5CrossPlatformAmbiguousOrWrongQualityRejected(t *testing.T) {
	l, r, _ := crossFixture(t, true, false, "")
	out, err := l.PlayInfoWithOptions(t.Context(), model.Track{ID: "wy:123", ProviderID: "wy"}, "128k", ResolveOptions{AutoSwitch: true})
	if err == nil || out.ResolvedTrack != nil || len(r.calls) != 1 {
		t.Fatal("ambiguous search picked first result")
	}
	l, r, tx := crossFixture(t, false, false, "flac")
	_, err = l.PlayInfoWithOptions(t.Context(), model.Track{ID: "wy:123", ProviderID: "wy"}, "128k", ResolveOptions{AutoSwitch: true})
	if !errors.Is(err, lxruntime.ErrNetwork) || tx.searches != 0 || len(r.calls) != 1 {
		t.Fatal("cross-platform silently changed quality")
	}
}
func TestLiveV5HTTPPlaybackDoesNotUpgradeURL(t *testing.T) {
	l, r, _, _ := liveFixture(t, 1, nil)
	r.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return json.RawMessage(`"http://8.8.8.8/public.mp3"`), nil
	}
	out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{PageScheme: "http"})
	if err != nil || out.URL != "http://8.8.8.8/public.mp3" {
		t.Fatal("HTTP URL rejected or silently upgraded")
	}
	if _, err = l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{PageScheme: "https"}); !errors.Is(err, ErrMixedContent) {
		t.Fatal(err)
	}
	if download, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{PageScheme: "https"}); err != nil || download.URL != "http://8.8.8.8/public.mp3" {
		t.Fatal("HTTP download failed or rewrote scheme")
	}
}

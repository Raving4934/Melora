package provider

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
)

type liveFixtureCatalog struct {
	catalog.Adapter
	calls atomic.Int32
	err   error
}

func (a *liveFixtureCatalog) MusicInfo(context.Context, string) (map[string]any, error) {
	a.calls.Add(1)
	if a.err != nil {
		return nil, a.err
	}
	return map[string]any{"source": "wy", "songmid": "123", "albumId": "456", "name": "fixture", "singer": "fixture artist"}, nil
}
func (a *liveFixtureCatalog) Track(context.Context, string) (model.Track, error) {
	return model.Track{ID: "wy:123", ProviderID: "wy", Title: "fixture"}, a.err
}

type liveFixtureRunner struct {
	mu        sync.Mutex
	calls     []string
	qualities map[string][]string
	invoke    func(context.Context, string, map[string]any) (json.RawMessage, error)
}

func (r *liveFixtureRunner) Inspect(_ context.Context, code string, _ lxruntime.Options) (lxruntime.Descriptor, error) {
	qualities := []string{"128k", "320k"}
	if v, ok := r.qualities[code]; ok {
		qualities = v
	}
	return lxruntime.Descriptor{Status: true, Sources: map[string]lxruntime.Source{"wy": {Name: "fixture", Type: "music", Actions: []string{"musicUrl"}, Qualitys: qualities}}}, nil
}
func (r *liveFixtureRunner) Invoke(ctx context.Context, code, platform, action string, info map[string]any, _ lxruntime.Options) (json.RawMessage, error) {
	r.mu.Lock()
	r.calls = append(r.calls, code)
	r.mu.Unlock()
	if platform != "wy" || action != "musicUrl" {
		return nil, lxruntime.ErrUnsupported
	}
	if r.invoke != nil {
		return r.invoke(ctx, code, info)
	}
	return json.RawMessage(`"https://8.8.8.8/public.mp3"`), nil
}
func liveFixture(t *testing.T, count int, qualities map[string][]string) (*Live, *liveFixtureRunner, *liveFixtureCatalog, []lxsource.Source) {
	t.Helper()
	r := &liveFixtureRunner{qualities: qualities}
	m, err := lxsource.New(filepath.Join(t.TempDir(), "sources"), r)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = m.Close() })
	sources := []lxsource.Source{}
	for i := 0; i < count; i++ {
		code := fmt.Sprintf("// fixture %d", i)
		s, _, err := m.Import(t.Context(), fmt.Sprintf("source-%d.js", i), []byte(code), nil)
		if err != nil {
			t.Fatal(err)
		}
		sources = append(sources, s)
	}
	c := &liveFixtureCatalog{}
	return &Live{Sources: m, Catalog: catalog.NewRegistry(map[string]catalog.Adapter{"wy": c})}, r, c, sources
}
func fixtureTrack() model.Track {
	return model.Track{ID: "wy:123", ProviderID: "wy", Title: "fixture", Artist: "fixture artist"}
}

func TestLiveV5URLShapes(t *testing.T) {
	for _, raw := range []string{`"https://8.8.8.8/public.mp3"`, `{"data":{"url":"https://8.8.8.8/public.mp3"}}`, `"{\"data\":{\"url\":\"https://8.8.8.8/public.mp3\"}}"`, `{"url":"not a URL","data":{"musicUrl":"https://8.8.8.8/public.mp3"}}`, `{"result":{"body":"{\"url\":\"https://8.8.8.8/public.mp3\"}"}}`, `[{"playUrl":"https://8.8.8.8/public.mp3"}]`} {
		got, err := decodeMediaURL(json.RawMessage(raw))
		if err != nil || got != "https://8.8.8.8/public.mp3" {
			t.Fatalf("shape rejected: %v", err)
		}
	}
	for _, raw := range []string{`"not a URL"`, `["https://8.8.8.8/a.mp3","https://8.8.8.8/b.mp3"]`, `{"url":"https://u:p@8.8.8.8/a.mp3"}`, `"//8.8.8.8/a.mp3"`, `"javascript:alert(1)"`, `"https://8.8.8.8/a.mp3#secret"`, strings.Repeat("x", (256<<10)+1), `{"data":{"data":{"data":{"data":{"data":{"data":{"data":{"url":"https://8.8.8.8/a.mp3"}}}}}}}}`} {
		if _, err := decodeMediaURL(json.RawMessage(raw)); err == nil {
			t.Fatal("invalid result accepted")
		}
	}
	if _, err := decodeMediaURL(json.RawMessage(`{"url":"https://8.8.8.8/a.mp3","headers":{"Referer":"SECRET"}}`)); !errors.Is(err, ErrMediaHeaders) {
		t.Fatal(err)
	}
}
func TestLiveV5ActiveFirstSwitchIsLocal(t *testing.T) {
	l, r, c, sources := liveFixture(t, 3, nil)
	if err := l.Sources.Select(sources[1].ID); err != nil {
		t.Fatal(err)
	}
	r.invoke = func(_ context.Context, code string, info map[string]any) (json.RawMessage, error) {
		if info["type"] != "320k" {
			t.Error("quality changed")
		}
		song := info["musicInfo"].(map[string]any)
		if song["songmid"] != "123" {
			t.Error("song changed")
		}
		if code == "// fixture 1" {
			song["songmid"] = "poison"
			return nil, lxruntime.ErrScript
		}
		return json.RawMessage(`"{\"data\":{\"url\":\"https://8.8.8.8/public.mp3\"}}"`), nil
	}
	out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: true})
	if err != nil || out.SourceID != sources[0].ID || out.Quality != "320k" || !reflect.DeepEqual(out.AttemptedSources, []string{sources[1].ID, sources[0].ID}) || out.ResolvedTrack == nil || out.ResolvedTrack.ID != "wy:123" {
		t.Fatalf("result identity/attempts mismatch: %v", err)
	}
	if c.calls.Load() != 1 {
		t.Fatal("metadata fetched more than once")
	}
	if active, _ := l.Sources.Active(); active.ID != sources[1].ID {
		t.Fatal("auto-switch changed global active")
	}
}
func TestLiveV5AutoSwitchOffAndExcludedSources(t *testing.T) {
	l, r, _, sources := liveFixture(t, 3, nil)
	r.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return nil, lxruntime.ErrNetwork
	}
	out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{})
	if !errors.Is(err, lxruntime.ErrNetwork) || len(out.AttemptedSources) != 1 || out.AttemptedSources[0] != sources[0].ID {
		t.Fatal("default unexpectedly switched")
	}
	out, err = l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true, ExcludeSources: []string{sources[0].ID, sources[2].ID}})
	if err == nil || !reflect.DeepEqual(out.AttemptedSources, []string{sources[1].ID}) {
		t.Fatal("excluded source invoked")
	}
	var failure *ResolveError
	if !errors.As(err, &failure) || !reflect.DeepEqual(failure.Attempts, out.AttemptedSources) {
		t.Fatal("failure lost attempted sources")
	}
}
func TestLiveV5SameQualityAndAttemptLimit(t *testing.T) {
	l, r, _, sources := liveFixture(t, 5, map[string][]string{"// fixture 1": {"flac"}})
	r.invoke = func(_ context.Context, _ string, info map[string]any) (json.RawMessage, error) {
		if info["type"] != "128k" {
			t.Error("automatic quality changed after first source")
		}
		return nil, lxruntime.ErrScript
	}
	out, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "standard", ResolveOptions{AutoSwitch: true})
	if err == nil || !reflect.DeepEqual(out.AttemptedSources, []string{sources[0].ID, sources[2].ID, sources[3].ID}) {
		t.Fatal("attempt bound or quality filtering failed")
	}
	l2, r2, c2, _ := liveFixture(t, 2, nil)
	if _, err := l2.PlayInfoWithOptions(t.Context(), fixtureTrack(), "flac", ResolveOptions{AutoSwitch: true}); !errors.Is(err, ErrQuality) {
		t.Fatal(err)
	}
	if c2.calls.Load() != 0 || len(r2.calls) != 0 {
		t.Fatal("unsupported quality fetched metadata or invoked script")
	}
}
func TestLiveV5CancellationAndColdFailure(t *testing.T) {
	l, r, _, _ := liveFixture(t, 3, nil)
	r.invoke = func(ctx context.Context, _ string, _ map[string]any) (json.RawMessage, error) {
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > resolveAttemptBudget {
			t.Error("missing attempt deadline")
		}
		<-ctx.Done()
		return nil, ctx.Err()
	}
	ctx, cancel := context.WithTimeout(t.Context(), 40*time.Millisecond)
	defer cancel()
	out, err := l.ResolveWithOptions(ctx, fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true})
	if !errors.Is(err, context.DeadlineExceeded) || len(out.AttemptedSources) != 1 {
		t.Fatalf("cancellation retried: %v", err)
	}
	fresh, _, cold, _ := liveFixture(t, 1, nil)
	cold.err = catalog.ErrUnavailable
	out, err = fresh.ResolveWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true})
	if !errors.Is(err, catalog.ErrUnavailable) || len(out.AttemptedSources) != 0 {
		t.Fatal("cold failure invoked sources or fabricated metadata")
	}
}
func TestLiveV5InvalidInputAndPublicMediaBoundary(t *testing.T) {
	l, r, c, _ := liveFixture(t, 2, nil)
	bad := fixtureTrack()
	bad.ProviderID = "tx"
	if _, err := l.PlayInfoWithOptions(t.Context(), bad, "128k", ResolveOptions{AutoSwitch: true}); !errors.Is(err, catalog.ErrNotFound) {
		t.Fatal(err)
	}
	if _, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true, ExcludeSources: []string{"invalid"}}); !errors.Is(err, ErrResolveOptions) {
		t.Fatal(err)
	}
	if c.calls.Load() != 0 || len(r.calls) != 0 {
		t.Fatal("invalid input made calls")
	}
	for _, raw := range []string{"https://127.0.0.1/a.mp3", "https://10.1.2.3/a.mp3", "https://[::1]/a.mp3", "https://169.254.169.254/a.mp3"} {
		if err := validateResolvedMedia(t.Context(), raw, "http", true); !errors.Is(err, ErrMediaURL) {
			t.Fatal("private media allowed")
		}
	}
	if err := validateResolvedMedia(t.Context(), "http://8.8.8.8/a.mp3", "https", true); !errors.Is(err, ErrMixedContent) {
		t.Fatal(err)
	}
	if err := validateResolvedMedia(t.Context(), "http://8.8.8.8/a.mp3", "http", false); err != nil {
		t.Fatal("public HTTP download rejected")
	}
}

func TestLiveV5CapabilitiesFollowAutoSwitch(t *testing.T) {
	l, _, _, sources := liveFixture(t, 2, map[string][]string{"// fixture 0": {"128k"}, "// fixture 1": {"320k", "flac"}})
	if !l.AutoSwitchEnabled() || !reflect.DeepEqual(l.Qualities("wy"), []string{"128k", "320k", "flac"}) {
		t.Fatal("default ready capability union missing")
	}
	l.SetAutoSwitch(false)
	if !reflect.DeepEqual(l.Qualities("wy"), []string{"128k"}) {
		t.Fatal("disabled auto-switch still unions inactive sources")
	}
	if err := l.Sources.Select(""); err != nil {
		t.Fatal(err)
	}
	if track := l.Enrich(fixtureTrack()); track.CanDownload || len(track.Qualities) != 0 {
		t.Fatal("disabled without active source advertises capabilities")
	}
	if _, err := l.Resolve(t.Context(), fixtureTrack(), "128k"); !errors.Is(err, lxsource.ErrNoActive) {
		t.Fatal("disabled legacy resolver unexpectedly switched")
	}
	l.SetAutoSwitch(true)
	track := l.Enrich(fixtureTrack())
	if !track.CanDownload || !reflect.DeepEqual(track.Qualities, []string{"128k", "320k", "flac"}) {
		t.Fatal("ready sources without active not advertised")
	}
	out, err := l.PlayInfo(t.Context(), fixtureTrack(), "flac")
	if err != nil || out.SourceID != sources[1].ID || out.Quality != "flac" {
		t.Fatalf("legacy methods not aligned with advertised capabilities: %v", err)
	}
	if l.Sources.List().ActiveID != "" {
		t.Fatal("automatic source selection persisted an active source")
	}
}
func TestLiveV5AutoSwitchCapabilityStateConcurrent(t *testing.T) {
	l, _, _, _ := liveFixture(t, 2, nil)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for n := 0; n < 50; n++ {
				l.SetAutoSwitch((i+n)%2 == 0)
				_ = l.Qualities("wy")
				_ = l.Enrich(fixtureTrack())
			}
		}(i)
	}
	wg.Wait()
}

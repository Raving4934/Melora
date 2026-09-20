package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
	"melora/internal/provider"
)

const playbackV7TrackID = "tx:004VvSEU21QShy"
const playbackV7URL = "https://8.8.8.8/playback-v7.flac" // 只验证公网字面量，不连接媒体或 DNS。

type playbackV7Call struct {
	code, platform, quality string
	deadline                time.Time
}
type playbackV7Runner struct {
	mu           sync.Mutex
	capabilities map[string]map[string][]string
	calls        []playbackV7Call
	invoke       func(context.Context, playbackV7Call) (json.RawMessage, error)
}

func (r *playbackV7Runner) Inspect(_ context.Context, code string, _ lxruntime.Options) (lxruntime.Descriptor, error) {
	sources := map[string]lxruntime.Source{}
	for platform, qualities := range r.capabilities[code] {
		sources[platform] = lxruntime.Source{Name: "offline fixture", Type: "music", Actions: []string{"musicUrl"}, Qualitys: qualities}
	}
	return lxruntime.Descriptor{Status: true, Sources: sources}, nil
}
func (r *playbackV7Runner) Invoke(ctx context.Context, code, platform, action string, info map[string]any, _ lxruntime.Options) (json.RawMessage, error) {
	call := playbackV7Call{code: code, platform: platform}
	call.quality, _ = info["type"].(string)
	call.deadline, _ = ctx.Deadline()
	r.mu.Lock()
	r.calls = append(r.calls, call)
	r.mu.Unlock()
	if action != "musicUrl" {
		return nil, lxruntime.ErrUnsupported
	}
	if r.invoke != nil {
		return r.invoke(ctx, call)
	}
	data, _ := json.Marshal(playbackV7URL)
	return data, nil
}
func (r *playbackV7Runner) recorded() []playbackV7Call {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]playbackV7Call(nil), r.calls...)
}

type playbackV7Catalog struct {
	catalog.Adapter
	track             model.Track
	rows              []model.Track
	trackErr, infoErr error
	searches          int
}

func (a *playbackV7Catalog) Track(_ context.Context, id string) (model.Track, error) {
	if a.trackErr != nil {
		return model.Track{}, a.trackErr
	}
	if id != a.track.ID {
		return model.Track{}, catalog.ErrNotFound
	}
	return a.track, nil
}
func (a *playbackV7Catalog) MusicInfo(context.Context, string) (map[string]any, error) {
	if a.infoErr != nil {
		return nil, a.infoErr
	}
	return map[string]any{"source": a.track.ProviderID, "songmid": strings.TrimPrefix(a.track.ID, a.track.ProviderID+":"), "name": a.track.Title}, nil
}
func (a *playbackV7Catalog) Search(_ context.Context, query, kind string, page int) (catalog.SearchResult, error) {
	a.searches++
	if query != "测试原曲 测试歌手" || kind != "track" || page != 1 {
		return catalog.SearchResult{}, catalog.ErrInput
	}
	return catalog.SearchResult{Tracks: a.rows, Total: len(a.rows), PageSize: 24}, nil
}
func playbackV7Setup(t *testing.T, specs ...map[string][]string) (*Server, *lxsource.Manager, *playbackV7Runner, []*playbackV7Catalog, []lxsource.Source) {
	t.Helper()
	s, _, _ := setup(t, "")
	runner := &playbackV7Runner{capabilities: map[string]map[string][]string{}}
	manager, err := lxsource.New(filepath.Join(t.TempDir(), "sources"), runner)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { manager.Close() })
	sources := []lxsource.Source{}
	for i, spec := range specs {
		code := fmt.Sprintf("// playback v7 fixture %d", i)
		runner.capabilities[code] = spec
		source, _, err := manager.Import(t.Context(), fmt.Sprintf("fixture-%d.js", i), []byte(code), nil)
		if err != nil {
			t.Fatal(err)
		}
		sources = append(sources, source)
	}
	original := model.Track{ID: playbackV7TrackID, ProviderID: "tx", Title: "测试原曲", Artist: "测试歌手", Album: "录音室专辑", Duration: 200}
	tx := &playbackV7Catalog{track: original}
	candidate := original
	candidate.ID = "wy:123"
	candidate.ProviderID = "wy"
	candidate.Duration = 201
	wy := &playbackV7Catalog{track: candidate, rows: []model.Track{candidate}}
	registry := catalog.NewRegistry(map[string]catalog.Adapter{"tx": tx, "wy": wy})
	s.UseLiveSources(manager, &provider.Live{Sources: manager, Catalog: registry})
	return s, manager, runner, []*playbackV7Catalog{tx, wy}, sources
}
func playbackV7Settings(t *testing.T, s *Server, auto bool, quality string) {
	t.Helper()
	settings, err := s.store.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	settings.AutoSwitchSource, settings.DefaultQuality = auto, quality
	if err := s.store.SaveSettings(t.Context(), settings); err != nil {
		t.Fatal(err)
	}
	// 故意不调用 SetAutoSwitch：必须证明 handler 从本次真实 DB 设置传递 opts。
}
func playbackV7Request(s *Server, query string) *httptest.ResponseRecorder {
	return request(s, "GET", "/api/v1/tracks/"+playbackV7TrackID+"/play-info"+query, nil, nil)
}
func playbackV7Headers(t *testing.T, w *httptest.ResponseRecorder, stage string, attempts int, auto string) {
	t.Helper()
	if got := w.Header().Get("X-Melora-Resolve-Stage"); got != stage {
		t.Fatalf("stage=%q want=%q", got, stage)
	}
	if got := w.Header().Get("X-Melora-Resolve-Attempts"); got != strconv.Itoa(attempts) {
		t.Fatalf("attempt count=%q want=%d", got, attempts)
	}
	if got := w.Header().Get("X-Melora-Auto-Switch"); got != auto {
		t.Fatalf("auto setting=%q want=%q", got, auto)
	}
	for _, key := range []string{"X-Melora-Resolve-Stage", "X-Melora-Resolve-Attempts", "X-Melora-Auto-Switch"} {
		if strings.ContainsAny(w.Header().Get(key), "\r\n") || strings.Contains(w.Header().Get(key), "http") {
			t.Fatal("unsafe diagnostic header")
		}
	}
}
func playbackV7Failure(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	if w.Code != status {
		t.Fatalf("status=%d want=%d", w.Code, status)
	}
	var body map[string]json.RawMessage
	if json.Unmarshal(w.Body.Bytes(), &body) != nil || len(body) != 1 || body["error"] == nil {
		t.Fatal("existing failure JSON shape changed")
	}
	var failure struct {
		Code string `json:"code"`
	}
	if json.Unmarshal(body["error"], &failure) != nil || failure.Code != code {
		t.Fatalf("error code=%q want=%q", failure.Code, code)
	}
}

func TestPlaybackV7ExistingSourceRunnerSuccessHeaders(t *testing.T) {
	s, manager, runner := liveSetup(t, "")
	if _, _, err := manager.Import(t.Context(), "fixture.js", []byte("// existing sourceRunner"), nil); err != nil {
		t.Fatal(err)
	}
	playbackV7Settings(t, s, false, "flac")
	w := request(s, "GET", "/api/v1/tracks/wy:123/play-info", nil, nil)
	assertStatus(t, w, 200)
	playbackV7Headers(t, w, "resolve", 1, "false")
	if runner.calls != 1 {
		t.Fatal("header not based on actual manager call")
	}
	var info model.PlayInfo
	if json.Unmarshal(w.Body.Bytes(), &info) != nil || info.Quality != "flac" || info.URL == "" {
		t.Fatal("success JSON changed")
	}
}
func TestPlaybackV7CatalogFailureHasZeroAndNoInventedSetting(t *testing.T) {
	s, _, runner, catalogs, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}})
	catalogs[0].trackErr = catalog.ErrUnavailable
	playbackV7Settings(t, s, true, "flac")
	req := httptest.NewRequest("GET", "http://127.0.0.1:3780/api/v1/tracks/"+playbackV7TrackID+"/play-info?quality=flac", nil)
	req.Header.Set("X-Melora-Auto-Switch", "false")
	req.Header.Set("X-Melora-Resolve-Attempts", "3")
	req.Header.Set("X-Melora-Resolve-Stage", "resolve")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, req)
	playbackV7Failure(t, w, 502, "catalog_unavailable")
	playbackV7Headers(t, w, "catalog", 0, "")
	if len(runner.recorded()) != 0 {
		t.Fatal("catalog failure invoked scripts")
	}
}
func TestPlaybackV7MetadataFailureInsideResolveRemainsCatalogStage(t *testing.T) {
	for _, test := range []struct {
		err    error
		status int
		code   string
	}{
		{catalog.ErrUnavailable, 502, "catalog_unavailable"}, {catalog.ErrUnsupported, 422, "catalog_capability_unsupported"}, {catalog.ErrNotFound, 404, "not_found"}, {catalog.ErrInput, 400, "invalid_catalog_request"},
	} {
		t.Run(test.code, func(t *testing.T) {
			s, _, runner, catalogs, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}})
			catalogs[0].infoErr = fmt.Errorf("wrapped: %w", test.err)
			playbackV7Settings(t, s, false, "flac")
			w := playbackV7Request(s, "?quality=flac")
			playbackV7Failure(t, w, test.status, test.code)
			playbackV7Headers(t, w, "catalog", 0, "false")
			if len(runner.recorded()) != 0 {
				t.Fatal("metadata lookup counted as an invocation")
			}
		})
	}
}
func TestPlaybackV7SettingsReadFailureDoesNotPublishDefaultFlag(t *testing.T) {
	s, _, runner, _, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}})
	s.store.Close()
	w := playbackV7Request(s, "?quality=flac")
	if w.Code != 500 {
		t.Fatalf("status=%d want=500", w.Code)
	}
	playbackV7Headers(t, w, "catalog", 0, "")
	if len(runner.recorded()) != 0 {
		t.Fatal("settings failure invoked scripts")
	}
}
func TestPlaybackV7SamePlatformUsesActualAutoSwitchAndNeverDowngradesFLAC(t *testing.T) {
	for _, auto := range []bool{false, true} {
		t.Run(strconv.FormatBool(auto), func(t *testing.T) {
			s, manager, runner, _, sources := playbackV7Setup(t, map[string][]string{"tx": {"flac", "128k"}}, map[string][]string{"tx": {"flac", "128k"}})
			playbackV7Settings(t, s, auto, "128k")
			s.live.SetAutoSwitch(!auto) // 陈旧内存值不应覆盖本次数据库设置。
			runner.invoke = func(_ context.Context, call playbackV7Call) (json.RawMessage, error) {
				if strings.HasSuffix(call.code, "0") {
					return nil, fmt.Errorf("private-business-marker: %w", lxruntime.ErrNetwork)
				}
				raw, _ := json.Marshal(playbackV7URL)
				return raw, nil
			}
			w := playbackV7Request(s, "?quality=flac")
			want := 1
			if auto {
				want = 2
				assertStatus(t, w, 200)
			} else {
				playbackV7Failure(t, w, 502, "lx_resolve_failed")
			}
			playbackV7Headers(t, w, "resolve", want, strconv.FormatBool(auto))
			calls := runner.recorded()
			if len(calls) != want {
				t.Fatal("automatic switching did not follow DB setting")
			}
			for _, call := range calls {
				if call.platform != "tx" || call.quality != "flac" {
					t.Fatal("explicit flac was downgraded")
				}
			}
			if manager.List().ActiveID != sources[0].ID {
				t.Fatal("request changed globally active script")
			}
			if strings.Contains(w.Body.String(), "private-business-marker") {
				t.Fatal("raw runner error leaked")
			}
		})
	}
}
func TestPlaybackV7AttemptsAreBoundedAndActiveSourceIsFirst(t *testing.T) {
	spec := map[string][]string{"tx": {"flac"}}
	s, manager, runner, _, sources := playbackV7Setup(t, spec, spec, spec, spec)
	if err := manager.Select(sources[2].ID); err != nil {
		t.Fatal(err)
	}
	playbackV7Settings(t, s, true, "flac")
	runner.invoke = func(ctx context.Context, call playbackV7Call) (json.RawMessage, error) {
		limit := 5 * time.Second
		if len(runner.recorded()) == 1 {
			limit = 8 * time.Second
		}
		if call.deadline.IsZero() || time.Until(call.deadline) > limit {
			t.Error("missing bounded preferred/fallback budget")
		}
		return nil, lxruntime.ErrNetwork
	}
	w := playbackV7Request(s, "?quality=flac")
	playbackV7Failure(t, w, 502, "lx_resolve_failed")
	playbackV7Headers(t, w, "resolve", 3, "true")
	calls := runner.recorded()
	if len(calls) != 3 || !strings.HasSuffix(calls[0].code, "2") || !strings.HasSuffix(calls[1].code, "0") || !strings.HasSuffix(calls[2].code, "1") {
		t.Fatal("source ordering or 3-call cap violated")
	}
}
func TestPlaybackV7ExcludeSourcesAreAppliedWithoutInventingAttempts(t *testing.T) {
	for _, auto := range []bool{false, true} {
		t.Run(strconv.FormatBool(auto), func(t *testing.T) {
			spec := map[string][]string{"tx": {"flac"}}
			s, _, runner, _, sources := playbackV7Setup(t, spec, spec)
			playbackV7Settings(t, s, auto, "flac")
			w := playbackV7Request(s, "?quality=flac&excludeSources="+sources[0].ID)
			want := 0
			if auto {
				want = 1
				assertStatus(t, w, 200)
			} else {
				playbackV7Failure(t, w, 502, "media_unavailable")
			}
			playbackV7Headers(t, w, "resolve", want, strconv.FormatBool(auto))
			calls := runner.recorded()
			if len(calls) != want || want > 0 && !strings.HasSuffix(calls[0].code, "1") {
				t.Fatal("excluded active source was retried")
			}
		})
	}
}
func TestPlaybackV7InvalidExcludedSourcesFailBeforeInvocation(t *testing.T) {
	for _, excluded := range []string{"invalid", strings.Repeat("a", 1025), strings.TrimSuffix(strings.Repeat(strings.Repeat("a", 24)+",", 21), ",")} {
		s, _, runner, _, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}})
		w := playbackV7Request(s, "?quality=flac&excludeSources="+url.QueryEscape(excluded))
		playbackV7Failure(t, w, 400, "invalid_resolve_options")
		playbackV7Headers(t, w, "resolve", 0, "true")
		if len(runner.recorded()) != 0 {
			t.Fatal("invalid options invoked source")
		}
	}
}
func TestPlaybackV7UnsupportedFLACNeverFallsBackToLossy(t *testing.T) {
	s, _, runner, _, _ := playbackV7Setup(t, map[string][]string{"tx": {"128k"}, "wy": {"128k"}})
	w := playbackV7Request(s, "?quality=flac")
	playbackV7Failure(t, w, 400, "unsupported_quality")
	playbackV7Headers(t, w, "resolve", 0, "true")
	if len(runner.recorded()) != 0 {
		t.Fatal("flac was silently downgraded")
	}
}
func TestPlaybackV7StrictCrossPlatformAndRepeatedScriptCounting(t *testing.T) {
	for _, sameScript := range []bool{false, true} {
		t.Run(strconv.FormatBool(sameScript), func(t *testing.T) {
			specs := []map[string][]string{{"tx": {"flac"}}, {"wy": {"flac"}}}
			if sameScript {
				specs = []map[string][]string{{"tx": {"flac"}, "wy": {"flac"}}}
			}
			s, manager, runner, _, _ := playbackV7Setup(t, specs...)
			before := manager.List().ActiveID
			runner.invoke = func(_ context.Context, call playbackV7Call) (json.RawMessage, error) {
				if call.platform == "tx" {
					return nil, lxruntime.ErrNetwork
				}
				raw, _ := json.Marshal(playbackV7URL)
				return raw, nil
			}
			w := playbackV7Request(s, "?quality=flac")
			assertStatus(t, w, 200)
			wantSources := 2
			if sameScript {
				wantSources = 1
			}
			playbackV7Headers(t, w, "resolve", wantSources, "true")
			var info model.PlayInfo
			if json.Unmarshal(w.Body.Bytes(), &info) != nil || info.Quality != "flac" || info.ResolvedTrack == nil || info.ResolvedTrack.ProviderID != "wy" || info.TrackID != playbackV7TrackID {
				t.Fatal("strict cross-platform resolution JSON changed")
			}
			calls := runner.recorded()
			if len(calls) != 2 || calls[0].platform != "tx" || calls[1].platform != "wy" || calls[1].quality != "flac" {
				t.Fatal("cross-platform attempts or quality incorrect")
			}
			if manager.List().ActiveID != before {
				t.Fatal("cross-platform changed global active source")
			}
		})
	}
}
func TestPlaybackV7CrossPlatformRejectsAmbiguousOrDifferentVersion(t *testing.T) {
	for _, mismatch := range []string{"ambiguous", "live", "artist", "duration", "lossy"} {
		t.Run(mismatch, func(t *testing.T) {
			qualities := []string{"flac"}
			if mismatch == "lossy" {
				qualities = []string{"128k"}
			}
			s, _, runner, catalogs, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}}, map[string][]string{"wy": qualities})
			candidate := catalogs[1].rows[0]
			switch mismatch {
			case "ambiguous":
				extra := candidate
				extra.ID = "wy:456"
				catalogs[1].rows = append(catalogs[1].rows, extra)
			case "live":
				catalogs[1].rows[0].Title += " (Live)"
			case "artist":
				catalogs[1].rows[0].Artist = "不同歌手"
			case "duration":
				catalogs[1].rows[0].Duration = 260
			}
			runner.invoke = func(context.Context, playbackV7Call) (json.RawMessage, error) { return nil, lxruntime.ErrNetwork }
			w := playbackV7Request(s, "?quality=flac")
			playbackV7Failure(t, w, 502, "lx_resolve_failed")
			playbackV7Headers(t, w, "resolve", 1, "true")
			if len(runner.recorded()) != 1 {
				t.Fatal("unsafe cross-platform candidate invoked")
			}
			if mismatch == "lossy" && catalogs[1].searches != 0 {
				t.Fatal("explicit flac considered lossy cross-platform search")
			}
		})
	}
}
func TestPlaybackV7CancellationStopsFurtherScripts(t *testing.T) {
	spec := map[string][]string{"tx": {"flac"}}
	s, _, runner, _, _ := playbackV7Setup(t, spec, spec, spec)
	entered := make(chan struct{})
	runner.invoke = func(ctx context.Context, _ playbackV7Call) (json.RawMessage, error) {
		close(entered)
		<-ctx.Done()
		return nil, ctx.Err()
	}
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	req := httptest.NewRequest("GET", "http://127.0.0.1:3780/api/v1/tracks/"+playbackV7TrackID+"/play-info?quality=flac", nil).WithContext(ctx)
	w := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { s.ServeHTTP(w, req); close(done) }()
	select {
	case <-entered:
	case <-done:
		t.Fatalf("request ended before resolver: status=%d", w.Code)
	case <-time.After(3 * time.Second):
		t.Fatal("resolver did not start")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cancel did not stop request")
	}
	playbackV7Failure(t, w, 408, "request_cancelled")
	playbackV7Headers(t, w, "resolve", 1, "true")
	if len(runner.recorded()) != 1 {
		t.Fatal("cancelled request invoked next script")
	}
}
func TestPlaybackV7DiagnosticsStayOnPlayInfoAndNeverExposePrivateValues(t *testing.T) {
	s, _, runner, _, sources := playbackV7Setup(t, map[string][]string{"tx": {"flac"}})
	runner.invoke = func(context.Context, playbackV7Call) (json.RawMessage, error) {
		return nil, errors.New("private-configuration-and-upstream-business-marker")
	}
	w := playbackV7Request(s, "?quality=flac")
	playbackV7Failure(t, w, 502, "lx_resolve_failed")
	playbackV7Headers(t, w, "resolve", 1, "true")
	for key, values := range w.Header() {
		for _, value := range values {
			if strings.Contains(value, sources[0].ID) || strings.Contains(value, "private-configuration") || strings.Contains(value, playbackV7URL) {
				t.Fatalf("private value leaked into %s", key)
			}
		}
	}
	if strings.Contains(w.Body.String(), "private-configuration") {
		t.Fatal("private failure message escaped")
	}
	other := request(s, http.MethodGet, "/api/v1/tracks/"+playbackV7TrackID, nil, nil)
	assertStatus(t, other, 200)
	for _, header := range []string{"X-Melora-Resolve-Stage", "X-Melora-Resolve-Attempts", "X-Melora-Auto-Switch"} {
		if other.Header().Get(header) != "" {
			t.Fatal("diagnostic header affected non-play-info handler")
		}
	}
}

func TestPlaybackV7DemoNeverClaimsLXDiagnostics(t *testing.T) {
	s, _, _ := setup(t, "")
	id := s.demo.Daily().Tracks[0].ID
	for _, tc := range []struct {
		quality string
		status  int
	}{{"standard", 200}, {"flac", 400}} {
		w := request(s, "GET", "/api/v1/tracks/"+id+"/play-info?quality="+tc.quality, nil, nil)
		assertStatus(t, w, tc.status)
		for _, header := range []string{"X-Melora-Resolve-Stage", "X-Melora-Resolve-Attempts", "X-Melora-Auto-Switch"} {
			if w.Header().Get(header) != "" {
				t.Fatal("Demo claimed LX diagnostics")
			}
		}
	}
}
func TestPlaybackV7CatalogErrorAfterAttemptRemainsResolveStage(t *testing.T) {
	s, _, runner, catalogs, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}}, map[string][]string{"wy": {"flac"}})
	catalogs[1].infoErr = catalog.ErrUnavailable
	runner.invoke = func(context.Context, playbackV7Call) (json.RawMessage, error) { return nil, lxruntime.ErrNetwork }
	w := playbackV7Request(s, "?quality=flac")
	playbackV7Failure(t, w, 502, "catalog_unavailable")
	playbackV7Headers(t, w, "resolve", 1, "true")
	if len(runner.recorded()) != 1 {
		t.Fatal("failed cross-platform metadata counted as an attempted source")
	}
}
func TestPlaybackV7ColdMetadataFailureCanUseStrictCrossPlatformWithoutCountingOriginal(t *testing.T) {
	s, _, runner, catalogs, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}, "wy": {"flac"}})
	catalogs[0].infoErr = catalog.ErrUnavailable
	w := playbackV7Request(s, "?quality=flac")
	assertStatus(t, w, 200)
	playbackV7Headers(t, w, "resolve", 1, "true")
	calls := runner.recorded()
	if len(calls) != 1 || calls[0].platform != "wy" || calls[0].quality != "flac" {
		t.Fatal("metadata failure invented a TX script attempt")
	}
}
func TestPlaybackV7DisabledSwitchNeverSearchesAnotherPlatform(t *testing.T) {
	s, _, runner, catalogs, _ := playbackV7Setup(t, map[string][]string{"tx": {"flac"}, "wy": {"flac"}})
	playbackV7Settings(t, s, false, "flac")
	runner.invoke = func(context.Context, playbackV7Call) (json.RawMessage, error) { return nil, lxruntime.ErrNetwork }
	w := playbackV7Request(s, "?quality=flac")
	playbackV7Failure(t, w, 502, "lx_resolve_failed")
	playbackV7Headers(t, w, "resolve", 1, "false")
	if len(runner.recorded()) != 1 || catalogs[1].searches != 0 {
		t.Fatal("disabled switching still used cross-platform fallback")
	}
}
func TestPlaybackV7ExcludedAllAndMissingActiveSourceCountZero(t *testing.T) {
	for _, excludedAll := range []bool{false, true} {
		s, manager, runner, _, sources := playbackV7Setup(t, map[string][]string{"tx": {"flac"}})
		query := "?quality=flac"
		if excludedAll {
			query += "&excludeSources=" + sources[0].ID
		} else {
			if err := manager.Select(""); err != nil {
				t.Fatal(err)
			}
			playbackV7Settings(t, s, false, "flac")
		}
		w := playbackV7Request(s, query)
		if excludedAll {
			playbackV7Failure(t, w, 502, "media_unavailable")
			playbackV7Headers(t, w, "resolve", 0, "true")
		} else {
			playbackV7Failure(t, w, 409, "lx_source_required")
			playbackV7Headers(t, w, "resolve", 0, "false")
		}
		if len(runner.recorded()) != 0 {
			t.Fatal("unavailable candidate counted as actual source")
		}
	}
}
func TestPlaybackV7SkippedLossySourceDoesNotIncreaseAttempts(t *testing.T) {
	s, _, runner, _, _ := playbackV7Setup(t, map[string][]string{"tx": {"128k"}}, map[string][]string{"tx": {"flac"}})
	w := playbackV7Request(s, "?quality=flac")
	assertStatus(t, w, 200)
	playbackV7Headers(t, w, "resolve", 1, "true")
	calls := runner.recorded()
	if len(calls) != 1 || calls[0].quality != "flac" || !strings.HasSuffix(calls[0].code, "1") {
		t.Fatal("lossy candidate invoked or counted")
	}
}

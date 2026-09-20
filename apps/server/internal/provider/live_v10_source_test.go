package provider

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"sync"
	"testing"
)

func TestV10HTTPMediaFallbackRespectsAutoSwitchAndQuality(t *testing.T) {
	for _, auto := range []bool{false, true} {
		t.Run(map[bool]string{true: "enabled", false: "disabled"}[auto], func(t *testing.T) {
			l, r, _, sources := liveFixture(t, 4, map[string][]string{"// fixture 1": {"128k"}})
			before := l.Sources.List()
			r.invoke = func(_ context.Context, code string, info map[string]any) (json.RawMessage, error) {
				if info["type"] != "320k" {
					t.Error("fallback downgraded quality")
				}
				switch code {
				case "// fixture 0":
					return json.RawMessage(`"http://8.8.8.8/never-fetched.mp3"`), nil
				case "// fixture 1":
					t.Error("lower-quality source invoked")
				case "// fixture 2":
					return json.RawMessage(`{"url":"https://8.8.8.8/never-fetched.mp3","headers":{"X-Private-Key":"SECRET"}}`), nil
				}
				return json.RawMessage(`"https://8.8.8.8/never-fetched.mp3"`), nil
			}
			out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: auto})
			if auto {
				if err != nil || out.SourceID != sources[3].ID || out.Quality != "320k" || out.URL != "https://8.8.8.8/never-fetched.mp3" || !reflect.DeepEqual(out.AttemptedSources, []string{sources[0].ID, sources[2].ID, sources[3].ID}) {
					t.Fatalf("did not exhaust eligible fallbacks: %+v %v", out, err)
				}
			} else if !errors.Is(err, ErrMixedContent) || out.URL != "" || out.SourceID != "" || !reflect.DeepEqual(out.AttemptedSources, []string{sources[0].ID}) {
				t.Fatalf("explicit false ignored or HTTP success fabricated: %+v %v", out, err)
			}
			if !reflect.DeepEqual(before, l.Sources.List()) {
				t.Fatal("local fallback changed active/ready state")
			}
		})
	}
}

func TestV10HTTPMediaAllFailDoesNotReportSourceSuccess(t *testing.T) {
	l, r, _, sources := liveFixture(t, 5, nil)
	before := l.Sources.List()
	r.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return json.RawMessage(`"http://8.8.8.8/never-fetched.mp3"`), nil
	}
	out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true, PageScheme: "https"})
	var failure *ResolveError
	want := []string{sources[0].ID, sources[1].ID, sources[2].ID}
	if !errors.Is(err, ErrMixedContent) || !errors.As(err, &failure) || !reflect.DeepEqual(failure.Attempts, want) || !reflect.DeepEqual(out.AttemptedSources, want) || out.URL != "" || out.SourceID != "" || out.ResolvedTrack != nil {
		t.Fatalf("all failures fabricated success/lost budget: %+v %v", out, err)
	}
	if !reflect.DeepEqual(before, l.Sources.List()) {
		t.Fatal("one media failure poisoned source initialization state")
	}
}

func TestV10HTTPMediaFallbackConcurrentPerRequest(t *testing.T) {
	l, r, _, sources := liveFixture(t, 2, nil)
	r.invoke = func(_ context.Context, code string, _ map[string]any) (json.RawMessage, error) {
		if code == "// fixture 0" {
			return json.RawMessage(`"http://8.8.8.8/never-fetched.mp3"`), nil
		}
		return json.RawMessage(`"https://8.8.8.8/never-fetched.mp3"`), nil
	}
	before := l.Sources.List()
	var wg sync.WaitGroup
	for i := range 24 {
		wg.Go(func() {
			auto := i%2 == 0
			out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: auto})
			if auto {
				if err != nil || out.SourceID != sources[1].ID || out.Quality != "320k" || len(out.AttemptedSources) != 2 {
					t.Errorf("concurrent fallback: %v", err)
				}
			} else if !errors.Is(err, ErrMixedContent) || len(out.AttemptedSources) != 1 || out.URL != "" {
				t.Error("per-request AutoSwitch policy leaked")
			}
		})
	}
	wg.Wait()
	if !reflect.DeepEqual(before, l.Sources.List()) {
		t.Fatal("concurrent fallback selected another source")
	}
}

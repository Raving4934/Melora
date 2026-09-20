package provider

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"
)

const v18HTTPSource = "http://media.example/audio/a%2Fb.flac?b=2&a=%2f&a=%2F&sig=FIXTURE_ONLY+%2B&empty=&bare"
const v18HTTPSResult = "https://media.example/audio/a%2Fb.flac?b=2&a=%2f&a=%2F&sig=FIXTURE_ONLY+%2B&empty=&bare"

func TestV18HTTPSCapableSourceVerifiedBeforeFallback(t *testing.T) {
	for _, auto := range []bool{false, true} {
		t.Run(map[bool]string{false: "switch-off", true: "switch-on"}[auto], func(t *testing.T) {
			l, runner, _, sources := liveFixture(t, 2, nil)
			before := l.Sources.List()
			probes := 0
			runner.invoke = func(_ context.Context, code string, info map[string]any) (json.RawMessage, error) {
				if code != "// fixture 0" || info["type"] != "320k" {
					t.Error("fallback invoked or quality changed before HTTPS validation")
				}
				return json.Marshal(v18HTTPSource)
			}
			l.verifyPlaybackHTTPS = func(ctx context.Context, raw string) (string, error) {
				probes++
				if raw != v18HTTPSource {
					t.Error("signature rewritten before probe")
				}
				deadline, ok := ctx.Deadline()
				if !ok || time.Until(deadline) > 8*time.Second {
					t.Error("probe outside attempt budget")
				}
				return v18HTTPSResult, nil
			}
			out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: auto, PageScheme: "https"})
			if err != nil || out.URL != v18HTTPSResult || !out.Direct || out.Quality != "320k" || out.TrackID != fixtureTrack().ID || out.SourceID != sources[0].ID || probes != 1 || len(runner.calls) != 1 || len(out.AttemptedSources) != 1 || out.ResolvedTrack == nil || out.ResolvedTrack.ID != fixtureTrack().ID {
				t.Fatalf("verified source failed/changed identity: %+v %v probes=%d", out, err, probes)
			}
			if !reflect.DeepEqual(before, l.Sources.List()) {
				t.Fatal("local HTTPS validation changed source status/active")
			}
		})
	}
}

func TestV18HTTPSUpgradeOnlyRunsForHTTPOnSecurePlayback(t *testing.T) {
	for _, tc := range []struct {
		page, raw       string
		playback, probe bool
	}{
		{"http", "http://8.8.8.8/original.mp3", true, false},
		{"https", "https://8.8.8.8/original.mp3", true, false},
		{"https", "http://8.8.8.8/original.mp3", false, false},
		{"", v18HTTPSource, true, true},
		{"https", v18HTTPSource, true, true},
	} {
		t.Run(tc.page+tc.raw+map[bool]string{true: "play", false: "download"}[tc.playback], func(t *testing.T) {
			called := false
			l := &Live{verifyPlaybackHTTPS: func(context.Context, string) (string, error) { called = true; return v18HTTPSResult, nil }}
			out, err := l.prepareResolvedMedia(t.Context(), tc.raw, tc.page, tc.playback)
			want := tc.raw
			if tc.probe {
				want = v18HTTPSResult
			}
			if err != nil || out != want || called != tc.probe {
				t.Fatalf("path changed: %s %v probe=%v", out, err, called)
			}
		})
	}
}

func TestV18HTTPSProbeFailureKeepsAutoSwitchQualityExclusionsAndAttempts(t *testing.T) {
	for _, auto := range []bool{false, true} {
		t.Run(map[bool]string{false: "off", true: "on"}[auto], func(t *testing.T) {
			l, r, _, sources := liveFixture(t, 5, map[string][]string{"// fixture 1": {"128k"}})
			before := l.Sources.List()
			probes := 0
			r.invoke = func(_ context.Context, code string, info map[string]any) (json.RawMessage, error) {
				if info["type"] != "320k" || code == "// fixture 1" || code == "// fixture 2" {
					t.Error("quality downgraded or excluded source invoked")
				}
				if code == "// fixture 3" {
					return json.RawMessage(`"https://8.8.8.8/final.mp3"`), nil
				}
				return json.Marshal(v18HTTPSource)
			}
			l.verifyPlaybackHTTPS = func(context.Context, string) (string, error) {
				probes++
				return "", errors.New("private sig=FIXTURE_ONLY")
			}
			out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: auto, ExcludeSources: []string{sources[2].ID}, PageScheme: "https"})
			if auto {
				if err != nil || out.SourceID != sources[3].ID || len(out.AttemptedSources) != 2 || len(r.calls) != 2 || probes != 1 {
					t.Fatalf("fallback changed: %+v %v calls=%v probes=%d", out, err, r.calls, probes)
				}
			} else if !errors.Is(err, ErrMixedContent) || len(r.calls) != 1 || probes != 1 || out.URL != "" || out.Direct || out.SourceID != "" || out.ResolvedTrack != nil || strings.Contains(err.Error(), "FIXTURE_ONLY") {
				t.Fatalf("disabled fallback/leak: %+v %v", out, err)
			}
			if !reflect.DeepEqual(before, l.Sources.List()) {
				t.Fatal("active/ready changed")
			}
		})
	}
}

func TestV18HTTPSProbeAllFailSharesThreeInvocationsAndEightFiveBudgets(t *testing.T) {
	l, r, _, _ := liveFixture(t, 5, nil)
	probes := 0
	r.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return json.Marshal(v18HTTPSource)
	}
	l.verifyPlaybackHTTPS = func(ctx context.Context, raw string) (string, error) {
		probes++
		maxBudget := 5 * time.Second
		if probes == 1 {
			maxBudget = 8 * time.Second
		}
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > maxBudget || raw != v18HTTPSource {
			t.Error("new attempt/signature budget invented")
		}
		return "", context.DeadlineExceeded // 只耗尽probe的短预算，而非外层ctx。
	}
	out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: true, PageScheme: "https"})
	if !errors.Is(err, ErrMixedContent) || probes != 3 || len(r.calls) != 3 || len(out.AttemptedSources) != 3 || out.URL != "" || !strings.Contains(err.Error(), "未能确认可用HTTPS") {
		t.Fatalf("unconfirmed result or budget wrong: %+v %v probes=%d", out, err, probes)
	}
}

func TestV18HTTPSProbeKeepsRequiredHeadersRejectedBeforeNetwork(t *testing.T) {
	for _, headers := range []any{map[string]any{"Referer": "FIXTURE_ONLY"}, map[string]any{"Cookie": "FIXTURE_ONLY"}, map[string]any{"X-Private-Key": "FIXTURE_ONLY"}, "invalid", map[string]any{}} {
		l, r, _, _ := liveFixture(t, 1, nil)
		probes := 0
		r.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
			return json.Marshal(map[string]any{"data": map[string]any{"url": v18HTTPSource, "headers": headers}})
		}
		l.verifyPlaybackHTTPS = func(context.Context, string) (string, error) { probes++; return v18HTTPSResult, nil }
		out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{PageScheme: "https"})
		empty := reflect.DeepEqual(headers, map[string]any{})
		if empty {
			if err != nil || probes != 1 || out.URL != v18HTTPSResult {
				t.Fatal("empty headers rejected", err)
			}
		} else if !errors.Is(err, ErrMediaHeaders) || probes != 0 || strings.Contains(err.Error(), "FIXTURE_ONLY") {
			t.Fatalf("required headers ignored: %v probes=%d", err, probes)
		}
	}
}

func TestV18HTTPSProbeCancellationDiscardsLateResultAndStopsFallback(t *testing.T) {
	l, r, _, _ := liveFixture(t, 4, nil)
	probes := 0
	ctx, cancel := context.WithTimeout(t.Context(), 60*time.Millisecond)
	defer cancel()
	r.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return json.Marshal(v18HTTPSource)
	}
	l.verifyPlaybackHTTPS = func(inner context.Context, _ string) (string, error) {
		probes++
		deadline, ok := inner.Deadline()
		if !ok || time.Until(deadline) > 60*time.Millisecond {
			t.Error("caller budget reset")
		}
		cancel()
		return v18HTTPSResult, nil
	}
	out, err := l.PlayInfoWithOptions(ctx, fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true, PageScheme: "https"})
	if !errors.Is(err, context.Canceled) || probes != 1 || len(r.calls) != 1 || out.URL != "" || out.Direct || out.ResolvedTrack != nil {
		t.Fatalf("late result/candidate accepted: %+v %v", out, err)
	}
}

func TestV18HTTPSProbeDoesNotCacheHostOrSignedURLResult(t *testing.T) {
	l, r, _, _ := liveFixture(t, 1, nil)
	calls := 0
	r.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return json.Marshal(v18HTTPSource)
	}
	l.verifyPlaybackHTTPS = func(context.Context, string) (string, error) {
		calls++
		if calls == 1 {
			return v18HTTPSResult, nil
		}
		return "", errors.New("expired signature")
	}
	if _, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{PageScheme: "https"}); err != nil {
		t.Fatal(err)
	}
	if out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{PageScheme: "https"}); !errors.Is(err, ErrMixedContent) || out.URL != "" || calls != 2 {
		t.Fatal("expired/signed URL reused", err)
	}
}

func TestV18HTTPSProbeCannotReturnUnverifiedProtocolAsSuccess(t *testing.T) {
	for _, invalid := range []string{"", "not a URL", "http://media.example/audio.mp3", "//media.example/a", "https://user:FIXTURE@media.example/a", "https://media.example/a#part"} {
		l := &Live{verifyPlaybackHTTPS: func(context.Context, string) (string, error) { return invalid, nil }}
		out, err := l.prepareResolvedMedia(t.Context(), v18HTTPSource, "https", true)
		if !errors.Is(err, ErrMixedContent) || out != "" {
			t.Fatalf("non-HTTPS verifier result accepted: %q %v", out, err)
		}
	}
}

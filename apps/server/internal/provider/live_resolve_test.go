package provider

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"

	"melora/internal/lxruntime"
	"melora/internal/lxsource"
)

func TestSelectSourceQualityUsesTheSourceDescriptorName(t *testing.T) {
	tests := []struct {
		name      string
		qualities []string
		requested string
		want      string
		ok        bool
	}{
		{name: "exact name wins", qualities: []string{"master", "flac24bit"}, requested: "flac24bit", want: "flac24bit", ok: true},
		{name: "hires alias", qualities: []string{"flac", "hires"}, requested: "flac24bit", want: "hires", ok: true},
		{name: "flac32bit alias", qualities: []string{"flac32bit"}, requested: "hires", want: "flac32bit", ok: true},
		{name: "master alias", qualities: []string{"master"}, requested: "flac24bit", want: "master", ok: true},
		{name: "lossless is not high resolution", qualities: []string{"flac", "320k"}, requested: "flac24bit", ok: false},
		{name: "unrelated quality is not an alias", qualities: []string{"hires"}, requested: "flac", ok: false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, ok := selectSourceQuality(test.qualities, test.requested)
			if got != test.want || ok != test.ok {
				t.Fatalf("selectSourceQuality(%v, %q) = %q, %v; want %q, %v", test.qualities, test.requested, got, ok, test.want, test.ok)
			}
		})
	}
}

func TestLiveResolvePassesEachCandidatesActualHighResolutionQuality(t *testing.T) {
	l, runner, _, sources := liveFixture(t, 3, map[string][]string{
		"// fixture 0": {"flac24bit"},
		"// fixture 1": {"hires"},
		"// fixture 2": {"master"},
	})
	var seen []string
	runner.invoke = func(_ context.Context, code string, info map[string]any) (json.RawMessage, error) {
		seen = append(seen, code+":"+info["type"].(string))
		if code != "// fixture 2" {
			return nil, lxruntime.ErrScript
		}
		return json.RawMessage(`"https://8.8.8.8/source.flac"`), nil
	}

	out, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "flac24bit", ResolveOptions{AutoSwitch: true})
	if err != nil || out.SourceID != sources[2].ID || out.Quality != "flac24bit" || out.MIMEType != "audio/flac" {
		t.Fatalf("high-resolution alias resolution failed: %+v %v", out, err)
	}
	want := []string{"// fixture 0:flac24bit", "// fixture 1:hires", "// fixture 2:master"}
	if !reflect.DeepEqual(seen, want) {
		t.Fatalf("candidate quality names = %v; want %v", seen, want)
	}
}

func TestLiveResolveDoesNotDowngradeRequestedHighResolutionQuality(t *testing.T) {
	l, runner, _, _ := liveFixture(t, 1, map[string][]string{
		"// fixture 0": {"flac", "320k", "128k"},
	})
	runner.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		t.Fatal("unsupported high-resolution request invoked a source")
		return nil, nil
	}

	out, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "flac24bit", ResolveOptions{AutoSwitch: true})
	if !errors.Is(err, ErrQuality) || out.URL != "" || out.SourceID != "" || len(runner.calls) != 0 {
		t.Fatalf("high-resolution request was downgraded or invoked: %+v %v calls=%v", out, err, runner.calls)
	}
}

func TestMediaMIMERecognizesHighResolutionAliases(t *testing.T) {
	for _, quality := range []string{"flac24bit", "hires", "flac32bit", "master"} {
		if got := mediaMIME("https://8.8.8.8/source", quality); got != "audio/flac" {
			t.Errorf("mediaMIME(%q) = %q; want audio/flac", quality, got)
		}
	}
}

func TestLiveResolveAcceptsGenericSourceResultContract(t *testing.T) {
	l, runner, _, sources := liveFixture(t, 1, map[string][]string{"// fixture 0": {"320k"}})
	runner.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return json.RawMessage(`{"url":"https://8.8.8.8/source.mp3","type":"320k","source":"wy","musicInfo":{"source":"wy","songmid":"123","name":"fixture","singer":"fixture artist"},"resourceId":"physical-1"}`), nil
	}

	out, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: true})
	if err != nil || out.URL != "https://8.8.8.8/source.mp3" || out.SourceID != sources[0].ID || out.Quality != "320k" || out.ResolvedSource != "wy" || !strings.HasPrefix(out.ResourceID, "lx:"+sources[0].ID+":") {
		t.Fatalf("generic source result contract was not preserved: %+v %v", out, err)
	}
}

func TestLiveResolveKeepsReturnedQualityDescriptor(t *testing.T) {
	l, runner, _, _ := liveFixture(t, 1, map[string][]string{"// fixture 0": {"hires"}})
	runner.invoke = func(_ context.Context, _ string, info map[string]any) (json.RawMessage, error) {
		if info["type"] != "hires" {
			t.Fatalf("source was not called with its descriptor quality: %#v", info["type"])
		}
		return json.RawMessage(`{"url":"https://8.8.8.8/source.flac","type":"hires","source":"wy","musicInfo":{"source":"wy","songmid":"123","name":"fixture","singer":"fixture artist"},"resourceId":"physical-hires"}`), nil
	}

	out, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "flac24bit", ResolveOptions{AutoSwitch: true})
	if err != nil || out.Quality != "hires" || out.MIMEType != "audio/flac" || !strings.HasPrefix(out.ResourceID, "lx:") {
		t.Fatalf("returned quality was lost or downgraded: %+v %v", out, err)
	}
}

func TestLiveResolveRejectsMismatchedContractIdentity(t *testing.T) {
	l, runner, _, _ := liveFixture(t, 1, map[string][]string{"// fixture 0": {"320k"}})
	runner.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
		return json.RawMessage(`{"url":"https://8.8.8.8/wrong.mp3","type":"320k","source":"wy","musicInfo":{"source":"wy","songmid":"not-the-track","name":"fixture","singer":"fixture artist"},"resourceId":"shared-physical"}`), nil
	}

	out, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: true})
	if !errors.Is(err, ErrMediaResult) || out.URL != "" || out.ResourceID != "" || out.ResolvedTrack != nil {
		t.Fatalf("mismatched source identity was accepted: %+v %v", out, err)
	}
}

func TestSourceResultCanReportDifferentCanonicalPlatform(t *testing.T) {
	candidate := resolveCandidate{
		source: lxsource.Source{
			ID:     "aaaaaaaaaaaaaaaaaaaaaaaa",
			Status: "ready",
			Platforms: map[string]lxruntime.Source{
				"wy": {Actions: []string{"musicUrl"}, Qualitys: []string{"320k"}},
			},
		},
		quality: "320k",
	}
	result, err := parseSourceMediaResult(json.RawMessage(`{"url":"https://8.8.8.8/kw.mp3","type":"320k","source":"tx","musicInfo":{"source":"tx","songmid":"tx-123","name":"fixture","singer":"fixture artist"},"resourceId":"tx-physical"}`), candidate, fixtureTrack(), map[string]any{"source": "wy", "songmid": "123", "name": "fixture", "singer": "fixture artist"})
	if err != nil || result.Platform != "tx" || result.Quality != "320k" || !strings.HasPrefix(result.ResourceID, "lx:") {
		t.Fatalf("cross-platform contract result rejected or normalized incorrectly: %+v %v", result, err)
	}
}

func TestSourceResultObjectIsNotTreatedAsLegacyNestedURL(t *testing.T) {
	candidate := resolveCandidate{
		source: lxsource.Source{
			ID:     "aaaaaaaaaaaaaaaaaaaaaaaa",
			Status: "ready",
			Platforms: map[string]lxruntime.Source{
				"wy": {Actions: []string{"musicUrl"}, Qualitys: []string{"320k"}},
			},
		},
		quality: "320k",
	}
	_, err := parseSourceMediaResult(json.RawMessage(`{"data":{"url":"https://8.8.8.8/nested.mp3"},"type":"320k"}`), candidate, fixtureTrack(), nil)
	if !errors.Is(err, ErrMediaResult) {
		t.Fatalf("extension object fell back to nested URL decoding: %v", err)
	}
}

func TestResourceIdentityIsScopedByScriptTrackAndQuality(t *testing.T) {
	first := scopedResourceID("script-a", "wy", "track-a", "320k", "file-1")
	if first != scopedResourceID("script-a", "wy", "track-a", "320k", "file-1") {
		t.Fatal("unstable identity")
	}
	for _, next := range []string{
		scopedResourceID("script-b", "wy", "track-a", "320k", "file-1"),
		scopedResourceID("script-a", "wy", "track-b", "320k", "file-1"),
		scopedResourceID("script-a", "wy", "track-a", "flac", "file-1"),
		scopedResourceID("script-a", "wy", "track-a", "320k", "file-2"),
	} {
		if first == next {
			t.Fatal("different resource scopes collided")
		}
	}
}

func TestLiveResolveNoSourceDoesNotFetchCatalogMetadata(t *testing.T) {
	l, _, catalogFixture, _ := liveFixture(t, 0, nil)
	_, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: true})
	if !errors.Is(err, lxsource.ErrNoActive) || catalogFixture.calls.Load() != 0 {
		t.Fatalf("no-source resolution reached metadata: err=%v calls=%d", err, catalogFixture.calls.Load())
	}
}

func TestLiveResolveKeepsBasicLossyCodecAndBitrate(t *testing.T) {
	for _, tc := range []struct{ actual, mime string }{
		{"aac100k", "audio/aac"}, {"ogg96k", "audio/ogg"}, {"64k", "audio/mpeg"},
		{"aac", "audio/aac"}, {"ogg", "audio/ogg"}, {"mp3", "audio/mpeg"},
	} {
		t.Run(tc.actual, func(t *testing.T) {
			l, runner, _, _ := liveFixture(t, 1, map[string][]string{"// fixture 0": {"128k"}})
			runner.invoke = func(context.Context, string, map[string]any) (json.RawMessage, error) {
				return json.Marshal(map[string]string{"url": "https://8.8.8.8/media", "type": tc.actual, "resourceId": "physical-basic"})
			}
			out, err := l.ResolveWithOptions(t.Context(), fixtureTrack(), "standard", ResolveOptions{})
			if err != nil || out.Quality != tc.actual || out.MIMEType != tc.mime || out.ResourceID == "" {
				t.Fatalf("actual lossy metadata lost: quality=%q mime=%q resource=%q err=%v", out.Quality, out.MIMEType, out.ResourceID, err)
			}
		})
	}
}

func TestReturnedLossyDescriptorDoesNotDowngradeHigherRequests(t *testing.T) {
	qualities := []string{"128k", "192k", "320k", "flac", "flac24bit"}
	for _, requested := range qualities[1:] {
		for _, actual := range []string{"aac100k", "ogg96k", "64k", "aac", "ogg", "mp3"} {
			if compatibleReturnedQuality(qualities, requested, actual) {
				t.Fatalf("request %q silently accepted basic %q", requested, actual)
			}
		}
	}
	for _, invalid := range []string{"aac0k", "aac-1k", "aac99999k", "oggNaNk", "unknown", "128k-trial"} {
		if compatibleReturnedQuality(qualities, "128k", invalid) {
			t.Fatalf("invalid actual quality accepted: %q", invalid)
		}
	}
}

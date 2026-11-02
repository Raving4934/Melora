package provider

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"melora/internal/lxruntime"
	"melora/internal/lxsource"
)

func TestValidResourceID(t *testing.T) {
	for _, test := range []struct {
		name  string
		value string
		valid bool
	}{
		{name: "short identity", value: "physical-1", valid: true},
		{name: "byte limit", value: strings.Repeat("a", 16<<10), valid: true},
		{name: "UTF8 byte limit", value: strings.Repeat("é", 8<<10), valid: true},
		{name: "empty"},
		{name: "over byte limit", value: strings.Repeat("a", (16<<10)+1)},
		{name: "UTF8 over byte limit", value: strings.Repeat("é", 8<<10) + "a"},
		{name: "NUL", value: "file\x00id"},
		{name: "newline", value: "file\nid"},
		{name: "tab", value: "file\tid"},
		{name: "DEL", value: "file\x7fid"},
		{name: "C1 control", value: "file\u0085id"},
		{name: "format character", value: "file\u200bid"},
		{name: "invalid UTF8", value: "file\xffid"},
		{name: "truncated UTF8", value: "file\xe4\xb8"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := validResourceID(test.value); got != test.valid {
				t.Fatalf("validResourceID(%d bytes) = %v; want %v", len(test.value), got, test.valid)
			}
		})
	}
}

func TestParseSourceMediaResultResourceID(t *testing.T) {
	const urlPrefix = "https://8.8.8.8/"
	url600 := urlPrefix + strings.Repeat("a", 600-len(urlPrefix))
	url8192 := urlPrefix + strings.Repeat("a", 8192-len(urlPrefix))
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
	actual := fixtureTrack()
	for _, test := range []struct {
		name       string
		url        string
		resourceID string
		invalid    bool
	}{
		{name: "short identity", url: url600, resourceID: "physical-1"},
		{name: "600 byte URL with format prefix", url: url600, resourceID: "url:320k:" + url600},
		{name: "8192 byte URL with format prefix", url: url8192, resourceID: "url:320k:" + url8192},
		{name: "identity at byte limit", url: url600, resourceID: strings.Repeat("a", 16<<10)},
		{name: "identity over byte limit", url: url600, resourceID: strings.Repeat("a", (16<<10)+1), invalid: true},
		{name: "control character", url: url600, resourceID: "file\nid", invalid: true},
		{name: "format character", url: url600, resourceID: "file\u200bid", invalid: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			raw, err := json.Marshal(map[string]string{
				"url": test.url, "type": candidate.quality, "resourceId": test.resourceID,
			})
			if err != nil {
				t.Fatal(err)
			}
			result, err := parseSourceMediaResult(raw, candidate, actual, nil)
			if test.invalid {
				if !errors.Is(err, ErrMediaResult) || !reflect.DeepEqual(result, sourceMediaResult{}) {
					t.Fatalf("invalid resource identity accepted: %+v %v", result, err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			payload := strings.Join([]string{actual.ProviderID, actual.ID, candidate.quality, test.resourceID}, "\x00")
			want := sourceMediaResult{
				URL: test.url, Quality: candidate.quality, ReportedQuality: true,
				Platform: actual.ProviderID, Track: actual,
				ResourceID: fmt.Sprintf("lx:%s:%x", candidate.source.ID, sha256.Sum256([]byte(payload))),
			}
			if !reflect.DeepEqual(result, want) {
				t.Fatalf("source result = %+v; want %+v", result, want)
			}
		})
	}
}

func TestResourceIdentityIncludesLongResourceIDTail(t *testing.T) {
	for _, size := range []int{600, 8192, 16 << 10} {
		t.Run(fmt.Sprintf("%d bytes", size), func(t *testing.T) {
			const prefix = "url:320k:https://8.8.8.8/"
			resource := prefix + strings.Repeat("a", size-len(prefix)-1)
			first := scopedResourceID("script-a", "wy", "wy:123", "320k", resource+"a")
			second := scopedResourceID("script-a", "wy", "wy:123", "320k", resource+"b")
			if first == second {
				t.Fatal("resource identities differing only in the final byte collided")
			}
		})
	}
}

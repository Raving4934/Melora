package downloadassets

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"math"
	"strings"
	"testing"
	"time"

	"melora/internal/catalog"
	"melora/internal/download"
	"melora/internal/model"
)

func v8PNG(t *testing.T) []byte {
	t.Helper()
	var out bytes.Buffer
	if err := png.Encode(&out, image.NewNRGBA(image.Rect(0, 0, 2, 2))); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}

func TestV8PartialAssetsReturnBeforeParentDeadline(t *testing.T) {
	for _, slow := range []string{"lyrics", "cover"} {
		t.Run(slow, func(t *testing.T) {
			parent, cancel := context.WithTimeout(t.Context(), 600*time.Millisecond)
			defer cancel()
			picture := v8PNG(t)
			lyric := func(ctx context.Context, id string) (catalog.Lyrics, error) {
				if id != "tx:fixture-mid" {
					t.Error("lyrics getter did not receive canonical track ID")
				}
				if slow == "lyrics" {
					<-ctx.Done()
					return catalog.Lyrics{}, ctx.Err()
				}
				return catalog.Lyrics{Lines: []catalog.LyricLine{{Time: 1, Text: "保留成功歌词"}}}, nil
			}
			cover := func(ctx context.Context, _ string) ([]byte, error) {
				if slow == "cover" {
					<-ctx.Done()
					return nil, ctx.Err()
				}
				return picture, nil
			}
			got, err := fetch(parent, model.DownloadJob{EmbedTags: true,
				Track: model.Track{ID: "tx:fixture-mid", CoverURL: "https://cover.example/fixture.png"}}, lyric, cover)
			if err == nil {
				t.Fatal("cancelled asset did not report partial failure")
			}
			// 复现调用者真实的 fail-safe：父 ctx 已过期时不会使用任何返回素材。
			accepted := got
			if parent.Err() != nil {
				accepted = download.MetadataAssets{}
			}
			if slow == "lyrics" && !bytes.Equal(accepted.Cover, picture) || slow == "cover" && accepted.Lyrics == "" {
				t.Fatal("successful sibling asset was lost at the outer deadline")
			}
			if parent.Err() != nil {
				t.Fatal("fetch did not reserve time before parent deadline")
			}
		})
	}
}

func TestV8InternalBudgetDoesNotReachOuterNineSeconds(t *testing.T) {
	lyric := func(ctx context.Context, _ string) (catalog.Lyrics, error) {
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > 8*time.Second {
			t.Error("internal budget exceeds eight seconds")
		}
		return catalog.Lyrics{Lines: []catalog.LyricLine{{Time: 1, Text: "有效歌词"}}}, nil
	}
	if _, err := fetch(t.Context(), model.DownloadJob{WriteLyrics: true}, lyric, nil); err != nil {
		t.Fatal(err)
	}
}

func TestV8LyricsIgnoreNonFiniteTimeAndNormalizeControls(t *testing.T) {
	lyric := func(context.Context, string) (catalog.Lyrics, error) {
		return catalog.Lyrics{Lines: []catalog.LyricLine{
			{Time: math.NaN(), Text: "不能保存无效时间"},
			{Time: math.Inf(1), Text: "不能保存无限时间"},
			{Time: 65.25, Text: "第一\u200b行\x01\n下一行"},
		}}, nil
	}
	got, err := fetch(t.Context(), model.DownloadJob{WriteLyrics: true}, lyric, nil)
	if err != nil || got.Lyrics != "[01:05.25]第一 行  下一行\n" {
		t.Fatal("unsafe time/control records prevented safe timed lyrics")
	}
}

func TestV8LegacyHTTPRemainsBlockedWithSafeReason(t *testing.T) {
	lyric := func(context.Context, string) (catalog.Lyrics, error) {
		return catalog.Lyrics{Lines: []catalog.LyricLine{{Time: 1, Text: "歌词仍应成功"}}}, nil
	}
	got, err := fetch(t.Context(), model.DownloadJob{WriteLyrics: true, WriteCover: true,
		Track: model.Track{CoverURL: "http://cover.example/fixture.jpg"}}, lyric, cover)
	if err == nil || !strings.Contains(err.Error(), "cover_http_not_allowed") || got.Lyrics == "" || len(got.Cover) != 0 {
		t.Fatal("HTTP policy failure was hidden or discarded successful lyrics")
	}
	if strings.Contains(err.Error(), "cover.example") || strings.Contains(err.Error(), "http://") {
		t.Fatal("HTTP policy diagnostic leaked the raw URL")
	}
}

func TestV8GetterFailuresNeverExposeRawDetails(t *testing.T) {
	bad := errors.New("private request details must not surface")
	_, err := fetch(t.Context(), model.DownloadJob{WriteLyrics: true, WriteCover: true},
		func(context.Context, string) (catalog.Lyrics, error) { return catalog.Lyrics{}, bad },
		func(context.Context, string) ([]byte, error) { return nil, bad })
	if err == nil || strings.Contains(err.Error(), "private request") {
		t.Fatal("asset failure exposed raw getter details")
	}
}

func TestV8MissingCoverLooksUpSameIDOnlyOnce(t *testing.T) {
	for _, tc := range []struct {
		name, snapshot, detailID string
		selected                 bool
		lookups, covers          int
		wantError                bool
	}{
		{"missing", "", "tx:fixture-mid", true, 1, 1, false},
		{"existing", "https://cover.example/existing.png", "tx:fixture-mid", true, 0, 1, false},
		{"wrong_identity", "", "kw:123", true, 1, 0, true},
		{"unverified_old_url", "http://img4.kwcdn.kuwo.cn/unknown.jpg", "tx:fixture-mid", true, 0, 1, false},
		{"unselected", "", "tx:fixture-mid", false, 0, 0, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			lookups, covers := 0, 0
			picture := v8PNG(t)
			detail := func(ctx context.Context, id string) (model.Track, error) {
				lookups++
				if id != "tx:fixture-mid" {
					t.Error("detail lookup changed canonical ID")
				}
				if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > 8*time.Second {
					t.Error("detail lookup escaped shared asset budget")
				}
				return model.Track{ID: tc.detailID, CoverURL: "https://cover.example/detail.png"}, nil
			}
			get := func(_ context.Context, raw string) ([]byte, error) {
				covers++
				want := catalog.NormalizeCoverURL(tc.snapshot)
				if want == "" {
					want = "https://cover.example/detail.png"
				}
				if raw != want {
					t.Error("cover did not use the verified same-ID metadata or existing snapshot")
				}
				return picture, nil
			}
			got, err := fetchWithTrack(t.Context(), model.DownloadJob{WriteCover: tc.selected, Quality: "flac",
				Track: model.Track{ID: "tx:fixture-mid", ProviderID: "kw", CoverURL: tc.snapshot}}, nil, get, detail)
			if (err != nil) != tc.wantError || lookups != tc.lookups || covers != tc.covers {
				t.Fatal("missing-only detail request boundary changed")
			}
			if tc.covers == 1 && (!bytes.Equal(got.Cover, picture) || got.CoverMIME != "image/png") {
				t.Fatal("same-ID cover was not preserved")
			}
		})
	}
}

func TestV8DetailLookupSharesBudgetAndPreservesLyrics(t *testing.T) {
	parent, cancel := context.WithTimeout(t.Context(), 400*time.Millisecond)
	defer cancel()
	lyric := func(context.Context, string) (catalog.Lyrics, error) {
		return catalog.Lyrics{Lines: []catalog.LyricLine{{Time: 1, Text: "详情慢也保留歌词"}}}, nil
	}
	calls := 0
	detail := func(ctx context.Context, _ string) (model.Track, error) {
		calls++
		<-ctx.Done()
		return model.Track{}, ctx.Err()
	}
	got, err := fetchWithTrack(parent, model.DownloadJob{EmbedTags: true, Track: model.Track{ID: "tx:fixture-mid"}}, lyric,
		func(context.Context, string) ([]byte, error) {
			t.Error("cover fetch ran after detail timeout")
			return nil, nil
		}, detail)
	if err == nil || !strings.Contains(err.Error(), "cover_deadline") || parent.Err() != nil || calls != 1 || got.Lyrics == "" {
		t.Fatal("slow detail lookup discarded lyrics or exceeded parent deadline")
	}
}

type v8LyricAdapter struct {
	catalog.Adapter
	calledID string
}

func (a *v8LyricAdapter) Lyrics(_ context.Context, id string) (catalog.Lyrics, error) {
	a.calledID = id
	return catalog.Lyrics{Lines: []catalog.LyricLine{{Time: 1, Text: "规范ID歌词"}}}, nil
}

func TestV8FetcherForwardsCanonicalTrackIDNotQualityOrProviderField(t *testing.T) {
	adapter := &v8LyricAdapter{}
	registry := catalog.NewRegistry(map[string]catalog.Adapter{"tx": adapter})
	got, err := Fetcher(registry)(t.Context(), model.DownloadJob{WriteLyrics: true, Quality: "flac",
		Track: model.Track{ID: "tx:fixture-mid", ProviderID: "kw", Title: "不按歌名匹配"}})
	if err != nil || got.Lyrics == "" || adapter.calledID != "tx:fixture-mid" {
		t.Fatal("Fetcher changed lyric provider lookup parameters")
	}
}

func TestV8CoverFormatAndSizeLimitsRemainConservative(t *testing.T) {
	var jpegData, gifData bytes.Buffer
	picture := image.NewNRGBA(image.Rect(0, 0, 2, 2))
	if err := jpeg.Encode(&jpegData, picture, nil); err != nil {
		t.Fatal(err)
	}
	if err := gif.Encode(&gifData, picture, nil); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name, mime string
		data       []byte
	}{
		{"jpeg", "image/jpeg", jpegData.Bytes()},
		{"png", "image/png", v8PNG(t)},
		{"gif", "", gifData.Bytes()},
		{"webp", "", []byte("RIFF0000WEBPVP8 fixture")},
		{"svg", "", []byte("<svg/> ")},
		{"oversized", "", bytes.Repeat([]byte{0}, (1<<20)+1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := fetch(t.Context(), model.DownloadJob{WriteCover: true}, nil,
				func(context.Context, string) ([]byte, error) { return tc.data, nil })
			if tc.mime == "" {
				if err == nil || len(got.Cover) != 0 {
					t.Fatal("unsupported/oversized cover was passed to the writer")
				}
			} else if err != nil || got.CoverMIME != tc.mime || !bytes.Equal(got.Cover, tc.data) {
				t.Fatal("JPEG/PNG asset bytes or detected MIME changed")
			}
		})
	}
}

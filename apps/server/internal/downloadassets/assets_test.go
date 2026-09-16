package downloadassets

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/png"
	"melora/internal/catalog"
	"melora/internal/model"
	"strings"
	"testing"
)

func TestAttachmentsAreOnlyFetchedWhenSelectedAndFailuresArePartial(t *testing.T) {
	calls := 0
	noLyrics := func(context.Context, string) (catalog.Lyrics, error) {
		calls++
		return catalog.Lyrics{}, errors.New("do not leak secret URL")
	}
	noCover := func(context.Context, string) ([]byte, error) { calls++; return nil, errors.New("private signed URL") }
	empty, err := fetch(t.Context(), model.DownloadJob{}, noLyrics, noCover)
	if err != nil || calls != 0 || empty.Lyrics != "" {
		t.Fatal("unselected assets fetched")
	}
	var pngData bytes.Buffer
	png.Encode(&pngData, image.NewRGBA(image.Rect(0, 0, 2, 2)))
	got, err := fetch(t.Context(), model.DownloadJob{WriteLyrics: true, WriteCover: true}, noLyrics, func(context.Context, string) ([]byte, error) { return pngData.Bytes(), nil })
	if err == nil || strings.Contains(err.Error(), "secret") || len(got.Cover) == 0 || got.CoverMIME != "image/png" || got.Lyrics != "" {
		t.Fatal(got, err)
	}
}
func TestLyricsAreTimedAndMetadataOnlySelectionFetchesEmbeddedAssets(t *testing.T) {
	lyric := func(context.Context, string) (catalog.Lyrics, error) {
		return catalog.Lyrics{Lines: []catalog.LyricLine{{Time: 65.25, Text: "第一行\n[00:00]不能成为新纪录"}}}, nil
	}
	got, err := fetch(t.Context(), model.DownloadJob{EmbedTags: true}, lyric, func(context.Context, string) ([]byte, error) { return []byte("<svg onload=bad/>"), nil })
	if err == nil || got.Lyrics != "[01:05.25]第一行 [00:00]不能成为新纪录\n" || len(got.Cover) != 0 {
		t.Fatal(got, err)
	}
}
func TestMalformedImageAndOversizedLyricsAreNotSaved(t *testing.T) {
	lyric := func(context.Context, string) (catalog.Lyrics, error) {
		lines := []catalog.LyricLine{}
		for i := 0; i < 300; i++ {
			lines = append(lines, catalog.LyricLine{Time: float64(i), Text: strings.Repeat("a", 8000)})
		}
		return catalog.Lyrics{Lines: lines}, nil
	}
	got, err := fetch(t.Context(), model.DownloadJob{WriteLyrics: true, WriteCover: true}, lyric, func(context.Context, string) ([]byte, error) { return []byte("ID3 definitely not cover"), nil })
	if err == nil || got.Lyrics != "" || len(got.Cover) != 0 {
		t.Fatal("unsafe assets accepted")
	}
}

func TestOldKuwoCoverUsesMirroredShardOrFailsSafely(t *testing.T) {
	lyric := func(context.Context, string) (catalog.Lyrics, error) { return catalog.Lyrics{}, nil }
	for _, tc := range []struct{ raw, want string }{
		{"https://img2.kwcdn.kuwo.cn/star/upload/9/9/1543919747769_.png", "https://img2.kuwo.cn/star/upload/9/9/1543919747769_.png"},
		{"http://img4.kwcdn.kuwo.cn/unknown.jpg", "https://img4.kuwo.cn/unknown.jpg"},
	} {
		calls := 0
		_, err := fetch(t.Context(), model.DownloadJob{WriteCover: true, Track: model.Track{CoverURL: tc.raw}}, lyric, func(_ context.Context, raw string) ([]byte, error) {
			calls++
			if raw != tc.want {
				t.Errorf("request %q want %q", raw, tc.want)
			}
			return nil, errors.New("isolated network fixture")
		})
		if err == nil || calls != 1 {
			t.Fatal("cover request boundary changed", calls, err)
		}
	}
}

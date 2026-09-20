package provider

import (
	"melora/internal/model"
	"testing"
)

func TestEnrichMirrorsHistoricalKuwoCovers(t *testing.T) {
	l := &Live{}
	raw := "https://img3.kwcdn.kuwo.cn/star/upload/1/1/1554695862673_.png"
	got := l.EnrichCollection(model.Collection{CoverURL: raw, Tracks: []model.Track{{ProviderID: "kw", CoverURL: raw}, {ProviderID: "kw", CoverURL: "https://img1.kwcdn.kuwo.cn/unknown.jpg"}}})
	want := "https://img3.kuwo.cn/star/upload/1/1/1554695862673_.png"
	if got.CoverURL != want || got.Tracks[0].CoverURL != got.CoverURL || got.Tracks[1].CoverURL != "https://img1.kuwo.cn/unknown.jpg" {
		t.Fatal(got)
	}
}

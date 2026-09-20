package catalog

import (
	"testing"
)

// 官方 toOldMusicInfo 必有 img/typeUrl；不伪造音质、媒体URL或客户端传入字段。
func TestV12NeteaseLegacyEnvelopeContainsTrustedCoverAndEmptyURLMap(t *testing.T) {
	c := NewWY(nil)
	_, ok := c.convert(Song{ID: 123, Name: "Trusted song", Duration: 120000, Artists: []Artist{{Name: "Artist"}}, Album: Album{ID: 3, Name: "Album", PicURL: "http://p1.music.126.net/cover.jpg"}})
	if !ok {
		t.Fatal("fixture invalid")
	}
	info, err := c.MusicInfo(t.Context(), "wy:123")
	if err != nil || info["img"] != "https://p1.music.126.net/cover.jpg" {
		t.Fatalf("legacy cover missing: %v", err)
	}
	types, ok := info["typeUrl"].(map[string]any)
	if !ok || len(types) != 0 {
		t.Fatal("legacy typeUrl must be isolated empty object")
	}
	types["128k"] = "https://untrusted.example.test/changed"
	again, err := c.MusicInfo(t.Context(), "wy:123")
	if err != nil || len(again["typeUrl"].(map[string]any)) != 0 {
		t.Fatal("script mutated trusted payload cache")
	}
}

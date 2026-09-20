package store

import (
	"path/filepath"
	"testing"
)

func TestDownloadDefaultsEmbedWithoutExtraFiles(t *testing.T) {
	file := filepath.Join(t.TempDir(), "state.db")
	db, err := Open(file)
	if err != nil {
		t.Fatal(err)
	}
	got, err := db.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if !got.EmbedTags || got.WriteLyrics || got.WriteCover {
		t.Fatalf("new default must embed only: %+v", got)
	}
	got.EmbedTags, got.WriteLyrics, got.WriteCover = false, true, true
	if err := db.SaveSettings(t.Context(), got); err != nil {
		t.Fatal(err)
	}
	db.Close()
	restored, err := Open(file)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	got, err = restored.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if got.EmbedTags || !got.WriteLyrics || !got.WriteCover {
		t.Fatal("explicit saved settings were overwritten by new defaults")
	}
}

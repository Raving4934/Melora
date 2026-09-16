package store

import (
	"fmt"
	"melora/internal/model"
	"path/filepath"
	"testing"
	"time"
)

func TestPrivateCatalogMetadataPersistsAndRejectsMediaOrCredentials(t *testing.T) {
	path := filepath.Join(t.TempDir(), "music.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	item := model.CatalogMetadata{Track: model.Track{ID: "kg:abc", ProviderID: "kg", Title: "曲目"}, MusicInfo: map[string]any{"source": "kg", "songmid": 1001, "hash": "abc", "typeUrl": map[string]any{}}}
	if err = db.SaveCatalogMetadata(t.Context(), []model.CatalogMetadata{item}); err != nil {
		t.Fatal(err)
	}
	db.Close()
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	got, err := db.CatalogMetadata(t.Context(), item.Track.ID)
	if err != nil || fmt.Sprint(got.MusicInfo["songmid"]) != "1001" {
		t.Fatal(got, err)
	}
	for _, key := range []string{"url", "cookie", "authorization", "password"} {
		item.MusicInfo[key] = "do-not-store"
		if db.SaveCatalogMetadata(t.Context(), []model.CatalogMetadata{item}) == nil {
			t.Fatalf("stored secret field %s", key)
		}
		delete(item.MusicInfo, key)
	}
	item.MusicInfo["typeUrl"] = map[string]any{"flac": "https://example.org/audio?token=secret"}
	if db.SaveCatalogMetadata(t.Context(), []model.CatalogMetadata{item}) == nil {
		t.Fatal("stored audio URL")
	}
	db.db.Exec("UPDATE catalog_metadata SET updated_at=?", time.Now().Add(-15*24*time.Hour).Unix())
	if _, err = db.CatalogMetadata(t.Context(), item.Track.ID); err == nil {
		t.Fatal("stale persisted metadata used indefinitely")
	}
}

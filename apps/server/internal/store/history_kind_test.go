package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"testing"

	"melora/internal/model"
)

func TestHistoryKindMigrationAndFiltering(t *testing.T) {
	path, ctx := filepath.Join(t.TempDir(), "legacy.db"), context.Background()
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = legacy.Exec(`
 CREATE TABLE play_history (id TEXT PRIMARY KEY, payload TEXT NOT NULL, played_at INTEGER NOT NULL);
 CREATE INDEX history_recent ON play_history(played_at DESC);
 PRAGMA user_version=1;`)
	if err != nil {
		t.Fatal(err)
	}
	legacyTrack := model.Track{ID: "demo:legacy", Title: "旧记录"}
	raw, err := json.Marshal(legacyTrack)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = legacy.Exec("INSERT INTO play_history(id,payload,played_at) VALUES(?,?,?)", legacyTrack.ID, string(raw), 123); err != nil {
		t.Fatal(err)
	}
	if err = legacy.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	entries, err := db.History(ctx, "")
	if err != nil || len(entries) != 1 {
		t.Fatalf("legacy history %+v %v", entries, err)
	}
	if entries[0].Kind != model.HistoryKindTrack || entries[0].ContextID != "" || entries[0].Track.ID != legacyTrack.ID {
		t.Fatalf("legacy row not normalized: %+v", entries[0])
	}
	if err := db.AddHistory(ctx, model.Track{ID: "demo:list"}, model.HistoryKindPlaylist, "wy:playlist_1"); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, model.Track{ID: "demo:book"}, model.HistoryKindAudiobook, "kw:book_album_10250871"); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, model.Track{ID: "demo:solo"}, model.HistoryKindTrack, "wy:playlist_1"); err != nil {
		t.Fatal(err)
	}
	playlists, err := db.History(ctx, model.HistoryKindPlaylist)
	if err != nil || len(playlists) != 1 || playlists[0].ContextID != "wy:playlist_1" {
		t.Fatalf("playlist filter %+v %v", playlists, err)
	}
	books, err := db.History(ctx, model.HistoryKindAudiobook)
	if err != nil || len(books) != 1 || books[0].Track.ID != "demo:book" {
		t.Fatalf("audiobook filter %+v %v", books, err)
	}
	tracks, err := db.History(ctx, model.HistoryKindTrack)
	if err != nil || len(tracks) != 2 {
		t.Fatalf("track filter %+v %v", tracks, err)
	}
	for _, entry := range tracks {
		if entry.ContextID != "" {
			t.Fatalf("单曲记录不能保留上下文: %+v", entry)
		}
	}
	if err := db.AddHistory(ctx, model.Track{ID: "demo:list", Title: "再次播放"}, model.HistoryKindAudiobook, "kw:book_album_9"); err != nil {
		t.Fatal(err)
	}
	if books, err = db.History(ctx, model.HistoryKindAudiobook); err != nil || len(books) != 2 {
		t.Fatalf("同类覆盖失败 %+v %v", books, err)
	}
	if playlists, err = db.History(ctx, model.HistoryKindPlaylist); err != nil || len(playlists) != 0 {
		t.Fatalf("重播应迁移分类 %+v %v", playlists, err)
	}
}

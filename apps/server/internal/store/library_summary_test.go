package store

import (
	"context"
	"path/filepath"
	"testing"

	"melora/internal/model"
)

func TestLibrarySummaryUsesDatabaseCounts(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "melora.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	track := model.Track{ID: "demo:one", Title: "One"}
	if err := db.FavoriteTrack(ctx, track, true); err != nil {
		t.Fatal(err)
	}
	if err := db.FavoritePlaylist(ctx, model.Collection{ID: "demo:list"}, true); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, track, model.HistoryKindTrack, ""); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, model.Track{ID: "demo:two", Title: "Two"}, model.HistoryKindPlaylist, "wy:playlist_1"); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, model.Track{ID: "demo:three", Title: "Three"}, model.HistoryKindAudiobook, "kw:book_album_10250871"); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, model.Track{ID: "demo:four", Title: "Four"}, model.HistoryKindTrack, "ignored"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.CreateUserPlaylist(ctx, "Local", ""); err != nil {
		t.Fatal(err)
	}
	summary, err := db.LibrarySummary(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if summary != (model.LibrarySummary{FavoriteTracks: 1, FavoritePlaylists: 1, History: 4, HistoryTracks: 2, HistoryPlaylists: 1, HistoryAudiobooks: 1, UserPlaylists: 1}) {
		t.Fatalf("unexpected summary: %+v", summary)
	}
}

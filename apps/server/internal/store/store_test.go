package store

import (
	"context"
	"fmt"
	"melora/internal/model"
	"os"
	"path/filepath"
	"testing"
)

func TestSQLiteWALAndReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "data", "melora.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	var mode string
	if err := db.db.QueryRow("PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("WAL: %s %v", mode, err)
	}
	track := model.Track{ID: "demo:track", Title: "Love '); DROP TABLE settings; --", Qualities: []string{"standard"}}
	if err = db.FavoriteTrack(ctx, track, true); err != nil {
		t.Fatal(err)
	}
	if err = db.AddHistory(ctx, track, model.HistoryKindTrack, ""); err != nil {
		t.Fatal(err)
	}
	settings := DefaultSettings()
	settings.ShowDirect = false
	if err = db.SaveSettings(ctx, settings); err != nil {
		t.Fatal(err)
	}
	job := model.DownloadJob{ID: "job-1", State: "paused", Track: track}
	if err = db.SaveDownload(job); err != nil {
		t.Fatal(err)
	}
	if err = db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	favorites, err := db.FavoriteTracks(ctx)
	if err != nil || len(favorites) != 1 || favorites[0].Title != track.Title {
		t.Fatalf("favorites %+v %v", favorites, err)
	}
	saved, err := db.Settings(ctx)
	if err != nil || saved.ShowDirect {
		t.Fatal("settings not persistent")
	}
	jobs, err := db.Downloads(ctx)
	if err != nil || len(jobs) != 1 || jobs[0].State != "paused" {
		t.Fatal("downloads not persistent")
	}
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0600 {
		t.Fatal("database permissions not private")
	}
}
func TestHistoryBoundedAndDeduplicated(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	for i := 0; i < 510; i++ {
		if err := db.AddHistory(ctx, model.Track{ID: fmt.Sprint(i)}, "", ""); err != nil {
			t.Fatal(err)
		}
	}
	history, err := db.History(ctx, "")
	if err != nil || len(history) != 500 {
		t.Fatalf("history length %d, %v", len(history), err)
	}
	if err = db.AddHistory(ctx, model.Track{ID: "100", Title: "Again"}, "", ""); err != nil {
		t.Fatal(err)
	}
	history, _ = db.History(ctx, "")
	if len(history) != 500 || history[0].Track.ID != "100" {
		t.Fatal("history upsert broken")
	}
}

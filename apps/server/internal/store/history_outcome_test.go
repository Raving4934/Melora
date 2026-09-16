package store

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"

	"melora/internal/model"
)

func TestHistoryCountersAndOutcome(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	track := model.Track{ID: "demo:loop", Title: "循环曲目", Duration: 100}
	if err := db.AddHistory(ctx, track, "", ""); err != nil {
		t.Fatal(err)
	}
	if err := db.AddHistory(ctx, track, "", ""); err != nil {
		t.Fatal(err)
	}
	entries, err := db.History(ctx, "")
	if err != nil || len(entries) != 1 {
		t.Fatalf("history %+v %v", entries, err)
	}
	if entries[0].PlayCount != 2 {
		t.Fatalf("重复播放应累加 play_count: %+v", entries[0])
	}
	// 40% × 100s = 40s：20s 算跳过，50s 算未听完但不算跳过，90s 完整播完。
	if err := db.RecordHistoryOutcome(ctx, track.ID, "", 20_000, false); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordHistoryOutcome(ctx, track.ID, "", 90_000, true); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordHistoryOutcome(ctx, track.ID, "", 50_000, false); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordHistoryOutcome(ctx, "demo:missing", "", 10_000, false); !errors.Is(err, ErrHistoryNotFound) {
		t.Fatalf("未记录曲目的结局必须显式报告乱序: %v", err)
	}
	entries, err = db.History(ctx, "")
	if err != nil || len(entries) != 1 {
		t.Fatalf("history %+v %v", entries, err)
	}
	entry := entries[0]
	if entry.CompletedCount != 1 || entry.SkipCount != 1 || entry.ListenedMs != 160_000 || entry.PlayCount != 2 {
		t.Fatalf("outcome counters %+v", entry)
	}
}

func TestHistoryOutcomeEventIsIdempotent(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	track := model.Track{ID: "demo:idempotent", Title: "幂等曲目", Duration: 100}
	if err := db.AddHistory(ctx, track, "", ""); err != nil {
		t.Fatal(err)
	}
	const eventID = "outcome_event_0001"
	for range 2 {
		if err := db.RecordHistoryOutcome(ctx, track.ID, eventID, 20_000, false); err != nil {
			t.Fatal(err)
		}
	}
	entries, err := db.History(ctx, "")
	if err != nil || len(entries) != 1 {
		t.Fatalf("history %+v %v", entries, err)
	}
	if entries[0].SkipCount != 1 || entries[0].ListenedMs != 20_000 {
		t.Fatalf("重复事件被重复累计: %+v", entries[0])
	}
	if err := db.RecordHistoryOutcome(ctx, track.ID, eventID, 21_000, false); !errors.Is(err, ErrOutcomeEventConflict) {
		t.Fatalf("复用 eventId 的不同内容必须冲突: %v", err)
	}

	// history 尚未建立时事务必须整体回滚；同一事件稍后仍应能够成功补发。
	const delayedEventID = "outcome_event_0002"
	if err := db.RecordHistoryOutcome(ctx, "demo:delayed", delayedEventID, 10_000, false); !errors.Is(err, ErrHistoryNotFound) {
		t.Fatalf("missing history error: %v", err)
	}
	delayed := model.Track{ID: "demo:delayed", Title: "延迟曲目", Duration: 100}
	if err := db.AddHistory(ctx, delayed, "", ""); err != nil {
		t.Fatal(err)
	}
	if err := db.RecordHistoryOutcome(ctx, delayed.ID, delayedEventID, 10_000, false); err != nil {
		t.Fatalf("回滚后的事件无法补发: %v", err)
	}

	if err := db.ClearHistory(ctx); err != nil {
		t.Fatal(err)
	}
	var events int
	if err := db.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM play_outcome_events").Scan(&events); err != nil || events != 0 {
		t.Fatalf("清空历史未同步清理幂等事件: count=%d err=%v", events, err)
	}
}

func TestHistoryV2MigrationAddsCounters(t *testing.T) {
	path := filepath.Join(t.TempDir(), "v2.db")
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = legacy.Exec(`
 CREATE TABLE play_history (
   id TEXT PRIMARY KEY, payload TEXT NOT NULL, played_at INTEGER NOT NULL,
   kind TEXT NOT NULL DEFAULT 'track', context_id TEXT NOT NULL DEFAULT ''
 );
 PRAGMA user_version=2;`)
	if err != nil {
		t.Fatal(err)
	}
	legacy.Exec("INSERT INTO play_history(id,payload,played_at,kind,context_id) VALUES('demo:old','{}',1,'playlist','wy:playlist_1')")
	if err = legacy.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	entries, err := db.History(context.Background(), "")
	if err != nil || len(entries) != 1 {
		t.Fatalf("history %+v %v", entries, err)
	}
	entry := entries[0]
	if entry.Kind != model.HistoryKindPlaylist || entry.ContextID != "wy:playlist_1" || entry.PlayCount != 1 {
		t.Fatalf("旧行升级错误: %+v", entry)
	}
	var version int
	if err := db.db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 4 {
		t.Fatalf("user_version %d %v", version, err)
	}
}

func TestFavoriteTimestamps(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ctx := context.Background()
	if err := db.FavoriteTrack(ctx, model.Track{ID: "demo:a"}, true); err != nil {
		t.Fatal(err)
	}
	if err := db.FavoritePlaylist(ctx, model.Collection{ID: "wy:playlist_1", Category: "摇滚"}, true); err != nil {
		t.Fatal(err)
	}
	tracks, err := db.FavoriteTracksWithTime(ctx)
	if err != nil || len(tracks) != 1 || tracks[0].At <= 0 || tracks[0].Track.ID != "demo:a" {
		t.Fatalf("timed favorites %+v %v", tracks, err)
	}
	collections, err := db.FavoritePlaylistsWithTime(ctx)
	if err != nil || len(collections) != 1 || collections[0].At <= 0 || collections[0].Collection.Category != "摇滚" {
		t.Fatalf("timed playlists %+v %v", collections, err)
	}
}

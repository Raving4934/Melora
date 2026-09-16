package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"melora/internal/model"
)

func playlistStore(t *testing.T) *Store {
	t.Helper()
	db, err := Open(filepath.Join(t.TempDir(), "playlists.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func playlistTrack(id string) model.Track {
	return model.Track{ID: id, ProviderID: "demo", Title: "曲目 " + id, Artist: "Artist", CoverURL: "https://example.org/" + id + ".jpg", Qualities: []string{"standard"}}
}

func createPlaylist(t *testing.T, db *Store) model.UserPlaylist {
	t.Helper()
	p, err := db.CreateUserPlaylist(context.Background(), "我的歌单", "描述")
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func assertPlaylistUnchanged(t *testing.T, db *Store, want model.UserPlaylist) {
	t.Helper()
	got, err := db.UserPlaylist(context.Background(), want.ID)
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("playlist changed: got=%+v want=%+v err=%v", got, want, err)
	}
}

func assertPlaylistTimeAdvanced(t *testing.T, before, after model.UserPlaylist) {
	t.Helper()
	old, err := time.Parse(time.RFC3339Nano, before.UpdatedAt)
	if err != nil {
		t.Fatal(err)
	}
	now, err := time.Parse(time.RFC3339Nano, after.UpdatedAt)
	if err != nil || !now.After(old) || before.CreatedAt != after.CreatedAt {
		t.Fatalf("timestamps not stable/monotonic: before=%+v after=%+v err=%v", before, after, err)
	}
}

func TestUserPlaylistCRUDOrderingAndSummary(t *testing.T) {
	db, ctx := playlistStore(t), context.Background()
	list, err := db.UserPlaylists(ctx)
	if err != nil || list == nil || len(list) != 0 {
		t.Fatalf("empty list: %+v %v", list, err)
	}
	p := createPlaylist(t, db)
	if p.ProviderID != "local" || !strings.HasPrefix(p.ID, "local:") || p.CreatedAt != p.UpdatedAt || p.Tracks == nil || p.TrackCount != 0 {
		t.Fatalf("invalid new playlist: %+v", p)
	}
	assertPlaylistUnchanged(t, db, p)
	unchanged, err := db.UpdateUserPlaylist(ctx, p.ID, " 我的歌单 ", " 描述 ")
	if err != nil || !reflect.DeepEqual(unchanged, p) {
		t.Fatalf("no-op metadata changed timestamp: %+v %v", unchanged, err)
	}
	first, second, third := playlistTrack("demo:a"), playlistTrack("demo:b"), playlistTrack("demo:c")
	added, err := db.AddUserPlaylistTrack(ctx, p.ID, first)
	if err != nil {
		t.Fatal(err)
	}
	assertPlaylistTimeAdvanced(t, p, added)
	p, err = db.AddUserPlaylistTrack(ctx, p.ID, second)
	if err != nil || p.TrackCount != 2 || p.CoverURL != first.CoverURL {
		t.Fatalf("add second: %+v %v", p, err)
	}
	forged := first
	forged.Title = "重复调用不得覆盖快照"
	duplicate, err := db.AddUserPlaylistTrack(ctx, p.ID, forged)
	if err != nil || !reflect.DeepEqual(duplicate, p) {
		t.Fatalf("duplicate not a no-op: %+v %v", duplicate, err)
	}
	updated, err := db.UpdateUserPlaylist(ctx, p.ID, "Love '); DROP TABLE settings; --", "新描述\n第二行")
	if err != nil || !reflect.DeepEqual(updated.Tracks, p.Tracks) {
		t.Fatalf("update lost tracks: %+v %v", updated, err)
	}
	assertPlaylistTimeAdvanced(t, p, updated)
	p, err = db.ReorderUserPlaylistTracks(ctx, p.ID, []string{second.ID, first.ID})
	if err != nil || p.Tracks[0].ID != second.ID || p.CoverURL != second.CoverURL || p.TrackCount != 2 {
		t.Fatalf("reorder: %+v %v", p, err)
	}
	assertPlaylistTimeAdvanced(t, updated, p)
	assertPlaylistUnchanged(t, db, p)
	same, err := db.ReorderUserPlaylistTracks(ctx, p.ID, []string{second.ID, first.ID})
	if err != nil || !reflect.DeepEqual(same, p) {
		t.Fatalf("same order changed timestamp: %+v %v", same, err)
	}
	other := createPlaylist(t, db)
	list, err = db.UserPlaylists(ctx)
	if err != nil || len(list) != 2 || list[0].ID != other.ID || list[1].ID != p.ID || list[1].TrackCount != 2 || list[1].CoverURL != second.CoverURL || list[1].Tracks != nil {
		t.Fatalf("list summary/order: %+v %v", list, err)
	}
	p, err = db.RemoveUserPlaylistTrack(ctx, p.ID, second.ID)
	if err != nil || p.TrackCount != 1 || p.CoverURL != first.CoverURL {
		t.Fatalf("remove: %+v %v", p, err)
	}
	p, err = db.AddUserPlaylistTrack(ctx, p.ID, third)
	if err != nil || p.TrackCount != 2 || p.Tracks[1].ID != third.ID {
		t.Fatalf("append after position gap: %+v %v", p, err)
	}
	if _, err := db.RemoveUserPlaylistTrack(ctx, p.ID, "missing"); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("missing member: %v", err)
	}
	assertPlaylistUnchanged(t, db, p)
	for _, id := range []string{first.ID, third.ID} {
		p, err = db.RemoveUserPlaylistTrack(ctx, p.ID, id)
		if err != nil {
			t.Fatal(err)
		}
	}
	if p.TrackCount != 0 || p.CoverURL != "" || len(p.Tracks) != 0 {
		t.Fatalf("empty detail: %+v", p)
	}
	if _, err := db.ReorderUserPlaylistTracks(ctx, p.ID, []string{}); err != nil {
		t.Fatal(err)
	}
	assertPlaylistUnchanged(t, db, p)
	if err := db.DeleteUserPlaylist(ctx, p.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := db.UserPlaylist(ctx, p.ID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("deleted detail: %v", err)
	}
	var count int
	if err := db.db.QueryRow("SELECT COUNT(*) FROM user_playlist_tracks WHERE playlist_id=?", p.ID).Scan(&count); err != nil || count != 0 {
		t.Fatalf("orphan members: %d %v", count, err)
	}
}

func TestUserPlaylistInputAndMissing(t *testing.T) {
	db, ctx := playlistStore(t), context.Background()
	p := createPlaylist(t, db)
	for _, tc := range []struct{ name, title, description string }{
		{"empty", "", ""}, {"whitespace", " \t\n ", ""},
		{"long title", strings.Repeat("歌", 81), ""}, {"long description", "x", strings.Repeat("🎵", 301)},
		{"invalid UTF8 title", string([]byte{255}), ""}, {"invalid UTF8 description", "x", string([]byte{255})},
		{"title control", "a\nb", ""}, {"description control", "x", "a\x00b"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := db.CreateUserPlaylist(ctx, tc.title, tc.description); !errors.Is(err, ErrInvalidUserPlaylist) {
				t.Fatalf("create invalid: %v", err)
			}
			if _, err := db.UpdateUserPlaylist(ctx, p.ID, tc.title, tc.description); !errors.Is(err, ErrInvalidUserPlaylist) {
				t.Fatalf("update invalid: %v", err)
			}
			assertPlaylistUnchanged(t, db, p)
		})
	}
	if _, err := db.CreateUserPlaylist(ctx, strings.Repeat("歌", 80), strings.Repeat("🎵", 300)); err != nil {
		t.Fatalf("rune boundary rejected: %v", err)
	}
	for _, id := range []string{"", " ", "a b", "a\x00b", string([]byte{255}), strings.Repeat("x", 1025)} {
		if _, err := db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack(id)); !errors.Is(err, ErrInvalidUserPlaylistTrack) {
			t.Fatalf("invalid track %q: %v", id, err)
		}
		if _, err := db.RemoveUserPlaylistTrack(ctx, p.ID, id); !errors.Is(err, ErrInvalidUserPlaylistTrack) {
			t.Fatalf("invalid removal %q: %v", id, err)
		}
	}
	for name, fn := range map[string]func() error{
		"get":     func() error { _, err := db.UserPlaylist(ctx, "missing"); return err },
		"update":  func() error { _, err := db.UpdateUserPlaylist(ctx, "missing", "title", ""); return err },
		"delete":  func() error { return db.DeleteUserPlaylist(ctx, "missing") },
		"add":     func() error { _, err := db.AddUserPlaylistTrack(ctx, "missing", playlistTrack("demo:a")); return err },
		"remove":  func() error { _, err := db.RemoveUserPlaylistTrack(ctx, "missing", "demo:a"); return err },
		"reorder": func() error { _, err := db.ReorderUserPlaylistTracks(ctx, "missing", []string{}); return err },
	} {
		t.Run(name, func(t *testing.T) {
			if err := fn(); !errors.Is(err, sql.ErrNoRows) {
				t.Fatalf("missing playlist: %v", err)
			}
		})
	}
}

func TestUserPlaylistRejectsNonPermutationWithoutMutation(t *testing.T) {
	db, ctx := playlistStore(t), context.Background()
	p := createPlaylist(t, db)
	for _, id := range []string{"demo:a", "demo:b", "demo:c"} {
		var err error
		p, err = db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack(id))
		if err != nil {
			t.Fatal(err)
		}
	}
	for _, ids := range [][]string{nil, {}, {"demo:a"}, {"demo:a", "demo:b"}, {"demo:a", "demo:b", "demo:c", "demo:d"}, {"demo:a", "demo:a", "demo:c"}, {"demo:a", "unknown", "demo:c"}, {"demo:a", "", "demo:c"}, make([]string, 501)} {
		if _, err := db.ReorderUserPlaylistTracks(ctx, p.ID, ids); !errors.Is(err, ErrInvalidUserPlaylistOrder) {
			t.Fatalf("order %v accepted: %v", ids, err)
		}
		assertPlaylistUnchanged(t, db, p)
	}
}

func TestUserPlaylistLimitsAndConcurrentWrites(t *testing.T) {
	db, ctx := playlistStore(t), context.Background()
	p := createPlaylist(t, db)
	for i := 1; i < MaxUserPlaylists-1; i++ {
		createPlaylist(t, db)
	}
	var wg sync.WaitGroup
	results := make(chan error, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := db.CreateUserPlaylist(ctx, "并发创建", "")
			results <- err
		}()
	}
	wg.Wait()
	close(results)
	succeeded := 0
	for err := range results {
		if err == nil {
			succeeded++
		} else if !errors.Is(err, ErrUserPlaylistLimit) {
			t.Fatal(err)
		}
	}
	list, err := db.UserPlaylists(ctx)
	if err != nil || succeeded != 1 || len(list) != MaxUserPlaylists {
		t.Fatalf("playlist limit: successes=%d len=%d err=%v", succeeded, len(list), err)
	}
	if _, err := db.UpdateUserPlaylist(ctx, p.ID, "达到上限仍可修改", ""); err != nil {
		t.Fatal(err)
	}
	if err := db.DeleteUserPlaylist(ctx, list[0].ID); err != nil {
		t.Fatal(err)
	}
	createPlaylist(t, db)
	for i := 0; i < MaxUserPlaylistTracks-1; i++ {
		if _, err := db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack(fmt.Sprintf("demo:%d", i))); err != nil {
			t.Fatal(err)
		}
	}
	results = make(chan error, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			p, err := db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack(fmt.Sprintf("demo:concurrent-%d", i)))
			if err == nil && p.TrackCount != len(p.Tracks) {
				err = errors.New("inconsistent response snapshot")
			}
			results <- err
		}(i)
	}
	wg.Wait()
	close(results)
	succeeded = 0
	for err := range results {
		if err == nil {
			succeeded++
		} else if !errors.Is(err, ErrUserPlaylistTrackLimit) {
			t.Fatal(err)
		}
	}
	p, err = db.UserPlaylist(ctx, p.ID)
	if err != nil || succeeded != 1 || p.TrackCount != MaxUserPlaylistTracks {
		t.Fatalf("track limit: successes=%d count=%d err=%v", succeeded, p.TrackCount, err)
	}
	// 达到上限后重复添加仍成功，并保持成员快照和时间戳不变。
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			duplicate, err := db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack("demo:0"))
			if err != nil || !reflect.DeepEqual(duplicate, p) {
				t.Errorf("concurrent duplicate: %v", err)
			}
		}()
	}
	wg.Wait()
	assertPlaylistUnchanged(t, db, p)
	ids := make([]string, len(p.Tracks))
	for i, track := range p.Tracks {
		ids[len(ids)-1-i] = track.ID
	}
	p, err = db.ReorderUserPlaylistTracks(ctx, p.ID, ids)
	if err != nil || p.Tracks[0].ID != ids[0] || p.TrackCount != MaxUserPlaylistTracks {
		t.Fatalf("full reorder: %v", err)
	}
	if _, err := db.RemoveUserPlaylistTrack(ctx, p.ID, "demo:0"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack("demo:replacement")); err != nil {
		t.Fatal(err)
	}
}

func TestUserPlaylistTransactionRollback(t *testing.T) {
	db, ctx := playlistStore(t), context.Background()
	p := createPlaylist(t, db)
	for _, id := range []string{"demo:a", "demo:b"} {
		var err error
		p, err = db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack(id))
		if err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.db.Exec(`CREATE TRIGGER fail_playlist_update BEFORE UPDATE ON user_playlists BEGIN SELECT RAISE(ABORT,'injected failure'); END`); err != nil {
		t.Fatal(err)
	}
	for name, fn := range map[string]func() error{
		"add":    func() error { _, err := db.AddUserPlaylistTrack(ctx, p.ID, playlistTrack("demo:c")); return err },
		"remove": func() error { _, err := db.RemoveUserPlaylistTrack(ctx, p.ID, "demo:a"); return err },
		"reorder": func() error {
			_, err := db.ReorderUserPlaylistTracks(ctx, p.ID, []string{"demo:b", "demo:a"})
			return err
		},
		"metadata": func() error { _, err := db.UpdateUserPlaylist(ctx, p.ID, "new title", "new"); return err },
	} {
		t.Run(name, func(t *testing.T) {
			if err := fn(); err == nil {
				t.Fatal("injected write failure ignored")
			}
			assertPlaylistUnchanged(t, db, p)
		})
	}
	if _, err := db.db.Exec(`DROP TRIGGER fail_playlist_update;
 CREATE TRIGGER fail_playlist_insert BEFORE INSERT ON user_playlist_tracks WHEN NEW.track_id='demo:a' BEGIN SELECT RAISE(ABORT,'partial reorder'); END;`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ReorderUserPlaylistTracks(ctx, p.ID, []string{"demo:b", "demo:a"}); err == nil {
		t.Fatal("partial reorder unexpectedly succeeded")
	}
	assertPlaylistUnchanged(t, db, p)
	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	if _, err := db.AddUserPlaylistTrack(cancelled, p.ID, playlistTrack("demo:c")); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled write: %v", err)
	}
	assertPlaylistUnchanged(t, db, p)
}

func TestUserPlaylistLegacyMigrationPreservesAllData(t *testing.T) {
	path, ctx := filepath.Join(t.TempDir(), "legacy.db"), context.Background()
	legacy, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer legacy.Close()
	// 真实构造 0.1.x 表结构，不使用当前 migrate 创建“旧库”。
	_, err = legacy.Exec(`
 CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL);
 CREATE TABLE providers (id TEXT PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)));
 CREATE TABLE favorite_tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, added_at INTEGER NOT NULL);
 CREATE TABLE favorite_playlists (id TEXT PRIMARY KEY, payload TEXT NOT NULL, added_at INTEGER NOT NULL);
 CREATE TABLE play_history (id TEXT PRIMARY KEY, payload TEXT NOT NULL, played_at INTEGER NOT NULL);
 CREATE INDEX history_recent ON play_history(played_at DESC);
 CREATE TABLE download_jobs (id TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at TEXT NOT NULL);
 INSERT INTO providers VALUES('demo',0);
 PRAGMA user_version=1;`)
	if err != nil {
		t.Fatal(err)
	}
	track := playlistTrack("demo:legacy")
	trackJSON, _ := json.Marshal(track)
	collectionJSON, _ := json.Marshal(model.Collection{ID: "demo:old", Title: "旧收藏歌单", TrackCount: 12})
	jobJSON, _ := json.Marshal(map[string]any{"id": "old-job", "state": "paused", "track": track, "createdAt": "2025-01-01T00:00:00Z", "targetPath": "/music/old.ogg"})
	payloads := map[string]string{
		"settings":        `{"downloadRoot":"/music","concurrency":2,"writeLyrics":true,"writeCover":true,"defaultQuality":"standard","showDirect":false}`,
		"favorite_tracks": string(trackJSON), "favorite_playlists": string(collectionJSON), "play_history": string(trackJSON), "download_jobs": string(jobJSON),
	}
	for table, payload := range payloads {
		query := "INSERT INTO " + table + " VALUES(?,?,?)"
		var args []any
		switch table {
		case "settings":
			query, args = "INSERT INTO settings VALUES(1,?)", []any{payload}
		case "download_jobs":
			args = []any{"old-job", payload, "2025-01-01T00:00:00Z"}
		case "favorite_playlists":
			args = []any{"demo:old", payload, 123}
		default:
			args = []any{track.ID, payload, 123}
		}
		if _, err := legacy.Exec(query, args...); err != nil {
			t.Fatal(err)
		}
	}
	if err := legacy.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { db.Close() }()
	p := createPlaylist(t, db)
	p, err = db.AddUserPlaylistTrack(ctx, p.ID, track)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	assertPlaylistUnchanged(t, db, p)
	if err := db.DeleteUserPlaylist(ctx, p.ID); err != nil {
		t.Fatal(err)
	}
	var members int
	if err := db.db.QueryRow("SELECT COUNT(*) FROM user_playlist_tracks").Scan(&members); err != nil || members != 0 {
		t.Fatalf("cascade failed: %d %v", members, err)
	}
	for table, want := range payloads {
		var got string
		if err := db.db.QueryRow("SELECT payload FROM " + table).Scan(&got); err != nil || got != want {
			t.Fatalf("legacy %s modified: %q want %q err=%v", table, got, want, err)
		}
	}
	settings, err := db.Settings(ctx)
	if err != nil || settings.WriteMetadata || settings.Concurrency != 2 || settings.ShowDirect || settings.DownloadRoot != "/music" {
		t.Fatalf("legacy settings: %+v %v", settings, err)
	}
	jobs, err := db.Downloads(ctx)
	if err != nil || len(jobs) != 1 || jobs[0].WriteMetadata || jobs[0].State != "paused" || jobs[0].MetadataPath != "" {
		t.Fatalf("legacy downloads: %+v %v", jobs, err)
	}
	enabled, err := db.ProviderEnabled(ctx)
	if err != nil || enabled {
		t.Fatalf("provider state overwritten: %v %v", enabled, err)
	}
}

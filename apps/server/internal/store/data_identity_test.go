package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"melora/internal/model"
)

type persistedDataIdentity struct {
	generation  string
	resetLegacy bool
}

func readDataIdentity(t *testing.T, db *sql.DB) persistedDataIdentity {
	t.Helper()
	var identity persistedDataIdentity
	var id, count, version int
	if err := db.QueryRow("SELECT id,generation,reset_legacy FROM app_data_identity").Scan(&id, &identity.generation, &identity.resetLegacy); err != nil {
		t.Fatalf("read persisted data identity: %v", err)
	}
	if err := db.QueryRow("SELECT COUNT(*) FROM app_data_identity").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if id != 1 || count != 1 || !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(identity.generation) {
		t.Fatalf("invalid singleton identity: id=%d count=%d identity=%+v", id, count, identity)
	}
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 4 {
		t.Fatalf("additive migration changed user_version: %d %v", version, err)
	}
	return identity
}

func identityRawDB(t *testing.T, path string) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	return db
}

func identityExec(t *testing.T, db *sql.DB, query string, args ...any) {
	t.Helper()
	if _, err := db.Exec(query, args...); err != nil {
		t.Fatal(err)
	}
}

func TestDataIdentityFreshStableReopen(t *testing.T) {
	for _, initial := range []string{"missing", "zero-byte", "empty-schema-version-one"} {
		t.Run(initial, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "melora.db")
			switch initial {
			case "zero-byte":
				if err := os.WriteFile(path, nil, 0600); err != nil {
					t.Fatal(err)
				}
			case "empty-schema-version-one":
				raw := identityRawDB(t, path)
				identityExec(t, raw, "PRAGMA user_version=1")
				if err := raw.Close(); err != nil {
					t.Fatal(err)
				}
			}
			var first persistedDataIdentity
			for reopen := 0; reopen < 3; reopen++ {
				db, err := Open(path)
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { db.Close() })
				got := readDataIdentity(t, db.db)
				if !got.resetLegacy {
					t.Fatal("fresh identity must persist resetLegacy=true, including after reopen")
				}
				if reopen == 0 {
					first = got
				} else if got != first {
					t.Fatalf("reopen rotated identity: got=%+v want=%+v", got, first)
				}
				if err := db.Close(); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}

func TestDataIdentityLegacy059PreservesUserDataAndSettings(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "melora.db")
	raw := identityRawDB(t, path)
	// 独立构造 0.5.9 schema，不通过当前 Open/migrate 伪造旧库。
	identityExec(t, raw, `
 CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL);
 CREATE TABLE providers (id TEXT PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)));
 CREATE TABLE favorite_tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, added_at INTEGER NOT NULL);
 CREATE TABLE favorite_playlists (id TEXT PRIMARY KEY, payload TEXT NOT NULL, added_at INTEGER NOT NULL);
 CREATE TABLE play_history (id TEXT PRIMARY KEY, payload TEXT NOT NULL, played_at INTEGER NOT NULL);
 CREATE INDEX history_recent ON play_history(played_at DESC);
 CREATE TABLE catalog_metadata (id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL);
 CREATE INDEX catalog_metadata_recent ON catalog_metadata(updated_at DESC);
 CREATE TABLE download_jobs (id TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at TEXT NOT NULL);
 CREATE TABLE user_playlists (id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
 CREATE TABLE user_playlist_tracks (
   playlist_id TEXT NOT NULL REFERENCES user_playlists(id) ON DELETE CASCADE,
   track_id TEXT NOT NULL, payload TEXT NOT NULL, position INTEGER NOT NULL CHECK(position>=0),
   PRIMARY KEY(playlist_id,track_id), UNIQUE(playlist_id,position)
 );
 INSERT INTO providers VALUES('demo',0);
 INSERT INTO user_playlists VALUES('saved-list','我的旧歌单','升级保留','2026-01-01','2026-01-02');
 PRAGMA user_version=1;`)
	track := model.Track{ID: "wy:legacy", ProviderID: "wy", Title: "旧收藏", Artist: "旧作者", Qualities: []string{"standard"}}
	trackJSON, err := json.Marshal(track)
	if err != nil {
		t.Fatal(err)
	}
	settings := `{"concurrency":2,"autoSwitchSource":false,"defaultQuality":"flac","writeLyrics":true,"showDirect":true,"futurePreference":"keep-me"}`
	payloads := map[string]string{
		"settings": settings, "favorite_tracks": string(trackJSON), "play_history": string(trackJSON),
		"favorite_playlists": `{"id":"wy:old-list","title":"旧收藏歌单","trackCount":3}`,
		"catalog_metadata":   `{"track":{"id":"wy:legacy","providerId":"wy"},"musicInfo":{"songmid":"legacy","source":"wy"}}`,
		"download_jobs":      `{"id":"saved-job","state":"paused","track":{"id":"wy:legacy"}}`,
	}
	for table, payload := range payloads {
		switch table {
		case "settings":
			identityExec(t, raw, "INSERT INTO settings VALUES(1,?)", payload)
		case "download_jobs":
			identityExec(t, raw, "INSERT INTO download_jobs VALUES('saved-job',?,'2026-01-01')", payload)
		default:
			identityExec(t, raw, "INSERT INTO "+table+" VALUES(?,?,123)", track.ID, payload)
		}
	}
	identityExec(t, raw, "INSERT INTO user_playlist_tracks VALUES('saved-list',?,?,0)", track.ID, string(trackJSON))
	if err := raw.Close(); err != nil {
		t.Fatal(err)
	}
	// 临时音源文件哨兵；仅验证迁移不会改写库外用户文件，不加载脚本或访问真实 data。
	sourceFiles := map[string]string{"source.js": "// legacy user source fixture\n", "registry.json": `{"selected":"legacy-source","enabled":true}`}
	for name, contents := range sourceFiles {
		if err := os.WriteFile(filepath.Join(root, name), []byte(contents), 0600); err != nil {
			t.Fatal(err)
		}
	}
	var first persistedDataIdentity
	for reopen := 0; reopen < 2; reopen++ {
		db, err := Open(path)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { db.Close() })
		got := readDataIdentity(t, db.db)
		if got.resetLegacy {
			t.Fatal("normal 0.5.9 upgrade must not reset legacy browser sessions")
		}
		if reopen == 0 {
			first = got
		} else if got != first {
			t.Fatal("legacy identity or flag changed after reopen")
		}
		for table, want := range payloads {
			var payload string
			if err := db.db.QueryRow("SELECT payload FROM " + table).Scan(&payload); err != nil || payload != want {
				t.Fatalf("legacy %s changed: %q want %q err=%v", table, payload, want, err)
			}
		}
		saved, err := db.Settings(context.Background())
		if err != nil || saved.Concurrency != 2 || saved.AutoSwitchSource || saved.DefaultQuality != "flac" || !saved.WriteLyrics || !saved.ShowDirect {
			t.Fatalf("legacy settings changed: %+v %v", saved, err)
		}
		if enabled, err := db.ProviderEnabled(context.Background()); err != nil || enabled {
			t.Fatalf("source selection changed: enabled=%v err=%v", enabled, err)
		}
		var title, description, created, updated, member string
		var position int
		if err := db.db.QueryRow("SELECT title,description,created_at,updated_at FROM user_playlists WHERE id='saved-list'").Scan(&title, &description, &created, &updated); err != nil || title != "我的旧歌单" || description != "升级保留" || created != "2026-01-01" || updated != "2026-01-02" {
			t.Fatalf("user playlist changed: %v", err)
		}
		if err := db.db.QueryRow("SELECT payload,position FROM user_playlist_tracks WHERE playlist_id='saved-list' AND track_id=?", track.ID).Scan(&member, &position); err != nil || member != string(trackJSON) || position != 0 {
			t.Fatalf("user playlist membership changed: %v", err)
		}
		if err := db.Close(); err != nil {
			t.Fatal(err)
		}
		for name, want := range sourceFiles {
			if got, err := os.ReadFile(filepath.Join(root, name)); err != nil || string(got) != want {
				t.Fatalf("source file changed: %s %v", name, err)
			}
		}
	}
}

func TestDataIdentityPreMigrationSchemaNotRowCount(t *testing.T) {
	for _, schema := range []string{
		"CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)",
		"CREATE TABLE retained_extension (id INTEGER PRIMARY KEY)",
		"CREATE VIEW retained_extension AS SELECT 1 AS id",
	} {
		t.Run(strings.Fields(schema)[2], func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "legacy-empty.db")
			raw := identityRawDB(t, path)
			identityExec(t, raw, schema)
			if err := raw.Close(); err != nil {
				t.Fatal(err)
			}
			db, err := Open(path)
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			if readDataIdentity(t, db.db).resetLegacy {
				t.Fatal("pre-existing schema, even without rows, must be treated as legacy")
			}
		})
	}
}

func TestDataIdentityDeletedDatabaseGetsNewGeneration(t *testing.T) {
	path := filepath.Join(t.TempDir(), "melora.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	before := readDataIdentity(t, db.db)
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	// 关闭唯一连接后仅删除此测试的新建 DB，不触碰任何用户库或目录。
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	after := readDataIdentity(t, db.db)
	if before.generation == after.generation || !after.resetLegacy {
		t.Fatalf("new database did not get a fresh generation: before=%+v after=%+v", before, after)
	}
}

func TestDataIdentitySchemaEnforcesSingletonAndFormat(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "melora.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	before := readDataIdentity(t, db.db)
	for _, statement := range []string{
		"INSERT INTO app_data_identity VALUES(2,'0123456789abcdef0123456789abcdef',0)",
		"INSERT INTO app_data_identity VALUES(1,'0123456789abcdef0123456789abcdef',0)",
		"UPDATE app_data_identity SET generation='ABCDEF0123456789ABCDEF0123456789'",
		"UPDATE app_data_identity SET generation='short'",
		"UPDATE app_data_identity SET generation=NULL",
		"UPDATE app_data_identity SET generation=CAST(generation AS BLOB)",
		"UPDATE app_data_identity SET reset_legacy=2",
		"UPDATE app_data_identity SET reset_legacy=NULL",
	} {
		if _, err := db.db.Exec(statement); err == nil {
			t.Fatalf("invalid identity write accepted: %s", statement)
		}
	}
	if after := readDataIdentity(t, db.db); after != before {
		t.Fatal("rejected writes changed identity")
	}
}

func TestDataIdentityCorruptionFailsWithoutRegeneration(t *testing.T) {
	valid := "0123456789abcdef0123456789abcdef"
	cases := []struct {
		name       string
		id         any
		generation any
		reset      any
		rows       int
	}{
		{"short", 1, "abc", 0, 1},
		{"long", 1, valid + "0", 0, 1},
		{"uppercase", 1, strings.ToUpper(valid), 0, 1},
		{"non-hex", 1, strings.Repeat("g", 32), 0, 1},
		{"whitespace", 1, valid[:31] + " ", 0, 1},
		{"nul", 1, valid[:31] + "\x00", 0, 1},
		{"blob", 1, []byte(valid), 0, 1},
		{"null-generation", 1, nil, 0, 1},
		{"numeric-generation", 1, 12345, 0, 1},
		{"bad-flag", 1, valid, 2, 1},
		{"negative-flag", 1, valid, -1, 1},
		{"null-flag", 1, valid, nil, 1},
		{"text-flag", 1, valid, "0", 1},
		{"real-flag", 1, valid, 0.0, 1},
		{"wrong-singleton-id", 2, valid, 0, 1},
		{"text-singleton-id", "1", valid, 0, 1},
		{"null-singleton-id", nil, valid, 0, 1},
		{"duplicate", 1, valid, 0, 2},
		{"missing-row", 1, valid, 0, 0},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "corrupt.db")
			raw := identityRawDB(t, path)
			// 无约束坏表模拟磁盘/手工损坏；初始化必须主动验证，不能只信任 DDL。
			identityExec(t, raw, "CREATE TABLE app_data_identity (id, generation, reset_legacy)")
			for i := 0; i < test.rows; i++ {
				identityExec(t, raw, "INSERT INTO app_data_identity VALUES(?,?,?)", test.id, test.generation, test.reset)
			}
			before := identitySnapshot(t, raw)
			db, err := Open(path)
			if db != nil {
				db.Close()
			}
			if err == nil || db != nil {
				t.Fatal("corrupt identity must fail initialization without returning a Store")
			}
			if after := identitySnapshot(t, raw); after != before {
				t.Fatal("corrupt identity was replaced or altered")
			}
			assertIdentityMigrationRolledBack(t, raw, true)
		})
	}
	for _, schema := range []string{
		"CREATE TABLE app_data_identity (id INTEGER PRIMARY KEY)",
		"CREATE VIEW app_data_identity AS SELECT 1 AS id, '0123456789abcdef0123456789abcdef' AS generation, 0 AS reset_legacy",
	} {
		t.Run(strings.Fields(schema)[1], func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "bad-schema.db")
			raw := identityRawDB(t, path)
			identityExec(t, raw, schema)
			db, err := Open(path)
			if db != nil {
				db.Close()
			}
			if err == nil || db != nil {
				t.Fatal("malformed identity schema must fail initialization")
			}
		})
	}
}

func identitySnapshot(t *testing.T, db *sql.DB) string {
	t.Helper()
	var snapshot string
	if err := db.QueryRow(`SELECT COALESCE(group_concat(quote(id)||':'||quote(generation)||':'||quote(reset_legacy)||':'||typeof(id)||':'||typeof(generation)||':'||typeof(reset_legacy), '|'),'') FROM app_data_identity`).Scan(&snapshot); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func assertIdentityMigrationRolledBack(t *testing.T, db *sql.DB, hadIdentity bool) {
	t.Helper()
	var identities, migrated, version int
	if err := db.QueryRow("SELECT COUNT(*) FROM sqlite_schema WHERE name='app_data_identity'").Scan(&identities); err != nil {
		t.Fatal(err)
	}
	want := 0
	if hadIdentity {
		want = 1
	}
	if identities != want {
		t.Fatalf("identity DDL escaped rollback: count=%d want=%d", identities, want)
	}
	if err := db.QueryRow("SELECT COUNT(*) FROM sqlite_schema WHERE name='favorite_tracks'").Scan(&migrated); err != nil || migrated != 0 {
		t.Fatalf("library DDL escaped failed transaction: %d %v", migrated, err)
	}
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil || version != 0 {
		t.Fatalf("failed migration claimed success via user_version: %d %v", version, err)
	}
}

func TestDataIdentityFailedMigrationRollsBack(t *testing.T) {
	for _, phase := range []string{"statement", "commit"} {
		t.Run(phase, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "rollback.db")
			raw := identityFailingMigrationDB(t, path, phase)
			db, err := Open(path)
			if db != nil {
				db.Close()
			}
			if err == nil || db != nil {
				t.Fatal("failed transaction returned a successful Store")
			}
			assertIdentityMigrationFailure(t, phase, err)
			assertIdentityMigrationRolledBack(t, raw, false)
			identityExec(t, raw, "DROP TRIGGER fail_migration")
			if err := raw.Close(); err != nil {
				t.Fatal(err)
			}
			db, err = Open(path)
			if err != nil {
				t.Fatalf("recovery after failed initialization: %v", err)
			}
			defer db.Close()
			if readDataIdentity(t, db.db).resetLegacy {
				t.Fatal("retry reclassified pre-existing schema as fresh")
			}
		})
	}
}

func identityFailingMigrationDB(t *testing.T, path, phase string) *sql.DB {
	t.Helper()
	raw := identityRawDB(t, path)
	identityExec(t, raw, "PRAGMA foreign_keys=ON")
	if phase == "statement" {
		identityExec(t, raw, `
 CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL);
 CREATE TRIGGER fail_migration BEFORE INSERT ON settings BEGIN SELECT RAISE(ABORT,'fixture statement failure'); END;`)
	} else {
		// 语句均成功，只有 COMMIT 才触发延迟外键失败。
		identityExec(t, raw, `
 CREATE TABLE providers (id TEXT PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)));
 CREATE TABLE fixture_parent (id INTEGER PRIMARY KEY);
 CREATE TABLE fixture_child (parent_id INTEGER REFERENCES fixture_parent(id) DEFERRABLE INITIALLY DEFERRED);
 CREATE TRIGGER fail_migration AFTER INSERT ON providers BEGIN INSERT INTO fixture_child VALUES(42); END;`)
	}
	return raw
}

func assertIdentityMigrationFailure(t *testing.T, phase string, err error) {
	t.Helper()
	want := "fixture statement failure"
	if phase == "commit" {
		want = "FOREIGN KEY constraint failed"
	}
	if err == nil || !strings.Contains(err.Error(), want) {
		t.Fatalf("failure did not reach expected %s boundary: %v", phase, err)
	}
}

func TestDataIdentityFailedMigrationDoesNotPublishCache(t *testing.T) {
	for _, phase := range []string{"statement", "commit"} {
		t.Run(phase, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "no-cache.db")
			raw := identityFailingMigrationDB(t, path, phase)
			db := &Store{db: raw}
			err := db.migrate(context.Background())
			assertIdentityMigrationFailure(t, phase, err)
			if got := db.DataIdentity(); got != (DataIdentity{}) {
				t.Fatalf("uncommitted identity escaped into Store cache: %+v", got)
			}
			if err := db.Close(); err != nil {
				t.Fatal(err)
			}
			assertIdentityMigrationRolledBack(t, identityRawDB(t, path), false)
		})
	}
}

func TestDataIdentityCacheIsCommittedValueCopy(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "cache.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	persisted := readDataIdentity(t, db.db)
	want := db.DataIdentity()
	if want.Generation != persisted.generation || want.ResetLegacy != persisted.resetLegacy {
		t.Fatal("cache does not match committed identity")
	}
	copy := db.DataIdentity()
	copy.Generation = "caller-mutated"
	copy.ResetLegacy = !copy.ResetLegacy
	if err := db.ClearHistory(context.Background()); err != nil {
		t.Fatal(err)
	}
	settings := DefaultSettings()
	settings.Concurrency = 2
	if err := db.SaveSettings(context.Background(), settings); err != nil {
		t.Fatal(err)
	}
	if readDataIdentity(t, db.db) != persisted {
		t.Fatal("ordinary user-data writes changed generation")
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	if got := db.DataIdentity(); got != want || got == copy {
		t.Fatalf("cache was mutable or read from closed DB: got=%+v want=%+v", got, want)
	}
}

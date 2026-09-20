// Package store 保存本机状态，不保存音频、登录令牌或远端媒体 URL。
package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"melora/internal/model"
	_ "modernc.org/sqlite"
)

type Store struct {
	db           *sql.DB
	dataIdentity DataIdentity
}

var (
	ErrHistoryNotFound      = errors.New("播放历史尚未建立")
	ErrOutcomeEventConflict = errors.New("播放结局事件内容冲突")
)

func DefaultSettings() model.Settings {
	return model.Settings{Concurrency: 1, DefaultQuality: "standard", FileNameFormat: "title-artist", AutoSwitchSource: true, EmbedTags: true}
}

func Open(path string) (*Store, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	if err = os.MkdirAll(filepath.Dir(absolute), 0700); err != nil {
		return nil, err
	}
	if info, statErr := os.Lstat(absolute); statErr == nil {
		if !info.Mode().IsRegular() {
			return nil, errors.New("数据库路径必须为普通文件")
		}
	} else if !errors.Is(statErr, os.ErrNotExist) {
		return nil, statErr
	}
	f, err := os.OpenFile(absolute, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err = f.Chmod(0600); err != nil {
		f.Close()
		return nil, err
	}
	if err = f.Close(); err != nil {
		return nil, err
	}
	uri := &url.URL{Scheme: "file", Path: filepath.ToSlash(absolute)}
	q := url.Values{}
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "synchronous(FULL)")
	uri.RawQuery = q.Encode()
	db, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	s := &Store{db: db}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err = s.migrate(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("初始化数据库: %w", err)
	}
	return s, nil
}

func (s *Store) migrate(ctx context.Context) error {
	var version int
	if err := s.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	if version > 4 {
		return errors.New("数据库版本高于当前程序支持的版本")
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	identity, err := initializeDataIdentity(ctx, tx)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `
 CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS providers (id TEXT PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)));
 CREATE TABLE IF NOT EXISTS favorite_tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, added_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS favorite_playlists (id TEXT PRIMARY KEY, payload TEXT NOT NULL, added_at INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS play_history (id TEXT PRIMARY KEY, payload TEXT NOT NULL, played_at INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'track', context_id TEXT NOT NULL DEFAULT '', play_count INTEGER NOT NULL DEFAULT 1, completed_count INTEGER NOT NULL DEFAULT 0, skip_count INTEGER NOT NULL DEFAULT 0, listened_ms INTEGER NOT NULL DEFAULT 0);
 CREATE INDEX IF NOT EXISTS history_recent ON play_history(played_at DESC);
 CREATE TABLE IF NOT EXISTS play_outcome_events (event_id TEXT PRIMARY KEY, track_id TEXT NOT NULL, played_ms INTEGER NOT NULL, completed INTEGER NOT NULL CHECK(completed IN (0,1)), recorded_at INTEGER NOT NULL);
 CREATE INDEX IF NOT EXISTS play_outcome_events_recent ON play_outcome_events(recorded_at DESC);
 CREATE TABLE IF NOT EXISTS catalog_metadata (id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL);
 CREATE INDEX IF NOT EXISTS catalog_metadata_recent ON catalog_metadata(updated_at DESC);
 CREATE TABLE IF NOT EXISTS download_jobs (id TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS user_playlists (
   id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL,
   created_at TEXT NOT NULL, updated_at TEXT NOT NULL
 );
 CREATE TABLE IF NOT EXISTS user_playlist_tracks (
   playlist_id TEXT NOT NULL REFERENCES user_playlists(id) ON DELETE CASCADE,
   track_id TEXT NOT NULL, payload TEXT NOT NULL, position INTEGER NOT NULL CHECK(position>=0),
   PRIMARY KEY(playlist_id,track_id), UNIQUE(playlist_id,position)
 );
 INSERT INTO providers(id,enabled) VALUES('demo',1) ON CONFLICT(id) DO NOTHING;
 PRAGMA user_version=4;`)
	if err != nil {
		return err
	}
	if err = ensureHistoryColumns(ctx, tx); err != nil {
		return err
	}
	data, err := json.Marshal(DefaultSettings())
	if err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO settings(id,payload) VALUES(1,?) ON CONFLICT(id) DO NOTHING", string(data)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	// 不在 INSERT 后提前发布，COMMIT 失败时不能声称身份已持久化。
	s.dataIdentity = identity
	return nil
}

func (s *Store) Close() error { return s.db.Close() }
func (s *Store) Settings(ctx context.Context) (model.Settings, error) {
	var data string
	settings := DefaultSettings()
	if err := s.db.QueryRowContext(ctx, "SELECT payload FROM settings WHERE id=1").Scan(&data); err != nil {
		return settings, err
	}
	err := json.Unmarshal([]byte(data), &settings)
	return settings, err
}
func (s *Store) SaveSettings(ctx context.Context, settings model.Settings) error {
	data, err := json.Marshal(settings)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, "UPDATE settings SET payload=? WHERE id=1", string(data))
	return err
}
func (s *Store) ProviderEnabled(ctx context.Context) (bool, error) {
	var enabled bool
	err := s.db.QueryRowContext(ctx, "SELECT enabled FROM providers WHERE id='demo'").Scan(&enabled)
	return enabled, err
}
func (s *Store) SetProviderEnabled(ctx context.Context, enabled bool) error {
	_, err := s.db.ExecContext(ctx, "UPDATE providers SET enabled=? WHERE id='demo'", enabled)
	return err
}
func (s *Store) FavoriteTrack(ctx context.Context, track model.Track, add bool) error {
	return s.favorite(ctx, "favorite_tracks", track.ID, track, add)
}
func (s *Store) FavoritePlaylist(ctx context.Context, playlist model.Collection, add bool) error {
	playlist.Tracks = nil
	return s.favorite(ctx, "favorite_playlists", playlist.ID, playlist, add)
}
func (s *Store) favorite(ctx context.Context, table, id string, value any, add bool) error {
	// table 仅由本包内两个固定调用点指定，不接受外部输入。
	if !add {
		_, err := s.db.ExecContext(ctx, "DELETE FROM "+table+" WHERE id=?", id)
		return err
	}
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, "INSERT INTO "+table+"(id,payload,added_at) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING", id, string(data), time.Now().UnixNano())
	return err
}
func list[T any](ctx context.Context, db *sql.DB, query string) ([]T, error) {
	rows, err := db.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]T, 0)
	for rows.Next() {
		var raw string
		var item T
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(raw), &item); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	return result, rows.Err()
}
func (s *Store) FavoriteTracks(ctx context.Context) ([]model.Track, error) {
	return list[model.Track](ctx, s.db, "SELECT payload FROM favorite_tracks ORDER BY added_at DESC,id")
}
func (s *Store) FavoritePlaylists(ctx context.Context) ([]model.Collection, error) {
	return list[model.Collection](ctx, s.db, "SELECT payload FROM favorite_playlists ORDER BY added_at DESC,id")
}

// TimedTrack / TimedCollection 只在服务端推荐画像中使用，不改变现有 API 契约。
type TimedTrack struct {
	Track model.Track
	At    int64
}
type TimedCollection struct {
	Collection model.Collection
	At         int64
}

func (s *Store) FavoriteTracksWithTime(ctx context.Context) ([]TimedTrack, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT payload, added_at FROM favorite_tracks ORDER BY added_at DESC,id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]TimedTrack, 0)
	for rows.Next() {
		var raw string
		var item TimedTrack
		if err := rows.Scan(&raw, &item.At); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(raw), &item.Track); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	return result, rows.Err()
}
func (s *Store) FavoritePlaylistsWithTime(ctx context.Context) ([]TimedCollection, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT payload, added_at FROM favorite_playlists ORDER BY added_at DESC,id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]TimedCollection, 0)
	for rows.Next() {
		var raw string
		var item TimedCollection
		if err := rows.Scan(&raw, &item.At); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(raw), &item.Collection); err != nil {
			return nil, err
		}
		result = append(result, item)
	}
	return result, rows.Err()
}
func (s *Store) History(ctx context.Context, kind string) ([]model.HistoryEntry, error) {
	query := "SELECT payload, kind, context_id, played_at, play_count, completed_count, skip_count, listened_ms FROM play_history"
	args := []any{}
	if kind != "" {
		query += " WHERE kind = ?"
		args = append(args, kind)
	}
	query += " ORDER BY played_at DESC, id"
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]model.HistoryEntry, 0)
	for rows.Next() {
		var raw string
		var entry model.HistoryEntry
		if err := rows.Scan(&raw, &entry.Kind, &entry.ContextID, &entry.PlayedAt, &entry.PlayCount, &entry.CompletedCount, &entry.SkipCount, &entry.ListenedMs); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(raw), &entry.Track); err != nil {
			return nil, err
		}
		result = append(result, entry)
	}
	return result, rows.Err()
}
func (s *Store) AddHistory(ctx context.Context, track model.Track, kind, contextID string) error {
	if kind == "" {
		kind = model.HistoryKindTrack
	}
	if kind == model.HistoryKindTrack {
		contextID = ""
	}
	data, err := json.Marshal(track)
	if err != nil {
		return err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// 同一首歌重复播放累加 play_count，画像才能区分“偶然听过”和“循环播放”。
	_, err = tx.ExecContext(ctx, `INSERT INTO play_history(id,payload,played_at,kind,context_id,play_count) VALUES(?,?,?,?,?,1)
 ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,played_at=excluded.played_at,kind=excluded.kind,context_id=excluded.context_id,play_count=play_count+1`, track.ID, string(data), time.Now().UnixNano(), kind, contextID)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY played_at DESC,id LIMIT 500)")
	if err != nil {
		return err
	}
	return tx.Commit()
}

// RecordHistoryOutcome 记录一次播放结局：完整播完计入 completed，明显提前切歌计入 skip。
func (s *Store) RecordHistoryOutcome(ctx context.Context, id, eventID string, playedMs int64, completed bool) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if eventID != "" {
		result, insertErr := tx.ExecContext(ctx, `INSERT INTO play_outcome_events(event_id,track_id,played_ms,completed,recorded_at)
 VALUES(?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING`, eventID, id, playedMs, completed, time.Now().Unix())
		if insertErr != nil {
			return insertErr
		}
		inserted, rowsErr := result.RowsAffected()
		if rowsErr != nil {
			return rowsErr
		}
		if inserted == 0 {
			var existingTrack string
			var existingPlayedMs int64
			var existingCompleted bool
			if err := tx.QueryRowContext(ctx, `SELECT track_id,played_ms,completed FROM play_outcome_events WHERE event_id=?`, eventID).Scan(&existingTrack, &existingPlayedMs, &existingCompleted); err != nil {
				return err
			}
			if existingTrack != id || existingPlayedMs != playedMs || existingCompleted != completed {
				return ErrOutcomeEventConflict
			}
			return tx.Commit()
		}
	}
	var raw string
	err = tx.QueryRowContext(ctx, "SELECT payload FROM play_history WHERE id=?", id).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		// outcome 必须跟随已成功建立的播放记录，不能把乱序请求伪装成成功。
		return ErrHistoryNotFound
	}
	if err != nil {
		return err
	}
	var track model.Track
	if err := json.Unmarshal([]byte(raw), &track); err != nil {
		return err
	}
	skipped := !completed && playedMs >= 0 && playedMs < historySkipThresholdMs(track.Duration)
	completedCount, skipCount := 0, 0
	if completed {
		completedCount = 1
	} else if skipped {
		skipCount = 1
	}
	if _, err = tx.ExecContext(ctx, `UPDATE play_history
 SET completed_count = completed_count + ?, skip_count = skip_count + ?, listened_ms = listened_ms + ?
 WHERE id = ?`, completedCount, skipCount, playedMs, id); err != nil {
		return err
	}
	// 幂等事件只需覆盖离线重放窗口；同时按时间与条数有界清理。
	if eventID != "" {
		if _, err = tx.ExecContext(ctx, `DELETE FROM play_outcome_events
 WHERE recorded_at < ? OR event_id IN (
   SELECT event_id FROM play_outcome_events ORDER BY recorded_at DESC,event_id DESC LIMIT -1 OFFSET 2048
 )`, time.Now().Add(-30*24*time.Hour).Unix()); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// historySkipThresholdMs：收听时长短于 min(45 秒, 40% 曲目时长) 时视为跳过。
func historySkipThresholdMs(durationSeconds int) int64 {
	threshold := int64(45_000)
	if durationSeconds > 0 {
		if byDuration := int64(durationSeconds) * 400; byDuration < threshold {
			threshold = byDuration
		}
	}
	return threshold
}

// ensureHistoryColumns 让 v1/v2 数据库无损升级：旧行自动落在 track/空上下文与零计数。
func ensureHistoryColumns(ctx context.Context, tx *sql.Tx) error {
	rows, err := tx.QueryContext(ctx, "PRAGMA table_info(play_history)")
	if err != nil {
		return err
	}
	columns := map[string]bool{}
	for rows.Next() {
		var (
			cid        int
			name       string
			columnType string
			notNull    int
			defaultV   any
			primary    int
		)
		if err := rows.Scan(&cid, &name, &columnType, &notNull, &defaultV, &primary); err != nil {
			rows.Close()
			return err
		}
		columns[name] = true
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for name, ddl := range map[string]string{
		"kind":            "ALTER TABLE play_history ADD COLUMN kind TEXT NOT NULL DEFAULT 'track'",
		"context_id":      "ALTER TABLE play_history ADD COLUMN context_id TEXT NOT NULL DEFAULT ''",
		"play_count":      "ALTER TABLE play_history ADD COLUMN play_count INTEGER NOT NULL DEFAULT 1",
		"completed_count": "ALTER TABLE play_history ADD COLUMN completed_count INTEGER NOT NULL DEFAULT 0",
		"skip_count":      "ALTER TABLE play_history ADD COLUMN skip_count INTEGER NOT NULL DEFAULT 0",
		"listened_ms":     "ALTER TABLE play_history ADD COLUMN listened_ms INTEGER NOT NULL DEFAULT 0",
	} {
		if columns[name] {
			continue
		}
		if _, err := tx.ExecContext(ctx, ddl); err != nil {
			return err
		}
	}
	return nil
}
func (s *Store) ClearHistory(ctx context.Context) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "DELETE FROM play_outcome_events"); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM play_history"); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Store) Downloads(ctx context.Context) ([]model.DownloadJob, error) {
	return list[model.DownloadJob](ctx, s.db, "SELECT payload FROM download_jobs ORDER BY created_at DESC,id")
}
func (s *Store) SaveDownload(job model.DownloadJob) error {
	if job.ID == "" {
		return errors.New("下载任务 ID 不能为空")
	}
	data, err := json.Marshal(job)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err = s.db.ExecContext(ctx, `INSERT INTO download_jobs(id,payload,created_at) VALUES(?,?,?)
 ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,created_at=excluded.created_at`, job.ID, string(data), job.CreatedAt)
	return err
}

package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

const (
	MaxUserPlaylists      = 100
	MaxUserPlaylistTracks = 500
)

var (
	ErrInvalidUserPlaylist      = errors.New("标题须为 1–80 个字符，描述最多 300 个字符")
	ErrUserPlaylistLimit        = errors.New("最多创建 100 个自建歌单")
	ErrUserPlaylistTrackLimit   = errors.New("每个自建歌单最多包含 500 首歌曲")
	ErrInvalidUserPlaylistTrack = errors.New("歌曲 ID 不能为空或包含无效字符")
	ErrInvalidUserPlaylistOrder = errors.New("trackIds 必须恰好包含当前歌单的全部歌曲，且不能重复")
)

func playlistText(title, description string) (string, string, error) {
	if !utf8.ValidString(title) || !utf8.ValidString(description) {
		return "", "", ErrInvalidUserPlaylist
	}
	title, description = strings.TrimSpace(title), strings.TrimSpace(description)
	if title == "" || utf8.RuneCountInString(title) > 80 || utf8.RuneCountInString(description) > 300 {
		return "", "", ErrInvalidUserPlaylist
	}
	for _, r := range title {
		if unicode.IsControl(r) {
			return "", "", ErrInvalidUserPlaylist
		}
	}
	for _, r := range description {
		if unicode.IsControl(r) && r != '\n' && r != '\r' && r != '\t' {
			return "", "", ErrInvalidUserPlaylist
		}
	}
	return title, description, nil
}

// 固定精度便于 SQLite 按文本排序；只有实际修改才推进更新时间。
func playlistTimestamp(previous string) string {
	now := time.Now().UTC()
	if old, err := time.Parse(time.RFC3339Nano, previous); err == nil && !now.After(old) {
		now = old.Add(time.Nanosecond)
	}
	return now.Format("2006-01-02T15:04:05.000000000Z")
}

func playlistSummary(p *model.UserPlaylist) {
	p.ProviderID = "local"
	p.TrackCount = len(p.Tracks)
	p.CoverURL = ""
	if len(p.Tracks) > 0 {
		p.CoverURL = p.Tracks[0].CoverURL
	}
}

// UserPlaylists 不读取每张歌单的全部曲目，不执行逐单查询。
func (s *Store) UserPlaylists(ctx context.Context) ([]model.UserPlaylist, error) {
	rows, err := s.db.QueryContext(ctx, `
 SELECT p.id,p.title,p.description,p.created_at,p.updated_at,
        COALESCE(c.track_count,0),COALESCE(first.payload,'')
 FROM user_playlists p
 LEFT JOIN (SELECT playlist_id,COUNT(*) track_count,MIN(position) first_position
            FROM user_playlist_tracks GROUP BY playlist_id) c ON c.playlist_id=p.id
 LEFT JOIN user_playlist_tracks first ON first.playlist_id=p.id AND first.position=c.first_position
 ORDER BY p.created_at DESC,p.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make([]model.UserPlaylist, 0)
	for rows.Next() {
		p := model.UserPlaylist{ProviderID: "local"}
		var first string
		if err := rows.Scan(&p.ID, &p.Title, &p.Description, &p.CreatedAt, &p.UpdatedAt, &p.TrackCount, &first); err != nil {
			return nil, err
		}
		if first != "" {
			var track model.Track
			if err := json.Unmarshal([]byte(first), &track); err != nil {
				return nil, err
			}
			p.CoverURL = track.CoverURL
		}
		result = append(result, p)
	}
	return result, rows.Err()
}

func readUserPlaylist(ctx context.Context, tx *sql.Tx, id string) (model.UserPlaylist, error) {
	p := model.UserPlaylist{Tracks: make([]model.Track, 0)}
	err := tx.QueryRowContext(ctx, "SELECT id,title,description,created_at,updated_at FROM user_playlists WHERE id=?", id).
		Scan(&p.ID, &p.Title, &p.Description, &p.CreatedAt, &p.UpdatedAt)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT payload FROM user_playlist_tracks WHERE playlist_id=? ORDER BY position", id)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw string
		var track model.Track
		if err := rows.Scan(&raw); err != nil {
			return model.UserPlaylist{}, err
		}
		if err := json.Unmarshal([]byte(raw), &track); err != nil {
			return model.UserPlaylist{}, err
		}
		p.Tracks = append(p.Tracks, track)
	}
	if err := rows.Err(); err != nil {
		return model.UserPlaylist{}, err
	}
	playlistSummary(&p)
	return p, nil
}

func (s *Store) UserPlaylist(ctx context.Context, id string) (model.UserPlaylist, error) {
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return model.UserPlaylist{}, err
	}
	defer tx.Rollback()
	p, err := readUserPlaylist(ctx, tx, id)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	if err := tx.Commit(); err != nil {
		return model.UserPlaylist{}, err
	}
	return p, nil
}

func (s *Store) CreateUserPlaylist(ctx context.Context, title, description string) (model.UserPlaylist, error) {
	title, description, err := playlistText(title, description)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	defer tx.Rollback()
	var count int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM user_playlists").Scan(&count); err != nil {
		return model.UserPlaylist{}, err
	}
	if count >= MaxUserPlaylists {
		return model.UserPlaylist{}, ErrUserPlaylistLimit
	}
	now := playlistTimestamp("")
	p := model.UserPlaylist{ID: "local:" + rand.Text(), ProviderID: "local", Title: title, Description: description,
		Tracks: make([]model.Track, 0), CreatedAt: now, UpdatedAt: now}
	if _, err := tx.ExecContext(ctx, "INSERT INTO user_playlists(id,title,description,created_at,updated_at) VALUES(?,?,?,?,?)", p.ID, title, description, now, now); err != nil {
		return model.UserPlaylist{}, err
	}
	if err := tx.Commit(); err != nil {
		return model.UserPlaylist{}, err
	}
	return p, nil
}

// 单连接和事务保证上限检查、成员关系、元数据与返回的完整快照一致。
// 回调不可访问 s.db，避免在单连接事务内等待自身。
func (s *Store) changeUserPlaylist(ctx context.Context, id string, change func(*sql.Tx, *model.UserPlaylist) (bool, error)) (model.UserPlaylist, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	defer tx.Rollback()
	p, err := readUserPlaylist(ctx, tx, id)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	changed, err := change(tx, &p)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	if changed {
		p.UpdatedAt = playlistTimestamp(p.UpdatedAt)
		if _, err := tx.ExecContext(ctx, "UPDATE user_playlists SET title=?,description=?,updated_at=? WHERE id=?", p.Title, p.Description, p.UpdatedAt, id); err != nil {
			return model.UserPlaylist{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return model.UserPlaylist{}, err
	}
	playlistSummary(&p)
	return p, nil
}

func (s *Store) UpdateUserPlaylist(ctx context.Context, id, title, description string) (model.UserPlaylist, error) {
	title, description, err := playlistText(title, description)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	return s.changeUserPlaylist(ctx, id, func(_ *sql.Tx, p *model.UserPlaylist) (bool, error) {
		changed := p.Title != title || p.Description != description
		p.Title, p.Description = title, description
		return changed, nil
	})
}

func (s *Store) DeleteUserPlaylist(ctx context.Context, id string) error {
	// 外键级联仅删除歌单成员关系，与收藏、历史、下载表完全隔离。
	result, err := s.db.ExecContext(ctx, "DELETE FROM user_playlists WHERE id=?", id)
	if err != nil {
		return err
	}
	n, err := result.RowsAffected()
	if err == nil && n == 0 {
		return sql.ErrNoRows
	}
	return err
}

func validPlaylistTrackID(id string) bool {
	return id != "" && len(id) <= 1024 && utf8.ValidString(id) && strings.IndexFunc(id, func(r rune) bool {
		return unicode.IsControl(r) || unicode.IsSpace(r)
	}) == -1
}

// track 必须由 API 的 knownTrack 取得；store 不接受 HTTP 客户端对象。
func (s *Store) AddUserPlaylistTrack(ctx context.Context, id string, track model.Track) (model.UserPlaylist, error) {
	if !validPlaylistTrackID(track.ID) {
		return model.UserPlaylist{}, ErrInvalidUserPlaylistTrack
	}
	data, err := json.Marshal(track)
	if err != nil {
		return model.UserPlaylist{}, err
	}
	return s.changeUserPlaylist(ctx, id, func(tx *sql.Tx, p *model.UserPlaylist) (bool, error) {
		for _, existing := range p.Tracks {
			if existing.ID == track.ID {
				return false, nil
			}
		}
		if len(p.Tracks) >= MaxUserPlaylistTracks {
			return false, ErrUserPlaylistTrackLimit
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO user_playlist_tracks(playlist_id,track_id,payload,position)
 SELECT ?,?,?,COALESCE(MAX(position),-1)+1 FROM user_playlist_tracks WHERE playlist_id=?`, id, track.ID, string(data), id)
		if err != nil {
			return false, err
		}
		p.Tracks = append(p.Tracks, track)
		return true, nil
	})
}

func (s *Store) RemoveUserPlaylistTrack(ctx context.Context, id, trackID string) (model.UserPlaylist, error) {
	if !validPlaylistTrackID(trackID) {
		return model.UserPlaylist{}, ErrInvalidUserPlaylistTrack
	}
	return s.changeUserPlaylist(ctx, id, func(tx *sql.Tx, p *model.UserPlaylist) (bool, error) {
		for i, track := range p.Tracks {
			if track.ID != trackID {
				continue
			}
			if _, err := tx.ExecContext(ctx, "DELETE FROM user_playlist_tracks WHERE playlist_id=? AND track_id=?", id, trackID); err != nil {
				return false, err
			}
			p.Tracks = append(p.Tracks[:i], p.Tracks[i+1:]...)
			return true, nil
		}
		return false, sql.ErrNoRows
	})
}

func (s *Store) ReorderUserPlaylistTracks(ctx context.Context, id string, trackIDs []string) (model.UserPlaylist, error) {
	if trackIDs == nil || len(trackIDs) > MaxUserPlaylistTracks {
		return model.UserPlaylist{}, ErrInvalidUserPlaylistOrder
	}
	return s.changeUserPlaylist(ctx, id, func(tx *sql.Tx, p *model.UserPlaylist) (bool, error) {
		if len(trackIDs) != len(p.Tracks) {
			return false, ErrInvalidUserPlaylistOrder
		}
		byID := make(map[string]model.Track, len(p.Tracks))
		for _, track := range p.Tracks {
			byID[track.ID] = track
		}
		ordered := make([]model.Track, len(trackIDs))
		changed := false
		for i, trackID := range trackIDs {
			track, exists := byID[trackID]
			if !exists {
				return false, ErrInvalidUserPlaylistOrder
			}
			delete(byID, trackID)
			ordered[i] = track
			changed = changed || p.Tracks[i].ID != trackID
		}
		if !changed {
			return false, nil
		}
		// 先完整验证集合，再在同一事务内重建位置，避免唯一位置交换冲突。
		if _, err := tx.ExecContext(ctx, "DELETE FROM user_playlist_tracks WHERE playlist_id=?", id); err != nil {
			return false, err
		}
		stmt, err := tx.PrepareContext(ctx, "INSERT INTO user_playlist_tracks(playlist_id,track_id,payload,position) VALUES(?,?,?,?)")
		if err != nil {
			return false, err
		}
		defer stmt.Close()
		for i, track := range ordered {
			data, err := json.Marshal(track)
			if err != nil {
				return false, err
			}
			if _, err := stmt.ExecContext(ctx, id, track.ID, string(data), i); err != nil {
				return false, err
			}
		}
		p.Tracks = ordered
		return true, nil
	})
}

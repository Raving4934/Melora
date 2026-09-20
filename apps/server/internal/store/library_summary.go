package store

import (
	"context"

	"melora/internal/model"
)

func (s *Store) LibrarySummary(ctx context.Context) (model.LibrarySummary, error) {
	var summary model.LibrarySummary
	err := s.db.QueryRowContext(ctx, `
 SELECT (SELECT COUNT(*) FROM favorite_tracks),
        (SELECT COUNT(*) FROM favorite_playlists),
        (SELECT COUNT(*) FROM play_history),
        (SELECT COUNT(*) FROM play_history WHERE kind = 'track'),
        (SELECT COUNT(*) FROM play_history WHERE kind = 'playlist'),
        (SELECT COUNT(*) FROM play_history WHERE kind = 'audiobook'),
        (SELECT COUNT(*) FROM user_playlists)`).Scan(
		&summary.FavoriteTracks,
		&summary.FavoritePlaylists,
		&summary.History,
		&summary.HistoryTracks,
		&summary.HistoryPlaylists,
		&summary.HistoryAudiobooks,
		&summary.UserPlaylists,
	)
	return summary, err
}

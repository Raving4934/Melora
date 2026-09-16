package store

import (
	"context"
	"errors"
	"time"
)

// DeleteDownloadRecords 仅供持锁的下载 Manager 调用；ID 是 Manager 筛选的完整快照。
// 不按数据库终态再过滤：完成持久化失败时旧 payload 仍可能是 finalizing。
// 不读取 payload/路径、不删除文件，缺失或重复的 ID 均按幂等删除处理。
func (s *Store) DeleteDownloadRecords(ctx context.Context, ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	for _, id := range ids {
		if id == "" {
			return errors.New("下载任务 ID 不能为空")
		}
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// 复用一个单参数语句，避免大量 ID 超出 SQLite 变量数/SQL 长度限制。
	statement, err := tx.PrepareContext(ctx, "DELETE FROM download_jobs WHERE id=?")
	if err != nil {
		return err
	}
	defer statement.Close()
	for _, id := range ids {
		if _, err := statement.ExecContext(ctx, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

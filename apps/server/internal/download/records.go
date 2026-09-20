package download

import (
	"context"
	"errors"
)

var errRecordRemovalUnavailable = errors.New("下载记录清理尚未配置持久化回调")

// SetRecordRemover 是可选能力，不改变 New/Persister 或既有调用者。
// remover 必须同步、原子、在有界时间内删除给定 ID，失败不得部分提交；
// 调用时持有 Manager 锁，不能重入 Manager，不能访问下载文件或重新筛选任务状态。
func (m *Manager) SetRecordRemover(remover func(context.Context, []string) error) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closing {
		return errClosed
	}
	if remover == nil {
		return errRecordRemovalUnavailable
	}
	m.removeRecords = remover
	return nil
}

// ClearRecords 只清除已停止 worker 的终态记录，不取消任务、不检查授权根、
// 不触碰任何音频/附件/part/恢复检查点。返回值与同一把锁内的快照一致。
func (m *Manager) ClearRecords(ctx context.Context) (cleared, remaining int, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	remaining = len(m.jobs)
	if m.closing {
		return 0, remaining, errClosed
	}
	if err := ctx.Err(); err != nil {
		return 0, remaining, err
	}
	ids := make([]string, 0)
	for _, id := range m.order {
		e := m.jobs[id]
		// completed 已经可见但 run 的 defer/worker 收尾尚未结束时，仍不可删除。
		if e.running {
			continue
		}
		switch e.job.State {
		case "completed", "failed", "cancelled":
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 {
		return 0, remaining, nil
	}
	if m.removeRecords == nil {
		return 0, remaining, errRecordRemovalUnavailable
	}
	// 不把 order 或将用于删除的选择集交给回调；DB 失败时内存和 SSE 均不变。
	if err := m.removeRecords(ctx, append([]string(nil), ids...)); err != nil {
		return 0, remaining, errPersist
	}
	// 即使请求此刻取消，DB 已提交也必须同步内存；否则后续动作/重启会复活记录。
	for _, id := range ids {
		delete(m.jobs, id)
	}
	kept := m.order[:0]
	for _, id := range m.order {
		if _, exists := m.jobs[id]; exists {
			kept = append(kept, id)
		}
	}
	clear(m.order[len(kept):])
	m.order = kept
	m.publishLocked()
	return len(ids), len(m.jobs), nil
}

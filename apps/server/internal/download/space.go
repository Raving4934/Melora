package download

import (
	"errors"
	"os"
	"syscall"
	"time"

	"melora/internal/storage"
)

const (
	spaceReserve  = uint64(16 << 20)
	spaceInterval = int64(1 << 20)
)

var (
	errSpace      = errors.New("下载目录可用空间不足，已停止下载并保留未完成文件")
	errSpaceProbe = errors.New("无法确认下载目录可用空间，已停止下载并保留未完成文件")
)

type spaceProbe func(*os.Root) (storage.Info, error)

func diskError(err error) error {
	if errors.Is(err, syscall.ENOSPC) || errors.Is(err, syscall.EDQUOT) {
		return errSpace
	}
	return errFile
}

func (m *Manager) availableSpace(root *os.Root) (uint64, error) {
	info, err := m.probe(root)
	if err != nil || info.AvailableBytes > info.FreeBytes || info.FreeBytes > info.TotalBytes {
		return 0, errSpaceProbe
	}
	return info.AvailableBytes, nil
}

func (m *Manager) checkSpace(root *os.Root, remaining int64) error {
	available, err := m.availableSpace(root)
	if err != nil {
		return err
	}
	if available < spaceReserve || uint64(max(remaining, 0)) > available-spaceReserve {
		return errSpace
	}
	return nil
}

// 每 1 MiB 或 1 秒重新探测；两次探测之间也扣除本任务写入量，
// 未知 Content-Length 不得通过单次小块写入逐渐吃掉低水位。
type spaceGuard struct {
	manager   *Manager
	root      *os.Root
	checked   time.Time
	done      int64
	available uint64
}

func (g *spaceGuard) check(done, total int64, next int) error {
	spent := uint64(max(done-g.done, 0))
	need := uint64(max(total-done, int64(next), 0))
	if g.checked.IsZero() || done-g.done >= spaceInterval || time.Since(g.checked) >= time.Second || spent > g.available || g.available-spent < spaceReserve || need > g.available-spent-spaceReserve {
		available, err := g.manager.availableSpace(g.root)
		if err != nil {
			return err
		}
		g.available, g.done, g.checked = available, done, time.Now()
		spent = 0
	}
	if g.available-spent < spaceReserve || need > g.available-spent-spaceReserve {
		return errSpace
	}
	return nil
}

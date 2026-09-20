package catalog

import (
	"context"
	"strconv"
	"sync"
	"time"

	"melora/internal/model"
)

const (
	txPlaylistCacheLimit = 32
	txPlaylistFreshTTL   = 5 * time.Minute
	txPlaylistRetainTTL  = 15 * time.Minute
	txPlaylistBackoff    = 30 * time.Second
)

type txPlaylistEntry struct {
	items                          []model.Collection
	freshUntil, expiresAt, retryAt time.Time
	err                            error
	ready                          chan struct{}
}
type txPlaylistMemory struct {
	mu      sync.Mutex
	entries map[string]*txPlaylistEntry
}

func cloneTXPlaylists(items []model.Collection) []model.Collection {
	if items == nil {
		return nil
	}
	out := make([]model.Collection, len(items))
	copy(out, items)
	for i := range out {
		if out[i].PlayCount != nil {
			n := *out[i].PlayCount
			out[i].PlayCount = &n
		}
		// 公开歌单列表不携带详情曲目，避免共享可变嵌套数据。
		out[i].Tracks = nil
	}
	return out
}

// 同键请求合并；最多 32 个页/分类（含进行中），不会因连续失败延长成功数据寿命。
// 保留期内刷新失败返回真实旧歌单 + 错误；聚合方必须保留 partial 并标注 playlists scope。
func (q *TX) Playlists(ctx context.Context, category string, page int) ([]model.Collection, error) {
	if page < 1 || page > 50 {
		return nil, ErrInput
	}
	if category == "" {
		category = "all"
	}
	if category != "all" {
		if _, err := txResourceNumber(category, "category"); err != nil {
			return nil, ErrInput
		}
	}
	key := category + ":" + strconv.Itoa(page)
	c := &q.playlists
	for {
		if err := ctx.Err(); err != nil {
			return nil, txRemoteError{cause: err}
		}
		c.mu.Lock()
		now := time.Now()
		if c.entries == nil {
			c.entries = make(map[string]*txPlaylistEntry)
		}
		for k, e := range c.entries {
			if e.ready == nil && !now.Before(e.expiresAt) {
				delete(c.entries, k)
			}
		}
		e := c.entries[key]
		if e != nil {
			if e.ready != nil {
				ready := e.ready
				c.mu.Unlock()
				select {
				case <-ctx.Done():
					return nil, txRemoteError{cause: ctx.Err()}
				case <-ready:
					continue
				}
			}
			if now.Before(e.freshUntil) || now.Before(e.retryAt) {
				items, err := cloneTXPlaylists(e.items), e.err
				c.mu.Unlock()
				if ctx.Err() != nil {
					return nil, txRemoteError{cause: ctx.Err()}
				}
				return items, err
			}
		} else {
			if len(c.entries) >= txPlaylistCacheLimit {
				oldestKey := ""
				var oldest time.Time
				for k, entry := range c.entries {
					if entry.ready == nil && (oldestKey == "" || entry.expiresAt.Before(oldest)) {
						oldestKey, oldest = k, entry.expiresAt
					}
				}
				if oldestKey == "" {
					c.mu.Unlock()
					return nil, txRemoteError{code: "upstream_unavailable"}
				}
				delete(c.entries, oldestKey)
			}
			e = &txPlaylistEntry{}
			c.entries[key] = e
		}
		e.ready = make(chan struct{})
		c.mu.Unlock()

		items, err := q.publicPlaylists(ctx, category, page)
		c.mu.Lock()
		now = time.Now()
		if ctx.Err() != nil {
			// 调用方取消不污染失败退避，也不清除先前的成功数据。
			close(e.ready)
			e.ready = nil
			if e.expiresAt.IsZero() {
				delete(c.entries, key)
			}
			var retained []model.Collection
			if now.Before(e.expiresAt) {
				retained = cloneTXPlaylists(e.items)
			}
			c.mu.Unlock()
			return retained, txRemoteError{cause: ctx.Err()}
		}
		if err == nil {
			e.items = cloneTXPlaylists(items)
			e.freshUntil, e.expiresAt = now.Add(txPlaylistFreshTTL), now.Add(txPlaylistRetainTTL)
			e.retryAt, e.err = time.Time{}, nil
		} else {
			e.err, e.retryAt = err, now.Add(txPlaylistBackoff)
			if !now.Before(e.expiresAt) {
				e.items = nil
				e.expiresAt = e.retryAt
			}
		}
		items, err = cloneTXPlaylists(e.items), e.err
		close(e.ready)
		e.ready = nil
		c.mu.Unlock()
		return items, err
	}
}

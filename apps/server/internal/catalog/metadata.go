package catalog

import (
	"bytes"
	"context"
	"encoding/json"
	"melora/internal/model"
	"strings"
	"sync"
	"time"
)

// MetadataStore 是可选的私有目录缓存；不可用时仍可正常请求真实上游，不缓存失败或媒体地址。
type MetadataStore interface {
	CatalogMetadata(context.Context, string) (model.CatalogMetadata, error)
	SaveCatalogMetadata(context.Context, []model.CatalogMetadata) error
}
type metadataEntry struct {
	raw     []byte
	expires time.Time
}
type metadataMemory struct {
	mu      sync.Mutex
	entries map[string]metadataEntry
	order   []string
	bytes   int
}

func (m *metadataMemory) put(snapshot model.CatalogMetadata) {
	if snapshot.Track.ID == "" || snapshot.Track.Title == "" || !strings.HasPrefix(snapshot.Track.ID, snapshot.Track.ProviderID+":") {
		return
	}
	snapshot.Track.CanDownload = false
	snapshot.Track.Qualities = []string{}
	raw, err := json.Marshal(snapshot)
	if err != nil || len(raw) > 32<<10 {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.entries == nil {
		m.entries = make(map[string]metadataEntry)
	}
	id := snapshot.Track.ID
	if old, exists := m.entries[id]; exists {
		m.bytes -= len(old.raw)
	} else {
		m.order = append(m.order, id)
	}
	m.entries[id] = metadataEntry{raw: raw, expires: time.Now().Add(24 * time.Hour)}
	m.bytes += len(raw)
	for len(m.entries) > 4000 || m.bytes > 16<<20 {
		old := m.order[0]
		m.order = m.order[1:]
		m.bytes -= len(m.entries[old].raw)
		delete(m.entries, old)
	}
}
func (m *metadataMemory) get(id string) (model.CatalogMetadata, bool) {
	m.mu.Lock()
	entry, ok := m.entries[id]
	m.mu.Unlock()
	var out model.CatalogMetadata
	if !ok || time.Now().After(entry.expires) {
		return out, false
	}
	decoder := json.NewDecoder(bytes.NewReader(entry.raw))
	decoder.UseNumber()
	if decoder.Decode(&out) != nil || out.Track.ID != id {
		return model.CatalogMetadata{}, false
	}
	return out, true
}
func (r *Registry) SetMetadataStore(store MetadataStore) { r.metadataStore = store }
func (r *Registry) cachedMetadata(ctx context.Context, id string) (model.CatalogMetadata, bool) {
	if item, ok := r.metadata.get(id); ok {
		return item, true
	}
	if r.metadataStore != nil {
		item, err := r.metadataStore.CatalogMetadata(ctx, id)
		if err == nil && item.Track.ID == id && strings.HasPrefix(id, item.Track.ProviderID+":") {
			r.metadata.put(item)
			return item, true
		}
	}
	return model.CatalogMetadata{}, false
}
func adapterMetadata(a Adapter, id string) (model.CatalogMetadata, bool) {
	switch a := a.(type) {
	case *WY:
		return a.metadata.get(id)
	case *TX:
		return a.metadata.get(id)
	case *KW:
		return a.metadata.get(id)
	case *KG:
		return a.metadata.get(id)
	case *MG:
		return a.metadata.get(id)
	}
	return model.CatalogMetadata{}, false
}
func (r *Registry) rememberTracks(ctx context.Context, tracks []model.Track) {
	snapshots := make([]model.CatalogMetadata, 0, min(len(tracks), 1000))
	for _, track := range tracks {
		a, err := r.byID(track.ID)
		if err != nil || track.ProviderID == "" {
			continue
		}
		item, ok := adapterMetadata(a, track.ID)
		if !ok {
			item = model.CatalogMetadata{Track: track}
		}
		if prior, found := r.metadata.get(track.ID); found && item.MusicInfo == nil {
			item.MusicInfo = prior.MusicInfo
		}
		r.metadata.put(item)
		snapshots = append(snapshots, item)
		if len(snapshots) == 1000 {
			break
		}
	}
	if r.metadataStore != nil && len(snapshots) > 0 {
		_ = r.metadataStore.SaveCatalogMetadata(ctx, snapshots)
	}
}
func (q *TX) cachedTracks(songs []txSong, limit int) ([]model.Track, error) {
	tracks, err := txTracks(songs, limit)
	if err != nil {
		return tracks, err
	}
	for _, song := range songs {
		if t, ok := txTrack(song); ok {
			music, e := txMusicInfo(song)
			if e == nil {
				q.metadata.put(model.CatalogMetadata{Track: t, MusicInfo: music})
			}
		}
	}
	return tracks, nil
}
func (k *KW) cachedTracks(rows []kwObject, limit int) ([]model.Track, error) {
	tracks, err := kwSongs(rows, limit)
	if err != nil {
		return tracks, err
	}
	for _, row := range rows {
		if t, m, ok := kwSong(row); ok {
			k.metadata.put(model.CatalogMetadata{Track: t, MusicInfo: m})
		}
	}
	return tracks, nil
}
func (k *KG) cachedTracks(rows []kgObject, limit int) ([]model.Track, error) {
	tracks, err := kgTracks(rows, limit)
	if err != nil {
		return tracks, err
	}
	for _, row := range rows {
		if t, m, ok := kgSong(row); ok {
			if n, valid := m["songmid"].(int64); !valid || n <= 0 {
				m = nil
			}
			k.metadata.put(model.CatalogMetadata{Track: t, MusicInfo: m})
		}
	}
	return tracks, nil
}
func (m *MG) cachedTracks(rows []mgObject, limit int) ([]model.Track, error) {
	tracks, err := mgSongs(rows, limit)
	if err != nil {
		return tracks, err
	}
	for _, row := range rows {
		if t, ok := mgSong(row); ok {
			m.metadata.put(model.CatalogMetadata{Track: t, MusicInfo: mgMusicInfo(row, t)})
		}
	}
	return tracks, nil
}

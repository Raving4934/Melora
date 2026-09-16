package store

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"melora/internal/model"
	"strings"
	"time"
)

var errCatalogMetadata = errors.New("目录元数据缓存无效")
var metadataFields = map[string]bool{}

func init() {
	for _, field := range strings.Fields("id songmid songId songid name songname songName singer artist source albumId albumMid albummid albumid albumNumericId albumName duration interval strMediaMid img types _types typeUrl hash _interval copyrightId rid musicId MUSICRID musicrid musicrId") {
		metadataFields[field] = true
	}
}
func encodeCatalogMetadata(item model.CatalogMetadata) ([]byte, error) {
	if item.Track.ID == "" || len(item.Track.ID) > 200 || !strings.HasPrefix(item.Track.ID, item.Track.ProviderID+":") || item.Track.Title == "" {
		return nil, errCatalogMetadata
	}
	switch item.Track.ProviderID {
	case "wy", "tx", "kw", "kg", "mg":
	default:
		return nil, errCatalogMetadata
	}
	if item.MusicInfo != nil {
		if item.MusicInfo["source"] != item.Track.ProviderID {
			return nil, errCatalogMetadata
		}
		for key := range item.MusicInfo {
			if !metadataFields[key] {
				return nil, errCatalogMetadata
			}
		}
		if urls, exists := item.MusicInfo["typeUrl"]; exists {
			raw, e := json.Marshal(urls)
			if e != nil || string(raw) != "{}" {
				return nil, errCatalogMetadata
			}
		}
	}
	item.Track.CanDownload = false
	item.Track.Qualities = []string{}
	raw, err := json.Marshal(item)
	if err != nil || len(raw) > 32<<10 {
		return nil, errCatalogMetadata
	}
	return raw, nil
}
func (s *Store) CatalogMetadata(ctx context.Context, id string) (model.CatalogMetadata, error) {
	var raw string
	var item model.CatalogMetadata
	err := s.db.QueryRowContext(ctx, "SELECT payload FROM catalog_metadata WHERE id=? AND updated_at>=?", id, time.Now().Add(-14*24*time.Hour).Unix()).Scan(&raw)
	if err != nil {
		return item, err
	}
	if len(raw) > 32<<10 {
		return item, errCatalogMetadata
	}
	dec := json.NewDecoder(bytes.NewBufferString(raw))
	dec.UseNumber()
	if dec.Decode(&item) != nil || item.Track.ID != id {
		return model.CatalogMetadata{}, errCatalogMetadata
	}
	if _, err = encodeCatalogMetadata(item); err != nil {
		return model.CatalogMetadata{}, err
	}
	return item, nil
}
func (s *Store) SaveCatalogMetadata(ctx context.Context, items []model.CatalogMetadata) error {
	if len(items) > 1000 {
		return errCatalogMetadata
	}
	type row struct {
		id  string
		raw []byte
	}
	rows := make([]row, 0, len(items))
	for _, item := range items {
		raw, err := encodeCatalogMetadata(item)
		if err != nil {
			return err
		}
		rows = append(rows, row{item.Track.ID, raw})
	}
	if len(rows) == 0 {
		return nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := time.Now().Unix()
	for _, r := range rows {
		if _, err = tx.ExecContext(ctx, `INSERT INTO catalog_metadata(id,payload,updated_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at`, r.id, string(r.raw), now); err != nil {
			return err
		}
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM catalog_metadata WHERE id IN (SELECT id FROM catalog_metadata ORDER BY updated_at DESC,id DESC LIMIT -1 OFFSET 4000) OR updated_at<?`, time.Now().Add(-14*24*time.Hour).Unix()); err != nil {
		return err
	}
	return tx.Commit()
}

package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
)

// DataIdentity 标识同一份持久化数据库，不是用户身份或访问凭据。
// ResetLegacy 只在首次初始化时判定，正常旧库升级不会要求清理旧浏览器状态。
type DataIdentity struct {
	Generation  string `json:"generation"`
	ResetLegacy bool   `json:"resetLegacy"`
}

// DataIdentity 返回迁移提交后缓存的值副本，会话轮询不访问 SQLite。
func (s *Store) DataIdentity() DataIdentity { return s.dataIdentity }

func initializeDataIdentity(ctx context.Context, tx *sql.Tx) (DataIdentity, error) {
	var hasSchema, hasIdentity bool
	// 必须在任何迁移 DDL 前检查；空表也是旧库，不能根据迁移后的表或行数判定。
	if err := tx.QueryRowContext(ctx, `
 SELECT EXISTS(SELECT 1 FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*'),
        EXISTS(SELECT 1 FROM sqlite_schema WHERE type='table' AND name='app_data_identity')`).Scan(&hasSchema, &hasIdentity); err != nil {
		return DataIdentity{}, err
	}
	if !hasIdentity {
		if _, err := tx.ExecContext(ctx, `
 CREATE TABLE app_data_identity (
   id INTEGER PRIMARY KEY CHECK(id=1),
   generation TEXT NOT NULL CHECK(typeof(generation)='text' AND length(generation)=32 AND generation NOT GLOB '*[^0-9a-f]*'),
   reset_legacy INTEGER NOT NULL CHECK(typeof(reset_legacy)='integer' AND reset_legacy IN (0,1))
 );`); err != nil {
			return DataIdentity{}, err
		}
		var random [16]byte
		if _, err := rand.Read(random[:]); err != nil {
			return DataIdentity{}, fmt.Errorf("生成数据库数据标识: %w", err)
		}
		if _, err := tx.ExecContext(ctx, "INSERT INTO app_data_identity(id,generation,reset_legacy) VALUES(1,?,?)", hex.EncodeToString(random[:]), !hasSchema); err != nil {
			return DataIdentity{}, err
		}
	}

	// 即使表已存在也严格校验；缺行、重复或损坏时失败，不静默生成新的代际。
	var identity DataIdentity
	var id, resetLegacy, count int
	var validTypes bool
	if err := tx.QueryRowContext(ctx, `
 SELECT id, generation, reset_legacy,
        typeof(id)='integer' AND typeof(generation)='text' AND typeof(reset_legacy)='integer',
        (SELECT COUNT(*) FROM app_data_identity)
 FROM app_data_identity`).Scan(&id, &identity.Generation, &resetLegacy, &validTypes, &count); err != nil {
		return DataIdentity{}, fmt.Errorf("读取数据库数据标识: %w", err)
	}
	if id != 1 || count != 1 || !validTypes || (resetLegacy != 0 && resetLegacy != 1) || !validDataGeneration(identity.Generation) {
		return DataIdentity{}, errors.New("数据库数据标识无效")
	}
	identity.ResetLegacy = resetLegacy == 1
	return identity, nil
}

func validDataGeneration(generation string) bool {
	if len(generation) != 32 {
		return false
	}
	for i := 0; i < len(generation); i++ {
		c := generation[i]
		if !(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

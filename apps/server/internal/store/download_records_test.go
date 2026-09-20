package store

import (
	"context"
	"path/filepath"
	"reflect"
	"testing"

	"melora/internal/model"
)

func downloadRecordRemover(t *testing.T, db *Store) func(context.Context, []string) error {
	t.Helper()
	remover, ok := any(db).(interface {
		DeleteDownloadRecords(context.Context, []string) error
	})
	if !ok {
		t.Fatal("Store lacks atomic download record deletion")
	}
	return remover.DeleteDownloadRecords
}

func downloadRecordStore(t *testing.T) *Store {
	t.Helper()
	db, err := Open(filepath.Join(t.TempDir(), "records.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	for _, j := range []model.DownloadJob{{ID: "a", State: "completed"}, {ID: "b", State: "failed"}, {ID: "c", State: "cancelled"}, {ID: "active", State: "downloading"}, {ID: "commit-window", State: "finalizing"}, {ID: "x' OR 1=1 --", State: "completed"}} {
		if err := db.SaveDownload(j); err != nil {
			t.Fatal(err)
		}
	}
	return db
}

func TestDeleteDownloadRecordsExactIDsIdempotentAndNoOtherTables(t *testing.T) {
	db := downloadRecordStore(t)
	remove := downloadRecordRemover(t, db)
	ctx := t.Context()
	if _, err := db.db.ExecContext(ctx, `INSERT INTO favorite_tracks(id,payload,added_at) VALUES('a','{}',1)`); err != nil {
		t.Fatal(err)
	}
	if err := remove(ctx, []string{"a", "b", "c", "commit-window", "x' OR 1=1 --", "missing", "a"}); err != nil {
		t.Fatal(err)
	}
	got, err := db.Downloads(ctx)
	if err != nil || len(got) != 1 || got[0].ID != "active" {
		t.Fatalf("removed wrong IDs: %+v %v", got, err)
	}
	// DB 旧状态可能是 finalizing：不能二次按数据库终态筛选造成复活。
	if err := remove(ctx, []string{"a", "commit-window"}); err != nil {
		t.Fatal("not idempotent", err)
	}
	if err := remove(ctx, nil); err != nil {
		t.Fatal("empty batch rejected", err)
	}
	var count int
	if err := db.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM favorite_tracks`).Scan(&count); err != nil || count != 1 {
		t.Fatal("deleted non-download records")
	}
	settings, err := db.Settings(ctx)
	if err != nil || !reflect.DeepEqual(settings, DefaultSettings()) {
		t.Fatal("clear changed settings")
	}
}

func TestDeleteDownloadRecordsRollbackOnStatementAndCommitFailure(t *testing.T) {
	for _, mode := range []string{"statement", "commit", "cancelled", "invalid-id"} {
		t.Run(mode, func(t *testing.T) {
			db := downloadRecordStore(t)
			remove := downloadRecordRemover(t, db)
			before, err := db.Downloads(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			if mode == "statement" {
				_, err = db.db.ExecContext(t.Context(), `CREATE TRIGGER refuse_record_delete BEFORE DELETE ON download_jobs WHEN OLD.id='b' BEGIN SELECT RAISE(ABORT,'injected delete failure'); END`)
			} else if mode == "commit" {
				_, err = db.db.ExecContext(t.Context(), `CREATE TABLE deletion_guard (id TEXT REFERENCES download_jobs(id) DEFERRABLE INITIALLY DEFERRED); INSERT INTO deletion_guard(id) VALUES('b')`)
			}
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			ids := []string{"a", "b", "c"}
			if mode == "cancelled" {
				cancel()
			}
			if mode == "invalid-id" {
				ids = []string{"a", ""}
			}
			if err := remove(ctx, ids); err == nil {
				t.Fatal("injected failure accepted")
			}
			after, err := db.Downloads(t.Context())
			if err != nil || !reflect.DeepEqual(before, after) {
				t.Fatalf("batch partially committed: before=%+v after=%+v err=%v", before, after, err)
			}
		})
	}
}

func TestDeleteDownloadRecordsDurableAfterReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "durable.db")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, id := range []string{"gone", "kept"} {
		if err := db.SaveDownload(model.DownloadJob{ID: id, State: "completed"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := downloadRecordRemover(t, db)(t.Context(), []string{"gone"}); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	got, err := reopened.Downloads(t.Context())
	if err != nil || len(got) != 1 || got[0].ID != "kept" {
		t.Fatalf("records revived on restart: %+v %v", got, err)
	}
}

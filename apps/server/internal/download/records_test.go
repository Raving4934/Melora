package download

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
	"melora/internal/store"
)

type recordClearing interface {
	SetRecordRemover(func(context.Context, []string) error) error
	ClearRecords(context.Context) (int, int, error)
}

func recordCapability(t *testing.T, m *Manager) recordClearing {
	t.Helper()
	capability, ok := any(m).(recordClearing)
	if !ok {
		t.Fatal("Manager lacks optional atomic record clearing")
	}
	return capability
}

func recordFixture(t *testing.T, states ...string) (*Manager, *recordingStore) {
	t.Helper()
	saved := &recordingStore{}
	m := &Manager{jobs: make(map[string]*entry), subscribers: make(map[uint64]chan []model.DownloadJob), persist: saved.save}
	m.cond = sync.NewCond(&m.mu)
	for i, state := range states {
		j := model.DownloadJob{ID: fmt.Sprintf("record-%02d", i), Track: testTrack(fmt.Sprint(i)), State: state, Quality: "standard", CreatedAt: timestamp()}
		m.jobs[j.ID] = &entry{job: j}
		m.order = append(m.order, j.ID)
		if err := saved.save(j); err != nil {
			t.Fatal(err)
		}
	}
	return m, saved
}

func TestClearDownloadRecordsOnlyIdleTerminalAndSSE(t *testing.T) {
	states := []string{"completed", "failed", "cancelled", "queued", "paused", "resolving", "downloading", "retry_wait", "waiting_for_url_refresh", "verifying", "writing_metadata", "finalizing", "unknown", "completed", "failed", "cancelled"}
	m, _ := recordFixture(t, states...)
	cap := recordCapability(t, m)
	for _, id := range m.order[13:] {
		m.jobs[id].running = true
	}
	calls := 0
	if err := cap.SetRecordRemover(func(_ context.Context, ids []string) error {
		calls++
		if !reflect.DeepEqual(ids, []string{"record-00", "record-01", "record-02"}) {
			t.Errorf("wrong terminal snapshot: %v", ids)
		}
		// 回调必须拿独立 ID 副本，不能通过改写参数改变内存删除集合。
		ids[0] = "record-03"
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	updates, unsubscribe := m.Updates()
	defer unsubscribe()
	<-updates
	before := m.List()
	cleared, remaining, err := cap.ClearRecords(t.Context())
	if err != nil || cleared != 3 || remaining != len(states)-3 || calls != 1 {
		t.Fatalf("clear=%d remaining=%d calls=%d err=%v", cleared, remaining, calls, err)
	}
	want := before[:len(before)-3]
	if !reflect.DeepEqual(m.List(), want) {
		t.Fatal("clear changed active/running records or list order")
	}
	select {
	case got := <-updates:
		if !reflect.DeepEqual(got, want) {
			t.Fatal("SSE did not receive remaining snapshot")
		}
	default:
		t.Fatal("clear did not publish SSE")
	}
	cleared, remaining, err = cap.ClearRecords(t.Context())
	if err != nil || cleared != 0 || remaining != len(want) || calls != 1 {
		t.Fatal("clear not idempotent")
	}
	select {
	case <-updates:
		t.Fatal("no-op emitted unnecessary event")
	default:
	}
}

func TestClearDownloadRecordsFailureAndCancellationKeepSnapshot(t *testing.T) {
	for _, mode := range []string{"missing-remover", "database-failure", "cancelled-before", "cancelled-after-commit", "closed"} {
		t.Run(mode, func(t *testing.T) {
			m, _ := recordFixture(t, "completed", "failed", "paused")
			cap := recordCapability(t, m)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			calls := 0
			if mode != "missing-remover" {
				if err := cap.SetRecordRemover(func(context.Context, []string) error {
					calls++
					if mode == "database-failure" {
						return errors.New("SQL/path/token=PRIVATE")
					}
					if mode == "cancelled-after-commit" {
						cancel()
					}
					return nil
				}); err != nil {
					t.Fatal(err)
				}
			}
			if mode == "cancelled-before" {
				cancel()
			}
			if mode == "closed" {
				m.closing = true
			}
			before := m.List()
			updates, unsubscribe := m.Updates()
			defer unsubscribe()
			<-updates
			cleared, remaining, err := cap.ClearRecords(ctx)
			if mode == "cancelled-after-commit" {
				if err != nil || cleared != 2 || remaining != 1 || len(m.List()) != 1 {
					t.Fatal("committed DB deletion abandoned in memory on late cancellation")
				}
			} else {
				if err == nil || cleared != 0 || remaining != 3 || !reflect.DeepEqual(before, m.List()) || strings.Contains(err.Error(), "PRIVATE") {
					t.Fatalf("failed clear partially changed state/leaked error: %d %d %v", cleared, remaining, err)
				}
				if mode != "database-failure" && calls != 0 {
					t.Fatal("invalid operation reached remover")
				}
				if mode != "closed" {
					select {
					case <-updates:
						t.Fatal("failed clear emitted SSE")
					default:
					}
				}
			}
		})
	}
}

func TestClearDownloadRecordsPreservesAllArtifactsWithStorageDisabled(t *testing.T) {
	root := t.TempDir()
	m, _ := recordFixture(t, "completed", "failed", "cancelled", "paused")
	cap := recordCapability(t, m)
	if err := cap.SetRecordRemover(func(context.Context, []string) error { return nil }); err != nil {
		t.Fatal(err)
	}
	originals := make(map[string]os.FileInfo)
	for _, id := range m.order {
		e := m.jobs[id]
		e.root = root
		e.job.TargetPath = filepath.Join(root, "historical-"+id+".flac")
		for _, name := range []string{"historical-" + id + ".flac", "historical-" + id + ".lrc", "historical-" + id + ".jpg", "historical-" + id + ".png", "historical-" + id + ".json", partName(id), tagPartName(id), metaName(id)} {
			path := filepath.Join(root, name)
			if err := os.WriteFile(path, []byte(name), 0600); err != nil {
				t.Fatal(err)
			}
			info, err := os.Stat(path)
			if err != nil {
				t.Fatal(err)
			}
			originals[path] = info
		}
	}
	// Manager 无授权目录句柄，历史 root 可以已停用；清记录不能检查/打开它。
	if m.files != nil || m.root != "" {
		t.Fatal("fixture accidentally enabled storage")
	}
	cleared, remaining, err := cap.ClearRecords(t.Context())
	if err != nil || cleared != 3 || remaining != 1 {
		t.Fatalf("disabled clear failed: %d %d %v", cleared, remaining, err)
	}
	for path, before := range originals {
		after, err := os.Stat(path)
		b, readErr := os.ReadFile(path)
		if err != nil || readErr != nil || !os.SameFile(before, after) || !before.ModTime().Equal(after.ModTime()) || string(b) != filepath.Base(path) {
			t.Fatalf("clear touched artifact %q", path)
		}
	}
}

func TestClearDownloadRecordsSerializesRetryWithoutResurrection(t *testing.T) {
	files, root, err := openStorage(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	for range 100 {
		m, saved := recordFixture(t, "failed")
		m.files, m.root, m.jobs["record-00"].root = files, root, root
		cap := recordCapability(t, m)
		if err := cap.SetRecordRemover(func(_ context.Context, ids []string) error {
			saved.mu.Lock()
			defer saved.mu.Unlock()
			for _, id := range ids {
				delete(saved.latest, id)
			}
			return nil
		}); err != nil {
			t.Fatal(err)
		}
		start := make(chan struct{})
		clearDone := make(chan error, 1)
		retryDone := make(chan error, 1)
		go func() { <-start; _, _, err := cap.ClearRecords(context.Background()); clearDone <- err }()
		go func() { <-start; _, err := m.Action("record-00", "retry"); retryDone <- err }()
		close(start)
		if err := <-clearDone; err != nil {
			t.Fatal(err)
		}
		retryErr := <-retryDone
		jobs := m.List()
		saved.mu.Lock()
		persisted, exists := saved.latest["record-00"]
		saved.mu.Unlock()
		if len(jobs) == 0 {
			if !errors.Is(retryErr, errMissing) || exists {
				t.Fatalf("cleared task resurrected: %v %+v", retryErr, persisted)
			}
		} else if retryErr != nil || len(jobs) != 1 || jobs[0].State != "queued" || !exists || persisted.State != "queued" {
			t.Fatalf("retry winner was cleared: %v %+v", retryErr, jobs)
		}
	}
}

func TestClearDownloadRecordsHoldsLockUntilBatchCommit(t *testing.T) {
	m, _ := recordFixture(t, "completed", "failed", "paused")
	cap := recordCapability(t, m)
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	unblock := func() { once.Do(func() { close(release) }) }
	defer unblock()
	if err := cap.SetRecordRemover(func(context.Context, []string) error { close(entered); <-release; return nil }); err != nil {
		t.Fatal(err)
	}
	finished := make(chan error, 1)
	go func() { _, _, err := cap.ClearRecords(context.Background()); finished <- err }()
	<-entered
	if m.mu.TryLock() {
		m.mu.Unlock()
		t.Fatal("snapshot lock released before transaction commit")
	}
	listed := make(chan []model.DownloadJob, 1)
	go func() { listed <- m.List() }()
	select {
	case <-listed:
		t.Fatal("reader observed in-flight batch")
	case <-time.After(10 * time.Millisecond):
	}
	unblock()
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if got := <-listed; len(got) != 1 || got[0].State != "paused" {
		t.Fatalf("reader saw partial deletion: %+v", got)
	}
}

func TestClearDownloadRecordsCompletedPersistenceFailureNoRestartRevival(t *testing.T) {
	root := t.TempDir()
	db, err := store.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	persist := func(j model.DownloadJob) error {
		if j.State == "completed" {
			return errors.New("injected completed failure")
		}
		return db.SaveDownload(j)
	}
	deps := dependencies{lookup: publicLookup, transport: roundTripFunc(func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }), limits: limits{maxBytes: 1 << 20, totalTime: 3 * time.Second, attempts: 1, progress: time.Millisecond}}
	m, err := newManager(root, 1, fixedResolver, persist, nil, deps)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	cap := recordCapability(t, m)
	remover, ok := any(db).(interface {
		DeleteDownloadRecords(context.Context, []string) error
	})
	if !ok {
		t.Fatal("store remover missing")
	}
	if err := cap.SetRecordRemover(remover.DeleteDownloadRecords); err != nil {
		t.Fatal(err)
	}
	j := createJob(t, m, "commit-window")
	done := rangeTerminal(t, m, j.ID)
	if done.State != "completed" || done.Error != errPersist.Error() {
		t.Fatalf("fault window missing: %+v", done)
	}
	rows, err := db.Downloads(t.Context())
	if err != nil || len(rows) != 1 || rows[0].State != "finalizing" {
		t.Fatalf("unexpected durable checkpoint: %+v %v", rows, err)
	}
	audioBefore := readTarget(t, done)
	privateBefore, err := m.files.ReadFile(metaName(j.ID))
	if err != nil {
		t.Fatal(err)
	}
	// 模拟 completed 已发布但 worker 仍收尾；即便终态也不能删。
	m.mu.Lock()
	m.jobs[j.ID].running = true
	m.mu.Unlock()
	if n, remaining, err := cap.ClearRecords(t.Context()); err != nil || n != 0 || remaining != 1 {
		t.Fatal("running completion cleared")
	}
	m.mu.Lock()
	m.jobs[j.ID].running = false
	m.mu.Unlock()
	if n, remaining, err := cap.ClearRecords(t.Context()); err != nil || n != 1 || remaining != 0 {
		t.Fatalf("clear completed memory/older DB state: %d %d %v", n, remaining, err)
	}
	privateAfter, err := m.files.ReadFile(metaName(j.ID))
	if err != nil || !bytes.Equal(audioBefore, readTarget(t, done)) || !bytes.Equal(privateBefore, privateAfter) {
		t.Fatal("clear deleted/changed published proof or audio")
	}
	m.Close()
	rows, err = db.Downloads(t.Context())
	if err != nil || len(rows) != 0 {
		t.Fatalf("DB record revived: %+v %v", rows, err)
	}
	restored, err := New("", 1, fixedResolver, db.SaveDownload, rows)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	if len(restored.List()) != 0 {
		t.Fatal("hidden checkpoint resurrected cleared record")
	}
}

func TestClearDownloadRecordsRacesWorkerFinalization(t *testing.T) {
	root := t.TempDir()
	db, err := store.Open(filepath.Join(t.TempDir(), "racing.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	deps := dependencies{lookup: publicLookup, transport: roundTripFunc(func(r *http.Request) (*http.Response, error) { return response(r, 200, v5MP3()), nil }), limits: limits{maxBytes: 1 << 20, totalTime: 5 * time.Second, attempts: 1, progress: time.Millisecond}}
	m, err := newManager(root, 3, fixedResolver, db.SaveDownload, nil, deps)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	cap := recordCapability(t, m)
	remover, ok := any(db).(interface {
		DeleteDownloadRecords(context.Context, []string) error
	})
	if !ok {
		t.Fatal("missing store remover")
	}
	paths := make(map[string]string)
	var removed atomic.Int32
	if err := cap.SetRecordRemover(func(ctx context.Context, ids []string) error {
		// 回调运行在 Manager 锁内，仅在测试中检查每个选择的 worker 生命周期。
		for _, id := range ids {
			e := m.jobs[id]
			if e.running || e.job.State != "completed" {
				t.Errorf("selected unfinished worker: %s %+v", id, e.job)
			}
			paths[id] = e.job.TargetPath
		}
		if err := remover.DeleteDownloadRecords(ctx, ids); err != nil {
			return err
		}
		removed.Add(int32(len(ids)))
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	finished := make(chan error, 1)
	go func() {
		for {
			_, _, err := cap.ClearRecords(ctx)
			if err != nil {
				finished <- err
				return
			}
			select {
			case <-ctx.Done():
				finished <- ctx.Err()
				return
			case <-time.After(time.Millisecond):
			}
		}
	}()
	// 失败时也先结束清理 goroutine，才能安全关闭 Manager/SQLite。
	joined := false
	defer func() {
		cancel()
		if !joined {
			<-finished
		}
	}()
	const count = 18
	for i := range count {
		createJob(t, m, fmt.Sprintf("racing-%d", i))
	}
	deadline := time.Now().Add(10 * time.Second)
	for removed.Load() != count {
		if time.Now().After(deadline) {
			t.Fatalf("workers/clear did not converge: removed=%d jobs=%+v", removed.Load(), m.List())
		}
		time.Sleep(time.Millisecond)
	}
	cancel()
	if err := <-finished; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	joined = true
	m.Close()
	saved, err := db.Downloads(t.Context())
	if err != nil || len(saved) != 0 || len(m.List()) != 0 {
		t.Fatalf("worker resurrected cleared records: %+v %v", saved, err)
	}
	for id, path := range paths {
		b, err := os.ReadFile(path)
		if err != nil || !bytes.Equal(b, v5MP3()) {
			t.Fatalf("clear damaged completed audio %s: %v", id, err)
		}
		if _, err := os.Stat(filepath.Join(root, "Singles", metaName(id))); err != nil {
			t.Fatal("clear removed private checkpoint", err)
		}
	}
}

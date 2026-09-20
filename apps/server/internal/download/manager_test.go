package download

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/model"
)

func TestCreateMustPersistBeforeAcknowledgementAndWorker(t *testing.T) {
	var resolved atomic.Int32
	store := &recordingStore{reject: func(j model.DownloadJob) bool { return j.State == "queued" }}
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) { return response(r, 200, audio(1024)), nil }, func(ctx context.Context, tr model.Track, q string) (string, error) {
		resolved.Add(1)
		return fixedResolver(ctx, tr, q)
	}, store, nil, nil)
	if _, err := m.Create(testTrack("reject"), "standard"); !errors.Is(err, errPersist) || strings.Contains(err.Error(), "SECRET") {
		t.Fatal("create persistence error not sanitized")
	}
	if len(m.List()) != 0 || resolved.Load() != 0 {
		t.Fatal("failed create entered queue")
	}
}
func TestPersistFailureStopsBeforeNetworkAndActionAck(t *testing.T) {
	store := &recordingStore{reject: func(j model.DownloadJob) bool { return j.State == "resolving" }}
	var called atomic.Int32
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		called.Add(1)
		return response(r, 200, audio(1024)), nil
	}, nil, store, nil, nil)
	j := createJob(t, m, "persist")
	failed := waitJob(t, m, j.ID, "failed")
	if called.Load() != 0 || failed.Error != errPersist.Error() {
		t.Fatal("state persistence did not stop worker")
	}
	root := t.TempDir()
	restored := seedPartial(t, root, audio(1024), 2048)
	store2 := &recordingStore{}
	m2 := testManager(t, root, func(r *http.Request) (*http.Response, error) {
		called.Add(1)
		return response(r, 200, audio(2048)), nil
	}, nil, store2, []model.DownloadJob{restored}, nil)
	store2.mu.Lock()
	store2.reject = func(j model.DownloadJob) bool { return j.State == "queued" || j.State == "cancelled" }
	store2.mu.Unlock()
	for _, action := range []string{"resume", "cancel"} {
		if _, err := m2.Action(restored.ID, action); !errors.Is(err, errPersist) {
			t.Fatalf("action acked failed persistence %v", err)
		}
	}
	if m2.List()[0].State != "paused" {
		t.Fatal("failed action changed state")
	}
	if _, err := os.Stat(filepath.Join(root, "Singles", partName(restored.ID))); err != nil {
		t.Fatal("cancel deleted part before commit")
	}
}
func TestCompletionCommitFailureNeverDeletesFinal(t *testing.T) {
	root := t.TempDir()
	store := &recordingStore{reject: func(j model.DownloadJob) bool { return j.State == "completed" }}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return response(r, 200, audio(2048)), nil }, nil, store, nil, nil)
	j := createJob(t, m, "completed-commit")
	done := waitJob(t, m, j.ID, "completed")
	waitIdle(t, m, j.ID)
	if done.Error != errPersist.Error() {
		t.Fatalf("completion persistence failure hidden: %+v", done)
	}
	if _, err := m.Action(j.ID, "cancel"); err == nil {
		t.Fatal("allowed deleting completed file")
	}
	if len(readTarget(t, done)) != 2048 {
		t.Fatal("lost final file")
	}
	m.Close()
	saved := store.get(j.ID)
	if saved.State != "finalizing" {
		t.Fatalf("wrong recovery checkpoint %+v", saved)
	}
	recoveryStore := &recordingStore{}
	m2 := testManager(t, root, func(*http.Request) (*http.Response, error) { t.Fatal("unnecessary download"); return nil, nil }, nil, recoveryStore, []model.DownloadJob{saved}, nil)
	if _, err := m2.Action(j.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	waitJob(t, m2, j.ID, "completed")
}
func TestDisabledRootCapabilitiesAndInvalidConfiguration(t *testing.T) {
	m, err := New("", 0, fixedResolver, func(model.DownloadJob) error { return nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	if _, err := m.Create(testTrack("x"), "standard"); !errors.Is(err, errDisabled) {
		t.Fatal("empty root enabled download")
	}
	for _, n := range []int{-1, 4} {
		if err := m.Configure("", n); err == nil {
			t.Fatal("invalid concurrency accepted")
		}
	}
	if err := m.Configure(t.TempDir(), 1); err != nil {
		t.Fatal(err)
	}
	tr := testTrack("no-auth")
	tr.CanDownload = false
	if _, err := m.Create(tr, "standard"); err == nil {
		t.Fatal("unlicensed track accepted")
	}
	if _, err := m.Create(testTrack("quality"), "unknown"); err == nil {
		t.Fatal("unsupported quality accepted")
	}
	for _, args := range []struct {
		resolver Resolver
		persist  Persister
	}{{nil, func(model.DownloadJob) error { return nil }}, {fixedResolver, nil}} {
		if manager, err := New("", 1, args.resolver, args.persist, nil); err == nil {
			manager.Close()
			t.Fatal("missing callbacks accepted")
		}
	}
}
func TestConfigureDifferentRootDoesNotMoveOrDeleteOldParts(t *testing.T) {
	root := t.TempDir()
	other := t.TempDir()
	job := seedPartial(t, root, audio(1024), 2048)
	m := testManager(t, root, func(*http.Request) (*http.Response, error) { t.Fatal("unexpected request"); return nil, nil }, nil, nil, []model.DownloadJob{job}, nil)
	if err := m.Configure(other, 2); err != nil {
		t.Fatal(err)
	}
	for _, action := range []string{"resume", "cancel"} {
		if _, err := m.Action(job.ID, action); !errors.Is(err, errRootChanged) {
			t.Fatal("old job bound to new root")
		}
	}
	if _, err := os.Stat(filepath.Join(root, "Singles", partName(job.ID))); err != nil {
		t.Fatal("lost old partial")
	}
	if err := m.Configure(root, 1); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Action(job.ID, "cancel"); err != nil {
		t.Fatal(err)
	}
	requirePartAbsent(t, root, job.ID)
}
func TestStateTransitionsQueuedPauseCancelAndRetry(t *testing.T) {
	blocked := make(chan struct{})
	var requests atomic.Int32
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		if requests.Add(1) == 1 {
			return blockingResponse(r, audio(8192), blocked), nil
		}
		return response(r, 200, audio(8192)), nil
	}, nil, nil, nil, nil)
	first := createJob(t, m, "first")
	select {
	case <-blocked:
	case <-time.After(3 * time.Second):
		t.Fatal("first not started")
	}
	second := createJob(t, m, "second")
	if second.State != "queued" {
		t.Fatal("second not queued")
	}
	for _, action := range []string{"resume", "retry", "bogus"} {
		if _, err := m.Action(second.ID, action); !errors.Is(err, errAction) {
			t.Fatal("invalid queued action accepted")
		}
	}
	if _, err := m.Action(second.ID, "pause"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Action(second.ID, "pause"); err != nil {
		t.Fatal("pause not idempotentent")
	}
	if _, err := m.Action(second.ID, "retry"); err == nil {
		t.Fatal("retry paused accepted")
	}
	if _, err := m.Action(second.ID, "resume"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Action(second.ID, "cancel"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Action(second.ID, "resume"); err == nil {
		t.Fatal("resumed cancelled job")
	}
	if _, err := m.Action(first.ID, "cancel"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Action("missing", "pause"); !errors.Is(err, errMissing) {
		t.Fatal("missing not rejected")
	}
	var attempts atomic.Int32
	retry := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		if attempts.Add(1) <= 3 {
			return response(r, 503, nil), nil
		}
		return response(r, 200, audio(1024)), nil
	}, nil, nil, nil, nil)
	job := createJob(t, retry, "retry")
	waitJob(t, retry, job.ID, "failed")
	waitIdle(t, retry, job.ID)
	if _, err := retry.Action(job.ID, "resume"); err == nil {
		t.Fatal("resumed failed rather than retry")
	}
	if _, err := retry.Action(job.ID, "retry"); err != nil {
		t.Fatal(err)
	}
	waitJob(t, retry, job.ID, "completed")
}
func TestClosePausesQueueAndClosesSubscriptionsIdempotently(t *testing.T) {
	blocked := make(chan struct{})
	store := &recordingStore{}
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) { return blockingResponse(r, audio(8192), blocked), nil }, nil, store, nil, nil)
	events, unsub := m.Updates()
	first := createJob(t, m, "one")
	select {
	case <-blocked:
	case <-time.After(3 * time.Second):
		t.Fatal("first not started")
	}
	second := createJob(t, m, "two")
	var wg sync.WaitGroup
	for range 4 {
		wg.Add(1)
		go func() { defer wg.Done(); m.Close() }()
	}
	wg.Wait()
	unsub()
	unsub()
	for range events {
	}
	for _, id := range []string{first.ID, second.ID} {
		if store.get(id).State != "paused" {
			t.Fatal("Close did not persist pause")
		}
	}
	if _, err := m.Create(testTrack("new"), "standard"); !errors.Is(err, errClosed) {
		t.Fatal("create after close")
	}
	if _, err := m.Action(first.ID, "resume"); !errors.Is(err, errClosed) {
		t.Fatal("action after close")
	}
	if err := m.Configure(t.TempDir(), 1); !errors.Is(err, errClosed) {
		t.Fatal("configure after close")
	}
	closed, cancel := m.Updates()
	defer cancel()
	<-closed
	if _, ok := <-closed; ok {
		t.Fatal("post-close subscription left open")
	}
}
func TestSlowSubscribersAndConcurrentSnapshotsDoNotBlockWorkers(t *testing.T) {
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) { return response(r, 200, audio(2048)), nil }, nil, nil, nil, nil)
	_, slowUnsub := m.Updates()
	defer slowUnsub()
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 50 {
				ch, cancel := m.Updates()
				snapshot := <-ch
				for i := range snapshot {
					snapshot[i].State = "mutated"
					snapshot[i].Track.Qualities[0] = "mutated"
				}
				m.List()
				cancel()
			}
		}()
	}
	job := createJob(t, m, "concurrent")
	waitJob(t, m, job.ID, "completed")
	wg.Wait()
	if m.List()[0].Track.Qualities[0] != "standard" {
		t.Fatal("snapshot mutated manager")
	}
}
func TestConcurrencyNeverExceedsConfiguredLimit(t *testing.T) {
	var active, maxActive atomic.Int32
	m := testManager(t, t.TempDir(), func(r *http.Request) (*http.Response, error) {
		n := active.Add(1)
		for old := maxActive.Load(); n > old && !maxActive.CompareAndSwap(old, n); old = maxActive.Load() {
		}
		defer active.Add(-1)
		time.Sleep(10 * time.Millisecond)
		return response(r, 200, audio(1024)), nil
	}, nil, nil, nil, nil)
	if err := m.Configure(m.root, 2); err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, id := range []string{"a", "b", "c", "d", "e", "f"} {
		ids = append(ids, createJob(t, m, id).ID)
	}
	for _, id := range ids {
		waitJob(t, m, id, "completed")
	}
	if maxActive.Load() != 2 {
		t.Fatalf("bad concurrency %d", maxActive.Load())
	}
}

func TestCompletedActionRejectedEvenDuringWorkerCleanup(t *testing.T) {
	m, err := New("", 1, fixedResolver, func(model.DownloadJob) error { return nil }, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	m.mu.Lock()
	m.jobs["done"] = &entry{job: model.DownloadJob{ID: "done", State: "completed"}, running: true}
	m.mu.Unlock()
	// 此测试只模拟 finalize commit 后、worker 尚未置 running=false 的窗口。
	defer func() { m.mu.Lock(); delete(m.jobs, "done"); m.mu.Unlock() }()
	for _, action := range []string{"pause", "cancel", "retry", "resume"} {
		if _, err := m.Action("done", action); !errors.Is(err, errAction) {
			t.Fatalf("completed %s accepted", action)
		}
	}
}
func TestRecoveryPersisterFailureIsSanitized(t *testing.T) {
	root := t.TempDir()
	job := seedPartial(t, root, audio(1024), 2048)
	m, err := New(root, 1, fixedResolver, func(model.DownloadJob) error { return errors.New("token=RECOVERY-SECRET") }, []model.DownloadJob{job})
	if err == nil {
		m.Close()
		t.Fatal("accepted failed recovery commit")
	}
	if !errors.Is(err, errPersist) || strings.Contains(err.Error(), "SECRET") {
		t.Fatal("unsanitized recovery persistence error")
	}
}

func TestActivePausePersistenceFailureIsNotAcknowledged(t *testing.T) {
	root := t.TempDir()
	blocked := make(chan struct{})
	store := &recordingStore{}
	m := testManager(t, root, func(r *http.Request) (*http.Response, error) { return blockingResponse(r, audio(8192), blocked), nil }, nil, store, nil, nil)
	job := createJob(t, m, "pause-failure")
	select {
	case <-blocked:
	case <-time.After(3 * time.Second):
		t.Fatal("download did not block")
	}
	store.mu.Lock()
	store.reject = func(j model.DownloadJob) bool { return j.State == "paused" }
	store.mu.Unlock()
	if _, err := m.Action(job.ID, "pause"); !errors.Is(err, errPersist) {
		t.Fatalf("false successful pause acknowledgement: %v", err)
	}
	failed := waitJob(t, m, job.ID, "failed")
	if failed.Error != errPersist.Error() || failed.BytesDone != 4096 {
		t.Fatalf("bad pause failure %+v", failed)
	}
	if _, err := os.Stat(filepath.Join(root, "Singles", partName(job.ID))); err != nil {
		t.Fatal("pause failure removed part")
	}
}

func TestRecoveredDescendingInputKeepsNewestFirst(t *testing.T) {
	initial := []model.DownloadJob{
		{ID: "newest", Track: testTrack("newest"), Quality: "standard", State: "downloading", CreatedAt: "2026-09-07T12:00:00Z"},
		{ID: "middle", Track: testTrack("middle"), Quality: "standard", State: "paused", CreatedAt: "2026-09-07T11:00:00Z"},
		{ID: "oldest", Track: testTrack("oldest"), Quality: "standard", State: "failed", CreatedAt: "2026-09-07T10:00:00Z"},
	}
	for range 2 { // 把上次 List 的 DESC 顺序再交给 New，模拟连续重启。
		m, err := New("", 1, fixedResolver, func(model.DownloadJob) error { return nil }, initial)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(m.Close)
		list := m.List()
		if len(list) != 3 {
			t.Fatal("recovery lost jobs")
		}
		for i, id := range []string{"newest", "middle", "oldest"} {
			if list[i].ID != id || list[i].State != "paused" {
				t.Fatalf("recovery reversed cards: %+v", list)
			}
		}
		m.mu.Lock()
		order := append([]string(nil), m.order...)
		m.mu.Unlock()
		if strings.Join(order, ",") != "oldest,middle,newest" {
			t.Fatalf("internal order is not ascending: %v", order)
		}
		updates, unsubscribe := m.Updates()
		snapshot := <-updates
		unsubscribe()
		for i := range list {
			if snapshot[i].ID != list[i].ID {
				t.Fatal("SSE snapshot and List order differ")
			}
		}
		m.Close()
		initial = list
	}
}

func TestRecoveredOrderUsesTimestampAndStableIDTieBreak(t *testing.T) {
	// a/b/d 为同一时刻的不同 RFC3339 表示；c 晚 100ms，不能按字符串误排。
	initial := []model.DownloadJob{
		{ID: "a", CreatedAt: "2026-09-07T10:00:00Z"},
		{ID: "c", CreatedAt: "2026-09-07T10:00:00.1Z"},
		{ID: "d", CreatedAt: "2026-09-07T18:00:00+08:00"},
		{ID: "b", CreatedAt: "2026-09-07T10:00:00.000Z"},
	}
	for range 2 {
		m, err := New("", 1, fixedResolver, func(model.DownloadJob) error { return nil }, initial)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(m.Close)
		list := m.List()
		var ids []string
		for _, job := range list {
			ids = append(ids, job.ID)
		}
		if strings.Join(ids, ",") != "c,d,b,a" {
			t.Fatalf("unstable time/ID ordering: %v", ids)
		}
		m.Close()
		initial = list
	}
}

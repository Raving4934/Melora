package lxsource_test

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"melora/internal/lxruntime"
	"melora/internal/lxsource"
)

type sourceResult struct {
	source  lxsource.Source
	created bool
	err     error
}

func awaitSignal(t *testing.T, signal <-chan struct{}, label string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %s", label)
	}
}

func awaitSource(t *testing.T, result <-chan sourceResult) sourceResult {
	t.Helper()
	select {
	case got := <-result:
		return got
	case <-time.After(5 * time.Second):
		t.Fatal("manager operation did not finish")
		return sourceResult{}
	}
}

func TestConcurrentCheckRejectsDeleteAndConfigureAndCoalescesInspection(t *testing.T) {
	manager, runner, dir := newManager(t)
	const code = "// blocked recheck"
	source := importSource(t, manager, code, "original.example.test")
	before := manager.List()
	entered := make(chan struct{})
	release := make(chan struct{})
	finished := make(chan struct{})
	result := make(chan sourceResult, 1)
	ctx, cancel := context.WithCancel(t.Context())
	var enteredOnce, releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	runner.setInspect(func(ctx context.Context, inspected string, _ lxruntime.Options) (lxruntime.Descriptor, error) {
		if inspected != code {
			return readyDescriptor(), nil
		}
		enteredOnce.Do(func() { close(entered) })
		select {
		case <-release:
			return readyDescriptor(), nil
		case <-ctx.Done():
			return lxruntime.Descriptor{}, ctx.Err()
		}
	})
	// 即使断言提前终止，也先收回检查 goroutine，再关闭 Manager 或清理目录。
	t.Cleanup(func() {
		cancel()
		unblock()
		awaitSignal(t, finished, "blocked check cleanup")
	})
	go func() {
		defer close(finished)
		checked, err := manager.Check(ctx, source.ID)
		result <- sourceResult{source: checked, err: err}
	}()
	awaitSignal(t, entered, "Runner.Inspect entry")

	// 在另一个 goroutine 中验证冲突及时返回，避免回归时整个测试永久死锁。
	conflicts := make(chan error, 1)
	go func() {
		if warning, err := manager.Delete(source.ID); err == nil || !strings.Contains(err.Error(), "检查") || warning != "" {
			conflicts <- fmt.Errorf("Delete while checking: warning=%q err=%v", warning, err)
			return
		}
		if _, err := manager.Configure(source.ID, []string{"changed.example.test"}); err == nil || !strings.Contains(err.Error(), "检查") {
			conflicts <- fmt.Errorf("Configure while checking: %v", err)
			return
		}
		duplicate, err := manager.Check(ctx, source.ID)
		if err != nil || duplicate.ID != source.ID {
			conflicts <- fmt.Errorf("duplicate Check: source=%+v err=%v", duplicate, err)
			return
		}
		if !reflect.DeepEqual(manager.List(), before) {
			conflicts <- errors.New("conflicting operation changed state")
			return
		}
		conflicts <- nil
	}()
	select {
	case err := <-conflicts:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("operations blocked on Runner instead of returning check conflict")
	}
	inspections, _ := runner.calls()
	if len(inspections) != 2 {
		t.Fatalf("concurrent Check duplicated Runner execution: %d calls", len(inspections))
	}
	if _, err := os.Stat(filepath.Join(dir, source.ID+".js")); err != nil {
		t.Fatalf("conflicting delete removed script: %v", err)
	}

	// 同一个 Runner 的其他源不能被这个慢检查全局阻塞。
	otherResult := make(chan sourceResult, 1)
	go func() {
		item, created, err := manager.Import(ctx, "other.js", []byte("// unrelated source"), nil)
		otherResult <- sourceResult{source: item, created: created, err: err}
	}()
	other := awaitSource(t, otherResult)
	if other.err != nil || !other.created || other.source.Status != "ready" {
		t.Fatalf("unrelated import failed during check: %+v", other)
	}
	unblock()
	checked := awaitSource(t, result)
	if checked.err != nil || checked.source.Status != "ready" || !reflect.DeepEqual(checked.source.AllowHTTPHosts, []string{"original.example.test"}) {
		t.Fatalf("check completion: %+v", checked)
	}
	awaitSignal(t, finished, "check completion")
	if warning, err := manager.Delete(source.ID); err != nil || warning != "" {
		t.Fatalf("checking flag not released: warning=%q err=%v", warning, err)
	}
	if got := manager.List(); len(got.Items) != 1 || got.Items[0].ID != other.source.ID {
		t.Fatalf("delete damaged unrelated source: %+v", got)
	}
}

func TestCancelledCheckReleasesConflictAndCanRecover(t *testing.T) {
	manager, runner, _ := newManager(t)
	source := importSource(t, manager, "// cancelled check")
	ctx, cancel := context.WithCancel(t.Context())
	entered := make(chan struct{})
	finished := make(chan struct{})
	result := make(chan sourceResult, 1)
	runner.setInspect(func(ctx context.Context, _ string, _ lxruntime.Options) (lxruntime.Descriptor, error) {
		close(entered)
		<-ctx.Done()
		return lxruntime.Descriptor{}, ctx.Err()
	})
	t.Cleanup(func() {
		cancel()
		awaitSignal(t, finished, "cancelled check cleanup")
	})
	go func() {
		defer close(finished)
		checked, err := manager.Check(ctx, source.ID)
		result <- sourceResult{source: checked, err: err}
	}()
	awaitSignal(t, entered, "cancelable Runner")
	cancel()
	got := awaitSource(t, result)
	awaitSignal(t, finished, "cancelled check")
	if got.err != nil || got.source.Status != "error" || got.source.Error != "检查已取消，可重新检查" {
		t.Fatalf("cancelled check: %+v", got)
	}
	if _, ok := manager.Active(); ok {
		t.Fatal("failed recheck kept source callable")
	}
	if _, err := manager.Configure(source.ID, []string{"after-cancel.example.test"}); err != nil {
		t.Fatalf("cancellation leaked checking lock: %v", err)
	}
	runner.setInspect(nil)
	if recovered, err := manager.Check(t.Context(), source.ID); err != nil || recovered.Status != "ready" || recovered.Error != "" {
		t.Fatalf("recovery after cancellation: %+v %v", recovered, err)
	}
}

func TestConcurrentIdenticalImportsDeduplicate(t *testing.T) {
	manager, runner, dir := newManager(t)
	const clients = 16
	start := make(chan struct{})
	results := make(chan sourceResult, clients)
	finished := make(chan struct{})
	var workers sync.WaitGroup
	for i := 0; i < clients; i++ {
		workers.Add(1)
		go func(i int) {
			defer workers.Done()
			<-start
			source, created, err := manager.Import(t.Context(), fmt.Sprintf("copy-%d.js", i), []byte("// concurrent identical source"), nil)
			results <- sourceResult{source: source, created: created, err: err}
		}(i)
	}
	go func() {
		workers.Wait()
		close(finished)
	}()
	close(start)
	t.Cleanup(func() { awaitSignal(t, finished, "concurrent imports cleanup") })
	awaitSignal(t, finished, "concurrent imports")
	createdCount := 0
	id := ""
	for i := 0; i < clients; i++ {
		result := awaitSource(t, results)
		if result.err != nil {
			t.Fatal(result.err)
		}
		if result.created {
			createdCount++
		}
		if id != "" && id != result.source.ID {
			t.Fatal("identical scripts received different IDs")
		}
		id = result.source.ID
	}
	inspections, _ := runner.calls()
	state := manager.List()
	if createdCount != 1 || len(inspections) != 1 || len(state.Items) != 1 || state.Items[0].Status != "ready" || state.ActiveID != id {
		t.Fatalf("concurrent dedup: created=%d inspections=%d state=%+v", createdCount, len(inspections), state)
	}
	reopened := newManagerAt(t, dir, &fakeRunner{})
	if !reflect.DeepEqual(reopened.List(), state) {
		t.Fatal("concurrent import did not persist one consistent record")
	}
}

func TestConcurrentSnapshotsDoNotAliasMutableState(t *testing.T) {
	manager, _, dir := newManager(t)
	source := importSource(t, manager, "// snapshot races", "original.example.test")
	const clients = 8
	results := make(chan error, clients)
	finished := make(chan struct{})
	var workers sync.WaitGroup
	for i := 0; i < clients; i++ {
		workers.Add(1)
		go func(i int) {
			defer workers.Done()
			for j := 0; j < 8; j++ {
				state := manager.List()
				state.Items[0].Platforms["fixture"].Actions[0] = "caller-only"
				state.Items[0].AllowHTTPHosts[0] = "caller-only.example.test"
				active, ok := manager.Active()
				if !ok || active.Platforms["fixture"].Actions[0] != "musicUrl" {
					results <- errors.New("snapshot mutation changed active capabilities")
					return
				}
				if i%2 == 0 {
					if _, err := manager.Configure(source.ID, []string{fmt.Sprintf("worker-%d.example.test", i)}); err != nil {
						results <- err
						return
					}
				} else if err := manager.Select(source.ID); err != nil {
					results <- err
					return
				}
			}
			results <- nil
		}(i)
	}
	go func() {
		workers.Wait()
		close(finished)
	}()
	t.Cleanup(func() { awaitSignal(t, finished, "snapshot stress cleanup") })
	awaitSignal(t, finished, "snapshot stress")
	for i := 0; i < clients; i++ {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	state := manager.List()
	if state.Items[0].Platforms["fixture"].Actions[0] != "musicUrl" || state.Items[0].AllowHTTPHosts[0] == "caller-only.example.test" {
		t.Fatal("caller mutated internal state")
	}
	reopened := newManagerAt(t, dir, &fakeRunner{})
	if !reflect.DeepEqual(reopened.List(), state) {
		t.Fatal("concurrent registry writes left a partial state")
	}
}

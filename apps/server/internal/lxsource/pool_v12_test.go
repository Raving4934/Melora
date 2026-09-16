package lxsource

import (
	"melora/internal/lxruntime"
	"path/filepath"
	"testing"
)

func TestV12DefaultManagerOwnsProcessPool(t *testing.T) {
	manager, err := New(filepath.Join(t.TempDir(), "sources"), nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := manager.runner.(*lxruntime.Pool); !ok {
		t.Fatal("default manager still uses per-call process runner")
	}
	if err := manager.Close(); err != nil {
		t.Fatal(err)
	}
}

type closeV12Runner struct {
	Runner
	closed int
}

func (r *closeV12Runner) Close() error { r.closed++; return nil }
func TestV12ManagerClosesOwnedRunner(t *testing.T) {
	runner := &closeV12Runner{}
	manager, err := New(filepath.Join(t.TempDir(), "sources"), runner)
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Close(); err != nil || runner.closed != 1 {
		t.Fatalf("runner not closed: calls=%d err=%v", runner.closed, err)
	}
}

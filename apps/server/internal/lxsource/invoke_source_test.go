package lxsource_test

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"melora/internal/lxruntime"
	"melora/internal/lxsource"
)

func TestV5InvokeSourceDoesNotSelectAndPreservesCancellation(t *testing.T) {
	m, r, _ := newManager(t)
	first := importSource(t, m, "// first")
	second := importSource(t, m, "// second")
	if err := m.Select(first.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := m.InvokeSource(t.Context(), second.ID, "fixture", "musicUrl", nil); err != nil {
		t.Fatal(err)
	}
	_, calls := r.calls()
	if len(calls) != 1 || calls[0].code != "// second" {
		t.Fatal("wrong source invoked")
	}
	if active, _ := m.Active(); active.ID != first.ID {
		t.Fatal("global active changed")
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := m.InvokeSource(ctx, second.ID, "fixture", "musicUrl", nil); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if _, err := m.InvokeSource(t.Context(), strings.Repeat("f", 24), "fixture", "musicUrl", nil); !errors.Is(err, lxsource.ErrMissing) {
		t.Fatal(err)
	}
}
func TestV5InvokeSourceErrorsKeepTypeButNotSecrets(t *testing.T) {
	m, r, _ := newManager(t)
	s := importSource(t, m, "// source")
	r.invoke = func(context.Context, string, string, string, map[string]any, lxruntime.Options) (json.RawMessage, error) {
		return nil, lxruntime.ErrNetwork
	}
	if _, err := m.InvokeSource(t.Context(), s.ID, "fixture", "musicUrl", nil); !errors.Is(err, lxruntime.ErrNetwork) {
		t.Fatal("lost typed error")
	}
	r.invoke = func(context.Context, string, string, string, map[string]any, lxruntime.Options) (json.RawMessage, error) {
		return nil, errors.New("token=SECRET")
	}
	if _, err := m.InvokeSource(t.Context(), s.ID, "fixture", "musicUrl", nil); err == nil || strings.Contains(err.Error(), "SECRET") {
		t.Fatal("short secret leaked")
	}
}

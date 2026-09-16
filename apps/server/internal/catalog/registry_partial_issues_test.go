package catalog

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"testing"
)

func TestRegistryPartialIssuesRetainEachOriginalCause(t *testing.T) {
	for _, capability := range []string{"charts", "playlists", "search"} {
		t.Run(capability, func(t *testing.T) {
			blocked := errors.New("private upstream URL and token must remain internal")
			timedOut := fmt.Errorf("private timeout detail: %w", context.DeadlineExceeded)
			r := NewRegistry(map[string]Adapter{
				"wy": &registryStub{prefix: "wy"},
				"tx": &registryStub{prefix: "tx", fail: blocked},
				"kw": &registryStub{prefix: "kw", fail: timedOut},
				"kg": &registryStub{prefix: "kg"},
			})
			var count int
			var err error
			switch capability {
			case "charts":
				items, e := r.ChartsFor(t.Context(), "all")
				count, err = len(items), e
			case "playlists":
				items, e := r.PlaylistsFor(t.Context(), "all", "", 1)
				count, err = len(items), e
			case "search":
				items, e := r.SearchFor(t.Context(), "all", "offline fixture", "track", 1)
				count, err = len(items.Tracks), e
			}
			var partial *PartialError
			if count != 2 || !errors.As(err, &partial) {
				t.Fatalf("successful data or partial classification lost: count=%d partial=%v", count, partial != nil)
			}
			if !reflect.DeepEqual(partial.Sources, []string{"tx", "kw"}) {
				t.Fatalf("registration order changed: %v", partial.Sources)
			}
			if len(partial.Causes) != 2 || partial.Causes["tx"] != blocked || partial.Causes["kw"] != timedOut {
				t.Fatal("partial error dropped or replaced a platform's original cause")
			}
			if _, exists := partial.Causes["wy"]; exists {
				t.Fatal("successful platform incorrectly has a cause")
			}
			if !errors.Is(err, ErrUnavailable) || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, blocked) {
				t.Fatal("Causes must not replace the existing Unwrap chain")
			}
		})
	}
}

func TestRegistryPartialIssuesLegacyContract(t *testing.T) {
	partial := &PartialError{Sources: []string{"tx", "kw"}}
	if partial.Causes != nil || partial.Error() != "部分音乐平台暂不可用" || partial.Unwrap() != ErrUnavailable {
		t.Fatal("legacy Sources-only construction changed")
	}
	ids, ok := IsPartial(fmt.Errorf("wrapped partial: %w", partial))
	if !ok || !reflect.DeepEqual(ids, []string{"kw", "tx"}) {
		t.Fatal("wrapped IsPartial behavior changed", ids, ok)
	}
	ids[0] = "mutated copy"
	if !reflect.DeepEqual(partial.Sources, []string{"tx", "kw"}) {
		t.Fatal("IsPartial mutated or exposed the original Sources slice")
	}
	for _, err := range []error{nil, ErrUnavailable, ErrInput, context.Canceled} {
		if ids, ok := IsPartial(err); ok || ids != nil {
			t.Fatal("non-partial error became partial")
		}
	}
}

func TestRegistryPartialIssuesSingleSourceAndTotalFailureStayNonPartial(t *testing.T) {
	cause := fmt.Errorf("private wrapped failure: %w", ErrUnsupported)
	for _, source := range []string{"tx", "all"} {
		t.Run("single-"+source, func(t *testing.T) {
			r := NewRegistry(map[string]Adapter{"tx": &registryStub{prefix: "tx", fail: cause}})
			items, err := r.ChartsFor(t.Context(), source)
			if len(items) != 0 || err != cause {
				t.Fatal("single-source error identity changed")
			}
			if _, ok := IsPartial(err); ok {
				t.Fatal("single source must not produce a partial error")
			}
		})
	}
	r := NewRegistry(map[string]Adapter{
		"wy": &registryStub{prefix: "wy", fail: cause},
		"tx": &registryStub{prefix: "tx", fail: context.DeadlineExceeded},
	})
	items, err := r.ChartsFor(t.Context(), "all")
	if len(items) != 0 || err != ErrUnavailable {
		t.Fatal("all-failed behavior changed")
	}
	if _, ok := IsPartial(err); ok {
		t.Fatal("all-failed response must not be partial success")
	}
	if _, err := r.ChartsFor(t.Context(), "unknown"); err != ErrInput {
		t.Fatal("invalid-source behavior changed")
	}
}

func TestRegistryPartialIssuesCauseMapsAreRequestLocal(t *testing.T) {
	cause := errors.New("original")
	r := NewRegistry(map[string]Adapter{
		"wy": &registryStub{prefix: "wy"},
		"tx": &registryStub{prefix: "tx", fail: cause},
	})
	_, firstErr := r.ChartsFor(t.Context(), "all")
	var first *PartialError
	if !errors.As(firstErr, &first) || first.Causes["tx"] != cause {
		t.Fatal("first request did not retain its cause")
	}
	first.Causes["tx"] = errors.New("caller changed its result")
	_, secondErr := r.ChartsFor(t.Context(), "all")
	var second *PartialError
	if !errors.As(secondErr, &second) || second.Causes["tx"] != cause {
		t.Fatal("a caller-mutated cause map contaminated another request")
	}
}

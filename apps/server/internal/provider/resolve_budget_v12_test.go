package provider

import (
	"context"
	"encoding/json"
	"melora/internal/lxruntime"
	"testing"
	"time"
)

// 真实源先取地址再HEAD的总耗时可超过5秒；只给首轮额外余量，不能同时放大总预算/重试次数。
func TestV12PreferredSourceBudgetAllowsBoundedMultiRequestResolution(t *testing.T) {
	l, r, _, _ := liveFixture(t, 1, nil)
	r.invoke = func(ctx context.Context, _ string, _ map[string]any) (json.RawMessage, error) {
		deadline, ok := ctx.Deadline()
		left := time.Until(deadline)
		if !ok || left < 7*time.Second || left > 8*time.Second {
			t.Errorf("preferred source budget=%v want 7..8s", left)
			return nil, lxruntime.ErrTimeout
		}
		return json.RawMessage(`"https://8.8.8.8/safe-fixture.mp3"`), nil
	}
	if _, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true, PageScheme: "http"}); err != nil {
		t.Fatal("preferred fixture failed")
	}
}
func TestV12FallbackBudgetsAndOuterCancellationRemainBounded(t *testing.T) {
	l, r, _, _ := liveFixture(t, 5, nil)
	calls := 0
	r.invoke = func(ctx context.Context, _ string, _ map[string]any) (json.RawMessage, error) {
		calls++
		deadline, ok := ctx.Deadline()
		limit := 5 * time.Second
		if calls == 1 {
			limit = 8 * time.Second
		}
		if !ok || time.Until(deadline) > limit {
			t.Error("per-call deadline exceeded")
		}
		return nil, lxruntime.ErrScript
	}
	if _, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{AutoSwitch: true}); err == nil || calls != 3 {
		t.Fatalf("attempt count=%d err=%v", calls, err)
	}
	l2, r2, _, _ := liveFixture(t, 1, nil)
	r2.invoke = func(ctx context.Context, _ string, _ map[string]any) (json.RawMessage, error) {
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > 50*time.Millisecond {
			t.Error("ignored caller deadline")
		}
		return nil, lxruntime.ErrScript
	}
	ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
	defer cancel()
	_, _ = l2.PlayInfoWithOptions(ctx, fixtureTrack(), "128k", ResolveOptions{})
}

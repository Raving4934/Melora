package provider

import (
	"context"
	"melora/internal/catalog"
	"melora/internal/model"
	"testing"
	"time"
)

// 主线确认诊断头按“去重音源数”：同一脚本跨平台调用两次仍只算一个音源。
// 只用既有真实 lxsource.Manager 和离线 Runner/目录替身，不访问媒体或公网。
func TestV7ResolveAttemptLogCountsDistinctSourcesAcrossPlatforms(t *testing.T) {
	l, runner, _ := crossFixture(t, false, false, "")
	info, err := l.PlayInfoWithOptions(t.Context(), model.Track{ID: "wy:123", ProviderID: "wy"}, "128k", ResolveOptions{AutoSwitch: true, PageScheme: "https"})
	if err != nil {
		t.Fatal("fixture did not resolve")
	}
	if len(runner.calls) != 2 {
		t.Fatalf("fixture invocation count=%d want=2", len(runner.calls))
	}
	if len(info.AttemptedSources) != 1 {
		t.Fatalf("distinct-source count=%d want=1 for two calls of one script", len(info.AttemptedSources))
	}
}

// 固定现有预算契约；API 集成另行检验首轮上下文 <=8 秒、后续 <=5 秒、最多3次调用和取消。
func TestV7ResolveBudgetsRemainBounded(t *testing.T) {
	if resolveAttemptLimit != 3 || resolveBudget != 14*time.Second || resolveAttemptBudget != 5*time.Second || resolvePreferredBudget != 8*time.Second {
		t.Fatal("resolve budget contract changed")
	}
	if crossSearchLimit != 2 || crossSearchBudget != 2*time.Second || crossMetadataBudget != 2*time.Second {
		t.Fatal("cross-platform budget contract changed")
	}
}

type v7DeadlineCatalog struct {
	*liveFixtureCatalog
	metadataBudget time.Duration
	t              *testing.T
}

func (a *v7DeadlineCatalog) MusicInfo(ctx context.Context, id string) (map[string]any, error) {
	deadline, ok := ctx.Deadline()
	if !ok || time.Until(deadline) > a.metadataBudget {
		a.t.Error("metadata context exceeded resolve budget")
	}
	return a.liveFixtureCatalog.MusicInfo(ctx, id)
}

// 不等待14秒：直接观察真实解析器传到目录适配器的 deadline；取消由 API 集成覆盖。
func TestV7ResolveContextsCarryActualTotalAndMetadataBudgets(t *testing.T) {
	for _, auto := range []bool{false, true} {
		l, _, original, _ := liveFixture(t, 1, nil)
		budget := 14 * time.Second
		if auto {
			budget = 3 * time.Second
		}
		l.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{"wy": &v7DeadlineCatalog{liveFixtureCatalog: original, metadataBudget: budget, t: t}})
		if _, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "128k", ResolveOptions{AutoSwitch: auto, PageScheme: "https"}); err != nil {
			t.Fatal("budget fixture did not resolve")
		}
		if original.calls.Load() != 1 {
			t.Fatal("budget observation did not reach metadata adapter")
		}
	}
}

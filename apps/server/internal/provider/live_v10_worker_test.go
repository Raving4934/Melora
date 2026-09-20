//go:build !race

package provider

// ProcessRunner 复用当前二进制；race shadow memory 与真实 worker 的硬资源上限冲突。
// 本用例用普通二进制验证真实隔离进程；race 则覆盖 live_v10_source_test.go 的
// Manager/provider 切源集成，以及 lxruntime 包的真实非插桩 worker + 插桩父进程。

import (
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"melora/internal/catalog"
	"melora/internal/lxsource"
)

func TestV10SyntheticWorkerHTTPMediaFallbackIntegration(t *testing.T) {
	// 真 Manager + 隔离运行器 + provider，但只有此处定义的离线合成脚本。
	// 返回字面量 URL 仅用于校验；没有元数据 HTTP、DNS 查询或媒体下载。
	manager, err := lxsource.New(filepath.Join(t.TempDir(), "sources"), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = manager.Close() })
	scripts := []string{
		`return 'http://8.8.8.8/never-fetched.mp3';`,
		`throw new Error('SECRET synthetic failure');`,
		`return {url:'https://8.8.8.8/never-fetched.mp3'};`,
	}
	sources := []lxsource.Source{}
	for _, body := range scripts {
		code := `lx.on(lx.EVENT_NAMES.request,async ({info})=>{if(info.type!=='320k')throw new Error('quality changed');` + body + `});lx.send(lx.EVENT_NAMES.inited,{sources:{wy:{name:'synthetic',type:'music',actions:['musicUrl'],qualitys:['320k']}}});`
		source, _, err := manager.Import(t.Context(), "synthetic.js", []byte(code), nil)
		if err != nil || source.Status != "ready" {
			t.Fatalf("synthetic initialization failed: %+v %v", source, err)
		}
		sources = append(sources, source)
	}
	l := &Live{Sources: manager, Catalog: catalog.NewRegistry(map[string]catalog.Adapter{"wy": &liveFixtureCatalog{}})}
	before := manager.List()
	out, err := l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{AutoSwitch: true})
	if err != nil || out.SourceID != sources[2].ID || !reflect.DeepEqual(out.AttemptedSources, []string{sources[0].ID, sources[1].ID, sources[2].ID}) || strings.HasPrefix(out.URL, "http:") {
		t.Fatalf("real worker fallback failed: %+v %v", out, err)
	}
	if !reflect.DeepEqual(before, manager.List()) {
		t.Fatal("real worker invocation changed source selection")
	}
	// 同一个初始化成功的 HTTP 源在禁止切源时仍真实失败，ready 绝不是播放成功。
	out, err = l.PlayInfoWithOptions(t.Context(), fixtureTrack(), "320k", ResolveOptions{})
	if !errors.Is(err, ErrMixedContent) || out.URL != "" || len(out.AttemptedSources) != 1 {
		t.Fatalf("initialization mistaken for availability: %+v %v", out, err)
	}
}

package lxsource_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"melora/internal/lxruntime"
	"melora/internal/lxsource"
)

type inspectCall struct {
	code  string
	hosts []string
}

type invokeCall struct {
	code, platform, action string
	info                   map[string]any
	hosts                  []string
}

// 所有测试均注入此 Runner；不会启动 JS 子进程、解析 DNS 或发送 HTTP。
// 回调在锁外执行，便于用通道确定性地制造正在检查的状态。
type fakeRunner struct {
	mu          sync.Mutex
	inspections []inspectCall
	invocations []invokeCall
	inspect     func(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error)
	invoke      func(context.Context, string, string, string, map[string]any, lxruntime.Options) (json.RawMessage, error)
}

func readyDescriptor() lxruntime.Descriptor {
	return lxruntime.Descriptor{Status: true, Sources: map[string]lxruntime.Source{
		"fixture": {Name: "离线平台", Type: "music", Actions: []string{"musicUrl", "lyric"}, Qualitys: []string{"128k", "320k"}},
	}}
}

func (r *fakeRunner) Inspect(ctx context.Context, code string, opts lxruntime.Options) (lxruntime.Descriptor, error) {
	r.mu.Lock()
	r.inspections = append(r.inspections, inspectCall{code: code, hosts: append([]string(nil), opts.AllowHTTPHosts...)})
	fn := r.inspect
	r.mu.Unlock()
	if fn != nil {
		return fn(ctx, code, opts)
	}
	return readyDescriptor(), nil
}

func (r *fakeRunner) Invoke(ctx context.Context, code, platform, action string, info map[string]any, opts lxruntime.Options) (json.RawMessage, error) {
	encoded, err := json.Marshal(info)
	if err != nil {
		return nil, err
	}
	var copied map[string]any
	if err := json.Unmarshal(encoded, &copied); err != nil {
		return nil, err
	}
	r.mu.Lock()
	r.invocations = append(r.invocations, invokeCall{code: code, platform: platform, action: action, info: copied, hosts: append([]string(nil), opts.AllowHTTPHosts...)})
	fn := r.invoke
	r.mu.Unlock()
	if fn != nil {
		return fn(ctx, code, platform, action, info, opts)
	}
	return json.RawMessage(`{"url":"https://media.example.test/song.ogg"}`), nil
}

func (r *fakeRunner) setInspect(fn func(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error)) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.inspect = fn
}

func (r *fakeRunner) calls() ([]inspectCall, []invokeCall) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]inspectCall(nil), r.inspections...), append([]invokeCall(nil), r.invocations...)
}

func newManagerAt(t *testing.T, dir string, runner *fakeRunner) *lxsource.Manager {
	t.Helper()
	manager, err := lxsource.New(dir, runner)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { _ = manager.Close() })
	return manager
}

func newManager(t *testing.T) (*lxsource.Manager, *fakeRunner, string) {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "sources")
	runner := &fakeRunner{}
	return newManagerAt(t, dir, runner), runner, dir
}

func importSource(t *testing.T, manager *lxsource.Manager, code string, hosts ...string) lxsource.Source {
	t.Helper()
	source, created, err := manager.Import(t.Context(), "fixture.js", []byte(code), hosts)
	if err != nil || !created || source.Status != "ready" {
		t.Fatalf("Import: source=%+v created=%v err=%v", source, created, err)
	}
	return source
}

func requireError(t *testing.T, got, want error) {
	t.Helper()
	if !errors.Is(got, want) {
		t.Fatalf("error = %v, want %v", got, want)
	}
}

func readFile(t *testing.T, path string) []byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile(%s): %v", path, err)
	}
	return data
}

func writeFile(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatalf("WriteFile(%s): %v", path, err)
	}
}

func requireMode(t *testing.T, path string, mode os.FileMode) {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != mode {
		t.Fatalf("%s mode = %04o, want %04o", path, info.Mode().Perm(), mode)
	}
}

func TestImportMetadataDedupAndPrivateFiles(t *testing.T) {
	manager, runner, dir := newManager(t)
	code := "/**\n * @name  自建音源\n * @author  作者\n * @version  2.3.4\n * @description  仅用于离线测试\n */\nconst fixture = 1;\n"
	raw := append([]byte{0xef, 0xbb, 0xbf}, []byte(code)...)
	source, created, err := manager.Import(t.Context(), "../../nested/source.JS", raw, []string{" Z.Example.Test ", "a.example.test", "z.example.test", ""})
	if err != nil || !created {
		t.Fatalf("Import: created=%v err=%v", created, err)
	}
	sum := sha256.Sum256([]byte(code))
	hash := hex.EncodeToString(sum[:])
	if source.ID != hash[:24] || source.SHA256 != hash || source.Filename != "source.JS" {
		t.Fatalf("identity/filename: %+v", source)
	}
	if source.Name != "自建音源" || source.Author != "作者" || source.Version != "2.3.4" || source.Description != "仅用于离线测试" {
		t.Fatalf("metadata: %+v", source)
	}
	if source.Status != "ready" || source.Error != "" || !reflect.DeepEqual(source.Platforms, readyDescriptor().Sources) {
		t.Fatalf("descriptor not applied: %+v", source)
	}
	for _, stamp := range []string{source.ImportedAt, source.CheckedAt} {
		if _, err := time.Parse(time.RFC3339, stamp); err != nil {
			t.Fatalf("invalid timestamp %q: %v", stamp, err)
		}
	}
	wantHosts := []string{"a.example.test", "z.example.test"}
	if !reflect.DeepEqual(source.AllowHTTPHosts, wantHosts) {
		t.Fatalf("hosts = %v", source.AllowHTTPHosts)
	}
	if got := string(readFile(t, filepath.Join(dir, source.ID+".js"))); got != code {
		t.Fatal("saved script differs from BOM-normalized input")
	}
	requireMode(t, dir, 0700)
	requireMode(t, filepath.Join(dir, source.ID+".js"), 0600)
	requireMode(t, filepath.Join(dir, "registry.json"), 0600)
	inspections, _ := runner.calls()
	if len(inspections) != 1 || inspections[0].code != code || !reflect.DeepEqual(inspections[0].hosts, wantHosts) {
		t.Fatalf("Inspect calls: %+v", inspections)
	}
	// 文件名和白名单不同也不能覆盖同一代码的已有记录，BOM 不参与去重摘要。
	duplicate, created, err := manager.Import(t.Context(), "different.js", []byte(code), []string{"other.example.test"})
	if err != nil || created || !reflect.DeepEqual(duplicate, source) {
		t.Fatalf("dedup: source=%+v created=%v err=%v", duplicate, created, err)
	}
	inspections, _ = runner.calls()
	if len(inspections) != 1 || len(manager.List().Items) != 1 {
		t.Fatal("duplicate import executed Runner or inserted another record")
	}
	if active, ok := manager.Active(); !ok || active.ID != source.ID {
		t.Fatalf("first ready source was not selected: %+v %v", active, ok)
	}
}

func TestImportMetadataSanitizationAndFallback(t *testing.T) {
	t.Run("rune_limits_and_controls", func(t *testing.T) {
		manager, _, _ := newManager(t)
		code := fmt.Sprintf("/**\n * @name %s\n * @author %s\n * @version %s\n * @description %s\n */", strings.Repeat("名", 90), strings.Repeat("作", 90), strings.Repeat("版", 50), strings.Repeat("述", 250))
		source := importSource(t, manager, code)
		for label, field := range map[string]struct {
			value string
			limit int
		}{"name": {source.Name, 80}, "author": {source.Author, 80}, "version": {source.Version, 40}, "description": {source.Description, 240}} {
			if !utf8.ValidString(field.value) || utf8.RuneCountInString(field.value) != field.limit {
				t.Errorf("%s was not truncated by rune: %q", label, field.value)
			}
		}
		cleaned := importSource(t, manager, "/**\n * @name A\aB\tC\n */")
		if cleaned.Name != "A B C" {
			t.Fatalf("control characters not sanitized: %q", cleaned.Name)
		}
	})
	t.Run("filename_fallback", func(t *testing.T) {
		manager, _, _ := newManager(t)
		source, _, err := manager.Import(t.Context(), "../nested/fall\tback.js", []byte("// no header"), nil)
		if err != nil || source.Name != "fall back.js" || source.Filename != "fall back.js" {
			t.Fatalf("fallback: %+v %v", source, err)
		}
	})
	t.Run("headers_after_prefix_are_ignored", func(t *testing.T) {
		manager, _, _ := newManager(t)
		source := importSource(t, manager, strings.Repeat(" ", 8192)+"\n * @name late header\n")
		if source.Name != "fixture.js" {
			t.Fatalf("metadata scanned beyond prefix: %q", source.Name)
		}
	})
}

func TestImportInputBoundaries(t *testing.T) {
	for _, tc := range []struct {
		name, filename string
		data           []byte
	}{
		{"empty", "source.js", nil},
		{"bom_only", "source.js", []byte{0xef, 0xbb, 0xbf}},
		{"too_large", "source.js", bytes.Repeat([]byte("a"), lxsource.MaxScriptBytes+1)},
		{"invalid_utf8", "source.js", []byte{0xff, 0xfe}},
		{"truncated_utf8", "source.js", []byte{'/', '/', 0xe4, 0xb8}},
		{"nul", "source.js", []byte("// before\x00after")},
		{"wrong_extension", "source.txt", []byte("// fixture")},
		{"double_extension", "source.js.txt", []byte("// fixture")},
		{"missing_extension", "source", []byte("// fixture")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			manager, runner, dir := newManager(t)
			_, created, err := manager.Import(t.Context(), tc.filename, tc.data, nil)
			requireError(t, err, lxsource.ErrInvalidScript)
			if created || len(manager.List().Items) != 0 {
				t.Fatal("invalid input changed registry")
			}
			calls, _ := runner.calls()
			entries, err := os.ReadDir(dir)
			if err != nil || len(calls) != 0 || len(entries) != 0 {
				t.Fatalf("invalid input caused side effects: calls=%d entries=%v err=%v", len(calls), entries, err)
			}
		})
	}
	for _, bom := range []bool{false, true} {
		t.Run(fmt.Sprintf("exact_limit_bom_%v", bom), func(t *testing.T) {
			manager, _, _ := newManager(t)
			data := bytes.Repeat([]byte("a"), lxsource.MaxScriptBytes)
			if bom {
				data = append([]byte{0xef, 0xbb, 0xbf}, data...)
			}
			_, created, err := manager.Import(t.Context(), "source.JS", data, nil)
			if err != nil || !created {
				t.Fatalf("exact limit rejected: created=%v err=%v", created, err)
			}
		})
	}
}

func TestImportSourceLimitAllowsDedup(t *testing.T) {
	manager, runner, _ := newManager(t)
	var first lxsource.Source
	for i := 0; i < 20; i++ {
		source := importSource(t, manager, fmt.Sprintf("// source %d", i))
		if i == 0 {
			first = source
		}
	}
	_, created, err := manager.Import(t.Context(), "overflow.js", []byte("// overflow"), nil)
	requireError(t, err, lxsource.ErrLimit)
	if created {
		t.Fatal("over-limit source reported as created")
	}
	duplicate, created, err := manager.Import(t.Context(), "renamed.js", []byte("// source 0"), nil)
	if err != nil || created || duplicate.ID != first.ID {
		t.Fatalf("dedup at limit: %+v %v %v", duplicate, created, err)
	}
	calls, _ := runner.calls()
	if len(calls) != 20 || len(manager.List().Items) != 20 {
		t.Fatalf("limit changed count: inspect=%d items=%d", len(calls), len(manager.List().Items))
	}
}

func TestCheckErrorStatesAndRecovery(t *testing.T) {
	for _, tc := range []struct {
		name string
		desc lxruntime.Descriptor
		err  error
		want string
	}{
		{"script_error", lxruntime.Descriptor{}, lxruntime.ErrScript, lxruntime.ErrScript.Error()},
		{"cancelled", lxruntime.Descriptor{}, fmt.Errorf("wrapped: %w", context.Canceled), "检查已取消，可重新检查"},
		{"deadline", lxruntime.Descriptor{}, fmt.Errorf("wrapped: %w", context.DeadlineExceeded), "音源初始化超时"},
		{"url_redacted", lxruntime.Descriptor{}, errors.New("failed https://example.test/?token=secret"), "音源运行失败，请检查接口兼容性或远端服务状态"},
		{"multiline_redacted", lxruntime.Descriptor{}, errors.New("first\nsecret"), "音源运行失败，请检查接口兼容性或远端服务状态"},
		{"long_error_redacted", lxruntime.Descriptor{}, errors.New(strings.Repeat("x", 181)), "音源运行失败，请检查接口兼容性或远端服务状态"},
		{"status_false", lxruntime.Descriptor{Sources: readyDescriptor().Sources}, nil, "脚本未声明可用平台，请检查LX接口兼容性"},
		{"no_platforms", lxruntime.Descriptor{Status: true}, nil, "脚本未声明可用平台，请检查LX接口兼容性"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			manager, runner, _ := newManager(t)
			runner.setInspect(func(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error) { return tc.desc, tc.err })
			source, created, err := manager.Import(t.Context(), "bad.js", []byte("// error fixture"), nil)
			if err != nil || !created || source.Status != "error" || source.Error != tc.want || source.CheckedAt == "" {
				t.Fatalf("error state was not persisted: %+v created=%v err=%v", source, created, err)
			}
			if err := manager.Select(source.ID); err == nil {
				t.Fatal("selected an unready source")
			}
			if _, ok := manager.Active(); ok {
				t.Fatal("error source became active")
			}
			runner.setInspect(nil)
			recovered, err := manager.Check(t.Context(), source.ID)
			if err != nil || recovered.Status != "ready" || recovered.Error != "" {
				t.Fatalf("recovery failed: %+v %v", recovered, err)
			}
			if active, ok := manager.Active(); !ok || active.ID != source.ID {
				t.Fatalf("recovered first source not active: %+v %v", active, ok)
			}
		})
	}
}

func TestSelectionDisableDeleteAndUnsupportedInvocation(t *testing.T) {
	manager, runner, dir := newManager(t)
	_, err := manager.Invoke(t.Context(), "fixture", "musicUrl", nil)
	requireError(t, err, lxsource.ErrNoActive)
	first := importSource(t, manager, "// first")
	second := importSource(t, manager, "// second")
	if active, ok := manager.Active(); !ok || active.ID != first.ID {
		t.Fatal("later import silently changed the selected source")
	}
	if err := manager.Select(second.ID); err != nil {
		t.Fatal(err)
	}
	for _, request := range [][2]string{{"unknown", "musicUrl"}, {"fixture", "unsupported"}} {
		_, err := manager.Invoke(t.Context(), request[0], request[1], nil)
		requireError(t, err, lxsource.ErrUnsupported)
	}
	_, calls := runner.calls()
	if len(calls) != 0 {
		t.Fatal("unsupported action reached Runner")
	}
	info := map[string]any{"musicInfo": map[string]any{"id": "offline-track"}, "type": "128k"}
	result, err := manager.Invoke(t.Context(), "fixture", "musicUrl", info)
	if err != nil || string(result) != `{"url":"https://media.example.test/song.ogg"}` {
		t.Fatalf("Invoke result: %s %v", result, err)
	}
	_, calls = runner.calls()
	if len(calls) != 1 || calls[0].code != "// second" || calls[0].platform != "fixture" || calls[0].action != "musicUrl" || !reflect.DeepEqual(calls[0].info, info) {
		t.Fatalf("incorrect runner dispatch: %+v", calls)
	}
	if err := manager.Select(""); err != nil {
		t.Fatal(err)
	}
	if _, ok := manager.Active(); ok || manager.List().ActiveID != "" {
		t.Fatal("disable did not clear selection")
	}
	_, err = manager.Invoke(t.Context(), "fixture", "musicUrl", nil)
	requireError(t, err, lxsource.ErrNoActive)
	if err := manager.Select(second.ID); err != nil {
		t.Fatal(err)
	}
	warning, err := manager.Delete(first.ID)
	if err != nil || warning != "" || manager.List().ActiveID != second.ID {
		t.Fatalf("deleting an inactive source changed selection: %q %v", warning, err)
	}
	warning, err = manager.Delete(second.ID)
	if err != nil || warning != "" || manager.List().ActiveID != "" || len(manager.List().Items) != 0 {
		t.Fatalf("delete active source: %q %v %+v", warning, err, manager.List())
	}
	for _, source := range []lxsource.Source{first, second} {
		if _, err := os.Lstat(filepath.Join(dir, source.ID+".js")); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("deleted script remains: %v", err)
		}
	}
}

func TestMissingSourceOperationsDoNotChangeState(t *testing.T) {
	manager, _, _ := newManager(t)
	before := manager.List()
	for _, id := range []string{"", "missing", "../outside", strings.Repeat("a", 24)} {
		_, err := manager.Check(t.Context(), id)
		requireError(t, err, lxsource.ErrMissing)
		_, err = manager.Configure(id, nil)
		requireError(t, err, lxsource.ErrMissing)
		_, err = manager.Delete(id)
		requireError(t, err, lxsource.ErrMissing)
		if id != "" {
			requireError(t, manager.Select(id), lxsource.ErrMissing)
		}
	}
	if !reflect.DeepEqual(manager.List(), before) {
		t.Fatal("missing source operations changed state")
	}
}

func TestHTTPHostConfigurationPropagatesWithoutChangingScript(t *testing.T) {
	manager, runner, dir := newManager(t)
	source := importSource(t, manager, "// http options", "first.example.test")
	before := readFile(t, filepath.Join(dir, source.ID+".js"))
	hosts := []string{" Z.Example.Test ", "a.example.test", "a.example.test", "", "8.8.8.8"}
	configured, err := manager.Configure(source.ID, hosts)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"8.8.8.8", "a.example.test", "z.example.test"}
	if !reflect.DeepEqual(configured.AllowHTTPHosts, want) || configured.SHA256 != source.SHA256 {
		t.Fatalf("Configure: %+v", configured)
	}
	hosts[0] = "caller-mutated.example.test"
	if _, err := manager.Check(t.Context(), source.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Invoke(t.Context(), "fixture", "musicUrl", nil); err != nil {
		t.Fatal(err)
	}
	inspections, invocations := runner.calls()
	if len(inspections) != 2 || len(invocations) != 1 || !reflect.DeepEqual(inspections[1].hosts, want) || !reflect.DeepEqual(invocations[0].hosts, want) {
		t.Fatalf("HTTP options not propagated: inspect=%+v invoke=%+v", inspections, invocations)
	}
	if !bytes.Equal(before, readFile(t, filepath.Join(dir, source.ID+".js"))) {
		t.Fatal("Configure rewrote script")
	}
	if _, err := manager.Configure(source.ID, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Invoke(t.Context(), "fixture", "lyric", nil); err != nil {
		t.Fatal(err)
	}
	_, invocations = runner.calls()
	if len(invocations[1].hosts) != 0 {
		t.Fatal("cleared HTTP hosts leaked into invocation")
	}
}

func TestHTTPHostsRejectInvalidConfigurationWithoutSideEffects(t *testing.T) {
	invalid := map[string][]string{
		"url": {"https://example.test"}, "path": {"example.test/path"}, "credentials": {"user@example.test"},
		"port": {"example.test:80"}, "wildcard": {"*.example.test"}, "query": {"example.test?token=secret"},
		"fragment": {"example.test#fragment"}, "backslash": {`example.test\path`}, "interior_space": {"exam ple.test"},
		"newline": {"example.test\nother.test"}, "trailing_dot": {"example.test."}, "empty_label": {"example..test"},
		"oversized": {strings.Repeat("a", 254)}, "too_many": make([]string, 11),
	}
	for name, hosts := range invalid {
		t.Run(name, func(t *testing.T) {
			manager, runner, dir := newManager(t)
			source := importSource(t, manager, "// valid hosts", "allowed.example.test")
			before := manager.List()
			registry := readFile(t, filepath.Join(dir, "registry.json"))
			_, err := manager.Configure(source.ID, hosts)
			requireError(t, err, lxsource.ErrHosts)
			_, created, err := manager.Import(t.Context(), "other.js", []byte("// rejected hosts"), hosts)
			requireError(t, err, lxsource.ErrHosts)
			calls, _ := runner.calls()
			if created || len(calls) != 1 || !reflect.DeepEqual(manager.List(), before) || !bytes.Equal(registry, readFile(t, filepath.Join(dir, "registry.json"))) {
				t.Fatal("invalid hosts changed state or executed Runner")
			}
		})
	}
}

// 与真实运行器的公网策略保持一致，不能保存一份注定被 Broker 拒绝的白名单。
func TestHTTPHostsRejectNonPublicTargets(t *testing.T) {
	for _, host := range []string{"127.0.0.1", "10.0.0.1", "169.254.169.254", "0.0.0.0", "localhost", "nas.local", "service.internal"} {
		t.Run(host, func(t *testing.T) {
			manager, runner, _ := newManager(t)
			source := importSource(t, manager, "// public policy", "allowed.example.test")
			before := manager.List()
			if _, err := manager.Configure(source.ID, []string{host}); !errors.Is(err, lxsource.ErrHosts) {
				t.Errorf("Configure(%q) = %v, want ErrHosts before saving incompatible HTTP policy", host, err)
			}
			if _, created, err := manager.Import(t.Context(), "other.js", []byte("// private policy"), []string{host}); !errors.Is(err, lxsource.ErrHosts) || created {
				t.Errorf("Import(%q): created=%v err=%v, want false/ErrHosts", host, created, err)
			}
			calls, _ := runner.calls()
			if !reflect.DeepEqual(manager.List(), before) || len(calls) != 1 {
				t.Error("rejected network policy must not change registry or execute Runner")
			}
		})
	}
}

func TestReturnedSourcesAreDeepCopies(t *testing.T) {
	manager, _, _ := newManager(t)
	source := importSource(t, manager, "// copy isolation", "allowed.example.test")
	before := manager.List()
	mutate := func(item *lxsource.Source) {
		item.Name = "changed"
		item.AllowHTTPHosts[0] = "changed.example.test"
		platform := item.Platforms["fixture"]
		platform.Actions[0] = "changed"
		platform.Qualitys[0] = "changed"
		item.Platforms["injected"] = platform
	}
	mutate(&source)
	list := manager.List()
	mutate(&list.Items[0])
	list.ActiveID = "changed"
	active, ok := manager.Active()
	if !ok {
		t.Fatal("missing active source")
	}
	mutate(&active)
	if !reflect.DeepEqual(manager.List(), before) {
		t.Fatal("caller mutation escaped into manager state")
	}
}

func TestInvokeErrorIsSafeAndDoesNotLeakRunnerDetails(t *testing.T) {
	manager, runner, _ := newManager(t)
	importSource(t, manager, "// invocation failure")
	runner.mu.Lock()
	runner.invoke = func(context.Context, string, string, string, map[string]any, lxruntime.Options) (json.RawMessage, error) {
		return nil, errors.New("secret https://example.test/?token=DO_NOT_EXPOSE\nstack")
	}
	runner.mu.Unlock()
	_, err := manager.Invoke(t.Context(), "fixture", "musicUrl", nil)
	if err == nil || strings.Contains(err.Error(), "DO_NOT_EXPOSE") || strings.Contains(err.Error(), "://") || strings.Contains(err.Error(), "\n") {
		t.Fatalf("unsafe invocation diagnostic: %v", err)
	}
}

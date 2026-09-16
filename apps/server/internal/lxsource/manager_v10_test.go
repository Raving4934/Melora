package lxsource_test

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"

	"melora/internal/lxruntime"
	"melora/internal/lxsource"
)

func TestV10ManagerPublicHTTPNeedsNoHostConfiguration(t *testing.T) {
	for _, hosts := range [][]string{nil, {"legacy.example.test"}} {
		name := "no-hosts"
		if len(hosts) > 0 {
			name = "legacy-hosts"
		}
		t.Run(name, func(t *testing.T) {
			manager, runner, dir := newManager(t)
			checkOpts := func(opts lxruntime.Options) {
				t.Helper()
				if !opts.AllowPublicHTTP {
					t.Error("Manager did not enable LX-only public HTTP")
				}
				if !reflect.DeepEqual(opts.AllowHTTPHosts, hosts) && !(len(opts.AllowHTTPHosts) == 0 && len(hosts) == 0) {
					t.Error("legacy hosts not preserved")
				}
			}
			runner.setInspect(func(_ context.Context, _ string, opts lxruntime.Options) (lxruntime.Descriptor, error) {
				checkOpts(opts)
				return readyDescriptor(), nil
			})
			runner.invoke = func(_ context.Context, _, _, _ string, _ map[string]any, opts lxruntime.Options) (json.RawMessage, error) {
				checkOpts(opts)
				return json.RawMessage(`"fixture-only"`), nil
			}
			source := importSource(t, manager, "// synthetic HTTP capability fixture", hosts...)
			if _, err := manager.Check(t.Context(), source.ID); err != nil {
				t.Fatal(err)
			}
			if _, err := manager.InvokeSource(t.Context(), source.ID, "fixture", "musicUrl", map[string]any{"allowPublicHTTP": false}); err != nil {
				t.Fatal(err)
			}
			reopened := newManagerAt(t, dir, runner)
			if _, err := reopened.Invoke(t.Context(), "fixture", "lyric", nil); err != nil {
				t.Fatal(err)
			}
			// 权限是父进程策略，不是脚本能力、注册表/API 新字段。
			encoded, _ := json.Marshal(manager.List())
			if strings.Contains(strings.ToLower(string(encoded)), "allowpublichttp") {
				t.Fatal("LX policy leaked into source registry contract")
			}
		})
	}
}

func TestV10ExportOriginalSourceWithoutRunningOrSelecting(t *testing.T) {
	manager, runner, _ := newManager(t)
	active := importSource(t, manager, "// active source")
	const code = "/** @name export fixture */\nconst SECRET = 'fixture-only';\n"
	source, _, err := manager.Import(t.Context(), "../../export\nname.js", []byte(code), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Select(active.ID); err != nil {
		t.Fatal(err)
	}
	for _, state := range []string{"ready", "error", "disabled"} {
		t.Run(state, func(t *testing.T) {
			if state == "error" {
				runner.setInspect(func(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error) {
					return lxruntime.Descriptor{}, lxruntime.ErrScript
				})
				got, err := manager.Check(t.Context(), source.ID)
				if err != nil || got.Status != "error" {
					t.Fatalf("error setup: %v", err)
				}
			}
			if state == "disabled" {
				if err := manager.Select(""); err != nil {
					t.Fatal(err)
				}
			}
			before := manager.List()
			inspections, invocations := runner.calls()
			filename, got, err := manager.Export(source.ID)
			if err != nil || got != code || filename != source.Filename || filepath.Base(filename) != filename || strings.ContainsAny(filename, "\r\n\x00/\\") {
				t.Fatalf("export: filename=%q err=%v", filename, err)
			}
			afterInspections, afterInvocations := runner.calls()
			if !reflect.DeepEqual(before, manager.List()) || len(inspections) != len(afterInspections) || len(invocations) != len(afterInvocations) {
				t.Fatal("export ran script or mutated state")
			}
		})
	}
	for _, id := range []string{"", "../exportname.js", source.Filename, strings.Repeat("0", 24)} {
		filename, code, err := manager.Export(id)
		if !errors.Is(err, lxsource.ErrMissing) || filename != "" || code != "" {
			t.Fatal("raw filename/missing id exposed source")
		}
	}
}

func TestV10ExportIntegrityFailuresNeverReturnCode(t *testing.T) {
	for _, mode := range []string{"hash", "symlink", "hardlink", "directory", "missing", "oversized", "unreadable"} {
		t.Run(mode, func(t *testing.T) {
			if mode == "unreadable" && os.Geteuid() == 0 {
				t.Skip("root bypasses file permissions")
			}
			manager, runner, dir := newManager(t)
			const original = "// SECRET original fixture"
			source := importSource(t, manager, original)
			path := filepath.Join(dir, source.ID+".js")
			outside := filepath.Join(filepath.Dir(dir), "outside.js")
			writeFile(t, outside, []byte(original))
			var err error
			switch mode {
			case "hash":
				writeFile(t, path, []byte("// SECRET replaced fixture"))
			case "unreadable":
				err = os.Chmod(path, 0)
			case "oversized":
				writeFile(t, path, []byte(strings.Repeat("x", lxsource.MaxScriptBytes+1)))
			default:
				if err = os.Remove(path); err != nil {
					t.Fatal(err)
				}
				switch mode {
				case "symlink":
					err = os.Symlink(outside, path)
				case "hardlink":
					err = os.Link(outside, path)
				case "directory":
					err = os.Mkdir(path, 0700)
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			filename, code, err := manager.Export(source.ID)
			if !errors.Is(err, lxsource.ErrStorage) || filename != "" || code != "" || strings.Contains(err.Error(), "SECRET") || strings.Contains(err.Error(), dir) {
				t.Fatalf("unsafe export error: %v", err)
			}
			inspections, invocations := runner.calls()
			if len(inspections) != 1 || len(invocations) != 0 {
				t.Fatal("export ran script")
			}
		})
	}
}

func TestV10ExportConcurrentDeleteIsAtomic(t *testing.T) {
	manager, _, _ := newManager(t)
	for range 12 {
		source := importSource(t, manager, "// concurrent export fixture")
		start := make(chan struct{})
		var wg sync.WaitGroup
		for range 16 {
			wg.Go(func() {
				<-start
				filename, code, err := manager.Export(source.ID)
				switch {
				case err == nil:
					if filename != "fixture.js" || code != "// concurrent export fixture" {
						t.Error("partial export")
					}
				case errors.Is(err, lxsource.ErrMissing):
					if filename != "" || code != "" {
						t.Error("deleted source exposed")
					}
				default:
					t.Errorf("export interleaved with delete: %v", err)
				}
			})
		}
		wg.Go(func() {
			<-start
			if _, err := manager.Delete(source.ID); err != nil {
				t.Error(err)
			}
		})
		close(start)
		wg.Wait()
		if _, _, err := manager.Export(source.ID); !errors.Is(err, lxsource.ErrMissing) {
			t.Fatal("deleted id still exports")
		}
	}
}

func TestV10ExportFilenameIsCleanBasename(t *testing.T) {
	for _, name := range []string{"../../secret\r\nsource.js", `C:\Users\fixture\source.js`} {
		t.Run(name, func(t *testing.T) {
			manager, _, _ := newManager(t)
			source, _, err := manager.Import(t.Context(), name, []byte("// filename fixture"), nil)
			if err != nil {
				t.Fatal(err)
			}
			filename, code, err := manager.Export(source.ID)
			if err != nil || code != "// filename fixture" || !strings.HasSuffix(filename, ".js") || strings.ContainsAny(filename, "/\\\r\n\x00") {
				t.Fatalf("unsafe basename %q: %v", filename, err)
			}
		})
	}
}

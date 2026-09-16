package lxsource_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"melora/internal/lxruntime"
	"melora/internal/lxsource"
)

func encodeState(t *testing.T, state lxsource.State) []byte {
	t.Helper()
	data, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestRegistryRoundTripPreservesSelectionAndDisabledState(t *testing.T) {
	for _, disabled := range []bool{false, true} {
		t.Run(fmt.Sprintf("disabled_%v", disabled), func(t *testing.T) {
			manager, _, dir := newManager(t)
			first := importSource(t, manager, "/**\n * @name 第一份\n */", "first.example.test")
			second := importSource(t, manager, "/**\n * @name 第二份\n */", "second.example.test")
			if _, err := manager.Configure(first.ID, []string{"updated.example.test"}); err != nil {
				t.Fatal(err)
			}
			selected := second.ID
			if disabled {
				selected = ""
			}
			if err := manager.Select(selected); err != nil {
				t.Fatal(err)
			}
			before := manager.List()
			if err := manager.Close(); err != nil {
				t.Fatal(err)
			}
			runner := &fakeRunner{}
			reopened := newManagerAt(t, dir, runner)
			if !reflect.DeepEqual(reopened.List(), before) {
				t.Fatalf("registry round trip changed state:\ngot=%+v\nwant=%+v", reopened.List(), before)
			}
			active, ok := reopened.Active()
			if ok == disabled || (!disabled && active.ID != second.ID) {
				t.Fatalf("selection did not survive restart: %+v active=%v", active, ok)
			}
			inspections, invocations := runner.calls()
			if len(inspections) != 0 || len(invocations) != 0 {
				t.Fatal("opening stored sources executed scripts")
			}
		})
	}
}

func TestRegistryRecoversInterruptedCheckAndUnknownSelection(t *testing.T) {
	for _, interrupted := range []bool{true, false} {
		t.Run(fmt.Sprintf("interrupted_%v", interrupted), func(t *testing.T) {
			manager, _, dir := newManager(t)
			importSource(t, manager, "// registry recovery")
			state := manager.List()
			if interrupted {
				state.Items[0].Status = "checking"
			} else {
				state.ActiveID = strings.Repeat("f", 24)
			}
			if err := manager.Close(); err != nil {
				t.Fatal(err)
			}
			writeFile(t, filepath.Join(dir, "registry.json"), encodeState(t, state))
			reopened := newManagerAt(t, dir, &fakeRunner{})
			got := reopened.List()
			if interrupted {
				if got.Items[0].Status != "error" || got.Items[0].Error == "" {
					t.Fatalf("interrupted check remains usable: %+v", got)
				}
			} else if got.ActiveID != "" {
				t.Fatalf("unknown active ID was not cleared: %+v", got)
			}
			if _, ok := reopened.Active(); ok {
				t.Fatal("recovered registry unexpectedly has an active source")
			}
		})
	}
}

func TestRegistryRejectsInvalidMetadata(t *testing.T) {
	for _, kind := range []string{"malformed_json", "invalid_id", "duplicate_id", "short_hash", "invalid_hosts", "too_many", "oversized"} {
		t.Run(kind, func(t *testing.T) {
			manager, _, dir := newManager(t)
			source := importSource(t, manager, "// invalid registry fixture")
			state := manager.List()
			if err := manager.Close(); err != nil {
				t.Fatal(err)
			}
			var raw []byte
			switch kind {
			case "malformed_json":
				raw = []byte(`{"items":`)
			case "invalid_id":
				state.Items[0].ID = "../outside"
			case "duplicate_id":
				state.Items = append(state.Items, source)
			case "short_hash":
				state.Items[0].SHA256 = "abc"
			case "invalid_hosts":
				state.Items[0].AllowHTTPHosts = []string{"http://example.test"}
			case "too_many":
				state.Items = make([]lxsource.Source, 21)
			case "oversized":
				raw = bytes.Repeat([]byte(" "), (2<<20)+1)
			}
			if raw == nil {
				raw = encodeState(t, state)
			}
			writeFile(t, filepath.Join(dir, "registry.json"), raw)
			runner := &fakeRunner{}
			reopened, err := lxsource.New(dir, runner)
			if reopened != nil {
				_ = reopened.Close()
				t.Error("invalid registry returned a manager")
			}
			requireError(t, err, lxsource.ErrStorage)
			calls, _ := runner.calls()
			if len(calls) != 0 {
				t.Fatal("invalid registry executed Runner")
			}
		})
	}
}

func TestNewRejectsSymlinkedDirectories(t *testing.T) {
	for _, parentLink := range []bool{false, true} {
		t.Run(fmt.Sprintf("parent_link_%v", parentLink), func(t *testing.T) {
			base := t.TempDir()
			real := filepath.Join(base, "real")
			if err := os.Mkdir(real, 0750); err != nil {
				t.Fatal(err)
			}
			// 固定基线，不把执行测试者的 umask 当作被测行为。
			if err := os.Chmod(real, 0750); err != nil {
				t.Fatal(err)
			}
			link := filepath.Join(base, "alias")
			if err := os.Symlink(real, link); err != nil {
				t.Fatal(err)
			}
			dir := link
			if parentLink {
				dir = filepath.Join(link, "sources")
			}
			manager, err := lxsource.New(dir, &fakeRunner{})
			if manager != nil {
				_ = manager.Close()
				t.Fatal("symlinked private directory accepted")
			}
			requireError(t, err, lxsource.ErrStorage)
			requireMode(t, real, 0750)
			if _, err := os.Stat(filepath.Join(real, "sources")); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("created directories through rejected parent symlink: %v", err)
			}
		})
	}
}

func TestNewRejectsRegistryLinksAndUnreadableFile(t *testing.T) {
	for _, kind := range []string{"symlink", "hardlink", "directory", "unreadable"} {
		t.Run(kind, func(t *testing.T) {
			if kind == "unreadable" && os.Geteuid() == 0 {
				t.Skip("root 可绕过 DAC，无法验证普通包用户的读权限丢失")
			}
			base := t.TempDir()
			dir := filepath.Join(base, "sources")
			if err := os.Mkdir(dir, 0700); err != nil {
				t.Fatal(err)
			}
			outside := filepath.Join(base, "outside.json")
			raw := []byte(`{"items":[],"activeSourceId":""}`)
			writeFile(t, outside, raw)
			registry := filepath.Join(dir, "registry.json")
			var err error
			switch kind {
			case "symlink":
				err = os.Symlink(outside, registry)
			case "hardlink":
				err = os.Link(outside, registry)
			case "directory":
				err = os.Mkdir(registry, 0700)
			case "unreadable":
				writeFile(t, registry, raw)
				err = os.Chmod(registry, 0)
			}
			if err != nil {
				t.Fatal(err)
			}
			manager, err := lxsource.New(dir, &fakeRunner{})
			if manager != nil {
				_ = manager.Close()
				t.Fatal("unsafe registry accepted")
			}
			requireError(t, err, lxsource.ErrStorage)
			if !bytes.Equal(raw, readFile(t, outside)) {
				t.Fatal("rejected registry changed external target")
			}
			requireMode(t, outside, 0600)
		})
	}
}

func TestScriptTamperingBlocksInvokeCheckAndRestart(t *testing.T) {
	for _, kind := range []string{"hash_mismatch", "symlink", "hardlink", "directory", "missing", "oversized", "unreadable"} {
		t.Run(kind, func(t *testing.T) {
			if kind == "unreadable" && os.Geteuid() == 0 {
				t.Skip("root 可绕过 DAC，无法验证普通包用户的脚本读权限丢失")
			}
			manager, runner, dir := newManager(t)
			const code = "// script integrity fixture"
			source := importSource(t, manager, code)
			path := filepath.Join(dir, source.ID+".js")
			outside := filepath.Join(filepath.Dir(dir), "outside.js")
			writeFile(t, outside, []byte(code))
			var err error
			switch kind {
			case "hash_mismatch":
				writeFile(t, path, []byte("// changed without registry hash"))
			case "oversized":
				writeFile(t, path, bytes.Repeat([]byte("a"), lxsource.MaxScriptBytes+1))
			case "unreadable":
				err = os.Chmod(path, 0)
			default:
				if err = os.Remove(path); err != nil {
					t.Fatal(err)
				}
				switch kind {
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
			_, err = manager.Invoke(t.Context(), "fixture", "musicUrl", nil)
			requireError(t, err, lxsource.ErrStorage)
			checked, err := manager.Check(t.Context(), source.ID)
			if err != nil || checked.Status != "error" || checked.Error == "" {
				t.Fatalf("tampering not recorded as error state: %+v %v", checked, err)
			}
			if _, ok := manager.Active(); ok {
				t.Fatal("tampered source remains usable")
			}
			inspections, invocations := runner.calls()
			if len(inspections) != 1 || len(invocations) != 0 {
				t.Fatalf("tampered script reached Runner: inspect=%d invoke=%d", len(inspections), len(invocations))
			}
			if err := manager.Close(); err != nil {
				t.Fatal(err)
			}
			reopened := newManagerAt(t, dir, &fakeRunner{})
			if got := reopened.List().Items[0]; got.Status != "error" || got.Error == "" {
				t.Fatalf("restart forgot failed integrity: %+v", got)
			}
			if _, ok := reopened.Active(); ok {
				t.Fatal("restart reactivated tampered source")
			}
			if string(readFile(t, outside)) != code {
				t.Fatal("integrity checks changed external link target")
			}
		})
	}
}

func TestRegistryHashTamperingDisablesOtherwiseUnchangedScript(t *testing.T) {
	manager, _, dir := newManager(t)
	source := importSource(t, manager, "// unchanged code")
	state := manager.List()
	state.Items[0].SHA256 = strings.Repeat("0", 64)
	if err := manager.Close(); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "registry.json"), encodeState(t, state))
	runner := &fakeRunner{}
	reopened := newManagerAt(t, dir, runner)
	got := reopened.List().Items[0]
	if got.ID != source.ID || got.Status != "error" || got.Error == "" {
		t.Fatalf("registry hash tampering ignored: %+v", got)
	}
	if _, ok := reopened.Active(); ok {
		t.Fatal("hash mismatch remained active")
	}
	_, err := reopened.Invoke(t.Context(), "fixture", "musicUrl", nil)
	requireError(t, err, lxsource.ErrNoActive)
	inspections, invocations := runner.calls()
	if len(inspections) != 0 || len(invocations) != 0 {
		t.Fatal("hash mismatch executed Runner")
	}
}

func TestImportRecoversOnlyMatchingPrivateOrphanScript(t *testing.T) {
	for _, kind := range []string{"matching", "different", "symlink", "hardlink"} {
		t.Run(kind, func(t *testing.T) {
			// 从成功导入中取得真实内容地址，随后删除注册表模拟注册保存前中断。
			manager, _, dir := newManager(t)
			const code = "// interrupted import"
			source := importSource(t, manager, code)
			if err := manager.Close(); err != nil {
				t.Fatal(err)
			}
			if err := os.Remove(filepath.Join(dir, "registry.json")); err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(dir, source.ID+".js")
			if kind == "different" {
				writeFile(t, path, []byte("// do not overwrite me"))
			}
			if kind == "symlink" || kind == "hardlink" {
				outside := filepath.Join(filepath.Dir(dir), "external.js")
				writeFile(t, outside, []byte(code))
				if err := os.Remove(path); err != nil {
					t.Fatal(err)
				}
				var err error
				if kind == "symlink" {
					err = os.Symlink(outside, path)
				} else {
					err = os.Link(outside, path)
				}
				if err != nil {
					t.Fatal(err)
				}
			}
			runner := &fakeRunner{}
			reopened := newManagerAt(t, dir, runner)
			got, created, err := reopened.Import(t.Context(), "source.js", []byte(code), nil)
			inspections, _ := runner.calls()
			if kind == "matching" {
				if err != nil || !created || got.ID != source.ID || len(inspections) != 1 {
					t.Fatalf("matching orphan not recovered: %+v %v %v", got, created, err)
				}
			} else {
				requireError(t, err, lxsource.ErrStorage)
				if created || len(inspections) != 0 || len(reopened.List().Items) != 0 {
					t.Fatal("unsafe orphan was imported")
				}
			}
			if kind == "different" && string(readFile(t, path)) != "// do not overwrite me" {
				t.Fatal("import overwrote mismatched orphan")
			}
		})
	}
}

func TestMutationsRollbackWhenDirectoryLosesWritePermission(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 可绕过 DAC，权限回归必须由普通用户运行")
	}
	for _, operation := range []string{"configure", "select", "disable", "delete", "check", "import"} {
		t.Run(operation, func(t *testing.T) {
			manager, _, dir := newManager(t)
			first := importSource(t, manager, "// permission first", "first.example.test")
			second := importSource(t, manager, "// permission second")
			before := manager.List()
			registry := readFile(t, filepath.Join(dir, "registry.json"))
			if err := os.Chmod(dir, 0500); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				if err := os.Chmod(dir, 0700); err != nil {
					t.Error(err)
				}
			})
			var err error
			switch operation {
			case "configure":
				_, err = manager.Configure(first.ID, []string{"changed.example.test"})
			case "select":
				err = manager.Select(second.ID)
			case "disable":
				err = manager.Select("")
			case "delete":
				_, err = manager.Delete(first.ID)
			case "check":
				_, err = manager.Check(t.Context(), first.ID)
			case "import":
				_, _, err = manager.Import(t.Context(), "third.js", []byte("// denied import"), nil)
			}
			requireError(t, err, lxsource.ErrStorage)
			if !reflect.DeepEqual(manager.List(), before) || !bytes.Equal(registry, readFile(t, filepath.Join(dir, "registry.json"))) {
				t.Fatal("failed mutation changed memory or durable state")
			}
			if err := os.Chmod(dir, 0700); err != nil {
				t.Fatal(err)
			}
			reopened := newManagerAt(t, dir, &fakeRunner{})
			if !reflect.DeepEqual(reopened.List(), before) {
				t.Fatal("permission failure damaged persisted state")
			}
		})
	}
}

func TestMutationsRollbackWhenRegistryCommitFails(t *testing.T) {
	for _, operation := range []string{"configure", "select", "disable", "delete", "check", "import"} {
		t.Run(operation, func(t *testing.T) {
			manager, runner, dir := newManager(t)
			first := importSource(t, manager, "// commit first", "first.example.test")
			second := importSource(t, manager, "// commit second")
			before := manager.List()
			registry := filepath.Join(dir, "registry.json")
			backup := filepath.Join(dir, "registry.saved")
			if err := os.Rename(registry, backup); err != nil {
				t.Fatal(err)
			}
			// 固定制造 rename 提交失败，不依赖 root/DAC、磁盘配额或共享文件系统。
			if err := os.Mkdir(registry, 0700); err != nil {
				t.Fatal(err)
			}
			var err error
			switch operation {
			case "configure":
				_, err = manager.Configure(first.ID, []string{"changed.example.test"})
			case "select":
				err = manager.Select(second.ID)
			case "disable":
				err = manager.Select("")
			case "delete":
				_, err = manager.Delete(first.ID)
			case "check":
				runner.setInspect(func(context.Context, string, lxruntime.Options) (lxruntime.Descriptor, error) {
					return lxruntime.Descriptor{}, lxruntime.ErrScript
				})
				_, err = manager.Check(t.Context(), first.ID)
			case "import":
				_, _, err = manager.Import(t.Context(), "third.js", []byte("// interrupted commit"), nil)
			}
			requireError(t, err, lxsource.ErrStorage)
			if !reflect.DeepEqual(manager.List(), before) {
				t.Fatalf("failed registry commit did not roll back: %+v", manager.List())
			}
			if _, err := os.Stat(filepath.Join(dir, first.ID+".js")); err != nil {
				t.Fatalf("script removed before registry commit: %v", err)
			}
			temporary, err := filepath.Glob(filepath.Join(dir, ".registry-*"))
			if err != nil || len(temporary) != 0 {
				t.Fatalf("failed commit leaked temporary registry: %v %v", temporary, err)
			}
			if err := os.Remove(registry); err != nil {
				t.Fatal(err)
			}
			if err := os.Rename(backup, registry); err != nil {
				t.Fatal(err)
			}
			reopened := newManagerAt(t, dir, &fakeRunner{})
			if !reflect.DeepEqual(reopened.List(), before) {
				t.Fatal("commit failure damaged durable state")
			}
			if operation == "import" {
				// 失败留下的内容地址脚本可安全重用，不应永远阻断重试。
				importSource(t, reopened, "// interrupted commit")
			}
		})
	}
}

func TestDeleteReportsCleanupFailureWithoutResurrectingRegistry(t *testing.T) {
	manager, _, dir := newManager(t)
	source := importSource(t, manager, "// cleanup warning")
	path := filepath.Join(dir, source.ID+".js")
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	marker := filepath.Join(path, "must-survive")
	writeFile(t, marker, []byte("not a script"))
	warning, err := manager.Delete(source.ID)
	if err != nil || warning == "" || len(manager.List().Items) != 0 || manager.List().ActiveID != "" {
		t.Fatalf("cleanup failure contract: warning=%q err=%v state=%+v", warning, err, manager.List())
	}
	if string(readFile(t, marker)) != "not a script" {
		t.Fatal("delete recursively removed unexpected contents")
	}
	reopened := newManagerAt(t, dir, &fakeRunner{})
	if len(reopened.List().Items) != 0 || reopened.List().ActiveID != "" {
		t.Fatal("cleanup warning resurrected deleted source after restart")
	}
}

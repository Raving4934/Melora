package config

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"

	"melora/internal/storage"
)

func aliasConfig(t *testing.T, env map[string]string) Config {
	t.Helper()
	cfg, err := FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}
func mustDirectory(t *testing.T, path string) {
	t.Helper()
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
}
func mustAlias(t *testing.T, target, path string) {
	t.Helper()
	if err := os.Symlink(target, path); err != nil {
		t.Fatal(err)
	}
}

func TestFNOSAliasPhysicalContract(t *testing.T) {
	env, physical, _ := directoryEnv(t)
	alias := filepath.Join(filepath.Dir(physical), "用户显示目录")
	mustAlias(t, physical, alias)
	album := filepath.Join(physical, "下载 中文 空格")
	mustDirectory(t, album)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = alias
	cfg := aliasConfig(t, env)
	if roots := cfg.AuthorizedDownloadRoots(); len(roots) != 1 || roots[0] != alias {
		t.Fatal(roots)
	}
	for _, item := range []struct{ name, target string }{
		{"相对入口", "下载 中文 空格"},
		{"物理绝对入口", album},
		{"显示绝对入口", filepath.Join(alias, "下载 中文 空格")},
	} {
		mustAlias(t, item.target, filepath.Join(physical, item.name))
	}
	for _, name := range []string{"下载 中文 空格", "相对入口", "物理绝对入口", "显示绝对入口"} {
		display := filepath.Join(alias, name)
		opened, info, err := cfg.OpenDownloadDirectoryInfo(display)
		if err != nil {
			t.Fatal(name, err)
		}
		if info.Path != display || info.Root != alias || info.CanonicalPath != album {
			t.Fatal(info)
		}
		opened.Close()
		writable, canonical, err := cfg.OpenWritableDownloadDirectory(display)
		if err != nil || canonical != album {
			t.Fatal(canonical, err)
		}
		actual, err := writable.Stat(".")
		writable.Close()
		if err != nil {
			t.Fatal(err)
		}
		// worker 的严格 helper 必须能使用返回的物理路径，并得到同一对象，而非收到一个 alias。
		worker, err := storage.OpenDirectory(canonical)
		if err != nil {
			t.Fatal(err)
		}
		expected, err := worker.Stat(".")
		worker.Close()
		if err != nil || !os.SameFile(actual, expected) {
			t.Fatal("worker path changed object", err)
		}
	}
	if got := cfg.DisplayDownloadPath(album); got != filepath.Join(alias, "下载 中文 空格") {
		t.Fatal(got)
	}
	files, err := os.ReadDir(album)
	if err != nil || len(files) != 0 {
		t.Fatal("probe residue", files, err)
	}
}

func TestFNOSAlternateDisplayAlias(t *testing.T) {
	env, physical, other := directoryEnv(t)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = physical
	cfg := aliasConfig(t, env)
	visible := filepath.Join(filepath.Dir(physical), "模拟用户目录")
	mustAlias(t, physical, visible)
	mustDirectory(t, filepath.Join(physical, "下载"))
	requested := filepath.Join(visible, "下载")
	root, info, err := cfg.OpenDownloadDirectoryInfo(requested)
	if err != nil {
		t.Fatal(err)
	}
	root.Close()
	if info.Root != visible || info.Path != requested || info.CanonicalPath != filepath.Join(physical, "下载") {
		t.Fatal(info)
	}
	// 输入 alias 不是新授权：目标必须命中已授权 physical，不能借此枚举另一用户目录。
	outside := filepath.Join(filepath.Dir(physical), "另一个显示入口")
	mustAlias(t, other, outside)
	for _, path := range []string{outside, other, filepath.Join(other, "不存在")} {
		if _, err := cfg.ValidateDownloadPath(path); !errors.Is(err, storage.ErrDirectoryOutside) {
			t.Fatal(path, err)
		}
	}
}

func TestFNOSCanonicalIntersectionAndRevocation(t *testing.T) {
	env, physical, other := directoryEnv(t)
	selected := filepath.Join(physical, "限定下载")
	mustDirectory(t, selected)
	grantAlias := filepath.Join(filepath.Dir(physical), "系统入口")
	limitAlias := filepath.Join(filepath.Dir(physical), "管理员入口")
	mustAlias(t, physical, grantAlias)
	mustAlias(t, selected, limitAlias)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = grantAlias + ":" + other
	env["MELORA_DOWNLOAD_ROOT"] = limitAlias
	cfg := aliasConfig(t, env)
	if cfg.DownloadRoot != limitAlias {
		t.Fatal("administrator restriction rewritten")
	}
	for _, path := range []string{selected, limitAlias, filepath.Join(grantAlias, "限定下载")} {
		canonical, err := cfg.ValidateDownloadPath(path)
		if err != nil || canonical != selected {
			t.Fatal(path, canonical, err)
		}
	}
	mustAlias(t, other, filepath.Join(selected, "越界入口"))
	mustAlias(t, physical, filepath.Join(selected, "父级入口"))
	for _, path := range []string{other, grantAlias, filepath.Join(selected, "越界入口"), filepath.Join(selected, "父级入口")} {
		if _, err := cfg.ValidateDownloadPath(path); err == nil {
			t.Fatal("intersection expanded", path)
		}
	}
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = ""
	revoked := aliasConfig(t, env)
	if revoked.HasDownloadAuthorization() || revoked.DownloadRoot != limitAlias {
		t.Fatal("revocation changed restriction or fell back to it")
	}
	if _, err := revoked.ValidateDownloadPath(limitAlias); err == nil {
		t.Fatal("revoked alias still accepted")
	}
}

func TestFNOSAliasRetargetCannotExpandGrant(t *testing.T) {
	env, physical, other := directoryEnv(t)
	alias := filepath.Join(filepath.Dir(physical), "下载入口")
	mustAlias(t, physical, alias)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = alias
	cfg := aliasConfig(t, env)
	mustAlias(t, other, alias+"-next")
	if err := os.Rename(alias+"-next", alias); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{alias, physical} {
		if _, err := cfg.ValidateDownloadPath(path); !errors.Is(err, storage.ErrDirectoryChanged) {
			t.Fatal("retarget accepted", path, err)
		}
	}
	if cfg.HasDownloadAuthorization() {
		t.Fatal("retargeted root remained enabled")
	}
}

func TestFNOSAliasPrivateTargetsRejected(t *testing.T) {
	env, physical, _ := directoryEnv(t)
	for _, target := range []string{env["MELORA_DATA_DIR"], env["WEB_DIR"], "/"} {
		alias := filepath.Join(filepath.Dir(physical), "private-alias")
		mustAlias(t, target, alias)
		env["TRIM_DATA_ACCESSIBLE_PATHS"] = alias
		cfg := aliasConfig(t, env)
		if cfg.HasDownloadAuthorization() {
			t.Fatal("private canonical root allowed", target)
		}
		if err := os.Remove(alias); err != nil {
			t.Fatal(err)
		}
	}
}

func TestFNOSDirectoryFailureKinds(t *testing.T) {
	env, physical, other := directoryEnv(t)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = physical
	cfg := aliasConfig(t, env)
	file := filepath.Join(physical, "不是目录")
	if err := os.WriteFile(file, nil, 0600); err != nil {
		t.Fatal(err)
	}
	mustAlias(t, filepath.Join(other, "不存在"), filepath.Join(physical, "越界链接"))
	mustAlias(t, "循环乙", filepath.Join(physical, "循环甲"))
	mustAlias(t, "循环甲", filepath.Join(physical, "循环乙"))
	for _, tc := range []struct {
		path string
		want error
	}{
		{filepath.Join(physical, "不存在"), storage.ErrDirectoryMissing},
		{file, storage.ErrDirectoryNotDir},
		{filepath.Join(physical, "越界链接"), storage.ErrDirectoryOutside},
		{filepath.Join(physical, "循环甲"), storage.ErrDirectoryLoop},
	} {
		if _, err := cfg.ValidateDownloadPath(tc.path); !errors.Is(err, tc.want) {
			t.Fatal(tc.path, err, tc.want)
		}
	}
	if os.Geteuid() == 0 {
		t.Skip("permission denial requires a non-root test user")
	}
	locked, readonly := filepath.Join(physical, "无权限"), filepath.Join(physical, "只读")
	mustDirectory(t, locked)
	mustDirectory(t, readonly)
	if err := os.Chmod(locked, 0000); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(locked, 0700)
	if err := os.Chmod(readonly, 0500); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(readonly, 0700)
	if _, err := cfg.ValidateDownloadPath(locked); !errors.Is(err, storage.ErrDirectoryPermission) || !errors.Is(err, os.ErrPermission) {
		t.Fatal(err)
	}
	if _, err := cfg.ValidateDownloadPath(readonly); !errors.Is(err, storage.ErrDirectoryNotWritable) {
		t.Fatal(err)
	}
	// 根自身的故障也不能被根列表过滤后变成一个泛化的“未授权”。
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = locked
	denied := aliasConfig(t, env)
	if err := denied.DownloadAuthorizationError(); !errors.Is(err, storage.ErrDirectoryPermission) {
		t.Fatal(err)
	}
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = filepath.Join(physical, "missing-root")
	missing := aliasConfig(t, env)
	if err := missing.DownloadAuthorizationError(); !errors.Is(err, storage.ErrDirectoryMissing) {
		t.Fatal(err)
	}
	// 即使旧设置保存的是物理路径，失效的管理员限制仍应报告缺失，而非误报用户路径越界。
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = physical
	env["MELORA_DOWNLOAD_ROOT"] = filepath.Join(physical, "missing-limit")
	limited := aliasConfig(t, env)
	if _, err := limited.ValidateDownloadPath(physical); !errors.Is(err, storage.ErrDirectoryMissing) {
		t.Fatal(err)
	}
}

func TestFNOSInnerAliasReplacementRace(t *testing.T) {
	env, physical, outside := directoryEnv(t)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = physical
	inside := filepath.Join(physical, "安全目录")
	mustDirectory(t, inside)
	marker := filepath.Join(outside, "outside-marker")
	if err := os.WriteFile(marker, []byte("fixture only"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(physical, "入口")
	mustAlias(t, inside, link)
	cfg := aliasConfig(t, env)
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		target := outside
		for {
			select {
			case <-stop:
				return
			default:
			}
			if os.Symlink(target, link+"-swap") == nil {
				os.Rename(link+"-swap", link)
			}
			if target == outside {
				target = inside
			} else {
				target = outside
			}
			runtime.Gosched()
		}
	}()
	defer func() { close(stop); wg.Wait() }()
	for i := 0; i < 600; i++ {
		root, canonical, err := cfg.OpenWritableDownloadDirectory(link)
		if err != nil {
			continue
		}
		_, escaped := root.Stat("outside-marker")
		root.Close()
		if canonical != inside || escaped == nil {
			t.Fatal("alias race escaped canonical authorization")
		}
	}
	entries, err := os.ReadDir(outside)
	if err != nil || len(entries) != 1 || entries[0].Name() != "outside-marker" {
		t.Fatal("outside directory modified", err)
	}
}

func TestFNOSAlternateAliasReplacementRace(t *testing.T) {
	env, physical, outside := directoryEnv(t)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = physical
	alias := filepath.Join(filepath.Dir(physical), "另一用户显示入口")
	mustAlias(t, physical, alias)
	if err := os.WriteFile(filepath.Join(outside, "outside-marker"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	cfg := aliasConfig(t, env)
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		target := outside
		for {
			select {
			case <-stop:
				return
			default:
			}
			if os.Symlink(target, alias+"-swap") == nil {
				os.Rename(alias+"-swap", alias)
			}
			if target == outside {
				target = physical
			} else {
				target = outside
			}
			runtime.Gosched()
		}
	}()
	defer func() { close(stop); wg.Wait() }()
	for i := 0; i < 600; i++ {
		root, canonical, err := cfg.OpenWritableDownloadDirectory(alias)
		if err != nil {
			continue
		}
		_, escape := root.Stat("outside-marker")
		root.Close()
		if canonical != physical || escape == nil {
			t.Fatal("alternate alias granted an outside object")
		}
	}
	entries, err := os.ReadDir(outside)
	if err != nil || len(entries) != 1 || entries[0].Name() != "outside-marker" {
		t.Fatal("outside directory modified", err)
	}
}

func TestFNOSBroadLimitIsNotItsOwnGrant(t *testing.T) {
	env, physical, _ := directoryEnv(t)
	// 管理员上限包含应用私有区并不授予它；实际 fnOS 授权只有其中独立的音乐目录。
	env["MELORA_DOWNLOAD_ROOT"] = filepath.Dir(physical)
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = physical
	cfg := aliasConfig(t, env)
	if err := cfg.ValidateRuntimePaths(); err != nil {
		t.Fatal("broad restriction blocked app", err)
	}
	if roots := cfg.AuthorizedDownloadRoots(); len(roots) != 1 || roots[0] != physical {
		t.Fatal(roots)
	}
	for _, private := range []string{env["MELORA_DATA_DIR"], env["WEB_DIR"], filepath.Dir(env["MELORA_SOCKET"])} {
		if _, err := cfg.ValidateDownloadPath(private); err == nil {
			t.Fatal("restriction granted private directory", private)
		}
	}
	// 没有 fnOS 授权时仍为零；standalone 的同一个宽根依旧因私有重叠被拒绝。
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = ""
	if aliasConfig(t, env).HasDownloadAuthorization() {
		t.Fatal("empty grant fell back to broad limit")
	}
	delete(env, "MELORA_GATEWAY_AUTH")
	delete(env, "MELORA_SOCKET")
	delete(env, "MELORA_BASE_PATH")
	if _, err := FromEnv(func(k string) string { return env[k] }); err == nil {
		t.Fatal("standalone private overlap accepted")
	}
}

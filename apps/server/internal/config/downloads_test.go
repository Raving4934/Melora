package config

import (
	"os"
	"path/filepath"
	"testing"
)

func directoryEnv(t *testing.T) (map[string]string, string, string) {
	t.Helper()
	base := t.TempDir()
	a := filepath.Join(base, "音乐 甲")
	b := filepath.Join(base, "音乐 乙")
	for _, p := range []string{a, b, filepath.Join(base, "data"), filepath.Join(base, "web")} {
		if err := os.Mkdir(p, 0700); err != nil {
			t.Fatal(err)
		}
	}
	return map[string]string{"MELORA_DATA_DIR": filepath.Join(base, "data"), "WEB_DIR": filepath.Join(base, "web"), "MELORA_SOCKET": filepath.Join(base, "app.sock"), "MELORA_BASE_PATH": "/app/melora", "MELORA_GATEWAY_AUTH": "fnos-admin", "TRIM_DATA_ACCESSIBLE_PATHS": a + ":" + b}, a, b
}
func TestFNOSAuthorizationWithoutManualDownloadEnv(t *testing.T) {
	env, a, b := directoryEnv(t)
	cfg, err := FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	roots := cfg.AuthorizedDownloadRoots()
	if len(roots) != 2 || roots[0] != a || roots[1] != b || cfg.DownloadRoot != "" || cfg.DownloadAuthorization != "fnos" {
		t.Fatalf("bad grant %#v", cfg)
	}
	sub := filepath.Join(b, "专辑")
	if err = os.Mkdir(sub, 0700); err != nil {
		t.Fatal(err)
	}
	if selected, e := cfg.ValidateDownloadPath(sub); e != nil || selected != sub {
		t.Fatal(selected, e)
	}
	files, _ := os.ReadDir(sub)
	if len(files) != 0 {
		t.Fatal("validation left probe files")
	}
	if _, e := cfg.ValidateDownloadPath(filepath.Dir(a)); e == nil {
		t.Fatal("escaped grant")
	}
	link := filepath.Join(a, "shortcut")
	if e := os.Symlink(b, link); e != nil {
		t.Fatal(e)
	}
	if _, e := cfg.ValidateDownloadPath(link); e == nil {
		t.Fatal("symlink accepted")
	}
}
func TestStandaloneNeverTrustsInheritedFNOSGrant(t *testing.T) {
	env, a, _ := directoryEnv(t)
	delete(env, "MELORA_SOCKET")
	delete(env, "MELORA_BASE_PATH")
	delete(env, "MELORA_GATEWAY_AUTH")
	cfg, err := FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if cfg.HasDownloadAuthorization() {
		t.Fatal("standalone trusted TRIM")
	}
	env["MELORA_DOWNLOAD_ROOT"] = a
	cfg, err = FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.HasDownloadAuthorization() || cfg.DownloadAuthorization != "environment" {
		t.Fatal("explicit standalone grant lost")
	}
}
func TestFNOSExplicitRootCannotExpandGrantAndRevocationDoesNotStopApp(t *testing.T) {
	env, a, b := directoryEnv(t)
	selected := filepath.Join(a, "selected")
	if e := os.Mkdir(selected, 0700); e != nil {
		t.Fatal(e)
	}
	env["MELORA_DOWNLOAD_ROOT"] = selected
	cfg, err := FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	roots := cfg.AuthorizedDownloadRoots()
	if len(roots) != 1 || roots[0] != selected {
		t.Fatal(roots)
	}
	if _, err = cfg.ValidateDownloadPath(b); err == nil {
		t.Fatal("explicit narrow root expanded")
	}
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = ""
	cfg, err = FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal("revocation killed app", err)
	}
	if cfg.HasDownloadAuthorization() {
		t.Fatal("revoked grant fell back to env")
	}
	env["MELORA_DOWNLOAD_ROOT"] = filepath.Join(a, "missing")
	cfg, err = FromEnv(func(k string) string { return env[k] })
	if err != nil || cfg.HasDownloadAuthorization() {
		t.Fatal(cfg, err)
	}
}
func TestFNOSRejectsPrivateRootsAndRootReplacement(t *testing.T) {
	env, a, b := directoryEnv(t)
	link := filepath.Join(filepath.Dir(a), "alias")
	if e := os.Symlink(a, link); e != nil {
		t.Fatal(e)
	}
	env["TRIM_DATA_ACCESSIBLE_PATHS"] = "/:relative:" + link + ":" + env["MELORA_DATA_DIR"] + ":" + env["WEB_DIR"] + ":" + a + ":" + a
	cfg, err := FromEnv(func(k string) string { return env[k] })
	if err != nil {
		t.Fatal(err)
	}
	roots := cfg.AuthorizedDownloadRoots()
	if len(roots) != 1 || roots[0] != link {
		t.Fatal("明确授权的 alias 应保留展示路径并按物理对象去重", roots)
	}
	if e := os.Remove(a); e != nil {
		t.Fatal(e)
	}
	if e := os.Symlink(b, a); e != nil {
		t.Fatal(e)
	}
	if cfg.HasDownloadAuthorization() {
		t.Fatal("replaced authorization root accepted")
	}
}

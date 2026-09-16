package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAddressAuthorization(t *testing.T) {
	for _, addr := range []string{"127.0.0.1:1", "localhost:3780", "[::1]:65535"} {
		if err := ValidateAddress(addr, "", ""); err != nil {
			t.Fatal(err)
		}
	}
	for _, addr := range []string{"127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1:-1", "0.0.0.0:3780", "[::]:3780", "192.168.1.3:3780", "bad"} {
		if err := ValidateAddress(addr, "", ""); err == nil {
			t.Fatalf("allowed %s", addr)
		}
	}
	if err := ValidateAddress("0.0.0.0:3780", strings.Repeat("a", 32), ""); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAddress("127.0.0.1:3780", "short", ""); err == nil {
		t.Fatal("weak token accepted")
	}
}

func TestStandaloneInsecureHTTPRequiresExplicitOptIn(t *testing.T) {
	root := t.TempDir()
	data, web := filepath.Join(root, "data"), filepath.Join(root, "web")
	for _, dir := range []string{data, web} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	env := map[string]string{
		"MELORA_ADDR":       "0.0.0.0:3780",
		"MELORA_AUTH_TOKEN": strings.Repeat("a", 32),
		"MELORA_DATA_DIR":   data,
		"WEB_DIR":           web,
	}
	load := func() (Config, error) { return FromEnv(func(key string) string { return env[key] }) }
	if _, err := load(); err == nil {
		t.Fatal("non-loopback cleartext listener accepted without explicit opt-in")
	}
	env["MELORA_ALLOW_INSECURE_HTTP"] = "1"
	cfg, err := load()
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.AllowInsecureHTTP {
		t.Fatal("explicit insecure HTTP opt-in was lost")
	}
	for _, value := range []string{"true", "yes", "2", "-1"} {
		env["MELORA_ALLOW_INSECURE_HTTP"] = value
		if _, err := load(); err == nil {
			t.Fatalf("invalid MELORA_ALLOW_INSECURE_HTTP value accepted: %q", value)
		}
	}
}

func TestGatewayAndLoopbackIgnoreDisabledInsecureHTTP(t *testing.T) {
	root := t.TempDir()
	data, web := filepath.Join(root, "data"), filepath.Join(root, "web")
	for _, dir := range []string{data, web} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	env := map[string]string{"MELORA_ADDR": "127.0.0.1:3780", "MELORA_DATA_DIR": data, "WEB_DIR": web, "MELORA_ALLOW_INSECURE_HTTP": "0"}
	if _, err := FromEnv(func(key string) string { return env[key] }); err != nil {
		t.Fatal("loopback compatibility regression", err)
	}
	dir, err := os.MkdirTemp("", "melora-config-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	env["MELORA_SOCKET"] = filepath.Join(dir, "app.sock")
	env["MELORA_BASE_PATH"] = "/app/melora"
	env["MELORA_GATEWAY_AUTH"] = "fnos-admin"
	if _, err := FromEnv(func(key string) string { return env[key] }); err != nil {
		t.Fatal("gateway compatibility regression", err)
	}
}
func TestTrustedProxiesParsing(t *testing.T) {
	nets, err := ParseTrustedProxies("127.0.0.1, 172.17.0.0/16, ::1")
	if err != nil || len(nets) != 3 {
		t.Fatalf("parse trusted proxies: %v %v", nets, err)
	}
	if empty, err := ParseTrustedProxies("  "); err != nil || empty != nil {
		t.Fatalf("blank trusted proxies = %v, %v", empty, err)
	}
	for _, bad := range []string{"not-an-ip", "10.0.0.0/99", "1.2.3.4/", "300.1.1.1"} {
		if _, err := ParseTrustedProxies(bad); err == nil {
			t.Fatalf("accepted invalid trusted proxy %q", bad)
		}
	}
}

func TestCloudDeployModeRequiresCredentialsAndRejectsDownloadRoot(t *testing.T) {
	root := t.TempDir()
	data, web := filepath.Join(root, "data"), filepath.Join(root, "web")
	for _, dir := range []string{data, web} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	env := map[string]string{
		"MELORA_DEPLOY_MODE":         "cloud",
		"MELORA_ADDR":                "0.0.0.0:3780",
		"MELORA_ALLOW_INSECURE_HTTP": "1",
		"MELORA_DATA_DIR":            data,
		"WEB_DIR":                    web,
	}
	load := func() (Config, error) { return FromEnv(func(key string) string { return env[key] }) }
	if _, err := load(); err == nil {
		t.Fatal("cloud deploy accepted without password or token")
	}
	env["MELORA_ADMIN_PASSWORD"] = "strong-password"
	cfg, err := load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DeployMode != DeployModeCloud || cfg.AdminUser != "admin" {
		t.Fatalf("cloud defaults wrong: %+v", cfg)
	}
	env["MELORA_ADMIN_USER"] = "owner"
	env["MELORA_ADMIN_PASSWORD"] = "short"
	if _, err := load(); err == nil {
		t.Fatal("weak admin password accepted")
	}
	env["MELORA_ADMIN_PASSWORD"] = "strong-password"
	env["MELORA_DOWNLOAD_ROOT"] = data
	if _, err := load(); err == nil {
		t.Fatal("cloud deploy accepted a server download root")
	}
}

func TestAuthorizedPathAndSymlink(t *testing.T) {
	root := t.TempDir()
	music := filepath.Join(root, "music")
	outside := filepath.Join(root, "outside")
	nested := filepath.Join(music, "album")
	for _, dir := range []string{music, outside, nested} {
		if err := os.Mkdir(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := ValidateDownloadPath(music, nested); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{outside, root, "../music", filepath.Join(root, "music-evil")} {
		if _, err := ValidateDownloadPath(music, path); err == nil {
			t.Fatalf("allowed %s", path)
		}
	}
	if err := os.Symlink(outside, filepath.Join(music, "escape")); err != nil {
		t.Fatal(err)
	}
	if _, err := ValidateDownloadPath(music, filepath.Join(music, "escape")); err == nil {
		t.Fatal("symlink escape accepted")
	}
	if _, err := ValidateDownloadPath("", music); err == nil {
		t.Fatal("unapproved directory accepted")
	}
	files, _ := os.ReadDir(nested)
	if len(files) != 0 {
		t.Fatal("validation left temporary probes")
	}
}

func TestGatewayConfigurationIsExplicitAndBounded(t *testing.T) {
	root := t.TempDir()
	cfg := Config{Addr: "127.0.0.1:3780", DataDir: filepath.Join(root, "data"), WebDir: filepath.Join(root, "web"), SocketPath: filepath.Join(root, "app.sock"), BasePath: "/app/melora", GatewayAuth: "fnos-admin"}
	// 使用较短的父路径避免测试函数名超过系统 Socket 限长。
	dir, err := os.MkdirTemp("", "melora-config-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	cfg.SocketPath = filepath.Join(dir, "app.sock")
	if err := cfg.ValidateGateway(); err != nil {
		t.Fatal(err)
	}
	bad := cfg
	bad.SocketPath = ""
	if bad.ValidateGateway() == nil {
		t.Fatal("gateway headers trusted without Unix Socket")
	}
	bad = cfg
	bad.GatewayAuth = ""
	if bad.ValidateGateway() == nil {
		t.Fatal("socket without declared authentication")
	}
	bad = cfg
	bad.BasePath = "/app/melora/../other"
	if bad.ValidateGateway() == nil {
		t.Fatal("unsafe prefix accepted")
	}
	bad = cfg
	bad.SocketPath = filepath.Join(cfg.WebDir, "app.sock")
	if bad.ValidateGateway() == nil {
		t.Fatal("socket within public web root")
	}
	bad = cfg
	bad.SocketPath = filepath.Join(dir, strings.Repeat("x", 110))
	if bad.ValidateGateway() == nil {
		t.Fatal("oversized socket path")
	}
}

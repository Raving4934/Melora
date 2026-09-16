package config

// 由 Python 回归脚本按真实 module/internal 布局复制 config、storage 及本契约到隔离目录。
// 不改后端源码，禁止联网、自动下载工具链或生成发布产物。
import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"melora/internal/storage"
)

func TestFPKEnvironmentMatchesActualBackend(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"web", "data", "music"} {
		if err := os.Mkdir(filepath.Join(root, name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	for _, addr := range []string{"127.0.0.1:3780", "[::1]:3780", "0.0.0.0:3780", "[::]:3780"} {
		for _, size := range []int{32, 4096} {
			env := map[string]string{
				"MELORA_ADDR":          addr,
				"MELORA_DATA_DIR":      filepath.Join(root, "data"),
				"WEB_DIR":              filepath.Join(root, "web"),
				"MELORA_DOWNLOAD_ROOT": filepath.Join(root, "music"),
				"MELORA_AUTH_TOKEN":    strings.Repeat("x", size),
			}
			cfg, err := FromEnv(func(key string) string { return env[key] })
			if err != nil {
				t.Fatalf("FPK accepted address/token rejected by backend: %v", err)
			}
			if err := cfg.ValidateRuntimePaths(); err != nil {
				t.Fatalf("FPK physical paths rejected by backend: %v", err)
			}
			if cfg.WebDir != env["WEB_DIR"] || cfg.DataDir != env["MELORA_DATA_DIR"] || cfg.AuthToken != env["MELORA_AUTH_TOKEN"] {
				t.Fatal("backend environment mapping differs from FPK")
			}
		}
	}
	if err := ValidateAddress("127.0.0.1:3780", "", ""); err != nil {
		t.Fatal("default loopback configuration must be accepted")
	}
	if err := ValidateAddress("0.0.0.0:3780", "", ""); err == nil {
		t.Fatal("anonymous external binding must be rejected")
	}
	if err := ValidateAddress("127.0.0.1:3780", strings.Repeat("x", 4097), ""); err == nil {
		t.Fatal("token exceeding 4096 bytes must be rejected")
	}
	if err := ValidateAddress("0.0.0.0:3780", "", "strong-password"); err != nil {
		t.Fatal("password-protected external binding must be accepted")
	}
}

func TestGatewayFPKEnvironmentMatchesActualBackend(t *testing.T) {
	// 使用短测试目录，避免测试函数名让 Unix Socket 超过内核的 107 字节路径限制。
	short, err := os.MkdirTemp(".", "u-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(short)
	root, err := filepath.Abs(short)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"web", "data"} {
		if err := os.Mkdir(filepath.Join(root, name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	env := map[string]string{
		"MELORA_DATA_DIR":     filepath.Join(root, "data"),
		"WEB_DIR":             filepath.Join(root, "web"),
		"MELORA_SOCKET":       filepath.Join(root, "app.sock"),
		"MELORA_BASE_PATH":    "/app/melora",
		"MELORA_GATEWAY_AUTH": "fnos-admin",
	}
	cfg, err := FromEnv(func(key string) string { return env[key] })
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SocketPath != env["MELORA_SOCKET"] || cfg.BasePath != "/app/melora" || cfg.GatewayAuth != "fnos-admin" || cfg.AuthToken != "" {
		t.Fatal("gateway FPK environment does not match actual backend")
	}
	if err := cfg.ValidateRuntimePaths(); err != nil {
		t.Fatal(err)
	}
}

func TestFPKGatewayAuthorizationContract(t *testing.T) {
	short, err := os.MkdirTemp(".", "d-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(short)
	root, err := filepath.Abs(short)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"web", "data", "music", "music/sub", "音乐 资料", "outside", "readonly"} {
		if err := os.Mkdir(filepath.Join(root, name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	music, extra := filepath.Join(root, "music"), filepath.Join(root, "音乐 资料")
	sub, alias := filepath.Join(music, "sub"), filepath.Join(root, "alias")
	if err := os.Symlink(music, alias); err != nil {
		t.Fatal(err)
	}
	// 限定的真实 fs fixture：显示 alias 下存在一个指向未授权目录的链接，绝不能借 alias 扩权。
	for _, parent := range []string{music, sub} {
		if err := os.Symlink(filepath.Join(root, "outside"), filepath.Join(parent, "escape")); err != nil {
			t.Fatal(err)
		}
	}
	for _, tc := range []struct {
		name, grants, restriction string
		want                      []string
	}{
		{"colon_and_spaces", music + ":" + extra, "", []string{music, extra}},
		{"narrow_admin_root", music + ":" + extra, sub, []string{sub}},
		{"narrow_system_grant", sub, music, []string{sub}},
		{"no_implicit_grant", "", music, nil},
		{"revoked_old_root", extra, music, nil},
		{"missing_old_root", music, filepath.Join(music, "missing"), nil},
		{"symlink_old_root", music, alias, []string{music}},
		{"symlink_parent_old_root", music, filepath.Join(alias, "sub"), []string{sub}},
		{"symlink_system_grant", alias, "", []string{alias}},
		{"revoked_alias_limit", "", alias, nil},
		{"alias_limit_cannot_grant_sibling", extra, alias, nil},
		{"private_and_system_roots", "/:" + filepath.Join(root, "web") + ":" + filepath.Join(root, "data"), "", nil},
		{"malformed_system_paths", ":relative/path::" + music + ":" + music, "", []string{music}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			env := map[string]string{
				"MELORA_DATA_DIR": filepath.Join(root, "data"), "WEB_DIR": filepath.Join(root, "web"),
				"MELORA_SOCKET": filepath.Join(root, "app.sock"), "MELORA_BASE_PATH": "/app/melora",
				"MELORA_GATEWAY_AUTH": "fnos-admin", "MELORA_DOWNLOAD_ROOT": tc.restriction,
				"TRIM_DATA_ACCESSIBLE_PATHS": tc.grants,
			}
			cfg, err := FromEnv(func(key string) string { return env[key] })
			if err != nil {
				t.Fatalf("unavailable download authorization must not block gateway startup: %v", err)
			}
			if err := cfg.ValidateRuntimePaths(); err != nil {
				t.Fatal(err)
			}
			got := cfg.AuthorizedDownloadRoots()
			if len(got) != len(tc.want) {
				t.Fatalf("effective roots %q, want %q", got, tc.want)
			}
			for i := range got {
				if got[i] != tc.want[i] {
					t.Fatalf("effective roots %q, want %q", got, tc.want)
				}
			}
			if cfg.DownloadRoot != tc.restriction {
				t.Fatal("administrator restriction text must not be discarded or rewritten; canonical intersection is separate")
			}
			if _, err := cfg.ValidateDownloadPath(filepath.Join(root, "outside")); err == nil {
				t.Fatal("outside path was accepted")
			}
			if len(tc.want) == 0 {
				if _, err := cfg.ValidateDownloadPath(music); err == nil {
					t.Fatal("disabled authorization still allowed a download")
				}
			}
			var choice, physical string
			switch tc.name {
			case "symlink_old_root", "symlink_system_grant":
				choice, physical = alias, music
			case "symlink_parent_old_root":
				choice, physical = filepath.Join(alias, "sub"), sub
			}
			if choice != "" {
				opened, selected, err := cfg.OpenDownloadDirectoryInfo(choice)
				if err != nil {
					t.Fatal(err)
				}
				opened.Close()
				if selected.Path != choice || selected.Root != choice || selected.CanonicalPath != physical {
					t.Fatalf("display alias/physical worker contract differs: %+v", selected)
				}
				canonical, err := cfg.ValidateDownloadPath(choice)
				if err != nil || canonical != physical {
					t.Fatalf("worker path %q, want %q: %v", canonical, physical, err)
				}
				writable, canonical, err := cfg.OpenWritableDownloadDirectory(choice)
				if err != nil {
					t.Fatal(err)
				}
				actual, statErr := writable.Stat(".")
				writable.Close()
				expected, pathErr := os.Stat(physical)
				if canonical != physical || statErr != nil || pathErr != nil || !os.SameFile(actual, expected) {
					t.Fatal("write probe FD differs from the canonical worker directory", statErr, pathErr)
				}
				if _, err := cfg.ValidateDownloadPath(filepath.Join(choice, "escape")); !errors.Is(err, storage.ErrDirectoryOutside) {
					t.Fatal("display alias followed a link outside the canonical grant", err)
				}
				if tc.name == "symlink_parent_old_root" {
					if _, err := cfg.ValidateDownloadPath(alias); !errors.Is(err, storage.ErrDirectoryOutside) {
						t.Fatal("narrow canonical restriction expanded to the alias parent", err)
					}
				}
			}
		})
	}
	// 生命周期只传环境；最终写入仍必须通过真实权限验证，不为共享目录放宽权限。
	if os.Geteuid() != 0 {
		readonly := filepath.Join(root, "readonly")
		if err := os.Chmod(readonly, 0500); err != nil {
			t.Fatal(err)
		}
		defer os.Chmod(readonly, 0700)
		cfg, err := FromEnv(func(key string) string {
			return map[string]string{
				"MELORA_DATA_DIR": filepath.Join(root, "data"), "WEB_DIR": filepath.Join(root, "web"),
				"MELORA_SOCKET": filepath.Join(root, "app.sock"), "MELORA_BASE_PATH": "/app/melora",
				"MELORA_GATEWAY_AUTH": "fnos-admin", "TRIM_DATA_ACCESSIBLE_PATHS": readonly,
			}[key]
		})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := cfg.ValidateDownloadPath(readonly); err == nil {
			t.Fatal("read-only directory was accepted for downloads")
		}
		info, err := os.Stat(readonly)
		if err != nil || info.Mode().Perm() != 0500 {
			t.Fatal("application must not change shared directory permissions")
		}
	}
	// standalone 不可借用 fnOS 环境自动扩大文件访问范围。
	for _, explicit := range []string{"", music} {
		cfg, err := FromEnv(func(key string) string {
			return map[string]string{
				"MELORA_DATA_DIR": filepath.Join(root, "data"), "WEB_DIR": filepath.Join(root, "web"),
				"MELORA_DOWNLOAD_ROOT": explicit, "TRIM_DATA_ACCESSIBLE_PATHS": extra,
			}[key]
		})
		if err != nil {
			t.Fatal(err)
		}
		got := cfg.AuthorizedDownloadRoots()
		if explicit == "" && len(got) != 0 || explicit != "" && (len(got) != 1 || got[0] != music) {
			t.Fatalf("standalone consumed implicit system authorization: %q", got)
		}
	}
}

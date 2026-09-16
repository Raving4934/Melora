// 此文件仅为生命周期测试服务。Listen/Healthcheck 使用临时复制的真实后端 transport 源码。
// 真实 Unix Socket/TCP + HTTP 响应，不以 pause 或伪造探测退出码冒充就绪。
package main

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	addr := os.Getenv("MELORA_ADDR")
	if addr == "" {
		addr = "127.0.0.1:3780"
	}
	socket, base := os.Getenv("MELORA_SOCKET"), os.Getenv("MELORA_BASE_PATH")
	if len(os.Args) == 2 && os.Args[1] == "--uninstall-preflight" {
		// 生命周期路由测试只验证 shell 的超时与分支；真实清理器由 internal/uninstall 覆盖。
		return
	}
	if len(os.Args) == 2 && os.Args[1] == "--uninstall-stop-check" {
		// 此夹具不启动音源 worker；服务已由生命周期脚本停止即可视为通过。
		// 可选挂起用于验证 Shell 不会被异常/旧二进制永久阻塞。
		if os.Getenv("MELORA_TEST_HANG_UNINSTALL_STOP_CHECK") == "1" {
			time.Sleep(30 * time.Second)
		}
		return
	}
	if len(os.Args) == 2 && os.Args[1] == "--healthcheck" {
		// 故意模拟损坏的探测程序，验证 shell 的外层超时；绝不返回虚假的健康成功。
		if os.Getenv("MELORA_TEST_HANG_PROBE") == "1" {
			time.Sleep(30 * time.Second)
		}
		if Healthcheck(addr, socket, base) != nil {
			os.Exit(1)
		}
		return
	}
	data := os.Getenv("MELORA_DATA_DIR")
	env := map[string]string{}
	for _, key := range []string{"MELORA_ACCESS_MODE", "MELORA_ADDR", "MELORA_DATA_DIR", "MELORA_DOWNLOAD_ROOT", "WEB_DIR", "MELORA_AUTH_TOKEN", "MELORA_SOCKET", "MELORA_BASE_PATH", "MELORA_GATEWAY_AUTH", "TRIM_DATA_ACCESSIBLE_PATHS", "TRIM_API_TOKEN", "MELORA_DEMO_MODE", "MELORA_DEPLOY_MODE", "MELORA_ADMIN_USER", "MELORA_ADMIN_PASSWORD", "MELORA_TRUSTED_PROXIES"} {
		env[key] = os.Getenv(key)
	}
	encoded, _ := json.Marshal(env)
	if os.WriteFile(filepath.Join(data, "fixture-env.json"), encoded, 0600) != nil {
		os.Exit(1)
	}
	log := json.NewEncoder(os.Stderr)
	_ = log.Encode(map[string]string{"event": "fixture_starting"})
	if os.Getenv("MELORA_TEST_SCENARIO") == "exit" {
		_ = log.Encode(map[string]string{"event": "fixture_startup_failed"})
		os.Exit(1)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer cancel()
	if os.Getenv("MELORA_TEST_IGNORE_TERM") == "1" {
		signal.Ignore(syscall.SIGTERM)
	}
	if os.Getenv("MELORA_TEST_SCENARIO") == "no_listener" {
		<-ctx.Done()
		return
	}
	listener, err := Listen(addr, socket)
	if err != nil {
		_ = log.Encode(map[string]string{"event": "fixture_listen_failed"})
		os.Exit(1)
	}
	defer listener.Close()
	mux := http.NewServeMux()
	mux.HandleFunc(base+"/health", func(w http.ResponseWriter, r *http.Request) {
		scenario := os.Getenv("MELORA_TEST_SCENARIO")
		if override, err := os.ReadFile(filepath.Join(data, "fixture-scenario")); err == nil {
			scenario = string(override)
		}
		switch scenario {
		case "unhealthy":
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"status":"ok","name":"Melora"}`))
		case "invalid_json":
			_, _ = w.Write([]byte(`not-json`))
		case "wrong_identity":
			_, _ = w.Write([]byte(`{"status":"ok","name":"NotMelora"}`))
		case "redirect":
			http.Redirect(w, r, base+"/health", http.StatusFound)
		default:
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"status":"ok","name":"Melora","version":"0.5.12"}`))
		}
	})
	server := &http.Server{Handler: mux, ReadHeaderTimeout: time.Second}
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	select {
	case <-ctx.Done():
		_ = server.Close()
	case <-done:
	}
}

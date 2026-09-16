//go:build linux

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"melora/internal/model"
)

// 执行实际run/SQLite/源管理器/Unix Socket/健康与管理员API，不只检查配置函数。
func TestGatewayStartupWithSearchOnlyAncestors(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root bypasses directory permissions")
	}
	root, err := os.MkdirTemp("", "melora-boot-")
	if err != nil {
		t.Fatal(err)
	}
	parent := filepath.Join(root, "search-only")
	t.Cleanup(func() { _ = os.Chmod(parent, 0700); _ = os.RemoveAll(root) })
	app, data := filepath.Join(parent, "app"), filepath.Join(parent, "private", "data")
	web, socket := filepath.Join(app, "web"), filepath.Join(app, "app.sock")
	for _, path := range []string{web, data} {
		if err := os.MkdirAll(path, 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(web, "index.html"), []byte("<!doctype html><title>fixture</title>"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(parent, 0111); err != nil {
		t.Fatal(err)
	}
	if _, err := os.ReadDir(parent); !errors.Is(err, os.ErrPermission) {
		t.Fatalf("fixture must deny listing: %v", err)
	}
	binary, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(binary, "-test.run=^TestDownloadStartupChild$")
	for _, variable := range os.Environ() {
		if !strings.HasPrefix(variable, "MELORA_") && !strings.HasPrefix(variable, "TRIM_") && !strings.HasPrefix(variable, "WEB_DIR=") {
			cmd.Env = append(cmd.Env, variable)
		}
	}
	cmd.Env = append(cmd.Env, "MELORA_TEST_STORAGE_STARTUP=1", "MELORA_SOCKET="+socket, "MELORA_BASE_PATH=/app/melora", "MELORA_GATEWAY_AUTH=fnos-admin", "MELORA_DATA_DIR="+data, "WEB_DIR="+web)
	var logs bytes.Buffer
	cmd.Stdout, cmd.Stderr = &logs, &logs
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	stopped := false
	defer func() {
		if stopped {
			return
		}
		_ = cmd.Process.Signal(syscall.SIGTERM)
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			_ = cmd.Process.Kill()
			<-done
		}
	}()
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", socket)
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: time.Second}
	ready := false
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case err := <-done:
			stopped = true
			t.Fatalf("startup exited: %v; %s", err, logs.String())
		default:
		}
		response, err := client.Get("http://localhost/app/melora/health")
		if err == nil {
			ready = response.StatusCode == http.StatusOK
			response.Body.Close()
			if ready {
				break
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !ready {
		t.Fatal("actual Unix Socket health did not become ready")
	}
	request, _ := http.NewRequest(http.MethodGet, "http://localhost/app/melora/api/v1/settings", nil)
	request.Header.Set("X-Trim-Userid", "1000")
	request.Header.Set("X-Trim-Username", "fixture-admin")
	request.Header.Set("X-Trim-Isadmin", "true")
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var settings model.Settings
	if response.StatusCode != http.StatusOK {
		t.Fatalf("settings status %d", response.StatusCode)
	}
	if err := json.NewDecoder(response.Body).Decode(&settings); err != nil {
		t.Fatal(err)
	}
	if settings.DefaultQuality != "standard" || settings.FileNameFormat != "title-artist" {
		t.Fatal("settings did not initialize")
	}
	if info, err := os.Stat(filepath.Join(data, "lx-sources")); err != nil || !info.IsDir() {
		t.Fatal("source private directory did not initialize")
	}
	if _, err := os.ReadDir(parent); !errors.Is(err, os.ErrPermission) {
		t.Fatal("startup unexpectedly changed ancestor permissions")
	}
	if info, err := os.Lstat(socket); err != nil || info.Mode()&os.ModeSocket == 0 || info.Mode().Perm() != 0660 {
		t.Fatalf("unsafe socket permissions: %v %v", info, err)
	}
}

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"melora/internal/download"
	"melora/internal/model"
	"melora/internal/store"
)

func TestDownloadStorageDowngradeDoesNotHideOtherErrors(t *testing.T) {
	settings := store.DefaultSettings()
	settings.DownloadRoot = filepath.Join(t.TempDir(), "missing")
	resolver := func(context.Context, model.Track, string) (string, error) { return "", errors.New("unused") }
	databaseFailure := errors.New("fixture database failure")
	_, _, err := initializeDownloads(settings, resolver, func(model.DownloadJob) error { return nil }, nil, func(model.Settings) error { return databaseFailure })
	if !errors.Is(err, databaseFailure) {
		t.Fatal("save failure hidden", err)
	}
	_, _, err = initializeDownloads(settings, resolver, func(model.DownloadJob) error { return nil }, []model.DownloadJob{{ID: "../invalid"}}, func(model.Settings) error { return nil })
	if err == nil || errors.Is(err, download.ErrStorageUnavailable) {
		t.Fatal("corrupted queue hidden", err)
	}
	settings.DownloadRoot = ""
	_, _, err = initializeDownloads(settings, nil, func(model.DownloadJob) error { return nil }, nil, func(model.Settings) error { return nil })
	if err == nil {
		t.Fatal("missing resolver hidden")
	}
}

// 子进程执行真实run启动路径，测试只发送子进程SIGTERM，不影响测试进程/用户服务。
func TestDownloadStartupChild(t *testing.T) {
	if os.Getenv("MELORA_TEST_STORAGE_STARTUP") != "1" {
		return
	}
	if err := run(slog.New(slog.NewTextHandler(os.Stderr, nil))); err != nil {
		os.Exit(2)
	}
	os.Exit(0)
}
func TestUnavailableSinglesDoesNotPreventApplicationStartup(t *testing.T) {
	for _, kind := range []string{"symlink", "readonly"} {
		t.Run(kind, func(t *testing.T) {
			if kind == "readonly" && os.Geteuid() == 0 {
				t.Skip("root bypasses Unix directory permission denial")
			}
			base := t.TempDir()
			data := filepath.Join(base, "data")
			web := filepath.Join(base, "web")
			root := filepath.Join(base, "music")
			outside := filepath.Join(base, "outside")
			for _, path := range []string{data, web, root, outside} {
				if err := os.Mkdir(path, 0700); err != nil {
					t.Fatal(err)
				}
			}
			if err := os.WriteFile(filepath.Join(web, "index.html"), []byte("<!doctype html><title>startup fixture</title>"), 0600); err != nil {
				t.Fatal(err)
			}
			sentinel := filepath.Join(outside, "existing.part")
			if err := os.WriteFile(sentinel, []byte("preserve me"), 0600); err != nil {
				t.Fatal(err)
			}
			singles := filepath.Join(root, "Singles")
			if kind == "symlink" {
				if err := os.Symlink(outside, singles); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := os.Mkdir(singles, 0500); err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { os.Chmod(singles, 0700) })
			}
			db, err := store.Open(filepath.Join(data, "melora.db"))
			if err != nil {
				t.Fatal(err)
			}
			settings := store.DefaultSettings()
			settings.DownloadRoot = root
			if err = db.SaveSettings(t.Context(), settings); err != nil {
				t.Fatal(err)
			}
			if err = db.SaveDownload(model.DownloadJob{ID: "keep-job", Track: model.Track{ID: "demo:fixture", ProviderID: "demo", Title: "preserve"}, Quality: "standard", State: "paused"}); err != nil {
				t.Fatal(err)
			}
			db.Close()
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			address := listener.Addr().String()
			listener.Close()
			command := exec.Command(os.Args[0], "-test.run=^TestDownloadStartupChild$")
			env := []string{}
			for _, line := range os.Environ() {
				if !strings.HasPrefix(line, "MELORA_") && !strings.HasPrefix(line, "TRIM_") && !strings.HasPrefix(line, "WEB_DIR=") {
					env = append(env, line)
				}
			}
			command.Env = append(env, "MELORA_TEST_STORAGE_STARTUP=1", "MELORA_DEMO_MODE=1", "MELORA_ADDR="+address, "MELORA_DATA_DIR="+data, "WEB_DIR="+web, "MELORA_DOWNLOAD_ROOT="+root)
			var logs bytes.Buffer
			command.Stdout = &logs
			command.Stderr = &logs
			if err = command.Start(); err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() { done <- command.Wait() }()
			defer func() {
				command.Process.Signal(syscall.SIGTERM)
				select {
				case <-done:
				case <-time.After(5 * time.Second):
					command.Process.Kill()
					<-done
				}
			}()
			client := &http.Client{Timeout: 300 * time.Millisecond}
			ready := false
			deadline := time.Now().Add(10 * time.Second)
			for time.Now().Before(deadline) {
				resp, requestErr := client.Get("http://" + address + "/health")
				if requestErr == nil {
					resp.Body.Close()
					if resp.StatusCode == 200 {
						ready = true
						break
					}
				}
				time.Sleep(30 * time.Millisecond)
			}
			if !ready {
				t.Fatal("download failure prevented HTTP readiness")
			}
			resp, err := client.Get("http://" + address + "/api/v1/settings")
			if err != nil {
				t.Fatal(err)
			}
			var current model.Settings
			err = json.NewDecoder(resp.Body).Decode(&current)
			resp.Body.Close()
			if err != nil || current.DownloadRoot != "" {
				t.Fatal("unavailable storage not disabled", current, err)
			}
			resp, err = client.Get("http://" + address + "/api/v1/downloads")
			if err != nil {
				t.Fatal(err)
			}
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if !bytes.Contains(body, []byte(`"id":"keep-job"`)) {
				t.Fatal("prior job lost", string(body))
			}
			remaining, err := os.ReadFile(sentinel)
			if err != nil || string(remaining) != "preserve me" {
				t.Fatal("existing external file touched")
			}
		})
	}
}

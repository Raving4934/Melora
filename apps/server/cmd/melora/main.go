// Melora 提供小型音乐元数据 API 和独立 NAS 下载 Worker，不提供音频代理。
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"time"

	"melora/internal/api"
	"melora/internal/catalog"
	"melora/internal/config"
	"melora/internal/download"
	"melora/internal/downloadassets"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
	"melora/internal/provider"
	"melora/internal/store"
	"melora/internal/transport"
	"melora/internal/uninstall"
)

func main() {
	// 卸载辅助必须在配置、SQLite、音源 Manager/worker 初始化之前分派。
	if len(os.Args) == 2 {
		switch os.Args[1] {
		case uninstall.PreflightArgument, uninstall.CleanupArgument, uninstall.StopCheckArgument, uninstall.DeferredCleanupArgument:
			removed, err := uninstall.Execute(os.Args[1])
			if err != nil {
				fmt.Fprintln(os.Stderr, "Melora:", err)
				os.Exit(1)
			}
			if os.Args[1] == uninstall.CleanupArgument || os.Args[1] == uninstall.DeferredCleanupArgument {
				fmt.Printf("Melora: 已清理 %d 个已知私有文件；未知文件、音乐、下载与 exports 未主动删除。\n", removed)
			}
			return
		}
	}
	if len(os.Args) == 2 && os.Args[1] == lxruntime.WorkerArgument {
		os.Exit(lxruntime.WorkerMain())
	}
	flags := flag.NewFlagSet("melora", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	health := flags.Bool("healthcheck", false, "check service readiness without changing data")
	if flags.Parse(os.Args[1:]) != nil || flags.NArg() != 0 {
		fmt.Fprintln(os.Stderr, "Melora: 无效启动参数")
		os.Exit(2)
	}
	if *health {
		cfg, err := config.Load()
		if err != nil || transport.Healthcheck(cfg.Addr, cfg.SocketPath, cfg.BasePath) != nil {
			fmt.Fprintln(os.Stderr, "Melora: 服务尚未就绪")
			os.Exit(1)
		}
		fmt.Println("Melora: ready")
		return
	}
	logger := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{ReplaceAttr: redactLogs(os.Getenv("MELORA_AUTH_TOKEN"))}))
	slog.SetDefault(logger)
	if err := run(logger); err != nil {
		logger.Error("Melora stopped", "error", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	if err = os.MkdirAll(cfg.DataDir, 0700); err != nil {
		return err
	}
	if err = cfg.ValidateRuntimePaths(); err != nil {
		return err
	}
	db, err := store.Open(filepath.Join(cfg.DataDir, "melora.db"))
	if err != nil {
		return err
	}
	defer db.Close()
	sources, err := lxsource.New(filepath.Join(cfg.DataDir, "lx-sources"), nil)
	if err != nil {
		return err
	}
	defer sources.Close()
	var live *provider.Live
	if os.Getenv("MELORA_DEMO_MODE") != "1" {
		live = provider.NewLive(sources, catalog.NewWY(catalog.GuardedClient()))
		live.Catalog.SetMetadataStore(db)
	}
	ctx := context.Background()
	settings, err := db.Settings(ctx)
	if err != nil {
		return err
	}
	if live != nil {
		live.SetAutoSwitch(settings.AutoSwitchSource)
	}
	demo := provider.NewDemo()
	// 保存的展示alias在校验后转换为受信任物理目录，再交给严格FD下载器。
	// 撤权只停用下载，不删除旧任务和音乐文件。cloud 模式完全不碰下载状态。
	var downloads api.Downloads
	var manager *download.Manager
	if cfg.DeployMode != config.DeployModeCloud {
		if settings.DownloadRoot != "" {
			canonical, validationErr := cfg.ValidateDownloadPath(settings.DownloadRoot)
			if validationErr != nil {
				logger.Warn("saved download directory is no longer authorized; downloads disabled")
				canonical = ""
			}
			if canonical != settings.DownloadRoot {
				settings.DownloadRoot = canonical
				if err = db.SaveSettings(ctx, settings); err != nil {
					return err
				}
			}
		}
		jobs, err := db.Downloads(ctx)
		if err != nil {
			return err
		}
		var updatedSettings model.Settings
		manager, updatedSettings, err = initializeDownloads(settings, func(ctx context.Context, track model.Track, quality string) (string, error) {
			if live != nil {
				preferences, e := db.Settings(ctx)
				if e != nil {
					return "", e
				}
				info, e := live.ResolveWithOptions(ctx, track, quality, provider.ResolveOptions{AutoSwitch: preferences.AutoSwitchSource})
				return info.URL, e
			}
			enabled, err := db.ProviderEnabled(ctx)
			if err != nil {
				return "", errors.New("音源状态不可用")
			}
			if !enabled {
				return "", errors.New("音源已停用")
			}
			return demo.Resolve(ctx, track, quality)
		}, db.SaveDownload, jobs, func(next model.Settings) error { return db.SaveSettings(ctx, next) })
		if err != nil {
			return err
		}
		if updatedSettings.DownloadRoot != settings.DownloadRoot {
			logger.Warn("download storage unavailable; downloads disabled while application remains accessible")
		}
		settings = updatedSettings
		defer manager.Close()
		manager.SetWriteMetadata(false)
		if err = manager.SetOptions(settings); err != nil {
			return err
		}
		if live != nil {
			if err = manager.SetMetadataFetcher(downloadassets.Fetcher(live.Catalog)); err != nil {
				return err
			}
		}
		downloads = manager
	}
	handler, err := api.New(cfg, db, demo, downloads)
	if err != nil {
		return err
	}
	defer handler.Close()
	handler.UseLiveSources(sources, live)
	listener, err := transport.Listen(cfg.Addr, cfg.SocketPath)
	if err != nil {
		return err
	}
	defer listener.Close()
	server := &http.Server{Addr: cfg.Addr, Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 20 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32 << 10}
	// SSE 自行设置写入超时，不能用全局 WriteTimeout 截断长连接。
	stopped, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	done := make(chan error, 1)
	go func() {
		logger.Info("Melora listening", "transport", listener.Addr().Network(), "address", listener.Addr().String(), "basePath", cfg.BasePath, "authMode", cfg.GatewayAuth, "demo", live == nil, "authentication", cfg.AuthToken != "" || cfg.AdminPassword != "" || cfg.GatewayAuth != "")
		done <- server.Serve(listener)
	}()
	select {
	case err = <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-stopped.Done():
		logger.Info("Melora shutting down; unfinished downloads will be paused")
		// 先通知 SSE 退出，再等待请求结束，最后暂停 Worker、落盘状态。
		handler.Close()
		shutdown, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err = server.Shutdown(shutdown); err != nil {
			_ = server.Close()
		}
		if manager != nil {
			manager.Close()
		}
		return err
	}
}

// 持久化诊断日志只记录脱敏内容；令牌与任何远端完整 URL 不进入日志文件。
func redactLogs(token string) func([]string, slog.Attr) slog.Attr {
	urls := regexp.MustCompile(`https?://[^\s"<>]+`)
	clean := func(value string) string {
		if token != "" {
			value = strings.ReplaceAll(value, token, "[redacted]")
		}
		return urls.ReplaceAllString(value, "[remote URL]")
	}
	return func(_ []string, attr slog.Attr) slog.Attr {
		attr.Value = attr.Value.Resolve()
		if err, ok := attr.Value.Any().(error); ok {
			attr.Value = slog.StringValue(clean(err.Error()))
		} else if attr.Value.Kind() == slog.KindString {
			attr.Value = slog.StringValue(clean(attr.Value.String()))
		}
		return attr
	}
}

package provider

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
)

func TestMain(m *testing.M) {
	if len(os.Args) == 2 && os.Args[1] == lxruntime.WorkerArgument {
		os.Exit(lxruntime.WorkerMain())
	}
	os.Exit(m.Run())
}
func liveV5ErrorClass(err error) string {
	if err == nil {
		return ""
	}
	for _, x := range []struct {
		err  error
		name string
	}{{context.Canceled, "canceled"}, {context.DeadlineExceeded, "deadline"}, {lxruntime.ErrUnsupported, "unsupported_sdk"}, {lxruntime.ErrTimeout, "worker_timeout"}, {lxruntime.ErrNetwork, "metadata_network"}, {lxruntime.ErrScript, "script_error"}, {lxruntime.ErrResource, "resource_limit"}, {ErrMediaURL, "invalid_media_result"}, {ErrMediaHeaders, "required_media_headers"}, {ErrMixedContent, "mixed_content"}, {ErrQuality, "quality_unsupported"}, {catalog.ErrUnavailable, "catalog_unavailable"}, {catalog.ErrNotFound, "track_not_found"}} {
		if errors.Is(err, x.err) {
			return x.name
		}
	}
	return "unavailable"
}

// 实网只读元数据与解析结果，不连接媒体、不保存脚本/签名 URL/正文；私有导入状态在清理时删除。
func TestLiveV5ProvidedSourcesIsolated(t *testing.T) {
	if os.Getenv("MELORA_LX_V5_LIVE") != "1" {
		t.Skip("explicit isolated LX v5 live test only")
	}
	reportPath, scriptDir := os.Getenv("MELORA_LX_V5_REPORT"), os.Getenv("MELORA_LX_V5_SCRIPTS")
	if !filepath.IsAbs(reportPath) || !strings.HasPrefix(filepath.Base(reportPath), "lx-v5-") || !filepath.IsAbs(scriptDir) {
		t.Fatal("absolute lx-v5 report path and script directory required")
	}
	file, err := os.OpenFile(reportPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal("cannot create isolated report")
	}
	defer file.Close()
	encode := json.NewEncoder(file)
	state, err := os.MkdirTemp(filepath.Dir(reportPath), "lx-v5-private-state-")
	if err != nil {
		t.Fatal("cannot create private state")
	}
	manager, err := lxsource.New(filepath.Join(state, "sources"), nil)
	if err != nil {
		os.RemoveAll(state)
		t.Fatal("cannot open isolated source manager")
	}
	t.Cleanup(func() { _ = manager.Close(); _ = os.RemoveAll(state) })
	l := &Live{Sources: manager, Catalog: catalog.NewAllRegistry(catalog.NewWY(catalog.GuardedClient()))}
	tracks := map[string]model.Track{}
	loadTrack := func(platform string) (model.Track, error) {
		if track, ok := tracks[platform]; ok {
			return track, nil
		}
		ctx, cancel := context.WithTimeout(t.Context(), 6*time.Second)
		defer cancel()
		var track model.Track
		var err error
		if platform == "wy" {
			track, err = l.Catalog.Track(ctx, "wy:186016")
		} else {
			var result catalog.SearchResult
			result, err = l.Catalog.SearchFor(ctx, platform, "周杰伦 晴天", "track", 1)
			if err == nil {
				if len(result.Tracks) == 0 {
					err = catalog.ErrNotFound
				} else {
					track = result.Tracks[0]
				}
			}
		}
		if err == nil {
			tracks[platform] = track
		}
		return track, err
	}
	entries, err := os.ReadDir(scriptDir)
	if err != nil {
		t.Fatal("cannot read provided script directory")
	}
	ready, resolved := 0, 0
	for _, entry := range entries {
		// 诊断时可精确选择一个已提供文件，避免重复采样其它上游；缺省仍覆盖全部。
		if selected := os.Getenv("MELORA_LX_V5_FILE"); selected != "" && entry.Name() != selected {
			continue
		}
		if !entry.Type().IsRegular() || filepath.Ext(entry.Name()) != ".js" {
			continue
		}
		stat, err := entry.Info()
		if err != nil || stat.Size() > lxsource.MaxScriptBytes {
			continue
		}
		code, err := os.ReadFile(filepath.Join(scriptDir, entry.Name()))
		if err != nil {
			t.Fatal("cannot read provided script")
		}
		// 仅此隔离测试显式许可脚本公开声明的两个 HTTP 元数据主机；不更改生产默认/用户状态。
		hosts := []string{}
		if strings.HasPrefix(entry.Name(), "HYWmusic_") {
			hosts = []string{"103.79.184.97"}
		}
		if strings.Contains(entry.Name(), "玉宁熙") {
			hosts = []string{"ynx.de5.net"}
		}
		start := time.Now()
		source, _, err := manager.Import(t.Context(), entry.Name(), code, hosts)
		code = nil
		record := map[string]any{"file": entry.Name(), "at": start.UTC().Format(time.RFC3339), "inspectStatus": source.Status, "inspectError": liveV5ErrorClass(err), "isolatedMetadataHTTPHosts": hosts}
		if err == nil && source.Status == "ready" {
			ready++
			platforms := []string{}
			for platform := range source.Platforms {
				platforms = append(platforms, platform)
			}
			sort.Strings(platforms)
			record["platforms"] = platforms
			platform := ""
			for _, p := range catalog.PlatformOrder {
				if len(sourceQualities(source, p)) > 0 {
					platform = p
					break
				}
			}
			record["testedPlatform"] = platform
			if platform != "" {
				track, trackErr := loadTrack(platform)
				if trackErr == nil {
					if manager.Select(source.ID) != nil {
						t.Fatal("isolated source selection failed")
					}
					result, resolveErr := l.PlayInfoWithOptions(t.Context(), track, "standard", ResolveOptions{PageScheme: "http"})
					record["resolveError"] = liveV5ErrorClass(resolveErr)
					record["attemptedSourceCount"] = len(result.AttemptedSources)
					record["quality"] = result.Quality
					if resolveErr == nil {
						resolved++
						u, _ := url.Parse(result.URL)
						record["mediaScheme"] = u.Scheme
						record["httpPageValid"] = validateResolvedMedia(t.Context(), result.URL, "http", true) == nil
						record["httpsPageValid"] = validateResolvedMedia(t.Context(), result.URL, "https", true) == nil
						record["downloadValid"] = validateResolvedMedia(t.Context(), result.URL, "https", false) == nil
					}
				} else {
					record["resolveError"] = liveV5ErrorClass(trackErr)
				}
			}
		}
		record["elapsedMs"] = time.Since(start).Milliseconds()
		if encode.Encode(record) != nil {
			t.Fatal("cannot write sanitized report")
		}
		t.Logf("file=%s ready=%v resolve=%v elapsedMs=%v", entry.Name(), source.Status == "ready", record["resolveError"], record["elapsedMs"])
	}
	_ = encode.Encode(map[string]any{"summary": true, "ready": ready, "resolved": resolved, "providerMediaRequests": 0, "userStateModified": false})
	if ready == 0 || resolved == 0 {
		t.Error("no usable provided source in this live run; see sanitized limitations")
	}
}

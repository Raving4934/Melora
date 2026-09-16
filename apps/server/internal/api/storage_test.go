package api

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"melora/internal/model"
	"melora/internal/store"
)

func TestStorageStatusReadOnlyAuthorizedAndNoPathOverride(t *testing.T) {
	s, db, music := setup(t, "")
	before, err := os.ReadDir(music)
	if err != nil {
		t.Fatal(err)
	}
	w := request(s, "GET", "/api/v1/storage/status?path=/etc", nil, nil)
	assertStatus(t, w, 200)
	var result struct {
		Authorized     bool   `json:"authorized"`
		Configured     bool   `json:"configured"`
		AuthorizedRoot string `json:"authorizedRoot"`
		Path           string `json:"path"`
		Capacity       struct {
			Available uint64 `json:"availableBytes"`
			Total     uint64 `json:"totalBytes"`
		} `json:"capacity"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if !result.Authorized || result.Configured || result.Path != music || result.AuthorizedRoot != music || result.Capacity.Total == 0 || result.Capacity.Available > result.Capacity.Total {
		t.Fatalf("bad authorized storage response: %s", w.Body.String())
	}
	after, err := os.ReadDir(music)
	if err != nil || len(before) != len(after) {
		t.Fatal("GET storage probe mutated directory", err)
	}
	settings := store.DefaultSettings()
	settings.DownloadRoot = music
	if err := db.SaveSettings(context.Background(), settings); err != nil {
		t.Fatal(err)
	}
	w = request(s, "GET", "/api/v1/storage/status", nil, nil)
	if !strings.Contains(w.Body.String(), `"configured":true`) {
		t.Fatal(w.Body.String())
	}
	// 即使持久化配置被外部修改，也不能借状态接口探测授权根外路径。
	settings.DownloadRoot = filepath.Dir(music)
	if err := db.SaveSettings(context.Background(), settings); err != nil {
		t.Fatal(err)
	}
	w = request(s, "GET", "/api/v1/storage/status", nil, nil)
	if strings.Contains(w.Body.String(), `"capacity"`) || !strings.Contains(w.Body.String(), `"error"`) {
		t.Fatal(w.Body.String())
	}
	s.cfg.DownloadRoot = ""
	w = request(s, "GET", "/api/v1/storage/status", nil, nil)
	if strings.Contains(w.Body.String(), `"path"`) || !strings.Contains(w.Body.String(), `"authorized":false`) {
		t.Fatal(w.Body.String())
	}
}
func TestStorageStatusAuthAndUnavailableDirectory(t *testing.T) {
	s, _, _ := setup(t, strings.Repeat("x", 32))
	assertStatus(t, request(s, "GET", "/api/v1/storage/status", nil, nil), 401)
	s2, db, music := setup(t, "")
	target := filepath.Join(music, "missing")
	settings := store.DefaultSettings()
	settings.DownloadRoot = target
	if err := db.SaveSettings(context.Background(), settings); err != nil {
		t.Fatal(err)
	}
	w := request(s2, "GET", "/api/v1/storage/status", nil, nil)
	assertStatus(t, w, 200)
	if !strings.Contains(w.Body.String(), `"error"`) || strings.Contains(w.Body.String(), `"capacity"`) {
		t.Fatal(w.Body.String())
	}
	if err := os.Symlink(filepath.Dir(music), target); err != nil {
		t.Fatal(err)
	}
	w = request(s2, "GET", "/api/v1/storage/status", nil, nil)
	if strings.Contains(w.Body.String(), `"capacity"`) {
		t.Fatal("symlink storage leaked", w.Body.String())
	}
}

type recordingDownloads struct {
	Downloads
	writeMetadata bool
	created       model.DownloadJob
}

func (d *recordingDownloads) SetWriteMetadata(enabled bool) { d.writeMetadata = enabled }
func (d *recordingDownloads) SetOptions(settings model.Settings) error {
	d.writeMetadata = settings.WriteMetadata
	return d.Downloads.SetOptions(settings)
}
func (d *recordingDownloads) CreateWithOptions(track model.Track, quality string, settings model.Settings) (model.DownloadJob, error) {
	d.created = model.DownloadJob{
		ID: "test-only", Track: track, Quality: quality, State: "queued",
		WriteMetadata: settings.WriteMetadata, FileNameFormat: settings.FileNameFormat,
		WriteLyrics: settings.WriteLyrics, WriteCover: settings.WriteCover, EmbedTags: settings.EmbedTags,
	}
	return d.created, nil
}
func (d *recordingDownloads) Create(track model.Track, quality string) (model.DownloadJob, error) {
	d.created = model.DownloadJob{ID: "test-only", Track: track, Quality: quality, State: "queued", WriteMetadata: d.writeMetadata}
	return d.created, nil
}
func TestRetiredJSONSettingDoesNotAffectLegacyJobs(t *testing.T) {
	s, db, music := setup(t, "")
	recorder := &recordingDownloads{Downloads: s.downloads}
	s.downloads = recorder
	legacyPath := filepath.Join(music, "legacy.json")
	legacyJSON := []byte(`{"title":"existing legacy metadata"}`)
	if err := os.WriteFile(legacyPath, legacyJSON, 0600); err != nil {
		t.Fatal(err)
	}
	legacy := model.DownloadJob{ID: "legacy-json", Track: model.Track{ID: "demo:legacy", Title: "legacy"}, Quality: "standard", State: "completed", WriteMetadata: true, MetadataPath: legacyPath}
	if err := db.SaveDownload(legacy); err != nil {
		t.Fatal(err)
	}
	settings := store.DefaultSettings()
	settings.DownloadRoot = music
	settings.WriteMetadata, settings.ShowDirect = true, true
	// 旧客户端字段继续可被接收，但无论 PUT/PATCH 都不能重新启用已退役的 JSON 选项。
	assertStatus(t, request(s, "PUT", "/api/v1/settings", settings, nil), 200)
	assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]bool{"writeMetadata": true, "showDirect": true}, nil), 200)
	saved, err := db.Settings(context.Background())
	if err != nil || saved.WriteMetadata || saved.ShowDirect || recorder.writeMetadata {
		t.Fatal("retired option re-enabled", saved, err)
	}
	w := request(s, "POST", "/api/v1/downloads", map[string]string{"trackId": "demo:maple-leaf-rag-1906"}, nil)
	assertStatus(t, w, 201)
	if recorder.created.WriteMetadata {
		t.Fatal("new job enabled retired JSON output")
	}
	jobs, err := db.Downloads(context.Background())
	if err != nil || len(jobs) != 1 || !jobs[0].WriteMetadata || jobs[0].MetadataPath != legacyPath {
		t.Fatal("legacy JSON snapshot was rewritten", jobs, err)
	}
	remaining, err := os.ReadFile(legacyPath)
	if err != nil || string(remaining) != string(legacyJSON) {
		t.Fatal("existing JSON file changed", err)
	}
}

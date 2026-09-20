package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"melora/internal/config"
)

// cloud 部署不提供任何服务端下载能力，任务 API 必须直接拒绝，而不是返回空列表。
func TestCloudDeployDisablesServerDownloadAPIs(t *testing.T) {
	s := &Server{cfg: config.Config{DeployMode: config.DeployModeCloud}}
	cases := map[string]func(http.ResponseWriter, *http.Request){
		"list":                s.downloadListCreate,
		"action":              s.downloadAction,
		"events":              s.downloadEvents,
		"byID":                s.downloadByID,
		"clear":               s.clearDownloadRecords,
		"storage-status":      s.storageStatus,
		"storage-directories": s.storageDirectories,
		"storage-validate":    s.validateStorage,
	}
	for name, handler := range cases {
		r := httptest.NewRequest("GET", "/api/v1/downloads", nil)
		r.SetPathValue("id", "fixture")
		r.SetPathValue("action", "pause")
		w := httptest.NewRecorder()
		handler(w, r)
		if w.Code != 404 {
			t.Fatalf("%s: status %d; want 404", name, w.Code)
		}
	}
}

func TestCloudDeployRejectsDownloadSettingsWithoutMutation(t *testing.T) {
	s, db, _ := setup(t, "")
	s.cfg.DeployMode = config.DeployModeCloud
	before, err := db.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	w := request(s, http.MethodPatch, "/api/v1/settings", map[string]any{"concurrency": 2}, nil)
	if w.Code != http.StatusNotFound {
		t.Fatalf("status %d; want 404: %s", w.Code, w.Body.String())
	}
	after, err := db.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if after != before {
		t.Fatalf("cloud download settings mutated: before=%+v after=%+v", before, after)
	}
}

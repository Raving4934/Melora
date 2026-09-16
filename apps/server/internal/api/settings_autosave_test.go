package api

import (
	"encoding/json"
	"path/filepath"
	"sync"
	"testing"
)

func TestSettingsPatchMergesFieldsAndRetiresLegacyOptions(t *testing.T) {
	s, db, _ := setup(t, "")
	for _, patch := range []map[string]any{
		{"defaultQuality": "flac"}, {"fileNameFormat": "artist-title"},
		{"writeLyrics": true}, {"writeCover": true}, {"embedTags": true},
		{"autoSwitchSource": false}, {"writeMetadata": true, "showDirect": true},
	} {
		assertStatus(t, request(s, "PATCH", "/api/v1/settings", patch, nil), 200)
	}
	got, err := db.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if got.DefaultQuality != "flac" || got.FileNameFormat != "artist-title" || !got.WriteLyrics || !got.WriteCover || !got.EmbedTags || got.AutoSwitchSource || got.WriteMetadata || got.ShowDirect {
		t.Fatalf("partial updates lost fields: %+v", got)
	}
}
func TestSettingsPatchDoesNotValidateUnchangedStaleDirectory(t *testing.T) {
	s, db, music := setup(t, "")
	current, err := db.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	current.DownloadRoot = filepath.Join(music, "removed-directory")
	if err = db.SaveSettings(t.Context(), current); err != nil {
		t.Fatal(err)
	}
	assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]any{"defaultQuality": "320k"}, nil), 200)
	got, _ := db.Settings(t.Context())
	if got.DownloadRoot != current.DownloadRoot || got.DefaultQuality != "320k" {
		t.Fatal(got)
	}
	assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]any{"downloadRoot": current.DownloadRoot}, nil), 400)
}
func TestSettingsPatchRejectsInvalidInputWithoutSaving(t *testing.T) {
	s, db, _ := setup(t, "")
	before, _ := db.Settings(t.Context())
	for _, patch := range []any{nil, []any{}, map[string]any{}, map[string]any{"unknown": true}, map[string]any{"concurrency": 0}, map[string]any{"concurrency": "2"}, map[string]any{"embedTags": nil}, map[string]any{"defaultQuality": "ultra"}, map[string]any{"fileNameFormat": "../title"}} {
		status := request(s, "PATCH", "/api/v1/settings", patch, nil).Code
		if status != 400 && status != 415 {
			t.Fatalf("invalid patch accepted: %#v status=%d", patch, status)
		}
	}
	after, _ := db.Settings(t.Context())
	if before != after {
		t.Fatal("invalid request mutated settings")
	}
}
func TestSettingsConcurrentFieldPatchesPreserveEachOther(t *testing.T) {
	s, db, _ := setup(t, "")
	var wg sync.WaitGroup
	for key, value := range map[string]any{"writeLyrics": true, "writeCover": true, "embedTags": true, "defaultQuality": "flac24bit", "fileNameFormat": "title"} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			w := request(s, "PATCH", "/api/v1/settings", map[string]any{key: value}, nil)
			if w.Code != 200 {
				t.Errorf("patch failed %s", w.Body.String())
			}
		}()
	}
	wg.Wait()
	got, _ := db.Settings(t.Context())
	if !got.WriteLyrics || !got.WriteCover || !got.EmbedTags || got.DefaultQuality != "flac24bit" || got.FileNameFormat != "title" {
		t.Fatal(got)
	}
	// 完整GET返回一致模型，保持旧PUT客户端的读取契约。
	w := request(s, "GET", "/api/v1/settings", nil, nil)
	assertStatus(t, w, 200)
	var body map[string]any
	if json.Unmarshal(w.Body.Bytes(), &body) != nil || body["defaultQuality"] != "flac24bit" {
		t.Fatal(w.Body.String())
	}
}

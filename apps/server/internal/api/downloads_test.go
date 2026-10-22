package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"melora/internal/catalog"
	"melora/internal/model"
)

type downloadCatalogAction struct {
	beforeTrack func()
}

func (f *downloadCatalogAction) Do(r *http.Request) (*http.Response, error) {
	if r.URL.Path == "/api/song/detail" && f.beforeTrack != nil {
		action := f.beforeTrack
		f.beforeTrack = nil
		action()
	}
	return (&catalogFixture{}).Do(r)
}

func downloadSnapshotSetup(t *testing.T) (*Server, *recordingDownloads, *downloadCatalogAction) {
	t.Helper()
	s, _, _ := liveSetup(t, "")
	assertStatus(t, importRequest(t, s, "download-source.js", "// isolated source fixture", nil), http.StatusCreated)
	assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]any{
		"downloadRoot": s.cfg.DownloadRoot, "defaultQuality": "128k",
		"writeLyrics": false, "writeCover": false, "embedTags": false,
	}, nil), http.StatusOK)
	capture := &recordingDownloads{Downloads: s.downloads}
	s.downloads = capture
	fixture := &downloadCatalogAction{}
	s.live.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{"wy": catalog.NewWY(fixture)})
	return s, capture, fixture
}

func TestDownloadCreateSnapshotsSettingsAfterMetadataLookup(t *testing.T) {
	for _, explicit := range []bool{false, true} {
		t.Run(map[bool]string{false: "defaults", true: "explicit-overrides"}[explicit], func(t *testing.T) {
			s, capture, fixture := downloadSnapshotSetup(t)
			fixture.beforeTrack = func() {
				assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]any{
					"defaultQuality": "flac", "writeLyrics": true, "writeCover": true,
					"embedTags": true, "fileNameFormat": "artist-title",
				}, nil), http.StatusOK)
			}
			body := map[string]any{"trackId": "wy:123"}
			if explicit {
				body["quality"], body["writeLyrics"], body["writeCover"], body["embedTags"] = "128k", false, false, false
			}
			assertStatus(t, request(s, "POST", "/api/v1/downloads", body, nil), http.StatusCreated)
			if fixture.beforeTrack != nil {
				t.Fatal("metadata lookup did not exercise the concurrent setting change")
			}
			wantQuality, wantAssets := "flac", true
			if explicit {
				wantQuality, wantAssets = "128k", false
			}
			got := capture.created
			if got.Quality != wantQuality || got.WriteLyrics != wantAssets || got.WriteCover != wantAssets || got.EmbedTags != wantAssets || got.FileNameFormat != "artist-title" {
				t.Fatalf("download settings do not match the committed configuration plus explicit options: %+v", got)
			}
		})
	}
}

func TestDownloadCreateRechecksSourceAfterMetadataLookup(t *testing.T) {
	s, capture, fixture := downloadSnapshotSetup(t)
	fixture.beforeTrack = func() {
		if _, err := s.sources.Delete(s.sources.List().ActiveID); err != nil {
			t.Fatal(err)
		}
	}
	response := request(s, "POST", "/api/v1/downloads", map[string]string{"trackId": "wy:123"}, nil)
	assertStatus(t, response, http.StatusConflict)
	assertSourceRequiredCode(t, response.Body.Bytes())
	if capture.created.ID != "" {
		t.Fatal("download queued after source removal")
	}
}

type metadataStateDownloads struct{ Downloads }

func (d *metadataStateDownloads) List() []model.DownloadJob {
	return []model.DownloadJob{{ID: "metadata", State: "writing_metadata"}, {ID: "paused", State: "paused"}}
}
func TestDownloadFilterAcceptsWritingMetadataState(t *testing.T) {
	s, _, _ := setup(t, "")
	s.downloads = &metadataStateDownloads{Downloads: s.downloads}
	response := request(s, "GET", "/api/v1/downloads?state=writing_metadata", nil, nil)
	assertStatus(t, response, http.StatusOK)
	var jobs []model.DownloadJob
	if err := json.Unmarshal(response.Body.Bytes(), &jobs); err != nil {
		t.Fatal(err)
	}
	if len(jobs) != 1 || jobs[0].ID != "metadata" {
		t.Fatalf("wrong filtered jobs: %+v", jobs)
	}
	assertStatus(t, request(s, "GET", "/api/v1/downloads?state=writing_metadata_invalid", nil, nil), http.StatusBadRequest)
}

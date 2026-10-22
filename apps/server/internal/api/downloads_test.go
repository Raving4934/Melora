package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"melora/internal/model"
)

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

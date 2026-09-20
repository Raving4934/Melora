package api

import (
	"context"
	"encoding/json"
	"sync/atomic"
	"testing"

	"melora/internal/catalog"
	"melora/internal/model"
)

type sourceGateCatalog struct {
	catalog.Adapter
	trackCalls atomic.Int32
}

func (c *sourceGateCatalog) Track(context.Context, string) (model.Track, error) {
	c.trackCalls.Add(1)
	return model.Track{ID: "wy:123", ProviderID: "wy", Title: "should not be fetched"}, nil
}

func assertSourceRequiredCode(t *testing.T, body []byte) {
	t.Helper()
	var response struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if json.Unmarshal(body, &response) != nil || response.Error.Code != "lx_source_required" {
		t.Fatalf("unexpected no-source error body: %s", body)
	}
}

func TestLiveOnlineGatePrecedesCatalogForPlaybackAndDownload(t *testing.T) {
	s, _, _ := liveSetup(t, "")
	catalogue := &sourceGateCatalog{}
	s.live.Catalog = catalog.NewRegistry(map[string]catalog.Adapter{"wy": catalogue})

	playback := request(s, "GET", "/api/v1/tracks/wy:123/play-info", nil, nil)
	assertStatus(t, playback, 409)
	assertSourceRequiredCode(t, playback.Body.Bytes())

	download := request(s, "POST", "/api/v1/downloads", map[string]string{"trackId": "wy:123"}, nil)
	assertStatus(t, download, 409)
	assertSourceRequiredCode(t, download.Body.Bytes())

	if calls := catalogue.trackCalls.Load(); calls != 0 {
		t.Fatalf("no-source online operations fetched catalog metadata: %d", calls)
	}
}

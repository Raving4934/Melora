package api

import (
	"encoding/json"
	"net/http"
	"testing"

	"melora/internal/model"
)

func TestFeaturedCollectionsPreservesProviderRoundsAndDeduplicates(t *testing.T) {
	items := []model.Collection{
		{ID: "wy:1", ProviderID: "wy"},
		{ID: "wy:2", ProviderID: "wy"},
		{ID: "tx:1", ProviderID: "tx"},
		{ID: "wy:1", ProviderID: "wy"},
		{ID: "tx:2", ProviderID: "tx"},
	}
	got := featuredCollections(items)
	want := []string{"wy:1", "tx:1", "wy:2", "tx:2"}
	if len(got) != len(want) {
		t.Fatalf("got %d items, want %d", len(got), len(want))
	}
	for index := range want {
		if got[index].ID != want[index] {
			t.Fatalf("item %d = %q, want %q", index, got[index].ID, want[index])
		}
	}
}

func TestChartShowcaseReturnsOneBoundedBatchWithPreviews(t *testing.T) {
	s, _, _ := setup(t, "")
	w := request(s, http.MethodGet, "/api/v1/charts/featured?source=all&batch=99", nil, nil)
	assertStatus(t, w, http.StatusOK)
	var response chartShowcaseResponse
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.Total != 3 || response.Batch != 1 || response.Batches != 1 || len(response.Items) != 3 {
		t.Fatalf("unexpected showcase pagination: %+v", response)
	}
	for _, item := range response.Items {
		if len(item.Tracks) == 0 || len(item.Tracks) > chartShowcasePreview {
			t.Fatalf("preview was not populated or bounded: %+v", item)
		}
	}
	if response.UnavailableIDs != nil {
		t.Fatalf("demo showcase unexpectedly unavailable: %v", response.UnavailableIDs)
	}
}

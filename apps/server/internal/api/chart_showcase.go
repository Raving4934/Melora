package api

import (
	"errors"
	"net/http"
	"strconv"
	"sync"

	"melora/internal/catalog"
	"melora/internal/model"
)

const (
	chartShowcaseBatchSize = 4
	chartShowcasePreview   = 3
)

type chartShowcaseResponse struct {
	Items          []model.Collection `json:"items"`
	UnavailableIDs []string           `json:"unavailableIds,omitempty"`
	Total          int                `json:"total"`
	Batch          int                `json:"batch"`
	Batches        int                `json:"batches"`
}

// featuredCollections mirrors the UI's fair ordering: preserve first-seen provider order,
// then take one chart per provider per round.
func featuredCollections(items []model.Collection) []model.Collection {
	groups := make(map[string][]model.Collection)
	providers := make([]string, 0)
	seenProviders := make(map[string]bool)
	seenItems := make(map[string]bool)
	for _, item := range items {
		if item.ID == "" || seenItems[item.ID] {
			continue
		}
		seenItems[item.ID] = true
		if !seenProviders[item.ProviderID] {
			seenProviders[item.ProviderID] = true
			providers = append(providers, item.ProviderID)
		}
		groups[item.ProviderID] = append(groups[item.ProviderID], item)
	}
	out := make([]model.Collection, 0, len(seenItems))
	for row := 0; ; row++ {
		added := false
		for _, providerID := range providers {
			if row < len(groups[providerID]) {
				out = append(out, groups[providerID][row])
				added = true
			}
		}
		if !added {
			return out
		}
	}
}

func showcaseBatch(value string, total int) (int, int) {
	batches := max(1, (total+chartShowcaseBatchSize-1)/chartShowcaseBatchSize)
	batch, err := strconv.Atoi(value)
	if err != nil || batch < 1 {
		batch = 1
	}
	return min(batch, batches), batches
}

func (s *Server) chartShowcase(w http.ResponseWriter, r *http.Request) {
	var (
		items []model.Collection
		err   error
	)
	if s.live != nil {
		if !liveSource(w, r) {
			return
		}
		items, err = s.live.Catalog.ChartsFor(r.Context(), r.URL.Query().Get("source"))
		if _, partial := catalogPartial(err); err != nil && !partial {
			s.liveError(w, err)
			return
		}
	} else {
		if !s.source(w, r) {
			return
		}
		enabled, ok := s.enabled(w, r)
		if !ok {
			return
		}
		if enabled {
			items = s.demo.Charts()
		}
	}

	ordered := featuredCollections(items)
	batch, batches := showcaseBatch(r.URL.Query().Get("batch"), len(ordered))
	start := min((batch-1)*chartShowcaseBatchSize, len(ordered))
	end := min(start+chartShowcaseBatchSize, len(ordered))
	visible := append([]model.Collection(nil), ordered[start:end]...)
	unavailable := make([]bool, len(visible))

	var wg sync.WaitGroup
	for index := range visible {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			var detail model.Collection
			var detailErr error
			if s.live != nil {
				detail, detailErr = s.live.Catalog.Chart(r.Context(), visible[index].ID)
				if detailErr == nil {
					detail = s.live.EnrichCollection(detail)
				}
			} else {
				var exists bool
				detail, exists = s.demo.Chart(visible[index].ID)
				if !exists {
					detailErr = errMissingChart
				}
			}
			if detailErr != nil {
				unavailable[index] = true
				visible[index].Tracks = []model.Track{}
				return
			}
			if len(detail.Tracks) > chartShowcasePreview {
				detail.Tracks = detail.Tracks[:chartShowcasePreview]
			}
			visible[index] = detail
		}(index)
	}
	wg.Wait()

	unavailableIDs := make([]string, 0)
	for index, failed := range unavailable {
		if failed {
			unavailableIDs = append(unavailableIDs, visible[index].ID)
		}
	}
	response := chartShowcaseResponse{
		Items:          visible,
		UnavailableIDs: unavailableIDs,
		Total:          len(ordered),
		Batch:          batch,
		Batches:        batches,
	}
	s.writeCatalog(w, response, err)
}

// A private sentinel keeps best-effort demo failures out of the HTTP contract.
var errMissingChart = errors.New("chart missing")

func catalogPartial(err error) ([]string, bool) {
	return catalog.IsPartial(err)
}

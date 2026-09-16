package download

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"melora/internal/model"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func publicLookup(context.Context, string, string) ([]netip.Addr, error) {
	return []netip.Addr{netip.MustParseAddr("93.184.215.14")}, nil
}
func testTrack(id string) model.Track {
	return model.Track{ID: id, ProviderID: "licensed", Title: "测试歌曲", Artist: "演示艺术家", Qualities: []string{"standard", "lossless"}, CanDownload: true}
}
func fixedResolver(context.Context, model.Track, string) (string, error) {
	return "https://media.example.com/audio?token=SECRET-SIGNED-URL", nil
}
func audio(n int) []byte {
	b := bytes.Repeat([]byte{0x11}, n)
	copy(b, []byte("ID3\x04\x00\x00\x00\x00\x00\x00"))
	// ID3之后是MPEG Layer III帧头；ID3本身不能证明音频格式。
	if len(b) > 10 {
		copy(b[10:], []byte{0xff, 0xfb, 0x90, 0x00})
	}
	return b
}
func response(req *http.Request, code int, body []byte) *http.Response {
	return &http.Response{StatusCode: code, Header: http.Header{"Content-Type": []string{"audio/mpeg"}, "Etag": []string{`"v1"`}}, Body: io.NopCloser(bytes.NewReader(body)), ContentLength: int64(len(body)), Request: req}
}

type recordingStore struct {
	mu      sync.Mutex
	records []model.DownloadJob
	latest  map[string]model.DownloadJob
	reject  func(model.DownloadJob) bool
}

func (s *recordingStore) save(j model.DownloadJob) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.reject != nil && s.reject(j) {
		return fmt.Errorf("https://private.example/?token=PERSIST-SECRET")
	}
	if s.latest == nil {
		s.latest = make(map[string]model.DownloadJob)
	}
	s.latest[j.ID] = cloneJob(j)
	s.records = append(s.records, cloneJob(j))
	return nil
}
func (s *recordingStore) get(id string) model.DownloadJob {
	s.mu.Lock()
	defer s.mu.Unlock()
	return cloneJob(s.latest[id])
}
func (s *recordingStore) states() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []string
	for _, j := range s.records {
		out = append(out, j.State)
	}
	return out
}
func testManager(t *testing.T, root string, rt roundTripFunc, resolve Resolver, store *recordingStore, initial []model.DownloadJob, change func(*dependencies)) *Manager {
	t.Helper()
	if resolve == nil {
		resolve = fixedResolver
	}
	if store == nil {
		store = &recordingStore{}
	}
	deps := dependencies{lookup: publicLookup, transport: rt, limits: limits{maxBytes: 1 << 20, totalTime: 3 * time.Second, attempts: 3, backoff: time.Millisecond, progress: time.Millisecond}}
	if change != nil {
		change(&deps)
	}
	m, err := newManager(root, 1, resolve, store.save, initial, deps)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(m.Close)
	return m
}
func waitJob(t *testing.T, m *Manager, id, state string) model.DownloadJob {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		for _, j := range m.List() {
			if j.ID == id && j.State == state {
				return j
			}
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("did not reach %s: %+v", state, m.List())
	return model.DownloadJob{}
}
func waitIdle(t *testing.T, m *Manager, id string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		m.mu.Lock()
		running := m.jobs[id].running
		m.mu.Unlock()
		if !running {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("worker did not stop")
}
func createJob(t *testing.T, m *Manager, id string) model.DownloadJob {
	t.Helper()
	j, err := m.Create(testTrack(id), "standard")
	if err != nil {
		t.Fatal(err)
	}
	return j
}
func seedPartial(t *testing.T, root string, data []byte, total int64) model.DownloadJob {
	t.Helper()
	files, _, err := openStorage(root)
	if err != nil {
		t.Fatal(err)
	}
	defer files.Close()
	job := model.DownloadJob{ID: "restored-job", Track: testTrack("restored"), Quality: "standard", State: "downloading", BytesDone: 999, BytesTotal: total, CreatedAt: timestamp(), UpdatedAt: timestamp()}
	job.TargetPath = targetPath(root, job, ".mp3")
	if err := files.WriteFile(partName(job.ID), data, 0600); err != nil {
		t.Fatal(err)
	}
	raw, _ := fixedResolver(context.Background(), job.Track, job.Quality)
	resource, _ := url.Parse(raw)
	if err := saveMeta(files, job.ID, partialMeta{Version: 1, ETag: `"v1"`, ResourceHash: resourceHash(resource), Total: total, Extension: ".mp3"}); err != nil {
		t.Fatal(err)
	}
	return job
}
func readTarget(t *testing.T, j model.DownloadJob) []byte {
	t.Helper()
	b, err := os.ReadFile(j.TargetPath)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func requirePartAbsent(t *testing.T, root, id string) {
	t.Helper()
	for _, name := range []string{partName(id), metaName(id)} {
		if _, err := os.Lstat(filepath.Join(root, "Singles", name)); !os.IsNotExist(err) {
			t.Fatalf("partial artifact remains: %s, %v", name, err)
		}
	}
}

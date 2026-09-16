package api

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"melora/internal/config"
	"melora/internal/download"
	"melora/internal/model"
	"melora/internal/provider"
	"melora/internal/store"
)

const clearRecordURL = "/api/v1/downloads/clear-records"
const clearRecordOrigin = "http://127.0.0.1:18081" // 仅内存 httptest，不绑定或访问端口。

func clearAPIFixture(t *testing.T, token string) (*Server, *store.Store, *download.Manager) {
	t.Helper()
	root := t.TempDir()
	for _, name := range []string{"state", "web"} {
		if err := os.Mkdir(filepath.Join(root, name), 0700); err != nil {
			t.Fatal(err)
		}
	}
	db, err := store.Open(filepath.Join(root, "state", "records.db"))
	if err != nil {
		t.Fatal(err)
	}
	demo := provider.NewDemo()
	initial := []model.DownloadJob{{ID: "completed", State: "completed"}, {ID: "cancelled", State: "cancelled"}, {ID: "paused", State: "paused"}, {ID: "restored-failed", State: "failed"}}
	// 旧 failed 在 New 中按现有契约恢复为 paused，本接口必须保留该暂停记录。
	m, err := download.New("", 1, demo.Resolve, db.SaveDownload, initial)
	if err != nil {
		db.Close()
		t.Fatal(err)
	}
	cfg := config.Config{Addr: "127.0.0.1:18081", DataDir: filepath.Join(root, "state"), WebDir: filepath.Join(root, "web"), AuthToken: token}
	s, err := New(cfg, db, demo, m)
	if err != nil {
		m.Close()
		db.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close(); m.Close(); db.Close() })
	return s, db, m
}

func clearAPIRequest(s *Server, method, path, body string, cookie *http.Cookie, origin string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, clearRecordOrigin+path, strings.NewReader(body))
	if body != "" {
		r.Header.Set("Content-Type", "application/json")
	}
	if cookie != nil {
		r.AddCookie(cookie)
	}
	if origin != "" {
		r.Header.Set("Origin", origin)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}

func clearCounts(t *testing.T, w *httptest.ResponseRecorder, cleared, remaining int) {
	t.Helper()
	assertStatus(t, w, 200)
	var got map[string]int
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || !reflect.DeepEqual(got, map[string]int{"cleared": cleared, "remaining": remaining}) {
		t.Fatalf("invalid clear contract: %s (%v)", w.Body.String(), err)
	}
}

func TestDownloadClearRecordsInjectsManagerAndPersistsWithoutAuthorization(t *testing.T) {
	s, db, m := clearAPIFixture(t, "")
	if s.cfg.HasDownloadAuthorization() {
		t.Fatal("fixture unexpectedly authorized downloads")
	}
	updates, cancel := m.Updates()
	defer cancel()
	<-updates
	clearCounts(t, clearAPIRequest(s, "POST", clearRecordURL, "", nil, clearRecordOrigin), 2, 2)
	jobs := m.List()
	if len(jobs) != 2 || jobs[0].State != "paused" || jobs[1].State != "paused" {
		t.Fatalf("active/paused record cleared: %+v", jobs)
	}
	select {
	case event := <-updates:
		if !reflect.DeepEqual(event, jobs) {
			t.Fatal("wrong SSE snapshot")
		}
	default:
		t.Fatal("API bypassed manager SSE")
	}
	saved, err := db.Downloads(t.Context())
	if err != nil || len(saved) != 2 {
		t.Fatalf("DB clear not committed: %+v %v", saved, err)
	}
	clearCounts(t, clearAPIRequest(s, "POST", clearRecordURL, "{}", nil, clearRecordOrigin), 0, 2)
	s.Close()
	m.Close()
	initial, err := db.Downloads(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	restored, err := download.New("", 1, provider.NewDemo().Resolve, db.SaveDownload, initial)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	for _, j := range restored.List() {
		if j.ID == "completed" || j.ID == "cancelled" {
			t.Fatal("cleared records revived after manager restart")
		}
	}
}

func TestDownloadClearRecordsRejectsParametersAndWrongMethods(t *testing.T) {
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"GET", clearRecordURL, "", 405}, {"DELETE", clearRecordURL, "", 405},
		{"POST", clearRecordURL + "?path=%2Fnot-authorized", "", 400},
		{"POST", clearRecordURL, `{"path":"/not-authorized"}`, 400},
		{"POST", clearRecordURL, `{"ids":["paused"]}`, 400},
		{"POST", clearRecordURL, `{} {}`, 400},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			s, db, m := clearAPIFixture(t, "")
			before := m.List()
			assertStatus(t, clearAPIRequest(s, tc.method, tc.path, tc.body, nil, clearRecordOrigin), tc.status)
			saved, err := db.Downloads(t.Context())
			if err != nil || len(saved) != 4 || !reflect.DeepEqual(before, m.List()) {
				t.Fatal("invalid request cleared records")
			}
		})
	}
}

func TestDownloadClearRecordsUsesExistingAuthenticationAndCSRF(t *testing.T) {
	s, _, m := clearAPIFixture(t, "clear-test-secret-at-least-24-bytes")
	assertStatus(t, clearAPIRequest(s, "POST", clearRecordURL, "", nil, clearRecordOrigin), 401)
	login := clearAPIRequest(s, "POST", "/api/v1/auth/session", `{"token":"clear-test-secret-at-least-24-bytes"}`, nil, clearRecordOrigin)
	assertStatus(t, login, 200)
	cookies := login.Result().Cookies()
	if len(cookies) == 0 {
		t.Fatal("missing session cookie")
	}
	assertStatus(t, clearAPIRequest(s, "POST", clearRecordURL, "", cookies[0], "https://hostile.invalid"), 403)
	if len(m.List()) != 4 {
		t.Fatal("rejected request mutated records")
	}
	clearCounts(t, clearAPIRequest(s, "POST", clearRecordURL, "", cookies[0], clearRecordOrigin), 2, 2)
}

func TestDownloadClearRecordsUsesGatewayCSRFAndAdminIdentity(t *testing.T) {
	s, _, m := clearAPIFixture(t, "")
	s.cfg.GatewayAuth = "fnos-admin"
	s.cfg.SocketPath = filepath.Join(s.cfg.DataDir, "gateway.sock") // 仅用于可信连接身份比较，不创建 Socket。
	makeRequest := func(socket, admin, csrf bool) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", clearRecordOrigin+clearRecordURL, nil)
		if socket {
			r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.UnixAddr{Name: s.cfg.SocketPath, Net: "unix"}))
		}
		r.Header.Set("X-Trim-Userid", "1000")
		r.Header.Set("X-Trim-Username", "record-admin")
		role := "false"
		if admin {
			role = "true"
		}
		r.Header.Set("X-Trim-Isadmin", role)
		r.Header.Set("Origin", clearRecordOrigin)
		if csrf {
			r.Header.Set(csrfHeader, s.csrfToken("1000", clearRecordOrigin, time.Now().Add(csrfLifetime)))
		}
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		return w
	}
	assertStatus(t, makeRequest(false, true, true), 401)
	assertStatus(t, makeRequest(true, false, true), 403)
	assertStatus(t, makeRequest(true, true, false), 403)
	if len(m.List()) != 4 {
		t.Fatal("unauthorized gateway clear")
	}
	clearCounts(t, makeRequest(true, true, true), 2, 2)
}

type legacyRecordDownloads struct{ Downloads }
type rejectedRecordBinding struct{ Downloads }

func (*rejectedRecordBinding) SetRecordRemover(func(context.Context, []string) error) error {
	return errors.New("injected binding failure")
}

func TestDownloadClearRecordsOptionalCapabilityPreservesOldMocks(t *testing.T) {
	s, db, m := clearAPIFixture(t, "")
	for _, downloader := range []Downloads{nil, &legacyRecordDownloads{Downloads: m}} {
		compatible, err := New(s.cfg, db, provider.NewDemo(), downloader)
		if err != nil {
			t.Fatal("legacy mock no longer accepted", err)
		}
		assertStatus(t, clearAPIRequest(compatible, "POST", clearRecordURL, "", nil, clearRecordOrigin), 503)
		compatible.Close()
	}
	failed, err := New(s.cfg, db, provider.NewDemo(), &rejectedRecordBinding{Downloads: m})
	if err == nil {
		failed.Close()
		t.Fatal("optional remover binding failure ignored")
	}
	if len(m.List()) != 4 {
		t.Fatal("unavailable feature directly deleted DB records")
	}
}

func TestDownloadClearRecordsDatabaseFailureKeepsMemoryAndSSE(t *testing.T) {
	s, db, m := clearAPIFixture(t, "")
	before := m.List()
	updates, cancel := m.Updates()
	defer cancel()
	<-updates
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	w := clearAPIRequest(s, "POST", clearRecordURL, "", nil, clearRecordOrigin)
	assertStatus(t, w, 500)
	if !reflect.DeepEqual(before, m.List()) {
		t.Fatal("DB failure partially cleared memory")
	}
	if strings.Contains(w.Body.String(), s.cfg.DataDir) || strings.Contains(w.Body.String(), "sql:") {
		t.Fatal("persistence internals leaked")
	}
	select {
	case <-updates:
		t.Fatal("DB failure emitted clear event")
	default:
	}
	reopened, err := store.Open(filepath.Join(s.cfg.DataDir, "records.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	saved, err := reopened.Downloads(t.Context())
	if err != nil || len(saved) != 4 {
		t.Fatalf("failed clear changed durable records: %+v %v", saved, err)
	}
}

func TestDownloadClearRecordsClearsRealFailedJobAfterRootDisabled(t *testing.T) {
	s, db, old := clearAPIFixture(t, "")
	old.Close()
	root := t.TempDir()
	resolver := func(context.Context, model.Track, string) (string, error) { return "file:///not-accessed", nil } // URL 策略立即拒绝，不联网/不打开该路径。
	m, err := download.New(root, 1, resolver, db.SaveDownload, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	replacement, err := New(s.cfg, db, provider.NewDemo(), m)
	if err != nil {
		t.Fatal(err)
	}
	defer replacement.Close()
	j, err := m.Create(model.Track{ID: "failed-live", ProviderID: "fixture", Title: "record-only", Artist: "fixture", CanDownload: true, Qualities: []string{"standard"}}, "standard")
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		jobs := m.List()
		if len(jobs) == 1 && jobs[0].State == "failed" && m.Configure("", 1) == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("failed worker did not finish: %+v", jobs)
		}
		time.Sleep(time.Millisecond)
	}
	part := filepath.Join(root, "Singles", ".melora-"+j.ID+".part")
	before, err := os.Stat(part)
	if err != nil {
		t.Fatal(err)
	}
	clearCounts(t, clearAPIRequest(replacement, "POST", clearRecordURL, "", nil, clearRecordOrigin), 1, 0)
	after, err := os.Stat(part)
	if err != nil || !os.SameFile(before, after) || !before.ModTime().Equal(after.ModTime()) {
		t.Fatal("clearing failed record touched its hidden part")
	}
	w := clearAPIRequest(replacement, "GET", "/api/v1/downloads", "", nil, clearRecordOrigin)
	assertStatus(t, w, 200)
	if !bytes.Equal(bytes.TrimSpace(w.Body.Bytes()), []byte("[]")) {
		t.Fatal("cleared task still listed", w.Body.String())
	}
}

func TestDownloadClearRecordsSQLiteBatchFailureRollsBackThenRetries(t *testing.T) {
	s, db, m := clearAPIFixture(t, "")
	raw, err := sql.Open("sqlite", filepath.Join(s.cfg.DataDir, "records.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close()
	// Manager 顺序 cancelled -> completed；首条删除后故障，必须整批回滚。
	_, err = raw.ExecContext(t.Context(), `CREATE TRIGGER clear_record_fault BEFORE DELETE ON download_jobs WHEN OLD.id='completed' BEGIN SELECT RAISE(ABORT,'PRIVATE-DB-ERROR'); END`)
	if err != nil {
		t.Fatal(err)
	}
	before := m.List()
	updates, cancel := m.Updates()
	defer cancel()
	<-updates
	for range 2 {
		w := clearAPIRequest(s, "POST", clearRecordURL, "", nil, clearRecordOrigin)
		assertStatus(t, w, 500)
		saved, err := db.Downloads(t.Context())
		if err != nil || len(saved) != 4 || !reflect.DeepEqual(before, m.List()) {
			t.Fatalf("partial batch clear: %+v %v", saved, err)
		}
		if strings.Contains(w.Body.String(), "PRIVATE") {
			t.Fatal("raw SQLite failure leaked")
		}
		select {
		case <-updates:
			t.Fatal("rolled-back transaction published event")
		default:
		}
	}
	if _, err := raw.ExecContext(t.Context(), `DROP TRIGGER clear_record_fault`); err != nil {
		t.Fatal(err)
	}
	clearCounts(t, clearAPIRequest(s, "POST", clearRecordURL, "", nil, clearRecordOrigin), 2, 2)
	saved, err := db.Downloads(t.Context())
	if err != nil || len(saved) != 2 || len(m.List()) != 2 {
		t.Fatalf("retry after DB rollback failed: %+v %v", saved, err)
	}
}

func TestDownloadClearRecordsConcurrentRequestsAreIdempotent(t *testing.T) {
	s, db, m := clearAPIFixture(t, "")
	const requests = 12
	start := make(chan struct{})
	results := make(chan *httptest.ResponseRecorder, requests)
	for range requests {
		go func() { <-start; results <- clearAPIRequest(s, "POST", clearRecordURL, "", nil, clearRecordOrigin) }()
	}
	close(start)
	total := 0
	for range requests {
		w := <-results
		assertStatus(t, w, 200)
		var got struct{ Cleared, Remaining int }
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		if got.Cleared != 0 && got.Cleared != 2 || got.Remaining != 2 {
			t.Fatal("inconsistent concurrent counts", w.Body.String())
		}
		total += got.Cleared
	}
	saved, err := db.Downloads(t.Context())
	if total != 2 || len(m.List()) != 2 || err != nil || len(saved) != 2 {
		t.Fatalf("concurrent requests lost/duplicated removal: total=%d saved=%+v err=%v", total, saved, err)
	}
}

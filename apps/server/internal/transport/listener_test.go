package transport

import (
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func shortDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "melora-uds-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return dir
}
func TestUnixListenerRealHealthAndPermissions(t *testing.T) {
	socket := filepath.Join(shortDir(t), "app.sock")
	listener, err := Listen("127.0.0.1:0", socket)
	if err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(socket)
	if info.Mode().Perm() != 0660 {
		t.Fatalf("unsafe socket mode %v", info.Mode())
	}
	if _, err := Listen("127.0.0.1:0", socket); err == nil {
		t.Fatal("overwrote active service socket")
	}
	sawUnix := make(chan bool, 1)
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, ok := r.Context().Value(http.LocalAddrContextKey).(*net.UnixAddr)
		select {
		case sawUnix <- ok:
		default:
		}
		if r.URL.Path != "/app/melora/health" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"status":"ok","name":"Melora"}`))
	})}
	go server.Serve(listener)
	t.Cleanup(func() { server.Close() })
	if err := Healthcheck("127.0.0.1:1", socket, "/app/melora"); err != nil {
		t.Fatal(err)
	}
	if !<-sawUnix {
		t.Fatal("HTTP request did not originate from Unix transport")
	}
	server.Close()
	if _, err := os.Lstat(socket); !os.IsNotExist(err) {
		t.Fatal("socket was not cleaned after stop")
	}
}
func TestUnixSocketRefusesFilesAndRecoversOwnedStaleSocket(t *testing.T) {
	root := shortDir(t)
	socket := filepath.Join(root, "app.sock")
	data := []byte("preserve user file")
	if err := os.WriteFile(socket, data, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Listen("", socket); err == nil {
		t.Fatal("overwrote ordinary file")
	}
	actual, _ := os.ReadFile(socket)
	if string(actual) != string(data) {
		t.Fatal("file changed")
	}
	os.Remove(socket)
	target := filepath.Join(root, "target")
	os.WriteFile(target, data, 0600)
	os.Symlink(target, socket)
	if _, err := Listen("", socket); err == nil {
		t.Fatal("accepted symlink")
	}
	os.Remove(socket)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: socket, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	listener.SetUnlinkOnClose(false)
	listener.Close()
	next, err := Listen("", socket)
	if err != nil {
		t.Fatal("cannot recover stale socket", err)
	}
	next.Close()
}
func TestHealthcheckDoesNotAcceptLivingButUnreadyProcess(t *testing.T) {
	started := time.Now()
	if err := Healthcheck("127.0.0.1:1", filepath.Join(shortDir(t), "missing.sock"), "/app/melora"); err == nil {
		t.Fatal("missing listener is ready")
	}
	if time.Since(started) > 2*time.Second {
		t.Fatal("probe has no effective deadline")
	}
	listener, err := Listen("127.0.0.1:0", "")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("alive but not health JSON")) })}
	go server.Serve(listener)
	defer server.Close()
	if Healthcheck(listener.Addr().String(), "", "") == nil {
		t.Fatal("accepted non-Melora response")
	}
}

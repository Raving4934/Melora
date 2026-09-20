package api

import (
	"crypto/tls"
	"encoding/json"
	"melora/internal/model"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPlaybackUsesSavedDefaultQualityAndExplicitOverride(t *testing.T) {
	s, _, runner := liveSetup(t, "")
	assertStatus(t, importRequest(t, s, "test.js", "test", nil), 201)
	assertStatus(t, request(s, "PATCH", "/api/v1/settings", map[string]any{"defaultQuality": "flac"}, nil), 200)
	runner.media = "https://8.8.8.8/audio.flac"
	w := request(s, "GET", "/api/v1/tracks/wy:123/play", nil, nil)
	assertStatus(t, w, 200)
	var info model.PlayInfo
	if json.Unmarshal(w.Body.Bytes(), &info) != nil || info.Quality != "flac" {
		t.Fatal(w.Body.String())
	}
	w = request(s, "GET", "/api/v1/tracks/wy:123/play?quality=128k", nil, nil)
	assertStatus(t, w, 200)
	if json.Unmarshal(w.Body.Bytes(), &info) != nil || info.Quality != "128k" {
		t.Fatal(w.Body.String())
	}
}
func TestHTTPMediaWorksOnHTTPPageAndNotHTTPSPage(t *testing.T) {
	s, _, runner := liveSetup(t, "")
	assertStatus(t, importRequest(t, s, "http-media.js", "http-media", nil), 201)
	runner.media = "http://8.8.8.8/audio.mp3"
	assertStatus(t, request(s, "GET", "/api/v1/tracks/wy:123/play", nil, nil), 200)
	r := httptest.NewRequest("GET", "http://127.0.0.1:3780/api/v1/tracks/wy:123/play", nil)
	r.Header.Set(clientOriginHeader, "https://music.example.com")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	assertStatus(t, w, 502)
}
func TestPlaybackPageProtocolDoesNotTrustForwardedOrDowngradeTLS(t *testing.T) {
	s, _, _ := setup(t, "")
	r := httptest.NewRequest("GET", "http://127.0.0.1:3780/", nil)
	r.Header.Set("X-Forwarded-Proto", "https")
	if s.playbackPageScheme(r) != "http" {
		t.Fatal("trusted forwarded header")
	}
	r.TLS = &tls.ConnectionState{}
	r.Header.Set(clientOriginHeader, "http://music.example.com")
	if s.playbackPageScheme(r) != "https" {
		t.Fatal("TLS was downgraded")
	}
	r.TLS = nil
	s.cfg.GatewayAuth = "fnos-admin"
	r.Header.Del(clientOriginHeader)
	if s.playbackPageScheme(r) != "https" {
		t.Fatal("gateway internal transport treated as page protocol")
	}
	r.Header.Set(clientOriginHeader, "http://music.example.com")
	if s.playbackPageScheme(r) != "http" {
		t.Fatal("valid page hint ignored")
	}
	r.Header.Set("Origin", "https://music.example.com")
	if s.playbackPageScheme(r) != "https" {
		t.Fatal("conflicting page hint accepted")
	}
	r.Header = http.Header{clientOriginHeader: []string{"http://one.example", "http://two.example"}}
	if s.playbackPageScheme(r) != "https" {
		t.Fatal("duplicate page hint accepted")
	}
}

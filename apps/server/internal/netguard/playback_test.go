package netguard

import (
	"context"
	"strings"
	"testing"
)

func TestBrowserHTTPMediaIsScopedToHTTPPages(t *testing.T) {
	for _, scheme := range []string{"http", "https", ""} {
		err := ValidatePlaybackURL(t.Context(), "http://8.8.8.8/music.mp3?signature=SECRET", scheme)
		if (err == nil) != (scheme == "http") {
			t.Fatalf("page scheme %q: %v", scheme, err)
		}
		if err != nil && strings.Contains(err.Error(), "SECRET") {
			t.Fatal("sensitive URL in error")
		}
	}
	if ValidateURL(t.Context(), "http://8.8.8.8/music.mp3", Options{}) == nil {
		t.Fatal("default validator policy changed")
	}
	for _, raw := range []string{"http://127.0.0.1/a", "http://10.0.0.1/a", "http://169.254.169.254/a", "http://[::1]/a", "http://localhost/a", "http://user:pass@8.8.8.8/a", "http://8.8.8.8/a#fragment", "http://8.8.8.8:0/a", "file:///etc/passwd", "ftp://8.8.8.8/a"} {
		if ValidatePlaybackURL(t.Context(), raw, "http") == nil {
			t.Fatalf("unsafe media accepted: %s", raw)
		}
	}
	if err := ValidatePlaybackURL(t.Context(), "https://8.8.8.8/music.flac", "https"); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if ValidatePlaybackURL(ctx, "http://8.8.8.8/music.mp3", "http") == nil {
		t.Fatal("cancelled validation succeeded")
	}
}

func TestUserDownloadMediaAcceptsOnlyPublicHTTPOrHTTPS(t *testing.T) {
	for _, raw := range []string{"http://8.8.8.8/music.mp3", "https://8.8.8.8/music.flac"} {
		if err := ValidateDownloadURL(t.Context(), raw); err != nil {
			t.Fatal(err)
		}
	}
	for _, raw := range []string{"http://127.0.0.1/music.mp3", "http://192.168.0.2/music.flac", "http://user:secret@8.8.8.8/a", "file:///music.mp3"} {
		if ValidateDownloadURL(t.Context(), raw) == nil {
			t.Fatal("unsafe download URL accepted")
		}
	}
}

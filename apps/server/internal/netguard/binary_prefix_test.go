package netguard

import (
	"bytes"
	"errors"
	"io"
	"net/http"
	"testing"
)

type prefixBody struct {
	io.Reader
	read   int
	closed bool
}

func (b *prefixBody) Read(p []byte) (int, error) {
	n, err := b.Reader.Read(p)
	b.read += n
	return n, err
}
func (b *prefixBody) Close() error { b.closed = true; return nil }

func TestBinaryPrefixReadsOnlyRequestedBytes(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		length int64
	}{
		{"partial", http.StatusPartialContent, 64},
		{"range ignored", http.StatusOK, 80 << 20},
		{"chunked", http.StatusOK, -1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			body := &prefixBody{Reader: bytes.NewReader(bytes.Repeat([]byte{1}, 1024))}
			b := v10Broker(t, Options{AllowPublicHTTP: true}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				if r.Header.Get("Range") != "bytes=0-63" || r.Header.Get("Accept-Encoding") != "identity" {
					t.Fatal("bounded prefix request headers lost")
				}
				resp := response(r, tc.status, nil)
				resp.ContentLength, resp.Body = tc.length, body
				resp.Header.Set("Content-Type", "audio/flac")
				if tc.status == http.StatusPartialContent {
					resp.Header.Set("Content-Range", "bytes 0-63/83886080")
				}
				return resp, nil
			}))
			got, err := b.Do(t.Context(), Request{URL: "http://media.example.com/file", Binary: true, MaxResponseBytes: 64, Headers: map[string]string{"Range": "bytes=0-63"}})
			if err != nil || len(got.Body) != 64 || body.read != 64 || !body.closed || !got.Binary {
				t.Fatalf("status=%d bytes=%d read=%d closed=%v binary=%v err=%v", got.StatusCode, len(got.Body), body.read, body.closed, got.Binary, err)
			}
		})
	}
}

func TestBinaryPrefixRejectsUnboundedRanges(t *testing.T) {
	for _, tc := range []struct {
		name, method, value string
		binary              bool
		limit               int
	}{
		{"text", "GET", "bytes=0-63", false, 64},
		{"post", "POST", "bytes=0-63", true, 64},
		{"head", "HEAD", "bytes=0-63", true, 64},
		{"no bound", "GET", "bytes=0-63", true, 0},
		{"large", "GET", "bytes=0-8191", true, 8192},
		{"offset", "GET", "bytes=64-127", true, 64},
		{"suffix", "GET", "bytes=-64", true, 64},
		{"open ended", "GET", "bytes=0-", true, 64},
		{"multiple", "GET", "bytes=0-63,128-191", true, 64},
		{"mismatched bound", "GET", "bytes=0-63", true, 128},
	} {
		t.Run(tc.name, func(t *testing.T) {
			b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				t.Fatal("invalid prefix request reached transport")
				return nil, nil
			}))
			_, err := b.Do(t.Context(), Request{URL: "https://media.example.com/file", Method: tc.method, Binary: tc.binary, MaxResponseBytes: tc.limit, Headers: map[string]string{"Range": tc.value}})
			if !errors.Is(err, ErrPolicy) {
				t.Fatalf("got %v, want policy rejection", err)
			}
		})
	}
}

func TestBinaryPrefixRejectsCompressedResponseWithoutReading(t *testing.T) {
	body := &prefixBody{Reader: bytes.NewReader([]byte("compressed data"))}
	b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		resp := response(r, http.StatusPartialContent, nil)
		resp.Body = body
		resp.Header.Set("Content-Encoding", "gzip")
		return resp, nil
	}))
	_, err := b.Do(t.Context(), Request{URL: "https://media.example.com/file", Binary: true, MaxResponseBytes: 64, Headers: map[string]string{"Range": "bytes=0-63"}})
	if !errors.Is(err, ErrPolicy) || body.read != 0 || !body.closed {
		t.Fatalf("err=%v read=%d closed=%v", err, body.read, body.closed)
	}
}

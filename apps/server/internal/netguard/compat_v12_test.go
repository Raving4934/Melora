package netguard

import (
	"bytes"
	"compress/gzip"
	"compress/zlib"
	"errors"
	"io"
	"net/http"
	"testing"
)

func compressedV12(t *testing.T, codec string, plain []byte) []byte {
	t.Helper()
	var b bytes.Buffer
	var w io.WriteCloser
	if codec == "gzip" {
		w = gzip.NewWriter(&b)
	} else {
		w = zlib.NewWriter(&b)
	}
	_, _ = w.Write(plain)
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}
func TestV12BoundedCompressedMetadata(t *testing.T) {
	for _, codec := range []string{"gzip", "deflate", "x-gzip", "x-deflate"} {
		t.Run(codec, func(t *testing.T) {
			compression := "deflate"
			if codec == "gzip" || codec == "x-gzip" {
				compression = "gzip"
			}
			plain := []byte(`{"ok":true}`)
			b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				if r.Header.Get("Accept") != "*/*" {
					t.Error("LX default Accept missing")
				}
				resp := response(r, 200, compressedV12(t, compression, plain))
				resp.Header.Set("Content-Encoding", codec)
				return resp, nil
			}))
			got, err := b.Do(t.Context(), Request{URL: "https://metadata.example.com/"})
			if err != nil || !bytes.Equal(got.Body, plain) {
				t.Fatalf("compressed metadata: bytes=%d err=%v", len(got.Body), err)
			}
		})
	}
}
func TestV12CompressedMetadataStillBlocksBombMediaAndMalformed(t *testing.T) {
	for _, tc := range []struct {
		name, encoding string
		plain          []byte
		raw            bool
		want           error
	}{
		{"bomb", "gzip", bytes.Repeat([]byte("a"), MaxResponseBytes+1), false, ErrLimit},
		{"audio", "gzip", []byte("ID3\x04\x00\x00\x00\x00\x00\x00"), false, ErrMedia},
		{"playlist", "deflate", []byte("#EXTM3U\nfile"), false, ErrMedia},
		{"truncated", "gzip", []byte{31, 139, 8}, true, ErrPolicy},
		{"unknown", "br", []byte("anything"), true, ErrPolicy},
		{"stacked", "gzip, deflate", []byte("anything"), true, ErrPolicy},
	} {
		t.Run(tc.name, func(t *testing.T) {
			b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				raw := tc.plain
				if !tc.raw {
					raw = compressedV12(t, tc.encoding, raw)
				}
				resp := response(r, 200, raw)
				resp.Header.Set("Content-Encoding", tc.encoding)
				return resp, nil
			}))
			_, err := b.Do(t.Context(), Request{URL: "https://metadata.example.com/"})
			if !errors.Is(err, tc.want) {
				t.Fatalf("err=%v want=%v", err, tc.want)
			}
		})
	}
}

type unreadHeadV12 struct {
	t      *testing.T
	closed bool
}

func (b *unreadHeadV12) Read([]byte) (int, error) {
	b.t.Error("HEAD attempted to read representation body")
	return 0, io.EOF
}
func (b *unreadHeadV12) Close() error { b.closed = true; return nil }
func TestV12HEADChecksHeadersWithoutDownloadingMedia(t *testing.T) {
	body := &unreadHeadV12{t: t}
	b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		resp := response(r, 200, nil)
		resp.Header.Set("Content-Type", "audio/flac")
		resp.Header.Set("Content-Encoding", "gzip")
		resp.Header.Set("Content-Length", "99999999")
		resp.ContentLength = 99999999
		resp.Body = body
		return resp, nil
	}))
	got, err := b.Do(t.Context(), Request{URL: "https://metadata.example.com/", Method: "HEAD"})
	if err != nil || got.StatusCode != 200 || len(got.Body) != 0 || !body.closed || got.Headers["content-type"] != "audio/flac" {
		t.Fatalf("HEAD metadata rejected or body read: status=%d bytes=%d closed=%v err=%v", got.StatusCode, len(got.Body), body.closed, err)
	}
}

// 分行重复字段与同一字段的逗号链等价，不能仅解码首层后让压缩媒体绕过嗅探。
func TestV12RepeatedContentEncodingRejected(t *testing.T) {
	for _, codec := range []string{"gzip", "deflate"} {
		t.Run(codec, func(t *testing.T) {
			body := compressedV12(t, codec, compressedV12(t, codec, []byte("ID3\x04\x00\x00\x00\x00\x00\x00")))
			b := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
				resp := response(r, 200, body)
				resp.Header.Add("Content-Encoding", codec)
				resp.Header.Add("Content-Encoding", codec)
				return resp, nil
			}))
			_, err := b.Do(t.Context(), Request{URL: "https://metadata.example.com/"})
			if !errors.Is(err, ErrPolicy) {
				t.Fatalf("multiple encodings must fail closed: %v", err)
			}
		})
	}
}

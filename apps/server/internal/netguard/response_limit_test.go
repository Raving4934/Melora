package netguard

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"sync/atomic"
	"testing"
)

type limitedResponseBody struct {
	data   []byte
	read   atomic.Int32
	closed atomic.Bool
}

func (b *limitedResponseBody) Read(p []byte) (int, error) {
	if len(b.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, b.data)
	b.data = b.data[n:]
	b.read.Add(int32(n))
	return n, nil
}
func (b *limitedResponseBody) Close() error {
	b.closed.Store(true)
	return nil
}

func TestBrokerBinaryResponseUsesExplicitBoundWithoutReadingTheWholeBody(t *testing.T) {
	body := &limitedResponseBody{data: bytes.Repeat([]byte("x"), 1<<20)}
	broker := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Request: r, Header: http.Header{"Content-Type": []string{"audio/flac"}}, Body: body, ContentLength: -1}, nil
	}))
	response, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/flac", Binary: true, MaxResponseBytes: 64})
	if !errors.Is(err, ErrLimit) || len(response.Body) != 0 || body.read.Load() > 65 || !body.closed.Load() {
		t.Fatalf("binary response limit failed: err=%v read=%d closed=%v", err, body.read.Load(), body.closed.Load())
	}
}

func TestBrokerBinaryResponseAllowsBoundedMediaHeader(t *testing.T) {
	body := []byte("fLaC\x00\x00\x00\x22STREAMINFO")
	broker := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Request: r, Header: http.Header{"Content-Type": []string{"audio/flac"}}, Body: io.NopCloser(bytes.NewReader(body)), ContentLength: int64(len(body))}, nil
	}))
	response, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/flac", Binary: true, MaxResponseBytes: 64})
	if err != nil || !response.Binary || !bytes.Equal(response.Body, body) {
		t.Fatalf("bounded binary response rejected: body=%x binary=%v err=%v", response.Body, response.Binary, err)
	}
}

func TestBrokerNonBinaryMediaAndInvalidExplicitResponseBoundsStayBlocked(t *testing.T) {
	broker := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Request: r, Header: http.Header{"Content-Type": []string{"audio/flac"}}, Body: io.NopCloser(bytes.NewReader([]byte("fLaC"))), ContentLength: 4}, nil
	}))
	if _, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/flac", MaxResponseBytes: 64}); !errors.Is(err, ErrMedia) {
		t.Fatalf("non-binary media response was allowed: %v", err)
	}
	for _, limit := range []int{-1, MaxResponseBytes + 1} {
		if _, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/", MaxResponseBytes: limit}); !errors.Is(err, ErrLimit) {
			t.Fatalf("invalid explicit response limit %d returned %v", limit, err)
		}
	}
}

func TestBrokerExplicitJSONResponseBoundRejectsTruncation(t *testing.T) {
	body := &limitedResponseBody{data: bytes.Repeat([]byte("{"), 1024)}
	broker := v10Broker(t, Options{}, publicLookup, roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 200, Request: r, Header: http.Header{"Content-Type": []string{"application/json"}}, Body: body, ContentLength: -1}, nil
	}))
	if _, err := broker.Do(context.Background(), Request{URL: "https://metadata.example.com/json", MaxResponseBytes: 32}); !errors.Is(err, ErrLimit) || body.read.Load() > 33 || !body.closed.Load() {
		t.Fatalf("JSON truncation was not rejected safely: err=%v read=%d closed=%v", err, body.read.Load(), body.closed.Load())
	}
}

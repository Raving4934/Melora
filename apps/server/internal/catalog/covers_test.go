package catalog

import (
	"context"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCoverTargetAllowList(t *testing.T) {
	for _, tc := range []struct {
		name string
		raw  string
		want string
		ok   bool
	}{
		{"kuwo exact", "http://img1.kuwo.cn/star/albumcover/120/a.jpg", "https://img1.kuwo.cn/star/albumcover/120/a.jpg", true},
		{"kuwo subdomain", "https://kwimg3.kuwo.cn/star/upload/ecom/x.png?size=300", "https://kwimg3.kuwo.cn/star/upload/ecom/x.png?size=300", true},
		{"netease subdomain", "https://p1.music.126.net/a.jpg", "https://p1.music.126.net/a.jpg", true},
		{"qq host", "https://y.gtimg.cn/music/photo_new/T002R500x500M000x.jpg", "https://y.gtimg.cn/music/photo_new/T002R500x500M000x.jpg", true},
		{"kugou host", "http://imge.kugou.com/a.jpg", "https://imge.kugou.com/a.jpg", true},
		{"fragment stripped", "https://img2.kuwo.cn/a.jpg#frag", "https://img2.kuwo.cn/a.jpg", true},
		{"lookalike suffix", "https://evilmusic.126.net/a.jpg", "", false},
		{"lookalike prefix", "https://music.126.net.evil.example/a.jpg", "", false},
		{"other host", "https://evil.example/a.jpg", "", false},
		{"userinfo", "https://user@img1.kuwo.cn/a.jpg", "", false},
		{"port", "https://img1.kuwo.cn:8443/a.jpg", "", false},
		{"no scheme", "//img1.kuwo.cn/a.jpg", "", false},
		{"relative", "/covers/a.svg", "", false},
		{"javascript", "javascript:alert(1)", "", false},
		{"oversized", "https://img1.kuwo.cn/" + strings.Repeat("a", 2048), "", false},
		{"empty", "", "", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := CoverTarget(tc.raw)
			if ok != tc.ok || got != tc.want {
				t.Fatalf("CoverTarget(%q) = %q,%v want %q,%v", tc.raw, got, ok, tc.want, tc.ok)
			}
		})
	}
}

func TestCoverStoreCachesDeduplicatesAndValidates(t *testing.T) {
	clock := newFakeClock()
	var calls atomic.Int32
	payload := []byte("PNG-BYTES")
	store := NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
		calls.Add(1)
		return payload, "image/png", nil
	}, clock.now)

	first, mimeType, err := store.Get(t.Context(), "https://img1.kuwo.cn/a.png")
	if err != nil || mimeType != "image/png" || string(first) != "PNG-BYTES" {
		t.Fatalf("first get: %q %q %v", first, mimeType, err)
	}
	second, _, err := store.Get(t.Context(), "http://img1.kuwo.cn/a.png")
	if err != nil || string(second) != "PNG-BYTES" {
		t.Fatal("http target should normalize to the same cache entry", err)
	}
	if calls.Load() != 1 {
		t.Fatalf("cache miss hit upstream %d times", calls.Load())
	}
	clock.advance(coverCacheTTL + time.Second)
	if _, _, err := store.Get(t.Context(), "https://img1.kuwo.cn/a.png"); err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 2 {
		t.Fatalf("expired entry not refetched: %d", calls.Load())
	}

	for _, tc := range []struct {
		name string
		mime string
		body []byte
	}{
		{"html", "text/html", payload},
		{"empty", "image/png", nil},
		{"oversized", "image/png", make([]byte, coverBodyLimit+1)},
	} {
		broken := NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
			return tc.body, tc.mime, nil
		}, clock.now)
		if _, _, err := broken.Get(t.Context(), "https://img2.kuwo.cn/b.jpg"); !errors.Is(err, ErrCoverUnavailable) {
			t.Fatalf("%s: want ErrCoverUnavailable, got %v", tc.name, err)
		}
	}

	var refused atomic.Int32
	invalid := NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
		refused.Add(1)
		return payload, "image/png", nil
	}, clock.now)
	if _, _, err := invalid.Get(t.Context(), "https://evil.example/a.png"); !errors.Is(err, ErrCoverTarget) || refused.Load() != 0 {
		t.Fatal("invalid host must fail before any fetch")
	}
}

func TestCoverStoreDeduplicatesConcurrentRequests(t *testing.T) {
	var calls atomic.Int32
	started := make(chan struct{})
	release := make(chan struct{})
	store := NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
		if calls.Add(1) == 1 {
			close(started)
		}
		<-release
		return []byte("PNG"), "image/png", nil
	}, newFakeClock().now)

	var wg sync.WaitGroup
	results := make([][]byte, 2)
	errs := make([]error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			results[i], _, errs[i] = store.Get(context.Background(), "https://img3.kuwo.cn/c.png")
		}(i)
	}
	<-started
	// 给第二个请求加入 inflight 的时间窗口；共享调用对象后再释放上游。
	time.Sleep(20 * time.Millisecond)
	close(release)
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatalf("concurrent requests should share one fetch, calls=%d", calls.Load())
	}
	for i := 0; i < 2; i++ {
		if errs[i] != nil || string(results[i]) != "PNG" {
			t.Fatalf("waiter %d got %q %v", i, results[i], errs[i])
		}
	}
}

func TestCoverStoreRetriesWhenInflightOwnerIsCancelled(t *testing.T) {
	var calls atomic.Int32
	started := make(chan struct{})
	store := NewCoverStoreWith(func(ctx context.Context, _ string) ([]byte, string, error) {
		if calls.Add(1) == 1 {
			close(started)
			<-ctx.Done()
			return nil, "", ctx.Err()
		}
		return []byte("PNG"), "image/png", nil
	}, time.Now)

	ownerCtx, cancelOwner := context.WithCancel(context.Background())
	ownerDone := make(chan error, 1)
	go func() {
		_, _, err := store.Get(ownerCtx, "https://img1.kuwo.cn/cancelled.png")
		ownerDone <- err
	}()
	<-started

	waiterDone := make(chan error, 1)
	go func() {
		data, _, err := store.Get(context.Background(), "https://img1.kuwo.cn/cancelled.png")
		if err == nil && string(data) != "PNG" {
			err = errors.New("waiter received wrong image")
		}
		waiterDone <- err
	}()
	// 确保第二个调用已经有机会加入 inflight，再模拟首个浏览器请求断开。
	time.Sleep(20 * time.Millisecond)
	cancelOwner()
	if err := <-ownerDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("owner cancellation lost: %v", err)
	}
	if err := <-waiterDone; err != nil {
		t.Fatalf("live waiter should retry after owner cancellation: %v", err)
	}
	if calls.Load() != 2 {
		t.Fatalf("expected one cancelled fetch and one retry, got %d", calls.Load())
	}
}

func TestCoverStoreCloseIsIdempotent(t *testing.T) {
	store := NewCoverStoreWith(func(context.Context, string) ([]byte, string, error) {
		return nil, "", ErrCoverUnavailable
	}, time.Now)
	var closes atomic.Int32
	store.close = func() { closes.Add(1) }
	store.Close()
	store.Close()
	if closes.Load() != 1 {
		t.Fatalf("close called %d times", closes.Load())
	}
}

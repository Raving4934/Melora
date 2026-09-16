package catalog

import (
	"context"
	"errors"
	"io"
	"net/http"
	"sync"
	"testing"
	"time"

	"melora/internal/netguard"
)

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newFakeClock() *fakeClock { return &fakeClock{t: time.Date(2026, 9, 11, 12, 0, 0, 0, time.UTC)} }
func (c *fakeClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}
func (c *fakeClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

type stubBroker struct {
	mu      sync.Mutex
	calls   int
	respond func(netguard.Request) netguard.Response
}

func (s *stubBroker) Do(_ context.Context, request netguard.Request) (netguard.Response, error) {
	s.mu.Lock()
	s.calls++
	s.mu.Unlock()
	return s.respond(request), nil
}
func (s *stubBroker) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.calls
}

func catalogTestRequest(t *testing.T, raw string) *http.Request {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, raw, nil)
	if err != nil {
		t.Fatal(err)
	}
	return request
}
func readTestBody(t *testing.T, response *http.Response) string {
	t.Helper()
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	return string(body)
}

func TestHostLimiterBurstRefillAndCooldown(t *testing.T) {
	clock := newFakeClock()
	limiter := newHostLimiter(clock.now)
	for i := 0; i < int(catalogHostBurst); i++ {
		if ok, wait := limiter.take("search.kuwo.cn"); !ok {
			t.Fatalf("request %d should fit burst, wait=%v", i, wait)
		}
	}
	if ok, wait := limiter.take("search.kuwo.cn"); ok || wait <= 0 {
		t.Fatalf("burst exhausted should wait, ok=%v wait=%v", ok, wait)
	}
	clock.advance(250 * time.Millisecond)
	if ok, _ := limiter.take("search.kuwo.cn"); !ok {
		t.Fatal("token should refill after 250ms at 4/s")
	}
	limiter.block("search.kuwo.cn", 30*time.Second)
	if ok, wait := limiter.take("search.kuwo.cn"); ok || wait < 29*time.Second {
		t.Fatalf("cooldown not enforced, ok=%v wait=%v", ok, wait)
	}
	clock.advance(31 * time.Second)
	if ok, wait := limiter.take("search.kuwo.cn"); !ok {
		t.Fatalf("cooldown should expire, wait=%v", wait)
	}
}

func TestRetryAfterDelay(t *testing.T) {
	now := time.Date(2026, 9, 11, 12, 0, 0, 0, time.UTC)
	response := func(status int, retryAfter string) *http.Response {
		header := http.Header{}
		if retryAfter != "" {
			header.Set("Retry-After", retryAfter)
		}
		return &http.Response{StatusCode: status, Header: header}
	}
	if _, blocked := retryAfterDelay(response(200, ""), now); blocked {
		t.Fatal("200 must not cool down")
	}
	if delay, blocked := retryAfterDelay(response(503, ""), now); blocked {
		t.Fatalf("503 without Retry-After must not invent cooldown: %v", delay)
	}
	if delay, blocked := retryAfterDelay(response(429, ""), now); !blocked || delay != catalogDefaultCooldown {
		t.Fatalf("429 default cooldown %v", delay)
	}
	if delay, blocked := retryAfterDelay(response(429, "5"), now); !blocked || delay != 5*time.Second {
		t.Fatalf("seconds Retry-After %v", delay)
	}
	if delay, blocked := retryAfterDelay(response(503, now.Add(20*time.Second).Format(http.TimeFormat)), now); !blocked || delay <= 19*time.Second || delay > 21*time.Second {
		t.Fatalf("date Retry-After %v", delay)
	}
	if delay, blocked := retryAfterDelay(response(429, "99999"), now); !blocked || delay != catalogMaxCooldown {
		t.Fatalf("cooldown must cap %v", delay)
	}
	for _, seconds := range []string{"9223372037", "9223372036854775807", "18446744073709551616"} {
		if delay, blocked := retryAfterDelay(response(429, seconds), now); !blocked || delay != catalogMaxCooldown {
			t.Fatalf("overflowing Retry-After %s escaped cap: blocked=%v delay=%v", seconds, blocked, delay)
		}
	}
	if delay, blocked := retryAfterDelay(response(429, "600"), now); !blocked || delay != catalogMaxCooldown {
		t.Fatalf("exact cap boundary %v", delay)
	}
	if delay, blocked := retryAfterDelay(response(429, "garbage"), now); !blocked || delay != catalogDefaultCooldown {
		t.Fatalf("malformed header fallback %v", delay)
	}
}

func TestResponseCacheTTLContentTypeAndKey(t *testing.T) {
	clock := newFakeClock()
	cache := newResponseCache(clock.now)
	request := catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=x")
	key := cache.key(request)
	if key == "" {
		t.Fatal("GET should be cacheable")
	}
	if _, ok := cache.get(key); ok {
		t.Fatal("empty cache must miss")
	}
	jsonResponse := &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": []string{"application/json; charset=utf-8"}}}
	cache.put(key, jsonResponse, []byte(`{"ok":true}`))
	cached, ok := cache.get(key)
	if !ok || readTestBody(t, cached) != `{"ok":true}` || cached.StatusCode != 200 {
		t.Fatal("cached JSON not served")
	}
	clock.advance(catalogCacheTTL + time.Second)
	if _, ok := cache.get(key); ok {
		t.Fatal("expired entry served")
	}
	htmlResponse := &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": []string{"text/html"}}}
	cache.put(key, htmlResponse, []byte("<html>"))
	if _, ok := cache.get(key); ok {
		t.Fatal("non-JSON must not be cached")
	}
	cache.put(key, jsonResponse, nil)
	if _, ok := cache.get(key); ok {
		t.Fatal("empty body must not be cached")
	}
	other := catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=y")
	if cache.key(other) == key {
		t.Fatal("different query must not share cache key")
	}
	post, _ := http.NewRequest(http.MethodPost, "https://search.kuwo.cn/r.s", nil)
	if cache.key(post) != "" {
		t.Fatal("POST must not be cached")
	}
}

func TestGuardedClientCachesRepeatedRequests(t *testing.T) {
	clock := newFakeClock()
	stub := &stubBroker{respond: func(netguard.Request) netguard.Response {
		return netguard.Response{StatusCode: 200, StatusMessage: "OK", Headers: map[string]string{"Content-Type": "application/json"}, Body: []byte(`{"result":"ok"}`)}
	}}
	client := &guardedClient{broker: stub, limits: newHostLimiter(clock.now), cache: newResponseCache(clock.now)}
	first, err := client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=x"))
	if err != nil {
		t.Fatal(err)
	}
	if body := readTestBody(t, first); body != `{"result":"ok"}` {
		t.Fatalf("body %q", body)
	}
	second, err := client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=x"))
	if err != nil {
		t.Fatal(err)
	}
	if body := readTestBody(t, second); body != `{"result":"ok"}` || stub.count() != 1 {
		t.Fatalf("second request served from cache? calls=%d body=%q", stub.count(), body)
	}
	if _, err := client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=y")); err != nil {
		t.Fatal(err)
	}
	if stub.count() != 2 {
		t.Fatalf("different query must hit upstream, calls=%d", stub.count())
	}
}

func TestGuardedClientHonorsRetryAfterCooldown(t *testing.T) {
	clock := newFakeClock()
	stub := &stubBroker{respond: func(netguard.Request) netguard.Response {
		return netguard.Response{StatusCode: http.StatusTooManyRequests, Headers: map[string]string{"Retry-After": "30"}, Body: []byte("{}")}
	}}
	client := &guardedClient{broker: stub, limits: newHostLimiter(clock.now), cache: newResponseCache(clock.now)}
	first, err := client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=x"))
	if err != nil || first.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("first 429 should reach caller: %v %+v", err, first)
	}
	_, err = client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=x"))
	var coded interface{ CatalogIssueCode() string }
	if !errors.Is(err, ErrUnavailable) || !errors.As(err, &coded) || coded.CatalogIssueCode() != "rate_limited" {
		t.Fatalf("cooldown error %v", err)
	}
	if stub.count() != 1 {
		t.Fatalf("cooldown must not reach upstream, calls=%d", stub.count())
	}
	clock.advance(31 * time.Second)
	if _, err := client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=x")); err != nil {
		t.Fatal(err)
	}
	if stub.count() != 2 {
		t.Fatalf("cooldown should expire, calls=%d", stub.count())
	}
}

func TestGuardedClientLocalBurstLimitStopsUpstreamStorm(t *testing.T) {
	clock := newFakeClock()
	stub := &stubBroker{respond: func(netguard.Request) netguard.Response {
		return netguard.Response{StatusCode: 200, Headers: map[string]string{"Content-Type": "application/json"}, Body: []byte(`{}`)}
	}}
	client := &guardedClient{broker: stub, limits: newHostLimiter(clock.now), cache: newResponseCache(clock.now)}
	for i := 0; i < int(catalogHostBurst); i++ {
		if _, err := client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all="+string(rune('a'+i)))); err != nil {
			t.Fatalf("request %d inside burst failed: %v", i, err)
		}
	}
	_, err := client.Do(catalogTestRequest(t, "https://search.kuwo.cn/r.s?all=z"))
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("burst limit must fail safely, got %v", err)
	}
	if stub.count() != int(catalogHostBurst) {
		t.Fatalf("rate limited request reached upstream, calls=%d", stub.count())
	}
}

func TestGuardedClientRejectsBeforeBroker(t *testing.T) {
	stub := &stubBroker{respond: func(netguard.Request) netguard.Response {
		t.Error("rejected request reached broker")
		return netguard.Response{}
	}}
	client := &guardedClient{broker: stub, limits: newHostLimiter(nil), cache: newResponseCache(nil)}
	requests := []*http.Request{
		catalogTestRequest(t, "http://search.kuwo.cn/r.s"),
		catalogTestRequest(t, "https://evil.example/r.s"),
	}
	post, _ := http.NewRequest(http.MethodPost, "https://search.kuwo.cn/r.s", nil)
	requests = append(requests, post)
	for _, request := range requests {
		if response, err := client.Do(request); !errors.Is(err, ErrUnavailable) || response != nil {
			t.Fatalf("request %s must be rejected, got %v", request.URL, err)
		}
	}
	if stub.count() != 0 {
		t.Fatalf("broker should not be called, calls=%d", stub.count())
	}
}

func TestCatalogRequestPreservesRateLimitAndFlattensOthers(t *testing.T) {
	limited := kwTestDoer(func(*http.Request) (*http.Response, error) { return nil, errCatalogRateLimited })
	_, err := catalogRequest(t.Context(), limited, http.MethodGet, "https://search.kuwo.cn/r.s", nil, nil)
	var coded interface{ CatalogIssueCode() string }
	if !errors.Is(err, ErrUnavailable) || !errors.As(err, &coded) || coded.CatalogIssueCode() != "rate_limited" {
		t.Fatalf("rate limit code lost: %v", err)
	}
	generic := kwTestDoer(func(*http.Request) (*http.Response, error) { return nil, errors.New("boom") })
	if _, err := catalogRequest(t.Context(), generic, http.MethodGet, "https://search.kuwo.cn/r.s", nil, nil); err != ErrUnavailable {
		t.Fatalf("generic transport error must flatten to ErrUnavailable, got %v", err)
	}
}

func TestSearchAdaptersPreserveRateLimitCode(t *testing.T) {
	limit := func(*http.Request) (*http.Response, error) { return nil, errCatalogRateLimited }
	if _, err := NewKW(kwTestDoer(limit)).Search(t.Context(), "测试", "track", 1); catalogIssueCode(err) != "rate_limited" {
		t.Fatalf("KW lost rate_limited: %v", err)
	}
	if _, err := NewKG(kgTestDoer(limit)).Search(t.Context(), "测试", "track", 1); catalogIssueCode(err) != "rate_limited" {
		t.Fatalf("KG lost rate_limited: %v", err)
	}
	if _, err := NewTX(txTestDoer(limit)).Search(t.Context(), "测试", "track", 1); catalogIssueCode(err) != "rate_limited" {
		t.Fatalf("TX lost rate_limited: %v", err)
	}
}

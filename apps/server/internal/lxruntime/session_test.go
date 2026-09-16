package lxruntime

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/netguard"
)

const sessionCounterScript = `
const boot = lx.utils.crypto.randomBytes(16).toString('hex');
let calls = 0;
lx.on(lx.EVENT_NAMES.request, async ({info}) => {
  info = info || {};
  if (info.fail) { calls = 999; throw new Error('SCRIPT-SECRET-MUST-NOT-LEAK'); }
  if (info.loop) { while (true) {} }
  if (info.wait) await new Promise(resolve => setTimeout(resolve, info.wait));
  return {boot, calls: ++calls};
});
` + initScript

type sessionCounter struct {
	Boot  string `json:"boot"`
	Calls int    `json:"calls"`
}

func sessionCall(t *testing.T, p *Pool, code string, opts Options) sessionCounter {
	t.Helper()
	value, err := p.Invoke(context.Background(), code, "wy", "musicUrl", nil, opts)
	if err != nil {
		t.Fatalf("session invoke: %v", err)
	}
	var result sessionCounter
	if json.Unmarshal(value, &result) != nil || result.Boot == "" {
		t.Fatal("invalid synthetic session result")
	}
	return result
}
func TestSessionInspectInvokeRetainsInitializationAndState(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	code := strings.Replace(sessionCounterScript, "name:'WY'", "name:boot", 1)
	descriptor, err := p.Inspect(context.Background(), code, Options{})
	if err != nil {
		t.Fatal(err)
	}
	first := sessionCall(t, p, code, Options{})
	if descriptor.Sources["wy"].Name != first.Boot {
		t.Fatal("initial Inspect heap was not reused by Invoke")
	}
	if _, err := p.Inspect(context.Background(), code, Options{}); err != nil {
		t.Fatal(err)
	}
	second := sessionCall(t, p, code, Options{})
	if first.Boot != second.Boot || first.Calls != 1 || second.Calls != 2 {
		t.Fatal("Inspect/Invoke reinitialized the same script instead of preserving VM state")
	}
}
func TestSessionOptionsCodeAndPoolIsolation(t *testing.T) {
	p, other := NewPool(), NewPool()
	t.Cleanup(func() { _ = p.Close(); _ = other.Close() })
	a := sessionCall(t, p, sessionCounterScript, Options{})
	b := sessionCall(t, p, sessionCounterScript, Options{AllowPublicHTTP: true})
	if a.Boot == b.Boot {
		t.Fatal("network options share JS state")
	}
	if got := sessionCall(t, p, sessionCounterScript, Options{}); got.Boot != a.Boot || got.Calls != 2 {
		t.Fatal("same key not reused")
	}
	if got := sessionCall(t, other, sessionCounterScript, Options{}); got.Boot == a.Boot || got.Calls != 1 {
		t.Fatal("different owners share JS state")
	}
	if got := sessionCall(t, p, sessionCounterScript+"\n// different code", Options{}); got.Boot == a.Boot || got.Calls != 1 {
		t.Fatal("different scripts share JS state")
	}
}
func TestSessionConcurrentCallsSerializeInOneVM(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	const count = 16
	var wg sync.WaitGroup
	results := make(chan sessionCounter, count)
	errorsCh := make(chan error, count)
	for range count {
		wg.Add(1)
		go func() {
			defer wg.Done()
			value, err := p.Invoke(context.Background(), sessionCounterScript, "wy", "musicUrl", map[string]any{"wait": 2}, Options{})
			if err != nil {
				errorsCh <- err
				return
			}
			var result sessionCounter
			if err := json.Unmarshal(value, &result); err != nil {
				errorsCh <- err
				return
			}
			results <- result
		}()
	}
	wg.Wait()
	close(results)
	close(errorsCh)
	for err := range errorsCh {
		t.Fatal(err)
	}
	seen := map[int]bool{}
	boot := ""
	for result := range results {
		if boot == "" {
			boot = result.Boot
		}
		if result.Boot != boot || seen[result.Calls] {
			t.Fatal("parallel calls lost shared serial state")
		}
		seen[result.Calls] = true
	}
	if len(seen) != count {
		t.Fatal("missing invocation")
	}
}
func TestSessionFailureAndCancellationReinitialize(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	first := sessionCall(t, p, sessionCounterScript, Options{})
	if _, err := p.Invoke(context.Background(), sessionCounterScript, "wy", "musicUrl", map[string]any{"fail": true}, Options{}); !errors.Is(err, ErrScript) {
		t.Fatalf("script failure: %v", err)
	}
	second := sessionCall(t, p, sessionCounterScript, Options{})
	if first.Boot == second.Boot || second.Calls != 1 {
		t.Fatal("failed VM was reused")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
	defer cancel()
	if _, err := p.Invoke(ctx, sessionCounterScript, "wy", "musicUrl", map[string]any{"loop": true}, Options{}); !errors.Is(err, ErrTimeout) {
		t.Fatalf("cancel: %v", err)
	}
	third := sessionCall(t, p, sessionCounterScript, Options{})
	if second.Boot == third.Boot || third.Calls != 1 {
		t.Fatal("interrupted VM was reused")
	}
}
func TestSessionCloseIsIdempotentAndRejectsFurtherCalls(t *testing.T) {
	p := NewPool()
	sessionCall(t, p, sessionCounterScript, Options{})
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := p.Invoke(context.Background(), sessionCounterScript, "wy", "musicUrl", nil, Options{}); !errors.Is(err, ErrClosed) {
		t.Fatalf("closed pool accepts invoke: %v", err)
	}
	if len(workerSlots) != 0 {
		t.Fatal("execution slot leaked after Close")
	}
}

// inited 只声明 SDK 能力，不等价于后台 config Promise 已 settle。
func TestSessionInspectPendingConfigDoesNotPoisonNextInvoke(t *testing.T) {
	for _, kind := range []string{"timer", "network"} {
		t.Run(kind, func(t *testing.T) {
			p := NewPool()
			t.Cleanup(func() { _ = p.Close() })
			config := `new Promise(resolve => setTimeout(()=>resolve('ready'),15))`
			if kind == "network" {
				config = `new Promise((resolve,reject)=>lx.request('https://metadata.example.test/config',{},(err,resp,body)=>err?reject(err):resolve(body)))`
				p.newBroker = func(Options) (metadataBroker, error) {
					return &fakeBroker{fn: func(ctx context.Context, _ netguard.Request) (netguard.Response, error) {
						select {
						case <-time.After(15 * time.Millisecond):
							return netguard.Response{Body: []byte(`"ready"`)}, nil
						case <-ctx.Done():
							return netguard.Response{}, ctx.Err()
						}
					}}, nil
				}
			}
			code := initScript + `;const config=` + config + `;lx.on(lx.EVENT_NAMES.request,async()=>await config);`
			if _, err := p.Inspect(context.Background(), code, Options{}); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
			defer cancel()
			value, err := p.Invoke(ctx, code, "wy", "musicUrl", nil, Options{})
			if err != nil || string(value) != `"ready"` {
				t.Fatalf("Inspect kept an abandoned config Promise: bytes=%d error=%v", len(value), err)
			}
		})
	}
}

type countedSessionBroker struct {
	calls  atomic.Int32
	closed atomic.Int32
	fn     func(context.Context, netguard.Request) (netguard.Response, error)
}

func (b *countedSessionBroker) Do(ctx context.Context, r netguard.Request) (netguard.Response, error) {
	if b.calls.Add(1) > netguard.MaxRequests {
		return netguard.Response{}, netguard.ErrLimit
	}
	if b.fn != nil {
		return b.fn(ctx, r)
	}
	return netguard.Response{Body: []byte(`"ok"`)}, nil
}
func (b *countedSessionBroker) Close() { b.closed.Add(1) }
func poolProcesses(p *Pool) []*sessionProcess {
	p.mu.Lock()
	defer p.mu.Unlock()
	var processes []*sessionProcess
	for _, entry := range p.entries {
		if entry.process != nil {
			processes = append(processes, entry.process)
		}
	}
	return processes
}
func waitSession(t *testing.T, ready func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !ready() {
		if time.Now().After(deadline) {
			t.Fatal("session cleanup did not finish")
		}
		time.Sleep(2 * time.Millisecond)
	}
}
func TestSessionRequestBudgetsResetAcrossInspectAndMoreThanTwelveInvokes(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	var brokers []*countedSessionBroker
	p.newBroker = func(Options) (metadataBroker, error) {
		b := &countedSessionBroker{}
		brokers = append(brokers, b)
		return b, nil
	}
	code := `
const boot=lx.utils.crypto.randomBytes(16).toString('hex'); let calls=0;
const fetchMeta=()=>new Promise((resolve,reject)=>lx.request('https://metadata.example.test/config',{},(err,resp,body)=>err?reject(err):resolve(body)));
lx.on(lx.EVENT_NAMES.request,async()=>{for(let i=0;i<12;i++)await fetchMeta(); return {boot,calls:++calls};});
(async()=>{for(let i=0;i<12;i++)await fetchMeta(); ` + initScript + `})();`
	if _, err := p.Inspect(context.Background(), code, Options{}); err != nil {
		t.Fatal(err)
	}
	boot := ""
	for i := 1; i <= 16; i++ {
		got := sessionCall(t, p, code, Options{})
		if boot == "" {
			boot = got.Boot
		}
		if got.Boot != boot || got.Calls != i {
			t.Fatalf("budget reset lost VM at call %d", i)
		}
	}
	if len(brokers) != 17 {
		t.Fatalf("expected a fresh Broker for each operation, got %d", len(brokers))
	}
	for _, b := range brokers {
		if b.calls.Load() != 12 || b.closed.Load() != 1 {
			t.Fatalf("Broker budget/Close not scoped to operation: %d/%d", b.calls.Load(), b.closed.Load())
		}
	}
}
func TestSessionPerCallRequestLimitStillFailsAndRetires(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	p.newBroker = func(Options) (metadataBroker, error) { return &countedSessionBroker{}, nil }
	code := invokeScript(`for(let i=0;i<13;i++) await new Promise(resolve=>lx.request('https://metadata.example.test/config',{},()=>resolve())); return 'unexpected';`)
	if _, err := p.Invoke(context.Background(), code, "wy", "musicUrl", nil, Options{}); !errors.Is(err, ErrLimit) {
		t.Fatalf("per-call request limit weakened: %v", err)
	}
	if len(poolProcesses(p)) != 0 {
		t.Fatal("over-budget process retained")
	}
}
func TestSessionTimerIDsAndTurnBudgetResetWithoutIDReuse(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	code := `const boot=lx.utils.crypto.randomBytes(16).toString('hex'); let calls=0,oldTimer=0;
` + invokeScript(`await Promise.all(Array.from({length:30},()=>new Promise(resolve=>{
 const id=setTimeout(resolve,0);clearTimeout(oldTimer);oldTimer=0;
})));oldTimer=setTimeout(()=>{},0);clearTimeout(oldTimer);return {boot,calls:++calls};`)
	boot := ""
	for i := 1; i <= 12; i++ {
		got := sessionCall(t, p, code, Options{})
		if boot == "" {
			boot = got.Boot
		}
		if got.Boot != boot || got.Calls != i {
			t.Fatal("timer/turn budget was cumulative or old timer cleared a new ID")
		}
	}
}
func TestSessionCompletedAbortCannotCancelNextOperationRequest(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	p.newBroker = func(Options) (metadataBroker, error) { return &countedSessionBroker{}, nil }
	code := `let oldAbort=()=>{}; ` + invokeScript(`let nextAbort;
 const value=await new Promise((resolve,reject)=>{nextAbort=lx.request('https://metadata.example.test/config',{},(err,resp,body)=>err?reject(err):resolve(body));oldAbort();});
 oldAbort=nextAbort; return value;`)
	for range 3 {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		got, err := p.Invoke(ctx, code, "wy", "musicUrl", nil, Options{})
		cancel()
		if err != nil || string(got) != `"ok"` {
			t.Fatalf("old abort hit a reused request ID: %v", err)
		}
	}
}
func TestSessionDirtyResultCancelsLateNetworkAndRetires(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	started, canceled := make(chan struct{}), make(chan struct{})
	p.newBroker = func(Options) (metadataBroker, error) {
		return &countedSessionBroker{fn: func(ctx context.Context, _ netguard.Request) (netguard.Response, error) {
			close(started)
			<-ctx.Done()
			close(canceled)
			return netguard.Response{}, ctx.Err()
		}}, nil
	}
	code := invokeScript(`lx.request('https://metadata.example.test/late',{},()=>{throw new Error('OLD-PRIVATE-CALLBACK')});await new Promise(r=>setTimeout(r,10));return 'done';`)
	got, err := p.Invoke(context.Background(), code, "wy", "musicUrl", nil, Options{})
	if err != nil || string(got) != `"done"` {
		t.Fatalf("dirty result lost: %v", err)
	}
	select {
	case <-started:
	default:
		t.Fatal("request fixture did not start")
	}
	select {
	case <-canceled:
	default:
		t.Fatal("late request not canceled before returning")
	}
	if len(poolProcesses(p)) != 0 {
		t.Fatal("canceled pending promise worker was cached")
	}
}
func TestSessionStaleResponseIgnoredAndWrongCallIDRejected(t *testing.T) {
	process, err := startSessionProcess(func() {})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(process.close)
	broker := &countedSessionBroker{}
	input := invocation{Code: sessionCounterScript, Platform: "wy", Action: "musicUrl", Info: json.RawMessage(`{}`)}
	if _, _, err := process.call(context.Background(), input, broker); err != nil {
		t.Fatal(err)
	}
	if err := writeFrame(process.input, message{Kind: "response", CallID: 1, ID: 1, Error: "network"}); err != nil {
		t.Fatal(err)
	}
	value, retire, err := process.call(context.Background(), input, broker)
	if err != nil || retire || !strings.Contains(string(value), `"calls":2`) {
		t.Fatalf("old response corrupted current invocation: %v", err)
	}
	var stream bytes.Buffer
	_ = writeFrame(&stream, message{Kind: "result", CallID: 99, Result: json.RawMessage(`"wrong"`)})
	if _, _, err := exchangeCall(context.Background(), io.Discard, &stream, broker, 2); !errors.Is(err, ErrProtocol) {
		t.Fatalf("wrong invocation accepted: %v", err)
	}
}
func TestSessionLRUIdleAgeAndCallEvictionReapRealWorkers(t *testing.T) {
	for _, kind := range []string{"capacity", "idle", "age", "calls"} {
		t.Run(kind, func(t *testing.T) {
			limits := defaultPoolLimits
			switch kind {
			case "capacity":
				limits.capacity = 1
			case "idle":
				limits.idle = 35 * time.Millisecond
			case "age":
				limits.age = 35 * time.Millisecond
			case "calls":
				limits.calls = 1
			}
			p := newPool(limits)
			t.Cleanup(func() { _ = p.Close() })
			first := sessionCall(t, p, sessionCounterScript, Options{})
			processes := poolProcesses(p)
			if kind == "capacity" {
				sessionCall(t, p, sessionCounterScript+"\n// eviction", Options{})
			}
			if kind == "idle" || kind == "age" {
				waitSession(t, func() bool { return len(poolProcesses(p)) == 0 })
			}
			for _, process := range processes {
				select {
				case <-process.exited:
				default:
					t.Fatal("evicted child not reaped")
				}
			}
			second := sessionCall(t, p, sessionCounterScript, Options{})
			if second.Boot == first.Boot || second.Calls != 1 {
				t.Fatal("evicted state returned")
			}
		})
	}
}
func TestSessionCanceledWaiterDoesNotPoisonActiveCall(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	started, finish := make(chan struct{}), make(chan struct{})
	p.newBroker = func(Options) (metadataBroker, error) {
		return &countedSessionBroker{fn: func(ctx context.Context, _ netguard.Request) (netguard.Response, error) {
			close(started)
			select {
			case <-finish:
				return netguard.Response{Body: []byte(`"ok"`)}, nil
			case <-ctx.Done():
				return netguard.Response{}, ctx.Err()
			}
		}}, nil
	}
	code := invokeScript(`return await new Promise((resolve,reject)=>lx.request('https://metadata.example.test/config',{},(err,resp,body)=>err?reject(err):resolve(body)));`)
	first := make(chan error, 1)
	go func() { _, err := p.Invoke(context.Background(), code, "wy", "musicUrl", nil, Options{}); first <- err }()
	<-started
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := p.Invoke(ctx, code, "wy", "musicUrl", nil, Options{}); !errors.Is(err, ErrTimeout) {
		t.Fatalf("waiting call: %v", err)
	}
	close(finish)
	if err := <-first; err != nil {
		t.Fatalf("waiting cancellation killed another call: %v", err)
	}
	if len(poolProcesses(p)) != 1 {
		t.Fatal("healthy active worker discarded")
	}
}
func TestSessionCloseCancelsActiveBrokerAndWaitersWithoutLeaks(t *testing.T) {
	p := NewPool()
	started, canceled := make(chan struct{}), make(chan struct{})
	p.newBroker = func(Options) (metadataBroker, error) {
		return &countedSessionBroker{fn: func(ctx context.Context, _ netguard.Request) (netguard.Response, error) {
			close(started)
			<-ctx.Done()
			close(canceled)
			return netguard.Response{}, ctx.Err()
		}}, nil
	}
	code := invokeScript(`return await new Promise(()=>lx.request('https://metadata.example.test/forever',{},()=>{}));`)
	result := make(chan error, 1)
	go func() {
		_, err := p.Invoke(context.Background(), code, "wy", "musicUrl", nil, Options{})
		result <- err
	}()
	<-started
	processes := poolProcesses(p)
	var waiters sync.WaitGroup
	for range 8 {
		waiters.Add(1)
		go func() {
			defer waiters.Done()
			_, err := p.Invoke(context.Background(), code, "wy", "musicUrl", nil, Options{})
			if err == nil {
				t.Error("closed waiter succeeded")
			}
		}()
	}
	start := time.Now()
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("Close did not cancel promptly")
	}
	if err := <-result; err == nil {
		t.Fatal("active cancellation succeeded")
	}
	waiters.Wait()
	select {
	case <-canceled:
	default:
		t.Fatal("broker outlived Close")
	}
	for _, process := range processes {
		select {
		case <-process.exited:
		default:
			t.Fatal("child not waited")
		}
	}
	if len(workerSlots) != 0 || len(poolProcesses(p)) != 0 {
		t.Fatal("slot/process leaked")
	}
}
func TestSessionIdleWorkersDoNotStarveLegacyOneshot(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	sessionCall(t, p, sessionCounterScript, Options{})
	sessionCall(t, p, sessionCounterScript+"\n// second", Options{})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := Inspect(ctx, initScript, Options{}); err != nil {
		t.Fatalf("idle pool monopolized global execution tokens: %v", err)
	}
	if len(workerSlots) != 0 {
		t.Fatal("idle sessions held execution tokens")
	}
}
func TestSessionRepeatedCloseCancellationAndFailureDoNotLeakGoroutines(t *testing.T) {
	before := runtime.NumGoroutine()
	for range 12 {
		p := NewPool()
		sessionCall(t, p, sessionCounterScript, Options{})
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Millisecond)
		_, _ = p.Invoke(ctx, sessionCounterScript, "wy", "musicUrl", map[string]any{"loop": true}, Options{})
		cancel()
		_ = p.Close()
	}
	waitSession(t, func() bool { runtime.GC(); return runtime.NumGoroutine() <= before+2 })
	if len(workerSlots) != 0 {
		t.Fatal("execution slot leaked")
	}
}
func TestSessionBackgroundRejectIsNotGlobalFailureAcrossCalls(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	code := `let calls=0;` + invokeScript(`Promise.reject(new Error('BACKGROUND-SECRET'));await new Promise(r=>setTimeout(r,1));return ++calls;`)
	for i := 1; i <= 3; i++ {
		value, err := p.Invoke(context.Background(), code, "wy", "musicUrl", nil, Options{})
		if err != nil || string(value) != strconv.Itoa(i) {
			t.Fatalf("background rejection poisoned reused VM: %v", err)
		}
	}
}

func TestSessionNewPoolDoesNotStartUnownedGoroutines(t *testing.T) {
	before := runtime.NumGoroutine()
	pools := make([]*Pool, 20)
	for i := range pools {
		pools[i] = NewPool()
	}
	if runtime.NumGoroutine() > before+1 {
		t.Error("unused Manager pool eagerly started goroutines")
	}
	for _, p := range pools {
		_ = p.Close()
	}
}
func TestSessionParallelKeysRespectCapacityAndDoNotEvictBusyWorkers(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	started, finish := make(chan struct{}, 2), make(chan struct{})
	p.newBroker = func(Options) (metadataBroker, error) {
		return &countedSessionBroker{fn: func(ctx context.Context, _ netguard.Request) (netguard.Response, error) {
			started <- struct{}{}
			select {
			case <-finish:
				return netguard.Response{Body: []byte(`"ok"`)}, nil
			case <-ctx.Done():
				return netguard.Response{}, ctx.Err()
			}
		}}, nil
	}
	code := invokeScript(`return await new Promise((resolve,reject)=>lx.request('https://metadata.example.test/config',{},(err,resp,body)=>err?reject(err):resolve(body)));`)
	results := make(chan error, 2)
	for _, suffix := range []string{"\n// a", "\n// b"} {
		go func() {
			_, err := p.Invoke(context.Background(), code+suffix, "wy", "musicUrl", nil, Options{})
			results <- err
		}()
	}
	<-started
	<-started
	if len(poolProcesses(p)) != 2 || len(workerSlots) != 2 {
		t.Error("parallel distinct keys did not run within two slots")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	_, err := p.Invoke(ctx, code+"\n// queued", "wy", "musicUrl", nil, Options{})
	cancel()
	if !errors.Is(err, ErrTimeout) {
		t.Errorf("capacity wait did not honor context: %v", err)
	}
	if len(poolProcesses(p)) != 2 {
		t.Error("busy workers evicted to admit another key")
	}
	close(finish)
	for range 2 {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
}
func TestSessionCrashReapsIdleAndActiveWorkersThenReinitializes(t *testing.T) {
	for _, active := range []bool{false, true} {
		t.Run(strconv.FormatBool(active), func(t *testing.T) {
			p := NewPool()
			t.Cleanup(func() { _ = p.Close() })
			first := sessionCall(t, p, sessionCounterScript, Options{})
			process := poolProcesses(p)[0]
			result := make(chan error, 1)
			if active {
				go func() {
					_, err := p.Invoke(context.Background(), sessionCounterScript, "wy", "musicUrl", map[string]any{"loop": true}, Options{})
					result <- err
				}()
				waitSession(t, func() bool { return len(workerSlots) > 0 })
			}
			killProcess(process.cmd)
			if active {
				if err := <-result; !errors.Is(err, ErrResource) {
					t.Fatalf("worker crash not classified: %v", err)
				}
			}
			waitSession(t, func() bool { return len(poolProcesses(p)) == 0 })
			select {
			case <-process.exited:
			default:
				t.Fatal("dead worker not waited")
			}
			next := sessionCall(t, p, sessionCounterScript, Options{})
			if next.Boot == first.Boot || next.Calls != 1 {
				t.Fatal("crashed session reused")
			}
		})
	}
}
func TestSessionNearCumulativeCPULimitRetiresBeforeHardDeath(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("Linux rlimits")
	}
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	code := `const boot=lx.utils.crypto.randomBytes(16).toString('hex');let calls=0;` + invokeScript(`if(info&&info.spin){const end=Date.now()+100;while(Date.now()<end){}}return{boot,calls:++calls};`)
	first := sessionCall(t, p, code, Options{})
	process := poolProcesses(p)[0]
	retired := false
	for range 80 {
		_, err := p.Invoke(context.Background(), code, "wy", "musicUrl", map[string]any{"spin": true}, Options{})
		if err != nil {
			t.Fatalf("short operations hit cumulative hard limit before retirement: %v", err)
		}
		if len(poolProcesses(p)) == 0 {
			retired = true
			break
		}
	}
	if !retired {
		t.Fatal("session never retired near cumulative CPU limit")
	}
	<-process.exited
	cpu := process.cmd.ProcessState.UserTime() + process.cmd.ProcessState.SystemTime()
	if cpu < 2*time.Second || cpu >= 3*time.Second {
		t.Fatalf("unexpected retirement CPU: %v", cpu)
	}
	next := sessionCall(t, p, code, Options{})
	if next.Boot == first.Boot || next.Calls != 1 {
		t.Fatal("CPU retired VM retained")
	}
}
func TestSessionHardCPULimitStillKillsWorkerWithoutParentTimeout(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("Linux rlimits")
	}
	process, err := startSessionProcess(func() {})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(process.close)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_, _, err = process.call(ctx, invocation{Code: `while(true){}`, Inspect: true}, &countedSessionBroker{})
	if !errors.Is(err, ErrResource) {
		t.Fatalf("kernel CPU limit did not stop infinite JS: %v", err)
	}
	<-process.exited
	cpu := process.cmd.ProcessState.UserTime() + process.cmd.ProcessState.SystemTime()
	if cpu < 2500*time.Millisecond || cpu > 4500*time.Millisecond {
		t.Fatalf("3/4s kernel CPU budget changed: %v", cpu)
	}
}
func TestSessionStartupResourceErrorsKeepSafeClassification(t *testing.T) {
	for _, code := range []string{"resource", "unsupported"} {
		var out bytes.Buffer
		_ = writeFrame(&out, message{Kind: "result", Error: code})
		_, _, err := exchangeCall(context.Background(), io.Discard, &out, &countedSessionBroker{}, 1)
		if !errors.Is(err, decodeError(code, "")) {
			t.Fatalf("startup failure mislabeled: %v", err)
		}
	}
}
func TestSessionAsyncInitializationThatSettlesBeforeInitedIsReused(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	code := `const boot=lx.utils.crypto.randomBytes(16).toString('hex');let calls=0,config;
lx.on(lx.EVENT_NAMES.request,async()=>({boot,calls:++calls,config}));
(async()=>{config=await new Promise(r=>setTimeout(()=>r('ready'),5));` + strings.Replace(initScript, "name:'WY'", "name:boot", 1) + `})();`
	descriptor, err := p.Inspect(context.Background(), code, Options{})
	if err != nil {
		t.Fatal(err)
	}
	first, second := sessionCall(t, p, code, Options{}), sessionCall(t, p, code, Options{})
	if first.Boot != descriptor.Sources["wy"].Name || second.Boot != first.Boot || second.Calls != 2 {
		t.Fatal("settled async init was repeated")
	}
}

// 主线真实源包含顺序 metadata/HEAD，总耗时可超过 5 秒；Pool 不覆盖 provider 的 8 秒首选预算。
func TestSessionRespectsUpstreamEightSecondBudgetInsteadOfCappingAtFive(t *testing.T) {
	p := NewPool()
	t.Cleanup(func() { _ = p.Close() })
	code := invokeScript(`await new Promise(r=>setTimeout(r,2650));await new Promise(r=>setTimeout(r,2650));return 'done';`)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	value, err := p.Invoke(ctx, code, "wy", "musicUrl", nil, Options{})
	if err != nil || string(value) != `"done"` {
		t.Fatalf("Pool silently shortened upstream 8s budget: %v", err)
	}
}

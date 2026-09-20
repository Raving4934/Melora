package lxruntime

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"slices"
	"sync"
	"time"
	"unicode/utf8"

	"melora/internal/netguard"
)

var workerSlots = make(chan struct{}, 2)

// 仅包内子进程集成测试可替换路径，公开 API 永远使用当前二进制。
var executablePath = os.Executable

type metadataBroker interface {
	Do(context.Context, netguard.Request) (netguard.Response, error)
	Close()
}

func execute(ctx context.Context, input invocation, opts Options) (json.RawMessage, error) {
	broker, err := netguard.NewBroker(opts)
	if err != nil {
		return nil, ErrNetwork
	}
	defer broker.Close()
	return executeWithBroker(ctx, input, broker)
}
func executeWithBroker(ctx context.Context, input invocation, broker metadataBroker) (json.RawMessage, error) {
	if len(input.Code) == 0 || len(input.Code) > maxCodeBytes || !utf8.ValidString(input.Code) || len(input.Info) > maxInfoBytes || len(input.Platform) > 32 || len(input.Action) > 32 {
		return nil, ErrLimit
	}
	deadline := 12 * time.Second
	if input.Inspect {
		deadline = 6 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	select {
	case workerSlots <- struct{}{}:
		defer func() { <-workerSlots }()
	case <-ctx.Done():
		return nil, ErrTimeout
	}
	if ctx.Err() != nil {
		return nil, ErrTimeout
	}
	binary, err := executablePath()
	if err != nil {
		return nil, ErrResource
	}
	cmd := exec.CommandContext(ctx, binary, WorkerArgument)
	cmd.Env = []string{"LANG=C.UTF-8", "TZ=UTC", "GOMAXPROCS=1", "GOMEMLIMIT=96MiB", "GOGC=50", "GOTRACEBACK=none"}
	cmd.Dir = "/"
	cmd.Stderr = io.Discard // 不将脚本异常、panic/OOM、响应或 secret 转发到系统日志。
	configureProcess(cmd)
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, ErrResource
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		in.Close()
		return nil, ErrResource
	}
	if err = cmd.Start(); err != nil {
		in.Close()
		out.Close()
		return nil, ErrResource
	}
	defer func() { killProcess(cmd); in.Close(); out.Close(); _ = cmd.Wait() }()
	if err = writeFrame(in, input); err != nil {
		if ctx.Err() != nil {
			return nil, ErrTimeout
		}
		return nil, ErrResource
	}
	return exchange(ctx, in, out, broker)
}

// 请求管道关闭与 worker stdout 的最终结果并不同步；必须读完结果帧，
// 不能把向已退出 worker 发送回包的 EPIPE 误判为执行失败。
func exchange(ctx context.Context, in io.Writer, out io.Reader, broker metadataBroker) (json.RawMessage, error) {
	result, _, err := exchangeCall(ctx, in, out, broker, 0)
	return result, err
}

func exchangeCall(ctx context.Context, in io.Writer, out io.Reader, broker metadataBroker, callID uint64) (result json.RawMessage, retire bool, err error) {
	ctx, cancel := context.WithCancel(ctx)
	readerDone := make(chan struct{})
	defer func() {
		cancel()
		if err != nil {
			// 失败时唤醒阻塞的 pipe reader；不能把读 goroutine 留到下一调用。
			if closer, ok := out.(io.Closer); ok {
				_ = closer.Close()
			}
		}
		<-readerDone
	}()
	events := make(chan message, 1)
	failed := make(chan error, 1)
	go func() {
		defer close(readerDone)
		for {
			var event message
			if err := readFrame(out, &event, 384<<10); err != nil {
				failed <- err
				return
			}
			select {
			case events <- event:
			case <-ctx.Done():
				return
			}
			if event.Kind == "result" {
				return
			}
		}
	}()
	requests := 0
	inputClosed := false
	answers := make(chan message, netguard.MaxRequests)
	pending := make(map[int]context.CancelFunc)
	var network sync.WaitGroup
	defer func() { cancel(); network.Wait() }()
	for {
		select {
		case <-ctx.Done():
			return nil, true, ErrTimeout
		case err := <-failed:
			if ctx.Err() != nil {
				return nil, true, ErrTimeout
			}
			if errors.Is(err, ErrLimit) {
				return nil, true, ErrLimit
			}
			if errors.Is(err, ErrProtocol) {
				return nil, true, ErrProtocol
			}
			return nil, true, ErrResource
		case answer := <-answers:
			if stop := pending[answer.ID]; stop != nil {
				stop()
			}
			if inputClosed {
				continue
			}
			if writeFrame(in, answer) != nil {
				if ctx.Err() != nil {
					return nil, true, ErrTimeout
				}
				inputClosed = true
				for _, stop := range pending {
					stop()
				}
				// 只停止回包，不取消 stdout 读取；最终 result 优先于管道关闭。
			}
		case event := <-events:
			if event.CallID != callID {
				// 资源限制安装发生在读取首帧之前；仅允许无调用号的固定启动失败。
				startupFailure := callID == 1 && event.CallID == 0 && event.Kind == "result" && (event.Error == "resource" || event.Error == "unsupported")
				if !startupFailure {
					return nil, true, ErrProtocol
				}
			}
			switch event.Kind {
			case "request":
				requests++
				maxID := netguard.MaxRequests
				if callID != 0 {
					maxID *= maxSessionCalls
				}
				if requests > netguard.MaxRequests || event.Request == nil || event.ID < 1 || event.ID > maxID {
					return nil, true, ErrLimit
				}

				if _, exists := pending[event.ID]; exists {
					return nil, true, ErrProtocol
				}
				requestCtx, stop := context.WithCancel(ctx)
				pending[event.ID] = stop
				if inputClosed {
					stop()
					break
				}
				network.Add(1)
				go func(event message) {
					defer network.Done()
					response, err := broker.Do(requestCtx, *event.Request)
					answer := message{Kind: "response", CallID: callID, ID: event.ID, Response: &response}
					if err != nil {
						answer.Response = nil
						answer.Error = "network"
					}
					select {
					case answers <- answer:
					case <-ctx.Done():
					}
				}(event)
			case "cancel":
				if stop := pending[event.ID]; stop != nil {
					stop()
				}

			case "result":
				if ctx.Err() != nil {
					return nil, true, ErrTimeout
				}
				if event.Error != "" {
					return nil, true, decodeError(event.Error, event.API)
				}
				if len(event.Result) == 0 || len(event.Result) > maxResultBytes || !json.Valid(event.Result) {
					return nil, true, ErrLimit
				}
				return append(json.RawMessage(nil), event.Result...), event.Retire || inputClosed, nil
			default:
				return nil, true, ErrProtocol
			}
		}
	}
}

const maxSessionCalls = 256

type poolLimits struct {
	capacity  int
	idle, age time.Duration
	calls     int
}

var defaultPoolLimits = poolLimits{capacity: 2, idle: 90 * time.Second, age: 10 * time.Minute, calls: maxSessionCalls}

// Pool 的状态只属于持有它的 Manager。全局 workerSlots 只限制同时执行 <=2，
// idle VM 不执行 JS，进程数按每 Pool 的 capacity 限定并由 reaper/Close 回收。
type Pool struct {
	mu            sync.Mutex
	entries       map[[sha256.Size]byte]*poolEntry
	changed       chan struct{}
	wake          chan struct{}
	ctx           context.Context
	cancel        context.CancelFunc
	closed        bool
	closeOnce     sync.Once
	calls         sync.WaitGroup
	reaperDone    chan struct{}
	reaperStarted bool
	limits        poolLimits
	newBroker     func(Options) (metadataBroker, error)
}
type poolEntry struct {
	key        [sha256.Size]byte
	busy       bool
	born, used time.Time
	process    *sessionProcess
}

func newPool(limits poolLimits) *Pool {
	ctx, cancel := context.WithCancel(context.Background())
	p := &Pool{entries: make(map[[sha256.Size]byte]*poolEntry), changed: make(chan struct{}), wake: make(chan struct{}, 1), ctx: ctx, cancel: cancel, reaperDone: make(chan struct{}), limits: limits,
		newBroker: func(opts Options) (metadataBroker, error) { return netguard.NewBroker(opts) }}
	return p
}
func sessionKey(code string, opts Options) [sha256.Size]byte {
	// key 不保留脚本原文；options 复制/排序后哈希，不让网络权限不同的脚本共享 VM。
	opts.AllowHTTPHosts = slices.Clone(opts.AllowHTTPHosts)
	slices.Sort(opts.AllowHTTPHosts)
	opts.AllowHTTPHosts = slices.Compact(opts.AllowHTTPHosts)
	if len(opts.AllowHTTPHosts) == 0 {
		opts.AllowHTTPHosts = nil
	}
	encoded, _ := json.Marshal(opts)
	hash := sha256.New()
	_, _ = hash.Write(encoded)
	_, _ = hash.Write([]byte{0})
	_, _ = hash.Write([]byte(code))
	var key [sha256.Size]byte
	copy(key[:], hash.Sum(nil))
	return key
}
func (p *Pool) signalLocked() { close(p.changed); p.changed = make(chan struct{}) }
func (p *Pool) notifyExit() {
	select {
	case p.wake <- struct{}{}:
	default:
	}
}
func (e *poolEntry) dead() bool {
	if e.process == nil {
		return false
	}
	select {
	case <-e.process.exited:
		return true
	default:
		return false
	}
}
func (p *Pool) expired(e *poolEntry, now time.Time) bool {
	return e.dead() || now.Sub(e.used) >= p.limits.idle || now.Sub(e.born) >= p.limits.age || e.process != nil && e.process.sequence >= uint64(p.limits.calls)
}

// entry 在回收及 Wait 完成前仍占容量，避免并发淘汰瞬间突破进程上限。
func (p *Pool) retire(e *poolEntry) {
	if e.process != nil {
		e.process.close()
	}
	p.mu.Lock()
	if p.entries[e.key] == e {
		delete(p.entries, e.key)
	}
	p.signalLocked()
	p.mu.Unlock()
}
func (p *Pool) acquire(ctx context.Context, key [sha256.Size]byte) (*poolEntry, error) {
	for {
		p.mu.Lock()
		if p.closed {
			p.mu.Unlock()
			return nil, ErrClosed
		}
		if ctx.Err() != nil {
			p.mu.Unlock()
			return nil, ErrTimeout
		}
		var victim *poolEntry
		if entry := p.entries[key]; entry != nil {
			if !entry.busy {
				entry.busy = true
				if p.expired(entry, time.Now()) {
					victim = entry
				} else {
					p.mu.Unlock()
					return entry, nil
				}
			}
		} else if len(p.entries) < p.limits.capacity {
			entry := &poolEntry{key: key, busy: true, born: time.Now(), used: time.Now()}
			p.entries[key] = entry
			p.mu.Unlock()
			return entry, nil
		} else {
			for _, entry := range p.entries {
				if !entry.busy && (victim == nil || entry.used.Before(victim.used)) {
					victim = entry
				}
			}
			if victim != nil {
				victim.busy = true
			}
		}
		changed := p.changed
		p.mu.Unlock()
		if victim != nil {
			p.retire(victim)
			continue
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return nil, ErrTimeout
		}
	}
}
func (p *Pool) release(e *poolEntry, discard bool) {
	p.mu.Lock()
	e.used = time.Now()
	discard = discard || p.closed || e.process == nil || p.expired(e, time.Now())
	if !discard {
		e.busy = false
		e.used = time.Now()
		p.signalLocked()
	}
	p.mu.Unlock()
	if discard {
		p.retire(e)
	}
}
func (p *Pool) reap() {
	defer close(p.reaperDone)
	interval := max(time.Millisecond, min(10*time.Second, p.limits.idle/2, p.limits.age/2))
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-p.ctx.Done():
			return
		case <-ticker.C:
		case <-p.wake:
		}
		p.mu.Lock()
		var victims []*poolEntry
		for _, entry := range p.entries {
			if !entry.busy && p.expired(entry, time.Now()) {
				entry.busy = true
				victims = append(victims, entry)
			}
		}
		p.mu.Unlock()
		for _, entry := range victims {
			p.retire(entry)
		}
	}
}

// Close 取消所有排队/在途调用，等待其网络、pipe reader 与子进程 Wait 完成。
func (p *Pool) Close() error {
	p.closeOnce.Do(func() {
		p.mu.Lock()
		p.closed = true
		if !p.reaperStarted {
			p.reaperStarted = true
			close(p.reaperDone)
		}
		p.cancel()
		p.signalLocked()
		p.mu.Unlock()
		<-p.reaperDone
		p.calls.Wait()
		p.mu.Lock()
		entries := make([]*poolEntry, 0, len(p.entries))
		for _, entry := range p.entries {
			entries = append(entries, entry)
		}
		p.mu.Unlock()
		for _, entry := range entries {
			p.retire(entry)
		}
	})
	return nil
}
func (p *Pool) execute(ctx context.Context, input invocation, opts Options) (json.RawMessage, error) {
	if len(input.Code) == 0 || len(input.Code) > maxCodeBytes || !utf8.ValidString(input.Code) || len(input.Info) > maxInfoBytes || len(input.Platform) > 32 || len(input.Action) > 32 {
		return nil, ErrLimit
	}
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil, ErrClosed
	}
	if !p.reaperStarted {
		p.reaperStarted = true
		go p.reap()
	}
	p.calls.Add(1)
	p.mu.Unlock()
	defer p.calls.Done()
	// Pool 只保留原 standalone 的硬墙钟上限；provider 的 8s/5s 与总预算由上游 context 控制。
	budget := 12 * time.Second
	if input.Inspect {
		budget = 6 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, budget)
	defer cancel()
	stopPoolCancel := context.AfterFunc(p.ctx, cancel)
	defer stopPoolCancel()
	// 一个操作一个 Broker：保留每跳 DNS/IP/TLS 校验，重置 requests/wireRequests 预算。
	broker, err := p.newBroker(opts)
	if err != nil {
		return nil, ErrNetwork
	}
	defer broker.Close()
	entry, err := p.acquire(ctx, sessionKey(input.Code, opts))
	if err != nil {
		return nil, err
	}
	discard, hasSlot := true, false
	defer func() {
		p.release(entry, discard)
		if hasSlot {
			<-workerSlots
		}
	}()
	select {
	case workerSlots <- struct{}{}:
		hasSlot = true
	case <-ctx.Done():
		return nil, ErrTimeout
	}
	if ctx.Err() != nil {
		return nil, ErrTimeout
	}
	if entry.process == nil {
		process, err := startSessionProcess(p.notifyExit)
		if err != nil {
			return nil, err
		}
		// reaper 只读 idle entry；busy 不变直到本调用 release。
		p.mu.Lock()
		entry.process = process
		p.mu.Unlock()
	}
	result, retire, err := entry.process.call(ctx, input, broker)
	discard = retire || err != nil
	return result, err
}

// 每个常驻 worker 只有一个 Wait goroutine；操作中的 stdout reader 在返回前退出。
type sessionProcess struct {
	cmd           *exec.Cmd
	input, output *os.File
	exited        chan struct{}
	stopOnce      sync.Once
	sequence      uint64
}

func startSessionProcess(notify func()) (*sessionProcess, error) {
	binary, err := executablePath()
	if err != nil {
		return nil, ErrResource
	}
	cmd := exec.Command(binary, WorkerArgument)
	cmd.Env = []string{"LANG=C.UTF-8", "TZ=UTC", "GOMAXPROCS=1", "GOMEMLIMIT=96MiB", "GOGC=50", "GOTRACEBACK=none"}
	cmd.Dir = "/"
	cmd.Stderr = io.Discard
	configureProcess(cmd)
	reader, input, err := os.Pipe()
	if err != nil {
		return nil, ErrResource
	}
	output, writer, err := os.Pipe()
	if err != nil {
		reader.Close()
		input.Close()
		return nil, ErrResource
	}
	cmd.Stdin = reader
	cmd.Stdout = writer // 手动 pipe：cmd.Wait 不抢先关闭尚未读完的最终 result。
	if err = cmd.Start(); err != nil {
		reader.Close()
		input.Close()
		output.Close()
		writer.Close()
		return nil, ErrResource
	}
	reader.Close()
	writer.Close()
	process := &sessionProcess{cmd: cmd, input: input, output: output, exited: make(chan struct{})}
	go func() { _ = cmd.Wait(); close(process.exited); notify() }()
	return process, nil
}
func (p *sessionProcess) close() {
	p.stopOnce.Do(func() { killProcess(p.cmd); _ = p.input.Close(); _ = p.output.Close() })
	<-p.exited
}
func (p *sessionProcess) call(ctx context.Context, input invocation, broker metadataBroker) (json.RawMessage, bool, error) {
	monitorDone := make(chan struct{})
	stop := context.AfterFunc(ctx, func() { p.close(); close(monitorDone) })
	var monitor sync.Once
	stopMonitor := func() {
		monitor.Do(func() {
			if !stop() {
				<-monitorDone
			}
		})
	}
	defer stopMonitor()
	p.sequence++
	input.CallID = p.sequence
	input.Session = true
	var err error
	if p.sequence == 1 {
		err = writeFrame(p.input, input)
	} else {
		input.Code = ""
		err = writeFrame(p.input, message{Kind: "invoke", CallID: p.sequence, Invoke: &input})
	}
	if err != nil {
		if ctx.Err() != nil {
			return nil, true, ErrTimeout
		}
		return nil, true, ErrResource
	}
	result, retire, err := exchangeCall(ctx, p.input, p.output, broker, p.sequence)
	stopMonitor()
	if ctx.Err() != nil {
		p.close()
		return nil, true, ErrTimeout
	}
	return result, retire, err
}

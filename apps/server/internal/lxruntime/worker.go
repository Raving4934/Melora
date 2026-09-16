package lxruntime

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/dop251/goja"
	"github.com/dop251/goja/parser"
)

type scheduled struct {
	at   time.Time
	fn   goja.Callable
	args []goja.Value
}
type runtimeWorker struct {
	vm                                                      *goja.Runtime
	input                                                   invocation
	output                                                  io.Writer
	inbox                                                   <-chan message
	handler                                                 goja.Callable
	descriptor                                              *Descriptor
	stringify, parse, resolvePromise, rawBuffer             goja.Callable
	timers                                                  map[int]scheduled
	callbacks                                               map[int]goja.Callable
	nextTimer, timerCount, requestCount, nextRequest, turns int
	fault                                                   error
	unsupported                                             string
}

// WorkerMain 必须由 main 在其它初始化之前检测 --lx-worker 后调用并 os.Exit。
// 不输出任何脚本日志、异常堆栈、URL 或响应正文。
func WorkerMain() (code int) {
	if len(os.Args) != 2 || os.Args[1] != WorkerArgument {
		return 70
	}
	defer func() {
		if recover() != nil {
			code = 72
		}
	}()
	if err := workerLimits(); err != nil {
		_ = writeFrame(os.Stdout, message{Kind: "result", Error: wireError(err)})
		return 74
	}
	var input invocation
	if err := readFrame(os.Stdin, &input, maxFrameBytes); err != nil {
		return 70
	}
	if len(input.Code) == 0 || len(input.Code) > maxCodeBytes || len(input.Info) > maxInfoBytes || len(input.Platform) > 32 || len(input.Action) > 32 || input.Session && input.CallID != 1 || !input.Session && input.CallID != 0 {
		return 70
	}
	inbox := make(chan message, 16)
	readerStop := make(chan struct{})
	defer close(readerStop)
	go func() {
		defer close(inbox)
		for {
			var msg message
			if readFrame(os.Stdin, &msg, 2<<20) != nil {
				return
			}
			select {
			case inbox <- msg:
			case <-readerStop:
				return
			}
		}
	}()
	w := &runtimeWorker{input: input, output: os.Stdout, inbox: inbox}
	for {
		w.timers = make(map[int]scheduled)
		w.callbacks = make(map[int]goja.Callable)
		w.timerCount, w.requestCount, w.turns = 0, 0, 0
		w.fault, w.unsupported = nil, ""
		result, err := w.run()
		answer := message{Kind: "result", CallID: w.input.CallID, Result: result,
			Retire: w.input.Session && (w.input.CallID >= maxSessionCalls || workerShouldRetire() || len(w.callbacks) > 0 || len(w.timers) > 0)}
		if err != nil {
			answer.Result = nil
			answer.Error = wireError(err)
			answer.API = w.unsupported
		}
		// 有悬挂任务必须退休：取消回调会留下永远 pending 的全局 Promise，不能再复用。
		// 仅无悬挂任务的 JS heap/初始化闭包可保留；不模拟无限后台事件循环。
		clear(w.timers)
		clear(w.callbacks)
		if writeFrame(os.Stdout, answer) != nil {
			return 70
		}
		if !w.input.Session || err != nil || answer.Retire {
			return 0
		}
		for {
			msg, ok := <-inbox
			if !ok {
				return 0
			}
			// 前一调用写出的回包可能已在 stdin 排队；只丢弃旧响应，不接受乱序操作。
			if msg.Kind == "response" && msg.CallID <= w.input.CallID {
				continue
			}
			if msg.Kind != "invoke" || msg.CallID != w.input.CallID+1 || msg.Invoke == nil || msg.Invoke.CallID != msg.CallID || !msg.Invoke.Session || msg.Invoke.Code != "" || len(msg.Invoke.Info) > maxInfoBytes || len(msg.Invoke.Platform) > 32 || len(msg.Invoke.Action) > 32 {
				return 70
			}
			w.input = *msg.Invoke
			break
		}
	}
}

func (w *runtimeWorker) run() (json.RawMessage, error) {
	if w.vm == nil {
		w.vm = goja.New()
		// 禁止 sourceMappingURL 触发默认文件加载器。不存在 require 或模块加载器。
		w.vm.SetParserOptions(parser.WithDisableSourceMaps)
		w.vm.SetMaxCallStackSize(512)
		if err := w.install(); err != nil {
			return nil, err
		}
		if _, err := w.vm.RunString(w.input.Code); err != nil {
			return nil, w.scriptError(err)
		}
		if err := w.pump(func() bool { return w.descriptor != nil }); err != nil {
			return nil, err
		}
	}
	if w.input.Inspect {
		return json.Marshal(w.descriptor)
	}
	source, ok := w.descriptor.Sources[w.input.Platform]
	if !ok || !slices.Contains(source.Actions, w.input.Action) {
		return nil, w.missing("lx.request.action")
	}
	if w.handler == nil {
		return nil, w.missing("lx.request.handler")
	}
	info := goja.Value(goja.Null())
	if len(w.input.Info) > 0 {
		var err error
		info, err = w.parse(goja.Undefined(), w.vm.ToValue(string(w.input.Info)))
		if err != nil {
			return nil, ErrProtocol
		}
	}
	request := w.vm.NewObject()
	_ = request.Set("source", w.input.Platform)
	_ = request.Set("action", w.input.Action)
	_ = request.Set("info", info)
	value, err := w.handler(goja.Undefined(), request)
	if err != nil {
		return nil, w.scriptError(err)
	}
	// 与 JavaScript await 一致：同步 string/object、原生 Promise 与 thenable 均可返回。
	// 使用 bootstrap 捕获的原生 Promise.resolve，不向脚本开放新的宿主 API。
	value, err = w.resolvePromise(goja.Undefined(), value)
	if err != nil {
		return nil, w.scriptError(err)
	}
	promise, ok := value.Export().(*goja.Promise)
	if !ok {
		return nil, ErrScript
	}
	if err := w.pump(func() bool { return promise.State() != goja.PromiseStatePending }); err != nil {
		return nil, err
	}
	if promise.State() != goja.PromiseStateFulfilled {
		return nil, ErrScript
	}
	return w.encode(promise.Result(), maxResultBytes)
}

func (w *runtimeWorker) pump(done func() bool) error {
	for {
		if w.fault != nil {
			return w.fault
		}
		if done() {
			return nil
		}
		now := time.Now()
		wait := 12 * time.Second
		for id, timer := range w.timers {
			if !now.Before(timer.at) {
				delete(w.timers, id)
				w.turns++
				if w.turns > 128 {
					return ErrLimit
				}
				if _, err := timer.fn(goja.Undefined(), timer.args...); err != nil {
					return w.scriptError(err)
				}
				wait = 0
				break
			}
			if d := time.Until(timer.at); d < wait {
				wait = d
			}
		}
		if wait == 0 {
			continue
		}
		timer := time.NewTimer(wait)
		select {
		case response, ok := <-w.inbox:
			timer.Stop()
			if !ok {
				return ErrProtocol
			}
			if response.Kind != "response" {
				return ErrProtocol
			}
			if response.CallID != w.input.CallID {
				if response.CallID < w.input.CallID {
					continue
				}
				return ErrProtocol
			}
			callback := w.callbacks[response.ID]
			delete(w.callbacks, response.ID)
			if callback == nil {
				continue
			}
			if err := w.deliver(callback, response); err != nil {
				return err
			}
		case <-timer.C:
		}
	}
}
func (w *runtimeWorker) deliver(callback goja.Callable, response message) error {
	var args []goja.Value
	if response.Error != "" || response.Response == nil {
		args = []goja.Value{w.vm.NewTypeError("LX_METADATA_REQUEST_FAILED"), goja.Null(), goja.Null()}
	} else {
		r := response.Response
		body := w.vm.ToValue(strings.ToValidUTF8(string(r.Body), "�"))
		if json.Valid(r.Body) {
			if parsed, err := w.parse(goja.Undefined(), w.vm.ToValue(string(r.Body))); err == nil {
				body = parsed
			}
		}
		headers := w.vm.NewObject()
		for k, v := range r.Headers {
			_ = headers.Set(k, v)
		}
		resp := w.vm.NewObject()
		_ = resp.Set("statusCode", r.StatusCode)
		_ = resp.Set("statusMessage", r.StatusMessage)
		_ = resp.Set("headers", headers)
		_ = resp.Set("body", body)
		raw, err := w.rawBuffer(goja.Undefined(), w.buffer(append([]byte(nil), r.Body...)))
		if err != nil {
			return ErrScript
		}
		_ = resp.Set("raw", raw)
		_ = resp.Set("bytes", len(r.Body))
		args = []goja.Value{goja.Null(), resp, body}
	}
	if _, err := callback(goja.Undefined(), args...); err != nil {
		return w.scriptError(err)
	}
	return nil
}
func (w *runtimeWorker) encode(value goja.Value, limit int) (json.RawMessage, error) {
	result, err := w.stringify(goja.Undefined(), value)
	if err != nil || goja.IsUndefined(result) {
		return nil, ErrScript
	}
	text := result.String()
	if len(text) > limit || !json.Valid([]byte(text)) {
		return nil, ErrLimit
	}
	return json.RawMessage(text), nil
}
func (w *runtimeWorker) missing(api string) error {
	w.unsupported = api
	w.fault = ErrUnsupported
	return ErrUnsupported
}
func (w *runtimeWorker) fail(err error) {
	w.fault = err
	panic(w.vm.NewTypeError("LX_OPERATION_REJECTED"))
}

var missingReference = regexp.MustCompile(`ReferenceError: ([A-Za-z]+) is not defined`)

func (w *runtimeWorker) scriptError(err error) error {
	if w.fault != nil {
		return w.fault
	}
	var exception *goja.Exception
	if errors.As(err, &exception) {
		if match := missingReference.FindStringSubmatch(exception.String()); len(match) == 2 && safeAPIName(match[1]) {
			return w.missing(match[1])
		}
	}
	return ErrScript
}

package lxruntime

import (
	_ "embed"
	"encoding/json"
	"regexp"
	"strings"
	"time"

	"github.com/dop251/goja"
	"melora/internal/netguard"
)

//go:embed bootstrap.js
var bootstrap string

func (w *runtimeWorker) install() error {
	native := w.vm.NewObject()
	_ = native.Set("unsupported", func(call goja.FunctionCall) goja.Value {
		name := call.Argument(0).String()
		if !safeAPIName(name) {
			name = "lx.utils"
		}
		w.fail(w.missing(name))
		return goja.Undefined()
	})
	_ = native.Set("on", func(call goja.FunctionCall) goja.Value {
		if call.Argument(0).String() != "request" {
			w.fail(w.missing("lx.on"))
		}
		fn, ok := goja.AssertFunction(call.Argument(1))
		if !ok {
			w.fail(ErrScript)
		}
		w.handler = fn
		return goja.Undefined()
	})
	_ = native.Set("init", func(call goja.FunctionCall) goja.Value {
		text := call.Argument(0).String()
		if len(text) > 64<<10 {
			w.fail(ErrLimit)
		}
		var d struct {
			Status  *bool             `json:"status"`
			Sources map[string]Source `json:"sources"`
		}
		if w.descriptor != nil || json.Unmarshal([]byte(text), &d) != nil || d.Status != nil && !*d.Status {
			w.fail(ErrScript)
		}
		for key, source := range d.Sources {
			if source.Name == "" {
				source.Name = key
				d.Sources[key] = source
			}
		}
		descriptor := Descriptor{Status: true, Sources: d.Sources}
		if !validDescriptor(descriptor) {
			w.fail(ErrScript)
		}
		w.descriptor = &descriptor
		return goja.Undefined()
	})
	_ = native.Set("request", func(call goja.FunctionCall) goja.Value {
		w.requestCount++
		if w.requestCount > netguard.MaxRequests {
			w.fail(ErrLimit)
		}
		text := call.Argument(0).String()
		if len(text) > 128<<10 {
			w.fail(ErrLimit)
		}
		var request netguard.Request
		if json.Unmarshal([]byte(text), &request) != nil || len(request.URL) > 8192 || len(request.Body) > netguard.MaxRequestBytes {
			w.fail(ErrLimit)
		}
		fn, ok := goja.AssertFunction(call.Argument(1))
		if !ok {
			w.fail(ErrScript)
		}
		w.nextRequest++
		id := w.nextRequest
		w.callbacks[id] = fn
		if writeFrame(w.output, message{Kind: "request", CallID: w.input.CallID, ID: id, Request: &request}) != nil {
			w.fail(ErrProtocol)
		}
		return w.vm.ToValue(id)
	})
	_ = native.Set("cancel", func(call goja.FunctionCall) goja.Value {
		id := int(call.Argument(0).ToInteger())
		if _, exists := w.callbacks[id]; exists {
			delete(w.callbacks, id)
			if writeFrame(w.output, message{Kind: "cancel", CallID: w.input.CallID, ID: id}) != nil {
				w.fail(ErrProtocol)
			}
		}
		return goja.Undefined()
	})
	_ = native.Set("timer", func(call goja.FunctionCall) goja.Value {
		if len(w.timers) >= 64 || w.timerCount >= 256 {
			w.fail(ErrLimit)
		}
		fn, ok := goja.AssertFunction(call.Argument(0))
		if !ok {
			w.fail(ErrScript)
		}
		delay := min(max(call.Argument(1).ToInteger(), 0), 5000)
		var args []goja.Value
		if w.vm.ExportTo(call.Argument(2), &args) != nil || len(args) > 32 {
			w.fail(ErrLimit)
		}
		w.nextTimer++
		w.timerCount++
		w.timers[w.nextTimer] = scheduled{at: time.Now().Add(time.Duration(delay) * time.Millisecond), fn: fn, args: args}
		return w.vm.ToValue(w.nextTimer)
	})
	_ = native.Set("clearTimer", func(call goja.FunctionCall) goja.Value {
		delete(w.timers, int(call.Argument(0).ToInteger()))
		return goja.Undefined()
	})
	w.installUtils(native)
	value, err := w.vm.RunString(bootstrap)
	if err != nil {
		return ErrScript
	}
	fn, ok := goja.AssertFunction(value)
	if !ok {
		return ErrScript
	}
	info, _ := json.Marshal(scriptInfo(w.input.Code))
	result, err := fn(goja.Undefined(), native, w.vm.ToValue(string(info)))
	if err != nil {
		return ErrScript
	}
	object := result.ToObject(w.vm)
	w.parse, _ = goja.AssertFunction(object.Get("parse"))
	w.stringify, _ = goja.AssertFunction(object.Get("stringify"))
	w.resolvePromise, _ = goja.AssertFunction(object.Get("resolvePromise"))
	w.rawBuffer, _ = goja.AssertFunction(object.Get("rawBuffer"))
	return nil
}

var headerField = regexp.MustCompile(`(?m)^\s*\*\s*@([a-zA-Z]+)\s+([^\r\n]*)`)

func scriptInfo(code string) map[string]string {
	info := map[string]string{"name": "", "description": "", "version": "", "author": "", "homepage": "", "rawScript": code}
	header := code[:min(len(code), 16<<10)]
	if end := strings.Index(header, "*/"); end >= 0 {
		header = header[:end]
	}
	for _, match := range headerField.FindAllStringSubmatch(header, -1) {
		limit := 0
		switch match[1] {
		case "name":
			limit = 24
		case "description", "version":
			limit = 36
		case "author":
			limit = 56
		case "homepage":
			limit = 1024
		}
		if limit == 0 {
			continue
		}
		text := []rune(strings.TrimSpace(match[2]))
		if len(text) > limit {
			text = text[:limit]
		}
		info[match[1]] = string(text)
	}
	return info
}

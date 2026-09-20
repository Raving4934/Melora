package lxruntime

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"melora/internal/netguard"
)

func TestV13BinaryRequestCarriesBoundAndReturnsLXBuffer(t *testing.T) {
	broker := &fakeBroker{fn: func(_ context.Context, request netguard.Request) (netguard.Response, error) {
		if !request.Binary || request.MaxResponseBytes != 64 {
			t.Errorf("binary request options were lost: %+v", request)
		}
		return netguard.Response{StatusCode: 200, Binary: true, Body: []byte{0x66, 0x4c, 0x61, 0x43}}, nil
	}}
	code := invokeScript(`return new Promise((resolve,reject)=>lx.request('https://metadata.example.test/flac',{binary:true,maxResponseBytes:64},(err,resp,body)=>err?reject(err):resolve({same:body===resp.raw,hex:body.toString('hex'),bytes:resp.bytes})));`)
	result, err := withBroker(context.Background(), code, false, broker)
	if err != nil || string(result) != `{"same":true,"hex":"664c6143","bytes":4}` {
		t.Fatalf("binary request result mismatch: %s %v", result, err)
	}
}

func TestV13BinaryRequestRejectsUnboundedExplicitLimitBeforeBroker(t *testing.T) {
	broker := &fakeBroker{fn: func(context.Context, netguard.Request) (netguard.Response, error) {
		t.Errorf("invalid response limit reached broker")
		return netguard.Response{}, nil
	}}
	for _, expression := range []string{"{maxResponseBytes:-1}", "{maxResponseBytes:1048577}"} {
		_, err := withBroker(context.Background(), invokeScript(`return new Promise(resolve=>lx.request('https://metadata.example.test/',`+expression+`,(err,resp)=>resolve(!!err&&resp===null)));`), false, broker)
		if !errors.Is(err, ErrLimit) || broker.calls.Load() != 0 {
			t.Fatalf("invalid response limit was not rejected: expression=%s calls=%d err=%v", expression, broker.calls.Load(), err)
		}
	}
}

func TestV13ExplicitResponseLimitPropagatesAsRequestError(t *testing.T) {
	broker := &fakeBroker{fn: func(_ context.Context, request netguard.Request) (netguard.Response, error) {
		if request.MaxResponseBytes != 32768 {
			t.Errorf("JSON response limit was not forwarded: %d", request.MaxResponseBytes)
		}
		// Broker returns ErrLimit when the response exceeds the explicit bound;
		// the worker must not deliver a truncated JSON string to the script.
		return netguard.Response{}, netguard.ErrLimit
	}}
	code := invokeScript(`return new Promise(resolve=>lx.request('https://metadata.example.test/json',{maxResponseBytes:32768},(err,resp,body)=>resolve({failed:!!err,empty:resp===null&&body===null})));`)
	result, err := withBroker(context.Background(), code, false, broker)
	var got map[string]any
	if err != nil || json.Unmarshal(result, &got) != nil || got["failed"] != true || got["empty"] != true {
		t.Fatalf("truncated JSON was not rejected as a request error: %s %v", result, err)
	}
}

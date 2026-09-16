package lxruntime

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"melora/internal/netguard"
)

func TestV5HandlerReturnNormalization(t *testing.T) {
	for name, expression := range map[string]string{
		"sync_string": `'https://media.example.test/public.mp3'`,
		"sync_object": `({data:{url:'https://media.example.test/public.mp3'}})`,
		"promise":     `Promise.resolve('https://media.example.test/public.mp3')`,
		"thenable":    `({then(resolve){setTimeout(()=>resolve('https://media.example.test/public.mp3'),1)}})`,
		"json_string": `JSON.stringify({data:{url:'https://media.example.test/public.mp3'}})`,
	} {
		t.Run(name, func(t *testing.T) {
			code := `lx.on(lx.EVENT_NAMES.request,()=>` + expression + `);` + initScript
			result, err := Invoke(t.Context(), code, "wy", "musicUrl", nil, Options{})
			if err != nil || !json.Valid(result) {
				t.Fatalf("valid return rejected: %v", err)
			}
		})
	}
}

func TestV5ThenableRejectAndThrowStayRedacted(t *testing.T) {
	for _, expression := range []string{
		`({then(resolve,reject){reject('SECRET_SIGNED_URL')}})`,
		`({get then(){throw new Error('SECRET_TOKEN')}})`,
		`({toJSON(){throw new Error('SECRET_TOKEN')}})`,
	} {
		_, err := Invoke(context.Background(), `lx.on(lx.EVENT_NAMES.request,()=>`+expression+`);`+initScript, "wy", "musicUrl", nil, Options{})
		if err == nil || strings.Contains(err.Error(), "SECRET") {
			t.Fatalf("invalid rejection handling: %v", err)
		}
		if !errors.Is(err, ErrScript) {
			t.Fatalf("unexpected error class: %v", err)
		}
	}
}

func TestV5RawBufferAndNeedleBodySemantics(t *testing.T) {
	b := &fakeBroker{fn: func(_ context.Context, r netguard.Request) (netguard.Response, error) {
		if string(r.Body) != "hello=world" {
			t.Error("body object must default to form encoding, not forced JSON")
		}
		if r.Headers["content-type"] != "application/x-www-form-urlencoded" {
			t.Error("incorrect default content type")
		}
		return netguard.Response{StatusCode: 200, Headers: map[string]string{"content-type": "application/json"}, Body: []byte(`{"ok":true}`)}, nil
	}}
	result, err := withBroker(t.Context(), invokeScript(`return await new Promise((resolve,reject)=>lx.request('https://metadata.example.test/',{method:'POST',body:{hello:'world'}},(err,resp)=>err?reject(err):resolve(resp.raw.toString())));`), false, b)
	if err != nil || string(result) != `"{\"ok\":true}"` {
		t.Fatalf("raw.toString differs from LX Buffer: %v", err)
	}
}
func TestV5ExplicitJSONAndHeaderScalars(t *testing.T) {
	b := &fakeBroker{fn: func(_ context.Context, r netguard.Request) (netguard.Response, error) {
		if !json.Valid(r.Body) || r.Headers["content-type"] != "application/json" || r.Headers["x-version"] != "2" {
			t.Error("explicit JSON or numeric header mismatch")
		}
		return netguard.Response{StatusCode: 200, Body: []byte(`{}`)}, nil
	}}
	_, err := withBroker(t.Context(), invokeScript(`return new Promise((resolve,reject)=>lx.request('https://metadata.example.test/',{method:'POST',headers:{'Content-Type':'application/json','X-Version':2},body:{hello:'world'}},(err,resp)=>err?reject(err):resolve(resp.body)));`), false, b)
	if err != nil {
		t.Fatal(err)
	}
}
func TestV5FailedCallbackUsesNull(t *testing.T) {
	b := &fakeBroker{fn: func(context.Context, netguard.Request) (netguard.Response, error) {
		return netguard.Response{}, netguard.ErrPolicy
	}}
	result, err := withBroker(t.Context(), invokeScript(`return new Promise(resolve=>lx.request('https://metadata.example.test/',{},(err,resp,body)=>resolve(!!err&&resp===null&&body===null)));`), false, b)
	if err != nil || string(result) != "true" {
		t.Fatalf("callback null contract mismatch: %v", err)
	}
}

package lxruntime

import (
	"context"
	"melora/internal/netguard"
	"testing"
)

// 固定官方 preload.js 的 if(body) / else if(form) / else if(formData) 分支。
// 共享请求封装常构造 {body: undefined}；它不等于发送 '=' 的 GET。
func TestV12RequestFalsyBodyMatchesLX(t *testing.T) {
	cases := []struct{ name, options, want string }{
		{"undefined", `{body:undefined}`, ""}, {"null", `{body:null}`, ""}, {"empty", `{body:''}`, ""}, {"false", `{body:false}`, ""}, {"zero", `{body:0}`, ""},
		{"fallback-form", `{method:'POST',body:null,form:{a:'ok'}}`, "a=ok"},
		{"body-precedence", `{method:'POST',body:'raw',form:{a:'ignored'}}`, "raw"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := &fakeBroker{fn: func(_ context.Context, r netguard.Request) (netguard.Response, error) {
				if string(r.Body) != tc.want {
					t.Errorf("body=%q want=%q", r.Body, tc.want)
				}
				if tc.want == "" && r.Headers["content-type"] != "" {
					t.Error("absent body synthesized content-type")
				}
				return netguard.Response{StatusCode: 200, Body: []byte(`"ok"`)}, nil
			}}
			_, err := withBroker(t.Context(), invokeScript(`return new Promise((resolve,reject)=>lx.request('https://metadata.example.test/',`+tc.options+`,(err,resp)=>err?reject(err):resolve(resp.body)));`), false, b)
			if err != nil || b.calls.Load() != 1 {
				t.Fatalf("request fixture: calls=%d err=%v", b.calls.Load(), err)
			}
		})
	}
}

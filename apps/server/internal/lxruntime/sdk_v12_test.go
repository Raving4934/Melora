package lxruntime

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"melora/internal/netguard"
)

// 固定 LX preload + Needle fork 的合成请求形状；仅走真实隔离 worker 和内存 Broker。
// 不加载提供源、不访问真实网络，尤其不改变父进程网络策略。
func TestV12SDKNeedleRequestEncoding(t *testing.T) {
	const target = "https://metadata.example.test/probe?old=fixture%2Fvalue&old=second"
	cases := []struct {
		name, options, url, body, contentType string
	}{
		{"get-object-replaces-query", `{method:'GET',body:{a:'b c',tags:['x','y']}}`, "https://metadata.example.test/probe?a=b%20c&tags[]=x&tags[]=y", "", ""},
		{"get-lowercase-form", `{method:'get',form:{a:'x/y'}}`, "https://metadata.example.test/probe?a=x%2Fy", "", ""},
		{"get-empty-object", `{method:'GET',body:{}}`, "https://metadata.example.test/probe?", "", ""},
		{"get-json-stays-body", `{method:'GET',headers:{'Content-Type':'application/json'},body:{a:1}}`, target, `{"a":1}`, "application/json"},
		{"get-binary-stays-body", `{method:'GET',body:lx.utils.buffer.from('binary fixture')}`, target, "binary fixture", "application/x-www-form-urlencoded"},
		{"get-no-data-preserves-signed-query", `{method:'GET',body:undefined}`, target, "", ""},
		{"get-form-overrides-json-header", `{method:'GET',headers:{'Content-Type':'application/json'},form:{a:'ok'}}`, "https://metadata.example.test/probe?a=ok", "", "application/json"},
		{"post-form-string", `{method:'POST',form:'a=ok&encoded=fixture%2Fonly'}`, target, "a=ok&encoded=fixture%2Fonly", "application/x-www-form-urlencoded"},
		{"post-form-plain-string", `{method:'POST',form:'literal fixture'}`, target, "literal fixture", "application/x-www-form-urlencoded"},
		{"formdata-is-not-multipart", `{method:'POST',formData:{a:'ok',tags:['x','y']}}`, target, "a=ok&tags[]=x&tags[]=y", "application/x-www-form-urlencoded"},
		{"formdata-explicit-content-type", `{method:'POST',headers:{'Content-Type':'application/json'},formData:{a:'ok'}}`, target, "a=ok", "application/json"},
		{"nested-array-nullish-and-keys", `{method:'POST',form:{tags:['a','b'],nested:{'x y':['a/b',null,undefined]},empty:null}}`, target, "tags[]=a&tags[]=b&nested[x%20y][]=a%2Fb&nested[x%20y][]=&nested[x%20y][]=&empty=", "application/x-www-form-urlencoded"},
		{"array-of-objects", `{method:'POST',form:{items:[{id:1},{id:2}]}}`, target, "items[][id]=1&items[][id]=2", "application/x-www-form-urlencoded"},
		{"root-array-of-objects", `{method:'POST',form:[{id:1},{id:2}]}`, target, "id=1&id=2", "application/x-www-form-urlencoded"},
		{"array-hole", `{method:'POST',form:{items:[, 'x']}}`, target, "items[]=&items[]=x", "application/x-www-form-urlencoded"},
		{"truthy-fallback-preserved", `{method:'POST',body:null,form:{a:'ok'},formData:{ignored:'yes'}}`, target, "a=ok", "application/x-www-form-urlencoded"},
		{"explicit-body-precedence", `{method:'POST',body:'raw',form:{ignored:'yes'}}`, target, "raw", "application/x-www-form-urlencoded"},
		{"post-json-unchanged", `{method:'POST',headers:{'Content-Type':'application/json'},body:{items:[1,2]}}`, target, `{"items":[1,2]}`, "application/json"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := &fakeBroker{fn: func(_ context.Context, r netguard.Request) (netguard.Response, error) {
				if r.URL != tc.url || string(r.Body) != tc.body || r.Headers["content-type"] != tc.contentType {
					t.Errorf("request mismatch: URL=%q body=%q content-type=%q", r.URL, r.Body, r.Headers["content-type"])
				}
				return netguard.Response{StatusCode: 200, Body: []byte(`"fixture-ok"`)}, nil
			}}
			code := invokeScript(`return new Promise((resolve,reject)=>lx.request('` + target + `',` + tc.options + `,(err,resp)=>err?reject(err):resolve(resp.body)));`)
			result, err := withBroker(t.Context(), code, false, b)
			if err != nil || b.calls.Load() != 1 || string(result) != `"fixture-ok"` {
				t.Fatalf("isolated request fixture: calls=%d result=%s err=%v", b.calls.Load(), result, err)
			}
		})
	}
}

func TestV12SDKBufferNodeCompatibility(t *testing.T) {
	cases := []struct{ name, expression, want string }{
		{"arraybuffer-offset-length", `(()=>{const a=new Uint8Array([17,34,51,68]);return lx.utils.buffer.from(a.buffer,1,2)})()`, "2233"},
		{"arraybuffer-offset-to-end", `lx.utils.buffer.from(new Uint8Array([17,34,51,68]).buffer,2)`, "3344"},
		{"arraybuffer-fractional", `lx.utils.buffer.from(new Uint8Array([17,34,51,68]).buffer,1.8,1.8)`, "22"},
		{"arraybuffer-negative-fraction", `lx.utils.buffer.from(new Uint8Array([17,34,51,68]).buffer,-0.5,2)`, "1122"},
		{"arraybuffer-empty-at-end", `lx.utils.buffer.from(new Uint8Array([17,34]).buffer,2,0)`, ""},
		{"arraybuffer-negative-length-is-empty", `lx.utils.buffer.from(new Uint8Array([17,34]).buffer,1,-1)`, ""},
		{"arraybuffer-nan-length-is-empty", `lx.utils.buffer.from(new Uint8Array([17,34]).buffer,NaN,NaN)`, ""},
		{"typedarray-does-not-apply-offset", `lx.utils.buffer.from(new Uint8Array([17,34,51]),1,1)`, "112233"},
		{"buffer-json-shape", `lx.utils.buffer.from({type:'Buffer',data:[17,34,255]})`, "1122ff"},
		{"buffer-json-empty", `lx.utils.buffer.from({type:'Buffer',data:[]})`, ""},
		{"urlsafe-alphabet-in-base64", `lx.utils.buffer.from('-_8','base64')`, "fbff"},
		{"urlsafe-alphabet-padded", `lx.utils.buffer.from('-_8=','base64')`, "fbff"},
		{"base64url-case-insensitive", `lx.utils.buffer.from('-_8','BASE64URL')`, "fbff"},
		{"standard-base64-unchanged", `lx.utils.buffer.from('ZmFrZQ==','base64')`, "66616b65"},
		{"hex-odd-nibble", `lx.utils.buffer.from('abc','hex')`, "ab"},
		{"hex-stops-invalid", `lx.utils.buffer.from('abXXcd','hex')`, "ab"},
		{"hex-stops-space", `lx.utils.buffer.from('ab cd','hex')`, "ab"},
		{"hex-invalid-first-is-empty", `lx.utils.buffer.from('zz','hex')`, ""},
		{"hex-upper-case-valid", `lx.utils.buffer.from('ABcd','HEX')`, "abcd"},
		{"utf8-unchanged", `lx.utils.buffer.from('你好','utf8')`, "e4bda0e5a5bd"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			result, err := Invoke(t.Context(), invokeScript(`return lx.utils.buffer.bufToString(`+tc.expression+`,'hex');`), "wy", "musicUrl", nil, Options{})
			var got string
			if err != nil || json.Unmarshal(result, &got) != nil || got != tc.want {
				t.Fatalf("buffer fixture: got=%s want=%q err=%v", result, tc.want, err)
			}
		})
	}
}

func TestV12SDKBufferBoundsStayEnforced(t *testing.T) {
	for _, expression := range []string{
		`lx.utils.buffer.from(new ArrayBuffer(4),5,0)`,
		`lx.utils.buffer.from(new ArrayBuffer(4),1,4)`,
		`lx.utils.buffer.from(new ArrayBuffer(4),-1,1)`,
		`lx.utils.buffer.from(new ArrayBuffer(4),Infinity,1)`,
		`lx.utils.buffer.from(new ArrayBuffer(4),1,Infinity)`,
		`lx.utils.buffer.from({type:'Buffer',data:'not an array'})`,
		`lx.utils.buffer.from('%invalid','base64')`,
	} {
		_, err := Invoke(t.Context(), invokeScript(`return `+expression+`;`), "wy", "musicUrl", nil, Options{})
		if err == nil {
			t.Errorf("invalid input unexpectedly accepted: %s", expression)
		}
	}
	for _, expression := range []string{
		`lx.utils.buffer.from(new ArrayBuffer(1048577),0,1)`,
		`lx.utils.buffer.from({type:'Buffer',data:new Array(1048577).fill(0)})`,
		`lx.utils.buffer.from('z'.repeat(2097153),'hex')`,
		`lx.utils.buffer.from('A'.repeat(2097153),'base64')`,
	} {
		_, err := Invoke(t.Context(), invokeScript(`return `+expression+`;`), "wy", "musicUrl", nil, Options{})
		if !errors.Is(err, ErrLimit) {
			t.Errorf("input byte limit changed: err=%v", err)
		}
	}
}

func TestV12SDKRequestEncodingLimitsAndNoHostEscalation(t *testing.T) {
	for _, options := range []string{
		`{method:'GET',body:{x:'a'.repeat(8200)}}`,
		`{method:'POST',form:'x='+ 'a'.repeat(65536)}`,
		`{method:'POST',form:{items:new Array(1025).fill('x')}}`,
		`{method:'POST',form:{a:{a:{a:{a:{a:{a:{a:'deep'}}}}}}}}`,
	} {
		b := &fakeBroker{fn: func(context.Context, netguard.Request) (netguard.Response, error) {
			t.Error("oversized form/query reached Broker")
			return netguard.Response{}, nil
		}}
		_, err := withBroker(t.Context(), invokeScript(`return new Promise((resolve,reject)=>lx.request('https://metadata.example.test/',`+options+`,(err,resp)=>err?reject(err):resolve(resp.body)));`), false, b)
		if err == nil || b.calls.Load() != 0 {
			t.Errorf("encoding budget changed: calls=%d err=%v", b.calls.Load(), err)
		}
	}
	// 编码改动不能使脚本获得新的父进程权限；公网 HTTP 默认仍在连接前被拒绝。
	code := invokeScript(`return new Promise(resolve=>lx.request('http://8.8.8.8/fixture',{body:{a:'ok'},allowPublicHTTP:true,rejectUnauthorized:false},(err,resp)=>resolve({blocked:!!err,empty:resp===null})));`)
	result, err := Invoke(t.Context(), code, "wy", "musicUrl", nil, Options{})
	if err != nil || !strings.Contains(string(result), `"blocked":true`) || !strings.Contains(string(result), `"empty":true`) {
		t.Fatalf("script raised host permissions: result=%s err=%v", result, err)
	}
}

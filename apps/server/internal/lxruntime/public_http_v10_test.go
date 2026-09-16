package lxruntime

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"melora/internal/netguard"
)

func TestV10SyntheticScriptCannotGrantItselfHTTPOrTLSPermissions(t *testing.T) {
	code := invokeScript(`return await new Promise(resolve=>lx.request('http://8.8.8.8/metadata',{
   allowPublicHTTP:true,AllowPublicHTTP:true,allowHTTPHosts:['8.8.8.8'],rejectUnauthorized:false,agent:{},proxy:'http://127.0.0.1:1'
 },(err,resp,body)=>resolve({blocked:!!err,empty:resp==null&&body==null})));`)
	// 父进程默认策略在 DNS/连接前拒绝 HTTP；不能向这个测试 URL 发请求。
	result, err := Invoke(t.Context(), code, "wy", "musicUrl", nil, Options{})
	if err != nil || !strings.Contains(string(result), `"blocked":true`) || !strings.Contains(string(result), `"empty":true`) {
		t.Fatalf("script raised parent permissions: %s %v", result, err)
	}
}

func TestV10PublicHTTPParentStillRejectsMaliciousSyntheticRequests(t *testing.T) {
	for _, raw := range []string{"http://127.0.0.1/", "http://169.254.169.254/latest/meta-data/", "http://100.100.100.200/", "http://168.63.129.16/", "http://[::ffff:8.8.8.8]/", "http://8.8.8.8:3780/", "https://8.8.8.8:5174/"} {
		t.Run(raw, func(t *testing.T) {
			encoded, _ := json.Marshal(raw)
			code := invokeScript(`return await new Promise(resolve=>lx.request(` + string(encoded) + `,{allowPublicHTTP:true,rejectUnauthorized:false},(err,resp,body)=>resolve({blocked:!!err,empty:resp==null&&body==null})));`)
			result, err := Invoke(t.Context(), code, "wy", "musicUrl", nil, Options{AllowPublicHTTP: true})
			if err != nil || !strings.Contains(string(result), `"blocked":true`) || !strings.Contains(string(result), `"empty":true`) {
				t.Fatalf("malicious request not blocked: %s %v", result, err)
			}
		})
	}
}

func TestV10SyntheticMetadataHTTPUsesParentBrokerCallback(t *testing.T) {
	// 进程协议没有网络权限字段；只把 request URL/body 交父进程，响应为内存 fixture。
	b := &fakeBroker{fn: func(_ context.Context, r netguard.Request) (netguard.Response, error) {
		if r.URL != "http://metadata.example.com/api" || r.Method != "POST" || string(r.Body) != "id=fixture" {
			t.Error("HTTP metadata IPC changed")
		}
		return netguard.Response{StatusCode: 200, Headers: map[string]string{"Content-Type": "application/json"}, Body: []byte(`{"ok":true}`)}, nil
	}}
	code := invokeScript(`return await new Promise(resolve=>lx.request('http://metadata.example.com/api',{method:'POST',form:{id:'fixture'},allowPublicHTTP:false},(err,resp,body)=>resolve({ok:!err&&body.ok,status:resp.statusCode})));`)
	result, err := withBroker(t.Context(), code, false, b)
	if err != nil || b.calls.Load() != 1 || !strings.Contains(string(result), `"ok":true`) {
		t.Fatalf("synthetic HTTP callback: %s %v", result, err)
	}
}

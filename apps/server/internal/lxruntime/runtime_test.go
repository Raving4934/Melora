package lxruntime

import (
	"bytes"
	"compress/zlib"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"melora/internal/netguard"
)

func TestMain(m *testing.M) {
	directory, err := os.MkdirTemp("", "melora-lx-worker-test-")
	if err != nil {
		fmt.Fprintln(os.Stderr, "cannot create worker build directory")
		os.Exit(1)
	}
	binary := filepath.Join(directory, "worker")
	cmd := exec.Command("go", "build", "-o", binary, "./testdata/worker")
	cmd.Env = append(os.Environ(), "CGO_ENABLED=0")
	if output, err := cmd.CombinedOutput(); err != nil {
		fmt.Fprintf(os.Stderr, "cannot build real worker: %v\n%s", err, output)
		os.RemoveAll(directory)
		os.Exit(1)
	}
	executablePath = func() (string, error) { return binary, nil }
	code := m.Run()
	os.RemoveAll(directory)
	os.Exit(code)
}

const initScript = `lx.send(lx.EVENT_NAMES.inited,{sources:{wy:{name:'WY',type:'music',actions:['musicUrl'],qualitys:['128k','320k']}}});`

func invokeScript(body string) string {
	return `lx.on(lx.EVENT_NAMES.request,async ({source,action,info})=>{` + body + `});` + initScript
}
func call(t *testing.T, code string) json.RawMessage {
	t.Helper()
	result, err := Invoke(context.Background(), code, "wy", "musicUrl", map[string]any{"type": "128k", "musicInfo": map[string]any{"id": "licensed-test"}}, Options{})
	if err != nil {
		t.Fatalf("worker failed: %v", err)
	}
	return result
}

type fakeBroker struct {
	fn    func(context.Context, netguard.Request) (netguard.Response, error)
	calls atomic.Int32
}

func (b *fakeBroker) Do(ctx context.Context, r netguard.Request) (netguard.Response, error) {
	b.calls.Add(1)
	return b.fn(ctx, r)
}
func (b *fakeBroker) Close() {}
func withBroker(ctx context.Context, code string, inspect bool, b metadataBroker) (json.RawMessage, error) {
	return executeWithBroker(ctx, invocation{Code: code, Inspect: inspect, Platform: "wy", Action: "musicUrl", Info: json.RawMessage(`{"type":"128k","musicInfo":{"id":"safe"}}`)}, b)
}

func TestRealWorkerPromiseBuffersCryptoAndSDK(t *testing.T) {
	code := `/**
 * @name unit-test
 * @version 0.1
 */
 ` + invokeScript(`
 await new Promise(resolve=>setTimeout(resolve,5));
 const b=lx.utils.buffer.from('你好','utf8');
 const encrypted=lx.utils.crypto.aesEncrypt(lx.utils.buffer.from('hello'),'aes-128-cbc',lx.utils.buffer.from('0123456789abcdef'),lx.utils.buffer.from('0123456789abcdef'));
 const encoded=new TextEncoder().encode('中文');
 const compressed=await lx.utils.zlib.deflate(b);
 const inflated=await lx.utils.zlib.inflate(compressed);
 return {sdk:lx.version,env:lx.env,name:lx.currentScriptInfo.name,version:lx.currentScriptInfo.version,
 raw:lx.currentScriptInfo.rawScript.includes('@name unit-test'),md5:lx.utils.crypto.md5('hello'),
 text:lx.utils.buffer.bufToString(b),base64:b.toString('base64'),hex:encrypted.toString('hex'),
 random:lx.utils.crypto.randomBytes(16).length,decoded:new TextDecoder().decode(encoded),
 inflated:inflated.toString(),atob:atob(btoa('hello')),source,action,id:info.musicInfo.id};`)
	var got map[string]any
	if err := json.Unmarshal(call(t, code), &got); err != nil {
		t.Fatal(err)
	}
	block, _ := aes.NewCipher([]byte("0123456789abcdef"))
	plain := append([]byte("hello"), []byte{11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11}...)
	encrypted := make([]byte, 16)
	cipher.NewCBCEncrypter(block, []byte("0123456789abcdef")).CryptBlocks(encrypted, plain)
	for key, want := range map[string]any{"sdk": "2.0.0", "env": "desktop", "name": "unit-test", "version": "0.1", "raw": true, "md5": "5d41402abc4b2a76b9719d911017c592", "text": "你好", "base64": "5L2g5aW9", "hex": hex.EncodeToString(encrypted), "random": float64(16), "decoded": "中文", "inflated": "你好", "atob": "hello", "source": "wy", "action": "musicUrl", "id": "licensed-test"} {
		if got[key] != want {
			t.Errorf("%s: got %v want %v", key, got[key], want)
		}
	}
}
func TestRealWorkerMinimalEnvironmentAndNoHostAPIs(t *testing.T) {
	t.Setenv("TRIM_TEST_TOKEN", "SHOULD-NEVER-REACH-WORKER")
	t.Setenv("API_TOKEN", "SECRET")
	t.Setenv("HTTP_PROXY", "http://127.0.0.1:9")
	got := string(call(t, invokeScript(`console.log('token=SECRET');console.error('https://secret/');return [typeof require,typeof fs,typeof process,typeof fetch,typeof XMLHttpRequest,typeof WebSocket];`)))
	if got != `["undefined","undefined","undefined","undefined","undefined","undefined"]` {
		t.Fatal(got)
	}
	for _, name := range []string{"require", "fs", "process"} {
		_, err := Invoke(context.Background(), invokeScript(`return `+name+`;`), "wy", "musicUrl", nil, Options{})
		if err == nil || strings.Contains(err.Error(), "SECRET") {
			t.Fatalf("host API accepted: %s %v", name, err)
		}
	}
}
func TestRealWorkerInfiniteLoopCancellationAndRecovery(t *testing.T) {
	for _, script := range []string{`while(true){}`, invokeScript(`while(true){}`), invokeScript(`return new Promise(()=>{});`), `Promise.resolve().then(function loop(){return Promise.resolve().then(loop)});` + initScript} {
		ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
		started := time.Now()
		_, err := Inspect(ctx, script, Options{})
		cancel()
		// Invoke 专门覆盖 handler 内死循环。
		if strings.Contains(script, "lx.on") {
			ctx, cancel = context.WithTimeout(context.Background(), 120*time.Millisecond)
			_, err = Invoke(ctx, script, "wy", "musicUrl", nil, Options{})
			cancel()
		}
		if !errors.Is(err, ErrTimeout) || time.Since(started) > 2*time.Second {
			t.Fatalf("loop not killed: %v elapsed=%v", err, time.Since(started))
		}
	}
	if string(call(t, invokeScript(`return 'healthy';`))) != `"healthy"` {
		t.Fatal("parent did not recover")
	}
}
func TestRealWorkerMemoryHardLimit(t *testing.T) {
	started := time.Now()
	_, err := Inspect(context.Background(), `const retained=[];while(true){retained.push(new Uint8Array(1024*1024));}`, Options{})
	if !errors.Is(err, ErrResource) {
		t.Fatalf("unbounded memory not stopped: %v", err)
	}
	if time.Since(started) > 7*time.Second {
		t.Fatal("memory task exceeded budget")
	}
	if string(call(t, invokeScript(`return 42;`))) != "42" {
		t.Fatal("worker OOM damaged parent")
	}
}
func TestErrorsNeverExposeScriptSecrets(t *testing.T) {
	for _, code := range []string{`throw new Error('https://secret/private?token=SECRET');`, invokeScript(`throw {message:'SECRET',toString(){return 'PRIVATE-SECRET'}};`), `const token='SECRET'; syntax invalid`, invokeScript(`return {toJSON(){throw new Error('SECRET')}};`)} {
		_, err := Invoke(context.Background(), code, "wy", "musicUrl", nil, Options{})
		if err == nil || strings.Contains(err.Error(), "SECRET") || strings.Contains(err.Error(), "http") {
			t.Fatalf("unsafe error: %v", err)
		}
	}
}
func TestRealWorkerParentBrokerCallbackAndRequestBodies(t *testing.T) {
	for _, kind := range []string{"body", "form", "formData"} {
		t.Run(kind, func(t *testing.T) {
			options := `{method:'POST',headers:{'X-Test':'value'},` + kind + `:{hello:'world'}}`
			b := &fakeBroker{fn: func(ctx context.Context, r netguard.Request) (netguard.Response, error) {
				if r.URL != "https://metadata.example.com/api" || r.Method != "POST" || r.Headers["x-test"] != "value" {
					t.Errorf("request mismatch: %+v", r)
				}
				if !strings.Contains(string(r.Body), "hello") || !strings.Contains(string(r.Body), "world") {
					t.Error("missing form/body")
				}
				return netguard.Response{StatusCode: 200, StatusMessage: "OK", Headers: map[string]string{"content-type": "application/json"}, Body: []byte(`{"url":"https://media.example.com/authorized.mp3"}`)}, nil
			}}
			code := invokeScript(`return await new Promise((resolve,reject)=>lx.request('https://metadata.example.com/api',` + options + `,(err,resp,body)=>{if(err)return reject(err);resolve({same:resp.body===body,raw:lx.utils.buffer.bufToString(resp.raw),bytes:resp.bytes,status:resp.statusCode,url:body.url});}));`)
			result, err := withBroker(context.Background(), code, false, b)
			if err != nil {
				t.Fatal(err)
			}
			var got map[string]any
			_ = json.Unmarshal(result, &got)
			if got["same"] != true || got["status"] != float64(200) || b.calls.Load() != 1 || got["url"] != "https://media.example.com/authorized.mp3" || got["bytes"] == nil || got["raw"] == nil {
				t.Fatalf("callback contract: %s", result)
			}
		})
	}
}
func TestRealWorkerAbortCancelsParentRequest(t *testing.T) {
	canceled := make(chan struct{}, 1)
	b := &fakeBroker{fn: func(ctx context.Context, r netguard.Request) (netguard.Response, error) {
		<-ctx.Done()
		canceled <- struct{}{}
		return netguard.Response{}, ctx.Err()
	}}
	code := invokeScript(`let called=false;const abort=lx.request('https://metadata.example.com/api',{},()=>{called=true});setTimeout(abort,10);await new Promise(resolve=>setTimeout(resolve,40));return called;`)
	result, err := withBroker(context.Background(), code, false, b)
	if err != nil || string(result) != "false" {
		t.Fatalf("abort: %s %v", result, err)
	}
	select {
	case <-canceled:
	case <-time.After(time.Second):
		t.Fatal("broker not canceled")
	}
}
func TestRealWorkerRequestAndTimerLimits(t *testing.T) {
	b := &fakeBroker{fn: func(ctx context.Context, r netguard.Request) (netguard.Response, error) {
		return netguard.Response{Body: []byte(`{}`)}, nil
	}}
	for _, code := range []string{`for(let i=0;i<13;i++)lx.request('https://metadata.example.com/',{},()=>{});` + initScript, `for(let i=0;i<65;i++)setTimeout(()=>{},5000);` + initScript, invokeScript(`return 'x'.repeat(300000);`)} {
		_, err := withBroker(context.Background(), code, false, b)
		if !errors.Is(err, ErrLimit) {
			t.Fatalf("limit not enforced: %v", err)
		}
	}
}
func TestRealWorkerProductionBrokerRejectsPrivateAndHTTP(t *testing.T) {
	for _, raw := range []string{"https://127.0.0.1/private", "https://169.254.169.254/latest/meta-data/", "http://example.com/"} {
		encoded, _ := json.Marshal(raw)
		result := call(t, invokeScript(`return await new Promise(resolve=>lx.request(`+string(encoded)+`,{},(err,resp,body)=>resolve({blocked:!!err,empty:resp==null&&body==null,message:err&&err.message})));`))
		if !strings.Contains(string(result), `"blocked":true`) || !strings.Contains(string(result), `"empty":true`) {
			t.Fatalf("private network accepted: %s", result)
		}
	}
}
func TestRealWorkerConcurrencyLimit(t *testing.T) {
	var wg sync.WaitGroup
	var failures atomic.Int32
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
			defer cancel()
			_, err := Inspect(ctx, `while(true){}`, Options{})
			if !errors.Is(err, ErrTimeout) {
				failures.Add(1)
			}
		}()
	}
	wg.Wait()
	if failures.Load() != 0 || len(workerSlots) != 0 {
		t.Fatalf("worker slots leaked: failures=%d active=%d", failures.Load(), len(workerSlots))
	}
}
func TestRepositorySourcesRegisterWithNetworkDenied(t *testing.T) {
	directory := filepath.Join("..", "..", "..", "..", ".lx 音源")
	files, err := filepath.Glob(filepath.Join(directory, "*.js"))
	if err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Skip("local ignored source references are not available")
	}
	if len(files) != 7 {
		t.Fatalf("expected 7 local references: %d", len(files))
	}
	for _, path := range files {
		t.Run(filepath.Base(path), func(t *testing.T) {
			code, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			b := &fakeBroker{fn: func(context.Context, netguard.Request) (netguard.Response, error) {
				return netguard.Response{}, netguard.ErrPolicy
			}}
			result, err := withBroker(context.Background(), string(code), true, b)
			if err != nil {
				t.Fatalf("registration regression: %v; blocked metadata requests=%d", err, b.calls.Load())
			}
			var d Descriptor
			if json.Unmarshal(result, &d) != nil || !validDescriptor(d) {
				t.Fatal("invalid descriptor")
			}
			keys := make([]string, 0, len(d.Sources))
			for key := range d.Sources {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			t.Logf("registration only: platforms=%v; blocked metadata requests=%d; no musicUrl invocation", keys, b.calls.Load())
		})
	}
}

func TestSourceMapsDoNotLoadFilesAndUpdateEventsAreInert(t *testing.T) {
	code := invokeScript(`await lx.send(lx.EVENT_NAMES.updateAlert,{updateUrl:'file:///etc/passwd',log:'SECRET'});return 'safe';`) + "\n//# sourceMappingURL=file:///etc/passwd\n"
	if string(call(t, code)) != `"safe"` {
		t.Fatal("source maps/update event altered execution")
	}
}
func TestBoundedBuffersAndCompressedBomb(t *testing.T) {
	for _, body := range []string{`return lx.utils.crypto.randomBytes(1024*1024+1);`, `return lx.utils.buffer.from('x'.repeat(1024*1024+1));`} {
		_, err := Invoke(context.Background(), invokeScript(body), "wy", "musicUrl", nil, Options{})
		if !errors.Is(err, ErrLimit) {
			t.Fatalf("buffer limit not enforced: %v", err)
		}
	}
	var compressed bytes.Buffer
	writer := zlib.NewWriter(&compressed)
	_, _ = writer.Write(bytes.Repeat([]byte("x"), (1<<20)+1))
	_ = writer.Close()
	data := base64.StdEncoding.EncodeToString(compressed.Bytes())
	_, err := Invoke(context.Background(), invokeScript(`return await lx.utils.zlib.inflate(lx.utils.buffer.from('`+data+`','base64'));`), "wy", "musicUrl", nil, Options{})
	if !errors.Is(err, ErrLimit) {
		t.Fatalf("inflate exceeded bound: %v", err)
	}
}
func TestRSAAndOtherAESModes(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 1024)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	keyJSON, _ := json.Marshal(string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der})))
	result := call(t, invokeScript(`return lx.utils.crypto.rsaEncrypt(lx.utils.buffer.from('hello'),`+string(keyJSON)+`).toString('hex');`))
	expected := new(big.Int).Exp(new(big.Int).SetBytes([]byte("hello")), big.NewInt(int64(key.E)), key.N).FillBytes(make([]byte, 128))
	var got string
	_ = json.Unmarshal(result, &got)
	if got != hex.EncodeToString(expected) {
		t.Fatal("RSA_NO_PADDING mismatch")
	}
	for _, mode := range []string{"aes-128-ecb", "aes-128-ctr"} {
		iv := `null`
		if strings.HasSuffix(mode, "ctr") {
			iv = `lx.utils.buffer.from('0123456789abcdef')`
		}
		if len(call(t, invokeScript(`return lx.utils.crypto.aesEncrypt(lx.utils.buffer.from('hello'),'`+mode+`',lx.utils.buffer.from('0123456789abcdef'),`+iv+`).toString('hex');`))) < 3 {
			t.Fatal("empty AES output")
		}
	}
	_, err = Invoke(context.Background(), invokeScript(`return lx.utils.crypto.aesEncrypt('x','aes-128-gcm','0123456789abcdef','SECRET');`), "wy", "musicUrl", nil, Options{})
	if !errors.Is(err, ErrUnsupported) || strings.Contains(err.Error(), "SECRET") {
		t.Fatalf("unsupported crypto disclosure: %v", err)
	}
}
func TestCancelWhileBrokerIsPendingReleasesWorker(t *testing.T) {
	started := make(chan struct{})
	b := &fakeBroker{fn: func(ctx context.Context, r netguard.Request) (netguard.Response, error) {
		close(started)
		<-ctx.Done()
		return netguard.Response{}, ctx.Err()
	}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	go func() {
		_, err := withBroker(ctx, invokeScript(`return new Promise((resolve,reject)=>lx.request('https://metadata.example.com/',{},(err,resp)=>err?reject(err):resolve(resp.body)));`), false, b)
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("broker not started")
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, ErrTimeout) {
			t.Fatalf("wrong cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("worker or broker not canceled")
	}
}

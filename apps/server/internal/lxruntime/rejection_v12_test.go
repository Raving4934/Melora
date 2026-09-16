package lxruntime

import (
	"errors"
	"testing"
	"time"
)

// LX 的主请求只等待自身 Promise，未等待的更新检查失败不得覆盖成功解析。
func TestV12BackgroundRejectionDoesNotPoisonMainRequest(t *testing.T) {
	for _, body := range []string{
		`Promise.reject(new Error('private-background-marker')); return 'https://media.example.test/ok.mp3';`,
		`setTimeout(()=>{Promise.reject(new Error('private-background-marker'))},0); await new Promise(r=>setTimeout(r,20)); return 'https://media.example.test/ok.mp3';`,
	} {
		result, err := Invoke(t.Context(), invokeScript(body), "wy", "musicUrl", nil, Options{})
		if err != nil || string(result) != `"https://media.example.test/ok.mp3"` {
			t.Fatalf("background rejection poisoned handler: result bytes=%d error=%v", len(result), err)
		}
	}
}
func TestV12MainRejectionAndThrowRemainRedacted(t *testing.T) {
	for _, body := range []string{`return Promise.reject(new Error('private-handler-marker'));`, `throw new Error('private-handler-marker');`} {
		start := time.Now()
		_, err := Invoke(t.Context(), invokeScript(body), "wy", "musicUrl", nil, Options{})
		if !errors.Is(err, ErrScript) || err.Error() != ErrScript.Error() || time.Since(start) > time.Second {
			t.Fatalf("main rejection not handled safely: %v", err)
		}
	}
}

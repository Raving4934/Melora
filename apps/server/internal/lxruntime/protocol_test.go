package lxruntime

import (
	"context"
	"errors"
	"io"
	"sync"
	"testing"
	"time"

	"melora/internal/netguard"
)

type closedWorkerInput struct {
	once   sync.Once
	closed chan struct{}
}

func (w *closedWorkerInput) Write([]byte) (int, error) {
	w.once.Do(func() { close(w.closed) })
	return 0, io.ErrClosedPipe
}

func TestFinalResultSurvivesClosedReplyPipe(t *testing.T) {
	for _, result := range []message{{Kind: "result", Error: "limit"}, {Kind: "result", Result: []byte(`"completed"`)}} {
		input := &closedWorkerInput{closed: make(chan struct{})}
		out, writer := io.Pipe()
		defer out.Close()
		defer writer.Close()
		producer := make(chan struct{})
		go func() {
			defer close(producer)
			defer writer.Close()
			request := message{Kind: "request", ID: 1, Request: &netguard.Request{URL: "https://metadata.example.com/"}}
			if writeFrame(writer, request) != nil {
				return
			}
			// 确定性制造：父进程先遇到回包 EPIPE，最终结果随后才能被读取。
			<-input.closed
			_ = writeFrame(writer, result)
		}()
		broker := &fakeBroker{fn: func(context.Context, netguard.Request) (netguard.Response, error) {
			return netguard.Response{Body: []byte(`{}`)}, nil
		}}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		actual, err := exchange(ctx, input, out, broker)
		cancel()
		if result.Error == "limit" {
			if !errors.Is(err, ErrLimit) {
				t.Fatalf("EPIPE hid limit: %v", err)
			}
		} else if err != nil || string(actual) != `"completed"` {
			t.Fatalf("EPIPE hid success: %s %v", actual, err)
		}
		<-producer
	}
}

func TestClosedReplyPipeWithoutFinalResultIsStillResourceFailure(t *testing.T) {
	input := &closedWorkerInput{closed: make(chan struct{})}
	out, writer := io.Pipe()
	defer out.Close()
	go func() {
		_ = writeFrame(writer, message{Kind: "request", ID: 1, Request: &netguard.Request{URL: "https://metadata.example.com/"}})
		<-input.closed
		_ = writer.Close() // 真正异常退出，没有伪造 completed 或 limit。
	}()
	broker := &fakeBroker{fn: func(context.Context, netguard.Request) (netguard.Response, error) { return netguard.Response{}, nil }}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := exchange(ctx, input, out, broker); !errors.Is(err, ErrResource) {
		t.Fatalf("crash not preserved: %v", err)
	}
}

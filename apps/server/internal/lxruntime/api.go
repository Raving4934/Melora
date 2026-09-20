// Package lxruntime 在独立子进程内执行 LX 脚本；网络由父进程代理。
// 这不是完整 OS 沙箱：不提供 namespace/seccomp 或独立 UID 隔离，
// 不应将进程边界等同于可抵御 JavaScript 引擎原生漏洞的安全边界。
package lxruntime

import (
	"context"
	"encoding/json"
	"errors"
	"melora/internal/netguard"
)

const WorkerArgument = "--lx-worker"

type Options = netguard.Options
type Source struct {
	Name     string   `json:"name"`
	Type     string   `json:"type"`
	Actions  []string `json:"actions"`
	Qualitys []string `json:"qualitys"`
}
type Descriptor struct {
	Status  bool              `json:"status"`
	Sources map[string]Source `json:"sources"`
}

var (
	ErrScript      = errors.New("LX 脚本执行失败")
	ErrTimeout     = errors.New("LX 脚本执行超时或已取消")
	ErrResource    = errors.New("LX 子进程达到资源限制或异常退出")
	ErrLimit       = errors.New("LX 输入、请求或结果超过限制")
	ErrProtocol    = errors.New("LX 子进程协议无效")
	ErrUnsupported = errors.New("LX 脚本使用不支持的 API 或操作")
	ErrNetwork     = errors.New("LX 元数据网络请求被安全策略拒绝或失败")
)

func Inspect(ctx context.Context, code string, opts Options) (Descriptor, error) {
	result, err := execute(ctx, invocation{Code: code, Inspect: true}, opts)
	if err != nil {
		return Descriptor{}, err
	}
	var descriptor Descriptor
	if json.Unmarshal(result, &descriptor) != nil || !validDescriptor(descriptor) {
		return Descriptor{}, ErrProtocol
	}
	return descriptor, nil
}

func Invoke(ctx context.Context, code, platform, action string, info map[string]any, opts Options) (json.RawMessage, error) {
	encoded, err := json.Marshal(info)
	if err != nil || len(encoded) > maxInfoBytes {
		return nil, ErrLimit
	}
	return execute(ctx, invocation{Code: code, Platform: platform, Action: action, Info: encoded}, opts)
}

// ErrClosed 不包含脚本或调用细节，Manager 关闭后不再启动子进程。
var ErrClosed = errors.New("LX 会话池已关闭")

// NewPool 创建 Manager 专属的有界会话池，调用方必须在 Manager.Close 中关闭。
// 不创建全局池；同一 Pool 的相同 code/options 复用 JS 状态，容量/空闲/CPU 淘汰后会重新初始化。
func NewPool() *Pool { return newPool(defaultPoolLimits) }

func (p *Pool) Inspect(ctx context.Context, code string, opts Options) (Descriptor, error) {
	result, err := p.execute(ctx, invocation{Code: code, Inspect: true}, opts)
	if err != nil {
		return Descriptor{}, err
	}
	var descriptor Descriptor
	if json.Unmarshal(result, &descriptor) != nil || !validDescriptor(descriptor) {
		return Descriptor{}, ErrProtocol
	}
	return descriptor, nil
}

func (p *Pool) Invoke(ctx context.Context, code, platform, action string, info map[string]any, opts Options) (json.RawMessage, error) {
	encoded, err := json.Marshal(info)
	if err != nil || len(encoded) > maxInfoBytes {
		return nil, ErrLimit
	}
	return p.execute(ctx, invocation{Code: code, Platform: platform, Action: action, Info: encoded}, opts)
}

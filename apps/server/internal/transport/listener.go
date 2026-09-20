// Package transport 分离独立 TCP 与 fnOS 本机网关 Socket，二者不会同时监听。
package transport

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"time"
)

func Listen(address, socket string) (net.Listener, error) {
	if socket == "" {
		return net.Listen("tcp", address)
	}
	if info, err := os.Lstat(socket); err == nil {
		if info.Mode()&os.ModeSocket == 0 {
			return nil, errors.New("Socket 路径被非 Socket 文件占用，拒绝覆盖")
		}
		stat, ok := info.Sys().(*syscall.Stat_t)
		if !ok || stat.Uid != uint32(os.Geteuid()) {
			return nil, errors.New("遗留 Socket 不属于当前包用户，拒绝覆盖")
		}
		conn, dialErr := net.DialTimeout("unix", socket, 200*time.Millisecond)
		if dialErr == nil {
			conn.Close()
			return nil, errors.New("应用 Socket 已有服务监听")
		}
		if !errors.Is(dialErr, syscall.ECONNREFUSED) {
			return nil, errors.New("无法确认遗留 Socket 已停止，拒绝覆盖")
		}
		current, err := os.Lstat(socket)
		if err != nil || !os.SameFile(info, current) {
			return nil, errors.New("Socket 路径在检查期间发生变化")
		}
		root, err := os.OpenRoot(filepath.Dir(socket))
		if err != nil {
			return nil, err
		}
		err = root.Remove(filepath.Base(socket))
		root.Close()
		if err != nil {
			return nil, err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: socket, Net: "unix"})
	if err != nil {
		return nil, err
	}
	// 包用户/组与系统特权网关可访问；不给其他本机用户伪造 X-Trim-* 的入口。
	if err = os.Chmod(socket, 0660); err != nil {
		listener.Close()
		return nil, err
	}
	return listener, nil
}

// Healthcheck 只做有界只读 HTTP 检查，不初始化数据库、不创建目录、不改变任务。
func Healthcheck(address, socket, basePath string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 1200*time.Millisecond)
	defer cancel()
	tr := &http.Transport{Proxy: nil}
	defer tr.CloseIdleConnections()
	host := address
	if socket != "" {
		tr.DialContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "unix", socket)
		}
		host = "localhost"
	} else {
		name, port, err := net.SplitHostPort(address)
		if err != nil {
			return err
		}
		if name == "" || name == "0.0.0.0" {
			name = "127.0.0.1"
		}
		if name == "::" {
			name = "::1"
		}
		host = net.JoinHostPort(name, port)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://"+host+basePath+"/health", nil)
	if err != nil {
		return err
	}
	response, err := (&http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("健康探针不跟随重定向") }}).Do(req)
	if err != nil {
		return errors.New("服务尚未响应健康检查")
	}
	defer response.Body.Close()
	var body struct {
		Status string `json:"status"`
		Name   string `json:"name"`
	}
	if response.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&body) != nil || body.Status != "ok" || body.Name != "Melora" {
		return errors.New("服务健康检查未通过")
	}
	return nil
}

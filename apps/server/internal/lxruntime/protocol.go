package lxruntime

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"melora/internal/netguard"
)

const (
	maxCodeBytes   = 2 << 20
	maxInfoBytes   = 128 << 10
	maxResultBytes = 256 << 10
	maxFrameBytes  = 14 << 20 // 初始脚本 JSON 转义的最坏情况；其余帧另有更小边界。
)

type invocation struct {
	Session  bool            `json:"session,omitempty"`
	CallID   uint64          `json:"callId,omitempty"`
	Code     string          `json:"code"`
	Inspect  bool            `json:"inspect"`
	Platform string          `json:"platform"`
	Action   string          `json:"action"`
	Info     json.RawMessage `json:"info"`
}

func validDescriptor(d Descriptor) bool {
	if !d.Status || len(d.Sources) == 0 || len(d.Sources) > 16 {
		return false
	}
	for key, s := range d.Sources {
		if len(key) == 0 || len(key) > 32 || len(s.Name) == 0 || len(s.Name) > 256 || s.Type != "music" || len(s.Actions) == 0 || len(s.Actions) > 8 || len(s.Qualitys) > 16 {
			return false
		}
		for _, a := range s.Actions {
			if a != "musicUrl" && a != "lyric" && a != "pic" {
				return false
			}
		}
		for _, q := range s.Qualitys {
			if len(q) == 0 || len(q) > 32 {
				return false
			}
		}
	}
	return true
}
func writeFrame(w io.Writer, v any) error {
	b, err := json.Marshal(v)
	if err != nil || len(b) > maxFrameBytes {
		return ErrLimit
	}
	var size [4]byte
	binary.BigEndian.PutUint32(size[:], uint32(len(b)))
	if _, err = w.Write(size[:]); err != nil {
		return err
	}
	_, err = w.Write(b)
	return err
}
func readFrame(r io.Reader, v any, limit int) error {
	var size [4]byte
	if _, err := io.ReadFull(r, size[:]); err != nil {
		return err
	}
	n := binary.BigEndian.Uint32(size[:])
	if n == 0 || n > uint32(limit) {
		return ErrLimit
	}
	b := make([]byte, n)
	if _, err := io.ReadFull(r, b); err != nil {
		return err
	}
	if json.Unmarshal(b, v) != nil {
		return ErrProtocol
	}
	return nil
}

type message struct {
	CallID   uint64             `json:"callId,omitempty"`
	Invoke   *invocation        `json:"invoke,omitempty"`
	Retire   bool               `json:"retire,omitempty"`
	Kind     string             `json:"kind"`
	ID       int                `json:"id,omitempty"`
	Request  *netguard.Request  `json:"request,omitempty"`
	Response *netguard.Response `json:"response,omitempty"`
	Result   json.RawMessage    `json:"result,omitempty"`
	Error    string             `json:"error,omitempty"`
	API      string             `json:"api,omitempty"`
}

func wireError(err error) string {
	switch {
	case errors.Is(err, ErrResource):
		return "resource"
	case errors.Is(err, ErrUnsupported):
		return "unsupported"
	case errors.Is(err, ErrLimit):
		return "limit"
	case errors.Is(err, ErrNetwork):
		return "network"
	case errors.Is(err, ErrProtocol):
		return "protocol"
	default:
		return "script"
	}
}
func decodeError(code, api string) error {
	switch code {
	case "resource":
		return ErrResource
	case "unsupported":
		if safeAPIName(api) {
			return &UnsupportedError{API: api}
		}
		return ErrUnsupported
	case "limit":
		return ErrLimit
	case "network":
		return ErrNetwork
	case "protocol":
		return ErrProtocol
	default:
		return ErrScript
	}
}

// UnsupportedError 只包含宿主白名单选出的 API 名，不透传 JS 异常消息。
type UnsupportedError struct{ API string }

func (e *UnsupportedError) Error() string { return "LX 不支持 API：" + e.API }
func (e *UnsupportedError) Unwrap() error { return ErrUnsupported }
func safeAPIName(name string) bool {
	switch name {
	case "lx.on", "lx.send", "lx.request.options", "lx.request.callback", "lx.request.formData", "lx.utils.crypto.aesEncrypt.mode", "lx.utils.crypto.rsaEncrypt.key", "lx.utils.buffer.encoding", "lx.request.handler", "lx.request.action", "lx.utils", "lx.utils.crypto", "lx.utils.buffer", "lx.utils.zlib", "require", "process", "fetch", "XMLHttpRequest", "WebSocket", "fs", "setInterval", "window", "document":
		return true
	}
	return false
}

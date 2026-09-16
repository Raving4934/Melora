package api

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
)

const maxBodyBytes int64 = 64 << 10

type errorBody struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func fail(w http.ResponseWriter, status int, code, message string) {
	body := errorBody{}
	body.Error.Code = code
	body.Error.Message = message
	writeJSON(w, status, body)
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func decode(w http.ResponseWriter, r *http.Request, value any) bool {
	media, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || media != "application/json" {
		fail(w, 415, "unsupported_media_type", "请求体必须为 application/json")
		return false
	}
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err = dec.Decode(value); err != nil {
		badJSON(w, err)
		return false
	}
	if err = dec.Decode(new(any)); !errors.Is(err, io.EOF) {
		if err == nil {
			err = errors.New("trailing JSON")
		}
		badJSON(w, err)
		return false
	}
	return true
}
func badJSON(w http.ResponseWriter, err error) {
	var max *http.MaxBytesError
	if errors.As(err, &max) {
		fail(w, 413, "body_too_large", "请求体不得超过 64 KiB")
		return
	}
	fail(w, 400, "invalid_json", "JSON 无效、含未知字段或包含多个值")
}
func method(handler http.HandlerFunc, allowed ...string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		for _, m := range allowed {
			if r.Method == m {
				handler(w, r)
				return
			}
		}
		for i, m := range allowed {
			if i == 0 {
				w.Header().Set("Allow", m)
			} else {
				w.Header().Set("Allow", w.Header().Get("Allow")+", "+m)
			}
		}
		fail(w, 405, "method_not_allowed", "此资源不支持该 HTTP 方法")
	}
}
func internalError(w http.ResponseWriter) {
	fail(w, 500, "internal_error", "无法完成持久化操作，请检查服务日志或存储状态")
}
func missing(w http.ResponseWriter) { fail(w, 404, "not_found", "请求的资源不存在") }
func ok(w http.ResponseWriter)      { writeJSON(w, 200, map[string]bool{"ok": true}) }

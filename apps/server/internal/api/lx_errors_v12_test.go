package api

import (
	"errors"
	"fmt"
	"melora/internal/lxruntime"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestV12LXFailuresHaveSafeSpecificMessages(t *testing.T) {
	for _, tc := range []struct {
		err      error
		status   int
		fragment string
	}{
		{lxruntime.ErrScript, 502, "脚本未能返回"}, {lxruntime.ErrNetwork, 502, "元数据请求"},
		{lxruntime.ErrTimeout, 504, "超时"}, {lxruntime.ErrUnsupported, 502, "不支持的接口"},
		{lxruntime.ErrResource, 502, "资源限制"}, {lxruntime.ErrLimit, 502, "大小或数量"},
		{lxruntime.ErrProtocol, 502, "通信异常"}, {errors.New("private-marker"), 502, "检查脚本兼容性"},
	} {
		t.Run(tc.fragment, func(t *testing.T) {
			w := httptest.NewRecorder()
			new(Server).liveError(w, fmt.Errorf("private-marker: %w", tc.err))
			body := w.Body.String()
			if w.Code != tc.status || !strings.Contains(body, tc.fragment) || strings.Contains(body, "白名单") || strings.Contains(body, "private-marker") {
				t.Fatalf("unsafe or unspecific failure: status=%d body=%s", w.Code, body)
			}
			if tc.status == 502 && !strings.Contains(body, `"code":"lx_resolve_failed"`) {
				t.Fatal("broke existing error-code contract")
			}
		})
	}
}

package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
)

// 不泄露远端业务原文/请求 URL，也不从未知业务码推断整个平台或登录状态。
// 保持 errors.Is(ErrUnavailable)，同时让调用者读取安全的能力级错误码。
type txRemoteError struct {
	code  string
	cause error
}

func (e txRemoteError) Error() string {
	return "扣扣此项公开目录请求未完成，请稍后重试"
}
func (e txRemoteError) Unwrap() error            { return ErrUnavailable }
func (e txRemoteError) Is(target error) bool     { return e.cause != nil && errors.Is(e.cause, target) }
func (e txRemoteError) CatalogIssueCode() string { return e.code }

var txUnavailable error = txRemoteError{code: "invalid_response"}

// 搜索 RPC 拒绝时仍可能携带明确的 meta 限制（实测 req.code=2001 / is_filter=-12）。
// 只读取已有搜索协议的限制标志；未知码或畸形元数据不猜原因，绝不将失败正文当结果。
func txRPCRejection(module, method string, data json.RawMessage) error {
	err := txRemoteError{code: "upstream_rejected"}
	if module != "music.search.SearchCgiService" || (method != "DoSearchForQQMusicDesktop" && method != "DoSearchForQQMusicMobile") {
		return err
	}
	var response struct {
		Meta *struct {
			Filter     int    `json:"is_filter"`
			SafetyType int    `json:"safetyType"`
			SafetyURL  string `json:"safetyUrl"`
		} `json:"meta"`
	}
	if json.Unmarshal(data, &response) != nil || response.Meta == nil {
		return err
	}
	meta := response.Meta
	if meta.Filter < 0 || meta.SafetyType != 0 || meta.SafetyURL != "" {
		err.code = "access_restricted"
	}
	return err
}

type txRequestDoer func(*http.Request) (*http.Response, error)

func (f txRequestDoer) Do(r *http.Request) (*http.Response, error) { return f(r) }

// 复用公共请求的 9 秒/1 MiB/GET 约束，仅记录状态分类，不增加网络请求。
func (q *TX) txRequest(ctx context.Context, endpoint string) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, txRemoteError{cause: err}
	}
	if q.client == nil {
		return nil, txRemoteError{code: "upstream_unavailable"}
	}
	var transportErr error
	var requestContext context.Context
	status := 0
	raw, err := catalogRequest(ctx, txRequestDoer(func(r *http.Request) (*http.Response, error) {
		requestContext = r.Context()
		res, e := q.client.Do(r)
		transportErr = e
		if res != nil {
			status = res.StatusCode
		}
		return res, e
	}), http.MethodGet, endpoint, txHeaders(), nil)
	if ctx.Err() != nil {
		return nil, txRemoteError{cause: ctx.Err()}
	}
	if err == nil {
		return raw, nil
	}
	// 本地限流/冷却的能力码优先于状态推断，避免冷却期被降级成笼统的 upstream_unavailable。
	if catalogIssueCode(err) != "" {
		return nil, err
	}
	// catalogRequest 隐藏了 Body.Read 的错误；内部请求超时仍应归为 timeout。
	if requestContext != nil && errors.Is(requestContext.Err(), context.DeadlineExceeded) {
		return nil, txRemoteError{code: "timeout", cause: context.DeadlineExceeded}
	}
	code := "upstream_unavailable"
	switch {
	case errors.Is(transportErr, context.DeadlineExceeded):
		code = "timeout"
	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		code = "access_restricted"
	case status == http.StatusTooManyRequests:
		code = "rate_limited"
	case status == http.StatusOK:
		code = "invalid_response"
	}
	return nil, txRemoteError{code: code, cause: transportErr}
}

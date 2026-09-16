package download

import "fmt"

// 固定枚举，不接收原始响应头、URL、validator 或 token。
type rangeFailureReason uint8

const (
	rangeHeaderMissing rangeFailureReason = iota + 1
	rangeSyntaxInvalid
	rangeTotalUnknown
	rangeOffsetMismatch
	rangeTotalChanged
	rangeLengthMismatch
	rangeValidatorUnavailable
	rangeValidatorMismatch
	rangeHeaderDuplicate
	rangeUnsatisfiedUnconfirmed
	rangeSizeLimit
)

type rangeResponseError struct {
	status int
	reason rangeFailureReason
}

func rangeDiagnostic(status int, reason rangeFailureReason) error {
	return &rangeResponseError{status: status, reason: reason}
}

func (e *rangeResponseError) Unwrap() error { return errRange }
func (e *rangeResponseError) Error() string {
	if e == nil || e.status != 200 && e.status != 206 && e.status != 416 {
		return errRange.Error()
	}
	code, message := "", ""
	switch e.reason {
	case rangeHeaderMissing:
		code, message = "range_header_missing", "缺少范围响应头"
	case rangeSyntaxInvalid:
		code, message = "range_syntax_invalid", "范围格式或数值无效"
	case rangeTotalUnknown:
		code, message = "range_total_unknown", "未提供可验证的对象总长度"
	case rangeOffsetMismatch:
		code, message = "range_offset_mismatch", "返回起点与实际断点不一致"
	case rangeTotalChanged:
		code, message = "range_total_changed", "续传对象总长度发生变化"
	case rangeLengthMismatch:
		code, message = "range_length_mismatch", "响应长度与声明范围不一致"
	case rangeValidatorUnavailable:
		code, message = "range_validator_unavailable", "续传响应缺少可用强校验器"
	case rangeValidatorMismatch:
		code, message = "range_validator_mismatch", "续传对象校验器发生变化"
	case rangeHeaderDuplicate:
		code, message = "range_header_duplicate", "范围或身份响应头重复"
	case rangeUnsatisfiedUnconfirmed:
		code, message = "range_416_unconfirmed", "无法确认本地文件已完整"
	case rangeSizeLimit:
		code, message = "range_size_limit", "声明对象超过下载大小限制"
	default:
		return errRange.Error()
	}
	return fmt.Sprintf("下载来源范围响应不兼容（HTTP %d；%s：%s）", e.status, code, message)
}

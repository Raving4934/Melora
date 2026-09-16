package download

// 只允许固定原因码；绝不回显响应体、原始 MIME、媒体地址或任意包装错误。
type mediaFailureReason uint8

const (
	mediaMIMEInvalid mediaFailureReason = iota + 1
	mediaMIMEUnsupported
	mediaMIMEMismatch
	mediaEncodingUnsupported
	mediaHeaderDuplicate
	mediaNotAudio
	mediaMagicUnknown
	mediaStructureInvalid
)

type mediaResponseError struct {
	reason             mediaFailureReason
	declared, detected string
}

func mediaDiagnostic(reason mediaFailureReason) error { return &mediaResponseError{reason: reason} }
func mediaFormatName(extension string) string {
	switch extension {
	case ".mp3":
		return "MP3"
	case ".flac":
		return "FLAC"
	case ".m4a":
		return "M4A"
	case ".aac":
		return "AAC"
	case ".ogg":
		return "OGG"
	case ".wav":
		return "WAV"
	default:
		return "未确认"
	}
}
func mediaMismatchDiagnostic(mediaType, extension string) error {
	formats := rememberMediaType(nil, mediaType)
	declared := ""
	if len(formats) == 1 {
		declared = formats[0]
	}
	return &mediaResponseError{reason: mediaMIMEMismatch, declared: declared, detected: extension}
}
func (e *mediaResponseError) Unwrap() error { return errMedia }
func (e *mediaResponseError) Error() string {
	if e == nil {
		return errMedia.Error()
	}
	var code, message string
	switch e.reason {
	case mediaMIMEInvalid:
		code, message = "media_mime_invalid", "响应的内容类型格式无效"
	case mediaMIMEUnsupported:
		code, message = "media_mime_unsupported", "响应声明的内容类型不在音频或通用二进制白名单中"
	case mediaMIMEMismatch:
		code, message = "media_mime_mismatch", "响应声明的音频类型与实际文件格式不一致"
		if e.declared != "" || e.detected != "" {
			message += "；声明类别 " + mediaFormatName(e.declared) + "，文件头 " + mediaFormatName(e.detected) + "；此格式组合尚不支持安全纠正"
		}
	case mediaEncodingUnsupported:
		code, message = "media_encoding_unsupported", "响应带有不支持的内容压缩编码"
	case mediaHeaderDuplicate:
		code, message = "media_header_duplicate", "内容类型或编码响应头重复，无法确认唯一声明"
	case mediaNotAudio:
		code, message = "media_not_audio", "返回内容看起来是网页或JSON，而非音频；请检查音源后重试"
	case mediaStructureInvalid:
		code, message = "media_structure_invalid", "内容未通过有界音频结构核验，不能仅凭文件头或MIME作为音频保存"
	case mediaMagicUnknown:
		code, message = "media_magic_unknown", "未识别出受支持的音频文件头；可能是其它格式、无效响应或数据不足"
	default:
		return errMedia.Error()
	}
	return "下载来源音频检查失败（" + code + "：" + message + "）"
}

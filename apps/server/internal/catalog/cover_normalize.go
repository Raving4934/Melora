package catalog

import (
	"net/url"
	"strings"
)

// NormalizeCoverURL 修复已知KW失效 CDN 主机：img{1-4}.kwcdn.kuwo.cn 无法通过 TLS，
// 同分片 img{1-4}.kuwo.cn 镜像可正常提供相同路径资源。只重写主机与协议，
// 保留原始路径、查询与片段；不解析、不猜测资源是否存在，其它主机原样返回。
// 本函数不是 URL 安全校验器，下载调用方仍须执行原有 HTTPS、公网地址及重定向校验。
func NormalizeCoverURL(raw string) string {
	if raw == "" || len(raw) > 2048 {
		return raw
	}
	u, err := url.Parse(raw)
	if err != nil || u.User != nil || u.Opaque != "" {
		return raw
	}
	if u.Scheme != "http" && u.Scheme != "https" && !(u.Scheme == "" && strings.HasPrefix(raw, "//")) {
		return raw
	}

	// 匹配完整 authority，而非后缀或 Hostname，避免误改带端口、尾点或伪装子域的地址。
	shard := ""
	switch strings.ToLower(u.Host) {
	case "img1.kwcdn.kuwo.cn":
		shard = "1"
	case "img2.kwcdn.kuwo.cn":
		shard = "2"
	case "img3.kwcdn.kuwo.cn":
		shard = "3"
	case "img4.kwcdn.kuwo.cn":
		shard = "4"
	default:
		return raw
	}
	u.Scheme = "https"
	u.Host = "img" + shard + ".kuwo.cn"
	return u.String()
}

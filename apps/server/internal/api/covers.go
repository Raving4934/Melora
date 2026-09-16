package api

import (
	"errors"
	"net/http"

	"melora/internal/catalog"
)

// 封面兜底代理：浏览器通常直连平台 CDN；仅直连失败时，服务端才按固定
// 图片域白名单读取并缓存，以规避客户端 DNS/IPv6 节点故障。仅图片，不代理音频。
func (s *Server) cover(w http.ResponseWriter, r *http.Request) {
	raw := r.URL.Query().Get("url")
	if raw == "" || len(raw) > 2048 {
		fail(w, 400, "invalid_cover_url", "封面地址无效")
		return
	}
	if s.covers == nil {
		fail(w, 502, "cover_unavailable", "封面暂不可用")
		return
	}
	data, mimeType, err := s.covers.Get(r.Context(), raw)
	if err != nil {
		if errors.Is(err, catalog.ErrCoverTarget) {
			fail(w, 400, "invalid_cover_url", "封面地址不在允许范围")
			return
		}
		if errors.Is(err, r.Context().Err()) {
			return
		}
		fail(w, 502, "cover_unavailable", "封面暂不可用")
		return
	}
	w.Header().Set("Content-Type", mimeType)
	w.Header().Set("Cache-Control", "public, max-age=21600, immutable")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

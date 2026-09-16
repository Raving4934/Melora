package provider

import (
	"context"
	"net/url"

	"melora/internal/netguard"
)

// prepareResolvedMedia 保留原校验的无连接/下载合同，只为 HTTPS 页面上的
// HTTP 候选加入有界 HEAD 验证。探测与源调用共享 attemptCtx，不另起解析预算。
func (l *Live) prepareResolvedMedia(ctx context.Context, raw, pageScheme string, playback bool) (string, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", ErrMediaURL
	}
	if playback && (pageScheme == "https" || pageScheme == "") && u.Scheme == "http" {
		verify := l.verifyPlaybackHTTPS
		if verify == nil {
			verify = netguard.UpgradePlaybackHTTPS
		}
		resolved, err := verify(ctx, raw)
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		if err != nil {
			return "", ErrMixedContent // HEAD拒绝/短预算不足，不等于证明原站HTTP-only。
		}
		final, err := url.Parse(resolved)
		if err != nil || final.Scheme != "https" || final.Host == "" || final.User != nil || final.Opaque != "" || final.Fragment != "" {
			return "", ErrMixedContent
		}
		// verifier已对最终HTTPS目标逐跳公网校验，不重复DNS消耗剩余预算。
		return resolved, nil
	}
	if err := validateResolvedMedia(ctx, raw, pageScheme, playback); err != nil {
		return "", err
	}
	return raw, nil
}

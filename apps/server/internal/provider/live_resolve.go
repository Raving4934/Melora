package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"path"
	"strings"
	"time"
	"unicode"

	"melora/internal/catalog"
	"melora/internal/lxruntime"
	"melora/internal/lxsource"
	"melora/internal/model"
	"melora/internal/netguard"
)

// ResolveOptions 只影响本次解析。PageScheme 必须由 API 从可信请求上下文派生，
// 不得直接接受查询参数；下载协议由独立 ValidateDownloadURL 公网策略约束。
type ResolveOptions struct {
	AutoSwitch     bool
	ExcludeSources []string
	PageScheme     string
}

var (
	ErrResolveOptions = errors.New("解析选项无效")
	ErrNoCandidate    = errors.New("没有可尝试的已导入音源，请检查音源或重新尝试")
	ErrMixedContent   = errors.New("该音源返回 HTTP 地址，未能确认可用HTTPS直连地址，请重试或切换音源")
	ErrHTTPMedia      = errors.New("当前媒体安全策略不支持 HTTP 地址，请切换音源")
	ErrMediaHeaders   = errors.New("该音源返回的媒体需要额外请求头，当前直连方式不支持，请切换音源")
)

const resolveAttemptLimit = 3
const resolveBudget = 14 * time.Second
const resolveAttemptBudget = 5 * time.Second

// LX 源可能先获取解析地址再 HEAD 探测；实测完整链路 5.6–6.3s。
// 只给首轮 8s，后续仍 5s，所有步骤始终共享 14s 总上下文，最多3次不变。
const resolvePreferredBudget = 8 * time.Second

// ResolveError 的公开错误不包含脚本、响应、地址或凭据；API 可取 Attempts 显示失败源。
type ResolveError struct {
	Attempts []string
	cause    error
}

func (e *ResolveError) Error() string { return e.cause.Error() }
func (e *ResolveError) Unwrap() error { return e.cause }

func resolveFailure(attempts []string, cause error) error {
	// 不把任意实现的错误文本透传给 API。仅保留固定、安全错误类型。
	kinds := []error{context.Canceled, context.DeadlineExceeded, ErrQuality, ErrMediaURL, ErrMixedContent, ErrHTTPMedia, ErrMediaHeaders, ErrNoCandidate, ErrResolveOptions,
		lxsource.ErrNoActive, lxsource.ErrMissing, lxsource.ErrUnsupported, lxsource.ErrStorage,
		catalog.ErrInput, catalog.ErrNotFound, catalog.ErrUnavailable, catalog.ErrUnsupported,
		lxruntime.ErrScript, lxruntime.ErrTimeout, lxruntime.ErrResource, lxruntime.ErrLimit, lxruntime.ErrProtocol, lxruntime.ErrUnsupported, lxruntime.ErrNetwork}
	kind := error(lxruntime.ErrScript)
	for _, candidate := range kinds {
		if errors.Is(cause, candidate) {
			kind = candidate
			break
		}
	}
	return &ResolveError{Attempts: append([]string(nil), attempts...), cause: kind}
}

func sourceQualities(source lxsource.Source, platform string) []string {
	capability, ok := source.Platforms[platform]
	if !ok || source.Status != "ready" {
		return []string{}
	}
	musicURL := false
	for _, action := range capability.Actions {
		if action == "musicUrl" {
			musicURL = true
			break
		}
	}
	if !musicURL {
		return []string{}
	}
	out := []string{}
	seen := map[string]bool{}
	for _, q := range capability.Qualitys {
		if q == "" || len(q) > 32 {
			continue
		}
		valid := true
		for _, c := range q {
			if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
				valid = false
				break
			}
		}
		if valid && !seen[q] {
			out = append(out, q)
			seen[q] = true
		}
	}
	return out
}
func containsQuality(qualities []string, quality string) bool {
	for _, q := range qualities {
		if q == quality {
			return true
		}
	}
	return false
}
func sourceIDValid(id string) bool {
	if len(id) != 24 {
		return false
	}
	for _, c := range id {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}
func (l *Live) resolveCandidates(track model.Track, quality string, opts ResolveOptions) ([]lxsource.Source, string, error) {
	if opts.PageScheme != "" && opts.PageScheme != "http" && opts.PageScheme != "https" || len(opts.ExcludeSources) > 20 {
		return nil, "", ErrResolveOptions
	}
	excluded := map[string]bool{}
	for _, id := range opts.ExcludeSources {
		if !sourceIDValid(id) {
			return nil, "", ErrResolveOptions
		}
		excluded[id] = true
	}
	if l.Sources == nil {
		return nil, "", lxsource.ErrNoActive
	}
	state := l.Sources.List()
	ordered := make([]lxsource.Source, 0, len(state.Items))
	for _, source := range state.Items {
		if source.ID == state.ActiveID {
			ordered = append(ordered, source)
			break
		}
	}
	if opts.AutoSwitch {
		for _, source := range state.Items {
			if source.ID != state.ActiveID {
				ordered = append(ordered, source)
			}
		}
	}
	if len(ordered) == 0 {
		return nil, "", lxsource.ErrNoActive
	}
	if quality == "" || quality == "standard" {
		for _, source := range ordered {
			qualities := sourceQualities(source, track.ProviderID)
			if len(qualities) == 0 {
				continue
			}
			quality = qualities[0]
			if containsQuality(qualities, "128k") {
				quality = "128k"
			}
			break
		}
	}
	out := []lxsource.Source{}
	supportsPlatform := false
	for _, source := range ordered {
		if excluded[source.ID] {
			continue
		}
		qualities := sourceQualities(source, track.ProviderID)
		if len(qualities) == 0 {
			continue
		}
		supportsPlatform = true
		if quality == "" || quality == "standard" {
			quality = qualities[0]
			if containsQuality(qualities, "128k") {
				quality = "128k"
			}
		}
		if !containsQuality(qualities, quality) {
			continue
		}
		out = append(out, source)
		if len(out) >= resolveAttemptLimit {
			break
		}
	}
	if len(out) == 0 {
		if supportsPlatform {
			return nil, "", ErrQuality
		}
		if !opts.AutoSwitch && len(excluded) == 0 {
			return nil, "", lxsource.ErrUnsupported
		}
		return nil, "", ErrNoCandidate
	}
	return out, quality, nil
}

type trustedMusicCache interface {
	CachedMusicInfo(string) (map[string]any, bool)
}
type trustedTrackCache interface {
	CachedTrack(string) (model.Track, bool)
}

func cloneMusicInfo(info map[string]any) (map[string]any, error) {
	encoded, err := json.Marshal(info)
	if err != nil || len(encoded) == 0 || len(encoded) > 128<<10 {
		return nil, lxruntime.ErrLimit
	}
	var copy map[string]any
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.UseNumber()
	if decoder.Decode(&copy) != nil || len(copy) == 0 {
		return nil, catalog.ErrUnavailable
	}
	return copy, nil
}
func (l *Live) resolveMusicInfo(ctx context.Context, id string) (map[string]any, error) {
	if l.Catalog == nil {
		return nil, catalog.ErrUnavailable
	}
	info, err := l.Catalog.MusicInfo(ctx, id)
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		// Registry 本身可缓存优先；这里只兼容主线显式可信缓存接口，绝不按标题搜索或用客户端字段补造。
		if cache, ok := any(l.Catalog).(trustedMusicCache); ok {
			if cached, found := cache.CachedMusicInfo(id); found {
				return cloneMusicInfo(cached)
			}
		}
		return nil, err
	}
	return cloneMusicInfo(info)
}
func validTrackIdentity(track model.Track) bool {
	source, local, ok := strings.Cut(track.ID, ":")
	if !ok || local == "" || len(track.ID) > 200 || source != track.ProviderID || catalog.PlatformNames[source] == "" || strings.ContainsAny(local, "/\\") {
		return false
	}
	for _, c := range track.ID {
		if unicode.IsControl(c) {
			return false
		}
	}
	return true
}

// ResolveWithOptions 用于服务端下载，使用独立公网 HTTP/HTTPS 校验，不受浏览器页面协议影响。
// 后续实际下载仍必须逐跳校验、固定 DNS；此处不连接或代理媒体。
func (l *Live) ResolveWithOptions(ctx context.Context, track model.Track, quality string, opts ResolveOptions) (model.PlayInfo, error) {
	return l.resolveWithOptions(ctx, track, quality, opts, false)
}

// PlayInfoWithOptions 由 API 传入实际页面协议；缺省按 HTTPS 页面保守处理。
func (l *Live) PlayInfoWithOptions(ctx context.Context, track model.Track, quality string, opts ResolveOptions) (model.PlayInfo, error) {
	return l.resolveWithOptions(ctx, track, quality, opts, true)
}
func (l *Live) resolveWithOptions(ctx context.Context, track model.Track, quality string, opts ResolveOptions, playback bool) (model.PlayInfo, error) {
	out := model.PlayInfo{TrackID: track.ID, AttemptedSources: []string{}}
	if err := ctx.Err(); err != nil {
		return out, resolveFailure(nil, err)
	}
	if !validTrackIdentity(track) {
		return out, resolveFailure(nil, catalog.ErrNotFound)
	}
	candidates, effective, initialErr := l.resolveCandidates(track, quality, opts)
	if errors.Is(initialErr, ErrResolveOptions) || !opts.AutoSwitch && initialErr != nil {
		return out, resolveFailure(nil, initialErr)
	}
	if effective == "" {
		effective = quality
	}
	plans, effective := l.crossPlans(track, effective, opts)
	if initialErr != nil && len(plans) == 0 {
		return out, resolveFailure(nil, initialErr)
	}
	out.Quality = effective
	ctx, cancel := context.WithTimeout(ctx, resolveBudget)
	defer cancel()
	last := initialErr
	if last == nil {
		last = ErrNoCandidate
	}
	attempts := 0
	try := func(actual model.Track, source lxsource.Source, info map[string]any) bool {
		if attempts >= resolveAttemptLimit || ctx.Err() != nil {
			return false
		}
		copy, err := cloneMusicInfo(info)
		if err != nil {
			last = err
			return false
		}
		attempts++
		if !containsQuality(out.AttemptedSources, source.ID) {
			out.AttemptedSources = append(out.AttemptedSources, source.ID)
		}
		attemptBudget := resolveAttemptBudget
		if attempts == 1 {
			attemptBudget = resolvePreferredBudget
		}
		attemptCtx, stop := context.WithTimeout(ctx, attemptBudget)
		defer stop()
		raw, err := l.Sources.InvokeSource(attemptCtx, source.ID, actual.ProviderID, "musicUrl", map[string]any{"type": effective, "musicInfo": copy})
		if err == nil {
			var resolved string
			resolved, err = decodeMediaURL(raw)
			if err == nil {
				resolved, err = l.prepareResolvedMedia(attemptCtx, resolved, opts.PageScheme, playback)
			}
			if err == nil && attemptCtx.Err() != nil {
				err = attemptCtx.Err()
			}
			if err == nil {
				out.URL = resolved
				out.MIMEType = mediaMIME(resolved, effective)
				out.Direct = true
				out.SourceID = source.ID
				resolvedTrack := actual
				out.ResolvedTrack = &resolvedTrack
				return true
			}
		}
		last = err
		return false
	}
	var originalInfo map[string]any
	firstPass := len(candidates)
	if len(plans) > 0 && firstPass > 2 {
		firstPass = 2
	} // 保留一次机会给严格同曲，不能被同平台的失败源耗尽。
	if len(candidates) > 0 {
		metadataCtx := ctx
		stop := func() {}
		if opts.AutoSwitch {
			metadataCtx, stop = context.WithTimeout(ctx, 3*time.Second)
		}
		var err error
		originalInfo, err = l.resolveMusicInfo(metadataCtx, track.ID)
		stop()
		if err != nil {
			last = err
		} else {
			for _, source := range candidates[:firstPass] {
				if try(track, source, originalInfo) {
					return out, nil
				}
				if ctx.Err() != nil {
					break
				}
			}
		}
	}
	if len(plans) > 0 && attempts < resolveAttemptLimit && ctx.Err() == nil {
		if original, ok := l.trustedMatchTrack(ctx, track.ID); ok {
			for _, plan := range plans {
				if attempts >= resolveAttemptLimit || ctx.Err() != nil {
					break
				}
				actual, ok := l.uniqueCrossMatch(ctx, original, plan.platform)
				if !ok {
					continue
				}
				sources, _, err := l.resolveCandidates(actual, effective, opts)
				if err != nil {
					continue
				}
				metadataCtx, stop := context.WithTimeout(ctx, crossMetadataBudget)
				info, err := l.resolveMusicInfo(metadataCtx, actual.ID)
				stop()
				if err != nil {
					last = err
					continue
				}
				for _, source := range sources {
					if try(actual, source, info) {
						return out, nil
					}
					if attempts >= resolveAttemptLimit || ctx.Err() != nil {
						break
					}
				}
			}
		}
	}
	// 没找到唯一同曲或目录失败时，仍可用剩余预算尝试第三个原平台脚本。
	if originalInfo != nil && attempts < resolveAttemptLimit && ctx.Err() == nil {
		for _, source := range candidates[firstPass:] {
			if try(track, source, originalInfo) {
				return out, nil
			}
			if attempts >= resolveAttemptLimit || ctx.Err() != nil {
				break
			}
		}
	}
	if ctx.Err() != nil {
		last = ctx.Err()
	}
	return out, resolveFailure(out.AttemptedSources, last)
}

func mediaMIME(raw, quality string) string {
	u, _ := url.Parse(raw)
	switch strings.ToLower(path.Ext(u.Path)) {
	case ".ogg", ".opus":
		return "audio/ogg"
	case ".flac":
		return "audio/flac"
	case ".m4a", ".mp4":
		return "audio/mp4"
	case ".wav":
		return "audio/wav"
	case ".aac":
		return "audio/aac"
	case ".ape":
		return "audio/ape"
	}
	switch quality {
	case "flac", "flac24bit":
		return "audio/flac"
	case "wav":
		return "audio/wav"
	case "ape":
		return "audio/ape"
	}
	return "audio/mpeg"
}

func validateResolvedMedia(ctx context.Context, raw, pageScheme string, playback bool) error {
	u, err := url.Parse(raw)
	if err != nil {
		return ErrMediaURL
	}
	if pageScheme == "" {
		pageScheme = "https"
	}
	if playback {
		if u.Scheme == "http" && pageScheme != "http" {
			return ErrMixedContent
		}
		err = netguard.ValidatePlaybackURL(ctx, raw, pageScheme)
	} else {
		err = netguard.ValidateDownloadURL(ctx, raw)
	}
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return ErrMediaURL
	}
	return nil
}

func decodeMediaURL(raw json.RawMessage) (string, error) {
	if len(raw) == 0 || len(raw) > 256<<10 {
		return "", ErrMediaURL
	}
	var value any
	if json.Unmarshal(raw, &value) != nil {
		return "", ErrMediaURL
	}
	resolved, headers := mediaValue(value, 0)
	if headers {
		return "", ErrMediaHeaders
	}
	if resolved == "" {
		return "", ErrMediaURL
	}
	return resolved, nil
}
func extractURL(value any, depth int) string {
	resolved, _ := mediaValue(value, depth)
	return resolved
}
func mediaValue(value any, depth int) (string, bool) {
	if depth > 6 {
		return "", false
	}
	switch v := value.(type) {
	case string:
		text := strings.TrimSpace(v)
		if len(text) > 256<<10 {
			return "", false
		}
		if len(text) <= 8192 {
			u, err := url.Parse(text)
			if err == nil && (u.Scheme == "https" || u.Scheme == "http") && u.Host != "" && u.Opaque == "" && u.User == nil && u.Fragment == "" {
				return text, false
			}
		}
		if len(text) > 0 && (text[0] == '{' || text[0] == '[' || text[0] == '"') {
			var child any
			if json.Unmarshal([]byte(text), &child) == nil {
				return mediaValue(child, depth+1)
			}
		}
	case map[string]any:
		requiresHeaders := false
		for _, key := range []string{"headers", "header"} {
			if child, ok := v[key]; ok && child != nil {
				if headers, ok := child.(map[string]any); !ok || len(headers) > 0 {
					requiresHeaders = true
				}
			}
		}
		for _, key := range []string{"url", "musicUrl", "playUrl", "play_url", "data", "result", "body"} {
			if child, ok := v[key]; ok {
				if resolved, headers := mediaValue(child, depth+1); resolved != "" {
					return resolved, headers || requiresHeaders
				}
			}
		}
	case []any:
		// 多 URL 数组的音质/歌曲含义不明确，不随意取第一条冒充目标歌曲。
		if len(v) == 1 {
			return mediaValue(v[0], depth+1)
		}
	}
	return "", false
}

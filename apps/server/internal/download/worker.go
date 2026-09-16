package download

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"melora/internal/model"
)

var (
	errRange         = errors.New("下载来源返回无效或不一致的 Content-Range")
	errRangeIdentity = errors.New("下载来源分段缺少可靠的对象标识（HTTP 206；range_identity_unconfirmed），无法安全续传；请更换来源重试")
	errRangeStatus   = errors.New("下载来源将部分或不明确的范围标记为完整响应（HTTP 200；range_200_inconsistent），已拒绝保存")
	errRangeLimit    = errors.New("下载来源分段请求过多（HTTP 206；range_request_limit），已停止以避免无限请求；请更换来源重试")
	errSize          = errors.New("下载文件超过大小限制或长度不一致")
	errMedia         = errors.New("下载来源未返回受支持的音频文件")
	errResolve       = errors.New("无法解析已授权下载地址")
	errTimeout       = errors.New("下载超过时间限制，请稍后重试")
	errSource        = errors.New("下载来源请求失败")
)

type attemptError struct {
	cause            error
	refresh, restart bool
}

func (e *attemptError) Error() string { return e.cause.Error() }
func (e *attemptError) Unwrap() error { return e.cause }
func transient(err error) error       { return &attemptError{cause: err} }
func safeError(err error) error {
	if errors.Is(err, errSpace) || errors.Is(err, syscall.ENOSPC) || errors.Is(err, syscall.EDQUOT) {
		return errSpace
	}
	// 保留磁盘错误优先级，但在 errRange 通用归并前保留受控分类；不回显包装层正文。
	var detail *rangeResponseError
	if errors.As(err, &detail) && detail != nil {
		return rangeDiagnostic(detail.status, detail.reason)
	}
	var mediaDetail *mediaResponseError
	if errors.As(err, &mediaDetail) && mediaDetail != nil {
		return &mediaResponseError{reason: mediaDetail.reason, declared: mediaDetail.declared, detected: mediaDetail.detected}
	}
	for _, known := range []error{errSpace, errSpaceProbe, errRateLimited, errUnsafeURL, errDNS, errNetwork, errRedirect, errRoot, errFile, errCollision, errPersist, errRangeIdentity, errRangeStatus, errRangeLimit, errRange, errSize, errMedia, errResolve, errSource, errTimeout} {
		if errors.Is(err, known) {
			return known
		}
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return errTimeout
	}
	if errors.Is(err, context.Canceled) {
		return errors.New("下载已停止")
	}
	return errSource // 不拼接 url.Error、resolver、transport、磁盘或持久化回调的原始错误。
}
func (m *Manager) state(e *entry, state string) error {
	return m.update(e, func(j *model.DownloadJob) { j.State = state; j.Speed = 0; j.Error = "" })
}

func (m *Manager) run(ctx context.Context, e *entry, root *os.Root) (result error) {
	m.mu.Lock()
	job := cloneJob(e.job)
	m.mu.Unlock()
	meta, err := loadMeta(root, job.ID)
	if err != nil {
		return err
	}
	if meta.Final != "" {
		if meta.Final != jobStem(job)+meta.Extension {
			// 检查点可领先任务快照；仅接受同任务的规范短号或旧 ID 名。
			if filepath.Ext(meta.Final) != meta.Extension || !validStem(job, strings.TrimSuffix(meta.Final, meta.Extension)) {
				return errFile
			}
			job.TargetPath = filepath.Join(jobRoot(job), "Singles", meta.Final)
			if err := m.update(e, func(j *model.DownloadJob) { j.TargetPath = job.TargetPath }); err != nil {
				return err
			}
		}
		sourceName := partName(job.ID)
		if meta.Tagged {
			sourceName = tagPartName(job.ID)
		}
		if _, err := root.Lstat(sourceName); errors.Is(err, os.ErrNotExist) {
			if _, err := root.Lstat(meta.Final); err == nil {
				return m.recoverFinal(ctx, e, root, job, meta)
			}
		}
	}
	file, err := openRegular(root, partName(job.ID), true)
	if err != nil {
		return err
	}
	defer func() {
		syncErr := file.Sync()
		info, statErr := file.Stat()
		closeErr := file.Close()
		if ioErr := errors.Join(syncErr, statErr, closeErr); ioErr != nil {
			mapped := diskError(ioErr)
			if result == nil || mapped == errSpace && !errors.Is(result, errPersist) {
				result = mapped
			}
		}
		if statErr != nil {
			return
		}
		// 完成状态已在原子发布后持久化，避免收尾期间重复回调掩盖失败。
		m.mu.Lock()
		completed := e.job.State == "completed"
		m.mu.Unlock()
		if completed {
			return
		}
		// 失败或取消也记录实际写入量，暂停确认须等待此保存结束。
		if err := m.update(e, func(j *model.DownloadJob) { j.BytesDone = info.Size(); j.Speed = 0 }); err != nil {
			result = err
		}
	}()
	for attempt := 0; attempt < m.limits.attempts; attempt++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		if attempt > 0 {
			if err := m.state(e, "resolving"); err != nil {
				return err
			}
		}
		info, err := file.Stat()
		if err != nil {
			return errFile
		}
		offset := info.Size()
		if !resumableRepresentation(meta) {
			offset = 0
		}
		if err := m.checkSpace(root, max(meta.Total-offset, 0)); err != nil {
			return err
		}
		rawURL, err := m.resolve(ctx, cloneJob(job).Track, job.Quality)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err != nil {
			err = transient(errResolve)
		} else {
			var u *url.URL
			u, err = url.Parse(rawURL)
			if err != nil {
				err = errUnsafeURL
			} else if err = validateURL(u); err == nil {
				err = m.transfer(ctx, e, root, file, job, u, &meta)
			}
		}
		rawURL = "" // 不将临时地址放入持久化记录、事件或日志。
		if err == nil {
			return m.finish(ctx, e, root, file, job, &meta)
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		var retry *attemptError
		if !errors.As(err, &retry) || attempt+1 >= m.limits.attempts {
			return err
		}
		if retry.restart {
			if err := file.Truncate(0); err != nil {
				return diskError(err)
			}
			if _, err := file.Seek(0, io.SeekStart); err != nil {
				return errFile
			}
			meta = partialMeta{}
			if err := file.Sync(); err != nil {
				return diskError(err)
			}
			if err := m.update(e, func(j *model.DownloadJob) { j.BytesDone = 0; j.BytesTotal = 0 }); err != nil {
				return err
			}
		}
		state := "retry_wait"
		if retry.refresh {
			state = "waiting_for_url_refresh"
		}
		if err := m.state(e, state); err != nil {
			return err
		}
		timer := time.NewTimer(m.limits.backoff * time.Duration(1<<attempt))
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
	return errSource
}

// 强 ETag 只能标识同一资源的版本，不能单凭相同字符串跨 URL 拼接。
func strongETag(value string) bool {
	if len(value) < 2 || len(value) > 512 || value[0] != '"' || value[len(value)-1] != '"' {
		return false
	}
	for i := 1; i < len(value)-1; i++ {
		b := value[i]
		if b != 0x21 && !(b >= 0x23 && b <= 0x7e) && b < 0x80 {
			return false
		}
	}
	return true
}

func resumeValidator(meta partialMeta) string {
	if strongETag(meta.ETag) {
		return meta.ETag
	}
	// RFC 9110 §13.1.5：存在实体标签时不能以 HTTP-date 绕过弱 ETag。
	if meta.ETag != "" {
		return ""
	}
	modified, modifiedErr := http.ParseTime(meta.LastModified)
	date, dateErr := http.ParseTime(meta.Date)
	// §8.8.2.2 还要求时间来源可信；此处选取 60 秒作为保守时钟裕量，
	// 不是声称 RFC 9110 规定固定 60 秒。没有足够证据时从零替代而非拼接。
	if modifiedErr == nil && dateErr == nil && date.Sub(modified) >= time.Minute {
		return meta.LastModified
	}
	return ""
}

func matchingValidator(meta partialMeta, h http.Header) bool {
	validator := resumeValidator(meta)
	if validator == "" {
		return false
	}
	candidate := partialMeta{ETag: h.Get("ETag"), LastModified: h.Get("Last-Modified"), Date: h.Get("Date")}
	return resumeValidator(candidate) == validator
}

func resourceHash(u *url.URL) string {
	if u == nil {
		return ""
	}
	copy := *u
	copy.Fragment, copy.RawFragment = "", ""
	digest := sha256.Sum256([]byte(copy.String()))
	return hex.EncodeToString(digest[:])
}

func resumableRepresentation(meta partialMeta) bool {
	return meta.ResourceHash != "" && resumeValidator(meta) != ""
}

func responseMeta(resp *http.Response, extension string, total int64) partialMeta {
	etag, modified, date := resp.Header.Get("ETag"), resp.Header.Get("Last-Modified"), resp.Header.Get("Date")
	if len(etag) > 512 || strings.ContainsAny(etag, "\r\n\x00") {
		// 保留“不可使用的实体标签”语义，不能误用 Last-Modified 代替它。
		etag = `W/"unusable"`
	}
	if len(modified) > 128 || strings.ContainsAny(modified, "\r\n\x00") {
		modified = ""
	}
	if len(date) > 128 || strings.ContainsAny(date, "\r\n\x00") {
		date = ""
	}
	identity := ""
	if resp.Request != nil {
		identity = resourceHash(resp.Request.URL)
	}
	return partialMeta{Version: 1, ETag: etag, LastModified: modified, Date: date, ResourceHash: identity, Total: total, Extension: extension}
}

// 每轮传输有界；外层网络重试仍受 attempts 和总时间限制。分段成功不消耗失败重试。
const maxRangeResponses = 128

var (
	errMoreRange = errors.New("internal: contiguous range incomplete")
	errNeedWhole = errors.New("internal: require independent representation")
)

func (m *Manager) transfer(ctx context.Context, e *entry, root *os.Root, file *os.File, job model.DownloadJob, u *url.URL, meta *partialMeta) error {
	forceFromZero, replaced := false, false
	for request := 0; request < maxRangeResponses; request++ {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := m.transferResponse(ctx, e, root, file, job, u, meta, forceFromZero)
		if errors.Is(err, errMoreRange) {
			forceFromZero = false
			continue
		}
		if errors.Is(err, errNeedWhole) {
			if replaced {
				return errRangeIdentity
			}
			// 先请求独立对象；空间、头和魔数检查通过前不丢弃原有 part。
			// 从零的显式范围请求是一次不同的安全替代，不复用先前未知对象的任何字节。
			forceFromZero, replaced = true, true
			continue
		}
		return err
	}
	return errRangeLimit
}

func (m *Manager) transferResponse(ctx context.Context, e *entry, root *os.Root, file *os.File, job model.DownloadJob, u *url.URL, meta *partialMeta, forceFromZero bool) error {
	info, err := file.Stat()
	if err != nil {
		return errFile
	}
	offset := info.Size()
	if offset < 0 || offset > m.limits.maxBytes {
		return errSize
	}
	if forceFromZero || offset > 0 && !resumableRepresentation(*meta) {
		// 旧检查点仍可读取，但缺少身份时只能请求独立对象；暂不删除原有字节。
		offset = 0
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return errUnsafeURL
	}
	req.Header.Set("Accept", "audio/*, application/ogg, application/octet-stream;q=0.5")
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("User-Agent", "Melora-Download/1")
	if offset > 0 {
		req.Header.Set("Range", "bytes="+strconv.FormatInt(offset, 10)+"-")
		req.Header.Set("If-Range", resumeValidator(*meta))
	} else if forceFromZero {
		req.Header.Set("Range", "bytes=0-")
	}
	resp, err := m.client.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if errors.Is(err, errUnsafeURL) || errors.Is(err, errRedirect) {
			return safeError(err)
		}
		if errors.Is(err, errDNS) {
			return transient(errDNS)
		}
		return transient(errNetwork)
	}
	defer resp.Body.Close()
	// 不把重复的单值范围/身份字段中的第一个值当作唯一真值。
	if resp.StatusCode == http.StatusOK || resp.StatusCode == http.StatusPartialContent || resp.StatusCode == http.StatusRequestedRangeNotSatisfiable {
		for _, key := range []string{"Content-Range", "ETag", "Last-Modified", "Date"} {
			if len(resp.Header.Values(key)) > 1 {
				return rangeDiagnostic(resp.StatusCode, rangeHeaderDuplicate)
			}
		}
	}
	switch resp.StatusCode {
	case http.StatusUnauthorized, http.StatusForbidden, http.StatusNotFound:
		return &attemptError{cause: errSource, refresh: true}
	case http.StatusTooManyRequests:
		return errRateLimited // 限流时停止自动尝试，避免短退避违反服务方的限流窗口。
	case http.StatusRequestTimeout, http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		return transient(errSource)
	case http.StatusRequestedRangeNotSatisfiable:
		total, ok := unsatisfiedRange(resp.Header.Get("Content-Range"))
		if ok && total == offset && total > 0 && total == meta.Total && resumableRepresentation(*meta) && matchingValidator(*meta, resp.Header) && responseMeta(resp, "", total).ResourceHash == meta.ResourceHash {
			return nil
		}
		return &attemptError{cause: rangeDiagnostic(http.StatusRequestedRangeNotSatisfiable, rangeUnsatisfiedUnconfirmed), restart: true}
	case http.StatusOK, http.StatusPartialContent:
	default:
		return errSource
	}
	if len(resp.Header.Values("Content-Type")) > 1 || len(resp.Header.Values("Content-Encoding")) > 1 {
		return mediaDiagnostic(mediaHeaderDuplicate)
	}
	if encoding := resp.Header.Get("Content-Encoding"); encoding != "" && !strings.EqualFold(encoding, "identity") {
		return mediaDiagnostic(mediaEncodingUnsupported)
	}
	// 无 Content-Type 不等于非音频：仅回退通用二进制，仍执行魔数、完整字节与最终校验。
	mediaType := "application/octet-stream"
	if raw := resp.Header.Get("Content-Type"); raw != "" {
		mediaType, _, err = mime.ParseMediaType(raw)
		if err != nil {
			return mediaDiagnostic(mediaMIMEInvalid)
		}
	}
	if !allowedMIME(mediaType) {
		return mediaDiagnostic(mediaMIMEUnsupported)
	}
	total := resp.ContentLength
	expected := int64(-1)
	if resp.StatusCode == http.StatusPartialContent {
		value := resp.Header.Get("Content-Range")
		start, end, size, ok := parseContentRange(value)
		if !ok {
			reason := rangeSyntaxInvalid
			if value == "" {
				reason = rangeHeaderMissing
			} else if strings.HasSuffix(value, "/*") {
				reason = rangeTotalUnknown
			}
			return rangeDiagnostic(resp.StatusCode, reason)
		}
		if start != offset {
			return rangeDiagnostic(resp.StatusCode, rangeOffsetMismatch)
		}
		if size > m.limits.maxBytes {
			return rangeDiagnostic(resp.StatusCode, rangeSizeLimit)
		}
		if offset > 0 && meta.Total > 0 && meta.Total != size {
			return rangeDiagnostic(resp.StatusCode, rangeTotalChanged)
		}
		if offset > 0 && !matchingValidator(*meta, resp.Header) {
			reason := rangeValidatorMismatch
			if resumeValidator(responseMeta(resp, "", size)) == "" {
				reason = rangeValidatorUnavailable
			}
			return rangeDiagnostic(resp.StatusCode, reason)
		}
		expected = end - start + 1
		if resp.ContentLength >= 0 && resp.ContentLength != expected {
			return rangeDiagnostic(resp.StatusCode, rangeLengthMismatch)
		}
		candidate := responseMeta(resp, "", size)
		if offset > 0 && candidate.ResourceHash != meta.ResourceHash {
			return errNeedWhole
		}
		if end < size-1 && !resumableRepresentation(candidate) {
			return errNeedWhole
		}
		total = size
	} else {
		// RFC 9110 §14.4：200 没有 Content-Range 的部分响应语义。
		// 仅兼容“明确覆盖整个对象 + 实际字节数一致”的冗余字段，永不追加到旧 part。
		offset = 0
		expected = resp.ContentLength
		if values := resp.Header.Values("Content-Range"); len(values) > 0 {
			start, end, size, ok := parseContentRange(values[0])
			if !ok || start != 0 || end != size-1 || resp.ContentLength >= 0 && resp.ContentLength != size {
				return errRangeStatus
			}
			total, expected = size, size
		}
	}
	if total > m.limits.maxBytes || total == 0 || total < -1 {
		return errSize
	}
	guard := spaceGuard{manager: m, root: root}
	if err := guard.check(offset, total, 0); err != nil {
		return err
	}
	reader := bufio.NewReaderSize(resp.Body, 32<<10)
	extension := meta.Extension
	if offset == 0 {
		prefix, peekErr := reader.Peek(512)
		if peekErr != nil && peekErr != io.EOF && peekErr != bufio.ErrBufferFull {
			return transient(errNetwork)
		}
		extension = detectFormat(prefix)
		if extension == "" {
			prefix = bytes.TrimSpace(prefix)
			if len(prefix) > 0 && (prefix[0] == '<' || prefix[0] == '{' || prefix[0] == '[') {
				return mediaDiagnostic(mediaNotAudio)
			}
			return mediaDiagnostic(mediaMagicUnknown)
		}
	}
	// MP3/FLAC的受支持音频声明冲突不再仅凭响应头拒绝：完整收齐后
	// 必须额外确认音频结构。未知/明确非音频MIME在上方仍拒绝。
	if !matchingMIME(mediaType, extension) && extension != ".mp3" && extension != ".flac" && extension != ".audio" {
		return mediaMismatchDiagnostic(mediaType, extension)
	}
	// 独立的 200/206 均从零替换；空间、范围及魔数验证通过后才截断私有 part。
	if offset == 0 && info.Size() > 0 {
		if err := file.Truncate(0); err != nil {
			return diskError(err)
		}
	}
	if _, err := file.Seek(offset, io.SeekStart); err != nil {
		return errFile
	}
	formats := meta.ReportedFormats
	if offset == 0 {
		formats = nil // 独立替代对象不继承原响应声明。
	}
	formats = rememberMediaType(formats, mediaType)
	*meta = responseMeta(resp, extension, max(total, 0))
	meta.ReportedFormats = formats
	if err := saveMeta(root, job.ID, *meta); err != nil {
		return err
	}
	if err := m.update(e, func(j *model.DownloadJob) {
		j.State = "downloading"
		j.BytesDone = offset
		j.BytesTotal = max(total, 0)
		j.Speed = 0
		j.TargetPath = targetPath(jobRoot(job), job, extension)
	}); err != nil {
		return err
	}
	started := time.Now()
	last := started
	lastBytes := offset
	buffer := make([]byte, 32<<10)
	done := offset
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		n, readErr := reader.Read(buffer)
		if n > 0 {
			if done+int64(n) > m.limits.maxBytes || total >= 0 && done+int64(n) > total || expected >= 0 && done-offset+int64(n) > expected {
				return errSize
			}
			if err := guard.check(done, total, n); err != nil {
				return err
			}
			written, writeErr := file.Write(buffer[:n])
			done += int64(written)
			if writeErr != nil || written != n {
				return diskError(writeErr)
			}
			now := time.Now()
			if now.Sub(last) >= m.limits.progress {
				if err := file.Sync(); err != nil {
					return diskError(err)
				}
				speed := int64(float64(done-lastBytes) / now.Sub(last).Seconds())
				if err := m.update(e, func(j *model.DownloadJob) { j.BytesDone = done; j.Speed = speed }); err != nil {
					return err
				}
				last, lastBytes = now, done
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				return transient(errNetwork)
			}
			break
		}
	}
	if done == 0 {
		return errMedia
	}
	if expected >= 0 && done-offset != expected {
		return transient(errSize)
	}
	if resp.StatusCode == http.StatusPartialContent && done < total {
		if err := file.Sync(); err != nil {
			return diskError(err)
		}
		if err := m.update(e, func(j *model.DownloadJob) { j.BytesDone = done; j.BytesTotal = total; j.Speed = 0 }); err != nil {
			return err
		}
		return errMoreRange
	}
	if total >= 0 && done != total {
		return transient(errSize)
	}
	meta.Total = done
	if err := file.Sync(); err != nil {
		return diskError(err)
	}
	return m.update(e, func(j *model.DownloadJob) { j.BytesDone = done; j.BytesTotal = done; j.Speed = 0 })
}

func parseContentRange(value string) (start, end, total int64, ok bool) {
	// RFC 9110 §14.1：仅 range unit 不区分大小写；数字、分隔符与范围仍严格。
	if len(value) < 6 || !strings.EqualFold(value[:5], "bytes") || value[5] != ' ' {
		return
	}
	halves := strings.Split(value[6:], "/")
	if len(halves) != 2 {
		return
	}
	ends := strings.Split(halves[0], "-")
	if len(ends) != 2 {
		return
	}
	var a, b, c bool
	start, a = decimal(ends[0])
	end, b = decimal(ends[1])
	total, c = decimal(halves[1])
	ok = a && b && c && total > 0 && start <= end && end < total
	return
}
func decimal(s string) (int64, bool) {
	if s == "" {
		return 0, false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseInt(s, 10, 64)
	return n, err == nil
}
func unsatisfiedRange(s string) (int64, bool) {
	if len(s) < 8 || !strings.EqualFold(s[:5], "bytes") || s[5:8] != " */" {
		return 0, false
	}
	return decimal(s[8:])
}
func allowedMIME(t string) bool {
	switch t {
	case "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3", "audio/x-mpeg-3", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac", "audio/x-flac", "audio/ogg", "audio/opus", "audio/wav", "audio/wave", "audio/x-wav", "audio/vnd.wave", "application/ogg", "application/octet-stream", "binary/octet-stream", "application/x-octet-stream":
		return true
	}
	return false
}
func matchingMIME(t, extension string) bool {
	if t == "application/octet-stream" || t == "binary/octet-stream" || t == "application/x-octet-stream" {
		return validExtension(extension) && extension != ".audio"
	}
	switch extension {
	case ".mp3":
		return t == "audio/mpeg" || t == "audio/mp3" || t == "audio/x-mp3" || t == "audio/mpeg3" || t == "audio/x-mpeg-3"
	case ".m4a":
		return t == "audio/mp4" || t == "audio/x-m4a"
	case ".aac":
		return t == "audio/aac"
	case ".flac":
		return t == "audio/flac" || t == "audio/x-flac"
	case ".ogg":
		return t == "audio/ogg" || t == "audio/opus" || t == "application/ogg"
	case ".wav":
		return t == "audio/wav" || t == "audio/wave" || t == "audio/x-wav" || t == "audio/vnd.wave"
	}
	return false
}
func detectFormat(b []byte) string {
	if len(b) >= 10 && string(b[:3]) == "ID3" {
		offset, err := id3Size(b[:10])
		if err != nil {
			return ""
		}
		// 标签跨过首屏peek时保持未识别，不能把标签内的封面当音频。
		if offset+12 > int64(len(b)) {
			return ".audio"
		}
		b = b[offset:]
		if len(b) >= 3 && string(b[:3]) == "ID3" {
			return ""
		}
	}
	switch {
	case len(b) >= 4 && bytes.Equal(b[:4], []byte("fLaC")):
		return ".flac"
	case len(b) >= 4 && bytes.Equal(b[:4], []byte("OggS")):
		return ".ogg"
	case len(b) >= 12 && bytes.Equal(b[:4], []byte("RIFF")) && bytes.Equal(b[8:12], []byte("WAVE")):
		return ".wav"
	case len(b) >= 12 && bytes.Equal(b[4:8], []byte("ftyp")):
		brand := string(b[8:12])
		if brand == "M4A " || brand == "M4B " || brand == "isom" || brand == "mp42" {
			return ".m4a"
		}
	case len(b) >= 4 && b[0] == 0xff && b[1]&0xf6 == 0xf0:
		return ".aac"
	case len(b) >= 4 && b[0] == 0xff && b[1]&0xe0 == 0xe0 && b[1]&0x06 != 0 && b[2]&0xf0 != 0 && b[2]&0xf0 != 0xf0:
		return ".mp3"
	}
	return ""
}
func jobRoot(job model.DownloadJob) string {
	// 调用方已在 Create/New 中校验 TargetPath。
	return filepath.Dir(filepath.Dir(job.TargetPath))
}

func verifyFile(ctx context.Context, file *os.File, total, maximum int64, extension string) (string, error) {
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || !singleLink(info) {
		return "", errFile
	}
	if info.Size() <= 0 || info.Size() != total || total > maximum {
		return "", errSize
	}
	actual, _, err := fileFormat(file, total)
	if err != nil {
		return "", err
	}
	if actual != extension {
		return "", errMedia
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return "", errFile
	}
	hash := sha256.New()
	buffer := make([]byte, 64<<10)
	count := int64(0)
	for {
		if err := ctx.Err(); err != nil {
			return "", err
		}
		n, err := file.Read(buffer)
		count += int64(n)
		if count > maximum || count > total {
			return "", errSize
		}
		if n > 0 {
			_, _ = hash.Write(buffer[:n])
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", errFile
		}
	}
	if count != total {
		return "", errSize
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func (m *Manager) finish(ctx context.Context, e *entry, root *os.Root, file *os.File, job model.DownloadJob, meta *partialMeta) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if err := m.state(e, "verifying"); err != nil {
		return err
	}
	if err := file.Sync(); err != nil {
		return diskError(err)
	}
	if err := prepareMedia(ctx, file, meta); err != nil {
		return err
	}
	digest, err := verifyFile(ctx, file, meta.Total, m.limits.maxBytes, meta.Extension)
	if err != nil {
		return err
	}
	var assets MetadataAssets
	publishFile, publishName := file, partName(job.ID)
	if job.FileNameFormat != "" {
		meta.ExtraWarnings = nil
		if job.WriteLyrics || job.WriteCover || job.EmbedTags {
			if err := m.state(e, "writing_metadata"); err != nil {
				return err
			}
		}
		assets = m.fetchAssets(ctx, e, job, meta)
		staged, err := m.prepareTags(ctx, root, file, job, meta, assets)
		if err != nil {
			return err
		}
		if staged != nil {
			publishFile, publishName = staged, tagPartName(job.ID)
			defer staged.Close()
			defer func() {
				if sameOpenFile(root, tagPartName(job.ID), staged) {
					_ = root.Remove(tagPartName(job.ID))
				}
			}()
			originalAfter, err := verifyFile(ctx, file, meta.Total, m.limits.maxBytes, meta.Extension)
			if err != nil {
				return err
			}
			if originalAfter != digest {
				return errFile
			}
			digest = meta.SHA256
		}
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	meta.SHA256 = digest
	m.mu.Lock()
	defer m.mu.Unlock()
	if e.stop != "" || ctx.Err() != nil {
		return context.Canceled
	}
	next := cloneJob(e.job)
	next.State = "finalizing"
	next.Speed = 0
	next.BytesDone = meta.Total
	next.BytesTotal = meta.Total
	if err := m.publishNamedLocked(ctx, e, root, publishFile, publishName, &next, meta); err != nil {
		return err
	}
	syncErr := syncDirectory(root)
	next.State = "completed"
	next.Error = ""
	if syncErr != nil {
		next.Error = "文件已落盘，但目录同步失败，请检查存储设备"
	}
	if !sameOpenFile(root, meta.Final, publishFile) {
		next.Error = errFile.Error()
		meta.Tagged = false
	}
	if meta.Tagged {
		if err := removeOwned(root, partName(job.ID), file); err != nil {
			addExtraWarning(meta, "cleanup")
		}
	}
	if job.FileNameFormat == "" || job.WriteMetadata {
		m.completeMetadata(root, &next, meta)
	} else {
		m.completeExtras(root, &next, meta, assets)
	}
	next.Warning = mergeWarning(next.Warning, mediaWarning(*meta))
	if err := m.commitCompletedLocked(e, next); err != nil {
		return err
	}
	return syncErr
}

// 文件一旦发布，不回退到 failed，不允许取消/重试删除或覆盖它。
func (m *Manager) commitCompletedLocked(e *entry, next model.DownloadJob) error {
	if err := m.commitLocked(e, next); err != nil {
		next.Error = errPersist.Error()
		next.UpdatedAt = timestamp()
		e.job = next
		e.lastErr = errPersist
		m.publishLocked()
		return err
	}
	return nil
}

func (m *Manager) recoverFinal(ctx context.Context, e *entry, root *os.Root, job model.DownloadJob, meta partialMeta) error {
	file, err := openRegular(root, meta.Final, false)
	if err != nil {
		return err
	}
	defer file.Close()
	if err := m.state(e, "verifying"); err != nil {
		return err
	}
	finalBytes := meta.Total
	if meta.Tagged {
		finalBytes = meta.FinalBytes
	}
	digest, err := verifyFile(ctx, file, finalBytes, m.limits.maxBytes, meta.Extension)
	if err != nil {
		return err
	}
	if digest != meta.SHA256 {
		return errCollision
	}
	var assets MetadataAssets
	if job.FileNameFormat != "" {
		assets = m.fetchAssets(ctx, e, job, &meta)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if e.stop != "" || ctx.Err() != nil {
		return context.Canceled
	}
	next := cloneJob(e.job)
	next.State = "completed"
	next.Error = ""
	next.Speed = 0
	next.BytesDone = meta.Total
	next.BytesTotal = meta.Total
	next.TargetPath = targetPath(e.root, job, meta.Extension)
	if job.FileNameFormat == "" || job.WriteMetadata {
		m.completeMetadata(root, &next, &meta)
	} else {
		if meta.Tagged {
			if original, err := openRegular(root, partName(job.ID), false); err == nil {
				if removeOwned(root, partName(job.ID), original) != nil {
					addExtraWarning(&meta, "cleanup")
				}
				original.Close()
			}
		}
		m.completeExtras(root, &next, &meta, assets)
	}
	next.Warning = mergeWarning(next.Warning, mediaWarning(meta))
	return m.commitCompletedLocked(e, next)
}

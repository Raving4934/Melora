package download

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"melora/internal/audiotags"
	"melora/internal/model"
)

const MaxLyricsBytes = audiotags.MaxLyricsBytes
const MaxCoverBytes = audiotags.MaxCoverBytes

func tagPartName(id string) string { return ".melora-" + id + ".tags.part" }

type assetReceipt struct {
	State  string `json:"state,omitempty"`
	Ext    string `json:"ext,omitempty"`
	Bytes  int64  `json:"bytes,omitempty"`
	SHA256 string `json:"sha256,omitempty"`
}

var extraWarnings = map[string]string{
	"fetch":            "歌词或封面获取失败，已保留音频",
	"lyrics":           "歌词缺失、格式不安全或超出大小限制",
	"cover":            "封面缺失、不支持或与 MIME/尺寸限制不符（仅支持 JPEG/PNG）",
	"text":             "标签文字不安全或超出大小限制，未修改内嵌标签",
	"tags_unsupported": "当前音频格式或标签结构不支持安全内嵌，未写入标签",
	"tags_invalid":     "音频标签结构无效，已保留原始音频",
	"tags_limit":       "内嵌标签超出文件或元数据大小限制，已保留原始音频",
	"tags_space":       "空间不足，未写内嵌标签，已保留原始音频",
	"tags_file":        "无法安全写入内嵌标签，已保留原始音频",
	"checkpoint":       "附加元数据恢复检查点保存失败",
	"cleanup":          "附加文件已完成，原始临时文件清理失败",
	"results":          "无法确认附加文件的实际结果，请检查下载目录",
}

func addExtraWarning(meta *partialMeta, code string) {
	if extraWarnings[code] == "" {
		return
	}
	for _, v := range meta.ExtraWarnings {
		if v == code {
			return
		}
	}
	meta.ExtraWarnings = append(meta.ExtraWarnings, code)
}
func mergeWarning(current, next string) string {
	if next == "" {
		return current
	}
	if current == "" {
		return next
	}
	if strings.Contains(current, next) {
		return current
	}
	return current + "；" + next
}
func receiptValid(r assetReceipt, cover bool) bool {
	if r.State == "" {
		return r.Ext == "" && r.Bytes == 0 && r.SHA256 == ""
	}
	switch r.State {
	case "pending", "written", "exists", "space", "probe", "file", "missing":
	default:
		return false
	}
	if r.State == "pending" || r.State == "written" {
		maxBytes := int64(MaxLyricsBytes)
		validExt := r.Ext == ".lrc"
		if cover {
			maxBytes = MaxCoverBytes
			validExt = r.Ext == ".jpg" || r.Ext == ".png"
		}
		hash, err := hex.DecodeString(r.SHA256)
		return validExt && r.Bytes > 0 && r.Bytes <= maxBytes && err == nil && len(hash) == 32
	}
	return r.Ext == "" && r.Bytes == 0 && r.SHA256 == ""
}
func safeLyrics(raw string) bool {
	if len(raw) == 0 || len(raw) > MaxLyricsBytes {
		return false
	}
	for _, line := range strings.Split(strings.ReplaceAll(raw, "\r\n", "\n"), "\n") {
		if !safeMetadataText(line, MaxLyricsBytes) {
			return false
		}
	}
	return true
}
func (m *Manager) fetchAssets(ctx context.Context, e *entry, job model.DownloadJob, meta *partialMeta) MetadataAssets {
	var a MetadataAssets
	if !(job.WriteLyrics || job.WriteCover || job.EmbedTags) {
		return a
	}
	if e.fetch == nil {
		addExtraWarning(meta, "fetch")
	} else {
		bounded, cancel := context.WithTimeout(ctx, 9*time.Second)
		got, err := e.fetch(bounded, cloneJob(job))
		timedOut := bounded.Err() != nil
		cancel()
		if err != nil || timedOut {
			addExtraWarning(meta, "fetch")
		}
		if !timedOut {
			a = got
		}
	}
	if safeLyrics(a.Lyrics) {
		a.Lyrics = strings.ReplaceAll(a.Lyrics, "\r\n", "\n")
	} else {
		a.Lyrics = ""
		if job.WriteLyrics || job.EmbedTags {
			addExtraWarning(meta, "lyrics")
		}
	}
	if len(a.Cover) > 0 && len(a.Cover) <= MaxCoverBytes {
		a.Cover = append([]byte(nil), a.Cover...)
	} else {
		a.Cover = nil
	}
	if mime, _, err := audiotags.ValidateCover(a.Cover, a.CoverMIME); err == nil {
		a.CoverMIME = mime
	} else {
		a.Cover = nil
		a.CoverMIME = ""
		if job.WriteCover || job.EmbedTags {
			addExtraWarning(meta, "cover")
		}
	}
	return a
}

type tagOutput struct {
	manager     *Manager
	root        *os.Root
	file        *os.File
	name        string
	guard       spaceGuard
	done, total int64
}

func (w *tagOutput) Write(p []byte) (int, error) {
	if int64(len(p)) > w.manager.limits.maxBytes-w.done {
		return 0, audiotags.ErrLimit
	}
	written := 0
	for len(p) > 0 {
		n := min(len(p), 64<<10)
		if err := w.guard.check(w.done, max(w.total, w.done+int64(n)), n); err != nil {
			return written, err
		}
		if !sameOpenFile(w.root, w.name, w.file) {
			return written, errFile
		}
		done, err := w.file.Write(p[:n])
		w.done += int64(done)
		written += done
		if err != nil || done != n {
			return written, diskError(err)
		}
		p = p[n:]
	}
	return written, nil
}
func removeOwned(root *os.Root, name string, file *os.File) error {
	if !sameOpenFile(root, name, file) {
		return errFile
	}
	if err := root.Remove(name); err != nil {
		return diskError(err)
	}
	return nil
}
func discardStaged(root *os.Root, id string) error {
	name := tagPartName(id)
	if _, err := root.Lstat(name); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	file, err := openRegular(root, name, false)
	if err != nil {
		return err
	}
	defer file.Close()
	return removeOwned(root, name, file)
}
func (m *Manager) prepareTags(ctx context.Context, root *os.Root, file *os.File, job model.DownloadJob, meta *partialMeta, a MetadataAssets) (*os.File, error) {
	meta.Tagged = false
	meta.FinalBytes = 0
	if !job.EmbedTags {
		return nil, nil
	}
	if meta.Extension != ".mp3" && meta.Extension != ".flac" && meta.Extension != ".m4a" {
		addExtraWarning(meta, "tags_unsupported")
		return nil, nil
	}
	for _, text := range []string{job.Track.Title, job.Track.Artist, job.Track.Album} {
		if !safeMetadataText(text, 4096) {
			addExtraWarning(meta, "text")
			return nil, nil
		}
	}
	estimate := meta.Total + int64(len(a.Cover)+len(a.Lyrics)*2+len(job.Track.Title)*2+len(job.Track.Artist)*2+len(job.Track.Album)*2) + (64 << 10)
	var err error
	var staged *os.File
	if err = m.checkSpace(root, estimate); err == nil {
		err = discardStaged(root, job.ID)
	}
	name := tagPartName(job.ID)
	if err == nil {
		staged, err = root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_RDWR|safeOpenFlags(), 0600)
		if err != nil {
			err = diskError(err)
		}
	}
	if err == nil {
		if !sameOpenFile(root, partName(job.ID), file) || !sameOpenFile(root, name, staged) {
			err = errFile
		} else {
			writer := &tagOutput{manager: m, root: root, file: staged, name: name, total: estimate, guard: spaceGuard{manager: m, root: root}}
			var result audiotags.Result
			result, err = audiotags.Write(ctx, file, meta.Total, writer, meta.Extension, audiotags.Tags{Title: job.Track.Title, Artist: job.Track.Artist, Album: job.Track.Album, Lyrics: a.Lyrics, Cover: a.Cover, CoverMIME: a.CoverMIME})
			if err == nil {
				err = staged.Sync()
				if err != nil {
					err = diskError(err)
				}
			}
			if err == nil {
				meta.SHA256, err = verifyFile(ctx, staged, result.Size, m.limits.maxBytes, meta.Extension)
			}
			if err == nil && (!sameOpenFile(root, name, staged) || !sameOpenFile(root, partName(job.ID), file)) {
				err = errFile
			}
			if err == nil {
				meta.Tagged = true
				meta.FinalBytes = result.Size
				return staged, nil
			}
		}
	}
	if staged != nil {
		_ = removeOwned(root, name, staged)
		staged.Close()
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	switch {
	case errors.Is(err, audiotags.ErrUnsupported):
		addExtraWarning(meta, "tags_unsupported")
	case errors.Is(err, audiotags.ErrInvalid):
		addExtraWarning(meta, "tags_invalid")
	case errors.Is(err, audiotags.ErrLimit):
		addExtraWarning(meta, "tags_limit")
	case errors.Is(err, errSpace):
		addExtraWarning(meta, "tags_space")
	default:
		addExtraWarning(meta, "tags_file")
	}
	return nil, nil
}
func (m *Manager) writeAsset(root *os.Root, name string, data []byte) error {
	if filepath.Base(name) != name || strings.ContainsAny(name, `/\`) || len(data) == 0 || len(data) > MaxCoverBytes {
		return errFile
	}
	if _, err := root.Lstat(name); err == nil {
		return errCollision
	} else if !errors.Is(err, os.ErrNotExist) {
		return errFile
	}
	if err := m.checkSpace(root, int64(len(data))); err != nil {
		return err
	}
	id, err := randomID()
	if err != nil {
		return err
	}
	temp := ".melora-asset-" + id
	file, err := root.OpenFile(temp, os.O_CREATE|os.O_EXCL|os.O_RDWR|safeOpenFlags(), 0600)
	if err != nil {
		return diskError(err)
	}
	defer file.Close()
	defer func() {
		if sameOpenFile(root, temp, file) {
			_ = root.Remove(temp)
		}
	}()
	guard := spaceGuard{manager: m, root: root}
	done := 0
	for done < len(data) {
		n := min(64<<10, len(data)-done)
		if err := guard.check(int64(done), int64(len(data)), n); err != nil {
			return err
		}
		if !sameOpenFile(root, temp, file) {
			return errFile
		}
		w, err := file.Write(data[done : done+n])
		if err != nil || w != n {
			return diskError(err)
		}
		done += w
	}
	if err := file.Sync(); err != nil {
		return diskError(err)
	}
	if !sameOpenFile(root, temp, file) {
		return errFile
	}
	if err := renameNoReplace(root, temp, name); err != nil {
		return err
	}
	if !sameOpenFile(root, name, file) {
		return errFile
	}
	return syncDirectory(root)
}
func checkAsset(root *os.Root, name string, r assetReceipt) error {
	f, err := openRegular(root, name, false)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.Size() != r.Bytes {
		return errFile
	}
	hash := sha256.New()
	n, err := io.Copy(hash, io.LimitReader(f, r.Bytes+1))
	if err != nil || n != r.Bytes || hex.EncodeToString(hash.Sum(nil)) != r.SHA256 || !sameOpenFile(root, name, f) {
		return errFile
	}
	return nil
}
func receiptError(err error) string {
	switch {
	case errors.Is(err, errCollision):
		return "exists"
	case errors.Is(err, errSpace):
		return "space"
	case errors.Is(err, errSpaceProbe):
		return "probe"
	default:
		return "file"
	}
}
func (m *Manager) completeAsset(root *os.Root, job model.DownloadJob, meta *partialMeta, r *assetReceipt, ext string, data []byte) string {
	if r.State == "written" || r.State == "pending" {
		name := jobStem(job) + r.Ext
		if err := checkAsset(root, name, *r); err == nil {
			r.State = "written"
			return filepath.Join(filepath.Dir(job.TargetPath), name)
		}
		if r.State == "written" {
			*r = assetReceipt{State: "file"}
			return ""
		}
		if _, err := root.Lstat(name); err == nil {
			*r = assetReceipt{State: "exists"}
			return ""
		}
		// pending 未发布且重取素材与原意图不一致时，不替换该意图。
		hash := sha256.Sum256(data)
		if len(data) == 0 || int64(len(data)) != r.Bytes || hex.EncodeToString(hash[:]) != r.SHA256 {
			*r = assetReceipt{State: "missing"}
			return ""
		}
	} else if r.State != "" {
		return ""
	}
	if len(data) == 0 {
		*r = assetReceipt{State: "missing"}
		return ""
	}
	hash := sha256.Sum256(data)
	*r = assetReceipt{State: "pending", Ext: ext, Bytes: int64(len(data)), SHA256: hex.EncodeToString(hash[:])}
	if err := saveMeta(root, job.ID, *meta); err != nil {
		addExtraWarning(meta, "checkpoint")
		return ""
	}
	name := jobStem(job) + ext
	if err := m.writeAsset(root, name, data); err != nil {
		*r = assetReceipt{State: receiptError(err)}
		return ""
	}
	r.State = "written"
	return filepath.Join(filepath.Dir(job.TargetPath), name)
}
func (m *Manager) completeExtras(root *os.Root, job *model.DownloadJob, meta *partialMeta, a MetadataAssets) {
	job.LyricsPath = ""
	job.CoverPath = ""
	job.TagsWritten = meta.Tagged
	if job.WriteLyrics {
		job.LyricsPath = m.completeAsset(root, *job, meta, &meta.Lyrics, ".lrc", []byte(a.Lyrics))
	}
	if job.WriteCover {
		ext := ".jpg"
		if a.CoverMIME == "image/png" {
			ext = ".png"
		}
		job.CoverPath = m.completeAsset(root, *job, meta, &meta.Cover, ext, a.Cover)
	}
	if err := saveMeta(root, job.ID, *meta); err != nil {
		addExtraWarning(meta, "checkpoint")
	}
	job.Warning = ""
	for _, code := range meta.ExtraWarnings {
		job.Warning = mergeWarning(job.Warning, extraWarnings[code])
	}
	for _, v := range []struct {
		name    string
		r       assetReceipt
		enabled bool
	}{{"歌词", meta.Lyrics, job.WriteLyrics}, {"封面", meta.Cover, job.WriteCover}} {
		if v.enabled && v.r.State != "written" {
			reason := "无法安全写入"
			switch v.r.State {
			case "exists":
				reason = "同名文件已存在，未覆盖"
			case "space":
				reason = "空间不足"
			case "probe":
				reason = "无法确认可用空间"
			case "missing":
				reason = "素材不可用"
			}
			job.Warning = mergeWarning(job.Warning, v.name+"未写入："+reason)
		}
	}
}
func validateExtrasPath(job model.DownloadJob) error {
	if job.TagsWritten && (!job.EmbedTags || job.State != "completed") {
		return errMetadataPath
	}
	for _, p := range []struct {
		value   string
		enabled bool
		cover   bool
	}{{job.LyricsPath, job.WriteLyrics, false}, {job.CoverPath, job.WriteCover, true}} {
		if p.value == "" {
			continue
		}
		ext := filepath.Ext(p.value)
		validExt := ext == ".lrc"
		if p.cover {
			validExt = ext == ".jpg" || ext == ".png"
		}
		if !p.enabled || job.State != "completed" || job.TargetPath == "" || !validExt || p.value != filepath.Join(filepath.Dir(job.TargetPath), jobStem(job)+ext) {
			return errMetadataPath
		}
	}
	return nil
}
func restoreExtras(root *os.Root, authorized string, e *entry) {
	if e.job.LyricsPath == "" && e.job.CoverPath == "" && !e.job.TagsWritten {
		return
	}
	reset := func() {
		e.job.LyricsPath = ""
		e.job.CoverPath = ""
		e.job.TagsWritten = false
		e.job.Warning = mergeWarning(e.job.Warning, extraWarnings["results"])
	}
	if root == nil || e.root != authorized {
		reset()
		return
	}
	meta, err := loadMeta(root, e.job.ID)
	if err != nil {
		reset()
		return
	}
	if e.job.LyricsPath != "" && (meta.Lyrics.State != "written" || checkAsset(root, filepath.Base(e.job.LyricsPath), meta.Lyrics) != nil) {
		e.job.LyricsPath = ""
		e.job.Warning = mergeWarning(e.job.Warning, extraWarnings["results"])
	}
	if e.job.CoverPath != "" && (meta.Cover.State != "written" || checkAsset(root, filepath.Base(e.job.CoverPath), meta.Cover) != nil) {
		e.job.CoverPath = ""
		e.job.Warning = mergeWarning(e.job.Warning, extraWarnings["results"])
	}
	if e.job.TagsWritten {
		info, err := root.Lstat(filepath.Base(e.job.TargetPath))
		if err != nil || !info.Mode().IsRegular() || !singleLink(info) || !meta.Tagged || info.Size() != meta.FinalBytes {
			e.job.TagsWritten = false
			e.job.Warning = mergeWarning(e.job.Warning, extraWarnings["results"])
		}
	}
}

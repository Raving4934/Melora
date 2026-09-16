package download

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode"
	"unicode/utf8"

	"melora/internal/model"
)

const maxMetadataBytes = 32 << 10

var (
	errMetadataText = errors.New("元数据包含不安全或过长文本")
	errMetadataPath = errors.New("已保存的元数据路径无效")
)

// 显式白名单，绝不序列化 Track/DownloadJob、解析响应或任何 URL/封面字段。
type sidecarMetadata struct {
	Version    int    `json:"version"`
	Title      string `json:"title"`
	Artist     string `json:"artist"`
	Album      string `json:"album"`
	ProviderID string `json:"providerId"`
	TrackID    string `json:"trackId"`
	Quality    string `json:"quality"`
	Bytes      int64  `json:"bytes"`
}

func metadataJSON(job model.DownloadJob) ([]byte, error) {
	fields := []struct {
		text  string
		limit int
	}{
		{job.Track.Title, 4096}, {job.Track.Artist, 4096}, {job.Track.Album, 4096},
		{job.Track.ProviderID, 128}, {job.Track.ID, 256}, {job.Quality, 64},
	}
	for _, field := range fields {
		if !safeMetadataText(field.text, field.limit) {
			return nil, errMetadataText
		}
	}
	if job.BytesDone < 0 || job.BytesDone > defaultMaxBytes {
		return nil, errMetadataText
	}
	data, err := json.Marshal(sidecarMetadata{1, job.Track.Title, job.Track.Artist, job.Track.Album, job.Track.ProviderID, job.Track.ID, job.Quality, job.BytesDone})
	if err != nil || len(data)+1 > maxMetadataBytes {
		return nil, errMetadataText
	}
	return append(data, '\n'), nil
}

func safeMetadataText(text string, limit int) bool {
	if len(text) > limit || !utf8.ValidString(text) {
		return false
	}
	for _, r := range text {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return false
		}
	}
	lower := strings.ToLower(text)
	for _, forbidden := range []string{"://", "http:", "https:", "file:", "data:", "javascript:", "token=", "token:", "authorization:", "cookie:", "bearer "} {
		if strings.Contains(lower, forbidden) {
			return false
		}
	}
	// 防止上游把路径或凭据误塞进文字字段；保留 AC/DC 等普通曲名。
	for _, word := range strings.FieldsFunc(text, func(r rune) bool {
		return unicode.IsSpace(r) || strings.ContainsRune("=\"'()[]{}，,;", r)
	}) {
		if strings.HasPrefix(word, "/") || strings.HasPrefix(word, `\`) || len(word) >= 3 && word[1] == ':' && (word[2] == '/' || word[2] == '\\') {
			return false
		}
	}
	return true
}

func metadataName(job model.DownloadJob) string { return jobStem(job) + ".json" }
func metadataPath(job model.DownloadJob) string {
	return filepath.Join(filepath.Dir(job.TargetPath), metadataName(job))
}

func validateMetadataPath(job model.DownloadJob) error {
	if job.MetadataPath != "" && (!job.WriteMetadata || job.State != "completed" || job.TargetPath == "" || job.MetadataPath != metadataPath(job)) {
		return errMetadataPath
	}
	return nil
}

// 校验名称仍指向打开的单链接常规文件，读写和原子发布前后均使用。
func sameOpenFile(root *os.Root, name string, file *os.File) bool {
	named, err := root.Lstat(name)
	actual, statErr := file.Stat()
	return err == nil && statErr == nil && named.Mode().IsRegular() && actual.Mode().IsRegular() && singleLink(named) && singleLink(actual) && os.SameFile(named, actual)
}

func writeSidecar(root *os.Root, name string, data []byte) error {
	if filepath.Base(name) != name || strings.ContainsAny(name, `/\`) || !strings.HasSuffix(name, ".json") || len(data) == 0 || len(data) > maxMetadataBytes {
		return errFile
	}
	if _, err := root.Lstat(name); err == nil {
		return errCollision
	} else if !errors.Is(err, os.ErrNotExist) {
		return errFile
	}
	id, err := randomID()
	if err != nil {
		return err
	}
	temp := ".melora-sidecar-" + id
	file, err := root.OpenFile(temp, os.O_CREATE|os.O_EXCL|os.O_RDWR|safeOpenFlags(), 0600)
	if err != nil {
		return diskError(err)
	}
	defer file.Close()
	defer func() {
		// 不删除被并发替换的临时路径，也不跟随链接做清理。
		if sameOpenFile(root, temp, file) {
			_ = root.Remove(temp)
		}
	}()
	if !sameOpenFile(root, temp, file) {
		return errFile
	}
	n, err := file.Write(data)
	if err != nil || n != len(data) {
		return diskError(err)
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
	if err := syncDirectory(root); err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return diskError(err)
	}
	return nil
}

func checkSidecar(root *os.Root, job model.DownloadJob) error {
	data, err := metadataJSON(job)
	if err != nil {
		return err
	}
	name := metadataName(job)
	file, err := openRegular(root, name, false)
	if err != nil {
		return err
	}
	defer file.Close()
	actual, err := io.ReadAll(io.LimitReader(file, maxMetadataBytes+1))
	if err != nil || !bytes.Equal(data, actual) || !sameOpenFile(root, name, file) {
		return errFile
	}
	return nil
}

func sidecarWarning(status string) string {
	switch status {
	case "exists":
		return "音频已完成；JSON 元数据文件已存在，未覆盖"
	case "space":
		return "音频已完成；空间不足，JSON 元数据未写入"
	case "probe":
		return "音频已完成；无法确认可用空间，JSON 元数据未写入"
	case "text":
		return "音频已完成；元数据文本不安全或超出大小限制，JSON 元数据未写入"
	case "file":
		return "音频已完成；JSON 元数据无法安全写入或校验，请检查目录权限和文件"
	}
	return ""
}

// 调用方持有 Manager.mu。将 sidecar 结果纳入 completed 的同一次持久化；
// 私有恢复检查点记录结果，完成提交失败后也不会覆盖或重试已有用户文件。
func (m *Manager) completeMetadata(root *os.Root, job *model.DownloadJob, meta *partialMeta) {
	job.MetadataPath, job.Warning = "", ""
	if !job.WriteMetadata {
		return
	}
	previous := meta.Sidecar
	var err error
	if meta.Sidecar == "written" {
		err = checkSidecar(root, *job)
	} else if meta.Sidecar == "" {
		var data []byte
		data, err = metadataJSON(*job)
		if err == nil {
			err = m.checkSpace(root, int64(len(data)))
		}
		if err == nil {
			err = writeSidecar(root, metadataName(*job), data)
		}
		if err == nil {
			meta.Sidecar = "written"
		}
	}
	if err != nil {
		switch {
		case errors.Is(err, errCollision):
			meta.Sidecar = "exists"
		case errors.Is(err, errSpace):
			meta.Sidecar = "space"
		case errors.Is(err, errSpaceProbe):
			meta.Sidecar = "probe"
		case errors.Is(err, errMetadataText):
			meta.Sidecar = "text"
		default:
			meta.Sidecar = "file"
		}
	}
	if meta.Sidecar == "written" {
		job.MetadataPath = metadataPath(*job)
	} else {
		job.Warning = sidecarWarning(meta.Sidecar)
	}
	if previous != meta.Sidecar {
		if err := saveMeta(root, job.ID, *meta); err != nil {
			if job.Warning != "" {
				job.Warning += "；"
			}
			job.Warning += "元数据恢复检查点保存失败，请检查存储设备"
		}
	}
}

func restoreMetadata(root *os.Root, authorized string, e *entry) {
	// 旧 job 默认 false；启动不生成、删除或更改任何用户 sidecar。
	if e.job.MetadataPath == "" {
		return
	}
	if root == nil || e.root != authorized || checkSidecar(root, e.job) != nil {
		e.job.MetadataPath = ""
		e.job.Warning = sidecarWarning("file")
	}
}

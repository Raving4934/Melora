package download

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode"
	"unicode/utf8"

	"melora/internal/storage"
)

var (
	errRoot      = errors.New("下载目录必须是已存在、可写且不含符号链接的绝对目录")
	errFile      = errors.New("无法安全访问下载文件，请检查目录权限和剩余空间")
	errCollision = errors.New("目标文件已存在，拒绝覆盖")
)

func randomID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", errFile
	}
	return hex.EncodeToString(b[:]), nil
}

// 沿用统一的逐级FD固定策略，下载器与设置/目录浏览使用相同路径边界。
func openAuthorizedRoot(path string) (*os.Root, string, error) {
	root, err := storage.OpenDirectory(path)
	if err != nil {
		return nil, "", errRoot
	}
	return root, filepath.Clean(path), nil
}

func probeRoot(root *os.Root) error {
	id, err := randomID()
	if err != nil {
		return errRoot
	}
	name := ".melora-probe-" + id
	f, err := root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return errRoot
	}
	_, writeErr := f.Write([]byte("melora"))
	syncErr := f.Sync()
	closeErr := f.Close()
	removeErr := root.Remove(name)
	if writeErr != nil || syncErr != nil || closeErr != nil || removeErr != nil {
		return errRoot
	}
	return nil
}

// ValidateRoot 只验证目录，不授予权限；授权根白名单由 API 层负责。
func ValidateRoot(path string) (string, error) {
	root, clean, err := openAuthorizedRoot(path)
	if err != nil {
		return "", err
	}
	defer root.Close()
	if err := probeRoot(root); err != nil {
		return "", err
	}
	return clean, nil
}

func openStorage(path string) (*os.Root, string, error) {
	if path == "" {
		return nil, "", nil
	}
	root, clean, err := openAuthorizedRoot(path)
	if err != nil {
		return nil, "", err
	}
	defer root.Close()
	if err := probeRoot(root); err != nil {
		return nil, "", err
	}
	if err := root.Mkdir("Singles", 0700); err != nil && !errors.Is(err, os.ErrExist) {
		return nil, "", errRoot
	}
	info, err := root.Lstat("Singles")
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, "", errRoot
	}
	files, err := root.OpenRoot("Singles")
	if err != nil {
		return nil, "", errRoot
	}
	actual, err := files.Stat(".")
	if err != nil || !os.SameFile(info, actual) {
		files.Close()
		return nil, "", errRoot
	}
	if err := probeRoot(files); err != nil {
		files.Close()
		return nil, "", err
	}
	return files, clean, nil
}

func safeName(s string) string {
	s = strings.ToValidUTF8(s, "_")
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) || strings.ContainsRune(`/\:*?"<>|`, r) {
			return '_'
		}
		return r
	}, s)
	s = strings.Join(strings.Fields(s), " ")
	s = strings.Trim(s, " .")
	if s == "" {
		s = "untitled"
	}
	stem := strings.ToUpper(strings.SplitN(s, ".", 2)[0])
	reserved := stem == "CON" || stem == "PRN" || stem == "AUX" || stem == "NUL"
	if len(stem) == 4 && (strings.HasPrefix(stem, "COM") || strings.HasPrefix(stem, "LPT")) && stem[3] >= '1' && stem[3] <= '9' {
		reserved = true
	}
	if reserved {
		s = "_" + s
	}
	// 按 UTF-8 字节限长，避免多字节字符被截断，也为 ID/扩展名保留空间。
	if len(s) > 64 {
		s = s[:64]
		for !utf8.ValidString(s) {
			s = s[:len(s)-1]
		}
		s = strings.TrimRight(s, " .")
	}
	return s
}
func validID(id string) bool {
	if len(id) == 0 || len(id) > 80 {
		return false
	}
	for _, r := range id {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_' || r == '-') {
			return false
		}
	}
	return true
}
func partName(id string) string { return ".melora-" + id + ".part" }
func metaName(id string) string { return ".melora-" + id + ".json" }

func openRegular(root *os.Root, name string, create bool) (*os.File, error) {
	if filepath.Base(name) != name || name == "." || name == ".." {
		return nil, errFile
	}
	if create {
		f, err := root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_RDWR|safeOpenFlags(), 0600)
		if err == nil {
			return f, nil
		}
		if !errors.Is(err, os.ErrExist) {
			return nil, diskError(err)
		}
	}
	before, err := root.Lstat(name)
	if err != nil || !before.Mode().IsRegular() || !singleLink(before) {
		return nil, errFile
	}
	f, err := root.OpenFile(name, os.O_RDWR|safeOpenFlags(), 0)
	if err != nil {
		return nil, errFile
	}
	after, err := f.Stat()
	if err != nil || !after.Mode().IsRegular() || !singleLink(after) || !os.SameFile(before, after) {
		f.Close()
		return nil, errFile
	}
	return f, nil
}

type partialMeta struct {
	ReportedFormats []string     `json:"reportedFormats,omitempty"`
	Tagged          bool         `json:"tagged,omitempty"`
	FinalBytes      int64        `json:"finalBytes,omitempty"`
	Lyrics          assetReceipt `json:"lyrics,omitempty"`
	Cover           assetReceipt `json:"cover,omitempty"`
	ExtraWarnings   []string     `json:"extraWarnings,omitempty"`
	Sidecar         string       `json:"sidecar,omitempty"`
	Version         int          `json:"version"`
	ETag            string       `json:"etag,omitempty"`
	LastModified    string       `json:"lastModified,omitempty"`
	Date            string       `json:"date,omitempty"`
	ResourceHash    string       `json:"resourceHash,omitempty"`
	Total           int64        `json:"total"`
	Extension       string       `json:"extension"`
	Final           string       `json:"final,omitempty"`
	SHA256          string       `json:"sha256,omitempty"`
}

func loadMeta(root *os.Root, id string) (partialMeta, error) {
	var m partialMeta
	if _, err := root.Lstat(metaName(id)); errors.Is(err, os.ErrNotExist) {
		return m, nil
	}
	f, err := openRegular(root, metaName(id), false)
	if err != nil {
		return m, err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, 4097))
	if err != nil || len(b) > 4096 {
		return m, errFile
	}
	if json.Unmarshal(b, &m) != nil || m.Version != 1 || m.Total < 0 || m.Total > defaultMaxBytes || !validExtension(m.Extension) || len(m.ETag) > 512 || len(m.LastModified) > 128 || len(m.Date) > 128 {
		return partialMeta{}, errFile
	}
	if len(m.ReportedFormats) > 6 {
		return partialMeta{}, errFile
	}
	for _, extension := range m.ReportedFormats {
		if !validExtension(extension) || extension == ".audio" || extension == "" {
			return partialMeta{}, errFile
		}
	}
	if m.FinalBytes < 0 || m.FinalBytes > defaultMaxBytes || m.Tagged && (m.FinalBytes == 0 || m.Final == "") || !receiptValid(m.Lyrics, false) || !receiptValid(m.Cover, true) || len(m.ExtraWarnings) > 16 {
		return partialMeta{}, errFile
	}
	for _, code := range m.ExtraWarnings {
		if extraWarnings[code] == "" {
			return partialMeta{}, errFile
		}
	}
	if m.Sidecar != "" && m.Sidecar != "written" && sidecarWarning(m.Sidecar) == "" {
		return partialMeta{}, errFile
	}
	if m.Sidecar != "" && m.Final == "" {
		return partialMeta{}, errFile
	}
	if m.ResourceHash != "" {
		if decoded, err := hex.DecodeString(m.ResourceHash); err != nil || len(decoded) != 32 {
			return partialMeta{}, errFile
		}
	}
	if strings.ContainsAny(m.ETag+m.LastModified+m.Date, "\x00\r\n") {
		return partialMeta{}, errFile
	}
	if m.Final != "" && (filepath.Base(m.Final) != m.Final || strings.ContainsAny(m.Final, `/\`) || len(m.SHA256) != 64) {
		return partialMeta{}, errFile
	}
	return m, nil
}
func saveMeta(root *os.Root, id string, m partialMeta) error {
	b, err := json.Marshal(m)
	if err != nil || len(b) > 4096 {
		return errFile
	}
	nonce, err := randomID()
	if err != nil {
		return err
	}
	temp := ".melora-meta-" + nonce
	f, err := root.OpenFile(temp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return diskError(err)
	}
	defer root.Remove(temp)
	_, werr := f.Write(b)
	serr := f.Sync()
	cerr := f.Close()
	if werr != nil || serr != nil || cerr != nil {
		return diskError(errors.Join(werr, serr, cerr))
	}
	// 只替换本任务私有元数据，不覆盖最终音乐文件；rename 不跟随末端 symlink。
	if err := root.Rename(temp, metaName(id)); err != nil {
		return diskError(err)
	}
	return syncDirectory(root)
}
func removePartial(root *os.Root, id string) error {
	if root == nil {
		return errRoot
	}
	for _, name := range []string{partName(id), tagPartName(id), metaName(id)} {
		if err := root.Remove(name); err != nil && !errors.Is(err, os.ErrNotExist) {
			return errFile
		}
	}
	return syncDirectory(root)
}
func syncDirectory(root *os.Root) error {
	d, err := root.Open(".")
	if err != nil {
		return errFile
	}
	defer d.Close()
	if err := d.Sync(); err != nil {
		return diskError(err)
	}
	return nil
}
func validExtension(s string) bool {
	switch s {
	case ".audio", ".mp3", ".flac", ".ogg", ".m4a", ".aac", ".wav":
		return true
	}
	return false
}

// ValidateExistingDestination 检查已有下载子目录，不创建它、不改变权限。
// 保存目录尚无Singles时由真正的下载配置负责创建；验证不能越过调用方固定的授权FD。
func ValidateExistingDestination(root *os.Root) error {
	if root == nil {
		return errRoot
	}
	info, err := root.Lstat("Singles")
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errRoot
	}
	files, err := storage.OpenRelativeDirectory(root, "Singles")
	if err != nil {
		return errRoot
	}
	defer files.Close()
	return probeRoot(files)
}

package download

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"melora/internal/model"
)

// base 占第 1 个候选，随后 (2)..(10000)。创建与晚到冲突均有硬上限，
// 不枚举目录，也不在耗尽时退回随机 ID 或覆盖已有文件。
const maxNameCandidates = 10000

var audioNameExtensions = []string{".audio", ".mp3", ".flac", ".ogg", ".m4a", ".aac", ".wav"}

// 仅用于读取旧快照/checkpoint；新分配不再追加任务 ID。
func collisionStem(job model.DownloadJob) string { return baseStem(job) + " (" + job.ID + ")" }

func numberedStem(base string, ordinal int) string {
	if ordinal == 1 {
		return base
	}
	return base + " (" + strconv.Itoa(ordinal) + ")"
}

// 只认规范十进制短号；拒绝 01、+2、超限值以及混入扩展/路径的后缀。
func stemOrdinal(base, stem string) (int, bool) {
	if stem == base {
		return 1, true
	}
	suffix, ok := strings.CutPrefix(stem, base+" (")
	if !ok {
		return 0, false
	}
	digits, ok := strings.CutSuffix(suffix, ")")
	if !ok {
		return 0, false
	}
	n, err := strconv.Atoi(digits)
	if err != nil || n < 2 || n > maxNameCandidates || strconv.Itoa(n) != digits {
		return 0, false
	}
	return n, true
}

func validStem(job model.DownloadJob, stem string) bool {
	base := baseStem(job)
	if stem == base {
		return true
	}
	if job.FileNameFormat == "" {
		return false
	}
	_, numbered := stemOrdinal(base, stem)
	return numbered || stem == collisionStem(job)
}

// 调用方持有 m.mu；只构造一次预留集，避免每个候选都遍历全部任务。
// failed/paused/completed 同样保留名称；cancelled 只释放内存预留，磁盘仍需检查。
func (m *Manager) reservedStems(job model.DownloadJob, directory string) map[string]struct{} {
	reserved := make(map[string]struct{}, len(m.jobs))
	for _, e := range m.jobs {
		if e.job.ID != job.ID && e.root == directory && e.job.State != "cancelled" {
			reserved[jobStem(e.job)] = struct{}{}
		}
	}
	return reserved
}

func stemAvailable(root *os.Root, reserved map[string]struct{}, stem string) (bool, error) {
	if _, exists := reserved[stem]; exists {
		return false, nil
	}
	if available, err := stemFilesAvailable(root, stem, audioNameExtensions); !available || err != nil {
		return available, err
	}
	return stemFilesAvailable(root, stem, []string{".lrc", ".jpg", ".png", ".json"})
}

func stemFilesAvailable(root *os.Root, stem string, extensions []string) (bool, error) {
	// 固定目录 FD 下逐叶 Lstat：符号链接/目录/硬链接也视为占位，
	// 不跟随、不递归、不删除；待识别的 .audio 与所有已支持音频扩展互斥。
	for _, ext := range extensions {
		if _, err := root.Lstat(stem + ext); err == nil {
			return false, nil
		} else if !errors.Is(err, os.ErrNotExist) {
			return false, errFile
		}
	}
	return true, nil
}

func (m *Manager) planName(job *model.DownloadJob) error {
	reserved := m.reservedStems(*job, m.root)
	base := baseStem(*job)
	for ordinal := 1; ordinal <= maxNameCandidates; ordinal++ {
		stem := numberedStem(base, ordinal)
		available, err := stemAvailable(m.files, reserved, stem)
		if err != nil {
			return err
		}
		if available {
			job.TargetPath = filepath.Join(m.root, "Singles", stem+".audio")
			return nil
		}
	}
	return errCollision
}

// 调用方持有 m.mu 直到 completed；保留原预留名，发生冲突才递增，不填回更小空号。
// 每次候选都先保存 checkpoint，再持久化 TargetPath，最后原子不覆盖发布。
// checkpoint 成功而回调失败时由 run 校验并接回该名称，绝不删除 part 来“解决”冲突。
func (m *Manager) publishNamedLocked(ctx context.Context, e *entry, root *os.Root, file *os.File, source string, next *model.DownloadJob, meta *partialMeta) error {
	base, stem := baseStem(*next), jobStem(*next)
	ordinal, _ := stemOrdinal(base, stem) // 旧 ID 名为 0，真实冲突后从 (2) 开始。
	reserved := m.reservedStems(*next, e.root)
	modern := next.FileNameFormat != ""
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		available := true
		var err error
		if modern {
			available, err = stemAvailable(root, reserved, stem)
			if err != nil {
				return err
			}
		}
		if available {
			meta.Final = stem + meta.Extension
			if err := saveMeta(root, next.ID, *meta); err != nil {
				return err
			}
			next.TargetPath = filepath.Join(e.root, "Singles", meta.Final)
			if err := m.commitLocked(e, *next); err != nil {
				return err
			}
			// Persister/外部进程可能在预检查后新建其它扩展或替换源文件。
			// 再次检查不代替 NOREPLACE，最后一个竞态窗口仍由内核拒绝覆盖。
			if modern {
				// 晚到的 sidecar 保持“音频成功 + warning”契约，不反复换号。
				available, err = stemFilesAvailable(root, stem, audioNameExtensions)
				if err != nil {
					return err
				}
			}
			if err := ctx.Err(); err != nil {
				return err
			}
			if !sameOpenFile(root, source, file) {
				return errFile
			}
			if available {
				err = renameNoReplace(root, source, meta.Final)
				if !errors.Is(err, errCollision) {
					return err
				}
			}
		}
		// 旧无 FileNameFormat 任务继续使用原路径及碰撞失败契约。
		if !modern || ordinal >= maxNameCandidates {
			return errCollision
		}
		ordinal = max(2, ordinal+1)
		stem = numberedStem(base, ordinal)
	}
}

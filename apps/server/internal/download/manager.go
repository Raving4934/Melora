// Package download 实现受控公网 HTTP/HTTPS、目录受限且可恢复的后台下载队列。
package download

import (
	"context"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"melora/internal/model"
	"melora/internal/storage"
)

type Resolver func(context.Context, model.Track, string) (string, error)

// Persister 必须同步、原子地保存记录，并在有界时间内返回；不得重入 Manager。
// 下载地址不进入 DownloadJob 或此回调。
type Persister func(model.DownloadJob) error

var (
	// ErrStorageUnavailable 只表示下载存储初始化失败，不包含坏记录或数据库持久化错误。
	ErrStorageUnavailable = errRoot
	errDisabled           = errors.New("尚未配置授权下载目录，下载已禁用")
	errClosed             = errors.New("下载管理器已关闭")
	errPersist            = errors.New("保存下载状态失败，请检查数据目录后重试")
	errAction             = errors.New("当前下载状态不允许此操作")
	errMissing            = errors.New("下载任务不存在")
	errRootChanged        = errors.New("任务属于其他下载目录，请恢复原授权目录后继续")
)

const defaultMaxBytes int64 = 2 << 30

type limits struct {
	maxBytes          int64
	totalTime         time.Duration
	attempts          int
	backoff, progress time.Duration
}

func defaultLimits() limits {
	return limits{defaultMaxBytes, 30 * time.Minute, 3, time.Second, 500 * time.Millisecond}
}

type dependencies struct {
	probe     spaceProbe
	lookup    lookupFunc
	transport http.RoundTripper
	limits    limits
}
type entry struct {
	job     model.DownloadJob
	root    string
	running bool
	stop    string
	cancel  context.CancelFunc
	done    chan struct{}
	lastErr error
	fetch   MetadataFetcher
}

type Manager struct {
	mu                   sync.Mutex
	cond                 *sync.Cond
	jobs                 map[string]*entry
	order                []string
	subscribers          map[uint64]chan []model.DownloadJob
	nextSubscriber       uint64
	root                 string
	files                *os.Root
	concurrency, running int
	resolve              Resolver
	persist              Persister
	removeRecords        func(context.Context, []string) error
	client               *http.Client
	closeIdle            func()
	limits               limits
	options              downloadOptions
	metadataFetcher      MetadataFetcher
	probe                spaceProbe
	closing              bool
	closeOnce            sync.Once
	wg                   sync.WaitGroup
}

func New(root string, concurrency int, resolve Resolver, persist Persister, initial []model.DownloadJob) (*Manager, error) {
	return newManager(root, concurrency, resolve, persist, initial, dependencies{})
}
func normalizeConcurrency(n int) (int, error) {
	if n == 0 {
		n = 1
	}
	if n < 1 || n > 3 {
		return 0, errors.New("下载并发必须为 1 至 3")
	}
	return n, nil
}
func newManager(root string, concurrency int, resolve Resolver, persist Persister, initial []model.DownloadJob, deps dependencies) (*Manager, error) {
	concurrency, err := normalizeConcurrency(concurrency)
	if err != nil {
		return nil, err
	}
	if resolve == nil || persist == nil {
		return nil, errors.New("下载管理器需要地址解析和状态持久化回调")
	}
	files, clean, err := openStorage(root)
	if err != nil {
		return nil, err
	}
	client, closeIdle := makeClient(deps.lookup, deps.transport)
	lim := deps.limits
	if lim.maxBytes == 0 {
		lim = defaultLimits()
	}
	m := &Manager{jobs: make(map[string]*entry), subscribers: make(map[uint64]chan []model.DownloadJob), root: clean, files: files,
		concurrency: concurrency, resolve: resolve, persist: persist, client: client, closeIdle: closeIdle, limits: lim}
	m.options = downloadOptions{format: "title-artist"}
	m.probe = deps.probe
	if m.probe == nil {
		m.probe = storage.ProbeRoot
	}
	m.cond = sync.NewCond(&m.mu)
	fail := func(err error) (*Manager, error) {
		if files != nil {
			files.Close()
		}
		closeIdle()
		return nil, err
	}
	// 先校验完整输入，再执行恢复持久化，避免坏记录驱动路径操作。
	for _, original := range initial {
		job := cloneJob(original)
		if !validID(job.ID) || m.jobs[job.ID] != nil || job.BytesDone < 0 || job.BytesTotal < 0 || job.BytesDone > lim.maxBytes || job.BytesTotal > lim.maxBytes {
			return fail(errors.New("已保存的下载记录无效"))
		}
		if job.FileNameFormat != "" && !validNameFormat(job.FileNameFormat) {
			return fail(errors.New("已保存的下载命名方式无效"))
		}
		e := &entry{job: job}
		if job.TargetPath != "" {
			dir := filepath.Dir(job.TargetPath)
			base := filepath.Base(job.TargetPath)
			if !filepath.IsAbs(job.TargetPath) || filepath.Base(dir) != "Singles" || filepath.Clean(job.TargetPath) != job.TargetPath || base != jobStem(job)+filepath.Ext(base) || !validExtension(filepath.Ext(base)) {
				return fail(errors.New("已保存的下载路径无效"))
			}
			e.root = filepath.Dir(dir)
		} else {
			e.root = clean
			e.job.TargetPath = targetPath(e.root, e.job, ".audio")
		}
		if err := validateExtrasPath(e.job); err != nil {
			return fail(err)
		}
		if err := validateMetadataPath(e.job); err != nil {
			return fail(err)
		}
		e.job.Error = ""
		e.job.Speed = 0
		if job.State != "completed" && job.State != "cancelled" {
			e.job.State = "paused"
			// BytesDone 从实际文件读取，不信任上次进程退出前的进度快照。
			if files != nil && e.root == clean {
				info, statErr := files.Lstat(partName(job.ID))
				if statErr == nil && info.Mode().IsRegular() && singleLink(info) && info.Size() <= lim.maxBytes {
					e.job.BytesDone = info.Size()
				} else if errors.Is(statErr, os.ErrNotExist) {
					e.job.BytesDone = 0
				} else {
					e.job.Error = errFile.Error()
				}
			}
		}
		m.jobs[job.ID] = e
		m.order = append(m.order, job.ID)
	}
	for _, id := range m.order {
		e := m.jobs[id]
		restoreMetadata(files, clean, e)
		restoreExtras(files, clean, e)
		e.job.UpdatedAt = timestamp()
		if persist(cloneJob(e.job)) != nil {
			return fail(errPersist)
		}
		if e.job.State == "cancelled" && files != nil && e.root == clean {
			if removePartial(files, id) != nil {
				return fail(errFile)
			}
		}
	}
	// 存储层可能按 created_at DESC 返回；内部始终保持创建时间升序，
	// snapshotLocked 反向遍历后才会在重启前后都保持最新优先。
	createdAt := make(map[string]time.Time, len(m.order))
	for _, id := range m.order {
		// 按实际时间比较，兼容不同时区和小数秒精度；无效旧时间按零值处理。
		createdAt[id], _ = time.Parse(time.RFC3339Nano, m.jobs[id].job.CreatedAt)
	}
	sort.Slice(m.order, func(i, j int) bool {
		a, b := m.order[i], m.order[j]
		if comparison := createdAt[a].Compare(createdAt[b]); comparison != 0 {
			return comparison < 0
		}
		return a < b // 相同创建时间以 ID 升序稳定打破平局。
	})
	for range 3 {
		m.wg.Add(1)
		go m.worker()
	}
	return m, nil
}

func timestamp() string { return time.Now().UTC().Format(time.RFC3339Nano) }
func cloneJob(job model.DownloadJob) model.DownloadJob {
	job.Track.Qualities = append([]string(nil), job.Track.Qualities...)
	return job
}
func jobStem(job model.DownloadJob) string {
	if job.FileNameFormat != "" && job.TargetPath != "" {
		stem := strings.TrimSuffix(filepath.Base(job.TargetPath), filepath.Ext(job.TargetPath))
		if validStem(job, stem) {
			return stem
		}
	}
	return baseStem(job)
}

func shortQuality(s string) string {
	s = safeName(s)
	if len(s) > 20 {
		s = s[:20]
		s = strings.ToValidUTF8(s, "_")
	}
	return s
}
func targetPath(root string, job model.DownloadJob, extension string) string {
	if root == "" {
		return ""
	}
	return filepath.Join(root, "Singles", jobStem(job)+extension)
}

func (m *Manager) Create(track model.Track, quality string) (model.DownloadJob, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.createLocked(track, quality, m.options)
}

// CreateWithOptions 仅覆盖本任务快照，不修改 Manager 默认配置。
func (m *Manager) CreateWithOptions(track model.Track, quality string, settings model.Settings) (model.DownloadJob, error) {
	format := settings.FileNameFormat
	if format == "" {
		format = "title-artist"
	}
	if !validNameFormat(format) {
		return model.DownloadJob{}, errors.New("文件命名方式无效")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.createLocked(track, quality, downloadOptions{format, settings.WriteLyrics, settings.WriteCover, settings.EmbedTags})
}
func (m *Manager) createLocked(track model.Track, quality string, options downloadOptions) (model.DownloadJob, error) {
	if m.closing {
		return model.DownloadJob{}, errClosed
	}
	if m.files == nil {
		return model.DownloadJob{}, errDisabled
	}
	if !track.CanDownload || track.ID == "" || track.ProviderID == "" {
		return model.DownloadJob{}, errors.New("此曲目未授权下载")
	}
	if len(track.ID) > 256 || len(track.ProviderID) > 128 || len(track.Title) > 4096 || len(track.Artist) > 4096 || len(quality) > 64 {
		return model.DownloadJob{}, errors.New("下载曲目参数过长")
	}
	if quality == "" {
		quality = "standard"
	}
	found := false
	for _, q := range track.Qualities {
		if q == quality {
			found = true
			break
		}
	}
	if !found {
		return model.DownloadJob{}, errors.New("此曲目不支持请求的音质")
	}
	for _, e := range m.jobs {
		if e.job.Track.ID == track.ID && e.job.Track.ProviderID == track.ProviderID && e.job.Quality == quality && e.job.State != "cancelled" && e.job.State != "failed" {
			return cloneJob(e.job), errors.New("相同曲目和音质已有下载任务")
		}
	}
	if len(m.jobs) >= 10000 {
		return model.DownloadJob{}, errors.New("下载记录数量已达上限")
	}
	id, err := randomID()
	if err != nil {
		return model.DownloadJob{}, err
	}
	job := model.DownloadJob{FileNameFormat: options.format, WriteLyrics: options.lyrics, WriteCover: options.cover, EmbedTags: options.tags, ID: id, Track: track, Quality: quality, State: "queued", CreatedAt: timestamp(), UpdatedAt: timestamp()}
	job = cloneJob(job)
	if err := m.planName(&job); err != nil {
		return model.DownloadJob{}, err
	}
	// ack 之前必须成功保存 queued，失败时不入队也不联网。
	if m.persist(cloneJob(job)) != nil {
		return model.DownloadJob{}, errPersist
	}
	m.jobs[id] = &entry{job: job, root: m.root}
	m.order = append(m.order, id)
	m.publishLocked()
	m.cond.Broadcast()
	return cloneJob(job), nil
}
func (m *Manager) List() []model.DownloadJob {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.snapshotLocked()
}
func (m *Manager) snapshotLocked() []model.DownloadJob {
	out := make([]model.DownloadJob, 0, len(m.order))
	for i := len(m.order) - 1; i >= 0; i-- {
		out = append(out, cloneJob(m.jobs[m.order[i]].job))
	}
	return out
}
func (m *Manager) Updates() (<-chan []model.DownloadJob, func()) {
	m.mu.Lock()
	defer m.mu.Unlock()
	ch := make(chan []model.DownloadJob, 1)
	ch <- m.snapshotLocked()
	if m.closing {
		close(ch)
		return ch, func() {}
	}
	id := m.nextSubscriber
	m.nextSubscriber++
	m.subscribers[id] = ch
	var once sync.Once
	return ch, func() {
		once.Do(func() {
			m.mu.Lock()
			defer m.mu.Unlock()
			if c, ok := m.subscribers[id]; ok {
				delete(m.subscribers, id)
				close(c)
			}
		})
	}
}
func (m *Manager) publishLocked() {
	for _, ch := range m.subscribers {
		// 慢订阅者只保留最新完整快照，不能阻塞 worker；每个订阅者独立深拷贝。
		select {
		case <-ch:
		default:
		}
		ch <- m.snapshotLocked()
	}
}
func (m *Manager) commitLocked(e *entry, job model.DownloadJob) error {
	job.UpdatedAt = timestamp()
	if m.persist(cloneJob(job)) != nil {
		return errPersist
	}
	e.job = cloneJob(job)
	m.publishLocked()
	return nil
}
func (m *Manager) failureLocked(e *entry, job model.DownloadJob, err error) {
	job.State = "failed"
	job.Speed = 0
	job.Error = err.Error()
	if m.commitLocked(e, job) != nil {
		job.Error = errPersist.Error()
		job.UpdatedAt = timestamp()
		e.job = cloneJob(job)
		m.publishLocked()
		e.lastErr = errPersist
	}
}

func (m *Manager) Action(id, action string) (model.DownloadJob, error) {
	m.mu.Lock()
	if m.closing {
		m.mu.Unlock()
		return model.DownloadJob{}, errClosed
	}
	e, ok := m.jobs[id]
	if !ok {
		m.mu.Unlock()
		return model.DownloadJob{}, errMissing
	}
	if action != "pause" && action != "resume" && action != "retry" && action != "cancel" {
		m.mu.Unlock()
		return model.DownloadJob{}, errAction
	}
	// finalizing 在同一把锁内发布文件及 completed；worker 收尾期间也不能
	// 接受完成任务的取消/暂停，否则 API 会错误确认一个未执行的操作。
	if e.job.State == "completed" {
		job := cloneJob(e.job)
		m.mu.Unlock()
		return job, errAction
	}
	if e.running {
		if (action != "pause" && action != "cancel") || e.stop != "" {
			job := cloneJob(e.job)
			m.mu.Unlock()
			return job, errAction
		}
		if action == "pause" {
			e.stop = "paused"
		} else {
			e.stop = "cancelled"
		}
		e.cancel()
		done := e.done
		m.mu.Unlock()
		<-done
		m.mu.Lock()
		job, err := cloneJob(e.job), e.lastErr
		m.mu.Unlock()
		return job, err
	}
	defer m.mu.Unlock()
	job := cloneJob(e.job)
	switch action {
	case "pause":
		if job.State == "paused" {
			return job, nil
		}
		if job.State != "queued" {
			return job, errAction
		}
		job.State = "paused"
	case "resume", "retry":
		if action == "resume" && job.State != "paused" || action == "retry" && job.State != "failed" {
			return job, errAction
		}
		if m.files == nil {
			return job, errDisabled
		}
		if e.root != m.root {
			return job, errRootChanged
		}
		job.State = "queued"
		job.Error = ""
	case "cancel":
		if job.State == "completed" {
			return job, errAction
		}
		if m.files == nil || e.root != m.root {
			return job, errRootChanged
		}
		job.State = "cancelled"
		job.Error = ""
	}
	job.Speed = 0
	if err := m.commitLocked(e, job); err != nil {
		return cloneJob(e.job), err
	}
	e.lastErr = nil
	if action == "cancel" {
		if err := removePartial(m.files, job.ID); err != nil {
			e.lastErr = err
			return cloneJob(e.job), err
		}
	}
	m.cond.Broadcast()
	return cloneJob(e.job), nil
}

// SetWriteMetadata 保留旧调用接口。新任务不再生成用户 JSON；恢复的旧快照仍有效。
func (m *Manager) SetWriteMetadata(bool) {}

func (m *Manager) Configure(root string, concurrency int) error {
	n, err := normalizeConcurrency(concurrency)
	if err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closing {
		return errClosed
	}
	clean := root
	if root != "" {
		clean = filepath.Clean(root)
	}
	if clean == m.root && n == m.concurrency {
		return nil
	}
	for _, e := range m.jobs {
		if e.running || e.job.State == "queued" {
			return errors.New("存在活动下载，请先暂停任务再更改配置")
		}
	}
	if clean != m.root {
		files, path, err := openStorage(root)
		if err != nil {
			return err
		}
		if m.files != nil {
			m.files.Close()
		}
		m.files = files
		m.root = path
	}
	m.concurrency = n
	m.cond.Broadcast()
	return nil
}

func (m *Manager) Close() {
	m.closeOnce.Do(func() {
		m.mu.Lock()
		m.closing = true
		for _, e := range m.jobs {
			if e.running {
				if e.stop == "" {
					e.stop = "paused"
				}
				e.cancel()
			} else if e.job.State == "queued" {
				job := cloneJob(e.job)
				job.State = "paused"
				job.Speed = 0
				if m.commitLocked(e, job) != nil {
					m.failureLocked(e, job, errPersist)
				}
			}
		}
		m.cond.Broadcast()
		m.mu.Unlock()
		m.wg.Wait()
		m.mu.Lock()
		defer m.mu.Unlock()
		m.closeIdle()
		if m.files != nil {
			m.files.Close()
		}
		for id, ch := range m.subscribers {
			close(ch)
			delete(m.subscribers, id)
		}
	})
}

func (m *Manager) worker() {
	defer m.wg.Done()
	for {
		m.mu.Lock()
		var e *entry
		for !m.closing {
			if m.running < m.concurrency {
				for _, id := range m.order {
					candidate := m.jobs[id]
					if candidate.job.State == "queued" && !candidate.running {
						e = candidate
						break
					}
				}
			}
			if e != nil {
				break
			}
			m.cond.Wait()
		}
		if m.closing {
			m.mu.Unlock()
			return
		}
		job := cloneJob(e.job)
		job.State = "resolving"
		job.Error = ""
		job.Speed = 0
		if m.commitLocked(e, job) != nil {
			m.failureLocked(e, job, errPersist)
			m.mu.Unlock()
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), m.limits.totalTime)
		e.fetch = m.metadataFetcher
		e.running = true
		e.cancel = cancel
		e.done = make(chan struct{})
		e.stop = ""
		e.lastErr = nil
		m.running++
		files := m.files
		m.mu.Unlock()
		result := m.run(ctx, e, files)
		cancel()
		m.mu.Lock()
		if e.stop != "" && e.job.State != "completed" {
			job := cloneJob(e.job)
			job.State = e.stop
			job.Speed = 0
			job.Error = ""
			if e.stop == "paused" && (errors.Is(result, errFile) || errors.Is(result, errSpace) || errors.Is(result, errPersist)) {
				job.State = "failed"
				job.Error = safeError(result).Error()
				e.lastErr = safeError(result)
			}
			// run 已关闭文件并保存实际 offset 后，才能确认暂停/取消。
			if m.commitLocked(e, job) != nil {
				e.lastErr = errPersist
				m.failureLocked(e, job, errPersist)
			} else if e.stop == "cancelled" {
				if err := removePartial(files, job.ID); err != nil {
					e.lastErr = err
					job.Error = err.Error()
					if m.commitLocked(e, job) != nil {
						e.lastErr = errPersist
					}
				}
			}
		} else if result != nil && e.job.State != "completed" {
			m.failureLocked(e, cloneJob(e.job), safeError(result))
		}
		e.running = false
		e.cancel = nil
		e.stop = ""
		m.running--
		close(e.done)
		m.cond.Broadcast()
		m.mu.Unlock()
	}
}

func (m *Manager) update(e *entry, fn func(*model.DownloadJob)) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	job := cloneJob(e.job)
	fn(&job)
	return m.commitLocked(e, job)
}

import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import {
  AlertTriangle,
  Check,
  ChevronDown,
  Copy,
  Download,
  FolderOpen,
  ListX,
  LoaderCircle,
  Pause,
  Play,
  RefreshCw,
  X,
} from 'lucide-react'
import { useAPI, send, idPath, invalidate, queryClient } from '../lib/api'
import { errorMessage, formatBytes } from '../lib/format'
import { downloadQualityDisplay } from '../lib/download-quality'
import type { DownloadJob, Settings } from '../lib/types'
import { Cover, EmptyState, IconButton, Modal, PageHeader, QueryState } from '../components/UI'
import { notify } from '../stores/ui'
import { apiURL } from '../lib/base'
import './Downloads.css'
const stateNames: Record<string, string> = {
  queued: '排队中',
  resolving: '获取下载地址',
  downloading: '正在下载',
  paused: '已暂停',
  retry_wait: '等待重试',
  waiting_for_url_refresh: '等待刷新地址',
  verifying: '正在校验',
  writing_metadata: '正在写入附加信息',
  finalizing: '正在归档',
  completed: '已完成',
  failed: '下载失败',
  cancelled: '已取消',
}
const active = new Set([
  'queued',
  'resolving',
  'downloading',
  'waiting_for_url_refresh',
  'retry_wait',
  'verifying',
  'writing_metadata',
  'finalizing',
])
type ClearRecordsResult = { cleared: number; remaining: number }
const clearSummary = (result: ClearRecordsResult) =>
  `已清除 ${result.cleared} 条下载记录，剩余 ${result.remaining} 条任务。文件未删除。`
const downloadTabs = new Set(['active', 'completed', 'failed'])

export function DownloadsPage() {
  const [params, setParams] = useSearchParams()
  const requestedTab = params.get('tab') || 'active'
  const tab = downloadTabs.has(requestedTab) ? requestedTab : 'active'
  useEffect(() => {
    if (requestedTab !== tab) setParams({ tab }, { replace: true })
  }, [requestedTab, setParams, tab])
  const [live, setLive] = useState(false)
  const [clearOpen, setClearOpen] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [clearError, setClearError] = useState('')
  const [clearResult, setClearResult] = useState<ClearRecordsResult | null>(null)
  const clearInFlight = useRef(false)
  const jobs = useAPI<DownloadJob[]>('/downloads')
  const settings = useAPI<Settings>('/settings')
  useEffect(() => {
    const source = new EventSource(apiURL('/downloads/events'))
    const receive = (event: MessageEvent<string>) => {
      try {
        const data: unknown = JSON.parse(event.data)
        if (Array.isArray(data)) {
          // 先同步取消旧读取，再应用权威快照；即使 HTTP 不响应 abort 也不能回流覆盖 SSE。
          void queryClient.cancelQueries({ queryKey: ['/downloads'], exact: true })
          queryClient.setQueryData(['/downloads'], data)
          setLive(true)
        }
      } catch {
        /* 无效事件不覆盖已有任务。 */
      }
    }
    source.addEventListener('downloads', receive)
    source.onopen = () => setLive(true)
    source.onerror = () => setLive(false)
    const poll = setInterval(() => {
      if (source.readyState !== EventSource.OPEN) void invalidate('/downloads')
    }, 8000)
    return () => {
      source.close()
      clearInterval(poll)
    }
  }, [])
  const counts = {
    active: jobs.data?.filter((job) => active.has(job.state) || job.state === 'paused').length || 0,
    completed: jobs.data?.filter((job) => job.state === 'completed').length || 0,
    failed: jobs.data?.filter((job) => ['failed', 'cancelled'].includes(job.state)).length || 0,
  }
  // 不依赖当前标签或行选择；只展示服务器允许清除的三种终态总数。
  const clearableCount = counts.completed + counts.failed
  const clearDisabled = clearing || (!clearResult && (jobs.isPending || !jobs.data || clearableCount === 0))
  const closeClear = () => {
    if (clearInFlight.current) return
    setClearOpen(false)
    if (!clearResult) setClearError('')
  }
  const clearRecords = async () => {
    // ref 同步防重入，覆盖同一 render 内的连续提交；不乐观移除缓存或重置标签。
    if (clearInFlight.current || clearDisabled) return
    clearInFlight.current = true
    setClearing(true)
    setClearError('')
    let result = clearResult
    try {
      // 一旦 POST 已成功，失败/关闭/重开后的按钮只重试读取，不能再清除后来完成的任务。
      if (!result) {
        const response = await send<ClearRecordsResult>('/downloads/clear-records', 'POST', {})
        if (
          !Number.isSafeInteger(response?.cleared) ||
          response.cleared < 0 ||
          !Number.isSafeInteger(response?.remaining) ||
          response.remaining < 0
        ) {
          throw new Error('服务返回的清除数量无效，请刷新后确认记录状态。')
        }
        result = response
      }
      // 全局 invalidate 默认吞掉后台读取错误；此处必须区分清除结果与列表同步结果。
      await queryClient.invalidateQueries({ queryKey: ['/downloads'], exact: true }, { throwOnError: true })
      setClearOpen(false)
      setClearResult(null)
      notify(clearSummary(result), 'success')
    } catch (error) {
      if (result) {
        setClearResult(result)
        setClearError(
          `${clearSummary(result)} 列表刷新失败：${errorMessage(error)} 请重试刷新，无需再次清除。`,
        )
      } else {
        setClearError(errorMessage(error))
      }
    } finally {
      clearInFlight.current = false
      setClearing(false)
    }
  }
  const visible =
    jobs.data?.filter((job) =>
      tab === 'active'
        ? active.has(job.state) || job.state === 'paused'
        : tab === 'completed'
          ? job.state === 'completed'
          : ['failed', 'cancelled'].includes(job.state),
    ) || []
  return (
    <div className="downloads-page">
      <PageHeader title="下载任务">
        <span className={`live-status ${live ? 'connected' : ''}`}>
          <i />
          {live ? '实时更新' : '正在连接 · 自动重试'}
        </span>
      </PageHeader>
      <div className="tab-bar downloads-tabs" role="tablist" aria-label="下载任务分类">
        {[
          ['active', '进行中'],
          ['completed', '已完成'],
          ['failed', '失败与取消'],
        ].map(([value, label]) => (
          <button
            key={value}
            id={`downloads-tab-${value}`}
            type="button"
            role="tab"
            aria-selected={tab === value}
            aria-controls="downloads-panel"
            className={tab === value ? 'active' : ''}
            onClick={() => setParams({ tab: value! })}
          >
            {label}
            <span className="tab-count">{counts[value as keyof typeof counts]}</span>
          </button>
        ))}
      </div>
      <div className="download-list-heading download-records-toolbar">
        <span>{tab === 'active' ? `同时下载 ${settings.data?.concurrency || 1} 个任务` : '下载记录'}</span>
        <button
          type="button"
          className="button secondary download-clear-trigger"
          aria-label={clearResult ? '刷新记录列表（清除已成功）' : `清除记录，共 ${clearableCount} 条`}
          disabled={clearDisabled}
          onClick={() => {
            if (!clearResult) setClearError('')
            setClearOpen(true)
          }}
        >
          {clearResult ? <RefreshCw size={16} aria-hidden="true" /> : <ListX size={16} aria-hidden="true" />}
          <span>{clearResult ? '刷新记录' : '清除记录'}</span>
          <span
            className="download-clear-count"
            title={clearResult ? '上次列表的终态数量，等待刷新确认' : `${clearableCount} 条可清除记录`}
            aria-hidden="true"
          >
            {clearableCount}
          </span>
        </button>
      </div>
      <div
        id="downloads-panel"
        role="tabpanel"
        aria-labelledby={`downloads-tab-${tab}`}
        aria-busy={jobs.isFetching}
      >
        <QueryState pending={jobs.isPending} error={jobs.error} retry={jobs.refetch}>
          {!visible.length ? (
            <EmptyState
              title={
                tab === 'completed'
                  ? '暂无已完成任务'
                  : tab === 'failed'
                    ? '暂无失败任务'
                    : '暂时没有进行中的任务'
              }
              description={
                tab === 'active'
                  ? '在歌曲旁点击下载，选择音质，保存到已授权的飞牛目录。'
                  : tab === 'completed'
                    ? '成功保存的音乐会显示在这里。'
                    : '失败或取消的任务会保留在这里，方便查看原因。'
              }
              icon={<Download size={28} />}
              action={
                tab === 'active' && (
                  <Link className="button secondary" to="/">
                    浏览音乐
                  </Link>
                )
              }
            />
          ) : (
            <div className="download-list">
              {visible.map((job) => (
                <DownloadRow key={job.id} job={job} />
              ))}
            </div>
          )}
        </QueryState>
      </div>
      {clearOpen && (
        <Modal title="清除下载记录" className="download-clear-modal" onClose={closeClear}>
          <div aria-busy={clearing}>
            <p className="modal-description">
              {clearResult ? '上次列表共有' : '当前共有'}{' '}
              <strong className="download-clear-total">{clearableCount}</strong> 条可清除记录。
            </p>
            <ul className="download-clear-boundaries">
              <li>清除全部已完成、失败和已取消记录，与当前标签页无关。</li>
              <li>进行中和已暂停任务会保留，不会停止或取消下载。</li>
              <li>不会删除音频、歌词、封面附件或任何文件。</li>
            </ul>
            <div
              className={`download-clear-feedback ${clearError ? 'has-error' : ''}`}
              role={clearError ? 'alert' : 'status'}
              tabIndex={clearError ? 0 : undefined}
            >
              {clearError ||
                (clearing
                  ? clearResult
                    ? '正在刷新列表，不会再次清除记录…'
                    : '正在清除记录，请稍候…'
                  : '实际清除数量以服务器处理结果为准。')}
            </div>
            <div className="modal-actions download-clear-actions">
              <button type="button" className="button secondary" disabled={clearing} onClick={closeClear}>
                {clearResult ? '稍后刷新' : '保留记录'}
              </button>
              <button
                type="button"
                className="button danger download-clear-confirm"
                disabled={clearDisabled}
                onClick={() => void clearRecords()}
              >
                {clearing ? (
                  <LoaderCircle size={16} className="spin" aria-hidden="true" />
                ) : (
                  <ListX size={16} aria-hidden="true" />
                )}
                <span>
                  {clearResult ? (clearing ? '刷新中…' : '重试刷新') : clearing ? '清除中…' : '确认清除'}
                </span>
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
function DownloadRow({ job }: { job: DownloadJob }) {
  const [pending, setPending] = useState(false)
  const [cancel, setCancel] = useState(false)
  const [pathDialog, setPathDialog] = useState(false)
  const quality = downloadQualityDisplay(job)
  const canPause = ['queued', 'resolving', 'downloading', 'waiting_for_url_refresh', 'retry_wait'].includes(
    job.state,
  )
  const progress = job.bytesTotal > 0 ? Math.min(100, (job.bytesDone / job.bytesTotal) * 100) : 0
  const action = async (name: string) => {
    setPending(true)
    try {
      await send(`/downloads/${idPath(job.id)}/${name}`, 'POST')
      await invalidate('/downloads')
      setCancel(false)
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      setPending(false)
    }
  }
  return (
    <article className="download-row">
      <Cover src={job.track.coverUrl} />
      <div className="download-detail">
        <div className="download-title">
          <strong title={job.track.title}>{job.track.title}</strong>
          {/* 文件格式始终占同一槽位，识别结果到达时不挤动标题或增加行高。 */}
          <span className="download-quality" title={quality.description}>
            <span className="download-requested-quality">{quality.requestedLabel}</span>
            <span className="download-file-format">{quality.fileLabel}</span>
          </span>
        </div>
        <p>
          {job.track.artist} · {job.track.album}
        </p>
        <code title={job.targetPath}>{job.targetPath || '等待分配保存路径'}</code>
        {active.has(job.state) || job.state === 'paused' ? (
          <div className="download-progress">
            <div
              role="progressbar"
              aria-label={`${job.track.title} 下载进度`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(progress)}
            >
              <span style={{ transform: `scaleX(${progress / 100})` }} />
            </div>
            <span>{job.bytesTotal ? `${progress.toFixed(1)}%` : '大小未知'}</span>
          </div>
        ) : null}
        {job.error && <p className="inline-error">{job.error}</p>}
        {job.warning && job.state !== 'completed' && (
          <p className="download-warning">
            <AlertTriangle size={13} />
            {job.warning}
          </p>
        )}
        <div className="download-meta">
          <span
            className={`download-state ${job.state === 'completed' ? 'success-text' : job.state === 'failed' ? 'danger-text' : ''}`}
          >
            {job.state === 'completed' && <Check size={12} />}
            {stateNames[job.state] || job.state}
          </span>
          <span
            className="download-bytes"
            title={`${formatBytes(job.bytesDone)}${job.bytesTotal > 0 ? ` / ${formatBytes(job.bytesTotal)}` : ''}`}
          >
            {formatBytes(job.bytesDone)}
            {job.bytesTotal > 0 && ` / ${formatBytes(job.bytesTotal)}`}
          </span>
          <span className="download-speed" aria-hidden={job.state !== 'downloading'}>
            {formatBytes(job.speed)}/s
          </span>
          {job.lyricsPath && <span className="success-text">歌词</span>}
          {job.coverPath && <span className="success-text">封面</span>}
          {job.tagsWritten && <span className="success-text">已写入标签</span>}
        </div>
        {job.warning && job.state === 'completed' && <DownloadNotes warning={job.warning} />}
      </div>
      <div className="download-actions">
        {canPause && (
          <IconButton
            label={`暂停 ${job.track.title}`}
            disabled={pending}
            onClick={() => {
              void action('pause')
            }}
          >
            <Pause size={17} />
          </IconButton>
        )}
        {active.has(job.state) && !canPause && (
          <span className="download-action-placeholder" aria-hidden="true" />
        )}
        {job.state === 'paused' && (
          <IconButton
            label={`继续 ${job.track.title}`}
            disabled={pending}
            onClick={() => {
              void action('resume')
            }}
          >
            <Play size={17} />
          </IconButton>
        )}
        {job.state === 'failed' && (
          <IconButton
            label={`重试 ${job.track.title}`}
            disabled={pending}
            onClick={() => {
              void action('retry')
            }}
          >
            <RefreshCw size={17} />
          </IconButton>
        )}
        {(active.has(job.state) || job.state === 'paused') && (
          <IconButton label={`取消 ${job.track.title}`} disabled={pending} onClick={() => setCancel(true)}>
            <X size={17} />
          </IconButton>
        )}
        {job.state === 'completed' && (
          <IconButton label={`查看 ${job.track.title} 保存位置`} onClick={() => setPathDialog(true)}>
            <FolderOpen size={17} />
          </IconButton>
        )}
      </div>
      {cancel && (
        <Modal title="取消这首歌的下载？" onClose={() => !pending && setCancel(false)}>
          <p className="modal-description">将停止下载并移除此任务的临时文件，不会删除已经保存的其他音乐。</p>
          <div className="modal-actions">
            <button className="button secondary" disabled={pending} onClick={() => setCancel(false)}>
              继续下载
            </button>
            <button
              className="button danger"
              disabled={pending}
              onClick={() => {
                void action('cancel')
              }}
            >
              确认取消
            </button>
          </div>
        </Modal>
      )}
      {pathDialog && (
        <Modal title="音乐保存位置" onClose={() => setPathDialog(false)}>
          <p className="modal-description">
            在飞牛文件管理器中打开下方路径。浏览器不能直接打开 NAS 的本地目录。
          </p>
          <code className="path-display">{job.targetPath}</code>
          {(
            [
              ['歌词文件', job.lyricsPath],
              ['封面文件', job.coverPath],
            ] as const
          ).map(([label, path]) =>
            path ? (
              <div key={label}>
                <p className="modal-description">{label}</p>
                <code className="path-display">{path}</code>
              </div>
            ) : null,
          )}
          {job.tagsWritten && <p className="modal-description success-text">音频内嵌标签已写入</p>}
          {job.warning && <DownloadNotes warning={job.warning} />}
          <button
            className="button secondary"
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(job.targetPath)
                notify('路径已复制', 'success')
              } catch {
                notify('无法自动复制，请选中上方路径手动复制。')
              }
            }}
          >
            <Copy size={15} />
            复制路径
          </button>
        </Modal>
      )}
    </article>
  )
}

// 完成文件的附加诊断默认收起；原生 details 保留键盘操作及同任务刷新时的展开状态。
function DownloadNotes({ warning }: { warning: string }) {
  return (
    <details className="download-notes">
      <summary>
        <span>下载详情</span>
        <ChevronDown size={14} aria-hidden="true" />
      </summary>
      <p className="download-notes-content">{warning}</p>
    </details>
  )
}

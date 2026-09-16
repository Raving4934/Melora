import { useState, type FormEvent } from 'react'
import { Check, FileCode2, LoaderCircle, Plus, RefreshCw, Download, Trash2 } from 'lucide-react'
import { api, idPath, invalidate, send, useAPI } from '../lib/api'
import { errorMessage } from '../lib/format'
import { notify } from '../stores/ui'
import { IconButton, Modal, SectionHeader } from './UI'

export interface LXSource {
  id: string
  name: string
  filename: string
  version: string
  author: string
  description: string
  status: 'checking' | 'ready' | 'error'
  error?: string
  allowHTTPHosts: string[]
  platforms: Record<string, { name: string; type: string; actions: string[]; qualitys: string[] | null }>
}
export interface SourcesState {
  items: LXSource[]
  activeSourceId: string
  available?: boolean
  catalogs?: string[]
  demoMode?: boolean
}
const platformNames: Record<string, string> = {
  wy: '网抑云',
  tx: '扣扣',
  kw: '酷沃',
  kg: '酷购',
  mg: '米咕',
  local: '自定义',
}
async function refreshSources() {
  await Promise.all([invalidate('/sources'), invalidate('/providers')])
  void invalidate('/')
}
export function SourcesPanel() {
  const query = useAPI<SourcesState>('/sources')
  const [importing, setImporting] = useState(false)
  const [deleting, setDeleting] = useState<LXSource | null>(null)
  const [busy, setBusy] = useState('')
  const [failures, setFailures] = useState<
    Record<string, { message: string; action: 'select' | 'check' | 'delete' | 'export' }>
  >({})
  const perform = async (source: LXSource, action: 'select' | 'check' | 'delete' | 'export') => {
    if (busy) return
    setBusy(source.id)
    setFailures((previous) => {
      const next = { ...previous }
      delete next[source.id]
      return next
    })
    try {
      if (action === 'select') await send('/sources/active', 'PUT', { id: source.id })
      if (action === 'check') {
        const result = await send<LXSource>(`/sources/${idPath(source.id)}/check`, 'POST')
        if (result.status !== 'ready') {
          await refreshSources()
          throw new Error(result.error || '初始化未通过')
        }
      }
      if (action === 'export') {
        const result = await api<{ filename: string; content: string }>(
          `/sources/${idPath(source.id)}/export`,
        )
        if (typeof result.content !== 'string' || !result.content || typeof result.filename !== 'string')
          throw new Error('导出内容无效，请重新尝试')
        const blob = new Blob([result.content], { type: 'text/javascript;charset=utf-8' })
        if (blob.size > 512 * 1024) throw new Error('导出的音源超出大小限制')
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = result.filename.replace(/[\/\\\u0000-\u001f\u007f]/g, '_') || 'melora-source.js'
        document.body.append(link)
        try {
          link.click()
        } finally {
          link.remove()
          setTimeout(() => URL.revokeObjectURL(url), 1000)
        }
        notify('已导出音源，请妥善保管文件中的个人配置', 'success')
        return
      }
      if (action === 'delete') {
        const result = await send<{ warning?: string }>(`/sources/${idPath(source.id)}`, 'DELETE')
        if (result.warning) notify(result.warning, 'info')
        setDeleting(null)
      }
      await refreshSources()
    } catch (error) {
      setFailures((previous) => ({ ...previous, [source.id]: { message: errorMessage(error), action } }))
    } finally {
      setBusy('')
    }
  }
  const queryError = query.error || query.backgroundError
  return (
    <section className="settings-section source-manager settings-lx" aria-label="LX音源管理">
      <SectionHeader title="LX 音源配置" subtitle="管理在线音频检索源与第三方插件">
        <button
          type="button"
          className="button primary settings-source-import"
          aria-label="导入 LX 音源"
          disabled={query.data?.available === false}
          onClick={() => setImporting(true)}
        >
          <Plus size={15} />
          导入音源
        </button>
      </SectionHeader>
      <div className="lx-sources-table" role="table" aria-label="已导入 LX 音源">
        {queryError || query.data?.available === false || query.isPending || !query.data?.items.length ? (
          <div role="row">
            <div className="lx-table-state" role="cell" aria-colspan={4}>
              <span
                role="status"
                className={query.isPending ? 'loading-status-copy' : undefined}
                title={queryError ? errorMessage(queryError) : undefined}
              >
                {queryError
                  ? errorMessage(queryError)
                  : query.data?.available === false
                    ? 'LX 解析服务不可用'
                    : query.isPending
                      ? '正在读取音源…'
                      : '尚未导入音源'}
              </span>
              {query.isPending && (
                <span className="loading-rail" aria-hidden="true">
                  <span />
                </span>
              )}
              {queryError && (
                <button
                  type="button"
                  className="text-button"
                  disabled={query.isFetching}
                  onClick={() => void query.refetch()}
                >
                  重试
                </button>
              )}
            </div>
          </div>
        ) : (
          <div className="lx-table-header" role="row">
            <span role="columnheader">音源名称</span>
            <span role="columnheader">适配平台</span>
            <span role="columnheader">运行状态</span>
            <span role="columnheader">操作</span>
          </div>
        )}
        {query.data?.items.map((source) => (
          <div className="lx-source-row" role="row" key={source.id}>
            <div className="lx-source-name" role="cell">
              <strong title={source.name}>{source.name}</strong>
              <small title={source.author || source.filename}>
                {[source.version, source.author || source.filename].filter(Boolean).join(' · ')}
              </small>
            </div>
            <div
              className="lx-source-platforms"
              role="cell"
              title={Object.keys(source.platforms || {})
                .map((platform) => platformNames[platform] || platform)
                .join(' / ')}
            >
              {Object.keys(source.platforms || {}).length ? (
                Object.keys(source.platforms || {}).map((platform) => (
                  <span className="lx-platform-chip" key={platform}>
                    {platformNames[platform] || platform}
                  </span>
                ))
              ) : (
                <span className="lx-platform-empty">未声明平台</span>
              )}
            </div>
            <div className="lx-source-state" role="cell">
              <span
                className={`lx-status-pill ${
                  failures[source.id] || source.status === 'error' ? 'is-error' : `is-${source.status}`
                }`}
                title={failures[source.id]?.message || source.error}
                role="status"
              >
                {busy === source.id
                  ? '处理中…'
                  : failures[source.id]?.message ||
                    (source.status === 'ready'
                      ? '已初始化（正常）'
                      : source.status === 'checking'
                        ? '初始化中'
                        : source.error || '初始化失败')}
              </span>
            </div>
            <div className="lx-source-actions" role="cell">
              {failures[source.id] ? (
                <button
                  type="button"
                  className="button secondary small source-select"
                  disabled={!!busy}
                  aria-label={`重试音源 ${source.name}`}
                  onClick={() => void perform(source, failures[source.id].action)}
                >
                  重试
                </button>
              ) : (
                <button
                  type="button"
                  className={`button secondary small source-select ${
                    query.data?.activeSourceId === source.id ? 'is-active' : ''
                  }`}
                  disabled={!!busy || source.status !== 'ready' || query.data?.activeSourceId === source.id}
                  onClick={() => void perform(source, 'select')}
                >
                  {query.data?.activeSourceId === source.id ? (
                    <>
                      <Check size={13} />
                      使用中
                    </>
                  ) : (
                    '设为当前'
                  )}
                </button>
              )}
              <IconButton
                label={`检查音源 ${source.name}`}
                disabled={!!busy}
                onClick={() => void perform(source, 'check')}
              >
                <RefreshCw size={15} className={busy === source.id ? 'spin' : ''} />
              </IconButton>
              <IconButton
                label={`导出音源 ${source.name}`}
                disabled={!!busy}
                onClick={() => void perform(source, 'export')}
              >
                <Download size={15} />
              </IconButton>
              <IconButton
                label={`删除音源 ${source.name}`}
                disabled={!!busy}
                onClick={() => setDeleting(source)}
              >
                <Trash2 size={15} />
              </IconButton>
            </div>
          </div>
        ))}
      </div>
      {importing && <ImportSource onClose={() => setImporting(false)} />}
      {deleting && (
        <Modal
          title="删除 LX 音源？"
          className="settings-source-modal"
          onClose={() => !busy && setDeleting(null)}
        >
          <p className="modal-description">删除「{deleting.name}」？收藏与下载文件不受影响。</p>
          <p className="form-feedback" role="status">
            {failures[deleting.id]?.message || '\u00a0'}
          </p>
          <div className="modal-actions">
            <button
              type="button"
              className="button secondary"
              disabled={!!busy}
              onClick={() => setDeleting(null)}
            >
              取消
            </button>
            <button
              type="button"
              className="button danger fixed-action"
              disabled={!!busy}
              onClick={() => void perform(deleting, 'delete')}
            >
              {busy ? <LoaderCircle size={15} className="spin" /> : <Trash2 size={15} />}确认删除
            </button>
          </div>
        </Modal>
      )}
    </section>
  )
}

function ImportSource({ onClose }: { onClose: () => void }) {
  const [file, setFile] = useState<File | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!file || pending) return
    if (!file.name.toLowerCase().endsWith('.js') || file.size > 512 * 1024) {
      setError('请选择不超过 512 KiB 的 .js 文件')
      return
    }
    setPending(true)
    setError('')
    try {
      const form = new FormData()
      form.append('file', file)
      const source = await api<LXSource>('/sources/import', {
        method: 'POST',
        body: form,
        signal: AbortSignal.timeout(20_000),
      })
      await refreshSources()
      notify(
        source.status === 'ready'
          ? `已导入 ${source.name}`
          : `文件已导入，但初始化未通过：${source.error || '请重新检查'}`,
        source.status === 'ready' ? 'success' : 'info',
      )
      onClose()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setPending(false)
    }
  }
  return (
    <Modal className="settings-source-modal" title="导入 LX 音源" onClose={() => !pending && onClose()}>
      <form
        onSubmit={(event) => {
          void submit(event)
        }}
      >
        <p className="modal-description">仅导入可信的 LX .js 文件。</p>
        <label className="source-file-input">
          <div className="source-file-icon-plate">
            <FileCode2 size={24} />
          </div>
          <strong>{file?.name || '选择 .js 音源文件'}</strong>
          <span>最大 512 KiB</span>
          <input
            aria-label="LX音源文件"
            type="file"
            title=""
            accept=".js,application/javascript,text/javascript"
            disabled={pending}
            onChange={(event) => {
              setFile(event.target.files?.[0] || null)
              setError('')
            }}
          />
        </label>
        <p className="form-feedback" role={error ? 'alert' : undefined}>
          {error || '\u00a0'}
        </p>
        <div className="modal-actions">
          <button type="button" className="button secondary" disabled={pending} onClick={onClose}>
            取消
          </button>
          <button className="button primary fixed-action" type="submit" disabled={!file || pending}>
            {pending ? <LoaderCircle size={15} className="spin" /> : <Plus size={15} />}导入并检查
          </button>
        </div>
      </form>
    </Modal>
  )
}

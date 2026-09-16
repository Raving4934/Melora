import { useEffect, useState, useSyncExternalStore } from 'react'
import { APP_VERSION } from '../lib/version'
import { useAPI, send, queryClient, invalidate, useCloudDeploy } from '../lib/api'
import type { Settings } from '../lib/types'
import { errorMessage, qualityName } from '../lib/format'
import { PageHeader, QueryState, SectionHeader } from '../components/UI'
import { DownloadDirectory } from '../components/DownloadDirectory'
import { SourcesPanel, type SourcesState } from '../components/SourcesPanel'
import './Settings.css'

const fieldLabels = {
  downloadRoot: '下载保存目录',
  fileNameFormat: '文件命名格式',
  concurrency: '同时下载任务数',
  writeLyrics: '歌词文件',
  writeCover: '封面文件',
  embedTags: '内嵌元数据与封面',
  defaultQuality: '在线播放音质',
  autoSwitchSource: '自动容灾切换可用音源',
}
type SettingField = keyof typeof fieldLabels
type Feedback = { state: 'dirty' | 'saving' | 'saved' | 'error'; message?: string }
type Snapshot = { values: Settings; feedback: Partial<Record<SettingField, Feedback>> }
const fields: SettingField[] = [
  'downloadRoot',
  'fileNameFormat',
  'concurrency',
  'writeLyrics',
  'writeCover',
  'embedTags',
  'defaultQuality',
  'autoSwitchSource',
]
const normalize = (value: Settings): Settings => ({
  ...value,
  fileNameFormat: value.fileNameFormat ?? 'title-artist',
  embedTags: value.embedTags ?? true,
  autoSwitchSource: value.autoSwitchSource ?? true,
})

// 每个缓存实例一条串行队列，切页不丢已确认的写入；仅当前字段的同版本响应可更新草稿。
export function createSettingsAutosave(
  initial: Settings,
  request: (patch: Partial<Settings>) => Promise<Settings>,
  saved: (settings: Settings) => void = () => {},
) {
  let confirmed = normalize(initial)
  let snapshot: Snapshot = { values: { ...confirmed }, feedback: {} }
  const listeners = new Set<() => void>()
  const versions = new Map<SettingField, number>()
  const queue = new Map<SettingField, { value: Settings[SettingField]; version: number }>()
  let active: { field: SettingField; version: number } | undefined
  let running = false
  const uncertain = new Set<SettingField>()
  const publish = (values = snapshot.values, feedback = snapshot.feedback) => {
    snapshot = { values, feedback }
    listeners.forEach((listener) => listener())
  }
  const status = (field: SettingField, feedback: Feedback) =>
    publish(snapshot.values, { ...snapshot.feedback, [field]: feedback })
  const drain = async () => {
    if (running) return
    running = true
    try {
      while (queue.size) {
        const [field, entry] = queue.entries().next().value!
        queue.delete(field)
        if (versions.get(field) !== entry.version) continue
        if (confirmed[field] === entry.value && !uncertain.has(field)) {
          status(field, { state: 'saved' })
          continue
        }
        active = { field, version: entry.version }
        const patch: Partial<Settings> = { [field]: entry.value }
        if (confirmed.writeMetadata) patch.writeMetadata = false
        if (confirmed.showDirect) patch.showDirect = false
        try {
          const result = await request(patch)
          if (
            !result ||
            typeof result[field] !== typeof entry.value ||
            (field !== 'downloadRoot' && result[field] !== entry.value) ||
            (field === 'downloadRoot' && entry.value !== '' && result.downloadRoot === '')
          ) {
            throw new Error('服务未确认这项变更，请重试')
          }
          confirmed = normalize(result)
          uncertain.delete(field)
          if (versions.get(field) === entry.version) {
            publish(
              { ...snapshot.values, [field]: confirmed[field] },
              { ...snapshot.feedback, [field]: { state: 'saved' } },
            )
          }
          saved(result)
        } catch (error) {
          uncertain.add(field)
          if (versions.get(field) === entry.version)
            status(field, { state: 'error', message: errorMessage(error) })
        } finally {
          active = undefined
        }
      }
    } finally {
      running = false
    }
  }
  const commit = (field: SettingField) => {
    const version = versions.get(field) ?? 0
    versions.set(field, version)
    if ((active?.field === field && active.version === version) || queue.get(field)?.version === version)
      return
    if (
      snapshot.values[field] === confirmed[field] &&
      active?.field !== field &&
      !uncertain.has(field) &&
      snapshot.feedback[field]?.state !== 'error'
    ) {
      queue.delete(field)
      if (snapshot.feedback[field]) status(field, { state: 'saved' })
      return
    }
    queue.set(field, { value: snapshot.values[field], version })
    status(field, { state: 'saving' })
    void drain()
  }
  return {
    getSnapshot: () => snapshot,
    subscribe: (listener: () => void) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    edit<K extends SettingField>(field: K, value: Settings[K], immediate = true) {
      if (snapshot.values[field] !== value) {
        versions.set(field, (versions.get(field) ?? 0) + 1)
        queue.delete(field)
        publish({ ...snapshot.values, [field]: value }, { ...snapshot.feedback, [field]: { state: 'dirty' } })
      }
      if (immediate) commit(field)
    },
    commit,
    accept(result: Settings) {
      if (running) return
      const incoming = normalize(result)
      confirmed = incoming
      const values = { ...snapshot.values }
      let changed = false
      for (const field of fields) {
        const state = snapshot.feedback[field]?.state
        if (state && state !== 'saved') continue
        if (values[field] !== incoming[field]) {
          Object.assign(values, { [field]: incoming[field] })
          changed = true
        }
      }
      if (changed) publish(values)
    },
  }
}

type AutoSave = ReturnType<typeof createSettingsAutosave>
const editors = new WeakMap<object, AutoSave>()
function getEditor(initial: Settings) {
  const cache = queryClient.getQueryCache().find({ queryKey: ['/settings'], exact: true })
  const existing = cache && editors.get(cache)
  if (existing) return existing
  const editor = createSettingsAutosave(
    initial,
    async (patch) => {
      // 不让先前启动的GET在PATCH成功后用旧快照覆盖缓存。
      await queryClient.cancelQueries({ queryKey: ['/settings'], exact: true })
      const result = await send<Settings>('/settings', 'PATCH', patch)
      // 保存期间也可能由切源等操作触发GET；完成时再次取消旧读，避免迟到回滚。
      await queryClient.cancelQueries({ queryKey: ['/settings'], exact: true })
      return result
    },
    (result) => {
      const prior = queryClient.getQueryData<Settings>(['/settings'])
      queryClient.setQueryData(['/settings'], result)
      if (prior?.autoSwitchSource !== result.autoSwitchSource) {
        for (const prefix of [
          '/tracks/',
          '/charts',
          '/playlists',
          '/search',
          '/recommendations',
          '/library',
        ]) {
          void invalidate(prefix)
        }
      }
      void invalidate('/providers')
      void invalidate('/storage/status')
    },
  )
  if (cache) editors.set(cache, editor)
  return editor
}

export function SettingsPage() {
  const settings = useAPI<Settings>('/settings')
  return (
    <div className="settings-v5">
      <PageHeader title="设置" />
      <div className="settings-content">
        <SourcesPanel />
        <QueryState
          pending={settings.isPending}
          error={settings.error}
          retry={settings.refetch}
          pendingClassName="settings-query-pending"
        >
          {settings.data && <SettingsEditor initial={settings.data} />}
        </QueryState>
      </div>
      <footer className="settings-version">乐屿 · Melora {APP_VERSION}</footer>
    </div>
  )
}

function SaveFeedback({
  field,
  feedback,
  retry,
}: {
  field: SettingField
  feedback?: Feedback
  retry: () => void
}) {
  return (
    <div className="settings-inline-feedback" data-state={feedback?.state || 'idle'}>
      <span id={`setting-${field}-feedback`} role="status" title={feedback?.message}>
        {feedback?.state === 'saving'
          ? '保存中…'
          : feedback?.state === 'saved'
            ? '已保存'
            : feedback?.state === 'error'
              ? feedback.message || '保存失败'
              : feedback?.state === 'dirty'
                ? '未保存'
                : '\u00a0'}
      </span>
      <button
        type="button"
        className="text-button"
        aria-label={`重试保存${fieldLabels[field]}`}
        disabled={feedback?.state !== 'error'}
        aria-hidden={feedback?.state !== 'error'}
        tabIndex={feedback?.state === 'error' ? 0 : -1}
        onClick={retry}
      >
        重试
      </button>
    </div>
  )
}

const qualityOptions = ['standard', '128k', '320k', 'flac', 'flac24bit', 'ape', 'wav'] as const
function SettingsEditor({ initial }: { initial: Settings }) {
  const cloud = useCloudDeploy()
  const [editor] = useState(() => getEditor(initial))
  const { values, feedback } = useSyncExternalStore(editor.subscribe, editor.getSnapshot)
  const sources = useAPI<SourcesState>('/sources')
  useEffect(() => editor.accept(initial), [editor, initial])
  const candidates =
    sources.data?.items.filter(
      (item) =>
        item.status === 'ready' && (values.autoSwitchSource || item.id === sources.data?.activeSourceId),
    ) || []
  const supported = new Set(
    candidates.flatMap((source) =>
      Object.values(source.platforms || {})
        .filter((platform) => platform.actions.includes('musicUrl'))
        .flatMap((platform) => platform.qualitys || []),
    ),
  )
  const qualities = qualityOptions.filter((id) => id === 'standard' || supported.has(id))
  const currentSupported = qualities.some((id) => id === values.defaultQuality)
  const stateFor = (field: SettingField) => (
    <SaveFeedback field={field} feedback={feedback[field]} retry={() => editor.commit(field)} />
  )
  const toggle = (field: 'embedTags' | 'autoSwitchSource', label: string, description: string) => (
    <div className="settings-item" key={field}>
      <div className="settings-item-control">
        <strong id={`setting-${field}-label`}>{label}</strong>
        <p id={`setting-${field}-description`}>{description}</p>
        <button
          type="button"
          className="switch"
          role="switch"
          aria-labelledby={`setting-${field}-label`}
          aria-describedby={`setting-${field}-description setting-${field}-feedback`}
          aria-checked={!!values[field]}
          onClick={() => editor.edit(field, !values[field])}
        >
          <span />
        </button>
      </div>
      {stateFor(field)}
    </div>
  )
  return (
    <div className="settings-preferences">
      {!cloud && (
        <section className="settings-section settings-downloads">
          <SectionHeader title="下载偏好" subtitle="管理保存位置、文件名称与音频元数据" />
          <div className="settings-card-body">
            <DownloadDirectory
              value={values.downloadRoot}
              description="未指定时将默认存储在系统本地 Music 路径"
              onChange={(path) => editor.edit('downloadRoot', path, false)}
              onCommit={(path) => editor.edit('downloadRoot', path)}
              dirty={feedback.downloadRoot?.state === 'dirty' || feedback.downloadRoot?.state === 'error'}
              feedback={stateFor('downloadRoot')}
            />
            <div className="settings-item">
              <div className="settings-item-control">
                <label htmlFor="file-name-format">文件命名格式</label>
                <p>下载音轨保存时使用的文件命名规则</p>
                <select
                  id="file-name-format"
                  value={values.fileNameFormat}
                  aria-describedby="setting-fileNameFormat-feedback"
                  onChange={(event) =>
                    editor.edit('fileNameFormat', event.target.value as Settings['fileNameFormat'])
                  }
                >
                  <option value="title-artist">歌名 - 歌手</option>
                  <option value="artist-title">歌手 - 歌名</option>
                  <option value="title">仅歌名</option>
                </select>
              </div>
              {stateFor('fileNameFormat')}
            </div>
            <div className="settings-item">
              <div className="settings-item-control">
                <label htmlFor="concurrency">同时下载任务数</label>
                <p>多线程并发数，过高可能影响检索稳定性</p>
                <select
                  id="concurrency"
                  value={values.concurrency}
                  aria-describedby="setting-concurrency-feedback"
                  onChange={(event) => editor.edit('concurrency', Number(event.target.value))}
                >
                  {[1, 2, 3].map((value) => (
                    <option key={value} value={value}>
                      {value} 个任务{value === 1 ? '（推荐）' : ''}
                    </option>
                  ))}
                </select>
              </div>
              {stateFor('concurrency')}
            </div>
            {toggle(
              'embedTags',
              '内嵌元数据（ID3）与封面',
              '开启后将歌曲信息、专辑封面及歌词直接写入音频文件',
            )}
          </div>
        </section>
      )}
      <section className="settings-section settings-playback">
        <SectionHeader title="播放与音质" subtitle="设置默认播放流与音源容错策略" />
        <div className="settings-card-body">
          <div className="settings-item">
            <div className="settings-item-control">
              <label htmlFor="default-quality">在线播放音质</label>
              <p>根据音源所支持的规格自动匹配播放流</p>
              <select
                id="default-quality"
                value={values.defaultQuality}
                aria-describedby="setting-defaultQuality-feedback"
                onChange={(event) => editor.edit('defaultQuality', event.target.value)}
              >
                {qualities.map((id) => (
                  <option key={id} value={id}>
                    {id === 'standard' ? '自动优选（默认）' : qualityName(id)}
                  </option>
                ))}
                {!currentSupported && (
                  <option value={values.defaultQuality} disabled>
                    {qualityName(values.defaultQuality)}（可用源未声明）
                  </option>
                )}
              </select>
            </div>
            {stateFor('defaultQuality')}
          </div>
          {toggle(
            'autoSwitchSource',
            '自动容灾切换可用音源',
            '当前音源无版权或失效时，自动尝试从其他音源匹配',
          )}
        </div>
      </section>
    </div>
  )
}

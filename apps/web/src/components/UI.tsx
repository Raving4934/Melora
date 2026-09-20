import { createContext, useContext, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import {
  AlertCircle,
  ArrowRight,
  Check,
  ChevronDown,
  Disc3,
  Download,
  LoaderCircle,
  Music2,
  Pause,
  Play,
  X,
} from 'lucide-react'
import { Link } from 'react-router'
import {
  api,
  idPath,
  queryClient,
  useAPI,
  useCatalogWarnings,
  useCatalogIssues,
  type CatalogIssueCode,
} from '../lib/api'
import type { Collection, Provider, Track } from '../lib/types'
import { notify, useUI } from '../stores/ui'
import { apiURL, assetURL } from '../lib/base'
import { errorMessage, formatPlayCount } from '../lib/format'
import { normalizeCoverURL } from '../lib/cover'
import { player, usePlayer, usePlayingCollection } from '../stores/player'

export function IconButton({
  label,
  children,
  className = '',
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return (
    <button type="button" className={`icon-button ${className}`} aria-label={label} title={label} {...props}>
      {children}
    </button>
  )
}
export function Cover({
  src,
  title = '',
  className = '',
  loading = 'lazy',
  fetchPriority = 'auto',
}: {
  src?: string
  title?: string
  className?: string
  loading?: 'eager' | 'lazy'
  fetchPriority?: 'high' | 'low' | 'auto'
}) {
  const placeholder = assetURL('/covers/placeholder.svg')
  const normalized = normalizeCoverURL(src)
  // 列表封面优先由浏览器直连平台 CDN，避免把正常图片流量集中到 Melora；
  // 协议相对地址统一提升为 HTTPS，直连失败后才使用受限同源代理兜底。
  const target = normalized.startsWith('//') ? `https:${normalized}` : normalized
  const direct = target ? assetURL(target) : ''
  const proxied =
    target && /^https?:\/\//i.test(target) ? apiURL(`/covers?url=${encodeURIComponent(target)}`) : ''
  return (
    <span className={`cover ${className}`}>
      <img
        src={direct || placeholder}
        alt={title}
        width="400"
        height="400"
        decoding="async"
        loading={loading}
        fetchPriority={fetchPriority}
        referrerPolicy="no-referrer"
        onError={(event) => {
          const current = event.currentTarget.getAttribute('src') || ''
          // 直连失败才进入代理；代理或本地图片失败后落到占位图，且不重复赋值形成错误循环。
          if (proxied && current === direct) {
            event.currentTarget.setAttribute('src', proxied)
            return
          }
          if (current !== placeholder) event.currentTarget.setAttribute('src', placeholder)
        }}
      />
    </span>
  )
}
export function PageHeader({
  title,
  description,
  children,
  className,
  inline = false,
}: {
  eyebrow?: string
  title: string
  description?: string
  children?: ReactNode
  className?: string
  inline?: boolean
}) {
  return (
    <header className={`page-heading ${inline ? 'page-heading-inline' : ''} ${className || ''}`.trim()}>
      <div className="page-heading-copy">
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {children && <div className="heading-actions">{children}</div>}
    </header>
  )
}
export function SectionHeader({
  title,
  subtitle,
  className = '',
  inline = false,
  children,
}: {
  title: string
  subtitle?: ReactNode
  className?: string
  inline?: boolean
  children?: ReactNode
}) {
  return (
    <div className={`section-heading ${inline ? 'section-heading-inline' : ''} ${className}`.trim()}>
      <div className="section-heading-copy">
        <h2>{title}</h2>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {children}
    </div>
  )
}
export function EmptyState({
  title = '还没有内容',
  description,
  icon,
  action,
}: {
  title?: string
  description?: string
  icon?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon || <Music2 size={28} strokeWidth={1.4} />}</div>
      <h3>{title}</h3>
      {description && <p>{description}</p>}
      {action}
    </div>
  )
}
export function LoadingSurface({
  label = '正在加载…',
  className = '',
}: {
  label?: string
  className?: string
}) {
  return (
    <div className={`query-pending ${className}`.trim()} role="status" aria-live="polite">
      <span className="loading-status-copy">{label}</span>
      <span className="loading-rail" aria-hidden="true">
        <span />
      </span>
    </div>
  )
}
export function QueryState({
  pending,
  error,
  retry,
  children,
  empty = false,
  emptyTitle,
  emptyDescription,
  pendingClassName = '',
}: {
  pending: boolean
  error: Error | null
  retry?: () => unknown
  children: ReactNode
  empty?: boolean
  emptyTitle?: string
  emptyDescription?: string
  pendingClassName?: string
}) {
  // 首次加载只保留稳定空间；指示条延迟出现，避免快速响应时闪一下，后台更新继续保留旧 DOM。
  if (pending) return <LoadingSurface className={pendingClassName} />
  if (error)
    return (
      <EmptyState
        title="加载失败"
        description={error.message}
        icon={<AlertCircle size={26} />}
        action={
          retry && (
            <button
              className="button secondary"
              onClick={() => {
                void retry()
              }}
            >
              重新加载
            </button>
          )
        }
      />
    )
  if (empty) return <EmptyState title={emptyTitle} description={emptyDescription} />
  return children
}
export function SourceFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const { data, error, refetch, isFetching } = useAPI<Provider[]>('/providers')
  const strip = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const element = strip.current
    const selected = element?.querySelector<HTMLButtonElement>('button[aria-pressed="true"]')
    if (!element || !selected || element.scrollWidth <= element.clientWidth) return
    const left =
      selected.getBoundingClientRect().left - element.getBoundingClientRect().left + element.scrollLeft
    if (left < element.scrollLeft || left + selected.offsetWidth > element.scrollLeft + element.clientWidth)
      element.scrollLeft = Math.max(0, left - 8)
  }, [value, data])

  const selectedProvider = data?.find((p) => p.id === value)
  const currentLabel =
    value === 'all' ? '全部平台' : selectedProvider?.isDemo ? '演示音源' : selectedProvider?.name || value

  return (
    <>
      <div ref={strip} className="source-filter source-filter-desktop" aria-label="音乐平台筛选">
        <button
          type="button"
          className={value === 'all' ? 'selected' : ''}
          aria-pressed={value === 'all'}
          onClick={() => onChange('all')}
        >
          全部平台
        </button>
        {data
          ?.filter((provider) => provider.enabled)
          .map((provider) => (
            <button
              type="button"
              key={provider.id}
              className={value === provider.id ? 'selected' : ''}
              aria-pressed={value === provider.id}
              onClick={() => onChange(provider.id)}
            >
              <span className="source-dot" />
              {provider.isDemo ? '演示音源' : provider.name}
            </button>
          ))}
      </div>
      <div className="source-filter-mobile">
        <div className="source-select-trigger" aria-label={`当前平台：${currentLabel}`}>
          <span>{currentLabel}</span>
          <ChevronDown size={14} />
          <select aria-label="选择音乐平台" value={value} onChange={(e) => onChange(e.target.value)}>
            <option value="all">全部平台</option>
            {data
              ?.filter((provider) => provider.enabled)
              .map((provider) => (
                <option key={provider.id} value={provider.id}>
                  {provider.isDemo ? '演示音源' : provider.name}
                </option>
              ))}
          </select>
        </div>
      </div>
      {error && (
        <div className="source-filter-error" role="alert">
          <span>平台加载失败：{error.message}</span>
          <button className="text-button" disabled={isFetching} onClick={() => void refetch()}>
            重试
          </button>
        </div>
      )}
    </>
  )
}
const ProviderNamesContext = createContext<Provider[] | undefined>(undefined)

export function ProviderNamesProvider({ children }: { children: ReactNode }) {
  const { data } = useAPI<Provider[]>('/providers')
  return <ProviderNamesContext value={data || []}>{children}</ProviderNamesContext>
}

function providerName(data: Provider[] | undefined, id: string) {
  const provider = data?.find((item) => item.id === id)
  return provider?.isDemo || id === 'demo' ? '演示音源' : provider?.name || id
}

function QueriedSourceName({ id }: { id: string }) {
  const { data } = useAPI<Provider[]>('/providers')
  return <>{providerName(data, id)}</>
}

export function SourceName({ id }: { id: string }) {
  const providers = useContext(ProviderNamesContext)
  return providers === undefined ? <QueriedSourceName id={id} /> : <>{providerName(providers, id)}</>
}
function CollectionCardItem({
  item,
  chart,
  selected,
  onSelect,
  linkFor,
  kind,
  unit,
}: {
  item: Collection
  chart: boolean
  selected?: string
  onSelect?: (item: Collection) => void
  linkFor?: (item: Collection) => string
  kind?: string
  unit: string
}) {
  const [loading, setLoading] = useState(false)
  const isPlaying = usePlayer((s) => s.playing)
  const currentTrack = usePlayer((s) => s.track)
  const playingCollectionId = usePlayingCollection((s) => s.collectionId)
  const playingTrackIds = usePlayingCollection((s) => s.trackIds)

  const isCurrent =
    playingCollectionId === item.id && currentTrack !== null && playingTrackIds.includes(currentTrack.id)
  const isThisPlaying = isCurrent && isPlaying
  const displayKind = kind || (chart ? '榜单' : '歌单')
  const targetLink = linkFor
    ? linkFor(item)
    : `/${chart ? 'charts' : 'playlists'}/${encodeURIComponent(item.id)}`

  const handlePlay = async (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()

    if (isThisPlaying) {
      player.pause()
      return
    }
    if (isCurrent && currentTrack) {
      player.resume()
      return
    }

    setLoading(true)
    try {
      let tracks = item.tracks
      if (!tracks || !tracks.length) {
        let apiPath = `/playlists/${idPath(item.id)}`
        if (targetLink.startsWith('/audiobooks/albums/')) {
          apiPath = `/audiobooks/albums/${idPath(item.id)}?page=1`
        } else if (chart || targetLink.startsWith('/charts/')) {
          apiPath = `/charts/${idPath(item.id)}`
        }
        const data = await queryClient.fetchQuery({
          queryKey: [apiPath],
          queryFn: ({ signal }) => api<Collection & { tracks?: Track[] }>(apiPath, { signal }),
        })
        tracks = data?.tracks || []
      }
      if (!tracks.length) {
        notify(`该${displayKind}暂无可播放内容`, 'error')
        return
      }
      await player.playCollection(item.id, tracks)
      notify(`开始播放：${item.title}`, 'info')
    } catch (err: unknown) {
      notify(`无法加载${displayKind}：${errorMessage(err)}`, 'error')
    } finally {
      setLoading(false)
    }
  }

  if (onSelect && !chart) {
    return (
      <button
        type="button"
        className={`collection-item ${selected === item.id ? 'active' : ''}`}
        aria-pressed={selected === item.id}
        onClick={() => onSelect(item)}
      >
        <div className="collection-art">
          <Cover src={item.coverUrl} />
          {item.playCount !== undefined && Number.isFinite(item.playCount) && item.playCount >= 0 && (
            <span
              className="collection-play-count"
              aria-label={`播放量 ${item.playCount.toLocaleString('zh-CN')} 次`}
            >
              <span aria-hidden="true">▷</span> {formatPlayCount(item.playCount)}
            </span>
          )}
        </div>
        <div className="collection-copy">
          <strong title={item.title}>{item.title}</strong>
          <span className="collection-meta">
            <SourceName id={item.providerId} /> · {item.trackCount} {unit}
          </span>
        </div>
      </button>
    )
  }

  return (
    <div className={`collection-item ${chart ? 'chart-item' : ''}`}>
      <div className={`collection-art ${chart ? 'chart-art' : ''}`}>
        <Link to={targetLink} className="collection-art-link" tabIndex={-1} aria-hidden="true">
          <Cover src={item.coverUrl} />
        </Link>
        {!chart && item.playCount !== undefined && Number.isFinite(item.playCount) && item.playCount >= 0 && (
          <span
            className="collection-play-count"
            aria-label={`播放量 ${item.playCount.toLocaleString('zh-CN')} 次`}
          >
            <span aria-hidden="true">▷</span> {formatPlayCount(item.playCount)}
          </span>
        )}
        <div className="collection-actions">
          <button
            type="button"
            className={`collection-play-btn ${isThisPlaying ? 'is-playing' : ''} ${loading ? 'is-loading' : ''}`}
            aria-label={isThisPlaying ? `暂停 ${item.title}` : `播放 ${item.title}`}
            title={isThisPlaying ? '暂停' : '直接播放'}
            onClick={handlePlay}
          >
            {loading ? (
              <LoaderCircle size={15} className="spin" />
            ) : isThisPlaying ? (
              <Pause size={15} fill="currentColor" />
            ) : (
              <Play size={15} fill="currentColor" style={{ marginLeft: 2 }} />
            )}
          </button>
        </div>
      </div>
      <Link
        className={`collection-copy ${chart ? 'chart-copy' : ''}`}
        to={targetLink}
        aria-label={`打开${displayKind} ${item.title}`}
      >
        <strong title={item.title}>{item.title}</strong>
        <span className="collection-meta">
          <SourceName id={item.providerId} /> · {item.trackCount} {unit}
          {chart ? '歌曲' : ''}
        </span>
        {chart && item.description && <p className="chart-description">{item.description}</p>}
        {chart && (
          <span className="chart-open">
            查看榜单 <ArrowRight size={15} />
          </span>
        )}
      </Link>
    </div>
  )
}

export function CollectionGrid({
  items,
  chart = false,
  selected,
  onSelect,
  linkFor,
  kind,
  unit = '首',
}: {
  items: Collection[]
  chart?: boolean
  selected?: string
  onSelect?: (item: Collection) => void
  linkFor?: (item: Collection) => string
  kind?: string
  unit?: string
}) {
  return (
    <div className={chart ? 'chart-grid' : 'collection-grid'}>
      {items.map((item) => (
        <CollectionCardItem
          key={item.id}
          item={item}
          chart={chart}
          selected={selected}
          onSelect={onSelect}
          linkFor={linkFor}
          kind={kind}
          unit={unit}
        />
      ))}
    </div>
  )
}
export function Modal({
  title,
  children,
  onClose,
  className = '',
  initialFocus,
}: {
  title: string
  children: ReactNode
  onClose: () => void
  className?: string
  initialFocus?: RefObject<HTMLElement | null>
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  useEffect(() => {
    const dialog = ref.current
    dialog?.showModal()
    // 在原生dialog进入top layer后聚焦；StrictMode重放也不让关闭按钮夺走输入焦点。
    initialFocus?.current?.focus({ preventScroll: true })
    const close = (event: Event) => {
      event.preventDefault()
      onCloseRef.current()
    }
    dialog?.addEventListener('cancel', close)
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      dialog?.removeEventListener('cancel', close)
      dialog?.close()
      document.body.style.overflow = previous
    }
  }, [initialFocus])
  return (
    <dialog
      ref={ref}
      className={`modal ${className}`}
      aria-label={title}
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          const box = event.currentTarget.getBoundingClientRect()
          if (
            event.clientX < box.left ||
            event.clientX > box.right ||
            event.clientY < box.top ||
            event.clientY > box.bottom
          )
            onClose()
        }
      }}
    >
      <header className="modal-heading">
        <h2>{title}</h2>
        <IconButton label="关闭" onClick={onClose}>
          <X size={19} />
        </IconButton>
      </header>
      {children}
    </dialog>
  )
}
export function Toast() {
  const toast = useUI((state) => state.toast)
  if (!toast) return null
  return (
    <div key={toast.id} className={`toast ${toast.kind}`} role={toast.kind === 'error' ? 'alert' : 'status'}>
      {toast.kind === 'error' ? (
        <AlertCircle size={17} />
      ) : toast.kind === 'success' ? (
        <Check size={17} />
      ) : (
        <Disc3 size={17} />
      )}
      <span>{toast.message}</span>
    </div>
  )
}
export function DownloadHint() {
  return (
    <span className="muted inline">
      <Download size={13} />
      由飞牛保存，非浏览器下载
    </span>
  )
}

export function CatalogWarnings({
  path,
  retry,
  busy,
}: {
  path: string
  retry: () => unknown
  busy?: boolean
}) {
  const failed = useCatalogWarnings(path)
  const issues = useCatalogIssues(path)
  const providers = useAPI<Provider[]>('/providers')
  if (!failed.length) return null
  const scope = path.startsWith('/search') ? '搜索' : path.startsWith('/charts') ? '榜单' : '歌单'
  const reasons: Record<CatalogIssueCode, string> = {
    access_restricted: '访问受平台限制',
    rate_limited: '请求过于频繁',
    timeout: '请求超时',
    unsupported: '暂不受支持',
    invalid_response: '响应格式异常',
    upstream_rejected: '被平台拒绝',
    cancelled: '请求已取消',
    upstream_unavailable: '请求未完成',
  }
  const messages = failed.map((id) => {
    const name = providers.data?.find((provider) => provider.id === id)?.name || id
    return `${name}的本次${scope}${reasons[issues[id]!] || '请求未完成'}`
  })
  return (
    <div className="catalog-warning" role="status">
      <AlertCircle size={16} />
      <span>{messages.join('；')}。可稍后重试；已显示的结果仍可使用。</span>
      <button type="button" className="text-button" disabled={busy} onClick={() => void retry()}>
        重试
      </button>
    </div>
  )
}

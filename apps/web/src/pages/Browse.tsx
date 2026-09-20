import {
  memo,
  useCallback,
  useDeferredValue,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type CompositionEvent,
  type MouseEvent,
  type ReactNode,
} from 'react'
import {
  AlertCircle,
  Info,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Heart,
  LoaderCircle,
  Pause,
  Play,
  RefreshCw,
  Search,
  X,
} from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'
import {
  APIError,
  api,
  idPath,
  invalidate,
  queryClient,
  send,
  useAPI,
  useCatalogPagination,
} from '../lib/api'
import type { Collection, DiscoveryFeed, Track } from '../lib/types'
import {
  CollectionGrid,
  CatalogWarnings,
  Cover,
  EmptyState,
  IconButton,
  Modal,
  PageHeader,
  QueryState,
  SectionHeader,
  SourceFilter,
  SourceName,
} from '../components/UI'
import { TrackList } from '../components/TrackList'
import { player, usePlayer, usePlayingCollection } from '../stores/player'
import { notify } from '../stores/ui'
import { errorMessage } from '../lib/format'

import {
  chartHeroSize,
  chartKindOf,
  chartKindOptions,
  chartKindSections,
  chartPreviewSize,
  filterCharts,
  isChartKind,
  type ChartKind,
} from './browse-data'
import './Browse.css'

type PlaylistCategory = { id: string; name: string; group: string }
const playlistPageSize = 24

// 切换查询时隐藏上一批内容，但沿用其高度；同一查询刷新不卸载已有列表。
function ResultRegion({ pending, children }: { pending: boolean; children: ReactNode }) {
  const region = useRef<HTMLDivElement>(null)
  const height = useRef(300)
  const pendingRef = useRef(pending)

  useLayoutEffect(() => {
    pendingRef.current = pending
    if (!pending && region.current) {
      height.current = Math.max(300, Math.ceil(region.current.getBoundingClientRect().height))
    }
  }, [pending])

  useLayoutEffect(() => {
    const element = region.current
    if (!element) return
    const measure = () => {
      if (!pendingRef.current) {
        height.current = Math.max(300, Math.ceil(element.getBoundingClientRect().height))
      }
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return (
    <div className="browse-results" ref={region} style={{ minHeight: pending ? height.current : 300 }}>
      {children}
    </div>
  )
}

type ChartShowcase = {
  items: Collection[]
  unavailableIds?: string[]
  total: number
  batch?: number
  batches?: number
}

function ChartPanel({ chart, unavailable = false }: { chart: Collection; unavailable?: boolean }) {
  const tracks = (chart.tracks || []).slice(0, chartPreviewSize)
  return (
    <article className={`chart-panel platform-${chart.providerId}`} aria-label={chart.title}>
      <header className="chart-panel-heading">
        <Link className="chart-panel-link" to={`/charts/${idPath(chart.id)}`}>
          <Cover src={chart.coverUrl} />
          <span>
            <small className={`chart-platform-badge platform-${chart.providerId}`}>
              <span className="platform-dot" aria-hidden="true" />
              <SourceName id={chart.providerId} />
            </small>
            <h2 title={chart.title}>{chart.title}</h2>
            <span className="chart-panel-meta">榜内 Top {chartPreviewSize}</span>
          </span>
        </Link>
        <IconButton
          className="chart-play-button"
          label={`播放 ${chart.title} 前三首`}
          disabled={!tracks.length}
          onClick={() => tracks[0] && player.playCollection(chart.id, tracks)}
        >
          <Play size={16} fill="currentColor" />
        </IconButton>
      </header>
      <div className="chart-panel-tracks">
        <QueryState
          pending={false}
          error={null}
          empty={!tracks.length}
          emptyTitle={unavailable ? '榜单暂时无法读取' : '这个榜单暂时没有歌曲'}
          emptyDescription={unavailable ? '上游目录暂不可用，请稍后刷新。' : undefined}
        >
          <TrackList tracks={tracks} compact collectionId={chart.id} />
        </QueryState>
      </div>
    </article>
  )
}

function PendingChartPanels() {
  return (
    <>
      {Array.from({ length: chartHeroSize }, (_, index) => (
        <div className="chart-panel chart-panel-pending" key={index} aria-hidden={index !== 0}>
          <div role={index === 0 ? 'status' : undefined}>
            <LoaderCircle size={20} className="spin" />
            <span>正在读取焦点榜单…</span>
          </div>
        </div>
      ))}
    </>
  )
}

type ChartDirectoryCardProps = {
  chart: Collection
  isCurrent: boolean
  isPlaying: boolean
  claimPlayIntent: () => () => boolean
}

const ChartDirectoryCard = memo(function ChartDirectoryCard({
  chart,
  isCurrent,
  isPlaying,
  claimPlayIntent,
}: ChartDirectoryCardProps) {
  const [loading, setLoading] = useState(false)
  const loadingRef = useRef(false)
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])
  const isThisPlaying = isCurrent && isPlaying

  const handlePlay = async (e: MouseEvent<HTMLButtonElement>) => {
    e.preventDefault()
    e.stopPropagation()

    if (loadingRef.current) return
    const isLatestIntent = claimPlayIntent()

    if (isThisPlaying) {
      player.pause()
      return
    }
    if (isCurrent) {
      player.resume()
      return
    }

    loadingRef.current = true
    setLoading(true)
    try {
      const data = await queryClient.fetchQuery({
        queryKey: [`/charts/${idPath(chart.id)}`],
        queryFn: ({ signal }) => api<Collection>(`/charts/${idPath(chart.id)}`, { signal }),
      })
      if (!isLatestIntent()) return
      const tracks = data?.tracks || []
      if (!tracks.length) {
        notify('该榜单暂无可播放歌曲', 'error')
        return
      }
      await player.playCollection(chart.id, tracks)
      if (isLatestIntent()) notify(`开始播放：${chart.title}`, 'info')
    } catch (err: unknown) {
      if (isLatestIntent()) notify(`无法加载榜单歌曲：${errorMessage(err)}`, 'error')
    } finally {
      loadingRef.current = false
      if (mountedRef.current) setLoading(false)
    }
  }

  return (
    <div className="chart-directory-card">
      <div className="chart-directory-cover">
        <Link
          to={`/charts/${idPath(chart.id)}`}
          className="chart-directory-cover-link"
          aria-label={`打开榜单 ${chart.title}`}
          tabIndex={-1}
        >
          <Cover src={chart.coverUrl} />
        </Link>
        <small className={`chart-platform-badge platform-${chart.providerId}`}>
          <span className="platform-dot" aria-hidden="true" />
          <SourceName id={chart.providerId} />
        </small>
        <div className="chart-directory-actions">
          <button
            type="button"
            className={`chart-directory-play-btn ${isThisPlaying ? 'is-playing' : ''} ${loading ? 'is-loading' : ''}`}
            aria-label={isThisPlaying ? `暂停 ${chart.title}` : `播放 ${chart.title}`}
            title={isThisPlaying ? '暂停' : '直接播放'}
            disabled={loading}
            aria-busy={loading}
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
        to={`/charts/${idPath(chart.id)}`}
        className="chart-directory-copy"
        aria-label={`打开榜单 ${chart.title}`}
      >
        <strong title={chart.title}>{chart.title}</strong>
        <span>
          {chart.category || '榜单'}
          {chart.trackCount > 0 ? ` · ${chart.trackCount} 首歌曲` : ''}
        </span>
      </Link>
    </div>
  )
})

export function ChartsPage() {
  const [params, setParams] = useSearchParams()
  const source = params.get('source') || 'all'
  const kindParam = params.get('kind')
  const kind: ChartKind = isChartKind(kindParam) ? kindParam : 'all'
  const query = params.get('q') || ''
  const queryComposing = useRef(false)
  const [queryDraft, setQueryDraft] = useState(query)
  const deferredQuery = useDeferredValue(query)
  const showcasePath = `/charts/featured?source=${encodeURIComponent(source)}&batch=1`
  const catalogPath = `/charts?source=${encodeURIComponent(source)}`
  const showcase = useAPI<ChartShowcase>(showcasePath)
  const catalog = useAPI<Collection[]>(catalogPath)
  const visible = (showcase.data?.items || []).slice(0, chartHeroSize)
  const catalogItems = catalog.data || []
  const unavailable = new Set(showcase.data?.unavailableIds || [])
  const filtered = useMemo(
    () => filterCharts(catalogItems, kind, deferredQuery),
    [catalogItems, deferredQuery, kind],
  )
  const counts = useMemo(() => {
    const result: Record<ChartKind, number> = {
      all: catalogItems.length,
      official: 0,
      language: 0,
      genre: 0,
      scene: 0,
    }
    for (const item of catalogItems) result[chartKindOf(item)]++
    return result
  }, [catalogItems])
  const sections = useMemo(
    () =>
      chartKindSections
        .map((section) => ({
          ...section,
          items: filtered.filter((item) => chartKindOf(item) === section.id),
        }))
        .filter((section) => section.items.length > 0),
    [filtered],
  )
  const isPlaying = usePlayer((state) => state.playing)
  const currentTrackId = usePlayer((state) => state.track?.id ?? null)
  const playingCollectionId = usePlayingCollection((state) => state.collectionId)
  const playingTrackIds = usePlayingCollection((state) => state.trackIds)
  const playingTrackIdSet = useMemo(() => new Set(playingTrackIds), [playingTrackIds])

  const chartPlayIntentRef = useRef(0)
  const claimChartPlayIntent = useCallback(() => {
    const intent = ++chartPlayIntentRef.current
    return () => chartPlayIntentRef.current === intent
  }, [])

  const busy = showcase.isFetching || catalog.isFetching
  const switching = showcase.isPlaceholderData || catalog.isPlaceholderData
  const filtering = query !== deferredQuery

  const [mobileSearchOpen, setMobileSearchOpen] = useState(false)
  // 窄屏才把搜索栏切换为单行展开态；桌面端保留平台/类型筛选，搜索框内联清空。
  const [narrowChartSearch, setNarrowChartSearch] = useState(
    () => window.matchMedia?.('(max-width: 700px)').matches ?? false,
  )
  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 700px)')
    if (!media) return
    const update = () => setNarrowChartSearch(media.matches)
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])
  const showMobileSearch = mobileSearchOpen || (narrowChartSearch && Boolean(queryDraft))

  useEffect(() => {
    if (!queryComposing.current) setQueryDraft(query)
  }, [query])

  const updateParam = (key: 'source' | 'kind' | 'q', value: string, replace = false) => {
    const next = new URLSearchParams(params)
    next.delete('batch')
    if (!value || (key === 'source' && value === 'all') || (key === 'kind' && value === 'all')) {
      next.delete(key)
    } else {
      next.set(key, value)
    }
    setParams(next, { replace })
  }
  const handleQueryChange = (event: ChangeEvent<HTMLInputElement>) => {
    const value = event.currentTarget.value
    setQueryDraft(value)
    if (!queryComposing.current && !(event.nativeEvent as InputEvent).isComposing) {
      updateParam('q', value, true)
    }
  }
  const handleQueryCompositionStart = () => {
    queryComposing.current = true
  }
  const handleQueryCompositionEnd = (event: CompositionEvent<HTMLInputElement>) => {
    queryComposing.current = false
    const value = event.currentTarget.value
    setQueryDraft(value)
    updateParam('q', value, true)
  }
  const clearChartQuery = () => {
    queryComposing.current = false
    setQueryDraft('')
    updateParam('q', '', true)
  }
  const clearDirectoryFilters = () => {
    queryComposing.current = false
    setQueryDraft('')
    const next = new URLSearchParams(params)
    next.delete('batch')
    next.delete('kind')
    next.delete('q')
    setParams(next, { replace: true })
  }

  return (
    <>
      <PageHeader
        className="browse-charts-header"
        title="排行榜"
        description="浏览全平台热门与特色榜单"
        inline
      />

      <div className={`chart-filter-hub ${showMobileSearch ? 'mobile-search-active' : ''}`}>
        {!showMobileSearch ? (
          <div className="chart-filter-bar">
            <div className="chart-filter-platform-group">
              <div className="browse-platform-filter">
                <SourceFilter value={source} onChange={(value) => updateParam('source', value)} />
              </div>
            </div>

            <div className="chart-kind-filter" aria-label="榜单类型筛选">
              {chartKindOptions.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  className={kind === option.id ? 'selected' : ''}
                  aria-pressed={kind === option.id}
                  onClick={() => updateParam('kind', option.id)}
                >
                  {option.label}
                  <span className="chart-kind-count" aria-hidden="true">
                    {counts[option.id]}
                  </span>
                </button>
              ))}
            </div>

            <div className="chart-filter-tools">
              <IconButton
                label="搜索榜单"
                className="chart-search-toggle-mobile"
                onClick={() => setMobileSearchOpen(true)}
              >
                <Search size={15} />
              </IconButton>
              <label className="chart-search">
                <Search size={15} aria-hidden="true" />
                <span className="sr-only">在榜单中检索</span>
                <input
                  type="search"
                  value={queryDraft}
                  maxLength={80}
                  placeholder="在榜单中检索…"
                  aria-label="在榜单中检索"
                  onCompositionStart={handleQueryCompositionStart}
                  onCompositionEnd={handleQueryCompositionEnd}
                  onChange={handleQueryChange}
                />
                {queryDraft && (
                  <button type="button" aria-label="清空榜单搜索" onClick={clearChartQuery}>
                    <X size={15} />
                  </button>
                )}
              </label>
              <IconButton
                label="刷新排行榜"
                disabled={busy}
                onClick={() => void Promise.all([showcase.refetch(), catalog.refetch()])}
              >
                <RefreshCw size={15} className={busy ? 'spin' : ''} />
              </IconButton>
            </div>
          </div>
        ) : (
          <div className="chart-filter-bar chart-search-active-bar">
            <label className="chart-search chart-search-expanded">
              <Search size={15} aria-hidden="true" />
              <span className="sr-only">在榜单中检索</span>
              <input
                type="search"
                value={queryDraft}
                maxLength={80}
                placeholder="在榜单中检索…"
                aria-label="在榜单中检索"
                autoFocus
                onCompositionStart={handleQueryCompositionStart}
                onCompositionEnd={handleQueryCompositionEnd}
                onChange={handleQueryChange}
              />
              <button
                type="button"
                aria-label="关闭或清空榜单搜索"
                onClick={() => {
                  clearChartQuery()
                  setMobileSearchOpen(false)
                }}
              >
                <X size={15} />
              </button>
            </label>
            <IconButton
              label="刷新排行榜"
              disabled={busy}
              onClick={() => void Promise.all([showcase.refetch(), catalog.refetch()])}
            >
              <RefreshCw size={15} className={busy ? 'spin' : ''} />
            </IconButton>
          </div>
        )}
      </div>

      <section className="chart-showcase" aria-label="焦点主榜" aria-busy={showcase.isFetching}>
        <SectionHeader title="热门榜单" subtitle={`各平台正在流行 · 试听 Top ${chartPreviewSize}`} inline />
        <div className="chart-catalog-status" role="status" aria-live="polite">
          {switching ? '正在切换目录，暂时保留当前内容…' : null}
          <CatalogWarnings path={showcasePath} retry={showcase.refetch} busy={busy} />
        </div>
        <div className={`chart-panel-grid ${showcase.isPlaceholderData ? 'is-updating' : ''}`}>
          {showcase.isPending ? (
            <PendingChartPanels />
          ) : showcase.error || !visible.length ? (
            <div className="chart-grid-empty">
              <QueryState
                pending={false}
                error={showcase.error}
                retry={showcase.refetch}
                empty={!visible.length}
                emptyTitle="当前没有可试听的焦点榜单"
                emptyDescription="仍可继续浏览下方完整目录，或切换其它音乐平台。"
              >
                {null}
              </QueryState>
            </div>
          ) : (
            visible.map((chart) => (
              <ChartPanel key={chart.id} chart={chart} unavailable={unavailable.has(chart.id)} />
            ))
          )}
        </div>
      </section>

      <section
        className="chart-directory"
        aria-label="全部榜单目录"
        aria-busy={catalog.isFetching || filtering}
      >
        <span className="sr-only" aria-live="polite">
          {filtering ? '正在筛选…' : `${filtered.length} 个结果`}
        </span>
        <div className="chart-catalog-status">
          <CatalogWarnings path={catalogPath} retry={catalog.refetch} busy={busy} />
        </div>
        <div className={`chart-directory-results ${catalog.isPlaceholderData ? 'is-updating' : ''}`}>
          {catalog.isPending ? (
            <QueryState pending error={null} pendingClassName="chart-directory-pending">
              {null}
            </QueryState>
          ) : catalog.error || !catalogItems.length ? (
            <QueryState
              pending={false}
              error={catalog.error}
              retry={catalog.refetch}
              empty={!catalogItems.length}
              emptyTitle="当前平台没有可用榜单"
              emptyDescription="请切换音乐平台，或检查音源是否支持排行榜目录。"
            >
              {null}
            </QueryState>
          ) : !filtered.length ? (
            <EmptyState
              title="没有找到匹配的榜单"
              description="试试缩短关键词，或清除类型筛选后继续浏览。"
              action={
                <button type="button" className="button secondary" onClick={clearDirectoryFilters}>
                  清除筛选
                </button>
              }
            />
          ) : (
            sections.map((section) => (
              <section
                className="chart-directory-group"
                key={section.id}
                aria-labelledby={`chart-directory-${section.id}`}
              >
                <header>
                  <div>
                    <h3 id={`chart-directory-${section.id}`}>{section.title}</h3>
                    <p>{section.description}</p>
                  </div>
                  <span>{section.items.length}</span>
                </header>
                <div className="chart-directory-grid">
                  {section.items.map((chart) => {
                    const isCurrent =
                      playingCollectionId === chart.id &&
                      currentTrackId !== null &&
                      playingTrackIdSet.has(currentTrackId)
                    return (
                      <ChartDirectoryCard
                        key={chart.id}
                        chart={chart}
                        isCurrent={isCurrent}
                        isPlaying={isCurrent && isPlaying}
                        claimPlayIntent={claimChartPlayIntent}
                      />
                    )
                  })}
                </div>
              </section>
            ))
          )}
        </div>
      </section>
    </>
  )
}

export function ChartDetailPage() {
  const { id = '' } = useParams()
  const result = useAPI<Collection>(`/charts/${idPath(id)}`)
  const current = result.isPlaceholderData ? undefined : result.data
  const pending = result.isPending || result.isPlaceholderData
  return (
    <>
      <ResultRegion pending={pending}>
        <QueryState pending={pending} error={result.error} retry={result.refetch}>
          {current ? (
            <CollectionDetail
              collection={current}
              tracks={current.tracks || []}
              pending={false}
              error={null}
              refreshing={result.isFetching}
              refresh={result.refetch}
              kind="榜单"
            />
          ) : null}
        </QueryState>
      </ResultRegion>
    </>
  )
}

export function DiscoverPage() {
  const result = useAPI<DiscoveryFeed>('/recommendations/daily')
  const [infoOpen, setInfoOpen] = useState(false)
  const issues: { sourceId: string; scope?: string; code?: string }[] = result.data?.issues?.length
    ? result.data.issues
    : (result.data?.unavailableSources || []).map((sourceId) => ({ sourceId }))
  const hasWarning = !!(issues.length || result.backgroundError || result.error)
  const tracks = (result.data?.tracks || []).slice(0, 18)
  const playlists = (result.data?.playlists || []).slice(0, 12)
  const heroTrack = tracks[0]
  const newTrack = tracks[1] || heroTrack
  const isPlaying = usePlayer((state) => state.playing)
  const currentTrack = usePlayer((state) => state.track)
  const isHeroPlaying = isPlaying && currentTrack?.id === heroTrack?.id
  const isNewTrackPlaying = isPlaying && currentTrack?.id === newTrack?.id

  const playerError = usePlayer((state) => state.error)
  const playingTrackID = usePlayer((state) => state.track?.id)
  const playbackTrack = tracks.find((track) => track.id === playingTrackID)
  const playbackError = playbackTrack ? playerError : null
  const playbackLoading = usePlayer((state) => state.loading)
  return (
    <>
      <PageHeader className="browse-discovery-header" title="发现" inline />
      <span className="sr-only" role="status">
        {hasWarning ? '部分推荐数据未更新，已保留其余内容，可打开推荐状态查看详情。' : ''}
      </span>
      <div className="discovery-feedback">
        {playbackError ? (
          <div className="discovery-inline-error" role="alert">
            <span>{playbackError}</span>
            <button
              type="button"
              className="text-button"
              disabled={playbackLoading || !playbackTrack}
              onClick={() => playbackTrack && player.play(playbackTrack, tracks)}
            >
              重试播放
            </button>
          </div>
        ) : null}
      </div>
      <div className="daily-feed" aria-busy={result.isFetching}>
        {result.error ? (
          <div className="discovery-inline-error" role="alert">
            <span>{result.error.message}</span>
            <button
              type="button"
              className="text-button"
              disabled={result.isFetching}
              onClick={() => void result.refetch()}
            >
              重试
            </button>
          </div>
        ) : (
          <>
            <div className="discovery-hero-grid">
              {/* 左侧卡片：猜你喜欢 */}
              <article className="discovery-hero-card daily-tracks" aria-label="猜你喜欢">
                {/* 动态模糊封面背景 */}
                {heroTrack?.coverUrl ? (
                  <Cover src={heroTrack.coverUrl} className="hero-card-ambient-bg" />
                ) : null}
                <div className="hero-card-ambient-overlay" aria-hidden="true" />

                <button
                  type="button"
                  className="hero-card-cover-wrap"
                  aria-label={isHeroPlaying ? '暂停推荐' : '播放推荐'}
                  disabled={!heroTrack}
                  onClick={() => {
                    if (!heroTrack) return
                    if (isHeroPlaying) player.pause()
                    else player.play(heroTrack, tracks)
                  }}
                >
                  {heroTrack ? (
                    <Cover
                      src={heroTrack.coverUrl}
                      className="hero-card-cover-img"
                      loading="eager"
                      fetchPriority="high"
                    />
                  ) : (
                    <span className="hero-card-cover-placeholder" />
                  )}
                  <span
                    className={`hero-cover-play-btn ${isHeroPlaying ? 'is-playing' : ''}`}
                    aria-hidden="true"
                  >
                    {isHeroPlaying ? (
                      <Pause size={24} fill="currentColor" />
                    ) : (
                      <Play size={24} fill="currentColor" style={{ marginLeft: 3 }} />
                    )}
                  </span>
                </button>

                <div className="hero-card-body">
                  <div className="section-heading hero-card-sub-header">
                    <div className="hero-sub-meta-wrap">
                      <h2 className="hero-sub-title">猜你喜欢</h2>
                      <span className="daily-tracks-reason sr-only">
                        {' '}
                        · {result.data?.reason || '根据最近播放和收藏的歌手推荐'}
                      </span>
                    </div>
                    <div className="discovery-header-actions">
                      <IconButton
                        label={hasWarning ? '推荐状态：部分内容未更新' : '推荐说明'}
                        className={hasWarning ? 'discovery-warning-button' : ''}
                        onClick={() => setInfoOpen(true)}
                      >
                        {hasWarning ? <AlertCircle size={15} /> : <Info size={15} />}
                      </IconButton>
                      <IconButton
                        label="刷新发现推荐"
                        disabled={result.isFetching}
                        onClick={() => void result.refetch()}
                      >
                        <RefreshCw size={14} className={result.isFetching ? 'spin' : ''} />
                      </IconButton>
                    </div>
                  </div>

                  <div className="hero-card-main">
                    <h3 className="hero-song-title" title={heroTrack?.title || '正在读取歌曲'}>
                      {heroTrack?.title || (result.isPending ? '正在读取歌曲…' : '精选推荐')}
                    </h3>
                    <p
                      className="hero-song-artist"
                      title={
                        heroTrack
                          ? `${heroTrack.artist}${heroTrack.album ? ` · ${heroTrack.album}` : ''}`
                          : ''
                      }
                    >
                      {heroTrack ? (
                        <>
                          <span>{heroTrack.artist}</span>
                          {heroTrack.album ? (
                            <>
                              <span className="hero-meta-dot">·</span>
                              <span className="hero-artist-album">{heroTrack.album}</span>
                            </>
                          ) : null}
                        </>
                      ) : (
                        <span>{result.isPending ? '准备好歌中' : '根据你的听歌偏好生成'}</span>
                      )}
                    </p>
                  </div>

                  <div className="hero-card-bottom-row">
                    <span className="hero-bottom-tag">偏好推荐</span>
                    <Link
                      to="/discover/daily"
                      className="hero-bottom-link"
                      aria-label={`查看猜你喜欢完整清单 ${tracks.length} 首`}
                    >
                      <span>完整清单 {tracks.length}</span>
                      <ChevronRight size={14} />
                    </Link>
                  </div>
                </div>

                <div className="daily-track-region sr-only" data-loaded={!!result.data}>
                  <TrackList tracks={tracks} compact />
                </div>
              </article>

              {/* 右侧卡片：新歌速递 */}
              <article className="discovery-hero-card discovery-new-tracks-card" aria-label="新歌速递">
                {/* 动态模糊封面背景 */}
                {newTrack?.coverUrl ? (
                  <Cover src={newTrack.coverUrl} className="hero-card-ambient-bg" />
                ) : null}
                <div className="hero-card-ambient-overlay" aria-hidden="true" />

                <button
                  type="button"
                  className="hero-card-cover-wrap"
                  aria-label={
                    isNewTrackPlaying
                      ? `暂停 ${newTrack?.title || '新歌速递'}`
                      : newTrack
                        ? `试听 ${newTrack.title}`
                        : '探索新歌速递'
                  }
                  disabled={!newTrack}
                  onClick={() => {
                    if (!newTrack) return
                    if (isNewTrackPlaying) player.pause()
                    else player.play(newTrack, tracks)
                  }}
                >
                  {newTrack ? (
                    <Cover
                      src={newTrack.coverUrl}
                      className="hero-card-cover-img"
                      loading="eager"
                      fetchPriority="high"
                    />
                  ) : (
                    <span className="hero-card-cover-placeholder" />
                  )}
                  <span
                    className={`hero-cover-play-btn ${isNewTrackPlaying ? 'is-playing' : ''}`}
                    aria-hidden="true"
                  >
                    {isNewTrackPlaying ? (
                      <Pause size={24} fill="currentColor" />
                    ) : (
                      <Play size={24} fill="currentColor" style={{ marginLeft: 3 }} />
                    )}
                  </span>
                </button>

                <div className="hero-card-body">
                  <div className="section-heading hero-card-sub-header">
                    <div className="hero-sub-meta-wrap">
                      <h2 className="hero-sub-title">新歌速递</h2>
                    </div>
                  </div>

                  <div className="hero-card-main">
                    <h3 className="hero-song-title" title={newTrack?.title || '新歌速递'}>
                      {newTrack?.title || '最新发行精选'}
                    </h3>
                    <p
                      className="hero-song-artist"
                      title={
                        newTrack ? `${newTrack.artist}${newTrack.album ? ` · ${newTrack.album}` : ''}` : ''
                      }
                    >
                      {newTrack ? (
                        <>
                          <span>{newTrack.artist}</span>
                          {newTrack.album ? (
                            <>
                              <span className="hero-meta-dot">·</span>
                              <span className="hero-artist-album">{newTrack.album}</span>
                            </>
                          ) : null}
                        </>
                      ) : (
                        <span>第一时间收听优质新作</span>
                      )}
                    </p>
                  </div>

                  <div className="hero-card-bottom-row">
                    <span className="hero-bottom-tag">每日极速更新</span>
                    <Link
                      to="/discover/new-tracks"
                      className="hero-bottom-link"
                      aria-label="进入新碟专区完整列表"
                    >
                      <span>新碟专区</span>
                      <ChevronRight size={14} />
                    </Link>
                  </div>
                </div>
              </article>
            </div>

            <section className="daily-playlists" aria-label="推荐歌单">
              <SectionHeader
                title="推荐歌单"
                subtitle={result.isPending ? '正在读取歌单' : `${playlists.length} 张歌单`}
                inline
              >
                <Link className="text-button" to="/playlists">
                  全部歌单 <ChevronRight size={14} />
                </Link>
              </SectionHeader>
              <div className="daily-playlist-region" data-loaded={!!result.data}>
                <QueryState
                  pending={result.isPending}
                  error={null}
                  empty={!playlists.length}
                  emptyTitle="暂时没有推荐歌单"
                  emptyDescription="可以浏览全部歌单，或稍后刷新重试。"
                >
                  <CollectionGrid items={playlists} />
                </QueryState>
              </div>
            </section>
          </>
        )}
      </div>

      {infoOpen && (
        <Modal title="推荐说明" onClose={() => setInfoOpen(false)}>
          <p>{result.data?.reason || '根据实际收藏、最近播放的歌手及公开音乐目录推荐。'}</p>
          {hasWarning ? (
            <>
              <p>以下仅表示部分推荐接口未返回内容，不代表整个平台无法搜索或播放。</p>
              <ul className="recommendation-issues">
                {issues.map((issue, index) => (
                  <li key={`${issue.sourceId}:${issue.scope || 'recommendations'}:${index}`}>
                    <SourceName id={issue.sourceId} /> ·{' '}
                    {(
                      { tracks: '歌曲推荐', playlists: '推荐歌单', 'new-tracks': '新歌目录' } as Record<
                        string,
                        string
                      >
                    )[issue.scope || ''] || '部分推荐数据'}
                    读取失败
                  </li>
                ))}
              </ul>
              {(result.backgroundError || result.error) && (
                <p>{(result.backgroundError || result.error)?.message}</p>
              )}
              <button
                type="button"
                className="button secondary"
                disabled={result.isFetching}
                onClick={() => void result.refetch()}
              >
                重试更新
              </button>
            </>
          ) : (
            <p>推荐来自已取得的真实目录数据，不是平台私人账户推荐。</p>
          )}
        </Modal>
      )}
    </>
  )
}

export function DailyRecommendationPage() {
  const [batch, setBatch] = useState(0)
  const result = useAPI<DiscoveryFeed>(
    batch > 0 ? `/recommendations/daily?batch=${batch}` : '/recommendations/daily',
  )
  const [infoOpen, setInfoOpen] = useState(false)
  const tracks = (result.data?.tracks || []).slice(0, 18)
  const heroTrack = tracks[0]
  const issues: { sourceId: string; scope?: string; code?: string }[] = result.data?.issues?.length
    ? result.data.issues
    : (result.data?.unavailableSources || []).map((sourceId) => ({ sourceId }))
  const hasWarning = !!(issues.length || result.backgroundError || result.error)

  return (
    <>
      <div className="collection-detail-back-bar">
        <Link to="/discover" className="button secondary small playlist-back-button">
          <ChevronLeft size={14} />
          <span>返回发现</span>
        </Link>
      </div>
      <CollectionDetail
        collection={{
          id: 'daily-recommendations',
          providerId: '每日推荐',
          title: '猜你喜欢',
          category: '推荐',
          description: result.data?.reason || '根据实际收藏、最近播放的歌手及公开音乐目录推荐。',
          coverUrl: heroTrack?.coverUrl || '',
          trackCount: tracks.length,
        }}
        tracks={tracks}
        pending={result.isPending}
        error={result.error}
        refreshing={result.isFetching}
        refresh={result.refetch}
        kind="歌单"
        actions={
          <>
            <button
              type="button"
              className="button secondary"
              disabled={result.isFetching}
              onClick={() => setBatch((value) => value + 1)}
            >
              <RefreshCw size={15} className={result.isFetching ? 'spin' : ''} />
              换一批
            </button>
            <IconButton
              label={hasWarning ? '推荐状态：部分内容未更新' : '推荐说明'}
              className={hasWarning ? 'discovery-warning-button' : ''}
              onClick={() => setInfoOpen(true)}
            >
              {hasWarning ? <AlertCircle size={16} /> : <Info size={16} />}
            </IconButton>
          </>
        }
      />
      {infoOpen && (
        <Modal title="推荐说明" onClose={() => setInfoOpen(false)}>
          <p>{result.data?.reason || '根据实际收藏、最近播放的歌手及公开音乐目录推荐。'}</p>
          {hasWarning ? (
            <>
              <p>以下仅表示部分推荐接口未返回内容，不代表整个平台无法搜索或播放。</p>
              <ul className="recommendation-issues">
                {issues.map((issue, index) => (
                  <li key={`${issue.sourceId}:${issue.scope || 'recommendations'}:${index}`}>
                    <SourceName id={issue.sourceId} /> ·{' '}
                    {(
                      { tracks: '歌曲推荐', playlists: '推荐歌单', 'new-tracks': '新歌目录' } as Record<
                        string,
                        string
                      >
                    )[issue.scope || ''] || '部分推荐数据'}
                    读取失败
                  </li>
                ))}
              </ul>
              {(result.backgroundError || result.error) && (
                <p>{(result.backgroundError || result.error)?.message}</p>
              )}
              <button
                type="button"
                className="button secondary"
                disabled={result.isFetching}
                onClick={() => void result.refetch()}
              >
                重试更新
              </button>
            </>
          ) : (
            <p>推荐来自已取得的真实目录数据，不是平台私人账户推荐。</p>
          )}
        </Modal>
      )}
    </>
  )
}

export function NewTracksPage() {
  const [area, setArea] = useState('all')
  const result = useAPI<{
    tracks: Track[]
    areas: { id: string; name: string }[]
  }>(`/discovery/new-tracks?area=${encodeURIComponent(area)}`)
  const tracks = result.data?.tracks || []
  const featuredTrack = tracks[0]

  return (
    <div className="new-tracks-page">
      <div className="collection-detail-back-bar">
        <Link to="/discover" className="button secondary small playlist-back-button">
          <ChevronLeft size={14} />
          <span>返回发现</span>
        </Link>
      </div>
      <CollectionDetail
        collection={{
          id: `new-tracks-${area}`,
          providerId: '新歌首发',
          title: '新歌速递',
          category: '新歌',
          description: '按地区浏览最新发行新歌，第一时间听见新鲜旋律。',
          coverUrl: featuredTrack?.coverUrl || '',
          trackCount: tracks.length,
        }}
        tracks={tracks}
        pending={result.isPending}
        error={result.error}
        refreshing={result.isFetching}
        refresh={result.refetch}
        kind="歌单"
        emptyTitle="该地区暂无新歌"
        status={result.isPlaceholderData ? '正在切换地区，暂时保留上次歌曲…' : `${tracks.length} 首新歌`}
        actions={
          <div className="category-filter new-track-areas" role="group" aria-label="新歌地区">
            <span>地区</span>
            {(result.data?.areas || [{ id: 'all', name: '全部' }]).map((item) => (
              <button
                key={item.id}
                type="button"
                className={item.id === area ? 'selected' : ''}
                aria-pressed={item.id === area}
                onClick={() => setArea(item.id)}
              >
                {item.name}
              </button>
            ))}
          </div>
        }
      />
    </div>
  )
}

function PlaylistPagination({
  page,
  nextDisabled,
  label,
  onChange,
}: {
  page: number
  nextDisabled: boolean
  label: string
  onChange: (page: number) => void
}) {
  return (
    <nav className="browse-pagination" aria-label={label}>
      <button
        type="button"
        className="button secondary small"
        aria-label="上一页"
        disabled={page <= 1}
        onClick={() => onChange(page - 1)}
      >
        <ChevronLeft size={15} />
        <span className="pagination-text">上一页</span>
      </button>
      <span aria-live="polite">第 {page} 页</span>
      <button
        type="button"
        className="button secondary small"
        aria-label="下一页"
        disabled={nextDisabled}
        onClick={() => onChange(page + 1)}
      >
        <span className="pagination-text">下一页</span>
        <ChevronRight size={15} />
      </button>
    </nav>
  )
}

export function PlaylistsPage() {
  const [categoriesOpen, setCategoriesOpen] = useState(false)
  const [params, setParams] = useSearchParams()
  const source = params.get('source') || 'all'
  const category = params.get('category') || 'all'
  const requestedPage = Number(params.get('page') || 1)
  const page = Number.isSafeInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1
  const categories = useAPI<{ categories: PlaylistCategory[] }>(
    `/playlist-categories?source=${encodeURIComponent(source)}`,
  )
  const playlistsPath = `/playlists?category=${encodeURIComponent(category)}&source=${encodeURIComponent(source)}&page=${page}`
  const playlists = useAPI<Collection[]>(playlistsPath)
  const hasMore = useCatalogPagination(playlistsPath)
  const current = playlists.data
  const pending = playlists.isPending
  const groups = new Map<string, PlaylistCategory[]>()
  const seen = new Set<string>()
  for (const item of (categories.isPlaceholderData ? [] : categories.data?.categories) || []) {
    if (
      !item.id ||
      !item.name ||
      item.id.toLowerCase() === 'all' ||
      item.id === '全部' ||
      item.name === '全部' ||
      seen.has(item.id)
    )
      continue
    seen.add(item.id)
    const group = item.group || '分类'
    groups.set(group, [...(groups.get(group) || []), item])
  }
  const allCategories = [...groups.values()].flat()
  const selectedCategory = allCategories.find((item) => item.id === category)
  const categoryButton = (item: PlaylistCategory, inDialog = false) => (
    <button
      key={item.id}
      type="button"
      className={item.id === category ? 'selected' : ''}
      aria-pressed={item.id === category}
      onClick={() => {
        change(source, item.id, 1)
        if (inDialog) setCategoriesOpen(false)
      }}
    >
      {item.name}
    </button>
  )
  const results = useRef<HTMLElement>(null)
  const change = (nextSource: string, nextCategory: string, nextPage: number) => {
    if (source === nextSource && category === nextCategory && page === nextPage) return
    setParams({ source: nextSource, category: nextCategory, page: String(nextPage) })
  }
  const changePage = (nextPage: number) => {
    // 只有用户从列表底部翻页才定位到结果开头，不在请求完成或每次渲染时强行滚动。
    const element = results.current
    const viewportTop = element?.closest('main')?.getBoundingClientRect().top || 0
    if (element && element.getBoundingClientRect().top < viewportTop)
      element.scrollIntoView({ block: 'start', behavior: 'instant' })
    change(source, category, nextPage)
  }
  const providerCounts = new Map<string, number>()
  for (const item of current || [])
    providerCounts.set(item.providerId, (providerCounts.get(item.providerId) || 0) + 1)
  const nextDisabled =
    playlists.isFetching ||
    playlists.isPlaceholderData ||
    !current ||
    !(hasMore ?? [...providerCounts.values()].some((count) => count >= playlistPageSize))
  const categoryUnsupported =
    categories.error instanceof APIError && categories.error.code === 'catalog_capability_unsupported'
  const choosePlatformFirst =
    source === 'all' && !categories.isPending && !categories.error && !allCategories.length
  return (
    <>
      <PageHeader className="browse-playlists-header" title="歌单" inline />
      <div className="browse-top-toolbar browse-playlists-toolbar">
        <div className="browse-playlists-filters">
          <div className="browse-platform-filter">
            <SourceFilter value={source} onChange={(value) => change(value, 'all', 1)} />
          </div>
          <button
            type="button"
            className="button secondary playlist-category-trigger"
            aria-label="选择歌单分类"
            aria-haspopup="dialog"
            disabled={choosePlatformFirst || categoryUnsupported}
            aria-expanded={categoriesOpen}
            onClick={() => setCategoriesOpen(true)}
          >
            <span className="playlist-category-trigger-text">
              {choosePlatformFirst
                ? '全部分类'
                : categoryUnsupported
                  ? '分类暂不支持'
                  : selectedCategory?.name || (category === 'all' ? '全部' : category)}
            </span>
            <ChevronDown size={14} />
          </button>
        </div>

        <div className="playlist-list-actions">
          <PlaylistPagination
            page={page}
            nextDisabled={nextDisabled}
            label="歌单分页"
            onChange={changePage}
          />
          <IconButton
            label="刷新歌单列表"
            disabled={playlists.isFetching}
            onClick={() => {
              void playlists.refetch()
            }}
          >
            <RefreshCw size={15} className={playlists.isFetching ? 'spin' : ''} />
          </IconButton>
        </div>
      </div>
      {(categoryUnsupported || categories.error) && (
        <div className="playlist-category-groups">
          {categoryUnsupported ? (
            <p className="category-status" role="status">
              该平台当前未提供可用的歌单分类，仍可浏览热门歌单与翻页。
            </p>
          ) : categories.error ? (
            <div className="source-filter-error" role="alert">
              <span>分类加载失败：{categories.error.message}</span>
              <button
                className="text-button"
                disabled={categories.isFetching}
                onClick={() => void categories.refetch()}
              >
                重试
              </button>
            </div>
          ) : null}
        </div>
      )}
      {categoriesOpen && (
        <Modal title="全部分类" className="category-dialog" onClose={() => setCategoriesOpen(false)}>
          <div className="category-filter" role="group" aria-label="歌单分类">
            <span>分类</span>
            {categoryButton({ id: 'all', name: '全部', group: '分类' }, true)}
          </div>
          {categories.isPending && (
            <p className="category-status" role="status">
              正在加载分类…
            </p>
          )}
          {[...groups].map(([group, items]) => (
            <div className="category-filter" key={group} role="group" aria-label={group}>
              <span>{group}</span>
              <div>{items.map((item) => categoryButton(item, true))}</div>
            </div>
          ))}
        </Modal>
      )}
      <CatalogWarnings path={playlistsPath} retry={playlists.refetch} busy={playlists.isFetching} />
      <section className="section playlist-section" ref={results} aria-busy={playlists.isFetching}>
        <SectionHeader
          className="browse-playlists-section-heading"
          title={category === 'all' ? '全部歌单' : `${selectedCategory?.name || category}歌单`}
          subtitle={current ? `本页 ${current.length} 张歌单` : '正在读取歌单'}
          inline
        />
        <div className="search-update-status" role="status">
          {playlists.isPlaceholderData ? '正在更新筛选，暂时保留上一批歌单…' : null}
        </div>
        <ResultRegion pending={pending}>
          <QueryState pending={pending} error={playlists.error} retry={playlists.refetch}>
            {current?.length ? (
              <CollectionGrid items={current} />
            ) : (
              <EmptyState
                title={page > 1 ? '这一页没有更多歌单' : '这个分类暂时没有歌单'}
                description={page > 1 ? '请返回上一页，或切换分类。' : '试试其他分类，或在设置中启用音源。'}
              />
            )}
          </QueryState>
        </ResultRegion>
        <PlaylistPagination
          page={page}
          nextDisabled={nextDisabled}
          label="歌单底部分页"
          onChange={changePage}
        />
      </section>
    </>
  )
}

function CollectionDetail({
  collection,
  tracks,
  pending,
  error,
  refreshing,
  refresh,
  kind,
  actions,
  status,
  emptyTitle,
}: {
  collection: Collection
  tracks?: Track[]
  pending: boolean
  error: Error | null
  refreshing: boolean
  refresh: () => unknown
  kind: '榜单' | '歌单'
  actions?: ReactNode
  status?: ReactNode
  emptyTitle?: string
}) {
  return (
    <>
      <header className="playlist-detail-heading">
        <Cover src={collection.coverUrl} />
        <div>
          <div className="playlist-source">
            <SourceName id={collection.providerId} />
          </div>
          <h1>{collection.title}</h1>
          {collection.description ? <p>{collection.description}</p> : null}
          <span className="muted">{tracks ? tracks.length : collection.trackCount} 首歌曲</span>
          <div className="detail-actions">
            <button
              className="button primary"
              disabled={pending || !tracks?.length}
              onClick={() => player.playCollection(collection.id, tracks || [])}
            >
              <Play size={15} fill="currentColor" />
              播放全部
            </button>
            {actions}
            <IconButton
              label={`刷新${kind}`}
              disabled={refreshing}
              onClick={() => {
                void refresh()
              }}
            >
              <RefreshCw size={15} className={refreshing ? 'spin' : ''} />
            </IconButton>
          </div>
        </div>
      </header>
      {status !== undefined && (
        <div className="search-update-status" role="status">
          {status}
        </div>
      )}
      <ResultRegion pending={pending}>
        <QueryState
          pending={pending}
          error={error}
          retry={refresh}
          empty={!tracks?.length}
          emptyTitle={emptyTitle || `${kind}暂无歌曲`}
        >
          <TrackList tracks={tracks || []} collectionId={collection.id} />
        </QueryState>
      </ResultRegion>
    </>
  )
}

export function PlaylistDetailPage() {
  const { id = '' } = useParams()
  const result = useAPI<Collection>(`/playlists/${idPath(id)}`)
  const favorites = useAPI<Collection[]>('/library/favorites/playlists')
  const [saving, setSaving] = useState(false)
  const current = result.isPlaceholderData ? undefined : result.data
  const pending = result.isPending || result.isPlaceholderData
  const liked = favorites.data?.some((item) => item.id === id)
  const toggleFavorite = async () => {
    setSaving(true)
    try {
      await send(`/library/favorites/playlists/${idPath(id)}`, liked ? 'DELETE' : 'POST')
      await Promise.all([invalidate('/library/favorites/playlists'), invalidate('/library/summary')])
      notify(liked ? '已取消收藏歌单' : '已收藏歌单', 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      setSaving(false)
    }
  }
  return (
    <>
      <ResultRegion pending={pending}>
        <QueryState pending={pending} error={result.error} retry={result.refetch}>
          {current ? (
            <CollectionDetail
              collection={current}
              tracks={current.tracks || []}
              pending={false}
              error={null}
              refreshing={result.isFetching}
              refresh={result.refetch}
              kind="歌单"
              actions={
                <button
                  className={`button secondary ${liked ? 'liked' : ''}`}
                  disabled={saving || favorites.isPending}
                  onClick={() => {
                    void toggleFavorite()
                  }}
                >
                  <Heart size={15} fill={liked ? 'currentColor' : 'none'} />
                  {liked ? '已收藏' : '收藏歌单'}
                </button>
              }
            />
          ) : null}
        </QueryState>
      </ResultRegion>
    </>
  )
}

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import {
  ChevronLeft,
  ChevronRight,
  Headphones,
  Heart,
  LoaderCircle,
  Pause,
  Play,
  RefreshCw,
} from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, idPath, invalidate, queryClient, send, useAPI } from '../lib/api'
import type { BookAlbum, BookHome, BookRankResult, BookSection, Collection } from '../lib/types'
import {
  CollectionGrid,
  Cover,
  EmptyState,
  IconButton,
  QueryState,
  SectionHeader,
  SourceName,
} from '../components/UI'
import { TrackList } from '../components/TrackList'
import { player, usePlayer, usePlayingCollection } from '../stores/player'
import { notify } from '../stores/ui'
import { errorMessage, formatPlayCount } from '../lib/format'
import { rankIcon } from '../lib/rank-icons'
import './Browse.css'
import './Audiobooks.css'

// 服务端目录接口只接受 1–50 页，前端分页与页码展示必须使用同一上限。
const MAX_CATALOG_PAGE = 50
// 「换一批」每次把队首整体后移的卡片数；翻页箭头按可视宽度滚动。
const BOOK_BATCH_SIZE = 3

// 上游听书条目没有逐条标签，用栏目名派生统一短角标，避免每张封面都是同一个泛化分类。
const BOOK_BADGE_LABELS: Record<string, string> = {
  小编推荐: '精选',
  新书推荐: '新书',
  男频热门推荐: '男频',
  女频热门推荐: '女频',
}
function shelfBadge(title: string) {
  if (BOOK_BADGE_LABELS[title]) return BOOK_BADGE_LABELS[title]
  const trimmed = title.replace(/(推荐|热门|精选|榜单|专区|频道)+$/gu, '').trim()
  return (trimmed || title).slice(0, 4)
}
function RankGlyph({ name, size = 16 }: { name: string; size?: number }) {
  const Icon = rankIcon(name)
  return <Icon size={size} aria-hidden="true" />
}
function smoothScrollBehavior(): ScrollBehavior {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 'auto' : 'smooth'
}

function BookCard({ item, badge }: { item: Collection; badge: string }) {
  const [loading, setLoading] = useState(false)
  const isPlaying = usePlayer((s) => s.playing)
  const currentTrack = usePlayer((s) => s.track)
  const playingCollectionId = usePlayingCollection((s) => s.collectionId)
  const playingTrackIds = usePlayingCollection((s) => s.trackIds)

  const isCurrent =
    playingCollectionId === item.id && currentTrack !== null && playingTrackIds.includes(currentTrack.id)
  const isThisPlaying = isCurrent && isPlaying
  const targetLink = `/audiobooks/albums/${idPath(item.id)}`

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
      const apiPath = `/audiobooks/albums/${idPath(item.id)}?page=1`
      const data = await queryClient.fetchQuery({
        queryKey: [apiPath],
        queryFn: ({ signal }) => api<BookAlbum>(apiPath, { signal }),
      })
      const tracks = data?.tracks || []
      if (!tracks.length) {
        notify('该专辑暂无章节内容', 'error')
        return
      }
      await player.playCollection(item.id, tracks)
      notify(`开始播放：${item.title}`, 'info')
    } catch (err: unknown) {
      notify(`无法加载专辑：${errorMessage(err)}`, 'error')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="book-card">
      <div className="book-art">
        <Link to={targetLink} className="book-art-link" tabIndex={-1} aria-hidden="true">
          <Cover src={item.coverUrl} />
        </Link>
        <span className="book-card-badge">{badge}</span>
        {item.playCount !== undefined && Number.isFinite(item.playCount) && item.playCount > 0 && (
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
      <Link to={targetLink} className="book-card-copy" aria-label={`打开有声专辑 ${item.title}`}>
        <strong title={item.title}>{item.title}</strong>
        <small title={item.description}>{item.description || item.category}</small>
      </Link>
    </div>
  )
}

function BookShelf({ section }: { section: BookSection }) {
  const rowRef = useRef<HTMLDivElement>(null)
  const [items, setItems] = useState(section.items)
  const [atStart, setAtStart] = useState(true)
  const [atEnd, setAtEnd] = useState(true)
  const syncEdges = useCallback(() => {
    const row = rowRef.current
    if (!row) return
    setAtStart(row.scrollLeft <= 4)
    setAtEnd(row.scrollLeft + row.clientWidth >= row.scrollWidth - 4)
  }, [])
  useEffect(() => {
    setItems(section.items)
  }, [section])
  useEffect(() => {
    const row = rowRef.current
    if (!row) return
    row.addEventListener('scroll', syncEdges, { passive: true })
    window.addEventListener('resize', syncEdges)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(syncEdges)
    observer?.observe(row)
    syncEdges()
    return () => {
      row.removeEventListener('scroll', syncEdges)
      window.removeEventListener('resize', syncEdges)
      observer?.disconnect()
    }
  }, [items, syncEdges])
  const scrollBatch = (direction: 1 | -1) => {
    const row = rowRef.current
    if (!row || typeof row.scrollBy !== 'function') return
    const amount = Math.max(Math.round(row.clientWidth * 0.85), 200)
    row.scrollBy({ left: direction * amount, behavior: smoothScrollBehavior() })
  }
  const nextBatch = () => {
    if (items.length < 2) return
    const shift = Math.min(BOOK_BATCH_SIZE, items.length - 1)
    setItems((previous) => [...previous.slice(shift), ...previous.slice(0, shift)])
    const row = rowRef.current
    if (row && typeof row.scrollTo === 'function') {
      row.scrollTo({ left: 0, behavior: smoothScrollBehavior() })
    }
  }
  const badge = shelfBadge(section.title)
  return (
    <section className="book-shelf" aria-label={section.title}>
      <SectionHeader title={section.title} subtitle={`${items.length} 部`} inline>
        <div className="book-shelf-actions" role="group" aria-label={`${section.title}翻页`}>
          <button
            type="button"
            className="book-shelf-action"
            disabled={atStart}
            onClick={() => scrollBatch(-1)}
            aria-label="向前翻一批"
            title="向前翻一批"
          >
            <ChevronLeft size={15} />
          </button>
          <button
            type="button"
            className="book-shelf-action"
            disabled={atEnd}
            onClick={() => scrollBatch(1)}
            aria-label="向后翻一批"
            title="向后翻一批"
          >
            <ChevronRight size={15} />
          </button>
          <button
            type="button"
            className="book-shelf-action"
            onClick={nextBatch}
            aria-label="换一批"
            title="换一批"
          >
            <RefreshCw size={14} />
          </button>
        </div>
      </SectionHeader>
      <div className="book-row" ref={rowRef}>
        {items.map((item) => (
          <BookCard key={item.id} item={item} badge={badge} />
        ))}
      </div>
    </section>
  )
}

export function AudioBooksPage() {
  const home = useAPI<BookHome>('/audiobooks/home')
  const sections = (home.data?.channels || []).flatMap((channel) => channel.sections)
  const ranks = home.data?.ranks || []
  return (
    <div className="audiobooks-page" aria-busy={home.isFetching}>
      <h1 className="sr-only">听书</h1>
      <QueryState pending={home.isPending} error={home.error} retry={home.refetch}>
        {home.data ? (
          <>
            {ranks.length > 0 && (
              <section className="book-ranks" aria-label="听书排行榜">
                <SectionHeader title="听书排行榜" subtitle="热播、会员与有声小说分类" inline />
                <div className="book-rank-row">
                  {ranks.map((tab) => (
                    <Link className="book-rank-card" key={tab.id} to={`/audiobooks/ranks/${idPath(tab.id)}`}>
                      <span className="book-rank-emblem" aria-hidden="true">
                        <RankGlyph name={tab.name} />
                      </span>
                      <div className="book-rank-card-content">
                        <strong>{tab.name}</strong>
                        <small>{tab.tags.map((tag) => tag.name).join(' · ')}</small>
                      </div>
                      <ChevronRight size={16} className="book-rank-card-arrow" />
                    </Link>
                  ))}
                </div>
              </section>
            )}
            {sections.length > 0 ? (
              sections.map((section) => <BookShelf key={section.id} section={section} />)
            ) : ranks.length === 0 ? (
              <EmptyState
                title="暂时没有听书内容"
                description="上游目录暂不可用，请稍后刷新重试。"
                icon={<Headphones size={28} />}
                action={
                  <button className="button secondary" onClick={() => void home.refetch()}>
                    重新加载
                  </button>
                }
              />
            ) : null}
          </>
        ) : null}
      </QueryState>
    </div>
  )
}

export function BookRankPage() {
  const { tabId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tagId = params.get('tagId') || ''
  const requested = Number(params.get('page') || 1)
  const page = Number.isSafeInteger(requested) && requested > 0 ? Math.min(MAX_CATALOG_PAGE, requested) : 1
  const result = useAPI<BookRankResult>(
    `/audiobooks/ranks/${idPath(tabId)}?tagId=${encodeURIComponent(tagId)}&page=${page}`,
  )
  const rank = result.data
  const items = rank?.items || []
  const totalPages =
    rank && rank.pageSize > 0
      ? Math.min(MAX_CATALOG_PAGE, Math.max(1, Math.ceil(rank.total / rank.pageSize)))
      : 1
  // 翻页/切换占位期间继续展示上一批内容，页码以已返回数据为准并受同一上限约束，
  // 避免旧内容配新页码或出现“第 50 / 3 页”这类越界显示。
  const viewPage = Math.min(rank?.page || page, totalPages)
  const activeTag = tagId || rank?.tagId || ''
  // 越界深链（如 page=50 但实际只有 3 页）归一到最后一页，而不是停在空页。
  useEffect(() => {
    if (!rank || rank.page <= totalPages) return
    setParams(
      {
        ...(tagId ? { tagId } : {}),
        ...(totalPages > 1 ? { page: String(totalPages) } : {}),
      },
      { replace: true },
    )
  }, [rank, totalPages, tagId, setParams])
  const changePage = (next: number) =>
    setParams({
      ...(tagId ? { tagId } : {}),
      ...(next > 1 ? { page: String(next) } : {}),
    })
  return (
    <div className="audiobooks-page" aria-busy={result.isFetching}>
      <h1 className="sr-only">听书排行榜</h1>
      <div className="collection-detail-back-bar">
        <Link className="button secondary small playlist-back-button" to="/audiobooks" aria-label="返回听书">
          <ChevronLeft size={14} />
          <span>返回听书</span>
        </Link>
      </div>
      <QueryState pending={result.isPending} error={result.error} retry={result.refetch}>
        {rank ? (
          <>
            <div className="book-rank-header">
              <div className="book-rank-title-wrap">
                <h2 className="book-rank-title">
                  <RankGlyph name={rank.tab.name} size={18} />
                  {rank.tab.name}
                </h2>
                <span className="book-rank-count-badge">
                  {result.isFetching ? '正在更新' : `共 ${rank.total} 部`}
                </span>
              </div>
              <div className="book-rank-header-actions">
                {totalPages > 1 && (
                  <nav className="browse-pagination book-rank-top-pagination" aria-label="榜单快速分页">
                    <button
                      type="button"
                      className="button secondary small"
                      disabled={viewPage <= 1 || result.isFetching}
                      onClick={() => changePage(viewPage - 1)}
                      aria-label="上一页"
                    >
                      <ChevronLeft size={14} />
                    </button>
                    <span aria-live="polite">
                      {viewPage} / {totalPages}
                    </span>
                    <button
                      type="button"
                      className="button secondary small"
                      disabled={viewPage >= totalPages || result.isFetching}
                      onClick={() => changePage(viewPage + 1)}
                      aria-label="下一页"
                    >
                      <ChevronRight size={14} />
                    </button>
                  </nav>
                )}
                <IconButton
                  label="刷新榜单"
                  disabled={result.isFetching}
                  onClick={() => void result.refetch()}
                >
                  <RefreshCw size={15} className={result.isFetching ? 'spin' : ''} />
                </IconButton>
              </div>
            </div>

            <div className="book-rank-tags" role="group" aria-label={`${rank.tab.name}子榜`}>
              <span className="book-rank-tags-label">榜单</span>
              <div className="book-rank-tags-list">
                {rank.tab.tags.map((tag) => (
                  <button
                    key={tag.id}
                    type="button"
                    className={`book-rank-tag-btn ${tag.id === activeTag ? 'selected' : ''}`}
                    aria-pressed={tag.id === activeTag}
                    onClick={() => setParams({ tagId: tag.id })}
                  >
                    <RankGlyph name={tag.name} size={14} />
                    {tag.name}
                  </button>
                ))}
              </div>
            </div>
            <QueryState
              pending={false}
              error={null}
              empty={!items.length}
              emptyTitle="这个子榜暂时没有内容"
              emptyDescription="换个榜单分类，或稍后刷新重试。"
            >
              <CollectionGrid
                items={items}
                linkFor={(item) => `/audiobooks/albums/${idPath(item.id)}`}
                kind="有声专辑"
                unit="集"
              />
            </QueryState>
            {totalPages > 1 && (
              <nav className="browse-pagination" aria-label="榜单分页">
                <button
                  type="button"
                  className="button secondary small"
                  disabled={viewPage <= 1 || result.isFetching}
                  onClick={() => changePage(viewPage - 1)}
                >
                  <ChevronLeft size={15} />
                  上一页
                </button>
                <span aria-live="polite">
                  第 {viewPage} / {totalPages} 页
                </span>
                <button
                  type="button"
                  className="button secondary small"
                  disabled={viewPage >= totalPages || result.isFetching}
                  onClick={() => changePage(viewPage + 1)}
                >
                  下一页
                  <ChevronRight size={15} />
                </button>
              </nav>
            )}
          </>
        ) : null}
      </QueryState>
    </div>
  )
}

export function AudiobookAlbumPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const requested = Number(params.get('page') || 1)
  const page = Number.isSafeInteger(requested) && requested > 0 ? Math.min(MAX_CATALOG_PAGE, requested) : 1
  const result = useAPI<BookAlbum>(`/audiobooks/albums/${idPath(id)}?page=${page}`)
  // 默认 query 行为继续保留分页旧页；只有实体 ID 变化时隐藏旧 placeholder，避免专辑内容错位。
  const staleAlbumPlaceholder = result.isPlaceholderData && result.data?.id !== id
  const album = staleAlbumPlaceholder ? undefined : result.data
  const tracks = album?.tracks || []
  const detailContentRef = useRef<HTMLDivElement>(null)
  const reservedDetailHeight = useRef(0)
  useLayoutEffect(() => {
    if (staleAlbumPlaceholder || !album) return
    const element = detailContentRef.current
    if (!element) return
    const measure = () => {
      reservedDetailHeight.current = Math.max(element.scrollHeight, element.getBoundingClientRect().height)
    }
    measure()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    observer?.observe(element)
    return () => observer?.disconnect()
  }, [album, staleAlbumPlaceholder])
  const favorites = useAPI<Collection[]>('/library/favorites/playlists')
  const [saving, setSaving] = useState(false)
  const liked = favorites.data?.some((item) => item.id === id) || false
  const toggleFavorite = async () => {
    setSaving(true)
    try {
      await send(`/library/favorites/playlists/${idPath(id)}`, liked ? 'DELETE' : 'POST')
      await Promise.all([invalidate('/library/favorites/playlists'), invalidate('/library/summary')])
      notify(liked ? '已取消收藏听书专辑' : '已收藏听书专辑', 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      setSaving(false)
    }
  }
  const totalPages =
    album && album.pageSize > 0
      ? Math.min(MAX_CATALOG_PAGE, Math.max(1, Math.ceil(album.total / album.pageSize)))
      : 1
  // 翻页占位期间保留上一页章节，页码以已返回数据为准并受同一上限约束。
  const viewPage = Math.min(album?.page || page, totalPages)
  useEffect(() => {
    if (!album || album.page <= totalPages) return
    setParams(totalPages > 1 ? { page: String(totalPages) } : {}, { replace: true })
  }, [album, totalPages, setParams])
  const changePage = (next: number) => setParams(next <= 1 ? {} : { page: String(next) })
  return (
    <div className="audiobooks-page" aria-busy={result.isFetching}>
      <div className="collection-detail-back-bar">
        <Link className="button secondary small playlist-back-button" to="/audiobooks" aria-label="返回听书">
          <ChevronLeft size={14} />
          <span>返回听书</span>
        </Link>
      </div>
      <div
        ref={detailContentRef}
        className={`audiobook-album-content ${staleAlbumPlaceholder ? 'is-switching' : ''}`}
        style={
          staleAlbumPlaceholder && reservedDetailHeight.current > 0
            ? { minHeight: reservedDetailHeight.current }
            : undefined
        }
      >
        <QueryState
          pending={result.isPending || staleAlbumPlaceholder}
          error={result.error}
          retry={result.refetch}
        >
          {album ? (
            <>
              <header className="playlist-detail-heading book-detail-heading">
                <Cover src={album.coverUrl} />
                <div>
                  <div className="playlist-source">
                    <SourceName id={album.providerId} />
                  </div>
                  <h1>{album.title}</h1>
                  {album.artist && <p className="book-artist">演播：{album.artist}</p>}
                  {album.description && <p className="book-description">{album.description}</p>}
                  <span className="muted">
                    {album.total > 0
                      ? `共 ${album.total} 集 · 第 ${viewPage} / ${totalPages} 页`
                      : `本页 ${tracks.length} 集`}
                  </span>
                  <div className="detail-actions">
                    <button
                      className="button primary"
                      disabled={!tracks.length}
                      onClick={() => player.playCollection(album.id, tracks)}
                    >
                      <Play size={15} fill="currentColor" />
                      播放本页
                    </button>
                    <button
                      className={`button secondary ${liked ? 'liked' : ''}`}
                      disabled={saving || favorites.isPending}
                      onClick={() => void toggleFavorite()}
                    >
                      <Heart size={15} fill={liked ? 'currentColor' : 'none'} />
                      {liked ? '已收藏' : '收藏专辑'}
                    </button>
                    <IconButton
                      label="刷新专辑"
                      disabled={result.isFetching}
                      onClick={() => void result.refetch()}
                    >
                      <RefreshCw size={15} className={result.isFetching ? 'spin' : ''} />
                    </IconButton>
                  </div>
                </div>
              </header>
              <QueryState
                pending={false}
                error={null}
                empty={!tracks.length}
                emptyTitle="这一页没有章节"
                emptyDescription="请返回上一页，或刷新重试。"
              >
                <TrackList tracks={tracks} collectionId={album.id} />
              </QueryState>
              {totalPages > 1 && (
                <nav className="browse-pagination" aria-label="章节分页">
                  <button
                    type="button"
                    className="button secondary small"
                    disabled={viewPage <= 1 || result.isFetching}
                    onClick={() => changePage(viewPage - 1)}
                  >
                    <ChevronLeft size={15} />
                    上一页
                  </button>
                  <span aria-live="polite">
                    第 {viewPage} / {totalPages} 页
                  </span>
                  <button
                    type="button"
                    className="button secondary small"
                    disabled={viewPage >= totalPages || result.isFetching}
                    onClick={() => changePage(viewPage + 1)}
                  >
                    下一页
                    <ChevronRight size={15} />
                  </button>
                </nav>
              )}
            </>
          ) : null}
        </QueryState>
      </div>
    </div>
  )
}

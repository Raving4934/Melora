import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Search, SearchX } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import { useAPI } from '../lib/api'
import type { SearchResult } from '../lib/types'
import {
  CatalogWarnings,
  CollectionGrid,
  Cover,
  EmptyState,
  PageHeader,
  QueryState,
  SourceFilter,
} from '../components/UI'
import { TrackList } from '../components/TrackList'
import './Browse.css'
import './Search.css'
export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') || ''
  const requestedType = params.get('type') || 'track'
  const type = ['track', 'playlist', 'artist', 'album', 'book'].includes(requestedType)
    ? requestedType
    : 'track'
  // 有声书当前只有酷沃目录支持，固定用它，避免其它平台返回能力错误与误导性提示。
  const source = type === 'book' ? 'kw' : params.get('source') || 'all'
  const requestedPage = Number(params.get('page') || 1)
  const page = Number.isSafeInteger(requestedPage) && requestedPage > 0 ? Math.min(50, requestedPage) : 1
  const searchPath = `/search?q=${encodeURIComponent(q)}&type=${encodeURIComponent(type)}&source=${encodeURIComponent(source)}&page=${page}`
  const query = useAPI<SearchResult>(searchPath, !!q.trim())
  const [lastView, setLastView] = useState({ q, type, source, page })
  useEffect(() => {
    if (query.data && !query.isPlaceholderData) setLastView({ q, type, source, page })
  }, [q, type, source, page, query.data, query.isPlaceholderData])
  // 保留上一查询的内容及其标签，不把旧歌曲误当成新类型/新平台的结果。
  const view = query.isPlaceholderData ? lastView : { q, type, source, page }
  const region = useRef<HTMLDivElement>(null)
  const regionHeight = useRef(420)
  useLayoutEffect(() => {
    if (query.isFetching || !region.current) return
    const element = region.current
    const measure = () => {
      regionHeight.current = Math.max(420, element.getBoundingClientRect().height)
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [query.data, query.isFetching])

  const update = (key: string, value: string) =>
    setParams((previous) => {
      const next = new URLSearchParams(previous)
      next.set(key, value)
      if (key !== 'page') next.delete('page')
      return next
    })
  const types = [
    ['track', '歌曲'],
    ['playlist', '歌单'],
    ['artist', '歌手'],
    ['album', '专辑'],
    ['book', '有声书'],
  ]
  return (
    <div className="search-page">
      <PageHeader title="搜索" />
      <div className="tab-bar search-type-tabs" aria-label="搜索类型">
        {types.map(([value, label]) => (
          <button
            key={value}
            className={type === value ? 'active' : ''}
            aria-pressed={type === value}
            onClick={() => update('type', value!)}
          >
            {label}
          </button>
        ))}
      </div>
      {type === 'book' ? (
        <p className="muted search-platform-bar">有声书结果来自酷沃</p>
      ) : (
        <div className="browse-platform-filter search-platform-bar">
          <SourceFilter value={source} onChange={(value) => update('source', value)} />
        </div>
      )}
      {q.trim() && <CatalogWarnings path={searchPath} retry={query.refetch} busy={query.isFetching} />}
      {!q.trim() ? (
        <EmptyState
          title="输入搜索关键词"
          description="支持搜索歌曲、歌手、专辑、歌单和有声书。"
          icon={<Search size={28} />}
        />
      ) : (
        <section className="section search-results" aria-busy={query.isFetching}>
          <div className="search-summary">
            <span>“{view.q}” 的搜索结果</span>
            <span>{query.data?.total || 0} 个结果</span>
          </div>
          <div className="search-update-status" role="status">
            {query.isPlaceholderData ? '正在查询，暂时保留上次结果…' : query.isFetching ? '正在更新…' : null}
          </div>
          <div
            ref={region}
            className="search-result-region"
            style={{ minHeight: query.isFetching ? regionHeight.current : 420 }}
          >
            <QueryState pending={query.isPending} error={query.error} retry={query.refetch}>
              {query.data?.total === 0 ? (
                <EmptyState
                  title="没有找到相关内容"
                  description="换个关键词，或试试其他音源和搜索类型。"
                  icon={<SearchX size={28} />}
                />
              ) : view.type === 'playlist' ? (
                <CollectionGrid items={query.data?.playlists || []} />
              ) : view.type === 'artist' ? (
                <div className="collection-grid artist-grid">
                  {query.data?.artists?.map((artist) => (
                    <Link
                      className="collection-item"
                      key={artist.id}
                      to={`/search?q=${encodeURIComponent(artist.name)}&type=track&source=${encodeURIComponent(view.source)}`}
                    >
                      <Cover src={artist.coverUrl} />
                      <strong>{artist.name}</strong>
                      <small>{artist.trackCount} 首作品</small>
                    </Link>
                  ))}
                </div>
              ) : view.type === 'book' ? (
                <CollectionGrid
                  items={(query.data?.albums || []).map((album) => ({
                    id: album.id,
                    providerId: 'kw',
                    title: album.title,
                    artist: album.artist,
                    description: album.artist,
                    coverUrl: album.coverUrl,
                    trackCount: album.trackCount,
                    category: '有声专辑',
                  }))}
                  linkFor={(item) => `/audiobooks/albums/${encodeURIComponent(item.id)}`}
                  kind="有声专辑"
                  unit="集"
                />
              ) : view.type === 'album' ? (
                <div className="collection-grid">
                  {query.data?.albums?.map((album) => (
                    <Link
                      className="collection-item"
                      key={album.id}
                      to={`/search?q=${encodeURIComponent(album.title)}&type=track&source=${encodeURIComponent(view.source)}`}
                    >
                      <Cover src={album.coverUrl} />
                      <strong>{album.title}</strong>
                      <small>
                        {album.artist} · {album.trackCount} 首作品
                      </small>
                    </Link>
                  ))}
                </div>
              ) : (
                <TrackList tracks={query.data?.tracks || []} />
              )}
            </QueryState>
          </div>
          {!!query.data?.pageSize && query.data.total > query.data.pageSize && (
            <nav className="search-pagination" aria-label="搜索分页">
              <button
                className="button secondary small"
                disabled={page <= 1 || query.isFetching}
                onClick={() => update('page', String(page - 1))}
              >
                上一页
              </button>
              <span>第 {page} 页</span>
              <button
                className="button secondary small"
                disabled={page * query.data.pageSize >= query.data.total || page >= 50 || query.isFetching}
                onClick={() => update('page', String(page + 1))}
              >
                下一页
              </button>
            </nav>
          )}
        </section>
      )}
    </div>
  )
}

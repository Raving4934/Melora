import { useEffect, useState } from 'react'
import { Play, Plus } from 'lucide-react'
import { Link, useSearchParams, useNavigate } from 'react-router'
import { useAPI, send, invalidate, idPath } from '../lib/api'
import type { Collection, HistoryEntry, HistoryKind, LibrarySummary, Track, UserPlaylist } from '../lib/types'
import {
  CollectionGrid,
  Cover,
  EmptyState,
  Modal,
  PageHeader,
  QueryState,
  SectionHeader,
} from '../components/UI'
import { PlaylistEditor, userPlaylistsPath } from '../components/UserPlaylists'
import { TrackList } from '../components/TrackList'
import { player } from '../stores/player'
import { notify } from '../stores/ui'
import { errorMessage } from '../lib/format'
import { isAudiobookCollection } from '../lib/history-kind'
import { pausePlayOutcomeDelivery } from '../lib/play-outcome'
const libraryTabs = new Set(['tracks', 'created', 'playlists', 'history'])
const historyKinds = new Set(['track', 'playlist', 'audiobook'])
const historyKindLabels: Record<HistoryKind, string> = {
  track: '单曲',
  playlist: '歌单',
  audiobook: '听书',
}
const historyFilters = [
  ['all', '全部'],
  ['track', '单曲'],
  ['playlist', '歌单'],
  ['audiobook', '听书专辑'],
] as const

export function LibraryPage() {
  const [params, setParams] = useSearchParams()
  const requestedTab = params.get('tab') || 'tracks'
  const tab = libraryTabs.has(requestedTab) ? requestedTab : 'tracks'
  const requestedKind = params.get('kind') || 'all'
  const historyKind = historyKinds.has(requestedKind) ? (requestedKind as HistoryKind) : null
  const historyPath = historyKind
    ? `/library/history/entries?kind=${historyKind}`
    : '/library/history/entries'
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  const summary = useAPI<LibrarySummary>('/library/summary')
  const ownPlaylists = useAPI<UserPlaylist[]>(userPlaylistsPath, tab === 'created')
  const isPlaylistTab = tab === 'playlists' || tab === 'created'
  const tracks = useAPI<Track[]>('/library/favorites/tracks', tab === 'tracks')
  const playlists = useAPI<Collection[]>('/library/favorites/playlists', tab === 'playlists')
  const history = useAPI<HistoryEntry[]>(historyPath, tab === 'history')
  useEffect(() => {
    if (requestedTab !== tab) setParams({ tab }, { replace: true })
    else if (tab === 'history' && requestedKind !== (historyKind ?? 'all')) {
      setParams(historyKind ? { tab, kind: historyKind } : { tab }, { replace: true })
    }
  }, [requestedTab, tab, requestedKind, historyKind, setParams])
  const [confirm, setConfirm] = useState(false)
  const [clearing, setClearing] = useState(false)
  const query =
    tab === 'created' ? ownPlaylists : tab === 'playlists' ? playlists : tab === 'history' ? history : tracks
  const historyEntries = tab === 'history' ? history.data || [] : []
  const currentTracks = tab === 'history' ? historyEntries.map((entry) => entry.track) : tracks.data
  const historyKindOf = new Map(historyEntries.map((entry) => [entry.track.id, entry.kind]))
  const historyBadge =
    tab === 'history' && !historyKind
      ? (track: Track) => {
          const kind = historyKindOf.get(track.id)
          return kind && kind !== 'track' ? historyKindLabels[kind] : undefined
        }
      : undefined
  const favoriteCollections = tab === 'playlists' ? playlists.data || [] : []
  const favoriteBooks = favoriteCollections.filter((item) => isAudiobookCollection(item.id))
  const favoriteLists = favoriteCollections.filter((item) => !isAudiobookCollection(item.id))
  const favoriteLink = (item: Collection) =>
    isAudiobookCollection(item.id) ? `/audiobooks/albums/${idPath(item.id)}` : `/playlists/${idPath(item.id)}`
  const clear = async () => {
    setClearing(true)
    const resumeOutcomeDelivery = pausePlayOutcomeDelivery()
    let cleared = false
    try {
      await send('/library/history', 'DELETE')
      cleared = true
      player.historyCleared()
      await Promise.all([invalidate('/library/history'), invalidate('/library/summary')])
      setConfirm(false)
      notify('播放记录已清空，下载文件不会受影响。', 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      // 成功时丢弃清空边界之前及执行期间产生的旧 outcome；失败则保留并恢复补发。
      resumeOutcomeDelivery(cleared)
      setClearing(false)
    }
  }
  const tabCounts: Record<string, number | undefined> = {
    tracks: summary.data?.favoriteTracks,
    created: summary.data?.userPlaylists,
    playlists: summary.data?.favoritePlaylists,
    history: summary.data?.history,
  }

  return (
    <>
      <PageHeader title="我的音乐">
        <button className="button primary small" onClick={() => setCreating(true)}>
          <Plus size={15} />
          新建歌单
        </button>
      </PageHeader>
      <div className="library-top-bar">
        <div className="tab-bar library-tabs" role="tablist" aria-label="我的音乐分类">
          {[
            ['tracks', '喜欢'],
            ['created', '自建'],
            ['playlists', '收藏'],
            ['history', '播放记录'],
          ].map(([value, label]) => {
            const count = tabCounts[value]
            return (
              <button
                key={value}
                id={`library-tab-${value}`}
                type="button"
                role="tab"
                aria-selected={tab === value}
                aria-controls="library-panel"
                className={tab === value ? 'active' : ''}
                onClick={() => setParams({ tab: value! })}
              >
                <span>{label}</span>
                <span className="tab-badge" data-empty={typeof count !== 'number'} aria-hidden="true">
                  {typeof count === 'number' ? count : '\u00a0'}
                </span>
              </button>
            )
          })}
        </div>
        <div className="library-actions">
          {tab === 'history' && !!history.data?.length && (
            <button className="text-button muted" onClick={() => setConfirm(true)}>
              清空记录
            </button>
          )}
          {!isPlaylistTab && !!currentTracks?.length && (
            <button
              className="button secondary small"
              onClick={() => currentTracks?.[0] && player.play(currentTracks[0], currentTracks)}
            >
              <Play size={13} fill="currentColor" />
              播放全部
            </button>
          )}
        </div>
      </div>
      {tab === 'history' && (
        <div className="history-filters" role="group" aria-label="播放记录筛选">
          {historyFilters.map(([value, label]) => {
            const count =
              value === 'all'
                ? summary.data?.history
                : value === 'track'
                  ? summary.data?.historyTracks
                  : value === 'playlist'
                    ? summary.data?.historyPlaylists
                    : summary.data?.historyAudiobooks
            const active = (historyKind ?? 'all') === value
            return (
              <button
                key={value}
                type="button"
                className={`history-filter ${active ? 'selected' : ''}`}
                aria-pressed={active}
                onClick={() =>
                  setParams(value === 'all' ? { tab: 'history' } : { tab: 'history', kind: value })
                }
              >
                {label}
                {typeof count === 'number' && <span className="history-filter-count">{count}</span>}
              </button>
            )
          })}
        </div>
      )}
      <div
        id="library-panel"
        className="library-results"
        role="tabpanel"
        aria-labelledby={`library-tab-${tab}`}
        aria-busy={query.isFetching}
      >
        <QueryState pending={query.isPending} error={query.error} retry={query.refetch}>
          {!query.data?.length ? (
            <EmptyState
              title={
                tab === 'created'
                  ? '暂无自建歌单'
                  : tab === 'history'
                    ? '暂无播放记录'
                    : tab === 'playlists'
                      ? '暂无收藏歌单'
                      : '暂无喜欢的歌曲'
              }
              description={
                tab === 'created'
                  ? '创建属于你的私享歌单，随时随地收录心爱曲目并自由规划。'
                  : tab === 'history'
                    ? '播放过的音乐会自动留存在这里。去排行榜挑首好歌开启聆听吧！'
                    : tab === 'playlists'
                      ? '尚未收藏任何歌单。前往全部歌单或排行榜，探索热门音乐宝藏。'
                      : '还没有喜欢的歌曲。在排行榜与发现页遇到动听旋律时，点击心形收藏即可加入。'
              }
              action={
                tab === 'created' ? (
                  <button className="button primary library-empty-cta" onClick={() => setCreating(true)}>
                    <Plus size={15} />
                    新建歌单
                  </button>
                ) : (
                  <Link
                    className="button primary library-empty-cta"
                    to={tab === 'playlists' ? '/playlists' : '/'}
                  >
                    {tab === 'playlists' ? '浏览歌单' : '浏览排行榜'}
                  </Link>
                )
              }
            />
          ) : tab === 'created' ? (
            <div className="collection-grid user-playlist-grid">
              {(ownPlaylists.data || []).map((playlist) => (
                <Link
                  key={playlist.id}
                  className="collection-item"
                  to={`/library/playlists/${encodeURIComponent(playlist.id)}`}
                >
                  <div className="collection-art">
                    <Cover src={playlist.coverUrl} />
                    <span className="cover-play">
                      <Play size={20} fill="currentColor" />
                    </span>
                  </div>
                  <div className="collection-copy">
                    <strong>{playlist.title}</strong>
                    <span>自建歌单 · {playlist.trackCount} 首</span>
                  </div>
                </Link>
              ))}
            </div>
          ) : tab === 'playlists' ? (
            <>
              {favoriteLists.length > 0 && (
                <section aria-label="收藏歌单">
                  <SectionHeader title="收藏歌单" subtitle={`${favoriteLists.length} 张`} inline />
                  <CollectionGrid items={favoriteLists} linkFor={favoriteLink} />
                </section>
              )}
              {favoriteBooks.length > 0 && (
                <section aria-label="收藏听书专辑">
                  <SectionHeader title="收藏听书专辑" subtitle={`${favoriteBooks.length} 部`} inline />
                  <CollectionGrid items={favoriteBooks} linkFor={favoriteLink} kind="有声专辑" unit="集" />
                </section>
              )}
            </>
          ) : (
            <TrackList tracks={currentTracks || []} badgeFor={historyBadge} />
          )}
        </QueryState>
      </div>
      {creating && (
        <PlaylistEditor
          onClose={() => setCreating(false)}
          onSaved={(playlist) => {
            setCreating(false)
            navigate(`/library/playlists/${encodeURIComponent(playlist.id)}`)
          }}
        />
      )}
      {confirm && (
        <Modal title="清空播放记录？" onClose={() => !clearing && setConfirm(false)}>
          <p className="modal-description">此操作仅移除播放历史，不影响收藏，也不会删除飞牛中的下载文件。</p>
          <div className="modal-actions">
            <button className="button secondary" disabled={clearing} onClick={() => setConfirm(false)}>
              保留记录
            </button>
            <button
              className="button danger"
              disabled={clearing}
              onClick={() => {
                void clear()
              }}
            >
              确认清空
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}

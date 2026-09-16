import { memo, useCallback, useMemo, useState, type ReactNode } from 'react'
import { Download, Heart, ListMusic, ListPlus, LoaderCircle, MoreHorizontal, Pause, Play } from 'lucide-react'
import { useAPI, send, idPath, invalidate, useCloudDeploy } from '../lib/api'
import { errorMessage, formatTime } from '../lib/format'
import type { Track } from '../lib/types'
import { player, usePlayer } from '../stores/player'
import { notify, useUI } from '../stores/ui'
import { Cover, IconButton, Modal } from './UI'

const favoritesPath = '/library/favorites/tracks'

const FavoriteControl = memo(function FavoriteControl({
  track,
  liked,
  loading,
  size = 16,
}: {
  track: Track
  liked: boolean
  loading: boolean
  size?: number
}) {
  const [pending, setPending] = useState(false)
  const toggle = async () => {
    setPending(true)
    try {
      await send(`${favoritesPath}/${idPath(track.id)}`, liked ? 'DELETE' : 'POST')
      await Promise.all([invalidate(favoritesPath), invalidate('/library/summary')])
      notify(liked ? '已取消收藏' : '已添加到喜欢的音乐', 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      setPending(false)
    }
  }
  return (
    <IconButton
      label={liked ? `取消收藏 ${track.title}` : `收藏 ${track.title}`}
      aria-pressed={liked}
      disabled={pending || loading}
      className={liked ? 'liked' : ''}
      onClick={(event) => {
        event.stopPropagation()
        void toggle()
      }}
    >
      <Heart size={size} fill={liked ? 'currentColor' : 'none'} />
    </IconButton>
  )
})

export function FavoriteButton({ track, size = 16 }: { track: Track; size?: number }) {
  const favorites = useAPI<Track[]>(favoritesPath)
  const liked = favorites.data?.some((item) => item.id === track.id) || false
  return <FavoriteControl track={track} liked={liked} loading={favorites.isPending} size={size} />
}

function TrackActionButtons({
  track,
  menu = false,
  onAction,
  cloud,
}: {
  track: Track
  menu?: boolean
  onAction?: () => void
  cloud: boolean
}) {
  const action = (callback: () => void) => {
    onAction?.()
    callback()
  }
  return (
    <>
      <button
        type="button"
        className={menu ? 'track-menu-action' : 'icon-button'}
        aria-label={`将 ${track.title} 添加到歌单`}
        onClick={() => action(() => useUI.getState().openPlaylist(track))}
      >
        <ListMusic size={18} />
        {menu && '添加到歌单'}
      </button>
      <button
        type="button"
        className={menu ? 'track-menu-action' : 'icon-button queue-add'}
        aria-label={`将 ${track.title} 加入队列`}
        onClick={() =>
          action(() => {
            usePlayer.setState((state) => ({
              queue: state.queue.some((item) => item.id === track.id) ? state.queue : [...state.queue, track],
            }))
            notify('已加入播放队列', 'success')
          })
        }
      >
        <ListPlus size={18} />
        {menu && '加入播放队列'}
      </button>
      {!cloud && track.canDownload && (
        <button
          type="button"
          className={menu ? 'track-menu-action' : 'icon-button'}
          aria-label={`保存 ${track.title} 到飞牛`}
          onClick={() => action(() => useUI.getState().openDownload(track))}
        >
          <Download size={18} />
          {menu && '保存到飞牛'}
        </button>
      )}
    </>
  )
}

const TrackRow = memo(function TrackRow({
  track,
  tracks,
  index,
  liked,
  favoritesPending,
  actions,
  badge,
  collectionId,
  onOpenActions,
  cloud,
}: {
  track: Track
  tracks: Track[]
  index: number
  liked: boolean
  favoritesPending: boolean
  actions?: (track: Track, index: number) => ReactNode
  badge?: ReactNode
  collectionId?: string
  onOpenActions: (track: Track) => void
  cloud: boolean
}) {
  // 使用稳定的数字 selector；播放进度、音量等更新不会让无关歌曲行重渲染。
  const playbackState = usePlayer((state) => {
    if (state.track?.id !== track.id) return 0
    return 1 | (state.playing ? 2 : 0) | (state.loading ? 4 : 0)
  })
  const current = (playbackState & 1) !== 0
  const playing = (playbackState & 2) !== 0
  const loading = (playbackState & 4) !== 0
  const togglePlayback = () => {
    if (current) {
      player.toggle()
      return
    }
    // 集合内的单曲播放同样保留来源上下文，历史才能按歌单/听书分类。
    if (collectionId) void player.playCollection(collectionId, tracks, track)
    else void player.play(track, tracks)
  }

  return (
    <div className={`track-row ${current ? 'is-current' : ''}`}>
      <button
        className={`track-number ${current && playing ? 'is-playing' : ''}`}
        aria-label={`${current && playing ? '暂停' : '播放'} ${track.title}`}
        onClick={togglePlayback}
      >
        <span className={index < 3 ? `top-rank rank-${index + 1}` : ''}>
          {String(index + 1).padStart(2, '0')}
        </span>
        <span className="row-play">
          {current && loading ? (
            <LoaderCircle className="spin" size={16} />
          ) : current && playing ? (
            <Pause size={15} fill="currentColor" />
          ) : (
            <Play size={15} fill="currentColor" />
          )}
        </span>
        {current && playing && (
          <span className="track-equalizer" aria-hidden="true">
            <span className="bar bar-1" />
            <span className="bar bar-2" />
            <span className="bar bar-3" />
          </span>
        )}
      </button>
      <button className="track-main" onClick={togglePlayback} title={`${track.title} — ${track.artist}`}>
        <Cover src={track.coverUrl} />
        <span>
          <strong>{track.title}</strong>
          <small>
            {track.artist}
            {badge ? <span className="track-kind-badge">{badge}</span> : null}
          </small>
        </span>
      </button>
      <span className="track-artist" title={`${track.artist} / ${track.album}`}>
        {track.artist}
        <small>{track.album}</small>
      </span>
      <span className="track-duration">{formatTime(track.duration)}</span>
      <div className={`track-actions ${actions ? 'custom-actions' : ''}`}>
        {actions ? (
          actions(track, index)
        ) : (
          <>
            <FavoriteControl track={track} liked={liked} loading={favoritesPending} />
            <span className="track-secondary-actions">
              <TrackActionButtons track={track} cloud={cloud} />
            </span>
            <IconButton
              label={`更多操作 ${track.title}`}
              className="track-more-button"
              onClick={() => onOpenActions(track)}
            >
              <MoreHorizontal size={20} />
            </IconButton>
          </>
        )}
      </div>
    </div>
  )
})

export function TrackList({
  tracks,
  compact = false,
  actions,
  collectionId,
  badgeFor,
}: {
  tracks: Track[]
  compact?: boolean
  actions?: (track: Track, index: number) => ReactNode
  collectionId?: string
  badgeFor?: (track: Track, index: number) => ReactNode
}) {
  const [actionTrack, setActionTrack] = useState<Track | null>(null)
  const cloud = useCloudDeploy()
  const favorites = useAPI<Track[]>(favoritesPath, !actions)
  const favoriteIds = useMemo(() => new Set(favorites.data?.map((track) => track.id) ?? []), [favorites.data])
  const openActions = useCallback((track: Track) => setActionTrack(track), [])

  return (
    <>
      <div className={`track-list ${compact ? 'compact' : ''}`}>
        {!compact && (
          <div className="track-labels" aria-hidden="true">
            <span>#</span>
            <span>歌曲</span>
            <span>歌手 / 专辑</span>
            <span>时长</span>
            <span />
          </div>
        )}
        {tracks.map((track, index) => (
          <TrackRow
            key={track.id}
            track={track}
            tracks={tracks}
            index={index}
            liked={favoriteIds.has(track.id)}
            favoritesPending={favorites.isPending}
            actions={actions}
            badge={badgeFor?.(track, index)}
            collectionId={collectionId}
            onOpenActions={openActions}
            cloud={cloud}
          />
        ))}
      </div>
      {actionTrack && (
        <Modal title="歌曲操作" className="track-action-dialog" onClose={() => setActionTrack(null)}>
          <div className="track-menu-heading">
            <Cover src={actionTrack.coverUrl} />
            <div>
              <strong>{actionTrack.title}</strong>
              <p>{actionTrack.artist}</p>
            </div>
          </div>
          <TrackActionButtons track={actionTrack} menu cloud={cloud} onAction={() => setActionTrack(null)} />
        </Modal>
      )}
    </>
  )
}

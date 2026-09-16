import { useState } from 'react'
import {
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  ListMusic,
  LoaderCircle,
  Pencil,
  Play,
  RefreshCw,
  Settings2,
  Trash2,
} from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { idPath, invalidate, queryClient, send, useAPI } from '../lib/api'
import type { Track, UserPlaylist } from '../lib/types'
import { errorMessage, formatTime } from '../lib/format'
import { Cover, EmptyState, IconButton, Modal, QueryState, SectionHeader } from '../components/UI'
import { TrackList } from '../components/TrackList'
import {
  cacheUserPlaylist,
  PlaylistEditor,
  userPlaylistPath,
  userPlaylistsPath,
} from '../components/UserPlaylists'
import { player } from '../stores/player'
import { notify } from '../stores/ui'

export function UserPlaylistPage() {
  const { id = '' } = useParams()
  return <PlaylistDetail key={id} id={id} />
}
function PlaylistDetail({ id }: { id: string }) {
  const path = userPlaylistPath(id)
  const query = useAPI<UserPlaylist>(path, !!id)
  const [editing, setEditing] = useState(false)
  const [managing, setManaging] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [removing, setRemoving] = useState<Track | null>(null)
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()
  const playlist = query.data
  const tracks = playlist?.tracks || []
  const updateTracks = async (method: string, suffix: string, body?: unknown) => {
    if (busy) return
    setBusy(true)
    try {
      const result = await send<UserPlaylist>(`${path}/tracks${suffix}`, method, body)
      cacheUserPlaylist(result)
      setRemoving(null)
      notify(method === 'DELETE' ? '已从歌单移除，音乐文件不受影响' : '歌曲顺序已保存', 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      setBusy(false)
    }
  }
  const move = (index: number, direction: number) => {
    const ids = tracks.map((track) => track.id)
    const destination = index + direction
    if (destination < 0 || destination >= ids.length || busy) return
    ;[ids[index], ids[destination]] = [ids[destination]!, ids[index]!]
    void updateTracks('PUT', '', { trackIds: ids })
  }
  const removePlaylist = async () => {
    if (busy) return
    setBusy(true)
    try {
      await send(path, 'DELETE')
      await queryClient.cancelQueries({ queryKey: [path] })
      queryClient.removeQueries({ queryKey: [path], exact: true })
      queryClient.setQueryData<UserPlaylist[]>([userPlaylistsPath], (current) =>
        current?.filter((item) => item.id !== id),
      )
      void Promise.all([invalidate(userPlaylistsPath), invalidate('/library/summary')])
      navigate('/library?tab=created', { replace: true })
      notify('歌单已删除，收藏和音乐文件均保留', 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
      setBusy(false)
    }
  }
  return (
    <>
      <Link className="user-playlist-back" to="/library?tab=created">
        <ArrowLeft size={15} />
        我的自建歌单
      </Link>
      <QueryState pending={query.isPending} error={query.error} retry={query.refetch}>
        {playlist && (
          <>
            <header className="playlist-detail-heading user-playlist-heading">
              <Cover src={playlist.coverUrl} />
              <div>
                <h1>{playlist.title}</h1>
                {playlist.description ? (
                  <p className="user-playlist-description">{playlist.description}</p>
                ) : null}
                <span className="muted">
                  自建歌单 · {playlist.trackCount} 首歌曲 · 总时长{' '}
                  {formatTime(tracks.reduce((sum, track) => sum + track.duration, 0))}
                </span>
                <div className="detail-actions">
                  <button
                    className="button primary"
                    disabled={!tracks.length}
                    onClick={() => player.playCollection(playlist.id, tracks)}
                  >
                    <Play size={15} fill="currentColor" />
                    播放全部
                  </button>
                  <button className="button secondary" disabled={busy} onClick={() => setEditing(true)}>
                    <Pencil size={15} />
                    编辑歌单
                  </button>
                  <IconButton label="删除这张歌单" disabled={busy} onClick={() => setDeleting(true)}>
                    <Trash2 size={17} />
                  </IconButton>
                </div>
              </div>
            </header>
            <SectionHeader title="歌曲列表" subtitle="从歌曲旁的歌单按钮添加音乐；相同歌曲不会重复加入。">
              <div className="inline">
                <IconButton
                  label="刷新自建歌单"
                  disabled={query.isFetching || busy}
                  onClick={() => {
                    void query.refetch()
                  }}
                >
                  <RefreshCw size={15} className={query.isFetching ? 'spin' : ''} />
                </IconButton>
                <button
                  className="button secondary small fixed-action"
                  aria-pressed={managing}
                  disabled={busy || !tracks.length}
                  onClick={() => setManaging(!managing)}
                >
                  <Settings2 size={14} />
                  {managing ? '完成管理' : '管理歌曲'}
                </button>
              </div>
            </SectionHeader>
            {!tracks.length ? (
              <EmptyState
                title="歌单暂无歌曲"
                description="去榜单、搜索或收藏页，点击歌曲旁的“添加到歌单”。"
                icon={<ListMusic size={27} />}
                action={
                  <Link className="button secondary" to="/">
                    浏览排行榜
                  </Link>
                }
              />
            ) : (
              <TrackList
                tracks={tracks}
                collectionId={playlist.id}
                actions={
                  managing
                    ? (track, index) => (
                        <>
                          <IconButton
                            label={`上移 ${track.title}`}
                            disabled={busy || index === 0}
                            onClick={() => move(index, -1)}
                          >
                            <ArrowUp size={16} />
                          </IconButton>
                          <IconButton
                            label={`下移 ${track.title}`}
                            disabled={busy || index === tracks.length - 1}
                            onClick={() => move(index, 1)}
                          >
                            <ArrowDown size={16} />
                          </IconButton>
                          <IconButton
                            label={`从歌单移除 ${track.title}`}
                            disabled={busy}
                            onClick={() => setRemoving(track)}
                          >
                            <Trash2 size={16} />
                          </IconButton>
                        </>
                      )
                    : undefined
                }
              />
            )}
          </>
        )}
      </QueryState>
      {editing && playlist && (
        <PlaylistEditor
          playlist={playlist}
          onClose={() => setEditing(false)}
          onSaved={() => setEditing(false)}
        />
      )}
      {deleting && (
        <Modal title="删除这张歌单？" onClose={() => !busy && setDeleting(false)}>
          <p className="modal-description">
            将删除「{playlist?.title}」及其中的歌曲关联。不会删除音乐文件，也不会取消歌曲收藏。
          </p>
          <div className="modal-actions">
            <button className="button secondary" disabled={busy} onClick={() => setDeleting(false)}>
              保留歌单
            </button>
            <button
              className="button danger fixed-action"
              disabled={busy}
              onClick={() => {
                void removePlaylist()
              }}
            >
              {busy && <LoaderCircle className="spin" size={15} />}确认删除
            </button>
          </div>
        </Modal>
      )}
      {removing && (
        <Modal title="从歌单移除这首歌？" onClose={() => !busy && setRemoving(null)}>
          <p className="modal-description">
            仅从这张歌单移除「{removing.title}」，歌曲收藏与已下载文件不受影响。
          </p>
          <div className="modal-actions">
            <button className="button secondary" disabled={busy} onClick={() => setRemoving(null)}>
              保留歌曲
            </button>
            <button
              className="button danger fixed-action"
              disabled={busy}
              onClick={() => {
                void updateTracks('DELETE', `/${idPath(removing.id)}`)
              }}
            >
              {busy && <LoaderCircle className="spin" size={15} />}确认移除
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}

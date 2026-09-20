import { useId, useState, type FormEvent } from 'react'
import { Check, ListMusic, LoaderCircle, Plus } from 'lucide-react'
import { idPath, invalidate, queryClient, send, useAPI } from '../lib/api'
import { errorMessage } from '../lib/format'
import type { Track, UserPlaylist } from '../lib/types'
import { notify, useUI } from '../stores/ui'
import { Cover, EmptyState, Modal, QueryState } from './UI'

export const userPlaylistsPath = '/library/playlists'
export const userPlaylistPath = (id: string) => `${userPlaylistsPath}/${idPath(id)}`
export function cacheUserPlaylist(playlist: UserPlaylist) {
  queryClient.setQueryData([userPlaylistPath(playlist.id)], playlist)
  const { tracks: _tracks, ...summary } = playlist
  queryClient.setQueryData<UserPlaylist[]>([userPlaylistsPath], (current) => {
    if (!current) return [summary]
    return current.some((item) => item.id === playlist.id)
      ? current.map((item) => (item.id === playlist.id ? summary : item))
      : [summary, ...current]
  })
  void invalidate(userPlaylistsPath)
  void invalidate('/library/summary')
}

export function PlaylistFields({
  title,
  description,
  disabled,
  onTitle,
  onDescription,
}: {
  title: string
  description: string
  disabled: boolean
  onTitle: (value: string) => void
  onDescription: (value: string) => void
}) {
  const id = useId()
  return (
    <div className="playlist-fields">
      <label htmlFor={`${id}-title`}>歌单名称</label>
      <input
        id={`${id}-title`}
        value={title}
        maxLength={80}
        required
        autoFocus
        autoComplete="off"
        placeholder="输入歌单名称"
        disabled={disabled}
        onChange={(event) => onTitle(event.target.value)}
      />
      <div className="playlist-field-label">
        <label htmlFor={`${id}-description`}>
          歌单简介 <span>选填</span>
        </label>
        <span>{description.length}/300</span>
      </div>
      <textarea
        id={`${id}-description`}
        value={description}
        maxLength={300}
        rows={3}
        placeholder="输入歌单简介"
        disabled={disabled}
        onChange={(event) => onDescription(event.target.value)}
      />
    </div>
  )
}

export function PlaylistEditor({
  playlist,
  onClose,
  onSaved,
}: {
  playlist?: UserPlaylist
  onClose: () => void
  onSaved: (playlist: UserPlaylist) => void
}) {
  const [title, setTitle] = useState(playlist?.title || '')
  const [description, setDescription] = useState(playlist?.description || '')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (pending || !title.trim()) return
    setPending(true)
    setError('')
    try {
      const result = await send<UserPlaylist>(
        playlist ? userPlaylistPath(playlist.id) : userPlaylistsPath,
        playlist ? 'PUT' : 'POST',
        { title: title.trim(), description: description.trim() },
      )
      cacheUserPlaylist(result)
      notify(playlist ? '歌单已更新' : '新歌单已创建', 'success')
      onSaved(result)
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setPending(false)
    }
  }
  return (
    <Modal title={playlist ? '编辑歌单' : '新建歌单'} onClose={() => !pending && onClose()}>
      <form
        onSubmit={(event) => {
          void save(event)
        }}
      >
        <p className="modal-description">歌单保存在这台 NAS，当前由管理员共享。删除歌单不会删除音乐文件。</p>
        <PlaylistFields
          title={title}
          description={description}
          disabled={pending}
          onTitle={setTitle}
          onDescription={setDescription}
        />
        <p className="form-feedback" role={error ? 'alert' : undefined}>
          {error || '\u00a0'}
        </p>
        <div className="modal-actions">
          <button type="button" className="button secondary" disabled={pending} onClick={onClose}>
            取消
          </button>
          <button type="submit" className="button primary fixed-action" disabled={pending || !title.trim()}>
            {pending ? <LoaderCircle size={15} className="spin" /> : <Check size={15} />}
            {playlist ? '保存修改' : '创建歌单'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

export function AddToPlaylistDialog() {
  const track = useUI((state) => state.playlistTrack)
  return track ? <AddToPlaylist key={track.id} track={track} /> : null
}
function AddToPlaylist({ track }: { track: Track }) {
  const playlists = useAPI<UserPlaylist[]>(userPlaylistsPath)
  const [creating, setCreating] = useState(false)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [pending, setPending] = useState('')
  const [error, setError] = useState('')
  const close = () => useUI.getState().openPlaylist(null)
  const add = async (playlist: UserPlaylist) => {
    setPending(playlist.id)
    setError('')
    try {
      const result = await send<UserPlaylist>(`${userPlaylistPath(playlist.id)}/tracks`, 'POST', {
        trackId: track.id,
      })
      cacheUserPlaylist(result)
      notify(`已加入「${playlist.title}」`, 'success')
      close()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setPending('')
    }
  }
  const create = async (event: FormEvent) => {
    event.preventDefault()
    if (pending || !title.trim()) return
    setPending('create')
    setError('')
    let created: UserPlaylist | undefined
    try {
      created = await send<UserPlaylist>(userPlaylistsPath, 'POST', {
        title: title.trim(),
        description: description.trim(),
      })
      cacheUserPlaylist(created)
      // 两个接口分别提交。添加失败仍保留刚创建的歌单，转回列表避免再次创建同名歌单。
      setCreating(false)
      const result = await send<UserPlaylist>(`${userPlaylistPath(created.id)}/tracks`, 'POST', {
        trackId: track.id,
      })
      cacheUserPlaylist(result)
      notify(`已创建「${created.title}」并加入歌曲`, 'success')
      close()
    } catch (cause) {
      setError(
        created ? `歌单已创建，但歌曲未加入：${errorMessage(cause)} 请在列表中重试。` : errorMessage(cause),
      )
    } finally {
      setPending('')
    }
  }
  return (
    <Modal title="添加到自建歌单" onClose={() => !pending && close()}>
      <div className="playlist-track-preview">
        <Cover src={track.coverUrl} />
        <div>
          <strong>{track.title}</strong>
          <span>{track.artist}</span>
        </div>
      </div>
      {creating ? (
        <form
          onSubmit={(event) => {
            void create(event)
          }}
        >
          <PlaylistFields
            title={title}
            description={description}
            disabled={!!pending}
            onTitle={setTitle}
            onDescription={setDescription}
          />
          <p className="form-feedback" role={error ? 'alert' : undefined}>
            {error || '\u00a0'}
          </p>
          <div className="modal-actions">
            <button
              type="button"
              className="button secondary"
              disabled={!!pending}
              onClick={() => {
                setCreating(false)
                setError('')
              }}
            >
              返回歌单列表
            </button>
            <button
              type="submit"
              className="button primary fixed-action"
              disabled={!!pending || !title.trim()}
            >
              {pending ? <LoaderCircle className="spin" size={15} /> : <Plus size={15} />}创建并添加
            </button>
          </div>
        </form>
      ) : (
        <>
          <button
            className="playlist-create-option"
            disabled={!!pending}
            onClick={() => {
              setCreating(true)
              setError('')
            }}
          >
            <Plus size={18} />
            <span>新建一张歌单</span>
          </button>
          <div className="playlist-picker" aria-busy={!!pending}>
            <QueryState pending={playlists.isPending} error={playlists.error} retry={playlists.refetch}>
              {!playlists.data?.length ? (
                <EmptyState
                  title="还没有自建歌单"
                  description="新建歌单后添加这首歌曲。"
                  icon={<ListMusic size={25} />}
                />
              ) : (
                playlists.data.map((playlist) => (
                  <button
                    className="playlist-picker-item"
                    key={playlist.id}
                    disabled={!!pending}
                    aria-label={`加入歌单 ${playlist.title}`}
                    onClick={() => {
                      void add(playlist)
                    }}
                  >
                    <Cover src={playlist.coverUrl} />
                    <span>
                      <strong>{playlist.title}</strong>
                      <small>{playlist.trackCount} 首歌曲</small>
                    </span>
                    {pending === playlist.id ? (
                      <LoaderCircle className="spin" size={17} />
                    ) : (
                      <Plus size={17} />
                    )}
                  </button>
                ))
              )}
            </QueryState>
          </div>
          <p className="form-feedback" role={error ? 'alert' : undefined}>
            {error || '\u00a0'}
          </p>
        </>
      )}
    </Modal>
  )
}

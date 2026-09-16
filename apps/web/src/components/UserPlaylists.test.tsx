import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../lib/api'
import type { UserPlaylist } from '../lib/types'
import { cacheUserPlaylist, PlaylistFields, userPlaylistPath, userPlaylistsPath } from './UserPlaylists'

const playlist = (id: string): UserPlaylist => ({
  id,
  providerId: 'local',
  title: id,
  description: '',
  coverUrl: '',
  trackCount: 0,
  createdAt: '2026-09-07T00:00:00Z',
  updatedAt: '2026-09-07T00:00:00Z',
})
afterEach(() => {
  queryClient.clear()
  cleanup()
})
describe('自建歌单缓存与表单稳定性', () => {
  it('编辑已有歌单保持卡片原顺序，不先跳到第一位再回弹', () => {
    const first = playlist('local:first')
    const second = playlist('local:second')
    queryClient.setQueryData([userPlaylistsPath], [first, second])
    cacheUserPlaylist({ ...second, title: '新标题' })
    expect(queryClient.getQueryData<UserPlaylist[]>([userPlaylistsPath])?.map((item) => item.id)).toEqual([
      first.id,
      second.id,
    ])
    expect(queryClient.getQueryData<UserPlaylist[]>([userPlaylistsPath])?.[1]?.title).toBe('新标题')
    expect(second.title).toBe('local:second')
  })
  it('新歌单插入顶部，摘要不复制全部歌曲而详情保留空集合', () => {
    const existing = playlist('local:old')
    const created = { ...playlist('local:new'), tracks: [] }
    queryClient.setQueryData([userPlaylistsPath], [existing])
    cacheUserPlaylist(created)
    expect(queryClient.getQueryData<UserPlaylist[]>([userPlaylistsPath])?.[0]).not.toHaveProperty('tracks')
    expect(queryClient.getQueryData([userPlaylistPath(created.id)])).toEqual(created)
    expect(queryClient.getQueryData<UserPlaylist[]>([userPlaylistsPath])).toHaveLength(2)
  })
  it('未访问过歌单页时仍能更新摘要，ID按路径段编码', () => {
    const created = playlist('local:example')
    cacheUserPlaylist(created)
    expect(queryClient.getQueryData([userPlaylistsPath])).toEqual([created])
    expect(userPlaylistPath('local:a/b')).toBe('/library/playlists/local%3Aa%2Fb')
  })
  it('表单字段有标签、长度上限及受控输入，不使用placeholder代替标签', () => {
    const onTitle = vi.fn()
    const onDescription = vi.fn()
    render(
      <PlaylistFields
        title="我的歌单"
        description="一段描述"
        disabled={false}
        onTitle={onTitle}
        onDescription={onDescription}
      />,
    )
    expect(screen.getByLabelText('歌单名称', { exact: true })).toHaveAttribute('maxlength', '80')
    expect(screen.getByLabelText(/歌单简介/)).toHaveAttribute('maxlength', '300')
    fireEvent.change(screen.getByLabelText('歌单名称'), { target: { value: '新名称' } })
    expect(onTitle).toHaveBeenCalledWith('新名称')
    fireEvent.change(screen.getByLabelText(/歌单简介/), { target: { value: '新描述' } })
    expect(onDescription).toHaveBeenCalledWith('新描述')
  })
  it('保存中禁用两个输入，保持值和相同DOM控件', () => {
    const props = { title: '草稿', description: '', onTitle: vi.fn(), onDescription: vi.fn() }
    const { rerender } = render(<PlaylistFields {...props} disabled={false} />)
    const input = screen.getByLabelText('歌单名称')
    rerender(<PlaylistFields {...props} disabled />)
    expect(screen.getByLabelText('歌单名称')).toBe(input)
    expect(input).toBeDisabled()
    expect(input).toHaveValue('草稿')
    expect(screen.getByLabelText(/歌单简介/)).toBeDisabled()
  })
})

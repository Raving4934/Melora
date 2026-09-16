import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Track } from '../lib/types'
import { player, usePlayer } from '../stores/player'
import { useUI } from '../stores/ui'
import { TrackList } from './TrackList'

const mocks = vi.hoisted(() => ({
  useAPI: vi.fn(),
  send: vi.fn(),
  invalidate: vi.fn(),
  coverRender: vi.fn(),
  useCloudDeploy: vi.fn(() => false),
}))

vi.mock('../lib/api', () => ({
  useAPI: mocks.useAPI,
  send: mocks.send,
  invalidate: mocks.invalidate,
  idPath: (id: string) => encodeURIComponent(id),
  useCloudDeploy: mocks.useCloudDeploy,
}))

vi.mock('./UI', async (importOriginal) => {
  const original = await importOriginal<typeof import('./UI')>()
  return {
    ...original,
    Cover: ({ src }: { src?: string }) => {
      mocks.coverRender(src)
      return <span className="cover" data-cover={src} />
    },
  }
})

const tracks: Track[] = [
  {
    id: 'demo:first',
    providerId: 'demo',
    title: '第一首',
    artist: '歌手甲',
    album: '专辑甲',
    duration: 61,
    coverUrl: '/covers/first.svg',
    qualities: ['standard'],
    canDownload: true,
  },
  {
    id: 'demo:second',
    providerId: 'demo',
    title: '第二首',
    artist: '歌手乙',
    album: '专辑乙',
    duration: 122,
    coverUrl: '/covers/second.svg',
    qualities: ['standard'],
    canDownload: false,
  },
]

beforeEach(() => {
  vi.clearAllMocks()
  mocks.useAPI.mockReturnValue({ data: [tracks[0]], isPending: false })
  mocks.send.mockResolvedValue(undefined)
  mocks.invalidate.mockResolvedValue(undefined)
  mocks.useCloudDeploy.mockReturnValue(false)
  usePlayer.setState({ ...usePlayer.getInitialState(), track: null, queue: [] })
  useUI.setState({ drawer: null, toast: null })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = false
    },
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('TrackList 性能与行为契约', () => {
  it('整张列表只读取一次部署模式，不让每个歌曲操作按钮建立 session 订阅', () => {
    render(<TrackList tracks={tracks} />)

    expect(mocks.useCloudDeploy).toHaveBeenCalledOnce()
  })

  it('整张列表只订阅一次收藏数据，并从共享集合派生每行状态', () => {
    const view = render(<TrackList tracks={tracks} />)

    expect(mocks.useAPI).toHaveBeenCalledOnce()
    expect(mocks.useAPI).toHaveBeenCalledWith('/library/favorites/tracks', true)
    expect(screen.getByRole('button', { name: '取消收藏 第一首' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '收藏 第二首' })).toHaveAttribute('aria-pressed', 'false')
    expect(view.container.querySelector('.track-list')).not.toHaveClass('compact')
    expect(view.container.querySelectorAll('.track-row')).toHaveLength(2)
    expect(view.container.querySelectorAll('.track-labels')).toHaveLength(1)
    expect(view.container.querySelectorAll('.track-actions')).toHaveLength(2)
    expect(screen.getByTitle('第一首 — 歌手甲')).toContainElement(
      view.container.querySelector('[data-cover="/covers/first.svg"]'),
    )
  })

  it('播放器无关状态不重渲染歌曲行，切歌时只更新新旧当前行', () => {
    render(<TrackList tracks={tracks} />)
    mocks.coverRender.mockClear()

    act(() => usePlayer.setState({ position: 30, volume: 0.5 }))
    expect(mocks.coverRender).not.toHaveBeenCalled()

    act(() => usePlayer.setState({ track: tracks[0], playing: true, loading: false }))
    expect(mocks.coverRender.mock.calls).toEqual([['/covers/first.svg']])
    expect(screen.getByRole('button', { name: '暂停 第一首' })).toBeVisible()
    expect(screen.getByRole('button', { name: '播放 第二首' })).toBeVisible()

    mocks.coverRender.mockClear()
    act(() => usePlayer.setState({ track: tracks[1], playing: true }))
    expect(mocks.coverRender.mock.calls).toEqual([['/covers/first.svg'], ['/covers/second.svg']])
    expect(screen.getByRole('button', { name: '播放 第一首' })).toBeVisible()
    expect(screen.getByRole('button', { name: '暂停 第二首' })).toBeVisible()
  })

  it('保持播放、收藏、队列和更多操作行为', async () => {
    const play = vi.spyOn(player, 'play').mockImplementation(() => Promise.resolve())
    const toggle = vi.spyOn(player, 'toggle').mockImplementation(() => {})
    render(<TrackList tracks={tracks} />)

    fireEvent.click(screen.getByRole('button', { name: '播放 第一首' }))
    expect(play).toHaveBeenCalledWith(tracks[0], tracks)

    act(() => usePlayer.setState({ track: tracks[0], playing: true }))
    fireEvent.click(screen.getByRole('button', { name: '暂停 第一首' }))
    expect(toggle).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('button', { name: '取消收藏 第一首' }))
    await waitFor(() =>
      expect(mocks.send).toHaveBeenCalledWith('/library/favorites/tracks/demo%3Afirst', 'DELETE'),
    )
    expect(mocks.invalidate).toHaveBeenCalledWith('/library/favorites/tracks')

    fireEvent.click(screen.getByRole('button', { name: '将 第二首 加入队列' }))
    expect(usePlayer.getState().queue).toEqual([tracks[1]])

    fireEvent.click(screen.getByRole('button', { name: '更多操作 第一首' }))
    const dialog = screen.getByRole('dialog', { name: '歌曲操作' })
    expect(dialog).toHaveClass('track-action-dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '将 第一首 添加到歌单' }))
    expect(useUI.getState().playlistTrack).toEqual(tracks[0])
    expect(screen.queryByRole('dialog', { name: '歌曲操作' })).not.toBeInTheDocument()
  })

  it('cloud 部署隐藏下载入口，其余操作不受影响', () => {
    mocks.useCloudDeploy.mockReturnValue(true)
    render(<TrackList tracks={tracks} />)

    expect(screen.queryByRole('button', { name: '保存 第一首 到飞牛' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '将 第一首 添加到歌单' })).toBeVisible()
    expect(screen.getByRole('button', { name: '将 第一首 加入队列' })).toBeVisible()
  })

  it('自定义 actions 保持 DOM 契约且不启用收藏请求', () => {
    const actions = vi.fn((track: Track) => <button type="button">移除 {track.title}</button>)
    const view = render(<TrackList tracks={tracks} compact actions={actions} />)

    expect(mocks.useAPI).toHaveBeenCalledOnce()
    expect(mocks.useAPI).toHaveBeenCalledWith('/library/favorites/tracks', false)
    expect(view.container.querySelector('.track-list')).toHaveClass('compact')
    expect(view.container.querySelector('.track-labels')).toBeNull()
    expect(view.container.querySelectorAll('.track-actions.custom-actions')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '收藏 第一首' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '移除 第一首' })).toBeVisible()
    expect(actions).toHaveBeenCalledTimes(2)
  })
})

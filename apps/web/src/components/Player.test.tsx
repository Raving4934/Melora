import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MiniPlayer, NowPlaying, PlayerDrawer } from './Player'
import { player, usePlayer } from '../stores/player'
import { useUI } from '../stores/ui'
import { queryClient } from '../lib/api'
import { qualityName } from '../lib/format'
import * as coverPalette from '../lib/cover-palette'
import { LYRIC_FONT_STORAGE_KEY, resetLyricFont, setLyricFont } from '../lib/lyric-font'
import type { Track } from '../lib/types'

const track: Track = {
  id: 'fixture:player',
  providerId: 'demo',
  title: '播放器测试曲目',
  artist: '测试演奏者',
  album: '测试专辑',
  duration: 120,
  coverUrl: '/covers/coast.svg',
  qualities: ['standard', '128k', '320k'],
  canDownload: true,
}
function mount(path = '/now-playing') {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<MiniPlayer />} />
          <Route path="/now-playing" element={<NowPlaying />} />
        </Routes>
        <PlayerDrawer />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
beforeEach(() => {
  resetLyricFont()
  queryClient.clear()
  usePlayer.setState({
    ...usePlayer.getInitialState(),
    track,
    queue: [track],
    duration: 120,
    position: 18,
    availableQualities: ['standard', '128k', '320k'],
  })
  useUI.setState({ drawer: null, toast: null })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      const data = path.includes('/lyrics')
        ? {
            source: '测试歌词',
            lines: [
              { time: 0, text: '第一行测试歌词' },
              { time: 10, text: '第二行测试歌词' },
              { time: 20, text: '第三行测试歌词' },
            ],
          }
        : path.includes('/settings')
          ? { defaultQuality: 'standard', autoSwitchSource: true }
          : []
      return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } })
    }),
  )
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
  queryClient.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('底栏使用共享短时间格式，没有独立大进度行和直连文案', () => {
  const view = mount('/')
  expect(view.container.querySelector('.player-time')).toHaveTextContent('00:18/02:00')
  expect(view.container.querySelector('.player-mini-progress')).toBeTruthy()
  expect(view.container.querySelector('.player-time-divider')).toHaveTextContent('/')
  expect(view.container.querySelector('.player-mini')).toHaveClass('mini-player')
  expect(view.container.querySelector('.player-mini-track')).toHaveClass('mini-track')
  expect(view.container.querySelector('.playback-controls')).toBeNull()
  expect(view.container.querySelector('.mini-center, .mini-seek, .direct-status')).toBeNull()
  expect(screen.queryByText(/直连|音频不经过/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '歌词' })).not.toBeInTheDocument()
})

it('封面真实点击切完整歌词，时间更新不重建封面节点', async () => {
  const view = mount()
  const cover = screen.getByRole('button', { name: '点击封面显示完整歌词' })
  const originalImage = cover.querySelector('img')
  await waitFor(() =>
    expect(view.container.querySelector('.player-lyric-line.current')).toHaveTextContent('第二行测试歌词'),
  )
  act(() => usePlayer.setState({ position: 21 }))
  expect(cover.querySelector('img')).toBe(originalImage)
  expect(view.container.querySelector('.player-lyric-line.current')).toHaveTextContent('第三行测试歌词')
  fireEvent.click(cover)
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-view', 'lyrics')
  fireEvent.click(screen.getByRole('button', { name: '显示封面' }))
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-view', 'cover')
})

it('移动预览展示多行歌词，完整歌词按钮调用真实seek', async () => {
  const seek = vi.spyOn(player, 'seek').mockImplementation(() => {})
  const view = mount()
  await waitFor(() =>
    expect(view.container.querySelector('.player-compact-lyrics')).toHaveTextContent('第二行测试歌词'),
  )
  expect(view.container.querySelectorAll('.player-compact-lyrics > span')).toHaveLength(9)
  const panel = screen.getByRole('region', { name: '歌词' })
  fireEvent.click(within(panel).getByRole('button', { name: '第三行测试歌词' }))
  expect(seek).toHaveBeenCalledWith(20)
})

it('倍速与音质控件复用共享标签并调用引擎，不显示未声明音质', async () => {
  const setRate = vi
    .spyOn(player, 'setRate')
    .mockImplementation((value) => usePlayer.setState({ playbackRate: value }))
  const setQuality = vi.spyOn(player, 'setQuality').mockResolvedValue()
  mount()
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  const dialog = screen.getByRole('dialog', { name: '播放设置' })
  fireEvent.change(within(dialog).getByRole('combobox', { name: '播放速度' }), { target: { value: '1.5' } })
  expect(setRate).toHaveBeenCalledWith(1.5)
  expect(within(dialog).getByRole('combobox', { name: '播放速度' })).toHaveValue('1.5')
  const select = within(dialog).getByRole('combobox', { name: '播放音质' })
  expect(within(select).getByRole('option', { name: qualityName('320k') })).toBeInTheDocument()
  expect(within(select).queryByRole('option', { name: /FLAC/ })).not.toBeInTheDocument()
  fireEvent.change(select, { target: { value: '320k' } })
  expect(setQuality).toHaveBeenCalledWith('320k')
})

it('下载只有图标，仍打开原下载入口', () => {
  mount()
  const download = screen.getByRole('button', { name: '下载当前歌曲' })
  expect(download).toHaveTextContent('')
  fireEvent.click(download)
  expect(useUI.getState().downloadTrack).toEqual(track)
})

it('队列保留真实操作且没有直连提示', async () => {
  mount()
  fireEvent.click(screen.getByRole('button', { name: '播放队列' }))
  const queue = screen.getByRole('dialog', { name: '播放队列 · 1' })
  expect(queue).toHaveTextContent(track.title)
  expect(queue).not.toHaveTextContent(/直连/)
  fireEvent.click(within(queue).getByRole('button', { name: '清空待播' }))
  expect(usePlayer.getState().queue).toEqual([track])
})

it('旧歌词入口迁移到沉浸视图，不渲染歌词侧栏', async () => {
  const view = mount('/')
  act(() => useUI.getState().setDrawer('lyrics'))
  await waitFor(() =>
    expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-view', 'lyrics'),
  )
  expect(screen.queryByRole('dialog', { name: '歌词' })).not.toBeInTheDocument()
})

it('空态、媒体错误和未授权下载均有明确状态', () => {
  usePlayer.setState({ error: '源暂不可用', track: { ...track, canDownload: false } })
  const view = mount()
  expect(screen.getByRole('alert')).toHaveTextContent('源暂不可用')
  expect(screen.getByRole('button', { name: '下载当前歌曲' })).toBeDisabled()
  act(() => usePlayer.setState({ track: null, error: null }))
  expect(view.container.querySelector('.player-empty')).toHaveTextContent('请选择歌曲')
})

it('cloud 部署不显示下载入口', () => {
  queryClient.setQueryData(['/auth/session'], {
    authenticated: true,
    required: false,
    deployMode: 'cloud',
  })
  mount()
  expect(screen.queryByRole('button', { name: '下载当前歌曲' })).not.toBeInTheDocument()
})

it('全屏只有一个主设置入口，倍速和音质是直接选择器，不打开重复弹窗', () => {
  const setRate = vi
    .spyOn(player, 'setRate')
    .mockImplementation((value) => usePlayer.setState({ playbackRate: value }))
  const setQuality = vi.spyOn(player, 'setQuality').mockResolvedValue()
  mount()
  expect(screen.getAllByRole('button', { name: '播放设置' })).toHaveLength(1)
  expect(screen.queryByRole('button', { name: '更多播放选项' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /打开设置/ })).not.toBeInTheDocument()
  fireEvent.change(screen.getByRole('combobox', { name: '播放速度' }), { target: { value: '1.5' } })
  expect(setRate).toHaveBeenCalledWith(1.5)
  expect(screen.getByRole('combobox', { name: '播放速度' })).toHaveValue('1.5')
  const quality = screen.getByRole('combobox', { name: '播放音质' })
  expect(within(quality).queryByRole('option', { name: /FLAC/ })).not.toBeInTheDocument()
  fireEvent.change(quality, { target: { value: '320k' } })
  expect(setQuality).toHaveBeenCalledWith('320k')
  expect(screen.queryByRole('dialog', { name: '播放设置' })).not.toBeInTheDocument()
  act(() => usePlayer.setState({ loading: true }))
  expect(quality).toBeDisabled()
  expect(screen.getByRole('combobox', { name: '播放速度' })).toBeEnabled()
})

it('迷你栏的上一首、下一首、取消加载及暂停保留真实 transport 操作', () => {
  const next = vi.spyOn(player, 'next').mockImplementation(() => {})
  const previous = vi.spyOn(player, 'previous').mockImplementation(() => {})
  const toggle = vi.spyOn(player, 'toggle').mockImplementation(() => {})
  mount('/')
  fireEvent.click(screen.getByRole('button', { name: '下一首' }))
  fireEvent.click(screen.getByRole('button', { name: '上一首' }))
  expect(next).toHaveBeenCalledOnce()
  expect(previous).toHaveBeenCalledOnce()
  act(() => usePlayer.setState({ loading: true }))
  fireEvent.click(screen.getByRole('button', { name: '取消加载' }))
  act(() => usePlayer.setState({ loading: false, playing: true }))
  fireEvent.click(screen.getByRole('button', { name: '暂停' }))
  expect(toggle).toHaveBeenCalledTimes(2)
})

it('空态、加载和时间更新不卸载底栏、进度输入与主按钮，空态仅禁用跳曲与进度', () => {
  usePlayer.setState({ track: null, duration: 0, position: 0 })
  const view = mount('/')
  const footer = screen.getByRole('contentinfo', { name: '音乐播放器' })
  const seek = screen.getByRole('slider', { name: '播放进度' })
  const main = screen.getByRole('button', { name: '播放' })
  expect(seek).toBeDisabled()
  expect(screen.getByRole('button', { name: '下一首' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '上一首' })).toBeDisabled()
  act(() => usePlayer.setState({ track, duration: 120, position: 18, loading: true }))
  expect(screen.getByRole('button', { name: '取消加载' })).toBe(main)
  expect(screen.getByRole('slider', { name: '播放进度' })).toBe(seek)
  expect(seek).toBeEnabled()
  act(() => usePlayer.setState({ position: 19, loading: false, playing: true }))
  expect(screen.getByRole('button', { name: '暂停' })).toBe(main)
  expect(screen.getByRole('contentinfo', { name: '音乐播放器' })).toBe(footer)
  expect(view.container.querySelector('.player-time')).toHaveAccessibleName('播放时间 00:19 / 02:00')
})

it.each([
  [0, 30, '0', '0%', true],
  [Number.NaN, 30, '0', '0%', true],
  [Number.POSITIVE_INFINITY, 30, '0', '0%', true],
  [120, -5, '0', '0%', false],
  [120, 180, '120', '100%', false],
  [120, Number.NaN, '0', '0%', false],
  [120, 30, '30', '25%', false],
])('进度防御非法数值：duration=%s position=%s', (duration, position, value, progress, disabled) => {
  usePlayer.setState({ duration, position })
  mount('/')
  const input = screen.getByRole('slider', { name: '播放进度' })
  expect(input).toHaveValue(value)
  expect(input.style.getPropertyValue('--progress')).toBe(progress)
  if (disabled) expect(input).toBeDisabled()
  else expect(input).toBeEnabled()
})

it('拖动迷你进度仅调用 seek，不切歌、不强制播放，也不重置倍速', () => {
  const seek = vi.spyOn(player, 'seek').mockImplementation(() => {})
  const play = vi.spyOn(player, 'play').mockResolvedValue()
  const toggle = vi.spyOn(player, 'toggle').mockImplementation(() => {})
  usePlayer.setState({ playbackRate: 1.5, playing: false })
  mount('/')
  fireEvent.change(screen.getByRole('slider', { name: '播放进度' }), { target: { value: '42' } })
  expect(seek).toHaveBeenCalledWith(42)
  expect(play).not.toHaveBeenCalled()
  expect(toggle).not.toHaveBeenCalled()
  expect(usePlayer.getState()).toMatchObject({ playbackRate: 1.5, playing: false })
})

it('桌面迷你栏保留单个播放设置弹窗，关闭不卸载入口', () => {
  mount('/')
  const trigger = screen.getByRole('button', { name: '播放设置' })
  fireEvent.click(trigger)
  const dialog = screen.getByRole('dialog', { name: '播放设置' })
  expect(screen.getAllByRole('dialog', { name: '播放设置' })).toHaveLength(1)
  expect(within(dialog).getByRole('slider', { name: '音量' })).toBeEnabled()
  fireEvent.click(within(dialog).getByRole('button', { name: '关闭' }))
  expect(screen.queryByRole('dialog', { name: '播放设置' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '播放设置' })).toBe(trigger)
})

it.each([
  [
    'https://img2.kwcdn.kuwo.cn/star/upload/9/9/1543919747769_.png',
    'https://img2.kuwo.cn/star/upload/9/9/1543919747769_.png',
  ],
  ['https://img4.kwcdn.kuwo.cn/unknown.jpg', 'https://img4.kuwo.cn/unknown.jpg'],
])('旧缓存封面和全屏背景均在发请求前归一化 %s', (raw, expected) => {
  const palette = vi.spyOn(coverPalette, 'loadCoverPalette').mockResolvedValue(null)
  usePlayer.setState({ track: { ...track, coverUrl: raw } })
  const view = mount()
  const images = Array.from(view.container.querySelectorAll('img'))
  expect(images.some((image) => image.src.includes('.kwcdn.kuwo.cn'))).toBe(false)
  expect(view.container.querySelector('.player-atmosphere')).toBeInTheDocument()
  if (expected) expect(palette).toHaveBeenCalledWith(expected, { signal: expect.any(AbortSignal) })
  else expect(palette).not.toHaveBeenCalled()
})

it('播放设置提供有限四档歌词字号，原节点和音频参数不因调字改变', async () => {
  const seek = vi.spyOn(player, 'seek').mockImplementation(() => {})
  const setRate = vi.spyOn(player, 'setRate').mockImplementation(() => {})
  usePlayer.setState({ playbackRate: 1.5, playing: false })
  const view = mount('/now-playing?view=lyrics')
  await waitFor(() => expect(view.container.querySelector('.player-lyric-line.current')).toBeTruthy())
  const current = view.container.querySelector('.player-lyric-line.current')
  const footer = view.container.querySelector('.player-immersive-footer')
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  const sizes = screen.getByRole('group', { name: '歌词字号' })
  expect(
    within(sizes)
      .getAllByRole('button')
      .map((button) => button.textContent),
  ).toEqual(['小', '标准', '大', '特大', '恢复默认'])
  fireEvent.click(within(sizes).getByRole('button', { name: '特大' }))
  expect(within(sizes).getByRole('button', { name: '特大' })).toHaveAttribute('aria-pressed', 'true')
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-lyric-font', 'extra-large')
  expect(
    (view.container.querySelector('.player-immersive') as HTMLElement).style.getPropertyValue(
      '--player-lyric-scale',
    ),
  ).toBe('1.4')
  expect(view.container.querySelector('.player-lyric-line.current')).toBe(current)
  expect(view.container.querySelector('.player-immersive-footer')).toBe(footer)
  expect(seek).not.toHaveBeenCalled()
  expect(setRate).not.toHaveBeenCalled()
  expect(usePlayer.getState()).toMatchObject({ position: 18, playbackRate: 1.5, playing: false })
  expect(localStorage.getItem(LYRIC_FONT_STORAGE_KEY)).toBe('extra-large')
})

it('关闭重开仍记住字号，恢复默认清除设备缓存', () => {
  mount()
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  fireEvent.click(screen.getByRole('button', { name: '大' }))
  fireEvent.click(screen.getByRole('button', { name: '关闭' }))
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  expect(screen.getByRole('button', { name: '大' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(screen.getByRole('button', { name: '恢复默认歌词字号' }))
  expect(screen.getByRole('button', { name: '标准' })).toHaveAttribute('aria-pressed', 'true')
  expect(localStorage.getItem(LYRIC_FONT_STORAGE_KEY)).toBeNull()
})

it('字号改变恢复跟随当前行，不让手动暂停跟随永久卡住，点击歌词仍seek', async () => {
  const seek = vi.spyOn(player, 'seek').mockImplementation(() => {})
  const view = mount('/now-playing?view=lyrics')
  await waitFor(() => expect(view.container.querySelector('.player-lyrics-scroll')).toBeTruthy())
  fireEvent.wheel(view.container.querySelector('.player-lyrics-scroll')!)
  expect(screen.getByRole('button', { name: '回到当前歌词' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  fireEvent.click(screen.getByRole('button', { name: '大' }))
  expect(screen.queryByRole('button', { name: '回到当前歌词' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '关闭' }))
  fireEvent.click(screen.getByRole('button', { name: '第三行测试歌词' }))
  expect(seek).toHaveBeenCalledWith(20)
})

it('空态也可以设置设备字号，进入全屏首个render直接沿用缓存', () => {
  usePlayer.setState({ track: null, duration: 0, position: 0 })
  const view = mount('/')
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  fireEvent.click(screen.getByRole('button', { name: '大' }))
  fireEvent.click(screen.getByRole('button', { name: '关闭' }))
  fireEvent.click(screen.getByRole('button', { name: '打开全屏播放器' }))
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-lyric-font', 'large')
  expect(view.container.querySelector('.player-empty')).toBeTruthy()
})

it('Storage写入被拒绝时字号仍可调且有明确的本次会话提示', () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('blocked', 'QuotaExceededError')
  })
  const view = mount()
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  fireEvent.click(screen.getByRole('button', { name: '特大' }))
  expect(screen.getByRole('group', { name: '歌词字号' })).toHaveTextContent('仅本次有效')
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-lyric-font', 'extra-large')
  fireEvent.click(screen.getByRole('button', { name: '关闭' }))
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  expect(screen.getByRole('button', { name: '特大' })).toHaveAttribute('aria-pressed', 'true')
})

it('预先保存的字号不需要effect二次替换；空态音符保留唯一中心容器', () => {
  setLyricFont('large')
  const view = mount()
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-lyric-font', 'large')
  fireEvent.click(screen.getByRole('button', { name: '收起播放器' }))
  act(() => usePlayer.setState({ track: null }))
  expect(view.container.querySelectorAll('.player-mini-placeholder > svg')).toHaveLength(1)
})

// JSDOM 不做布局：这里仅验证对齐算法、观察器生命周期和手动阅读语义；像素几何由专用浏览器验证。
function mockLyricGeometry() {
  let height = 400
  let lineHeight = 60
  const scrollTo = vi.fn(function (this: HTMLElement, options?: ScrollToOptions | number, y?: number) {
    this.scrollTop = typeof options === 'number' ? y || 0 : options?.top || 0
  })
  const observers: { callback: () => void; disconnect: ReturnType<typeof vi.fn> }[] = []
  vi.stubGlobal(
    'ResizeObserver',
    class {
      disconnect = vi.fn()
      observe = vi.fn()
      unobserve = vi.fn()
      constructor(callback: () => void) {
        observers.push({ callback, disconnect: this.disconnect })
      }
    },
  )
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (this: HTMLElement) {
    if (!this.classList.contains('player-lyrics-scroll')) return 0
    this.scrollTo = scrollTo
    return height
  })
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(300)
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('player-lyric-line') ? lineHeight : 0
  })
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const container = this.closest<HTMLElement>('.player-lyrics-scroll')
    const index = container ? [...container.children].indexOf(this) : -1
    const top = index < 0 ? 50 : 50 + height / 2 + index * lineHeight - container!.scrollTop
    return {
      x: 0,
      y: top,
      top,
      bottom: top + lineHeight,
      left: 0,
      right: 300,
      width: 300,
      height: lineHeight,
      toJSON: () => ({}),
    }
  })
  return {
    scrollTo,
    observers,
    resize(nextHeight: number, nextLineHeight = lineHeight) {
      height = nextHeight
      lineHeight = nextLineHeight
      observers.at(-1)?.callback()
    },
  }
}

it('按歌词窗口高度留半屏空间，首尾行均可居中，resize 不调用媒体 seek', async () => {
  const geometry = mockLyricGeometry()
  const seek = vi.spyOn(player, 'seek').mockImplementation(() => {})
  const view = mount('/now-playing?view=lyrics')
  await waitFor(() => expect(geometry.scrollTo).toHaveBeenCalled())
  expect(geometry.scrollTo).toHaveBeenLastCalledWith({ top: 90, behavior: 'instant' })
  act(() => usePlayer.setState({ position: 0 }))
  expect(geometry.scrollTo).toHaveBeenLastCalledWith({ top: 30, behavior: 'smooth' })
  act(() => usePlayer.setState({ position: 21 }))
  expect(geometry.scrollTo).toHaveBeenLastCalledWith({ top: 150, behavior: 'smooth' })
  act(() => geometry.resize(240, 100))
  expect(geometry.scrollTo).toHaveBeenLastCalledWith({ top: 250, behavior: 'instant' })
  expect(seek).not.toHaveBeenCalled()
  view.unmount()
  expect(geometry.observers.every((observer) => observer.disconnect.mock.calls.length > 0)).toBe(true)
})

it('键盘/触摸暂停跟随，尺寸变化不抢手动滚动，恢复跟随后重新对齐', async () => {
  const geometry = mockLyricGeometry()
  const view = mount('/now-playing?view=lyrics')
  await waitFor(() => expect(geometry.scrollTo).toHaveBeenCalled())
  const container = view.container.querySelector<HTMLElement>('.player-lyrics-scroll')!
  fireEvent.keyDown(container, { key: 'PageDown' })
  expect(screen.getByRole('button', { name: '回到当前歌词' })).toBeInTheDocument()
  geometry.scrollTo.mockClear()
  act(() => geometry.resize(300))
  act(() => usePlayer.setState({ position: 21 }))
  expect(geometry.scrollTo).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '回到当前歌词' }))
  expect(geometry.scrollTo).toHaveBeenCalled()
  fireEvent.touchMove(container)
  expect(screen.getByRole('button', { name: '回到当前歌词' })).toBeInTheDocument()
  geometry.scrollTo.mockClear()
  fireEvent.click(screen.getByRole('button', { name: '显示封面' }))
  fireEvent.click(screen.getAllByRole('button', { name: '显示完整歌词' })[0])
  expect(screen.queryByRole('button', { name: '回到当前歌词' })).not.toBeInTheDocument()
  expect(geometry.scrollTo).toHaveBeenLastCalledWith(expect.objectContaining({ behavior: 'instant' }))
})

it('隐藏或零高歌词不滚动，重新有高度后补齐定位且不重建当前行', async () => {
  const geometry = mockLyricGeometry()
  geometry.resize(0)
  const view = mount('/now-playing?view=lyrics')
  await waitFor(() => expect(view.container.querySelector('.player-lyric-line.current')).toBeTruthy())
  const current = view.container.querySelector('.player-lyric-line.current')
  expect(geometry.scrollTo).not.toHaveBeenCalled()
  act(() => geometry.resize(320))
  expect(geometry.scrollTo).toHaveBeenLastCalledWith({ top: 90, behavior: 'instant' })
  expect(view.container.querySelector('.player-lyric-line.current')).toBe(current)
  const calls = geometry.scrollTo.mock.calls.length
  act(() => geometry.resize(320))
  expect(geometry.scrollTo).toHaveBeenCalledTimes(calls)
})

it('只标记真实 iframe，不将 fnOS 路径或宿主顶栏高度当作播放器 padding', () => {
  const view = mount()
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-embedded', 'false')
  expect(view.container.querySelector('.player-immersive')).not.toHaveAttribute('data-host')
})

it('静音操作委派给 store.toggleMute，不在组件内保存或恢复音量', () => {
  const toggleMute = vi.spyOn(player, 'toggleMute').mockImplementation(() => {})
  const volume = vi.spyOn(player, 'volume').mockImplementation(() => {})
  mount()
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  fireEvent.click(
    within(screen.getByRole('dialog', { name: '播放设置' })).getByRole('button', { name: '静音' }),
  )
  expect(toggleMute).toHaveBeenCalledTimes(1)
  expect(volume).not.toHaveBeenCalled()
  act(() => usePlayer.setState({ muted: true, volume: 0, volumeBeforeMute: 0.38 }))
  fireEvent.click(
    within(screen.getByRole('dialog', { name: '播放设置' })).getByRole('button', { name: '取消静音' }),
  )
  expect(toggleMute).toHaveBeenCalledTimes(2)
  expect(volume).not.toHaveBeenCalled()
})

it('从持久化静音状态进入/重开设置时，按钮沿用 store 静音标记及原音量', () => {
  usePlayer.setState({ volume: 0, muted: true, volumeBeforeMute: 0.38 })
  // 模拟 Dirac 的接口合同；媒体及持久化实现由 store 测试负责。
  const toggleMute = vi.spyOn(player, 'toggleMute').mockImplementation(() => {
    const { volume, volumeBeforeMute } = usePlayer.getState()
    const next = volume > 0 ? 0 : volumeBeforeMute
    usePlayer.setState({ volume: next, muted: next === 0 })
  })
  mount()
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  expect(
    within(screen.getByRole('dialog', { name: '播放设置' })).getByRole('button', { name: '取消静音' }),
  ).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '关闭' }))
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  fireEvent.click(
    within(screen.getByRole('dialog', { name: '播放设置' })).getByRole('button', { name: '取消静音' }),
  )
  expect(toggleMute).toHaveBeenCalledTimes(1)
  expect(usePlayer.getState()).toMatchObject({ volume: 0.38, muted: false, volumeBeforeMute: 0.38 })
  expect(
    within(screen.getByRole('dialog', { name: '播放设置' })).getByRole('slider', { name: '音量' }),
  ).toHaveValue('0.38')
  expect(
    within(screen.getByRole('dialog', { name: '播放设置' })).getByRole('button', { name: '静音' }),
  ).toBeInTheDocument()
})

it('普通迷你播放器使用深色播放设置', () => {
  mount('/')
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  const dialog = screen.getByRole('dialog', { name: '播放设置' })
  expect(dialog).toHaveClass('player-dark-dialog', 'is-docked')
})

it('双立柱只有一套左侧播放控制，底部只放辅助功能；歌词切换保持进度与控制节点', async () => {
  const view = mount()
  const deck = screen.getByRole('region', { name: '播放控制台' })
  const seek = within(deck).getByRole('slider', { name: '播放进度' })
  const controls = deck.querySelector('.player-transport')
  expect(view.container.querySelectorAll('.player-seek')).toHaveLength(1)
  expect(view.container.querySelector('.player-deck-meta h1')).toHaveTextContent(track.title)
  const dock = screen.getByRole('contentinfo', { name: '播放辅助工具' })
  expect(within(dock).getByRole('slider', { name: '音量' })).toBeInTheDocument()
  expect(dock.querySelector('.player-seek')).toBeNull()
  expect(dock.querySelector('.player-transport')).toBeNull()
  await waitFor(() => expect(view.container.querySelector('.player-lyric-line.current')).toBeTruthy())
  const lyric = view.container.querySelector('.player-lyric-line.current')
  fireEvent.click(within(dock).getByRole('button', { name: '显示完整歌词' }))
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-view', 'lyrics')
  expect(within(deck).getByRole('slider', { name: '播放进度' })).toBe(seek)
  expect(deck.querySelector('.player-transport')).toBe(controls)
  expect(view.container.querySelector('.player-lyric-line.current')).toBe(lyric)
  fireEvent.click(within(dock).getByRole('button', { name: '显示封面' }))
  expect(view.container.querySelector('.player-immersive')).toHaveAttribute('data-view', 'cover')
  expect(within(deck).getByRole('slider', { name: '播放进度' })).toBe(seek)
})

it('辅栏默认显示1.0×但不将1.25等真实倍速四舍五入', () => {
  const view = mount()
  expect(view.container.querySelector('.player-rate-select > span')).toHaveTextContent('1.0×')
  act(() => usePlayer.setState({ playbackRate: 1.25 }))
  expect(view.container.querySelector('.player-rate-select > span')).toHaveTextContent('1.25×')
})

it.each([{}, 'unexpected text', 7])('歌词容器异常不卸载播放器：%j', (lines) => {
  queryClient.setQueryData([`/tracks/${encodeURIComponent(track.id)}/lyrics`], { lines })
  const view = mount()
  expect(view.container.querySelector('.player-immersive')).toBeInTheDocument()
  expect(view.container.querySelectorAll('.player-transport.large')).toHaveLength(1)
  expect(within(screen.getByRole('region', { name: '歌词' })).getByRole('heading')).toHaveTextContent(
    '暂无歌词',
  )
  expect(usePlayer.getState().position).toBe(18)
})
it('歌词混有null和非法条目时只用有效行，排序与控制保留，文本不解析为HTML', () => {
  queryClient.setQueryData([`/tracks/${encodeURIComponent(track.id)}/lyrics`], {
    lines: [
      null,
      0,
      'invalid',
      {},
      { time: '10', text: '非法时间' },
      { time: 20, text: '后行' },
      { time: 0, text: '<b>有效歌词</b>' },
      { time: -1, text: '负数' },
    ],
  })
  const view = mount()
  expect([...view.container.querySelectorAll('.player-lyric-line')].map((line) => line.textContent)).toEqual([
    '<b>有效歌词</b>',
    '后行',
  ])
  expect(view.container.querySelector('.player-lyric-line b')).toBeNull()
  expect(view.container.querySelector('.player-transport.large')).toBeInTheDocument()
  expect(usePlayer.getState().position).toBe(18)
})

it('播放设置以紧凑选择行呈现速度和音质，不再显示六块速度矩阵', () => {
  mount()
  fireEvent.click(screen.getByRole('button', { name: '播放设置' }))
  const dialog = screen.getByRole('dialog', { name: '播放设置' })
  const speed = within(dialog).getByRole('combobox', { name: '播放速度' })
  expect(within(speed).getAllByRole('option')).toHaveLength(6)
  expect(within(speed).getByRole('option', { name: '1.0×' })).toBeInTheDocument()
  expect(within(speed).getByRole('option', { name: '1.25×' })).toBeInTheDocument()
  expect(dialog.querySelector('.player-rate-options')).toBeNull()
  expect(within(dialog).getByRole('combobox', { name: '播放音质' })).toBeInTheDocument()
  expect(within(dialog).getByRole('slider', { name: '音量' })).toBeInTheDocument()
})

import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { ReactNode } from 'react'
import type { Collection, Track } from '../lib/types'
import { ChartDetailPage, ChartsPage, DiscoverPage, NewTracksPage, PlaylistsPage } from '../pages/Browse'
import { player, usePlayer, usePlayingCollection } from '../stores/player'
import { CollectionGrid, Cover, IconButton, PageHeader, QueryState } from './UI'

const clients: QueryClient[] = []
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute('open', '')
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute('open')
  })
})
afterEach(() => {
  cleanup()
  for (const client of clients.splice(0)) client.clear()
  usePlayer.setState({ track: null, queue: [], error: null, loading: false, playing: false })
  usePlayingCollection.setState({ collectionId: null, trackIds: [] })
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
describe('稳定基础组件', () => {
  it('封面预留尺寸并对失效图片回退', () => {
    render(<Cover src="/missing.svg" title="封面" />)
    const image = screen.getByRole('img', { name: '封面' })
    expect(image).toHaveAttribute('width', '400')
    expect(image).toHaveAttribute('height', '400')
    expect(image).toHaveAttribute('loading', 'lazy')
    expect(image).toHaveAttribute('fetchpriority', 'auto')
    expect(image).toHaveAttribute('referrerpolicy', 'no-referrer')
    fireEvent.error(image)
    expect(image).toHaveAttribute('src', '/covers/placeholder.svg')
  })
  it.each([undefined, ''])('没有封面地址（%s）时使用中性占位，更新封面保留尺寸', (src) => {
    const { rerender } = render(<Cover src={src} title="暂无封面" />)
    const image = screen.getByRole('img', { name: '暂无封面' })
    expect(image).toHaveAttribute('src', '/covers/placeholder.svg')
    expect(image).toHaveAttribute('width', '400')
    expect(image).toHaveAttribute('height', '400')
    fireEvent.error(image)
    expect(image).toHaveAttribute('src', '/covers/placeholder.svg')
    rerender(<Cover src="/covers/real-album.jpg" title="暂无封面" />)
    expect(screen.getByRole('img', { name: '暂无封面' })).toBe(image)
    expect(image).toHaveAttribute('src', '/covers/real-album.jpg')
    expect(image).toHaveAttribute('width', '400')
    expect(image).toHaveAttribute('height', '400')
  })
  it('外站封面优先直连，失败后才走同源代理并最终落到占位图', () => {
    const raw = 'https://img1.kuwo.cn/star/albumcover/120/a.jpg'
    render(<Cover src={raw} title="代理封面" />)
    const image = screen.getByRole('img', { name: '代理封面' })
    expect(image).toHaveAttribute('src', raw)
    fireEvent.error(image)
    expect(image).toHaveAttribute('src', `/api/v1/covers?url=${encodeURIComponent(raw)}`)
    fireEvent.error(image)
    expect(image).toHaveAttribute('src', '/covers/placeholder.svg')
  })
  it('协议相对封面提升为 HTTPS，关键封面可显式覆盖懒加载优先级', () => {
    const target = 'https://music.126.net/cover.jpg'
    render(<Cover src="//music.126.net/cover.jpg" title="首屏封面" loading="eager" fetchPriority="high" />)
    const image = screen.getByRole('img', { name: '首屏封面' })
    expect(image).toHaveAttribute('src', target)
    expect(image).toHaveAttribute('loading', 'eager')
    expect(image).toHaveAttribute('fetchpriority', 'high')
    fireEvent.error(image)
    expect(image).toHaveAttribute('src', `/api/v1/covers?url=${encodeURIComponent(target)}`)
  })
  it('页头兼容旧eyebrow参数但不渲染装饰文案，标题说明及操作保留', () => {
    const refresh = vi.fn()
    render(
      <PageHeader eyebrow="YOUR DAILY SOUNDTRACK" title="排行榜" description="由音源返回">
        <button onClick={refresh}>刷新内容</button>
      </PageHeader>,
    )
    expect(screen.getByRole('heading', { name: '排行榜', level: 1 })).toBeVisible()
    expect(screen.getByText('由音源返回')).toBeVisible()
    expect(screen.queryByText('YOUR DAILY SOUNDTRACK')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '刷新内容' }))
    expect(refresh).toHaveBeenCalledOnce()
  })
  it('图标按钮支持可访问名称和真实点击', () => {
    const click = vi.fn()
    render(
      <IconButton label="下一首" onClick={click}>
        next
      </IconButton>,
    )
    fireEvent.click(screen.getByRole('button', { name: '下一首' }))
    expect(click).toHaveBeenCalledOnce()
  })
  it('加载时提供状态，错误态支持重试，不显示伪数据', () => {
    const retry = vi.fn()
    const { rerender } = render(
      <QueryState pending error={null}>
        <div>真实内容</div>
      </QueryState>,
    )
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('正在加载')
    expect(status.querySelector('.loading-status-copy')).toHaveTextContent('正在加载')
    expect(status.querySelector('.loading-rail')).not.toBeNull()
    expect(status.querySelector('.spin')).toBeNull()
    expect(screen.queryByText('真实内容')).toBeNull()
    rerender(
      <QueryState pending={false} error={new Error('网络不可用')} retry={retry}>
        <div>真实内容</div>
      </QueryState>,
    )
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }))
    expect(retry).toHaveBeenCalledOnce()
    expect(screen.getByText('网络不可用')).toBeInTheDocument()
  })
})

// 仅内存单元夹具；不启动后端，也不将测试歌曲视作真实在线目录。
const song = (id: string, title: string): Track => ({
  id,
  title,
  providerId: 'wy',
  artist: '测试歌手',
  album: '测试专辑',
  duration: 120,
  coverUrl: '',
  qualities: [],
  canDownload: false,
})
const collection = (id: string, title: string): Collection => ({
  id,
  title,
  providerId: 'wy',
  description: '目录返回的说明',
  coverUrl: '',
  trackCount: 1,
  category: '华语',
})
const areas = [
  { id: 'all', name: '全部' },
  { id: 'zh', name: '华语' },
  { id: 'western', name: '欧美' },
  { id: 'jp', name: '日语' },
  { id: 'kr', name: '韩语' },
]
const response = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
function deferredResponse() {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function browseFetch(read: (url: URL) => Response | Promise<Response>) {
  const fetcher = vi.fn((input: RequestInfo | URL) =>
    Promise.resolve(read(new URL(String(input), 'http://localhost'))),
  )
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}
function LocationSearch() {
  return <output data-testid="location-search">{useLocation().search}</output>
}
function browseUI(children: ReactNode, path = '/', basename = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  clients.push(client)
  client.setQueryData(
    ['/providers'],
    [
      { id: 'wy', name: '测试目录', enabled: true, isDemo: false },
      { id: 'demo', name: 'Demo', enabled: true, isDemo: true },
    ],
  )
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter basename={basename} initialEntries={[path]}>
        {children}
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('集合卡片播放状态', () => {
  it('只有当前歌曲属于记录的集合时才显示暂停，避免其它入口播放后旧卡片误亮', () => {
    const item = collection('wy:playlist-a', '测试歌单')
    const member = song('wy:member', '集合歌曲')
    const unrelated = song('wy:unrelated', '其它歌曲')
    usePlayingCollection.setState({ collectionId: item.id, trackIds: [member.id] })
    usePlayer.setState({ track: unrelated, queue: [unrelated], playing: true })
    browseUI(<CollectionGrid items={[item]} />)
    expect(screen.getByRole('button', { name: `播放 ${item.title}` })).toBeVisible()
    act(() => usePlayer.setState({ track: member, queue: [member], playing: true }))
    expect(screen.getByRole('button', { name: `暂停 ${item.title}` })).toBeVisible()
  })

  it('排行榜目录播放加载时禁用按钮，并忽略重复播放请求', async () => {
    const chart = collection('wy:chart-reentry', '防重入榜单')
    const track = song('wy:chart-reentry-track', '防重入歌曲')
    const detail = deferredResponse()
    let detailReads = 0
    const fetcher = browseFetch((url) => {
      if (url.pathname === '/api/v1/charts/featured') {
        return response({ items: [], unavailableIds: [], total: 0, batch: 1, batches: 1 })
      }
      if (url.pathname === '/api/v1/charts') return response([chart])
      if (url.pathname === '/api/v1/charts/wy%3Achart-reentry') {
        detailReads++
        return detail.promise
      }
      return response([])
    })
    let resolvePlayback!: () => void
    const playback = new Promise<void>((resolve) => {
      resolvePlayback = resolve
    })
    const playCollection = vi.spyOn(player, 'playCollection').mockReturnValue(playback)

    browseUI(<ChartsPage />, '/')
    const button = await screen.findByRole('button', { name: `播放 ${chart.title}` })

    fireEvent.click(button)
    await waitFor(() => expect(button).toBeDisabled())
    expect(button).toHaveAttribute('aria-busy', 'true')

    fireEvent.click(button)
    expect(detailReads).toBe(1)

    await act(async () => detail.resolve(response({ ...chart, tracks: [track] })))
    await waitFor(() => expect(playCollection).toHaveBeenCalledOnce())
    expect(button).toBeDisabled()

    resolvePlayback()
    await waitFor(() => expect(button).toBeEnabled())
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).includes('/charts/wy%3Achart-reentry')),
    ).toHaveLength(1)
  })

  it('较新的榜单播放意图淘汰较慢旧请求', async () => {
    const chartA = collection('wy:chart-slow-a', '慢榜单 A')
    const chartB = collection('wy:chart-fast-b', '快榜单 B')
    const trackA = song('wy:track-a', '歌曲 A')
    const trackB = song('wy:track-b', '歌曲 B')
    const detailA = deferredResponse()
    browseFetch((url) => {
      if (url.pathname === '/api/v1/charts/featured') {
        return response({ items: [], unavailableIds: [], total: 0, batch: 1, batches: 1 })
      }
      if (url.pathname === '/api/v1/charts') return response([chartA, chartB])
      if (url.pathname === '/api/v1/charts/wy%3Achart-slow-a') return detailA.promise
      if (url.pathname === '/api/v1/charts/wy%3Achart-fast-b')
        return response({ ...chartB, tracks: [trackB] })
      return response([])
    })
    const playCollection = vi.spyOn(player, 'playCollection').mockResolvedValue()

    browseUI(<ChartsPage />, '/')
    fireEvent.click(await screen.findByRole('button', { name: `播放 ${chartA.title}` }))
    fireEvent.click(screen.getByRole('button', { name: `播放 ${chartB.title}` }))
    await waitFor(() => expect(playCollection).toHaveBeenCalledWith(chartB.id, [trackB]))

    await act(async () => detailA.resolve(response({ ...chartA, tracks: [trackA] })))
    await waitFor(() => expect(screen.getByRole('button', { name: `播放 ${chartA.title}` })).toBeEnabled())
    expect(playCollection).not.toHaveBeenCalledWith(chartA.id, [trackA])
    expect(playCollection).toHaveBeenCalledTimes(1)
  })
})

describe('目录的真实导航与查询切换', () => {
  it('榜单搜索在输入法组词期间保留草稿，仅在选词结束后更新查询', async () => {
    browseFetch((url) => {
      if (url.pathname === '/api/v1/charts/featured') {
        return response({ items: [], unavailableIds: [], total: 0, batch: 1, batches: 1 })
      }
      return response([])
    })
    browseUI(
      <>
        <ChartsPage />
        <LocationSearch />
      </>,
    )

    const input = screen.getByRole('searchbox', { name: '在榜单中检索' })
    const location = screen.getByTestId('location-search')
    fireEvent.compositionStart(input)
    fireEvent.change(input, { target: { value: 'wo ai' } })
    expect(input).toHaveValue('wo ai')
    expect(new URLSearchParams(location.textContent || '').get('q')).toBeNull()

    fireEvent.change(input, { target: { value: '我爱' } })
    expect(input).toHaveValue('我爱')
    expect(new URLSearchParams(location.textContent || '').get('q')).toBeNull()

    fireEvent.compositionEnd(input, { data: '我爱' })
    await waitFor(() => expect(new URLSearchParams(location.textContent || '').get('q')).toBe('我爱'))
    expect(input).toHaveValue('我爱')
  })

  it.each(['', '/app/melora'])('榜内歌曲可直接播放，详情链接保留前缀且刷新留住列表（%s）', async (prefix) => {
    const chart = collection('demo:chart-a', '测试榜单')
    const track = song('wy:1', '榜单歌曲')
    const refreshing = deferredResponse()
    let reads = 0
    const fetcher = browseFetch((url) => {
      if (url.pathname === '/api/v1/charts/featured') {
        return response({ items: [{ ...chart, tracks: [track] }], total: 1, batch: 1, batches: 1 })
      }
      if (url.pathname === '/api/v1/charts') return response([chart])
      if (url.pathname === '/api/v1/charts/demo%3Achart-a') {
        reads++
        return reads === 1 ? response({ ...chart, tracks: [track] }) : refreshing.promise
      }
      return response([])
    })
    const view = browseUI(
      <Routes>
        <Route path="/" element={<ChartsPage />} />
        <Route path="/charts/:id" element={<ChartDetailPage />} />
      </Routes>,
      `${prefix}/`,
      prefix || '/',
    )
    await screen.findByText(track.title)
    const link = view.container.querySelector<HTMLAnchorElement>('.chart-panel-link')!
    expect(link).toHaveAttribute('href', `${prefix}/charts/demo%3Achart-a`)
    expect(screen.getByRole('button', { name: '播放 测试榜单 前三首' })).toBeEnabled()
    expect(view.container.querySelector('.track-row')).toHaveTextContent(track.title)
    fireEvent.click(link)
    await screen.findByRole('heading', { name: chart.title, level: 1 })
    const row = view.container.querySelector('.track-row')!
    expect(row).toHaveTextContent(track.title)
    expect(screen.getByRole('button', { name: '播放全部' })).toBeEnabled()
    expect(screen.queryByRole('link', { name: '返回排行榜' })).toBeNull()
    expect(fetcher.mock.calls.some(([url]) => String(url).includes('/playlists/'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '刷新榜单' }))
    await waitFor(() => expect(reads).toBe(2))
    expect(view.container.querySelector('.track-row')).toBe(row)
    expect(view.container.querySelector('.query-pending')).toBeNull()
    expect(screen.getByRole('button', { name: '刷新榜单' })).toBeDisabled()
    await act(async () =>
      refreshing.resolve(response({ ...chart, tracks: [{ ...track, title: '已更新的榜单歌曲' }] })),
    )
    await screen.findByText('已更新的榜单歌曲')
    expect(view.container.querySelector('.track-row')).toBe(row)
    expect(screen.queryByRole('link', { name: '返回排行榜' })).toBeNull()
  })

  it('榜单详情失败可重试，不恢复已删除的返回入口或假曲目', async () => {
    const fetcher = browseFetch(() => response({ error: { code: 'not_found', message: '榜单不存在' } }, 404))
    const view = browseUI(
      <Routes>
        <Route path="/charts/:id" element={<ChartDetailPage />} />
      </Routes>,
      '/charts/demo:missing',
    )
    await screen.findByText('榜单不存在')
    expect(screen.queryByRole('link', { name: '返回排行榜' })).toBeNull()
    expect(screen.getByRole('button', { name: '重新加载' })).toBeEnabled()
    expect(view.container.querySelector('.track-row')).toBeNull()
    expect(fetcher.mock.calls.every(([url]) => String(url).includes('/charts/demo%3Amissing'))).toBe(true)
  })

  it('次级新歌按需加载，地区切换保留旧节点并明确提示更新', async () => {
    const next = deferredResponse()
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      height: 640,
      top: 0,
    } as DOMRect)
    const fetcher = browseFetch((url) => {
      if (url.pathname === '/api/v1/discovery/new-tracks') {
        if (url.searchParams.get('area') === 'zh') return next.promise
        return response({ title: '新歌速递', area: 'all', areas, tracks: [song('wy:old', '全部地区歌曲')] })
      }
      return response([])
    })
    const view = browseUI(
      <Routes>
        <Route path="/discover" element={<DiscoverPage />} />
        <Route path="/discover/new-tracks" element={<NewTracksPage />} />
      </Routes>,
      '/discover',
    )
    expect(screen.getByRole('heading', { name: '发现', level: 1 })).toBeVisible()
    expect(fetcher.mock.calls.some(([url]) => String(url).includes('/discovery/new-tracks'))).toBe(false)
    fireEvent.click(screen.getByRole('link', { name: '进入新碟专区完整列表' }))
    await screen.findByText('全部地区歌曲')
    const oldRow = view.container.querySelector('.new-tracks-page .track-row')
    const filter = screen.getByRole('group', { name: '新歌地区' })
    expect(within(filter).getAllByRole('button')).toHaveLength(5)
    fireEvent.click(within(filter).getByRole('button', { name: '华语' }))
    await waitFor(() =>
      expect(fetcher.mock.calls.some(([url]) => String(url).endsWith('/discovery/new-tracks?area=zh'))).toBe(
        true,
      ),
    )
    expect(screen.getByText('全部地区歌曲')).toBeVisible()
    expect(view.container.querySelector('.new-tracks-page .track-row')).toBe(oldRow)
    expect(screen.getByText('正在切换地区，暂时保留上次歌曲…')).toBeVisible()
    await act(async () =>
      next.resolve(
        response({ title: '新歌速递', area: 'zh', areas, tracks: [song('wy:new', '华语地区歌曲')] }),
      ),
    )
    await screen.findByText('华语地区歌曲')
    expect(within(filter).getByRole('button', { name: '华语' })).toHaveAttribute('aria-pressed', 'true')
    expect(view.container.querySelector('.collection-grid')).toBeNull()
    expect(fetcher.mock.calls.some(([url]) => String(url).includes('/recommendations/daily'))).toBe(true)
  })

  it('发现推荐实际发起播放，无可用音源时保留错误与重试而无音源管理入口', async () => {
    const track = song('wy:play', '待播放的新歌')
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => {})
    const fetcher = browseFetch((url) => {
      if (url.pathname === '/api/v1/recommendations/daily')
        return response({ tracks: [track], playlists: [], personalized: false, reason: '公开推荐' })
      if (url.pathname === '/api/v1/settings') return response({ defaultQuality: 'standard' })
      if (url.pathname.endsWith('/play-info'))
        return response({ error: { code: 'source_not_ready', message: '尚未选择可用的 LX 音源' } }, 409)
      return response([])
    })
    browseUI(<DiscoverPage />, '/discover')
    fireEvent.click(await screen.findByRole('button', { name: `播放 ${track.title}` }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('尚未选择可用的 LX 音源')
    expect(within(alert).getByRole('button', { name: '重试播放' })).toBeEnabled()
    expect(screen.queryByRole('link', { name: '音源设置' })).toBeNull()
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).includes('/tracks/wy%3Aplay/play-info?quality=standard'),
      ),
    ).toBe(true)
  })

  it('歌单分类弹窗去重、24项分页及音源切换是真请求，更新时保留旧页并提示', async () => {
    const pageTwo = deferredResponse()
    const categoryPage = deferredResponse()
    const firstPage = Array.from({ length: 24 }, (_, i) => collection(`wy:list-${i}`, `第一页歌单 ${i + 1}`))
    const fetcher = browseFetch((url) => {
      if (url.pathname === '/api/v1/playlist-categories')
        return response({
          categories: [
            { id: 'all', name: '全部', group: '分类' },
            { id: '全部', name: '全部', group: '分类' },
            { id: '华语', name: '华语', group: '语种' },
            { id: '华语', name: '华语', group: '语种' },
            { id: '流行', name: '流行', group: '风格' },
          ],
        })
      if (url.pathname === '/api/v1/playlists') {
        if (url.searchParams.get('source') === 'demo')
          return response([collection('demo:new', '切源后的歌单')])
        if (url.searchParams.get('category') === '华语') return categoryPage.promise
        if (url.searchParams.get('page') === '2') return pageTwo.promise
        return response(firstPage)
      }
      return response([])
    })
    const view = browseUI(<PlaylistsPage />, '/playlists')
    await screen.findByRole('link', { name: '打开歌单 第一页歌单 24' })
    fireEvent.click(screen.getByRole('button', { name: '选择歌单分类' }))
    const filter = screen.getByRole('dialog', { name: '全部分类' })
    expect(within(filter).getAllByRole('button', { name: '全部' })).toHaveLength(1)
    expect(within(filter).getAllByRole('button', { name: '华语' })).toHaveLength(1)
    fireEvent.click(within(filter).getByRole('button', { name: '关闭' }))
    const pagination = screen.getByRole('navigation', { name: '歌单分页' })
    expect(within(pagination).getByRole('button', { name: '上一页' })).toBeDisabled()
    fireEvent.click(
      within(screen.getByRole('navigation', { name: '歌单底部分页' })).getByRole('button', {
        name: '下一页',
      }),
    )
    await waitFor(() => expect(fetcher.mock.calls.some(([url]) => String(url).includes('page=2'))).toBe(true))
    expect(screen.getByRole('link', { name: '打开歌单 第一页歌单 1' })).toBeVisible()
    expect(view.container.querySelectorAll('.collection-item')).toHaveLength(24)
    expect(screen.getByText('正在更新筛选，暂时保留上一批歌单…')).toBeVisible()
    expect(within(pagination).getByRole('button', { name: '下一页' })).toBeDisabled()
    await act(async () => pageTwo.resolve(response([collection('wy:page2', '第二页歌单')])))
    await screen.findByRole('link', { name: '打开歌单 第二页歌单' })
    expect(within(pagination).getByText('第 2 页')).toBeVisible()
    expect(within(pagination).getByRole('button', { name: '下一页' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '选择歌单分类' }))
    fireEvent.click(
      within(screen.getByRole('dialog', { name: '全部分类' })).getByRole('button', { name: '华语' }),
    )
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => {
          const parsed = new URL(String(url), 'http://localhost')
          return parsed.searchParams.get('category') === '华语' && parsed.searchParams.get('page') === '1'
        }),
      ).toBe(true),
    )
    expect(screen.getByRole('link', { name: '打开歌单 第二页歌单' })).toBeVisible()
    expect(screen.queryByRole('dialog', { name: '全部分类' })).toBeNull()
    await act(async () => categoryPage.resolve(response([collection('wy:zh', '华语分类歌单')])))
    await screen.findByRole('link', { name: '打开歌单 华语分类歌单' })
    expect(within(pagination).getByText('第 1 页')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '演示音源' }))
    await screen.findByRole('link', { name: '打开歌单 切源后的歌单' })
    expect(fetcher.mock.calls.some(([url]) => String(url).includes('category=all&source=demo&page=1'))).toBe(
      true,
    )
  })
})

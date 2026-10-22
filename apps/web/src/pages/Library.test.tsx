import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { queryClient } from '../lib/api'
import type { Collection, HistoryEntry, Track } from '../lib/types'
import { LibraryPage } from './Library'

// 只替换 jsdom 未实现的原生 dialog；页面、查询缓存和请求逻辑保持真实。
vi.mock('../components/UI', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../components/UI')>()),
  Modal: ({ title, children }: { title: string; children: ReactNode }) => (
    <section role="dialog" aria-label={title}>
      {children}
    </section>
  ),
}))

const track: Track = {
  id: 'demo:history-one',
  providerId: 'demo',
  title: '清晨歌曲',
  artist: '演示歌手',
  album: '演示专辑',
  duration: 180,
  coverUrl: '',
  qualities: ['standard'],
  canDownload: false,
}

const playlist: Collection = {
  id: 'wy:playlist_1',
  providerId: 'wy',
  title: '清晨歌单',
  description: '',
  coverUrl: '',
  trackCount: 12,
  category: '歌单',
}
const album: Collection = {
  id: 'kw:book_album_10250871',
  providerId: 'kw',
  title: '盗墓笔记',
  description: '',
  coverUrl: '',
  trackCount: 250,
  category: '有声专辑',
}
const history: HistoryEntry[] = [
  {
    track,
    kind: 'playlist',
    contextId: playlist.id,
    playedAt: 3,
    playCount: 1,
    completedCount: 0,
    skipCount: 0,
    listenedMs: 0,
  },
  {
    track: { ...track, id: 'demo:history-two', title: '夜航章节' },
    kind: 'audiobook',
    contextId: album.id,
    playedAt: 2,
    playCount: 1,
    completedCount: 0,
    skipCount: 0,
    listenedMs: 0,
  },
  {
    track: { ...track, id: 'demo:history-three', title: '独奏单曲' },
    kind: 'track',
    playedAt: 1,
    playCount: 1,
    completedCount: 0,
    skipCount: 0,
    listenedMs: 0,
  },
]

function renderLibrary(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <LibraryPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  queryClient.clear()
  queryClient.setDefaultOptions({ queries: { retry: false, staleTime: 30_000 } })
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('本测试不应触发网络请求')))
  queryClient.setQueryData(['/library/summary'], {
    favoriteTracks: 0,
    favoritePlaylists: 2,
    history: 3,
    historyTracks: 1,
    historyPlaylists: 1,
    historyAudiobooks: 1,
    userPlaylists: 0,
  })
  queryClient.setQueryData(['/library/favorites/tracks'], [])
  queryClient.setQueryData(['/library/favorites/playlists'], [playlist, album])
  queryClient.setQueryData(['/library/history/entries'], history)
  queryClient.setQueryData(['/library/history/entries?kind=playlist'], [history[0]])
  queryClient.setQueryData(['/library/history/entries?kind=audiobook'], [history[1]])
  queryClient.setQueryData(['/library/history/entries?kind=track'], [history[2]])
})

afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('我的音乐库', () => {
  it('清空确认成功后即使重新读取失败也移除各分类旧记录和计数', async () => {
    const fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) =>
      init?.method === 'DELETE'
        ? new Response(null, { status: 204 })
        : Response.json({ error: { message: '刷新失败' } }, { status: 503 }),
    )
    vi.stubGlobal('fetch', fetch)
    renderLibrary('/library?tab=history')
    fireEvent.click(screen.getByRole('button', { name: '清空记录' }))
    fireEvent.click(screen.getByRole('button', { name: '确认清空' }))
    expect(await screen.findByText('暂无播放记录')).toBeVisible()
    for (const kind of ['', '?kind=track', '?kind=playlist', '?kind=audiobook']) {
      expect(queryClient.getQueryData([`/library/history/entries${kind}`])).toEqual([])
    }
    expect(queryClient.getQueryData(['/library/summary'])).toMatchObject({
      history: 0,
      historyTracks: 0,
      historyPlaylists: 0,
      historyAudiobooks: 0,
      favoritePlaylists: 2,
    })
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
  })

  it('清空写入失败保留记录与确认入口，不伪造空态', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ error: { message: '写入失败' } }, { status: 503 })),
    )
    renderLibrary('/library?tab=history')
    fireEvent.click(screen.getByRole('button', { name: '清空记录' }))
    fireEvent.click(screen.getByRole('button', { name: '确认清空' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '确认清空' })).toBeEnabled())
    expect(queryClient.getQueryData(['/library/history/entries'])).toEqual(history)
    expect(queryClient.getQueryData(['/library/summary'])).toMatchObject({ history: 3 })
  })

  it('会话已切换时，旧清空请求的迟到确认不抹掉新历史', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((done) => {
            resolve = done
          }),
      ),
    )
    renderLibrary('/library?tab=history')
    fireEvent.click(screen.getByRole('button', { name: '清空记录' }))
    fireEvent.click(screen.getByRole('button', { name: '确认清空' }))
    const nextHistory = [{ ...history[0], track: { ...track, id: 'new-session' } }]
    await act(async () => {
      queryClient.removeQueries({
        predicate: (query) => String(query.queryKey[0]).startsWith('/library/history'),
      })
      queryClient.setQueryData(['/library/history/entries'], nextHistory)
      queryClient.setQueryData(['/library/summary'], { history: 1 })
      resolve(new Response(null, { status: 204 }))
    })
    await waitFor(() => expect(screen.getByRole('button', { name: '确认清空' })).toBeEnabled())
    expect(queryClient.getQueryData(['/library/history/entries'])).toEqual(nextHistory)
    expect(queryClient.getQueryData(['/library/summary'])).toEqual({ history: 1 })
  })

  it('收藏按歌单与听书专辑分组，专辑链接进入听书页', () => {
    renderLibrary('/library?tab=playlists')
    expect(screen.getByRole('heading', { name: '收藏歌单' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '收藏听书专辑' })).toBeVisible()
    expect(screen.getByRole('link', { name: /盗墓笔记/ })).toHaveAttribute(
      'href',
      '/audiobooks/albums/kw%3Abook_album_10250871',
    )
    expect(screen.getByRole('link', { name: /清晨歌单/ })).toHaveAttribute(
      'href',
      '/playlists/wy%3Aplaylist_1',
    )
  })

  it('播放记录按来源筛选，并在全部视图标记歌单/听书来源', async () => {
    renderLibrary('/library?tab=history')
    const filters = screen.getByRole('group', { name: '播放记录筛选' })
    expect(within(filters).getByRole('button', { name: /全部/ })).toHaveAttribute('aria-pressed', 'true')
    expect(within(filters).getByRole('button', { name: /听书专辑/ })).toHaveTextContent('1')
    const badges = Array.from(document.querySelectorAll('.track-kind-badge')).map(
      (element) => element.textContent,
    )
    expect(badges).toEqual(['歌单', '听书'])
    fireEvent.click(within(filters).getByRole('button', { name: /^歌单/ }))
    expect(await screen.findByText('清晨歌曲')).toBeVisible()
    expect(screen.queryByText('夜航章节')).not.toBeInTheDocument()
    expect(screen.queryByText('独奏单曲')).not.toBeInTheDocument()
  })
})

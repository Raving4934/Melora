import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { queryClient } from '../lib/api'
import type { BookAlbum, BookHome, BookRankResult } from '../lib/types'
import { player, usePlayingCollection } from '../stores/player'
import { AudioBooksPage, AudiobookAlbumPage, BookRankPage } from './Audiobooks'

const home: BookHome = {
  channels: [
    {
      id: 'kw:book_18',
      title: '小说',
      sections: [
        {
          id: 'kw:book_18_1',
          title: '小编推荐',
          items: [
            {
              id: 'kw:book_album_102904',
              providerId: 'kw',
              title: '话说泰山',
              description: '长篇评书',
              coverUrl: '',
              trackCount: 0,
              category: '有声专辑',
            },
            {
              id: 'kw:book_album_204403',
              providerId: 'kw',
              title: '白眉大侠',
              description: '单田芳评书',
              coverUrl: '',
              trackCount: 0,
              category: '有声专辑',
            },
          ],
        },
      ],
    },
  ],
  ranks: [
    { id: '13', name: '热播榜', tags: [{ id: '27', name: '总榜' }] },
    {
      id: '1',
      name: '有声小说',
      tags: [
        { id: '30', name: '总榜' },
        { id: '44', name: '悬疑热播榜' },
      ],
    },
  ],
}

const rankResult: BookRankResult = {
  tab: home.ranks[1]!,
  tagId: '30',
  page: 1,
  pageSize: 50,
  total: 2,
  items: [
    {
      id: 'kw:book_album_50927272',
      providerId: 'kw',
      title: '从赘婿到女帝宠臣',
      artist: '玥明珠',
      description: '穿越架空世界',
      coverUrl: '',
      trackCount: 1592,
      playCount: 15420978,
      category: '有声专辑',
    },
    {
      id: 'kw:book_album_101176',
      providerId: 'kw',
      title: '白眉大侠',
      artist: '单田芳',
      description: '',
      coverUrl: '',
      trackCount: 320,
      category: '有声专辑',
    },
  ],
}

const album: BookAlbum = {
  id: 'kw:book_album_10250871',
  providerId: 'kw',
  title: '盗墓笔记|周建龙演播',
  artist: '一路听天下&周建龙',
  description: '播音：周建龙\n作者：南派三叔',
  coverUrl: '',
  trackCount: 250,
  category: '有声专辑',
  tracks: [
    {
      id: 'kw:193883468',
      providerId: 'kw',
      title: '第001集',
      artist: '周建龙',
      album: '盗墓笔记',
      duration: 1500,
      coverUrl: '',
      qualities: [],
      canDownload: false,
    },
  ],
  page: 1,
  pageSize: 100,
  total: 250,
}

function renderHome() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <AudioBooksPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

const albumId = 'kw:book_album_10250871'
const albumPath = `/audiobooks/albums/${encodeURIComponent(albumId)}`

function AlbumNavigationHarness() {
  const navigate = useNavigate()
  return (
    <>
      <button type="button" onClick={() => navigate('/audiobooks/albums/kw%3Abook_album_204403?page=1')}>
        切换到另一个专辑
      </button>
      <AudiobookAlbumPage />
    </>
  )
}

function renderAlbum(path = `${albumPath}?page=1`) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path="/audiobooks/albums/:id" element={<AudiobookAlbumPage />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

function renderAlbumWithNavigation() {
  return render(
    <MemoryRouter initialEntries={[`${albumPath}?page=1`]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path="/audiobooks/albums/:id" element={<AlbumNavigationHarness />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

function renderRank(path = '/audiobooks/ranks/1') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <Routes>
          <Route path="/audiobooks/ranks/:tabId" element={<BookRankPage />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

const rankPath = (tagId: string, page = 1) =>
  `/audiobooks/ranks/1?tagId=${encodeURIComponent(tagId)}&page=${page}`

beforeEach(() => {
  queryClient.clear()
  queryClient.setDefaultOptions({ queries: { retry: false, staleTime: 30_000 } })
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('本测试不应触发网络请求')))
  vi.spyOn(player, 'play').mockResolvedValue()
  queryClient.setQueryData(['/library/favorites/tracks'], [])
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  usePlayingCollection.setState({ collectionId: null, trackIds: [] })
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('听书首页', () => {
  it('展示排行榜与小说专区条目，链接到榜单和专辑页', () => {
    queryClient.setQueryData(['/audiobooks/home'], home)
    renderHome()
    expect(screen.getByRole('heading', { level: 1, name: '听书' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '小编推荐' })).toBeVisible()
    expect(screen.getByRole('link', { name: /^热播榜/ })).toHaveAttribute('href', '/audiobooks/ranks/13')
    expect(screen.getByRole('link', { name: /^有声小说/ })).toHaveAttribute('href', '/audiobooks/ranks/1')
    // 不同榜单必须使用不同图标，而不是统一耳机占位。
    const emblems = document.querySelectorAll('.book-rank-emblem svg')
    expect(emblems[0]?.getAttribute('class')).toContain('lucide-flame')
    expect(emblems[1]?.getAttribute('class')).toContain('lucide-headphones')
    expect(screen.getByRole('link', { name: /打开有声专辑 话说泰山/ })).toHaveAttribute(
      'href',
      '/audiobooks/albums/kw%3Abook_album_102904',
    )
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('书轨提供翻页与换一批，封面使用磨砂角标和统一播放按钮', () => {
    queryClient.setQueryData(['/audiobooks/home'], home)
    renderHome()
    expect(screen.getAllByText('精选')).toHaveLength(2)
    expect(screen.queryByText('完整专辑')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '播放 话说泰山' })).toBeInTheDocument()
    const albumNames = () =>
      screen.getAllByRole('link', { name: /打开有声专辑/ }).map((link) => link.getAttribute('aria-label'))
    const before = albumNames()
    fireEvent.click(screen.getByRole('button', { name: '换一批' }))
    expect(albumNames()).toEqual([before[1], before[0]])
    expect(screen.getByRole('button', { name: '向前翻一批' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '向后翻一批' })).toBeDisabled()
  })

  it('目录为空时给出可重试的空状态，失败时展示错误', async () => {
    queryClient.setQueryData(['/audiobooks/home'], { channels: [], ranks: [] })
    renderHome()
    expect(screen.getByText('暂时没有听书内容')).toBeVisible()
    cleanup()
    queryClient.clear()
    renderHome()
    expect(await screen.findByText('加载失败')).toBeVisible()
  })
})

describe('听书榜单页', () => {
  it('展示子榜切换与专辑列表，专辑链接到章节页', async () => {
    queryClient.setQueryData([rankPath('')], rankResult)
    renderRank()
    expect(screen.getByRole('heading', { level: 1, name: '听书排行榜' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '有声小说' })).toBeVisible()
    expect(screen.getByRole('button', { name: '总榜' })).toHaveAttribute('aria-pressed', 'true')
    expect(
      screen.getByRole('button', { name: '总榜' }).querySelector('svg')?.getAttribute('class'),
    ).toContain('lucide-trophy')
    expect(
      screen.getByRole('button', { name: '悬疑热播榜' }).querySelector('svg')?.getAttribute('class'),
    ).toContain('lucide-ghost')
    expect(screen.getByRole('link', { name: /打开有声专辑 从赘婿到女帝宠臣/ })).toHaveAttribute(
      'href',
      '/audiobooks/albums/kw%3Abook_album_50927272',
    )
    expect(screen.getByText(/1592 集/)).toBeVisible()
    expect(screen.getByText(/共 2 部/)).toBeVisible()

    queryClient.setQueryData([rankPath('44')], {
      ...rankResult,
      tagId: '44',
      total: 1,
      items: [rankResult.items[1]!],
    })
    fireEvent.click(screen.getByRole('button', { name: '悬疑热播榜' }))
    await waitFor(() => expect(screen.getByText(/共 1 部/)).toBeVisible())
    expect(screen.getByRole('link', { name: /打开有声专辑 白眉大侠/ })).toHaveAttribute(
      'href',
      '/audiobooks/albums/kw%3Abook_album_101176',
    )
  })

  it('空子榜给出空状态，失败时展示错误', async () => {
    queryClient.setQueryData([rankPath('')], { ...rankResult, total: 0, items: [] })
    renderRank()
    expect(screen.getByText('这个子榜暂时没有内容')).toBeVisible()
    cleanup()
    queryClient.clear()
    renderRank()
    expect(await screen.findByText('加载失败')).toBeVisible()
  })

  it('切换子榜请求未完成时保留旧榜单内容', async () => {
    queryClient.setQueryData([rankPath('')], rankResult)
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
    renderRank()
    fireEvent.click(screen.getByRole('button', { name: '悬疑热播榜' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '悬疑热播榜' })).toHaveAttribute('aria-pressed', 'true'),
    )
    expect(screen.getByRole('link', { name: /打开有声专辑 从赘婿到女帝宠臣/ })).toBeVisible()
    expect(screen.getByText('正在更新')).toBeVisible()
  })

  it('深链页码超过实际页数时归一到最后一页', () => {
    queryClient.setQueryData([rankPath('', 50)], {
      ...rankResult,
      page: 50,
      total: 100,
      items: [],
    })
    renderRank('/audiobooks/ranks/1?page=50')
    expect(screen.getByText(/第 2 \/ 2 页/)).toBeVisible()
    expect(screen.queryByText(/第 50 \//)).not.toBeInTheDocument()
  })
})

describe('听书专辑页', () => {
  it('切换专辑 ID 时不展示旧内容，并保留原详情高度占位', async () => {
    const height = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(1_200)
    try {
      queryClient.setQueryData([`${albumPath}?page=1`], album)
      vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
      renderAlbumWithNavigation()

      expect(screen.getByRole('heading', { name: '盗墓笔记|周建龙演播' })).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: '切换到另一个专辑' }))

      await waitFor(() => expect(screen.getByText('正在加载…')).toBeVisible())
      expect(screen.queryByRole('heading', { name: '盗墓笔记|周建龙演播' })).not.toBeInTheDocument()
      expect(screen.queryByText('第001集')).not.toBeInTheDocument()
      expect(document.querySelector('.audiobook-album-content.is-switching')).toHaveStyle({
        minHeight: '1200px',
      })
    } finally {
      height.mockRestore()
    }
  })

  it('展示演播者、简介、章节与分页，并播放本页', async () => {
    queryClient.setQueryData([`${albumPath}?page=1`], album)
    renderAlbum()
    expect(screen.getByRole('heading', { name: '盗墓笔记|周建龙演播' })).toBeVisible()
    expect(screen.getByText('演播：一路听天下&周建龙')).toBeVisible()
    expect(screen.getByText(/播音：周建龙/)).toBeVisible()
    expect(screen.getByText('共 250 集 · 第 1 / 3 页')).toBeVisible()
    expect(screen.getByText('第001集')).toBeVisible()
    expect(screen.getByRole('button', { name: '收藏专辑' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /播放本页/ }))
    expect(player.play).toHaveBeenCalledWith(album.tracks?.[0], album.tracks, {
      preserveCollection: true,
    })

    queryClient.setQueryData([`${albumPath}?page=2`], {
      ...album,
      page: 2,
      tracks: [{ ...album.tracks?.[0], id: 'kw:193883469', title: '第101集' }],
    })
    fireEvent.click(screen.getByRole('button', { name: /下一页/ }))
    await waitFor(() => expect(screen.getByText('共 250 集 · 第 2 / 3 页')).toBeVisible())
    expect(screen.getByText('第101集')).toBeVisible()
  })

  it('越界页码回落到第一页而不是请求第 0 页', () => {
    queryClient.setQueryData([`${albumPath}?page=1`], album)
    renderAlbum(`${albumPath}?page=0`)
    expect(screen.getByText('共 250 集 · 第 1 / 3 页')).toBeVisible()
  })

  it('翻页请求未完成时保留上一页章节而不是整页消失', async () => {
    queryClient.setQueryData([`${albumPath}?page=1`], album)
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
    renderAlbum()
    expect(screen.getByText('第001集')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /下一页/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /下一页/ })).toBeDisabled())
    expect(screen.getByText('第001集')).toBeVisible()
    expect(screen.getByText('共 250 集 · 第 1 / 3 页')).toBeVisible()
    expect(screen.queryByText('第101集')).not.toBeInTheDocument()
  })

  it('听书专辑可收藏，收藏后按钮进入已收藏态', async () => {
    queryClient.setQueryData([`${albumPath}?page=1`], album)
    queryClient.setQueryData(['/library/favorites/playlists'], [])
    const requests: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        requests.push(`${init?.method || 'GET'} ${url}`)
        const json = (body: unknown) =>
          new Response(JSON.stringify(body), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        if (url.includes('/library/favorites/playlists'))
          return json([{ ...album, id: albumId, tracks: undefined }])
        if (url.includes('/library/favorites/tracks')) return json([])
        return json([])
      }),
    )
    renderAlbum()
    fireEvent.click(await screen.findByRole('button', { name: '收藏专辑' }))
    await waitFor(() =>
      expect(
        requests.some((entry) => entry.startsWith('POST') && entry.includes(encodeURIComponent(albumId))),
      ).toBe(true),
    )
    expect(await screen.findByRole('button', { name: '已收藏' })).toBeInTheDocument()
  })

  it('深链页码超过实际页数时归一到最后一页', () => {
    queryClient.setQueryData([`${albumPath}?page=50`], { ...album, page: 50, tracks: [] })
    renderAlbum(`${albumPath}?page=50`)
    expect(screen.getByText('共 250 集 · 第 3 / 3 页')).toBeVisible()
    expect(screen.queryByText(/第 50 \//)).not.toBeInTheDocument()
  })

  it('专辑读取失败时展示错误并可重试', async () => {
    renderAlbum()
    expect(await screen.findByText('加载失败')).toBeVisible()
  })
})

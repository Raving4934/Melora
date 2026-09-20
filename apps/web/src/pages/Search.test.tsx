import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { queryClient } from '../lib/api'
import type { SearchResult } from '../lib/types'
import { SearchPage } from './Search'

const result: SearchResult = {
  total: 2,
  tracks: [],
  playlists: [],
  artists: [],
  albums: [
    {
      id: 'kw:book_album_14593455',
      title: '雪中悍刀行',
      artist: '声娱文化',
      coverUrl: '',
      trackCount: 388,
    },
    {
      id: 'kw:book_album_14693800',
      title: '凡人修仙传',
      artist: '微媒有道',
      coverUrl: '',
      trackCount: 1926,
    },
  ],
}

function renderSearch(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <SearchPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  queryClient.clear()
  queryClient.setDefaultOptions({ queries: { retry: false, staleTime: 30_000 } })
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('本测试不应触发网络请求')))
  queryClient.setQueryData(['/library/favorites/tracks'], [])
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('搜索页有声书标签', () => {
  it('展示酷沃有声书结果并链接到章节页，同时隐藏平台筛选', () => {
    const q = '凡人修仙传'
    const path = `/search?q=${encodeURIComponent(q)}&type=book&source=kw&page=1`
    queryClient.setQueryData([path], result)
    renderSearch(`/search?q=${encodeURIComponent(q)}&type=book`)

    expect(screen.getByRole('button', { name: '有声书' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('有声书结果来自酷沃')).toBeVisible()
    expect(screen.getByRole('link', { name: /打开有声专辑 凡人修仙传/ })).toHaveAttribute(
      'href',
      '/audiobooks/albums/kw%3Abook_album_14693800',
    )
    expect(screen.getByRole('link', { name: /打开有声专辑 雪中悍刀行/ })).toHaveAttribute(
      'href',
      '/audiobooks/albums/kw%3Abook_album_14593455',
    )
    expect(screen.getByText(/1926 集/)).toBeVisible()
  })

  it('从其它类型切到有声书时改用酷沃目录，不携带平台筛选', () => {
    const q = '凡人修仙传'
    const bookPath = `/search?q=${encodeURIComponent(q)}&type=book&source=kw&page=1`
    queryClient.setQueryData([bookPath], result)
    renderSearch(`/search?q=${encodeURIComponent(q)}&type=track&source=all`)

    expect(screen.getByRole('button', { name: '歌曲' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: '有声书' }))
    expect(screen.getByRole('link', { name: /打开有声专辑 凡人修仙传/ })).toHaveAttribute(
      'href',
      '/audiobooks/albums/kw%3Abook_album_14693800',
    )
  })

  it('普通专辑结果继续按曲目搜索，不跳听书详情', () => {
    const q = '周杰伦'
    const path = `/search?q=${encodeURIComponent(q)}&type=album&source=kw&page=1`
    queryClient.setQueryData([path], {
      ...result,
      total: 1,
      albums: [
        {
          id: 'kw:album_14365066',
          title: 'Mojito',
          artist: '周杰伦',
          coverUrl: '',
          trackCount: 1,
        },
      ],
    })
    renderSearch(`/search?q=${encodeURIComponent(q)}&type=album&source=kw`)

    const link = screen.getByRole('link', { name: /Mojito/ })
    expect(link).toHaveAttribute('href', `/search?q=${encodeURIComponent('Mojito')}&type=track&source=kw`)
    expect(link.getAttribute('href')).not.toContain('/audiobooks/')
  })
})

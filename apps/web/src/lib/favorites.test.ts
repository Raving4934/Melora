import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { queryClient } from './api'
import { setFavorite } from './favorites'
import type { Collection, Track } from './types'

const song: Track = {
  id: 'wy:one',
  providerId: 'wy',
  title: 'One',
  artist: 'Artist',
  album: 'Album',
  duration: 60,
  coverUrl: '',
  qualities: ['128k'],
  canDownload: true,
}
const collection: Collection = {
  id: 'kw:book:one',
  providerId: 'kw',
  title: 'Book',
  description: '',
  coverUrl: '',
  trackCount: 1,
  category: 'audiobook',
  tracks: [song],
}
const listPath = '/library/favorites/playlists'
const ok = () => new Response('{"ok":true}', { headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  queryClient.clear()
  queryClient.setDefaultOptions({ queries: { retry: false } })
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ok()),
  )
})
afterEach(() => {
  queryClient.clear()
  vi.unstubAllGlobals()
})

it('收藏成功同步完整已知列表与计数，但不把整本章节塞进收藏摘要', async () => {
  queryClient.setQueryData([listPath], [])
  queryClient.setQueryData(['/library/summary'], { favoriteTracks: 9, favoritePlaylists: 0 })
  expect(await setFavorite('playlists', collection, true)).toBe(true)
  expect(queryClient.getQueryData([listPath])).toEqual([{ ...collection, tracks: undefined }])
  expect(queryClient.getQueryData(['/library/summary'])).toEqual({ favoriteTracks: 9, favoritePlaylists: 1 })
})

it('重复收藏不重复添加或改变已有顺序，取消只移除目标', async () => {
  const first = { ...song, id: 'wy:first' }
  const path = '/library/favorites/tracks'
  queryClient.setQueryData([path], [first, song])
  await setFavorite('tracks', song, true)
  expect(queryClient.getQueryData([path])).toEqual([first, song])
  await setFavorite('tracks', song, false)
  expect(queryClient.getQueryData([path])).toEqual([first])
  expect(vi.mocked(fetch).mock.calls.map(([, init]) => init?.method)).toEqual(['POST', 'DELETE'])
})

it('写入失败不乐观修改已知收藏状态', async () => {
  queryClient.setQueryData([listPath], [collection])
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('{"error":{"message":"保存失败"}}', { status: 503 })),
  )
  await expect(setFavorite('playlists', collection, false)).rejects.toThrow('保存失败')
  expect(queryClient.getQueryData([listPath])).toEqual([collection])
})

it('未知完整列表不被单个实体伪造成完整列表', async () => {
  await setFavorite('playlists', collection, true)
  expect(queryClient.getQueryData([listPath])).toBeUndefined()
})

it('旧读取会被取消，不能覆盖已经确认的收藏写入', async () => {
  queryClient.setQueryData([listPath], [])
  let signal: AbortSignal | undefined
  const oldRead = queryClient
    .fetchQuery({
      queryKey: [listPath],
      queryFn: (context) => {
        signal = context.signal
        return new Promise<Collection[]>(() => {})
      },
    })
    .catch(() => undefined)
  await setFavorite('playlists', collection, true)
  await oldRead
  expect(signal?.aborted).toBe(true)
  expect(queryClient.getQueryData([listPath])).toEqual([{ ...collection, tracks: undefined }])
})

it('会话切换后迟到的收藏响应不重建缓存或覆盖新会话', async () => {
  queryClient.setQueryData([listPath], [])
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
  const write = setFavorite('playlists', collection, true)
  queryClient.clear()
  const other = { ...collection, id: 'kw:book:other' }
  queryClient.setQueryData([listPath], [other])
  resolve(ok())
  expect(await write).toBe(false)
  expect(queryClient.getQueryData([listPath])).toEqual([other])
})

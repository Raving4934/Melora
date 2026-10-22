import { idPath, invalidate, queryClient, send } from './api'
import type { Collection, LibrarySummary, Track } from './types'

/** 写入确认后更新已知列表，后台刷新失败不能把按钮退回旧状态。 */
export async function setFavorite(kind: 'tracks' | 'playlists', item: Track | Collection, selected: boolean) {
  const path = `/library/favorites/${kind}`
  const filter = { queryKey: [path], exact: true }
  const cache = queryClient.getQueryCache()
  const owner = cache.find(filter)
  await send(`${path}/${idPath(item.id)}`, selected ? 'POST' : 'DELETE')
  // 登出或数据库切换会移除查询；迟到的写请求不能复活上一会话的数据。
  if (cache.find(filter) !== owner) return false
  await queryClient.cancelQueries(filter)
  if (cache.find(filter) !== owner) return false
  let count: number | undefined
  queryClient.setQueryData<(Track | Collection)[]>([path], (current) => {
    if (!current) return current
    const summary = kind === 'playlists' ? { ...item, tracks: undefined } : item
    const next = selected
      ? current.some((entry) => entry.id === item.id)
        ? current
        : [summary, ...current]
      : current.filter((entry) => entry.id !== item.id)
    count = next.length
    return next
  })
  if (count !== undefined) {
    const field = kind === 'tracks' ? 'favoriteTracks' : 'favoritePlaylists'
    queryClient.setQueryData<LibrarySummary>(['/library/summary'], (current) =>
      current ? { ...current, [field]: count } : current,
    )
  }
  await Promise.all([invalidate(path), invalidate('/library/summary')])
  return cache.find(filter) === owner
}

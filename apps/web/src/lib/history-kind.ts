import type { HistoryKind } from './types'

export const HISTORY_KIND_VALUES: HistoryKind[] = ['track', 'playlist', 'audiobook']

// 听书专辑使用独立命名空间；仅凭 collectionId 就能区分来源，无需请求上游。
export function isAudiobookCollection(id: string | null | undefined): boolean {
  return typeof id === 'string' && id.startsWith('kw:book_album_')
}

// 只有当前曲目确实属于最近一次集合播放时才标记上下文，避免陈旧上下文误分类。
export function historyKindFor(
  collectionId: string | null,
  trackIds: string[],
  trackId: string,
): HistoryKind {
  if (!collectionId || !trackIds.includes(trackId)) return 'track'
  return isAudiobookCollection(collectionId) ? 'audiobook' : 'playlist'
}

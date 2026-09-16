import { describe, expect, it } from 'vitest'
import { historyKindFor, isAudiobookCollection } from './history-kind'

describe('播放历史来源判定', () => {
  it('识别听书专辑命名空间', () => {
    expect(isAudiobookCollection('kw:book_album_10250871')).toBe(true)
    expect(isAudiobookCollection('wy:playlist_1')).toBe(false)
    expect(isAudiobookCollection('wy:playlist_book_album_1')).toBe(false)
    expect(isAudiobookCollection(null)).toBe(false)
  })

  it('只有当前曲目属于活跃集合时才标记来源', () => {
    const tracks = ['kw:1', 'kw:2']
    expect(historyKindFor('wy:playlist_1', tracks, 'kw:1')).toBe('playlist')
    expect(historyKindFor('kw:book_album_9', tracks, 'kw:2')).toBe('audiobook')
    expect(historyKindFor('wy:playlist_1', tracks, 'kw:404')).toBe('track')
    expect(historyKindFor(null, tracks, 'kw:1')).toBe('track')
    expect(historyKindFor('wy:playlist_1', [], 'kw:1')).toBe('track')
  })
})

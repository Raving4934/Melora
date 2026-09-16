import { describe, expect, it } from 'vitest'
import type { Collection } from '../lib/types'
import {
  chartHeroSize,
  chartKindOf,
  chartPreviewSize,
  featuredCharts,
  filterCharts,
  isChartKind,
} from './browse-data'

const chart = (id: string, title: string, providerId = 'wy', category = '榜单') =>
  ({ id, title, providerId, category, description: '', coverUrl: '', trackCount: 100 }) as Collection

const charts = Array.from({ length: 164 }, (_, index) =>
  chart(
    `chart:${index}`,
    `榜单 ${index}`,
    ['wy', 'tx', 'kw', 'kg', 'mg'][Math.min(4, Math.floor(index / 33))],
  ),
)

describe('排行榜目录数据', () => {
  it('焦点区保持四榜并优先覆盖不同平台', () => {
    const featured = featuredCharts(charts)
    expect(featured).toHaveLength(chartHeroSize)
    expect(featured.map((item) => item.providerId)).toEqual(['wy', 'tx', 'kw', 'kg'])
    expect(new Set(featured.map((item) => item.id)).size).toBe(featured.length)
    expect(featuredCharts([])).toEqual([])
    expect(featuredCharts([charts[0]!, charts[0]!])).toHaveLength(1)
    expect(chartPreviewSize).toBe(3)
  })

  it('按语种、流派、场景分类，未命中规则的榜单归入官方综合', () => {
    expect(chartKindOf(chart('1', '华语新歌榜'))).toBe('language')
    expect(chartKindOf(chart('2', '摇滚热歌榜'))).toBe('genre')
    expect(chartKindOf(chart('3', '影视金曲榜'))).toBe('scene')
    expect(chartKindOf(chart('4', '平台飙升榜'))).toBe('official')
    expect(isChartKind('genre')).toBe(true)
    expect(isChartKind('unknown')).toBe(false)
  })

  it('搜索同时匹配标题、分类与描述，并可叠加类型筛选', () => {
    const items = [
      chart('1', '网易云飙升榜'),
      chart('2', '欧美新歌榜'),
      { ...chart('3', '编辑精选'), category: '电子' },
      { ...chart('4', '每日推荐'), description: '适合运动时收听' },
    ]
    expect(filterCharts(items, 'all', '网易云').map((item) => item.id)).toEqual(['1'])
    expect(filterCharts(items, 'language', '').map((item) => item.id)).toEqual(['2'])
    expect(filterCharts(items, 'genre', '精选').map((item) => item.id)).toEqual(['3'])
    expect(filterCharts(items, 'scene', '运动').map((item) => item.id)).toEqual(['4'])
  })
})

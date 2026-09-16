import type { Collection } from '../lib/types'

export const chartHeroSize = 4
export const chartPreviewSize = 3

export type ChartKind = 'all' | 'official' | 'language' | 'genre' | 'scene'
export type ClassifiedChartKind = Exclude<ChartKind, 'all'>

export const chartKindOptions: ReadonlyArray<{ id: ChartKind; label: string }> = [
  { id: 'all', label: '全部' },
  { id: 'official', label: '官方 / 综合' },
  { id: 'language', label: '语种榜' },
  { id: 'genre', label: '风格流派' },
  { id: 'scene', label: '场景 / 特色' },
]

export const chartKindSections: ReadonlyArray<{
  id: ClassifiedChartKind
  title: string
  description: string
}> = [
  { id: 'official', title: '官方榜 / 综合热度', description: '热歌、新歌、飙升与平台综合榜' },
  { id: 'language', title: '语种榜', description: '华语、欧美、日韩与地区语言榜' },
  { id: 'genre', title: '风格流派', description: '摇滚、说唱、电子、民谣等类型榜' },
  { id: 'scene', title: '场景 / 特色', description: '影视、游戏、运动、怀旧与主题榜' },
]

const languageKeywords = [
  '华语',
  '国语',
  '粤语',
  '欧美',
  '日韩',
  '日语',
  '韩语',
  '英语',
  '中文',
  '俄语',
  '法语',
  '德语',
  '泰语',
  '越南',
]
const genreKeywords = [
  '摇滚',
  '说唱',
  '嘻哈',
  '民谣',
  '电子',
  '爵士',
  '古典',
  '国风',
  'acg',
  'dj',
  '舞曲',
  'r&b',
  '蓝调',
  '乡村',
  '金属',
  '朋克',
  '轻音乐',
]
const sceneKeywords = [
  '影视',
  '电影',
  '电视剧',
  '动漫',
  '动画',
  '游戏',
  '车载',
  '运动',
  '学习',
  '睡眠',
  'ktv',
  '网络',
  '校园',
  '情歌',
  '怀旧',
  '抖音',
  '短视频',
  '综艺',
  '咖啡',
  '夜店',
  '铃声',
]

function chartSearchText(chart: Collection) {
  return `${chart.title} ${chart.category || ''} ${chart.description || ''}`.toLocaleLowerCase('zh-CN')
}

export function isChartKind(value: string | null): value is ChartKind {
  return chartKindOptions.some((item) => item.id === value)
}

export function chartKindOf(chart: Collection): ClassifiedChartKind {
  const text = chartSearchText(chart)
  if (languageKeywords.some((keyword) => text.includes(keyword))) return 'language'
  if (genreKeywords.some((keyword) => text.includes(keyword))) return 'genre'
  if (sceneKeywords.some((keyword) => text.includes(keyword))) return 'scene'
  return 'official'
}

export function filterCharts(items: Collection[], kind: ChartKind, query: string) {
  const keyword = query.trim().toLocaleLowerCase('zh-CN')
  return items.filter(
    (item) =>
      (kind === 'all' || chartKindOf(item) === kind) && (!keyword || chartSearchText(item).includes(keyword)),
  )
}

// 精选面板轮流取各平台榜单；服务端只为前六个焦点榜读取歌曲预览。
export function featuredCharts(items: Collection[]) {
  const groups = new Map<string, Collection[]>()
  const seen = new Set<string>()
  for (const item of items) {
    if (seen.has(item.id)) continue
    seen.add(item.id)
    const group = groups.get(item.providerId) || []
    group.push(item)
    groups.set(item.providerId, group)
  }
  const result: Collection[] = []
  for (let row = 0; result.length < chartHeroSize; row++) {
    let added = false
    for (const group of groups.values()) {
      if (group[row]) {
        result.push(group[row])
        added = true
        if (result.length === chartHeroSize) return result
      }
    }
    if (!added) return result
  }
  return result
}

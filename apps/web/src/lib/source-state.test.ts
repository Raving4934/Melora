import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { APIError, queryClient } from './api'
import {
  guardOnlineOperation,
  handleSourceRequiredError,
  readSourceSnapshot,
  sourceReadiness,
} from './source-state'
import type { Provider, Track } from './types'
import { useUI } from '../stores/ui'

const liveTrack: Pick<Track, 'id' | 'providerId'> = { id: 'wy:online-track', providerId: 'wy' }
const demoTrack: Pick<Track, 'id' | 'providerId'> = { id: 'demo:one', providerId: 'demo' }

function provider(overrides: Partial<Provider> = {}): Provider {
  return {
    id: 'wy',
    name: '网抑云',
    description: '真实音乐目录',
    enabled: true,
    isDemo: false,
    status: 'catalog',
    capabilities: {
      search: true,
      charts: true,
      playlists: true,
      recommendations: true,
      play: true,
      download: true,
    },
    ...overrides,
  }
}

beforeEach(() => {
  queryClient.clear()
  useUI.setState({ sourceGate: null })
})
afterEach(() => {
  queryClient.clear()
  useUI.setState({ sourceGate: null })
})

describe('普通用户可见的源能力门禁', () => {
  it('优先使用 providers capability，不要求普通用户读取管理员 sources', () => {
    queryClient.setQueryData(['/sources'], {
      items: [],
      activeSourceId: '',
      available: false,
    })
    queryClient.setQueryData(
      ['/providers'],
      [provider({ capabilities: { ...provider().capabilities, play: true } })],
    )

    expect(readSourceSnapshot().readiness).toBe('ready')
    expect(guardOnlineOperation(liveTrack, 'play')).toBe(true)
    expect(useUI.getState().sourceGate).toBeNull()
  })

  it('只按当前曲目的 provider capability 判定，不把其它平台的可用源当成支持', () => {
    queryClient.setQueryData(['/providers'], [provider()])

    expect(guardOnlineOperation({ id: 'tx:online-track', providerId: 'tx' }, 'play')).toBe(false)
    expect(useUI.getState().sourceGate).toBe('play')
  })

  it('providers 已明确无播放能力时拦截在线播放并打开设置入口', () => {
    queryClient.setQueryData(
      ['/providers'],
      [provider({ capabilities: { ...provider().capabilities, play: false } })],
    )

    expect(guardOnlineOperation(liveTrack, 'play')).toBe(false)
    expect(useUI.getState().sourceGate).toBe('play')
  })

  it('下载能力还包含保存目录状态，play 可用时不误导到源设置', () => {
    queryClient.setQueryData(
      ['/providers'],
      [provider({ capabilities: { ...provider().capabilities, play: true, download: false } })],
    )

    expect(guardOnlineOperation(liveTrack, 'play')).toBe(true)
    expect(guardOnlineOperation(liveTrack, 'download')).toBe(true)
    expect(useUI.getState().sourceGate).toBeNull()
  })

  it('下载同样需要在线地址时，使用 play capability 进行无源拦截', () => {
    queryClient.setQueryData(
      ['/providers'],
      [provider({ capabilities: { ...provider().capabilities, play: false, download: false } })],
    )

    expect(guardOnlineOperation(liveTrack, 'download')).toBe(false)
    expect(useUI.getState().sourceGate).toBe('download')
  })

  it('初始化没有可用缓存时保持 unknown，不把加载中误报为无源', () => {
    expect(readSourceSnapshot().readiness).toBe('unknown')
    expect(guardOnlineOperation(liveTrack, 'play')).toBe(true)
    expect(useUI.getState().sourceGate).toBeNull()
  })

  it('unavailable 缓存正在失效刷新时返回 pending，不用旧状态拦截点击', async () => {
    const unavailable = provider({ capabilities: { ...provider().capabilities, play: false } })
    queryClient.setQueryData(['/providers'], [unavailable])
    await queryClient.invalidateQueries({ queryKey: ['/providers'], refetchType: 'none' })
    expect(readSourceSnapshot().readiness).toBe('pending')
    expect(guardOnlineOperation(liveTrack, 'play')).toBe(true)

    let release!: (value: Provider[]) => void
    const refetch = queryClient.fetchQuery<Provider[]>({
      queryKey: ['/providers'],
      staleTime: 0,
      queryFn: () =>
        new Promise<Provider[]>((resolve) => {
          release = resolve
        }),
    })
    await Promise.resolve()

    expect(readSourceSnapshot().readiness).toBe('pending')
    expect(guardOnlineOperation(liveTrack, 'play')).toBe(true)
    expect(useUI.getState().sourceGate).toBeNull()

    release([provider()])
    await refetch
    expect(readSourceSnapshot().readiness).toBe('ready')
  })

  it('演示曲目不经过在线源门禁', () => {
    queryClient.setQueryData(
      ['/providers'],
      [provider({ capabilities: { ...provider().capabilities, play: false } })],
    )

    expect(guardOnlineOperation(demoTrack, 'play')).toBe(true)
    expect(useUI.getState().sourceGate).toBeNull()
  })

  it('仅有演示 provider 时不放行新的真实在线曲目', () => {
    queryClient.setQueryData(
      ['/providers'],
      [
        {
          ...provider(),
          id: 'demo',
          name: '公开授权演示',
          isDemo: true,
        },
      ],
    )

    expect(guardOnlineOperation(liveTrack, 'play')).toBe(false)
    expect(useUI.getState().sourceGate).toBe('play')
  })
})

describe('结构化无源错误', () => {
  it('识别后端稳定错误码并统一打开源设置入口', () => {
    const error = new APIError('尚未选择可用的 LX 音源', 409, 'lx_source_required')

    expect(handleSourceRequiredError(error, 'play')).toBe(true)
    expect(useUI.getState().sourceGate).toBe('play')
  })

  it('sources 管理态仅在 providers 未加载时作为已加载缓存的回退，available 不替代播放能力', () => {
    expect(sourceReadiness({ items: [], activeSourceId: '', available: true })).toBe('unavailable')
    queryClient.setQueryData(['/sources'], { items: [], activeSourceId: '', available: false })

    expect(readSourceSnapshot().readiness).toBe('unavailable')
  })

  it('只识别本次统一错误码，不按旧错误码或文案走兼容双轨', () => {
    const oldShape = new APIError('尚未选择可用的 LX 音源', 502, 'lx_resolve_failed')
    const invalidMedia = new APIError('音源脚本返回结果无效', 502, 'invalid_media_result')

    expect(handleSourceRequiredError(oldShape, 'play')).toBe(false)
    expect(handleSourceRequiredError(invalidMedia, 'play')).toBe(false)
    expect(useUI.getState().sourceGate).toBeNull()
  })
})

import type { QueryClient } from '@tanstack/react-query'
import { APIError, queryClient, useAPI } from './api'
import type { SourcesState } from './source-types'
import type { Provider, Track } from './types'
import { useUI, type SourceGateOperation } from '../stores/ui'

export const SOURCES_QUERY_KEY = ['/sources'] as const
export const PROVIDERS_QUERY_KEY = ['/providers'] as const
export const SOURCE_REQUIRED_MESSAGE = '请先在设置中导入并启用一个可用的自定义音源。'

type SourceReadiness = 'pending' | 'unknown' | 'ready' | 'unavailable' | 'demo'

export interface SourceSnapshot {
  data?: SourcesState
  providers?: Provider[]
  readiness: SourceReadiness
}

export function activeSourceReady(data: SourcesState | undefined, providerId?: string) {
  if (!data || data.demoMode) return false
  const active = data.items.find((source) => source.id === data.activeSourceId && source.status === 'ready')
  if (!active) return false
  if (!providerId) return true
  const platform = active.platforms?.[providerId]
  return !!platform && platform.actions.includes('musicUrl') && (platform.qualitys?.length ?? 0) > 0
}

export function sourceReadiness(data: SourcesState | undefined, providerId?: string): SourceReadiness {
  if (!data) return 'unknown'
  if (data.demoMode) return 'demo'
  // `available` 只表示 LX 模块是否存在；是否能播放必须看实际 active ready 源及平台能力。
  return activeSourceReady(data, providerId) ? 'ready' : 'unavailable'
}

function providerCapability(providers: Provider[] | undefined, providerId?: string): SourceReadiness {
  if (!providers) return 'unknown'
  if (providers.length === 0) return 'unavailable'
  const liveProviders = providers.filter((provider) => !provider.isDemo && provider.id !== 'demo')
  if (liveProviders.length === 0) return providerId ? 'unavailable' : 'demo'
  const relevantProviders = providerId
    ? liveProviders.filter((provider) => provider.id === providerId)
    : liveProviders
  if (relevantProviders.length === 0) return 'unavailable'
  const knownProviders = relevantProviders.filter(
    (provider) => typeof provider.capabilities?.play === 'boolean',
  )
  if (knownProviders.length === 0) return 'unknown'
  return knownProviders.some((provider) => provider.enabled && provider.capabilities.play === true)
    ? 'ready'
    : 'unavailable'
}

function queryRefreshing(client: QueryClient | undefined, key: readonly [string]) {
  const state = client?.getQueryState(key)
  return state?.isInvalidated === true || state?.status === 'pending' || state?.fetchStatus === 'fetching'
}

/**
 * `/providers` 是普通用户可见的能力投影，优先用于门禁；`/sources` 仅在已加载时作为设置管理态补充。
 * query 正在初始化或失效刷新时优先返回 pending，不能用上一份 unavailable 缓存拦截用户。
 */
export function readSourceSnapshot(client: QueryClient = queryClient, providerId?: string): SourceSnapshot {
  const providers = client?.getQueryData<Provider[]>(PROVIDERS_QUERY_KEY)
  const data = client?.getQueryData<SourcesState>(SOURCES_QUERY_KEY)
  if (queryRefreshing(client, PROVIDERS_QUERY_KEY)) return { data, providers, readiness: 'pending' }

  const providersReadiness = providerCapability(providers, providerId)
  if (providersReadiness !== 'unknown') return { data, providers, readiness: providersReadiness }
  if (queryRefreshing(client, SOURCES_QUERY_KEY)) return { data, providers, readiness: 'pending' }
  const managedReadiness = sourceReadiness(data, providerId)
  if (managedReadiness !== 'unknown') return { data, providers, readiness: managedReadiness }
  return { data, providers, readiness: 'unknown' }
}

/**
 * App 认证完成后挂载一次，预加载普通用户可见的 capability 状态；设置页的 `/sources` 只在进入设置时读取。
 */
export function SourceStateBootstrap() {
  useAPI<Provider[]>('/providers')
  return null
}

export function isDemoTrack(track: Pick<Track, 'id' | 'providerId'>) {
  return track.providerId === 'demo' || track.id.startsWith('demo:')
}

export function isSourceRequiredError(error: unknown): error is APIError {
  return !!APIError && error instanceof APIError && error.code === 'lx_source_required'
}

export function openSourceSetup(operation: SourceGateOperation) {
  useUI.getState().openSourceGate(operation)
}

/**
 * 只在已确认当前操作没有可见 capability 时拦截；初始化 pending/缓存缺失/缓存请求失败均交给后端真实结果处理。
 */
export function guardOnlineOperation(
  track: Pick<Track, 'id' | 'providerId'>,
  operation: SourceGateOperation,
) {
  if (isDemoTrack(track)) return true
  // providers.download 还包含保存目录/授权状态；源门禁只判断是否有在线播放能力。
  const { readiness } = readSourceSnapshot(queryClient, track.providerId)
  if (readiness !== 'unavailable' && readiness !== 'demo') return true
  openSourceSetup(operation)
  return false
}

export function handleSourceRequiredError(error: unknown, operation: SourceGateOperation) {
  if (!isSourceRequiredError(error)) return false
  openSourceSetup(operation)
  return true
}

import { keepPreviousData, QueryCache, QueryClient, useQuery } from '@tanstack/react-query'
import { createContext, createElement, useContext, useSyncExternalStore, type ReactNode } from 'react'
import { notify } from '../stores/ui'
import { apiURL, appBasePath } from './base'
import type { Session } from './types'
export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      if (query.state.data !== undefined) notify(`更新失败，已保留现有内容：${error.message}`, 'error')
    },
  }),
  defaultOptions: { queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false } },
})
function gatewaySessionLost(message: string) {
  void queryClient.cancelQueries({ predicate: (query) => query.queryKey[0] !== '/auth/session' })
  queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== '/auth/session' })
  queryClient.setQueryData(['/auth/session'], {
    authenticated: false,
    required: false,
    authMode: 'fnos',
    error: message,
  })
}
export interface PlaybackFailure {
  stage: 'catalog' | 'resolve'
  attempts: number
  autoSwitch?: boolean
}
function playbackFailure(path: string, headers: Headers): PlaybackFailure | undefined {
  if (!/^\/tracks\/[^/]+\/(play-info|play)(\?|$)/.test(path)) return
  const stage = headers.get('X-Melora-Resolve-Stage')
  const count = headers.get('X-Melora-Resolve-Attempts')
  if ((stage !== 'catalog' && stage !== 'resolve') || count === null || !/^[0-3]$/.test(count)) return
  const mode = headers.get('X-Melora-Auto-Switch')
  return {
    stage,
    attempts: Number(count),
    ...(mode === 'true' || mode === 'false' ? { autoSwitch: mode === 'true' } : {}),
  }
}
export class APIError extends Error {
  constructor(
    message: string,
    public status: number,
    public code: string,
    public resolution?: PlaybackFailure,
  ) {
    super(message)
    this.name = 'APIError'
  }
}
interface GatewaySession {
  authenticated: boolean
  csrfToken?: string
}
async function gatewayCSRF(force: boolean, signal?: AbortSignal | null): Promise<string> {
  let session = force ? undefined : queryClient.getQueryData<GatewaySession>(['/auth/session'])
  if (!session?.csrfToken) {
    session = await api<GatewaySession>('/auth/session', { signal })
    queryClient.setQueryData(['/auth/session'], session)
  }
  if (!session.authenticated) {
    gatewaySessionLost('请使用飞牛管理员账号重新登录。')
    throw new APIError('飞牛管理员会话已失效，请重新打开应用。', 401, 'fnos_identity_required')
  }
  if (!session.csrfToken)
    throw new APIError('无法取得写操作校验信息，请关闭并重新打开应用。', 403, 'csrf_unavailable')
  return session.csrfToken
}
export async function api<T>(path: string, init: RequestInit = {}, retryCSRF = true): Promise<T> {
  const method = (init.method || 'GET').toUpperCase()
  const mutation = !['GET', 'HEAD', 'OPTIONS'].includes(method)
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type'))
    headers.set('Content-Type', 'application/json')
  if (method === 'GET') headers.set('X-Melora-Origin', window.location.origin)
  if (appBasePath && mutation) headers.set('X-Melora-CSRF', await gatewayCSRF(false, init.signal))
  let response: Response
  try {
    response = await fetch(apiURL(path), {
      credentials: 'same-origin',
      ...init,
      headers,
      signal: init.signal ?? AbortSignal.timeout(15_000),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new APIError('无法连接服务，请确认后端已启动后重试。', 0, 'NETWORK_ERROR')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    if (
      appBasePath &&
      mutation &&
      retryCSRF &&
      response.status === 403 &&
      body?.error?.code === 'csrf_rejected'
    ) {
      // 校验失败在写入前返回，允许更新会话后最多重发一次，不重复成功写操作。
      await gatewayCSRF(true, init.signal)
      return api<T>(path, init, false)
    }
    if (
      appBasePath &&
      (response.status === 401 ||
        (response.status === 403 && (!body?.error?.code || String(body.error.code).startsWith('fnos_'))))
    ) {
      gatewaySessionLost(body?.error?.message || '请使用飞牛管理员账号重新登录。')
    }
    if (!appBasePath && response.status === 401 && path !== '/auth/session')
      void queryClient.invalidateQueries({ queryKey: ['/auth/session'] })
    throw new APIError(
      body?.error?.message || `请求失败（${response.status}）`,
      response.status,
      body?.error?.code || 'REQUEST_FAILED',
      playbackFailure(path, response.headers),
    )
  }
  if (response.status === 204) return undefined as T
  if (!response.headers.get('Content-Type')?.includes('application/json')) {
    if (appBasePath) gatewaySessionLost('飞牛登录已失效或网关尚未就绪，请重新打开应用。')
    throw new APIError(
      appBasePath
        ? '飞牛登录已失效或网关尚未就绪，请回到飞牛桌面重新打开乐屿。'
        : '服务未返回有效的 JSON，请检查后端是否正常启动。',
      response.status,
      'INVALID_API_RESPONSE',
    )
  }
  const result = (await response.json()) as T
  if (method === 'GET' && /^\/(charts|playlists|search)(?:[?\/]|$)/.test(path)) {
    const unavailable = [
      ...new Set(
        (response.headers.get('X-Melora-Unavailable-Sources') || '')
          .slice(0, 256)
          .split(',')
          .map((id) => id.trim())
          .filter((id) => ['wy', 'tx', 'kw', 'kg', 'mg'].includes(id)),
      ),
    ]
    const issues: Record<string, CatalogIssueCode> = {}
    const rawIssues = response.headers.get('X-Melora-Catalog-Issues') || ''
    // 可选、能力级诊断：只接受有界白名单；不把任意响应文本/URL放进提示。
    if (rawIssues.length <= 512) {
      for (const pair of rawIssues.split(',').slice(0, 10)) {
        const [source, code, extra] = pair.split('=')
        if (
          source &&
          code &&
          extra === undefined &&
          unavailable.includes(source) &&
          catalogIssueCodes.has(code as CatalogIssueCode) &&
          !Object.hasOwn(issues, source)
        )
          issues[source] = code as CatalogIssueCode
      }
    }
    queryClient.setQueryData(['catalog-warnings', path], unavailable)
    queryClient.setQueryData(['catalog-issues', path], issues)
    const more = response.headers.get('X-Melora-Has-More')
    queryClient.setQueryData(
      ['catalog-pagination', path],
      more === 'true' ? true : more === 'false' ? false : null,
    )
  }
  return result
}
export function useAPI<T>(path: string, enabled = true) {
  const query = useQuery<T>({
    queryKey: [path],
    queryFn: ({ signal }) => api<T>(path, { signal }),
    enabled,
    placeholderData: keepPreviousData,
    refetchInterval: appBasePath && path === '/auth/session' ? 30_000 : false,
    refetchIntervalInBackground: !!appBasePath && path === '/auth/session',
  })
  // 后台失败仅通知，不用整块错误占位替换仍可使用的数据。
  return {
    ...query,
    error: query.data !== undefined ? null : query.error,
    backgroundError: query.data !== undefined ? query.error : null,
  }
}
export function send<T>(path: string, method: string, body?: unknown) {
  return api<T>(path, { method, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) })
}
type DeployContextValue = boolean
const DeployContext = createContext<DeployContextValue | undefined>(undefined)
const noCloudDeploy = () => false
const noDeploySubscription = () => () => undefined
const cachedCloudDeploy = () => queryClient.getQueryData<Session>(['/auth/session'])?.deployMode === 'cloud'
const subscribeCachedDeploy = (onStoreChange: () => void) => {
  const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
    if (event.query.queryKey[0] === '/auth/session') onStoreChange()
  })
  if (queryClient.getQueryData(['/auth/session']) === undefined) {
    void queryClient
      .fetchQuery<Session>({
        queryKey: ['/auth/session'],
        queryFn: ({ signal }) => api<Session>('/auth/session', { signal }),
      })
      .catch(() => undefined)
  }
  return unsubscribe
}

/**
 * 在应用根部提供稳定的部署模式；context 值只依赖 deployMode，session 心跳刷新不会放大列表重渲染。
 */
export function SessionProvider({ session, children }: { session?: Session; children: ReactNode }) {
  const cloud = session?.deployMode === 'cloud'
  return createElement(DeployContext.Provider, { value: cloud }, children)
}

// cloud 部署只提供在线播放，全部下载入口必须隐藏。Provider 外保留响应式缓存兼容能力。
export function useCloudDeploy(): boolean {
  const contextValue = useContext(DeployContext)
  const cachedValue = useSyncExternalStore(
    contextValue === undefined ? subscribeCachedDeploy : noDeploySubscription,
    contextValue === undefined ? cachedCloudDeploy : noCloudDeploy,
    noCloudDeploy,
  )
  // 正式应用位于 Provider 内且不订阅 QueryCache；独立挂载仅监听 auth/session 的布尔快照。
  return contextValue ?? cachedValue
}
export function invalidate(prefix: string) {
  return queryClient.invalidateQueries({ predicate: (query) => String(query.queryKey[0]).startsWith(prefix) })
}
export const idPath = (id: string) => encodeURIComponent(id)

export type CatalogIssueCode =
  | 'cancelled'
  | 'timeout'
  | 'unsupported'
  | 'invalid_response'
  | 'upstream_rejected'
  | 'access_restricted'
  | 'rate_limited'
  | 'upstream_unavailable'
const catalogIssueCodes = new Set<CatalogIssueCode>([
  'cancelled',
  'timeout',
  'unsupported',
  'invalid_response',
  'upstream_rejected',
  'access_restricted',
  'rate_limited',
  'upstream_unavailable',
])
export function useCatalogIssues(path: string) {
  return useQuery<Record<string, CatalogIssueCode>>({
    queryKey: ['catalog-issues', path],
    queryFn: () => ({}),
    enabled: false,
    initialData: {},
    staleTime: Infinity,
  }).data
}

export function useCatalogWarnings(path: string) {
  return useQuery<string[]>({
    queryKey: ['catalog-warnings', path],
    queryFn: () => [],
    enabled: false,
    initialData: [],
    staleTime: Infinity,
  }).data
}

export function useCatalogPagination(path: string) {
  return useQuery<boolean | null>({
    queryKey: ['catalog-pagination', path],
    queryFn: () => null,
    enabled: false,
    initialData: null,
    staleTime: Infinity,
  }).data
}

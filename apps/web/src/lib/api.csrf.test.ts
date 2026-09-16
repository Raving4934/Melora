import { afterEach, describe, expect, it, vi } from 'vitest'
vi.mock('./base', () => ({
  appBasePath: '/app/melora',
  apiURL: (path: string) => '/app/melora/api/v1' + path,
}))
import { api, queryClient } from './api'
const response = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
afterEach(() => {
  queryClient.clear()
  vi.unstubAllGlobals()
})
describe('网关CSRF协议', () => {
  it('会话GET声明浏览器origin，不依赖被反代改写的Host', async () => {
    const fetcher = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
      response({ authenticated: true, csrfToken: 'token' }),
    )
    vi.stubGlobal('fetch', fetcher)
    await api('/auth/session')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('X-Melora-Origin')).toBe(
      window.location.origin,
    )
  })
  it('大JS的FormData附带令牌，Content-Type边界仍由浏览器生成', async () => {
    queryClient.setQueryData(['/auth/session'], { authenticated: true, csrfToken: 'cached-token' })
    const fetcher = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) =>
      response({ status: 'ready' }, 201),
    )
    vi.stubGlobal('fetch', fetcher)
    const body = new FormData()
    body.append('file', new Blob(['x'.repeat(100 * 1024)]), 'test.js')
    await api('/sources/import', { method: 'POST', body })
    const request = fetcher.mock.calls[0]![1]!
    expect(request.body).toBe(body)
    expect(new Headers(request.headers).get('X-Melora-CSRF')).toBe('cached-token')
    expect(new Headers(request.headers).has('Content-Type')).toBe(false)
  })
  it('没有缓存时先取得当前用户令牌，再发送JSON写入', async () => {
    const fetcher = vi.fn(async (url: RequestInfo | URL, _init?: RequestInit) =>
      response(
        String(url).endsWith('/auth/session')
          ? { authenticated: true, csrfToken: 'new-token' }
          : { ok: true },
      ),
    )
    vi.stubGlobal('fetch', fetcher)
    await api('/sources/active', { method: 'PUT', body: JSON.stringify({ id: 'source' }) })
    expect(fetcher).toHaveBeenCalledTimes(2)
    const headers = new Headers(fetcher.mock.calls[1]![1]!.headers)
    expect(headers.get('X-Melora-CSRF')).toBe('new-token')
    expect(headers.get('Content-Type')).toBe('application/json')
  })
  it('服务重启或令牌过期仅刷新并重试一次，不把CSRF误报为管理员退出', async () => {
    queryClient.setQueryData(['/auth/session'], { authenticated: true, csrfToken: 'expired-token' })
    let writes = 0
    const fetcher = vi.fn(async (url: RequestInfo | URL, _init?: RequestInit) => {
      if (String(url).endsWith('/auth/session'))
        return response({ authenticated: true, csrfToken: 'fresh-token' })
      writes++
      return writes === 1
        ? response({ error: { code: 'csrf_rejected', message: 'expired' } }, 403)
        : response({ ok: true })
    })
    vi.stubGlobal('fetch', fetcher)
    await api('/library/history', { method: 'DELETE' })
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(new Headers(fetcher.mock.calls[2]![1]!.headers).get('X-Melora-CSRF')).toBe('fresh-token')
    expect(queryClient.getQueryData(['/auth/session'])).toMatchObject({ authenticated: true })
  })
  it('重复校验失败不会无限重试或执行无令牌写操作', async () => {
    queryClient.setQueryData(['/auth/session'], { authenticated: true, csrfToken: 'expired-token' })
    const fetcher = vi.fn(async (url: RequestInfo | URL, _init?: RequestInit) =>
      String(url).endsWith('/auth/session')
        ? response({ authenticated: true, csrfToken: 'fresh-token' })
        : response({ error: { code: 'csrf_rejected', message: 'rejected' } }, 403),
    )
    vi.stubGlobal('fetch', fetcher)
    await expect(api('/library/history', { method: 'DELETE' })).rejects.toMatchObject({
      code: 'csrf_rejected',
    })
    expect(fetcher).toHaveBeenCalledTimes(3)
  })
})

import { afterEach, expect, it, vi } from 'vitest'
import { api, queryClient } from './api'

afterEach(() => {
  queryClient.clear()
  vi.unstubAllGlobals()
})
function respond(unavailable = 'tx', issues?: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(JSON.stringify([{ id: 'wy:success' }]), {
          headers: {
            'Content-Type': 'application/json',
            'X-Melora-Unavailable-Sources': unavailable,
            ...(issues === undefined ? {} : { 'X-Melora-Catalog-Issues': issues }),
          },
        }),
    ),
  )
}
it('保留目录数组合同，并读取仅限本次失败平台的安全原因', async () => {
  respond('tx', 'tx=access_restricted,wy=timeout')
  await expect(api('/search?q=fixture')).resolves.toEqual([{ id: 'wy:success' }])
  expect(queryClient.getQueryData(['catalog-warnings', '/search?q=fixture'])).toEqual(['tx'])
  expect(queryClient.getQueryData(['catalog-issues', '/search?q=fixture'])).toEqual({
    tx: 'access_restricted',
  })
})
it.each([
  'tx=secret-url',
  'tx=https://private.example/token',
  '__proto__=timeout',
  'tx=access_restricted=secret',
  'tx=access_restricted,' + 'x'.repeat(513),
])('恶意/过长诊断不进入展示缓存：%s', async (header) => {
  respond('tx', header)
  await api('/charts')
  expect(queryClient.getQueryData(['catalog-issues', '/charts'])).toEqual({})
})
it('旧服务器无原因头仍兼容，下次恢复清理陈旧原因且不会跨请求路径串用', async () => {
  respond('tx', 'tx=access_restricted')
  await api('/search?q=old')
  respond('tx')
  await api('/search?q=new')
  expect(queryClient.getQueryData(['catalog-issues', '/search?q=new'])).toEqual({})
  respond('')
  await api('/search?q=old')
  expect(queryClient.getQueryData(['catalog-issues', '/search?q=old'])).toEqual({})
  expect(queryClient.getQueryData(['catalog-warnings', '/search?q=old'])).toEqual([])
})
it('重复来源去重，未知平台/非目录响应不借用此诊断', async () => {
  respond('tx,tx,unknown', 'tx=rate_limited,tx=timeout')
  await api('/playlists')
  expect(queryClient.getQueryData(['catalog-warnings', '/playlists'])).toEqual(['tx'])
  expect(queryClient.getQueryData(['catalog-issues', '/playlists'])).toEqual({ tx: 'rate_limited' })
  await api('/settings')
  expect(queryClient.getQueryData(['catalog-issues', '/settings'])).toBeUndefined()
})

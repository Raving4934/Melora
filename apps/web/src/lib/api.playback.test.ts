import { afterEach, expect, it, vi } from 'vitest'
import { api, APIError } from './api'

afterEach(() => vi.unstubAllGlobals())
function failure(headers: Record<string, string>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(JSON.stringify({ error: { code: 'lx_resolve_failed', message: '解析失败' } }), {
          status: 502,
          headers: { 'Content-Type': 'application/json', ...headers },
        }),
    ),
  )
}
it('可选解析诊断只读取有界计数、白名单阶段与真实布尔值', async () => {
  failure({
    'X-Melora-Resolve-Stage': 'resolve',
    'X-Melora-Resolve-Attempts': '3',
    'X-Melora-Auto-Switch': 'false',
  })
  await expect(api('/tracks/tx%3Aabc/play-info?quality=flac')).rejects.toMatchObject({
    status: 502,
    code: 'lx_resolve_failed',
    resolution: { stage: 'resolve', attempts: 3, autoSwitch: false },
  })
})
it.each(['', '-1', '4', '999', 'NaN', '1.5', '03', 'https://private.example/token'])(
  '忽略非法或超预算尝试计数 %s',
  async (value) => {
    failure({ 'X-Melora-Resolve-Stage': 'resolve', 'X-Melora-Resolve-Attempts': value })
    try {
      await api('/tracks/tx%3Aabc/play-info?quality=flac')
      throw new Error('missing error')
    } catch (error) {
      expect(error).toBeInstanceOf(APIError)
      expect((error as APIError).resolution).toBeUndefined()
    }
  },
)
it('非播放接口和未知阶段不借用解析诊断，旧服务无Header仍兼容', async () => {
  failure({ 'X-Melora-Resolve-Stage': 'resolve', 'X-Melora-Resolve-Attempts': '1' })
  await expect(api('/settings')).rejects.toMatchObject({ resolution: undefined, message: '解析失败' })
  failure({ 'X-Melora-Resolve-Stage': 'private-url', 'X-Melora-Resolve-Attempts': '1' })
  await expect(api('/tracks/tx%3Aabc/play-info')).rejects.toMatchObject({
    resolution: undefined,
    message: '解析失败',
  })
  failure({})
  await expect(api('/tracks/tx%3Aabc/play-info')).rejects.toMatchObject({
    resolution: undefined,
    message: '解析失败',
  })
})

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { CatalogWarnings } from './UI'
import { queryClient } from '../lib/api'

afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.unstubAllGlobals()
})
function mount(path: string, issues: Record<string, string> = {}) {
  queryClient.setQueryData(['/providers'], [{ id: 'tx', name: '扣扣' }])
  queryClient.setQueryData(['catalog-warnings', path], ['tx'])
  queryClient.setQueryData(['catalog-issues', path], issues)
  const retry = vi.fn()
  const result = render(
    <QueryClientProvider client={queryClient}>
      <CatalogWarnings path={path} retry={retry} />
    </QueryClientProvider>,
  )
  return { ...result, retry }
}
it('受限提示只指向本次搜索，不推断登录或整个平台不可用，重试入口保留', () => {
  const { retry } = mount('/search?q=fixture', { tx: 'access_restricted' })
  expect(screen.getByRole('status')).toHaveTextContent('扣扣的本次搜索访问受平台限制')
  expect(screen.getByRole('status')).toHaveTextContent('已显示的结果仍可使用')
  expect(screen.getByRole('status')).not.toHaveTextContent(/暂不可用|登录|会员/)
  fireEvent.click(screen.getByRole('button', { name: '重试' }))
  expect(retry).toHaveBeenCalledTimes(1)
})
it.each([
  ['/charts', '榜单'],
  ['/playlists', '歌单'],
])('旧header只有失败来源仍明确能力范围：%s', (path, scope) => {
  mount(path)
  expect(screen.getByRole('status')).toHaveTextContent(`扣扣的本次${scope}请求未完成`)
})

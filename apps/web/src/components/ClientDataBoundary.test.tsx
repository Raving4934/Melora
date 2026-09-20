import { StrictMode, useLayoutEffect } from 'react'
import { cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  activate: vi.fn(),
  restore: vi.fn(),
  reload: vi.fn(),
  cancel: vi.fn(),
  remove: vi.fn(),
  ui: vi.fn(),
  flushOutcomes: vi.fn(),
  authorizeOutcomes: vi.fn(),
}))
vi.mock('../lib/data-identity', async (original) => ({
  ...(await original<object>()),
  activateDataIdentity: mocks.activate,
}))
vi.mock('../lib/api', () => ({ queryClient: { cancelQueries: mocks.cancel, removeQueries: mocks.remove } }))
vi.mock('../stores/player', () => ({ player: { restoreDataSession: mocks.restore } }))
vi.mock('../lib/lyric-font', () => ({ reloadLyricFontPreference: mocks.reload }))
vi.mock('../lib/play-outcome', () => ({
  authorizePlayOutcomeDelivery: mocks.authorizeOutcomes,
  flushPendingPlayOutcomes: mocks.flushOutcomes,
}))
vi.mock('../stores/ui', () => ({ useUI: { setState: mocks.ui } }))
import { ClientDataBoundary } from './ClientDataBoundary'
const a = { generation: 'a'.repeat(32), resetLegacy: false }
const b = { generation: 'b'.repeat(32), resetLegacy: true }
beforeEach(() => {
  vi.clearAllMocks()
  mocks.activate.mockReturnValue(true)
  mocks.cancel.mockResolvedValue(undefined)
  mocks.flushOutcomes.mockResolvedValue(undefined)
})
afterEach(cleanup)
it('只有认证session可激活代际，旧服务缺字段保持兼容', () => {
  const view = render(
    <ClientDataBoundary session={{ authenticated: false, required: true, dataIdentity: a }}>
      private child
    </ClientDataBoundary>,
  )
  expect(mocks.activate).not.toHaveBeenCalled()
  view.rerender(
    <ClientDataBoundary session={{ authenticated: true, required: false }}>old server</ClientDataBoundary>,
  )
  expect(mocks.activate).not.toHaveBeenCalled()
  expect(view.getByText('old server')).toBeTruthy()
})
it('首次私有子页面layoutEffect前已停止旧媒体并清除旧查询；相同代际轮询不重挂列表', () => {
  let mounts = 0
  function Child() {
    useLayoutEffect(() => {
      expect(mocks.restore).toHaveBeenCalledTimes(1)
      mounts++
    }, [])
    return <div>ready</div>
  }
  const view = render(
    <ClientDataBoundary session={{ authenticated: true, required: false, dataIdentity: a }}>
      <Child />
    </ClientDataBoundary>,
  )
  expect(view.getByText('ready')).toBeTruthy()
  expect(mocks.cancel).toHaveBeenCalledOnce()
  expect(mocks.remove).toHaveBeenCalledOnce()
  expect(mocks.reload).toHaveBeenCalledOnce()
  expect(mocks.ui).toHaveBeenCalledOnce()
  expect(mocks.authorizeOutcomes).toHaveBeenCalledWith(a.generation)
  expect(mocks.flushOutcomes).toHaveBeenCalledOnce()
  view.rerender(
    <ClientDataBoundary session={{ authenticated: true, required: false, dataIdentity: { ...a } }}>
      <Child />
    </ClientDataBoundary>,
  )
  expect(mounts).toBe(1)
  expect(mocks.restore).toHaveBeenCalledOnce()
})
it('代际改变只在切换时重置，既有auth query保留', () => {
  const view = render(
    <ClientDataBoundary session={{ authenticated: true, required: false, dataIdentity: a }}>
      old
    </ClientDataBoundary>,
  )
  view.rerender(
    <ClientDataBoundary session={{ authenticated: true, required: false, dataIdentity: b }}>
      new
    </ClientDataBoundary>,
  )
  expect(mocks.restore).toHaveBeenCalledTimes(2)
  const predicate = mocks.remove.mock.calls[0]![0].predicate
  expect(predicate({ queryKey: ['/auth/session'] })).toBe(false)
  expect(predicate({ queryKey: ['/sources'] })).toBe(true)
  expect(view.getByText('new')).toBeTruthy()
})
it('StrictMode再次setup且代际相同不重复清理媒体', () => {
  mocks.activate.mockReturnValueOnce(true).mockReturnValue(false)
  render(
    <StrictMode>
      <ClientDataBoundary session={{ authenticated: true, required: false, dataIdentity: a }}>
        ok
      </ClientDataBoundary>
    </StrictMode>,
  )
  expect(mocks.restore).toHaveBeenCalledOnce()
})

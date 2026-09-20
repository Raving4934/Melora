import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AppShell } from './AppShell'
import { queryClient } from '../lib/api'
import { preloadRoute } from '../lib/route-preload'
import { usePlayer } from '../stores/player'

vi.mock('../lib/route-preload', () => ({ preloadRoute: vi.fn() }))

function Location() {
  const route = useLocation()
  return <output data-testid="location">{route.pathname + route.search}</output>
}

function StatefulLocation() {
  const [count, setCount] = useState(0)
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <section>
      <output data-testid="stateful-location">{location.pathname}</output>
      <output data-testid="stateful-count">{count}</output>
      <button type="button" onClick={() => setCount((value) => value + 1)}>
        增加页内状态
      </button>
      <button type="button" onClick={() => navigate('/items/b')}>
        切换参数路由
      </button>
    </section>
  )
}

function mount(path = '/') {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="*" element={<Location />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function mountStatefulRoute() {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/items/a']}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="items/:id" element={<StatefulLocation />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  queryClient.clear()
  vi.mocked(preloadRoute).mockClear()
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() })
  usePlayer.setState(usePlayer.getInitialState())
  queryClient.setQueryData(['/auth/session'], { authenticated: true, required: false })
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('[]', { headers: { 'Content-Type': 'application/json' } })),
  )
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.open = false
    },
  })
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
it('移动搜索保留可访问名称和搜索键，清空后不失焦，关闭回到入口', async () => {
  mount()
  const trigger = screen.getByRole('button', { name: '打开搜索' })
  fireEvent.click(trigger)
  const dialog = screen.getByRole('dialog', { name: '搜索' })
  const input = within(dialog).getByRole('textbox', { name: '搜索关键词' })
  await waitFor(() => expect(input).toHaveFocus())
  expect(input).toHaveAttribute('enterkeyhint', 'search')
  fireEvent.change(input, { target: { value: '海风' } })
  fireEvent.click(within(dialog).getByRole('button', { name: '清除关键词' }))
  expect(input).toHaveValue('')
  expect(input).toHaveFocus()
  fireEvent.click(within(dialog).getByRole('button', { name: '关闭' }))
  await waitFor(() => expect(trigger).toHaveFocus())
})
it('搜索提交保留来源与类型、去掉旧分页，关闭弹窗只导航一次', async () => {
  mount('/search?q=海风&source=kw&type=album&page=3')
  fireEvent.click(screen.getByRole('button', { name: '打开搜索' }))
  const dialog = screen.getByRole('dialog', { name: '搜索' })
  const input = within(dialog).getByRole('textbox', { name: '搜索关键词' })
  expect(input).toHaveValue('海风')
  fireEvent.change(input, { target: { value: '  夜航  ' } })
  fireEvent.submit(within(dialog).getByRole('search'))
  expect(screen.queryByRole('dialog', { name: '搜索' })).not.toBeInTheDocument()
  const destination = new URL(screen.getByTestId('location').textContent!, 'https://fixture.invalid')
  expect(Object.fromEntries(destination.searchParams)).toEqual({ q: '夜航', source: 'kw', type: 'album' })
})

it('导航意图提前加载对应页面代码', () => {
  mount()
  fireEvent.pointerEnter(screen.getByRole('link', { name: '我的音乐' }))
  fireEvent.focus(screen.getByRole('textbox', { name: '搜索关键词' }))
  fireEvent.pointerEnter(screen.getByRole('button', { name: '更多' }))
  expect(preloadRoute).toHaveBeenCalledWith('/library')
  expect(preloadRoute).toHaveBeenCalledWith('/search')
  expect(preloadRoute).toHaveBeenCalledWith('/downloads')
  expect(preloadRoute).toHaveBeenCalledWith('/settings')
})

it('参数路由切换时保留页内状态，并在绘制前复位主内容滚动', () => {
  mountStatefulRoute()
  fireEvent.click(screen.getByRole('button', { name: '增加页内状态' }))
  expect(screen.getByTestId('stateful-count')).toHaveTextContent('1')

  fireEvent.click(screen.getByRole('button', { name: '切换参数路由' }))

  expect(screen.getByTestId('stateful-location')).toHaveTextContent('/items/b')
  expect(screen.getByTestId('stateful-count')).toHaveTextContent('1')
  expect(HTMLElement.prototype.scrollTo).toHaveBeenCalledTimes(2)
})

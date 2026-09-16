import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'
import { queryClient } from './lib/api'
import type { Session } from './lib/types'

const sessionResponse: Session = {
  authenticated: false,
  required: true,
  loginMethod: 'password',
  deployMode: 'cloud',
}

beforeEach(() => {
  queryClient.clear()
  window.localStorage.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const path = String(input)
      if (path.includes('/auth/session'))
        return new Response(
          JSON.stringify(init.method === 'POST' ? { authenticated: true } : sessionResponse),
          {
            headers: { 'Content-Type': 'application/json' },
          },
        )
      return new Response('[]', { headers: { 'Content-Type': 'application/json' } })
    }),
  )
})

afterEach(() => {
  cleanup()
  queryClient.clear()
  window.localStorage.clear()
  vi.unstubAllGlobals()
})

function mount() {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

it('password 登录方式展示用户名与密码并提交凭据', async () => {
  mount()
  const username = await screen.findByLabelText('用户名')
  expect(screen.getByLabelText('密码')).toHaveAttribute('autocomplete', 'current-password')
  fireEvent.change(username, { target: { value: 'admin' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'correct-horse' } })
  fireEvent.click(screen.getByRole('button', { name: /进入乐屿/ }))

  await waitFor(() => {
    const post = vi
      .mocked(fetch)
      .mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === 'POST')
    expect(post).toBeTruthy()
    expect(JSON.parse(String((post![1] as RequestInit).body))).toEqual({
      username: 'admin',
      password: 'correct-horse',
    })
  })
  expect(screen.queryByLabelText('访问令牌')).not.toBeInTheDocument()
})

it('可切换密码明文显示', async () => {
  mount()
  const password = await screen.findByLabelText('密码')
  expect(password).toHaveAttribute('type', 'password')
  const toggle = screen.getByRole('button', { name: '显示密码' })
  expect(toggle).toHaveAttribute('aria-pressed', 'false')

  fireEvent.click(toggle)
  expect(password).toHaveAttribute('type', 'text')
  expect(screen.getByRole('button', { name: '隐藏密码' })).toHaveAttribute('aria-pressed', 'true')
})

it('登录成功后记住用户名，取消勾选则清除', async () => {
  mount()
  fireEvent.change(await screen.findByLabelText('用户名'), { target: { value: 'owner' } })
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'secret-pass' } })
  expect(screen.getByRole('checkbox', { name: '记住用户名' })).not.toBeChecked()
  fireEvent.click(screen.getByRole('checkbox', { name: '记住用户名' }))
  fireEvent.click(screen.getByRole('button', { name: /进入乐屿/ }))
  await waitFor(() => expect(window.localStorage.getItem('melora:login-username:v1')).toBe('owner'))

  cleanup()
  queryClient.clear()
  window.localStorage.setItem('melora:login-username:v1', 'owner')
  mount()
  const prefill = await screen.findByLabelText('用户名')
  expect(prefill).toHaveValue('owner')
  expect(screen.getByRole('checkbox', { name: '记住用户名' })).toBeChecked()

  fireEvent.click(screen.getByRole('checkbox', { name: '记住用户名' }))
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'secret-pass' } })
  fireEvent.click(screen.getByRole('button', { name: /进入乐屿/ }))
  await waitFor(() => expect(window.localStorage.getItem('melora:login-username:v1')).toBeNull())
})

it('鉴权未知的冷启动默认使用应用同构骨架，不挂载登录页', async () => {
  mount()
  expect(document.querySelector('.app-boot-header')).not.toBeNull()
  expect(document.querySelector('.login-page')).toBeNull()
  expect(document.querySelector('.login-boot')).toBeNull()

  await screen.findByLabelText('用户名')
  expect(document.querySelector('.app-boot-header')).toBeNull()
})

it('遗留的登出提示也不能让鉴权未知态闪现登录页', async () => {
  window.localStorage.setItem('melora:auth-hint:v1', 'out')
  mount()
  expect(document.querySelector('.app-boot-header')).not.toBeNull()
  expect(document.querySelector('.login-page')).toBeNull()
  expect(document.querySelector('.login-boot')).toBeNull()

  await screen.findByLabelText('用户名')
})

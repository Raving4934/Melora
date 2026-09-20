import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { SourcesPanel, type LXSource, type SourcesState } from './SourcesPanel'
import { queryClient } from '../lib/api'

const source: LXSource = {
  id: '0123456789abcdef01234567',
  name: '在线测试源',
  filename: 'online-source.js',
  version: '1',
  author: '测试',
  description: '',
  status: 'ready',
  allowHTTPHosts: [],
  platforms: {},
}
let state: SourcesState
let fetcher: ReturnType<typeof vi.fn>
let expectedImportURL = 'https://cdn.example.test/source.js'

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open')
  }
})

beforeEach(() => {
  queryClient.clear()
  queryClient.setDefaultOptions({ queries: { retry: false, staleTime: 30_000 } })
  state = { items: [], activeSourceId: '', available: true }
  expectedImportURL = 'https://cdn.example.test/source.js'
  fetcher = vi.fn(async (input: string, init: RequestInit = {}) => {
    const url = new URL(input, 'http://localhost')
    const path = url.pathname.replace('/api/v1', '')
    if (path === '/sources') return json(state)
    if (path === '/providers') return json([])
    if (path === '/sources/import-url') {
      const body = JSON.parse(String(init.body)) as { url: string }
      expect(body.url).toBe(expectedImportURL)
      state = { ...state, items: [source], activeSourceId: source.id }
      return json(source, 201)
    }
    throw new Error(`Unexpected ${init.method || 'GET'} ${path}`)
  })
  vi.stubGlobal('fetch', fetcher)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  queryClient.clear()
})

function renderPanel() {
  return render(
    <QueryClientProvider client={queryClient}>
      <SourcesPanel />
    </QueryClientProvider>,
  )
}

describe('SourcesPanel 在线 LX 音源导入', () => {
  it('切换在线链接模式并提交到 import-url，导入后刷新现有源列表', async () => {
    renderPanel()
    await screen.findByText('尚未导入音源')
    fireEvent.click(screen.getByRole('button', { name: '导入 LX 音源' }))
    expect(screen.getByRole('tab', { name: '本地文件' })).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('tab', { name: '在线链接' }))
    const input = screen.getByLabelText('LX音源链接')
    fireEvent.change(input, { target: { value: 'https://cdn.example.test/source.js' } })
    fireEvent.click(screen.getByRole('button', { name: '导入并检查' }))
    await waitFor(() => expect(screen.getByText('在线测试源')).toBeVisible())
    expect(
      fetcher.mock.calls.some(
        ([input, init]) => String(input).endsWith('/sources/import-url') && init.method === 'POST',
      ),
    ).toBe(true)
  })

  it('允许 HTTP 在线链接并显示明文风险提示，但不阻断导入', async () => {
    expectedImportURL = 'http://cdn.example.test/source.js'
    renderPanel()
    await screen.findByText('尚未导入音源')
    fireEvent.click(screen.getByRole('button', { name: '导入 LX 音源' }))
    fireEvent.click(screen.getByRole('tab', { name: '在线链接' }))
    const input = screen.getByLabelText('LX音源链接')
    fireEvent.change(input, { target: { value: expectedImportURL } })
    expect(screen.getByRole('note')).toHaveTextContent('HTTP 明文传输风险')
    fireEvent.click(screen.getByRole('button', { name: '导入并检查' }))
    await waitFor(() => expect(screen.getByText('在线测试源')).toBeVisible())
    expect(
      fetcher.mock.calls.some(
        ([input, init]) => String(input).endsWith('/sources/import-url') && init.method === 'POST',
      ),
    ).toBe(true)
  })

  it('在线链接客户端先拒绝凭据和片段，不发起导入请求', async () => {
    renderPanel()
    await screen.findByText('尚未导入音源')
    fireEvent.click(screen.getByRole('button', { name: '导入 LX 音源' }))
    fireEvent.click(screen.getByRole('tab', { name: '在线链接' }))
    const input = screen.getByLabelText('LX音源链接')
    fireEvent.change(input, { target: { value: 'http://user:secret@example.test/source.js#token' } })
    fireEvent.click(screen.getByRole('button', { name: '导入并检查' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('公网 HTTP/HTTPS')
    expect(fetcher.mock.calls.some(([input]) => String(input).endsWith('/sources/import-url'))).toBe(false)
  })
})

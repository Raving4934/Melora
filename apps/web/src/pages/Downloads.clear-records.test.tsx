import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { DownloadsPage } from './Downloads'
import * as api from '../lib/api'
import type { DownloadJob } from '../lib/types'
import { useUI } from '../stores/ui'

class DownloadEvents extends EventTarget {
  static OPEN = 1
  static current: DownloadEvents
  constructor() {
    super()
    DownloadEvents.current = this
  }
  emit(data: unknown) {
    this.dispatchEvent(new MessageEvent('downloads', { data: JSON.stringify(data) }))
  }
  readyState = 1
  close() {}
}
const states = ['downloading', 'paused', 'completed', 'failed', 'cancelled']
const fixture: DownloadJob[] = states.map((state, index) => ({
  id: `clear-record-${index}`,
  track: {
    id: `demo:clear-${index}`,
    providerId: 'demo',
    title: `${state} 测试任务`,
    artist: '下载记录回归',
    album: '测试',
    duration: 90,
    coverUrl: '',
    qualities: ['128k'],
    canDownload: true,
  },
  quality: '128k',
  state,
  bytesDone: 1024,
  bytesTotal: 4096,
  speed: 0,
  targetPath: `/music/保留文件-${index}.mp3`,
  createdAt: '2026-09-08T00:00:00Z',
  updatedAt: '2026-09-08T00:00:00Z',
}))
const terminal = new Set(['completed', 'failed', 'cancelled'])
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
let jobs: DownloadJob[]
let clearReply: (() => Promise<Response>) | undefined
let getReply: (() => Promise<Response>) | undefined
let calls: { path: string; method: string; body: unknown }[]
const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const closeModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')

beforeEach(() => {
  api.queryClient.clear()
  api.queryClient.setDefaultOptions({ queries: { staleTime: 30_000, retry: false } })
  jobs = [...fixture]
  calls = []
  clearReply = undefined
  getReply = undefined
  useUI.setState({ toast: null })
  vi.stubGlobal('EventSource', DownloadEvents)
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '')
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open')
    },
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const path = new URL(String(input), 'http://localhost').pathname.replace('/api/v1', '')
      const method = init.method || 'GET'
      calls.push({ path, method, body: init.body ? JSON.parse(String(init.body)) : undefined })
      if (path === '/downloads' && method === 'GET') return getReply ? getReply() : json(jobs)
      if (path === '/downloads/clear-records' && method === 'POST') {
        if (clearReply) return clearReply()
        const cleared = jobs.filter((job) => terminal.has(job.state)).length
        jobs = jobs.filter((job) => !terminal.has(job.state))
        return json({ cleared, remaining: jobs.length })
      }
      if (path === '/settings') return json({ concurrency: 1 })
      throw new Error(`意外请求：${method} ${path}`)
    }),
  )
  api.queryClient.setQueryData(['/settings'], { concurrency: 1 })
})
afterEach(() => {
  cleanup()
  api.queryClient.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  if (showModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', showModal)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal')
  if (closeModal) Object.defineProperty(HTMLDialogElement.prototype, 'close', closeModal)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
})
function mount(warm = true) {
  if (warm) api.queryClient.setQueryData(['/downloads'], jobs)
  return render(
    <MemoryRouter>
      <QueryClientProvider client={api.queryClient}>
        <DownloadsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}
const trigger = () => screen.getByRole('button', { name: /^清除记录，共/ })
const dialog = () => screen.getByRole('dialog', { name: '清除下载记录' })
function open() {
  fireEvent.click(trigger())
  return within(dialog())
}
const submissions = () => calls.filter((call) => call.path === '/downloads/clear-records')

it('初次加载禁用；数据抵达后复用同一个入口，终态数跨标签求和', async () => {
  const read = deferred<Response>()
  getReply = () => read.promise
  mount(false)
  const button = trigger()
  expect(button).toBeDisabled()
  expect(button).toHaveAccessibleName('清除记录，共 0 条')
  await act(async () => read.resolve(json(jobs)))
  await waitFor(() => expect(button).toBeEnabled())
  expect(trigger()).toBe(button)
  expect(button).toHaveAccessibleName('清除记录，共 3 条')
  for (const name of [/^已完成/, /^失败与取消/, /^进行中/]) {
    fireEvent.click(screen.getByRole('tab', { name }))
    expect(trigger()).toHaveAccessibleName('清除记录，共 3 条')
  }
  expect(submissions()).toEqual([])
})

it.each([
  { records: [] as DownloadJob[] },
  { records: fixture.slice(0, 2) },
  { records: [{ ...fixture[0]!, state: 'running' }] },
])('空记录或只有活动/暂停任务时不能发起清除', ({ records }) => {
  jobs = records
  mount()
  expect(trigger()).toBeDisabled()
  fireEvent.click(trigger())
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(submissions()).toEqual([])
  expect(api.queryClient.getQueryData(['/downloads'])).toEqual(records)
})

it('确认清晰说明跨标签全部终态、保留活动及暂停、不删任何文件，取消不发送', () => {
  mount()
  const modal = open()
  expect(modal.getByText(/全部已完成、失败和已取消/)).toHaveTextContent('与当前标签页无关')
  expect(modal.getByText(/进行中和已暂停/)).toHaveTextContent('不会停止或取消下载')
  expect(modal.getByText(/不会删除音频、歌词、封面附件或任何文件/)).toBeVisible()
  expect(submissions()).toEqual([])
  fireEvent.click(modal.getByRole('button', { name: '保留记录' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(submissions()).toEqual([])
})

it('失败保持弹窗/原行并可重试，成功以服务端真实数量反馈且不切标签', async () => {
  const invalidate = vi.spyOn(api.queryClient, 'invalidateQueries')
  clearReply = async () => json({ error: { message: '清除记录暂时失败，请重试' } }, 503)
  mount()
  fireEvent.click(screen.getByRole('tab', { name: /^失败与取消/ }))
  const oldRows = screen.getAllByRole('article')
  const modalNode = dialogAfterOpen()
  fireEvent.click(within(modalNode).getByRole('button', { name: '确认清除' }))
  await waitFor(() => expect(within(modalNode).getByRole('alert')).toHaveTextContent('清除记录暂时失败'))
  expect(dialog()).toBe(modalNode)
  expect(screen.getAllByRole('article')).toEqual(oldRows)
  expect(invalidate).not.toHaveBeenCalled()
  expect(useUI.getState().toast).toBeNull()
  clearReply = async () => {
    jobs = fixture.slice(0, 2)
    return json({ cleared: 7, remaining: 2 })
  }
  fireEvent.click(within(modalNode).getByRole('button', { name: '确认清除' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['/downloads'], exact: true }, { throwOnError: true })
  expect(submissions()).toEqual([
    { path: '/downloads/clear-records', method: 'POST', body: {} },
    { path: '/downloads/clear-records', method: 'POST', body: {} },
  ])
  expect(useUI.getState().toast).toMatchObject({
    kind: 'success',
    message: '已清除 7 条下载记录，剩余 2 条任务。文件未删除。',
  })
  expect(screen.getByRole('tab', { name: /^失败与取消/ })).toHaveClass('active')
  expect(screen.getByText('暂无失败任务')).toBeVisible()
  expect(api.queryClient.getQueryData(['/downloads'])).toEqual(fixture.slice(0, 2))
})
function dialogAfterOpen() {
  open()
  return dialog()
}

it('pending 防重复/退出；POST及刷新等待阶段都保留旧数据，不乐观清空', async () => {
  const post = deferred<Response>(),
    read = deferred<Response>()
  clearReply = () => post.promise
  getReply = () => read.promise
  mount()
  fireEvent.click(screen.getByRole('tab', { name: /^已完成/ }))
  const row = screen.getByRole('article')
  const before = api.queryClient.getQueryData(['/downloads'])
  const modal = open()
  fireEvent.click(modal.getByRole('button', { name: '确认清除' }))
  fireEvent.click(modal.getByRole('button', { name: '清除中…' }))
  expect(submissions()).toHaveLength(1)
  expect(trigger()).toBeDisabled()
  expect(modal.getByRole('button', { name: '清除中…' })).toBeDisabled()
  expect(modal.getByRole('button', { name: '保留记录' })).toBeDisabled()
  fireEvent.click(modal.getByRole('button', { name: '关闭' }))
  fireEvent(dialog(), new Event('cancel', { cancelable: true }))
  expect(dialog()).toBeVisible()
  expect(screen.getByRole('article')).toBe(row)
  expect(api.queryClient.getQueryData(['/downloads'])).toBe(before)
  await act(async () => post.resolve(json({ cleared: 3, remaining: 2 })))
  await waitFor(() =>
    expect(calls.some((call) => call.path === '/downloads' && call.method === 'GET')).toBe(true),
  )
  expect(screen.getByRole('article')).toBe(row)
  expect(screen.queryByText('正在加载…')).not.toBeInTheDocument()
  expect(api.queryClient.getQueryData(['/downloads'])).toBe(before)
  await act(async () => read.resolve(json(fixture.slice(0, 2))))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(trigger()).toBeDisabled()
  expect(submissions()).toHaveLength(1)
})

it('后台刷新保留旧记录和可用入口，不以 isFetching 锁住按钮', async () => {
  const read = deferred<Response>()
  getReply = () => read.promise
  mount()
  const row = screen.getAllByRole('article')[0]
  act(() => {
    void api.invalidate('/downloads')
  })
  await waitFor(() => expect(api.queryClient.getQueryState(['/downloads'])?.fetchStatus).toBe('fetching'))
  expect(trigger()).toBeEnabled()
  expect(screen.getAllByRole('article')[0]).toBe(row)
  await act(async () => read.resolve(json(jobs)))
})

it('终态被其它客户端清完时不伪造原计数，合法0清除仍报告服务器结果', async () => {
  clearReply = async () => {
    jobs = fixture.slice(0, 2)
    return json({ cleared: 0, remaining: 2 })
  }
  mount()
  fireEvent.click(open().getByRole('button', { name: '确认清除' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(useUI.getState().toast?.message).toBe('已清除 0 条下载记录，剩余 2 条任务。文件未删除。')
})

it('弹窗打开后终态归零，保留确认边界并禁用提交', async () => {
  mount()
  open()
  act(() => api.queryClient.setQueryData(['/downloads'], fixture.slice(0, 2)))
  await waitFor(() => expect(trigger()).toHaveAccessibleName('清除记录，共 0 条'))
  expect(within(dialog()).getByRole('button', { name: '确认清除' })).toBeDisabled()
  expect(submissions()).toEqual([])
})

it.each([
  null,
  {},
  { cleared: -1, remaining: 2 },
  { cleared: '3', remaining: 2 },
  { cleared: 3, remaining: 1.5 },
])('无效服务端数量不冒报成功，弹窗保留可重试：%j', async (response) => {
  clearReply = async () => json(response)
  mount()
  const modal = open()
  fireEvent.click(modal.getByRole('button', { name: '确认清除' }))
  await waitFor(() => expect(modal.getByRole('alert')).toHaveTextContent('服务返回的清除数量无效'))
  expect(modal.getByRole('button', { name: '确认清除' })).toBeEnabled()
  expect(api.queryClient.getQueryData(['/downloads'])).toEqual(fixture)
  expect(useUI.getState().toast).toBeNull()
})

it.each([
  { name: '全部记录已被清除', snapshot: [] as DownloadJob[] },
  { name: 'worker 已完成而旧 GET 仍在下载', snapshot: [{ ...fixture[0]!, state: 'completed' }, fixture[1]!] },
])('P2 SSE 优先：$name，迟到 GET 不复活旧行或回退状态', async ({ snapshot }) => {
  const read = deferred<Response>()
  getReply = () => read.promise
  mount()
  let refresh!: Promise<void>
  act(() => {
    refresh = api.invalidate('/downloads')
  })
  await waitFor(() => expect(calls.filter((call) => call.path === '/downloads')).toHaveLength(1))
  act(() => DownloadEvents.current.emit(snapshot))
  await waitFor(() => expect(api.queryClient.getQueryData(['/downloads'])).toEqual(snapshot))
  // fetch mock 故意不理会 abort，验证 Query 层也拒绝接受迟到快照。
  await act(async () => {
    read.resolve(json(fixture))
    await refresh
  })
  expect(api.queryClient.getQueryData(['/downloads'])).toEqual(snapshot)
  expect(screen.getByText('实时更新')).toBeVisible()
  expect(trigger()).toHaveAccessibleName(
    `清除记录，共 ${snapshot.filter((job) => terminal.has(job.state)).length} 条`,
  )
  expect(submissions()).toHaveLength(0)
})

it('P2 清除后刷新 GET 被连续新 SSE 取代时视为读取成功，不误报清除失败', async () => {
  const read = deferred<Response>()
  getReply = () => read.promise
  mount()
  fireEvent.click(open().getByRole('button', { name: '确认清除' }))
  await waitFor(() => expect(calls.filter((call) => call.path === '/downloads')).toHaveLength(1))
  const completed = [{ ...fixture[0]!, state: 'completed' }, fixture[1]!]
  act(() => {
    DownloadEvents.current.emit(fixture.slice(0, 2))
    DownloadEvents.current.emit(completed)
  })
  await act(async () => read.resolve(json(fixture.slice(0, 2))))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(api.queryClient.getQueryData(['/downloads'])).toEqual(completed)
  expect(useUI.getState().toast).toMatchObject({
    kind: 'success',
    message: '已清除 3 条下载记录，剩余 2 条任务。文件未删除。',
  })
  expect(submissions()).toHaveLength(1)
})

it('P2 POST 成功 GET 失败：保留行与已成功回执，关闭重开及连续重试只发 GET', async () => {
  getReply = async () => json({ error: { message: '模拟列表读取失败' } }, 503)
  mount()
  fireEvent.click(screen.getByRole('tab', { name: /^已完成/ }))
  const row = screen.getByRole('article')
  fireEvent.click(open().getByRole('button', { name: '确认清除' }))
  await waitFor(() =>
    expect(within(dialog()).getByRole('alert')).toHaveTextContent(/已清除 3 条下载记录.*列表刷新失败/),
  )
  expect(within(dialog()).getByRole('alert')).toHaveTextContent('无需再次清除')
  expect(screen.getByRole('article')).toBe(row)
  expect(useUI.getState().toast?.kind).not.toBe('success')
  expect(submissions()).toHaveLength(1)
  fireEvent.click(within(dialog()).getByRole('button', { name: '稍后刷新' }))
  fireEvent.click(screen.getByRole('button', { name: '刷新记录列表（清除已成功）' }))
  expect(within(dialog()).queryByRole('button', { name: '确认清除' })).not.toBeInTheDocument()
  fireEvent.click(within(dialog()).getByRole('button', { name: '重试刷新' }))
  await waitFor(() => expect(within(dialog()).getByRole('alert')).toHaveTextContent('列表刷新失败'))
  expect(calls.filter((call) => call.path === '/downloads')).toHaveLength(2)
  expect(submissions()).toHaveLength(1)
  const read = deferred<Response>()
  getReply = () => read.promise
  fireEvent.click(within(dialog()).getByRole('button', { name: '重试刷新' }))
  fireEvent.click(within(dialog()).getByRole('button', { name: '刷新中…' }))
  expect(within(dialog()).getByRole('button', { name: '刷新中…' })).toBeDisabled()
  expect(screen.getByRole('article')).toBe(row)
  // 清除后才完成的新记录必须保留，不能通过重发 POST 被顺带清除。
  const latest = [{ ...fixture[0]!, state: 'completed' }, fixture[1]!]
  await act(async () => read.resolve(json(latest)))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(api.queryClient.getQueryData(['/downloads'])).toEqual(latest)
  expect(submissions()).toHaveLength(1)
  expect(calls.filter((call) => call.path === '/downloads')).toHaveLength(3)
  expect(trigger()).toHaveAccessibleName('清除记录，共 1 条')
  expect(screen.getByRole('tab', { name: /^已完成/ })).toHaveClass('active')
  expect(useUI.getState().toast).toMatchObject({
    kind: 'success',
    message: '已清除 3 条下载记录，剩余 2 条任务。文件未删除。',
  })
})

import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { DownloadDirectory } from './DownloadDirectory'
import { queryClient } from '../lib/api'

const root = '/vol3/音乐 & 收藏'
const child = `${root}/歌单 #1`
const commits = vi.fn()
let calls: string[]
let override: ((path: string) => Response | Promise<Response> | undefined) | undefined
function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}
function deferred() {
  let resolve!: (response: Response) => void
  const promise = new Promise<Response>((done) => {
    resolve = done
  })
  return { promise, resolve }
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
  commits.mockReset()
  calls = []
  override = undefined
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(input, 'http://localhost')
      calls.push(url.pathname + url.search)
      const extra = override?.(url.searchParams.get('path') || '')
      if (extra) return extra
      expect(url.pathname).toBe('/api/v1/storage/directories')
      const path = url.searchParams.get('path') || ''
      return json({
        path,
        parent: path === child ? root : '',
        root: path ? root : '',
        roots: [root],
        directories:
          path === ''
            ? [{ name: '音乐 & 收藏', path: root }]
            : path === root
              ? [{ name: '歌单 #1', path: child }]
              : [],
        truncated: false,
      })
    }),
  )
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.unstubAllGlobals()
})
function Harness({ initial = '' }: { initial?: string }) {
  const [value, setValue] = useState(initial)
  const [dirty, setDirty] = useState(false)
  return (
    <DownloadDirectory
      value={value}
      dirty={dirty}
      onChange={(next) => {
        setValue(next)
        setDirty(true)
      }}
      onCommit={(next) => {
        commits(next)
        setValue(next)
        setDirty(false)
      }}
      feedback={<span>测试反馈占位</span>}
    />
  )
}
function show(initial = '') {
  render(
    <QueryClientProvider client={queryClient}>
      <Harness initial={initial} />
    </QueryClientProvider>,
  )
}
async function browse() {
  fireEvent.click(screen.getByRole('button', { name: '浏览' }))
  fireEvent.click(await screen.findByRole('button', { name: `打开目录 ${root}` }))
  await waitFor(() => expect(screen.getByRole('button', { name: '选择此目录' })).toBeEnabled())
}

describe('v5 下载目录紧凑输入与 Modal', () => {
  it('主栏无授权/刷新/步骤/统计噪声，不读取授权快照或静默验证', () => {
    show('/vol3/旧目录')
    expect(screen.getByLabelText('下载保存目录')).toHaveValue('/vol3/旧目录')
    expect(screen.getByRole('button', { name: '浏览' })).toBeEnabled()
    expect(screen.queryByText(/刷新授权|授权步骤|已读取.*授权|验证权限|存储空间/)).toBeNull()
    expect(calls).toEqual([])
    expect(commits).not.toHaveBeenCalled()
  })
  it('不逐键提交，目录确认与blur提交精确文本；IME组合Enter不提交', () => {
    show()
    const input = screen.getByLabelText('下载保存目录')
    fireEvent.change(input, { target: { value: child } })
    expect(commits).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    expect(commits).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(commits).toHaveBeenLastCalledWith(child)
    fireEvent.change(input, { target: { value: `${child}/新目录` } })
    fireEvent.blur(input)
    expect(commits).toHaveBeenLastCalledWith(`${child}/新目录`)
    fireEvent.change(input, { target: { value: root } })
    fireEvent.click(screen.getByRole('button', { name: '确认下载目录' }))
    expect(commits).toHaveBeenLastCalledWith(root)
  })
  it('可浏览NAS第二卷路径及特殊字符，明确选择才提交', async () => {
    show('/old')
    await browse()
    expect(commits).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: `打开目录 ${child}` }))
    await waitFor(() => expect(screen.getByRole('button', { name: '上一级' })).toBeEnabled())
    expect(screen.getByText('此目录没有可浏览的子目录。')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '选择此目录' }))
    expect(commits).toHaveBeenCalledExactlyOnceWith(child)
    expect(screen.getByLabelText('下载保存目录')).toHaveValue(child)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls).toContain(`/api/v1/storage/directories?path=${encodeURIComponent(child)}`)
  })
  it.each(['关闭', 'Escape', '遮罩'])('%s关闭保留草稿，内部按钮全部type=button', async (method) => {
    show('/draft')
    await browse()
    const dialog = screen.getByRole('dialog', { name: '选择下载目录' })
    for (const button of within(dialog).getAllByRole('button'))
      expect(button).toHaveAttribute('type', 'button')
    if (method === '关闭') fireEvent.click(within(dialog).getByRole('button', { name: '关闭' }))
    else if (method === 'Escape') fireEvent(dialog, new Event('cancel', { cancelable: true }))
    else fireEvent.click(dialog, { clientX: -1, clientY: -1 })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByLabelText('下载保存目录')).toHaveValue('/draft')
    expect(commits).not.toHaveBeenCalled()
  })
  it('目录请求失败显示服务真实短错误，可重试，不伪造目录或保存成功', async () => {
    override = () =>
      json({ error: { code: 'directory_unreadable', message: '目录不可访问：请检查挂载权限' } }, 403)
    show('/draft')
    fireEvent.click(screen.getByRole('button', { name: '浏览' }))
    await screen.findByText('目录不可访问：请检查挂载权限')
    expect(screen.getByRole('button', { name: '选择此目录' })).toBeDisabled()
    expect(commits).not.toHaveBeenCalled()
    override = undefined
    fireEvent.click(screen.getByRole('button', { name: '刷新列表' }))
    expect(await screen.findByRole('button', { name: `打开目录 ${root}` })).toBeEnabled()
  })
  it('切路径期间旧列表只占位、禁用选择；失败后可返回根列表', async () => {
    show()
    fireEvent.click(screen.getByRole('button', { name: '浏览' }))
    const row = await screen.findByRole('button', { name: `打开目录 ${root}` })
    const delayed = deferred()
    override = (path) => (path ? delayed.promise : undefined)
    fireEvent.click(row)
    expect(screen.getByRole('button', { name: `打开目录 ${root}` })).toBe(row)
    expect(row).toBeDisabled()
    expect(screen.getByRole('button', { name: '选择此目录' })).toBeDisabled()
    await act(async () => delayed.resolve(json({ error: { message: '路径已失效' } }, 403)))
    await screen.findByText('路径已失效')
    fireEvent.click(screen.getByRole('button', { name: '全部目录' }))
    expect(await screen.findByRole('button', { name: `打开目录 ${root}` })).toBeEnabled()
  })
})

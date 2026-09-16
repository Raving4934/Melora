import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { SettingsPage, createSettingsAutosave } from './Settings'
import { queryClient } from '../lib/api'
import type { Settings } from '../lib/types'
import type { SourcesState } from '../components/SourcesPanel'

const base: Settings = {
  fileNameFormat: 'title-artist',
  downloadRoot: '/vol3/音乐',
  concurrency: 1,
  writeLyrics: false,
  writeCover: false,
  embedTags: false,
  defaultQuality: 'standard',
  autoSwitchSource: false,
  writeMetadata: false,
  showDirect: false,
}
let settings: Settings
let sources: SourcesState
let calls: { path: string; method: string; body?: Partial<Settings> }[]
let override: ((path: string, init: RequestInit) => Response | Promise<Response> | undefined) | undefined
function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (cause: Error) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
const settle = () => new Promise((done) => setTimeout(done, 0))
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
  settings = { ...base }
  sources = {
    items: [
      {
        id: 'one',
        name: '测试源',
        filename: 'one.js',
        author: '测试',
        version: '1',
        description: '',
        status: 'ready',
        allowHTTPHosts: [],
        platforms: {
          wy: {
            name: '网抑云',
            type: 'music',
            actions: ['musicUrl'],
            qualitys: ['128k', '320k', 'flac', 'flac24bit'],
          },
        },
      },
    ],
    activeSourceId: 'one',
    available: true,
  }
  calls = []
  override = undefined
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit = {}) => {
      const url = new URL(input, 'http://localhost')
      const path = url.pathname.replace('/api/v1', '')
      const method = init.method || 'GET'
      const body = typeof init.body === 'string' ? JSON.parse(init.body) : undefined
      calls.push({ path, method, body })
      const extra = override?.(path, init)
      if (extra) return extra
      if (path === '/settings') {
        if (method === 'PATCH') settings = { ...settings, ...body }
        return json(settings)
      }
      if (path === '/sources') return json(sources)
      if (path === '/sources/active') {
        sources = { ...sources, activeSourceId: body.id }
        return json(sources)
      }
      if (path.endsWith('/check')) return json(sources.items.find((source) => path.includes(source.id)))
      if (path === '/sources/one' && method === 'DELETE') {
        sources = { ...sources, items: [] }
        return json({})
      }
      if (path === '/sources/one' && method === 'PATCH') return json(sources.items[0])
      if (path === '/storage/directories') {
        const selected = url.searchParams.get('path') || ''
        return json({
          path: selected,
          parent: '',
          root: selected,
          roots: ['/vol3/音乐'],
          directories: selected ? [] : [{ name: '音乐', path: '/vol3/音乐' }],
          truncated: false,
        })
      }
      if (path === '/providers') return json([])
      throw new Error(`Unexpected ${method} ${path}`)
    }),
  )
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.unstubAllGlobals()
})
function show() {
  return render(
    <QueryClientProvider client={queryClient}>
      <SettingsPage />
    </QueryClientProvider>,
  )
}
async function ready() {
  await screen.findByLabelText('文件命名格式')
}
function patches() {
  return calls.filter((call) => call.method === 'PATCH' && call.path === '/settings')
}

describe('v5 Settings 字段自动保存界面', () => {
  it('cloud 部署隐藏下载偏好且保留播放设置', async () => {
    override = (path) =>
      path === '/auth/session'
        ? json({ authenticated: true, required: false, deployMode: 'cloud' })
        : undefined
    show()
    await screen.findByLabelText('在线播放音质')
    await waitFor(() => expect(screen.queryByLabelText('下载保存目录')).toBeNull())
    expect(screen.queryByLabelText('文件命名格式')).toBeNull()
    expect(screen.queryByLabelText('同时下载任务数')).toBeNull()
    expect(screen.getByRole('switch', { name: '自动容灾切换可用音源' })).toBeVisible()
  })

  it('移除Provider/全局保存/JSON/统计/授权/网络面板，提供三种命名与真实设置', async () => {
    show()
    await ready()
    expect(screen.queryByRole('button', { name: '保存设置' })).toBeNull()
    expect(
      screen.queryByText(/音乐目录|JSON|存储空间|已读取.*授权|刷新授权|授权步骤|显示直连|网络路径/),
    ).toBeNull()
    expect(within(screen.getByLabelText('文件命名格式')).getAllByRole('option')).toHaveLength(3)
    for (const name of ['内嵌元数据（ID3）与封面', '自动容灾切换可用音源'])
      expect(screen.getByRole('switch', { name })).toBeVisible()
    expect(screen.queryByRole('switch', { name: '另存歌词文件（LRC）' })).toBeNull()
    expect(screen.queryByRole('switch', { name: '另存封面图片' })).toBeNull()
    expect(patches()).toEqual([])
  })
  it('开关即时更新，PATCH只发变动字段并在新保存关闭两个旧字段', async () => {
    settings = { ...base, writeMetadata: true, showDirect: true }
    show()
    await ready()
    fireEvent.click(screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' }))
    expect(screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    await waitFor(() => expect(settings.embedTags).toBe(true))
    expect(patches()[0].body).toEqual({ embedTags: true, writeMetadata: false, showDirect: false })
    fireEvent.change(screen.getByLabelText('文件命名格式'), { target: { value: 'artist-title' } })
    await waitFor(() => expect(settings.fileNameFormat).toBe('artist-title'))
    expect(patches()[1].body).toEqual({ fileNameFormat: 'artist-title' })
  })
  it('目录不逐键提交；Enter、blur、确认同版本去重，失败保留路径并局部重试', async () => {
    show()
    await ready()
    const input = screen.getByLabelText('下载保存目录')
    fireEvent.change(input, { target: { value: '/vol3/无权限' } })
    expect(patches()).toEqual([])
    const delayed = deferred<Response>()
    override = (path, init) => (path === '/settings' && init.method === 'PATCH' ? delayed.promise : undefined)
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.blur(input)
    fireEvent.click(screen.getByRole('button', { name: '确认下载目录' }))
    await waitFor(() => expect(patches()).toHaveLength(1))
    await act(async () =>
      delayed.resolve(
        json({ error: { code: 'invalid_download_root', message: '保存目录不可写：请检查挂载权限' } }, 400),
      ),
    )
    await screen.findByText('保存目录不可写：请检查挂载权限')
    expect(input).toHaveValue('/vol3/无权限')
    expect(document.getElementById('setting-downloadRoot-feedback')).not.toHaveTextContent('已保存')
    override = undefined
    fireEvent.click(screen.getByRole('button', { name: '重试保存下载保存目录' }))
    await waitFor(() => expect(settings.downloadRoot).toBe('/vol3/无权限'))
    expect(patches()).toHaveLength(2)
    expect(patches()[1].body).toEqual({ downloadRoot: '/vol3/无权限' })
  })
  it('真实blur提交与浏览选择走同一自动保存队列，不预先用旧授权快照拦截NAS路径', async () => {
    show()
    await ready()
    fireEvent.change(screen.getByLabelText('下载保存目录'), { target: { value: '/vol3/新目录' } })
    fireEvent.blur(screen.getByLabelText('下载保存目录'))
    await waitFor(() => expect(settings.downloadRoot).toBe('/vol3/新目录'))
    fireEvent.click(screen.getByRole('button', { name: '浏览' }))
    fireEvent.click(await screen.findByRole('button', { name: '打开目录 /vol3/音乐' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '选择此目录' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '选择此目录' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(settings.downloadRoot).toBe('/vol3/音乐'))
    expect(calls.some((call) => call.path === '/storage/status' || call.path === '/storage/validate')).toBe(
      false,
    )
  })
  it('保存中仍可连续操作；旧响应不覆盖同字段新意图，其他字段不丢', async () => {
    show()
    await ready()
    const delayed = deferred<Response>()
    let first = true
    override = (path, init) => {
      if (path === '/settings' && init.method === 'PATCH' && first) {
        first = false
        return delayed.promise
      }
    }
    const embedding = screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' })
    fireEvent.click(embedding)
    await waitFor(() => expect(patches()).toHaveLength(1))
    fireEvent.click(embedding)
    fireEvent.change(screen.getByLabelText('同时下载任务数'), { target: { value: '2' } })
    expect(embedding).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByLabelText('同时下载任务数')).toHaveValue('2')
    expect(patches()).toHaveLength(1)
    settings = { ...base, embedTags: true }
    await act(async () => delayed.resolve(json(settings)))
    await waitFor(() => expect(patches()).toHaveLength(3))
    expect(embedding).toHaveAttribute('aria-checked', 'false')
    await waitFor(() => expect(settings).toMatchObject({ embedTags: false, concurrency: 2 }))
    expect(patches().map((call) => call.body)).toEqual([
      { embedTags: true },
      { embedTags: false },
      { concurrency: 2 },
    ])
  })
  it('失败不阻塞其它字段，保留最新值且提供单字段重试', async () => {
    show()
    await ready()
    override = (path, init) =>
      path === '/settings' && init.method === 'PATCH' && String(init.body).includes('embedTags')
        ? json({ error: { message: '暂时无法保存内嵌选项' } }, 503)
        : undefined
    fireEvent.click(screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' }))
    fireEvent.change(screen.getByLabelText('同时下载任务数'), { target: { value: '2' } })
    await screen.findByText('暂时无法保存内嵌选项')
    await waitFor(() => expect(settings.concurrency).toBe(2))
    expect(screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    override = undefined
    fireEvent.click(screen.getByRole('button', { name: '重试保存内嵌元数据与封面' }))
    await waitFor(() => expect(settings.embedTags).toBe(true))
    expect(patches().at(-1)?.body).toEqual({ embedTags: true })
  })
  it('音质按当前源声明过滤，支持标准自动/128k/320k/flac/flac24bit；不重置旧值', async () => {
    show()
    await ready()
    const quality = screen.getByLabelText('在线播放音质')
    expect(
      within(quality)
        .getAllByRole('option')
        .map((option) => (option as HTMLOptionElement).value),
    ).toEqual(['standard', '128k', '320k', 'flac', 'flac24bit'])
    fireEvent.change(quality, { target: { value: 'flac24bit' } })
    await waitFor(() => expect(settings.defaultQuality).toBe('flac24bit'))
    sources.items[0].platforms.wy.qualitys = ['128k']
    await act(() => queryClient.invalidateQueries({ queryKey: ['/sources'] }))
    await waitFor(() =>
      expect(within(quality).getByRole('option', { name: 'Hi-Res · 24bit（可用源未声明）' })).toBeDisabled(),
    )
    expect(quality).toHaveValue('flac24bit')
    expect(patches()).toHaveLength(1)
  })
  it('切页不中断排队请求，返回后同一编辑器保留未完成意图', async () => {
    const mounted = show()
    await ready()
    const delayed = deferred<Response>()
    let first = true
    override = (path, init) => {
      if (path === '/settings' && init.method === 'PATCH' && first) {
        first = false
        return delayed.promise
      }
    }
    fireEvent.change(screen.getByLabelText('同时下载任务数'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' }))
    await waitFor(() => expect(patches()).toHaveLength(1))
    mounted.unmount()
    show()
    await ready()
    expect(screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    settings = { ...base, concurrency: 2 }
    await act(async () => delayed.resolve(json(settings)))
    await waitFor(() => expect(settings.embedTags).toBe(true))
    expect(patches().at(-1)?.body).toEqual({ embedTags: true })
  })
  it('保存过程中启动的GET迟到也不能回滚新设置或缓存', async () => {
    show()
    await ready()
    const write = deferred<Response>()
    const read = deferred<Response>()
    override = (path, init) =>
      path === '/settings' ? (init.method === 'PATCH' ? write.promise : read.promise) : undefined
    fireEvent.click(screen.getByRole('switch', { name: '自动容灾切换可用音源' }))
    await waitFor(() => expect(patches()).toHaveLength(1))
    void queryClient.invalidateQueries({ queryKey: ['/settings'] })
    settings = { ...base, autoSwitchSource: true }
    await act(async () => write.resolve(json(settings)))
    await waitFor(() =>
      expect(document.getElementById('setting-autoSwitchSource-feedback')).toHaveTextContent('已保存'),
    )
    await act(async () => read.resolve(json(base)))
    expect(screen.getByRole('switch', { name: '自动容灾切换可用音源' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    expect(queryClient.getQueryData<Settings>(['/settings'])?.autoSwitchSource).toBe(true)
  })
  it('较早GET迟到不能回滚已成功自动保存的值', async () => {
    show()
    await ready()
    const delayed = deferred<Response>()
    override = (path, init) =>
      path === '/settings' && (!init.method || init.method === 'GET') ? delayed.promise : undefined
    void queryClient.invalidateQueries({ queryKey: ['/settings'] })
    fireEvent.click(screen.getByRole('switch', { name: '自动容灾切换可用音源' }))
    await waitFor(() => expect(settings.autoSwitchSource).toBe(true))
    await act(async () => delayed.resolve(json(base)))
    expect(screen.getByRole('switch', { name: '自动容灾切换可用音源' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
  })
})

describe('v5 字段队列边界', () => {
  it('200响应未确认请求字段时不能伪造保存成功', async () => {
    const editor = createSettingsAutosave(base, async () => base)
    editor.edit('writeLyrics', true)
    await settle()
    expect(editor.getSnapshot().values.writeLyrics).toBe(true)
    expect(editor.getSnapshot().feedback.writeLyrics).toEqual({
      state: 'error',
      message: '服务未确认这项变更，请重试',
    })
  })
  it('快速反转回在途目标时合并冗余写入，仍为最新版本标记成功', async () => {
    const delayed = deferred<Settings>()
    const request = vi.fn(() => delayed.promise)
    const editor = createSettingsAutosave(base, request)
    editor.edit('writeLyrics', true)
    editor.edit('writeLyrics', false)
    editor.edit('writeLyrics', true)
    delayed.resolve({ ...base, writeLyrics: true })
    await settle()
    expect(request).toHaveBeenCalledTimes(1)
    expect(editor.getSnapshot().values.writeLyrics).toBe(true)
    expect(editor.getSnapshot().feedback.writeLyrics?.state).toBe('saved')
  })
  it('响应丢失时仍发送回退意图，即使回退值等于最后已知值', async () => {
    const delayed = deferred<Settings>()
    const request = vi
      .fn()
      .mockImplementationOnce(() => delayed.promise)
      .mockResolvedValue(base)
    const editor = createSettingsAutosave(base, request)
    editor.edit('writeLyrics', true)
    editor.edit('writeLyrics', false)
    delayed.reject(new Error('连接中断'))
    await settle()
    expect(request.mock.calls).toEqual([[{ writeLyrics: true }], [{ writeLyrics: false }]])
    expect(editor.getSnapshot().feedback.writeLyrics?.state).toBe('saved')
  })
  it('失败已经返回后改回原值，也必须确认写入而非静默忽略', async () => {
    const request = vi.fn().mockRejectedValueOnce(new Error('响应丢失')).mockResolvedValue(base)
    const editor = createSettingsAutosave(base, request)
    editor.edit('writeLyrics', true)
    await settle()
    expect(editor.getSnapshot().feedback.writeLyrics?.state).toBe('error')
    editor.edit('writeLyrics', false)
    await settle()
    expect(request.mock.calls).toEqual([[{ writeLyrics: true }], [{ writeLyrics: false }]])
    expect(editor.getSnapshot().feedback.writeLyrics?.state).toBe('saved')
  })
  it('未提交目录新文本会取消旧目录排队项，但不取消其它字段或已发请求', async () => {
    const delayed = deferred<Settings>()
    const request = vi
      .fn()
      .mockImplementationOnce(() => delayed.promise)
      .mockImplementation(async (patch: Partial<Settings>) => ({ ...base, writeCover: true, ...patch }))
    const editor = createSettingsAutosave(base, request)
    editor.edit('writeCover', true)
    editor.edit('downloadRoot', '/one')
    editor.edit('downloadRoot', '/two', false)
    delayed.resolve({ ...base, writeCover: true })
    await settle()
    expect(request).toHaveBeenCalledTimes(1)
    expect(editor.getSnapshot().values.downloadRoot).toBe('/two')
    editor.commit('downloadRoot')
    await settle()
    expect(request).toHaveBeenLastCalledWith({ downloadRoot: '/two' })
  })
  it('同字段旧失败不会覆盖新值状态或吞掉排队保存', async () => {
    const delayed = deferred<Settings>()
    const request = vi
      .fn()
      .mockImplementationOnce(() => delayed.promise)
      .mockResolvedValue({ ...base, concurrency: 3 })
    const editor = createSettingsAutosave(base, request)
    editor.edit('concurrency', 2)
    editor.edit('concurrency', 3)
    delayed.reject(new Error('旧错误'))
    await settle()
    expect(editor.getSnapshot().values.concurrency).toBe(3)
    expect(editor.getSnapshot().feedback.concurrency).toEqual({ state: 'saved' })
    expect(request).toHaveBeenLastCalledWith({ concurrency: 3 })
  })
})

describe('v5 LX 紧凑行与失败反馈', () => {
  it('使用表格式行，无目录重复面板或长说明', async () => {
    show()
    await ready()
    const table = screen.getByRole('table', { name: '已导入 LX 音源' })
    expect(within(table).getAllByRole('row')).toHaveLength(2)
    expect(within(table).getByText('测试源')).toBeVisible()
    expect(screen.queryByText(/音乐平台目录与 LX|未选择音源。搜索可用/)).toBeNull()
    expect(screen.getByRole('button', { name: '导入 LX 音源' })).toBeVisible()
  })
  it('设为当前失败保留目标和短错误，点击重试成功后才显示使用中', async () => {
    sources.activeSourceId = ''
    show()
    await ready()
    override = (path) =>
      path === '/sources/active' ? json({ error: { message: '切源失败，请重试' } }, 503) : undefined
    fireEvent.click(screen.getByRole('button', { name: '设为当前' }))
    await screen.findByText('切源失败，请重试')
    expect(screen.queryByRole('button', { name: '使用中' })).toBeNull()
    override = undefined
    fireEvent.click(screen.getByRole('button', { name: '重试音源 测试源' }))
    await screen.findByRole('button', { name: '使用中' })
  })
  it('检查返回error不能冒充成功，保留可重试状态', async () => {
    show()
    await ready()
    override = (path) =>
      path.endsWith('/check')
        ? json({ ...sources.items[0], status: 'error', error: '脚本初始化未通过' })
        : undefined
    fireEvent.click(screen.getByRole('button', { name: '检查音源 测试源' }))
    await screen.findByText('脚本初始化未通过')
    expect(screen.getByRole('button', { name: '重试音源 测试源' })).toBeEnabled()
  })
})

it('下载偏好只呈现内嵌选项；旧独立文件字段保留兼容但不提供新 UI 入口', async () => {
  settings = { ...base, writeLyrics: true, writeCover: true, embedTags: false, concurrency: 3 }
  show()
  await ready()
  expect(screen.queryByText('另存歌词文件（LRC）')).toBeNull()
  expect(screen.queryByText('另存封面图片')).toBeNull()
  expect(screen.queryByRole('button', { name: '仅内嵌，不另存文件' })).toBeNull()
  expect(screen.getByText('开启后将歌曲信息、专辑封面及歌词直接写入音频文件')).toBeVisible()
  expect(patches()).toEqual([])
  fireEvent.click(screen.getByRole('switch', { name: '内嵌元数据（ID3）与封面' }))
  await waitFor(() => expect(settings.embedTags).toBe(true))
  expect(settings).toMatchObject({
    writeLyrics: true,
    writeCover: true,
    concurrency: 3,
    downloadRoot: base.downloadRoot,
  })
  expect(patches().map((call) => call.body)).toEqual([{ embedTags: true }])
})

describe('源文件导出契约', () => {
  function captureDownloads() {
    const createObjectURL = vi.fn<(blob: Blob) => string>(() => 'blob:synthetic-export')
    const revokeObjectURL = vi.fn<(url: string) => void>()
    const BaseURL = URL
    vi.stubGlobal(
      'URL',
      class extends BaseURL {
        static createObjectURL = createObjectURL
        static revokeObjectURL = revokeObjectURL
      },
    )
    const links: { filename: string; href: string }[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      links.push({ filename: this.download, href: this.href })
    })
    return { createObjectURL, revokeObjectURL, links, restore: () => click.mockRestore() }
  }
  it('初始化失败源也可导出原始内容，操作不切源不检查，Blob及时回收且不入查询缓存', async () => {
    const capture = captureDownloads()
    try {
      sources.items[0].status = 'error'
      sources.items[0].error = '初始化失败'
      sources.activeSourceId = ''
      const content = '// 合成导出文件\nconst marker = "FIXTURE-EXPORT-ONLY";\n'
      override = (path) =>
        path === '/sources/one/export' ? json({ filename: 'fixture.js', content }) : undefined
      show()
      await ready()
      const button = screen.getByRole('button', { name: '导出音源 测试源' })
      expect(button).toBeEnabled()
      fireEvent.click(button)
      await waitFor(() =>
        expect(capture.links).toEqual([{ filename: 'fixture.js', href: 'blob:synthetic-export' }]),
      )
      const blob = capture.createObjectURL.mock.calls[0][0]
      expect(blob.size).toBe(new TextEncoder().encode(content).length)
      const text = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result))
        reader.onerror = reject
        reader.readAsText(blob)
      })
      expect(text).toBe(content)
      expect(calls.filter((call) => call.path.includes('/sources/'))).toEqual([
        { path: '/sources/one/export', method: 'GET', body: undefined },
      ])
      expect(sources.activeSourceId).toBe('')
      expect(
        JSON.stringify(
          queryClient
            .getQueryCache()
            .getAll()
            .map((entry) => entry.state.data),
        ),
      ).not.toContain('FIXTURE-EXPORT-ONLY')
      await waitFor(() => expect(capture.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic-export'), {
        timeout: 2000,
      })
    } finally {
      capture.restore()
    }
  })
  it('导出失败后的重试仍只执行导出，不误触设为当前或检查', async () => {
    const capture = captureDownloads()
    try {
      override = (path) =>
        path === '/sources/one/export' ? json({ error: { message: '暂时不能导出' } }, 503) : undefined
      show()
      await ready()
      fireEvent.click(screen.getByRole('button', { name: '导出音源 测试源' }))
      await screen.findByText('暂时不能导出')
      expect(capture.createObjectURL).not.toHaveBeenCalled()
      override = (path) =>
        path === '/sources/one/export'
          ? json({ filename: 'fixture.js', content: '// synthetic retry fixture' })
          : undefined
      fireEvent.click(screen.getByRole('button', { name: '重试音源 测试源' }))
      await waitFor(() => expect(capture.createObjectURL).toHaveBeenCalledOnce())
      expect(calls.filter((call) => call.path.includes('/sources/'))).toEqual([
        { path: '/sources/one/export', method: 'GET', body: undefined },
        { path: '/sources/one/export', method: 'GET', body: undefined },
      ])
      expect(sources.activeSourceId).toBe('one')
      await waitFor(() => expect(capture.revokeObjectURL).toHaveBeenCalledOnce(), { timeout: 2000 })
    } finally {
      capture.restore()
    }
  })
})

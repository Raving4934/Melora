import { expect, test, type Page } from '@playwright/test'
import type { DownloadJob } from '../src/lib/types'

// UI 合同测试：所有 API 都由当前 page 拦截，不清理或写入共享 E2E 后端数据。
const prefixFor = (project: string) => (project.startsWith('fnos-') ? '/app/melora' : '')
const terminal = new Set(['completed', 'failed', 'cancelled'])
function jobsFixture(): DownloadJob[] {
  return ['downloading', 'paused', 'completed', 'failed', 'cancelled'].map((state, i) => ({
    id: `clear-records-${i}`,
    track: {
      id: `demo:clear-${i}`,
      providerId: 'demo',
      title: `${state} 记录测试`,
      artist: '仅 UI 夹具',
      album: '清除记录',
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
    targetPath: `/music/保留音频-${i}.mp3`,
    lyricsPath: `/music/保留歌词-${i}.lrc`,
    coverPath: `/music/保留封面-${i}.jpg`,
    createdAt: '2026-09-08T00:00:00Z',
    updatedAt: '2026-09-08T00:00:00Z',
  }))
}
function barrier() {
  let release!: () => void
  const promise = new Promise<void>((resolve) => {
    release = resolve
  })
  return { promise, release }
}
async function mockDownloads(page: Page, prefix: string) {
  const state = {
    jobs: jobsFixture(),
    getGate: undefined as Promise<void> | undefined,
    clearGate: undefined as Promise<void> | undefined,
    failClear: false,
    failGet: false,
    extraTerminalAtClear: 0,
    reads: 0,
    readsReleased: 0,
    mutations: [] as { method: string; endpoint: string; body: unknown }[],
  }
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const endpoint = url.pathname.slice(url.pathname.indexOf('/api/v1') + 7)
    const method = request.method()
    if (method !== 'GET') state.mutations.push({ method, endpoint, body: request.postDataJSON() })
    if (endpoint === '/auth/session')
      return route.fulfill({
        json: {
          authenticated: true,
          required: false,
          authMode: prefix ? 'fnos' : 'standalone',
          csrfToken: 'download-records-fixture-csrf',
        },
      })
    if (endpoint === '/settings')
      return route.fulfill({ json: { concurrency: 1, defaultQuality: '128k', autoSwitchSource: true } })
    if (endpoint === '/downloads/events') return route.abort()
    if (endpoint === '/downloads' && method === 'GET') {
      state.reads++
      const snapshot = structuredClone(state.jobs)
      if (state.getGate) await state.getGate
      state.readsReleased++
      if (state.failGet)
        return route.fulfill({ status: 503, json: { error: { message: '模拟列表读取失败' } } })
      return route.fulfill({ json: snapshot })
    }
    if (endpoint === '/downloads/clear-records' && method === 'POST') {
      if (state.clearGate) await state.clearGate
      if (state.failClear)
        return route.fulfill({
          status: 503,
          json: { error: { message: '记录清除暂时失败，请重试。文件没有删除。' } },
        })
      // 模拟确认后、服务器处理前又产生终态记录，验证 UI 不用旧计数拼成功提示。
      for (let i = 0; i < state.extraTerminalAtClear; i++)
        state.jobs.push({ ...jobsFixture()[2]!, id: `server-new-${i}` })
      state.extraTerminalAtClear = 0
      const cleared = state.jobs.filter((job) => terminal.has(job.state)).length
      state.jobs = state.jobs.filter((job) => !terminal.has(job.state))
      return route.fulfill({ json: { cleared, remaining: state.jobs.length } })
    }
    if (method !== 'GET')
      return route.fulfill({ status: 400, json: { error: { message: `不允许的 UI 测试写入 ${endpoint}` } } })
    return route.fulfill({ json: [] })
  })
  return state
}
const trigger = (page: Page) => page.getByRole('button', { name: /^清除记录，共/ })
const dialog = (page: Page) => page.getByRole('dialog', { name: '清除下载记录', exact: true })
async function stableUI(page: Page) {
  return page.locator('.download-records-toolbar').evaluate((toolbar) => {
    const box = (node: Element) => {
      const r = node.getBoundingClientRect()
      return { x: r.x, y: r.y, width: r.width, height: r.height }
    }
    return {
      toolbar: box(toolbar),
      button: box(toolbar.querySelector('button')!),
      tabs: box(document.querySelector('.tab-bar')!),
      heading: box(document.querySelector('.page-heading')!),
    }
  })
}
// 浏览器边界浮点相减曾读到 43.999969px；只归一到 0.001 CSS px，仍拒绝 43.999px。
const expectTouchTarget = (value: number | undefined) =>
  expect(Math.round((value ?? 0) * 1000) / 1000).toBeGreaterThanOrEqual(44)
async function assertGeometry(page: Page) {
  const data = await stableUI(page)
  expectTouchTarget(data.button.width)
  expectTouchTarget(data.button.height)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(
    await page.locator('.download-records-toolbar').evaluate((n) => n.scrollWidth <= n.clientWidth),
  ).toBe(true)
  if (await dialog(page).count()) {
    expect(await dialog(page).evaluate((n) => n.scrollWidth <= n.clientWidth)).toBe(true)
    for (const button of await dialog(page).getByRole('button').all()) {
      const box = await button.boundingBox()
      expectTouchTarget(box?.width)
      expectTouchTarget(box?.height)
    }
  }
  return data
}

test('终态总计跨标签、确认边界清晰，取消不发送；刷新/开合/按钮几何稳定', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  const fixture = await mockDownloads(page, prefix)
  await page.goto(`${prefix}/downloads`)
  await expect(trigger(page)).toHaveAccessibleName('清除记录，共 3 条')
  const initial = await assertGeometry(page)
  await page.screenshot({ path: info.outputPath('downloads-clear-entry.png') })
  for (const name of [/^已完成/, /^失败与取消/, /^进行中/]) {
    await page.getByRole('tab', { name }).click()
    await expect(trigger(page)).toHaveAccessibleName('清除记录，共 3 条')
    expect(await stableUI(page)).toEqual(initial)
  }
  for (let i = 0; i < 3; i++) {
    await trigger(page).click()
    await expect(dialog(page)).toBeVisible()
    await expect(dialog(page)).toContainText('清除全部已完成、失败和已取消记录，与当前标签页无关。')
    await expect(dialog(page)).toContainText('进行中和已暂停任务会保留，不会停止或取消下载。')
    await expect(dialog(page)).toContainText('不会删除音频、歌词、封面附件或任何文件。')
    await assertGeometry(page)
    if (i === 0) await page.screenshot({ path: info.outputPath('downloads-clear-confirm.png') })
    await dialog(page).getByRole('button', { name: '保留记录', exact: true }).click()
    await expect(dialog(page)).toHaveCount(0)
    expect(await stableUI(page)).toEqual(initial)
  }
  for (let i = 0; i < 5; i++) {
    await page.reload()
    await expect(trigger(page)).toHaveAccessibleName('清除记录，共 3 条')
    expect(await assertGeometry(page)).toEqual(initial)
  }
  expect(fixture.mutations).toEqual([])
  await info.attach('stable-toolbar-geometry', {
    body: JSON.stringify(initial),
    contentType: 'application/json',
  })
})

test('初次加载禁用但不撤走入口；只有活动/暂停任务时计数为零且不可提交', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  const fixture = await mockDownloads(page, prefix)
  const read = barrier()
  fixture.getGate = read.promise
  await page.goto(`${prefix}/downloads`)
  await expect(trigger(page)).toBeVisible()
  await expect(trigger(page)).toBeDisabled()
  const before = await stableUI(page)
  read.release()
  fixture.getGate = undefined
  await expect(trigger(page)).toBeEnabled()
  expect(await stableUI(page)).toEqual(before)
  fixture.jobs = jobsFixture().slice(0, 2)
  await page.reload()
  await expect(page.getByRole('article')).toHaveCount(2)
  await expect(trigger(page)).toHaveAccessibleName('清除记录，共 0 条')
  await expect(trigger(page)).toBeDisabled()
  expect(await assertGeometry(page)).toEqual(before)
  fixture.jobs = []
  await page.reload()
  await expect(page.getByText('暂时没有进行中的任务')).toBeVisible()
  await expect(trigger(page)).toBeDisabled()
  expect(fixture.mutations).toEqual([])
})

test('失败不关弹窗、不移除旧行；重试发送空对象，使用服务端真实数量且保留活动任务', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  const fixture = await mockDownloads(page, prefix)
  fixture.failClear = true
  await page.goto(`${prefix}/downloads`)
  await expect(trigger(page)).toBeEnabled()
  const failedTab = page.getByRole('tab', { name: /^失败与取消/ })
  await failedTab.click()
  await expect(failedTab).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('failed 记录测试', { exact: true })).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(2)
  await page
    .getByRole('article')
    .first()
    .evaluate((n) => {
      ;(window as unknown as { retainedDownloadRow: Element }).retainedDownloadRow = n
    })
  await trigger(page).click()
  const confirm = dialog(page).getByRole('button', { name: '确认清除', exact: true })
  await confirm.scrollIntoViewIfNeeded()
  const before = await confirm.boundingBox()
  await confirm.click()
  await expect(dialog(page).getByRole('alert')).toContainText('记录清除暂时失败')
  await expect(confirm).toBeEnabled()
  expect(await confirm.boundingBox()).toEqual(before)
  expect(
    await page
      .getByRole('article')
      .first()
      .evaluate((n) => (window as unknown as { retainedDownloadRow: Element }).retainedDownloadRow === n),
  ).toBe(true)
  await assertGeometry(page)
  await page.screenshot({ path: info.outputPath('downloads-clear-failed.png') })
  fixture.failClear = false
  fixture.extraTerminalAtClear = 4
  await confirm.click()
  await expect(dialog(page)).toHaveCount(0)
  await expect(page.locator('.toast')).toHaveText('已清除 7 条下载记录，剩余 2 条任务。文件未删除。')
  await expect(page.getByRole('tab', { name: /^失败与取消/ })).toHaveClass(/active/)
  await expect(page.getByText('暂无失败任务')).toBeVisible()
  await expect(trigger(page)).toBeDisabled()
  expect(fixture.mutations).toEqual(
    Array.from({ length: 2 }, () => ({ method: 'POST', endpoint: '/downloads/clear-records', body: {} })),
  )
  await page.getByRole('tab', { name: /^进行中/ }).click()
  await expect(page.getByRole('article')).toHaveCount(2)
  await expect(page.getByText('downloading 记录测试', { exact: true })).toBeVisible()
  await expect(page.getByText('paused 记录测试', { exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('downloads-clear-success-active-preserved.png') })
})

test('pending 禁重入，POST及刷新等待阶段保持旧行和标签；完成后只应用新数据', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  const fixture = await mockDownloads(page, prefix)
  const post = barrier(),
    read = barrier()
  fixture.clearGate = post.promise
  await page.goto(`${prefix}/downloads`)
  await expect(trigger(page)).toBeEnabled()
  await page.getByRole('tab', { name: /^已完成/ }).click()
  await trigger(page).click()
  const confirm = dialog(page).getByRole('button', { name: '确认清除', exact: true })
  await confirm.scrollIntoViewIfNeeded()
  const before = await confirm.boundingBox()
  await confirm.click()
  const pending = dialog(page).getByRole('button', { name: '清除中…', exact: true })
  await expect(pending).toBeDisabled()
  await expect(trigger(page)).toBeDisabled()
  expect(await pending.boundingBox()).toEqual(before)
  await pending.dispatchEvent('click')
  await page.keyboard.press('Escape')
  await expect(dialog(page)).toBeVisible()
  await expect(page.getByRole('article')).toHaveCount(1)
  expect(fixture.mutations).toHaveLength(1)
  await page.screenshot({ path: info.outputPath('downloads-clear-pending.png') })
  const reads = fixture.reads
  fixture.getGate = read.promise
  post.release()
  await expect.poll(() => fixture.reads).toBeGreaterThan(reads)
  await expect(pending).toBeDisabled()
  await expect(page.getByRole('article')).toHaveCount(1)
  await expect(page.getByText('正在加载…', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: /^已完成/ })).toHaveClass(/active/)
  expect(await pending.boundingBox()).toEqual(before)
  read.release()
  fixture.getGate = undefined
  await expect(dialog(page)).toHaveCount(0)
  await expect(page.getByText('暂无已完成任务')).toBeVisible()
  await expect(trigger(page)).toBeDisabled()
  expect(fixture.mutations).toEqual([{ method: 'POST', endpoint: '/downloads/clear-records', body: {} }])
})

async function mockDownloadEvents(page: Page) {
  await page.addInitScript(() => {
    class DownloadEvents extends EventTarget {
      static OPEN = 1
      readyState = 1
      onopen: (() => void) | null = null
      onerror: (() => void) | null = null
      receive = (event: Event) => {
        this.dispatchEvent(
          new MessageEvent('downloads', { data: JSON.stringify((event as CustomEvent).detail) }),
        )
      }
      constructor() {
        super()
        window.addEventListener('clear-records-sse', this.receive)
        queueMicrotask(() => this.onopen?.())
      }
      close() {
        this.readyState = 2
        window.removeEventListener('clear-records-sse', this.receive)
      }
    }
    window.EventSource = DownloadEvents as unknown as typeof EventSource
  })
}
async function emitDownloads(page: Page, jobs: DownloadJob[]) {
  await page.evaluate(
    (data) => window.dispatchEvent(new CustomEvent('clear-records-sse', { detail: data })),
    jobs,
  )
}

test('P2 SSE 清空后初始旧 GET 迟到不能复活记录，保持已连接不依赖轮询', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  const fixture = await mockDownloads(page, prefix)
  await mockDownloadEvents(page)
  const read = barrier()
  fixture.getGate = read.promise
  await page.goto(`${prefix}/downloads`)
  await expect.poll(() => fixture.reads).toBeGreaterThan(0)
  await expect(page.getByText('实时更新', { exact: true })).toBeVisible()
  const initialReads = fixture.reads
  expect(fixture.readsReleased).toBe(0) // SSE 必须先于仍被闸门阻塞的初始 GET。
  await emitDownloads(page, [])
  await expect(page.getByText('暂时没有进行中的任务')).toBeVisible()
  expect(fixture.readsReleased).toBe(0)
  read.release()
  fixture.getGate = undefined
  await expect.poll(() => fixture.readsReleased).toBe(initialReads)
  // 让旧 HTTP promise 有机会落回缓存；不能仅断言 SSE 刚写入的瞬间。
  await page.waitForTimeout(350)
  await expect(trigger(page)).toHaveAccessibleName('清除记录，共 0 条')
  await expect(page.getByRole('article')).toHaveCount(0)
  expect(fixture.reads).toBe(initialReads)
  expect(fixture.mutations).toHaveLength(0)
  await assertGeometry(page)
})

test('P2 清除后 SSE 完成先到旧 GET 后到，新状态不回退且清除正确结束', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  const fixture = await mockDownloads(page, prefix)
  await mockDownloadEvents(page)
  await page.goto(`${prefix}/downloads`)
  await expect(trigger(page)).toBeEnabled()
  const initialReads = fixture.reads
  await page.getByRole('tab', { name: /^已完成/ }).click()
  const read = barrier()
  fixture.getGate = read.promise
  await trigger(page).click()
  await dialog(page).getByRole('button', { name: '确认清除', exact: true }).click()
  await expect.poll(() => fixture.reads).toBe(initialReads + 1)
  expect(fixture.readsReleased).toBe(initialReads) // 清除后的刷新 GET 仍未结束。
  const latest = [{ ...jobsFixture()[0]!, state: 'completed' }, jobsFixture()[1]!]
  await emitDownloads(page, latest)
  await expect(page.getByText('downloading 记录测试', { exact: true })).toBeVisible()
  read.release()
  fixture.getGate = undefined
  await expect(dialog(page)).toHaveCount(0)
  await page.waitForTimeout(350)
  await expect(page.getByText('downloading 记录测试', { exact: true })).toBeVisible()
  await expect(trigger(page)).toHaveAccessibleName('清除记录，共 1 条')
  await expect(page.locator('.toast')).toHaveText('已清除 3 条下载记录，剩余 2 条任务。文件未删除。')
  expect(fixture.mutations).toEqual([{ method: 'POST', endpoint: '/downloads/clear-records', body: {} }])
  await assertGeometry(page)
})

test('P2 POST 已成功 GET 失败仅重试列表，关闭重开及重试不重复清除新终态', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  const fixture = await mockDownloads(page, prefix)
  await mockDownloadEvents(page)
  await page.goto(`${prefix}/downloads`)
  await expect(trigger(page)).toBeEnabled()
  const initialReads = fixture.reads
  await page.getByRole('tab', { name: /^已完成/ }).click()
  const toolbar = await stableUI(page)
  await trigger(page).click()
  const confirm = dialog(page).getByRole('button', { name: '确认清除', exact: true })
  await confirm.scrollIntoViewIfNeeded()
  const box = await confirm.boundingBox()
  fixture.failGet = true
  await confirm.click()
  await expect(dialog(page).getByRole('alert')).toContainText(/已清除 3 条下载记录.*列表刷新失败/)
  await expect(dialog(page).getByRole('alert')).toContainText('无需再次清除')
  await expect(page.getByRole('article')).toHaveCount(1)
  expect(await dialog(page).getByRole('button', { name: '重试刷新' }).boundingBox()).toEqual(box)
  expect(fixture.mutations).toHaveLength(1)
  await assertGeometry(page)
  await page.screenshot({ path: info.outputPath('downloads-clear-refresh-failed.png') })
  await dialog(page).getByRole('button', { name: '稍后刷新' }).click()
  await page.getByRole('button', { name: '刷新记录列表（清除已成功）' }).click()
  await expect(dialog(page).getByRole('button', { name: '确认清除', exact: true })).toHaveCount(0)
  // 后续才完成的任务不得被重复 POST 误删。
  fixture.jobs = [{ ...jobsFixture()[0]!, state: 'completed' }, jobsFixture()[1]!]
  fixture.failGet = false
  const read = barrier()
  fixture.getGate = read.promise
  await dialog(page).getByRole('button', { name: '重试刷新' }).click()
  const pending = dialog(page).getByRole('button', { name: '刷新中…', exact: true })
  await expect(pending).toBeDisabled()
  await pending.dispatchEvent('click')
  await expect(page.getByRole('article')).toHaveCount(1)
  expect(await pending.boundingBox()).toEqual(box)
  read.release()
  fixture.getGate = undefined
  await expect(dialog(page)).toHaveCount(0)
  await expect(page.getByText('downloading 记录测试', { exact: true })).toBeVisible()
  await expect(trigger(page)).toHaveAccessibleName('清除记录，共 1 条')
  expect(fixture.mutations).toEqual([{ method: 'POST', endpoint: '/downloads/clear-records', body: {} }])
  // StrictMode 开发构建会取消并重发初始 GET，按操作前基线检查新增读取。
  expect(fixture.reads - initialReads).toBe(3) // 失败 GET 两次（默认重试一次）+ 手动刷新。
  expect(await stableUI(page)).toEqual(toolbar)
  await assertGeometry(page)
})

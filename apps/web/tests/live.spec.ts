import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import {
  fixtureOrigin,
  freshSettings,
  importSource,
  jsonOK,
  mutationHeaders,
  removeSourceUI,
  sourceRow,
  sources,
} from './settings-v5-e2e.helpers'

// 仅注册/SDK回归，不调用远端，不播放example地址，不伪装生产音源。
const namespace = 'LX E2E · '
const legacyNames = ['LX 浏览器回归测试', '错误源测试', '未声明音质测试']
const owned = (name: string) => name.startsWith(namespace) || legacyNames.includes(name)
function script(name: string) {
  return `/**\n * @name ${name}\n * @version 1.0.0\n */
const { on, send, EVENT_NAMES } = globalThis.lx;
on(EVENT_NAMES.request, ({action}) => {
  if (action !== 'musicUrl') throw new Error('unsupported');
  return Promise.resolve('https://example.com/test.mp3');
});
send(EVENT_NAMES.inited, {sources:{wy:{name:'网抑云',type:'music',actions:['musicUrl'],qualitys:['128k','flac']}}});
/* ${'x'.repeat(80 * 1024)} */`
}
let activeBefore = ''
let countBefore = 0

test.beforeEach(async ({ request, baseURL }) => {
  fixtureOrigin(baseURL, ['3783'])
  const headers = await mutationHeaders(request, '', baseURL)
  const state = await sources(request)
  activeBefore = state.items.some((source) => source.id === state.activeSourceId && !owned(source.name))
    ? state.activeSourceId
    : ''
  for (const source of state.items.filter((source) => owned(source.name))) {
    await jsonOK(await request.delete(`/api/v1/sources/${encodeURIComponent(source.id)}`, { headers }))
  }
  countBefore = (await sources(request)).items.length
  // 不删除其它spec的音源；显式取消当前选择以核验默认无播放能力，结束后恢复。
  await jsonOK(await request.put('/api/v1/sources/active', { headers, data: { id: '' } }))
})
test.afterEach(async ({ request, baseURL }) => {
  const headers = await mutationHeaders(request, '', baseURL)
  const state = await sources(request)
  for (const source of state.items.filter((source) => owned(source.name))) {
    await jsonOK(await request.delete(`/api/v1/sources/${encodeURIComponent(source.id)}`, { headers }))
  }
  const rest = await sources(request)
  if (activeBefore && rest.items.some((source) => source.id === activeBefore && source.status === 'ready')) {
    await jsonOK(await request.put('/api/v1/sources/active', { headers, data: { id: activeBefore } }))
  }
})

test('真实非Demo模式：大于64KiB音源直接导入、导出原文件、选中、检查、持久化与删除', async ({
  page,
  request,
  browser,
  baseURL,
}, info) => {
  const providers = await jsonOK<{ id: string; isDemo: boolean; capabilities: { play: boolean } }[]>(
    await request.get('/api/v1/providers'),
  )
  expect(providers.map((item) => item.id)).toEqual(['wy', 'tx', 'kw', 'kg', 'mg'])
  expect(providers.some((item) => item.isDemo)).toBe(false)
  expect(providers[0].capabilities.play).toBe(false)
  await page.goto('/settings')
  await expect(page.getByRole('heading', { name: 'LX 音源配置', exact: true })).toBeVisible()
  if (countBefore === 0) await expect(page.getByText('尚未导入音源', { exact: true })).toBeVisible()
  const name = `${namespace}${info.project.name} · 正常源`
  const code = script(name)
  expect(Buffer.byteLength(code)).toBeGreaterThan(64 * 1024)
  // 合法161字符文件名会在旧登记展示名中截断成.j；导出仍须可直接重新导入。
  const originalFilename = `${'a'.repeat(158)}.js`
  const { source, row, response: upload } = await importSource(page, name, code, originalFilename)
  expect(upload.request().headers()['content-type']).toContain('multipart/form-data; boundary=')
  await expect(row.getByText('已初始化（正常）', { exact: true })).toBeVisible()
  await expect(row.getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
  const state = await sources(request)
  expect(state.items).toHaveLength(countBefore + 1)
  expect(state.items.find((entry) => entry.id === source.id)).toMatchObject({
    status: 'ready',
    platforms: { wy: { qualitys: ['128k', 'flac'] } },
  })
  expect(state.activeSourceId).toBe(source.id)
  expect((await jsonOK<typeof providers>(await request.get('/api/v1/providers')))[0].capabilities.play).toBe(
    true,
  )
  await page.reload()
  await expect(row.getByText('已初始化（正常）', { exact: true })).toBeVisible()
  await expect(page.getByLabel('HTTP主机白名单', { exact: true })).toHaveCount(0)
  await expect(row.getByRole('button', { name: `配置音源 ${name}`, exact: true })).toHaveCount(0)
  // Chromium 不保证暴露包含文件的 multipart postData；核对真实导入后的默认状态。
  expect(source.allowHTTPHosts).toEqual([])
  const exporting = page.waitForEvent('download')
  const exportResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/sources/${encodeURIComponent(source.id)}/export`),
  )
  await row.getByRole('button', { name: `导出音源 ${name}`, exact: true }).click()
  const exported = await exporting
  expect(exported.suggestedFilename()).toBe(`${'a'.repeat(157)}.js`)
  const exportedContent = await readFile((await exported.path())!, 'utf8')
  expect(exportedContent).toBe(code)
  const exportedAPI = await exportResponse
  expect(exportedAPI.status()).toBe(200)
  expect(exportedAPI.headers()['cache-control']).toContain('no-store')
  const reimported = await importSource(page, name, exportedContent, exported.suggestedFilename())
  expect(reimported.source.id).toBe(source.id)
  expect((await sources(request)).items).toHaveLength(countBefore + 1)
  expect((await sources(request)).activeSourceId).toBe(source.id)
  const checked = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/v1/sources/${encodeURIComponent(source.id)}/check`) &&
      response.request().method() === 'POST',
  )
  await row.getByRole('button', { name: `检查音源 ${name}`, exact: true }).click()
  expect(await jsonOK(await checked)).toMatchObject({ id: source.id, status: 'ready' })
  await expect(row.getByRole('button', { name: `检查音源 ${name}`, exact: true })).toBeEnabled()
  // 真实取消选择后，经表格操作重新选择；不能仅用文案断言代替活动源API状态。
  await jsonOK(
    await request.put('/api/v1/sources/active', {
      headers: await mutationHeaders(request, '', baseURL),
      data: { id: '' },
    }),
  )
  await page.reload()
  const choosing = page.waitForResponse(
    (response) => response.url().endsWith('/api/v1/sources/active') && response.request().method() === 'PUT',
  )
  await row.getByRole('button', { name: '设为当前', exact: true }).click()
  expect(await jsonOK(await choosing)).toMatchObject({ activeSourceId: source.id })
  await expect(row.getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
  await page.reload()
  await freshSettings(browser, baseURL, '', async (fresh) => {
    const persisted = sourceRow(fresh, name)
    await expect(persisted.getByText('已初始化（正常）', { exact: true })).toBeVisible()
    await expect(persisted.getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
    await expect(persisted.getByRole('button', { name: `导出音源 ${name}`, exact: true })).toBeEnabled()
    await expect(fresh.getByLabel('HTTP主机白名单', { exact: true })).toHaveCount(0)
  })
  const width = await page.evaluate(() => ({
    content: document.documentElement.scrollWidth,
    viewport: innerWidth,
  }))
  expect(width.content).toBeLessThanOrEqual(width.viewport + 1)
  await page.screenshot({ path: test.info().outputPath('lx-sources.png'), fullPage: true })
  await removeSourceUI(page, name, source.id)
  const deleted = await sources(request)
  expect(deleted.items).toHaveLength(countBefore)
  expect(deleted.activeSourceId).toBe('')
})

test('错误脚本显示短诊断且不可选，不污染已有当前源、不泄露抛错内容', async ({ page, request }, info) => {
  await page.goto('/settings')
  const validName = `${namespace}${info.project.name} · 当前基线`
  const { source: baseline } = await importSource(page, validName, script(validName), 'active-baseline.js')
  await expect(sourceRow(page, validName).getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
  const name = `${namespace}${info.project.name} · 错误源`
  const { source, row } = await importSource(
    page,
    name,
    `/**\n * @name ${name}\n */\nthrow new Error('PRIVATE_SECRET_123 https://private.invalid/token');`,
    'invalid.js',
  )
  expect(source.status).toBe('error')
  const state = await sources(request)
  const stored = state.items.find((entry) => entry.id === source.id)!
  expect(stored.status).toBe('error')
  expect(stored.error).toEqual(expect.any(String))
  expect(stored.error!.length).toBeGreaterThan(0)
  await expect(row.getByRole('status')).toHaveText(stored.error!)
  await expect(row.getByRole('button', { name: '设为当前', exact: true })).toBeDisabled()
  expect(state.activeSourceId).toBe(baseline.id)
  for (const marker of ['PRIVATE_SECRET_123', 'private.invalid']) {
    expect(JSON.stringify(state)).not.toContain(marker)
    await expect(row).not.toContainText(marker)
  }
  await page.reload()
  await expect(row.getByRole('status')).toHaveText(stored.error!)
  await expect(sourceRow(page, validName).getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
  await removeSourceUI(page, name, source.id)
  expect((await sources(request)).activeSourceId).toBe(baseline.id)
})

test('音源缺少音质列表不使设置崩溃、不伪造播放能力，表格仍可删除', async ({ page, request }, info) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/settings')
  const name = `${namespace}${info.project.name} · 未声明音质`
  const { source, row } = await importSource(
    page,
    name,
    `/**\n * @name ${name}\n */
lx.on(lx.EVENT_NAMES.request,()=>Promise.resolve('https://example.com/a.mp3'));
lx.send(lx.EVENT_NAMES.inited,{sources:{wy:{name:'网抑云',type:'music',actions:['musicUrl']}}});`,
    'no-quality.js',
  )
  await expect(page.getByRole('heading', { name: 'LX 音源配置', exact: true })).toBeVisible()
  expect(
    (await jsonOK<{ capabilities: { play: boolean } }[]>(await request.get('/api/v1/providers')))[0]
      .capabilities.play,
  ).toBe(false)
  await page.reload()
  await expect(row).toBeVisible()
  expect(errors).toEqual([])
  await removeSourceUI(page, name, source.id)
  expect((await sources(request)).items.some((entry) => entry.id === source.id)).toBe(false)
})

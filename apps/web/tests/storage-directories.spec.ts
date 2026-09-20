import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { fixtureOrigin, freshSettings, jsonOK, mutationHeaders, prefixFor } from './settings-v5-e2e.helpers'

interface StorageState {
  authorized: boolean
  configured: boolean
  authorizationSource: string
  authorizedRoots: string[]
  authorizedRoot: string
  path?: string
}
interface SavedSettings {
  downloadRoot: string
  concurrency: number
  writeMetadata: boolean
  showDirect: boolean
}
const settingsAt = async (request: APIRequestContext, prefix: string) =>
  jsonOK<SavedSettings>(await request.get(`${prefix}/api/v1/settings`))
function patchResponse(page: Page) {
  return page.waitForResponse(
    (response) => response.url().endsWith('/api/v1/settings') && response.request().method() === 'PATCH',
  )
}
const feedback = (page: Page) => page.locator('#setting-downloadRoot-feedback')
let previous: SavedSettings

test.beforeEach(async ({ request, baseURL }, info) => {
  const prefix = prefixFor(info.project.name)
  fixtureOrigin(baseURL, prefix ? ['3782'] : ['3781'])
  const headers = await mutationHeaders(request, prefix, baseURL)
  previous = await settingsAt(request, prefix)
  await jsonOK(
    await request.patch(`${prefix}/api/v1/settings`, { headers, data: { downloadRoot: '', concurrency: 1 } }),
  )
})
test.afterEach(async ({ request, baseURL }, info) => {
  if (!previous) return
  const prefix = prefixFor(info.project.name)
  const headers = await mutationHeaders(request, prefix, baseURL)
  // 只恢复本spec修改的两项，不整对象覆盖主线并行修改的其它设置。
  await jsonOK(
    await request.patch(`${prefix}/api/v1/settings`, {
      headers,
      data: { downloadRoot: previous.downloadRoot, concurrency: previous.concurrency },
    }),
  )
})

test('目录Modal选择即自动PATCH保存，刷新与新浏览器上下文保留且网关采用多根授权', async ({
  page,
  request,
  browser,
  baseURL,
}, info) => {
  const prefix = prefixFor(info.project.name)
  const storage = await jsonOK<StorageState>(await request.get(`${prefix}/api/v1/storage/status`))
  expect(storage).toMatchObject({
    authorized: true,
    configured: false,
    authorizationSource: prefix ? 'fnos' : 'environment',
  })
  expect(storage.authorizedRoots).toHaveLength(prefix ? 2 : 1)
  const root = storage.authorizedRoots.at(-1)!
  const listed = await jsonOK<{ directories: { name: string; path: string }[] }>(
    await request.get(`${prefix}/api/v1/storage/directories?path=${encodeURIComponent(root)}`),
  )
  const directory = listed.directories.find((entry) => entry.name === `回归-${info.project.name}`)
  expect(directory, '必须使用harness预建的项目专用目录').toBeTruthy()
  expect(directory!.path.startsWith(`${root}/`)).toBe(true)
  await page.goto(`${prefix}/settings`)
  const field = page.getByRole('textbox', { name: '下载保存目录', exact: true })
  await expect(field).toHaveValue('')
  await expect(page.getByRole('button', { name: /^(保存设置|验证权限|刷新授权)$/ })).toHaveCount(0)
  const browse = page.getByRole('button', { name: '浏览', exact: true })
  await browse.scrollIntoViewIfNeeded()
  const before = await field.boundingBox()
  await browse.click()
  const dialog = page.getByRole('dialog', { name: '选择下载目录', exact: true })
  await expect(dialog).toBeVisible()
  expect(await field.boundingBox()).toEqual(before)
  await dialog.getByRole('button', { name: `打开目录 ${root}`, exact: true }).click()
  await dialog.getByRole('button', { name: `打开目录 ${directory!.path}`, exact: true }).click()
  // 复用隔离 harness 时，上一次成功保存已合法创建 Singles；以当前真实 API
  // 列表验证 UI，而不是误把‘测试目录最初为空’当作生产目录合同。
  const children = await jsonOK<{ directories: { path: string }[] }>(
    await request.get(`${prefix}/api/v1/storage/directories?path=${encodeURIComponent(directory!.path)}`),
  )
  await expect(dialog.locator('.directory-browser-path')).toHaveText(directory!.path)
  await expect(dialog.getByRole('button', { name: '选择此目录', exact: true })).toBeEnabled()
  await expect(dialog.locator('.directory-browser-list button')).toHaveCount(children.directories.length)
  for (const child of children.directories)
    await expect(dialog.getByRole('button', { name: `打开目录 ${child.path}`, exact: true })).toBeVisible()
  if (!children.directories.length) await expect(dialog).toContainText('此目录没有可浏览的子目录')
  const saving = patchResponse(page)
  await dialog.getByRole('button', { name: '选择此目录', exact: true }).click()
  const response = await saving
  expect(response.request().postDataJSON()).toEqual({ downloadRoot: directory!.path })
  if (prefix) expect(response.request().headers()['x-melora-csrf']).toBeTruthy()
  expect(await jsonOK<SavedSettings>(response)).toMatchObject({
    downloadRoot: directory!.path,
    writeMetadata: false,
    showDirect: false,
  })
  await expect(dialog).not.toBeVisible()
  await expect(field).toHaveValue(directory!.path)
  await expect(feedback(page)).toHaveText('已保存')
  expect((await settingsAt(request, prefix)).downloadRoot).toBe(directory!.path)
  await page.reload()
  await expect(field).toHaveValue(directory!.path)
  await freshSettings(browser, baseURL, prefix, async (fresh) => {
    await expect(fresh.getByRole('textbox', { name: '下载保存目录', exact: true })).toHaveValue(
      directory!.path,
    )
  })
  const updated = await jsonOK<StorageState>(await request.get(`${prefix}/api/v1/storage/status`))
  expect(updated).toMatchObject({ authorized: true, configured: true, path: directory!.path })
  // 保留真实验证接口越界拒绝断言；UI不再要求单独点验证按钮。
  const rejected = await request.post(`${prefix}/api/v1/storage/validate`, {
    headers: await mutationHeaders(request, prefix, baseURL),
    data: { path: '/etc' },
  })
  expect(rejected.status()).toBe(400)
  expect((await settingsAt(request, prefix)).downloadRoot).toBe(directory!.path)
  const geometry = await page.evaluate(() => ({
    width: document.documentElement.scrollWidth,
    viewport: innerWidth,
  }))
  expect(geometry.width).toBeLessThanOrEqual(geometry.viewport + 1)
  expect(before?.width).toBeGreaterThan(0)
})

test('文本目录仅blur时自动保存；目录加载失败可重试，关闭Modal不另改路径', async ({
  page,
  request,
  browser,
  baseURL,
}, info) => {
  const prefix = prefixFor(info.project.name)
  const storage = await jsonOK<StorageState>(await request.get(`${prefix}/api/v1/storage/status`))
  const root = storage.authorizedRoots.at(-1)!
  await page.goto(`${prefix}/settings`)
  const field = page.getByRole('textbox', { name: '下载保存目录', exact: true })
  await expect(field).toHaveValue('')
  const writes: unknown[] = []
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/settings') && request.method() === 'PATCH')
      writes.push(request.postDataJSON())
  })
  await field.fill(root)
  expect((await settingsAt(request, prefix)).downloadRoot).toBe('')
  expect(writes).toEqual([])
  const saved = patchResponse(page)
  await field.press('Tab')
  expect(await jsonOK<SavedSettings>(await saved)).toMatchObject({ downloadRoot: root })
  await expect(feedback(page)).toHaveText('已保存')
  expect(writes).toEqual([{ downloadRoot: root }])
  await page.route('**/api/v1/storage/directories*', (route) =>
    route.fulfill({
      status: 503,
      json: { error: { code: 'directory_unavailable', message: '目录读取测试失败' } },
    }),
  )
  await page.getByRole('button', { name: '浏览', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '选择下载目录', exact: true })
  await expect(dialog).toContainText('目录读取测试失败')
  await expect(dialog.getByRole('button', { name: '选择此目录', exact: true })).toBeDisabled()
  await page.unroute('**/api/v1/storage/directories*')
  await dialog.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(dialog.getByRole('button', { name: `打开目录 ${root}`, exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: `打开目录 ${root}`, exact: true }).click()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(field).toHaveValue(root)
  expect(writes).toEqual([{ downloadRoot: root }])
  expect((await settingsAt(request, prefix)).downloadRoot).toBe(root)
  await page.reload()
  await expect(field).toHaveValue(root)
  await freshSettings(browser, baseURL, prefix, async (fresh) => {
    await expect(fresh.getByLabel('下载保存目录', { exact: true })).toHaveValue(root)
  })
})

test('目录确认遭真实API拒绝时保留意图与重试，无关下拉仍可保存，修正路径后恢复', async ({
  page,
  request,
}, info) => {
  const prefix = prefixFor(info.project.name)
  const storage = await jsonOK<StorageState>(await request.get(`${prefix}/api/v1/storage/status`))
  await page.goto(`${prefix}/settings`)
  const field = page.getByRole('textbox', { name: '下载保存目录', exact: true })
  await field.fill('/etc')
  const rejected = patchResponse(page)
  await field.press('Enter')
  const response = await rejected
  expect(response.status()).toBe(400)
  expect(response.request().postDataJSON()).toEqual({ downloadRoot: '/etc' })
  const reason = (await response.json()).error.message as string
  expect(reason.length).toBeGreaterThan(0)
  await expect(feedback(page)).toHaveText(reason)
  await expect(field).toHaveValue('/etc')
  expect((await settingsAt(request, prefix)).downloadRoot).toBe('')
  // 点击重试可能先触发input blur；自动保存必须对同版本去重。
  const retried = patchResponse(page)
  await page.getByRole('button', { name: '重试保存下载保存目录', exact: true }).click()
  expect((await retried).status()).toBe(400)
  await expect(feedback(page)).toHaveText(reason)
  const otherSave = patchResponse(page)
  await page.getByLabel('同时下载任务数', { exact: true }).selectOption('2')
  const other = await otherSave
  expect(other.request().postDataJSON()).toEqual({ concurrency: 2 })
  expect(await jsonOK<SavedSettings>(other)).toMatchObject({ concurrency: 2, downloadRoot: '' })
  await expect(field).toHaveValue('/etc')
  const valid = storage.authorizedRoots.at(-1)!
  await field.fill(valid)
  const corrected = patchResponse(page)
  await field.press('Tab')
  expect(await jsonOK<SavedSettings>(await corrected)).toMatchObject({ downloadRoot: valid, concurrency: 2 })
  await expect(feedback(page)).toHaveText('已保存')
  await page.reload()
  await expect(field).toHaveValue(valid)
  await expect(page.getByLabel('同时下载任务数', { exact: true })).toHaveValue('2')
})

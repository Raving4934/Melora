import { expect, test } from '@playwright/test'
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
const prefix = '/app/melora'
const namespace = '网关上传回归源'
let activeBefore = ''

test.beforeEach(async ({ request, baseURL }) => {
  fixtureOrigin(baseURL, ['3782'])
  const state = await sources(request, prefix)
  activeBefore = state.items.some(
    (source) => source.id === state.activeSourceId && !source.name.startsWith(namespace),
  )
    ? state.activeSourceId
    : ''
  const headers = await mutationHeaders(request, prefix, baseURL)
  for (const source of state.items.filter((source) => source.name.startsWith(namespace))) {
    await jsonOK(
      await request.delete(`${prefix}/api/v1/sources/${encodeURIComponent(source.id)}`, { headers }),
    )
  }
})
test.afterEach(async ({ request, baseURL }) => {
  const headers = await mutationHeaders(request, prefix, baseURL)
  const state = await sources(request, prefix)
  for (const source of state.items.filter((source) => source.name.startsWith(namespace))) {
    await jsonOK(
      await request.delete(`${prefix}/api/v1/sources/${encodeURIComponent(source.id)}`, { headers }),
    )
  }
  const rest = await sources(request, prefix)
  if (activeBefore && rest.items.some((source) => source.id === activeBefore && source.status === 'ready')) {
    await jsonOK(
      await request.put(`${prefix}/api/v1/sources/active`, { headers, data: { id: activeBefore } }),
    )
  }
})

test('代理改写Host并剥离Fetch-Metadata，紧凑音源表格仍能真实上传/选择/检查/删除且拒绝越权', async ({
  page,
  request,
  browser,
  baseURL,
}, info) => {
  const origin = fixtureOrigin(baseURL, ['3782'])
  const name = `${namespace} · ${info.project.name}`
  const script = `/**\n * @name ${name}\n */
lx.on(lx.EVENT_NAMES.request,()=>Promise.resolve('https://example.com/test.mp3'));
lx.send(lx.EVENT_NAMES.inited,{sources:{wy:{name:'网抑云',type:'music',actions:['musicUrl'],qualitys:['128k']}}});
/*${'x'.repeat(100 * 1024)}*/`
  await page.goto(prefix + '/settings')
  const headers = await mutationHeaders(request, prefix, baseURL)
  const { source, row, response: uploaded } = await importSource(page, name, script, 'gateway-test.js')
  const sent = uploaded.request()
  expect(sent.url()).toBe(origin + prefix + '/api/v1/sources/import')
  expect(sent.headers()['x-melora-csrf']).toBeTruthy()
  expect(sent.headers()['content-type']).toContain('multipart/form-data; boundary=')
  expect(Buffer.byteLength(script)).toBeGreaterThan(64 * 1024)
  await expect(row.getByText('已初始化（正常）', { exact: true })).toBeVisible()
  const actual = await sources(request, prefix)
  expect(actual.items.find((entry) => entry.id === source.id)).toMatchObject({
    name,
    status: 'ready',
    platforms: { wy: { qualitys: ['128k'] } },
  })
  // 即使携带有效管理员令牌，跨origin和普通身份仍必须在写入前被拒绝。
  const attacked = await request.delete(`${prefix}/api/v1/sources/${encodeURIComponent(source.id)}`, {
    headers: { ...headers, Origin: 'https://evil.example' },
  })
  expect(attacked.status()).toBe(403)
  const member = await request.delete(`${prefix}/api/v1/sources/${encodeURIComponent(source.id)}`, {
    headers: { ...headers, 'x-fixture-user': 'member' },
  })
  expect(member.status()).toBe(403)
  expect((await sources(request, prefix)).items.some((entry) => entry.id === source.id)).toBe(true)
  if (actual.activeSourceId !== source.id) {
    const choosing = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/sources/active') && response.request().method() === 'PUT',
    )
    await row.getByRole('button', { name: '设为当前', exact: true }).click()
    const selected = await choosing
    expect(selected.request().headers()['x-melora-csrf']).toBeTruthy()
    expect(await jsonOK(selected)).toMatchObject({ activeSourceId: source.id })
  }
  await expect(row.getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
  const checking = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/v1/sources/${encodeURIComponent(source.id)}/check`) &&
      response.request().method() === 'POST',
  )
  await row.getByRole('button', { name: `检查音源 ${name}`, exact: true }).click()
  const checked = await checking
  expect(checked.request().headers()['x-melora-csrf']).toBeTruthy()
  expect(await jsonOK(checked)).toMatchObject({ id: source.id, status: 'ready' })
  await expect(row.getByRole('button', { name: `检查音源 ${name}`, exact: true })).toBeEnabled()
  await page.reload()
  await expect(row.getByText('已初始化（正常）', { exact: true })).toBeVisible()
  await expect(row.getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
  await freshSettings(browser, baseURL, prefix, async (fresh) => {
    await expect(sourceRow(fresh, name).getByText('已初始化（正常）', { exact: true })).toBeVisible()
    await expect(sourceRow(fresh, name).getByRole('button', { name: '使用中', exact: true })).toBeDisabled()
  })
  await page.screenshot({ path: test.info().outputPath('gateway-source-import.png'), fullPage: true })
  await removeSourceUI(page, name, source.id)
  expect((await sources(request, prefix)).items.some((entry) => entry.id === source.id)).toBe(false)
})

test('旧CSRF令牌仅安全刷新重试一次，新建歌单仍是一次真实201写入', async ({
  page,
  request,
  baseURL,
}, info) => {
  let replaced = false
  await page.route('**/api/v1/auth/session', async (route) => {
    const response = await route.fetch()
    if (!replaced) {
      replaced = true
      await route.fulfill({ response, json: { ...(await response.json()), csrfToken: 'expired-token' } })
    } else await route.fulfill({ response })
  })
  await page.goto(prefix + '/library')
  await page.locator('.page-heading').getByRole('button', { name: '新建歌单', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新建歌单', exact: true })
  const name = `回归 · 令牌刷新 · ${info.project.name}`
  await dialog.getByLabel('歌单名称', { exact: true }).fill(name)
  const statuses: number[] = []
  const bodies: (string | null)[] = []
  page.on('response', (response) => {
    if (response.request().method() === 'POST' && response.url().endsWith('/library/playlists')) {
      statuses.push(response.status())
      bodies.push(response.request().postData())
    }
  })
  const created = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith('/library/playlists') &&
      response.status() === 201,
  )
  await dialog.getByRole('button', { name: '创建歌单', exact: true }).click()
  const entity = await jsonOK<{ id: string }>(await created)
  expect(entity.id).toEqual(expect.any(String))
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
  expect(statuses).toEqual([403, 201])
  expect(bodies).toHaveLength(2)
  expect(bodies[1]).toBe(bodies[0])
  await expect(page.getByRole('heading', { name: '需要飞牛管理员身份' })).toHaveCount(0)
  try {
    await page.getByRole('button', { name: '删除这张歌单', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(page.getByRole('heading', { name, exact: true })).toHaveCount(0)
    expect(
      (await request.get(`${prefix}/api/v1/library/playlists/${encodeURIComponent(entity.id)}`)).status(),
    ).toBe(404)
  } finally {
    const remaining = await request.get(`${prefix}/api/v1/library/playlists/${encodeURIComponent(entity.id)}`)
    if (remaining.ok())
      await jsonOK(
        await request.delete(`${prefix}/api/v1/library/playlists/${encodeURIComponent(entity.id)}`, {
          headers: await mutationHeaders(request, prefix, baseURL),
        }),
      )
  }
})

test('设置自动PATCH携带CSRF，旧令牌仅403→200一次重试，越权拒绝且刷新持久化', async ({
  page,
  request,
  browser,
  baseURL,
}) => {
  const headers = await mutationHeaders(request, prefix, baseURL)
  const initial = await jsonOK<{ fileNameFormat: string; writeMetadata: boolean; showDirect: boolean }>(
    await request.get(`${prefix}/api/v1/settings`),
  )
  const next = initial.fileNameFormat === 'title' ? 'artist-title' : 'title'
  let replaced = false
  await page.route('**/api/v1/auth/session', async (route) => {
    const response = await route.fetch()
    if (!replaced) {
      replaced = true
      await route.fulfill({ response, json: { ...(await response.json()), csrfToken: 'expired-token' } })
    } else await route.fulfill({ response })
  })
  const statuses: number[] = []
  const patches: unknown[] = []
  page.on('response', (response) => {
    if (response.url().endsWith('/api/v1/settings') && response.request().method() === 'PATCH') {
      statuses.push(response.status())
      patches.push(response.request().postDataJSON())
      expect(response.request().headers()['x-melora-csrf']).toBeTruthy()
    }
  })
  try {
    await page.goto(prefix + '/settings')
    const select = page.getByLabel('文件命名格式', { exact: true })
    await expect(select).toHaveValue(initial.fileNameFormat)
    const saved = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/settings') &&
        response.request().method() === 'PATCH' &&
        response.status() === 200,
    )
    await select.selectOption(next)
    expect(await jsonOK(await saved)).toMatchObject({
      fileNameFormat: next,
      writeMetadata: false,
      showDirect: false,
    })
    await expect(page.locator('#setting-fileNameFormat-feedback')).toHaveText('已保存')
    expect(statuses).toEqual([403, 200])
    const expected = {
      fileNameFormat: next,
      ...(initial.writeMetadata ? { writeMetadata: false } : {}),
      ...(initial.showDirect ? { showDirect: false } : {}),
    }
    expect(patches).toEqual([expected, expected])
    const blocked = await request.patch(`${prefix}/api/v1/settings`, {
      headers: { ...headers, Origin: 'https://evil.example' },
      data: { fileNameFormat: initial.fileNameFormat },
    })
    expect(blocked.status()).toBe(403)
    const member = await request.patch(`${prefix}/api/v1/settings`, {
      headers: { ...headers, 'x-fixture-user': 'member' },
      data: { fileNameFormat: initial.fileNameFormat },
    })
    expect(member.status()).toBe(403)
    expect(await jsonOK(await request.get(`${prefix}/api/v1/settings`))).toMatchObject({
      fileNameFormat: next,
    })
    await page.reload()
    await expect(select).toHaveValue(next)
    await freshSettings(browser, baseURL, prefix, async (fresh) => {
      await expect(fresh.getByLabel('文件命名格式', { exact: true })).toHaveValue(next)
    })
  } finally {
    await jsonOK(
      await request.patch(`${prefix}/api/v1/settings`, {
        headers: await mutationHeaders(request, prefix, baseURL),
        data: { fileNameFormat: initial.fileNameFormat },
      }),
    )
  }
})

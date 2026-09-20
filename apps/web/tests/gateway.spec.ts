import { expect, test, type APIRequestContext, type FrameLocator, type Page } from '@playwright/test'

const prefix = '/app/melora'
const firstTitle = 'Maple Leaf Rag · 1906 年录音'
const sourceName = '回归 · 网关 CSRF 导入'

async function gatewayHeaders(request: APIRequestContext, baseURL: string | undefined) {
  expect(baseURL).toBeTruthy()
  const origin = new URL(baseURL!).origin
  const response = await request.get(`${prefix}/api/v1/auth/session`, {
    headers: { 'X-Melora-Origin': origin },
  })
  expect(response.ok()).toBe(true)
  const session = await response.json()
  expect(session).toMatchObject({ authenticated: true, required: false, authMode: 'fnos' })
  expect(session.csrfToken).toEqual(expect.any(String))
  expect(session.csrfToken.length).toBeGreaterThan(0)
  return { Origin: origin, 'X-Melora-CSRF': session.csrfToken as string }
}

async function openFirstChart(app: Page | FrameLocator) {
  await expect(app.getByRole('heading', { name: '排行榜', exact: true })).toBeVisible()
  await expect(app.locator('.chart-panel .track-row').first()).toBeVisible()
  const card = app.locator('.chart-panel-link').first()
  await expect(card).toHaveAttribute('href', /^\/app\/melora\/charts\/[^/]+$/)
  const href = (await card.getAttribute('href'))!
  const title = (await card.locator('h2').textContent())!
  await card.click()
  await expect(app.getByRole('heading', { name: title, exact: true })).toBeVisible()
  await expect(app.locator('.track-row')).toHaveCount(2)
  await expect(app.getByRole('link', { name: '返回排行榜', exact: true })).toHaveCount(0)
  return href
}

test.beforeEach(async ({ request, baseURL }) => {
  const headers = await gatewayHeaders(request, baseURL)
  const demo = await request.patch(`${prefix}/api/v1/providers/demo`, {
    headers,
    data: { enabled: true },
  })
  expect(demo.ok()).toBe(true)
  expect(await demo.json()).toMatchObject({ id: 'demo', enabled: true, isDemo: true })
  for (const path of [
    '/library/favorites/tracks/demo:maple-leaf-rag-1906',
    '/library/favorites/playlists/demo:coast',
  ]) {
    expect((await request.delete(`${prefix}/api/v1${path}`, { headers })).ok()).toBe(true)
  }
  const sources = await request.get(`${prefix}/api/v1/sources`)
  expect(sources.ok()).toBe(true)
  for (const source of (await sources.json()).items as { id: string; name: string }[]) {
    if (source.name === sourceName) {
      expect(
        (await request.delete(`${prefix}/api/v1/sources/${encodeURIComponent(source.id)}`, { headers })).ok(),
      ).toBe(true)
    }
  }
})

test('飞牛网关深层刷新遇到慢会话恢复时也不挂载 Web 登录页', async ({ page }) => {
  let releaseSession!: () => void
  const sessionGate = new Promise<void>((resolve) => {
    releaseSession = resolve
  })
  await page.route('**/app/melora/api/v1/auth/session', async (route) => {
    await sessionGate
    await route.continue()
  })

  await page.goto(`${prefix}/library?tab=history`)
  await expect(page.locator('.app-boot-shell')).toBeVisible()
  await expect(page.locator('.login-page')).toHaveCount(0)
  await expect(page.getByLabel('用户名')).toHaveCount(0)

  releaseSession()
  await expect(page.getByRole('heading', { name: '我的音乐', exact: true })).toBeVisible()
  await expect(page.locator('.login-page')).toHaveCount(0)
})

test('飞牛同源 iframe 内打开，所有资源和导航保留网关前缀', async ({ page, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin
  const errors: string[] = []
  const localRequests: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.origin === origin) localRequests.push(url.pathname)
  })
  await page.goto('/__test/desktop')
  const app = page.frameLocator('iframe[title="乐屿 · Melora"]')
  await expect(app.getByRole('heading', { name: '排行榜', exact: true })).toBeVisible()
  await expect(app.locator('.chart-panel .track-row').first()).toBeVisible()
  await expect(app.locator('.chart-panel-link').first()).toBeVisible()
  await expect(app.locator('html')).toHaveAttribute('data-host', 'fnos')
  await expect(app.locator('.app-shell')).toHaveCSS('margin-top', '0px')
  await expect(app.locator('.app-shell')).toHaveCSS('border-top-width', '0px')
  expect(await app.locator('.chart-panel-link img').first().getAttribute('src')).toContain(
    '/app/melora/covers/',
  )
  const chartPath = await openFirstChart(app)
  expect(page.frames().some((frame) => frame.url() === `${origin}${chartPath}`)).toBe(true)
  await app
    .getByRole('navigation', { name: '主导航' })
    .getByRole('link', { name: '排行榜', exact: true })
    .click()
  await expect(app.getByRole('heading', { name: '排行榜', exact: true })).toBeVisible()
  await expect(app.locator('.chart-panel .track-row').first()).toBeVisible()
  for (const name of ['发现', '歌单', '我的音乐', '排行榜']) {
    await app.getByRole('navigation', { name: '主导航' }).getByRole('link', { name, exact: true }).click()
    await expect(app.getByRole('heading', { name, exact: true, level: 1 })).toBeVisible()
    if (name === '发现') {
      await expect(app.locator('.daily-tracks .track-row')).toHaveCount(2)
      await expect(app.locator('.daily-playlists .collection-item').first()).toBeVisible()
      await expect(app.getByRole('link', { name: '进入新碟专区完整列表' })).toBeVisible()
      expect(localRequests.some((path) => path.endsWith('/api/v1/discovery/new-tracks'))).toBe(false)
    }
    await expect(app.locator('.query-pending:visible')).toHaveCount(0)
    await expect(app.locator('.mini-player')).toBeVisible()
  }
  await page.screenshot({ path: testInfo.outputPath('fnos-iframe.png') })
  expect(localRequests.some((path) => path.startsWith('/app/melora/assets/'))).toBe(true)
  expect(localRequests.some((path) => path.startsWith('/app/melora/api/v1/charts'))).toBe(true)
  expect(localRequests.filter((path) => /^\/(api|assets|covers)\//.test(path))).toEqual([])
  expect(errors).toEqual([])
})

test('网关深层页面刷新、收藏与 SSE 可用，不要求独立令牌', async ({ page, request, baseURL }) => {
  const sessionResponse = await request.get('/app/melora/api/v1/auth/session', {
    headers: { 'X-Melora-Origin': new URL(baseURL!).origin },
  })
  expect(sessionResponse.ok()).toBe(true)
  expect(await sessionResponse.json()).toMatchObject({
    authenticated: true,
    required: false,
    authMode: 'fnos',
    csrfToken: expect.any(String),
  })
  await page.goto('/app/melora/playlists/demo:coast')
  await expect(page.getByRole('heading', { name: '海岸慢听', exact: true })).toBeVisible()
  await page.reload()
  await expect(page.locator('.track-row')).toHaveCount(2)
  // 不按 button.last() 猜收藏控件：详情现在还包含刷新按钮。
  const favoriteResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      decodeURIComponent(new URL(response.url()).pathname) ===
        `${prefix}/api/v1/library/favorites/playlists/demo:coast`,
  )
  await page.getByRole('button', { name: '收藏歌单', exact: true }).click()
  const favorite = await favoriteResponse
  expect(favorite.ok()).toBe(true)
  expect(await favorite.request().headerValue('x-melora-csrf')).toBeTruthy()
  await expect(page.getByRole('button', { name: '已收藏', exact: true })).toBeVisible()
  await page.goto('/app/melora/downloads')
  await expect(page.getByText('实时更新', { exact: true })).toBeVisible()
  const event = await page.evaluate(
    () =>
      new Promise<string>((resolve) => {
        const source = new EventSource('/app/melora/api/v1/downloads/events')
        const timeout = setTimeout(() => {
          source.close()
          resolve('timeout')
        }, 3000)
        source.addEventListener(
          'downloads',
          (message) => {
            clearTimeout(timeout)
            source.close()
            resolve((message as MessageEvent<string>).data)
          },
          { once: true },
        )
      }),
  )
  expect(JSON.parse(event)).toEqual([])
  await page.getByRole('button', { name: '更多', exact: true }).click()
  await page.getByRole('menuitem', { name: '设置', exact: true }).click()
  await expect(page).toHaveURL(/\/app\/melora\/settings$/)
  await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible()
  await expect(page.getByText('访问令牌', { exact: true })).toHaveCount(0)
  const sources = page.getByRole('region', { name: 'LX音源管理', exact: true })
  await expect(sources.getByRole('heading', { name: 'LX 音源配置', exact: true })).toBeVisible()
  await expect(sources.getByText('尚未导入音源', { exact: true })).toBeVisible()
  await expect(sources.getByRole('button', { name: '导入 LX 音源', exact: true })).toBeVisible()
})

test('网关普通用户及无身份被拒绝，伪造身份与无令牌跨站写入仍被拦截', async ({ request, baseURL }) => {
  const anonymous = await request.get('/app/melora/api/v1/settings', {
    headers: {
      'x-fixture-user': 'anonymous',
      'x-trim-isadmin': 'true',
      'x-trim-userid': '1000',
      'x-trim-username': 'forged',
    },
  })
  expect(anonymous.status()).toBe(401)
  const member = await request.get('/app/melora/api/v1/settings', {
    headers: { 'x-fixture-user': 'member', 'x-trim-isadmin': 'true' },
  })
  expect(member.status()).toBe(403)
  const csrf = await request.delete('/app/melora/api/v1/library/history', {
    headers: { Origin: 'https://evil.example' },
  })
  expect(csrf.status()).toBe(403)
  const headers = await gatewayHeaders(request, baseURL)
  // 即使客户端 Origin 正确，主线反代移除 Fetch Metadata 后也不能免 token。
  const missingToken = await request.delete(`${prefix}/api/v1/library/history`, {
    headers: { Origin: headers.Origin, 'X-Melora-Origin': headers.Origin },
  })
  expect(missingToken.status()).toBe(403)
  const forgedToken = await request.delete(`${prefix}/api/v1/library/history`, {
    headers: { ...headers, 'X-Melora-CSRF': 'not-a-valid-csrf-token' },
  })
  expect(forgedToken.status()).toBe(403)
  const otherOrigin = await request.delete(`${prefix}/api/v1/library/history`, {
    headers: { ...headers, Origin: 'https://evil.example' },
  })
  expect(otherOrigin.status()).toBe(403)
  const allowed = await request.delete(`${prefix}/api/v1/library/history`, { headers })
  expect(allowed.ok()).toBe(true)
  expect(await allowed.json()).toEqual({ ok: true })
})

test('管理员会话丢失后清空旧页面，不继续显示受保护内容', async ({ page }) => {
  await page.goto('/app/melora/')
  const chartPath = await openFirstChart(page)
  await expect(page).toHaveURL((url) => url.pathname === chartPath)
  await page.setExtraHTTPHeaders({ 'x-fixture-user': 'member' })
  await page
    .locator('.track-row')
    .first()
    .getByRole('button', { name: `收藏 ${firstTitle}`, exact: true })
    .click()
  await expect(page.getByRole('heading', { name: '需要飞牛管理员身份' })).toBeVisible()
  await expect(page.locator('.track-row')).toHaveCount(0)
  await expect(page.locator('.mini-player')).toHaveCount(0)
})

test('网关真实反代差异下导入LX、刷新与删除均携带CSRF令牌', async ({ page, request, baseURL }) => {
  // 自包含 SDK 注册夹具，无远端请求，不读取仓库中的任何用户音源。
  const code = `/**
 * @name ${sourceName}
 * @version 1.0.0
 */
const { on, send, EVENT_NAMES } = globalThis.lx;
on(EVENT_NAMES.request, () => { throw new Error('fixture-no-media'); });
send(EVENT_NAMES.inited, { sources: { wy: { name: '回归测试接口', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });
/* ${'x'.repeat(80 * 1024)} */`
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(`${prefix}/settings`)
  await page.getByRole('button', { name: '导入 LX 音源', exact: true }).click()
  await page.getByLabel('LX音源文件').setInputFiles({
    name: 'gateway-csrf-fixture.js',
    mimeType: 'text/javascript',
    buffer: Buffer.from(code),
  })
  const importResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `${prefix}/api/v1/sources/import`,
  )
  await page.getByRole('button', { name: '导入并检查', exact: true }).click()
  const imported = await importResponse
  expect(imported.ok()).toBe(true)
  expect(await imported.request().headerValue('x-melora-csrf')).toBeTruthy()
  expect(await imported.request().headerValue('origin')).toBe(new URL(baseURL!).origin)
  const row = page.locator('.lx-source-row').filter({ hasText: sourceName })
  await expect(row.getByText('已初始化（正常）', { exact: true })).toBeVisible({ timeout: 15_000 })
  await page.reload()
  await expect(row.getByText('已初始化（正常）', { exact: true })).toBeVisible()
  const checked = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname.startsWith(`${prefix}/api/v1/sources/`) &&
      new URL(response.url()).pathname.endsWith('/check'),
  )
  await row.getByRole('button', { name: `检查音源 ${sourceName}`, exact: true }).click()
  expect((await checked).ok()).toBe(true)
  await expect(row.getByRole('button', { name: `检查音源 ${sourceName}`, exact: true })).toBeEnabled({
    timeout: 15_000,
  })
  await row.getByRole('button', { name: `删除音源 ${sourceName}`, exact: true }).click()
  const deleted = page.waitForResponse(
    (response) =>
      response.request().method() === 'DELETE' &&
      new URL(response.url()).pathname.startsWith(`${prefix}/api/v1/sources/`),
  )
  await page
    .getByRole('dialog', { name: '删除 LX 音源？', exact: true })
    .getByRole('button', { name: '确认删除', exact: true })
    .click()
  const removed = await deleted
  expect(removed.ok()).toBe(true)
  expect(await removed.request().headerValue('x-melora-csrf')).toBeTruthy()
  await expect(row).toHaveCount(0)
  const state = await request.get(`${prefix}/api/v1/sources`)
  expect(state.ok()).toBe(true)
  expect((await state.json()).items.some((item: { name: string }) => item.name === sourceName)).toBe(false)
  expect(errors).toEqual([])
})

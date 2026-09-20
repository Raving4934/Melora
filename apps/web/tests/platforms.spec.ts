import { expect, test, type Page } from '@playwright/test'

// 本文件大量使用 page.route 固定目录响应；禁止 Service Worker 抢先消费请求。
test.use({ serviceWorkers: 'block' })
const platforms = { wy: '网抑云', tx: '扣扣', kw: '酷沃', kg: '酷购', mg: '米咕' }
// 响应式平台筛选：桌面端是按钮组，移动端渲染为下拉框。
async function selectSource(page: Page, source: string, name: string) {
  const select = page.getByRole('combobox', { name: '选择音乐平台' })
  const button = page.getByRole('button', { name, exact: true })
  // 应用外壳可能仍在启动，先等待任一形态的控件挂载，再做响应式分支。
  await expect(select.or(button).first()).toBeVisible()
  if (await select.isVisible()) {
    await select.selectOption(source)
    return
  }
  await button.click()
}
const item = (source: string) => ({
  id: `${source}:chart_1`,
  providerId: source,
  title: `${platforms[source as keyof typeof platforms]}榜单测试`,
  description: '平台路由回归',
  coverUrl: '/covers/placeholder.svg',
  trackCount: 1,
  category: 'all',
})
const song = (source: string) => ({
  id: `${source}:song`,
  providerId: source,
  title: `${source}测试歌曲`,
  artist: '测试歌手',
  album: '测试专辑',
  duration: 180,
  coverUrl: '/covers/placeholder.svg',
  qualities: [],
  canDownload: false,
})
test('五个平台筛选可见且查询/榜单详情按对应ID请求', async ({ page, request }) => {
  const providers = await (await request.get('/api/v1/providers')).json()
  expect(providers.map((provider: { id: string }) => provider.id)).toEqual(Object.keys(platforms))
  await page.route('**/api/v1/charts**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/charts/featured') {
      const source = url.searchParams.get('source') || 'all'
      const items = (source === 'all' ? Object.keys(platforms) : [source]).map((id) => ({
        ...item(id),
        tracks: [song(id)],
      }))
      return route.fulfill({ json: { items, total: items.length, batch: 1, batches: 1 } })
    }
    if (url.pathname === '/api/v1/charts') {
      const source = url.searchParams.get('source') || 'all'
      const items = (source === 'all' ? Object.keys(platforms) : [source]).map((id) => item(id))
      return route.fulfill({ json: items })
    }
    const source = decodeURIComponent(url.pathname.split('/').at(-1)!).split(':')[0]!
    await route.fulfill({ json: { ...item(source), tracks: [song(source)] } })
  })
  for (const [source, name] of Object.entries(platforms)) {
    await page.goto('/')
    await selectSource(page, source, name)
    await expect(page).toHaveURL((url) => url.searchParams.get('source') === source)
    const panel = page.getByRole('article', { name: `${name}榜单测试`, exact: true })
    await expect(panel).toBeVisible()
    await expect(panel.locator('.track-row')).toContainText(`${source}测试歌曲`)
    await expect(
      panel.getByRole('button', { name: `播放 ${name}榜单测试 前三首`, exact: true }),
    ).toBeEnabled()
    await panel.locator('.chart-panel-link').click()
    await expect(page).toHaveURL((url) => decodeURIComponent(url.pathname) === `/charts/${source}:chart_1`)
    await expect(page.locator('.track-row')).toContainText(`${source}测试歌曲`)
  }
})
test('歌单分类使用所选平台参数；聚合局部失败不会推动或清空其它结果', async ({ page }) => {
  const queries: string[] = []
  await page.route('**/api/v1/playlist-categories*', (route) => {
    const source = new URL(route.request().url()).searchParams.get('source') || 'all'
    return route.fulfill({
      json: {
        categories:
          source === 'all' ? [] : [{ id: `${source}:tag_1`, name: `${source}分类`, group: '平台分类' }],
      },
    })
  })
  await page.route('**/api/v1/playlists?*', (route) => {
    const url = new URL(route.request().url())
    queries.push(url.search)
    const source = url.searchParams.get('source') || 'wy'
    return route.fulfill({
      json: [{ ...item(source === 'all' ? 'wy' : source), id: `${source}:playlist_1` }],
    })
  })
  await page.goto('/playlists?source=mg')
  await page.getByRole('button', { name: '选择歌单分类', exact: true }).click()
  await page
    .getByRole('dialog', { name: '全部分类', exact: true })
    .getByRole('button', { name: 'mg分类', exact: true })
    .click()
  await expect
    .poll(() =>
      queries.some((query) => {
        const params = new URLSearchParams(query)
        return params.get('source') === 'mg' && params.get('category') === 'mg:tag_1'
      }),
    )
    .toBe(true)
  let partial = false
  // 目录列表同样渲染 .catalog-warning，但它不是本用例的关注点：其响应未做固定时
  // 会打到真实服务端，可用性随同一次运行中其它用例改动的 LX 音源状态漂移，
  // 从而使"米咕的本次榜单请求未完成"被重复计入。此处显式固定为空目录。
  await page.route(/\/api\/v1\/charts\?/, (route) => route.fulfill({ json: [] }))
  await page.route('**/api/v1/charts/featured?*', (route) =>
    route.fulfill({
      headers: partial ? { 'X-Melora-Unavailable-Sources': 'mg' } : {},
      json: {
        items: (partial ? ['wy', 'tx', 'kw', 'kg'] : Object.keys(platforms)).map((source) => ({
          ...item(source),
          tracks: [song(source)],
        })),
        total: partial ? 4 : 5,
        batch: 1,
        batches: 1,
      },
    }),
  )
  await page.goto('/')
  await expect(page.locator('.chart-panel .track-row')).toHaveCount(4)
  const first = page.locator('.chart-panel').first()
  const before = await first.boundingBox()
  partial = true
  await page.getByRole('button', { name: '刷新排行榜', exact: true }).click()
  await expect(page.locator('.chart-panel')).toHaveCount(4)
  await expect(page.locator('.catalog-warning').filter({ hasText: '米咕的本次榜单请求未完成' })).toHaveCount(
    1,
  )
  expect(await first.boundingBox()).toEqual(before)
})

// 生产页面和真实会话来自Go；此处仅固定目录响应及其安全头，外部扣扣不参与离线CI。
test('扣扣搜索受限提示来自本次安全原因头，保留结果和重试几何，恢复后清除', async ({ page }) => {
  let attempts = 0
  let releaseRetry: (() => void) | undefined
  const retryGate = new Promise<void>((resolve) => {
    releaseRetry = resolve
  })
  const requests: string[] = []
  await page.route('**/api/v1/search?*', async (route) => {
    requests.push(route.request().url())
    attempts += 1
    const partial = attempts === 1
    if (!partial) await retryGate
    await route.fulfill({
      headers: partial
        ? {
            'X-Melora-Unavailable-Sources': 'tx',
            'X-Melora-Catalog-Issues': 'tx=access_restricted,wy=timeout',
          }
        : {},
      json: {
        tracks: [song('wy')],
        playlists: [],
        artists: [],
        albums: [],
        total: 1,
      },
    })
  })
  await page.goto('/search?q=TX%E6%8F%90%E7%A4%BA%E5%9B%9E%E5%BD%92&source=all')
  const warning = page.locator('.catalog-warning')
  await expect(warning).toContainText('扣扣的本次搜索访问受平台限制')
  await expect(warning).not.toContainText(/网抑云|登录|会员|扣扣暂不可用/)
  const rows = page.locator('.search-result-region .track-row')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('wy测试歌曲')
  const rowElement = await rows.first().elementHandle()
  const before = await rows.first().boundingBox()
  const retry = warning.getByRole('button', { name: '重试', exact: true })
  const buttonBefore = await retry.boundingBox()
  await retry.click()
  await expect(retry).toBeDisabled()
  expect(await retry.boundingBox()).toEqual(buttonBefore)
  expect(await rows.first().boundingBox()).toEqual(before)
  await expect(page.locator('.search-result-region .query-pending')).toHaveCount(0)
  releaseRetry!()
  await expect(warning).toHaveCount(0)
  await expect(rows).toHaveCount(1)
  expect(await rowElement!.evaluate((node) => node.isConnected)).toBe(true)
  expect(await rows.first().boundingBox()).toEqual(before)
  expect(requests).toHaveLength(2)
  expect(requests[0]).toBe(requests[1])
})

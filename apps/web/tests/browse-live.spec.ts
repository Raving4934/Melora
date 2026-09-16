import { expect, test, type Page } from '@playwright/test'

const names: Record<string, string> = {
  wy: '网抑云',
  tx: '扣扣',
  kw: '酷沃',
  kg: '酷购',
  mg: '米咕',
}
const providers = Object.keys(names)

// 响应式平台筛选：桌面端是按钮组，移动端渲染为下拉框；两套布局都要能选中。
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

// 榜单搜索在窄屏需要先展开；清空按钮在两种布局下标签不同。
async function searchCharts(page: Page, text: string) {
  const toggle = page.getByRole('button', { name: '搜索榜单', exact: true })
  if (await toggle.isVisible()) await toggle.click()
  await page.getByRole('searchbox', { name: '在榜单中检索' }).fill(text)
}
async function clearChartSearch(page: Page) {
  const inline = page.getByRole('button', { name: '清空榜单搜索', exact: true })
  const expanded = page.getByRole('button', { name: '关闭或清空榜单搜索', exact: true })
  if (await inline.isVisible()) await inline.click()
  else await expanded.click()
}

const track = (n: number, title: string, providerId = 'wy') => ({
  id: `${providerId}:${n}`,
  providerId,
  title,
  artist: '目录测试歌手',
  album: '目录测试专辑',
  duration: 180,
  coverUrl: '',
  qualities: [],
  canDownload: false,
})
const collection = (n: number, title: string, providerId = 'wy') => ({
  id: `${providerId}:${n}`,
  providerId,
  title,
  description: '',
  coverUrl: '',
  category: '测试',
  trackCount: 7,
  playCount: 125000,
})
const chartCategories = ['综合', '欧美', '摇滚', '影视']
const catalog = Array.from({ length: 164 }, (_, n) => ({
  ...collection(n, `精选测试榜${n + 1}`, providers[Math.min(4, Math.floor(n / 33))]),
  category: chartCategories[n % chartCategories.length],
}))
async function fixture(page: Page) {
  const state = {
    details: [] as string[],
    dailyRequests: 0,
    newRequests: 0,
    searchRequests: [] as string[],
    failDaily: false,
    failChart: false,
  }
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = decodeURIComponent(url.pathname.replace('/api/v1', ''))
    let body: unknown
    if (path === '/auth/session') body = { authenticated: true, required: false }
    else if (path.startsWith('/library/')) body = route.request().method() === 'GET' ? [] : { ok: true }
    else if (path === '/providers')
      body = providers.map((id) => ({
        id,
        name: names[id],
        enabled: true,
        capabilities: { charts: true, search: true, playlists: true },
      }))
    else if (path === '/charts/featured') {
      const source = url.searchParams.get('source') || 'all'
      if (source !== 'all') await new Promise((resolve) => setTimeout(resolve, 350))
      const available = source === 'all' ? catalog : catalog.filter((item) => item.providerId === source)
      const grouped = providers.map((providerId) =>
        available.filter((item) => item.providerId === providerId),
      )
      const ordered = Array.from({ length: Math.max(0, ...grouped.map((items) => items.length)) }, (_, row) =>
        grouped.map((items) => items[row]).filter((item) => item !== undefined),
      ).flat()
      const batches = Math.max(1, Math.ceil(ordered.length / 4))
      const requestedBatch = Number(url.searchParams.get('batch') || 1)
      const batch = Math.min(
        Number.isSafeInteger(requestedBatch) && requestedBatch > 0 ? requestedBatch : 1,
        batches,
      )
      const visible = ordered.slice((batch - 1) * 4, batch * 4)
      const unavailableIds: string[] = []
      const items = visible.map((item) => {
        state.details.push(item.id)
        if (state.failChart && item.id === catalog[0]!.id) {
          unavailableIds.push(item.id)
          return { ...item, tracks: [] }
        }
        return {
          ...item,
          tracks: Array.from({ length: 3 }, (_, n) =>
            track(Number(item.id.split(':')[1]) * 10 + n, `${item.title}歌曲${n + 1}`, item.providerId),
          ),
        }
      })
      body = { items, unavailableIds, total: ordered.length, batch, batches }
    } else if (path === '/charts') {
      const source = url.searchParams.get('source') || 'all'
      body = source === 'all' ? catalog : catalog.filter((item) => item.providerId === source)
    } else if (path.startsWith('/charts/')) {
      const id = path.slice('/charts/'.length)
      const item = catalog.find((entry) => entry.id === id)!
      body = {
        ...item,
        tracks: Array.from({ length: 7 }, (_, n) =>
          track(Number(id.split(':')[1]) * 10 + n, `${item.title}歌曲${n + 1}`, item.providerId),
        ),
      }
    } else if (path === '/recommendations/daily') {
      state.dailyRequests++
      if (state.dailyRequests > 1) await new Promise((resolve) => setTimeout(resolve, 350))
      if (state.failDaily) {
        await route.fulfill({
          status: 503,
          json: { error: { code: 'unavailable', message: '测试推荐暂不可用' } },
        })
        return
      }
      body = {
        tracks: Array.from({ length: 18 }, (_, n) => track(800 + n, `推荐歌曲${n + 1}`, providers[n % 5])),
        playlists: Array.from({ length: 12 }, (_, n) =>
          collection(900 + n, `推荐歌单${n + 1}`, providers[n % 5]),
        ),
        personalized: true,
        reason: '根据最近播放和收藏的歌手推荐',
        unavailableSources: ['mg'],
      }
    } else if (path === '/discovery/new-tracks') {
      state.newRequests++
      const area = url.searchParams.get('area') || 'all'
      body = {
        tracks: [track(999, `${area}新歌测试`)],
        areas: [
          { id: 'all', name: '全部' },
          { id: 'zh', name: '华语' },
        ],
      }
    } else if (path === '/playlist-categories')
      body = {
        categories: [
          { id: 'all', name: '全部', group: '' },
          { id: '华语', name: '华语', group: '语种' },
          { id: '摇滚', name: '摇滚', group: '风格' },
        ],
      }
    else if (path === '/playlists') {
      const category = url.searchParams.get('category') || 'all'
      const p = Number(url.searchParams.get('page') || 1)
      body = Array.from({ length: 24 }, (_, n) => collection(p * 100 + n, `${category}歌单第${p}页-${n + 1}`))
    } else if (path.startsWith('/playlists/'))
      body = { ...collection(100, '打开的测试歌单'), tracks: [track(1234, '歌单歌曲')] }
    else if (path === '/search') {
      state.searchRequests.push(url.search)
      const source = url.searchParams.get('source') || 'all'
      const selected = source === 'all' ? providers : [source]
      body = {
        tracks: selected.map((id, n) => track(2000 + n, `${names[id]}搜索歌曲`, id)),
        playlists: [],
        artists: [],
        albums: [],
        total: selected.length,
      }
    } else if (path.includes('/play-info')) {
      await route.fulfill({
        status: 409,
        json: { error: { code: 'lx_source_required', message: '请先导入并选择 LX 音源' } },
      })
      return
    } else {
      await route.continue()
      return
    }
    await route.fulfill({ json: body })
  })
  return state
}

test('榜单搜索兼容中文输入法组词，选词前不改写查询参数', async ({ page }) => {
  await fixture(page)
  await page.goto('/')
  if ((page.viewportSize()?.width ?? 999) <= 700) {
    const searchToggle = page.getByRole('button', { name: '搜索榜单' })
    await expect(searchToggle).toBeVisible()
    await searchToggle.click()
  }
  const input = page.getByRole('searchbox', { name: '在榜单中检索' })

  await input.dispatchEvent('compositionstart')
  await input.fill('wo ai')
  await expect(input).toHaveValue('wo ai')
  expect(new URL(page.url()).searchParams.get('q')).toBeNull()

  await input.fill('我爱')
  await expect(input).toHaveValue('我爱')
  expect(new URL(page.url()).searchParams.get('q')).toBeNull()

  await input.dispatchEvent('compositionend', { data: '我爱' })
  await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('我爱')
  await expect(input).toHaveValue('我爱')
})

test('164榜以4个热门榜试听、全量目录检索与平台筛选呈现', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/')
  const panels = page.locator('article.chart-panel')
  const directoryCards = page.locator('.chart-directory-card')
  await expect(panels).toHaveCount(4)
  await expect(page.locator('.chart-panel .track-row')).toHaveCount(12)
  await expect(directoryCards).toHaveCount(164)
  await expect(page.getByRole('button', { name: '换一批', exact: true })).toHaveCount(0)
  expect(new Set(state.details).size).toBe(4)
  expect(state.details).toHaveLength(4)
  await expect(panels.first()).toContainText('网抑云')
  await expect(panels.nth(3)).toContainText('酷购')

  const first = await panels.first().boundingBox()
  await page.getByRole('button', { name: '刷新排行榜', exact: true }).click()
  await expect(page.getByRole('button', { name: '刷新排行榜', exact: true })).toBeEnabled()
  expect(await panels.first().boundingBox()).toEqual(first)
  expect(new Set(state.details).size).toBe(4)

  await searchCharts(page, '精选测试榜35')
  await expect(page).toHaveURL(/q=/)
  await expect(directoryCards).toHaveCount(1)
  await expect(directoryCards.first()).toContainText('精选测试榜35')
  expect(new Set(state.details).size).toBe(4)
  await clearChartSearch(page)
  await expect(directoryCards).toHaveCount(164)
  await page.getByRole('button', { name: '语种榜', exact: true }).click()
  await expect(page).toHaveURL(/kind=language/)
  await expect(directoryCards).toHaveCount(41)
  await page.getByRole('button', { name: '全部', exact: true }).click()
  await expect(directoryCards).toHaveCount(164)

  await selectSource(page, 'tx', '扣扣')
  await expect(page).toHaveURL(/source=tx/)
  await expect(panels).toHaveCount(4)
  await expect(directoryCards).toHaveCount(33)
  await expect
    .poll(async () => (await panels.allTextContents()).every((text) => text.includes('扣扣')))
    .toBe(true)
  await page.getByRole('button', { name: '播放 精选测试榜34 前三首', exact: true }).click()
  await expect(page.locator('.mini-player')).toContainText('精选测试榜34歌曲1')
  await panels.first().locator('.chart-panel-link').click()
  await expect(page.getByRole('heading', { name: '精选测试榜34', exact: true })).toBeVisible()
  await expect(page.locator('.track-row')).toHaveCount(7)
  await expect(page.locator('.browse-back')).toHaveCount(0)
  await expect(page.getByRole('link', { name: '返回排行榜', exact: true })).toHaveCount(0)
  await page.goBack()
  await expect(panels).toHaveCount(4)
  await expect(directoryCards).toHaveCount(33)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('charts-directory.png'), fullPage: true })
})

test('发现直接消费daily的18曲12歌单，失败保留内容，次级新歌按需请求', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/discover')
  await expect(page.getByRole('heading', { name: '猜你喜欢', exact: true })).toBeVisible()
  await expect(page.locator('.daily-track-region .track-row')).toHaveCount(18)
  await expect(page.locator('.daily-playlists .collection-item')).toHaveCount(12)
  await expect(page.locator('.daily-playlists .collection-item').first()).toContainText('12.5万')
  await expect(page.getByRole('heading', { name: '发现', level: 1, exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '推荐歌单', exact: true })).toBeVisible()
  await expect(page.locator('.discovery-intro, .discovery-kicker')).toHaveCount(0)
  await expect(page.getByText('为你发现', { exact: true })).toHaveCount(0)
  await expect(page.locator('.daily-tracks .section-heading')).toContainText('根据最近播放和收藏的歌手推荐')
  await page.getByRole('button', { name: /推荐说明|推荐状态/ }).click()
  await expect(page.getByRole('dialog', { name: '推荐说明' })).toContainText('米咕')
  await page
    .getByRole('dialog', { name: '推荐说明' })
    .getByRole('button', { name: '关闭', exact: true })
    .click()
  expect(state.dailyRequests).toBe(1)
  expect(state.newRequests).toBe(0)
  const first = page.locator('.daily-playlists .collection-item').first()
  const before = await first.boundingBox()
  await page.getByRole('button', { name: '刷新发现推荐', exact: true }).click()
  await expect(page.locator('.daily-track-region .track-row')).toHaveCount(18)
  await expect(page.getByRole('button', { name: '刷新发现推荐', exact: true })).toBeEnabled()
  expect(await first.boundingBox()).toEqual(before)
  state.failDaily = true
  await page.getByRole('button', { name: '刷新发现推荐', exact: true }).click()
  await expect(page.getByRole('button', { name: '刷新发现推荐', exact: true })).toBeEnabled()
  await expect(page.locator('.daily-track-region .track-row')).toHaveCount(18)
  await expect(page.locator('.daily-playlists .collection-item')).toHaveCount(12)
  await page.getByRole('button', { name: '播放推荐', exact: true }).click()
  await expect(page.locator('.discovery-feedback')).toContainText('请先导入并选择 LX 音源')
  await expect(page.locator('.discovery-feedback').getByRole('link')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '重试播放', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '重试播放', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试播放', exact: true })).toBeEnabled()
  await page.getByRole('link', { name: '进入新碟专区完整列表' }).click()
  await expect(page.locator('.new-tracks-page .track-row')).toContainText('all新歌测试')
  await page
    .getByRole('group', { name: '新歌地区' })
    .getByRole('button', { name: '华语', exact: true })
    .click()
  await expect(page.locator('.new-tracks-page .track-row')).toContainText('zh新歌测试')
  expect(state.newRequests).toBe(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('discovery-daily.png'), fullPage: true })
})

test('歌单分类只从标题右侧弹层选择，翻页与详情可用', async ({ page }) => {
  await fixture(page)
  await page.goto('/playlists')
  await expect(page.getByRole('button', { name: '选择歌单分类' })).toBeVisible()
  await expect(page.locator('.category-shortcuts')).toHaveCount(0)
  await page.getByRole('button', { name: '选择歌单分类' }).click()
  const dialog = page.getByRole('dialog', { name: '全部分类' })
  await expect(dialog.getByRole('button', { name: '全部', exact: true })).toHaveCount(1)
  await dialog.getByRole('button', { name: '华语', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.locator('.collection-item').first()).toContainText('华语歌单第1页')
  await page.getByRole('button', { name: '下一页', exact: true }).last().click()
  await expect(page.locator('.collection-item').first()).toContainText('华语歌单第2页')
  await page.getByRole('button', { name: '选择歌单分类' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '摇滚', exact: true }).click()
  await expect(page.locator('.collection-item').first()).toContainText('摇滚歌单第1页')
  await page.getByRole('link', { name: '打开歌单 摇滚歌单第1页-1', exact: true }).click()
  await expect(page.getByRole('heading', { name: '打开的测试歌单', exact: true })).toBeVisible()
  await expect(page.locator('.browse-back')).toHaveCount(0)
  await expect(page.getByRole('link', { name: '返回歌单', exact: true })).toHaveCount(0)
})

test('搜索all只发一条聚合请求，保持后端五平台首曲顺序', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/search?q=测试')
  await expect(page.locator('.track-row')).toHaveCount(5)
  await expect(page.locator('.track-main strong')).toHaveText(providers.map((id) => `${names[id]}搜索歌曲`))
  expect(state.searchRequests).toHaveLength(1)
  expect(new URLSearchParams(state.searchRequests[0]).get('source')).toBe('all')
  await selectSource(page, 'tx', '扣扣')
  await expect(page.locator('.track-row')).toHaveCount(1)
  await expect(page.locator('.track-main strong')).toHaveText(['扣扣搜索歌曲'])
  expect(state.searchRequests).toHaveLength(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

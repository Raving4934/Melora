import { expect, test, type Page } from '@playwright/test'
const trackId = 'demo:maple-leaf-rag-1906'
const title = 'Maple Leaf Rag · 1906 年录音'
const secondTitle = 'The Entertainer · 2007 年键盘演奏'

// 必须经过真实卡片点击，避免用深链掩盖首页链接/点击回归。
async function openFirstChart(page: Page) {
  await expect(page.getByRole('heading', { name: '排行榜', exact: true })).toBeVisible()
  await expect(page.locator('.chart-panel .track-row').first()).toBeVisible()
  const card = page.locator('.chart-panel-link').first()
  await expect(card).toHaveAttribute('href', /^\/charts\/[^/]+$/)
  const href = (await card.getAttribute('href'))!
  const chartTitle = (await card.locator('h2').textContent())!
  await card.click()
  await expect(page).toHaveURL((url) => url.pathname === href)
  await expect(page.getByRole('heading', { name: chartTitle, exact: true })).toBeVisible()
  await expect(page.locator('.track-row')).toHaveCount(2)
  await expect(page.getByRole('button', { name: '返回上一页', exact: true })).toHaveCount(0)
  return href
}
function silentWav() {
  const length = 8000 * 30 * 2
  const buffer = Buffer.alloc(44 + length)
  buffer.write('RIFF', 0)
  buffer.writeUInt32LE(36 + length, 4)
  buffer.write('WAVEfmt ', 8)
  buffer.writeUInt32LE(16, 16)
  buffer.writeUInt16LE(1, 20)
  buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(8000, 24)
  buffer.writeUInt32LE(16000, 28)
  buffer.writeUInt16LE(2, 32)
  buffer.writeUInt16LE(16, 34)
  buffer.write('data', 36)
  buffer.writeUInt32LE(length, 40)
  return buffer
}
test.beforeEach(async ({ request }) => {
  const demo = await request.patch('/api/v1/providers/demo', { data: { enabled: true } })
  expect(demo.ok()).toBe(true)
  expect(await demo.json()).toMatchObject({ id: 'demo', enabled: true, isDemo: true })
  expect((await request.delete('/api/v1/library/history')).ok()).toBe(true)
  expect((await request.delete(`/api/v1/library/favorites/tracks/${trackId}`)).ok()).toBe(true)
  expect((await request.delete('/api/v1/library/favorites/playlists/demo:coast')).ok()).toBe(true)
  const settings = await request.put('/api/v1/settings', {
    data: {
      downloadRoot: '',
      concurrency: 1,
      writeLyrics: false,
      writeCover: false,
      embedTags: false,
      autoSwitchSource: true,
      fileNameFormat: 'title-artist',
      defaultQuality: 'standard',
      showDirect: true,
    },
  })
  expect(settings.ok()).toBe(true)
})
test('鉴权请求延迟时刷新任意页面都不挂载 Web 登录页', async ({ page }) => {
  let releaseSession!: () => void
  const sessionGate = new Promise<void>((resolve) => {
    releaseSession = resolve
  })
  await page.route('**/api/v1/auth/session', async (route) => {
    await sessionGate
    await route.continue()
  })

  await page.goto('/library?tab=history')
  await expect(page.locator('.app-boot-shell')).toBeVisible()
  await expect(page.locator('.login-page')).toHaveCount(0)
  await expect(page.getByLabel('用户名')).toHaveCount(0)

  releaseSession()
  await expect(page.getByRole('heading', { name: '我的音乐', exact: true })).toBeVisible()
  await expect(page.locator('.login-page')).toHaveCount(0)
})

test('Demo 目录 API、全部页面与稳定导航', async ({ page, request }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '排行榜', exact: true })).toBeVisible()
  await expect(page.locator('.chart-panel .track-row').first()).toBeVisible()
  await expect(page.locator('.chart-panel-link').first()).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主导航' }).getByRole('link')).toHaveCount(5)
  await expect(
    page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '下载' }),
  ).toHaveCount(0)
  await expect(page.locator('.page-heading .eyebrow, .chart-copy small, .content-footer')).toHaveCount(0)
  await expect(page.locator('.mini-track')).toContainText('未播放')
  await expect(page.locator('.mini-track')).toContainText('请选择歌曲')
  const response = await request.get('/api/v1/recommendations/daily')
  expect(response.ok()).toBe(true)
  const daily = (await response.json()) as {
    tracks: { title: string }[]
    playlists: { title: string }[]
  }
  expect(daily.tracks).toHaveLength(2)
  expect(daily.playlists.length).toBeGreaterThan(0)
  const newTracks = await request.get('/api/v1/discovery/new-tracks?area=all')
  expect(newTracks.ok()).toBe(true)
  const discovery = (await newTracks.json()) as {
    area: string
    areas: { id: string; name: string }[]
    tracks: { title: string; providerId: string }[]
  }
  expect(discovery.area).toBe('all')
  expect(discovery.tracks).toHaveLength(2)
  expect(discovery.tracks.every((track) => track.providerId === 'demo')).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('home.png') })
  const chartPath = await openFirstChart(page)
  const chartResponse = await request.get(`/api/v1${chartPath}`)
  expect(chartResponse.ok()).toBe(true)
  expect((await chartResponse.json()).tracks).toHaveLength(2)
  await page.reload()
  await expect(page.locator('.track-row')).toHaveCount(2)
  await page
    .getByRole('navigation', { name: '主导航' })
    .getByRole('link', { name: '排行榜', exact: true })
    .click()
  await expect(page).toHaveURL((url) => url.pathname === '/')
  await expect(page.locator('.chart-panel .track-row').first()).toBeVisible()
  for (const [name, heading] of [
    ['发现', '发现'],
    ['歌单', '歌单'],
    ['我的音乐', '我的音乐'],
  ]) {
    await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name, exact: true }).click()
    await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
    if (name === '发现') {
      await expect(page.getByRole('heading', { name: '猜你喜欢', exact: true })).toBeVisible()
      await expect(page.locator('.daily-tracks .track-row')).toHaveCount(daily.tracks.length)
      await expect(page.locator('.daily-playlists .collection-item')).toHaveCount(daily.playlists.length)
      await expect(page.getByRole('link', { name: '音源设置', exact: true })).toHaveCount(0)
      await expect(page.getByRole('link', { name: '管理音源', exact: true })).toHaveCount(0)
      await page.getByRole('link', { name: '进入新碟专区完整列表' }).click()
      await expect(page.locator('.new-tracks-page .track-row')).toHaveCount(discovery.tracks.length)
      await page.getByRole('link', { name: '返回发现', exact: true }).click()
      await expect(page.locator('.daily-feature, .demo-note')).toHaveCount(0)
    }
    await expect(page.locator('.mini-player')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  await page.getByRole('button', { name: '更多', exact: true }).click()
  await page
    .getByRole('menu', { name: '更多', exact: true })
    .getByRole('menuitem', { name: '下载任务', exact: true })
    .click()
  await expect(page.getByRole('heading', { name: '下载任务', exact: true })).toBeVisible()
  await expect(page.getByText('暂时没有进行中的任务')).toBeVisible()
  await page.getByRole('button', { name: '更多', exact: true }).click()
  await page
    .getByRole('menu', { name: '更多', exact: true })
    .getByRole('menuitem', { name: '设置', exact: true })
    .click()
  await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible()
  const sources = page.getByRole('region', { name: 'LX音源管理', exact: true })
  await expect(sources.getByRole('heading', { name: 'LX 音源配置', exact: true })).toBeVisible()
  await expect(sources.getByText('尚未导入音源', { exact: true })).toBeVisible()
  await expect(sources.getByRole('button', { name: '导入 LX 音源', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '音乐目录', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '保存设置', exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('settings.png') })
  expect(errors).toEqual([])
})
test('收藏歌曲、歌单和服务器持久化', async ({ page }) => {
  await page.goto('/')
  await openFirstChart(page)
  await page.getByRole('button', { name: `收藏 ${title}`, exact: true }).click()
  await expect(page.getByRole('button', { name: `取消收藏 ${title}`, exact: true })).toBeVisible()
  await page.goto('/library')
  await expect(page.locator('.track-row')).toHaveCount(1)
  await page.reload()
  await expect(page.getByText(title, { exact: true })).toBeVisible()
  await page.goto('/playlists/demo:coast')
  await page.getByRole('button', { name: '收藏歌单', exact: true }).click()
  await expect(page.getByRole('button', { name: '已收藏', exact: true })).toBeVisible()
  await page.goto('/library?tab=playlists')
  await expect(page.getByText('海岸慢听', { exact: true })).toBeVisible()
})
test('搜索歌曲/歌手/专辑/歌单与空态', async ({ page }) => {
  async function search(query: string) {
    const trigger = page.getByRole('button', { name: '打开搜索', exact: true })
    if (await trigger.isVisible()) await trigger.click()
    const field = page.getByRole('textbox', { name: '搜索关键词' })
    await field.fill(query)
    await field.press('Enter')
  }
  await page.goto('/search?q=Joplin')
  await expect(page.locator('.track-row')).toHaveCount(2)
  await page.getByRole('button', { name: '歌手', exact: true }).click()
  await expect(page.getByText('没有找到相关内容')).toBeVisible()
  await search('Marine')
  await expect(page.locator('.artist-grid .collection-item')).toHaveCount(1)
  await page.getByRole('button', { name: '专辑', exact: true }).click()
  // Router 的导航可能延后提交；先确认类型确已切换，再从全局入口发下一次查询。
  await expect(page).toHaveURL((url) => url.searchParams.get('type') === 'album')
  await expect(page.getByRole('button', { name: '专辑', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await search('Joplin')
  await expect(page.locator('.collection-item')).toHaveCount(2)
  await page.getByRole('button', { name: '歌单', exact: true }).click()
  await expect(page).toHaveURL((url) => url.searchParams.get('type') === 'playlist')
  await expect(page.getByRole('button', { name: '歌单', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await search('海岸')
  await expect(page.getByText('海岸慢听', { exact: true })).toBeVisible()
  await search('NO_MATCH_24680')
  await expect(page.getByText('没有找到相关内容')).toBeVisible()
})
test('浏览器实际播放、跨页不断播、沉浸封面歌词切换与队列', async ({ page }, testInfo) => {
  let directRequests = 0
  await page.route('https://upload.wikimedia.org/**', async (route) => {
    directRequests++
    await route.fulfill({
      status: 200,
      contentType: 'audio/wav',
      body: silentWav(),
      headers: { 'Access-Control-Allow-Origin': '*' },
    })
  })
  await page.goto('/')
  await openFirstChart(page)
  await page.getByRole('button', { name: '播放全部', exact: true }).click()
  await expect(page.locator('.mini-player').getByRole('button', { name: '暂停', exact: true })).toBeVisible()
  expect(directRequests).toBeGreaterThan(0)
  await page.getByRole('navigation').getByRole('link', { name: '发现', exact: true }).click()
  await expect(page.getByRole('heading', { name: '发现', exact: true, level: 1 })).toBeVisible()
  await expect(page.locator('.mini-player').getByRole('button', { name: '暂停', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '打开全屏播放器' }).click()
  await expect(page).toHaveURL(/now-playing/)
  await expect(page.locator('.main-nav')).toHaveCount(0)
  await expect(page.locator('.mini-player')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('now-playing.png') })
  await expect(page.locator('.direct-status')).toHaveCount(0)
  await page.getByRole('button', { name: '点击封面显示完整歌词', exact: true }).click()
  await expect(page.getByRole('button', { name: '显示封面', exact: true })).toBeVisible()
  await expect(page.getByRole('dialog', { name: '歌词', exact: true })).toHaveCount(0)
  await expect(page.getByRole('region', { name: '歌词', exact: true })).toContainText('暂无歌词')
  await page.getByRole('button', { name: '播放队列', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '播放队列 · 2' })).toBeVisible()
  await page.getByRole('dialog').getByText(secondTitle, { exact: true }).click()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('heading', { name: secondTitle, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '暂停', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '暂停', exact: true }).click()
  await page.getByRole('button', { name: '收起播放器' }).click()
  await page.getByRole('navigation').getByRole('link', { name: '我的音乐', exact: true }).click()
  await page.getByRole('tab', { name: '播放记录', exact: true }).click()
  await expect(page.locator('.track-row')).toHaveCount(2)
})
test('未授权下载不伪造成功，目录自动保存明确报错且其它偏好可保存', async ({ page }) => {
  await page.goto('/')
  await openFirstChart(page)
  const row = page.locator('.track-row').first()
  const save = row.getByRole('button', { name: `保存 ${title} 到飞牛`, exact: true })
  if (await save.isVisible()) await save.click()
  else {
    await row.getByRole('button', { name: `更多操作 ${title}`, exact: true }).click()
    await page
      .getByRole('dialog', { name: '歌曲操作', exact: true })
      .getByRole('button', { name: `保存 ${title} 到飞牛`, exact: true })
      .click()
  }
  await expect(page.getByText('先选择一个音乐保存位置')).toBeVisible()
  await page.getByRole('link', { name: '前往下载设置' }).click()
  const directory = page.getByRole('textbox', { name: '下载保存目录', exact: true })
  await directory.fill('/etc')
  await directory.press('Tab')
  await expect(page.locator('#download-directory-feedback')).toContainText(/授权|目录/)
  for (const label of ['另存歌词文件（LRC）', '另存封面图片']) {
    await expect(page.getByRole('switch', { name: label, exact: true })).toHaveCount(0)
  }
  await page.getByRole('switch', { name: '内嵌元数据（ID3）与封面', exact: true }).click()
  await expect(page.locator('#setting-embedTags-feedback')).toHaveText('已保存')
  await page.reload()
  await expect(page.getByRole('switch', { name: '内嵌元数据（ID3）与封面', exact: true })).toHaveAttribute(
    'aria-checked',
    'true',
  )
  await expect(directory).toHaveValue('')
  await expect(page.getByRole('switch', { name: '显示直连播放状态' })).toHaveCount(0)
})

test('刷新与移动端所有主要页面无横向溢出', async ({ page }) => {
  for (const path of [
    '/',
    '/discover',
    '/playlists',
    '/search?q=Joplin',
    '/library',
    '/downloads',
    '/settings',
    '/now-playing',
  ]) {
    await page.goto(path)
    await expect(page.locator('h1,h3').first()).toBeVisible()
    await expect(page.locator('.query-pending:visible')).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), path).toBe(true)
  }
})

test('慢速后台刷新保留歌曲行与播放器位置', async ({ page }) => {
  await page.goto('/')
  const chartPath = await openFirstChart(page)
  const firstRow = page.locator('.track-row').first()
  const before = await page.locator('.mini-player').boundingBox()
  const rowBefore = (await firstRow.boundingBox())!
  await firstRow.evaluate((element) => element.setAttribute('data-stability-marker', 'original'))
  await page.route(`**/api/v1${chartPath}`, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 800))
    await route.continue()
  })
  const refresh = page.getByRole('button', { name: '刷新榜单', exact: true })
  // 桌面/移动都必须能实际操作刷新，不以绕过控件的导航替代。
  await expect(refresh).toBeVisible()
  const response = page.waitForResponse(
    (response) => new URL(response.url()).pathname === `/api/v1${chartPath}`,
  )
  const buttonBefore = (await refresh.boundingBox())!
  await refresh.click()
  await expect(refresh).toBeDisabled()
  await expect(page.locator('.track-row[data-stability-marker="original"]')).toHaveCount(1)
  await expect(page.locator('.query-pending:visible')).toHaveCount(0)
  const buttonDuring = (await refresh.boundingBox())!
  expect(buttonDuring.width).toBeCloseTo(buttonBefore.width, 0)
  expect(buttonDuring.height).toBeCloseTo(buttonBefore.height, 0)
  expect(Math.abs((await firstRow.boundingBox())!.y - rowBefore.y)).toBeLessThan(2)
  expect(await page.locator('.mini-player').boundingBox()).toEqual(before)
  expect((await response).ok()).toBe(true)
  await expect(refresh).toBeEnabled()
  await expect(page.locator('.track-row[data-stability-marker="original"]')).toHaveCount(1)
  expect(await page.locator('.mini-player').boundingBox()).toEqual(before)
})

test('下载退避不丢失任务，可暂停和继续', async ({ page }) => {
  // 此项使用明确的 API/SSE 状态夹具，不声称写入实际音频。
  let state = 'retry_wait'
  const job = () => ({
    id: 'test-retry',
    quality: 'standard',
    state,
    bytesDone: 512,
    bytesTotal: 4096,
    speed: 0,
    targetPath: '/test-fixture/music.ogg',
    createdAt: '2026-09-07T00:00:00Z',
    updatedAt: '2026-09-07T00:00:00Z',
    track: {
      id: trackId,
      providerId: 'demo',
      title,
      artist: 'United States Marine Band',
      album: 'Demo',
      duration: 115,
      coverUrl: '/covers/paper.svg',
      qualities: ['standard'],
      canDownload: true,
    },
  })
  await page.route('**/api/v1/downloads', (route) => route.fulfill({ json: [job()] }))
  await page.route('**/api/v1/downloads/events', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: `event: downloads\ndata: ${JSON.stringify([job()])}\n\n`,
    }),
  )
  await page.route('**/api/v1/downloads/test-retry/*', async (route) => {
    state = route.request().url().endsWith('/pause') ? 'paused' : 'queued'
    await route.fulfill({ json: job() })
  })
  await page.goto('/downloads')
  await expect(page.getByText('等待重试', { exact: true })).toBeVisible()
  await expect(page.locator('.download-row')).toHaveCount(1)
  await page.getByRole('button', { name: `暂停 ${title}`, exact: true }).click()
  await expect(page.getByText('已暂停', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: `继续 ${title}`, exact: true }).click()
  await expect(page.getByText('排队中', { exact: true })).toBeVisible()
})

test('发现推荐与次级新歌独立，地区切换保留旧歌并显示更新状态', async ({ page, request }) => {
  const response = await request.get('/api/v1/discovery/new-tracks?area=all')
  expect(response.ok()).toBe(true)
  const demo = (await response.json()) as { tracks: { id: string; title: string; providerId: string }[] }
  expect(demo.tracks).toHaveLength(2)
  expect(demo.tracks.every((track) => track.providerId === 'demo')).toBe(true)
  // Demo 实际只提供“全部”；地区切换是明确的 UI 夹具，不冒充实时地区新歌数据。
  const areas = [
    { id: 'all', name: '全部' },
    { id: 'western', name: '欧美' },
    { id: 'zh', name: '华语' },
  ]
  const requestedAreas: string[] = []
  await page.route('**/api/v1/discovery/new-tracks?*', async (route) => {
    const query = new URL(route.request().url()).searchParams
    const area = query.get('area') || 'all'
    requestedAreas.push(area)
    expect(query.has('source')).toBe(false)
    if (area !== 'all') await new Promise((resolve) => setTimeout(resolve, 650))
    await route.fulfill({
      json: {
        title: '新歌速递',
        area,
        areas,
        tracks: area === 'all' ? demo.tracks : area === 'western' ? [demo.tracks[1]] : [],
      },
    })
  })
  await page.goto('/discover')
  await page.getByRole('link', { name: '进入新碟专区完整列表' }).click()
  await expect(page.getByRole('heading', { name: '新歌速递', exact: true, level: 1 })).toBeVisible()
  await expect(page.locator('.new-tracks-page .track-row')).toHaveCount(2)
  const filter = page.getByRole('group', { name: '新歌地区', exact: true })
  await expect(filter.getByRole('button')).toHaveText(areas.map((area) => area.name))
  await filter.getByRole('button', { name: '欧美', exact: true }).click()
  await expect(page.locator('.new-tracks-page .search-update-status')).toContainText(
    '正在切换地区，暂时保留上次歌曲…',
  )
  await expect(page.locator('.new-tracks-page .track-row')).toHaveCount(2)
  await expect.poll(() => requestedAreas.includes('western')).toBe(true)
  await expect(filter.getByRole('button', { name: '欧美', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await expect(page.locator('.new-tracks-page .track-row .track-main strong')).toHaveText([
    demo.tracks[1].title,
  ])
  await expect(page.locator('.new-tracks-page .search-update-status')).toContainText('1 首新歌')
  await filter.getByRole('button', { name: '华语', exact: true }).click()
  await expect(page.getByRole('heading', { name: '该地区暂无新歌', exact: true })).toBeVisible()
  await expect(page.locator('.new-tracks-page .track-row')).toHaveCount(0)
  await expect(page.locator('.new-tracks-page .search-update-status')).toContainText('0 首新歌')
  await filter.getByRole('button', { name: '全部', exact: true }).click()
  await expect(page.locator('.new-tracks-page .track-row')).toHaveCount(2)
  await expect(filter.getByRole('button', { name: '全部', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(requestedAreas).toEqual(expect.arrayContaining(['all', 'western', 'zh']))
  await page.getByRole('link', { name: '返回发现', exact: true }).click()
  await expect(page.locator('.daily-playlists .collection-item').first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('歌单分类来自API，全部仅一个，分类按钮实际切换结果', async ({ page, request }) => {
  const response = await request.get('/api/v1/playlist-categories')
  expect(response.ok()).toBe(true)
  const { categories } = (await response.json()) as {
    categories: { id: string; name: string; group: string }[]
  }
  expect(categories.length).toBeGreaterThan(0)
  const category = categories.find((item) => item.id !== 'all' && item.name !== '全部')!
  expect(category).toBeTruthy()
  const playlists = await request.get(
    `/api/v1/playlists?category=${encodeURIComponent(category.id)}&source=all&page=1`,
  )
  expect(playlists.ok()).toBe(true)
  const expected = (await playlists.json()) as { title: string }[]
  expect(expected.length).toBeGreaterThan(0)
  expect(expected.length).toBeLessThanOrEqual(24)
  await page.goto('/playlists')
  await page.getByRole('button', { name: '选择歌单分类', exact: true }).click()
  const categoryDialog = page.getByRole('dialog', { name: '全部分类', exact: true })
  await expect(categoryDialog).toBeVisible()
  await expect(categoryDialog.getByRole('button', { name: '全部', exact: true })).toHaveCount(1)
  for (const item of categories) {
    await expect(categoryDialog.getByRole('button', { name: item.name, exact: true })).toBeVisible()
  }
  await categoryDialog.getByRole('button', { name: category.name, exact: true }).click()
  await expect(categoryDialog).not.toBeVisible()
  await expect(page).toHaveURL(
    (url) => url.searchParams.get('category') === category.id && url.searchParams.get('page') === '1',
  )
  await expect(page.getByRole('button', { name: '选择歌单分类', exact: true })).toContainText(category.name)
  await expect(page.locator('.collection-grid .collection-copy > strong')).toHaveText(
    expected.map((item) => item.title),
  )
  const desktopPagination = page.getByRole('navigation', { name: '歌单分页', exact: true })
  const pagination = (await desktopPagination.count())
    ? desktopPagination
    : page.getByRole('navigation', { name: '歌单底部分页', exact: true })
  await expect(pagination.getByRole('button', { name: '上一页', exact: true })).toBeDisabled()
})

test('歌单24条每页，上下分页可点击且更换分类重置到第一页', async ({ page, request }) => {
  const categoryResponse = await request.get('/api/v1/playlist-categories')
  expect(categoryResponse.ok()).toBe(true)
  const { categories } = (await categoryResponse.json()) as { categories: { id: string; name: string }[] }
  const category = categories.find((item) => item.id !== 'all' && item.name !== '全部')!
  expect(category).toBeTruthy()
  const seedResponse = await request.get('/api/v1/playlists?source=all&category=all&page=1')
  expect(seedResponse.ok()).toBe(true)
  const seed = (await seedResponse.json())[0]
  expect(seed).toBeTruthy()
  // 25 张显式分页夹具，只验证 UI/查询参数，不宣称后端 Demo 有 25 张真实歌单。
  const items = Array.from({ length: 25 }, (_, index) => ({
    ...seed,
    id: `ui-fixture:page-${index + 1}`,
    title: `分页夹具 · ${index + 1}`,
    description: '仅用于分页控件回归测试',
  }))
  const queries: { page: number; category: string; source: string | null }[] = []
  await page.route('**/api/v1/playlists?*', async (route) => {
    const query = new URL(route.request().url()).searchParams
    const pageNumber = Number(query.get('page'))
    const selected = query.get('category') || 'all'
    expect(pageNumber).toBeGreaterThanOrEqual(1)
    queries.push({ page: pageNumber, category: selected, source: query.get('source') })
    await route.fulfill({
      json:
        selected === 'all'
          ? items.slice((pageNumber - 1) * 24, pageNumber * 24)
          : [{ ...items[0], title: '分类夹具 · 第一页' }],
    })
  })
  await page.goto('/playlists')
  const desktopPagination = page.getByRole('navigation', { name: '歌单分页', exact: true })
  const bottom = page.getByRole('navigation', { name: '歌单底部分页', exact: true })
  await expect(page.locator('.collection-grid').getByRole('link')).toHaveCount(24)
  const top = (await desktopPagination.count()) ? desktopPagination : bottom
  await expect(top.getByRole('button', { name: '上一页', exact: true })).toBeDisabled()
  await expect(top.getByRole('button', { name: '下一页', exact: true })).toBeEnabled()
  await bottom.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page).toHaveURL((url) => url.searchParams.get('page') === '2')
  await expect(page.locator('.collection-grid .collection-copy > strong')).toHaveText(['分页夹具 · 25'])
  await expect(top).toContainText('第 2 页')
  await expect(bottom).toContainText('第 2 页')
  await expect(top.getByRole('button', { name: '下一页', exact: true })).toBeDisabled()
  await expect(bottom.getByRole('button', { name: '下一页', exact: true })).toBeDisabled()
  await top.getByRole('button', { name: '上一页', exact: true }).click()
  await expect(page.locator('.collection-grid').getByRole('link')).toHaveCount(24)
  await top.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(page.locator('.collection-grid').getByRole('link')).toHaveCount(1)
  await page.getByRole('button', { name: '选择歌单分类', exact: true }).click()
  await page
    .getByRole('dialog', { name: '全部分类', exact: true })
    .getByRole('button', { name: category.name, exact: true })
    .click()
  await expect(page).toHaveURL(
    (url) => url.searchParams.get('category') === category.id && url.searchParams.get('page') === '1',
  )
  await expect(page.locator('.collection-grid .collection-copy > strong')).toHaveText(['分类夹具 · 第一页'])
  await expect(top.getByRole('button', { name: '上一页', exact: true })).toBeDisabled()
  expect(queries).toEqual(
    expect.arrayContaining([
      { page: 1, category: 'all', source: 'all' },
      { page: 2, category: 'all', source: 'all' },
      { page: 1, category: category.id, source: 'all' },
    ]),
  )
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('下载请求与文件格式分别可见，素材写入期间行和按钮不消失', async ({ page }, testInfo) => {
  // UI+真实EventSource重连夹具：不冒充真实平台媒体，不向真实目录写文件。
  if (testInfo.project.name === 'mobile') await page.setViewportSize({ width: 320, height: 740 })
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const track = {
    id: 'kw:format-v8',
    providerId: 'kw',
    title: '下载格式回归歌曲',
    artist: '测试歌手',
    album: '测试专辑',
    duration: 180,
    coverUrl: '',
    qualities: ['flac'],
    canDownload: true,
  }
  let current = {
    id: 'format-v8',
    track,
    quality: 'flac',
    state: 'resolving',
    bytesDone: 0,
    bytesTotal: 0,
    speed: 0,
    targetPath: '/fixture/Singles/下载格式回归歌曲.audio',
    createdAt: '2026-09-08T00:00:00Z',
    updatedAt: '2026-09-08T00:00:00Z',
    warning: '',
  }
  await page.route('**/api/v1/downloads', (route) => route.fulfill({ json: [current] }))
  await page.route('**/api/v1/downloads/events', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: `retry: 300\nevent: downloads\ndata: ${JSON.stringify([current])}\n\n`,
    }),
  )
  await page.goto('/downloads')
  const row = page.locator('.download-row')
  await expect(row).toHaveCount(1)
  await expect(row).toContainText('请求 无损·FLAC')
  await expect(row).toContainText('文件 未识别')
  const original = await row.elementHandle()
  const cancel = page.getByRole('button', { name: `取消 ${track.title}`, exact: true })
  const beforeRow = await row.boundingBox()
  const beforeCancel = await cancel.boundingBox()
  current = {
    ...current,
    state: 'downloading',
    bytesDone: 1024,
    bytesTotal: 2048,
    targetPath: '/fixture/Singles/下载格式回归歌曲.mp3',
  }
  await expect(row).toContainText('文件 MP3')
  expect(await original!.evaluate((node) => node.isConnected)).toBe(true)
  const afterRow = await row.boundingBox()
  const afterCancel = await cancel.boundingBox()
  expect(Math.abs(afterRow!.height - beforeRow!.height)).toBeLessThanOrEqual(1)
  expect(Math.abs(afterCancel!.x - beforeCancel!.x)).toBeLessThanOrEqual(1)
  expect(Math.abs(afterCancel!.y - beforeCancel!.y)).toBeLessThanOrEqual(1)
  current = { ...current, state: 'writing_metadata', bytesDone: 2048, speed: 0 }
  await expect(row).toContainText('正在写入附加信息')
  const writingCancel = await cancel.boundingBox()
  expect(Math.abs(writingCancel!.x - beforeCancel!.x)).toBeLessThanOrEqual(1)
  expect(Math.abs(writingCancel!.y - beforeCancel!.y)).toBeLessThanOrEqual(1)
  await expect(page.getByRole('tab', { name: /^进行中/ })).toContainText('1')
  expect(await original!.evaluate((node) => node.isConnected)).toBe(true)
  await expect(row).toContainText('文件 MP3')
  current = { ...current, state: 'completed', warning: '歌词获取失败，已保留音频' }
  await expect(page.getByRole('tab', { name: /^已完成/ })).toContainText('1')
  await page.getByRole('tab', { name: /^已完成/ }).click()
  await expect(row).toContainText('请求 无损·FLAC')
  await expect(row).toContainText('文件 MP3')
  await expect(row).toContainText('已完成')
  const completedWarning = row.getByText(current.warning, { exact: true })
  await expect(completedWarning).toHaveCount(1)
  await expect(completedWarning).toBeHidden()
  await row.getByText('下载详情', { exact: true }).click()
  await expect(completedWarning).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(errors).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('download-requested-vs-file.png') })
})

test('MIME纠正后的文件格式真实可见、技术说明按需展开，不把MP3宣称为FLAC', async ({ page }, testInfo) => {
  if (testInfo.project.name === 'mobile') await page.setViewportSize({ width: 320, height: 740 })
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  const jobs = ['flac', 'mp3'].map((format) => ({
    id: `mime-v9-${format}`,
    track: {
      id: `tx:mime-v9-${format}`,
      providerId: 'tx',
      title: `内容核验 ${format.toUpperCase()}`,
      artist: '测试歌手',
      album: '测试专辑',
      duration: 180,
      coverUrl: '',
      qualities: ['flac'],
      canDownload: true,
    },
    quality: 'flac',
    state: 'completed',
    bytesDone: 2048,
    bytesTotal: 2048,
    speed: 0,
    targetPath: `/fixture/Singles/内容核验.${format}`,
    createdAt: '2026-09-08T00:00:00Z',
    updatedAt: '2026-09-08T00:00:00Z',
    warning: `来源 MIME 声明与音频内容不一致，已通过结构核验并按 ${format.toUpperCase()} 格式保存；文件格式不代表实测音质（media_mime_corrected）`,
  }))
  await page.route('**/api/v1/downloads', (route) => route.fulfill({ json: jobs }))
  await page.route('**/api/v1/downloads/events', (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: `retry: 60000\nevent: downloads\ndata: ${JSON.stringify(jobs)}\n\n`,
    }),
  )
  await page.goto('/downloads')
  await page.getByRole('tab', { name: /^已完成/ }).click()
  await expect(page.locator('.download-row')).toHaveCount(2)
  for (const [index, format] of ['FLAC', 'MP3'].entries()) {
    const row = page.locator('.download-row').nth(index)
    await expect(row).toContainText('请求 无损·FLAC')
    await expect(row).toContainText(`文件 ${format}`)
    const warning = row.getByText(jobs[index]!.warning, { exact: true })
    await expect(warning).toContainText('media_mime_corrected')
    await expect(warning).toBeHidden()
    await row.getByText('下载详情', { exact: true }).click()
    await expect(warning).toBeVisible()
    await expect(row.locator('.inline-error')).toHaveCount(0)
    await expect(row).toContainText('已完成')
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.reload()
  await page.getByRole('tab', { name: /^已完成/ }).click()
  await expect(page.locator('.download-row').nth(1)).toContainText('文件 MP3')
  for (const [index, job] of jobs.entries()) {
    const warning = page.locator('.download-row').nth(index).getByText(job.warning, { exact: true })
    await expect(warning).toHaveCount(1)
    await expect(warning).toBeHidden()
  }
  expect(errors).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('mime-corrected-formats.png') })
})

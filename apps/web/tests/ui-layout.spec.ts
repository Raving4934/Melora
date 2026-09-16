import { expect, test, type Locator, type Page } from '@playwright/test'
import type { Collection, Track } from '../src/lib/types'

// 只纳入 desktop 项目；两个 describe 自设视口，避免被 mobile 项目重复执行。
// 全部 API/媒体/封面在浏览器 route 中隔离；不使用 request fixture 清理或写真实后端。
// 不在此文件启动服务器或构建 dist，运行时由主线准备现有 E2E 服务。
const demoOrigin = 'http://127.0.0.1:3781'
const fixtureOrigin = 'https://ui-layout.invalid'
const firstTitle = 'Maple Leaf Rag · 1906 年录音'
const firstID = 'demo:maple-leaf-rag-1906'
const commonCategories = ['华语', '流行', '欧美', '民谣', '电子', '轻音乐', 'ACG', '运动']
const rareCategory = '场景分类40'
const tracks: Track[] = Array.from({ length: 24 }, (_, index) => ({
  id: index === 0 ? firstID : `demo:layout-track-${index}`,
  providerId: 'demo',
  title:
    index === 0
      ? firstTitle
      : `布局演示歌曲 ${index + 1} · 一个包含中英文的较长歌曲标题 Long Recording Title`,
  artist: '演示歌手 · 长姓名与协作艺术家',
  album: '布局回归专辑 · Complete Demo Recordings',
  duration: 30,
  coverUrl: `${fixtureOrigin}/covers/track.svg`,
  qualities: ['standard'],
  canDownload: true,
}))
const charts: Collection[] = Array.from({ length: 6 }, (_, index) => ({
  id: `demo:layout-chart-${index}`,
  providerId: 'demo',
  title: `演示榜单 ${index + 1}`,
  description: '封面本身已印有榜单名称，界面标题应置于图外，不可再次叠在图上。',
  coverUrl: `${fixtureOrigin}/covers/chart-${index}.svg`,
  trackCount: tracks.length,
  category: '演示',
  tracks,
}))
const categories = [
  { id: 'all', name: '全部', group: '' },
  ...commonCategories.map((name) => ({ id: name, name, group: '常用' })),
  ...Array.from({ length: 40 }, (_, index) => ({
    id: `场景分类${index + 1}`,
    name: `场景分类${index + 1}`,
    group: '场景',
  })),
]
function playlists(category = 'all', page = 1): Collection[] {
  return Array.from({ length: 24 }, (_, index) => ({
    id: `demo:layout-playlist-${page}-${index}`,
    providerId: 'demo',
    title: `${category === 'all' ? '全部' : category} · 演示歌单 ${index + 1}`,
    description: '隔离的布局回归数据',
    coverUrl: `${fixtureOrigin}/covers/playlist.svg`,
    category,
    trackCount: tracks.length,
    playCount: 12500,
    tracks,
  }))
}
function silentWav() {
  const bytes = 8000 * 30 * 2
  const buffer = Buffer.alloc(44 + bytes)
  buffer.write('RIFF', 0)
  buffer.writeUInt32LE(36 + bytes, 4)
  buffer.write('WAVEfmt ', 8)
  buffer.writeUInt32LE(16, 16)
  buffer.writeUInt16LE(1, 20)
  buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(8000, 24)
  buffer.writeUInt32LE(16000, 28)
  buffer.writeUInt16LE(2, 32)
  buffer.writeUInt16LE(16, 34)
  buffer.write('data', 36)
  buffer.writeUInt32LE(bytes, 40)
  return buffer
}

async function installLayoutMock(page: Page, baseURL: string | undefined) {
  expect(baseURL, '布局用例只能连接既有 E2E Demo 静态入口').toBeTruthy()
  expect(new URL(baseURL!).origin).toBe(demoOrigin)
  const unexpected: string[] = []
  const favorites = new Set(tracks.map((track) => track.id))
  let history = tracks.slice(0, 3)
  let wav: Buffer | undefined
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = decodeURIComponent(url.pathname)
    const method = request.method()
    if (url.origin === fixtureOrigin && path.startsWith('/covers/')) {
      const chart = charts.find((item) => new URL(item.coverUrl).pathname === path)
      const title = chart?.title || 'Demo'
      // 模拟真实提供方已印字的封面；无需第三方封面、OCR 或截图像素快照。
      await route.fulfill({
        contentType: 'image/svg+xml',
        body: `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><rect width="400" height="400" fill="#365564"/><text x="200" y="210" text-anchor="middle" font-size="42" fill="white">${title}</text></svg>`,
      })
      return
    }
    if (url.origin === fixtureOrigin && path === '/audio.wav') {
      wav ??= silentWav()
      const range = /^bytes=(\d+)-(\d*)$/.exec(request.headers().range || '')
      const start = range ? Number(range[1]) : 0
      const end = range?.[2] ? Math.min(Number(range[2]), wav.length - 1) : wav.length - 1
      if (start > end || start >= wav.length) {
        await route.fulfill({ status: 416, headers: { 'Content-Range': `bytes */${wav.length}` } })
        return
      }
      await route.fulfill({
        status: range ? 206 : 200,
        contentType: 'audio/wav',
        headers: {
          'Accept-Ranges': 'bytes',
          'Access-Control-Allow-Origin': '*',
          ...(range ? { 'Content-Range': `bytes ${start}-${end}/${wav.length}` } : {}),
        },
        body: wav.subarray(start, end + 1),
      })
      return
    }
    if (url.origin === demoOrigin && path.startsWith('/api/v1/')) {
      const endpoint = path.slice('/api/v1'.length)
      if (method === 'GET' && endpoint === '/covers') {
        // 布局用例不做真实封面代理：拒绝后由 <img> 回退直连隔离的 fixture 封面。
        await route.fulfill({
          status: 400,
          json: { error: { code: 'cover_proxy_disabled', message: '布局用例直连封面' } },
        })
        return
      }
      let body: unknown
      if (method === 'GET') {
        if (endpoint === '/auth/session') body = { authenticated: true, required: false }
        else if (endpoint === '/providers')
          body = [
            {
              id: 'demo',
              name: '演示音源',
              enabled: true,
              isDemo: true,
              status: 'available',
              description: '浏览器内隔离的 Demo 数据',
              capabilities: {
                search: true,
                charts: true,
                playlists: true,
                recommendations: true,
                play: true,
                download: true,
              },
            },
          ]
        else if (endpoint === '/settings')
          body = {
            downloadRoot: '',
            concurrency: 1,
            writeMetadata: false,
            writeLyrics: false,
            writeCover: false,
            defaultQuality: 'standard',
            showDirect: true,
          }
        else if (endpoint === '/recommendations/daily')
          body = {
            tracks: tracks.slice(0, 18),
            playlists: playlists().slice(0, 12),
            personalized: true,
            reason: '根据最近播放和收藏的歌手推荐',
          }
        else if (endpoint === '/charts') body = charts
        else if (endpoint === '/charts/featured')
          body = {
            items: charts.map((chart) => ({ ...chart, tracks: chart.tracks?.slice(0, 3) })),
            total: charts.length,
            batch: Number(url.searchParams.get('batch') || 1),
            batches: 1,
            unavailableIds: [],
          }
        else if (endpoint.startsWith('/charts/'))
          body = charts.find((item) => item.id === endpoint.slice('/charts/'.length))
        else if (endpoint === '/discovery/new-tracks')
          body = {
            title: '新歌速递',
            area: url.searchParams.get('area') || 'all',
            areas: [
              { id: 'all', name: '全部' },
              { id: 'zh', name: '华语' },
              { id: 'western', name: '欧美' },
            ],
            tracks,
          }
        else if (endpoint === '/playlist-categories') body = { categories }
        else if (endpoint === '/playlists')
          body = playlists(
            url.searchParams.get('category') || 'all',
            Number(url.searchParams.get('page') || 1),
          )
        else if (endpoint.startsWith('/playlists/')) body = playlists()[0]
        else if (endpoint === '/library/summary')
          body = {
            favoriteTracks: favorites.size,
            favoritePlaylists: 4,
            userPlaylists: 0,
            history: history.length,
            historyTracks: 1,
            historyPlaylists: 1,
            historyAudiobooks: 1,
          }
        else if (endpoint === '/library/favorites/tracks')
          body = tracks.filter((track) => favorites.has(track.id))
        else if (endpoint === '/library/favorites/playlists')
          body = [
            ...playlists().slice(0, 3),
            {
              ...playlists()[0],
              id: 'kw:book_album_10250871',
              providerId: 'kw',
              title: '盗墓笔记',
              category: '有声专辑',
              trackCount: 250,
            },
          ]
        else if (endpoint === '/library/playlists') body = []
        else if (endpoint === '/library/history/entries') {
          const kinds = ['track', 'playlist', 'audiobook'] as const
          const entries = history.map((track, index) => ({
            track,
            kind: kinds[index % kinds.length]!,
            playedAt: history.length - index,
          }))
          const kind = url.searchParams.get('kind')
          body = kind ? entries.filter((entry) => entry.kind === kind) : entries
        } else if (endpoint === '/search')
          body = {
            tracks,
            playlists: playlists(),
            total: tracks.length,
            artists: [],
            albums: [],
            page: 1,
            pageSize: 24,
          }
        else if (endpoint.endsWith('/play-info')) {
          const id = endpoint.slice('/tracks/'.length, -'/play-info'.length)
          if (tracks.some((track) => track.id === id))
            body = { trackId: id, url: `${fixtureOrigin}/audio.wav`, mimeType: 'audio/wav', direct: true }
        } else if (endpoint.endsWith('/lyrics'))
          body = { lines: [{ time: 0, text: '布局演示歌词' }], source: 'Demo' }
        else if (endpoint.startsWith('/tracks/'))
          body = tracks.find((track) => track.id === endpoint.slice('/tracks/'.length))
      } else if (endpoint === '/library/history' && method === 'POST') {
        const data = request.postDataJSON() as { trackId: string }
        const track = tracks.find((item) => item.id === data.trackId)
        if (track) history = [track, ...history.filter((item) => item.id !== track.id)]
        body = { ok: true }
      } else if (endpoint.startsWith('/library/favorites/tracks/') && ['POST', 'DELETE'].includes(method)) {
        const id = endpoint.slice('/library/favorites/tracks/'.length)
        if (method === 'POST') favorites.add(id)
        else favorites.delete(id)
        body = { ok: true }
      }
      if (body !== undefined) {
        await route.fulfill({ json: body })
        return
      }
      // 未声明 API 失败关闭，不能 route.continue() 读取或修改共享用户数据。
      unexpected.push(`${method} ${endpoint}`)
      await route.fulfill({
        status: 501,
        json: { error: { code: 'layout_mock_missing', message: '布局测试缺少 mock' } },
      })
      return
    }
    if (url.origin === demoOrigin && method === 'GET' && !path.startsWith('/api/')) {
      await route.continue() // 只加载现有 HTML、JS、CSS、字体等静态资源。
      return
    }
    unexpected.push(`${method} ${url.origin}${path}`)
    await route.abort('blockedbyclient')
  })
  return unexpected
}

const testWithMock = test.extend<{ layoutMock: void }>({
  layoutMock: [
    async ({ page, baseURL }, use) => {
      const unexpected = await installLayoutMock(page, baseURL)
      await use()
      expect(unexpected, '所有 API 和外部请求必须经过隔离 mock').toEqual([])
    },
    { auto: true },
  ],
})
testWithMock.use({ serviceWorkers: 'block', reducedMotion: 'reduce' })

const pages = [
  { path: '/', heading: '排行榜', items: '.chart-panel', count: 4, songList: true },
  { path: '/discover', heading: '发现', items: '.discovery-hero-card', count: 2, songList: false },
  { path: '/discover/daily', heading: '猜你喜欢', items: '.track-row', count: 18, songList: true },
  {
    path: '/playlists',
    heading: '歌单',
    items: '.collection-grid .collection-item',
    count: 24,
    songList: false,
  },
  { path: '/library', heading: '我的音乐', items: '.track-row', count: 24, songList: true },
  { path: '/search?q=Maple', heading: '搜索', items: '.track-row', count: 24, songList: true },
]
async function openPage(page: Page, entry: (typeof pages)[number]) {
  await page.goto(entry.path)
  await expect(page.getByRole('heading', { name: entry.heading, exact: true, level: 1 })).toBeVisible()
  await expect(page.locator(entry.items)).toHaveCount(entry.count)
  await page.evaluate(() => document.fonts.ready.then(() => undefined))
}
async function minimumFont(locator: Locator, minimum: number) {
  const visible = await locator.evaluateAll((elements) =>
    elements
      .filter((element) => {
        const box = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && Number(style.opacity) > 0
      })
      .map((element) => ({
        text: element.textContent?.trim(),
        size: Number.parseFloat(getComputedStyle(element).fontSize),
      })),
  )
  expect(visible.length, '字体断言必须命中实际可见的正文，不能空列表通过').toBeGreaterThan(0)
  for (const item of visible) expect(item.size, `文字字号不足：${item.text}`).toBeGreaterThanOrEqual(minimum)
}
async function readableText(page: Page, songList: boolean) {
  if (songList) {
    await expect(page.locator('.track-row .track-main strong').first()).toBeVisible()
    await minimumFont(page.locator('.track-row .track-main strong'), 14)
    await minimumFont(
      page.locator('.track-main small, .track-artist, .track-artist small, .track-duration'),
      12,
    )
  } else {
    await minimumFont(page.locator('.collection-copy strong'), 14)
    await minimumFont(page.locator('.collection-meta'), 12)
  }
}
async function desktopWidth(page: Page) {
  const box = await page.locator('.content-inner').evaluate((element) => {
    const rect = element.getBoundingClientRect()
    const style = getComputedStyle(element)
    const left = rect.left + Number.parseFloat(style.paddingLeft) + Number.parseFloat(style.borderLeftWidth)
    const right =
      rect.right - Number.parseFloat(style.paddingRight) - Number.parseFloat(style.borderRightWidth)
    return { width: right - left, left, rightGap: innerWidth - right, viewport: innerWidth }
  })
  expect(box.width, '大屏不能把有效内容挤进窄栏').toBeGreaterThanOrEqual(box.viewport * 0.7)
  expect(box.left, '左侧总留白不超过视口 10%').toBeLessThanOrEqual(box.viewport * 0.1 + 1)
  expect(box.rightGap, '右侧总留白不超过视口 10%').toBeLessThanOrEqual(box.viewport * 0.1 + 1)
  expect(box.left).toBeGreaterThanOrEqual(-1)
  expect(box.rightGap).toBeGreaterThanOrEqual(-1)
}
async function noHorizontalOverflow(page: Page) {
  const layout = await page.evaluate(() => {
    const main = document.querySelector('#main-content')
    const content = document.querySelector('.content-inner')
    return {
      viewport: innerWidth,
      root: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
      mainOverflow: main ? main.scrollWidth - main.clientWidth : 0,
      contentOverflow: content ? content.scrollWidth - content.clientWidth : 0,
      left: content?.getBoundingClientRect().left ?? 0,
      right: content?.getBoundingClientRect().right ?? innerWidth,
    }
  })
  expect(layout.root).toBeLessThanOrEqual(layout.viewport + 1)
  expect(layout.body).toBeLessThanOrEqual(layout.viewport + 1)
  expect(layout.mainOverflow, '不能仅靠根 overflow:hidden 掩盖主内容溢出').toBeLessThanOrEqual(1)
  expect(layout.contentOverflow).toBeLessThanOrEqual(1)
  expect(layout.left).toBeGreaterThanOrEqual(-1)
  expect(layout.right).toBeLessThanOrEqual(layout.viewport + 1)
}
// canDownload=true 的 Demo 行也只能显示收藏+更多，不能用88px槽挤进3个44px按钮。
async function mobileTrackGeometry(page: Page, selector = '.track-row') {
  const geometry = await page.locator(selector).evaluateAll((elements) => {
    const box = (element: Element) => {
      const rect = element.getBoundingClientRect()
      return {
        left: rect.left,
        right: rect.right,
        top: rect.top,
        bottom: rect.bottom,
        width: rect.width,
        height: rect.height,
      }
    }
    return elements.map((row, index) => {
      const main = row.querySelector('.track-main')
      const title = row.querySelector('.track-main strong')
      const actions = row.querySelector('.track-actions')
      if (!main || !title || !actions) return { index, missing: true } as const
      const buttons = [...actions.querySelectorAll('button')]
        .filter((button) => {
          const rect = button.getBoundingClientRect()
          return rect.width > 0 && rect.height > 0 && getComputedStyle(button).visibility !== 'hidden'
        })
        .map(box)
      return {
        index,
        missing: false,
        main: box(main),
        title: box(title),
        actions: box(actions),
        buttons,
      } as const
    })
  })
  expect(geometry.length, '必须用真实渲染的可下载歌曲行校验几何关系').toBeGreaterThan(0)
  for (const row of geometry) {
    expect(row.missing, `第${row.index + 1}行缺少标题或操作区`).toBe(false)
    if (row.missing) continue
    for (const [label, box] of [
      ['标题', row.title],
      ['歌曲点击区域', row.main],
    ] as const) {
      const overlapX = Math.min(box.right, row.actions.right) - Math.max(box.left, row.actions.left)
      const overlapY = Math.min(box.bottom, row.actions.bottom) - Math.max(box.top, row.actions.top)
      expect(overlapX > 1 && overlapY > 1, `第${row.index + 1}行${label}与操作区重叠`).toBe(false)
    }
    expect(row.buttons, '390px行内保留收藏+更多，完整操作移入弹窗').toHaveLength(2)
    for (const button of row.buttons) {
      expect(button.width).toBeGreaterThanOrEqual(44)
      expect(button.height).toBeGreaterThanOrEqual(44)
      expect(button.left).toBeGreaterThanOrEqual(row.actions.left - 1)
      expect(button.right, '按钮不能溢出88px操作槽').toBeLessThanOrEqual(row.actions.right + 1)
      expect(button.right).toBeLessThanOrEqual(391)
    }
  }
}

async function touchable(locator: Locator) {
  await expect(locator).toBeVisible()
  await expect(locator).toBeEnabled()
  await locator.scrollIntoViewIfNeeded()
  const box = await locator.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.width, '关键触控目标宽度至少 44px').toBeGreaterThanOrEqual(44)
  expect(box!.height, '关键触控目标高度至少 44px').toBeGreaterThanOrEqual(44)
  await locator.click({ trial: true }) // 同时检查遮挡和真实命中区域，不用 force 点击绕过问题。
}

for (const width of [320, 390, 768, 1440, 1920]) {
  const mobile = width < 600
  testWithMock.describe(`${width}px 最终操作布局`, () => {
    testWithMock.use({
      viewport: { width, height: mobile ? 844 : 1080 },
      isMobile: mobile,
      hasTouch: mobile,
      deviceScaleFactor: 1,
    })
    for (const entry of pages) {
      testWithMock(`${entry.heading}：正文、页面与播放器无溢出`, async ({ page }, testInfo) => {
        await openPage(page, entry)
        if (entry.path === '/') await expect(page.locator('.chart-panel .track-row')).toHaveCount(12)
        await noHorizontalOverflow(page)
        if (entry.path === '/discover') {
          const heading = await page.locator('.page-heading').boundingBox()
          const firstSection = await page.locator('.daily-tracks .section-heading').boundingBox()
          expect(heading).not.toBeNull()
          expect(firstSection).not.toBeNull()
          expect(
            firstSection!.y - (heading!.y + heading!.height),
            '发现标题后不能预留巨大空白',
          ).toBeGreaterThanOrEqual(0)
          expect(firstSection!.y - (heading!.y + heading!.height), '首次推荐紧跟标题').toBeLessThanOrEqual(40)
        }
        if (!mobile) await desktopWidth(page)
        await readableText(page, entry.songList)
        if (width === 390 && entry.songList)
          await mobileTrackGeometry(
            page,
            entry.path === '/' ? '.chart-panel:first-of-type .track-row' : '.track-row',
          )
        await expect(page.getByRole('button', { name: '返回上一页', exact: true })).toHaveCount(0)
        await expect(page.getByRole('link', { name: '管理音源', exact: true })).toHaveCount(0)
        const footer = await page.locator('.mini-player').boundingBox()
        expect(footer).not.toBeNull()
        expect(footer!.x).toBeGreaterThanOrEqual(-1)
        expect(footer!.x + footer!.width).toBeLessThanOrEqual(width + 1)
        if (entry.path === '/' || entry.path === '/discover')
          await page.screenshot({ path: testInfo.outputPath('layout.png') })
      })
    }
    testWithMock('榜单封面与标题不重叠，根页面可直接播放', async ({ page }) => {
      await openPage(page, pages[0]!)
      await expect(page.locator('.chart-panel .track-row')).toHaveCount(12)
      const panel = page.getByRole('article', { name: charts[0]!.title, exact: true })
      const art = panel.locator('.chart-panel-link .cover')
      const title = panel.locator('.chart-panel-link h2')
      await expect(title).toHaveText(charts[0]!.title)
      const image = art.locator('img')
      await expect(image).toHaveAttribute('src', charts[0]!.coverUrl)
      await expect.poll(() => image.evaluate((e) => (e as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
      const a = (await art.boundingBox())!
      const b = (await title.boundingBox())!
      expect(
        Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x) > 1 &&
          Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y) > 1,
      ).toBe(false)
      await panel.getByRole('button', { name: `播放 ${charts[0]!.title} 前三首`, exact: true }).click()
      await expect(
        page.locator('.mini-player').getByRole('button', { name: '暂停', exact: true }),
      ).toBeVisible()
      await expect(page).toHaveURL((url) => url.pathname === '/')
      await expect(page.locator('.mini-track strong')).toHaveText(firstTitle)
      await panel.locator('.chart-panel-link').click()
      await expect(page.locator('.track-row')).toHaveCount(tracks.length)
    })
    testWithMock('分类移到标题右侧弹窗，展开关闭不推动歌单列表', async ({ page }) => {
      await openPage(
        page,
        pages.find((entry) => entry.path === '/playlists')!,
      )
      const trigger = page.getByRole('button', { name: '选择歌单分类', exact: true })
      await touchable(trigger)
      const card = page.locator('.collection-item').first()
      const before = await card.boundingBox()
      await trigger.click()
      const dialog = page.getByRole('dialog', { name: '全部分类', exact: true })
      await expect(dialog.getByRole('button', { name: '全部', exact: true })).toHaveCount(1)
      await expect(dialog.getByRole('button', { name: rareCategory, exact: true })).toBeVisible()
      expect(await card.boundingBox()).toEqual(before)
      await page.keyboard.press('Escape')
      await expect(dialog).not.toBeVisible()
      expect(await card.boundingBox()).toEqual(before)
      await trigger.click()
      await dialog.getByRole('button', { name: rareCategory, exact: true }).click()
      await expect(dialog).not.toBeVisible()
      await expect(trigger).toContainText(rareCategory)
      await expect(page.locator('.collection-copy strong').first()).toContainText(rareCategory)
      await expect(card.locator('.collection-play-count')).toContainText('1.3万')
      await noHorizontalOverflow(page)
    })
    testWithMock('底栏单行时间、进度和倍速可操作且不显示网络状态', async ({ page }) => {
      await openPage(
        page,
        pages.find((entry) => entry.path === '/discover/daily')!,
      )
      const mini = page.locator('.mini-player')
      const time = mini.locator('.player-time')
      await expect(time).toHaveText('00:00/00:00')
      await expect(mini.locator('.direct-status')).toHaveCount(0)
      await page.locator('.track-row .track-main').first().click()
      const pause = mini.getByRole('button', { name: '暂停', exact: true })
      await expect(pause).toBeVisible()
      await touchable(pause)
      await pause.click()
      await expect(time).toHaveText(/^\d{2}:\d{2}\/00:30$/)
      const progress = mini.getByRole('slider', { name: '播放进度', exact: true })
      await expect(progress).toBeEnabled()
      const before = Number(await progress.inputValue())
      await progress.focus()
      await progress.press('ArrowRight')
      await expect.poll(async () => Number(await progress.inputValue())).toBeGreaterThan(before)
      if (width <= 700) {
        await mini.locator('.mini-track').click()
        await page.locator('.player-immersive').getByRole('button', { name: '播放设置', exact: true }).click()
      } else await mini.getByRole('button', { name: '播放设置', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '播放设置', exact: true })
      const dialogBox = await dialog.boundingBox()
      expect(dialogBox).not.toBeNull()
      expect(dialogBox!.width, '播放设置应保持紧凑宽度').toBeLessThanOrEqual(442)
      if (width > 700) expect(dialogBox!.height, '桌面播放设置不应形成大面板').toBeLessThanOrEqual(560)
      const speed = dialog.getByRole('combobox', { name: '播放速度', exact: true })
      await speed.selectOption('1.5')
      await expect(speed).toHaveValue('1.5')
      await page.keyboard.press('Escape')
      if (width <= 700) await page.getByRole('button', { name: '收起播放器', exact: true }).click()
      await expect(mini.getByRole('button', { name: '播放', exact: true })).toBeVisible()
      await noHorizontalOverflow(page)
    })
  })
}

testWithMock.describe('滚动辅助与播放队列', () => {
  testWithMock.use({ viewport: { width: 1440, height: 940 } })
  const libraryPage = pages.find((entry) => entry.path === '/library')!
  testWithMock('长页面滚动时显示单个返回顶部/底部按钮并自动隐藏', async ({ page }) => {
    await openPage(page, libraryPage)
    const scroller = page.locator('.main-content')
    const up = page.getByRole('button', { name: '返回顶部', exact: true })
    const down = page.getByRole('button', { name: '返回底部', exact: true })
    const box = (await scroller.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await expect(up).toHaveCount(0)
    await expect(down).toHaveCount(0)
    await page.mouse.wheel(0, 600)
    await expect(up).toBeVisible()
    await expect(down).toHaveCount(0)
    await up.click()
    await expect.poll(() => scroller.evaluate((element) => element.scrollTop)).toBe(0)
    await expect(down).toBeVisible()
    await expect(up).toHaveCount(0)
    await expect(down).toHaveCount(0)
    await page.mouse.wheel(0, 600)
    await expect(up).toBeVisible()
    await expect(down).toHaveCount(0)
  })
  testWithMock('播放队列抽屉可用滚轮滚动且表头保持可见', async ({ page }) => {
    await openPage(page, libraryPage)
    await page.getByRole('button', { name: '播放全部', exact: true }).click()
    await expect(
      page.locator('.mini-player').getByRole('button', { name: '暂停', exact: true }),
    ).toBeVisible()
    await page.locator('.mini-player').getByRole('button', { name: '播放队列', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: /^播放队列 ·/ })
    const list = dialog.locator('.queue-list')
    await expect(list).toBeVisible()
    const headingBefore = (await dialog.locator('.modal-heading').boundingBox())!
    const box = (await list.boundingBox())!
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.wheel(0, 600)
    await expect.poll(() => list.evaluate((element) => element.scrollTop)).toBeGreaterThan(100)
    const headingAfter = (await dialog.locator('.modal-heading').boundingBox())!
    expect(Math.abs(headingAfter.y - headingBefore.y)).toBeLessThanOrEqual(1)
    expect(await dialog.evaluate((element) => element.scrollTop)).toBe(0)
  })
})

testWithMock.describe('音乐库来源与收藏', () => {
  testWithMock.use({ viewport: { width: 1440, height: 940 } })
  testWithMock('播放记录按来源筛选，收藏区分歌单与听书专辑', async ({ page }) => {
    await page.goto('/library?tab=history')
    await expect(page.getByRole('heading', { level: 1, name: '我的音乐' })).toBeVisible()
    const filters = page.getByRole('group', { name: '播放记录筛选' })
    await expect(filters.getByRole('button', { name: /听书专辑/ })).toBeVisible()
    await expect(page.locator('.track-kind-badge')).toHaveCount(2)
    await filters.getByRole('button', { name: /^歌单/ }).click()
    await expect(page).toHaveURL(/kind=playlist/)
    await expect(page.locator('.track-kind-badge')).toHaveCount(0)
    await expect(page.locator('.track-row')).toHaveCount(1)
    await page.getByRole('tab', { name: '收藏' }).click()
    await expect(page.getByRole('heading', { name: '收藏歌单' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '收藏听书专辑' })).toBeVisible()
    await expect(page.locator('a[href="/audiobooks/albums/kw%3Abook_album_10250871"]').first()).toBeVisible()
  })
})

testWithMock.describe('每日推荐批次', () => {
  testWithMock.use({ viewport: { width: 1440, height: 940 } })
  testWithMock('换一批请求新的探索批次', async ({ page }) => {
    const searches: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/recommendations/daily')) {
        searches.push(new URL(request.url()).search)
      }
    })
    await page.goto('/discover/daily')
    await expect(page.getByRole('heading', { name: '猜你喜欢' })).toBeVisible()
    await page.getByRole('button', { name: '换一批' }).click()
    await expect.poll(() => searches.some((search) => search.includes('batch=1'))).toBe(true)
  })
})

import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test as base, type Locator, type Page, type TestInfo } from '@playwright/test'
import type { Collection, DownloadJob, Provider, SearchResult, Settings, Track } from '../src/lib/types'

// 主线仅将本文件纳入 desktop testMatch；各 describe 自设视口，不能在此启动服务或 build。
// 所有 API 均为浏览器内 mock，只有既有3781入口的静态 GET 通行；不借用旧tests的可写fixture。
const demoOrigin = 'http://127.0.0.1:3781'
const artOrigin = 'https://polish-v6-art.invalid'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const runName = `run-${new Date().toISOString().replaceAll(':', '-')}-${process.pid}`
const evidenceRoot = path.join(root, '.superpowers/tmp/polish-v6-extra', runName)
const knownBadCDN = /^img[1-4]\.kwcdn\.kuwo\.cn$/i

// 预期值独立于被测 normalizeCoverURL；不在测试里调用生产 helper 生成自己的断言。
const oldCoverCases = [
  {
    raw: 'https://img1.kwcdn.kuwo.cn/star/upload/8/8/1543919795640_.png',
    expected: 'https://img1.kuwo.cn/star/upload/8/8/1543919795640_.png',
  },
  {
    raw: 'http://img2.kwcdn.kuwo.cn/star/upload/1/1/1554695862673_.png',
    expected: 'https://img2.kuwo.cn/star/upload/1/1/1554695862673_.png',
  },
  {
    raw: '//img3.kwcdn.kuwo.cn/star/upload/9/9/1543919747769_.png',
    expected: 'https://img3.kuwo.cn/star/upload/9/9/1543919747769_.png',
  },
  {
    raw: 'https://img4.kwcdn.kuwo.cn/star/upload/6/6/1554970547302_.png?v=legacy',
    expected: 'https://img4.kuwo.cn/star/upload/6/6/1554970547302_.png?v=legacy',
  },
  {
    raw: 'https://img2.kwcdn.kuwo.cn/star/upload/2/2/1543919658018_.png',
    expected: 'https://img2.kuwo.cn/star/upload/2/2/1543919658018_.png',
  },
  {
    raw: 'https://img1.kwcdn.kuwo.cn/star/upload/unverified-old-cover.png',
    expected: '/covers/placeholder.svg',
  },
]
const tracks: Track[] = Array.from({ length: 6 }, (_, index) => ({
  id: `kw:polish-track-${index + 1}`,
  providerId: 'kw',
  title: `夜航 ${index + 1} · 独立界面验收`,
  artist: '海岸乐队',
  album: '安静的晚风',
  duration: 180,
  coverUrl: `${artOrigin}/cover-${index + 1}.svg`,
  qualities: ['standard', '128k', '320k', 'flac'],
  canDownload: true,
}))
const oldTracks: Track[] = tracks.map((track, index) => ({
  ...track,
  id: `kw:legacy-cover-${index + 1}`,
  title: `旧酷沃封面验收 ${index + 1}`,
  coverUrl: oldCoverCases[index]!.raw,
}))
const collections: Collection[] = Array.from({ length: 4 }, (_, index) => ({
  id: `kw:polish-list-${index + 1}`,
  providerId: 'kw',
  title: ['夜晚散步', '午后轻音乐', '一路向海', '城市慢镜头'][index]!,
  description: '浏览器内隔离的推荐歌单',
  category: '轻音乐',
  coverUrl: `${artOrigin}/playlist-${index + 1}.svg`,
  trackCount: tracks.length,
  playCount: 2400,
  tracks,
}))
const providers: Provider[] = [
  ['kw', '酷沃'],
  ['wy', '网抑云'],
  ['tx', '扣扣'],
].map(([id, name]) => ({
  id: id!,
  name: name!,
  description: '浏览器 mock 平台',
  enabled: true,
  isDemo: false,
  status: 'available',
  capabilities: {
    play: true,
    search: true,
    charts: true,
    playlists: true,
    recommendations: true,
    download: true,
  },
}))
const settings: Settings = {
  downloadRoot: '/vol1/1000/Music/独立验收',
  fileNameFormat: 'title-artist',
  concurrency: 2,
  writeMetadata: false,
  writeLyrics: true,
  writeCover: false,
  embedTags: true,
  defaultQuality: '320k',
  autoSwitchSource: true,
  showDirect: false,
}
const sources = {
  available: true,
  activeSourceId: 'polish-source-1',
  items: [1, 2, 3].map((id) => ({
    id: `polish-source-${id}`,
    name: id === 2 ? '备用音源 · 一个包含长名称的界面验收样例' : `公开音乐音源 ${id}`,
    filename: `polish-${id}.js`,
    version: '1.0.0',
    author: 'UI 回归夹具',
    description: '不会导入或修改后端的虚拟音源',
    status: 'ready',
    allowHTTPHosts: [],
    platforms: {
      kw: { name: '酷沃', type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac'] },
      wy: { name: '网抑云', type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] },
    },
  })),
}
const jobs: DownloadJob[] = ['downloading', 'completed', 'failed'].map((state, index) => ({
  id: `polish-download-${index}`,
  track: tracks[index]!,
  quality: '320k',
  state,
  bytesDone: state === 'completed' ? 8_000_000 : 2_000_000,
  bytesTotal: 8_000_000,
  speed: state === 'downloading' ? 256_000 : 0,
  targetPath: `${settings.downloadRoot}/${tracks[index]!.title}.mp3`,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  error: state === 'failed' ? '隔离夹具：音源暂不可用' : undefined,
}))

type RecordedRequest = { url: string; method: string; type: string }
type Rect = { x: number; y: number; width: number; height: number }
type DailyGate = { requested: Promise<void>; release: () => void }
type Harness = {
  requests: RecordedRequest[]
  unexpected: string[]
  writes: string[]
  searchRequests: Record<string, string>[]
  evidence: Record<string, unknown>
  legacyCovers: boolean
  holdNextDaily: () => DailyGate
  shot: (name: string) => Promise<void>
}

async function installMock(page: Page, baseURL: string | undefined, info: TestInfo): Promise<Harness> {
  expect(baseURL, '必须复用主线已启动的静态入口').toBeTruthy()
  expect(new URL(baseURL!).origin, '仅允许3781的静态入口，禁止误连真实3780').toBe(demoOrigin)
  const directory = path.join(
    evidenceRoot,
    info.project.name,
    `${page.viewportSize()?.width}-${info.title.replace(/[^\p{L}\p{N}-]+/gu, '-').slice(0, 75)}`,
  )
  await fs.mkdir(directory, { recursive: true })
  const pageErrors: string[] = []
  const failedRequests: string[] = []
  const consoleErrors: string[] = []
  let pendingDaily: { arrive: () => void; wait: Promise<void>; release: () => void } | undefined
  const gates: DailyGate[] = []
  const harness: Harness = {
    requests: [],
    unexpected: [],
    writes: [],
    searchRequests: [],
    legacyCovers: false,
    evidence: { pageErrors, failedRequests, consoleErrors },
    holdNextDaily() {
      expect(pendingDaily, '上一延迟响应必须先释放').toBeUndefined()
      let arrive!: () => void
      let release!: () => void
      const requested = new Promise<void>((resolve) => {
        arrive = resolve
      })
      const wait = new Promise<void>((resolve) => {
        release = resolve
      })
      pendingDaily = { arrive, wait, release }
      const gate = { requested, release }
      gates.push(gate)
      return gate
    },
    async shot(name) {
      const file = path.join(directory, `${name}.png`)
      await page.screenshot({ path: file, fullPage: true, animations: 'disabled' })
      await info.attach(name, { path: file, contentType: 'image/png' })
    },
  }
  // 记录“发起尝试”，不以 route.abort 后网络失败/没有成功响应作为归一化成功证据。
  page.on('request', (request) => {
    harness.requests.push({ url: request.url(), method: request.method(), type: request.resourceType() })
    if (!['GET', 'HEAD'].includes(request.method()))
      harness.writes.push(`${request.method()} ${request.url()}`)
  })
  page.on('pageerror', (error) => pageErrors.push(error.message))
  page.on('requestfailed', (request) =>
    failedRequests.push(`${request.url()} ${request.failure()?.errorText}`),
  )
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  await page.route('**/*', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const endpoint = url.pathname.slice('/api/v1'.length)
    const mirroredLegacy = new Set(
      oldCoverCases.map((item) => item.expected).filter((item) => item.startsWith('http')),
    )
    const unverifiedMirror = 'https://img1.kuwo.cn/star/upload/unverified-old-cover.png'
    if (url.origin === demoOrigin && endpoint === '/covers' && request.method() === 'GET') {
      const target = url.searchParams.get('url') || ''
      if (target.startsWith(`${artOrigin}/`) || mirroredLegacy.has(target) || target === unverifiedMirror) {
        // 生产组件仅在直连失败后走同源代理；这里提供确定失败以验证最终占位回退。
        await route.fulfill({ status: 502, contentType: 'text/plain', body: 'isolated proxy miss' })
        return
      }
    }
    if (
      request.method() === 'GET' &&
      (url.origin === artOrigin || mirroredLegacy.has(url.href) || url.href === unverifiedMirror)
    ) {
      await route.fulfill({
        status: url.href === unverifiedMirror ? 404 : 200,
        contentType: 'image/svg+xml',
        body:
          url.href === unverifiedMirror
            ? ''
            : '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><rect width="400" height="400" fill="#315769"/><circle cx="285" cy="112" r="58" fill="#dfc99d"/><path d="M0 300Q120 160 230 295T400 270V400H0" fill="#527e85"/><text x="30" y="355" fill="white" font-size="30">夜航 · UI 验收</text></svg>',
      })
      return
    }
    if (url.origin === demoOrigin && url.pathname.startsWith('/api/v1/') && request.method() === 'GET') {
      let body: unknown
      if (endpoint === '/auth/session') body = { authenticated: true, required: false }
      else if (endpoint === '/providers') body = providers
      else if (endpoint === '/settings')
        body = harness.legacyCovers ? { ...settings, autoSwitchSource: false } : settings
      else if (endpoint === '/sources') body = sources
      else if (endpoint === '/storage/status')
        body = { authorized: true, configured: true, path: settings.downloadRoot }
      else if (endpoint === '/recommendations/daily') {
        const gate = pendingDaily
        pendingDaily = undefined
        if (gate) {
          gate.arrive()
          await gate.wait
        }
        body = {
          tracks: tracks.slice(0, 2),
          playlists: collections,
          personalized: true,
          reason: '两首歌曲的稀疏推荐验收',
        }
      } else if (endpoint === '/discovery/new-tracks')
        body = { tracks: [], areas: [{ id: 'all', name: '全部' }], area: 'all', title: '新歌速递' }
      else if (endpoint === '/charts') body = collections.slice(0, 2)
      else if (endpoint.startsWith('/charts/')) body = collections[0]
      else if (endpoint === '/playlist-categories')
        body = {
          categories: [
            { id: 'all', name: '全部', group: '' },
            { id: '轻音乐', name: '轻音乐', group: '风格' },
          ],
        }
      else if (endpoint === '/playlists') body = collections
      else if (endpoint.startsWith('/playlists/')) body = collections[0]
      else if (endpoint === '/library/summary')
        body = {
          favoriteTracks: harness.legacyCovers ? oldTracks.length : tracks.length,
          favoritePlaylists: collections.length,
          history: 2,
          userPlaylists: 0,
        }
      else if (endpoint === '/library/favorites/tracks') body = harness.legacyCovers ? oldTracks : tracks
      else if (endpoint === '/library/favorites/playlists') body = collections
      else if (endpoint === '/library/playlists') body = []
      else if (endpoint === '/library/history/entries')
        body = tracks.slice(0, 2).map((track, index) => ({ track, kind: 'track', playedAt: index }))
      else if (endpoint === '/search') {
        const query = Object.fromEntries(url.searchParams)
        harness.searchRequests.push(query)
        const data: SearchResult = {
          tracks: query.type === 'track' ? tracks : [],
          playlists: query.type === 'playlist' ? collections : [],
          artists:
            query.type === 'artist'
              ? [{ id: 'kw:artist', name: '海岸乐队', coverUrl: `${artOrigin}/artist.svg`, trackCount: 6 }]
              : [],
          albums:
            query.type === 'album'
              ? [
                  {
                    id: 'kw:album',
                    title: '安静的晚风',
                    artist: '海岸乐队',
                    coverUrl: `${artOrigin}/album.svg`,
                    trackCount: 6,
                  },
                ]
              : [],
          total: 120,
          page: Number(query.page || 1),
          pageSize: 24,
        }
        body = data
      } else if (endpoint.endsWith('/lyrics')) body = { lines: [], source: '只读封面验收夹具' }
      else if (endpoint.endsWith('/play-info')) {
        // 本用例只验封面，明确 mock 媒体不可用；不会触发播放成功后的历史写入。
        await route.fulfill({
          status: 503,
          json: { error: { code: 'cover_only_fixture', message: '封面验收不启动媒体播放' } },
        })
        return
      } else if (endpoint === '/downloads') body = jobs
      else if (endpoint === '/downloads/events') {
        await route.fulfill({
          contentType: 'text/event-stream',
          body: `retry: 60000\nevent: downloads\ndata: ${JSON.stringify(jobs)}\n\n`,
        })
        return
      }
      if (body !== undefined) {
        await route.fulfill({ json: body })
        return
      }
    }
    if (
      url.origin === demoOrigin &&
      request.method() === 'GET' &&
      !url.pathname.startsWith('/api') &&
      !url.pathname.startsWith('/__test')
    ) {
      await route.continue() // 仅现有 HTML / JS / CSS / 本地封面等静态 GET。
      return
    }
    // 包括全部写入、未声明API、已知坏CDN；安全阻断，但 request recorder 仍会使相关断言失败。
    harness.unexpected.push(`${request.method()} ${url.href}`)
    await route.abort('blockedbyclient')
  })
  info.annotations.push({
    type: 'isolation',
    description: '全部API/外部图片浏览器mock；仅3781静态GET通行；无后台状态修改',
  })
  // 由 auto fixture 调用，失败/超时也释放延迟响应，并落盘网络与几何证据。
  harness.evidence.finish = async () => {
    gates.forEach((gate) => gate.release())
    if (!page.isClosed()) {
      try {
        await harness.shot('final-state')
      } catch {
        /* 页面异常仍保留JSON/trace。 */
      }
    }
    const file = path.join(directory, 'audit.json')
    await fs.writeFile(
      file,
      JSON.stringify(
        {
          title: info.title,
          status: info.status,
          viewport: page.viewportSize(),
          requests: harness.requests,
          unexpected: harness.unexpected,
          writes: harness.writes,
          evidence: { ...harness.evidence, finish: undefined },
        },
        null,
        2,
      ),
    )
    await info.attach('network-and-layout-audit', { path: file, contentType: 'application/json' })
  }
  return harness
}
const test = base.extend<{ polish: Harness }>({
  polish: [
    async ({ page, baseURL }, use, info) => {
      const harness = await installMock(page, baseURL, info)
      try {
        await use(harness)
      } finally {
        expect.soft(harness.writes, '此验收不应发起任何后端写入').toEqual([])
        expect.soft(harness.unexpected, '禁止漏 mock 后继续访问共享后端或外部网络').toEqual([])
        expect.soft(harness.evidence.pageErrors, '页面不应出现运行时异常').toEqual([])
        expect
          .soft(
            harness.requests.filter((request) => knownBadCDN.test(new URL(request.url).hostname)),
            '必须没有坏CDN的请求尝试，不能用拦截后失败来伪装成功',
          )
          .toEqual([])
        await (harness.evidence.finish as () => Promise<void>)()
      }
    },
    { auto: true },
  ],
})
test.use({ serviceWorkers: 'block', reducedMotion: 'reduce' })

async function box(locator: Locator): Promise<Rect> {
  const value = await locator.boundingBox()
  expect(value, `缺少可测量节点：${locator}`).not.toBeNull()
  return value!
}
// 对齐基准是内容区，不把合法 border / padding 当作闲置或溢出。
async function contentBox(
  locator: Locator,
): Promise<Rect & { borderBox: Rect; insets: { left: number; right: number; top: number; bottom: number } }> {
  return locator.evaluate((element) => {
    const rect = element.getBoundingClientRect()
    const css = getComputedStyle(element)
    const px = (value: string) => Number.parseFloat(value) || 0
    const insets = {
      left: px(css.borderLeftWidth) + px(css.paddingLeft),
      right: px(css.borderRightWidth) + px(css.paddingRight),
      top: px(css.borderTopWidth) + px(css.paddingTop),
      bottom: px(css.borderBottomWidth) + px(css.paddingBottom),
    }
    return {
      x: rect.x + insets.left,
      y: rect.y + insets.top,
      width: rect.width - insets.left - insets.right,
      height: rect.height - insets.top - insets.bottom,
      borderBox: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
      insets,
    }
  })
}
const right = (value: Rect) => value.x + value.width
const bottom = (value: Rect) => value.y + value.height
async function noOverflow(page: Page) {
  const sizes = await page.evaluate(() => ({
    viewport: innerWidth,
    document: document.documentElement.scrollWidth,
    main: document.querySelector('.main-content')?.clientWidth,
    mainScroll: document.querySelector('.main-content')?.scrollWidth,
  }))
  expect.soft(sizes.document, '页面不能横向溢出').toBeLessThanOrEqual(sizes.viewport + 1)
  expect.soft(sizes.mainScroll, '主内容不能横向溢出').toBeLessThanOrEqual((sizes.main || 0) + 1)
}
async function ready(page: Page, route: string, heading: string) {
  await page.goto(route)
  await expect(page.getByRole('heading', { name: heading, exact: true, level: 1 })).toBeVisible()
  await expect(page.locator('.query-pending:visible')).toHaveCount(0)
  await page.evaluate(() => document.fonts.ready)
}
function globalSearchInputs(page: Page) {
  return page.locator('.header-search input:visible, .global-search-dialog input:visible')
}
async function openSearch(page: Page, width: number) {
  if (width <= 700) {
    await expect(globalSearchInputs(page)).toHaveCount(0)
    await page.getByRole('button', { name: '打开搜索', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '搜索', exact: true })).toBeVisible()
  }
  await expect(globalSearchInputs(page)).toHaveCount(1)
  const input = page.getByRole('textbox', { name: '搜索关键词', exact: true })
  await expect(input).toBeVisible()
  if (width <= 700) await expect(input).toBeFocused()
  return input
}
async function checkQuery(page: Page, harness: Harness, expected: Record<string, string>) {
  await expect.poll(() => Object.fromEntries(new URL(page.url()).searchParams)).toEqual(expected)
  await expect
    .poll(() => harness.searchRequests.at(-1))
    .toMatchObject({ ...expected, page: expected.page || '1' })
  await expect(page.locator('.search-results')).toHaveAttribute('aria-busy', 'false')
}

const widths = [320, 390, 768, 1440, 1920]
for (const width of widths) {
  test.describe(`v6独立验收-${width}`, () => {
    test.use({
      viewport: { width, height: width <= 390 ? 844 : 1000 },
      isMobile: width <= 700,
      hasTouch: width <= 700,
    })
    test.describe.configure({ timeout: 60_000 })

    test('设置卡片居中、响应式分栏与控件稳定对齐', async ({ page, polish }) => {
      await ready(page, '/settings', '设置')
      await expect(page.locator('.lx-source-row')).toHaveCount(3)
      await expect(page.getByLabel('下载保存目录', { exact: true })).toHaveValue(settings.downloadRoot)
      const inner = await contentBox(page.locator('.content-inner'))
      const content = await box(page.locator('.settings-content'))
      const source = await contentBox(page.locator('.settings-lx'))
      const preferences = await box(page.locator('.settings-preferences'))
      const downloads = await box(page.locator('.settings-downloads'))
      const playback = await box(page.locator('.settings-playback'))
      polish.evidence.settings = { inner, content, source, preferences, downloads, playback }
      expect.soft(content.width, '设置工作台最大宽度应保持可读').toBeLessThanOrEqual(1240)
      expect
        .soft(
          Math.abs(content.x - (inner.x + (inner.width - content.width) / 2)),
          '设置工作台应在主内容区水平居中',
        )
        .toBeLessThanOrEqual(2)
      expect.soft(Math.abs(source.borderBox.width - content.width)).toBeLessThanOrEqual(2)
      expect.soft(Math.abs(source.borderBox.width - preferences.width)).toBeLessThanOrEqual(2)
      expect.soft(preferences.y).toBeGreaterThanOrEqual(bottom(source.borderBox))
      if (width >= 1120) {
        expect.soft(Math.abs(downloads.y - playback.y), '下载/播放在>=1120视口并列').toBeLessThanOrEqual(1)
        expect.soft(right(downloads)).toBeLessThanOrEqual(playback.x)
        expect.soft(Math.abs(downloads.width - playback.width)).toBeLessThanOrEqual(1)
        expect.soft(Math.abs(right(playback) - right(source.borderBox))).toBeLessThanOrEqual(2)
      } else {
        expect.soft(playback.y, '<1120下载/播放必须纵向排列').toBeGreaterThanOrEqual(bottom(downloads))
        expect.soft(Math.abs(downloads.x - playback.x)).toBeLessThanOrEqual(1)
        expect.soft(Math.abs(downloads.width - playback.width)).toBeLessThanOrEqual(1)
      }
      const controlEvidence = []
      for (const section of await page.locator('.settings-preferences > section').all()) {
        const body = await contentBox(section.locator('.settings-card-body'))
        for (const item of await section.locator('.settings-item').all()) {
          const label = await box(item.locator('.settings-item-control > :is(label, strong)'))
          const control = await box(item.locator('.settings-item-control > :is(select, .switch)'))
          controlEvidence.push({ label, control, section: body })
          expect.soft(control.height, '设置控件命中高度').toBeGreaterThanOrEqual(40)
          expect
            .soft(Math.abs(right(control) - right(body)), '控件应与卡片内容右边对齐')
            .toBeLessThanOrEqual(2)
          if (width > 640) {
            expect.soft(right(label) + 8, '标签不得与控件重叠').toBeLessThanOrEqual(control.x)
          } else {
            expect.soft(control.y, '窄屏控件应换行到说明下方').toBeGreaterThanOrEqual(bottom(label))
          }
        }
      }
      polish.evidence.settingsControls = controlEvidence
      const sourceHeading = await contentBox(page.locator('.settings-lx .section-heading'))
      const importButton = await box(page.getByRole('button', { name: '导入 LX 音源', exact: true }))
      const importRightEdge = width <= 640 ? right(sourceHeading.borderBox) - 18 : right(sourceHeading)
      expect.soft(Math.abs(right(importButton) - importRightEdge)).toBeLessThanOrEqual(2)
      const sourceActions = []
      for (const row of await page.locator('.lx-source-row').all()) {
        const rowBox = await contentBox(row)
        const actions = await row.locator('.lx-source-actions button').all()
        const actionBoxes = await Promise.all(actions.map(box))
        sourceActions.push({
          row: rowBox,
          actionGroup: await contentBox(row.locator('.lx-source-actions')),
          actions: actionBoxes,
          buttonSizing: await row.locator('.source-select').evaluate((button) => ({
            width: getComputedStyle(button).width,
            minWidth: getComputedStyle(button).minWidth,
          })),
        })
        if (width > 900) {
          expect
            .soft(Math.abs(right(actionBoxes.at(-1)!) - right(rowBox)), '宽屏音源操作应靠分区右侧')
            .toBeLessThanOrEqual(2)
        } else {
          expect
            .soft(Math.abs(actionBoxes[0]!.x - rowBox.x), '窄屏音源操作应从内容左侧开始')
            .toBeLessThanOrEqual(2)
        }
        for (const [index, action] of actionBoxes.entries()) {
          expect.soft(action.width).toBeGreaterThanOrEqual(40)
          expect.soft(action.height).toBeGreaterThanOrEqual(40)
          if (index) expect.soft(right(actionBoxes[index - 1]!)).toBeLessThanOrEqual(action.x)
        }
      }
      polish.evidence.sourceActions = sourceActions
      await noOverflow(page)
      await polish.shot('settings-sources')
      await page.locator('.settings-downloads').scrollIntoViewIfNeeded()
      await polish.shot('settings-downloads')
      await page.locator('.settings-playback').scrollIntoViewIfNeeded()
      await polish.shot('settings-playback')
    })

    test('所有主页面全局搜索可用，排行榜保留目录检索与更多弹层', async ({ page, polish }) => {
      const pages = [
        ['/', '排行榜', 'charts'],
        ['/discover', '发现', 'discover'],
        ['/playlists', '歌单', 'playlists'],
        ['/library', '我的音乐', 'library'],
        ['/downloads', '下载任务', 'downloads'],
        ['/settings', '设置', 'settings'],
        ['/search?q=海风&type=track&source=all', '搜索', 'search'],
      ]
      for (const [route, heading, name] of pages) {
        await ready(page, route!, heading!)
        const before = page.url()
        await openSearch(page, width)
        expect.soft(page.url(), '打开手机搜索弹层不能先导航').toBe(before)
        await polish.shot(`global-search-${name}`)
        if (width <= 700) {
          await page.keyboard.press('Escape')
          await expect(page.getByRole('dialog', { name: '搜索', exact: true })).toHaveCount(0)
          await expect(page.getByRole('button', { name: '打开搜索', exact: true })).toBeFocused()
        }
        await noOverflow(page)
      }
      const more = page.getByRole('button', { name: '更多', exact: true })
      await more.click()
      const menu = page.getByRole('menu', { name: '更多', exact: true })
      await expect(menu).toBeVisible()
      await expect(more).toHaveAttribute('aria-expanded', 'true')
      await expect(menu.getByRole('menuitem', { name: '设置', exact: true })).toBeVisible()
      await expect(menu.getByRole('menuitem', { name: '下载任务', exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: /头像|用户菜单|账户菜单/ })).toHaveCount(0)
      await expect(page.locator('.avatar, .avatar-button')).toHaveCount(0)
      expect.soft(await menu.innerText()).not.toMatch(/访客|当前用户|本地用户/)
      await polish.shot('more-menu')
      await page.keyboard.press('Escape')
      await expect(menu).toHaveCount(0)
      await expect(more).toBeFocused()
    })

    test('搜索提交保留来源类型且各筛选正确重置分页', async ({ page, polish }) => {
      await ready(page, '/search?q=海风&type=album&source=kw&page=3', '搜索')
      const input = await openSearch(page, width)
      await expect(input).toHaveValue('海风')
      await input.fill('夜航')
      await input.press('Enter')
      await checkQuery(page, polish, { q: '夜航', type: 'album', source: 'kw' })
      await page.getByRole('button', { name: '下一页', exact: true }).click()
      await checkQuery(page, polish, { q: '夜航', type: 'album', source: 'kw', page: '2' })
      await page.getByRole('button', { name: '歌曲', exact: true }).click()
      await checkQuery(page, polish, { q: '夜航', type: 'track', source: 'kw' })
      await page.getByRole('button', { name: '下一页', exact: true }).click()
      await checkQuery(page, polish, { q: '夜航', type: 'track', source: 'kw', page: '2' })
      const sourceFilter =
        width <= 700
          ? page.getByRole('combobox', { name: '选择音乐平台', exact: true })
          : page.getByRole('button', { name: '网抑云', exact: true })
      if (width <= 700) await sourceFilter.selectOption('wy')
      else await sourceFilter.click()
      await checkQuery(page, polish, { q: '夜航', type: 'track', source: 'wy' })
      await expect(page.getByRole('button', { name: '歌曲', exact: true })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
      if (width <= 700) await expect(sourceFilter).toHaveValue('wy')
      else await expect(sourceFilter).toHaveAttribute('aria-pressed', 'true')
      polish.evidence.searchQueries = polish.searchRequests
      await noOverflow(page)
      await polish.shot('search-preserved-filters')
    })

    test('下载三种任务分组没有旧目录和说明面板', async ({ page, polish }) => {
      await ready(page, '/downloads', '下载任务')
      for (const label of ['进行中', '已完成', '失败与取消']) {
        await page
          .getByRole('tablist', { name: '下载任务分类', exact: true })
          .getByRole('tab', { name: new RegExp(`^${label}`) })
          .click()
        await expect(page.locator('.download-row')).toHaveCount(1)
        await expect(page.locator('.download-location, .download-explainer')).toHaveCount(0)
        await expect(page.getByText('下载目录', { exact: true })).toHaveCount(0)
        await noOverflow(page)
        await polish.shot(`downloads-${label}`)
      }
    })

    test('旧酷沃封面预归一化而非请求坏CDN后回退', async ({ page, polish }) => {
      polish.legacyCovers = true
      await ready(page, '/library', '我的音乐')
      await expect(page.locator('.track-row')).toHaveCount(oldTracks.length)
      const images = []
      for (const [index, track] of oldTracks.entries()) {
        const row = page.locator('.track-row').filter({ has: page.getByText(track.title, { exact: true }) })
        await row.scrollIntoViewIfNeeded()
        const image = row.locator('.cover img')
        const expected = oldCoverCases[index]!.expected
        await expect(image).toHaveAttribute('src', expected)
        await expect
          .poll(() => image.evaluate((node: HTMLImageElement) => node.complete && node.naturalWidth > 0))
          .toBe(true)
        const expectedURL = new URL(expected, demoOrigin).href
        await expect.poll(() => polish.requests.some((request) => request.url === expectedURL)).toBe(true)
        images.push({
          raw: track.coverUrl,
          src: await image.getAttribute('src'),
          currentSrc: await image.evaluate((node: HTMLImageElement) => node.currentSrc),
        })
      }
      const successfulTargets = new Set(
        oldCoverCases.map((item) => item.expected).filter((item) => item.startsWith('http')),
      )
      const unnecessaryProxyAttempts = polish.requests.filter((request) => {
        const url = new URL(request.url)
        return (
          url.origin === demoOrigin &&
          url.pathname === '/api/v1/covers' &&
          successfulTargets.has(url.searchParams.get('url') || '')
        )
      })
      expect(unnecessaryProxyAttempts, '直连成功的封面不得再占用 Melora 代理').toEqual([])
      const badAttempts = polish.requests.filter((request) => knownBadCDN.test(new URL(request.url).hostname))
      expect(badAttempts, '原始坏CDN请求发起次数必须为0，即使请求被route.abort也算失败').toEqual([])
      polish.evidence.legacyCovers = {
        images,
        badAttempts,
        responseBoundary: '镜像图片是隔离SVG，不声称真实CDN可用；断言记录的是发起请求',
      }
      await page.locator('.track-row').first().scrollIntoViewIfNeeded()
      await polish.shot('legacy-cover-normalized')
      const ambient = []
      for (const index of [0, oldTracks.length - 1]) {
        const track = oldTracks[index]!
        await page.getByRole('button', { name: `播放 ${track.title}`, exact: true }).click()
        await page.getByRole('button', { name: '打开全屏播放器', exact: true }).click()
        await expect(page.getByRole('heading', { name: track.title, exact: true, level: 1 })).toBeVisible()
        if (index === 0) {
          // 背景改为安全提色的Canvas色雾，不再放一张模糊封面img。
          await expect(page.locator('.player-atmosphere.ambient-canvas')).toBeVisible()
          await expect(page.locator('.player-album-cover img')).toHaveAttribute(
            'src',
            oldCoverCases[index]!.expected,
          )
        } else {
          await expect(page.locator('.player-atmosphere.ambient-canvas')).toBeVisible()
          await expect(page.locator('.player-album-cover img')).toHaveAttribute(
            'src',
            '/covers/placeholder.svg',
          )
        }
        ambient.push({
          raw: track.coverUrl,
          palette: await page.locator('.player-atmosphere').evaluate((node) => ({
            primary: (node as HTMLElement).style.getPropertyValue('--ambient-primary'),
            secondary: (node as HTMLElement).style.getPropertyValue('--ambient-secondary'),
          })),
        })
        expect(
          polish.requests.filter((request) => knownBadCDN.test(new URL(request.url).hostname)),
          '全屏背景不得绕过封面归一化',
        ).toEqual([])
        await polish.shot(`legacy-ambient-${index}`)
        await page.getByRole('button', { name: '收起播放器', exact: true }).click()
      }
      polish.evidence.legacyAmbient = ambient
      await noOverflow(page)
    })

    test('两首推荐无巨大空白且后台刷新保留旧几何', async ({ page, polish }) => {
      const initial = polish.holdNextDaily()
      await page.goto('/discover')
      await initial.requested
      const region = page.locator('.daily-track-region')
      await expect(region).toHaveAttribute('data-loaded', 'false')
      initial.release()
      const cards = page.locator('.discovery-hero-card')
      await expect(cards).toHaveCount(2)
      await expect(page.locator('.daily-feed')).toHaveAttribute('aria-busy', 'false')
      await expect(region).toHaveAttribute('data-loaded', 'true')
      await page.evaluate(() => document.fonts.ready)
      const first = await cards.first().elementHandle()
      const layout = async () => ({
        trackRegion: await box(region),
        firstCard: await box(cards.first()),
        lastCard: await box(cards.last()),
        playlists: await box(page.locator('.daily-playlists')),
        playlistRegion: await box(page.locator('.daily-playlist-region')),
        refresh: await box(page.getByRole('button', { name: '刷新发现推荐', exact: true })),
      })
      const before = await layout()
      const gap = before.playlists.y - bottom(before.lastCard)
      expect.soft(gap, '卡片结束到推荐歌单之间应<=40px，不保留巨大空白').toBeLessThanOrEqual(40)
      expect.soft(gap, '推荐歌单不能覆盖卡片').toBeGreaterThanOrEqual(0)
      await polish.shot('sparse-ready')
      const background = polish.holdNextDaily()
      await page.getByRole('button', { name: '刷新发现推荐', exact: true }).click()
      await background.requested
      await expect(page.locator('.daily-feed')).toHaveAttribute('aria-busy', 'true')
      await expect(cards).toHaveCount(2)
      await expect(region.locator('.query-pending')).toHaveCount(0)
      await expect(region).toHaveAttribute('data-loaded', 'true')
      const during = await layout()
      expect.soft(during, '后台刷新必须保留旧数据分区几何').toEqual(before)
      expect
        .soft(await cards.first().evaluate((node, old) => node === old, first), '旧首曲节点不能卸载重建')
        .toBe(true)
      await polish.shot('sparse-background-refresh')
      background.release()
      await expect(page.locator('.daily-feed')).toHaveAttribute('aria-busy', 'false')
      expect.soft(await layout(), '相同数据刷新完成后几何不变').toEqual(before)
      expect.soft(await cards.first().evaluate((node, old) => node === old, first)).toBe(true)
      polish.evidence.sparseRecommendations = { before, during, after: await layout(), gap }
      await noOverflow(page)
    })
  })
}

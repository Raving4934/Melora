// 独立播放器 mock 浏览器验证：随机本机端口、API/媒体均拦截，不构建 dist、不接触用户 data。
// 从仓库根目录执行：node apps/web/tests/player.browser.mjs
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { chromium, expect } from '@playwright/test'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const lyricFontCheck = process.argv.includes('--lyric-font')
const outputRoot = path.join(
  root,
  lyricFontCheck
    ? '.superpowers/tmp/v10-player/lyric-regression'
    : '.superpowers/tmp/v10-player/media-regression',
)
const output = path.join(outputRoot, `run-${new Date().toISOString().replaceAll(':', '-')}`)
await fs.mkdir(output, { recursive: true })
const startedAt = new Date().toISOString()
await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({ status: 'running', startedAt }))
const server = await createServer({
  configFile: false,
  root: path.join(root, 'apps/web'),
  plugins: [react()],
  cacheDir: path.join(output, 'vite-cache'),
  server: { host: '127.0.0.1', port: 0, strictPort: false, hmr: false },
  clearScreen: false,
  logLevel: 'warn',
})
await server.listen(0)
const address = server.httpServer.address()
if ([3780, 3781, 3782, 3783, 5174].includes(address.port)) {
  await server.close()
  throw new Error('独立验证拒绝占用主线端口')
}
const base = `http://127.0.0.1:${address.port}`
const browser = await chromium.launch({ headless: true }).catch(async (error) => {
  await server.close()
  throw error
})
const track = {
  id: 'demo:player-fixture',
  providerId: 'demo',
  title: '晚风经过窗台',
  artist: '播放器回归测试',
  album: '界面与状态夹具',
  duration: 90,
  coverUrl: '/covers/dusk.svg',
  qualities: ['standard', '128k', '320k', 'flac'],
  canDownload: true,
}
const settings = {
  defaultQuality: '128k',
  autoSwitchSource: true,
  downloadRoot: '',
  concurrency: 1,
  writeLyrics: false,
  writeCover: false,
  writeMetadata: false,
  showDirect: false,
}
const lines = [
  '晚风轻轻经过窗台',
  '街灯在远处亮起来',
  '把这一刻慢慢收好',
  '让声音停在你身旁',
  '云向安静的天空走去',
  '我们听见夜色的回响',
  '城市慢慢睡着了',
  '还有一首歌没有唱完',
  '晚风又轻轻经过窗台',
  '明天仍有新的光',
].map((text, i) => ({ time: i * 8, text }))
function wav() {
  const length = 8000 * 90 * 2
  const bytes = Buffer.alloc(44 + length)
  bytes.write('RIFF', 0)
  bytes.writeUInt32LE(36 + length, 4)
  bytes.write('WAVEfmt ', 8)
  bytes.writeUInt32LE(16, 16)
  bytes.writeUInt16LE(1, 20)
  bytes.writeUInt16LE(1, 22)
  bytes.writeUInt32LE(8000, 24)
  bytes.writeUInt32LE(16000, 28)
  bytes.writeUInt16LE(2, 32)
  bytes.writeUInt16LE(16, 34)
  bytes.write('data', 36)
  bytes.writeUInt32LE(length, 40)
  return bytes
}
// 只量测播放器，不将主线其它页面的布局变化算成 Player 的通过证据。
async function miniLayout(page) {
  return page.locator('.player-mini').evaluate((footer) => {
    const box = (node) => {
      const rect = node.getBoundingClientRect()
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height }
    }
    const query = (selector) => footer.querySelector(selector)
    const input = query('.player-progress-input')
    const time = query('.player-time')
    return {
      geometry: Object.fromEntries(
        [
          ['footer', footer],
          ['track', query('.player-mini-track')],
          ['play', query('.main-play')],
          ['next', query('.player-next')],
          ['queue', query('[aria-label="播放队列"]')],
          ['seek', input],
        ].map(([name, node]) => [name, box(node)]),
      ),
      css: {
        gridRows: getComputedStyle(footer).gridTemplateRows,
        paddingBottom: getComputedStyle(footer).paddingBottom,
        rangeHeight: getComputedStyle(input).height,
        timeFont: getComputedStyle(time).fontSize,
        timeGap: getComputedStyle(time).gap,
        nextDisplay: getComputedStyle(query('.player-next')).display,
        settingsDisplay: getComputedStyle(query('.player-mini-settings')).display,
      },
      visibleButtons: [...footer.querySelectorAll('button')]
        .filter((button) => button.getBoundingClientRect().width > 0)
        .map((button) => ({ label: button.getAttribute('aria-label'), ...box(button) })),
    }
  })
}
async function assertMini(page, width) {
  const mini = page.locator('.player-mini')
  await expect(mini).toBeVisible()
  await expect(mini.getByRole('button', { name: '下一首', exact: true })).toBeVisible()
  await expect(mini.locator('.mini-center, .mini-seek, .playback-controls')).toHaveCount(0)
  if (width <= 700) {
    await expect(mini.getByRole('button', { name: '上一首' })).toBeHidden()
    await expect(mini.getByRole('button', { name: '播放设置', exact: true })).toBeHidden()
    await expect(mini.getByRole('button', { name: '静音', exact: true })).toBeHidden()
  } else {
    await expect(mini.getByRole('button', { name: '上一首' })).toBeVisible()
    await expect(mini.getByRole('button', { name: '播放设置', exact: true })).toBeVisible()
  }
  const layout = await miniLayout(page)
  expect(layout.geometry.seek.height).toBeGreaterThanOrEqual(44)
  expect(Number.parseFloat(layout.css.timeFont)).toBeGreaterThanOrEqual(12)
  expect(Number.parseFloat(layout.css.timeGap)).toBeGreaterThanOrEqual(4)
  expect(layout.geometry.seek.y + layout.geometry.seek.height).toBeLessThanOrEqual(layout.geometry.play.y)
  for (const button of layout.visibleButtons) {
    expect(button.width, button.label).toBeGreaterThanOrEqual(44)
    expect(button.height, button.label).toBeGreaterThanOrEqual(44)
    expect(button.x + button.width, button.label).toBeLessThanOrEqual(width)
  }
  for (const [index, button] of layout.visibleButtons.entries()) {
    for (const other of layout.visibleButtons.slice(index + 1)) {
      const separated =
        button.x + button.width <= other.x ||
        other.x + other.width <= button.x ||
        button.y + button.height <= other.y ||
        other.y + other.height <= button.y
      expect(separated, `${button.label} 不得覆盖 ${other.label}`).toBe(true)
    }
  }
  if (width <= 700) expect(layout.visibleButtons).toHaveLength(4)
  expect(await mini.evaluate((node) => node.scrollWidth <= node.clientWidth)).toBe(true)
  return layout
}
const audioBody = wav()
const reports = []
try {
  for (const [width, height, secure] of lyricFontCheck
    ? [
        [320, 680, false],
        [390, 844, false],
        [1440, 940, false],
      ]
    : [
        [1440, 940, false],
        [1920, 1080, false],
        [412, 915, false],
        [390, 844, false],
        [320, 680, false],
        [390, 844, true],
      ]) {
    const context = await browser.newContext({
      viewport: { width, height },
      reducedMotion: 'reduce',
      hasTouch: width <= 700,
      isMobile: width <= 700,
      serviceWorkers: 'block',
    })
    const page = await context.newPage()
    const errors = []
    const resolvedQualities = []
    const playRequests = []
    const mediaRequests = []
    const sessionSettings = { ...settings }
    let responseDelay = 0
    let mediaDelay = 0
    const label = `${width}${secure ? '-https' : ''}`
    const pageOrigin = secure ? 'https://app.player.test' : base
    await page.route('**/*', async (route) => {
      const url = new URL(route.request().url())
      if (url.origin === base && !url.pathname.startsWith('/api/')) return route.continue()
      errors.push(`未 mock 的网络请求：${url.origin}${url.pathname}`)
      return route.abort()
    })
    // HTTPS 页面由同一个独立 Vite 提供代码；浏览器 origin 真实为 HTTPS，不伪改 window.location。
    if (secure)
      await page.route(`${pageOrigin}/**`, async (route) => {
        const url = new URL(route.request().url())
        const response = await context.request.get(`${base}${url.pathname}${url.search}`)
        await route.fulfill({ response })
      })
    page.on('pageerror', (error) => errors.push(error.message))
    await page.addInitScript(() => {
      window.__playerUnhandled = []
      window.addEventListener('unhandledrejection', (event) =>
        window.__playerUnhandled.push(String(event.reason)),
      )
      const Original = window.Audio
      window.__playerAudioCount = 0
      window.Audio = function (...args) {
        const audio = new Original(...args)
        window.__playerAudio = audio
        window.__playerAudioCount++
        return audio
      }
      window.Audio.prototype = Original.prototype
    })
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url())
      const endpoint = url.pathname.replace('/api/v1', '')
      let data = []
      if (endpoint === '/auth/session') data = { authenticated: true, required: false }
      else if (endpoint === '/settings') data = sessionSettings
      else if (endpoint.endsWith('/lyrics')) data = { lines, source: '播放器测试夹具' }
      else if (endpoint.endsWith('/play')) {
        errors.push('播放器错误地请求了 /play 别名')
        return route.fulfill({ status: 404, json: { error: { message: '只能使用 play-info' } } })
      } else if (endpoint.endsWith('/play-info')) {
        const quality = url.searchParams.get('quality') || sessionSettings.defaultQuality
        const excluded = (url.searchParams.get('excludeSources') || '').split(',').filter(Boolean)
        const sourceId = `player-fixture-source-${excluded.length}`
        playRequests.push({
          path: url.pathname,
          quality,
          excluded,
          origin: route.request().headers()['x-melora-origin'],
        })
        if (responseDelay) await new Promise((resolve) => setTimeout(resolve, responseDelay))
        resolvedQualities.push(quality)
        data = {
          trackId: decodeURIComponent(endpoint.split('/')[2]),
          direct: true,
          quality,
          sourceId,
          attemptedSources: [...excluded, sourceId],
          url: `${excluded.length ? 'https' : 'http'}://media.player.test/${sourceId}/${quality}/music.wav`,
          mimeType: 'audio/wav',
        }
      } else if (endpoint === '/providers')
        data = [
          {
            id: 'demo',
            name: '演示',
            enabled: true,
            isDemo: true,
            status: 'ok',
            capabilities: {
              play: true,
              search: true,
              charts: true,
              playlists: true,
              recommendations: true,
              download: true,
            },
          },
        ]
      else if (endpoint === '/sources') data = { items: [], activeSourceId: '', available: true }
      else if (endpoint === '/playlist-categories') data = { categories: [] }
      else if (endpoint === '/recommendations/daily')
        data = { tracks: [], playlists: [], personalized: false }
      else if (endpoint === '/library/history' && route.request().method() === 'POST') data = { ok: true }
      await route.fulfill({ json: data })
    })
    await page.route(/^https?:\/\/media\.player\.test\//, async (route) => {
      mediaRequests.push(route.request().url())
      if (mediaDelay) await new Promise((resolve) => setTimeout(resolve, mediaDelay))
      const range = /^bytes=(\d+)-(\d*)$/.exec(route.request().headers().range || '')
      const start = range ? Number(range[1]) : 0
      const end = range?.[2] ? Math.min(Number(range[2]), audioBody.length - 1) : audioBody.length - 1
      return route.fulfill({
        status: range ? 206 : 200,
        contentType: 'audio/wav',
        body: audioBody.subarray(start, end + 1),
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Accept-Ranges': 'bytes',
          ...(range ? { 'Content-Range': `bytes ${start}-${end}/${audioBody.length}` } : {}),
        },
      })
    })
    await page.goto(`${pageOrigin}/now-playing`)
    await expect(page.locator('.player-immersive')).toBeVisible()
    const emptyFullFooter = await page.locator('.player-immersive-footer').boundingBox()
    await page.screenshot({ path: path.join(output, `empty-full-${label}.png`) })
    await page.getByRole('button', { name: '收起播放器', exact: true }).click()
    const emptyMini = await assertMini(page, width)
    const miniCenter = await page.locator('.player-mini-placeholder').evaluate((node) => {
      const cover = node.getBoundingClientRect(),
        icon = node.querySelector('svg').getBoundingClientRect()
      return {
        display: getComputedStyle(node).display,
        dx: icon.x + icon.width / 2 - cover.x - cover.width / 2,
        dy: icon.y + icon.height / 2 - cover.y - cover.height / 2,
      }
    })
    if (lyricFontCheck) {
      expect(miniCenter.display).toBe('grid')
      expect(Math.abs(miniCenter.dx)).toBeLessThanOrEqual(0.5)
      expect(Math.abs(miniCenter.dy)).toBeLessThanOrEqual(0.5)
    }
    await expect(page.getByRole('slider', { name: '播放进度' })).toBeDisabled()
    await expect(page.getByRole('button', { name: '下一首', exact: true })).toBeDisabled()
    await page.screenshot({ path: path.join(output, `empty-mini-${label}.png`) })
    // 五次真刷新：每次重新载入 CSS 与空播放器，比较同一播放器结构，不伪报全站 CLS。
    for (let refresh = 0; refresh < (lyricFontCheck ? 1 : 5); refresh++) {
      await page.reload()
      const refreshed = await assertMini(page, width)
      expect(refreshed.geometry).toEqual(emptyMini.geometry)
    }
    await page.getByRole('button', { name: '打开全屏播放器' }).click()
    await page.evaluate(async (fixture) => {
      const store = await import('/src/stores/player.ts')
      window.__playerHarness = store
      const button = document.createElement('button')
      button.textContent = '载入播放器测试夹具'
      button.style.cssText =
        'position:fixed;top:0;left:0;z-index:9999;background:white;color:black;padding:12px'
      button.onclick = () => {
        void store.player.play(fixture, [fixture])
        button.remove()
      }
      document.body.append(button)
    }, track)
    await page.getByRole('button', { name: '载入播放器测试夹具' }).click()
    await expect(page.getByRole('heading', { name: track.title, exact: true })).toBeVisible()
    await expect(
      page.locator('.player-transport').getByRole('button', { name: '暂停', exact: true }),
    ).toBeVisible()
    await page.evaluate(() => window.__playerHarness.player.seek(18))
    await expect.poll(() => page.evaluate(() => window.__playerAudio.currentTime)).toBeGreaterThanOrEqual(17)
    expect(resolvedQualities[0]).toBe('128k')
    await expect(page.locator('.player-album-button')).toBeVisible()
    if (width < 700) {
      await expect(page.locator('.player-compact-lyrics')).toBeVisible()
      await expect(page.locator('.player-compact-lyrics > span')).toHaveCount(3)
      await expect(page.locator('.player-lyrics-panel')).toBeHidden()
    } else {
      await expect(page.locator('.player-lyrics-panel')).toBeVisible()
      const cover = await page.locator('.player-album-button').boundingBox()
      const lyrics = await page.locator('.player-lyrics-panel').boundingBox()
      expect(cover.x + cover.width).toBeLessThanOrEqual(lyrics.x)
    }
    const footerBox = await page.locator('.player-immersive-footer').boundingBox()
    expect(footerBox).toEqual(emptyFullFooter)
    await expect(page.getByRole('button', { name: '播放设置', exact: true })).toHaveCount(1)
    await expect(page.getByRole('button', { name: /更多播放选项|打开设置/ })).toHaveCount(0)
    for (const select of await page.locator('.player-tool-select select').all()) {
      const box = await select.boundingBox()
      expect(box.width).toBeGreaterThanOrEqual(44)
      expect(box.height).toBeGreaterThanOrEqual(44)
    }
    expect(footerBox.y + footerBox.height).toBeLessThanOrEqual(height)
    expect(await page.evaluate(() => window.__playerAudio.src.startsWith(location.protocol))).toBe(true)
    expect(playRequests).toHaveLength(secure ? 2 : 1)
    if (secure) expect(playRequests[1].excluded).toEqual(['player-fixture-source-0'])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    expect(await page.getByRole('button', { name: '下载当前歌曲' }).innerText()).toBe('')
    await page.screenshot({ path: path.join(output, `cover-${label}.png`) })
    await page.getByRole('button', { name: '点击封面显示完整歌词' }).focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('.player-immersive')).toHaveAttribute('data-view', 'lyrics')
    await expect(page.locator('.player-art-panel')).toBeHidden()
    await expect(page.locator('.player-lyrics-panel')).toBeVisible()
    await expect(page.getByRole('dialog', { name: '歌词', exact: true })).toHaveCount(0)
    await page.screenshot({ path: path.join(output, `lyrics-${label}.png`) })
    if (lyricFontCheck) {
      await page.locator('.player-transport').getByRole('button', { name: '暂停', exact: true }).click()
      await page.getByRole('combobox', { name: '播放速度', exact: true }).selectOption('1.5')
      await page.evaluate(() => window.__playerHarness.player.seek(32))
      const current = page.locator('.player-lyric-line.current')
      await expect(current).toHaveText(lines[4].text)
      const defaultFont = await current.evaluate((node) => parseFloat(getComputedStyle(node).fontSize))
      const footerBefore = await page.locator('.player-immersive-footer').boundingBox()
      await page.getByRole('button', { name: '播放设置', exact: true }).click()
      await page
        .getByRole('group', { name: '歌词字号', exact: true })
        .getByRole('button', { name: '特大', exact: true })
        .click()
      await expect(page.locator('.player-immersive')).toHaveAttribute('data-lyric-font', 'extra-large')
      const largeFont = await current.evaluate((node) => parseFloat(getComputedStyle(node).fontSize))
      expect(largeFont).toBeCloseTo(defaultFont * 1.4, 2)
      await page.screenshot({ path: path.join(output, `font-options-${label}.png`) })
      await page
        .getByRole('dialog', { name: '播放设置', exact: true })
        .getByRole('button', { name: '关闭', exact: true })
        .click()
      const centered = () =>
        current.evaluate((node) => {
          const line = node.getBoundingClientRect(),
            area = node.parentElement.getBoundingClientRect()
          return Math.abs(line.y + line.height / 2 - area.y - area.height / 2)
        })
      await expect.poll(centered).toBeLessThanOrEqual(2)
      expect(await page.locator('.player-immersive-footer').boundingBox()).toEqual(footerBefore)
      expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(true)
      expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.5)
      expect(await page.evaluate(() => window.__playerAudio.currentTime)).toBeCloseTo(32, 0)
      await page.locator('.player-lyric-line').nth(5).click()
      await expect.poll(() => page.evaluate(() => window.__playerAudio.currentTime)).toBeCloseTo(40, 0)
      await expect(current).toHaveText(lines[5].text)
      await expect.poll(centered).toBeLessThanOrEqual(2)
      await page.screenshot({ path: path.join(output, `font-large-${label}.png`) })
      await page.getByRole('button', { name: '播放设置', exact: true }).click()
      await expect(page.getByRole('button', { name: '特大', exact: true })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
      expect(await page.evaluate(() => window.__playerAudioCount)).toBe(1)
      expect(await page.evaluate(() => window.__playerUnhandled)).toEqual([])
      await page.reload()
      await expect(page.locator('.player-immersive')).toHaveAttribute('data-lyric-font', 'extra-large')
      await page.getByRole('button', { name: '播放设置', exact: true }).click()
      await expect(page.getByRole('button', { name: '特大', exact: true })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
      await page.screenshot({ path: path.join(output, `font-persisted-${label}.png`) })
      await page.getByRole('button', { name: '恢复默认歌词字号', exact: true }).click()
      await expect(page.locator('.player-immersive')).toHaveAttribute('data-lyric-font', 'standard')
      expect(await page.evaluate(() => localStorage.getItem('melora:lyric-font:v1'))).toBeNull()
      expect(await page.evaluate(() => window.__playerUnhandled)).toEqual([])
      expect(errors).toEqual([])
      reports.push({
        width,
        height,
        result: 'passed',
        miniCenter,
        defaultFont,
        largeFont,
        closeReopenAndReload: true,
        reset: true,
        realAudioSeek: 40,
        rate: 1.5,
        pausedPreserved: true,
        footerStable: true,
        pageErrors: errors,
        unhandledRejections: [],
      })
      await context.close()
      continue
    }
    await page.getByRole('button', { name: '播放设置' }).click()
    const options = page.getByRole('dialog', { name: '播放设置', exact: true })
    const closeSettings = await options.getByRole('button', { name: '关闭', exact: true }).boundingBox()
    expect(closeSettings.width).toBeGreaterThanOrEqual(44)
    expect(closeSettings.height).toBeGreaterThanOrEqual(44)
    await options.getByRole('button', { name: '1.5×', exact: true }).click()
    expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.5)
    responseDelay = 450
    const transport = page.locator('.player-transport .main-play')
    const transportBox = await transport.boundingBox()
    await options.getByRole('combobox', { name: '播放音质', exact: true }).selectOption('flac')
    await expect(options.getByRole('combobox', { name: '播放音质', exact: true })).toBeDisabled()
    expect(await transport.boundingBox()).toEqual(transportBox)
    await expect(options.getByRole('combobox', { name: '播放音质', exact: true })).toBeEnabled()
    responseDelay = 0
    expect(await transport.boundingBox()).toEqual(transportBox)
    expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(false)
    expect(resolvedQualities.at(-1)).toBe('flac')
    expect(await page.evaluate(() => window.__playerAudio.currentTime)).toBeGreaterThanOrEqual(17)
    expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.5)
    await options.getByRole('button', { name: '关闭', exact: true }).click()
    await page.locator('.player-transport').getByRole('button', { name: '暂停', exact: true }).click()
    const pausedAt = await page.evaluate(() => window.__playerAudio.currentTime)
    await page.getByRole('button', { name: '播放设置' }).click()
    mediaDelay = 350
    await options.getByRole('combobox', { name: '播放音质', exact: true }).selectOption('320k')
    await expect(options.getByRole('combobox', { name: '播放音质', exact: true })).toBeEnabled()
    expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(true)
    // 解析完成不等于 metadata 到达；人为延迟媒体，先验证UI进度，再验证真实Audio恢复。
    expect(await page.evaluate(() => window.__playerHarness.usePlayer.getState().position)).toBeCloseTo(
      pausedAt,
      0,
    )
    await expect.poll(() => page.evaluate(() => window.__playerAudio.readyState)).toBeGreaterThanOrEqual(1)
    await expect
      .poll(async () => Math.abs((await page.evaluate(() => window.__playerAudio.currentTime)) - pausedAt))
      .toBeLessThan(1)
    const qualityRestoredAt = await page.evaluate(() => window.__playerAudio.currentTime)
    mediaDelay = 0
    await options.getByRole('button', { name: '关闭', exact: true }).click()
    // 注入媒体错误，实际 Audio 重新载入 WAV，验证暂停/进度/倍速与排除源均保留。
    const recoveryStart = playRequests.length
    responseDelay = 450
    const footerBeforeRecovery = await page.locator('.player-immersive-footer').boundingBox()
    await page.evaluate(() => window.__playerAudio.dispatchEvent(new Event('error')))
    await expect(page.locator('.player-feedback')).toContainText('正在尝试其他可用音源')
    expect(await page.locator('.player-immersive-footer').boundingBox()).toEqual(footerBeforeRecovery)
    await page.screenshot({ path: path.join(output, `recovering-${label}.png`) })
    await expect
      .poll(() => page.evaluate(() => window.__playerHarness.usePlayer.getState().loading))
      .toBe(false)
    await expect.poll(() => page.evaluate(() => window.__playerAudio.readyState)).toBeGreaterThanOrEqual(1)
    await expect.poll(() => page.evaluate(() => window.__playerAudio.currentTime)).toBeCloseTo(pausedAt, 0)
    responseDelay = 0
    const recoveryRestoredAt = await page.evaluate(() => window.__playerAudio.currentTime)
    expect(playRequests).toHaveLength(recoveryStart + 1)
    expect(playRequests.at(-1).excluded).toContain(`player-fixture-source-${secure ? 1 : 0}`)
    expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(true)
    expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.5)
    expect(
      await page.evaluate(() => window.__playerHarness.usePlayer.getState().queue.map((item) => item.id)),
    ).toEqual([track.id])
    expect(await page.locator('.player-immersive-footer').boundingBox()).toEqual(footerBeforeRecovery)
    await page.getByRole('button', { name: '收起播放器', exact: true }).click()
    await expect(page.locator('.player-mini')).toBeVisible()
    await expect(page.locator('.query-pending')).toHaveCount(0)
    await expect(page.locator('.player-time')).toHaveText(/^\d{2}:\d{2}\s*\/\s*\d{2}:\d{2}$/)
    await expect(page.locator('.player-mini .mini-center, .player-mini .direct-status')).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const readyMini = await assertMini(page, width)
    expect(readyMini.geometry).toEqual(emptyMini.geometry)
    const miniSeek = page.getByRole('slider', { name: '播放进度' })
    const seekBox = await miniSeek.boundingBox()
    // 在轨道之外、44px 命中区之内点击；手机用真实触摸事件而不是 dispatchEvent。
    const hitX = seekBox.x + seekBox.width * 0.55
    const hitY = seekBox.y + seekBox.height - 4
    if (width <= 700) await page.touchscreen.tap(hitX, hitY)
    else await page.mouse.click(hitX, hitY)
    await expect.poll(() => page.evaluate(() => window.__playerAudio.currentTime)).toBeGreaterThan(40)
    const tappedAt = await page.evaluate(() => window.__playerAudio.currentTime)
    await miniSeek.focus()
    await miniSeek.press('ArrowRight')
    await expect.poll(() => page.evaluate(() => window.__playerAudio.currentTime)).toBeGreaterThan(tappedAt)
    expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(true)
    expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.5)
    // 拖动实际 range；保持暂停和倍速，不卸载节点。
    await page.mouse.move(seekBox.x + seekBox.width * 0.55, seekBox.y + 22)
    await page.mouse.down()
    await page.mouse.move(seekBox.x + seekBox.width * 0.3, seekBox.y + 22, { steps: 8 })
    await page.mouse.up()
    await expect.poll(() => page.evaluate(() => window.__playerAudio.currentTime)).toBeLessThan(35)
    expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(true)
    await page.getByRole('button', { name: '播放队列', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '播放队列 · 1' })).toBeVisible()
    await page.getByRole('dialog', { name: '播放队列 · 1' }).getByRole('button', { name: '关闭' }).click()
    expect((await miniLayout(page)).geometry).toEqual(readyMini.geometry)
    await page.screenshot({ path: path.join(output, `mini-${label}.png`) })
    await page.getByRole('button', { name: '打开全屏播放器' }).click()
    expect(await page.evaluate(() => window.__playerAudioCount)).toBe(1)
    expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(true)
    const coverBefore = await page.locator('.player-album-button').boundingBox()
    await page.evaluate(() => {
      window.__playerCoverNode = document.querySelector('.player-album-button img')
      window.__playerHarness.player.seek(28)
    })
    expect(
      await page.evaluate(
        () => window.__playerCoverNode === document.querySelector('.player-album-button img'),
      ),
    ).toBe(true)
    expect(await page.locator('.player-album-button').boundingBox()).toEqual(coverBefore)
    await expect(page.getByText(/直连播放|音频不经过飞牛/)).toHaveCount(0)
    // 连续不同源仍失败：包括前面的混合内容回退，整个本次解析最多恢复3次。
    for (let attempt = 0; attempt < 3; attempt++) {
      await page.evaluate(() => window.__playerAudio.dispatchEvent(new Event('error')))
      await expect
        .poll(() => page.evaluate(() => window.__playerHarness.usePlayer.getState().loading))
        .toBe(false)
      if (await page.evaluate(() => !!window.__playerHarness.usePlayer.getState().error)) break
    }
    await expect(page.locator('.player-feedback')).toContainText('仍无法播放')
    expect(playRequests.filter((request) => request.quality === '320k')).toHaveLength(4)
    expect(await page.evaluate(() => window.__playerHarness.usePlayer.getState().playing)).toBe(false)
    await page.screenshot({ path: path.join(output, `exhausted-${label}.png`) })
    // 显式重试可重新开始；HTTPS 页面关闭自动换源后不发起 HTTP 媒体请求。
    sessionSettings.autoSwitchSource = !secure
    const retryBox = await page
      .locator('.player-feedback')
      .getByRole('button', { name: '重试', exact: true })
      .boundingBox()
    const errorSeek = await page.getByRole('slider', { name: '播放进度', exact: true }).boundingBox()
    expect(retryBox.height).toBeGreaterThanOrEqual(44)
    expect(retryBox.y + retryBox.height).toBeLessThanOrEqual(errorSeek.y)
    const beforeRetry = playRequests.length
    await page.locator('.player-feedback').getByRole('button', { name: '重试', exact: true }).click()
    if (secure) {
      await expect(page.locator('.player-feedback')).toContainText('HTTPS')
      expect(playRequests).toHaveLength(beforeRetry + 1)
      expect(mediaRequests.every((url) => url.startsWith('https:'))).toBe(true)
      expect(await page.evaluate(() => window.__playerAudio.getAttribute('src'))).toBeNull()
      await page.screenshot({ path: path.join(output, `protocol-blocked-${label}.png`) })
    } else {
      await expect(
        page.locator('.player-transport').getByRole('button', { name: '暂停', exact: true }),
      ).toBeVisible()
      expect(mediaRequests.some((url) => url.startsWith('http:'))).toBe(true)
      await page.evaluate(() => window.__playerHarness.player.pause())
    }
    expect(
      playRequests.every((request) => request.path.endsWith('/play-info') && request.origin === pageOrigin),
    ).toBe(true)
    // 独立的底部倍速/音质控件不再打开 PlaybackOptions，仍调用真实播放器引擎。
    sessionSettings.autoSwitchSource = true
    await page.getByRole('combobox', { name: '播放速度', exact: true }).selectOption('1.25')
    expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.25)
    const directRate = page.getByRole('combobox', { name: '播放速度', exact: true })
    await directRate.focus()
    expect(await directRate.evaluate((select) => getComputedStyle(select.parentElement).outlineWidth)).toBe(
      '2px',
    )
    await directRate.press('ArrowDown')
    expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.5)
    await page.getByRole('combobox', { name: '播放音质', exact: true }).selectOption('flac')
    await expect(page.getByRole('combobox', { name: '播放音质', exact: true })).toBeEnabled()
    await expect
      .poll(() => page.evaluate(() => window.__playerHarness.usePlayer.getState().resolvedQuality))
      .toBe('flac')
    await expect(page.getByRole('dialog', { name: '播放设置', exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => window.__playerAudio.paused)).toBe(true)
    // 全屏轨道同样支持键盘命中，不受全局 range 高度规则覆盖。
    const fullSeek = page.getByRole('slider', { name: '播放进度' })
    expect((await fullSeek.boundingBox()).height).toBe(44)
    await fullSeek.focus()
    await fullSeek.press('Home')
    await expect.poll(() => page.evaluate(() => window.__playerAudio.currentTime)).toBeLessThan(1)
    await page.getByRole('button', { name: '收起播放器', exact: true }).click()
    // 等待上一段故障场景的全局 toast 自然消退，再拍独立加载/安全区证据。
    await expect(page.locator('.toast')).toHaveCount(0, { timeout: 7000 })
    const secondTrack = {
      ...track,
      id: 'demo:player-next-fixture',
      title: '下一首 · 很长的歌曲名称也不推移播放按钮或队列入口',
    }
    await page.evaluate((fixture) => {
      const state = window.__playerHarness.usePlayer.getState()
      window.__playerHarness.usePlayer.setState({ queue: [state.track, fixture] })
    }, secondTrack)
    responseDelay = 500
    await page.getByRole('button', { name: '下一首', exact: true }).click()
    await expect(page.getByRole('button', { name: '取消加载', exact: true })).toBeVisible()
    const loadingMini = await assertMini(page, width)
    expect(loadingMini.geometry).toEqual(emptyMini.geometry)
    await page.screenshot({ path: path.join(output, `loading-mini-${label}.png`) })
    await expect(page.getByRole('button', { name: '暂停', exact: true })).toBeVisible()
    responseDelay = 0
    await expect
      .poll(() => page.evaluate(() => window.__playerHarness.usePlayer.getState().track.id))
      .toBe(secondTrack.id)
    expect((await miniLayout(page)).geometry).toEqual(loadingMini.geometry)
    await page.getByRole('button', { name: '暂停', exact: true }).click()
    expect(await page.evaluate(() => window.__playerAudio.playbackRate)).toBe(1.5)
    if (width <= 700) await page.getByRole('button', { name: '打开全屏播放器' }).click()
    await page.getByRole('button', { name: '上一首', exact: true }).click()
    await expect
      .poll(() => page.evaluate(() => window.__playerHarness.usePlayer.getState().track.id))
      .toBe(track.id)
    await expect(
      page.locator('.player-transport').getByRole('button', { name: '暂停', exact: true }),
    ).toBeVisible()
    await page.locator('.player-transport').getByRole('button', { name: '暂停', exact: true }).click()
    expect(await page.evaluate(() => window.__playerAudioCount)).toBe(1)
    let safeArea = null
    if (width <= 700) {
      // Chromium 协议真实覆盖 CSS env()；不冒充 iOS/飞牛真机安全区验证。
      const cdp = await context.newCDPSession(page)
      await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: { top: 20, bottom: 34 } })
      await page.getByRole('button', { name: '收起播放器', exact: true }).click()
      const insetMini = await assertMini(page, width)
      expect(insetMini.css.paddingBottom).toBe('34px')
      expect(insetMini.geometry.play.y + insetMini.geometry.play.height).toBeLessThanOrEqual(height - 34)
      await page.screenshot({ path: path.join(output, `safe-area-mini-${label}.png`) })
      await page.getByRole('button', { name: '打开全屏播放器' }).click()
      const insetFull = await page.locator('.player-immersive').evaluate((node) => ({
        top: getComputedStyle(node.querySelector('.player-immersive-header')).paddingTop,
        bottom: getComputedStyle(node.querySelector('.player-immersive-footer')).paddingBottom,
      }))
      expect(insetFull).toEqual({ top: '20px', bottom: '34px' })
      const tools = await page.locator('.player-bottom-tools').boundingBox()
      expect(tools.y + tools.height).toBeLessThanOrEqual(height - 34)
      await page.screenshot({ path: path.join(output, `safe-area-full-${label}.png`) })
      safeArea = {
        emulation: 'Chromium CDP env override; not a real device',
        top: 20,
        bottom: 34,
        mini: insetMini,
        full: insetFull,
      }
      await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: {} })
      await cdp.detach()
    }
    expect(errors).toEqual([])
    reports.push({
      width,
      height,
      result: 'passed',
      pageProtocol: secure ? 'https:' : 'http:',
      playRequests,
      protocolPolicy: secure
        ? 'HTTP rejected; HTTPS source recovered; auto-switch off rejected without media request'
        : 'HTTP WAV fixture played without URL rewriting',
      recovery: {
        pausedAt,
        qualityRestoredAt,
        recoveryRestoredAt,
        restoresPausedPositionAndRate: true,
        playbackRate: 1.5,
        boundedRecoveries: 3,
      },
      layout: {
        noHorizontalOverflow: true,
        fixedTransportAndFooter: true,
        stableCoverNode: true,
        keyboardModeSwitch: true,
        emptyMini,
        readyMini,
        loadingMini,
        stableAcrossFiveReloads: true,
        miniSeekTouchOrClickKeyboardDrag: true,
        directRateAndQualitySelectors: true,
        realNextPreviousAndPause: true,
        safeArea,
      },
      screenshots: [
        'empty-full',
        'empty-mini',
        'cover',
        'lyrics',
        'mini',
        'loading-mini',
        'recovering',
        'exhausted',
        ...(width <= 700 ? ['safe-area-mini', 'safe-area-full'] : []),
        ...(secure ? ['protocol-blocked'] : []),
      ].map((view) => `${view}-${label}.png`),
      requestedQualities: resolvedQualities,
      audioInstances: 1,
      pageErrors: errors,
      media:
        'real HTMLAudioElement with synthetic WAV fixture; injected error events; no real source/network playback claim',
    })
    await context.close()
  }
  const result = { status: 'passed', startedAt, completedAt: new Date().toISOString(), base, reports }
  await fs.writeFile(path.join(outputRoot, 'latest-run.txt'), output)
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result, null, 2))
} catch (error) {
  await fs.writeFile(
    path.join(output, 'results.json'),
    JSON.stringify({ status: 'failed', startedAt, reports, error: String(error) }, null, 2),
  )
  throw error
} finally {
  await browser.close()
  await server.close()
}

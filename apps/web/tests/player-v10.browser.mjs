// 专用 v10 Player 验证：真实 App/CSS、独立 Vite 随机端口、API mock；不 build、不连接业务服务。
// node apps/web/tests/player-v10.browser.mjs [--baseline]
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { chromium, expect } from '@playwright/test'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const baseline = process.argv.includes('--baseline')
const outputRoot = path.join(root, '.superpowers/tmp/v10-player')
const output = path.join(
  outputRoot,
  `${baseline ? 'baseline' : 'geometry'}-${new Date().toISOString().replaceAll(':', '-')}`,
)
await fs.mkdir(output, { recursive: true })
const files = [
  'apps/web/src/components/Player.tsx',
  'apps/web/src/components/Player.css',
  'apps/web/src/components/Player.test.tsx',
  'apps/web/tests/player.browser.mjs',
  'apps/web/tests/player-v10.browser.mjs',
  'apps/web/src/styles.css',
  'apps/web/src/practical-ui.css',
  'apps/web/src/stores/player.ts',
]
async function hashes() {
  return Object.fromEntries(
    await Promise.all(
      files.map(async (file) => [
        file,
        createHash('sha256')
          .update(await fs.readFile(path.join(root, file)))
          .digest('hex'),
      ]),
    ),
  )
}
const initialHashes = await hashes()
const server = await createServer({
  configFile: false,
  root: path.join(root, 'apps/web'),
  plugins: [react()],
  cacheDir: path.join(output, 'vite-cache'),
  server: { host: '127.0.0.1', port: 0, hmr: false },
  logLevel: 'warn',
})
await server.listen(0)
const port = server.httpServer.address().port
if ([3780, 3781, 3782, 3783, 5174].includes(port)) {
  await server.close()
  throw new Error('拒绝占用主线服务端口')
}
const base = `http://127.0.0.1:${port}`
const browser = await chromium.launch({ headless: true }).catch(async (error) => {
  await server.close()
  throw error
})
const fixture = {
  id: 'demo:v10-player',
  providerId: 'demo',
  title: '晚风经过窗台 · 一首很长很长的测试歌曲',
  artist: 'Player 视口与歌词测试',
  album: '仅用于几何验证',
  duration: 240,
  coverUrl: '/covers/dusk.svg',
  qualities: ['128k', '320k', 'flac'],
  canDownload: true,
}
const lines = Array.from({ length: 24 }, (_, i) => ({
  time: i * 10,
  text:
    i === 5
      ? '这是一句需要换行的很长歌词，让每一个字都能看清，也能轻松选择播放位置 UnbrokenLongLyricWordWithoutSpaces1234567890'
      : `第${i + 1}行 · 晚风轻轻经过窗台`,
}))
const cases = [
  { width: 320, height: 480, dpr: 1.25 },
  { width: 320, height: 680, dpr: 2.625 },
  { width: 390, height: 560, dpr: 2.75 },
  { width: 390, height: 844, dpr: 3, motion: 'no-preference' },
  { width: 412, height: 480, dpr: 2.625 },
  { width: 412, height: 915, dpr: 1.5 },
  { width: 1440, height: 480, dpr: 1.25 },
  { width: 1440, height: 940, dpr: 1 },
  { width: 390, height: 720, dpr: 2.75, host: 'right-border' },
  { width: 412, height: 640, dpr: 2.625, host: 'bottom-border' },
  { width: 320, height: 640, dpr: 1.25, host: 'fractional' },
]
const reports = []
let activePage
let activeFrame
async function layout(frame, cascade = false) {
  return frame.evaluate((cascade) => {
    const q = (s) => document.querySelector(s)
    const box = (node) => {
      if (!node) return null
      const r = node.getBoundingClientRect()
      return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
    }
    const selectors = {
      root: '.player-immersive',
      header: '.player-immersive-header',
      heading: '.player-track-heading',
      stage: '.player-stage',
      art: '.player-album-button',
      panel: '.player-lyrics-panel',
      scroll: '.player-lyrics-scroll',
      current: '.player-lyric-line.current',
      footer: '.player-immersive-footer',
      play: '.player-transport.large .main-play',
      tools: '.player-bottom-tools',
    }
    const geometry = Object.fromEntries(Object.entries(selectors).map(([k, s]) => [k, box(q(s))]))
    const css = Object.fromEntries(
      Object.entries(selectors).map(([k, s]) => {
        const node = q(s)
        if (!node) return [k, null]
        const style = getComputedStyle(node)
        return [
          k,
          Object.fromEntries(
            [
              'position',
              'width',
              'height',
              'minHeight',
              'maxHeight',
              'paddingTop',
              'paddingBottom',
              'paddingLeft',
              'paddingRight',
              'margin',
              'borderWidth',
              'borderRadius',
              'textAlign',
              'overflowX',
              'overflowY',
              'fontSize',
            ].map((key) => [key, style[key]]),
          ),
        ]
      }),
    )
    const matchedRules = {}
    if (cascade)
      for (const [name, selector] of Object.entries(selectors)) {
        const node = q(selector)
        if (!node) continue
        const matches = []
        function walk(rules, sheet, conditions = []) {
          for (const rule of rules) {
            if (rule instanceof CSSMediaRule && !matchMedia(rule.conditionText).matches) continue
            if (rule instanceof CSSSupportsRule && !CSS.supports(rule.conditionText)) continue
            if (rule.selectorText && node.matches(rule.selectorText))
              matches.push({ sheet, selector: rule.selectorText, css: rule.style.cssText, conditions })
            if (rule.cssRules) walk(rule.cssRules, sheet, [...conditions, rule.conditionText || 'group'])
          }
        }
        for (const sheet of document.styleSheets) {
          try {
            walk(sheet.cssRules, sheet.href || sheet.ownerNode?.dataset?.viteDevId || 'inline')
          } catch {}
        }
        matchedRules[name] = matches
      }
    const scroller = q('.player-lyrics-scroll')
    const current = q('.player-lyric-line.current')
    let glyphs = null
    if (current) {
      const range = document.createRange()
      range.selectNodeContents(current)
      const r = range.getBoundingClientRect()
      glyphs = { x: r.x, y: r.y, width: r.width, height: r.height, centerX: r.x + r.width / 2 }
    }
    const controls = [
      ...q('.player-immersive').querySelectorAll('button:not(.player-lyric-line), select, input, a'),
    ]
      .filter((n) => n.getClientRects().length && getComputedStyle(n).visibility !== 'hidden')
      .map((n) => ({ label: n.getAttribute('aria-label') || n.textContent, tag: n.tagName, ...box(n) }))
    return {
      viewport: {
        width: innerWidth,
        height: innerHeight,
        clientWidth: document.documentElement.clientWidth,
        visualWidth: visualViewport?.width,
        visualHeight: visualViewport?.height,
        dpr: devicePixelRatio,
      },
      document: {
        width: document.documentElement.scrollWidth,
        height: document.documentElement.scrollHeight,
        scrollX,
        scrollY,
      },
      geometry,
      css,
      controls,
      matchedRules,
      glyphs,
      lyrics: scroller
        ? {
            width: scroller.clientWidth,
            scrollWidth: scroller.scrollWidth,
            height: scroller.clientHeight,
            scrollHeight: scroller.scrollHeight,
            scrollTop: scroller.scrollTop,
          }
        : null,
      center:
        geometry.current && geometry.scroll
          ? {
              x:
                geometry.current.x +
                geometry.current.width / 2 -
                geometry.scroll.x -
                geometry.scroll.width / 2,
              y:
                geometry.current.y +
                geometry.current.height / 2 -
                geometry.scroll.y -
                geometry.scroll.height / 2,
            }
          : null,
    }
  }, cascade)
}
async function state(frame, patch) {
  await frame.evaluate(async (patch) => {
    const { usePlayer } = await import('/src/stores/player.ts')
    usePlayer.setState(patch)
  }, patch)
}
async function seed(frame) {
  await expect(frame.locator('.player-immersive')).toBeVisible()
  await state(frame, {
    track: fixture,
    queue: [fixture],
    duration: 240,
    position: 80,
    playing: false,
    availableQualities: fixture.qualities,
  })
  await expect(frame.locator('.player-lyric-line')).toHaveCount(lines.length)
}
async function assertBounds(frame) {
  const data = await layout(frame)
  const { geometry: g, viewport: v } = data
  expect(g.root.x).toBe(0)
  expect(g.root.y).toBe(0)
  expect(Math.abs(g.root.width - v.visualWidth)).toBeLessThanOrEqual(0.51)
  expect(Math.abs(g.root.height - v.visualHeight)).toBeLessThanOrEqual(0.51)
  expect(data.document.width).toBeLessThanOrEqual(v.width)
  expect(data.document.height).toBeLessThanOrEqual(v.height)
  expect(data.css.root.borderWidth).toBe('0px')
  expect(data.css.root.borderRadius).toBe('0px')
  expect(g.header.y).toBe(0)
  expect(g.footer.bottom).toBeLessThanOrEqual(g.root.bottom + 0.01)
  for (const c of data.controls) {
    expect(c.width, c.label).toBeGreaterThanOrEqual(43.99)
    expect(c.height, c.label).toBeGreaterThanOrEqual(43.99)
    expect(c.x, c.label).toBeGreaterThanOrEqual(-0.01)
    expect(c.right, c.label).toBeLessThanOrEqual(g.root.right + 0.01)
    expect(c.y, c.label).toBeGreaterThanOrEqual(g.header.y)
    expect(c.bottom, c.label).toBeLessThanOrEqual(g.root.bottom + 0.01)
  }
  return data
}
async function centered(frame) {
  await expect(frame.locator('.player-immersive')).toHaveAttribute('data-view', 'lyrics')
  await expect.poll(async () => Math.abs((await layout(frame)).center?.y ?? Infinity)).toBeLessThanOrEqual(1)
  const data = await layout(frame)
  expect(Math.abs(data.center.x)).toBeLessThanOrEqual(0.5)
  expect(data.css.current.textAlign).toBe('center')
  expect(data.geometry.scroll).toEqual(data.geometry.panel)
  expect(data.lyrics.scrollWidth).toBe(data.lyrics.width)
  expect(
    Math.abs(data.glyphs.centerX - data.geometry.scroll.x - data.geometry.scroll.width / 2),
  ).toBeLessThanOrEqual(1)
  return data
}
try {
  for (const c of (baseline ? cases.filter((_, i) => [0, 3, 7, 8, 10].includes(i)) : cases).filter(
    (c) => !process.argv.includes('--first') || c === cases[0],
  )) {
    const label = `${c.width}x${c.height}-dpr${c.dpr}${c.host ? `-${c.host}` : ''}`
    const context = await browser.newContext({
      viewport: { width: c.width, height: c.height },
      deviceScaleFactor: c.dpr,
      isMobile: c.width <= 700,
      hasTouch: c.width <= 700,
      reducedMotion: c.motion || 'reduce',
      serviceWorkers: 'block',
    })
    const page = await context.newPage()
    activePage = page
    const errors = []
    let lyricDelay = 0
    let lyricRequests = 0
    page.on('pageerror', (error) => errors.push(error.message))
    await page.route('**/*', async (route) => {
      const url = new URL(route.request().url())
      if (url.origin !== base) {
        errors.push(`非本机网络请求 ${url.origin}`)
        return route.abort()
      }
      if (!url.pathname.startsWith('/api/')) return route.continue()
      let data = []
      if (url.pathname.endsWith('/auth/session')) data = { authenticated: true, required: false }
      else if (url.pathname.endsWith('/settings')) data = { defaultQuality: '128k', autoSwitchSource: true }
      else if (url.pathname.endsWith('/lyrics')) {
        lyricRequests++
        if (lyricDelay) await new Promise((r) => setTimeout(r, lyricDelay))
        data = { lines, source: 'v10 geometry fixture' }
      } else if (url.pathname.endsWith('/providers')) data = []
      else if (url.pathname.endsWith('/charts')) data = []
      else if (url.pathname.endsWith('/play-info')) data = { error: { message: '几何测试不播放音频' } }
      return route.fulfill({ json: data })
    })
    let frame = page
    if (c.host) {
      await page.route(`${base}/__v10_host`, (route) =>
        route.fulfill({
          contentType: 'text/html',
          body: `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>*{box-sizing:border-box}body{margin:0;background:white}header{height:94px;background:#f3f3f3;display:grid;place-items:center;font:18px sans-serif}iframe{display:block;border:0;width:${c.host === 'fractional' ? 'calc(100% - .5px)' : '100%'};height:calc(100dvh - ${c.host === 'fractional' ? '118.5px' : '118px'});${c.host === 'right-border' ? 'border-right:1px solid white' : c.host === 'bottom-border' ? 'border-bottom:1px solid white' : ''}}footer{height:24px;background:#f3f3f3;text-align:center}</style><header>模拟 fnOS 宿主顶栏（不可覆盖）</header><iframe title="Melora 子视口" src="${base}/now-playing"></iframe><footer>系统手势区模拟</footer>`,
        }),
      )
      await page.goto(`${base}/__v10_host`)
      const iframe = await page.locator('iframe').elementHandle()
      frame = await iframe.contentFrame()
    } else await page.goto(`${base}/now-playing`)
    activeFrame = frame
    await seed(frame)
    const cover = await layout(frame, true)
    await page.screenshot({ path: path.join(output, `${label}-cover.png`) })
    await frame.getByRole('button', { name: '点击封面显示完整歌词' }).click()
    await expect(frame.locator('.player-immersive')).toHaveAttribute('data-view', 'lyrics')
    await page.waitForTimeout(c.motion ? 250 : 60)
    const full = await layout(frame, true)
    await page.screenshot({ path: path.join(output, `${label}-lyrics.png`) })
    const report = { case: c, cover, full, fonts: [], reloads: [], toggles: [], host: null }
    reports.push(report)
    if (baseline) {
      await context.close()
      continue
    }
    await assertBounds(frame)
    await centered(frame)
    for (const [size, labelText] of [
      ['small', '小'],
      ['standard', '标准'],
      ['large', '大'],
      ['extra-large', '特大'],
    ]) {
      const before = (await layout(frame)).geometry
      await frame.getByRole('button', { name: '播放设置', exact: true }).click()
      await frame
        .getByRole('group', { name: '歌词字号' })
        .getByRole('button', { name: labelText, exact: true })
        .click()
      await expect(frame.locator('.player-immersive')).toHaveAttribute('data-lyric-font', size)
      const dialog = frame.getByRole('dialog', { name: '播放设置', exact: true })
      const options = await dialog.evaluate((node) => ({
        width: node.clientWidth,
        scrollWidth: node.scrollWidth,
        controls: [...node.querySelectorAll('button, select, input')].map((n) => ({
          label: n.getAttribute('aria-label') || n.textContent,
          width: n.getBoundingClientRect().width,
          height: n.getBoundingClientRect().height,
        })),
      }))
      expect(options.scrollWidth).toBeLessThanOrEqual(options.width)
      for (const control of options.controls) {
        expect(control.width, control.label).toBeGreaterThanOrEqual(44)
        expect(control.height, control.label).toBeGreaterThanOrEqual(44)
      }
      await frame.getByRole('button', { name: '关闭', exact: true }).click()
      await state(frame, { position: 50 })
      const long = await centered(frame)
      expect(long.geometry.footer).toEqual(before.footer)
      expect(long.geometry.header).toEqual(before.header)
      await assertBounds(frame)
      await page.screenshot({ path: path.join(output, `${label}-${size}-long.png`) })
      // 比窗口更高的长行不截字：手动滚动可把行首与行尾分别移到视觉中心。
      await frame.locator('.player-lyrics-scroll').dispatchEvent('touchmove')
      const longEnds = await frame.locator('.player-lyric-line.current').evaluate((node) => {
        const area = node.parentElement
        const range = document.createRange()
        const text = node.firstChild
        const align = (start, end) => {
          range.setStart(text, start)
          range.setEnd(text, end)
          let r = range.getBoundingClientRect()
          area.scrollTo({
            top: area.scrollTop + r.y + r.height / 2 - area.getBoundingClientRect().y - area.clientHeight / 2,
            behavior: 'instant',
          })
          r = range.getBoundingClientRect()
          return r.y + r.height / 2 - area.getBoundingClientRect().y - area.clientHeight / 2
        }
        return [align(0, 1), align(text.length - 1, text.length)]
      })
      expect(longEnds.every((offset) => Math.abs(offset) <= 1)).toBe(true)
      await frame.getByRole('button', { name: '回到当前歌词' }).click()
      const endpoints = []
      for (const position of [0, 230]) {
        await state(frame, { position })
        endpoints.push(await centered(frame))
      }
      await frame.locator('.player-lyrics-scroll').dispatchEvent('wheel', { deltaY: -160 })
      await expect(frame.getByRole('button', { name: '回到当前歌词' })).toBeVisible()
      await frame
        .locator('.player-lyrics-scroll')
        .evaluate((node) => node.scrollTo({ top: 120, behavior: 'instant' }))
      const manual = await frame.locator('.player-lyrics-scroll').evaluate((n) => n.scrollTop)
      await state(frame, { position: 80 })
      expect(await frame.locator('.player-lyrics-scroll').evaluate((n) => n.scrollTop)).toBe(manual)
      await frame.getByRole('button', { name: '回到当前歌词' }).click()
      await centered(frame)
      // 歌词点击必须真正走 seek，而非只改变高亮。
      await frame.locator('.player-lyric-line').nth(9).click()
      await expect
        .poll(() =>
          frame.evaluate(async () => (await import('/src/stores/player.ts')).usePlayer.getState().position),
        )
        .toBe(90)
      await centered(frame)
      report.fonts.push({ size, long, longEnds, options, endpoints, manualScrollHeld: true, lyricSeek: 90 })
    }
    // 后台歌词刷新保留节点与框架；不把整个 App 的 CLS 当作 Player 的证据。
    await frame.locator('.player-lyric-line.current').evaluate((n) => {
      window.__v10Current = n
    })
    const beforeRefetch = lyricRequests
    lyricDelay = 500
    await frame.evaluate(async () => {
      const { queryClient } = await import('/src/lib/api.ts')
      void queryClient.invalidateQueries({ queryKey: ['/tracks/demo%3Av10-player/lyrics'] })
    })
    await page.waitForTimeout(80)
    expect(await frame.locator('.player-lyric-line.current').evaluate((n) => window.__v10Current === n)).toBe(
      true,
    )
    await page.waitForTimeout(500)
    expect(await frame.locator('.player-lyric-line.current').evaluate((n) => window.__v10Current === n)).toBe(
      true,
    )
    expect(lyricRequests).toBeGreaterThan(beforeRefetch)
    report.backgroundRefetchPreservedNode = true
    lyricDelay = 0
    for (let i = 0; i < 3; i++) {
      const before = (await layout(frame)).geometry
      await frame.getByRole('button', { name: '显示封面', exact: true }).click()
      const next = await assertBounds(frame)
      expect(next.geometry.footer).toEqual(before.footer)
      expect(next.geometry.header).toEqual(before.header)
      await frame.getByRole('button', { name: '显示完整歌词', exact: true }).first().click()
      await centered(frame)
      report.toggles.push({ stableHeaderAndFooter: true })
    }
    for (let i = 0; i < 3; i++) {
      const before = (await layout(frame)).geometry
      await frame.getByRole('button', { name: '收起播放器', exact: true }).click()
      await expect(frame.locator('.player-mini')).toBeVisible()
      await frame.getByRole('button', { name: '打开全屏播放器' }).click()
      await expect(frame.locator('.player-immersive')).toHaveAttribute('data-view', 'cover')
      const reopened = await assertBounds(frame)
      expect(reopened.geometry.header).toEqual(before.header)
      expect(reopened.geometry.footer).toEqual(before.footer)
      await frame.getByRole('button', { name: '点击封面显示完整歌词' }).click()
      await centered(frame)
    }
    report.fullOpenCloseCycles = 3
    const stable = (await layout(frame)).geometry
    for (let i = 0; i < 5; i++) {
      await frame.evaluate(() => location.reload())
      await seed(frame)
      await expect(frame.locator('.player-immersive')).toHaveAttribute('data-view', 'lyrics')
      await expect(frame.locator('.player-immersive')).toHaveAttribute('data-lyric-font', 'extra-large')
      const next = await assertBounds(frame)
      await centered(frame)
      expect(next.geometry.footer).toEqual(stable.footer)
      expect(next.geometry.header).toEqual(stable.header)
      report.reloads.push({ header: next.geometry.header, footer: next.geometry.footer })
    }
    // loading/recovery/error 不改变底部框架，也不让 spinner 改变点击范围。
    const stableFooter = (await layout(frame)).geometry.footer
    for (const patch of [
      { loading: true },
      { loading: false, recovering: true },
      { recovering: false, error: '测试：暂时无法读取音频' },
      { error: null },
    ]) {
      await state(frame, patch)
      const next = await assertBounds(frame)
      expect(next.geometry.footer).toEqual(stableFooter)
    }
    report.stableFeedbackStates = true
    // Dirac store 合同：静音前音量由引擎持久化，设置组件卸载/刷新不能重置为 0.7。
    await frame.evaluate(async () => (await import('/src/stores/player.ts')).player.volume(0.38))
    await frame.getByRole('button', { name: '播放设置', exact: true }).click()
    await frame.getByRole('button', { name: '静音', exact: true }).click()
    await frame.getByRole('button', { name: '关闭', exact: true }).click()
    await frame.evaluate(() => location.reload())
    await seed(frame)
    await frame.getByRole('button', { name: '播放设置', exact: true }).click()
    await frame.getByRole('button', { name: '取消静音', exact: true }).click()
    const unmuted = await frame.evaluate(async () => {
      const { usePlayer } = await import('/src/stores/player.ts')
      const { volume, muted, volumeBeforeMute } = usePlayer.getState()
      return { volume, muted, volumeBeforeMute }
    })
    expect(unmuted).toEqual({ volume: 0.38, muted: false, volumeBeforeMute: 0.38 })
    report.muteReload = unmuted
    await frame.getByRole('button', { name: '关闭', exact: true }).click()
    await centered(frame)
    // 应用高度改变时跟随当前行；手动阅读时不抢滚动位置。
    await page.setViewportSize({ width: c.width, height: c.height + 73 })
    await centered(frame)
    await assertBounds(frame)
    await page.setViewportSize({ width: c.width, height: c.height })
    await centered(frame)
    await frame.locator('.player-lyrics-scroll').focus()
    await page.keyboard.press('PageUp')
    await expect(frame.getByRole('button', { name: '回到当前歌词' })).toBeVisible()
    await frame.getByRole('button', { name: '回到当前歌词' }).click()
    await centered(frame)
    if (c.host) {
      await expect(frame.locator('.player-immersive')).toHaveAttribute('data-embedded', 'true')
      const cdp = await context.newCDPSession(page)
      await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: { top: 20, bottom: 34 } })
      const embeddedSafe = await assertBounds(frame)
      expect(embeddedSafe.css.header.paddingTop).toBe('8px')
      expect(embeddedSafe.css.footer.paddingBottom).toBe(c.width < 360 ? '9px' : '14px')
      report.embeddedSafeArea = embeddedSafe
      await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: {} })
      await cdp.detach()
      report.host = await page.locator('iframe').evaluate((node) => {
        const r = node.getBoundingClientRect(),
          style = getComputedStyle(node)
        return {
          x: r.x,
          y: r.y,
          width: r.width,
          height: r.height,
          borderRight: style.borderRightWidth,
          borderBottom: style.borderBottomWidth,
          top: node.previousElementSibling.getBoundingClientRect().height,
          bottom: node.nextElementSibling.getBoundingClientRect().height,
        }
      })
      expect(report.host.top).toBe(94)
      expect(report.host.bottom).toBe(24)
      expect(report.host.borderRight).toBe(c.host === 'right-border' ? '1px' : '0px')
      expect(report.host.borderBottom).toBe(c.host === 'bottom-border' ? '1px' : '0px')
    } else if (c.width <= 700) {
      const cdp = await context.newCDPSession(page)
      await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: { top: 20, bottom: 34 } })
      const safe = await assertBounds(frame)
      expect(safe.css.header.paddingTop).toBe('20px')
      expect(safe.css.footer.paddingBottom).toBe('34px')
      expect(safe.geometry.tools.bottom).toBeLessThanOrEqual(c.height - 34)
      await centered(frame)
      report.safeArea = safe
      await page.screenshot({ path: path.join(output, `${label}-safe-area.png`) })
      await cdp.send('Emulation.setSafeAreaInsetsOverride', { insets: {} })
      await cdp.detach()
    }
    expect(errors).toEqual([])
    report.pageErrors = errors
    report.passed = true
    await context.close()
    console.log(`PASS ${label}`)
  }
  const result = {
    status: baseline ? 'baseline-recorded' : 'passed',
    base,
    initialHashes,
    finalHashes: await hashes(),
    reports,
    boundary:
      '真实 Chromium + 完整 App；iframe 为模拟，无法直接验证截图设备宿主；媒体恢复由既有 player.browser.mjs 单独验证。',
  }
  await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(result, null, 2))
  await fs.writeFile(path.join(outputRoot, baseline ? 'baseline-latest.txt' : 'geometry-latest.txt'), output)
  console.log(`${result.status}: ${output}`)
} catch (error) {
  console.error('Original regression failure:', error)
  if (activePage && !activePage.isClosed())
    await activePage.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {})
  await fs.writeFile(
    path.join(output, 'results.json'),
    JSON.stringify(
      {
        status: 'failed',
        failureLayout:
          activeFrame && !activePage.isClosed() ? await layout(activeFrame, true).catch(() => null) : null,
        error: String(error),
        stack: error.stack,
        initialHashes,
        finalHashes: await hashes(),
        reports,
      },
      null,
      2,
    ),
  )
  console.error(`Evidence: ${output}`)
  throw error
} finally {
  await browser.close()
  await server.close()
}

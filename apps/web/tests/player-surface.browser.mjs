// node apps/web/tests/player-surface.browser.mjs [--baseline] [--legacy-background]
// 真实App随机端口；API仅本地fixture。包含整条物理边缘与明确标注的画布暴露故障注入，不冒充原生设备复现。
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { chromium, expect } from '@playwright/test'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const baseline = process.argv.includes('--baseline')
const output = path.join(
  root,
  '.superpowers/tmp/edges-v11',
  `${baseline ? 'baseline' : 'browser'}-${Date.now()}`,
)
await fs.mkdir(output, { recursive: true })
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
  throw new Error('服务端口不在隔离范围')
}
const base = `http://127.0.0.1:${port}`
const browser = await chromium.launch()
const reports = []
async function surface(frame) {
  return frame.evaluate(() => ({
    root: getComputedStyle(document.documentElement).backgroundColor,
    body: getComputedStyle(document.body).backgroundColor,
    app: getComputedStyle(document.querySelector('#root')).backgroundColor,
    mode: document.documentElement.dataset.meloraSurface,
    theme: document.querySelector('meta[name="theme-color"]')?.content,
    width: innerWidth,
    height: innerHeight,
    scrollWidth: document.documentElement.scrollWidth,
    rect: document.querySelector('.player-immersive')?.getBoundingClientRect().toJSON(),
    playerScroll: (() => {
      const el = document.querySelector('.player-immersive')
      return el
        ? {
            width: el.clientWidth,
            height: el.clientHeight,
            scrollWidth: el.scrollWidth,
            scrollHeight: el.scrollHeight,
          }
        : null
    })(),
  }))
}
async function shot(page, name) {
  const image = await page.screenshot({ path: path.join(output, `${name}.png`) })
  return page.evaluate(async (base64) => {
    const image = new Image()
    const ready = new Promise((resolve, reject) => {
      image.onload = resolve
      image.onerror = reject
    })
    image.src = `data:image/png;base64,${base64}`
    await ready
    const canvas = document.createElement('canvas')
    canvas.width = image.naturalWidth
    canvas.height = image.naturalHeight
    const ctx = canvas.getContext('2d')
    ctx.drawImage(image, 0, 0)
    const w = canvas.width,
      h = canvas.height,
      d = ctx.getImageData(0, 0, w, h).data
    const stats = (points) => {
      let bright = 0,
        max = 0,
        min = 255
      for (const [x, y] of points) {
        const i = (y * w + x) * 4
        const v = Math.min(d[i], d[i + 1], d[i + 2])
        max = Math.max(max, v)
        min = Math.min(min, v)
        if (v >= 180) bright++
      }
      return { pixels: points.length, bright, min, max }
    }
    const luminance = (x, y) => {
      const i = (y * w + x) * 4
      return d[i] * 0.2126 + d[i + 1] * 0.7152 + d[i + 2] * 0.0722
    }
    const relative = (side) => {
      const deltas = []
      let longest = 0,
        run = 0
      for (let i = 0; i < (side === 'right' ? h : w); i++) {
        const reference = Array.from({ length: 5 }, (_, k) =>
          side === 'right' ? luminance(w - 5 - k, i) : luminance(i, h - 5 - k),
        ).sort((a, b) => a - b)[2]
        const delta = (side === 'right' ? luminance(w - 1, i) : luminance(i, h - 1)) - reference
        deltas.push(delta)
        run = delta >= 8 ? run + 1 : 0
        longest = Math.max(longest, run)
      }
      const sorted = [...deltas].sort((a, b) => a - b)
      return {
        medianDelta: sorted[Math.floor(sorted.length / 2)],
        maxAbsoluteDelta: Math.max(...deltas.map(Math.abs)),
        longestBrightRun: longest,
      }
    }
    return {
      width: w,
      height: h,
      relativeRight: relative('right'),
      relativeBottom: relative('bottom'),
      right: stats(Array.from({ length: h }, (_, y) => [w - 1, y])),
      bottom: stats(Array.from({ length: w }, (_, x) => [x, h - 1])),
    }
  }, image.toString('base64'))
}
try {
  for (const c of [
    { width: 390, height: 560, dpr: 2.75 },
    { width: 390, height: 561, dpr: 2.75 },
    { width: 412, height: 480, dpr: 2.625 },
    { width: 412, height: 915, dpr: 1.5 },
    { width: 393, height: 851, dpr: 2.75 },
    { width: 412, height: 915, dpr: 2.625 },
    { width: 390, height: 844, dpr: 3 },
    { width: 320, height: 480, dpr: 1.25 },
    { width: 1440, height: 940, dpr: 1 },
    { width: 390, height: 561, dpr: 2.75, frame: true },
    { width: 412, height: 915, dpr: 1.5, frame: true },
  ]) {
    const context = await browser.newContext({
      viewport: { width: c.width, height: c.height },
      deviceScaleFactor: c.dpr,
      isMobile: c.width < 700,
      hasTouch: c.width < 700,
      reducedMotion: 'reduce',
      serviceWorkers: 'block',
    })
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', (error) => errors.push(error.message))
    let denied = false
    await page.route('**/*', async (route) => {
      const url = new URL(route.request().url())
      if (url.origin !== base) return route.abort()
      if (url.pathname === '/__surface_host')
        return route.fulfill({
          contentType: 'text/html',
          body: `<meta name=viewport content="width=device-width,initial-scale=1"><style>html,body{margin:0;width:100%;height:100%;background:#bada55}iframe{display:block;border:0;width:100%;height:100%}</style><iframe src="${base}/now-playing"></iframe>`,
        })
      if (!url.pathname.startsWith('/api/')) return route.continue()
      let data = []
      if (url.pathname.endsWith('/auth/session'))
        data = denied
          ? { authenticated: false, required: true, authMode: 'fnos' }
          : { authenticated: true, required: false }
      else if (url.pathname.endsWith('/settings')) data = { defaultQuality: '128k', autoSwitchSource: true }
      else if (url.pathname.endsWith('/lyrics')) data = { lines: [{ time: 0, text: '画布与边缘测试' }] }
      else if (url.pathname.endsWith('/charts')) data = []
      await route.fulfill({ json: data })
    })
    await page.goto(`${base}/${c.frame ? '__surface_host' : 'now-playing'}`)
    const frame = c.frame ? page.frames().find((f) => f.parentFrame() === page.mainFrame()) : page
    await expect(frame.locator('.player-immersive')).toBeVisible()
    await frame.evaluate(async () => {
      const { usePlayer } = await import('/src/stores/player.ts')
      const track = {
        id: 'demo:canvas',
        providerId: 'demo',
        title: '边缘测试',
        artist: '合成样本',
        album: '',
        duration: 120,
        coverUrl: '/covers/dusk.svg',
        qualities: ['128k'],
        canDownload: false,
      }
      usePlayer.setState({ track, queue: [track], duration: 120, position: 20, playing: false })
    })
    const name = `${c.width}x${c.height}-dpr${c.dpr}${c.frame ? '-frame' : ''}`
    await frame.waitForFunction(() => [...document.images].every((img) => img.complete))
    // 可复现旧背景边界算法，仅用于负向回归，不能作为新实现通过的结果。
    if (process.argv.includes('--legacy-background'))
      await frame.addStyleTag({ content: '.player-atmosphere{position:absolute;inset:0}' })
    const current = await surface(frame),
      normal = await shot(page, `${name}-normal`)
    // 故障注入：只裁掉播放器最外1 CSS px以暴露下面的真实document；不改根画布/主题，不把它当原设备复现。
    await frame.locator('.player-immersive').evaluate((el) => (el.style.clipPath = 'inset(0 1px 1px 0)'))
    const exposed = await shot(page, `${name}-canvas-exposed`)
    await frame.locator('.player-immersive').evaluate((el) => (el.style.clipPath = ''))
    reports.push({ case: c, current, normal, exposed, errors })
    if (!baseline) {
      expect(current.root).toBe('rgb(23, 26, 30)')
      expect(current.body).toBe(current.root)
      expect(current.app).toBe(current.root)
      expect(current.theme).toBe('#171a1e')
      expect(current.playerScroll.scrollWidth).toBe(current.playerScroll.width)
      expect(current.playerScroll.scrollHeight).toBe(current.playerScroll.height)
      for (const band of [normal.relativeRight, normal.relativeBottom]) {
        expect(Math.abs(band.medianDelta)).toBeLessThanOrEqual(2)
        expect(band.maxAbsoluteDelta).toBeLessThanOrEqual(4)
        expect(band.longestBrightRun).toBe(0)
      }
      if (c.frame)
        expect(await page.locator('html').evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(
          'rgb(186, 218, 85)',
        )
      for (const image of [normal, exposed]) {
        expect(image.right.bright).toBe(0)
        expect(image.bottom.bright).toBe(0)
      }
      for (let n = 0; n < 3; n++) {
        await frame.getByRole('button', { name: '收起播放器' }).click()
        await expect.poll(async () => (await surface(frame)).root).toBe('rgb(255, 255, 255)')
        expect((await surface(frame)).theme).toBe('#ffffff')
        await frame.locator('.player-mini-track').click()
        await expect(frame.locator('.player-immersive')).toBeVisible()
        const returned = await surface(frame)
        if (returned.root !== current.root) {
          await page.waitForTimeout(150)
          await fs.writeFile(
            path.join(output, 'route-debug.json'),
            JSON.stringify(
              {
                url: page.url(),
                returned,
                later: await surface(frame),
                head: await page.locator('head').innerHTML(),
                errors,
              },
              null,
              2,
            ),
          )
        }
        expect(returned.root).toBe(current.root)
      }
      if (c.frame) await frame.goto(`${base}/now-playing`)
      else await page.reload()
      await expect(frame.locator('.player-immersive')).toBeVisible()
      expect((await surface(frame)).root).toBe(current.root)
      denied = true
      await frame.evaluate(async () => {
        const { queryClient } = await import('/src/lib/api.ts')
        await queryClient.invalidateQueries({ queryKey: ['/auth/session'] })
      })
      await expect(frame.getByText('需要飞牛管理员身份', { exact: true })).toBeVisible()
      expect((await surface(frame)).root).toBe('rgb(255, 255, 255)')
      expect((await surface(frame)).theme).toBe('#ffffff')
      expect(errors).toEqual([])
    }
    await context.close()
  }
  await fs.writeFile(
    path.join(output, 'report.json'),
    JSON.stringify({ baseline, status: baseline ? 'observed' : 'passed', reports }, null, 2),
  )
  await fs.writeFile(
    path.join(root, '.superpowers/tmp/edges-v11', baseline ? 'baseline-latest.txt' : 'browser-latest.txt'),
    output,
  )
  console.log(JSON.stringify({ output, scenarios: reports.length, status: baseline ? 'observed' : 'passed' }))
} catch (error) {
  await fs.writeFile(
    path.join(output, 'failure.json'),
    JSON.stringify({ message: String(error), reports }, null, 2),
  )
  throw error
} finally {
  await browser.close()
  await server.close()
}

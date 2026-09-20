// 独立真实App/同源iframe边界回归；只用私有Vite/API fixture，不访问NAS或媒体。
// --baseline 明确关闭frame桥接作像素对照，不冒充原设备/完整旧版；原始v13前红测另有快照。
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { chromium } from '@playwright/test'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const baseline = process.argv.includes('--baseline')
const out = path.join(
  root,
  '.superpowers/tmp/immersive-storage-v13/ui-edges',
  `${baseline ? 'baseline' : 'verify'}-${Date.now()}`,
)
await fs.mkdir(out, { recursive: true })
const server = await createServer({
  configFile: false,
  root: path.join(root, 'apps/web'),
  plugins: [
    {
      name: 'edge-control',
      enforce: 'pre',
      transform(code, id) {
        if (!baseline || !id.replaceAll('\\', '/').endsWith('/lib/document-surface.tsx')) return
        const line = 'const restoreFrame = immersive ? installImmersiveFrameSurface() : () => {}'
        if (!code.includes(line)) throw new Error('Cannot create explicit disabled-frame control')
        return code.replace(line, 'const restoreFrame = () => {}')
      },
    },
    react(),
  ],
  cacheDir: path.join(out, 'cache'),
  server: { host: '127.0.0.1', port: 0, hmr: false, cors: true },
  logLevel: 'warn',
})
await server.listen(0)
const port = server.httpServer.address().port
if ([3780, 3781, 3782, 3783, 5174].includes(port)) {
  await server.close()
  throw new Error('reserved port')
}
const base = `http://127.0.0.1:${port}`
const browser = await chromium.launch({ headless: true })
const rows = []
const failures = []
const dark = 'rgb(23, 26, 30)'
const track = {
  id: 'demo:edge-fixture',
  providerId: 'demo',
  title: '边界回归',
  artist: '离线测试',
  album: '',
  duration: 240,
  coverUrl: '/covers/dusk.svg',
  qualities: ['128k'],
  canDownload: true,
}
function check(ok, label) {
  if (!ok) failures.push(label)
}
const cases = [
  { name: 'border-exact-390', width: 390, height: 640, dpr: 3, border: 1, gap: 0 },
  { name: 'border-exact-412', width: 412, height: 720, dpr: 2, border: 1, gap: 0 },
  { name: 'border-390', width: 390.25, height: 640.5, dpr: 2.75, border: 1, gap: 0 },
  { name: 'gap-412', width: 412.375, height: 720.25, dpr: 2.625, border: 0, gap: 1 },
  { name: 'gap-border', width: 390.5, height: 640.5, dpr: 1.5, border: 1, gap: 0.5 },
  { name: 'zero-edge', width: 390, height: 640, dpr: 3, border: 0, gap: 0 },
  { name: 'other-content', width: 390, height: 640, dpr: 2.625, border: 1, gap: 1, sibling: true },
  { name: 'large-gap', width: 390, height: 640, dpr: 2.625, border: 0, gap: 12 },
  { name: 'sandbox', width: 390, height: 640, dpr: 2.625, border: 1, gap: 1, sandbox: true },
]
try {
  for (const tc of cases) {
    const context = await browser.newContext({
      viewport: { width: 450, height: 850 },
      deviceScaleFactor: tc.dpr,
      isMobile: true,
      reducedMotion: 'reduce',
    })
    try {
      const host = `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:#fff}#chrome{height:40px;background:#eee}#pane{position:relative;width:${tc.width}px;height:${tc.height}px;background:white;overflow:hidden}iframe{display:block;box-sizing:border-box;width:calc(100% - ${tc.gap}px);height:calc(100% - ${tc.gap}px);border:0;border-right:${tc.border}px solid white;border-bottom:${tc.border}px solid white}#other{position:absolute;left:0;top:0;width:3px;height:3px;background:lime}</style><div id="chrome">HOST SENTINEL</div><div id="pane"><iframe ${tc.sandbox ? 'sandbox="allow-scripts"' : ''} src="${base}/now-playing"></iframe>${tc.sibling ? '<div id="other"></div>' : ''}</div><script>window.initialEdge={frame:document.querySelector('iframe').getBoundingClientRect().toJSON(),pane:document.querySelector('#pane').getBoundingClientRect().toJSON(),chrome:getComputedStyle(document.querySelector('#chrome')).backgroundColor};</script>`
      await context.route('**/*', async (route) => {
        const u = new URL(route.request().url())
        if (u.pathname === '/__edge_host') return route.fulfill({ contentType: 'text/html', body: host })
        if (u.origin !== base) return route.abort()
        if (u.pathname.startsWith('/api/v1/')) {
          const p = u.pathname.slice('/api/v1'.length)
          let data =
            p === '/auth/session'
              ? { authenticated: true, required: false }
              : p === '/settings'
                ? { concurrency: 1, defaultQuality: '128k' }
                : p.endsWith('/lyrics')
                  ? {
                      lines: Array.from({ length: 20 }, (_, i) => ({
                        time: i * 10,
                        text: `第${i + 1}句边界歌词`,
                      })),
                    }
                  : []
          return route.fulfill({ headers: { 'access-control-allow-origin': '*' }, json: data })
        }
        return route.continue()
      })
      const page = await context.newPage()
      await page.goto(`${base}/__edge_host`)
      const frame = page.frames().find((f) => f !== page.mainFrame())
      await frame.waitForSelector('.player-immersive')
      await frame.evaluate(async (t) => {
        const { usePlayer } = await import('/src/stores/player.ts')
        usePlayer.setState({
          track: t,
          queue: [t],
          position: 20,
          duration: 240,
          playing: false,
          loading: false,
        })
      }, track)
      await frame.waitForSelector('.player-album-button')
      const measure = () =>
        page.evaluate(() => {
          const f = document.querySelector('iframe'),
            p = document.querySelector('#pane'),
            s = getComputedStyle(f)
          return {
            initial: window.initialEdge,
            frame: f.getBoundingClientRect().toJSON(),
            pane: p.getBoundingClientRect().toJSON(),
            frameBackground: s.backgroundColor,
            right: s.borderRightColor,
            bottom: s.borderBottomColor,
            paneBackground: getComputedStyle(p).backgroundColor,
            chrome: getComputedStyle(document.querySelector('#chrome')).backgroundColor,
            other: document.querySelector('#other')?.getAttribute('style') ?? null,
          }
        })
      let m = await measure()
      const editable = !tc.sandbox
      check(!editable || (m.right === dark && m.bottom === dark), `${tc.name}: own frame border paint`)
      // 不通过可见DOM启发式接管未知父容器（closed ShadowRoot可隐藏其它应用）。
      check(m.paneBackground === 'rgb(255, 255, 255)', `${tc.name}: parent scope`)
      check(
        JSON.stringify(m.frame) === JSON.stringify(m.initial.frame) &&
          JSON.stringify(m.pane) === JSON.stringify(m.initial.pane),
        `${tc.name}: geometry unchanged`,
      )
      check(m.chrome === m.initial.chrome, `${tc.name}: host chrome unchanged`)
      await page.screenshot({ path: path.join(out, `${tc.name}.png`) })
      rows.push({ case: tc, immersive: m })
      await frame.getByRole('button', { name: '收起播放器', exact: true }).click()
      await frame.waitForSelector('.player-mini')
      m = await measure()
      check(
        m.right === 'rgb(255, 255, 255)' &&
          m.bottom === 'rgb(255, 255, 255)' &&
          m.paneBackground === 'rgb(255, 255, 255)',
        `${tc.name}: route cleanup restores colors`,
      )
      rows.at(-1).restored = m
      if (tc.name === 'border-390') {
        await frame.goto(`${base}/now-playing`)
        await frame.waitForSelector('.player-immersive')
        await frame.goto('about:blank')
        const navigated = await measure()
        check(
          navigated.right === 'rgb(255, 255, 255)' && navigated.bottom === 'rgb(255, 255, 255)',
          'document navigation: restore without React cleanup',
        )
        rows.at(-1).navigated = navigated
      }
    } finally {
      await context.close()
    }
  }
  const context = await browser.newContext({
    viewport: { width: 319, height: 560 },
    deviceScaleFactor: 2.625,
    isMobile: true,
  })
  try {
    await context.route('**/api/v1/**', (r) =>
      r.fulfill({
        json: r.request().url().endsWith('/auth/session') ? { authenticated: true, required: false } : [],
      }),
    )
    const page = await context.newPage()
    await page.goto(`${base}/now-playing`)
    await page.waitForSelector('.player-immersive')
    const m = await page.evaluate(() => ({
      width: innerWidth,
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
      overflow: getComputedStyle(document.documentElement).overflow,
      bodyMin: getComputedStyle(document.body).minWidth,
      scale: visualViewport?.scale,
    }))
    check(
      m.scroll <= m.client && m.overflow === 'hidden' && m.bodyMin === '0px',
      'narrow: root cannot create secondary scrollbar',
    )
    rows.push({ case: 'narrow', measurement: m })
  } finally {
    await context.close()
  }
} finally {
  await browser.close()
  await server.close()
  await fs.writeFile(path.join(out, 'report.json'), JSON.stringify({ baseline, rows, failures }, null, 2))
  await fs.writeFile(
    path.join(root, '.superpowers/tmp/immersive-storage-v13/ui-edges/latest.txt'),
    out + '\n',
  )
}
console.log(JSON.stringify({ output: path.relative(root, out), cases: rows.length, failures }, null, 2))
if (failures.length && !baseline) process.exitCode = 1

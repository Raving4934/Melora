import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'

const indexHTML = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
const mainTSX = readFileSync(resolve(process.cwd(), 'src/main.tsx'), 'utf8')
const manifestPath = resolve(process.cwd(), 'public/manifest.webmanifest')
const serviceWorkerPath = resolve(process.cwd(), 'public/sw.js')
const packageJSON = JSON.parse(readFileSync(resolve(process.cwd(), '../../package.json'), 'utf8')) as {
  version: string
}

it('发布可安装应用和 iOS 独立模式所需的文档元数据', () => {
  expect(indexHTML).toContain('width=device-width, initial-scale=1.0, viewport-fit=cover')
  expect(indexHTML).toContain('<link rel="manifest" href="./manifest.webmanifest" />')
  expect(indexHTML).toContain('<meta name="apple-mobile-web-app-capable" content="yes" />')
  expect(indexHTML).toContain(
    '<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />',
  )
  expect(indexHTML).toContain('<meta name="apple-mobile-web-app-title" content="乐屿" />')
  expect(indexHTML).toContain('<link rel="apple-touch-icon" href="./icons/apple-touch-icon.png" />')
})

it('应用入口启用生产环境 PWA 注册', () => {
  expect(mainTSX).toContain("import { registerPWA } from './lib/pwa'")
  expect(mainTSX).toContain('void registerPWA()')
})

it('发布相对作用域的 standalone Manifest 和完整安装图标', () => {
  expect(existsSync(manifestPath)).toBe(true)
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as {
    name: string
    short_name: string
    id: string
    start_url: string
    scope: string
    display: string
    theme_color: string
    background_color: string
    icons: Array<{ src: string; sizes: string; type: string; purpose?: string }>
  }
  expect(manifest).toMatchObject({
    name: '乐屿 · Melora',
    short_name: '乐屿',
    id: './',
    start_url: './',
    scope: './',
    display: 'standalone',
    theme_color: '#ffffff',
    background_color: '#ffffff',
  })
  expect(manifest.icons).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ src: './icons/melora-192.png', sizes: '192x192', type: 'image/png' }),
      expect.objectContaining({ src: './icons/melora-512.png', sizes: '512x512', type: 'image/png' }),
      expect.objectContaining({
        src: './icons/melora-maskable-512.png',
        sizes: '512x512',
        type: 'image/png',
        purpose: 'maskable',
      }),
    ]),
  )
})

it('Service Worker 只缓存版本化应用壳并绕过 API 与媒体', () => {
  expect(existsSync(serviceWorkerPath)).toBe(true)
  const source = readFileSync(serviceWorkerPath, 'utf8')
  expect(source).toContain(`melora-shell-v${packageJSON.version}`)
  expect(source).toContain("self.addEventListener('install'")
  expect(source).toContain("self.addEventListener('activate'")
  expect(source).toContain("self.addEventListener('fetch'")
  expect(source).toContain('url.origin !== self.location.origin')
  expect(source).toContain("url.pathname.includes('/api/')")
  expect(source).toContain("request.destination === 'audio'")
  expect(source).toContain("request.mode === 'navigate'")
})

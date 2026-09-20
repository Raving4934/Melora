// 音源脚本 Node 冒烟：加载 shim + 预置脚本，验证 inited 声明与 musicUrl 解析。
// 用法：node lx-smoke.mjs <脚本文件> [source] [keyword]
import fs from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createNative } from './native-node.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const appAssets = path.resolve(here, '../../app/src/main/assets')

const scriptFile = process.argv[2]
const wantSource = process.argv[3]
const keyword = process.argv[4] || '晴天 周杰伦'
if (!scriptFile) {
  console.error('usage: node lx-smoke.mjs <script.js> [source] [keyword]')
  process.exit(1)
}

globalThis.__lxNative = createNative({ debugHttp: !!process.env.DEBUG_HTTP })
if (typeof globalThis.console === 'undefined') {
  globalThis.console = { log: () => {}, warn: () => {}, error: () => {}, info: () => {}, debug: () => {} }
}

const shim = fs.readFileSync(path.join(appAssets, 'lx-shim.js'), 'utf8')
;(0, eval)(shim)

const code = fs.readFileSync(scriptFile, 'utf8')
;(0, eval)(`(function(){ ${code} })()`)

const pump = async (takeExpr, timeoutMs = 30_000) => {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    globalThis.__lxTick && globalThis.__lxTick()
    const raw = (0, eval)(takeExpr)
    if (raw) return JSON.parse(raw)
    await sleep(20)
  }
  throw new Error('timeout')
}

const inited = globalThis.__lxInited
console.log('[inited]', JSON.stringify(inited).slice(0, 400))
if (!inited || !inited.sources) {
  console.error('脚本未成功初始化')
  process.exit(1)
}

// 用内置 SDK 的搜索结果构造真实 musicInfo（与 App 路径一致）
const sdkHarness = await import('./search-helper.mjs')
const song = await sdkHarness.searchSong(wantSource || Object.keys(inited.sources)[0], keyword)
console.log('[song]', song.name, '-', song.singer, '| qualitys:', Object.keys(song._types || {}).join(','))

const source = wantSource || Object.keys(inited.sources)[0]
const qualitys = inited.sources[source]?.qualitys || ['320k']
const quality = qualitys.find((q) => q === '320k') || qualitys[0]

globalThis.__lxInvoke(JSON.stringify({
  source,
  action: 'musicUrl',
  info: { type: quality, musicInfo: song },
}))
const result = await pump('globalThis.__lxTakeResult()')
console.log('[result]', JSON.stringify(result).slice(0, 400))
if (!result.ok) process.exit(1)

const url = typeof result.data === 'string' ? result.data : result.data?.url
if (!url || !/^https?:/.test(url)) {
  console.error('未返回有效 URL')
  process.exit(1)
}
console.log('[OK] resolved quality:', typeof result.data === 'object' ? result.data.type || quality : quality)

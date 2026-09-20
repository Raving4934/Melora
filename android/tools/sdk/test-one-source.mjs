// 单脚本 × 全平台解析测试：输出每平台 RESULT 行（PLAY/BADURL/FAIL/SKIP）。
import fs from 'node:fs'
import { execFileSync } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createNative } from './native-node.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const appAssets = path.resolve(here, '../../app/src/main/assets')
const scriptFile = process.argv[2]
const keyword = process.argv[3] || '晴天 周杰伦'

globalThis.__lxNative = createNative({ debugHttp: !!process.env.DEBUG_HTTP })
if (typeof globalThis.console === 'undefined') {
  globalThis.console = { log() {}, warn() {}, error() {}, info() {}, debug() {} }
}

const shim = fs.readFileSync(path.join(appAssets, 'lx-shim.js'), 'utf8')
;(0, eval)(shim)
const code = fs.readFileSync(scriptFile, 'utf8')
;(0, eval)(`(function(){ ${code} })()`)

const pump = async (takeExpr, timeoutMs) => {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    globalThis.__lxTick && globalThis.__lxTick()
    const raw = (0, eval)(takeExpr)
    if (raw) return JSON.parse(raw)
    await sleep(20)
  }
  return null
}

let inited = globalThis.__lxInited
const initDeadline = Date.now() + 10_000
while (!inited && Date.now() < initDeadline) {
  globalThis.__lxTick && globalThis.__lxTick()
  await sleep(50)
  inited = globalThis.__lxInited
}
if (!inited?.sources) {
  console.log('INIT_FAIL 脚本未完成初始化')
  process.exit(0)
}
console.log('SOURCES', Object.keys(inited.sources).join(','))

const sdk = await import('./search-helper.mjs')

for (const source of ['kw', 'kg', 'wy', 'tx', 'mg']) {
  const support = inited.sources[source]
  if (!support) {
    console.log(`RESULT ${source} SKIP 未声明`)
    continue
  }
  let song
  try {
    song = await sdk.searchSong(source, keyword)
  } catch (error) {
    console.log(`RESULT ${source} SEARCH_FAIL ${error.message}`)
    continue
  }
  const qualitys = support.qualitys || []
  const quality = qualitys.includes('320k') ? '320k' : qualitys.includes('128k') ? '128k' : qualitys[0] || '128k'
  const t0 = Date.now()
  globalThis.__lxInvoke(JSON.stringify({ source, action: 'musicUrl', info: { type: quality, musicInfo: song } }))
  const res = await pump('globalThis.__lxTakeResult()', 35_000)
  const ms = Date.now() - t0
  if (!res || !res.ok) {
    console.log(`RESULT ${source} FAIL ${ms}ms ${res?.error || 'timeout'}`)
    continue
  }
  const data = res.data
  const url = typeof data === 'string' ? data : data?.url
  if (!url || !/^https?:/.test(url)) {
    console.log(`RESULT ${source} NOURL ${ms}ms ${JSON.stringify(data).slice(0, 120)}`)
    continue
  }
  let playable = false
  let detail = ''
  try {
    const out = execFileSync(
      'curl',
      ['-sSL', '--max-time', '15', '-r', '0-65535', '-o', '/tmp/lx-probe.bin', '-w', '%{http_code}', url],
      { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 },
    )
    const size = fs.statSync('/tmp/lx-probe.bin').size
    playable = Number(out) >= 200 && Number(out) < 300 && size > 0
    detail = `HTTP ${out.trim()} ${size}B`
  } catch (error) {
    detail = `curl:${String(error.message).slice(0, 80)}`
  }
  console.log(`RESULT ${source} ${playable ? 'PLAY' : 'BADURL'} ${ms}ms q=${typeof data === 'object' ? data.type || quality : quality} ${detail}`)
  if (!playable) console.log(`   URL ${url}`)
}
process.exit(0)

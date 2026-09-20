// 屿溪 kw 排查 3：Proxy 记录脚本对响应的读取；可选把 201 重写为 200
import fs from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { createNative } from './native-node.mjs'

const FINAL_URL = process.env.FINAL_URL === '1'

globalThis.__lxNative = createNative({})
const shim = fs.readFileSync('../../app/src/main/assets/lx-shim.js', 'utf8')
;(0, eval)(shim)
globalThis.lx.currentScriptInfo = { name: '星海音乐源', description: '', version: '0.0.0', author: '屿溪', homepage: '' }

const origRequest = globalThis.lx.request
globalThis.lx.request = (url, options, callback) => {
  if (typeof callback === 'function') {
    const wrapped = (error, resp) => {
      if (error) {
        console.log('[CB-ERR]', String(error.message).slice(0, 140))
        callback(error)
        return
      }
      if (FINAL_URL && resp.statusCode === 201) resp.statusCode = 200
      if (process.env.FINAL_URL === '1' && resp.finalUrl === undefined) resp.finalUrl = String(url)
      const proxy = new Proxy(resp, {
        get(target, prop) {
          const value = target[prop]
          const brief = typeof value === 'object'
            ? `${value?.constructor?.name || 'obj'}(len=${value?.byteLength ?? value?.length ?? '?'})`
            : String(value).slice(0, 80)
          console.log('[ACC]', String(prop), '=', brief)
          return value
        },
      })
      callback(null, proxy)
    }
    return origRequest(url, options, wrapped)
  }
  return origRequest(url, options, callback)
}

const code = fs.readFileSync('/tmp/opencode/phone-sources/星海音乐源_v2.3.11.js', 'utf8')
;(0, eval)(`(function(){ ${code} })()`)

const pump = async (expr, timeoutMs) => {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    globalThis.__lxTick && globalThis.__lxTick()
    const raw = (0, eval)(expr)
    if (raw) return JSON.parse(raw)
    await sleep(20)
  }
  return null
}

const initDeadline = Date.now() + 12_000
while (!globalThis.__lxInited && Date.now() < initDeadline) {
  globalThis.__lxTick && globalThis.__lxTick()
  await sleep(30)
}

const sdk = await import('./search-helper.mjs')
const song = await sdk.searchSong('kw', '晴天 周杰伦')
console.log('--- resolve (FINAL_URL=' + FINAL_URL + ') ---')
globalThis.__lxInvoke(JSON.stringify({ source: 'kw', action: 'musicUrl', info: { type: '320k', musicInfo: song } }))
const res = await pump('globalThis.__lxTakeResult()', 20_000)
console.log('[result]', JSON.stringify(res).slice(0, 260))
process.exit(0)

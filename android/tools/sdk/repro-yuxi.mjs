// 屿溪 kw 排查 3：Proxy 记录脚本对响应的读取；可选把 201 重写为 200
import fs from 'node:fs'
import { createNative, invokeLx, waitForLx } from './native-node.mjs'

const REWRITE_STATUS = process.env.REWRITE_STATUS === '1'

globalThis.__lxNative = createNative({})
const sourcePath = process.argv[2]
if (!sourcePath) throw new Error('请通过命令行参数指定本地音源脚本路径')

const shim = fs.readFileSync(new URL('../../app/src/main/assets/lx-shim.js', import.meta.url), 'utf8')
;(0, eval)(shim)
globalThis.lx.currentScriptInfo = { name: '屿溪-终章', description: '', version: '0.0.0', author: '屿溪', homepage: '' }

const origRequest = globalThis.lx.request
globalThis.lx.request = (url, options, callback) => {
  if (typeof callback === 'function') {
    const wrapped = (error, resp) => {
      if (error) {
        console.log('[CB-ERR]', String(error.message).slice(0, 140))
        callback(error)
        return
      }
      if (REWRITE_STATUS && resp.statusCode === 201) resp.statusCode = 200
      if (process.env.FINAL_URL === '1' && resp.finalUrl === undefined) resp.finalUrl = String(url)
      if (process.env.PARSE_BODY === '1' && ArrayBuffer.isView(resp.body)) {
        const text = Buffer.from(resp.body.buffer, resp.body.byteOffset, resp.body.byteLength).toString('utf8')
        try {
          resp.body = JSON.parse(text)
        } catch (error) {
          resp.body = text
        }
      }
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

const code = fs.readFileSync(sourcePath, 'utf8')
;(0, eval)(`(function(){ ${code} })()`)

await waitForLx(() => globalThis.__lxInited, 12_000)

const sdk = await import('./search-helper.mjs')
const song = await sdk.searchSong('kw', '晴天 周杰伦')
console.log('--- resolve (REWRITE_STATUS=' + REWRITE_STATUS + ') ---')
const res = await invokeLx({ source: 'kw', action: 'musicUrl', info: { type: '320k', musicInfo: song } }, 20_000)
console.log('[result]', JSON.stringify(res).slice(0, 260))
process.exit(0)

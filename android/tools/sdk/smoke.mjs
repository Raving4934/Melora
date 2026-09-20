// Node 侧离线冒烟：用 curl 实现同步 __lxNative，直接驱动 music-sdk.js 验证目录链路。
// 用法：node smoke.mjs kw search 周杰伦
import { execFileSync } from 'node:child_process'
import { createHash, createCipheriv, createDecipheriv, publicEncrypt, constants, randomBytes } from 'node:crypto'
import { deflateRawSync, inflateSync, deflateSync } from 'node:zlib'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const bundlePath = path.resolve(here, '../../app/src/main/assets/sdk/music-sdk.js')

const normalizeAlgo = (algo) => ({ md5: 'md5', sha1: 'sha1', sha256: 'sha256' }[String(algo).toLowerCase()] ?? String(algo))

const native = {
  http(payloadJson) {
    const payload = JSON.parse(payloadJson)
    const args = ['-sS', '-X', (payload.method || 'GET').toUpperCase(), '--max-time', String(Math.ceil((payload.timeout || 15000) / 1000) + 5)]
    for (const [key, value] of Object.entries(payload.headers || {})) {
      args.push('-H', `${key}: ${value}`)
    }
    if (payload.body) {
      args.push('--data-binary', Buffer.from(payload.body, 'base64'))
    }
    args.push('-w', '\n__HTTP_STATUS__%{http_code}')
    args.push(payload.url)
    try {
      const output = execFileSync('curl', args, { maxBuffer: 128 * 1024 * 1024 })
      const marker = output.lastIndexOf('\n__HTTP_STATUS__')
      const body = output.subarray(0, marker)
      const status = Number(output.subarray(marker + 16).toString().trim())
      if (process.env.DEBUG_HTTP) {
        console.error(`[http] ${payload.method || 'GET'} ${payload.url} -> ${status} ${body.subarray(0, 160).toString()}`)
      }
      return JSON.stringify({ statusCode: status, statusMessage: '', headers: {}, raw: body.toString('base64') })
    } catch (error) {
      return JSON.stringify({ error: String(error.message || error) })
    }
  },
  hash(algo, dataBase64) {
    return createHash(normalizeAlgo(algo)).update(Buffer.from(dataBase64, 'base64')).digest('hex')
  },
  aes(action, mode, keyBase64, ivBase64, dataBase64) {
    const normalized = String(mode).toLowerCase()
    const blockMode = normalized.split('-').some((part) => part.includes('ecb')) ? 'ecb' : 'cbc'
    const noPadding = normalized.includes('nopadding')
    const key = Buffer.from(keyBase64, 'base64')
    const iv = Buffer.from(ivBase64, 'base64')
    const data = Buffer.from(dataBase64, 'base64')
    if (action === 'encrypt') {
      const cipher = createCipheriv(`aes-128-${blockMode}`, key, blockMode === 'cbc' ? iv : null)
      cipher.setAutoPadding(!noPadding)
      return Buffer.concat([cipher.update(data), cipher.final()]).toString('base64')
    }
    const decipher = createDecipheriv(`aes-128-${blockMode}`, key, blockMode === 'cbc' ? iv : null)
    decipher.setAutoPadding(!noPadding)
    return Buffer.concat([decipher.update(data), decipher.final()]).toString('base64')
  },
  rsaEncrypt(dataBase64, keyPem, padding) {
    const isOaep = String(padding || '').includes('OAEP')
    const encrypted = publicEncrypt(
      { key: keyPem, padding: isOaep ? constants.RSA_PKCS1_OAEP_PADDING : constants.RSA_NO_PADDING },
      Buffer.from(dataBase64, 'base64'),
    )
    return encrypted.toString('base64')
  },
  randomBase64(length) {
    return randomBytes(Math.max(1, Math.min(4096, Number(length) || 16))).toString('base64')
  },
  zlibInflate(dataBase64) {
    return inflateSync(Buffer.from(dataBase64, 'base64')).toString('base64')
  },
  zlibDeflate(dataBase64) {
    return deflateSync(Buffer.from(dataBase64, 'base64')).toString('base64')
  },
  log(...args) {
    if (process.env.SDK_VERBOSE) console.log('[js]', ...args)
  },
}

globalThis.__meloraDebug = !!process.env.SDK_DEBUG
globalThis.__lxNative = native
const bundle = fs.readFileSync(bundlePath, 'utf8')
// eslint-disable-next-line no-eval
;(0, eval)(bundle)

const take = async () => {
  for (let i = 0; i < 200; i++) {
    const raw = globalThis.__meloraTake()
    if (raw) return JSON.parse(raw)
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error('timeout waiting result')
}

const [source = 'kw', action = 'search', ...rest] = process.argv.slice(2)
const params = {}
if (action === 'search' || action === 'songlistSearch' || action === 'tipSearch') params.text = rest.join(' ') || '周杰伦'
if (action === 'playlists') { params.sortId = rest[0] || 'hot'; params.tagId = rest[1] || '' }
if (action === 'playlistSongs') params.id = rest[0]
if (action === 'boardSongs') params.bangId = rest[0]

const invoke = async (act, prm) => {
  globalThis.__meloraInvoke(JSON.stringify({ action: act, source, params: prm }))
  return take()
}

if (action === 'lyric' || action === 'pic') {
  const search = await invoke('search', { text: rest.join(' ') || '周杰伦', page: 1, limit: 3 })
  if (!search.ok || !search.data.list?.length) {
    console.error('search failed:', search.error ?? 'empty')
    process.exit(1)
  }
  const song = search.data.list[0]
  console.error(`# song: ${song.name} - ${song.singer} (${song.songmid})`)
  const result = await invoke(action, { song })
  if (!result.ok) {
    console.error('FAILED:', result.error)
    process.exit(1)
  }
  console.log(JSON.stringify(result.data).slice(0, 1200))
} else {
  const result = await invoke(action, params)
  if (!result.ok) {
    console.error('FAILED:', result.error)
    process.exit(1)
  }
  const data = result.data
  console.log(JSON.stringify(data, null, 1).slice(0, 4000))
}

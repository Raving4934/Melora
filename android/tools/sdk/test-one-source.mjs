// 单脚本逐平台测速，输出目录查询、脚本解析、媒体前缀读取和三步总耗时。
import fs from 'node:fs'
import { get as httpGet, setGlobalProxyFromEnv } from 'node:http'
import { get as httpsGet } from 'node:https'
import { createNative, invokeLx, waitForLx } from './native-node.mjs'
import { searchSong } from './search-helper.mjs'

const MEDIA_PREFIX_BYTES = 64 * 1024
const MEDIA_REDIRECTS = new Set([301, 302, 303, 307, 308])
const MAX_MEDIA_REDIRECTS = 10
const MPEG1_KBPS = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
const MPEG2_KBPS = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]

function mp3FrameSize(bytes, offset) {
  const [sync, flags, rates] = bytes.subarray(offset, offset + 3)
  if (rates === undefined || sync !== 0xff || (flags & 0xe6) !== 0xe2) return 0
  const version = (flags >> 3) & 3
  const bitrate = (version === 3 ? MPEG1_KBPS : MPEG2_KBPS)[rates >> 4]
  const sampleRate = [44100, 48000, 32000][(rates >> 2) & 3]
  if (version === 1 || !bitrate || !sampleRate) return 0
  const coefficient = version === 0 ? 288_000 : 144_000
  return Math.floor(coefficient * bitrate / sampleRate) + ((rates >> 1) & 1)
}

function aacFrameSize(bytes, offset) {
  const [sync, flags, rates, channel, length, fullness] = bytes.subarray(offset, offset + 6)
  if (fullness === undefined || sync !== 0xff || (flags & 0xf6) !== 0xf0 || ((rates >> 2) & 0xf) > 12) return 0
  const size = ((channel & 3) << 11) | (length << 3) | (fullness >> 5)
  return size > ((flags & 1) ? 7 : 9) ? size : 0
}

function audioPrefix(bytes) {
  const ascii = (offset, length) => bytes.toString('latin1', offset, offset + length)
  if (ascii(0, 3) === 'ID3') {
    const sizeBytes = bytes.subarray(6, 10)
    if (sizeBytes.length !== 4 || bytes[3] < 2 || bytes[3] > 4 || sizeBytes.some(byte => byte & 0x80)) return null
    const tagSize = sizeBytes.reduce((size, byte) => size * 128 + byte, 0)
    const footer = bytes[3] === 4 && (bytes[5] & 0x10) !== 0 ? 10 : 0
    bytes = bytes.subarray(10 + tagSize + footer)
  }

  if (bytes.length >= 42 && ascii(0, 4) === 'fLaC' && (bytes[4] & 0x7f) === 0 &&
    bytes.readUIntBE(5, 3) === 34 && (bytes.readUIntBE(18, 3) >> 4) > 0) return 'FLAC'
  if (bytes.length >= 12 && ascii(0, 4) === 'RIFF' && ascii(8, 4) === 'WAVE') return 'WAV'
  if (bytes.length >= 16 && ascii(4, 4) === 'ftyp' && ascii(8, 4) === 'M4A ') return 'M4A'
  if (bytes.length >= 27 && ascii(0, 4) === 'OggS' && bytes[4] === 0 && bytes[26] > 0) {
    const packet = 27 + bytes[26]
    if (ascii(packet, 8) === 'OpusHead') return 'Ogg Opus'
    if (ascii(packet, 7) === '\x01vorbis') return 'Ogg Vorbis'
  }
  const mp3 = mp3FrameSize(bytes, 0)
  if (mp3 > 0 && mp3FrameSize(bytes, mp3) > 0) return 'MP3'
  const aac = aacFrameSize(bytes, 0)
  if (aac > 0 && aacFrameSize(bytes, aac) > 0) return 'AAC ADTS'
  return null
}

async function checkMediaResponse(response) {
  const statusCode = response.statusCode
  if (statusCode < 200 || statusCode >= 300) {
    const status = [401, 403, 404, 410].includes(statusCode) ? 'BADURL' : 'UNKNOWN'
    return { status, detail: `HTTP ${statusCode}` }
  }
  const type = response.headers['content-type']?.split(';', 1)[0].trim().toLowerCase()
  if (type === 'text/html' || type === 'application/xhtml+xml') {
    return { status: 'BADURL', detail: `HTTP ${statusCode} HTML` }
  }
  if (type === 'application/json' || type === 'text/json' || type?.endsWith('+json')) {
    return { status: 'BADURL', detail: `HTTP ${statusCode} JSON` }
  }

  const prefix = Buffer.alloc(MEDIA_PREFIX_BYTES)
  let size = 0
  for await (const chunk of response) {
    size += chunk.copy(prefix, size, 0, Math.min(chunk.length, prefix.length - size))
    if (size === prefix.length) break
  }
  const text = prefix.toString('utf8', 0, Math.min(size, 512)).trimStart()
  const detail = `HTTP ${statusCode} ${size}B`
  if (size === 0) return { status: 'BADURL', detail: `${detail} 空响应` }
  if (/^(?:<!doctype\s+html\b|<(?:html|head|body)\b)/i.test(text)) {
    return { status: 'BADURL', detail: `${detail} HTML` }
  }
  if (text.startsWith('#EXTM3U')) {
    return { status: 'UNKNOWN', detail: `${detail} 播放清单` }
  }
  const format = audioPrefix(prefix.subarray(0, size))
  return format
    ? { status: 'AUDIO', detail: `${detail} ${format}` }
    : { status: 'UNKNOWN', detail: `${detail} 未识别音频前缀` }
}

export async function probeMedia(url, timeoutMs = 15_000) {
  const signal = AbortSignal.timeout(timeoutMs)
  try {
    let address = new URL(url)
    for (let redirects = 0; redirects <= MAX_MEDIA_REDIRECTS; redirects++) {
      if (address.protocol !== 'http:' && address.protocol !== 'https:') {
        return { status: 'BADURL', detail: '链接协议无效' }
      }
      const get = address.protocol === 'https:' ? httpsGet : httpGet
      const response = await new Promise((resolve, reject) => {
        get(address, {
          headers: {
            Range: `bytes=0-${MEDIA_PREFIX_BYTES - 1}`,
            'Accept-Encoding': 'identity',
            'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36',
          },
          signal,
        }, resolve).once('error', reject)
      })
      try {
        if (MEDIA_REDIRECTS.has(response.statusCode) && response.headers.location) {
          address = new URL(response.headers.location, address)
        } else {
          return await checkMediaResponse(response)
        }
      } finally {
        response.destroy()
      }
    }
    return { status: 'BADURL', detail: '链接重定向次数过多' }
  } catch (error) {
    return { status: 'UNKNOWN', detail: signal.aborted ? '媒体读取超时' : `媒体读取失败 ${error.code || error.name}` }
  }
}

const elapsed = (start) => `${Math.round(performance.now() - start)}ms`

async function main(scriptFile, keyword = '晴天 周杰伦') {
  if (!scriptFile) {
    console.error('usage: node test-one-source.mjs <script.js> [keyword]')
    process.exitCode = 1
    return
  }
  globalThis.__lxNative = createNative({ debugHttp: !!process.env.DEBUG_HTTP })

  const shim = fs.readFileSync(new URL('../../app/src/main/assets/lx-shim.js', import.meta.url), 'utf8')
  ;(0, eval)(shim)
  const code = fs.readFileSync(scriptFile, 'utf8')
  ;(0, eval)(`(function(){ ${code} })()`)

  const inited = await waitForLx(() => globalThis.__lxInited, 10_000)
  if (!inited?.sources) {
    console.log('INIT_FAIL 脚本未完成初始化')
    return
  }
  console.log('SOURCES', Object.keys(inited.sources).join(','))

  for (const source of ['kw', 'kg', 'wy', 'tx', 'mg']) {
    const support = inited.sources[source]
    if (!support) {
      console.log(`RESULT ${source} SKIP 未声明`)
      continue
    }
    const start = performance.now()
    let song
    try {
      song = await searchSong(source, keyword)
    } catch (error) {
      console.log(`RESULT ${source} SEARCH_FAIL search=${elapsed(start)} ${error.message}`)
      continue
    }
    const searchTime = elapsed(start)
    const qualities = support.qualitys || []
    const quality = ['320k', '128k'].find(value => qualities.includes(value)) || qualities[0] || '128k'
    const resolveStart = performance.now()
    const result = await invokeLx({ source, action: 'musicUrl', info: { type: quality, musicInfo: song } }, 35_000)
    const timings = `search=${searchTime} resolve=${elapsed(resolveStart)}`
    if (!result?.ok) {
      console.log(`RESULT ${source} FAIL ${timings} ${result?.error || 'timeout'}`)
      continue
    }
    const { data } = result
    const url = typeof data === 'string' ? data : data?.url
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) {
      console.log(`RESULT ${source} NOURL ${timings} 脚本未返回 HTTP(S) 链接`)
      continue
    }
    const mediaStart = performance.now()
    const { status, detail } = await probeMedia(url)
    console.log(`RESULT ${source} ${status} ${timings} media=${elapsed(mediaStart)} total=${elapsed(start)} q=${data?.type || quality} ${detail}`)
  }
}

if (import.meta.main) {
  setGlobalProxyFromEnv()
  await main(process.argv[2], process.argv[3])
}

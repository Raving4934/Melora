// 乐屿 SDK HTTP 适配层：桥接 __lxNative.http 同步宿主。
// 运行在 QuickJS 专用线程内，宿主 HTTP 为同步调用，Promise 仅作为编排语义使用。
import { Buffer } from 'buffer'

const native = globalThis.__lxNative

const DEFAULT_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36',
}

const toBase64 = (text) => Buffer.from(text, 'utf8').toString('base64')

const doRequest = (url, options = {}) => {
  const method = String(options.method || 'get').toUpperCase()
  const headers = Object.assign({}, DEFAULT_HEADERS, options.headers || {})
  let body = null

  if (options.form) {
    const parts = []
    for (const [key, value] of Object.entries(options.form)) {
      parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    }
    body = toBase64(parts.join('&'))
    if (!headers['Content-Type']) headers['Content-Type'] = 'application/x-www-form-urlencoded'
  } else if (options.body != null) {
    const text = typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
    if (!headers['Content-Type']) headers['Content-Type'] = 'application/json'
    body = toBase64(text)
  }

  const payload = {
    url: String(url),
    method,
    headers,
    timeout: Number(options.timeout) || 15000,
    responseType: 'text',
  }
  if (body != null) payload.body = body

  const raw = JSON.parse(native.http(JSON.stringify(payload)))
  if (raw.error) throw new Error(raw.error)
  const responseUrl = raw.finalUrl || String(url)

  if (options.binary) {
    return {
      statusCode: raw.statusCode,
      statusMessage: raw.statusMessage || '',
      headers: raw.headers || {},
      body: Buffer.from(raw.raw || '', 'base64'),
      url: responseUrl,
    }
  }

  const text = Buffer.from(raw.raw || '', 'base64').toString('utf8')
  let parsed = text
  try {
    parsed = JSON.parse(text)
  } catch (_) { /* 保留原始文本 */ }

  return {
    statusCode: raw.statusCode,
    statusMessage: raw.statusMessage || '',
    headers: raw.headers || {},
    body: parsed,
    url: responseUrl,
  }
}

export const httpFetch = (url, options = { method: 'get' }) => new Promise((resolve, reject) => {
  try {
    resolve(doRequest(url, options))
  } catch (error) {
    reject(error)
  }
})

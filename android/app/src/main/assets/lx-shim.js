// 乐屿 LX 脚本运行时 shim：实现 globalThis.lx 的 mobile 子集。
// 同步宿主函数由 __lxNative 提供（HTTP/哈希/压缩/AES/随机数），Promise 由 Kotlin 侧任务泵驱动。
(() => {
  'use strict'

  const native = globalThis.__lxNative
  const handlers = Object.create(null)
  let invokeResult = undefined
  let invokeGeneration = 0

  // 控制台与定时器：QuickJS 无宿主事件循环，定时器由 Kotlin 泵每轮调用 __lxTick 触发。
  if (typeof globalThis.console === 'undefined') {
    globalThis.console = {
      log: (...args) => native.log(args.map((item) => stringifyArg(item)).join(' ')),
      warn: (...args) => native.log('[warn] ' + args.map((item) => stringifyArg(item)).join(' ')),
      error: (...args) => native.log('[error] ' + args.map((item) => stringifyArg(item)).join(' ')),
      info: (...args) => native.log(args.map((item) => stringifyArg(item)).join(' ')),
      debug: () => {},
    }
  }

  function stringifyArg(value) {
    if (typeof value === 'string') return value
    try {
      return JSON.stringify(value)
    } catch (error) {
      return String(value)
    }
  }

  const timerList = []
  let timerSeq = 0
  globalThis.setTimeout = (handler, timeout, ...args) => {
    const id = ++timerSeq
    timerList.push({ id, at: Date.now() + (Number(timeout) || 0), handler, args, repeat: 0 })
    return id
  }
  globalThis.setInterval = (handler, timeout, ...args) => {
    const id = ++timerSeq
    timerList.push({ id, at: Date.now() + (Number(timeout) || 0), handler, args, repeat: Math.max(Number(timeout) || 0, 16) })
    return id
  }
  const clearTimer = (id) => {
    const index = timerList.findIndex((timer) => timer.id === id)
    if (index >= 0) timerList.splice(index, 1)
  }
  globalThis.clearTimeout = clearTimer
  globalThis.clearInterval = clearTimer

  globalThis.__lxTick = () => {
    const now = Date.now()
    for (let i = 0; i < timerList.length;) {
      const timer = timerList[i]
      if (timer.at > now) {
        i++
        continue
      }
      if (timer.repeat > 0) timer.at = now + timer.repeat
      else timerList.splice(i, 1)
      try {
        timer.handler(...timer.args)
      } catch (error) {
        globalThis.console.error('定时器回调异常: ' + String((error && error.message) || error))
      }
      if (timer.repeat === 0) continue
      i++
    }
  }

  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

  function bytesToBase64(bytes) {
    let out = ''
    for (let i = 0; i < bytes.length; i += 3) {
      const b0 = bytes[i]
      const b1 = i + 1 < bytes.length ? bytes[i + 1] : undefined
      const b2 = i + 2 < bytes.length ? bytes[i + 2] : undefined
      out += B64[b0 >> 2]
      out += B64[((b0 & 3) << 4) | ((b1 ?? 0) >> 4)]
      out += b1 === undefined ? '=' : B64[((b1 & 15) << 2) | ((b2 ?? 0) >> 6)]
      out += b2 === undefined ? '=' : B64[b2 & 63]
    }
    return out
  }

  function base64ToBytes(text) {
    const clean = String(text).replace(/[^A-Za-z0-9+/=]/g, '')
    const bytes = []
    for (let i = 0; i < clean.length; i += 4) {
      const c0 = B64.indexOf(clean[i])
      const c1 = B64.indexOf(clean[i + 1])
      const c2 = B64.indexOf(clean[i + 2])
      const c3 = B64.indexOf(clean[i + 3])
      const value = (c0 << 18) | (c1 << 12) | ((c2 < 0 ? 0 : c2) << 6) | (c3 < 0 ? 0 : c3)
      bytes.push((value >> 16) & 255)
      if (clean[i + 2] !== undefined && clean[i + 2] !== '=') bytes.push((value >> 8) & 255)
      if (clean[i + 3] !== undefined && clean[i + 3] !== '=') bytes.push(value & 255)
    }
    return Uint8Array.from(bytes)
  }

  function utf8Encode(text) {
    const bytes = []
    for (let i = 0; i < text.length; i++) {
      let code = text.charCodeAt(i)
      if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
        const next = text.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          code = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00)
          i++
        }
      }
      if (code < 0x80) bytes.push(code)
      else if (code < 0x800) bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f))
      else if (code < 0x10000) bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f))
      else bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f))
    }
    return Uint8Array.from(bytes)
  }

  function utf8Decode(bytes) {
    let out = ''
    for (let i = 0; i < bytes.length; ) {
      const b0 = bytes[i++]
      let code
      if (b0 < 0x80) code = b0
      else if ((b0 & 0xe0) === 0xc0) code = ((b0 & 0x1f) << 6) | (bytes[i++] & 0x3f)
      else if ((b0 & 0xf0) === 0xe0) code = ((b0 & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f)
      else code = ((b0 & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f)
      if (code > 0xffff) {
        code -= 0x10000
        out += String.fromCharCode(0xd800 + (code >> 10), 0xdc00 + (code & 0x3ff))
      } else out += String.fromCharCode(code)
    }
    return out
  }

  class LxBuffer extends Uint8Array {
    static from(value, encoding) {
      if (typeof value === 'string') {
        if (encoding === 'base64') return new LxBuffer(base64ToBytes(value))
        if (encoding === 'hex') {
          const clean = value.replace(/[^0-9a-f]/gi, '')
          const bytes = new Uint8Array(Math.floor(clean.length / 2))
          for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(clean.substr(i * 2, 2), 16)
          return new LxBuffer(bytes)
        }
        return new LxBuffer(utf8Encode(value))
      }
      if (value instanceof ArrayBuffer) return new LxBuffer(new Uint8Array(value))
      if (ArrayBuffer.isView(value)) {
        return new LxBuffer(new Uint8Array(value.buffer, value.byteOffset, value.byteLength))
      }
      if (Array.isArray(value)) return new LxBuffer(Uint8Array.from(value))
      return new LxBuffer(0)
    }

    static fromArrayBuffer(buffer) {
      return LxBuffer.from(buffer)
    }

    static toArrayBuffer(buffer) {
      const view = LxBuffer.from(buffer)
      return view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength)
    }

    static concat(items, totalLength) {
      const views = items.map((item) => LxBuffer.from(item))
      const size = totalLength ?? views.reduce((sum, view) => sum + view.length, 0)
      const out = new Uint8Array(size)
      let offset = 0
      for (const view of views) {
        out.set(view, offset)
        offset += view.length
      }
      return new LxBuffer(out)
    }

    toString(encoding = 'utf8') {
      if (encoding === 'base64') return bytesToBase64(this)
      if (encoding === 'hex') {
        return Array.from(this)
          .map((byte) => byte.toString(16).padStart(2, '0'))
          .join('')
      }
      return utf8Decode(this)
    }

    toArrayBuffer() {
      return this.buffer.slice(this.byteOffset, this.byteOffset + this.byteLength)
    }

    slice(start, end) {
      return new LxBuffer(Uint8Array.prototype.slice.call(this, start, end))
    }
  }

  const toBase64 = (value) => bytesToBase64(LxBuffer.from(value))

  // 与 LX 移动版一致的默认请求头（脚本可覆盖）
  const defaultRequestHeaders = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36',
    Accept: 'application/json',
  }

  function request(url, options, callback) {
    const run = () => {
      const opts = options || {}
      const payload = {
        url: String(url),
        method: (opts.method || 'get').toUpperCase(),
        headers: Object.assign({}, defaultRequestHeaders, opts.headers || {}),
        timeout: opts.timeout || 15000,
        responseType: opts.responseType || '',
        maxResponseBytes: opts.maxResponseBytes,
      }
      if (opts.body != null) {
        // 与 LX 宿主一致：对象/数组 body 自动 JSON 序列化后再发送
        let body = opts.body
        if (
          typeof body === 'object' &&
          !(body instanceof ArrayBuffer) &&
          !ArrayBuffer.isView(body)
        ) {
          body = JSON.stringify(body)
        }
        payload.body = toBase64(body)
      }
      if (opts.form) payload.form = opts.form
      const raw = JSON.parse(native.http(JSON.stringify(payload)))
      if (raw.error) throw new Error(raw.error)
      const rawBuffer = LxBuffer.from(base64ToBytes(raw.raw || ''))
      const response = {
        statusCode: raw.statusCode,
        statusMessage: raw.statusMessage || '',
        headers: raw.headers || {},
        url: raw.finalUrl || String(url),
        raw: rawBuffer,
      }
      // 与 LX 宿主一致：默认把文本响应尝试 JSON.parse 为对象；binary:true 才返回 Buffer
      const text = rawBuffer.toString()
      if (opts.binary === true) response.body = rawBuffer
      else if (payload.responseType === 'arraybuffer') response.body = rawBuffer.toArrayBuffer()
      else if (payload.responseType === 'text') response.body = text
      else {
        try {
          response.body = JSON.parse(text)
        } catch (error) {
          response.body = text
        }
      }
      return response
    }
    if (typeof callback === 'function') {
      try {
        const response = run()
        callback(null, response, response.body)
      } catch (error) {
        callback(error, null, null)
      }
      return
    }
    return new Promise((resolve, reject) => {
      try {
        resolve(run())
      } catch (error) {
        reject(error)
      }
    })
  }

  const utils = {
    buffer: {
      from: LxBuffer.from,
      fromArrayBuffer: LxBuffer.fromArrayBuffer,
      toArrayBuffer: LxBuffer.toArrayBuffer,
      concat: LxBuffer.concat,
      isBuffer: (value) => value instanceof LxBuffer || value instanceof Uint8Array,
    },
    str2b64: (text) => bytesToBase64(utf8Encode(String(text))),
    b642str: (text) => utf8Decode(base64ToBytes(text)),
    buf2b64: (value) => toBase64(value),
    b642buf: (text) => LxBuffer.from(base64ToBytes(text)),
    md5: (text) => native.hash('md5', bytesToBase64(utf8Encode(String(text)))),
    sha1: (text) => native.hash('sha1', bytesToBase64(utf8Encode(String(text)))),
    sha256: (text) => native.hash('sha256', bytesToBase64(utf8Encode(String(text)))),
    rand: (min, max, length, type = 'string') => {
      const size = Math.max(1, Math.min(4096, Number(length) || 16))
      if (type === 'hex') return LxBuffer.from(base64ToBytes(native.randomBase64(size))).toString('hex')
      if (type === 'base64') return native.randomBase64(size)
      const lower = Math.min(Number(min) || 0, Number(max) || 0)
      const upper = Math.max(Number(min) || 0, Number(max) || 0)
      const span = Math.max(1, upper - lower + 1)
      const bytes = base64ToBytes(native.randomBase64(size * 2))
      let out = ''
      for (let i = 0; i < size; i++) {
        out += String.fromCharCode(lower + ((bytes[i] * 256 + (bytes[i + size] || 0)) % span))
      }
      return out
    },
    zlib: {
      inflate: (value) => LxBuffer.from(base64ToBytes(native.zlibInflate(toBase64(value)))),
      deflate: (value) => LxBuffer.from(base64ToBytes(native.zlibDeflate(toBase64(value)))),
    },
    crypto: {
      randomBytes: (length) => LxBuffer.from(base64ToBytes(native.randomBase64(Math.max(1, Number(length) || 16)))),
      aesEncrypt: (value, mode, key, iv) =>
        LxBuffer.from(
          base64ToBytes(native.aes('encrypt', mode, toBase64(key), iv ? toBase64(iv) : '', toBase64(value))),
        ),
      aesDecrypt: (value, mode, key, iv) =>
        LxBuffer.from(
          base64ToBytes(native.aes('decrypt', mode, toBase64(key), iv ? toBase64(iv) : '', toBase64(value))),
        ),
      rsaEncrypt: (value, keyPem, padding) =>
        LxBuffer.from(
          base64ToBytes(native.rsaEncrypt(toBase64(value), String(keyPem), padding || 'RSA/ECB/NoPadding')),
        ),
    },
  }

  globalThis.lx = {
    version: '2.0.0',
    env: 'mobile',
    // 脚本元信息：引擎在加载每个脚本前会写入真实值（@name/@version 等）
    currentScriptInfo: { name: '', description: '', version: '', author: '', homepage: '' },
    EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
    on(eventName, handler) {
      handlers[eventName] = handler
    },
    send(eventName, data) {
      if (eventName === 'inited') globalThis.__lxInited = data
      if (eventName === 'updateAlert') console.log('[lx]', (data && data.message) || '')
    },
    request,
    utils,
  }

  globalThis.__lxInvoke = (payloadJson) => {
    // 同一引擎超时后会被下一档/下一首复用；旧Promise晚到不能冒充新请求的结果。
    const generation = ++invokeGeneration
    invokeResult = undefined
    try {
      const handler = handlers.request
      if (typeof handler !== 'function') {
        invokeResult = JSON.stringify({ ok: false, error: '脚本未注册 request 事件' })
        return
      }
      const payload = JSON.parse(payloadJson)
      Promise.resolve(handler(payload)).then(
        (data) => {
          if (generation !== invokeGeneration) return
          invokeResult = JSON.stringify({ ok: true, data: data === undefined ? null : data })
        },
        (error) => {
          if (generation !== invokeGeneration) return
          invokeResult = JSON.stringify({ ok: false, error: String((error && error.message) || error) })
        },
      )
    } catch (error) {
      invokeResult = JSON.stringify({ ok: false, error: String((error && error.message) || error) })
    }
  }

  globalThis.__lxTakeResult = () => {
    const value = invokeResult
    invokeResult = undefined
    return value === undefined ? '' : value
  }

  globalThis.__lxInited = null
})()

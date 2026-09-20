// 共享 Node 侧原生桥：curl 同步 HTTP + node:crypto，与 Android LxHost 行为对齐。
import { execFileSync } from 'node:child_process'
import { createHash, createCipheriv, createDecipheriv, publicEncrypt, constants, randomBytes } from 'node:crypto'
import { deflateRawSync, inflateSync, deflateSync } from 'node:zlib'

const normalizeAlgo = (algo) => ({ md5: 'md5', sha1: 'sha1', sha256: 'sha256' }[String(algo).toLowerCase()] ?? String(algo))

export function createNative(options = {}) {
  const debugHttp = options.debugHttp ?? false
  return {
    http(payloadJson) {
      const payload = JSON.parse(payloadJson)
      const args = ['-sS', '-X', (payload.method || 'GET').toUpperCase(), '--max-time', String(Math.ceil((payload.timeout || 15000) / 1000) + 5)]
      for (const [key, value] of Object.entries(payload.headers || {})) {
        args.push('-H', `${key}: ${value}`)
      }
      if (payload.body) {
        args.push('--data-binary', Buffer.from(payload.body, 'base64'))
      }
      args.push('-w', '\n__HTTP_STATUS__%{http_code}__URL__%{url_effective}')
      args.push(payload.url)
      try {
        const output = execFileSync('curl', args, { maxBuffer: 128 * 1024 * 1024 })
        const marker = output.lastIndexOf('\n__HTTP_STATUS__')
        const body = output.subarray(0, marker)
        const tail = output.subarray(marker + 16).toString().trim()
        const [statusPart, urlPart] = tail.split('__URL__')
        const status = Number(statusPart)
        if (debugHttp) {
          console.error(`[http] ${payload.method || 'GET'} ${payload.url} -> ${status} ${body.subarray(0, 140).toString()}`)
        }
        return JSON.stringify({
          statusCode: status,
          statusMessage: '',
          finalUrl: urlPart || payload.url,
          headers: {},
          raw: body.toString('base64'),
        })
      } catch (error) {
        if (debugHttp) console.error(`[http] ${payload.url} -> ERROR ${error.message}`)
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
}

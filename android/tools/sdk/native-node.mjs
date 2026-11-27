// 共享 Node 侧原生桥：目录 SDK 同步调用，LX 脚本通过任务泵领取网络结果。
import { execFile, execFileSync } from 'node:child_process'
import { createHash, createCipheriv, createDecipheriv, publicEncrypt, constants, randomBytes } from 'node:crypto'
import { setTimeout as sleep } from 'node:timers/promises'
import { inflateSync, deflateSync } from 'node:zlib'

const normalizeAlgo = (algo) => ({ md5: 'md5', sha1: 'sha1', sha256: 'sha256' }[String(algo).toLowerCase()] ?? String(algo))
let nextScope = 0

export async function waitForLx(readResult, timeoutMs) {
  const deadline = performance.now() + timeoutMs
  while (performance.now() < deadline) {
    globalThis.__lxTick()
    const result = readResult()
    if (result) return result
    await sleep(20)
  }
  return null
}

export async function invokeLx(payload, timeoutMs) {
  const id = String(++nextScope)
  globalThis.__lxBeginRequest(id)
  try {
    globalThis.__lxInvoke(JSON.stringify(payload))
    const result = await waitForLx(globalThis.__lxTakeResult, timeoutMs)
    return result ? JSON.parse(result) : null
  } finally {
    globalThis.__lxEndRequest(id)
    globalThis.__lxNative.scope.end('')
  }
}

export function createNative(options = {}) {
  const debugHttp = options.debugHttp ?? false
  const curlOptions = { maxBuffer: 128 * 1024 * 1024 }
  const requests = new Map()
  const responses = []
  let activeScope = ''
  let currentScope = ''

  function curlRequest(payload) {
    const args = ['-sS', '-X', (payload.method || 'GET').toUpperCase(), '--max-time', String(Math.ceil((payload.timeout || 15000) / 1000) + 5)]
    for (const [key, value] of Object.entries(payload.headers || {})) {
      args.push('-H', `${key}: ${value}`)
    }
    const input = payload.body ? Buffer.from(payload.body, 'base64') : undefined
    if (input) args.push('--data-binary', '@-')
    args.push('-w', '\n__HTTP_STATUS__%{http_code}__URL__%{url_effective}', payload.url)
    return { args, input }
  }

  function httpResponse(payload, output) {
    const marker = output.lastIndexOf('\n__HTTP_STATUS__')
    const body = output.subarray(0, marker)
    const tail = output.subarray(marker + 16).toString().trim()
    const [statusPart, urlPart] = tail.split('__URL__')
    const status = Number(statusPart)
    if (debugHttp) {
      console.error(`[http] ${payload.method || 'GET'} ${payload.url} -> ${status} ${body.subarray(0, 140).toString()}`)
    }
    const limit = payload.maxResponseBytes > 0 ? payload.maxResponseBytes : body.length
    return {
      statusCode: status,
      statusMessage: '',
      finalUrl: urlPart || payload.url,
      headers: {},
      raw: body.subarray(0, limit).toString('base64'),
    }
  }

  function httpError(error) {
    if (debugHttp) console.error('[http] request failed', error.message)
    return { error: String(error.message || error) }
  }

  return {
    scope: {
      begin(id) { activeScope = currentScope = id },
      end(id) {
        if (activeScope === id) activeScope = currentScope = ''
        for (const [requestId, request] of requests) {
          if (request.scope !== id) continue
          requests.delete(requestId)
          request.child.kill()
        }
        for (let i = responses.length - 1; i >= 0; --i) {
          if (responses[i].scope === id) responses.splice(i, 1)
        }
      },
      current() { return currentScope },
      enter(id) {
        const previous = currentScope
        currentScope = id
        return previous
      },
    },
    http(payloadJson) {
      try {
        const payload = JSON.parse(payloadJson)
        const { args, input } = curlRequest(payload)
        return JSON.stringify(httpResponse(payload, execFileSync('curl', args, { ...curlOptions, input })))
      } catch (error) {
        return JSON.stringify(httpError(error))
      }
    },
    startHttp(payloadJson, scope, requestId) {
      try {
        if (scope && scope !== activeScope) throw new Error('脚本请求已结束')
        const payload = JSON.parse(payloadJson)
        const { args, input } = curlRequest(payload)
        const owner = activeScope
        const child = execFile('curl', args, { ...curlOptions, encoding: 'buffer' }, (error, output) => {
          if (!requests.delete(requestId)) return
          const response = error ? httpError(error) : httpResponse(payload, output)
          responses.push({ scope: owner, json: JSON.stringify({ ...response, requestId }) })
        })
        requests.set(requestId, { scope: owner, child })
        child.stdin.on('error', () => {}) // execFile reports curl failures through its completion callback.
        child.stdin.end(input)
        return ''
      } catch (error) {
        return JSON.stringify(httpError(error))
      }
    },
    takeHttpResponse() { return responses.shift()?.json || '' },
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

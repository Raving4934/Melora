import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const loadKgLyric = async () => {
  const result = await build({
    entryPoints: [path.join(root, 'src/musicSdk/kg/lyric.js')],
    bundle: true,
    format: 'esm',
    platform: 'node',
    target: 'node20',
    alias: {
      'react-native-quick-md5': path.join(root, 'compat/quick-md5.js'),
      'react-native-quick-base64': path.join(root, 'compat/quick-base64.js'),
    },
    write: false,
    logLevel: 'silent',
  })
  const code = Buffer.from(result.outputFiles[0].text).toString('base64')
  return (await import(`data:text/javascript;base64,${code}`)).default
}

test('KG lyric download retries the same endpoint with the same parameters', async () => {
  const urls = []
  const lyricText = '[00:00.000]Melora'
  globalThis.__lxNative = {
    http(payloadJson) {
      const payload = JSON.parse(payloadJson)
      urls.push(payload.url)
      const succeeded = urls.length > 1
      const body = succeeded
        ? { fmt: 'lrc', content: Buffer.from(lyricText).toString('base64') }
        : {}
      return JSON.stringify({
        statusCode: succeeded ? 200 : 503,
        statusMessage: succeeded ? 'OK' : 'Unavailable',
        headers: { 'content-type': 'application/json' },
        finalUrl: payload.url,
        raw: Buffer.from(JSON.stringify(body)).toString('base64'),
      })
    },
  }

  const kgLyric = await loadKgLyric()
  const result = await kgLyric.getLyricDownload('lyric-id', 'access-key', 'lrc')

  assert.equal(result.lyric, lyricText)
  assert.equal(urls.length, 2)
  assert.equal(urls[0], urls[1])
  assert.match(urls[0], /id=lyric-id&accesskey=access-key&fmt=lrc/)
})

import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { setInterval, clearInterval } from 'node:timers'
import test from 'node:test'
import { probeMedia } from '../test-one-source.mjs'

const flac = Buffer.alloc(42)
flac.write('fLaC')
flac.writeUIntBE(34, 5, 3)
flac.writeUIntBE(44100 << 4, 18, 3)

async function withServer(handler, run) {
  const server = createServer(handler)
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    return await run(`http://127.0.0.1:${server.address().port}`)
  } finally {
    server.closeAllConnections()
    await new Promise(resolve => server.close(resolve))
  }
}

test('link probe rejects a successful page with a misleading audio MIME', async () => {
  const html = Buffer.from('\uFEFF \n<!DOCTYPE html><html>Unavailable</html>')
  await withServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'audio/mpeg' })
    response.end(html)
  }, async (url) => {
    assert.deepEqual(await probeMedia(url), { status: 'BADURL', detail: `HTTP 200 ${html.length}B HTML` })
  })
})

test('link probe confirms FLAC and MP3 and leaves metadata alone unconfirmed', async () => {
  const tag = Buffer.from('ID3\x04\0\0\0\0\0\0')
  const frame = Buffer.alloc(417)
  frame.set([0xff, 0xfb, 0x90, 0x64])
  const fixtures = {
    '/lossless': flac,
    '/mp3': Buffer.concat([tag, frame, frame]),
    '/playlist': '#EXTM3U\n#EXTINF:6,\ninput.aac',
    '/tag': tag,
    '/opaque': 'Response data',
  }
  await withServer((request, response) => {
    assert.equal(request.headers.range, 'bytes=0-65535')
    response.writeHead(206, { 'Content-Type': 'application/octet-stream' })
    response.end(fixtures[request.url])
  }, async (url) => {
    assert.deepEqual(await probeMedia(url + '/lossless'), { status: 'AUDIO', detail: 'HTTP 206 42B FLAC' })
    assert.deepEqual(await probeMedia(url + '/mp3'), { status: 'AUDIO', detail: 'HTTP 206 844B MP3' })
    assert.match((await probeMedia(url + '/playlist')).detail, /播放清单/)
    for (const name of ['playlist', 'tag', 'opaque']) {
      assert.equal((await probeMedia(url + '/' + name)).status, 'UNKNOWN')
    }
  })
})

test('link probe stops at the prefix limit when the server ignores Range', async () => {
  const chunk = Buffer.alloc(4096)
  flac.copy(chunk)
  await withServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'audio/flac' })
    const stream = setInterval(() => response.write(chunk), 1)
    response.once('close', () => clearInterval(stream))
  }, async (url) => {
    const result = await probeMedia(url, 1_000)
    assert.deepEqual(result, { status: 'AUDIO', detail: 'HTTP 200 65536B FLAC' })
  })
})

test('link probe follows relative redirects and reports a stalled response as unconfirmed', async () => {
  await withServer((request, response) => {
    if (request.url === '/redirect') {
      response.writeHead(302, { Location: '/blocked' })
      response.end()
    } else if (request.url === '/blocked') {
      response.writeHead(403)
      response.end()
    } else {
      response.writeHead(200)
      response.flushHeaders()
    }
  }, async (url) => {
    assert.deepEqual(await probeMedia(url + '/redirect'), { status: 'BADURL', detail: 'HTTP 403' })
    assert.deepEqual(await probeMedia(url + '/stall', 100), { status: 'UNKNOWN', detail: '媒体读取超时' })
  })
})

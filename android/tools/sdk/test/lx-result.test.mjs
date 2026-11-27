import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import { createNative } from '../native-node.mjs'

const shim = await readFile(new URL('../../../app/src/main/assets/lx-shim.js', import.meta.url), 'utf8')
const fixture = () => {
  const context = vm.createContext({ __lxNative: {}, console })
  vm.runInContext(shim, context)
  vm.runInContext(`
    const pending = {};
    lx.on('request', ({source}) => new Promise((resolve, reject) => { pending[source] = {resolve, reject}; }));
    globalThis.finish = (id, value, fail) => fail ? pending[id].reject(new Error(value)) : pending[id].resolve(value);
  `, context)
  return context
}
const tick = () => new Promise(resolve => setImmediate(resolve))

test('LX mobile exposes MD5 and buffer conversion through the standard utility paths', () => {
  const context = vm.createContext({ __lxNative: createNative(), console })
  vm.runInContext(shim, context)
  const result = vm.runInContext(`
    const { buffer, crypto, md5 } = lx.utils
    const input = '乐屿🎵'
    const bytes = buffer.from(input, 'utf8')
    JSON.stringify({
      md5: crypto.md5(input),
      sameMd5: crypto.md5 === md5,
      text: buffer.bufToString(bytes),
      base64: buffer.bufToString(bytes, 'base64'),
      hex: buffer.bufToString(new Uint8Array([0, 255, 42, 0]).subarray(1, 3), 'HEX'),
    })
  `, context)
  assert.deepEqual(JSON.parse(result), {
    md5: '9b1911b2b940e48b6129c6ff0d5a049c',
    sameMd5: true,
    text: '乐屿🎵',
    base64: '5LmQ5bG/8J+OtQ==',
    hex: 'ff2a',
  })
})

test('LX preserves optional business error codes for synchronous and asynchronous failures', async () => {
  for (const async of [false, true]) {
    for (const code of ['MUSIC_UNAVAILABLE', 'UNKNOWN', undefined, 42]) {
      const context = vm.createContext({ __lxNative: {}, console, fixtureCode: code, fixtureAsync: async })
      vm.runInContext(shim, context)
      vm.runInContext(`lx.on('request', () => {
        const error = new Error('not available');
        error.code = fixtureCode;
        if (fixtureAsync) return Promise.reject(error);
        throw error;
      });`, context)
      context.__lxInvoke('{}')
      await tick()
      assert.deepEqual(JSON.parse(context.__lxTakeResult()), {
        ok: false, error: 'not available', ...(typeof code === 'string' ? { errorCode: code } : {}),
      })
    }
  }
})

test('late success and failure from a retired LX invocation cannot overwrite the current result', async () => {
  for (const fail of [false, true]) {
    const context = fixture()
    context.__lxInvoke(JSON.stringify({ source: 'old' }))
    context.__lxInvoke(JSON.stringify({ source: 'new' }))
    context.finish('old', 'stale', fail)
    await tick()
    assert.equal(context.__lxTakeResult(), '')
    context.finish('new', 'current', false)
    await tick()
    assert.deepEqual(JSON.parse(context.__lxTakeResult()), { ok: true, data: 'current' })
  }
})

test('new invocation clears an unconsumed result left by an abandoned waiter', async () => {
  const context = fixture()
  context.__lxInvoke(JSON.stringify({ source: 'old' }))
  context.finish('old', 'stale', false)
  await tick()
  context.__lxInvoke(JSON.stringify({ source: 'new' }))
  assert.equal(context.__lxTakeResult(), '')
})


test('source inspection checks registration without invoking the audio request handler', () => {
  const context = vm.createContext({ __lxNative: {}, console })
  vm.runInContext(shim, context)
  assert.equal(context.__lxHasRequestHandler(), false)
  vm.runInContext("globalThis.calls = 0; lx.on('request', () => { calls++; return 'fixture'; });", context)
  assert.equal(context.__lxHasRequestHandler(), true)
  assert.equal(context.calls, 0)
  vm.runInContext("lx.on('request', null)", context)
  assert.equal(context.__lxHasRequestHandler(), false)
})

const entry = await readFile(new URL('../entry.js', import.meta.url), 'utf8')
const sdkProtocol = entry.slice(entry.indexOf('let pendingResult'))
assert.ok(sdkProtocol.startsWith('let pendingResult'), 'test must execute the production SDK result protocol')

test('retired SDK promises cannot overwrite the next invocation, including invalid input', async () => {
  for (const fail of [false, true]) {
    for (const invalid of [false, true]) {
      const pending = {}
      const context = vm.createContext({
        dispatch: (_action, source) => new Promise((resolve, reject) => { pending[source] = { resolve, reject } }),
      })
      vm.runInContext(sdkProtocol, context)
      context.__meloraInvoke(JSON.stringify({ source: 'old' }))
      await tick()
      context.__meloraInvoke(invalid ? '{' : JSON.stringify({ source: 'new' }))
      await tick()
      if (fail) pending.old.reject(new Error('stale'))
      else pending.old.resolve('stale')
      await tick()
      if (invalid) {
        assert.deepEqual(JSON.parse(context.__meloraTake()), { ok: false, error: '调用参数解析失败' })
      } else {
        assert.equal(context.__meloraTake(), '')
        pending.new.resolve('current')
        await tick()
        assert.deepEqual(JSON.parse(context.__meloraTake()), { ok: true, data: 'current' })
      }
      assert.equal(context.__meloraTake(), '')
    }
  }
})

const sdkBundle = await readFile(new URL('../../../app/src/main/assets/sdk/music-sdk.js', import.meta.url), 'utf8')

function catalogFixture(respond) {
  const context = vm.createContext({
    __lxNative: {
      http(payload) {
        const request = JSON.parse(payload)
        const body = respond(new URL(request.url))
        return JSON.stringify({ statusCode: 200, raw: Buffer.from(JSON.stringify(body)).toString('base64') })
      },
    },
  })
  vm.runInContext(sdkBundle, context, { filename: 'music-sdk.js' })
  return async (source, params) => {
    context.__meloraInvoke(JSON.stringify({ action: 'playlists', source, params }))
    await tick()
    const response = JSON.parse(context.__meloraTake())
    assert.equal(response.ok, true, response.error)
    return response.data
  }
}

test('shipped SDK reports Kuwo digest-43 categories as a fixed catalog even with a full grid', async () => {
  const list = Array.from({ length: 36 }, (_, index) => ({
    type: 'songlist', digest: 8, id: index + 1, name: `歌单 ${index + 1}`,
  }))
  const catalog = catalogFixture(url => {
    assert.equal(url.hostname, 'mobileinterfaces.kuwo.cn')
    assert.equal(url.searchParams.get('id'), '140')
    assert.equal(url.searchParams.has('pn'), false)
    return [{ list }]
  })
  const { list: cards, ...page } = await catalog('kw', { tagId: '140-43', page: 1 })

  assert.deepEqual(page, {
    kind: 'playlists', source: 'kw', total: 1000, page: 1, limit: 1000, hasMore: false,
  })
  assert.equal(cards.length, 36)
  assert.deepEqual(cards.map(card => [card.source, card.id]), list.map(card => ['kw', `digest-8__${card.id}`]))
})

test('shipped SDK reports Migu full and short catalogs with the same limit and actual requested page', async () => {
  const requests = []
  const catalog = catalogFixture(url => {
    assert.equal(url.hostname, 'app.c.nf.migu.cn')
    const page = Number(url.searchParams.get('pageNo'))
    requests.push(page)
    const count = page === 1 ? 30 : 29
    const items = Array.from({ length: count }, (_, index) => ({
      resType: '2021', resId: (page - 1) * 30 + index + 1, txt: `歌单 ${index + 1}`,
    }))
    return {
      code: '000000',
      // 额外条目不属于歌单，即使原始响应看似满页，过滤后的短页仍是末页。
      data: { contents: [{ contents: [...items, { resType: '9999', resId: 'other' }] }] },
    }
  })
  for (const [page, count, hasMore] of [[1, 30, true], [2, 29, false]]) {
    const { list: cards, ...result } = await catalog('mg', { page })
    assert.deepEqual(result, { kind: 'playlists', source: 'mg', total: 99999, page, limit: 30, hasMore })
    assert.equal(cards.length, count)
    assert.equal(cards[0].id, String((page - 1) * 30 + 1))
  }
  assert.deepEqual(requests, [1, 2])
})

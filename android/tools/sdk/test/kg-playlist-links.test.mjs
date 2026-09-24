import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import { createNative } from '../native-node.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
let moduleId = 0
async function apiWithPage(respond = () => { throw new Error('Unexpected page request') }) {
  const requests = []
  globalThis.__lxNative = { ...createNative(), http: raw => {
    const request = JSON.parse(raw); requests.push(request.url)
    const response = respond(request.url)
    return JSON.stringify({ statusCode: response.status ?? 200, finalUrl: response.url ?? request.url,
      headers: {}, raw: Buffer.from(typeof response.body === 'string' ? response.body : JSON.stringify(response.body)).toString('base64') })
  } }
  const built = await build({ entryPoints: [path.join(root, 'src/musicSdk/kg/songList.js')], bundle: true,
    format: 'esm', platform: 'node', write: false, logLevel: 'silent', alias: {
      'react-native-quick-md5': path.join(root, 'compat/quick-md5.js'),
      'react-native-quick-base64': path.join(root, 'compat/quick-base64.js'),
    } })
  const api = (await import('data:text/javascript;base64,' + Buffer.from(built.outputFiles[0].text + '\n//# sourceURL=kg-playlist-links.mjs').toString('base64') + '#' + ++moduleId)).default
  api.getListDetailBySpecialId = async (id, page) => ({ kind: 'special', id, page })
  api.getUserListDetail2 = async (id, page) => ({ kind: 'global', id, page })
  api.getUserListDetail3 = async (id, page) => ({ kind: 'chain', id, page })
  return { api, requests }
}
const pageHtml = () => '<script>\nvar specialInfo = ' + JSON.stringify({
  name: '标题包含 }; 和 "引号"', plist: [{ nested: true }], id: 12,
  global_collection_id: 'collection_1_7_12_0',
}) + ';</script>'

test('Numeric public detail still directly uses metadata without fetching HTML', async () => {
  const { api, requests } = await apiWithPage()
  assert.deepEqual(await api.getListDetail('https://www.kugou.com/yy/special/single/42.html', 3), { kind: 'special', id: '42', page: 3 })
  assert.deepEqual(await api.getListDetail('id_42', 3), { kind: 'special', id: '42', page: 3 })
  assert.equal(requests.length, 0)
})

test('Encrypted detail reads balanced JSON and preserves global identity and page', async () => {
  const { api, requests } = await apiWithPage(() => ({ body: pageHtml() }))
  const result = await api.getListDetail('https://www.kugou.com/yy/special/single/abc123.html?encryp=1', 2)
  assert.deepEqual(result, { kind: 'global', id: 'collection_1_7_12_0', page: 2 })
  assert.equal(requests.length, 1)
  assert.deepEqual(await api.getListDetail('https://www.kugou.com/yy/special/single/123.html?encryp=1', 4), { kind: 'global', id: 'collection_1_7_12_0', page: 4 })
})

test('Personal collection paths do not lose the creator portion of their identity', async () => {
  const { api, requests } = await apiWithPage()
  for (const path of ['/yy/special/single/collection_3_7_12_0.html', '/songlist/collection_3_7_12_0.html']) {
    assert.deepEqual(await api.getListDetail('https://www.kugou.com' + path, 2), { kind: 'global', id: 'collection_3_7_12_0', page: 2 })
  }
  assert.equal(requests.length, 0)
})

test('Short link final HTML is consumed once instead of fetching the destination twice', async () => {
  const { api, requests } = await apiWithPage(() => ({
    url: 'https://www.kugou.com/yy/special/single/abc123.html?encryp=1', body: pageHtml(),
  }))
  assert.equal((await api.getListDetail('https://t1.kugou.com/fixture1', 1)).id, 'collection_1_7_12_0')
  assert.equal(requests.length, 1)
})

test('Short links landing on songs, albums or another site are rejected even with playlist-shaped data', async () => {
  for (const target of ['https://m.kugou.com/share/song.html?id=42', 'https://www.kugou.com/album/42.html',
    'https://kugou.com.outside.test/yy/special/single/42.html']) {
    const { api, requests } = await apiWithPage(() => ({ url: target, body: pageHtml() }))
    await assert.rejects(api.getListDetail('https://t1.kugou.com/fixture1', 1))
    assert.equal(requests.length, 1)
  }
})

test('Expired short links and missing metadata fail with bounded requests', async () => {
  for (const response of [{ body: '{}' }, { status: 404, body: 'not found' }, { body: '<script>var data = [];</script>' }]) {
    const { api, requests } = await apiWithPage(() => response)
    await assert.rejects(api.getListDetail('https://t1.kugou.com/fixture1', 1))
    assert.equal(requests.length, 1)
  }
})

test('Chain aliases and PC service pages use the same routing entry', async () => {
  const { api, requests } = await apiWithPage(() => ({ body: pageHtml() }))
  for (const url of ['https://m.kugou.com/share/?chain=abcdef123',
    'https://m.kugou.com/share/?id=abcdef123', 'https://m.kugou.com/share/index.php?id=abcdef123', 'https://m.kugou.com/schain/transfer?chain=abcdef123',
    'https://www.kugou.com/share/abcdef123.html']) {
    assert.deepEqual(await api.getListDetail(url, 2), { kind: 'chain', id: 'abcdef123', page: 2 })
  }
  assert.equal(requests.length, 0)
  assert.equal((await api.getListDetail('https://pc.service.kugou.com/yueku/v9/special/single/123-3-456.html', 2)).id, 'collection_1_7_12_0')
})


test('Chain metadata uses the same balanced JSON reader and does not cache missing data', async () => {
  let body = '<script>var phpParam = ' + JSON.stringify({ name: 'title }; quoted', id: 12 }, null, 2) + ';</script>'
  const { api, requests } = await apiWithPage(() => ({ body }))
  assert.equal((await api.getListInfoByChain('fixture-good')).name, 'title }; quoted')
  body = '<html>unavailable</html>'
  assert.equal((await api.getListInfoByChain('fixture-good')).id, 12)
  assert.equal(requests.length, 1)
  await assert.rejects(api.getListInfoByChain('fixture-bad'), /未获取到/)
  assert.equal(api.cache.has('fixture-bad'), false)
})


test('Redirected legacy zlist fetches its JSON endpoint rather than replaying the HTML response', async () => {
  const { api, requests } = await apiWithPage(url => url.includes('t1.kugou.com')
    ? { url: 'https://wwwapi.kugou.com/share/zlist.html?fixture=1', body: '<html>legacy</html>' }
    : { body: { errcode: 0, info: { '0': { name: 'fixture' } } } })
  api.getUserListDetailByLink = async (body, url, page) => ({ url, page })
  assert.deepEqual(await api.getListDetail('https://t1.kugou.com/fixture2', 2), {
    url: 'https://m3ws.kugou.com/zlist/list?fixture=1', page: 2,
  })
  assert.equal(requests.length, 2)
})

import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import { createNative } from '../native-node.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
let moduleId = 0
async function platform(source, respond = () => { throw new Error('unexpected HTTP request') }) {
  globalThis.__lxNative = {
    ...createNative(),
    http(payload) {
      const request = JSON.parse(payload)
      const body = respond(request)
      return JSON.stringify({ statusCode: 200, headers: {}, finalUrl: request.url,
        raw: Buffer.from(JSON.stringify(body)).toString('base64') })
    },
  }
  const result = await build({
    entryPoints: [path.join(root, `src/musicSdk/${source}/songList.js`)], bundle: true,
    format: 'esm', platform: 'node', target: 'node20', write: false, logLevel: 'silent',
    alias: {
      'react-native-quick-md5': path.join(root, 'compat/quick-md5.js'),
      'react-native-quick-base64': path.join(root, 'compat/quick-base64.js'),
      '@/utils/nativeModules/crypto': path.join(root, 'compat/native-crypto.js'),
    },
  })
  return (await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text + `\n//# sourceURL=playlist-import-${source}.mjs`).toString('base64')}#${++moduleId}`)).default
}

test('QQ share page parses its ID without fetching HTML and short song links are rejected', async () => {
  const api = await platform('tx')
  let redirects = 0
  api.handleParseId = async () => { redirects++; return 'https://y.qq.com/n/ryqq/playlist/42' }
  assert.equal(await api.getListId('https://i.y.qq.com/n2/m/share/details/taoge.html?id=42'), '42')
  assert.equal(redirects, 0)
  assert.equal(await api.getListId('https://c.y.qq.com/base/fcgi-bin/u?__=fixture'), '42')
  assert.equal(redirects, 1)
  api.handleParseId = async () => 'https://y.qq.com/n/ryqq/songDetail/42'
  await assert.rejects(api.getListId('https://c.y.qq.com/base/fcgi-bin/u?__=song'), /无法识别/)
})

test('QQ page four is pagination, not retry count; offset and declared total are preserved', async () => {
  const requests = []
  const api = await platform('tx', request => {
    const payload = JSON.parse(Buffer.from(request.body, 'base64').toString())
    requests.push(payload.req_1.param)
    return { code: 0, req_1: { code: 0, data: { total_song_num: 450,
      dirinfo: { title: 'fixture' }, songlist: [{ id: 301 }] } } }
  })
  api.filterListDetail = list => list
  const page = await api.getListDetail('42', 4)
  assert.equal(requests[0].song_begin, 300)
  assert.equal(requests[0].song_num, 100)
  assert.equal(page.page, 4)
  assert.equal(page.total, 450)
  assert.equal(page.allPage, 5)
})

test('QQ truncated legacy response switches to its paginated API instead of claiming completeness', async () => {
  let calls = 0
  const api = await platform('tx', request => {
    calls++
    if (request.method === 'GET') return { code: 0, subcode: 0, cdlist: [{ songnum: 500, songlist: [{ id: 1 }] }] }
    return { code: 0, req_1: { code: 0, data: { total_song_num: 500, songlist: [{ id: 1 }], dirinfo: {} } } }
  })
  api.filterListDetail = list => list
  const page = await api.getListDetail('42', 1)
  assert.equal(calls, 2)
  assert.equal(page.total, 500)
  assert.equal(page.allPage, 5)
})

test('Migu desktop, mobile and current hash links reuse one detail path', async () => {
  const api = await platform('mg')
  const requests = []
  api.getListDetailList = async (id, page) => { requests.push([id, page]); return { list: [] } }
  api.getListDetailInfo = async () => ({ name: 'fixture' })
  for (const url of ['https://music.migu.cn/v3/music/playlist/42',
    'https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?id=42',
    'https://music.migu.cn/v5/#/playlist?playlistId=42']) {
    await api.getListDetail(url, 2)
  }
  assert.deepEqual(requests, [['42', 2], ['42', 2], ['42', 2]])
})

test('Netease complete track data still slices pages over 1000 rather than repeating page one', async () => {
  const tracks = Array.from({ length: 2005 }, (_, i) => ({ id: i + 1 }))
  const api = await platform('wy', () => ({ code: 200,
    playlist: { trackIds: tracks, tracks, name: 'fixture', creator: {} }, privileges: tracks }))
  api.filterListDetail = body => body.playlist.tracks
  const first = await api.getListDetail('42', 1)
  const third = await api.getListDetail('42', 3)
  assert.equal(first.list.length, 1000)
  assert.equal(third.list.length, 5)
  assert.equal(third.list[0].id, 2001)
  assert.equal(third.total, 2005)
  assert.equal(third.allPage, 3)
})

test('Kugou personal collection uses a stable 300-item page size and propagates the cursor', async () => {
  const api = await platform('kg')
  api.decodeGcid = async () => 'fixture'
  const requested = []
  api.createHttp = async url => {
    if (url.includes('/info_v2')) return { songcount: 520, specialname: 'fixture' }
    const params = new URL(url).searchParams
    const page = Number(params.get('page')); const limit = Number(params.get('pagesize'))
    requested.push([page, limit])
    return { info: Array.from({ length: Math.min(limit, 520 - (page - 1) * limit) }, (_, i) => ({ id: (page - 1) * limit + i + 1 })) }
  }
  api.getMusicInfos = async list => list
  const first = await api.getListDetail('https://www.kugou.com/songlist/gcid_fixture/', 1)
  const second = await api.getListDetail('https://www.kugou.com/songlist/gcid_fixture/', 2)
  assert.deepEqual(requested, [[1, 300], [2, 300]])
  assert.equal(first.list.at(-1).id, 300)
  assert.equal(second.list[0].id, 301)
  assert.equal(second.list.at(-1).id, 520)
  assert.equal(second.total, 520)
  assert.equal(second.allPage, 2)
})

test('Kugou legacy shared list does not shrink final page size and overlap earlier items', async () => {
  const api = await platform('kg')
  api.getMusicInfos = async list => list
  api.createHttp = async url => {
    const query = new URL(url).searchParams
    assert.equal(query.get('pagesize'), '90')
    assert.equal(query.get('page'), '3')
    return { list: { info: [{ id: 181 }] } }
  }
  const page = await api.getUserListDetailByLink({ info: { '0': { count: 195, name: 'fixture' } } },
    'https://m3ws.kugou.com/zlist/list?pagesize=30&page=1', 3)
  assert.equal(page.list[0].id, 181)
  assert.equal(page.total, 195)
  assert.equal(page.allPage, 3)
})

test('Kuwo digest-five retries keep the metadata operation and requested cursor', async () => {
  let attempts = 0
  const urls = []
  const api = await platform('kw', request => {
    urls.push(request.url)
    if (request.url.includes('cont=ninfo')) return ++attempts === 1 ? {} : { child: [{ sourceid: 'inner' }] }
    return { result: 'ok', musiclist: [], total: 2500, rn: 1000 }
  })
  assert.equal(await api.getListDetailDigest5Info('fixture', 3), 'inner')
  assert.equal(urls.length, 2)
  api.filterListDetail = list => list
  const page = await api.getListDetailDigest5Music('inner', 3)
  assert.match(urls.at(-1), /pn=2&rn=/)
  assert.doesNotMatch(urls.at(-1), /\}/)
  assert.equal(page.allPage, 3)
})

test('Migu metadata retry stays on metadata and tolerates absent optional cover fields', async () => {
  let calls = 0
  const api = await platform('mg', () => ++calls === 1 ? { code: 'FAILED' } : { code: '000000', data: { title: 'fixture' } })
  api.getListDetail = () => { throw new Error('metadata retried as songs') }
  await api.getListDetailInfo('42')
  assert.equal(calls, 2)
})


test('Netease short song link cannot be mistaken for a playlist with the same numeric ID', async () => {
  const api = await platform('wy')
  globalThis.__lxNative.http = () => JSON.stringify({ statusCode: 200, headers: {},
    finalUrl: 'https://music.163.com/#/song?id=42', raw: Buffer.from('').toString('base64') })
  await assert.rejects(api.getListId('https://163cn.tv/fixture'), /不是网易云歌单/)
})

test('Netease mobile shares extract playlist ID rather than userid or creatorId without HTTP', async () => {
  const api = await platform('wy')
  for (const url of [
    'https://y.music.163.com/m/playlist?id=42&userid=700001&creatorId=700002',
    'https://y.music.163.com/m/playlist?userid=700001&id=42&creatorId=700002',
  ]) {
    assert.equal((await api.getListId(url)).id, '42')
  }
})

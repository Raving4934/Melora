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

const neteaseTrack = id => ({ id, name: `歌曲 ${id}`, dt: 210_000 })
const neteasePrivilege = ({ id }) => ({ id, maxbr: 128000 })
const neteasePlaylist = (tracks, preview = tracks) => ({
  code: 200,
  playlist: {
    trackIds: tracks.map(({ id }) => ({ id })),
    tracks: preview,
    name: 'fixture',
    coverImgUrl: 'https://example.test/cover.jpg',
    creator: { nickname: 'fixture author' },
  },
  privileges: preview.map(neteasePrivilege),
})
const neteaseDetailPath = '/api/v6/playlist/detail'
const neteaseSongPath = '/weapi/v3/song/detail'

test('Netease v6 complete metadata preserves the page size and final partial page', async () => {
  const tracks = Array.from({ length: 2005 }, (_, i) => neteaseTrack(i + 1))
  const requests = []
  const api = await platform('wy', request => {
    requests.push(request)
    return neteasePlaylist(tracks)
  })
  const first = await api.getListDetail('42', 1)
  const third = await api.getListDetail('42', 3)
  const afterLastPage = await api.getListDetail('42', 4)
  assert.equal(requests.length, 3)
  for (const request of requests) {
    assert.equal(request.method, 'GET')
    assert.equal(request.url, 'https://music.163.com/api/v6/playlist/detail?id=42&n=100&s=0')
    assert.equal(request.headers.Referer, 'https://music.163.com/')
    assert.equal(request.headers.Cookie, undefined)
  }
  assert.equal(first.list.length, 1000)
  assert.equal(third.list.length, 5)
  assert.equal(third.list[0].songmid, 2001)
  assert.equal(third.total, 2005)
  assert.equal(third.allPage, 3)
  assert.equal(third.rawCount, 5)
  assert.equal(third.info.name, 'fixture')
  assert.equal(third.info.img, 'https://example.test/cover.jpg')
  assert.equal(afterLastPage.rawCount, 0)
  assert.deepEqual(afterLastPage.list, [])
})

test('Netease v6 matches complete privileges by song ID across page boundaries', async () => {
  const tracks = Array.from({ length: 1002 }, (_, i) => neteaseTrack(i + 1))
  const response = neteasePlaylist(tracks)
  const privileges = response.privileges
  privileges[1000].maxbr = 999000
  const firstPrivilege = privileges[0]
  privileges[0] = privileges[1000]
  privileges[1000] = firstPrivilege
  const api = await platform('wy', request => {
    assert.equal(new URL(request.url).pathname, neteaseDetailPath)
    return response
  })

  const result = await api.getListDetail('42', 2)
  assert.deepEqual(result.list.map(song => song.songmid), [1001, 1002])
  assert.deepEqual(result.list[0].types.map(entry => entry.type), ['128k', '320k', 'flac'])
  assert.equal(result.rawCount, 2)
})

test('Netease v6 preview and song detail keep their respective artist and quality metadata', async () => {
  const tracks = [
    {
      ...neteaseTrack(1),
      ar: [{ name: 'A &amp; B' }, { name: '' }, { name: 'C' }],
      al: { id: 7, name: 'Album', picUrl: 'https://example.test/album.jpg' },
      l: { size: 1024 },
      h: { size: 2048 },
      sq: { size: 4096 },
      hr: { size: 8192 },
    },
    {
      ...neteaseTrack(2),
      pc: { ar: 'Cloud artist', sn: 'Cloud track', alb: 'Cloud album' },
    },
  ]
  const privileges = [
    { id: 1, maxbr: 999000, maxBrLevel: 'hires' },
    { id: 2, maxbr: 192000 },
  ]
  const api = await platform('wy', request => {
    const url = new URL(request.url)
    if (url.pathname === neteaseDetailPath) {
      if (url.searchParams.get('id') === '42') return { ...neteasePlaylist(tracks), privileges }
      return neteasePlaylist(tracks, [])
    }
    assert.equal(url.pathname, neteaseSongPath)
    return { code: 200, songs: tracks, privileges }
  })

  const preview = await api.getListDetail('42')
  const completed = await api.getListDetail('43')
  const sizes = ['1.00 KiB', '2.00 KiB', '4.00 KiB', '8.00 KiB']
  assert.equal(preview.list[0].interval, '03:30')
  assert.equal(completed.list[0].interval, '03:30')
  assert.equal(preview.list[0].singer, 'A & B、C')
  assert.equal(completed.list[0].singer, 'A &amp; B、、C')
  assert.deepEqual(preview.list[0].types.map(entry => entry.size), [sizes[0], sizes[1], null, sizes[3]])
  assert.deepEqual(completed.list[0].types.map(entry => entry.size), sizes)
  assert.deepEqual(preview.list[0]._types.flac, { size: null })
  assert.deepEqual(completed.list[0]._types.flac, { size: sizes[2] })
  assert.deepEqual(preview.list[1], completed.list[1])
  assert.equal(completed.list[1].name, 'Cloud track')
  assert.equal(completed.list[1].albumName, 'Cloud album')
  assert.equal(completed.list[1].img, '')
})

test('Netease v6 preview completes only the requested page in playlist order and counts filtered songs', async () => {
  const tracks = Array.from({ length: 1003 }, (_, i) => neteaseTrack(i + 1))
  const requests = []
  const requestedIds = []
  const api = await platform('wy', request => {
    const path = new URL(request.url).pathname
    requests.push(path)
    if (path === neteaseDetailPath) return neteasePlaylist(tracks, tracks.slice(0, 100))
    assert.equal(path, neteaseSongPath)
    const songs = [tracks[1002], tracks[1000], tracks[1001]]
    return { code: 200, songs, privileges: [songs[1], songs[0]].map(neteasePrivilege) }
  })
  const native = globalThis.__lxNative
  const aes = native.aes
  native.aes = (action, mode, key, iv, payload) => {
    const input = Buffer.from(payload, 'base64').toString()
    if (action === 'encrypt' && input.startsWith('{')) {
      const { ids } = JSON.parse(input)
      requestedIds.push(...JSON.parse(ids))
    }
    return aes(action, mode, key, iv, payload)
  }

  const second = await api.getListDetail('42', 2).finally(() => { native.aes = aes })
  assert.deepEqual(requests, [neteaseDetailPath, neteaseSongPath])
  assert.deepEqual(requestedIds, [1001, 1002, 1003])
  assert.deepEqual(second.list.map(song => song.songmid), [1001, 1003])
  assert.equal(second.rawCount, 3)
  assert.equal(second.total, 1003)
  assert.equal(second.page, 2)
  assert.equal(second.limit, 1000)
  assert.equal(second.allPage, 2)
})

test('Netease provider retries authorized detail and song completion without leaking the cookie', async () => {
  const tracks = [neteaseTrack(1), neteaseTrack(2)]
  const requests = []
  let detailCalls = 0
  let songCalls = 0
  const api = await platform('wy', request => {
    const url = new URL(request.url)
    requests.push([url.pathname, url.searchParams.get('id'), request.headers.Cookie])
    if (url.pathname === neteaseDetailPath) {
      return ++detailCalls === 1 ? { code: 502 } : neteasePlaylist(tracks, [])
    }
    assert.equal(url.pathname, neteaseSongPath)
    return ++songCalls === 1 ? { code: 502 } : { code: 200, songs: tracks, privileges: tracks.map(neteasePrivilege) }
  })

  const authorized = await api.getListDetail('https://music.163.com/#/playlist?id=42###test-token')
  assert.equal(authorized.list.length, 2)
  const publicResult = await api.getListDetail('42')
  assert.equal(publicResult.list.length, 2)
  assert.deepEqual(requests, [
    [neteaseDetailPath, '42', 'MUSIC_U=test-token'],
    [neteaseDetailPath, '42', 'MUSIC_U=test-token'],
    [neteaseSongPath, null, 'MUSIC_U=test-token'],
    [neteaseDetailPath, '42', 'MUSIC_U=test-token'],
    [neteaseSongPath, null, 'MUSIC_U=test-token'],
    [neteaseDetailPath, '42', undefined],
    [neteaseSongPath, null, undefined],
  ])
})

test('Netease detail stops after three rejected responses', async () => {
  const requests = []
  const api = await platform('wy', request => {
    requests.push(new URL(request.url).pathname)
    return { code: 405 }
  })
  await assert.rejects(api.getListDetail('42'), /^Error: try max num$/)
  assert.deepEqual(requests, Array(3).fill(neteaseDetailPath))
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

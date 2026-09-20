import assert from 'node:assert/strict'
import { createCipheriv, createDecipheriv, createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sourceRoot = path.join(root, 'src/musicSdk')

const aes = (action, mode, keyBase64, ivBase64, dataBase64) => {
  const algorithm = mode.includes('cbc') ? 'aes-128-cbc' : 'aes-128-ecb'
  const key = Buffer.from(keyBase64, 'base64')
  const iv = algorithm.endsWith('cbc') ? Buffer.from(ivBase64, 'base64') : null
  const cipher = action === 'encrypt'
    ? createCipheriv(algorithm, key, iv)
    : createDecipheriv(algorithm, key, iv)
  return Buffer.concat([cipher.update(Buffer.from(dataBase64, 'base64')), cipher.final()]).toString('base64')
}

globalThis.__leaderboardRequests = []
globalThis.__leaderboardHttp = () => ({ statusCode: 500, body: {} })
globalThis.__lxNative = {
  hash(algorithm, dataBase64) {
    return createHash(algorithm).update(Buffer.from(dataBase64, 'base64')).digest('hex')
  },
  aes,
  rsaEncrypt() {
    return Buffer.alloc(128).toString('base64')
  },
  http(payloadJson) {
    const payload = JSON.parse(payloadJson)
    globalThis.__leaderboardRequests.push(payload)
    const response = globalThis.__leaderboardHttp(payload)
    const raw = response.raw ?? JSON.stringify(response.body ?? {})
    return JSON.stringify({
      statusCode: response.statusCode ?? 200,
      statusMessage: response.statusMessage ?? 'OK',
      headers: response.headers ?? { 'content-type': 'application/json' },
      raw: Buffer.from(raw).toString('base64'),
    })
  },
}

const aliasPlugin = {
  name: 'leaderboard-test-aliases',
  setup(buildApi) {
    buildApi.onResolve({ filter: /^@\/utils\/request$/ }, () => ({ path: path.join(root, 'src/request.js') }))
    buildApi.onResolve({ filter: /^@\/utils\/nativeModules\/crypto$/ }, () => ({ path: path.join(root, 'compat/native-crypto.js') }))
  },
}

const loadLeaderboard = async source => {
  const result = await build({
    entryPoints: [path.join(sourceRoot, source, 'leaderboard.js')],
    bundle: true,
    format: 'esm',
    platform: 'node',
    target: 'node20',
    alias: {
      'react-native-quick-md5': path.join(root, 'compat/quick-md5.js'),
      'react-native-quick-base64': path.join(root, 'compat/quick-base64.js'),
    },
    plugins: [aliasPlugin],
    write: false,
    logLevel: 'silent',
  })
  const code = Buffer.from(result.outputFiles[0].text).toString('base64')
  return import(`data:text/javascript;base64,${code}`)
}

const songs = (count, create) => Array.from({ length: count }, (_, index) => create(index))

const kwSongs = count => songs(count, index => ({
  artist: '歌手',
  name: `酷我歌曲${index}`,
  album: '专辑',
  albumId: 'album',
  id: String(index),
  duration: '180',
  pic: 'https://example.test/cover.jpg',
  n_minfo: '',
}))

const kgSongs = count => songs(count, index => ({
  filesize: 1,
  '320filesize': 0,
  sqfilesize: 0,
  filesize_high: 0,
  hash: `hash-${index}`,
  audio_id: String(index),
  songname: `酷狗歌曲${index}`,
  remark: '专辑',
  album_id: 'album',
  duration: 180,
  authors: [{ author_name: '歌手' }],
}))

const txSongs = count => songs(count, index => ({
  file: { size_128mp3: 1, size_320mp3: 0, size_flac: 0, size_hires: 0, media_mid: `media-${index}` },
  singer: [{ name: '歌手' }],
  title: `企鹅歌曲${index}`,
  album: { name: '专辑', mid: 'album' },
  interval: 180,
  id: index,
  mid: `mid-${index}`,
}))

const wySongs = count => songs(count, index => ({
  id: index,
  name: `网易歌曲${index}`,
  ar: [{ name: '歌手' }],
  al: { id: 'album', name: '专辑', picUrl: 'https://example.test/cover.jpg' },
  dt: 180000,
}))

const mgSongs = count => songs(count, index => ({
  songId: `mg-${index}`,
  songName: `咪咕歌曲${index}`,
  length: '03:00',
  album: '专辑',
  albumId: 'album',
  albumImgs: [{ img: 'https://example.test/cover.jpg' }],
  artists: [{ name: '歌手' }],
  newRateFormats: [],
}))

const encryptedKuwoResponse = body => aes(
  'encrypt',
  'aes-128-ecb',
  'cFcnPcf6Kb85RC1y3V6M5A==',
  '',
  Buffer.from(JSON.stringify(body)).toString('base64'),
)

const resetHttp = handler => {
  globalThis.__leaderboardRequests = []
  globalThis.__leaderboardHttp = handler
}

test('leaderboard adapters accept a bounded preview without changing default page sizes', async () => {
  const kwModule = await loadLeaderboard('kw')
  const kw = kwModule.default
  resetHttp(() => ({
    statusCode: 200,
    raw: encryptedKuwoResponse({ code: 200, data: { total: 8, musiclist: kwSongs(5) } }),
  }))
  const kwPreview = await kw.getList('93', 1, 3)
  assert.equal(kwPreview.list.length, 3)
  assert.equal(kwPreview.limit, 3)
  const decodeKwRequest = payload => JSON.parse(Buffer.from(
    aes('decrypt', 'aes-128-ecb', 'cFcnPcf6Kb85RC1y3V6M5A==', '', new URL(payload.url).searchParams.get('data')),
    'base64',
  ).toString('utf8'))
  assert.equal(decodeKwRequest(globalThis.__leaderboardRequests[0]).rn, 3)
  const kwFull = await kw.getList('93', 1)
  assert.equal(kwFull.list.length, 5)
  assert.equal(kwFull.limit, 100)
  assert.equal(decodeKwRequest(globalThis.__leaderboardRequests.at(-1)).rn, 100)

  const kg = (await loadLeaderboard('kg')).default
  const kgRequests = []
  kg.getData = url => {
    kgRequests.push(url)
    return Promise.resolve({ body: { errcode: 0, data: { total: 8, info: kgSongs(5) } } })
  }
  const kgPreview = await kg.getList('8888', 1, 3)
  assert.equal(kgPreview.list.length, 3)
  assert.equal(kgPreview.limit, 3)
  assert.match(kgRequests[0], /pagesize=3/)
  const kgFull = await kg.getList('8888', 1)
  assert.equal(kgFull.list.length, 5)
  assert.equal(kgFull.limit, 100)
  assert.match(kgRequests.at(-1), /pagesize=100/)

  const tx = (await loadLeaderboard('tx')).default
  const txRequests = []
  tx.getPeriods = () => Promise.resolve('2026-09-19')
  tx.listDetailRequest = (id, period, limit) => {
    txRequests.push({ id, period, limit })
    return Promise.resolve({ body: { code: 0, toplist: { data: { songInfoList: txSongs(5) } } } })
  }
  const txPreview = await tx.getList('4', 1, 3)
  assert.equal(txPreview.list.length, 3)
  assert.equal(txPreview.limit, 3)
  assert.equal(txRequests[0].limit, 3)
  const txFull = await tx.getList('4', 1)
  assert.equal(txFull.list.length, 5)
  assert.equal(txFull.limit, 300)
  assert.equal(txRequests.at(-1).limit, 300)

  const wy = (await loadLeaderboard('wy')).default
  const wyRequests = []
  wy.getData = (id, limit) => {
    wyRequests.push({ id, limit })
    return Promise.resolve({
      statusCode: 200,
      body: { code: 200, playlist: { trackIds: songs(5, index => ({ id: index })) } },
    })
  }
  resetHttp(payload => payload.url.includes('/weapi/v3/song/detail')
    ? { statusCode: 200, body: { code: 200, songs: wySongs(5), privileges: wySongs(5).map(song => ({ id: song.id, maxbr: 128000 })) } }
    : { statusCode: 500, body: {} })
  const wyPreview = await wy.getList('19723756', 1, 3)
  assert.equal(wyPreview.list.length, 3)
  assert.equal(wyPreview.limit, 3)
  assert.equal(wyRequests[0].limit, 3)
  const wyFull = await wy.getList('19723756', 1)
  assert.equal(wyFull.list.length, 5)
  assert.equal(wyFull.limit, 100000)
  assert.equal(wyRequests.at(-1).limit, 100000)

  const mg = (await loadLeaderboard('mg')).default
  const mgRequests = []
  mg.getData = url => {
    mgRequests.push(url)
    return Promise.resolve({
      statusCode: 200,
      body: { code: '000000', columnInfo: { contents: mgSongs(5).map(objectInfo => ({ objectInfo })) } },
    })
  }
  const mgPreview = await mg.getList('27553319', 1, 3)
  assert.equal(mgPreview.list.length, 3)
  assert.equal(mgPreview.limit, 3)
  assert.match(mgRequests[0], /pageSize=3&pageNo=0/)
  const mgFull = await mg.getList('27553319', 1)
  assert.equal(mgFull.list.length, 5)
  assert.equal(mgFull.limit, 200)
  assert.doesNotMatch(mgRequests.at(-1), /pageSize=/)
})

test('boardSongs entry forwards optional limit while preserving the default call shape', async () => {
  const entry = await readFile(path.join(root, 'entry.js'), 'utf8')
  assert.match(entry, /getList\(params\.bangId, params\.page \|\| 1, params\.limit\)/)
  assert.match(entry, /limit: result\.limit \?\? 30/)
})

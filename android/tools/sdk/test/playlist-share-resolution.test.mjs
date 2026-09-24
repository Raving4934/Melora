import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import { createNative } from '../native-node.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
let moduleId = 0

async function platform(source, redirects = new Map()) {
  globalThis.__lxNative = {
    ...createNative(),
    http(payloadJson) {
      const request = JSON.parse(payloadJson)
      const finalUrl = redirects.get(request.url) ?? request.url
      return JSON.stringify({ statusCode: 200, headers: {}, finalUrl,
        raw: Buffer.from('{}').toString('base64') })
    },
  }
  const result = await build({
    entryPoints: [path.join(root, `src/musicSdk/${source}/songList.js`)],
    bundle: true,
    format: 'esm',
    platform: 'node',
    target: 'node20',
    write: false,
    logLevel: 'silent',
    alias: {
      'react-native-quick-md5': path.join(root, 'compat/quick-md5.js'),
      'react-native-quick-base64': path.join(root, 'compat/quick-base64.js'),
      '@/utils/nativeModules/crypto': path.join(root, 'compat/native-crypto.js'),
    },
  })
  const code = Buffer.from(result.outputFiles[0].text + '\n//# sourceURL=playlist-share-resolution.mjs').toString('base64')
  return (await import(`data:text/javascript;base64,${code}#${++moduleId}`)).default
}

test('QQ resolves only supported playlist routes and QQ short links', async () => {
  const short = 'https://c6.y.qq.com/base/fcgi-bin/u?fixture=1'
  const api = await platform('tx', new Map([
    [short, 'https://i.y.qq.com/n/m/detail/taoge/index.html?share=1&id=301'],
  ]))

  assert.equal(await api.getListId('7217720898'), '7217720898')
  assert.equal(await api.getListId('https://y.qq.com/n/yqq/playlist/42.html'), '42')
  assert.equal(await api.getListId('https://y.qq.com/w/taoge.html?id=42'), '42')
  assert.equal(await api.getListId('https://y.qq.com/w/taoge.html?from=share&id=43#share'), '43')
  assert.equal(await api.getListId('https://i.y.qq.com/n2/m/share/details/taoge.html?platform=11&id=43'), '43')
  assert.equal(await api.getListId('https://i.y.qq.com/n/m/detail/taoge/index.html?share=1&id=44'), '44')
  assert.equal(await api.getListId('https://y.qq.com/musicmac/v6/playlist/detail.html?foo=x&id=45'), '45')
  assert.equal(await api.getListId('https://i2.y.qq.com/n3/other/pages/details/playlist.html?id=46'), '46')
  assert.equal(await api.getListId(short), '301')

  for (const url of [
    'https://y.qq.com/n/ryqq/songDetail/42?id=42',
    'https://y.qq.com/n/ryqq/albumDetail/42?id=42',
    'https://outside.test/playlist/42',
    'https://y.qq.com/w/taoge.html?id=not-a-number',
    'https://y.qq.com/w/song.html?id=42',
  ]) await assert.rejects(api.getListId(url), /无法识别/)
})

test('Netease parses playlist path, query and fragment forms and rejects wrong short-link targets', async () => {
  const short = 'https://163cn.tv/fixture'
  const badShort = 'https://163cn.tv/song-fixture'
  const api = await platform('wy', new Map([
    [short, 'https://y.music.163.com/m/playlist?from=share&id=56'],
    [badShort, 'https://music.163.com/song?id=57'],
  ]))

  for (const [url, id] of [
    ['https://music.163.com/playlist/52?foo=1', '52'],
    ['https://y.music.163.com/m/playlist?foo=1&id=53', '53'],
    ['https://music.163.com/#/playlist?foo=x&id=54', '54'],
    ['https://music.163.com/#/playlist/55', '55'],
    ['https://music.163.com/?id=99#/playlist?id=55', '55'],
    [short, '56'],
  ]) assert.equal((await api.getListId(url)).id, id)

  await assert.rejects(api.getListId(badShort), /不是网易云歌单/)
  await assert.rejects(api.getListId('https://outside.test/playlist?id=58'), /不是网易云歌单/)
})

test('Migu accepts v3, H5 and v5 playlist links and resolves bounded multi-hop shares', async () => {
  const shortA = 'https://c.migu.cn/a'
  const shortB = 'https://c.migu.cn/b'
  const api = await platform('mg', new Map([
    [shortA, shortB],
    [shortB, 'https://music.migu.cn/v5/#/playlist?from=share&playlistId=63'],
  ]))
  const requested = []
  api.getListDetailList = async (id, page) => { requested.push([id, page]); return { list: [] } }
  api.getListDetailInfo = async () => ({ name: 'fixture' })

  for (const [url, id] of [
    ['https://music.migu.cn/v3/music/playlist/61', '61'],
    ['https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?channel=x&id=62', '62'],
    ['https://music.migu.cn/v5/#/playlist?playlistId=64', '64'],
  ]) await api.getListDetail(url, 2).then(() => assert.equal(requested.at(-1)[0], id))
  await api.getListDetail(shortA, 3)
  assert.deepEqual(requested.at(-1), ['63', 3])
  api.getDetailUrl = async () => { throw new Error('Pagination must not resolve the short link again') }
  await api.getListDetail(shortA, 4)
  assert.deepEqual(requested.at(-1), ['63', 4])
})

test('Migu rejects non-playlists, redirect cycles and chains beyond the hop limit', async () => {
  const cycleA = 'https://c.migu.cn/cycle-a'
  const cycleB = 'https://c.migu.cn/cycle-b'
  const chain = Array.from({ length: 7 }, (_, i) => `https://c.migu.cn/hop-${i}`)
  const redirects = new Map([
    ['https://music.migu.cn/v3/music/song/71', 'https://music.migu.cn/v3/music/song/71'],
    [cycleA, cycleB], [cycleB, cycleA],
    ...chain.slice(0, -1).map((url, i) => [url, chain[i + 1]]),
  ])
  const api = await platform('mg', redirects)
  let calls = 0
  api.getListDetailList = async () => { calls++; return { list: [] } }
  api.getListDetailInfo = async () => ({})

  await assert.rejects(api.getListDetail('https://music.migu.cn/v3/music/song/71', 1), /不是咪咕歌单/)
  await assert.rejects(api.getListDetail(cycleA, 1), /不是咪咕歌单/)
  await assert.rejects(api.getListDetail(chain[0], 1), /跳转次数超限/)
  assert.equal(calls, 0)
  assert.equal(api.resolvedPlaylistIds.size, 0)
})


test('Kuwo mobile, desktop, trailing slash and Bodian links preserve identity and page', async () => {
  const api = await platform('kw')
  api.getListDetailDigest8 = async (id, page) => ({ id, page })
  api.getListDetailMusicListByBD = async (url, page) => ({ url, page })
  for (const url of [
    'https://www.kuwo.cn/playlist_detail/81',
    'https://www.kuwo.cn/playlist_detail/81/',
    'https://m.kuwo.cn/h5app/playlist/81?from=share',
    'https://m.kuwo.cn/newh5app/playlist_detail/81/?from=share',
  ]) {
    assert.deepEqual(await api.getListDetail(url, 3), { id: '81', page: 3 })
    assert.equal(api.getDetailPageUrl(url), 'http://www.kuwo.cn/playlist_detail/81')
  }
  const url = 'https://h5app.kuwo.cn/m/bodian/collection.html?playlistId=82&source=5'
  assert.deepEqual(await api.getListDetail(url, 2), { url, page: 2 })
})

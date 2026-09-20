import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const loadKgSongList = async () => {
  const result = await build({
    entryPoints: [path.join(root, 'src/musicSdk/kg/songList.js')],
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

test('KG playlist detail uses the structured endpoint and maps playable songs', async () => {
  const requests = []
  globalThis.__lxNative = {
    hash(algorithm, dataBase64) {
      return createHash(algorithm).update(Buffer.from(dataBase64, 'base64')).digest('hex')
    },
    http(payloadJson) {
      const payload = JSON.parse(payloadJson)
      requests.push(payload)
      const response = {
        status: 1,
        error_code: 0,
        data: {
          count: 1,
          page: 1,
          pagesize: 30,
          info: [{
            hash: 'HASH128',
            audio_id: 42,
            size: 3_000_000,
            name: '歌手甲 - 测试歌曲',
            album_id: 'album-1',
            timelen: 181_000,
            cover: 'http://imge.kugou.com/stdmusic/{size}/cover.jpg',
            albuminfo: { id: 7, name: '测试专辑' },
            singerinfo: [{ id: 8, name: '歌手甲' }],
            relate_goods: [
              { level: 2, bitrate: 128, hash: 'HASH128', size: 3_000_000 },
              { level: 4, bitrate: 320, hash: 'HASH320', size: 8_000_000 },
              { level: 5, bitrate: 900, hash: 'HASHFLAC', size: 24_000_000 },
            ],
          }],
        },
      }
      return JSON.stringify({
        statusCode: 200,
        statusMessage: 'OK',
        headers: { 'content-type': 'application/json' },
        finalUrl: payload.url,
        raw: Buffer.from(JSON.stringify(response)).toString('base64'),
      })
    },
  }

  const songList = await loadKgSongList()
  const result = await songList.getListDetail('id_123456', 1)

  assert.equal(requests.length, 1)
  assert.match(requests[0].url, /^https:\/\/gatewayretry\.kugou\.com\/v2\/get_other_list_file\?/)
  assert.match(requests[0].url, /specialid=123456/)
  assert.match(requests[0].url, /pagesize=30/)
  assert.match(requests[0].url, /signature=[a-f0-9]{32}$/)
  assert.equal(requests[0].headers['x-router'], 'pubsongscdn.kugou.com')
  assert.doesNotMatch(requests[0].url, /special\/single/)

  assert.equal(result.total, 1)
  assert.equal(result.limit, 30)
  assert.equal(result.list[0].name, '测试歌曲')
  assert.equal(result.list[0].singer, '歌手甲')
  assert.equal(result.list[0].albumName, '测试专辑')
  assert.equal(result.list[0].songmid, '42')
  assert.equal(result.list[0].img, 'https://imge.kugou.com/stdmusic/500/cover.jpg')
  assert.deepEqual(result.list[0].types.map(item => item.type), ['128k', '320k', 'flac'])
  assert.equal(result.list[0]._types['320k'].hash, 'HASH320')
})

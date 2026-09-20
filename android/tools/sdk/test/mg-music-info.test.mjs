import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const loadMusicInfo = async () => {
  const result = await build({
    entryPoints: [path.join(root, 'src/musicSdk/mg/musicInfo.js')],
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
  return import(`data:text/javascript;base64,${code}`)
}

test('Migu song filters normalize relative artwork to the image CDN', async () => {
  const { filterMusicInfoList, filterMusicInfoListV5 } = await loadMusicInfo()
  const relative = '/data/oss/resource/00/2h/vf/q3.webp'

  const legacy = filterMusicInfoList([{
    songId: 'legacy-1',
    songName: '旧接口歌曲',
    length: '03:15',
    albumImgs: [{ img: relative }],
  }])
  const v5 = filterMusicInfoListV5([{
    songId: 'v5-1',
    songName: '歌单歌曲',
    duration: 195,
    img3: relative,
    audioFormats: [
      { formatType: 'PQ', asize: '4153471' },
      { formatType: 'ZQ24', isize: '28544468' },
    ],
  }])

  const expected = `https://d.musicapp.migu.cn${relative}`
  assert.equal(legacy[0].img, expected)
  assert.equal(v5[0].img, expected)
  assert.deepEqual(v5[0].types.map(item => item.type), ['128k', 'flac24bit'])
  assert.notEqual(v5[0].types[0].size, '0 B')
})

test('Migu image normalization preserves complete URLs and upgrades HTTP', async () => {
  const { normalizeImageUrl } = await loadMusicInfo()

  assert.equal(normalizeImageUrl('https://example.com/a.jpg'), 'https://example.com/a.jpg')
  assert.equal(normalizeImageUrl('http://example.com/a.jpg'), 'https://example.com/a.jpg')
  assert.equal(normalizeImageUrl('//example.com/a.jpg'), 'https://example.com/a.jpg')
  assert.equal(normalizeImageUrl(null), null)
})

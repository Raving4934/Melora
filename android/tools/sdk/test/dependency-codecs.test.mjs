import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import vm from 'node:vm'
import { fileURLToPath } from 'node:url'
import { deflateSync } from 'node:zlib'
import { build } from 'esbuild'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

// 和 QuickJS 一样不提供 TextDecoder/TextEncoder，不能让浏览器全局对象掩盖回归。
async function load(relativePath) {
  const result = await build({
    entryPoints: [path.join(root, relativePath)], bundle: true, format: 'iife',
    globalName: 'codec', platform: 'browser', target: 'es2020', write: false,
    logLevel: 'silent', inject: [path.join(root, 'bootstrap.js')],
    alias: {
      'react-native-quick-md5': path.join(root, 'compat/quick-md5.js'),
      'react-native-quick-base64': path.join(root, 'compat/quick-base64.js'),
    },
  })
  const context = vm.createContext({ Buffer, __lxNative: {} })
  vm.runInContext(result.outputFiles[0].text, context)
  assert.equal(context.TextDecoder, undefined)
  return context.codec
}

test('he 2 preserves Unicode HTML entity decoding', async () => {
  const { decodeName } = await load('src/index.js')
  assert.equal(decodeName('乐屿 &amp; &#x1F3B5; &quot;歌词&quot;'), '乐屿 & 🎵 "歌词"')
  assert.equal(decodeName(''), '')
})

test('pako 3 KRC decompression still returns UTF-8 text without TextDecoder', async () => {
  const { decodeLyric } = await load('src/musicSdk/kg/util.js')
  const text = '[0,1000]<0,500,0>乐屿🎵<500,500,0>歌词\n[1000,500]下一句'
  const compressed = deflateSync(Buffer.from(text))
  const key = Buffer.from([0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
    0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69])
  const encrypted = compressed.map((value, index) => value ^ key[index % key.length])
  const payload = Buffer.concat([Buffer.from('krc1'), encrypted]).toString('base64')
  const result = await decodeLyric(payload)
  assert.equal(typeof result, 'string')
  assert.equal(result, text)
})

test('pako 3 Kuwo plain and encrypted lyric payloads keep their text contract', async () => {
  const { default: decode } = await load('src/musicSdk/kw/decodeLyric.js')
  const text = '[00:01.000]乐屿 & 歌词 🎵\n[00:02.000]下一句'
  const header = Buffer.from('tp=content\r\n\r\n')
  assert.equal(await decode({ lrcBuffer: Buffer.concat([header, deflateSync(text)]),
    isGetLyricx: false }), text)
  const key = Buffer.from('yeelion')
  const encrypted = Buffer.from(text).map((value, index) => value ^ key[index % key.length])
  assert.equal(await decode({
    lrcBuffer: Buffer.concat([header, deflateSync(encrypted.toString('base64'))]),
    isGetLyricx: true,
  }), text)
})

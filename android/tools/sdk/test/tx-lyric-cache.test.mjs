import assert from 'node:assert/strict'
import path from 'node:path'
import vm from 'node:vm'
import { createRequire } from 'node:module'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

async function lyricWithInfo(getInfo) {
  const maps = []
  class ObservedMap extends Map {
    constructor(...args) { super(...args); maps.push(this) }
  }
  const built = await build({
    entryPoints: [path.join(root, 'src/musicSdk/tx/lyric.js')], bundle: true,
    format: 'iife', globalName: 'fixtureLyric', platform: 'node', write: false, logLevel: 'silent',
    plugins: [{ name: 'fixture-music-info', setup(builder) {
      builder.onResolve({ filter: /^\.\/musicInfo$/ }, () => ({ path: 'music-info', namespace: 'fixture' }))
      builder.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: 'export default id => globalThis.fixtureGetInfo(id)' }))
    } }],
  })
  const sandbox = { Map: ObservedMap, Buffer, fixtureGetInfo: getInfo, require: createRequire(import.meta.url) }
  vm.runInNewContext(built.outputFiles[0].text, sandbox)
  return { api: sandbox.fixtureLyric.default,
    retainedPromises: () => maps.flatMap(map => [...map.keys(), ...map.values()])
      .filter(value => value && typeof value.then === 'function').length }
}

test('QQ song ID success caches only values and releases every request Promise', async () => {
  let calls = 0
  const { api, retainedPromises } = await lyricWithInfo(async id => { calls++; return { songId: id } })
  for (let i = 0; i < 50; i++) assert.equal(await api.getSongId({ songmid: `fixture-${i}` }), `fixture-${i}`)
  assert.equal(retainedPromises(), 0)
  assert.equal(await api.getSongId({ songmid: 'fixture-0' }), 'fixture-0')
  assert.equal(await api.getSongId({ songmid: 'unused', songId: 'provided' }), 'provided')
  assert.equal(calls, 50)
})

test('QQ song ID failure releases the Promise and the same song can retry', async () => {
  let calls = 0
  const { api, retainedPromises } = await lyricWithInfo(async () => {
    if (++calls === 1) throw new Error('fixture lookup failed')
    return { songId: 'retried' }
  })
  await assert.rejects(api.getSongId({ songmid: 'fixture' }), /fixture lookup failed/)
  assert.equal(retainedPromises(), 0)
  assert.equal(await api.getSongId({ songmid: 'fixture' }), 'retried')
  assert.equal(calls, 2)
  assert.equal(retainedPromises(), 0)
})

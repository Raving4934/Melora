import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'

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

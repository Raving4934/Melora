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

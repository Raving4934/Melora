import assert from 'node:assert/strict'
import test from 'node:test'
import { requestByDataChannel, setDataChannel } from '../src/musicSdk/channel.js'

test('web preference does not eagerly call app channel', async () => {
  const calls = []
  setDataChannel('web')
  const result = await requestByDataChannel(
    () => { calls.push('app'); return 'app' },
    () => { calls.push('web'); return 'web' },
  )
  assert.equal(result, 'web')
  assert.deepEqual(calls, ['web'])
})

test('preferred failure invokes exactly one fallback', async () => {
  const calls = []
  setDataChannel('app')
  const result = await requestByDataChannel(
    () => { calls.push('app'); throw new Error('offline') },
    () => { calls.push('web'); return 'web' },
  )
  assert.equal(result, 'web')
  assert.deepEqual(calls, ['app', 'web'])
})

test('invalid preference normalizes to automatic app-first mode', async () => {
  const calls = []
  setDataChannel('invalid')
  await requestByDataChannel(
    () => { calls.push('app'); return 'app' },
    () => { calls.push('web'); return 'web' },
  )
  assert.deepEqual(calls, ['app'])
})

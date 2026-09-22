import assert from 'node:assert/strict'
import test from 'node:test'
import { requestWithFallback } from '../src/musicSdk/requestWithFallback.js'

test('primary success never requests fallback', async () => {
  const calls = []
  const response = { list: ['fixture'] }
  const result = await requestWithFallback(
    () => { calls.push('primary'); return response },
    () => { calls.push('fallback'); throw new Error('must not run') },
  )
  assert.equal(result, response)
  assert.deepEqual(calls, ['primary'])
})

test('synchronous failure invokes exactly one fallback', async () => {
  const calls = []
  const result = await requestWithFallback(
    () => { calls.push('primary'); throw new Error('offline') },
    () => { calls.push('fallback'); return 'recovered' },
  )
  assert.equal(result, 'recovered')
  assert.deepEqual(calls, ['primary', 'fallback'])
})

test('pending primary does not eagerly start fallback', async () => {
  let reject
  const pending = new Promise((_, fail) => { reject = fail })
  let calls = 0
  const result = requestWithFallback(() => pending, () => { calls++; return 'recovered' })
  await Promise.resolve()
  assert.equal(calls, 0)
  reject(new Error('timeout'))
  assert.equal(await result, 'recovered')
  assert.equal(calls, 1)
})

test('both failures propagate fallback error without infinite retries', async () => {
  const finalError = new Error('unavailable')
  let calls = 0
  await assert.rejects(requestWithFallback(
    () => { calls++; return Promise.reject(new Error('offline')) },
    () => { calls++; throw finalError },
  ), error => error === finalError)
  assert.equal(calls, 2)
})

test('one request failure cannot change the next request preference', async () => {
  await requestWithFallback(() => { throw new Error('offline') }, () => 'fallback')
  assert.equal(await requestWithFallback(() => 'primary', () => 'fallback'), 'primary')
})

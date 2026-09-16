import { beforeEach, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('./api', async (original) => ({
  ...(await original<object>()),
  api: mocks.api,
}))

import { APIError } from './api'
import { activateDataIdentity } from './data-identity'
import {
  authorizePlayOutcomeDelivery,
  flushPendingPlayOutcomes,
  pendingPlayOutcomesForTest,
  pausePlayOutcomeDelivery,
  queuePlayOutcome,
} from './play-outcome'

const generationA = 'a'.repeat(32)
const generationB = 'b'.repeat(32)
const outcome = (trackId: string, playedMs: number, completed = false) => ({
  dataGeneration: generationA,
  trackId,
  playedMs,
  completed,
})

beforeEach(() => {
  localStorage.clear()
  activateDataIdentity({ generation: generationA, resetLegacy: true })
  authorizePlayOutcomeDelivery(generationA)
  mocks.api.mockReset().mockResolvedValue({ ok: true })
})

it('离线队列校验输入并限制为最近 32 条', () => {
  expect(queuePlayOutcome(outcome('', 2_000))).toBeNull()
  expect(queuePlayOutcome(outcome('demo:one', 999))).toBeNull()
  for (let index = 0; index < 40; index++) {
    queuePlayOutcome(outcome(`demo:${index}`, 1_000 + index))
  }
  const queue = pendingPlayOutcomesForTest()
  expect(queue).toHaveLength(32)
  expect(queue[0]?.trackId).toBe('demo:8')
  expect(queue.at(-1)?.trackId).toBe('demo:39')
  expect(queue.every((item) => item.dataGeneration === generationA)).toBe(true)
  expect(queue.every((item) => /^[A-Za-z0-9_-]{16,80}$/.test(item.eventId))).toBe(true)
})

it('发送成功后删除，网络/鉴权/历史乱序故障保留待重试', async () => {
  queuePlayOutcome(outcome('demo:network', 2_000))
  mocks.api.mockRejectedValueOnce(new APIError('offline', 0, 'NETWORK_ERROR'))
  await flushPendingPlayOutcomes()
  expect(pendingPlayOutcomesForTest()).toHaveLength(1)

  mocks.api.mockRejectedValueOnce(new APIError('history pending', 409, 'history_not_ready'))
  await flushPendingPlayOutcomes()
  expect(pendingPlayOutcomesForTest()).toHaveLength(1)

  await flushPendingPlayOutcomes()
  expect(pendingPlayOutcomesForTest()).toHaveLength(0)
  expect(mocks.api).toHaveBeenLastCalledWith(
    '/library/history/demo%3Anetwork/outcome',
    expect.objectContaining({
      method: 'POST',
      signal: expect.any(AbortSignal),
      body: expect.any(String),
    }),
  )
  expect(JSON.parse(mocks.api.mock.calls.at(-1)?.[1]?.body)).toEqual(
    expect.objectContaining({
      dataGeneration: generationA,
      playedMs: 2_000,
      completed: false,
      eventId: expect.any(String),
    }),
  )
})

it('服务器明确拒绝的损坏或跨代际事件不无限重放', async () => {
  queuePlayOutcome(outcome('demo:bad', 2_000))
  mocks.api.mockRejectedValueOnce(new APIError('identity changed', 409, 'data_identity_changed'))
  await flushPendingPlayOutcomes()
  expect(pendingPlayOutcomesForTest()).toHaveLength(0)
})

it('历史清空边界会中止发送并丢弃边界内的旧 outcome', async () => {
  queuePlayOutcome(outcome('demo:clear', 2_000))
  const resume = pausePlayOutcomeDelivery()
  resume(true)
  await flushPendingPlayOutcomes()
  expect(pendingPlayOutcomesForTest()).toHaveLength(0)
  expect(mocks.api).not.toHaveBeenCalled()
})

it('代际切换会中止旧 flush，迟到会话不能写入新代际队列', async () => {
  queuePlayOutcome(outcome('demo:old', 2_000))
  let aborted = false
  mocks.api.mockImplementationOnce(
    (_path: string, init: RequestInit) =>
      new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => {
          aborted = true
          reject(new DOMException('aborted', 'AbortError'))
        })
      }),
  )
  const oldFlush = flushPendingPlayOutcomes()
  await vi.waitFor(() => expect(mocks.api).toHaveBeenCalledOnce())

  activateDataIdentity({ generation: generationB, resetLegacy: true })
  authorizePlayOutcomeDelivery(generationB)
  await oldFlush
  expect(aborted).toBe(true)
  expect(queuePlayOutcome(outcome('demo:late', 2_000))).toBeNull()
  expect(pendingPlayOutcomesForTest()).toHaveLength(0)
})

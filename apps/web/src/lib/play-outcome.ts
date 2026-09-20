import { api, APIError, idPath } from './api'
import {
  CLIENT_DATA_KEYS,
  currentDataGeneration,
  readClientData,
  removeClientData,
  writeClientData,
} from './data-identity'

const version = 2
const maxItems = 32
const maxAgeMs = 30 * 24 * 60 * 60 * 1000

type PendingPlayOutcome = {
  eventId: string
  dataGeneration: string
  trackId: string
  playedMs: number
  completed: boolean
  createdAt: number
}

type PlayOutcomeInput = Omit<PendingPlayOutcome, 'eventId' | 'createdAt'>

let authorizedGeneration: string | null = null
let deliveryPauseDepth = 0
let activeDelivery: AbortController | null = null
let flushing: Promise<void> | null = null

function validGeneration(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{32}$/.test(value)
}

function validIdentifier(value: unknown, max = 200): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= max &&
    !/[\u0000-\u001f\u007f]/.test(value)
  )
}

function validEventId(value: unknown): value is string {
  return (
    typeof value === 'string' && value.length >= 16 && value.length <= 80 && /^[A-Za-z0-9_-]+$/.test(value)
  )
}

function validItem(value: unknown, generation: string): value is PendingPlayOutcome {
  if (!value || typeof value !== 'object') return false
  const item = value as Partial<PendingPlayOutcome>
  return (
    validEventId(item.eventId) &&
    item.dataGeneration === generation &&
    validIdentifier(item.trackId) &&
    typeof item.playedMs === 'number' &&
    Number.isSafeInteger(item.playedMs) &&
    item.playedMs >= 0 &&
    item.playedMs <= 6 * 60 * 60 * 1000 &&
    typeof item.completed === 'boolean' &&
    (item.completed || item.playedMs >= 1000) &&
    typeof item.createdAt === 'number' &&
    Number.isSafeInteger(item.createdAt) &&
    item.createdAt > 0
  )
}

function readQueue(generation = currentDataGeneration(), now = Date.now()): PendingPlayOutcome[] {
  if (!validGeneration(generation) || currentDataGeneration() !== generation) return []
  try {
    const raw = readClientData(CLIENT_DATA_KEYS.playOutcomes)
    if (!raw || raw.length > 16 * 1024) return []
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || (parsed as { version?: unknown }).version !== version)
      return []
    const items = (parsed as { items?: unknown }).items
    if (!Array.isArray(items)) return []
    const seen = new Set<string>()
    return items
      .filter((item) => validItem(item, generation))
      .filter((item) => now - item.createdAt <= maxAgeMs && !seen.has(item.eventId) && seen.add(item.eventId))
      .slice(-maxItems)
  } catch {
    return []
  }
}

function writeQueue(generation: string, items: PendingPlayOutcome[]) {
  if (currentDataGeneration() !== generation) return
  try {
    if (!items.length) {
      removeClientData(CLIENT_DATA_KEYS.playOutcomes)
      return
    }
    writeClientData(CLIENT_DATA_KEYS.playOutcomes, JSON.stringify({ version, items: items.slice(-maxItems) }))
  } catch {
    // 禁用或已满的 localStorage 只影响推荐反馈，不影响播放。
  }
}

function createEventId() {
  try {
    if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    return [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('')
  } catch {
    return `${Date.now().toString(36)}_${Math.random().toString(36).slice(2).padEnd(16, '0')}`.slice(0, 80)
  }
}

export function queuePlayOutcome(input: PlayOutcomeInput): PendingPlayOutcome | null {
  const playedMs = Math.round(input.playedMs)
  if (
    !validGeneration(input.dataGeneration) ||
    currentDataGeneration() !== input.dataGeneration ||
    !validIdentifier(input.trackId) ||
    !Number.isSafeInteger(playedMs) ||
    playedMs < 0 ||
    (!input.completed && playedMs < 1000)
  )
    return null
  const item: PendingPlayOutcome = {
    eventId: createEventId(),
    dataGeneration: input.dataGeneration,
    trackId: input.trackId,
    playedMs: Math.min(playedMs, 6 * 60 * 60 * 1000),
    completed: input.completed,
    createdAt: Date.now(),
  }
  const items = readQueue(input.dataGeneration).filter((current) => current.eventId !== item.eventId)
  items.push(item)
  writeQueue(input.dataGeneration, items)
  return item
}

function removeOutcome(generation: string, eventId: string) {
  writeQueue(
    generation,
    readQueue(generation).filter((item) => item.eventId !== eventId),
  )
}

async function deliver(item: PendingPlayOutcome, generation: string, signal: AbortSignal) {
  if (
    signal.aborted ||
    deliveryPauseDepth > 0 ||
    authorizedGeneration !== generation ||
    currentDataGeneration() !== generation
  )
    return
  try {
    await api(`/library/history/${idPath(item.trackId)}/outcome`, {
      method: 'POST',
      body: JSON.stringify({
        eventId: item.eventId,
        dataGeneration: item.dataGeneration,
        playedMs: item.playedMs,
        completed: item.completed,
      }),
      signal,
    })
    if (authorizedGeneration === generation && currentDataGeneration() === generation)
      removeOutcome(generation, item.eventId)
  } catch (error) {
    if (signal.aborted || authorizedGeneration !== generation || currentDataGeneration() !== generation)
      return
    // history 与网络/鉴权问题可重试；其它明确的 4xx 表示事件本身无效，继续重放没有意义。
    const retryable =
      error instanceof APIError &&
      (error.status === 0 ||
        [401, 403, 429].includes(error.status) ||
        (error.status === 409 && error.code === 'history_not_ready'))
    if (error instanceof APIError && error.status >= 400 && error.status < 500 && !retryable)
      removeOutcome(generation, item.eventId)
  }
}

export function authorizePlayOutcomeDelivery(generation: string | null) {
  const next = validGeneration(generation) && currentDataGeneration() === generation ? generation : null
  if (authorizedGeneration === next) return
  authorizedGeneration = next
  activeDelivery?.abort()
  activeDelivery = null
  flushing = null
}

export function pausePlayOutcomeDelivery() {
  const generation = authorizedGeneration
  deliveryPauseDepth++
  activeDelivery?.abort()
  activeDelivery = null
  flushing = null
  let released = false
  return (discard = false) => {
    if (released) return
    released = true
    if (discard && generation && currentDataGeneration() === generation) writeQueue(generation, [])
    deliveryPauseDepth = Math.max(0, deliveryPauseDepth - 1)
    if (deliveryPauseDepth === 0) void flushPendingPlayOutcomes()
  }
}

export function flushPendingPlayOutcomes() {
  const generation = authorizedGeneration
  if (flushing || deliveryPauseDepth > 0 || !generation || currentDataGeneration() !== generation)
    return flushing ?? Promise.resolve()
  const controller = new AbortController()
  activeDelivery = controller
  let task: Promise<void>
  task = (async () => {
    for (const item of readQueue(generation)) {
      if (
        controller.signal.aborted ||
        deliveryPauseDepth > 0 ||
        authorizedGeneration !== generation ||
        currentDataGeneration() !== generation
      )
        break
      await deliver(item, generation, controller.signal)
    }
  })().finally(() => {
    if (flushing === task) flushing = null
    if (activeDelivery === controller) activeDelivery = null
  })
  flushing = task
  return task
}

export function pendingPlayOutcomesForTest() {
  return readQueue()
}

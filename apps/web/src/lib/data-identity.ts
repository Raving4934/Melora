// 只隔离本应用浏览器状态，不参与服务端数据删除或鉴权。
export interface DataIdentity {
  generation: string
  resetLegacy: boolean
}
export const CLIENT_DATA_KEYS = {
  queue: 'melora:player-session:v1:queue',
  playback: 'melora:player-session:v1:playback',
  playOutcomes: 'melora:play-outcomes:v1',
  lyric: 'melora:lyric-font:v1',
} as const
export type ClientDataKey = (typeof CLIENT_DATA_KEYS)[keyof typeof CLIENT_DATA_KEYS]
export const DATA_IDENTITY_KEY = 'melora:client-data-identity:v1'
const limits: Record<ClientDataKey, number> = {
  [CLIENT_DATA_KEYS.queue]: 512 * 1024,
  [CLIENT_DATA_KEYS.playback]: 4096,
  [CLIENT_DATA_KEYS.playOutcomes]: 32 * 1024,
  [CLIENT_DATA_KEYS.lyric]: 64,
}
let initialized = false
let generation: string | null = null
let legacyFallback = false

export function validDataIdentity(value: unknown): value is DataIdentity {
  if (!value || typeof value !== 'object') return false
  const data = value as Partial<DataIdentity>
  return (
    typeof data.generation === 'string' &&
    /^[a-f0-9]{32}$/.test(data.generation) &&
    typeof data.resetLegacy === 'boolean'
  )
}
function readRaw(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}
function writeRaw(key: string, value: string): boolean {
  try {
    window.localStorage.setItem(key, value)
    return true
  } catch {
    return false
  }
}
function removeRaw(key: string) {
  try {
    window.localStorage.removeItem(key)
    return true
  } catch {
    return false
  }
}
function initialize() {
  if (initialized) return
  initialized = true
  const raw = readRaw(DATA_IDENTITY_KEY)
  if (!raw || raw.length > 256) return
  try {
    const saved = JSON.parse(raw)
    if (
      saved?.version === 1 &&
      typeof saved.generation === 'string' &&
      /^[a-f0-9]{32}$/.test(saved.generation) &&
      typeof saved.legacyFallback === 'boolean'
    ) {
      generation = saved.generation
      legacyFallback = saved.legacyFallback
    }
  } catch {
    /* 损坏的标记不成为已验证的数据代际。 */
  }
}
function keyFor(key: ClientDataKey, id: string | null) {
  return id ? `${key}:data:${id}` : key
}
export function clientDataStorageKey(key: ClientDataKey) {
  initialize()
  return keyFor(key, generation)
}
export function currentDataGeneration() {
  initialize()
  return generation
}
export function readClientData(key: ClientDataKey) {
  const scoped = clientDataStorageKey(key)
  const raw = window.localStorage.getItem(scoped)
  return raw === null && generation && legacyFallback ? window.localStorage.getItem(key) : raw
}
export function writeClientData(key: ClientDataKey, value: string) {
  const scoped = clientDataStorageKey(key)
  const ok = writeRaw(scoped, value)
  if (ok && generation && legacyFallback) removeRaw(key)
  return ok
}
export function removeClientData(key: ClientDataKey) {
  const removed = removeRaw(clientDataStorageKey(key))
  return generation && legacyFallback ? removeRaw(key) && removed : removed
}
function migrateLegacy(id: string) {
  let complete = true
  for (const key of Object.keys(limits) as ClientDataKey[]) {
    const target = keyFor(key, id)
    const old = readRaw(key)
    if (old === null) continue
    // 只搬运已知应用键；损坏/超限旧值由原有解析器丢弃，不搬其它应用存储。
    if (old.length * 2 > limits[key]) {
      removeRaw(key)
      continue
    }
    if (readRaw(target) !== null || writeRaw(target, old)) removeRaw(key)
    else complete = false // 配额/权限失败保留旧值，并持久化只用于这次升级的回退。
  }
  return !complete
}
/** 仅在认证后的session返回可信身份后调用。旧服务未返回字段时保持旧行为。 */
export function activateDataIdentity(identity: unknown): boolean {
  if (!validDataIdentity(identity)) return false
  initialize()
  const previous = generation
  const changed = previous !== identity.generation
  const revokeFallback = legacyFallback && identity.resetLegacy
  if (!changed && !legacyFallback) return false
  if (changed) {
    legacyFallback = previous === null && !identity.resetLegacy
    generation = identity.generation
  }
  // 存储marker不是权威：新库明确禁止旧值回退时，必须先撤销再迁移。
  if (identity.resetLegacy) legacyFallback = false
  if (legacyFallback) legacyFallback = migrateLegacy(identity.generation)
  if (changed && previous !== null) {
    for (const key of Object.keys(limits) as ClientDataKey[]) removeRaw(keyFor(key, previous))
  }
  if (identity.resetLegacy) {
    legacyFallback = false
    for (const key of Object.keys(limits) as ClientDataKey[]) removeRaw(key)
  }
  writeRaw(DATA_IDENTITY_KEY, JSON.stringify({ version: 1, generation, legacyFallback }))
  return changed || revokeFallback
}

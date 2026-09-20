import {
  CLIENT_DATA_KEYS,
  readClientData,
  writeClientData,
  removeClientData,
  type ClientDataKey,
} from './data-identity'
import type { Track } from './types'

// 与服务器 /library/history 无关：仅保存此浏览器设备上的待播会话。
export const PLAYER_SESSION_KEYS = {
  queue: CLIENT_DATA_KEYS.queue,
  playback: CLIENT_DATA_KEYS.playback,
} as const
export const PLAYER_SESSION_QUEUE_LIMIT = 200
export const PLAYER_SESSION_MAX_BYTES = 512 * 1024
export const PLAYER_SESSION_PROGRESS_INTERVAL = 5_000
const version = 1
const maxDuration = 7 * 24 * 60 * 60
const qualities = new Set(['standard', '128k', '320k', 'flac', 'flac24bit', 'ape', 'wav'])

export interface PlayerSession {
  track: Track | null
  queue: Track[]
  position: number
  duration: number
  volume: number
  muted: boolean
  volumeBeforeMute: number
  playbackRate: number
  quality: string
  mode: 'list' | 'single' | 'shuffle'
  historyRecorded: boolean
}
function defaults(): PlayerSession {
  return {
    track: null,
    queue: [],
    position: 0,
    duration: 0,
    volume: 0.7,
    muted: false,
    volumeBeforeMute: 0.7,
    playbackRate: 1,
    quality: 'standard',
    mode: 'list',
    historyRecorded: false,
  }
}
function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}
function number(value: unknown, min: number, max: number, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max
    ? value
    : fallback
}
function text(value: unknown, limit: number): value is string {
  return typeof value === 'string' && value.length <= limit && !/[\x00-\x1f\x7f]/.test(value)
}
function identifier(value: unknown, limit = 200): value is string {
  return (
    text(value, limit) &&
    /^[a-z0-9][a-z0-9_.:-]*$/i.test(value) &&
    !['undefined', 'null', 'NaN'].includes(value)
  )
}
function safeCover(value: unknown): string {
  if (!text(value, 1024) || !value || /[\s\\?#]/.test(value)) return ''
  // 本地只允许静态封面，不缓存 API/代理路径。远端拒绝所有 query/hash，避免签名落盘。
  if (/^\/covers\/[a-z0-9][a-z0-9/_.-]*\.(svg|png|jpe?g|webp|avif)$/i.test(value))
    return value.includes('..') ? '' : value
  try {
    const url = new URL(value)
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.port ||
      !/\.(svg|png|jpe?g|webp|avif|gif)$/i.test(url.pathname) ||
      /(?:token|signature|credential|authorization)/i.test(url.pathname)
    )
      return ''
    const host = url.hostname.toLowerCase().replace(/\.$/, '')
    // 封面缓存宁可丢弃，也不恢复本机、内网或 IPv6 字面量。
    if (
      !host.includes('.') ||
      host.includes(':') ||
      /\.(localhost|local|internal|lan)$/.test(host) ||
      host === 'localhost'
    )
      return ''
    const ip = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(host)
    if (ip) {
      const a = Number(ip[1])
      const b = Number(ip[2])
      if (
        a === 0 ||
        a === 10 ||
        a === 127 ||
        a >= 224 ||
        (a === 169 && b === 254) ||
        (a === 172 && b >= 16 && b <= 31) ||
        (a === 192 && b === 168) ||
        (a === 100 && b >= 64 && b <= 127)
      )
        return ''
    }
    return url.href
  } catch {
    return ''
  }
}
/** 读和写共用字段白名单，绝不展开 Track/PlayInfo 等外部对象。 */
export function sanitizeSessionTrack(value: unknown): Track | null {
  if (
    !record(value) ||
    !identifier(value.id) ||
    !identifier(value.providerId, 100) ||
    !text(value.title, 512) ||
    !value.title.trim() ||
    !text(value.artist, 512) ||
    !text(value.album, 512)
  )
    return null
  return {
    id: value.id,
    providerId: value.providerId,
    title: value.title,
    artist: value.artist,
    album: value.album,
    duration: number(value.duration, 0, maxDuration, 0),
    coverUrl: safeCover(value.coverUrl),
    qualities: Array.isArray(value.qualities)
      ? [
          ...new Set(
            value.qualities
              .slice(0, 16)
              .filter((q): q is string => typeof q === 'string' && qualities.has(q)),
          ),
        ]
      : ['standard'],
    canDownload: value.canDownload === true,
  }
}
function metadata(value: { track?: unknown; queue?: unknown }) {
  const track = sanitizeSessionTrack(value.track)
  const queue = new Map<string, Track>()
  if (Array.isArray(value.queue)) {
    for (const item of value.queue.slice(0, PLAYER_SESSION_QUEUE_LIMIT)) {
      const safe = sanitizeSessionTrack(item)
      if (safe) queue.set(safe.id, safe)
    }
  }
  if (track) {
    if (!queue.has(track.id) && queue.size >= PLAYER_SESSION_QUEUE_LIMIT)
      queue.delete([...queue.keys()].at(-1)!)
    queue.set(track.id, track)
  }
  return { track, queue: [...queue.values()] }
}
// 包括 window/localStorage 属性 getter 在内的全部存储访问都留在安全边界内。
function read(key: ClientDataKey, limit: number): Record<string, unknown> | null {
  try {
    const raw = readClientData(key)
    if (!raw) return null
    if (raw.length * 2 > limit) {
      remove(key)
      return null
    }
    const value: unknown = JSON.parse(raw)
    if (record(value) && value.version === version) return value
  } catch {
    // SSR、隐私模式、损坏旧缓存都降级为默认会话。
  }
  remove(key)
  return null
}
function remove(key: ClientDataKey) {
  try {
    removeClientData(key)
  } catch {
    // 禁用存储时仍允许当前内存会话工作。
  }
}
function write(key: ClientDataKey, value: unknown, limit: number): boolean {
  try {
    const raw = JSON.stringify(value)
    if (raw.length * 2 > limit) return false
    return writeClientData(key, raw)
  } catch {
    return false
  }
}
export function readPlayerSession(): PlayerSession {
  const result = defaults()
  const savedQueue = read(PLAYER_SESSION_KEYS.queue, PLAYER_SESSION_MAX_BYTES)
  const saved = read(PLAYER_SESSION_KEYS.playback, 4096)
  if (savedQueue) Object.assign(result, metadata(savedQueue))
  if (!saved) {
    result.duration = result.track?.duration ?? 0
    return result
  }
  result.volume = number(saved.volume, 0, 1, result.volume)
  result.muted = saved.muted === true || result.volume === 0
  if (result.muted) result.volume = 0
  result.volumeBeforeMute = number(saved.volumeBeforeMute, Number.MIN_VALUE, 1, result.volume || 0.7)
  result.playbackRate = number(saved.playbackRate, 0.5, 2, 1)
  if (typeof saved.quality === 'string' && qualities.has(saved.quality)) result.quality = saved.quality
  if (saved.mode === 'list' || saved.mode === 'single' || saved.mode === 'shuffle') result.mode = saved.mode
  const sameTrack = !!result.track && saved.trackId === result.track.id
  result.duration = sameTrack
    ? number(saved.duration, 0, maxDuration, result.track?.duration ?? 0)
    : (result.track?.duration ?? 0)
  result.position = sameTrack ? number(saved.position, 0, result.duration || maxDuration, 0) : 0
  result.historyRecorded = sameTrack && saved.historyRecorded === true
  return result
}
function playback(state: PlayerSession) {
  const volume = number(state.volume, 0, 1, 0.7)
  const duration = number(state.duration, 0, maxDuration, 0)
  return {
    version,
    trackId: identifier(state.track?.id) ? state.track.id : null,
    position: number(state.position, 0, duration || maxDuration, 0),
    duration,
    volume,
    muted: state.muted === true || volume === 0,
    volumeBeforeMute: number(state.volumeBeforeMute, Number.MIN_VALUE, 1, volume || 0.7),
    playbackRate: number(state.playbackRate, 0.5, 2, 1),
    quality: qualities.has(state.quality) ? state.quality : 'standard',
    mode: ['list', 'single', 'shuffle'].includes(state.mode) ? state.mode : 'list',
    historyRecorded: !!state.track && state.historyRecorded === true,
  }
}
/** 队列仅结构变更时序列化；timeupdate 最多每 5 秒写一份小快照。 */
export function createPlayerSessionPersistence(getState: () => PlayerSession) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let dirty = false
  let queueDirty = false
  let suspended = 0
  function flush() {
    if (suspended) return
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
    const state = getState()
    if (queueDirty) {
      // 按 UTF-16 字节预算保留当前曲目；逐项计长，避免极长队列反复整体序列化。
      const safe = metadata(state)
      const lengths = safe.queue.map((track) => JSON.stringify(track).length)
      let length =
        JSON.stringify({ version, track: safe.track, queue: [] }).length +
        lengths.reduce((sum, length) => sum + length, 0) +
        Math.max(0, safe.queue.length - 1)
      while (length * 2 > PLAYER_SESSION_MAX_BYTES && safe.queue.length) {
        let index = safe.queue.length - 1
        if (safe.queue[index]?.id === safe.track?.id) index--
        if (index < 0) break
        length -= lengths.splice(index, 1)[0]! + (safe.queue.length > 1 ? 1 : 0)
        safe.queue.splice(index, 1)
      }
      queueDirty = !write(PLAYER_SESSION_KEYS.queue, { version, ...safe }, PLAYER_SESSION_MAX_BYTES)
    }
    if (dirty) dirty = !write(PLAYER_SESSION_KEYS.playback, playback(state), 4096)
  }
  function sync(state: PlayerSession, previous: PlayerSession) {
    if (suspended) return
    const changedQueue = state.queue !== previous.queue || state.track !== previous.track
    queueDirty ||= changedQueue
    const changedSettings =
      state.volume !== previous.volume ||
      state.muted !== previous.muted ||
      state.volumeBeforeMute !== previous.volumeBeforeMute ||
      state.playbackRate !== previous.playbackRate ||
      state.quality !== previous.quality ||
      state.mode !== previous.mode ||
      state.historyRecorded !== previous.historyRecorded
    if (changedQueue || changedSettings) {
      dirty = true
      flush()
    } else if (state.position !== previous.position || state.duration !== previous.duration) {
      dirty = true
      if (timer === undefined) timer = setTimeout(flush, PLAYER_SESSION_PROGRESS_INTERVAL)
    }
  }
  function clear() {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
    dirty = false
    queueDirty = false
    if (suspended) return
    remove(PLAYER_SESSION_KEYS.queue)
    remove(PLAYER_SESSION_KEYS.playback)
  }
  function hidden() {
    if (document.visibilityState === 'hidden') flush()
  }
  // 不监听路由、组件卸载或 storage：页面切换/BFCache 不重置会话，也不与其它标签抢播放权。
  if (typeof window !== 'undefined') window.addEventListener('pagehide', flush)
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', hidden)
  return {
    sync,
    flush,
    clear,
    // 更换已验证服务端数据代际：废弃旧定时写入，重置内存时不擦掉新代际快照。
    withoutSaving<T>(callback: () => T): T {
      if (timer !== undefined) clearTimeout(timer)
      timer = undefined
      dirty = false
      queueDirty = false
      suspended++
      try {
        return callback()
      } finally {
        suspended--
      }
    },
    dispose() {
      flush()
      if (typeof window !== 'undefined') window.removeEventListener('pagehide', flush)
      if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', hidden)
    },
  }
}

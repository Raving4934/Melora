import {
  CLIENT_DATA_KEYS,
  clientDataStorageKey,
  readClientData,
  writeClientData,
  removeClientData,
} from './data-identity'
import { useSyncExternalStore } from 'react'

// 只存设备侧显示偏好，不进入播放器引擎或服务端 Settings 合同。
export const LYRIC_FONT_STORAGE_KEY = CLIENT_DATA_KEYS.lyric
export const lyricFontOptions = [
  { value: 'small', label: '小', scale: 0.8 },
  { value: 'standard', label: '标准', scale: 1 },
  { value: 'large', label: '大', scale: 1.2 },
  { value: 'extra-large', label: '特大', scale: 1.4 },
] as const
export type LyricFontSize = (typeof lyricFontOptions)[number]['value']
export type LyricFontPreference = Readonly<{ size: LyricFontSize; persisted: boolean }>
const defaultPreference: LyricFontPreference = { size: 'standard', persisted: true }
const listeners = new Set<() => void>()
let snapshot: LyricFontPreference | undefined

export function parseLyricFont(value: unknown): LyricFontSize {
  return lyricFontOptions.find((option) => option.value === value)?.value || 'standard'
}
export function lyricFontScale(size: LyricFontSize): number {
  return lyricFontOptions.find((option) => option.value === size)?.scale || 1
}
function readStored(fallback: LyricFontSize = 'standard'): LyricFontPreference {
  if (typeof window === 'undefined') return defaultPreference
  try {
    return { size: parseLyricFont(readClientData(LYRIC_FONT_STORAGE_KEY)), persisted: true }
  } catch {
    // 只捕获 Storage 同步权限错误；不拦截媒体 Promise 或全局异常。
    return { size: fallback, persisted: false }
  }
}
export function getLyricFontPreference(): LyricFontPreference {
  // CSR 首次渲染同步读取，不能先用标准字号绘制、再通过 effect 替换成已保存的字号。
  if (typeof window === 'undefined') return defaultPreference
  if (!snapshot) snapshot = readStored()
  else if (!listeners.size && snapshot.persisted) {
    // 关闭全部字号视图期间可能有其它标签页修改；重新打开前同步校准，仍不先画旧字号。
    const latest = readStored(snapshot.size)
    if (snapshot.size !== latest.size || snapshot.persisted !== latest.persisted) snapshot = latest
  }
  return snapshot
}
function publish(next: LyricFontPreference) {
  if (snapshot?.size === next.size && snapshot.persisted === next.persisted) return
  snapshot = next
  listeners.forEach((listener) => listener())
}
function onStorage(event: StorageEvent) {
  if (event.key !== clientDataStorageKey(LYRIC_FONT_STORAGE_KEY) && event.key !== null) return
  try {
    if (event.storageArea && event.storageArea !== window.localStorage) return
  } catch {
    return
  }
  publish(readStored(snapshot?.size))
}
export function subscribeLyricFont(listener: () => void) {
  if (!listeners.size && typeof window !== 'undefined') window.addEventListener('storage', onStorage)
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
    if (!listeners.size && typeof window !== 'undefined') window.removeEventListener('storage', onStorage)
  }
}
function save(size: LyricFontSize, reset = false) {
  let persisted = false
  try {
    persisted = reset
      ? removeClientData(LYRIC_FONT_STORAGE_KEY)
      : writeClientData(LYRIC_FONT_STORAGE_KEY, size)
  } catch {
    // 配额/隐私限制下保留本次会话的选择，并在设置面板明确提示未持久化。
  }
  publish({ size, persisted })
}
export function setLyricFont(value: unknown) {
  save(parseLyricFont(value))
}
export function resetLyricFont() {
  save('standard', true)
}
export function useLyricFontPreference() {
  return useSyncExternalStore(subscribeLyricFont, getLyricFontPreference, () => defaultPreference)
}

// 只更新当前设备的显示快照，不改服务器/旧数据代际的偏好。
export function reloadLyricFontPreference() {
  publish(readStored())
}

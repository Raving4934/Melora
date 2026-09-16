import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const key = 'melora:lyric-font:v1'
beforeEach(() => {
  vi.resetModules()
  localStorage.removeItem(key)
})
afterEach(() => vi.restoreAllMocks())

it.each([undefined, null, '', '140', 'NaN', 'large ', '"large"', '__proto__', { size: 'large' }, 120])(
  '非法字号缓存安全回到标准：%s',
  async (value) => {
    const font = await import('./lyric-font')
    expect(font.parseLyricFont(value)).toBe('standard')
  },
)
it('四档大小有限，标准是现有响应式字体的1倍', async () => {
  const font = await import('./lyric-font')
  expect(font.lyricFontOptions.map((option) => font.parseLyricFont(option.value))).toEqual([
    'small',
    'standard',
    'large',
    'extra-large',
  ])
  expect(font.lyricFontOptions.map((option) => font.lyricFontScale(option.value))).toEqual([0.8, 1, 1.2, 1.4])
})
it('第一次读取就得到设备缓存，未变化的snapshot引用稳定', async () => {
  localStorage.setItem(key, 'extra-large')
  const font = await import('./lyric-font')
  const first = font.getLyricFontPreference()
  expect(first).toEqual({ size: 'extra-large', persisted: true })
  expect(font.getLyricFontPreference()).toBe(first)
})
it('非法localStorage字符串不会成为任意CSS值', async () => {
  localStorage.setItem(key, 'calc(999999px)')
  const font = await import('./lyric-font')
  expect(font.getLyricFontPreference()).toEqual({ size: 'standard', persisted: true })
})
it('修改/重置只影响独立的版本化字号key', async () => {
  localStorage.setItem('lyric-test-unrelated', 'keep')
  const font = await import('./lyric-font')
  font.setLyricFont('large')
  expect(localStorage.getItem(key)).toBe('large')
  font.resetLyricFont()
  expect(font.getLyricFontPreference().size).toBe('standard')
  expect(localStorage.getItem(key)).toBeNull()
  expect(localStorage.getItem('lyric-test-unrelated')).toBe('keep')
  localStorage.removeItem('lyric-test-unrelated')
})
it('写入失败保留内存选择，不被仍可读的旧缓存反向覆盖', async () => {
  localStorage.setItem(key, 'small')
  const font = await import('./lyric-font')
  font.getLyricFontPreference()
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('quota', 'QuotaExceededError')
  })
  font.setLyricFont('extra-large')
  expect(font.getLyricFontPreference()).toEqual({ size: 'extra-large', persisted: false })
  expect(localStorage.getItem(key)).toBe('small')
})
it('读取权限异常回到默认且不抛出', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  const font = await import('./lyric-font')
  expect(font.getLyricFontPreference()).toEqual({ size: 'standard', persisted: false })
})
it('无法删除缓存时本次仍可恢复默认，并如实标记未持久化', async () => {
  localStorage.setItem(key, 'large')
  const font = await import('./lyric-font')
  font.getLyricFontPreference()
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  font.resetLyricFont()
  expect(font.getLyricFontPreference()).toEqual({ size: 'standard', persisted: false })
})
it('跨标签同步/clear安全，忽略其它key和sessionStorage，并清理订阅', async () => {
  const font = await import('./lyric-font')
  const listener = vi.fn()
  const stop = font.subscribeLyricFont(listener)
  try {
    font.getLyricFontPreference()
    localStorage.setItem(key, 'large')
    window.dispatchEvent(new StorageEvent('storage', { key, storageArea: localStorage }))
    expect(font.getLyricFontPreference().size).toBe('large')
    expect(listener).toHaveBeenCalledTimes(1)
    window.dispatchEvent(new StorageEvent('storage', { key: 'other', storageArea: localStorage }))
    window.dispatchEvent(new StorageEvent('storage', { key, storageArea: sessionStorage }))
    expect(listener).toHaveBeenCalledTimes(1)
    localStorage.removeItem(key)
    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))
    expect(font.getLyricFontPreference().size).toBe('standard')
    expect(listener).toHaveBeenCalledTimes(2)
  } finally {
    stop()
  }
  window.dispatchEvent(new StorageEvent('storage', { key, storageArea: localStorage }))
  expect(listener).toHaveBeenCalledTimes(2)
})
it('没有订阅者期间缓存被另一标签更新，重开前会同步读取', async () => {
  const font = await import('./lyric-font')
  expect(font.getLyricFontPreference().size).toBe('standard')
  localStorage.setItem(key, 'large')
  expect(font.getLyricFontPreference().size).toBe('large')
})

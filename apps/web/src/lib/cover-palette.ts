export type CoverPalette = Readonly<{
  base: string
  primary: string
  secondary: string
  accent: string
}>

type RGB = readonly [red: number, green: number, blue: number]
type HSL = readonly [hue: number, saturation: number, lightness: number]
type Bucket = { weight: number; red: number; green: number; blue: number; key: number }

export const COVER_PALETTE_SAMPLE_SIZE = 40
export const COVER_PALETTE_CACHE_LIMIT = 24
export const COVER_PALETTE_FAILURE_TTL_MS = 30_000
const MAX_PENDING = 4
const LOAD_TIMEOUT_MS = 8_000
const MAX_SAMPLES = COVER_PALETTE_SAMPLE_SIZE ** 2

// 首张封面也不可读时仍有温润的不透明底色，而不是透明/纯黑的画布。
export const DEFAULT_COVER_PALETTE: CoverPalette = Object.freeze({
  base: 'rgb(48 43 48)',
  primary: 'rgb(110 79 86)',
  secondary: 'rgb(66 86 101)',
  accent: 'rgb(125 105 91)',
})

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

function toHSL([red, green, blue]: RGB): HSL {
  const r = red / 255
  const g = green / 255
  const b = blue / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const delta = max - min
  const lightness = (max + min) / 2
  if (!delta) return [32, 0, lightness]
  const hue = max === r ? ((g - b) / delta) % 6 : max === g ? (b - r) / delta + 2 : (r - g) / delta + 4
  return [(hue * 60 + 360) % 360, delta / (1 - Math.abs(2 * lightness - 1)), lightness]
}

function fromHSL(hue: number, saturation: number, lightness: number): RGB {
  const a = saturation * Math.min(lightness, 1 - lightness)
  const channel = (offset: number) => {
    const k = (offset + hue / 30) % 12
    return Math.round(255 * (lightness - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))))
  }
  return [channel(0), channel(8), channel(4)]
}

function luminance(rgb: RGB): number {
  const linear = rgb.map((value) => {
    const c = value / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
}

function tone(color: RGB, role: keyof CoverPalette): string {
  const [hue, sourceSaturation, sourceLightness] = toHSL(color)
  const neutral = sourceSaturation < 0.06
  const saturation =
    (neutral ? 0.08 : clamp(sourceSaturation * 0.72, 0.16, 0.68)) * (role === 'base' ? 0.62 : 1)
  const lightness = {
    base: 0.18 + sourceLightness * 0.06,
    primary: 0.34 + sourceLightness * 0.1,
    secondary: 0.3 + sourceLightness * 0.08,
    accent: 0.39 + sourceLightness * 0.08,
  }[role]
  const targetHue = neutral ? 32 : hue
  const ceiling = role === 'base' ? 0.075 : 0.145
  let rgb = fromHSL(targetHue, saturation, lightness)
  // 黄/绿/白封面也不能抬高白字背后的亮度；仅压亮度，不替换真实色相。
  if (luminance(rgb) > ceiling) {
    let low = 0
    let high = lightness
    for (let step = 0; step < 12; step++) {
      const middle = (low + high) / 2
      const candidate = fromHSL(targetHue, saturation, middle)
      if (luminance(candidate) <= ceiling) {
        low = middle
        rgb = candidate
      } else high = middle
    }
  }
  return `rgb(${rgb.join(' ')})`
}

/** 确定性的加权 4-bit RGB 量化；透明像素不染色，输入再大也最多采样 1600 点。 */
export function extractCoverPalette(pixels: Uint8ClampedArray): CoverPalette | null {
  const count = Math.floor(pixels.length / 4)
  if (!count) return null
  const samples = Math.min(count, MAX_SAMPLES)
  const buckets = new Map<number, Bucket>()
  for (let sample = 0; sample < samples; sample++) {
    const offset = Math.floor((sample * count) / samples) * 4
    const alpha = pixels[offset + 3] / 255
    if (alpha < 0.08) continue
    const red = pixels[offset]
    const green = pixels[offset + 1]
    const blue = pixels[offset + 2]
    const max = Math.max(red, green, blue)
    const min = Math.min(red, green, blue)
    // 面积为主，温和降低黑白边框的权重，避免一粒高饱和噪声夺走主色。
    const weight = alpha * (0.65 + ((max - min) / 255) * 0.35)
    const key = ((red >> 4) << 8) | ((green >> 4) << 4) | (blue >> 4)
    const bucket = buckets.get(key) ?? { weight: 0, red: 0, green: 0, blue: 0, key }
    bucket.weight += weight
    bucket.red += red * weight
    bucket.green += green * weight
    bucket.blue += blue * weight
    buckets.set(key, bucket)
  }
  const ranked = [...buckets.values()].sort((a, b) => b.weight - a.weight || a.key - b.key)
  if (!ranked.length) return null
  const colorOf = (bucket: Bucket): RGB => [
    bucket.red / bucket.weight,
    bucket.green / bucket.weight,
    bucket.blue / bucket.weight,
  ]
  const chosen: RGB[] = [colorOf(ranked[0])]
  const candidates = ranked.filter((bucket) => bucket.weight >= ranked[0].weight * 0.08)
  for (let index = 1; index < 3; index++) {
    let best: RGB | undefined
    let bestScore = 0
    for (const bucket of candidates) {
      const color = colorOf(bucket)
      const distance = Math.min(
        ...chosen.map((previous) => color.reduce((sum, channel, i) => sum + (channel - previous[i]) ** 2, 0)),
      )
      if (distance < 24 ** 2) continue
      const score = bucket.weight * Math.sqrt(distance)
      if (score > bestScore) {
        best = color
        bestScore = score
      }
    }
    chosen.push(best ?? chosen[0])
  }
  return Object.freeze({
    base: tone(chosen[0], 'base'),
    primary: tone(chosen[0], 'primary'),
    secondary: tone(chosen[1], 'secondary'),
    accent: tone(chosen[2], 'accent'),
  })
}

type CacheEntry = { palette: CoverPalette | null; expiresAt: number }
type Pending = {
  controller: AbortController
  promise: Promise<CoverPalette | null>
  users: number
  done: boolean
}
const cache = new Map<string, CacheEntry>()
const pending = new Map<string, Pending>()

function coverKey(cover?: string): string | null {
  if (!cover?.trim() || cover.length > 16_384 || typeof document === 'undefined') return null
  try {
    const url = new URL(cover, document.baseURI)
    if (url.username || url.password) return null
    if (url.protocol === 'data:')
      return /^data:image\/(?:png|jpeg|webp|gif|avif|bmp|svg\+xml)[;,]/i.test(cover) ? url.href : null
    return ['http:', 'https:', 'blob:'].includes(url.protocol) ? url.href : null
  } catch {
    return null
  }
}

function cached(key: string): CacheEntry | undefined {
  const entry = cache.get(key)
  return entry && entry.expiresAt > Date.now() ? entry : undefined
}

/** 只读缓存快照可用于 React 首次渲染；不在 render 中改 LRU 次序。 */
export function getCachedCoverPalette(cover?: string): CoverPalette | null {
  const key = coverKey(cover)
  return key ? (cached(key)?.palette ?? null) : null
}

function remember(key: string, palette: CoverPalette | null) {
  cache.delete(key)
  cache.set(key, { palette, expiresAt: palette ? Infinity : Date.now() + COVER_PALETTE_FAILURE_TTL_MS })
  if (cache.size > COVER_PALETTE_CACHE_LIMIT) cache.delete(cache.keys().next().value!)
}

function readImagePalette(url: string, signal: AbortSignal): Promise<CoverPalette | null> {
  return new Promise((resolve) => {
    let image: HTMLImageElement | undefined
    let timeout: ReturnType<typeof setTimeout> | undefined
    let settled = false
    let decoding = false
    const finish = (palette: CoverPalette | null) => {
      if (settled) return
      settled = true
      clearTimeout(timeout)
      signal.removeEventListener('abort', cancel)
      if (image) {
        image.onload = null
        image.onerror = null
        // 不赋空 src（可能请求当前页面）；释放装饰图像与未完成的 decode。
        image.removeAttribute('src')
      }
      resolve(palette)
    }
    const cancel = () => finish(null)
    const sample = () => {
      if (settled || signal.aborted || !image) return
      try {
        if (!image.naturalWidth || !image.naturalHeight) return finish(null)
        const scale = Math.min(
          1,
          COVER_PALETTE_SAMPLE_SIZE / Math.max(image.naturalWidth, image.naturalHeight),
        )
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
        canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
        const context = canvas.getContext('2d', { willReadFrequently: true })
        if (!context) return finish(null)
        context.drawImage(image, 0, 0, canvas.width, canvas.height)
        finish(extractCoverPalette(context.getImageData(0, 0, canvas.width, canvas.height).data))
      } catch {
        // CORS/tainted canvas、解码失败均属于可接受的装饰降级，不影响播放。
        finish(null)
      }
    }
    try {
      if (signal.aborted) return finish(null)
      image = new Image()
      image.crossOrigin = 'anonymous'
      image.referrerPolicy = 'no-referrer'
      image.decoding = 'async'
      image.fetchPriority = 'low'
      image.onload = () => {
        if (settled || decoding || !image) return
        decoding = true
        try {
          if (typeof image.decode === 'function') void image.decode().then(sample, () => finish(null))
          else sample()
        } catch {
          finish(null)
        }
      }
      image.onerror = () => finish(null)
      signal.addEventListener('abort', cancel, { once: true })
      timeout = setTimeout(() => finish(null), LOAD_TIMEOUT_MS)
      image.src = url
    } catch {
      finish(null)
    }
  })
}

/** 仅请求传入的封面，不探测其它资源/代理；失败与取消统一返回 null，调用者保留旧色。 */
export function loadCoverPalette(
  cover?: string,
  options: { signal?: AbortSignal } = {},
): Promise<CoverPalette | null> {
  const { signal } = options
  const key = coverKey(cover)
  if (!key || signal?.aborted) return Promise.resolve(null)
  const hit = cached(key)
  if (hit) {
    cache.delete(key)
    cache.set(key, hit)
    return Promise.resolve(hit.palette)
  }
  let request = pending.get(key)
  if (!request) {
    if (pending.size >= MAX_PENDING) {
      const oldestKey = pending.keys().next().value!
      pending.get(oldestKey)!.controller.abort()
      pending.delete(oldestKey)
    }
    const controller = new AbortController()
    const next: Pending = { controller, users: 0, done: false, promise: Promise.resolve(null) }
    next.promise = readImagePalette(key, controller.signal)
      .then((palette) => {
        if (!controller.signal.aborted) remember(key, palette)
        return palette
      })
      .finally(() => {
        next.done = true
        if (pending.get(key) === next) pending.delete(key)
      })
    request = next
    pending.set(key, next)
  }
  const active = request
  active.users++
  return new Promise((resolve) => {
    let finished = false
    const finish = (palette: CoverPalette | null) => {
      if (finished) return
      finished = true
      signal?.removeEventListener('abort', cancel)
      active.users--
      if (!active.done && active.users === 0) {
        if (pending.get(key) === active) pending.delete(key)
        active.controller.abort()
      }
      resolve(signal?.aborted ? null : palette)
    }
    const cancel = () => finish(null)
    signal?.addEventListener('abort', cancel, { once: true })
    void active.promise.then(finish, () => finish(null))
  })
}

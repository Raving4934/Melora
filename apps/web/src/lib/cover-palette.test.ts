import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_COVER_PALETTE, extractCoverPalette } from './cover-palette'

type RGBA = [number, number, number, number]
const pixels = (...colors: RGBA[]) => new Uint8ClampedArray(colors.flat())
const red: RGBA = [224, 45, 65, 255]
const blue: RGBA = [30, 70, 218, 255]
const green: RGBA = [35, 192, 92, 255]
const channels = (color: string) => color.match(/\d+/g)!.map(Number)
const luminance = (color: string) => {
  const [r, g, b] = channels(color).map((value) => {
    const c = value / 255
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return r * 0.2126 + g * 0.7152 + b * 0.0722
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

describe('有界确定性封面提色', () => {
  it('重复采样、输入复制与像素顺序反转保持同一色板，不改输入', () => {
    const colors = [red, red, red, blue, blue, green]
    const input = pixels(...colors)
    const original = input.slice()
    const palette = extractCoverPalette(input)
    expect(palette).toEqual(extractCoverPalette(input.slice()))
    expect(palette).toEqual(extractCoverPalette(pixels(...[...colors].reverse())))
    expect(input).toEqual(original)
    expect(Object.isFrozen(palette)).toBe(true)
    expect(palette).not.toEqual(DEFAULT_COVER_PALETTE)
  })

  it('面积最大的真实红色主导，蓝绿副色来自封面而非随机主题', () => {
    const palette = extractCoverPalette(pixels(red, red, red, red, blue, blue, green))!
    const [r, g, b] = channels(palette.primary)
    expect(r).toBeGreaterThan(g * 1.5)
    expect(r).toBeGreaterThan(b * 1.5)
    expect(channels(palette.secondary)[2]).toBeGreaterThan(channels(palette.secondary)[0])
    expect(channels(palette.accent)[1]).toBeGreaterThan(channels(palette.accent)[0])
  })

  it('相同权重的色块有固定决胜次序', () => {
    expect(extractCoverPalette(pixels(red, blue))).toEqual(extractCoverPalette(pixels(blue, red)))
  })

  it('完全透明的隐藏 RGB 不染色；低 alpha 像素按可见面积降权', () => {
    expect(extractCoverPalette(pixels(red, [0, 255, 0, 0], [255, 255, 255, 0]))).toEqual(
      extractCoverPalette(pixels(red)),
    )
    const palette = extractCoverPalette(pixels(red, [0, 0, 255, 25], [0, 0, 255, 25]))!
    expect(channels(palette.primary)[0]).toBeGreaterThan(channels(palette.primary)[2])
  })

  it.each([
    new Uint8ClampedArray(),
    new Uint8ClampedArray([255, 255, 255]),
    pixels([255, 0, 255, 0]),
    pixels([255, 255, 255, 1]),
  ])('空图、残缺像素和不可见图返回 null，供 UI 保留旧色', (input) => {
    expect(extractCoverPalette(input)).toBeNull()
  })

  it.each([
    [0, 0, 0, 255],
    [255, 255, 255, 255],
    [128, 128, 128, 255],
    [255, 255, 0, 255],
    [0, 255, 0, 255],
    [0, 0, 255, 255],
    [255, 0, 0, 255],
    [0, 255, 255, 255],
  ] as RGBA[])('极端色 %j 仍输出不透明、非死黑且限亮的色板', (...color) => {
    const palette = extractCoverPalette(pixels(color))!
    for (const value of Object.values(palette)) {
      expect(value).toMatch(/^rgb\(\d+ \d+ \d+\)$/)
      expect(luminance(value)).toBeGreaterThan(0.01)
      expect(luminance(value)).toBeLessThanOrEqual(0.145)
      expect(1.05 / (luminance(value) + 0.05)).toBeGreaterThan(5.38)
    }
  })

  it('全白/全黑保留温和的明暗区别，但不跳到白底或纯黑', () => {
    const dark = extractCoverPalette(pixels([0, 0, 0, 255]))!
    const light = extractCoverPalette(pixels([255, 255, 255, 255]))!
    expect(luminance(light.base)).toBeGreaterThan(luminance(dark.base))
    expect(dark).not.toEqual(light)
    expect(luminance(DEFAULT_COVER_PALETTE.base)).toBeGreaterThan(0.01)
  })

  it('单色不引入无关色相，稀少孤立噪声也不能占据一整层', () => {
    const palette = extractCoverPalette(pixels(...Array<RGBA>(100).fill(red), blue))!
    for (const color of Object.values(palette)) {
      const [r, g, b] = channels(color)
      expect(r).toBeGreaterThan(g)
      expect(r).toBeGreaterThan(b)
    }
  })

  it('超大输入读取不超过 1600 个 RGBA 像素', () => {
    const source = new Uint8ClampedArray(100_000 * 4).fill(180)
    let reads = 0
    const bounded = new Proxy(source, {
      get(target, key) {
        if (typeof key === 'string' && /^\d+$/.test(key)) reads++
        return Reflect.get(target, key, target)
      },
    })
    expect(extractCoverPalette(bounded)).not.toBeNull()
    expect(reads).toBeLessThanOrEqual(1600 * 4)
  })
})

class TestImage {
  static instances: TestImage[] = []
  naturalWidth = 800
  naturalHeight = 800
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  crossOrigin = ''
  referrerPolicy = ''
  decoding = ''
  fetchPriority = ''
  requested = { url: '', crossOrigin: '', referrerPolicy: '' }
  private source = ''
  decode: (() => Promise<void>) | undefined = vi.fn(() => Promise.resolve())
  removeAttribute = vi.fn((name: string) => {
    if (name === 'src') this.source = ''
  })
  constructor() {
    TestImage.instances.push(this)
  }
  set src(url: string) {
    this.source = url
    this.requested = { url, crossOrigin: this.crossOrigin, referrerPolicy: this.referrerPolicy }
  }
  get src() {
    return this.source
  }
}

describe('封面读取、CORS、取消与有界缓存', () => {
  let lib: typeof import('./cover-palette')
  let context: { drawImage: ReturnType<typeof vi.fn>; getImageData: ReturnType<typeof vi.fn> }
  let getContext: ReturnType<typeof vi.spyOn>
  const latest = () => TestImage.instances.at(-1)!
  const load = async (url: string) => {
    const promise = lib.loadCoverPalette(url)
    latest().onload?.()
    return await promise
  }

  beforeEach(async () => {
    vi.resetModules()
    lib = await import('./cover-palette')
    vi.useFakeTimers()
    TestImage.instances = []
    vi.stubGlobal('Image', TestImage)
    vi.stubGlobal('fetch', vi.fn())
    context = { drawImage: vi.fn(), getImageData: vi.fn(() => ({ data: pixels(red, blue, red) })) }
    getContext = vi
      .spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockReturnValue(context as unknown as CanvasRenderingContext2D)
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('只请求传入封面，设置匿名 CORS 后再赋 src，不用 fetch/代理/凭据', async () => {
    const url = 'https://covers.example.test/album.png?size=300'
    const promise = lib.loadCoverPalette(url)
    const image = latest()
    expect(image.requested).toEqual({ url, crossOrigin: 'anonymous', referrerPolicy: 'no-referrer' })
    expect(image.decoding).toBe('async')
    expect(image.fetchPriority).toBe('low')
    image.onload?.()
    expect(await promise).toEqual(extractCoverPalette(pixels(red, blue, red)))
    expect(fetch).not.toHaveBeenCalled()
    expect(image.src).toBe('')
    expect(image.onload).toBeNull()
    expect(image.onerror).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('保留主线传入的网关路径，不自行再次 assetURL/normalize', async () => {
    const promise = lib.loadCoverPalette('/app/melora/covers/fixture.svg')
    expect(latest().requested.url).toBe(new URL('/app/melora/covers/fixture.svg', document.baseURI).href)
    latest().onload?.()
    expect(await promise).not.toBeNull()
  })

  it('图片 decode 完成前不创建 canvas；最长边 40px，保持纵横比', async () => {
    const decoded = deferred<void>()
    const promise = lib.loadCoverPalette('/covers/large.png')
    const image = latest()
    image.naturalWidth = 10_000
    image.naturalHeight = 4_000
    image.decode = () => decoded.promise
    image.onload?.()
    expect(getContext).not.toHaveBeenCalled()
    decoded.resolve()
    expect(await promise).not.toBeNull()
    expect(getContext).toHaveBeenCalledWith('2d', { willReadFrequently: true })
    expect(context.drawImage).toHaveBeenCalledWith(image, 0, 0, 40, 16)
    expect(context.getImageData).toHaveBeenCalledWith(0, 0, 40, 16)
  })

  it.each([
    [1, 10_000, 1, 40],
    [10_000, 1, 40, 1],
    [1, 1, 1, 1],
  ])('极端纵横比 %sx%s 的抽样仍有界且没有零尺寸', async (width, height, outWidth, outHeight) => {
    const promise = lib.loadCoverPalette('/covers/extreme.png')
    const image = latest()
    image.naturalWidth = width
    image.naturalHeight = height
    image.onload?.()
    expect(await promise).not.toBeNull()
    expect(context.drawImage).toHaveBeenCalledWith(image, 0, 0, outWidth, outHeight)
  })

  it('没有 decode API 的浏览器可从 load 事件采样', async () => {
    const promise = lib.loadCoverPalette('/covers/legacy.png')
    latest().decode = undefined
    latest().onload?.()
    expect(await promise).not.toBeNull()
  })

  it.each(['image', 'decode', 'decode-sync', 'canvas', 'draw', 'cors', 'empty', 'transparent'])(
    '%s 失败静默返回 null',
    async (failure) => {
      const error = vi.spyOn(console, 'error').mockImplementation(() => {})
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      const promise = lib.loadCoverPalette(`/covers/failure-${failure}.png`)
      const image = latest()
      if (failure === 'decode') image.decode = () => Promise.reject(new Error('decode failed'))
      if (failure === 'decode-sync')
        image.decode = () => {
          throw new Error('decode failed')
        }
      if (failure === 'canvas') getContext.mockReturnValue(null)
      if (failure === 'draw')
        context.drawImage.mockImplementation(() => {
          throw new Error('draw failed')
        })
      if (failure === 'cors')
        context.getImageData.mockImplementation(() => {
          throw new DOMException('Tainted canvas', 'SecurityError')
        })
      if (failure === 'empty') image.naturalWidth = 0
      if (failure === 'transparent') context.getImageData.mockReturnValue({ data: pixels([255, 0, 0, 0]) })
      if (failure === 'image') image.onerror?.()
      else image.onload?.()
      expect(await promise).toBeNull()
      expect(error).not.toHaveBeenCalled()
      expect(warn).not.toHaveBeenCalled()
      expect(image.src).toBe('')
      expect(vi.getTimerCount()).toBe(0)
    },
  )

  it('加载或 decode 挂起在 8 秒后释放，不留永久任务', async () => {
    const promise = lib.loadCoverPalette('/covers/hanging.png')
    const decoded = deferred<void>()
    latest().decode = () => decoded.promise
    latest().onload?.()
    await vi.advanceTimersByTimeAsync(8_000)
    expect(await promise).toBeNull()
    expect(latest().src).toBe('')
    decoded.resolve()
    await Promise.resolve()
    expect(context.drawImage).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('预取消不请求；加载中取消立即释放，保存的过期 onload 也不能采样', async () => {
    const controller = new AbortController()
    controller.abort()
    expect(await lib.loadCoverPalette('/covers/pre-aborted.png', { signal: controller.signal })).toBeNull()
    expect(TestImage.instances).toHaveLength(0)
    const active = new AbortController()
    const promise = lib.loadCoverPalette('/covers/aborted.png', { signal: active.signal })
    const image = latest()
    const staleLoad = image.onload!
    active.abort()
    expect(await promise).toBeNull()
    expect(image.removeAttribute).toHaveBeenCalledWith('src')
    staleLoad()
    expect(context.drawImage).not.toHaveBeenCalled()
    expect(lib.getCachedCoverPalette('/covers/aborted.png')).toBeNull()
    expect(await load('/covers/aborted.png')).not.toBeNull()
    expect(TestImage.instances).toHaveLength(2)
  })

  it('decode 中换曲后旧 Promise 不采样、不回写缓存、不覆盖新结果', async () => {
    const controller = new AbortController()
    const decoded = deferred<void>()
    const old = lib.loadCoverPalette('/covers/old.png', { signal: controller.signal })
    latest().decode = () => decoded.promise
    latest().onload?.()
    controller.abort()
    const current = await load('/covers/current.png')
    expect(current).not.toBeNull()
    decoded.resolve()
    expect(await old).toBeNull()
    await Promise.resolve()
    expect(context.drawImage).toHaveBeenCalledTimes(1)
    expect(lib.getCachedCoverPalette('/covers/old.png')).toBeNull()
    expect(lib.getCachedCoverPalette('/covers/current.png')).toBe(current)
  })

  it('相同封面并发共享一次解码/采样，一个订阅取消不影响另一个', async () => {
    const controller = new AbortController()
    const first = lib.loadCoverPalette('/covers/shared.png', { signal: controller.signal })
    const second = lib.loadCoverPalette('/covers/shared.png')
    expect(TestImage.instances).toHaveLength(1)
    controller.abort()
    expect(await first).toBeNull()
    expect(latest().src).not.toBe('')
    latest().onload?.()
    const palette = await second
    expect(palette).not.toBeNull()
    expect(context.drawImage).toHaveBeenCalledTimes(1)
    expect(await lib.loadCoverPalette('/covers/shared.png')).toBe(palette)
    expect(TestImage.instances).toHaveLength(1)
  })

  it('失败短缓存抑制 CORS 重试风暴，过期后允许重试', async () => {
    const url = '/covers/retry.png'
    const first = lib.loadCoverPalette(url)
    latest().onerror?.()
    expect(await first).toBeNull()
    expect(await lib.loadCoverPalette(url)).toBeNull()
    expect(TestImage.instances).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(lib.COVER_PALETTE_FAILURE_TTL_MS)
    expect(await load(url)).not.toBeNull()
    expect(TestImage.instances).toHaveLength(2)
  })

  it('24 项 LRU 触顶后淘汰冷色板，缓存命中不再提色', async () => {
    for (let index = 0; index < lib.COVER_PALETTE_CACHE_LIMIT; index++) {
      expect(await load(`/covers/cache-${index}.png`)).not.toBeNull()
    }
    const hot = lib.getCachedCoverPalette('/covers/cache-0.png')
    expect(await lib.loadCoverPalette('/covers/cache-0.png')).toBe(hot)
    await load('/covers/overflow.png')
    expect(lib.getCachedCoverPalette('/covers/cache-1.png')).toBeNull()
    expect(lib.getCachedCoverPalette('/covers/cache-0.png')).toBe(hot)
    expect(TestImage.instances).toHaveLength(lib.COVER_PALETTE_CACHE_LIMIT + 1)
    expect(context.drawImage).toHaveBeenCalledTimes(lib.COVER_PALETTE_CACHE_LIMIT + 1)
  })

  it('最多 4 个在途图像，第 5 个淘汰最旧请求且不负缓存取消项', async () => {
    const controllers = Array.from({ length: 5 }, () => new AbortController())
    const requests = controllers.map((controller, index) =>
      lib.loadCoverPalette(`/covers/pending-${index}.png`, { signal: controller.signal }),
    )
    expect(TestImage.instances[0].src).toBe('')
    expect(await requests[0]).toBeNull()
    expect(TestImage.instances.filter((image) => image.src)).toHaveLength(4)
    controllers.forEach((controller) => controller.abort())
    expect(await Promise.all(requests)).toEqual([null, null, null, null, null])
    expect(await load('/covers/pending-0.png')).not.toBeNull()
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    undefined,
    '',
    '  ',
    'javascript:alert(1)',
    'file:///private/cover.png',
    'https://user:password@covers.example.test/a.png',
    'data:text/html,hello',
    'http://[',
    `https://covers.example.test/${'x'.repeat(16_384)}`,
  ])('不安全/无效输入 %s 不发起图像或其它请求', async (url) => {
    expect(await lib.loadCoverPalette(url)).toBeNull()
    expect(TestImage.instances).toHaveLength(0)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('没有 DOM 的环境安全返回 null', async () => {
    vi.stubGlobal('document', undefined)
    expect(lib.getCachedCoverPalette('/covers/a.png')).toBeNull()
    expect(await lib.loadCoverPalette('/covers/a.png')).toBeNull()
    expect(TestImage.instances).toHaveLength(0)
  })
})

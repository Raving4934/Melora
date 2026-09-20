import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { StrictMode } from 'react'
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AmbientCanvas } from './AmbientCanvas'
import {
  DEFAULT_COVER_PALETTE,
  getCachedCoverPalette,
  loadCoverPalette,
  type CoverPalette,
} from '../lib/cover-palette'

// Vitest 的默认 CSS mock 会把 ?raw 也置空，源码契约直接读取限定 CSS 文件。
const styles = readFileSync(resolve('src/components/AmbientCanvas.css'), 'utf8')

vi.mock('../lib/cover-palette', async (original) => ({
  ...(await original<typeof import('../lib/cover-palette')>()),
  getCachedCoverPalette: vi.fn(() => null),
  loadCoverPalette: vi.fn(),
}))

function deferred() {
  let resolve!: (palette: CoverPalette | null) => void
  const promise = new Promise<CoverPalette | null>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
const warm: CoverPalette = {
  base: 'rgb(55 38 43)',
  primary: 'rgb(134 58 73)',
  secondary: 'rgb(95 69 57)',
  accent: 'rgb(150 82 78)',
}
const cool: CoverPalette = {
  base: 'rgb(35 47 53)',
  primary: 'rgb(57 109 131)',
  secondary: 'rgb(55 85 80)',
  accent: 'rgb(72 105 128)',
}
const rootOf = (container: HTMLElement) => container.querySelector<HTMLElement>('.player-atmosphere')!
const baseOf = (container: HTMLElement) => rootOf(container).style.getPropertyValue('--ambient-base')

const originalAnimate = Object.getOwnPropertyDescriptor(Element.prototype, 'animate')

beforeEach(() => {
  Object.defineProperty(Element.prototype, 'animate', {
    configurable: true,
    value: vi.fn(() => ({ finished: new Promise(() => {}), cancel: vi.fn() })),
  })
  vi.clearAllMocks()
  vi.mocked(getCachedCoverPalette).mockReturnValue(null)
  vi.mocked(loadCoverPalette).mockResolvedValue(null)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
})
afterEach(() => {
  cleanup()
  if (originalAnimate) Object.defineProperty(Element.prototype, 'animate', originalAnimate)
  else delete (Element.prototype as unknown as Record<string, unknown>).animate
  vi.restoreAllMocks()
})

describe('AmbientCanvas 状态与稳定性', () => {
  it('无封面首屏也不透明且只有两张固定画布，隐藏于辅助技术、不含焦点/图像节点', () => {
    const view = render(<AmbientCanvas playing={false} />)
    const root = rootOf(view.container)
    expect(root).toHaveClass('player-atmosphere', 'ambient-canvas')
    expect(root).toHaveAttribute('aria-hidden', 'true')
    expect(root).toHaveAttribute('data-motion', 'paused')
    expect(root.children).toHaveLength(2)
    expect(root.querySelectorAll('.ambient-canvas__wash')).toHaveLength(6)
    expect(root.querySelector('img, canvas, button, a, input, [tabindex]')).toBeNull()
    expect(baseOf(view.container)).toBe(DEFAULT_COVER_PALETTE.base)
    expect(loadCoverPalette).not.toHaveBeenCalled()
  })

  it('首帧命中已有缓存时不经过默认色板闪变', () => {
    vi.mocked(getCachedCoverPalette).mockReturnValue(warm)
    const view = render(<AmbientCanvas cover="/covers/warm.png" trackKey="a" playing />)
    expect(baseOf(view.container)).toBe(warm.base)
  })

  it('解码等待/失败/无封面期间保留已有色板，不重建根节点与色雾', async () => {
    const first = deferred()
    const next = deferred()
    vi.mocked(loadCoverPalette).mockReturnValueOnce(first.promise).mockReturnValueOnce(next.promise)
    const view = render(<AmbientCanvas cover="/covers/first.png" trackKey="a" playing />)
    const root = rootOf(view.container)
    const washes = [...root.children]
    await act(async () => first.resolve(warm))
    view.rerender(<AmbientCanvas cover="/covers/failed.png" trackKey="b" playing />)
    expect(baseOf(view.container)).toBe(warm.base)
    await act(async () => next.resolve(null))
    expect(baseOf(view.container)).toBe(warm.base)
    view.rerender(<AmbientCanvas trackKey="c" playing={false} />)
    expect(baseOf(view.container)).toBe(warm.base)
    expect(rootOf(view.container)).toBe(root)
    expect([...root.children]).toEqual(washes)
    expect(root).toHaveAttribute('data-motion', 'paused')
  })

  it('快速 A→B→C，先到 C 再到 B 不回写 B，换曲不重启动画节点', async () => {
    const first = deferred()
    const second = deferred()
    const third = deferred()
    vi.mocked(loadCoverPalette)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(third.promise)
    const view = render(<AmbientCanvas cover="/covers/a.png" trackKey="a" playing />)
    const root = rootOf(view.container)
    await act(async () => first.resolve(warm))
    view.rerender(<AmbientCanvas cover="/covers/b.png" trackKey="b" playing />)
    const secondSignal = vi.mocked(loadCoverPalette).mock.calls[1][1]!.signal!
    view.rerender(<AmbientCanvas cover="/covers/c.png" trackKey="c" playing />)
    expect(secondSignal.aborted).toBe(true)
    expect(baseOf(view.container)).toBe(warm.base)
    await act(async () => third.resolve(cool))
    await act(async () => second.resolve(warm))
    expect(baseOf(view.container)).toBe(cool.base)
    expect(rootOf(view.container)).toBe(root)
    expect(root).toHaveAttribute('data-track-key', 'c')
  })

  it('同一封面换 trackKey、暂停/恢复或父组件重渲染不重复提色', async () => {
    const result = deferred()
    vi.mocked(loadCoverPalette).mockReturnValue(result.promise)
    const view = render(<AmbientCanvas cover="/covers/same.png" trackKey="a" playing />)
    const signal = vi.mocked(loadCoverPalette).mock.calls[0][1]!.signal!
    view.rerender(<AmbientCanvas cover="/covers/same.png" trackKey="b" playing={false} />)
    view.rerender(<AmbientCanvas cover="/covers/same.png" trackKey="b" playing />)
    view.rerender(<AmbientCanvas cover="/covers/same.png" trackKey="b" playing />)
    expect(loadCoverPalette).toHaveBeenCalledTimes(1)
    expect(signal.aborted).toBe(false)
    await act(async () => result.resolve(warm))
    expect(baseOf(view.container)).toBe(warm.base)
  })

  it('playing 与页面可见性共同控制动画；隐藏再恢复不能复活已暂停背景', () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get')
    const view = render(<AmbientCanvas playing />)
    const root = rootOf(view.container)
    expect(root).toHaveAttribute('data-motion', 'running')
    visibility.mockReturnValue('hidden')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    expect(root).toHaveAttribute('data-motion', 'paused')
    visibility.mockReturnValue('visible')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    expect(root).toHaveAttribute('data-motion', 'running')
    view.rerender(<AmbientCanvas playing={false} />)
    visibility.mockReturnValue('hidden')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    visibility.mockReturnValue('visible')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    expect(root).toHaveAttribute('data-motion', 'paused')
  })

  it('页面初始隐藏即暂停；卸载移除订阅并取消请求，晚到结果不回写', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const remove = vi.spyOn(document, 'removeEventListener')
    const result = deferred()
    vi.mocked(loadCoverPalette).mockReturnValue(result.promise)
    const view = render(<AmbientCanvas cover="/covers/pending.png" playing />)
    const signal = vi.mocked(loadCoverPalette).mock.calls[0][1]!.signal!
    expect(rootOf(view.container)).toHaveAttribute('data-motion', 'paused')
    view.unmount()
    expect(signal.aborted).toBe(true)
    expect(remove).toHaveBeenCalledWith('visibilitychange', expect.any(Function))
    await act(async () => result.resolve(warm))
    expect(view.container.children).toHaveLength(0)
  })

  it('StrictMode 的试运行被取消，不能把过期微任务写回新挂载', async () => {
    const old = deferred()
    const current = deferred()
    vi.mocked(loadCoverPalette).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const view = render(
      <StrictMode>
        <AmbientCanvas cover="/covers/strict.png" playing />
      </StrictMode>,
    )
    expect(loadCoverPalette).toHaveBeenCalledTimes(2)
    expect(vi.mocked(loadCoverPalette).mock.calls[0][1]!.signal!.aborted).toBe(true)
    await act(async () => current.resolve(cool))
    await act(async () => old.resolve(warm))
    expect(baseOf(view.container)).toBe(cool.base)
  })

  it('快速回切从当前混色重定向，不强制完成过时的深色；仅两槽且卸载取消动画与兜底计时', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(getCachedCoverPalette).mockReturnValue(warm)
      const first = deferred()
      const next = deferred()
      vi.mocked(loadCoverPalette).mockReturnValueOnce(first.promise).mockReturnValueOnce(next.promise)
      const view = render(<AmbientCanvas cover="/covers/cool.png" playing />)
      await act(async () => first.resolve(cool))
      const root = rootOf(view.container)
      const incoming = root.querySelector<HTMLElement>("[data-role='incoming']")!
      const elements = [...root.children]
      expect(incoming.style.getPropertyValue('--ambient-base')).toBe(cool.base)
      const css = window.getComputedStyle.bind(window)
      vi.spyOn(window, 'getComputedStyle').mockImplementation((node, pseudo) =>
        node === incoming ? ({ opacity: '0.4' } as CSSStyleDeclaration) : css(node, pseudo),
      )
      view.rerender(<AmbientCanvas cover="/covers/warm.png" playing />)
      await act(async () => next.resolve(warm))
      expect(
        root.querySelector<HTMLElement>("[data-role='incoming']")?.style.getPropertyValue('--ambient-base'),
      ).toBe(warm.base)
      expect(
        root.querySelector<HTMLElement>("[data-role='outgoing']")?.style.getPropertyValue('--ambient-base'),
      ).toBe('rgb(47 41.6 47)')
      expect([...root.children]).toEqual(elements)
      expect(Element.prototype.animate).toHaveBeenCalledTimes(2)
      expect(Element.prototype.animate).toHaveBeenNthCalledWith(
        1,
        [{ opacity: 0 }, { opacity: 1 }],
        expect.objectContaining({ duration: 450, fill: 'both' }),
      )
      const animations = vi.mocked(Element.prototype.animate).mock.results
      expect(animations[0].value.cancel).toHaveBeenCalledOnce()
      await act(async () => vi.advanceTimersByTime(1600))
      expect(root.querySelector("[data-role='incoming']")).toBeNull()
      expect(
        root.querySelector<HTMLElement>("[data-role='current']")?.style.getPropertyValue('--ambient-base'),
      ).toBe(warm.base)
      view.unmount()
      expect(animations[1].value.cancel).toHaveBeenCalledOnce()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('缺少Web Animations时只即时换色，核心播放器不会因装饰能力缺失崩溃', async () => {
    Object.defineProperty(Element.prototype, 'animate', { configurable: true, value: undefined })
    vi.mocked(getCachedCoverPalette).mockReturnValue(warm)
    vi.mocked(loadCoverPalette).mockResolvedValue(cool)
    const view = render(<AmbientCanvas cover="/covers/cool.png" playing />)
    await act(async () => {})
    expect(rootOf(view.container)).toHaveAttribute('data-color-state', 'settled')
    expect(
      rootOf(view.container)
        .querySelector<HTMLElement>("[data-role='current']")
        ?.style.getPropertyValue('--ambient-base'),
    ).toBe(cool.base)
  })

  it('减少动态效果从首帧生效，色板更新直接落在当前画布，不留下淡入等待', async () => {
    const media = { matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }
    vi.stubGlobal('matchMedia', () => media)
    try {
      vi.mocked(getCachedCoverPalette).mockReturnValue(warm)
      const result = deferred()
      vi.mocked(loadCoverPalette).mockReturnValue(result.promise)
      const view = render(<AmbientCanvas cover="/covers/cool.png" playing />)
      await act(async () => result.resolve(cool))
      expect(rootOf(view.container).querySelector("[data-role='incoming']")).toBeNull()
      expect(
        rootOf(view.container)
          .querySelector<HTMLElement>("[data-role='current']")
          ?.style.getPropertyValue('--ambient-base'),
      ).toBe(cool.base)
      view.unmount()
      expect(media.removeEventListener).toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('旧WebKit的媒体查询监听可降级并清理，不因装饰能力差异卸载播放器', () => {
    const media = { matches: false, addListener: vi.fn(), removeListener: vi.fn() }
    vi.stubGlobal('matchMedia', () => media)
    try {
      const view = render(<AmbientCanvas playing />)
      expect(rootOf(view.container)).toHaveAttribute('data-motion', 'running')
      expect(media.addListener).toHaveBeenCalled()
      view.unmount()
      expect(media.removeListener).toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('没有 rAF/interval 动画循环，也不修改 html/body/frame 的布局', async () => {
    const raf = vi.spyOn(window, 'requestAnimationFrame')
    const interval = vi.spyOn(window, 'setInterval')
    const html = document.documentElement.getAttribute('style')
    const body = document.body.getAttribute('style')
    const view = render(<AmbientCanvas playing />)
    await act(async () => {})
    expect(raf).not.toHaveBeenCalled()
    expect(interval).not.toHaveBeenCalled()
    expect(document.documentElement.getAttribute('style')).toBe(html)
    expect(document.body.getAttribute('style')).toBe(body)
    view.unmount()
  })
})

describe('背景 CSS 性能/可访问性契约（浏览器计算样式另行验证）', () => {
  it('固定 -2px 裁边且底色不透明，选择器不触碰应用/宿主尺寸', () => {
    const root = styles.match(/\.player-atmosphere\.ambient-canvas\s*\{([^}]+)\}/)![1]
    expect(root).toContain('position: fixed')
    expect(root).toContain('inset: -2px')
    expect(root).toContain('overflow: hidden')
    expect(root).toContain('contain: paint')
    expect(root).toContain('pointer-events: none')
    expect(root).toContain('background: #171a1e')
    expect(styles).not.toMatch(/(?:^|\})\s*(?:html|body|iframe|:root|\.player-immersive)\b/m)
  })

  it('顶部使用沉浸主题色柔和衔接 Android PWA 状态栏', () => {
    const overlay = styles.match(/\.ambient-canvas::before\s*\{([^}]+)\}/)![1]
    expect(overlay).toMatch(/linear-gradient\(\s*180deg/)
    expect(overlay).toContain('#171a1e 0')
    expect(overlay).toContain('rgb(23 26 30 / 0%) 104px')
  })

  it('三个缓慢循环只动画 transform/opacity，不动画 blur/几何/颜色', () => {
    const keyframes = [...styles.matchAll(/@keyframes[^\{]+\{([\s\S]*?)\n\}/g)]
    expect(keyframes).toHaveLength(3)
    for (const [, body] of keyframes) {
      const properties = [...body.matchAll(/([\w-]+)\s*:/g)].map((match) => match[1])
      expect(properties.every((property) => ['transform', 'opacity'].includes(property))).toBe(true)
    }
    const durations = [...styles.matchAll(/animation-duration:\s*(\d+)s/g)].map((match) => Number(match[1]))
    expect(durations).toHaveLength(3)
    expect(durations.every((duration) => duration >= 30)).toBe(true)
    expect(styles).not.toMatch(/will-change|backdrop-filter|transition:\s*all/)
  })

  it('色雾直接使用柔和渐变，没有GPU大模糊纹理，大小保持桌面/移动上限', () => {
    expect(styles).not.toMatch(/filter:\s*blur|backdrop-filter/)
    expect(styles.match(/radial-gradient/g)).toHaveLength(3)
    expect(styles).toContain('width: clamp(280px, 68vw, 960px)')
    expect(styles).toContain('height: clamp(300px, 76vh, 840px)')
    expect(styles).toContain('width: min(96vw, 560px)')
    expect(styles).toContain('height: min(64vh, 640px)')
    expect(styles).not.toMatch(/scale\(|\b(?:1[2-9]\d|[2-9]\d\d)(?:vw|vh)/)
  })

  it('默认暂停，playing/visible 许可才运行；reduced-motion 连底色也即时换色', () => {
    const wash = styles.match(/\.ambient-canvas__wash\s*\{([^}]+)\}/)![1]
    expect(wash).toContain('animation-play-state: paused')
    expect(styles).toMatch(
      /\[data-motion='running'\] \.ambient-canvas__wash\s*\{\s*animation-play-state: running/,
    )
    const reduced = styles.slice(styles.indexOf('@media (prefers-reduced-motion: reduce)'))
    expect(reduced).toContain('animation: none')
    expect(reduced).toContain('transform: none')
    expect(reduced).toContain('transition: none')
    expect(reduced).toContain('.ambient-palette-layer')
  })

  it('换色仅合成不透明的旧底与新层opacity，不逐帧重绘背景颜色或把两层同时淡出', () => {
    expect(styles).not.toMatch(/transition:\s*background(?:-color)?/)
    const outgoing = styles.match(/\.ambient-palette-layer\[data-role='outgoing'\]\s*\{([^}]+)\}/)![1]
    expect(outgoing).toContain('opacity: 1')
    const layer = styles.match(/\.ambient-palette-layer\s*\{([^}]+)\}/)![1]
    expect(layer).toContain('background: var(--ambient-base)')
    expect(styles).not.toContain('ambient-palette-in')
    expect(styles).not.toMatch(/data-role='idle'[^}]*animation-play-state: paused/s)
  })

  it('噪点为带固定 seed 的静态内联 SVG 小平铺，不请求外部图片', () => {
    const grain = styles.match(/\.ambient-canvas::after\s*\{([^}]+)\}/g)!.at(-1)!
    expect(grain).toContain('data:image/svg+xml')
    expect(grain).toContain("seed='7'")
    expect(grain).toContain('background-size: 160px 160px')
    expect(grain).toContain('opacity: 0.035')
    expect(grain).not.toContain('animation')
    expect(styles).not.toMatch(/url\(["']?https?:/)
  })
})

import { memo, useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import {
  DEFAULT_COVER_PALETTE,
  getCachedCoverPalette,
  loadCoverPalette,
  type CoverPalette,
} from '../lib/cover-palette'
import './AmbientCanvas.css'

export type AmbientCanvasProps = {
  /** 主线已完成 normalizeCoverURL + assetURL；组件不改写或代理封面地址。 */
  cover?: string
  trackKey?: string
  playing: boolean
}

const fadeDuration = 450
const motionQuery = '(prefers-reduced-motion: reduce)'
type Scene = {
  from: CoverPalette
  to: CoverPalette
  fading: boolean
}
const samePalette = (a: CoverPalette, b: CoverPalette) =>
  a.base === b.base && a.primary === b.primary && a.secondary === b.secondary && a.accent === b.accent
const paletteStyle = (palette: CoverPalette): CSSProperties =>
  ({
    '--ambient-base': palette.base,
    '--ambient-primary': palette.primary,
    '--ambient-secondary': palette.secondary,
    '--ambient-accent': palette.accent,
  }) as CSSProperties

// 两槽色雾使用相同的动画时钟。颜色来自限界提色器的不透明rgb；只在换曲时
// 读取一次实际合成opacity并混合四个色值，不逐帧读样式，也不强制显示过时目标。
function mixPalette(from: CoverPalette, to: CoverPalette, opacity: number): CoverPalette {
  const weight = Number.isFinite(opacity) ? Math.min(1, Math.max(0, opacity)) : 0
  if (weight === 0) return from
  if (weight === 1) return to
  const mix = (a: string, b: string) => {
    if (a === b) return a
    const left = a.match(/^rgb\(([\d.]+) ([\d.]+) ([\d.]+)\)$/)
    const right = b.match(/^rgb\(([\d.]+) ([\d.]+) ([\d.]+)\)$/)
    if (!left || !right) return a
    const channels = [1, 2, 3].map((index) =>
      Number((Number(left[index]) + (Number(right[index]) - Number(left[index])) * weight).toFixed(4)),
    )
    return `rgb(${channels.join(' ')})`
  }
  return {
    base: mix(from.base, to.base),
    primary: mix(from.primary, to.primary),
    secondary: mix(from.secondary, to.secondary),
    accent: mix(from.accent, to.accent),
  }
}

/** 独立装饰画布：不订阅播放进度；没有 JS 逐帧动画，不影响页面或 iframe 尺寸。 */
export const AmbientCanvas = memo(function AmbientCanvas({ cover, trackKey, playing }: AmbientCanvasProps) {
  const [palette, setPalette] = useState(() => getCachedCoverPalette(cover) ?? DEFAULT_COVER_PALETTE)
  const [visible, setVisible] = useState(() => document.visibilityState !== 'hidden')
  const [reduced, setReduced] = useState(() => window.matchMedia?.(motionQuery).matches ?? false)
  const [scene, setScene] = useState<Scene>(() => ({ from: palette, to: palette, fading: false }))
  const incoming = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const updateVisibility = () => setVisible(document.visibilityState !== 'hidden')
    updateVisibility()
    document.addEventListener('visibilitychange', updateVisibility)
    return () => document.removeEventListener('visibilitychange', updateVisibility)
  }, [])

  useEffect(() => {
    const media = window.matchMedia?.(motionQuery)
    if (!media) return
    const update = () => setReduced(media.matches)
    update()
    if (typeof media.addEventListener === 'function') {
      media.addEventListener('change', update)
      return () => media.removeEventListener('change', update)
    }
    // 旧WebKit只有addListener；装饰性的偏好监听不能让整个播放器进入错误页。
    media.addListener?.(update)
    return () => media.removeListener?.(update)
  }, [])

  useEffect(() => {
    if (!cover) return
    const controller = new AbortController()
    void loadCoverPalette(cover, { signal: controller.signal }).then((next) => {
      // 缓存命中也经过微任务；快速换曲/卸载后不允许旧结果回写。
      if (!controller.signal.aborted && next) setPalette(next)
    })
    return () => controller.abort()
  }, [cover])

  useLayoutEffect(() => {
    if (reduced || !visible) {
      if (scene.fading || !samePalette(scene.to, palette))
        setScene({ from: palette, to: palette, fading: false })
      return
    }
    if (samePalette(scene.to, palette)) return
    const opacity = scene.fading && incoming.current ? Number(getComputedStyle(incoming.current).opacity) : 0
    const from = scene.fading ? mixPalette(scene.from, scene.to, opacity) : scene.from
    setScene({ from, to: palette, fading: true })
  }, [palette, reduced, visible, scene])

  useLayoutEffect(() => {
    if (!scene.fading || !incoming.current) return
    const node = incoming.current
    let stopped = false
    let animation: Animation | undefined
    let timer: ReturnType<typeof setTimeout> | undefined
    const finish = () => {
      if (stopped) return
      setScene((previous) =>
        previous === scene ? { from: scene.to, to: scene.to, fading: false } : previous,
      )
    }
    try {
      if (typeof node.animate !== 'function' || reduced || !visible) finish()
      else {
        // 在绘制前以新的不透明混色底开始淡入；中途更新先读取旧呈现，再取消旧动画。
        // finished的闭包只允许结束自己那一轮，取消或迟到回调不能清掉新目标。
        animation = node.animate([{ opacity: 0 }, { opacity: 1 }], {
          duration: fadeDuration,
          easing: 'cubic-bezier(0.22, 0.61, 0.36, 1)',
          fill: 'both',
        })
        void animation.finished.then(finish, finish)
        // 仅缺失结束通知时兜底；不把主线程阻塞时间承诺成硬实时期限。
        timer = setTimeout(finish, 1500)
      }
    } catch {
      finish() // 装饰API缺失或异常只即时换色，不让整个播放器退出。
    }
    return () => {
      stopped = true
      clearTimeout(timer)
      animation?.cancel()
    }
  }, [scene, reduced, visible])

  return (
    <div
      className="player-atmosphere ambient-canvas"
      aria-hidden="true"
      data-motion={playing && visible ? 'running' : 'paused'}
      data-color-state={scene.fading ? 'fading' : 'settled'}
      data-track-key={trackKey}
      style={paletteStyle(palette)}
    >
      {[scene.from, scene.to].map((colors, index) => (
        <div
          key={index}
          className="ambient-palette-layer"
          ref={index === 1 ? incoming : undefined}
          data-role={
            index === 0 ? (scene.fading ? 'outgoing' : 'current') : scene.fading ? 'incoming' : 'idle'
          }
          style={paletteStyle(colors)}
        >
          <div className="ambient-canvas__wash ambient-canvas__wash--primary" />
          <div className="ambient-canvas__wash ambient-canvas__wash--secondary" />
          <div className="ambient-canvas__wash ambient-canvas__wash--accent" />
        </div>
      ))}
    </div>
  )
})

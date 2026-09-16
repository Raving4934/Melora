import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import { ArrowDown, ArrowUp } from 'lucide-react'

const EDGE_THRESHOLD = 240
const HIDE_DELAY_MS = 2500
const SCROLL_KEYS = new Set(['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '])

interface ScrollEdges {
  canScrollUp: boolean
  canScrollDown: boolean
}

export function ScrollEdgeControls({
  target,
  className = '',
}: {
  target: RefObject<HTMLElement | null>
  className?: string
}) {
  const [edges, setEdges] = useState<ScrollEdges>({ canScrollUp: false, canScrollDown: false })
  const [visible, setVisible] = useState(false)
  const hideTimer = useRef<number | null>(null)
  const clearHideTimer = useCallback(() => {
    if (hideTimer.current !== null) {
      window.clearTimeout(hideTimer.current)
      hideTimer.current = null
    }
  }, [])
  const scheduleHide = useCallback(() => {
    clearHideTimer()
    hideTimer.current = window.setTimeout(() => {
      hideTimer.current = null
      setVisible(false)
    }, HIDE_DELAY_MS)
  }, [clearHideTimer])
  const measure = useCallback(() => {
    const element = target.current
    if (!element) return false
    const remaining = element.scrollHeight - element.clientHeight - element.scrollTop
    const canScrollUp = element.scrollTop > EDGE_THRESHOLD
    const canScrollDown = remaining > EDGE_THRESHOLD
    setEdges((previous) =>
      previous.canScrollUp === canScrollUp && previous.canScrollDown === canScrollDown
        ? previous
        : { canScrollUp, canScrollDown },
    )
    return canScrollUp || canScrollDown
  }, [target])
  const reveal = useCallback(() => {
    if (!measure()) {
      setVisible(false)
      return
    }
    setVisible(true)
    scheduleHide()
  }, [measure, scheduleHide])
  useEffect(() => {
    const element = target.current
    if (!element) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (SCROLL_KEYS.has(event.key)) reveal()
    }
    element.addEventListener('wheel', reveal, { passive: true })
    element.addEventListener('touchmove', reveal, { passive: true })
    element.addEventListener('scroll', measure, { passive: true })
    element.addEventListener('keydown', onKeyDown)
    window.addEventListener('resize', measure)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    if (observer) {
      observer.observe(element)
      if (element.firstElementChild) observer.observe(element.firstElementChild)
    }
    measure()
    return () => {
      element.removeEventListener('wheel', reveal)
      element.removeEventListener('touchmove', reveal)
      element.removeEventListener('scroll', measure)
      element.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('resize', measure)
      observer?.disconnect()
      clearHideTimer()
    }
  }, [target, reveal, measure, clearHideTimer])
  const scrollToEdge = (edge: 'top' | 'bottom') => {
    const element = target.current
    if (!element) return
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false
    element.scrollTo({
      top: edge === 'top' ? 0 : element.scrollHeight,
      behavior: reduced ? 'auto' : 'smooth',
    })
    scheduleHide()
  }
  if (!visible || (!edges.canScrollUp && !edges.canScrollDown)) return null
  const toTop = edges.canScrollUp
  return (
    <div
      className={`scroll-edge-controls ${className}`.trim()}
      role="group"
      aria-label="页面滚动"
      onMouseEnter={clearHideTimer}
      onMouseLeave={scheduleHide}
      onFocus={clearHideTimer}
      onBlur={scheduleHide}
    >
      {toTop ? (
        <button
          type="button"
          className="scroll-edge-button"
          aria-label="返回顶部"
          title="返回顶部"
          onClick={() => scrollToEdge('top')}
        >
          <ArrowUp size={18} aria-hidden="true" />
        </button>
      ) : (
        <button
          type="button"
          className="scroll-edge-button"
          aria-label="返回底部"
          title="返回底部"
          onClick={() => scrollToEdge('bottom')}
        >
          <ArrowDown size={18} aria-hidden="true" />
        </button>
      )}
    </div>
  )
}

// iframe元素属于父文档，不随子文档根画布变色。仅借用自身frame的颜色，绝不修改父容器。
const surface = '#171a1e'
const sides = ['top', 'right', 'bottom', 'left'] as const
const noop = () => {}
type OwnedStyle = { property: string; before: string; priority: string; assigned: string }

export function installImmersiveFrameSurface(win: Window = window): () => void {
  let frame: HTMLIFrameElement
  let doc: Document
  try {
    const element = win.frameElement
    if (!element || element.tagName !== 'IFRAME') return noop
    frame = element as HTMLIFrameElement
    if (frame.contentWindow !== win) return noop
    doc = win.document
  } catch {
    return noop
  }
  const owner = frame.ownerDocument.defaultView
  if (!owner) return noop
  const hadStyle = frame.hasAttribute('style')
  const owned: OwnedStyle[] = []
  const relinquished = new Set<string>()
  let stopped = false
  let hidden = false
  let watchedParent: HTMLElement | null = null
  const matches = (entry: OwnedStyle) =>
    frame.style.getPropertyValue(entry.property) === entry.assigned &&
    frame.style.getPropertyPriority(entry.property) === entry.priority
  const release = (entry: OwnedStyle) => {
    if (matches(entry)) {
      if (entry.before) frame.style.setProperty(entry.property, entry.before, entry.priority)
      else frame.style.removeProperty(entry.property)
    } else relinquished.add(entry.property) // 宿主新值优先，不在后续resize抢回。
  }
  const releaseAll = () => {
    for (const entry of owned) release(entry)
    owned.length = 0
    if (!hadStyle && frame.style.length === 0) frame.removeAttribute('style')
  }
  const update = () => {
    if (stopped || hidden) return
    try {
      const wanted = new Set<string>()
      const box = frame.getBoundingClientRect()
      // CSS视口宽度不代表设备屏宽：平板/宿主缩放/WebKit取整均可能超过700。
      // 主题跟随沉浸生命周期，不跟随响应式布局断点。
      if (frame.isConnected && box.width > 0 && box.height > 0) {
        const style = owner.getComputedStyle(frame)
        wanted.add('background-color')
        for (const side of sides) {
          const width = Number.parseFloat(style.getPropertyValue(`border-${side}-width`)) || 0
          if (width <= 2) wanted.add(`border-${side}-color`)
        }
      }
      for (let i = owned.length - 1; i >= 0; i--) {
        const entry = owned[i]!
        if (!wanted.has(entry.property) || !matches(entry)) {
          release(entry)
          owned.splice(i, 1)
        }
      }
      for (const property of wanted) {
        if (relinquished.has(property) || owned.some((entry) => entry.property === property)) continue
        const before = frame.style.getPropertyValue(property)
        const priority = frame.style.getPropertyPriority(property)
        // 不提升宿主声明的优先级，不改宽度/位置/厚边框/outline/阴影或任何祖先。
        frame.style.setProperty(property, surface, priority)
        owned.push({ property, before, priority, assigned: frame.style.getPropertyValue(property) })
      }
      if (!hadStyle && frame.style.length === 0) frame.removeAttribute('style')
      if (watchedParent !== frame.parentElement) observe()
    } catch {
      // 装饰处理失败不能影响路由、鉴权或根画布恢复。
      try {
        releaseAll()
      } catch {
        /* 浏览上下文已关闭时不继续读写。 */
      }
    }
  }
  const mutation = typeof MutationObserver === 'undefined' ? null : new MutationObserver(update)
  const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update)
  const observe = () => {
    mutation?.disconnect()
    watchedParent = frame.parentElement
    mutation?.observe(frame, { attributes: true, attributeFilter: ['style', 'class'] })
    if (watchedParent) mutation?.observe(watchedParent, { childList: true })
    resize?.observe(frame)
  }
  const hide = () => {
    hidden = true
    mutation?.disconnect()
    resize?.disconnect()
    try {
      releaseAll()
    } catch {
      /* 导航过程中的装饰清理不能阻断导航。 */
    }
  }
  const show = (event: PageTransitionEvent) => {
    if (!stopped && event.persisted) {
      hidden = false
      update()
      observe()
    }
  }
  const stop = () => {
    if (stopped) return
    stopped = true
    hide()
    try {
      win.removeEventListener('resize', update)
      win.removeEventListener('pagehide', hide)
      win.removeEventListener('pageshow', show)
    } catch {
      /* 导航后的Window访问可能已受跨源限制。 */
    }
    frame.removeEventListener('load', navigation)
  }
  const navigation = () => {
    // 完整Document导航不保证React卸载；父文档持有的frame不能遗留旧颜色。
    try {
      if (frame.contentDocument !== doc) stop()
    } catch {
      stop()
    }
  }
  win.addEventListener('resize', update)
  win.addEventListener('pagehide', hide)
  win.addEventListener('pageshow', show)
  frame.addEventListener('load', navigation)
  update()
  observe()
  return stop
}

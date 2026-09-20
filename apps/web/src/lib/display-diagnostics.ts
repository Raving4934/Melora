import { APP_VERSION } from './version'

const maxAncestors = 6
const maxScripts = 32
const assetPattern = /^(?:\.\/|\/app\/melora\/|\/)?assets\/(index-[A-Za-z0-9_-]{6,64}\.js)$/
const overflowValues = ['visible', 'hidden', 'clip', 'scroll', 'auto']
const borderValues = [
  'none',
  'hidden',
  'solid',
  'dashed',
  'dotted',
  'double',
  'groove',
  'ridge',
  'inset',
  'outset',
]

// 只返回固定状态，不传播外部 getter 的异常名称、消息或对象。
function read<T>(collect: () => T): T | { status: 'unavailable' } {
  try {
    return collect()
  } catch {
    return { status: 'unavailable' }
  }
}

function finite(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function enumeration(value: unknown, allowed: readonly string[]): string | null {
  return typeof value === 'string' && allowed.includes(value) ? value : null
}

function pixels(value: unknown): number | null {
  if (typeof value !== 'string' || value.length > 40 || !/^-?(?:\d+(?:\.\d+)?|\.\d+)px$/.test(value))
    return null
  return finite(Number.parseFloat(value))
}

function color(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 100) return null
  // 不接受 var()/url()/命名标识符或任意 CSS 文本；未知色彩语法宁可不收集。
  if (/^#[\da-f]{3,4}$/i.test(value) || /^#[\da-f]{6}(?:[\da-f]{2})?$/i.test(value)) return value
  if (
    /^rgba?\(\s*[\d.]+%?(?:\s*,\s*|\s+)[\d.]+%?(?:\s*,\s*|\s+)[\d.]+%?(?:\s*[,/]\s*[\d.]+%?)?\s*\)$/i.test(
      value,
    )
  )
    return value
  return value === 'transparent' ? value : null
}

function present(value: unknown): boolean | null {
  return typeof value === 'string' && value !== '' ? value !== 'none' : null
}

function styles(node: Element) {
  const owner = node.ownerDocument.defaultView
  if (!owner) return { status: 'unavailable' as const }
  const style = owner.getComputedStyle(node)
  return {
    backgroundColor: color(style.backgroundColor),
    position: enumeration(style.position, ['static', 'relative', 'absolute', 'fixed', 'sticky']),
    overflowX: enumeration(style.overflowX, overflowValues),
    overflowY: enumeration(style.overflowY, overflowValues),
    borderTopWidthPx: pixels(style.borderTopWidth),
    borderRightWidthPx: pixels(style.borderRightWidth),
    borderBottomWidthPx: pixels(style.borderBottomWidth),
    borderLeftWidthPx: pixels(style.borderLeftWidth),
    borderRightStyle: enumeration(style.borderRightStyle, borderValues),
    borderBottomStyle: enumeration(style.borderBottomStyle, borderValues),
    borderRightColor: color(style.borderRightColor),
    borderBottomColor: color(style.borderBottomColor),
    outlineStyle: enumeration(style.outlineStyle, borderValues),
    outlineWidthPx: pixels(style.outlineWidth),
    outlineColor: color(style.outlineColor),
    scrollbarWidth: enumeration(style.scrollbarWidth, ['auto', 'thin', 'none']),
    // 装饰/裁切声明可能携带 URL 或用户字符串；一律只记录存在性，不做字符串脱敏。
    transformPresent: present(style.transform),
    filterPresent: present(style.filter),
    backdropFilterPresent: present(style.backdropFilter),
    clipPathPresent: present(style.clipPath),
    maskPresent: present(style.maskImage),
    borderImagePresent: present(style.borderImageSource),
    boxShadowPresent: present(style.boxShadow),
    paintContainment:
      typeof style.contain === 'string'
        ? style.contain.split(/\s+/).some((value) => ['paint', 'strict', 'content'].includes(value))
        : null,
    zoom:
      typeof style.zoom === 'string' && style.zoom.length <= 40 && /^\d+(?:\.\d+)?$/.test(style.zoom)
        ? finite(Number(style.zoom))
        : null,
    borderTopLeftRadiusPx: pixels(style.borderTopLeftRadius),
    borderTopRightRadiusPx: pixels(style.borderTopRightRadius),
    borderBottomRightRadiusPx: pixels(style.borderBottomRightRadius),
    borderBottomLeftRadiusPx: pixels(style.borderBottomLeftRadius),
  }
}

function element(node: Element | null) {
  if (!node) return { status: 'missing' as const }
  return read(() => {
    const box = node.getBoundingClientRect()
    return {
      status: 'ok' as const,
      rect: {
        x: finite(box.x),
        y: finite(box.y),
        width: finite(box.width),
        height: finite(box.height),
        right: finite(box.right),
        bottom: finite(box.bottom),
      },
      scroll: {
        width: finite(node.scrollWidth),
        height: finite(node.scrollHeight),
        clientWidth: finite(node.clientWidth),
        clientHeight: finite(node.clientHeight),
        left: finite(node.scrollLeft),
        top: finite(node.scrollTop),
      },
      style: read(() => styles(node)),
    }
  })
}

function assets(doc: Document) {
  const scripts = doc.scripts
  const length = finite(scripts.length)
  if (length === null || length < 0) return { status: 'unavailable' as const }
  const total = Math.floor(length)
  const examined = Math.min(total, maxScripts)
  const files: string[] = []
  for (let i = 0; i < examined; i++) {
    // 只检查有界数量的原始 src 属性；不解析/输出 URL，也不访问 location/baseURI。
    const file = read(() => {
      const src = scripts[i]?.getAttribute('src')
      return typeof src === 'string' && src.length <= 160 ? assetPattern.exec(src)?.[1] : undefined
    })
    if (typeof file === 'string') files.push(file)
  }
  return { total, examined, files, unknownCount: total - files.length, truncated: total > examined }
}

function frame(win: Window) {
  const embedded = win.self !== win.top
  const own = win.frameElement
  if (!own) return { status: embedded ? 'unavailable' : 'standalone', embedded }
  if (own.tagName !== 'IFRAME' || (own as HTMLIFrameElement).contentWindow !== win) {
    return { status: 'identity-mismatch', embedded }
  }
  const ancestors: ReturnType<typeof element>[] = []
  let parent = own.parentElement
  for (let depth = 0; parent && depth < maxAncestors; depth++) {
    ancestors.push(element(parent))
    parent = parent.parentElement
  }
  return { status: 'ok', embedded, element: element(own), ancestors, truncated: !!parent }
}

// 仅由用户手动触发；无监听、计时器、请求、DOM/样式写入、存储访问或跨调用缓存。
export function collectDisplayDiagnostics(win: Window = window): string {
  return JSON.stringify(
    {
      schema: 'melora-display-diagnostics-v1',
      appVersion: APP_VERSION,
      surface: read(() => ({
        mode: enumeration(win.document.documentElement.getAttribute('data-melora-surface'), [
          'page',
          'immersive',
        ]),
        themeColor: color(win.document.querySelector('meta[name="theme-color"]')?.getAttribute('content')),
      })),
      viewport: read(() => {
        const visual = win.visualViewport
        return {
          width: finite(win.innerWidth),
          height: finite(win.innerHeight),
          dpr: finite(win.devicePixelRatio),
          visual: visual
            ? {
                width: finite(visual.width),
                height: finite(visual.height),
                scale: finite(visual.scale),
                offsetLeft: finite(visual.offsetLeft),
                offsetTop: finite(visual.offsetTop),
              }
            : null,
        }
      }),
      local: read(() => {
        const doc = win.document
        return {
          html: read(() => element(doc.documentElement)),
          body: read(() => element(doc.body)),
          root: read(() => element(doc.querySelector('#root'))),
          player: read(() => element(doc.querySelector('.player-immersive'))),
          atmosphere: read(() => element(doc.querySelector('.player-atmosphere'))),
        }
      }),
      assets: read(() => assets(win.document)),
      frame: read(() => frame(win)),
    },
    null,
    2,
  )
}

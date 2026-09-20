import { afterEach, expect, it, vi } from 'vitest'
import { collectDisplayDiagnostics } from './display-diagnostics'
import { APP_VERSION } from './version'

const cleanups: (() => void)[] = []
function add<T extends Element>(node: T, parent: Element = document.body): T {
  parent.append(node)
  cleanups.push(() => node.remove())
  return node
}
function app(doc: Document = document) {
  const root = doc.createElement('div')
  root.id = 'root'
  root.innerHTML = '<div class="player-immersive"><div class="player-atmosphere"></div></div>'
  add(root, doc.body)
  return root
}
function standalone(overrides: Record<string, unknown> = {}): Window {
  const win = {
    document,
    innerWidth: 390,
    innerHeight: 641,
    devicePixelRatio: 2.625,
    visualViewport: null,
    frameElement: null,
    ...overrides,
  }
  Object.assign(win, { self: win, top: win })
  return win as unknown as Window
}
function result(win: Window = standalone()) {
  return JSON.parse(collectDisplayDiagnostics(win))
}
function script(src: string) {
  const node = document.createElement('script')
  node.setAttribute('src', src)
  return add(node, document.head)
}
function embedded(depth = 1) {
  let parent: Element = document.body
  for (let i = 0; i < depth; i++) parent = add(document.createElement('div'), parent)
  const node = add(document.createElement('iframe'), parent)
  return { node, parent, win: node.contentWindow! }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  cleanups
    .splice(0)
    .reverse()
    .forEach((cleanup) => cleanup())
})

it('正常采集只含版本、白名单画布、有限数值、局部几何和本应用hash资产名', () => {
  const root = app()
  root.style.backgroundColor = 'rgb(23, 26, 30)'
  vi.spyOn(root, 'getBoundingClientRect').mockReturnValue(new DOMRect(0.375, 0.25, 390.375, 640.5))
  const getAttribute = document.documentElement.getAttribute.bind(document.documentElement)
  vi.spyOn(document.documentElement, 'getAttribute').mockImplementation((name) =>
    name === 'data-melora-surface' ? 'immersive' : getAttribute(name),
  )
  const meta = add(document.createElement('meta'), document.head)
  meta.name = 'theme-color'
  meta.content = '#171a1e'
  script('./assets/index-AbCd_123.js')
  script('/app/melora/assets/index-12abCD_9.js')
  const data = result(
    standalone({ visualViewport: { width: 390, height: 630.5, scale: 1, offsetLeft: 0, offsetTop: 2 } }),
  )
  expect(data.schema).toBe('melora-display-diagnostics-v1')
  expect(data.appVersion).toBe(APP_VERSION)
  expect(data.surface).toEqual({ mode: 'immersive', themeColor: '#171a1e' })
  expect(data.viewport).toEqual({
    width: 390,
    height: 641,
    dpr: 2.625,
    visual: { width: 390, height: 630.5, scale: 1, offsetLeft: 0, offsetTop: 2 },
  })
  expect(data.local.root.rect).toEqual({
    x: 0.375,
    y: 0.25,
    width: 390.375,
    height: 640.5,
    right: 390.75,
    bottom: 640.75,
  })
  expect(data.local.root.style.backgroundColor).toBe('rgb(23, 26, 30)')
  expect(data.assets.files).toEqual(['index-AbCd_123.js', 'index-12abCD_9.js'])
  expect(data.assets.unknownCount).toBe(0)
})

it('缺节点、独立文档和不可用visualViewport有显式安全状态', () => {
  const data = result()
  expect(data.frame).toEqual({ status: 'standalone', embedded: false })
  for (const name of ['root', 'player', 'atmosphere']) expect(data.local[name]).toEqual({ status: 'missing' })
  expect(data.local.html.status).toBe('ok')
  expect(data.viewport.visual).toBeNull()
})

it('默认参数可在当前window采集；NaN/Infinity不作为有效数值输出', () => {
  expect(JSON.parse(collectDisplayDiagnostics()).appVersion).toBe(APP_VERSION)
  expect(
    result(standalone({ innerWidth: NaN, innerHeight: Infinity, devicePixelRatio: 'secret' })).viewport,
  ).toEqual({ width: null, height: null, dpr: null, visual: null })
})

it('同源仅收集身份匹配的自身frame和祖先，不收集兄弟内容', () => {
  const { node, parent, win } = embedded()
  app(win.document)
  parent.setAttribute('style', 'overflow: hidden; clip-path: inset(0px)')
  node.setAttribute('style', 'border: 0; background-color: rgb(23, 26, 30)')
  vi.spyOn(node, 'getBoundingClientRect').mockReturnValue(new DOMRect(14.375, 20.25, 390.375, 640.5))
  const sibling = add(document.createElement('div'), parent)
  const unused = vi.spyOn(sibling, 'getBoundingClientRect')
  const data = result(win)
  expect(data.frame.status).toBe('ok')
  expect(data.frame.embedded).toBe(true)
  expect(data.frame.element.rect.right).toBe(404.75)
  expect(data.frame.element.style.borderRightWidthPx).toBe(0)
  expect(data.frame.ancestors).toHaveLength(3)
  expect(data.frame.ancestors[0].style.clipPathPresent).toBe(true)
  expect(data.frame.truncated).toBe(false)
  expect(unused).not.toHaveBeenCalled()
})

it('祖先最多6层，未扫描第7层几何，截断可见', () => {
  const { node, win } = embedded(8)
  let seventh = node.parentElement!
  for (let i = 1; i < 7; i++) seventh = seventh.parentElement!
  const untouched = vi.spyOn(seventh, 'getBoundingClientRect')
  const data = result(win)
  expect(data.frame.ancestors).toHaveLength(6)
  expect(data.frame.truncated).toBe(true)
  expect(untouched).not.toHaveBeenCalled()
})

it('嵌入但frameElement不可访问时不误报独立；身份不匹配不扫描frame', () => {
  const win = standalone()
  Object.defineProperty(win, 'top', { value: {}, configurable: true })
  expect(result(win).frame).toEqual({ status: 'unavailable', embedded: true })
  const { node } = embedded()
  Object.defineProperty(win, 'frameElement', { value: node, configurable: true })
  const untouched = vi.spyOn(node, 'getBoundingClientRect')
  expect(result(win).frame.status).toBe('identity-mismatch')
  expect(untouched).not.toHaveBeenCalled()
})

it.each(['frameElement', 'document', 'top', 'visualViewport'])(
  '跨源/关闭上下文的%s getter失败不传播异常数据',
  (key) => {
    const win = standalone()
    Object.defineProperty(win, key, {
      get() {
        throw new Error('PRIVATE_ERROR https://nas/private?token=123#secret')
      },
    })
    const text = collectDisplayDiagnostics(win)
    expect(text).not.toMatch(/PRIVATE_ERROR|https:|token|#secret/)
    const data = JSON.parse(text)
    expect(data.appVersion).toBe(APP_VERSION)
    if (key === 'document') expect(data.local.status).toBe('unavailable')
    else if (key === 'visualViewport') expect(data.viewport.status).toBe('unavailable')
    else expect(data.frame.status).toBe('unavailable')
  },
)

it('单节点几何或计算样式失败仍可返回其它字段，异常消息不可见', () => {
  const root = app()
  vi.spyOn(root, 'getBoundingClientRect').mockImplementation(() => {
    throw new Error('PRIVATE_RECT')
  })
  const computed = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation((node) => {
    if (node === document.body) throw new Error('PRIVATE_STYLE')
    return computed(node)
  })
  const text = collectDisplayDiagnostics(standalone())
  const data = JSON.parse(text)
  expect(data.local.root.status).toBe('unavailable')
  expect(data.local.body.style.status).toBe('unavailable')
  expect(data.viewport.width).toBe(390)
  expect(text).not.toContain('PRIVATE_')
})

it('敏感文本/属性/URL/CSS/未知资产只丢弃或记存在性，不使用不完备URL字符串脱敏', () => {
  const secret = 'PRIVATE_SENTINEL'
  const root = app()
  root.setAttribute('title', secret)
  root.setAttribute('data-user', `/home/${secret}`)
  add(document.createElement('p'), root).textContent = secret
  const getAttribute = document.documentElement.getAttribute.bind(document.documentElement)
  vi.spyOn(document.documentElement, 'getAttribute').mockImplementation((name) =>
    name === 'data-melora-surface' ? secret : getAttribute(name),
  )
  const meta = add(document.createElement('meta'), document.head)
  meta.name = 'theme-color'
  meta.content = secret
  script(`https://nas/${secret}/assets/index-AbCd_123.js?token=${secret}#${secret}`)
  script(`/assets/${secret}.js`)
  script(`/assets/index-AbCd_123.js?${secret}`)
  script(`./assets/index-AbCd_123.js#${secret}`)
  script('./assets/index-safeHASH.js')
  const computed = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation(
    (node) =>
      new Proxy(computed(node), {
        get(target, key) {
          if (['filter', 'clipPath', 'boxShadow', 'maskImage', 'transform'].includes(String(key)))
            return `u\\72l("https://nas/${secret}")`
          if (
            [
              'backgroundColor',
              'overflowX',
              'zoom',
              'borderRightWidth',
              'borderRightColor',
              'position',
            ].includes(String(key))
          )
            return secret
          return Reflect.get(target, key, target)
        },
      }),
  )
  const text = collectDisplayDiagnostics(standalone())
  expect(text).not.toMatch(/PRIVATE_SENTINEL|https:|\/home\/|token=|title|cookie|class|"id"/)
  const data = JSON.parse(text)
  expect(data.surface).toEqual({ mode: null, themeColor: null })
  expect(data.assets.files).toEqual(['index-safeHASH.js'])
  expect(data.assets.unknownCount).toBe(4)
  expect(data.local.root.style.filterPresent).toBe(true)
  expect(data.local.root.style.clipPathPresent).toBe(true)
  expect(data.local.root.style.backgroundColor).toBeNull()
})

it('script扫描最多32项，未知和未检查资产仅计数', () => {
  const nodes = Array.from({ length: 40 }, (_, i) =>
    script(`./assets/index-HASH${String(i).padStart(4, '0')}.js`),
  )
  const untouched = vi.spyOn(nodes[32]!, 'getAttribute')
  const data = result()
  expect(data.assets).toMatchObject({ total: 40, examined: 32, unknownCount: 8, truncated: true })
  expect(data.assets.files).toHaveLength(32)
  expect(untouched).not.toHaveBeenCalled()
})

it('手动重复采集不修改DOM/样式/存储、不读取存储、不请求API或注册监听', () => {
  app()
  script('./assets/index-AbCd_123.js')
  localStorage.setItem('display-diagnostic-test', 'LOCAL_PRIVATE')
  sessionStorage.setItem('display-diagnostic-test', 'SESSION_PRIVATE')
  cleanups.push(() => {
    localStorage.removeItem('display-diagnostic-test')
    sessionStorage.removeItem('display-diagnostic-test')
  })
  const before = document.documentElement.outerHTML
  const storage = ['getItem', 'setItem', 'removeItem', 'clear', 'key'].map((key) =>
    vi.spyOn(Storage.prototype, key as 'getItem'),
  )
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  const listener = vi.spyOn(window, 'addEventListener')
  const win = standalone({ fetch })
  const forbidden = ['location', 'navigator', 'localStorage', 'sessionStorage'].map((key) => {
    const getter = vi.fn(() => {
      throw new Error('禁止读取敏感来源')
    })
    Object.defineProperty(win, key, { get: getter })
    return getter
  })
  const one = collectDisplayDiagnostics(win)
  const two = collectDisplayDiagnostics(win)
  expect(two).toBe(one)
  expect(document.documentElement.outerHTML).toBe(before)
  expect(fetch).not.toHaveBeenCalled()
  expect(listener).not.toHaveBeenCalled()
  for (const getter of forbidden) expect(getter).not.toHaveBeenCalled()
  for (const spy of storage) expect(spy).not.toHaveBeenCalled()
  expect(one).not.toMatch(/LOCAL_PRIVATE|SESSION_PRIVATE/)
  for (const spy of storage) spy.mockRestore()
  expect(localStorage.getItem('display-diagnostic-test')).toBe('LOCAL_PRIVATE')
  expect(sessionStorage.getItem('display-diagnostic-test')).toBe('SESSION_PRIVATE')
})

it('异常脚本计数不输出任意字符串；单个src读取失败只计入未知资产', () => {
  const node = script('./assets/index-AbCd_123.js')
  vi.spyOn(node, 'getAttribute').mockImplementation(() => {
    throw new Error('PRIVATE_SRC')
  })
  expect(result().assets).toMatchObject({ total: 1, files: [], unknownCount: 1 })
  const scripts = document.scripts
  vi.spyOn(document, 'scripts', 'get').mockReturnValue(
    new Proxy(scripts, {
      get(target, key) {
        return key === 'length' ? 'PRIVATE_LENGTH' : Reflect.get(target, key, target)
      },
    }),
  )
  const text = collectDisplayDiagnostics(standalone())
  expect(JSON.parse(text).assets).toEqual({ status: 'unavailable' })
  expect(text).not.toMatch(/PRIVATE_SRC|PRIVATE_LENGTH/)
})

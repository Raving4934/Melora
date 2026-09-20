import { afterEach, describe, expect, it, vi } from 'vitest'
import { installImmersiveFrameSurface } from './immersive-frame-surface'
const dark = 'rgb(23, 26, 30)'
const cleanups: (() => void)[] = []
function fixture(gap = 1, border = 1) {
  const parent = document.createElement('div')
  parent.style.cssText = 'background-color:white;width:390px;height:640px'
  const frame = document.createElement('iframe')
  frame.style.cssText = `border:${border}px solid white;background-color:white;width:389px;height:639px`
  parent.append(frame)
  document.body.append(parent)
  const win = frame.contentWindow!
  Object.defineProperty(win, 'innerWidth', { configurable: true, value: 390 })
  vi.spyOn(parent, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 390, 640))
  vi.spyOn(frame, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 390 - gap, 640 - gap))
  cleanups.push(() => parent.remove())
  const start = () => {
    const stop = installImmersiveFrameSurface(win)
    cleanups.push(stop)
    return stop
  }
  return { parent, frame, win, start }
}
afterEach(() => {
  cleanups
    .splice(0)
    .reverse()
    .forEach((f) => f())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
describe('沉浸仅借用自己的边缘颜色，几何与宿主其余区域不动', () => {
  it('同源1px边框暗化但未知父容器不动，退出还原；不改width/height/outline', () => {
    const { parent, frame, start } = fixture()
    frame.style.outline = '2px solid orange'
    const before = [
      parent.style.width,
      parent.style.height,
      frame.style.width,
      frame.style.height,
      frame.style.outline,
    ]
    const stop = start()
    expect(frame.style.borderRightColor).toBe(dark)
    expect(frame.style.borderBottomColor).toBe(dark)
    expect(parent.style.backgroundColor).toBe('white')
    expect([
      parent.style.width,
      parent.style.height,
      frame.style.width,
      frame.style.height,
      frame.style.outline,
    ]).toEqual(before)
    stop()
    expect(frame.style.borderRightColor).toBe('white')
    expect(parent.style.backgroundColor).toBe('white')
  })
  it.each([0, 3, 10])('没有微缝或间隙为%s时不修改父容器', (gap) => {
    const { parent, frame, start } = fixture(gap)
    start()
    expect(parent.style.backgroundColor).toBe('white')
    expect(frame.style.borderRightColor).toBe(dark)
  })
  it.each([701, 768, 834, 1024, 1440])('CSS视口为%s的平板或缩放宿主也保持沉浸边色，几何不变', (width) => {
    const { parent, frame, win, start } = fixture()
    Object.defineProperty(win, 'innerWidth', { configurable: true, value: width })
    frame.style.transform = `scale(${390 / width})`
    const geometry = [frame.style.width, frame.style.height, frame.style.transform]
    const stop = start()
    expect(frame.style.backgroundColor).toBe(dark)
    expect(frame.style.borderRightColor).toBe(dark)
    expect(frame.style.borderBottomColor).toBe(dark)
    expect(parent.style.backgroundColor).toBe('white')
    expect([frame.style.width, frame.style.height, frame.style.transform]).toEqual(geometry)
    stop()
    expect(frame.style.borderRightColor).toBe('white')
    expect(frame.style.borderBottomColor).toBe('white')
    expect([frame.style.width, frame.style.height, frame.style.transform]).toEqual(geometry)
  })
  it('厚边框不改色，焦点与大装饰不在本应用接管范围', () => {
    const { frame, start } = fixture(0, 8)
    start()
    expect(frame.style.borderRightColor).toBe('white')
    expect(frame.style.backgroundColor).toBe(dark)
  })
  it.each(['element', 'text', 'image'])('父容器有%s内容时不借用', (kind) => {
    const { parent, frame, start } = fixture()
    if (kind === 'element') parent.append(document.createElement('button'))
    else if (kind === 'text') parent.append(document.createTextNode('host status'))
    else parent.style.backgroundImage = 'linear-gradient(white, red)'
    start()
    expect(parent.style.backgroundColor).toBe('white')
    expect(frame.style.borderBottomColor).toBe(dark)
  })
  it('宿主添加兄弟内容也不影响父容器', async () => {
    const { parent, start } = fixture()
    start()
    parent.append(document.createElement('div'))
    await Promise.resolve()
    expect(parent.style.backgroundColor).toBe('white')
  })
  it('不会修改顶层html/body，即使iframe是body唯一元素', () => {
    const { parent, frame, start } = fixture()
    const before = document.body.style.cssText
    document.body.append(frame)
    start()
    expect(document.body.style.cssText).toBe(before)
    expect(parent.style.backgroundColor).toBe('white')
    cleanups.push(() => frame.remove())
  })
  it('宿主改过的值不覆盖、不在resize时抢回；其它宿主样式保留', () => {
    const { parent, frame, win, start } = fixture()
    const stop = start()
    parent.style.backgroundColor = 'yellow'
    frame.style.setProperty('border-right-color', 'red', 'important')
    frame.style.width = '388px'
    win.dispatchEvent(new Event('resize'))
    expect(parent.style.backgroundColor).toBe('yellow')
    expect(frame.style.borderRightColor).toBe('red')
    stop()
    expect(parent.style.backgroundColor).toBe('yellow')
    expect(frame.style.borderRightColor).toBe('red')
    expect(frame.style.width).toBe('388px')
    expect(frame.style.borderBottomColor).toBe('white')
  })
  it('恢复原有important；重复安装/清理不会遗留深色', () => {
    const { frame, start } = fixture()
    frame.style.setProperty('background-color', 'white', 'important')
    for (let i = 0; i < 4; i++) {
      const stop = start()
      expect(frame.style.backgroundColor).toBe(dark)
      stop()
      stop()
      expect(frame.style.backgroundColor).toBe('white')
      expect(frame.style.getPropertyPriority('background-color')).toBe('important')
    }
  })
  it('跨越桌面尺寸仍保持沉浸主题，只有离开才还原；宿主不动', () => {
    const { parent, frame, win, start } = fixture()
    const stop = start()
    for (const width of [700, 701, 1024, 390]) {
      Object.defineProperty(win, 'innerWidth', { configurable: true, value: width })
      win.dispatchEvent(new Event('resize'))
      expect(parent.style.backgroundColor).toBe('white')
      expect(frame.style.borderBottomColor).toBe(dark)
    }
    stop()
    expect(frame.style.borderBottomColor).toBe('white')
  })
  it('frame被移走时恢复，不把旧容器永远染黑', async () => {
    const { parent, frame, start } = fixture()
    start()
    frame.remove()
    await Promise.resolve()
    expect(parent.style.backgroundColor).toBe('white')
    expect(frame.style.borderBottomColor).toBe('white')
  })
  it('缺少可选observer仍可初始化、resize和清理，不轮询', () => {
    vi.stubGlobal('ResizeObserver', undefined)
    vi.stubGlobal('MutationObserver', undefined)
    const { parent, start } = fixture()
    const stop = start()
    expect(parent.style.backgroundColor).toBe('white')
    stop()
    expect(parent.style.backgroundColor).toBe('white')
  })
  it('顶层页面与不可访问frame均安全no-op', () => {
    const before = document.documentElement.outerHTML
    installImmersiveFrameSurface(window)()
    const inaccessible = {
      get frameElement() {
        throw new DOMException('blocked', 'SecurityError')
      },
    } as unknown as Window
    expect(() => installImmersiveFrameSurface(inaccessible)()).not.toThrow()
    expect(document.documentElement.outerHTML).toBe(before)
  })
})

it('pagehide还原，BFCache pageshow按原生命周期恢复，最终清理后不再申请', () => {
  const { frame, win, start } = fixture()
  const stop = start()
  win.dispatchEvent(new PageTransitionEvent('pagehide'))
  expect(frame.style.borderBottomColor).toBe('white')
  win.dispatchEvent(new Event('resize'))
  expect(frame.style.borderBottomColor).toBe('white')
  win.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
  expect(frame.style.borderBottomColor).toBe(dark)
  stop()
  win.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
  expect(frame.style.borderBottomColor).toBe('white')
})
it('新的Document载入清理旧效果，不依赖React卸载', () => {
  const { frame, start, win } = fixture()
  start()
  frame.src = 'about:blank#new-document'
  frame.dispatchEvent(new Event('load'))
  expect(frame.style.borderBottomColor).toBe('white')
  win.dispatchEvent(new Event('resize'))
  expect(frame.style.borderBottomColor).toBe('white')
})
it('原无style属性时清理不留下空style及其选择器副作用', () => {
  const { frame, start } = fixture()
  frame.removeAttribute('style')
  const stop = start()
  expect(frame.hasAttribute('style')).toBe(true)
  stop()
  expect(frame.hasAttribute('style')).toBe(false)
})
it('closed ShadowRoot隐藏的兄弟也不会被父背景修改影响', () => {
  const { parent, start } = fixture()
  const hidden = parent.attachShadow({ mode: 'closed' })
  const other = document.createElement('iframe')
  hidden.append(document.createElement('slot'), other)
  expect(parent.shadowRoot).toBeNull()
  start()
  expect(parent.style.backgroundColor).toBe('white')
  expect(other.hasAttribute('style')).toBe(false)
})

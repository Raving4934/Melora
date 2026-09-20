import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useRef } from 'react'
import { ScrollEdgeControls } from './ScrollEdgeControls'

const scrollTo = vi.fn()
const scrollToDescriptor = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTo')

function place(element: HTMLElement, scrollTop: number, scrollHeight: number, clientHeight: number) {
  Object.defineProperty(element, 'scrollTop', { value: scrollTop, writable: true, configurable: true })
  Object.defineProperty(element, 'scrollHeight', { value: scrollHeight, configurable: true })
  Object.defineProperty(element, 'clientHeight', { value: clientHeight, configurable: true })
}

function Harness() {
  const target = useRef<HTMLDivElement>(null)
  return (
    <div ref={target} data-testid="scroller">
      <div>页面内容</div>
      <ScrollEdgeControls target={target} />
    </div>
  )
}

function scroller() {
  return screen.getByTestId('scroller')
}

beforeEach(() => {
  scrollTo.mockClear()
  Object.defineProperty(Element.prototype, 'scrollTo', {
    value: scrollTo,
    writable: true,
    configurable: true,
  })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  if (scrollToDescriptor) Object.defineProperty(Element.prototype, 'scrollTo', scrollToDescriptor)
  else delete (Element.prototype as { scrollTo?: unknown }).scrollTo
  vi.unstubAllGlobals()
})

it('初始不显示，向下滚动后出现单个返回底部按钮', () => {
  render(<Harness />)
  place(scroller(), 0, 2000, 600)
  fireEvent.scroll(scroller())
  expect(screen.queryByRole('group', { name: '页面滚动' })).not.toBeInTheDocument()
  fireEvent.wheel(scroller())
  expect(screen.getByRole('button', { name: '返回底部' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '返回顶部' })).not.toBeInTheDocument()
})

it('滚动到中段时切换为返回顶部', () => {
  render(<Harness />)
  place(scroller(), 800, 2000, 600)
  fireEvent.wheel(scroller())
  expect(screen.getByRole('button', { name: '返回顶部' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '返回底部' })).not.toBeInTheDocument()
})

it('内容不足以滚动时滚轮也不显示控件', () => {
  render(<Harness />)
  place(scroller(), 0, 600, 600)
  fireEvent.wheel(scroller())
  expect(screen.queryByRole('group', { name: '页面滚动' })).not.toBeInTheDocument()
})

it('停止操作后自动隐藏', () => {
  vi.useFakeTimers()
  render(<Harness />)
  place(scroller(), 0, 2000, 600)
  fireEvent.wheel(scroller())
  expect(screen.getByRole('button', { name: '返回底部' })).toBeInTheDocument()
  act(() => vi.advanceTimersByTime(2600))
  expect(screen.queryByRole('button', { name: '返回底部' })).not.toBeInTheDocument()
})

it('悬停保持显示，移开后重新计时隐藏', () => {
  vi.useFakeTimers()
  render(<Harness />)
  place(scroller(), 0, 2000, 600)
  fireEvent.wheel(scroller())
  const group = screen.getByRole('group', { name: '页面滚动' })
  fireEvent.mouseEnter(group)
  act(() => vi.advanceTimersByTime(3000))
  expect(screen.getByRole('button', { name: '返回底部' })).toBeInTheDocument()
  fireEvent.mouseLeave(group)
  act(() => vi.advanceTimersByTime(2600))
  expect(screen.queryByRole('button', { name: '返回底部' })).not.toBeInTheDocument()
})

it('减弱动效下点击即时跳转', () => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: true })),
  )
  render(<Harness />)
  place(scroller(), 800, 2000, 600)
  fireEvent.wheel(scroller())
  fireEvent.click(screen.getByRole('button', { name: '返回顶部' }))
  expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'auto' })
  place(scroller(), 0, 2000, 600)
  fireEvent.wheel(scroller())
  fireEvent.click(screen.getByRole('button', { name: '返回底部' }))
  expect(scrollTo).toHaveBeenCalledWith({ top: 2000, behavior: 'auto' })
})

it('未开启减弱动效时使用平滑滚动', () => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: false })),
  )
  render(<Harness />)
  place(scroller(), 800, 2000, 600)
  fireEvent.wheel(scroller())
  fireEvent.click(screen.getByRole('button', { name: '返回顶部' }))
  expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: 'smooth' })
})

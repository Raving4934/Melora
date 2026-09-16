import { StrictMode } from 'react'
import { cleanup, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { DocumentSurface, setDocumentSurface, isImmersivePath } from './document-surface'
import { ErrorBoundary } from '../App'

afterEach(() => {
  cleanup()
  setDocumentSurface(false)
  vi.restoreAllMocks()
})
it('沉浸画布和主题色同步；退出恢复page，不修改宿主或改变已有body布局', () => {
  const meta = document.createElement('meta')
  meta.name = 'theme-color'
  meta.content = '#ffffff'
  document.head.append(meta)
  const bodyStyle = document.body.getAttribute('style')
  try {
    const view = render(
      <StrictMode>
        <DocumentSurface immersive />
      </StrictMode>,
    )
    expect(document.documentElement.dataset.meloraSurface).toBe('immersive')
    expect(meta.content).toBe('#171a1e')
    expect(document.body.getAttribute('style')).toBe(bodyStyle)
    view.rerender(
      <StrictMode>
        <DocumentSurface immersive={false} />
      </StrictMode>,
    )
    expect(document.documentElement.dataset.meloraSurface).toBe('page')
    expect(meta.content).toBe('#ffffff')
    view.rerender(
      <StrictMode>
        <DocumentSurface immersive />
      </StrictMode>,
    )
    view.unmount()
    expect(document.documentElement.dataset.meloraSurface).toBe('page')
    expect(meta.content).toBe('#ffffff')
  } finally {
    meta.remove()
  }
})
it('缺少可选meta也能同步画布，不插入重复标签', () => {
  const doc = document.implementation.createHTMLDocument('fixture')
  setDocumentSurface(true, doc)
  expect(doc.documentElement.dataset.meloraSurface).toBe('immersive')
  expect(doc.querySelector('meta[name="theme-color"]')).toBeNull()
  setDocumentSurface(false, doc)
  expect(doc.documentElement.dataset.meloraSurface).toBe('page')
})
it('包括初次渲染失败在内，ErrorBoundary回退恢复page而非白底深色文本错配', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  setDocumentSurface(true)
  function Broken(): never {
    throw new Error('synthetic render failure')
  }
  const view = render(
    <ErrorBoundary>
      <DocumentSurface immersive />
      <Broken />
    </ErrorBoundary>,
  )
  expect(view.getByText('页面遇到了一点问题')).toBeTruthy()
  expect(document.documentElement.dataset.meloraSurface).toBe('page')
})

it('画布匹配遵循真实Route语义但不扩大服务端白名单', () => {
  for (const pathname of ['/now-playing', '/now-playing/', '/NOW-PLAYING', '/now%2Dplaying'])
    expect(isImmersivePath(pathname)).toBe(true)
  for (const pathname of ['/settings', '/now-playing-other', '/now-playing/other'])
    expect(isImmersivePath(pathname)).toBe(false)
  expect(isImmersivePath('/app/melora/now-playing', '/app/melora')).toBe(true)
  expect(isImmersivePath('/other/now-playing', '/app/melora')).toBe(false)
})

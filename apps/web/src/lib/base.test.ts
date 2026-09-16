import { afterEach, describe, expect, it } from 'vitest'
import { basePathFromDocument } from './base'
describe('应用路径前缀', () => {
  afterEach(() => document.querySelector('base')?.remove())
  it('独立开发模式保持根路径', () => {
    expect(basePathFromDocument()).toBe('')
  })
  it('读取服务端声明的 fnOS 网关前缀', () => {
    document.head.insertAdjacentHTML('afterbegin', '<base href="/app/melora/" data-melora-base>')
    expect(basePathFromDocument()).toBe('/app/melora')
  })
  it('不使用任意页面地址作为 API 前缀', () => {
    document.head.insertAdjacentHTML('afterbegin', '<base href="/other-app/" data-melora-base>')
    expect(basePathFromDocument()).toBe('')
  })
})

import { expect, it, vi } from 'vitest'
import { registerPWA, serviceWorkerPaths } from './pwa'

it('从可信 base 标签推导 Service Worker 地址和作用域', () => {
  const doc = document.implementation.createHTMLDocument('PWA fixture')
  const base = doc.createElement('base')
  base.href = 'https://nas.example/app/melora/'
  doc.head.append(base)

  expect(serviceWorkerPaths(doc)).toEqual({
    scriptURL: 'https://nas.example/app/melora/sw.js',
    scope: '/app/melora/',
  })
})

it('安全生产环境以子路径 scope 注册 Service Worker', async () => {
  const doc = document.implementation.createHTMLDocument('PWA fixture')
  const base = doc.createElement('base')
  base.href = 'https://nas.example/app/melora/'
  doc.head.append(base)
  const register = vi.fn().mockResolvedValue({})

  await registerPWA({
    enabled: true,
    secure: true,
    container: { register },
    doc,
  })

  expect(register).toHaveBeenCalledExactlyOnceWith('https://nas.example/app/melora/sw.js', {
    scope: '/app/melora/',
  })
})

it('开发环境和非安全上下文不注册 Service Worker', async () => {
  const register = vi.fn().mockResolvedValue({})
  const container = { register }

  await registerPWA({ enabled: false, secure: true, container })
  await registerPWA({ enabled: true, secure: false, container })

  expect(register).not.toHaveBeenCalled()
})

import { useLayoutEffect } from 'react'
import { matchRoutes } from 'react-router'
import { installImmersiveFrameSurface } from './immersive-frame-surface'

// 与实际客户端Route共用匹配语义，包含大小写、末尾斜杠与已编码的合法路径。
export function isImmersivePath(pathname: string, basename = '/') {
  return !!matchRoutes([{ path: '/now-playing' }], { pathname }, basename)
}

// 与 index.html 首帧画布和 Go 静态入口的固定颜色保持一致，不读取音频/队列。
export function setDocumentSurface(immersive: boolean, doc: Document = document) {
  doc.documentElement.dataset.meloraSurface = immersive ? 'immersive' : 'page'
  const meta = doc.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (meta) meta.content = immersive ? '#171a1e' : '#ffffff'
}

// 由 App 持有，不跟播放器进度重渲染；路由、鉴权和 ErrorBoundary 统一管理生命周期。
export function DocumentSurface({ immersive }: { immersive: boolean }) {
  useLayoutEffect(() => {
    setDocumentSurface(immersive)
    const restoreFrame = immersive ? installImmersiveFrameSurface() : () => {}
    return () => {
      try {
        restoreFrame()
      } finally {
        setDocumentSurface(false)
      }
    }
  }, [immersive])
  return null
}

type ServiceWorkerRegistrar = {
  register(scriptURL: string, options: { scope: string }): Promise<unknown>
}

type RegisterPWAOptions = {
  enabled?: boolean
  secure?: boolean
  container?: ServiceWorkerRegistrar
  doc?: Document
}

export function serviceWorkerPaths(doc: Document = document) {
  const base = new URL('./', doc.baseURI)
  return {
    scriptURL: new URL('sw.js', base).href,
    scope: base.pathname,
  }
}

export async function registerPWA({
  enabled = import.meta.env.PROD,
  secure = typeof window !== 'undefined' && window.isSecureContext,
  container = typeof navigator !== 'undefined' && 'serviceWorker' in navigator
    ? navigator.serviceWorker
    : undefined,
  doc = document,
}: RegisterPWAOptions = {}) {
  if (!enabled || !secure || !container) return
  const { scriptURL, scope } = serviceWorkerPaths(doc)
  try {
    return await container.register(scriptURL, { scope })
  } catch {
    // PWA 增强失败不能阻断音乐应用启动；浏览器仍可作为普通网页使用。
  }
}

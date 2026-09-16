// Go 静态入口注入 <base>；开发独立模式为 /，fnOS 网关为 /app/melora/。
// 不从任意查询参数或可伪造的 Header 推断应用前缀。
export function basePathFromDocument(doc: Document = document): string {
  const base = doc.querySelector<HTMLBaseElement>('base[data-melora-base]')
  const path = new URL(base?.href || '/', window.location.origin).pathname.replace(/\/$/, '')
  return path === '/app/melora' ? path : ''
}
export const appBasePath = basePathFromDocument()
export const appURL = (path: string) => `${appBasePath}${path.startsWith('/') ? '' : '/'}${path}`
export const apiURL = (path: string) => appURL(`/api/v1${path}`)
export const assetURL = (path: string) => (path.startsWith('/covers/') ? appURL(path) : path)

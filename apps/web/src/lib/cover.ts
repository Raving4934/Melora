// 与服务端 catalog.NormalizeCoverURL 保持一致：只改写酷我已失效 CDN 的
// 精确 authority，并保留同分片路径；其它提供方与异常输入原样返回。
export function normalizeCoverURL(raw?: string): string {
  if (!raw || raw.length > 2048) return raw || ''
  const match = /^(?:https?:)?\/\/img([1-4])\.kwcdn\.kuwo\.cn(\/[^?#]*)?([?#].*)?$/i.exec(raw)
  if (!match) return raw
  return `https://img${match[1]}.kuwo.cn${match[2] || '/'}${match[3] || ''}`
}

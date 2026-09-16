import { describe, expect, it } from 'vitest'
import { normalizeCoverURL } from './cover'

describe('旧封面缓存的严格归一化', () => {
  it.each(['http:', 'https:', ''])('失效 CDN %s 按原分片切到 HTTPS 镜像并保留 query/hash', (scheme) => {
    const value = normalizeCoverURL(`${scheme}//img2.kwcdn.kuwo.cn/star/upload/9/9/1543919747769_.png?a=1#b`)
    expect(value).toBe('https://img2.kuwo.cn/star/upload/9/9/1543919747769_.png?a=1#b')
    expect(normalizeCoverURL(value)).toBe(value)
  })
  it('可信故障 CDN 的其它路径也交给同分片镜像处理，不再提前丢封面', () => {
    expect(normalizeCoverURL('https://img1.kwcdn.kuwo.cn/unknown.jpg')).toBe(
      'https://img1.kuwo.cn/unknown.jpg',
    )
    expect(normalizeCoverURL('//IMG4.kwcdn.kuwo.cn/star/upload/../8/8/1543919795640_.png')).toBe(
      'https://img4.kuwo.cn/star/upload/../8/8/1543919795640_.png',
    )
  })
  it.each([
    '/covers/1.svg',
    'https://example.com/a.png',
    'https://img1.kwcdn.kuwo.cn.evil.example/a',
    'https://img1.kwcdn.kuwo.cn:443/a',
    'https://u@img1.kwcdn.kuwo.cn/a',
  ])('不盲改外部或异常输入 %s', (raw) => {
    expect(normalizeCoverURL(raw)).toBe(raw)
  })
})

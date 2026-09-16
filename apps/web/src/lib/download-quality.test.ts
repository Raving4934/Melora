import { describe, expect, it } from 'vitest'
import { downloadQualityDisplay } from './download-quality'

const base = {
  quality: 'flac',
  state: 'completed',
  bytesDone: 2 * 1024 * 1024,
  targetPath: '/music/Singles/爱如潮水(咚鼓版).mp3',
}

describe('下载请求音质与文件格式', () => {
  it('请求 FLAC、实得 MP3 时并列展示，绝不改写请求或推测码率', () => {
    const job = Object.freeze({ ...base })
    const display = downloadQualityDisplay(job)
    expect(display.requestedLabel).toBe('请求 无损·FLAC')
    expect(display.fileLabel).toBe('文件 MP3')
    expect(display.description).toContain('请求的 FLAC 与文件 MP3 不一致')
    expect(display.description).toContain('未更改请求音质')
    expect(display.fileLabel).not.toMatch(/无损|128|320|kbps/)
    expect(job.quality).toBe('flac')
  })

  it.each(['mp3', 'flac', 'm4a', 'aac', 'ogg', 'wav'])(
    '仅展示 %s 格式，不把格式等同实测品质',
    (extension) => {
      const display = downloadQualityDisplay({ ...base, targetPath: `/music/song.${extension}` })
      expect(display.fileFormat).toBe(extension.toUpperCase())
      expect(display.fileLabel).toBe(`文件 ${extension.toUpperCase()}`)
      expect(display.description).toContain('不代表实测码率、位深或无损来源')
    },
  )

  it.each(['MP3', 'FlAc', 'M4A'])('兼容已有任务大小写后缀 %s，无需迁移', (extension) => {
    expect(downloadQualityDisplay({ ...base, targetPath: `/music/旧歌曲.${extension}` }).fileFormat).toBe(
      extension.toUpperCase(),
    )
  })

  it.each([
    '',
    '/music/明天见.audio',
    '/music/song.mp3.part',
    '/music/album.flac/song',
    '/music/song.ape',
    '/music/song.opus',
    '/music/song.mp4',
    '/music/song.mp3/',
    '/music/song.mp3?token=hidden',
    '/music/song.mp3#fragment',
    'https://example.test/song.mp3',
    '//example.test/song.mp3',
    'song.mp3',
  ])('不把未确认路径 %s 当成已识别文件', (targetPath) => {
    const display = downloadQualityDisplay({ ...base, targetPath })
    expect(display.fileFormat).toBeNull()
    expect(display.fileLabel).toBe('文件 未识别')
    expect(display.requestedLabel).toBe('请求 无损·FLAC')
  })

  it.each([0, -1, NaN, Infinity, -Infinity])('已下载字节 %s 不构成文件证据', (bytesDone) => {
    expect(downloadQualityDisplay({ ...base, bytesDone }).fileFormat).toBeNull()
  })

  it.each(['unknown', '', 'new-server-state', 'queued', 'resolving', 'cancelled'])(
    '状态 %s 不提前宣称已识别文件',
    (state) => {
      expect(downloadQualityDisplay({ ...base, state }).fileFormat).toBeNull()
    },
  )

  it.each([
    'downloading',
    'paused',
    'retry_wait',
    'waiting_for_url_refresh',
    'verifying',
    'writing_metadata',
    'finalizing',
    'failed',
  ])('状态 %s 可展示已写数据格式，但不承诺保存成功', (state) => {
    const display = downloadQualityDisplay({ ...base, state })
    expect(display.fileLabel).toBe('文件 MP3')
    expect(display.description).toContain('任务尚未完成，不代表文件已保存成功')
  })

  it.each([
    ['standard', '请求 自动'],
    ['128k', '请求 标准·128 kbps'],
    ['320k', '请求 高品质·320 kbps'],
    ['flac24bit', '请求 Hi-Res·24bit'],
    ['custom', '请求 CUSTOM'],
    ['', '请求 未知'],
  ])('保留 %s 的请求语义，不根据实得格式反写', (quality, requestedLabel) => {
    expect(downloadQualityDisplay({ ...base, quality }).requestedLabel).toBe(requestedLabel)
  })

  it('请求 24bit 而实得 FLAC 也不声称位深已验证', () => {
    const display = downloadQualityDisplay({ ...base, quality: 'flac24bit', targetPath: '/music/song.flac' })
    expect(display.fileLabel).toBe('文件 FLAC')
    expect(display.fileLabel).not.toContain('24bit')
  })
})

export function formatTime(seconds: number) {
  const safe = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0
  return `${String(Math.floor(safe / 60)).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`
}
export function formatBytes(value: number) {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  return `${(value / 1024 ** index).toFixed(index > 0 ? 1 : 0)} ${units[index]}`
}
export const qualityName = (quality: string) => {
  const label = {
    standard: '自动',
    '128k': '标准 · 128 kbps',
    '320k': '高品质 · 320 kbps',
    flac: '无损 · FLAC',
    flac24bit: 'Hi-Res · 24bit',
    ape: '无损 · APE',
    wav: '无损 · WAV',
    high: '高品质',
    lossless: '无损音质',
    original: '原始音质',
  }[quality]
  if (label) return label
  const bitrate = /^(aac|ogg|mp3)?([1-9][0-9]{0,3})k$/i.exec(quality)
  return bitrate ? `${(bitrate[1] || 'MP3').toUpperCase()} · ${bitrate[2]} kbps` : quality.toUpperCase()
}
export const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : '操作失败，请重试。'

export function formatPlayCount(value: number) {
  if (!Number.isFinite(value) || value < 0) return ''
  if (value >= 100_000_000) return `${(value / 100_000_000).toFixed(1).replace(/\.0$/, '')}亿`
  if (value >= 10_000) return `${(value / 10_000).toFixed(1).replace(/\.0$/, '')}万`
  return Math.floor(value).toLocaleString('zh-CN')
}

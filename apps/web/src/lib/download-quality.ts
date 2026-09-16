import { qualityName } from './format'
import type { DownloadJob } from './types'

type DownloadQualityInput = Pick<DownloadJob, 'quality' | 'state' | 'targetPath' | 'bytesDone'>

// 只认当前下载器可按字节识别的格式；不根据请求音质、URL 或文件大小推测编码参数。
const fileFormats: Record<string, string> = {
  mp3: 'MP3',
  flac: 'FLAC',
  m4a: 'M4A',
  aac: 'AAC',
  ogg: 'OGG',
  wav: 'WAV',
}
const statesWithDownloadedData = new Set([
  'downloading',
  'paused',
  'retry_wait',
  'waiting_for_url_refresh',
  'verifying',
  'writing_metadata',
  'finalizing',
  'completed',
  'failed',
])

function downloadedFormat(job: DownloadQualityInput): string | null {
  if (!statesWithDownloadedData.has(job.state) || !Number.isFinite(job.bytesDone) || job.bytesDone <= 0)
    return null
  // targetPath 是后端本地绝对路径，不是媒体 URL；只读最终后缀，不把 .audio/临时文件当格式。
  if (!job.targetPath.startsWith('/') || job.targetPath.startsWith('//')) return null
  const name = job.targetPath.slice(job.targetPath.lastIndexOf('/') + 1)
  const extension = /\.([a-z0-9]+)$/i.exec(name)?.[1]?.toLowerCase()
  return extension && Object.hasOwn(fileFormats, extension) ? fileFormats[extension]! : null
}

export function downloadQualityDisplay(job: DownloadQualityInput) {
  const requested = qualityName(job.quality).replace(/\s*·\s*/g, '·') || '未知'
  const fileFormat = downloadedFormat(job)
  const requestedLabel = `请求 ${requested}`
  const fileLabel = fileFormat ? `文件 ${fileFormat}` : '文件 未识别'
  const detail = fileFormat
    ? `文件格式 ${fileFormat}，依据后端对已下载字节的识别；不代表实测码率、位深或无损来源。${job.state === 'completed' ? '' : '任务尚未完成，不代表文件已保存成功。'}`
    : '尚无可信的文件格式信息；占位路径、零字节或未确认状态不代表已识别音频。'
  const mismatch =
    (job.quality === 'flac' || job.quality === 'flac24bit') && fileFormat === 'MP3'
      ? '请求的 FLAC 与文件 MP3 不一致，未更改请求音质。'
      : ''
  return { requestedLabel, fileLabel, fileFormat, description: `${requestedLabel}。${detail}${mismatch}` }
}

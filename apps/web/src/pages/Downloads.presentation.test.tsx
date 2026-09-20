import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { DownloadsPage } from './Downloads'
import { queryClient } from '../lib/api'
import type { DownloadJob } from '../lib/types'

class DownloadEvents extends EventTarget {
  static OPEN = 1
  static current: DownloadEvents
  readyState = DownloadEvents.OPEN
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn()
  constructor(_url: string | URL) {
    super()
    DownloadEvents.current = this
  }
  emit(jobs: DownloadJob[]) {
    this.dispatchEvent(new MessageEvent('downloads', { data: JSON.stringify(jobs) }))
  }
}
const warning =
  '歌词或封面获取失败，已保留音频；封面缺失、不支持或与 MIME/尺寸限制不符（仅支持 JPEG/PNG）；来源 MIME 声明与音频内容不一致，已通过结构核验并按 FLAC 格式保存；文件格式不代表实测音质（media_mime_corrected）'
const job: DownloadJob = {
  id: 'v18-embedded',
  track: {
    id: 'wy:v18',
    providerId: 'wy',
    title: '此间少年',
    artist: '黄霄雲',
    album: '此间少年',
    duration: 180,
    coverUrl: '',
    qualities: ['flac'],
    canDownload: true,
  },
  quality: 'flac',
  state: 'completed',
  bytesDone: 26.5 * 1024 * 1024,
  bytesTotal: 26.5 * 1024 * 1024,
  speed: 0,
  targetPath: '/music/Singles/此间少年 - 黄霄雲.flac',
  embedTags: true,
  tagsWritten: true,
  warning,
  createdAt: '2026-09-09T04:30:00Z',
  updatedAt: '2026-09-09T04:30:00Z',
}

beforeAll(() => {
  // 只补 jsdom 缺失的宿主 dialog 方法，不替换生产组件或 details 行为。
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open')
  }
})
beforeEach(() => {
  queryClient.clear()
  vi.stubGlobal('EventSource', DownloadEvents)
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('本测试不应访问网络或用户文件')))
  queryClient.setQueryData(['/settings'], { concurrency: 1 })
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.unstubAllGlobals()
})
function show(current: DownloadJob = job) {
  queryClient.setQueryData(['/downloads'], [current])
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <DownloadsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
  if (current.state === 'completed') fireEvent.click(screen.getByRole('tab', { name: /^已完成/ }))
  if (['failed', 'cancelled'].includes(current.state))
    fireEvent.click(screen.getByRole('tab', { name: /^失败与取消/ }))
  return screen.getByRole('article')
}

// 通过可见性和真实 details 开关验证，不把 hidden 文本的存在冒充默认可见。
describe('v18 下载完成信息收敛', () => {
  it('完成卡片保留真实状态与文件格式，长技术 warning 默认收起且可展开/收回', () => {
    const row = within(show())
    expect(row.getByText('此间少年')).toBeVisible()
    expect(row.getByText('请求 无损·FLAC')).toBeVisible()
    expect(row.getByText('文件 FLAC')).toBeVisible()
    expect(row.getByText('文件 FLAC').parentElement).toHaveAttribute(
      'title',
      expect.stringContaining('不代表实测码率、位深或无损来源'),
    )
    expect(row.getByText('已完成')).toBeVisible()
    expect(row.getByText('26.5 MB / 26.5 MB')).toBeVisible()
    expect(row.getByText('已写入标签')).toBeVisible()
    const note = row.getByText(warning)
    expect(note).not.toBeVisible()
    const details = note.closest('details')
    expect(details).not.toHaveAttribute('open')
    const summary = row.getByText('下载详情')
    expect(summary.closest('summary')).not.toBeNull()
    expect(summary).toBeVisible()
    fireEvent.click(summary)
    expect(details).toHaveAttribute('open')
    expect(note).toBeVisible()
    fireEvent.click(summary)
    expect(details).not.toHaveAttribute('open')
    expect(note).not.toBeVisible()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('素材不完整不伪造标签成功；无需详情的正常完成任务不增加空入口', () => {
    const row = within(show({ ...job, tagsWritten: false, warning: undefined }))
    expect(row.getByText('已完成')).toBeVisible()
    expect(row.queryByText('已写入标签')).toBeNull()
    expect(row.queryByText('下载详情')).toBeNull()
  })

  it('内嵌失败的技术详情可查，但不把文件完成当作完整标签成功', () => {
    const failedEmbedding = '当前音频格式或标签结构不支持安全内嵌，未写入标签（tags_unsupported）'
    const row = within(show({ ...job, tagsWritten: false, warning: failedEmbedding }))
    expect(row.getByText('已完成')).toBeVisible()
    expect(row.queryByText('已写入标签')).toBeNull()
    expect(row.getByText(failedEmbedding)).not.toBeVisible()
    fireEvent.click(row.getByText('下载详情'))
    expect(row.getByText(failedEmbedding)).toBeVisible()
  })

  it.each(['downloading', 'writing_metadata', 'paused', 'failed', 'cancelled'])(
    '%s 非完成任务保留主区真实错误及 warning，不被折叠策略遮蔽',
    (state) => {
      const row = within(show({ ...job, state, error: '磁盘空间不足，请释放空间后重试', tagsWritten: false }))
      expect(row.getByText('磁盘空间不足，请释放空间后重试')).toBeVisible()
      expect(row.getByText(warning)).toBeVisible()
      expect(row.queryByText('下载详情')).toBeNull()
      expect(row.queryByText('已写入标签')).toBeNull()
      if (state === 'failed') expect(row.getByRole('button', { name: '重试 此间少年' })).toBeVisible()
    },
  )

  it('即使服务端把完成与错误同时返回，也不隐藏真错误', () => {
    const row = within(show({ ...job, error: '归档结果需要确认' }))
    expect(row.getByText('归档结果需要确认')).toBeVisible()
    expect(row.getByText(warning)).not.toBeVisible()
  })

  it('SSE 更新保留同一行、标题、按钮、详情节点及用户的展开状态', async () => {
    const row = show()
    const title = screen.getByText('此间少年')
    const location = screen.getByRole('button', { name: '查看 此间少年 保存位置' })
    const summary = screen.getByText('下载详情')
    const details = summary.closest('details')
    fireEvent.click(summary)
    expect(details).toHaveAttribute('open')
    const updated = `${warning}；补充诊断`
    act(() => DownloadEvents.current.emit([{ ...job, warning: updated, updatedAt: '2026-09-09T04:31:00Z' }]))
    await waitFor(() => expect(screen.getByText(updated)).toBeVisible())
    expect(screen.getByRole('article')).toBe(row)
    expect(screen.getByText('此间少年')).toBe(title)
    expect(screen.getByRole('button', { name: '查看 此间少年 保存位置' })).toBe(location)
    expect(screen.getByText('下载详情')).toBe(summary)
    expect(screen.getByText(updated).closest('details')).toBe(details)
    expect(details).toHaveAttribute('open')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('历史独立文件不删除，保存位置仍可查；位置弹窗的技术详情也默认收起', () => {
    show({ ...job, lyricsPath: '/music/旧歌词.lrc', coverPath: '/music/旧封面.jpg' })
    fireEvent.click(screen.getByRole('button', { name: '查看 此间少年 保存位置' }))
    const dialog = within(screen.getByRole('dialog', { name: '音乐保存位置' }))
    expect(dialog.getByText('/music/旧歌词.lrc')).toBeVisible()
    expect(dialog.getByText('/music/旧封面.jpg')).toBeVisible()
    expect(dialog.getByText('音频内嵌标签已写入')).toBeVisible()
    expect(dialog.getByText(warning)).not.toBeVisible()
    fireEvent.click(dialog.getByText('下载详情'))
    expect(dialog.getByText(warning)).toBeVisible()
    expect(fetch).not.toHaveBeenCalled()
  })
})

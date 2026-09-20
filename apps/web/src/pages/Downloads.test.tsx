import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { DownloadsPage } from './Downloads'
import { queryClient } from '../lib/api'
import type { DownloadJob } from '../lib/types'

class DownloadEvents extends EventTarget {
  static OPEN = 1
  static instances: DownloadEvents[] = []
  readyState = DownloadEvents.OPEN
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn()
  constructor(_url: string | URL) {
    super()
    DownloadEvents.instances.push(this)
  }
  emit(data: unknown) {
    this.dispatchEvent(new MessageEvent('downloads', { data: JSON.stringify(data) }))
  }
}
const job: DownloadJob = {
  id: 'v8-download',
  track: {
    id: 'kw:v8',
    providerId: 'kw',
    title: '爱如潮水(咚鼓版)',
    artist: '测试歌手',
    album: '下载回归',
    duration: 180,
    coverUrl: '',
    qualities: ['flac'],
    canDownload: true,
  },
  quality: 'flac',
  state: 'completed',
  bytesDone: 2 * 1024 * 1024,
  bytesTotal: 2 * 1024 * 1024,
  speed: 0,
  targetPath: '/music/Singles/爱如潮水(咚鼓版).mp3',
  createdAt: '2026-09-08T00:00:00Z',
  updatedAt: '2026-09-08T00:00:00Z',
}

beforeEach(() => {
  queryClient.clear()
  DownloadEvents.instances = []
  vi.stubGlobal('EventSource', DownloadEvents)
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('本测试不应触发网络请求')))
  queryClient.setQueryData(['/settings'], { concurrency: 1 })
})
afterEach(() => {
  cleanup()
  queryClient.clear()
  vi.unstubAllGlobals()
})
function show(current: DownloadJob) {
  queryClient.setQueryData(['/downloads'], [current])
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <DownloadsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}
function completedRow() {
  fireEvent.click(screen.getByRole('tab', { name: /^已完成/ }))
  return screen.getByRole('article')
}

describe('下载页请求音质与文件格式', () => {
  it('FLAC 请求、2MB MP3 完成及素材警告并存，不冒称无损文件或失败', () => {
    show({ ...job, warning: '歌词：素材不可用' })
    const row = within(completedRow())
    expect(row.getByText('请求 无损·FLAC')).toBeVisible()
    expect(row.getByText('文件 MP3')).toBeVisible()
    expect(row.getByText('已完成')).toBeVisible()
    expect(row.getByText('歌词：素材不可用')).not.toBeVisible()
    fireEvent.click(row.getByText('下载详情'))
    expect(row.getByText('歌词：素材不可用')).toBeVisible()
    expect(row.getByText('2.0 MB / 2.0 MB')).toBeVisible()
    expect(row.getByText('文件 MP3').parentElement).toHaveAttribute(
      'title',
      expect.stringContaining('请求的 FLAC 与文件 MP3 不一致'),
    )
    expect(queryClient.getQueryData<DownloadJob[]>(['/downloads'])?.[0]?.quality).toBe('flac')
    expect(fetch).not.toHaveBeenCalled()
  })

  it('明天见 .audio 失败仍显示原错误及未识别，不把占位后缀当音频格式', () => {
    show({
      ...job,
      track: { ...job.track, title: '明天见' },
      state: 'failed',
      bytesDone: 0,
      targetPath: '/music/Singles/明天见.audio',
      error: '下载来源未返回受支持的音频文件',
    })
    fireEvent.click(screen.getByRole('tab', { name: /^失败与取消/ }))
    expect(screen.getByText('请求 无损·FLAC')).toBeVisible()
    expect(screen.getByText('文件 未识别')).toBeVisible()
    expect(screen.getByText('下载来源未返回受支持的音频文件')).toBeVisible()
    expect(screen.getByRole('button', { name: '重试 明天见' })).toBeVisible()
    expect(screen.queryByText('文件 AUDIO')).toBeNull()
  })

  it.each(['M4A', 'FLAC'])('历史 %s 文件仅展示格式，不给文件加无损/位深承诺', (extension) => {
    show({ ...job, targetPath: `/music/旧任务.${extension}` })
    const row = within(completedRow())
    const file = row.getByText(`文件 ${extension}`)
    expect(file).toBeVisible()
    expect(file.parentElement).toHaveAttribute(
      'title',
      expect.stringContaining('不代表实测码率、位深或无损来源'),
    )
  })

  it('SSE 识别及进度更新复用原行、标题格式槽和按钮，不改 quality', async () => {
    const initial = { ...job, state: 'downloading', bytesDone: 0, targetPath: '/music/song.audio' }
    show(initial)
    const row = screen.getByRole('article')
    const request = screen.getByText('请求 无损·FLAC')
    const file = screen.getByText('文件 未识别')
    const tag = file.parentElement
    const pause = screen.getByRole('button', { name: '暂停 爱如潮水(咚鼓版)' })
    expect(file).toHaveClass('download-file-format')
    expect(tag).toHaveClass('download-quality')

    act(() => DownloadEvents.instances[0]!.emit([{ ...job, state: 'downloading' }]))
    await waitFor(() => expect(screen.getByText('文件 MP3')).toBeVisible())
    expect(screen.getByRole('article')).toBe(row)
    expect(screen.getByText('请求 无损·FLAC')).toBe(request)
    expect(screen.getByText('文件 MP3')).toBe(file)
    expect(file.parentElement).toBe(tag)
    expect(screen.getByRole('button', { name: '暂停 爱如潮水(咚鼓版)' })).toBe(pause)
    expect(queryClient.getQueryData<DownloadJob[]>(['/downloads'])?.[0]?.quality).toBe('flac')

    act(() => DownloadEvents.instances[0]!.emit({ invalid: true }))
    expect(screen.getByRole('article')).toBe(row)
    expect(screen.getByText('文件 MP3')).toBeVisible()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('SSE resolving→downloading→writing_metadata 不丢行或短暂清零，完成后再切分组', async () => {
    show({ ...job, state: 'resolving', bytesDone: 0, targetPath: '/music/song.audio' })
    const row = screen.getByRole('article')
    const activeTab = screen.getByRole('tab', { name: /^进行中/ })
    const speed = row.querySelector('.download-speed')!
    expect(speed).toHaveAttribute('aria-hidden', 'true')
    const seenCounts: string[] = []
    const observer = new MutationObserver(() => seenCounts.push(activeTab.textContent || ''))
    observer.observe(activeTab, { childList: true, characterData: true, subtree: true })
    try {
      expect(activeTab).toHaveTextContent('进行中1')
      for (const [state, label] of [
        ['downloading', '正在下载'],
        ['writing_metadata', '正在写入附加信息'],
      ]) {
        act(() => DownloadEvents.instances[0]!.emit([{ ...job, state: state! }]))
        await waitFor(() => expect(screen.getByText(label!, { exact: true })).toBeVisible())
        expect(screen.getByRole('article')).toBe(row)
        expect(activeTab).toHaveTextContent('进行中1')
        expect(row.querySelector('.download-speed')).toBe(speed)
        expect(speed).toHaveAttribute('aria-hidden', state === 'downloading' ? 'false' : 'true')
        expect(screen.queryByText('暂时没有进行中的任务')).toBeNull()
        expect(screen.getByText('文件 MP3')).toBeVisible()
      }
      expect(seenCounts.every((text) => text === '进行中1')).toBe(true)
      expect(screen.queryByRole('button', { name: '暂停 爱如潮水(咚鼓版)' })).toBeNull()
    } finally {
      observer.disconnect()
    }
    act(() => DownloadEvents.instances[0]!.emit([job]))
    await waitFor(() => expect(activeTab).toHaveTextContent('进行中0'))
    expect(screen.getByRole('tab', { name: /^已完成/ })).toHaveTextContent('已完成1')
    expect(screen.queryByRole('article')).toBeNull()
    const completed = within(completedRow())
    expect(completed.getByText('已完成')).toBeVisible()
    expect(completed.getByText('文件 MP3')).toBeVisible()
    expect(queryClient.getQueryData<DownloadJob[]>(['/downloads'])?.[0]?.quality).toBe('flac')
  })

  it.each(['verifying', 'writing_metadata', 'finalizing'])(
    '%s 保留暂停槽的非交互占位，取消按钮仍在第二槽且不新增暂停动作',
    (state) => {
      show({ ...job, state })
      const row = screen.getByRole('article')
      const actions = row.querySelector('.download-actions')!
      const placeholder = actions.firstElementChild!
      expect(placeholder).toHaveClass('download-action-placeholder')
      expect(placeholder).toHaveAttribute('aria-hidden', 'true')
      expect(placeholder.tagName).toBe('SPAN')
      expect(placeholder).not.toHaveAttribute('tabindex')
      expect(actions.children).toHaveLength(2)
      expect(actions.children[1]).toBe(screen.getByRole('button', { name: '取消 爱如潮水(咚鼓版)' }))
      expect(screen.queryByRole('button', { name: '暂停 爱如潮水(咚鼓版)' })).toBeNull()
      expect(screen.getByRole('tab', { name: /^进行中/ })).toHaveTextContent('进行中1')
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('已有 MP3 后缀但零字节不显示已识别，标题和文件槽始终保留', () => {
    show({ ...job, state: 'downloading', bytesDone: 0 })
    expect(screen.getByText('请求 无损·FLAC')).toBeVisible()
    expect(screen.getByText('文件 未识别')).toBeVisible()
    expect(screen.queryByText('文件 MP3')).toBeNull()
  })
})

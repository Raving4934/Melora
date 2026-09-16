import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { DownloadDialog } from './DownloadDialog'
import { useUI } from '../stores/ui'
import type { Settings, Track } from '../lib/types'
const mocks = vi.hoisted(() => ({
  send: vi.fn(),
  useAPI: vi.fn(),
  invalidate: vi.fn(),
  refetch: vi.fn(),
  useCloudDeploy: vi.fn(() => false),
}))
vi.mock('../lib/api', () => ({ ...mocks, idPath: encodeURIComponent }))
// jsdom 尚无 dialog API；只补测试宿主方法，不替换生产 Modal。
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open')
  }
})
const old: Track = {
  id: 'wy:123',
  providerId: 'wy',
  title: '旧队列歌曲',
  artist: '歌手',
  album: '专辑',
  duration: 120,
  coverUrl: '',
  canDownload: true,
  qualities: ['320k'],
}
afterEach(() => {
  cleanup()
  useUI.setState({ downloadTrack: null })
  mocks.useCloudDeploy.mockReturnValue(false)
  vi.clearAllMocks()
})
function show(
  current: Track,
  fetching = false,
  backgroundError: Error | null = null,
  preferences: Partial<Settings> = {},
) {
  mocks.useAPI.mockImplementation((path: string) => ({
    data: path === '/settings' ? { downloadRoot: '/music', writeMetadata: false, ...preferences } : current,
    isPending: false,
    isFetching: fetching,
    error: null,
    backgroundError: path === '/settings' ? null : backgroundError,
    refetch: mocks.refetch,
  }))
  useUI.setState({ downloadTrack: old })
  render(
    <MemoryRouter>
      <DownloadDialog />
    </MemoryRouter>,
  )
}
describe('切源后的下载能力', () => {
  it('cloud 部署不渲染下载弹窗', () => {
    mocks.useCloudDeploy.mockReturnValue(true)
    show(old)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('读取当前音质，不把旧队列的320k交给仅支持128k的新源', async () => {
    mocks.send.mockResolvedValue({ id: 'job' })
    show({ ...old, qualities: ['128k'] })
    expect(mocks.useAPI).toHaveBeenCalledWith('/tracks/wy%3A123')
    expect(screen.getByLabelText('下载音质')).toHaveValue('128k')
    expect(screen.queryByRole('option', { name: '320K' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '确认保存' }))
    await waitFor(() =>
      expect(mocks.send).toHaveBeenCalledWith('/downloads', 'POST', {
        trackId: 'wy:123',
        quality: '128k',
        writeLyrics: false,
        writeCover: false,
        embedTags: true,
      }),
    )
  })
  it('当前源不支持或仍在刷新时不提交旧能力', () => {
    show({ ...old, qualities: [], canDownload: false })
    expect(screen.getByRole('button', { name: '确认保存' })).toBeDisabled()
    expect(screen.getByText('当前音源不支持下载此曲目，请先选择可用的 LX 音源。')).toBeVisible()
    expect(mocks.send).not.toHaveBeenCalled()
  })
  it('刷新期间保持控件但禁用提交，防止抢用旧缓存', () => {
    show({ ...old, qualities: ['128k'] }, true)
    expect(screen.getByLabelText('下载音质')).toBeVisible()
    expect(screen.getByRole('button', { name: '确认保存' })).toBeDisabled()
  })
  it('旧能力的后台刷新失败时仍禁止提交，并可主动重试', () => {
    show(old, false, new Error('目录暂不可用'))
    expect(screen.getByRole('button', { name: '确认保存' })).toBeDisabled()
    expect(screen.getByText('无法刷新当前音质：目录暂不可用')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '重新读取音质' }))
    expect(mocks.refetch).toHaveBeenCalledOnce()
    expect(mocks.send).not.toHaveBeenCalled()
  })
})

it.each([true, false])('仅呈现内嵌选项，继承 embedTags=%s 但忽略旧独立文件偏好', async (embedTags) => {
  mocks.send.mockResolvedValue({ id: 'job' })
  show({ ...old, qualities: ['128k', 'flac'] }, false, null, {
    defaultQuality: 'flac',
    writeLyrics: true,
    writeCover: true,
    embedTags,
  })
  expect(screen.getByLabelText('下载音质')).toHaveValue('flac')
  expect(screen.getAllByRole('checkbox')).toHaveLength(1)
  const embedding = screen.getByRole('checkbox', { name: '内嵌歌曲信息、歌词与封面' })
  expect(embedding).toHaveProperty('checked', embedTags)
  expect(screen.queryByText('另存歌词文件（LRC）')).toBeNull()
  expect(screen.queryByText('另存封面图片')).toBeNull()
  expect(screen.queryByRole('button', { name: '仅内嵌，不另存文件' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '确认保存' }))
  await waitFor(() =>
    expect(mocks.send).toHaveBeenCalledWith('/downloads', 'POST', {
      trackId: old.id,
      quality: 'flac',
      writeLyrics: false,
      writeCover: false,
      embedTags,
    }),
  )
  expect(mocks.send).toHaveBeenCalledOnce()
})

it('本任务可覆盖内嵌偏好，不修改全局设置', async () => {
  mocks.send.mockResolvedValue({ id: 'job' })
  show({ ...old, qualities: ['flac'] }, false, null, {
    writeLyrics: true,
    writeCover: true,
    embedTags: false,
  })
  const embedding = screen.getByRole('checkbox', { name: '内嵌歌曲信息、歌词与封面' })
  expect(embedding).not.toBeChecked()
  fireEvent.click(embedding)
  expect(embedding).toBeChecked()
  expect(mocks.send).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '确认保存' }))
  await waitFor(() =>
    expect(mocks.send).toHaveBeenCalledWith('/downloads', 'POST', {
      trackId: old.id,
      quality: 'flac',
      embedTags: true,
      writeLyrics: false,
      writeCover: false,
    }),
  )
  expect(mocks.send).not.toHaveBeenCalledWith('/settings', expect.anything(), expect.anything())
})

it('创建失败后保留音质和内嵌选项，重试仍明确禁止另存文件', async () => {
  mocks.send.mockRejectedValueOnce(new Error('保存目录暂不可用')).mockResolvedValueOnce({ id: 'job' })
  show({ ...old, qualities: ['128k', 'flac'] }, false, null, {
    writeLyrics: true,
    writeCover: true,
    embedTags: true,
  })
  fireEvent.change(screen.getByLabelText('下载音质'), { target: { value: 'flac' } })
  fireEvent.click(screen.getByRole('checkbox', { name: '内嵌歌曲信息、歌词与封面' }))
  const submit = screen.getByRole('button', { name: '确认保存' })
  fireEvent.click(submit)
  await screen.findByText('保存目录暂不可用')
  expect(screen.getByLabelText('下载音质')).toHaveValue('flac')
  expect(screen.getByRole('checkbox', { name: '内嵌歌曲信息、歌词与封面' })).not.toBeChecked()
  expect(screen.getByRole('button', { name: '确认保存' })).toBe(submit)
  expect(submit).toBeEnabled()
  fireEvent.click(submit)
  await waitFor(() => expect(mocks.send).toHaveBeenCalledTimes(2))
  expect(mocks.send.mock.calls).toEqual(
    Array.from({ length: 2 }, () => [
      '/downloads',
      'POST',
      { trackId: old.id, quality: 'flac', embedTags: false, writeLyrics: false, writeCover: false },
    ]),
  )
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

it('保存中复用内嵌开关和提交按钮，并禁用变更与重复提交', async () => {
  let finish!: (value: { id: string }) => void
  mocks.send.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  show({ ...old, qualities: ['flac'] }, false, null, { writeLyrics: true, writeCover: true })
  const embedding = screen.getByRole('checkbox', { name: '内嵌歌曲信息、歌词与封面' })
  const submit = screen.getByRole('button', { name: '确认保存' })
  fireEvent.click(submit)
  expect(screen.getByRole('checkbox', { name: '内嵌歌曲信息、歌词与封面' })).toBe(embedding)
  expect(screen.getByRole('button', { name: '确认保存' })).toBe(submit)
  expect(embedding).toBeDisabled()
  expect(submit).toBeDisabled()
  fireEvent.click(submit)
  expect(mocks.send).toHaveBeenCalledOnce()
  expect(mocks.send).toHaveBeenCalledWith('/downloads', 'POST', {
    trackId: old.id,
    quality: 'flac',
    embedTags: true,
    writeLyrics: false,
    writeCover: false,
  })
  finish({ id: 'job' })
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

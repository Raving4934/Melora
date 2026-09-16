import { useState } from 'react'
import { Download, FolderOpen, HardDrive, LoaderCircle } from 'lucide-react'
import { Link } from 'react-router'
import { useUI, notify } from '../stores/ui'
import { useAPI, send, invalidate, idPath, useCloudDeploy } from '../lib/api'
import type { Settings, Track } from '../lib/types'
import { errorMessage, qualityName } from '../lib/format'
import { Cover, Modal, QueryState } from './UI'
import './DownloadDialog.css'
export function DownloadDialog() {
  const track = useUI((state) => state.downloadTrack)
  const cloud = useCloudDeploy()
  return track && !cloud ? <DownloadForm key={track.id} track={track} /> : null
}
function DownloadForm({ track }: { track: Track }) {
  const [quality, setQuality] = useState<string | null>(null)
  const [embedTags, setEmbedTags] = useState<boolean | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const settings = useAPI<Settings>('/settings')
  // 当前播放器/队列可能来自旧LX源，弹窗必须重新读取当前可用能力。
  const details = useAPI<Track>(`/tracks/${idPath(track.id)}`)
  const qualities = details.data?.qualities || []
  const capabilityFailed = !!details.backgroundError
  const preferredQuality = quality ?? settings.data?.defaultQuality ?? 'standard'
  const selectedQuality = qualities.includes(preferredQuality) ? preferredQuality : qualities[0] || 'standard'
  const selectedEmbedTags = embedTags ?? settings.data?.embedTags ?? true
  const close = () => {
    if (!pending) useUI.getState().openDownload(null)
  }
  const start = async () => {
    if (pending || details.isFetching || capabilityFailed || !details.data?.canDownload || !qualities.length)
      return
    setPending(true)
    setError('')
    try {
      await send('/downloads', 'POST', {
        trackId: track.id,
        quality: selectedQuality,
        // 新 UI 只保存音频；明确覆盖旧的独立文件偏好，不修改旧 API 或历史文件。
        writeLyrics: false,
        writeCover: false,
        embedTags: selectedEmbedTags,
      })
      void invalidate('/downloads')
      useUI.getState().openDownload(null)
      notify('已加入飞牛下载队列', 'success')
    } catch (error) {
      setError(errorMessage(error))
    } finally {
      setPending(false)
    }
  }
  return (
    <Modal title="保存到飞牛" onClose={close}>
      <div className="download-song">
        <Cover src={track.coverUrl} />
        <div>
          <strong>{track.title}</strong>
          <span>
            {track.artist} · {track.album}
          </span>
        </div>
      </div>
      <QueryState
        pending={settings.isPending || details.isPending}
        error={settings.error || details.error}
        retry={() => {
          void Promise.all([settings.refetch(), details.refetch()])
        }}
      >
        {!settings.data?.downloadRoot ? (
          <div className="download-unconfigured">
            <FolderOpen size={25} />
            <h3>先选择一个音乐保存位置</h3>
            <p>请先在设置中选择下载目录。</p>
            <Link to="/settings" className="button primary" onClick={close}>
              前往下载设置
            </Link>
          </div>
        ) : (
          <>
            <label className="field-label" htmlFor="download-quality">
              下载音质
            </label>
            <select
              id="download-quality"
              value={selectedQuality}
              disabled={pending || details.isFetching || !qualities.length}
              onChange={(event) => setQuality(event.target.value)}
            >
              {qualities.length === 0 && <option value="standard">暂无可用音质</option>}
              {qualities.map((value) => (
                <option key={value} value={value}>
                  {qualityName(value)}
                </option>
              ))}
            </select>
            <div className="save-location">
              <HardDrive size={17} />
              <div>
                <span>保存位置</span>
                <code>{settings.data.downloadRoot}</code>
              </div>
            </div>
            <fieldset className="download-attachments download-save-mode" disabled={pending}>
              <legend>保存方式</legend>
              <label className="download-embed-option">
                <input
                  type="checkbox"
                  checked={selectedEmbedTags}
                  onChange={(event) => setEmbedTags(event.target.checked)}
                />
                内嵌歌曲信息、歌词与封面
              </label>
              <p className="download-mode-note">开启后将可用内容写入音频，不另存歌词或封面文件。</p>
            </fieldset>
            <div role="alert" className="inline-error">
              {error ||
                (capabilityFailed
                  ? `无法刷新当前音质：${errorMessage(details.backgroundError)}`
                  : !details.data?.canDownload
                    ? '当前音源不支持下载此曲目，请先选择可用的 LX 音源。'
                    : '')}
            </div>
            {capabilityFailed && (
              <button
                className="text-button"
                disabled={details.isFetching}
                onClick={() => {
                  void details.refetch()
                }}
              >
                重新读取音质
              </button>
            )}
            <div className="modal-actions">
              <button className="button secondary" onClick={close} disabled={pending}>
                取消
              </button>
              <button
                className="button primary"
                disabled={
                  pending ||
                  details.isFetching ||
                  capabilityFailed ||
                  !details.data?.canDownload ||
                  !qualities.length
                }
                onClick={() => {
                  void start()
                }}
              >
                {pending ? <LoaderCircle size={16} className="spin" /> : <Download size={16} />}确认保存
              </button>
            </div>
          </>
        )}
      </QueryState>
    </Modal>
  )
}

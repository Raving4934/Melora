import { useState, type ReactNode } from 'react'
import { Check, ChevronRight, FolderOpen } from 'lucide-react'
import { useAPI } from '../lib/api'
import { errorMessage } from '../lib/format'
import type { StorageDirectories } from '../lib/types'
import { Modal } from './UI'
import './DownloadDirectory.css'

export function DownloadDirectory({
  value,
  onChange,
  onCommit,
  feedback,
  description,
  dirty = false,
}: {
  value: string
  onChange: (path: string) => void
  onCommit: (path: string) => void
  feedback?: ReactNode
  description?: string
  dirty?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [path, setPath] = useState('')
  const listing = useAPI<StorageDirectories>(
    `/storage/directories${path ? `?path=${encodeURIComponent(path)}` : ''}`,
    open,
  )
  const listingError = listing.error || listing.backgroundError
  const current = listing.data?.path === path && !listing.isPlaceholderData
  const canSelect = current && !listing.isFetching && !listingError
  return (
    <div className="download-directory">
      <label htmlFor="download-root">下载保存目录</label>
      {description && <p className="download-directory-description">{description}</p>}
      <div className="download-directory-inputs">
        <div className="download-directory-field">
          <input
            id="download-root"
            value={value}
            placeholder="选择或输入下载目录"
            aria-describedby="download-directory-feedback"
            onChange={(event) => onChange(event.target.value)}
            onBlur={() => onCommit(value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                event.preventDefault()
                onCommit(value)
              }
            }}
          />
          <button
            type="button"
            className="download-directory-confirm"
            aria-label="确认下载目录"
            disabled={!dirty}
            data-visible={dirty}
            onClick={() => onCommit(value)}
          >
            <Check size={16} />
          </button>
        </div>
        <button
          type="button"
          className="button secondary small"
          aria-haspopup="dialog"
          onClick={() => setOpen(true)}
        >
          <FolderOpen size={15} />
          浏览
        </button>
      </div>
      <div id="download-directory-feedback" className="download-directory-feedback">
        {feedback}
      </div>
      {open && (
        <Modal title="选择下载目录" className="download-directory-modal" onClose={() => setOpen(false)}>
          <section
            id="authorized-directory-browser"
            className="authorized-directory-browser"
            aria-label="目录浏览器"
            aria-busy={listing.isFetching}
          >
            <div className="directory-browser-toolbar">
              <button type="button" className="text-button" disabled={!path} onClick={() => setPath('')}>
                全部目录
              </button>
              <button
                type="button"
                className="text-button"
                disabled={!canSelect || !listing.data?.parent}
                onClick={() => setPath(listing.data!.parent)}
              >
                上一级
              </button>
              <button
                type="button"
                className="text-button"
                disabled={listing.isFetching}
                onClick={() => void listing.refetch()}
              >
                刷新列表
              </button>
            </div>
            <p className="directory-browser-path" title={current ? listing.data?.path : path}>
              {path || '全部目录'}
            </p>
            <div className={`directory-browser-status ${listingError ? 'has-error' : ''}`} role="status">
              {listingError
                ? `${errorMessage(listingError)}`
                : listing.isFetching
                  ? '正在读取目录…'
                  : listing.data?.truncated
                    ? '目录较多；可手动填写完整路径。'
                    : '\u00a0'}
            </div>
            <div className="directory-browser-list">
              <ul>
                {listing.data?.directories.map((directory) => (
                  <li key={directory.path}>
                    <button
                      type="button"
                      disabled={!canSelect}
                      onClick={() => setPath(directory.path)}
                      title={directory.path}
                      aria-label={`打开目录 ${directory.path}`}
                    >
                      <div className="directory-item-icon">
                        <FolderOpen size={16} />
                      </div>
                      <span>
                        <strong>{directory.name}</strong>
                        <small>{directory.path}</small>
                      </span>
                      <ChevronRight size={16} className="directory-item-chevron" />
                    </button>
                  </li>
                ))}
              </ul>
              {!listing.data && (
                <p className="muted">{listingError ? '请重试读取目录。' : '正在读取目录…'}</p>
              )}
              {current && !listing.isFetching && !listingError && !listing.data?.directories.length && (
                <p className="muted">此目录没有可浏览的子目录。</p>
              )}
            </div>
            <div className="directory-browser-footer">
              <span>选择后自动保存。</span>
              <button
                type="button"
                className="button secondary small"
                disabled={!canSelect || !path}
                onClick={() => {
                  onCommit(listing.data!.path)
                  setOpen(false)
                }}
              >
                <Check size={15} />
                选择此目录
              </button>
            </div>
          </section>
        </Modal>
      )}
    </div>
  )
}

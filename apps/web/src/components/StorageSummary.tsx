import { HardDrive, RefreshCw } from 'lucide-react'
import { useAPI } from '../lib/api'
import { formatBytes } from '../lib/format'
import type { StorageStatus } from '../lib/types'
import { IconButton } from './UI'

export function StorageSummary() {
  const query = useAPI<StorageStatus>('/storage/status')
  const data = query.data
  const capacity = data?.capacity
  const unavailable =
    capacity && capacity.totalBytes > 0
      ? Math.min(100, Math.max(0, (1 - capacity.availableBytes / capacity.totalBytes) * 100))
      : 0
  return (
    <section className="storage-summary" aria-label="存储空间" aria-busy={query.isFetching}>
      <HardDrive size={19} />
      <div className="storage-summary-main">
        <div className="storage-summary-title">
          <strong>{data?.configured ? '音乐保存磁盘' : '下载存储状态'}</strong>
          <span>
            {capacity
              ? `可用 ${formatBytes(capacity.availableBytes)} / 总计 ${formatBytes(capacity.totalBytes)}`
              : query.isPending
                ? '正在读取存储状态…'
                : '容量暂不可用'}
          </span>
        </div>
        <div className="storage-capacity-bar" aria-hidden="true">
          <span style={{ transform: `scaleX(${unavailable / 100})` }} />
        </div>
        <p>
          {query.error?.message ||
            data?.error ||
            (data?.authorized ? data.path || data.authorizedRoot : '尚未授权下载目录；在线试听不受影响。')}
        </p>
      </div>
      <IconButton
        label="刷新存储空间"
        disabled={query.isFetching}
        onClick={() => {
          void query.refetch()
        }}
      >
        <RefreshCw size={14} className={query.isFetching ? 'spin' : ''} />
      </IconButton>
    </section>
  )
}

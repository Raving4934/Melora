import { Link } from 'react-router'
import { Modal } from './UI'
import { useUI } from '../stores/ui'
import { SOURCE_REQUIRED_MESSAGE } from '../lib/source-state'

const operationLabels = {
  play: '播放新的在线曲目',
  download: '下载新的在线曲目',
  batch: '批量播放在线曲目',
} as const

export function SourceSetupDialog() {
  const operation = useUI((state) => state.sourceGate)
  if (!operation) return null
  const close = () => useUI.getState().openSourceGate(null)
  return (
    <Modal title="需要配置自定义源" className="source-required-dialog" onClose={close}>
      <p className="modal-description">{SOURCE_REQUIRED_MESSAGE}</p>
      <p className="modal-description">{operationLabels[operation]}需要先取得可用的在线地址。</p>
      <div className="modal-actions">
        <button type="button" className="button secondary" onClick={close}>
          稍后
        </button>
        <Link to="/settings#lx-source-settings" className="button primary" onClick={close}>
          打开自定义源设置
        </Link>
      </div>
    </Modal>
  )
}

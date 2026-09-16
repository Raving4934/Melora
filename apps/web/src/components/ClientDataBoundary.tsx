import { useLayoutEffect, useState, type ReactNode } from 'react'
import { activateDataIdentity, validDataIdentity } from '../lib/data-identity'
import { queryClient } from '../lib/api'
import { reloadLyricFontPreference } from '../lib/lyric-font'
import { authorizePlayOutcomeDelivery, flushPendingPlayOutcomes } from '../lib/play-outcome'
import type { Session } from '../lib/types'
import { player } from '../stores/player'
import { useUI } from '../stores/ui'

/** 认证完成后先绑定数据代际，再首次绘制私有页面；不是刷新时反复卸载列表。 */
export function ClientDataBoundary({ session, children }: { session?: Session; children: ReactNode }) {
  const identity =
    session?.authenticated && validDataIdentity(session.dataIdentity) ? session.dataIdentity : null
  const generation = identity?.generation ?? null
  const resetLegacy = identity?.resetLegacy ?? false
  const [ready, setReady] = useState<string | null>(null)
  useLayoutEffect(() => {
    if (!generation) {
      authorizePlayOutcomeDelivery(null)
      return
    }
    if (activateDataIdentity({ generation, resetLegacy })) {
      // 子页面此时尚未挂载；迟到的旧请求必须取消，不能把旧音源/歌单回填给新数据库。
      void queryClient.cancelQueries({ predicate: (query) => query.queryKey[0] !== '/auth/session' })
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== '/auth/session' })
      player.restoreDataSession()
      reloadLyricFontPreference()
      useUI.setState({ drawer: null, downloadTrack: null, playlistTrack: null, toast: null })
    }
    // 身份代际绑定完成后才授权网络补发，旧代际 Promise 即使迟到也无法跨界写入。
    authorizePlayOutcomeDelivery(generation)
    void flushPendingPlayOutcomes()
    setReady(generation)
    return () => authorizePlayOutcomeDelivery(null)
  }, [generation, resetLegacy])
  // 在layout effect中同步准备，浏览器不会画出一次旧队列再清空的中间帧。
  return generation && ready !== generation ? null : children
}

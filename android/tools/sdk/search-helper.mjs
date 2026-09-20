// 供 lx-smoke 复用：在 Node 中运行 music-sdk bundle 获取真实歌曲信息。
import fs from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

let loaded = false

function ensureBundle() {
  if (loaded) return
  const here = path.dirname(fileURLToPath(import.meta.url))
  const bundlePath = path.resolve(here, '../../app/src/main/assets/sdk/music-sdk.js')
  const bundle = fs.readFileSync(bundlePath, 'utf8')
  ;(0, eval)(bundle)
  loaded = true
}

export async function searchSong(source, keyword = '晴天 周杰伦') {
  ensureBundle()
  globalThis.__meloraInvoke(JSON.stringify({
    action: 'search',
    source,
    params: { text: keyword, page: 1, limit: 5 },
  }))
  for (let i = 0; i < 600; i++) {
    const raw = globalThis.__meloraTake()
    if (raw) {
      const parsed = JSON.parse(raw)
      if (!parsed.ok) throw new Error(parsed.error)
      const list = parsed.data.list || []
      if (!list.length) throw new Error('no search result')
      return list.find((song) => Object.keys(song._types || {}).length) || list[0]
    }
    await sleep(25)
  }
  throw new Error('sdk timeout')
}

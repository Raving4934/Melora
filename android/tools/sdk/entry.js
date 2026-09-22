// 乐屿 musicSdk 运行时入口：把 musicSdk（Apache-2.0）适配进 QuickJS。
// 统一协议：__meloraInvoke(payloadJson) 发起调用，__meloraTake() 轮询取回 JSON 结果。
import './bootstrap'

import kwMusicSearch from './src/musicSdk/kw/musicSearch'
import kwSongList from './src/musicSdk/kw/songList'
import kwLeaderboard from './src/musicSdk/kw/leaderboard'
import kwHotSearch from './src/musicSdk/kw/hotSearch'
import kwTipSearch from './src/musicSdk/kw/tipSearch'
import kwLyric from './src/musicSdk/kw/lyric'
import kwPic from './src/musicSdk/kw/pic'

import kgMusicSearch from './src/musicSdk/kg/musicSearch'
import kgSongList from './src/musicSdk/kg/songList'
import kgLeaderboard from './src/musicSdk/kg/leaderboard'
import kgHotSearch from './src/musicSdk/kg/hotSearch'
import kgTipSearch from './src/musicSdk/kg/tipSearch'
import kgLyric from './src/musicSdk/kg/lyric'
import kgPic from './src/musicSdk/kg/pic'

import txMusicSearch from './src/musicSdk/tx/musicSearch'
import txSongList from './src/musicSdk/tx/songList'
import txLeaderboard from './src/musicSdk/tx/leaderboard'
import txHotSearch from './src/musicSdk/tx/hotSearch'
import txTipSearch from './src/musicSdk/tx/tipSearch'
import txLyric from './src/musicSdk/tx/lyric'

import wyMusicSearch from './src/musicSdk/wy/musicSearch'
import wySongList from './src/musicSdk/wy/songList'
import wyLeaderboard from './src/musicSdk/wy/leaderboard'
import wyHotSearch from './src/musicSdk/wy/hotSearch'
import wyTipSearch from './src/musicSdk/wy/tipSearch'
import wyLyric from './src/musicSdk/wy/lyric'
import wyMusicInfo from './src/musicSdk/wy/musicInfo'

import mgMusicSearch from './src/musicSdk/mg/musicSearch'
import mgSongList from './src/musicSdk/mg/songList'
import mgLeaderboard from './src/musicSdk/mg/leaderboard'
import mgHotSearch from './src/musicSdk/mg/hotSearch'
import mgTipSearch from './src/musicSdk/mg/tipSearch'
import mgLyric from './src/musicSdk/mg/lyric'
import mgPic from './src/musicSdk/mg/pic'

const PLATFORMS = {
  kw: { search: kwMusicSearch, songList: kwSongList, leaderboard: kwLeaderboard, hotSearch: kwHotSearch, tipSearch: kwTipSearch, lyric: kwLyric, pic: kwPic },
  kg: { search: kgMusicSearch, songList: kgSongList, leaderboard: kgLeaderboard, hotSearch: kgHotSearch, tipSearch: kgTipSearch, lyric: kgLyric, pic: kgPic },
  tx: { search: txMusicSearch, songList: txSongList, leaderboard: txLeaderboard, hotSearch: txHotSearch, tipSearch: txTipSearch, lyric: txLyric },
  wy: { search: wyMusicSearch, songList: wySongList, leaderboard: wyLeaderboard, hotSearch: wyHotSearch, tipSearch: wyTipSearch, lyric: wyLyricAdapter(wyLyric), musicInfo: wyMusicInfo },
  mg: { search: mgMusicSearch, songList: mgSongList, leaderboard: mgLeaderboard, hotSearch: mgHotSearch, tipSearch: mgTipSearch, lyric: mgLyric, pic: mgPic },
}

// wy 的 lyric 导出是函数本体（getLyric(songmid)），统一包装成对象协议。
function wyLyricAdapter(fn) {
  return { getLyric: (songInfo) => fn(songInfo.songmid ?? songInfo.songId) }
}

const QUALITY_ORDER = ['master', 'atmos_plus', 'atmos', 'hires', 'flac24bit', 'flac', 'ape', 'wav', '320k', '192k', '128k']

// 部分平台接口存在双重转义（字面 \u0026），统一解码避免界面出现原始转义串。
const decodeEscapes = (text) => typeof text === 'string'
  ? text.replace(/\\+u([0-9a-fA-F]{4})/g, (_, code) => String.fromCharCode(parseInt(code, 16)))
  : text

const normalizeSongs = (list, source) => (Array.isArray(list) ? list : []).map((raw) => normalizeSong(raw, source))

const normalizeSong = (raw, source) => {
  const out = {}
  for (const key in raw) {
    const value = raw[key]
    if (typeof value === 'function' || value === undefined) continue
    out[key] = value
  }
  out.source = out.source || source
  out.songmid = String(out.songmid ?? '')
  out.name = decodeEscapes(out.name ?? '')
  out.singer = decodeEscapes(out.singer ?? '')
  out.albumName = decodeEscapes(out.albumName ?? '')
  out.interval = out.interval && out.interval !== '--/--' ? out.interval : '00:00'
  if (!Array.isArray(out.types)) out.types = []
  if (!out._types || typeof out._types !== 'object') out._types = {}
  out.types.sort((a, b) => QUALITY_ORDER.indexOf(a.type) - QUALITY_ORDER.indexOf(b.type))
  if (!out.img) out.img = coverFallback(source, out)
  return out
}

const coverFallback = (source, song) => {
  if (source === 'tx' && song.albumMid) return `https://y.gtimg.cn/music/photo_new/T002R500x500M000${song.albumMid}.jpg`
  return null
}

const normalizePlaylists = (list, source) => (Array.isArray(list) ? list : []).map((raw) => {
  const out = {}
  for (const key in raw) {
    const value = raw[key]
    if (typeof value === 'function' || value === undefined) continue
    out[key] = value
  }
  out.source = out.source || source
  out.id = String(out.id ?? '')
  out.name = decodeEscapes(out.name ?? '')
  out.img = out.img ?? null
  out.play_count = out.play_count ?? out.playCount ?? ''
  out.total = out.total ?? 0
  out.author = decodeEscapes(out.author ?? out.uname ?? '')
  return out
})


const lyricsOf = (raw) => {
  if (!raw || typeof raw !== 'object') return { lyric: typeof raw === 'string' ? raw : '', tlyric: '', rlyric: '', lxlyric: '' }
  return {
    lyric: raw.lyric ?? raw.lrc ?? '',
    tlyric: raw.tlyric ?? raw.tlrc ?? '',
    rlyric: raw.rlyric ?? '',
    lxlyric: raw.lxlyric ?? '',
    raw: raw.raw ?? null,
  }
}

async function dispatch(action, source, params = {}) {
  const platform = PLATFORMS[source]
  if (!platform) throw new Error(`不支持的平台: ${source}`)

  switch (action) {
    case 'search': {
      const result = await platform.search.search(params.text, params.page || 1, params.limit || 30)
      return {
        kind: 'songs',
        source,
        list: normalizeSongs(result.list, source),
        total: result.total ?? 0,
        allPage: result.allPage ?? 1,
        limit: result.limit ?? 30,
        page: params.page || 1,
      }
    }
    case 'songlistSearch': {
      if (!platform.songList || !platform.songList.search) throw new Error(`${source} 不支持歌单搜索`)
      const result = await platform.songList.search(params.text, params.page || 1, params.limit || 20)
      return {
        kind: 'playlists',
        source,
        list: normalizePlaylists(result.list, source),
        total: result.total ?? 0,
        page: params.page || 1,
      }
    }
    case 'hotSearch': {
      const result = await platform.hotSearch.getList()
      return { kind: 'words', source, list: (result && result.list) || [] }
    }
    case 'tipSearch': {
      const result = await platform.tipSearch.search(params.text)
      return { kind: 'words', source, list: Array.isArray(result) ? result : (result && result.list) || [] }
    }
    case 'boards': {
      const result = await platform.leaderboard.getBoards()
      return {
        kind: 'boards',
        source,
        list: ((result && result.list) || []).map((item) => ({
          id: String(item.id ?? ''),
          name: item.name ?? '',
          bangid: String(item.bangid ?? item.id ?? ''),
          img: item.img ?? null,
        })),
      }
    }
    case 'boardSongs': {
      const result = await platform.leaderboard.getList(params.bangId, params.page || 1, params.limit)
      return {
        kind: 'songs',
        source,
        list: normalizeSongs(result.list, source),
        total: result.total ?? 0,
        allPage: result.allPage ?? 0,
        limit: result.limit ?? 30,
        page: params.page || 1,
      }
    }
    case 'playlistTags': {
      const result = await platform.songList.getTags()
      return {
        kind: 'tags',
        source,
        hotTag: ((result && result.hotTag) || []).map((item) => ({ id: String(item.id ?? ''), name: item.name ?? '' })),
        tags: ((result && result.tags) || []).map((group) => ({
          name: group.name ?? '',
          list: (group.list || []).map((item) => ({ id: String(item.id ?? ''), name: item.name ?? '' })),
        })),
      }
    }
    case 'playlists': {
      const result = await platform.songList.getList(params.sortId ?? 'hot', params.tagId ?? '', params.page || 1)
      return {
        kind: 'playlists',
        source,
        list: normalizePlaylists(result.list, source),
        total: result.total ?? 0,
        page: params.page || 1,
      }
    }
    case 'playlistSongs': {
      const result = await platform.songList.getListDetail(params.id, params.page || 1)
      return {
        kind: 'songs',
        source,
        list: normalizeSongs(result.list, source),
        total: result.total ?? 0,
        allPage: result.allPage ?? 0,
        limit: result.limit ?? 30,
        page: params.page || 1,
      }
    }
    case 'lyric': {
      const result = await platform.lyric.getLyric(params.song)
      return Object.assign({ kind: 'lyric', source }, lyricsOf(result))
    }
    case 'pic': {
      if (platform.pic) {
        const url = await platform.pic.getPic(params.song)
        return { kind: 'pic', source, url: typeof url === 'string' ? url : null }
      }
      if (source === 'tx') {
        return { kind: 'pic', source, url: coverFallback('tx', params.song) }
      }
      if (source === 'wy') {
        const info = await platform.musicInfo(params.song.songmid)
        return { kind: 'pic', source, url: (info && info.img) || null }
      }
      return { kind: 'pic', source, url: null }
    }
    default:
      throw new Error(`不支持的动作: ${action}`)
  }
}

let pendingResult

globalThis.__meloraInvoke = (payloadJson) => {
  pendingResult = undefined
  let payload
  try {
    payload = JSON.parse(payloadJson)
  } catch (error) {
    pendingResult = JSON.stringify({ ok: false, error: '调用参数解析失败' })
    return
  }
  Promise.resolve()
    .then(() => dispatch(payload.action, payload.source, payload.params || {}))
    .then(
      (data) => { pendingResult = JSON.stringify({ ok: true, data }) },
      (error) => { pendingResult = JSON.stringify({ ok: false, error: String(globalThis.__meloraDebug ? (error && error.stack) || error : (error && error.message) || error) }) },
    )
}

globalThis.__meloraTake = () => {
  const value = pendingResult
  pendingResult = undefined
  return value === undefined ? '' : value
}

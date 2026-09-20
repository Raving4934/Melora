// 乐屿 SDK 工具兼容层：导出集合。
import he from 'he'
import { Buffer } from 'buffer'

const native = globalThis.__lxNative

const numFix = (n) => (n < 10 ? `0${n}` : String(n))

export const sizeFormate = (size) => {
  if (!size) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  const number = Math.floor(Math.log(size) / Math.log(1024))
  return `${(size / Math.pow(1024, Math.floor(number))).toFixed(2)} ${units[number]}`
}

const toDateObj = (date) => {
  if (!date) return ''
  switch (typeof date) {
    case 'string':
      if (!date.includes('T')) date = date.split('.')[0].replace(/-/g, '/')
    // fallthrough
    case 'number':
      date = new Date(date)
    // fallthrough
    case 'object':
      break
    default:
      return ''
  }
  return date
}

export const dateFormat = (date, format = 'Y-M-D h:m:s') => {
  const value = toDateObj(date)
  if (!value) return ''
  return format
    .replace('Y', value.getFullYear().toString())
    .replace('M', numFix(value.getMonth() + 1))
    .replace('D', numFix(value.getDate()))
    .replace('h', numFix(value.getHours()))
    .replace('m', numFix(value.getMinutes()))
    .replace('s', numFix(value.getSeconds()))
}

export const dateFormat2 = (time) => {
  const differ = Math.trunc((Date.now() - time) / 1000)
  if (differ < 60) return `${Math.max(differ, 0)}秒前`
  if (differ < 3600) return `${Math.trunc(differ / 60)}分钟前`
  if (differ < 86400) return `${Math.trunc(differ / 3600)}小时前`
  return dateFormat(time)
}

export const formatPlayTime = (time) => {
  const m = Math.trunc(time / 60)
  const s = Math.trunc(time % 60)
  return m === 0 && s === 0 ? '--/--' : `${numFix(m)}:${numFix(s)}`
}

export const formatPlayTime2 = (time) => {
  const m = Math.trunc(time / 60)
  const s = Math.trunc(time % 60)
  return `${numFix(m)}:${numFix(s)}`
}

export const formatPlayCount = (num) => {
  if (num > 100000000) return `${Math.trunc(num / 10000000) / 10}亿`
  if (num > 10000) return `${Math.trunc(num / 1000) / 10}万`
  return String(num)
}

export const decodeName = (str) => {
  if (!str) return ''
  return he.decode(str)
}

export const toMD5 = (str) => native.hash('md5', Buffer.from(String(str), 'utf8').toString('base64'))

export const filterMusicList = (list) => {
  const ids = new Set()
  return list.filter((item) => {
    if (!item.id || ids.has(item.id) || !item.name) return false
    if (item.singer == null) item.singer = ''
    ids.add(item.id)
    return true
  })
}

export const deduplicationList = (list) => {
  const ids = new Set()
  return list.filter((item) => {
    if (ids.has(item.id)) return false
    ids.add(item.id)
    return true
  })
}

export const toNewMusicInfo = (oldMusicInfo) => {
  const meta = {
    songId: oldMusicInfo.songmid,
    albumName: oldMusicInfo.albumName,
    picUrl: oldMusicInfo.img,
  }
  const newInfo = {
    id: `${oldMusicInfo.source}_${oldMusicInfo.songmid}`,
    name: oldMusicInfo.name,
    singer: oldMusicInfo.singer,
    source: oldMusicInfo.source,
    interval: oldMusicInfo.interval,
    meta,
  }
  meta.qualitys = oldMusicInfo.types
  meta._qualitys = oldMusicInfo._types
  meta.albumId = oldMusicInfo.albumId
  switch (oldMusicInfo.source) {
    case 'kg':
      meta.hash = oldMusicInfo.hash
      newInfo.id = `${oldMusicInfo.songmid}_${oldMusicInfo.hash}`
      break
    case 'tx':
      meta.strMediaMid = oldMusicInfo.strMediaMid
      meta.albumMid = oldMusicInfo.albumMid
      meta.id = oldMusicInfo.songId
      break
    case 'mg':
      meta.copyrightId = oldMusicInfo.copyrightId
      meta.lrcUrl = oldMusicInfo.lrcUrl
      meta.mrcUrl = oldMusicInfo.mrcUrl
      meta.trcUrl = oldMusicInfo.trcUrl
      break
    default:
      break
  }
  return newInfo
}

export const toOldMusicInfo = (minfo) => {
  const oInfo = {
    name: minfo.name,
    singer: minfo.singer,
    source: minfo.source,
    songmid: minfo.meta.songId,
    interval: minfo.interval,
    albumName: minfo.meta.albumName,
    img: minfo.meta.picUrl ?? '',
    typeUrl: {},
    albumId: minfo.meta.albumId,
    types: minfo.meta.qualitys,
    _types: minfo.meta._qualitys,
  }
  switch (minfo.source) {
    case 'kg':
      oInfo.hash = minfo.meta.hash
      break
    case 'tx':
      oInfo.strMediaMid = minfo.meta.strMediaMid
      oInfo.albumMid = minfo.meta.albumMid
      oInfo.songId = minfo.meta.id
      break
    case 'mg':
      oInfo.copyrightId = minfo.meta.copyrightId
      oInfo.lrcUrl = minfo.meta.lrcUrl
      oInfo.mrcUrl = minfo.meta.mrcUrl
      oInfo.trcUrl = minfo.meta.trcUrl
      break
    default:
      break
  }
  return oInfo
}

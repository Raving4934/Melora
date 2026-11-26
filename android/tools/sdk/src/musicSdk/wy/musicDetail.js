import { httpFetch } from '../../request'
import { weapi } from './utils/crypto'
import { formatPlayTime, sizeFormate } from '../../index'
import { formatSingerName } from '../utils'
// https://github.com/Binaryify/NeteaseCloudMusicApi/blob/master/module/song_detail.js

export const formatTrackDetails = (songs, privileges, { playlistOffset = null } = {}) => {
  const fromPlaylist = playlistOffset !== null
  const offset = playlistOffset ?? 0
  const privilegeById = new Map()
  for (const privilege of privileges) {
    if (privilege && !privilegeById.has(privilege.id)) privilegeById.set(privilege.id, privilege)
  }

  const list = []
  for (const [index, item] of songs.entries()) {
    // 保留同一位置的条目优先级，顺序错位时从整份响应按 ID 匹配。
    const alignedPrivilege = privileges[offset + index]
    const privilege = alignedPrivilege?.id === item.id ? alignedPrivilege : privilegeById.get(item.id)
    if (!privilege) continue

    const types = []
    if (privilege.maxBrLevel === 'hires') {
      types.push({ type: 'flac24bit', size: item.hr ? sizeFormate(item.hr.size) : null })
    }
    switch (privilege.maxbr) {
      case 999000:
        types.push({ type: 'flac', size: !fromPlaylist && item.sq ? sizeFormate(item.sq.size) : null })
      case 320000:
        types.push({ type: '320k', size: item.h ? sizeFormate(item.h.size) : null })
      case 192000:
      case 128000:
        types.push({ type: '128k', size: item.l ? sizeFormate(item.l.size) : null })
    }
    const _types = {}
    for (const { type, size } of types) _types[type] = { size }
    const cloud = item.pc
    let singer
    if (cloud) singer = cloud.ar ?? ''
    else if (fromPlaylist) singer = formatSingerName(item.ar)
    else singer = item.ar?.map(artist => artist.name).join('、') ?? ''
    list.push({
      singer,
      name: cloud ? cloud.sn ?? '' : item.name ?? '',
      albumName: cloud ? cloud.alb ?? '' : item.al?.name,
      albumId: item.al?.id,
      source: 'wy',
      interval: formatPlayTime(item.dt / 1000),
      songmid: item.id,
      img: cloud ? item.al?.picUrl ?? '' : item.al?.picUrl,
      lrc: null,
      otherSource: null,
      types: types.reverse(),
      _types,
      typeUrl: {},
    })
  }
  return list
}

export default {
  async getList(ids = [], { cookie } = {}) {
    const { body, statusCode } = await httpFetch('https://music.163.com/weapi/v3/song/detail', {
      method: 'post',
      headers: {
        'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36',
        origin: 'https://music.163.com',
        ...(cookie && { Cookie: cookie }),
      },
      form: weapi({
        c: '[' + ids.map(id => ('{"id":' + id + '}')).join(',') + ']',
        ids: '[' + ids.join(',') + ']',
      }),
    })
    if (statusCode !== 200 || body?.code !== 200 || !Array.isArray(body.songs)) throw new Error('获取歌曲详情失败')
    const { songs } = body
    const privileges = Array.isArray(body.privileges) ? body.privileges : []
    return { source: 'wy', list: formatTrackDetails(songs, privileges), rawCount: songs.length }
  },
}

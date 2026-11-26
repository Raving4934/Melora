// https://github.com/Binaryify/NeteaseCloudMusicApi/blob/master/module/playlist_catlist.js
// https://github.com/Binaryify/NeteaseCloudMusicApi/blob/master/module/playlist_hot.js
// https://github.com/Binaryify/NeteaseCloudMusicApi/blob/master/module/top_playlist.js
// https://github.com/Binaryify/NeteaseCloudMusicApi/blob/master/module/playlist_detail.js

import { weapi } from './utils/crypto'
import { httpFetch } from '../../request'
import { dateFormat, formatPlayCount } from '../../index'
import musicDetailApi, { formatTrackDetails } from './musicDetail'
import { eapiRequest } from './utils/index'
import { parseMusicUrl } from '../utils'

const queryId = query => /(?:^|&)id=(\d+)(?:&|$)/.exec(query)?.[1] || null

const parsePlaylistId = value => {
  const url = parseMusicUrl(value)
  if (!url || (url.hostname !== 'music.163.com' && !url.hostname.endsWith('.music.163.com'))) return null

  const queryAt = url.hash.indexOf('?')
  const routes = [
    [url.pathname, url.query],
    [queryAt < 0 ? url.hash : url.hash.slice(0, queryAt), queryAt < 0 ? '' : url.hash.slice(queryAt + 1)],
  ]
  for (const [route, query] of routes) {
    const pathId = /(?:^|\/)playlist\/(\d+)(?:\/[^?#]*)?$/.exec(route)?.[1]
    if (pathId) return pathId
    if (/(?:^|\/)playlist\/?$/.test(route) && queryId(query)) return queryId(query)
  }
  return null
}

const orderPlaylistSongs = (songs, trackIds) => {
  const byId = new Map()
  for (let index = songs.length - 1; index >= 0; index--) {
    const song = songs[index]
    const id = String(song.songmid)
    const matches = byId.get(id)
    if (matches) matches.push(song)
    else byId.set(id, [song])
  }
  const ordered = []
  for (const id of trackIds) {
    const song = byId.get(String(id))?.pop()
    if (song) ordered.push(song)
  }
  return ordered
}

export default {
  limit_list: 30,
  successCode: 200,
  sortList: [
    {
      name: '最热',
      tid: 'hot',
      id: 'hot',
    },
    // {
    //   name: '最新',
    //   tid: 'new',
    //   id: 'new',
    // },
  ],
  async handleParseId(link, retryNum = 0) {
    if (retryNum > 2) throw new Error('link try max num')

    const requestObj_listDetailLink = httpFetch(link)
    const { url, statusCode } = await requestObj_listDetailLink
    // console.log(headers)
    if (statusCode > 400) return this.handleParseId(link, ++retryNum)
    const id = parsePlaylistId(url)
    if (!id) throw new Error('分享链接不是网易云歌单')
    return id
  },

  async getListId(id) {
    let cookie
    if (/###/.test(id)) {
      const [url, token] = id.split('###')
      id = url
      cookie = `MUSIC_U=${token}`
    }
    if ((/[?&:/]/.test(id))) {
      id = parsePlaylistId(id) || await this.handleParseId(id)
    }
    return { id, cookie }
  },
  async getListDetail(rawId, page = 1) {
    const { id, cookie } = await this.getListId(rawId)
    const headers = { Referer: 'https://music.163.com/' }
    if (cookie) headers.Cookie = cookie
    const detailUrl = `https://music.163.com/api/v6/playlist/detail?id=${encodeURIComponent(id)}&n=100&s=0`
    const limit = 1000
    const start = (page - 1) * limit
    for (let attempt = 0; attempt < 3; attempt++) {
      const { statusCode, body } = await httpFetch(detailUrl, { headers })
      if (statusCode !== 200 || body?.code !== this.successCode || !body.playlist) continue

      const { playlist } = body
      const trackIds = Array.isArray(playlist.trackIds) ? playlist.trackIds : []
      const total = trackIds.length
      const selectedIds = trackIds.slice(start, start + limit).map(track => track.id)
      const tracks = Array.isArray(playlist.tracks) ? playlist.tracks : []
      const privileges = Array.isArray(body.privileges) ? body.privileges : []
      let list = []
      let rawCount = 0

      // v6 返回全部 trackIds，tracks 只有预览。按轨道 ID 选页，不能用预览长度推断总数。
      if (selectedIds.length > 0) {
        if (tracks.length === total && privileges.length === total &&
          selectedIds.every((trackId, index) => String(tracks[start + index]?.id) === String(trackId))) {
          const selectedTracks = tracks.slice(start, start + limit)
          list = formatTrackDetails(selectedTracks, privileges, { playlistOffset: start })
          rawCount = selectedTracks.length
        } else {
          try {
            const detail = await musicDetailApi.getList(selectedIds, { cookie })
            list = orderPlaylistSongs(detail.list, selectedIds)
            rawCount = detail.rawCount
          } catch (error) {
            console.log(error)
            continue
          }
        }
      }

      return {
        list,
        rawCount,
        page,
        limit,
        total,
        allPage: Math.ceil(total / limit),
        source: 'wy',
        info: {
          play_count: formatPlayCount(playlist.playCount),
          name: playlist.name,
          img: playlist.coverImgUrl,
          desc: playlist.description,
          author: playlist.creator?.nickname,
        },
      }
    }
    throw new Error('try max num')
  },
  // 获取列表数据
  getList(sortId, tagId, page, tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch('https://music.163.com/weapi/playlist/list', {
      method: 'post',
      form: weapi({
        cat: tagId || '全部', // 全部,华语,欧美,日语,韩语,粤语,小语种,流行,摇滚,民谣,电子,舞曲,说唱,轻音乐,爵士,乡村,R&B/Soul,古典,民族,英伦,金属,朋克,蓝调,雷鬼,世界音乐,拉丁,另类/独立,New Age,古风,后摇,Bossa Nova,清晨,夜晚,学习,工作,午休,下午茶,地铁,驾车,运动,旅行,散步,酒吧,怀旧,清新,浪漫,性感,伤感,治愈,放松,孤独,感动,兴奋,快乐,安静,思念,影视原声,ACG,儿童,校园,游戏,70后,80后,90后,网络歌曲,KTV,经典,翻唱,吉他,钢琴,器乐,榜单,00后
        order: sortId, // hot,new
        limit: this.limit_list,
        offset: this.limit_list * (page - 1),
        total: true,
      }),
    })
    return request.then(({ body }) => {
      // console.log(body)
      if (body.code !== this.successCode) return this.getList(sortId, tagId, page, ++tryNum)
      return {
        list: this.filterList(body.playlists),
        total: parseInt(body.total),
        page,
        limit: this.limit_list,
        source: 'wy',
      }
    })
  },
  filterList(rawData) {
    // console.log(rawData)
    return rawData.map(item => ({
      play_count: formatPlayCount(item.playCount),
      id: String(item.id),
      author: item.creator.nickname,
      name: item.name,
      time: item.createTime ? dateFormat(item.createTime, 'Y-M-D') : '',
      img: item.coverImgUrl,
      grade: item.grade,
      total: item.trackCount,
      desc: item.description,
      source: 'wy',
    }))
  },

  // 获取标签
  getTag(tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch('https://music.163.com/weapi/playlist/catalogue', {
      method: 'post',
      form: weapi({}),
    })
    return request.then(({ body }) => {
      // console.log(JSON.stringify(body))
      if (body.code !== this.successCode) return this.getTag(++tryNum)
      return this.filterTagInfo(body)
    })
  },
  filterTagInfo({ sub, categories }) {
    const subList = {}
    for (const item of sub) {
      if (!subList[item.category]) subList[item.category] = []
      subList[item.category].push({
        parent_id: categories[item.category],
        parent_name: categories[item.category],
        id: item.name,
        name: item.name,
        source: 'wy',
      })
    }

    const list = []
    for (const key of Object.keys(categories)) {
      list.push({
        name: categories[key],
        list: subList[key],
        source: 'wy',
      })
    }
    return list
  },

  // 获取热门标签
  getHotTag(tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch('https://music.163.com/weapi/playlist/hottags', {
      method: 'post',
      form: weapi({}),
    })
    return request.then(({ body }) => {
      // console.log(JSON.stringify(body))
      if (body.code !== this.successCode) return this.getTag(++tryNum)
      return this.filterHotTagInfo(body.tags)
    })
  },
  filterHotTagInfo(rawList) {
    return rawList.map(item => ({
      id: item.playlistTag.name,
      name: item.playlistTag.name,
      source: 'wy',
    }))
  },

  getTags() {
    return Promise.all([this.getTag(), this.getHotTag()]).then(([tags, hotTag]) => ({ tags, hotTag, source: 'wy' }))
  },

  async getDetailPageUrl(rawId) {
    const { id } = await this.getListId(rawId)
    return `https://music.163.com/#/playlist?id=${id}`
  },

  search(text, page, limit = 20) {
    return eapiRequest('/api/cloudsearch/pc', {
      s: text,
      type: 1000, // 1: 单曲, 10: 专辑, 100: 歌手, 1000: 歌单, 1002: 用户, 1004: MV, 1006: 歌词, 1009: 电台, 1014: 视频
      limit,
      total: page == 1,
      offset: limit * (page - 1),
    })
      .then(({ body }) => {
        if (body.code != this.successCode) throw new Error('filed')
        // console.log(body)
        return {
          list: this.filterList(body.result.playlists),
          limit,
          total: body.result.playlistCount,
          source: 'wy',
        }
      })
  },
}

// getList
// getTags
// getListDetail

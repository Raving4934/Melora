import { httpFetch } from '../../request'
import { decodeName, formatPlayTime, sizeFormate } from '../../index'
import { formatSingerName } from '../utils'
import { requestByDataChannel } from '../channel'

export default {
  limit: 30,
  total: 0,
  page: 0,
  allPage: 1,

  // 通道 A：App 移动 CDN（无需签名，返回体积小、首包快）
  appSearch(str, page, limit) {
    const requestObj = httpFetch(`http://mobilecdn.kugou.com/api/v3/search/song?keyword=${encodeURIComponent(str)}&page=${page}&pagesize=${limit}&showtype=1&format=json`, {
      headers: {
        'User-Agent': 'Android9-AndroidPhone-13194-130-0-searchrecommendprotocol-wifi',
        'kg-rc': 1,
      },
    })
    return requestObj.then(({ body, statusCode }) => {
      if (statusCode !== 200 || !body?.data?.info?.length) throw new Error('app search empty')
      const list = body.data.info.map(item => this.appFilterData(item))
      if (!list.length) throw new Error('app search empty')
      return { list, total: body.data.total ?? list.length }
    })
  },

  appFilterData(rawData) {
    const types = []
    const _types = {}
    const push = (type, size, hash) => {
      if (!size || size === 0 || !hash) return
      const formatted = sizeFormate(size)
      types.push({ type, size: formatted, hash })
      _types[type] = { size: formatted, hash }
    }
    push('128k', rawData.filesize, rawData.hash)
    push('320k', rawData['320filesize'], rawData['320hash'])
    push('flac', rawData.sqfilesize, rawData.sqhash)
    return {
      singer: decodeName(rawData.singername),
      name: decodeName(rawData.songname),
      albumName: decodeName(rawData.album_name),
      albumId: rawData.album_id,
      songmid: String(rawData.audio_id ?? ''),
      source: 'kg',
      interval: formatPlayTime(rawData.duration),
      _interval: rawData.duration,
      img: null,
      lrc: null,
      otherSource: null,
      hash: rawData.hash,
      types,
      _types,
      typeUrl: {},
    }
  },

  // 通道 B：网页端搜索（字段更全，作为兜底）
  musicSearch(str, page, limit) {
    const searchRequest = httpFetch(`https://songsearch.kugou.com/song_search_v2?keyword=${encodeURIComponent(str)}&page=${page}&pagesize=${limit}&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1`)
    return searchRequest.then(({ body }) => body)
  },
  filterData(rawData) {
    const types = []
    const _types = {}
    if (rawData.FileSize !== 0) {
      let size = sizeFormate(rawData.FileSize)
      types.push({ type: '128k', size, hash: rawData.FileHash })
      _types['128k'] = {
        size,
        hash: rawData.FileHash,
      }
    }
    if (rawData.HQFileSize !== 0) {
      let size = sizeFormate(rawData.HQFileSize)
      types.push({ type: '320k', size, hash: rawData.HQFileHash })
      _types['320k'] = {
        size,
        hash: rawData.HQFileHash,
      }
    }
    if (rawData.SQFileSize !== 0) {
      let size = sizeFormate(rawData.SQFileSize)
      types.push({ type: 'flac', size, hash: rawData.SQFileHash })
      _types.flac = {
        size,
        hash: rawData.SQFileHash,
      }
    }
    if (rawData.ResFileSize !== 0) {
      let size = sizeFormate(rawData.ResFileSize)
      types.push({ type: 'flac24bit', size, hash: rawData.ResFileHash })
      _types.flac24bit = {
        size,
        hash: rawData.ResFileHash,
      }
    }
    return {
      singer: decodeName(formatSingerName(rawData.Singers, 'name')),
      name: decodeName(`${rawData.OriSongName}${rawData.Suffix ? ` ${rawData.Suffix}` : ''}`),
      albumName: decodeName(rawData.AlbumName),
      albumId: rawData.AlbumID,
      songmid: rawData.Audioid,
      source: 'kg',
      interval: formatPlayTime(rawData.Duration),
      _interval: rawData.Duration,
      img: null,
      lrc: null,
      otherSource: null,
      hash: rawData.FileHash,
      types,
      _types,
      typeUrl: {},
    }
  },
  handleResult(rawData) {
    let ids = new Set()
    const list = []
    rawData.forEach(item => {
      const key = item.Audioid + item.FileHash
      if (ids.has(key)) return
      ids.add(key)
      list.push(this.filterData(item))
      for (const childItem of item.Grp) {
        const key = item.Audioid + item.FileHash
        if (ids.has(key)) continue
        ids.add(key)
        list.push(this.filterData(childItem))
      }
    })
    return list
  },
  webSearch(str, page, limit) {
    return this.musicSearch(str, page, limit).then(result => {
      if (!result || result.error_code !== 0) throw new Error('web search failed')
      let list = this.handleResult(result.data.lists)
      if (list == null || !list.length) throw new Error('web search empty')
      return { list, total: result.data.total ?? list.length }
    })
  },
  search(str, page = 1, limit) {
    if (limit == null) limit = this.limit
    return requestByDataChannel(
      () => this.appSearch(str, page, limit),
      () => this.webSearch(str, page, limit),
    ).then(result => {
      this.total = result.total
      this.page = page
      this.allPage = Math.ceil(this.total / limit) || 1
      return {
        list: result.list,
        allPage: this.allPage,
        limit,
        total: this.total,
        source: 'kg',
      }
    })
  },
}

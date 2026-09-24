import { httpFetch } from '../../request'
import { decodeName, formatPlayTime, sizeFormate, dateFormat, formatPlayCount } from '../../index'
import infSign from './vendors/infSign.min.cjs'
import { signatureParams } from './util'
import { parseMusicUrl } from '../utils'

const handleSignature = (id, page, limit) => new Promise((resolve, reject) => {
  infSign({ appid: 1058, type: 0, module: 'playlist', page, pagesize: limit, specialid: id }, null, {
    useH5: !0,
    isCDN: !0,
    callback(i) {
      resolve(i.signature)
    },
  })
})

// 只读取网页内平台声明的 JSON 元数据，不执行分享页脚本。
const embeddedPlaylistInfo = html => {
  const match = /(?:^|[\n>])\s*(?:var|let|const)\s+(?:specialInfo|phpParam)\s*=\s*(?=\{)/.exec(html)
  if (!match) return null
  const start = match.index + match[0].length
  let depth = 0; let quoted = false; let escaped = false
  for (let i = start; i < html.length; i++) {
    const char = html[i]
    if (quoted) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === '"') quoted = false
    } else if (char === '"') quoted = true
    else if (char === '{') depth++
    else if (char === '}' && --depth === 0) return JSON.parse(html.slice(start, i + 1))
  }
  throw new Error('酷狗歌单页面信息不完整')
}

export default {
  listDetailLimit: 10000,
  currentTagInfo: {
    id: undefined,
    info: undefined,
  },
  sortList: [
    {
      name: '推荐',
      tid: 'recommend',
      id: '5',
    },
    {
      name: '最热',
      tid: 'hot',
      id: '6',
    },
    {
      name: '最新',
      tid: 'new',
      id: '7',
    },
    {
      name: '热藏',
      tid: 'hot_collect',
      id: '3',
    },
    {
      name: '飙升',
      tid: 'rise',
      id: '8',
    },
  ],
  cache: new Map(),
  filterSpecialDetail(rawList) {
    const ids = new Set()
    const qualityNames = { 2: '128k', 4: '320k', 5: 'flac', 6: 'flac24bit' }
    return rawList.flatMap(item => {
      if (!item) return []
      const songmid = String(item.audio_id ?? '')
      const uniqueId = songmid || item.hash
      if (!uniqueId || ids.has(uniqueId)) return []
      ids.add(uniqueId)

      const types = []
      const _types = {}
      const qualities = Array.isArray(item.relate_goods) && item.relate_goods.length
        ? item.relate_goods
        : [{ level: 2, bitrate: 128, hash: item.hash, size: item.size }]
      qualities.forEach(quality => {
        const type = qualityNames[quality.level] || (quality.bitrate === 128 ? '128k' : null)
        if (!type || !quality.hash || _types[type]) return
        const size = sizeFormate(Number(quality.size) || 0)
        types.push({ type, size, hash: quality.hash })
        _types[type] = { size, hash: quality.hash }
      })

      const fullName = String(item.name || item.remark || '')
      const separator = fullName.indexOf(' - ')
      const fallbackSinger = separator > 0 ? fullName.slice(0, separator) : ''
      const name = separator > 0 ? fullName.slice(separator + 3) : fullName
      const cover = item.cover || item.trans_param?.union_cover
      return [{
        singer: decodeName(item.singerinfo?.map(singer => singer.name).filter(Boolean).join('、') || fallbackSinger),
        name: decodeName(name),
        albumName: decodeName(String(item.albuminfo?.name || '')),
        albumId: item.albuminfo?.id ?? item.album_id ?? null,
        songmid,
        source: 'kg',
        interval: formatPlayTime((Number(item.timelen) || 0) / 1000),
        img: cover ? cover.replace('{size}', '500').replace(/^http:/, 'https:') : null,
        lrc: null,
        hash: item.hash,
        otherSource: null,
        types,
        _types,
        typeUrl: {},
      }]
    })
  },
  async getListDetailBySpecialId(id, page, retryNum = 0) {
    const limit = 30
    const params = [
      `specialid=${id}`,
      'need_sort=1',
      'module=CloudMusic',
      'clientver=11239',
      `pagesize=${limit}`,
      `specalidpgc=${id}`,
      'userid=0',
      `page=${page}`,
      'type=0',
      'area_code=1',
      'appid=1005',
    ].join('&')
    try {
      const { body, statusCode } = await httpFetch(
        `https://gatewayretry.kugou.com/v2/get_other_list_file?${params}&signature=${signatureParams(params)}`,
        {
          timeout: 10_000,
          headers: {
            'User-Agent': 'Android9-AndroidPhone-11239-18-0-playlist-wifi',
            'x-router': 'pubsongscdn.kugou.com',
          },
        },
      )
      if (statusCode !== 200 || body?.status !== 1 || body?.error_code !== 0 || !Array.isArray(body.data?.info)) {
        throw new Error('invalid Kugou playlist response')
      }
      const rawList = body.data.info
      const list = this.filterSpecialDetail(rawList)
      const total = Number(body.data.count) || 0
      const pageSize = Number(body.data.pagesize) || limit
      return {
        list,
        rawCount: rawList.length,
        page: Number(body.data.page) || page,
        limit: pageSize,
        total,
        allPage: total ? Math.ceil(total / pageSize) : 0,
        source: 'kg',
        info: {
          name: body.data.specialname || body.data.name,
          img: body.data.imgurl || body.data.pic,
        },
      }
    } catch (error) {
      if (retryNum < 1) return this.getListDetailBySpecialId(id, page, retryNum + 1)
      throw new Error('酷狗歌单暂时无法加载，请稍后重试')
    }
  },
  getInfoUrl(tagId) {
    return tagId
      ? `http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&cdn=cdn&t=5&c=${tagId}`
      : 'http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&'
  },
  getSongListUrl(sortId, tagId, page) {
    if (tagId == null) tagId = ''
    return `http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=${sortId}&c=${tagId}&p=${page}`
  },
  filterInfoHotTag(rawData) {
    const result = []
    if (rawData.status !== 1) return result
    for (const key of Object.keys(rawData.data)) {
      let tag = rawData.data[key]
      result.push({
        id: tag.special_id,
        name: tag.special_name,
        source: 'kg',
      })
    }
    return result
  },
  filterTagInfo(rawData) {
    const result = []
    for (const name of Object.keys(rawData)) {
      result.push({
        name,
        list: rawData[name].data.map(tag => ({
          parent_id: tag.parent_id,
          parent_name: tag.pname,
          id: tag.id,
          name: tag.name,
          source: 'kg',
        })),
      })
    }
    return result
  },

  getSongList(sortId, tagId, page, tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch(
      this.getSongListUrl(sortId, tagId, page),
    )
    return request.then(({ body }) => {
      if (!body || body.status !== 1) return this.getSongList(sortId, tagId, page, ++tryNum)
      return this.filterList(body.special_db)
    })
  },
  getSongListRecommend(tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const requestRecommend = httpFetch(
      'http://everydayrec.service.kugou.com/guess_special_recommend',
      {
        method: 'post',
        headers: {
          'User-Agent': 'KuGou2012-8275-web_browser_event_handler',
        },
        body: {
          appid: 1001,
          clienttime: 1566798337219,
          clientver: 8275,
          key: 'f1f93580115bb106680d2375f8032d96',
          mid: '21511157a05844bd085308bc76ef3343',
          platform: 'pc',
          userid: '262643156',
          return_min: 6,
          return_max: 15,
        },
      },
    )
    return requestRecommend.then(({ body }) => {
      if (body.status !== 1) return this.getSongListRecommend(++tryNum)
      return this.filterList(body.data.special_list)
    })
  },
  filterList(rawData) {
    return rawData.map(item => ({
      play_count: item.total_play_count || formatPlayCount(item.play_count),
      id: 'id_' + item.specialid,
      author: item.nickname,
      name: item.specialname,
      time: dateFormat(item.publish_time || item.publishtime, 'Y-M-D'),
      img: item.img || item.imgurl,
      total: item.songcount,
      grade: item.grade,
      desc: item.intro,
      source: 'kg',
    }))
  },

  async createHttp(url, options, retryNum = 0) {
    if (retryNum > 2) throw new Error('try max num')
    let result
    options.cache = 'default'
    try {
      result = await httpFetch(url, options)
    } catch (err) {
      console.log(err)
      return this.createHttp(url, options, ++retryNum)
    }
    // console.log(result.statusCode, result.body)
    if (result.statusCode !== 200 ||
      (
        (result.body.error_code !== undefined
          ? result.body.error_code
          : result.body.errcode !== undefined
            ? result.body.errcode
            : result.body.err_code
        ) !== 0)
    ) return this.createHttp(url, options, ++retryNum)
    if (result.body.data) return result.body.data
    if (Array.isArray(result.body.info)) return result.body
    return result.body.info
  },

  createTask(hashs) {
    let data = {
      area_code: '1',
      show_privilege: 1,
      show_album_info: '1',
      is_publish: '',
      appid: 1005,
      clientver: 11451,
      mid: '1',
      dfid: '-',
      clienttime: Date.now(),
      key: 'OIlwieks28dk2k092lksi2UIkp',
      fields: 'album_info,author_name,audio_info,ori_audio_name,base,songname',
    }
    let list = hashs
    let tasks = []
    while (list.length) {
      tasks.push(Object.assign({ data: list.slice(0, 100) }, data))
      if (list.length < 100) break
      list = list.slice(100)
    }
    let url = 'http://gateway.kugou.com/v2/album_audio/audio'
    return tasks.map(task => this.createHttp(url, {
      method: 'POST',
      body: task,
      headers: {
        'KG-THash': '13a3164',
        'KG-RC': '1',
        'KG-Fake': '0',
        'KG-RF': '00869891',
        'User-Agent': 'Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi',
        'x-router': 'kmr.service.kugou.com',
      },
    }).then(data => data.map(s => s[0])))
  },
  async getMusicInfos(list) {
    return this.filterData2(
      await Promise.all(
        this.createTask(
          this.deDuplication(list)
            .map(item => ({ hash: item.hash })),
        ))
        .then(([...datas]) => datas.flat()))
  },

  async getUserListDetailByCode(id, page = 1) {
    const songInfo = await this.createHttp('http://t.kugou.com/command/', {
      method: 'POST',
      headers: {
        'KG-RC': 1,
        'KG-THash': 'network_super_call.cpp:3676261689:379',
        'User-Agent': '',
      },
      body: { appid: 1001, clientver: 9020, mid: '21511157a05844bd085308bc76ef3343', clienttime: 640612895, key: '36164c4015e704673c588ee202b9ecb8', data: id },
    })
    // console.log(songInfo)
    // type 1单曲，2歌单，3电台，4酷狗码，5别人的播放队列
    let songList
    let info = songInfo.info
    switch (info.type) {
      case 2:
        if (!info.global_collection_id) return this.getListDetailBySpecialId(info.id, page)
        break

      default:
        break
    }
    if (info.global_collection_id) return this.getUserListDetail2(info.global_collection_id, page)
    if (info.userid != null) {
      songList = await this.createHttp('http://www2.kugou.kugou.com/apps/kucodeAndShare/app/', {
        method: 'POST',
        headers: {
          'KG-RC': 1,
          'KG-THash': 'network_super_call.cpp:3676261689:379',
          'User-Agent': '',
        },
        body: { appid: 1001, clientver: 9020, mid: '21511157a05844bd085308bc76ef3343', clienttime: 640612895, key: '36164c4015e704673c588ee202b9ecb8', data: { id: info.id, type: 3, userid: info.userid, collect_type: 0, page: 1, pagesize: info.count } },
      })
      // console.log(songList)
    }
    let list = await this.getMusicInfos(songList || songInfo.list)
    return {
      list,
      page: 1,
      limit: info.count,
      total: list.length,
      source: 'kg',
      info: {
        name: info.name,
        img: (info.img_size && info.img_size.replace('{size}', 240)) || info.img,
        // desc: body.result.info.list_desc,
        author: info.username,
        // play_count: formatPlayCount(info.count),
      },
    }
  },

  async getUserListDetail3(chain, page) {
    const songInfo = await this.createHttp(`http://m.kugou.com/schain/transfer?pagesize=${this.listDetailLimit}&chain=${chain}&su=1&page=${page}&n=0.7928855356604456`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1',
      },
    })
    if (!songInfo.list) {
      if (songInfo.global_collection_id) return this.getUserListDetail2(songInfo.global_collection_id, page)
      else return this.getUserListDetail4(songInfo, chain, page).catch(() => this.getUserListDetail5(chain))
    }
    const rawList = Array.isArray(songInfo.list) ? songInfo.list : []
    const list = await this.getMusicInfos(rawList)
    const total = Number(songInfo.total ?? songInfo.info?.count ?? songInfo.count) || 0
    // console.log(info, songInfo)
    return {
      list,
      rawCount: rawList.length,
      page,
      limit: this.listDetailLimit,
      total,
      allPage: total ? Math.ceil(total / this.listDetailLimit) : 0,
      source: 'kg',
      info: {
        name: songInfo.info.name,
        img: songInfo.info.img,
        // desc: body.result.info.list_desc,
        author: songInfo.info.username,
        // play_count: formatPlayCount(info.count),
      },
    }
  },

  deDuplication(datas) {
    let ids = new Set()
    return datas.filter(({ hash }) => {
      if (ids.has(hash)) return false
      ids.add(hash)
      return true
    })
  },

  async decodeGcid(gcid) {
    const params = 'dfid=-&appid=1005&mid=0&clientver=20109&clienttime=640612895&uuid=-'
    const body = {
      ret_info: 1,
      data: [
        {
          id: gcid,
          id_type: 2,
        },
      ],
    }
    const result = await this.createHttp(`https://t.kugou.com/v1/songlist/batch_decode?${params}&signature=${signatureParams(params, 'android', JSON.stringify(body))}`, {
      method: 'POST',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 10; HUAWEI HMA-AL00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36',
        Referer: 'https://m.kugou.com/',
      },
      body,
    })
    return result.list[0].global_collection_id
  },

  async getUserListDetailByLink({ info }, link, page = 1) {
    const listInfo = info['0']
    const limit = 90
    const total = Number(listInfo.count) || 0
    const setQuery = (url, key, value) => new RegExp(`${key}=\\d+`).test(url)
      ? url.replace(new RegExp(`${key}=\\d+`), `${key}=${value}`)
      : `${url}${url.includes('?') ? '&' : '?'}${key}=${value}`
    const url = setQuery(setQuery(link, 'pagesize', limit), 'page', page)
    const data = await this.createHttp(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1',
        Referer: link,
      },
    })
    const rawList = data.list.info || []
    return {
      list: await this.getMusicInfos(rawList),
      rawCount: rawList.length,
      page,
      limit,
      total,
      allPage: total ? Math.ceil(total / limit) : 0,
      source: 'kg',
      info: {
        name: listInfo.name,
        img: listInfo.pic && listInfo.pic.replace('{size}', 240),
        author: listInfo.list_create_username,
      },
    }
  },
  async getUserListDetail2(global_collection_id, page = 1) {
    let id = global_collection_id
    if (id.length > 1000) throw new Error('get list error')
    const params = 'appid=1058&specialid=0&global_specialid=' + id + '&format=jsonp&srcappid=2919&clientver=20000&clienttime=1586163242519&mid=1586163242519&uuid=1586163242519&dfid=-'
    let info = await this.createHttp(`https://mobiles.kugou.com/api/v5/special/info_v2?${params}&signature=${signatureParams(params, 'web')}`, {
      headers: {
        mid: '1586163242519',
        Referer: 'https://m3ws.kugou.com/share/index.php',
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1',
        dfid: '-',
        clienttime: '1586163242519',
      },
    })
    const limit = 300
    const pageParams = 'appid=1058&global_specialid=' + id + '&specialid=0&plat=0&version=8000&page=' + page + '&pagesize=' + limit + '&srcappid=2919&clientver=20000&clienttime=1586163263991&mid=1586163263991&uuid=1586163263991&dfid=-'
    const response = await this.createHttp(`https://mobiles.kugou.com/api/v5/special/song_v2?${pageParams}&signature=${signatureParams(pageParams, 'web')}`, {
      headers: {
        mid: '1586163263991',
        Referer: 'https://m3ws.kugou.com/share/index.php',
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1',
        dfid: '-',
        clienttime: '1586163263991',
      },
    })
    const songInfo = response.info || []
    const list = await this.getMusicInfos(songInfo)
    const total = Number(info.songcount) || 0
    // console.log(info, songInfo, list)
    return {
      list,
      rawCount: songInfo.length,
      page,
      limit,
      total,
      allPage: total ? Math.ceil(total / limit) : 0,
      source: 'kg',
      info: {
        name: info.specialname,
        img: info.imgurl && info.imgurl.replace('{size}', 240),
        desc: info.intro,
        author: info.nickname,
        play_count: formatPlayCount(info.playcount),
      },
    }
  },

  async getListInfoByChain(chain) {
    if (this.cache.has(chain)) return this.cache.get(chain)
    const { body } = await httpFetch(`https://m.kugou.com/share/?chain=${chain}&id=${chain}`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1',
      },
    })
    const result = typeof body === 'string' ? embeddedPlaylistInfo(body) : null
    if (!result) throw new Error('未获取到可公开访问的酷狗歌单信息')
    this.cache.set(chain, result)
    return result
  },

  async getUserListDetailByPcChain(chain) {
    let key = `${chain}_pc_list`
    if (this.cache.has(key)) return this.cache.get(key)
    const { body } = await httpFetch(`http://www.kugou.com/share/${chain}.html`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36',
      },
    })
    let result = body.match(/var\sdataFromSmarty\s=\s(\[.+?\])/)
    if (result) result = JSON.parse(result[1])
    this.cache.set(chain, result)
    result = await this.getMusicInfos(result)
    // console.log(info, songInfo)
    return result
  },

  async getUserListDetail4(songInfo, chain, page) {
    const limit = 100
    const [listInfo, list] = await Promise.all([
      this.getListInfoByChain(chain),
      this.getUserListDetailById(songInfo.id, page, limit),
    ])
    return {
      list: list || [],
      page,
      limit,
      total: Number(listInfo.songcount ?? listInfo.count) || 0,
      source: 'kg',
      info: {
        name: listInfo.specialname,
        img: listInfo.imgurl && listInfo.imgurl.replace('{size}', 240),
        // desc: body.result.info.list_desc,
        author: listInfo.nickname,
        // play_count: formatPlayCount(info.count),
      },
    }
  },

  async getUserListDetail5(chain) {
    const [listInfo, list] = await Promise.all([
      this.getListInfoByChain(chain),
      this.getUserListDetailByPcChain(chain),
    ])
    return {
      list: list || [],
      page: 1,
      limit: this.listDetailLimit,
      total: Number(listInfo.songcount ?? listInfo.count) || 0,
      source: 'kg',
      info: {
        name: listInfo.specialname,
        img: listInfo.imgurl && listInfo.imgurl.replace('{size}', 240),
        // desc: body.result.info.list_desc,
        author: listInfo.nickname,
        // play_count: formatPlayCount(info.count),
      },
    }
  },

  async getUserListDetailById(id, page, limit) {
    const signature = await handleSignature(id, page, limit)
    let info = await this.createHttp(`https://pubsongscdn.kugou.com/v2/get_other_list_file?srcappid=2919&clientver=20000&appid=1058&type=0&module=playlist&page=${page}&pagesize=${limit}&specialid=${id}&signature=${signature}`, {
      headers: {
        Referer: 'https://m3ws.kugou.com/share/index.php',
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1',
        dfid: '-',
      },
    })

    // console.log(info)
    let result = await this.getMusicInfos(info.info)
    // console.log(info, songInfo)
    return result
  },

  async getUserListDetail(link, page = 1) {
    const visited = new Set()
    let pendingResponse
    for (let hop = 0; hop < 5; hop++) {
      link = link.replace(/&amp;/g, '&').replace(/#.*$/, '')
      if (visited.has(link)) throw new Error('酷狗分享链接发生循环跳转')
      visited.add(link)
      const url = parseMusicUrl(link)
      if (!url || (url.hostname !== 'kugou.com' && !url.hostname.endsWith('.kugou.com'))) throw new Error('分享链接未指向酷狗歌单')
      const host = url.hostname
      const path = url.pathname
      const query = key => {
        const value = new RegExp(`(?:^|&)${key}=([^&#]*)`).exec(url.query)?.[1]
        return value ? decodeURIComponent(value) : null
      }
      const detail = /\/special\/single\/([\w-]+)\.html$/i.exec(path)?.[1]
      const share = /\/share\/([\w-]+)\.html$/i.exec(path)?.[1]
      const legacy = /\/zlist(?:\.html|\/list)$/.test(path)
      const short = /^t\d*\.kugou\.com$/.test(host) && /^\/[\w-]+\/?$/.test(path)
      const playlistPage = detail || /^\/songlist\//.test(path) || /^\/share(?:\/|$)/.test(path) || path === '/schain/transfer' || legacy
      if ((!playlistPage && !short) || /(?:song|album)\.html$/i.test(path)) throw new Error('分享链接不是酷狗歌单')

      const globalId = query('global_collection_id') || /\/(collection_[\w-]+)\.html$/.exec(path)?.[1]
      if (globalId && /^[\w-]+$/.test(globalId)) return this.getUserListDetail2(globalId, page)
      if (detail && /^\d+$/.test(detail) && query('encryp') !== '1') return this.getListDetailBySpecialId(detail, page)
      const gcid = /\/songlist\/(gcid_[\w-]+)/i.exec(path)?.[1]
      if (gcid) return this.getUserListDetail2(await this.decodeGcid(gcid), page)
      const chain = query('chain') || (/^\/share(?:\/index\.php)?\/?$/.test(path) ? query('id') : null) || (share && share !== 'zlist' && share !== 'index' ? share : null)
      if (chain && /^[\w-]+$/.test(chain)) return this.getUserListDetail3(chain, page)

      if (legacy && path.endsWith('zlist.html')) {
        link = link.replace(/^(.*)zlist\.html/, 'https://m3ws.kugou.com/zlist/list')
        pendingResponse = null
        continue
      }
      const response = pendingResponse || await httpFetch(link, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1',
          Referer: link,
        },
      })
      pendingResponse = null
      if (response.statusCode < 200 || response.statusCode >= 400) throw new Error('酷狗分享链接暂时无法访问')
      const destination = (response.url || link).replace(/#.*$/, '')
      if (destination !== link) {
        link = destination
        pendingResponse = response
        continue
      }
      if (typeof response.body === 'string') {
        const info = embeddedPlaylistInfo(response.body)
        if (info?.global_collection_id && /^[\w-]+$/.test(info.global_collection_id)) {
          return this.getUserListDetail2(info.global_collection_id, page)
        }
        if (info?.encode_gcid && /^gcid_[\w-]+$/.test(info.encode_gcid)) {
          return this.getUserListDetail2(await this.decodeGcid(info.encode_gcid), page)
        }
        if (detail && /^\d+$/.test(String(info?.id))) return this.getListDetailBySpecialId(String(info.id), page)
      } else if (legacy && response.body?.errcode === 0 && response.body.info?.['0']) {
        return this.getUserListDetailByLink(response.body, link, page)
      }
      throw new Error('未获取到可公开访问的酷狗歌单信息')
    }
    throw new Error('酷狗分享链接跳转过多')
  },

  async getListDetail(id, page = 1) {
    id = String(id)
    if (/^https?:\/\//i.test(id)) return this.getUserListDetail(id, page)
    if (/^\d+$/.test(id)) return this.getUserListDetailByCode(id, page)
    if (/^id_\d+$/.test(id)) return this.getListDetailBySpecialId(id.slice(3), page)
    throw new Error('无法识别酷狗歌单链接或编号')
  },
  filterData(rawList) {
    // console.log(rawList)
    return rawList.map(item => {
      const types = []
      const _types = {}
      if (item.filesize !== 0) {
        let size = sizeFormate(item.filesize)
        types.push({ type: '128k', size, hash: item.hash })
        _types['128k'] = {
          size,
          hash: item.hash,
        }
      }
      if (item.filesize_320 !== 0) {
        let size = sizeFormate(item.filesize_320)
        types.push({ type: '320k', size, hash: item.hash_320 })
        _types['320k'] = {
          size,
          hash: item.hash_320,
        }
      }
      if (item.filesize_ape !== 0) {
        let size = sizeFormate(item.filesize_ape)
        types.push({ type: 'ape', size, hash: item.hash_ape })
        _types.ape = {
          size,
          hash: item.hash_ape,
        }
      }
      if (item.filesize_flac !== 0) {
        let size = sizeFormate(item.filesize_flac)
        types.push({ type: 'flac', size, hash: item.hash_flac })
        _types.flac = {
          size,
          hash: item.hash_flac,
        }
      }
      return {
        singer: decodeName(item.singername),
        name: decodeName(item.songname),
        albumName: decodeName(item.album_name),
        albumId: item.album_id,
        songmid: item.audio_id,
        source: 'kg',
        interval: formatPlayTime(item.duration / 1000),
        img: null,
        lrc: null,
        hash: item.hash,
        types,
        _types,
        typeUrl: {},
      }
    })
  },
  // getSinger(singers) {
  //   let arr = []
  //   singers?.forEach(singer => {
  //     arr.push(singer.name)
  //   })
  //   return arr.join('、')
  // },
  // v9 API
  // filterDatav9(rawList) {
  //   console.log(rawList)
  //   return rawList.map(item => {
  //     const types = []
  //     const _types = {}
  //     item.relate_goods.forEach(qualityObj => {
  //       if (qualityObj.level === 2) {
  //         let size = sizeFormate(qualityObj.size)
  //         types.push({ type: '128k', size, hash: qualityObj.hash })
  //         _types['128k'] = {
  //           size,
  //           hash: qualityObj.hash,
  //         }
  //       } else if (qualityObj.level === 4) {
  //         let size = sizeFormate(qualityObj.size)
  //         types.push({ type: '320k', size, hash: qualityObj.hash })
  //         _types['320k'] = {
  //           size,
  //           hash: qualityObj.hash,
  //         }
  //       } else if (qualityObj.level === 5) {
  //         let size = sizeFormate(qualityObj.size)
  //         types.push({ type: 'flac', size, hash: qualityObj.hash })
  //         _types.flac = {
  //           size,
  //           hash: qualityObj.hash,
  //         }
  //       } else if (qualityObj.level === 6) {
  //         let size = sizeFormate(qualityObj.size)
  //         types.push({ type: 'flac24bit', size, hash: qualityObj.hash })
  //         _types.flac24bit = {
  //           size,
  //           hash: qualityObj.hash,
  //         }
  //       }
  //     })
  //     const nameInfo = item.name.split(' - ')
  //     return {
  //       singer: this.getSinger(item.singerinfo),
  //       name: decodeName((nameInfo[1] ?? nameInfo[0]).trim()),
  //       albumName: decodeName(item.albuminfo.name),
  //       albumId: item.albuminfo.id,
  //       songmid: item.audio_id,
  //       source: 'kg',
  //       interval: formatPlayTime(item.timelen / 1000),
  //       img: null,
  //       lrc: null,
  //       hash: item.hash,
  //       types,
  //       _types,
  //       typeUrl: {},
  //     }
  //   })
  // },

  // hash list filter
  filterData2(rawList) {
    // console.log(rawList)
    let ids = new Set()
    let list = []
    rawList.forEach(item => {
      if (!item) return
      if (ids.has(item.audio_info.audio_id)) return
      ids.add(item.audio_info.audio_id)
      const types = []
      const _types = {}
      if (item.audio_info.filesize !== '0') {
        let size = sizeFormate(parseInt(item.audio_info.filesize))
        types.push({ type: '128k', size, hash: item.audio_info.hash })
        _types['128k'] = {
          size,
          hash: item.audio_info.hash,
        }
      }
      if (item.audio_info.filesize_320 !== '0') {
        let size = sizeFormate(parseInt(item.audio_info.filesize_320))
        types.push({ type: '320k', size, hash: item.audio_info.hash_320 })
        _types['320k'] = {
          size,
          hash: item.audio_info.hash_320,
        }
      }
      if (item.audio_info.filesize_flac !== '0') {
        let size = sizeFormate(parseInt(item.audio_info.filesize_flac))
        types.push({ type: 'flac', size, hash: item.audio_info.hash_flac })
        _types.flac = {
          size,
          hash: item.audio_info.hash_flac,
        }
      }
      if (item.audio_info.filesize_high !== '0') {
        let size = sizeFormate(parseInt(item.audio_info.filesize_high))
        types.push({ type: 'flac24bit', size, hash: item.audio_info.hash_high })
        _types.flac24bit = {
          size,
          hash: item.audio_info.hash_high,
        }
      }
      list.push({
        singer: decodeName(item.author_name),
        name: decodeName(item.songname),
        albumName: decodeName(item.album_info.album_name),
        albumId: item.album_info.album_id,
        songmid: item.audio_info.audio_id,
        source: 'kg',
        interval: formatPlayTime(parseInt(item.audio_info.timelength) / 1000),
        img: null,
        lrc: null,
        hash: item.audio_info.hash,
        otherSource: null,
        types,
        _types,
        typeUrl: {},
      })
    })
    return list
  },

  // 获取列表信息
  getListInfo(tagId, tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const requestInfo = httpFetch(this.getInfoUrl(tagId))
    return requestInfo.then(({ body }) => {
      if (body.status !== 1) return this.getListInfo(tagId, ++tryNum)
      return {
        limit: body.data.params.pagesize,
        page: body.data.params.p,
        total: body.data.params.total,
        source: 'kg',
      }
    })
  },

  // 获取列表数据
  getList(sortId, tagId, page) {
    let tasks = [this.getSongList(sortId, tagId, page)]
    tasks.push(
      this.currentTagInfo.id === tagId
        ? Promise.resolve(this.currentTagInfo.info)
        : this.getListInfo(tagId).then(info => {
          this.currentTagInfo.id = tagId
          this.currentTagInfo.info = Object.assign({}, info)
          return info
        }),
    )
    if (!tagId && page === 1 && sortId === this.sortList[0].id) tasks.push(this.getSongListRecommend()) // 如果是所有类别，则顺便获取推荐列表
    return Promise.all(tasks).then(([list, info, recommendList]) => {
      if (recommendList) list.unshift(...recommendList)
      return {
        list,
        ...info,
      }
    })
  },

  // 获取标签
  getTags(tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch(this.getInfoUrl())
    return request.then(({ body }) => {
      if (body.status !== 1) return this.getTags(++tryNum)
      return {
        hotTag: this.filterInfoHotTag(body.data.hotTag),
        tags: this.filterTagInfo(body.data.tagids),
        source: 'kg',
      }
    })
  },

  getDetailPageUrl(id) {
    if (typeof id == 'string') {
      if (/^https?:\/\//.test(id)) return id
      id = id.replace('id_', '')
    }
    return `https://www.kugou.com/yy/special/single/${id}.html`
  },

  search(text, page, limit = 20) {
    // http://msearchretry.kugou.com/api/v3/search/special?version=9209&keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&pagesize=20&filter=0&page=1&sver=2&with_res_tag=0
    // return httpFetch(`http://ioscdn.kugou.com/api/v3/search/special?keyword=${encodeURIComponent(text)}&page=${page}&pagesize=${limit}&showtype=10&plat=2&version=7910&correct=1&sver=5`)
    return httpFetch(`http://msearchretry.kugou.com/api/v3/search/special?keyword=${encodeURIComponent(text)}&page=${page}&pagesize=${limit}&showtype=10&filter=0&version=7910&sver=2`)
      .then(({ body }) => {
        if (body.errcode != 0) throw new Error('filed')
        // console.log(body.data.info)
        return {
          list: body.data.info.map(item => {
            return {
              play_count: formatPlayCount(item.playcount),
              id: 'id_' + item.specialid,
              author: item.nickname,
              name: item.specialname,
              time: dateFormat(item.publishtime, 'Y-M-D'),
              img: item.imgurl,
              grade: item.grade,
              desc: item.intro,
              total: item.songcount,
              source: 'kg',
            }
          }),
          limit,
          total: body.data.total,
          source: 'kg',
        }
      })
  },
}

// getList
// getTags
// getListDetail

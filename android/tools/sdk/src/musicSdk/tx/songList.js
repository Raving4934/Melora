import { httpFetch } from '../../request'
import { decodeName, formatPlayTime, sizeFormate, dateFormat, formatPlayCount } from '../../index'
import { formatSingerName, parseMusicUrl } from '../utils'

const queryId = query => /(?:^|&)id=(\d+)(?:&|$)/.exec(query)?.[1] || null

export default {
  limit_list: 36,
  limit_song: 100000,
  successCode: 0,
  sortList: [
    {
      name: '最热',
      tid: 'hot',
      id: 5,
    },
    {
      name: '最新',
      tid: 'new',
      id: 2,
    },
  ],
  regExps: {
    hotTagHtml: /class="c_bg_link js_tag_item" data-id="\w+">.+?<\/a>/g,
    hotTag: /data-id="(\w+)">(.+?)<\/a>/,
  },
  tagsUrl: 'https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=%7B%22tags%22%3A%7B%22method%22%3A%22get_all_categories%22%2C%22param%22%3A%7B%22qq%22%3A%22%22%7D%2C%22module%22%3A%22playlist.PlaylistAllCategoriesServer%22%7D%7D',
  hotTagUrl: 'https://c.y.qq.com/node/pc/wk_v15/category_playlist.html',
  getListUrl(sortId, id, page) {
    if (id) {
      id = parseInt(id)
      return `https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=${encodeURIComponent(JSON.stringify({
        comm: { cv: 1602, ct: 20 },
        playlist: {
          method: 'get_category_content',
          param: {
            titleid: id,
            caller: '0',
            category_id: id,
            size: this.limit_list,
            page: page - 1,
            use_page: 1,
          },
          module: 'playlist.PlayListCategoryServer',
        },
        }))}`
    }
    return `https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=${encodeURIComponent(JSON.stringify({
          comm: { cv: 1602, ct: 20 },
          playlist: {
            method: 'get_playlist_by_tag',
            param: { id: 10000000, sin: this.limit_list * (page - 1), size: this.limit_list, order: sortId, cur_page: page },
            module: 'playlist.PlayListPlazaServer',
          },
      }))}`
  },
  getListDetailUrl(id) {
    return `https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=${id}&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0`
  },

  // http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=2849349915&pn=0&rn=100&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1
  // 获取标签
  getTag(tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch(this.tagsUrl)
    return request.then(({ body }) => {
      if (body.code !== this.successCode) return this.getTag(++tryNum)
      return this.filterTagInfo(body.tags.data.v_group)
    })
  },
  // 获取标签
  getHotTag(tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch(this.hotTagUrl)
    return request.then(({ statusCode, body }) => {
      if (statusCode !== 200) return this.getHotTag(++tryNum)
      return this.filterInfoHotTag(body)
    })
  },
  filterInfoHotTag(html) {
    let hotTag = html.match(this.regExps.hotTagHtml)
    const hotTags = []
    if (!hotTag) return hotTags

    hotTag.forEach(tagHtml => {
      let result = tagHtml.match(this.regExps.hotTag)
      if (!result) return
      hotTags.push({
        id: parseInt(result[1]),
        name: result[2],
        source: 'tx',
      })
    })
    return hotTags
  },
  filterTagInfo(rawList) {
    return rawList.map(type => ({
      name: type.group_name,
      list: type.v_item.map(item => ({
        parent_id: type.group_id,
        parent_name: type.group_name,
        id: item.id,
        name: item.name,
        source: 'tx',
      })),
    }))
  },

  // 获取列表数据
  getList(sortId, tagId, page, tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))
    const request = httpFetch(
      this.getListUrl(sortId, tagId, page),
    )
    // console.log(this.getListUrl(sortId, tagId, page))
    return request.then(({ body }) => {
      if (body.code !== this.successCode) return this.getList(sortId, tagId, page, ++tryNum)
      return tagId ? this.filterList2(body.playlist.data, page) : this.filterList(body.playlist.data, page)
    })
  },

  filterList(data, page) {
    return {
      list: data.v_playlist.map(item => ({
        play_count: formatPlayCount(item.access_num),
        id: String(item.tid),
        author: item.creator_info.nick,
        name: item.title,
        time: item.modify_time ? dateFormat(item.modify_time * 1000, 'Y-M-D') : '',
        img: item.cover_url_medium,
        // grade: item.favorcnt / 10,
        total: item.song_ids?.length,
        desc: decodeName(item.desc).replace(/<br>/g, '\n'),
        source: 'tx',
      })),
      total: data.total,
      page,
      limit: this.limit_list,
      source: 'tx',
    }
  },
  filterList2({ content }, page) {
    // console.log(content.v_item)
    return {
      list: content.v_item.map(({ basic }) => ({
        play_count: formatPlayCount(basic.play_cnt),
        id: String(basic.tid),
        author: basic.creator.nick,
        name: basic.title,
        // time: basic.publish_time,
        img: basic.cover.medium_url || basic.cover.default_url,
        // grade: basic.favorcnt / 10,
        desc: decodeName(basic.desc).replace(/<br>/g, '\n'),
        source: 'tx',
      })),
      total: content.total_cnt,
      page,
      limit: this.limit_list,
      source: 'tx',
    }
  },

  async handleParseId(link, retryNum = 0) {
    if (retryNum > 2) return Promise.reject(new Error('link try max num'))

    const requestObj_listDetailLink = httpFetch(link)
    const { url, statusCode } = await requestObj_listDetailLink
    // console.log(headers)
    if (statusCode > 400) return this.handleParseId(link, ++retryNum)
    return url
  },

  async getListId(id) {
    if (!/^[0-9]+$/.test(String(id))) {
      const parsePlaylistId = value => {
        const url = parseMusicUrl(value)
        if (!url || (url.hostname !== 'y.qq.com' && !url.hostname.endsWith('.y.qq.com'))) return null
        const pathId = /(?:^|\/)playlist\/(\d+)(?:\.html)?\/?$/.exec(url.pathname)?.[1]
        if (pathId) return pathId
        if ([
          '/n2/m/share/details/taoge.html',
          '/n/m/share/details/taoge.html',
          '/share/details/taoge.html',
          '/taoge.html',
          '/n/m/detail/taoge/index.html',
          '/n3/other/pages/details/playlist.html',
          '/musicmac/v6/playlist/detail.html',
        ].includes(url.pathname)) return queryId(url.query)
        return null
      }
      let playlistId = parsePlaylistId(id)
      if (!playlistId) {
        const url = parseMusicUrl(id)
        if (!/^c[^.]*\.y\.qq\.com$/.test(url?.hostname || '')) throw new Error('无法识别QQ音乐歌单链接')
        playlistId = parsePlaylistId(await this.handleParseId(id))
      }
      if (!playlistId) throw new Error('无法识别QQ音乐歌单链接')
      id = playlistId
    }
    return id
  },
  // 歌单详情主接口用于完整元数据；新接口按页返回歌曲和服务端总数。
  async getListDetail2(id, page = 1, tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))

    const limit = 100
    const requestObj_listDetail = httpFetch('https://u.y.qq.com/cgi-bin/musicu.fcg', {
      method: 'post',
      headers: {
        Origin: 'https://y.qq.com',
        Referer: `https://y.qq.com/n/yqq/playsquare/${id}.html`,
      },
      body: {
        comm: {
          cv: 4747474,
          ct: 24,
          format: 'json',
          inCharset: 'utf-8',
          outCharset: 'utf-8',
          platform: 'yqq.json',
          needNewCode: 1,
          uin: 0,
        },
        req_1: {
          module: 'music.srfDissInfo.aiDissInfo',
          method: 'uniform_get_Dissinfo',
          param: {
            disstid: parseInt(id),
            userinfo: 1,
            tag: 1,
            orderlist: 1,
            song_begin: (page - 1) * limit,
            song_num: limit,
            onlysonglist: 0,
            enc_host_uin: '',
          },
        },
      },
    })
    const { body } = await requestObj_listDetail
    if (body.code !== this.successCode) return this.getListDetail2(id, page, tryNum + 1)
    if (body.req_1?.code !== this.successCode) throw new Error('failed')

    const result = body.req_1.data
    const dirinfo = result.dirinfo || {}
    const rawList = Array.isArray(result.songlist) ? result.songlist : []
    const total = Number(result.total_song_num) || 0
    return {
      list: this.filterListDetail(rawList),
      rawCount: rawList.length,
      page,
      limit,
      total,
      allPage: total ? Math.ceil(total / limit) : 0,
      source: 'tx',
      info: {
        name: dirinfo.title,
        img: dirinfo.picurl,
        desc: decodeName(dirinfo.desc ?? '').replace(/<br>/g, '\n'),
        author: dirinfo.host_nick,
        play_count: formatPlayCount(dirinfo.listennum),
      },
    }
  },
  // 获取歌曲列表内的音乐
  async getListDetail(id, page = 1, tryNum = 0) {
    if (tryNum > 2) return Promise.reject(new Error('try max num'))

    id = await this.getListId(id)
    if (page > 1) return this.getListDetail2(id, page)

    const { body } = await httpFetch(this.getListDetailUrl(id), {
      headers: {
        Origin: 'https://y.qq.com',
        Referer: `https://y.qq.com/n/yqq/playsquare/${id}.html`,
      },
    })
    if (body.code !== this.successCode) return this.getListDetail(id, page, tryNum + 1)
    if (body.subcode !== this.successCode || !Array.isArray(body.cdlist) || !body.cdlist[0]) {
      return this.getListDetail2(id, page)
    }

    const cdlist = body.cdlist[0]
    const rawList = Array.isArray(cdlist.songlist) ? cdlist.songlist : []
    const total = Number(cdlist.songnum ?? cdlist.total_song_num)
    // 旧接口只在显式总数与整份返回列表相等时使用；截断或无总数时统一走可分页接口。
    if (!Number.isInteger(total) || total !== rawList.length) return this.getListDetail2(id, page)
    return {
      list: this.filterListDetail(rawList),
      rawCount: rawList.length,
      page: 1,
      limit: rawList.length,
      total,
      allPage: 1,
      source: 'tx',
      info: {
        name: cdlist.dissname,
        img: cdlist.logo,
        desc: decodeName(cdlist.desc ?? '').replace(/<br>/g, '\n'),
        author: cdlist.nickname,
        play_count: formatPlayCount(cdlist.visitnum),
      },
    }
  },
  filterListDetail(rawList) {
    // console.log(rawList)
    return rawList.map(item => {
      let types = []
      let _types = {}
      if (item.file.size_128mp3 !== 0) {
        let size = sizeFormate(item.file.size_128mp3)
        types.push({ type: '128k', size })
        _types['128k'] = {
          size,
        }
      }
      if (item.file.size_320mp3 !== 0) {
        let size = sizeFormate(item.file.size_320mp3)
        types.push({ type: '320k', size })
        _types['320k'] = {
          size,
        }
      }
      if (item.file.size_flac !== 0) {
        let size = sizeFormate(item.file.size_flac)
        types.push({ type: 'flac', size })
        _types.flac = {
          size,
        }
      }
      if (item.file.size_hires !== 0) {
        let size = sizeFormate(item.file.size_hires)
        types.push({ type: 'flac24bit', size })
        _types.flac24bit = {
          size,
        }
      }
      // types.reverse()
      return {
        singer: formatSingerName(item.singer, 'name'),
        name: item.title,
        albumName: item.album.name,
        albumId: item.album.mid,
        source: 'tx',
        interval: formatPlayTime(item.interval),
        songId: item.id,
        albumMid: item.album.mid,
        strMediaMid: item.file.media_mid,
        songmid: item.mid,
        img: (item.album.name === '' || item.album.name === '空')
          ? item.singer?.length ? `https://y.gtimg.cn/music/photo_new/T001R500x500M000${item.singer[0].mid}.jpg` : ''
          : `https://y.gtimg.cn/music/photo_new/T002R500x500M000${item.album.mid}.jpg`,
        lrc: null,
        otherSource: null,
        types,
        _types,
        typeUrl: {},
      }
    })
  },
  getTags() {
    return Promise.all([this.getTag(), this.getHotTag()]).then(([tags, hotTag]) => ({ tags, hotTag, source: 'tx' }))
  },

  async getDetailPageUrl(id) {
    id = await this.getListId(id)

    return `https://y.qq.com/n/ryqq/playlist/${id}`
  },

  search(text, page, limit = 20, retryNum = 0) {
    if (retryNum > 5) throw new Error('max retry')
    return httpFetch(`http://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist?page_no=${page - 1}&num_per_page=${limit}&format=json&query=${encodeURIComponent(text)}&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)',
        Referer: 'http://y.qq.com/portal/search.html',
      },
    })
      .then(({ body }) => {
        if (body.code != 0) return this.search(text, page, limit, ++retryNum)
        // console.log(body.data.list)
        return {
          list: body.data.list.map(item => {
            return {
              play_count: formatPlayCount(item.listennum),
              id: String(item.dissid),
              author: decodeName(item.creator.name),
              name: decodeName(item.dissname),
              time: dateFormat(item.createtime, 'Y-M-D'),
              img: item.imgurl,
              // grade: item.favorcnt / 10,
              total: item.song_count,
              desc: decodeName(decodeName(item.introduction)).replace(/<br>/g, '\n'),
              source: 'tx',
            }
          }),
          limit,
          total: body.data.sum,
          source: 'tx',
        }
      })
  },
}

// getList
// getTags
// getListDetail

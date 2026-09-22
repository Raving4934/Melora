import { httpFetch } from '../../request'
import { decodeName } from '../../index'
import { requestWithFallback } from '../requestWithFallback'

export default {
  getList() {
    return requestWithFallback(
      () => this.appHot(),
      () => this.gatewayHot(),
    ).then(list => ({ source: 'kg', list }))
  },

  // App 通道：移动 CDN 热搜（免签名，首包小）
  appHot() {
    const requestObj = httpFetch('http://mobilecdn.kugou.com/api/v3/search/hot?plat=0&page=1&pagesize=20&format=json', {
      cache: 'default',
      headers: { 'User-Agent': 'Android9-AndroidPhone-13194-130-0-searchrecommendprotocol-wifi', 'kg-rc': 1 },
    })
    return requestObj.then(({ body, statusCode }) => {
      const list = body?.data?.info?.map(item => decodeName(item.keyword)).filter(Boolean) ?? []
      if (statusCode !== 200 || !list.length) throw new Error('app hot empty')
      return list
    })
  },

  // 网关通道：热搜 tab（字段分组，作为兜底）
  gatewayHot() {
    const _requestObj = httpFetch('http://gateway.kugou.com/api/v3/search/hot_tab?signature=ee44edb9d7155821412d220bcaf509dd&appid=1005&clientver=10026&plat=0', {
      method: 'get',
      cache: null,
      headers: {
        dfid: '1ssiv93oVqMp27cirf2CvoF1',
        mid: '156798703528610303473757548878786007104',
        clienttime: 1584257267,
        'x-router': 'msearch.kugou.com',
        'user-agent': 'Android9-AndroidPhone-10020-130-0-searchrecommendprotocol-wifi',
        'kg-rc': 1,
      },
    })
    return _requestObj.then(({ body, statusCode }) => {
      if (statusCode != 200 || body.errcode !== 0) throw new Error('获取热搜词失败')
      const list = this.filterList(body.data.list)
      if (!list.length) throw new Error('gateway hot empty')
      return list
    })
  },

  filterList(rawList) {
    const list = []
    rawList.forEach(item => {
      item.keywords.map(k => list.push(decodeName(k.keyword)))
    })
    return list
  },
}

// import { decodeName } from '../../index'
// import { tokenRequest } from './util'
import { httpFetch } from '../../request'

export default {
  regExps: {
    relWord: /RELWORD=(.+)/,
  },
  async tipSearchBySong(str) {
    // 报错403，加了referer还是有问题（直接换一个
    // request = await tokenRequest(`http://www.kuwo.cn/api/www/search/searchKey?key=${encodeURIComponent(str)}`)
    const request = httpFetch(`https://tips.kuwo.cn/t.s?corp=kuwo&newver=3&p2p=1&notrace=0&c=mbox&w=${encodeURIComponent(str)}&encoding=utf8&rformat=json`, {
      Referer: 'http://www.kuwo.cn/',
    })
    return request.then(({ body, statusCode }) => {
      if (statusCode != 200 || !body.WORDITEMS) return Promise.reject(new Error('请求失败'))
      return body.WORDITEMS
    })
  },
  handleResult(rawData) {
    return rawData.map(item => item.RELWORD)
  },
  async search(str) {
    return this.tipSearchBySong(str).then(result => this.handleResult(result))
  },
}

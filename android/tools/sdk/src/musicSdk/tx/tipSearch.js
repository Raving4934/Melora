import { httpFetch } from '../../request'

export default {
  // regExps: {
  //   relWord: /RELWORD=(.+)/,
  // },
  tipSearch(str) {
    const request = httpFetch(`https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?is_xml=0&format=json&key=${encodeURIComponent(str)}&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0`, {
      headers: {
        Referer: 'https://y.qq.com/portal/player.html',
      },
    })
    return request.then(({ statusCode, body }) => {
      if (statusCode != 200 || body.code != 0) return Promise.reject(new Error('请求失败'))
      return body.data
    })
  },
  handleResult(rawData) {
    return rawData.map(info => `${info.name} - ${info.singer}`)
  },
  async search(str) {
    return this.tipSearch(str).then(result => this.handleResult(result.song.itemlist))
  },
}

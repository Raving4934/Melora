import { httpFetch } from '../../request'
import { weapi } from './utils/crypto'
import { formatSingerName } from '../utils'

export default {
  tipSearchBySong(str) {
    const request = httpFetch('https://music.163.com/weapi/search/suggest/web', {
      method: 'POST',
      headers: {
        referer: 'https://music.163.com/',
        origin: 'https://music.163.com/',
      },
      form: weapi({
        s: str,
      }),
    })
    return request.then(({ statusCode, body }) => {
      if (statusCode != 200 || body.code != 200) return Promise.reject(new Error('请求失败'))
      return body.result.songs
    })
  },
  handleResult(rawData) {
    return rawData.map(info => `${info.name} - ${formatSingerName(info.artists, 'name')}`)
  },
  async search(str) {
    return this.tipSearchBySong(str).then(result => this.handleResult(result))
  },
}

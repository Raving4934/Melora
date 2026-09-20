import { createHttpFetch } from './util'

export default {
  tipSearchBySong(str) {
    const request = createHttpFetch(`https://searchtip.kugou.com/getSearchTip?MusicTipCount=10&keyword=${encodeURIComponent(str)}`, {
      headers: {
        referer: 'https://www.kugou.com/',
      },
    })
    return request.then(body => {
      return body[0].RecordDatas
    })
  },
  handleResult(rawData) {
    return rawData.map(info => info.HintInfo)
  },
  async search(str) {
    return this.tipSearchBySong(str).then(result => this.handleResult(result))
  },
}

import { httpFetch } from '../../request'

export default {
  getPic({ songmid }) {
    let requestObj = httpFetch(`http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=${songmid}`)
    requestObj = requestObj.then(({ body }) => /^http/.test(body) ? body : null)
    return requestObj
  },
}

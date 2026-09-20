import musicSearch from './musicSearch'

export default {
  // 咪咕官方 suggest 接口已下线（v3 站点 301），以轻量搜索结果作为联想词兜底。
  async search(str) {
    return musicSearch.search(str, 1, 6).then(({ list }) =>
      (list || []).map(item => `${item.name} - ${item.singer}`))
  },
}

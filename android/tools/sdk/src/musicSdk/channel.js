// QuickJS 宿主 HTTP 为同步调用：通道必须惰性执行，按首选失败后再回退，不能伪装成并发竞速。
let current = 'auto'

export const setDataChannel = value => {
  current = value === 'app' || value === 'web' ? value : 'auto'
}

export const requestByDataChannel = (appRequest, webRequest) => {
  const [primary, fallback] = current === 'web'
    ? [webRequest, appRequest]
    : [appRequest, webRequest]
  return Promise.resolve().then(primary).catch(() => Promise.resolve().then(fallback))
}

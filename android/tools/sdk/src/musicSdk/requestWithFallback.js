// 宿主HTTP为同步调用：首选成功时不发备用请求，失败后再自动回退，不并发竞速。
export const requestWithFallback = (primary, fallback) =>
  Promise.resolve().then(primary).catch(() => Promise.resolve().then(fallback))

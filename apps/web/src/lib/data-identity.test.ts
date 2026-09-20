import { beforeEach, expect, it, vi } from 'vitest'
const a = 'a'.repeat(32)
const b = 'b'.repeat(32)
beforeEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  vi.resetModules()
})
async function fresh() {
  return import('./data-identity')
}
it('没有后端数据标识的旧服务继续使用原有应用键', async () => {
  const m = await fresh()
  localStorage.setItem(m.CLIENT_DATA_KEYS.queue, 'old queue')
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBe('old queue')
  for (const value of [
    null,
    undefined,
    {},
    { generation: '../escape', resetLegacy: true },
    { generation: a },
  ]) {
    expect(m.activateDataIdentity(value)).toBe(false)
  }
  expect(m.clientDataStorageKey(m.CLIENT_DATA_KEYS.queue)).toBe(m.CLIENT_DATA_KEYS.queue)
})
it('新库不复活旧队列/进度/字号，只处理已知应用键，不动其它存储', async () => {
  const m = await fresh()
  for (const key of Object.values(m.CLIENT_DATA_KEYS)) localStorage.setItem(key, 'old')
  localStorage.setItem('another-app:secret', 'untouched')
  localStorage.setItem('melora:unknown', 'also untouched')
  expect(m.activateDataIdentity({ generation: a, resetLegacy: true })).toBe(true)
  for (const key of Object.values(m.CLIENT_DATA_KEYS)) {
    expect(m.readClientData(key)).toBeNull()
    expect(localStorage.getItem(key)).toBeNull()
  }
  expect(localStorage.getItem('another-app:secret')).toBe('untouched')
  expect(localStorage.getItem('melora:unknown')).toBe('also untouched')
  m.writeClientData(m.CLIENT_DATA_KEYS.queue, 'new')
  expect(m.activateDataIdentity({ generation: a, resetLegacy: true })).toBe(false)
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBe('new')
  vi.resetModules()
  const reload = await fresh()
  expect(reload.readClientData(reload.CLIENT_DATA_KEYS.queue)).toBe('new')
  expect(reload.activateDataIdentity({ generation: a, resetLegacy: true })).toBe(false)
})
it('旧数据库正常升级迁移浏览器会话，不清掉用户队列/偏好', async () => {
  const m = await fresh()
  for (const key of Object.values(m.CLIENT_DATA_KEYS)) localStorage.setItem(key, key)
  expect(m.activateDataIdentity({ generation: a, resetLegacy: false })).toBe(true)
  for (const key of Object.values(m.CLIENT_DATA_KEYS)) {
    expect(m.readClientData(key)).toBe(key)
    expect(localStorage.getItem(key)).toBeNull()
  }
  vi.resetModules()
  const reload = await fresh()
  for (const key of Object.values(m.CLIENT_DATA_KEYS)) expect(reload.readClientData(key)).toBe(key)
})
it('保留重装代际不变；库被删除后换代，不读取任何旧代际状态', async () => {
  const m = await fresh()
  m.activateDataIdentity({ generation: a, resetLegacy: false })
  m.writeClientData(m.CLIENT_DATA_KEYS.queue, 'old data')
  const oldKey = m.clientDataStorageKey(m.CLIENT_DATA_KEYS.queue)
  expect(m.activateDataIdentity({ generation: a, resetLegacy: false })).toBe(false)
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBe('old data')
  expect(m.activateDataIdentity({ generation: b, resetLegacy: true })).toBe(true)
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBeNull()
  expect(localStorage.getItem(oldKey)).toBeNull()
})
it('旧标签页延迟写入和旧版本写原始键，都不能污染清理后的新库会话', async () => {
  const oldTab = await fresh()
  oldTab.activateDataIdentity({ generation: a, resetLegacy: true })
  vi.resetModules()
  const newTab = await fresh()
  newTab.activateDataIdentity({ generation: b, resetLegacy: true })
  newTab.writeClientData(newTab.CLIENT_DATA_KEYS.queue, 'fresh queue')
  oldTab.writeClientData(oldTab.CLIENT_DATA_KEYS.queue, 'delayed old queue')
  localStorage.setItem(oldTab.CLIENT_DATA_KEYS.queue, 'legacy page writes again')
  expect(newTab.readClientData(newTab.CLIENT_DATA_KEYS.queue)).toBe('fresh queue')
  expect(oldTab.activateDataIdentity({ generation: a, resetLegacy: true })).toBe(false)
  vi.resetModules()
  const reload = await fresh()
  expect(reload.readClientData(reload.CLIENT_DATA_KEYS.queue)).toBe('fresh queue')
})
it('存储删除/写入失败仍按当前内存代际隔离，不反向读取旧键', async () => {
  const m = await fresh()
  localStorage.setItem(m.CLIENT_DATA_KEYS.queue, 'old')
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new Error('denied')
  })
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('denied')
  })
  expect(() => m.activateDataIdentity({ generation: a, resetLegacy: true })).not.toThrow()
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBeNull()
  expect(m.writeClientData(m.CLIENT_DATA_KEYS.queue, 'new')).toBe(false)
})
it('旧库迁移配额失败保留旧值，恢复后可安全重试而不丢队列', async () => {
  const m = await fresh()
  localStorage.setItem(m.CLIENT_DATA_KEYS.queue, 'legacy queue')
  const set = Storage.prototype.setItem
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key, value) {
    if (key.includes(':data:')) throw new DOMException('Quota', 'QuotaExceededError')
    return set.call(this, key, value)
  })
  m.activateDataIdentity({ generation: a, resetLegacy: false })
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBe('legacy queue')
  vi.restoreAllMocks()
  expect(m.activateDataIdentity({ generation: a, resetLegacy: false })).toBe(false)
  expect(localStorage.getItem(m.CLIENT_DATA_KEYS.queue)).toBeNull()
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBe('legacy queue')
})
it('超限旧键不搬运，损坏marker也不授权任意存储路径', async () => {
  const m = await fresh()
  localStorage.setItem(
    m.DATA_IDENTITY_KEY,
    JSON.stringify({ version: 1, generation: '../other', legacyFallback: true }),
  )
  localStorage.setItem(m.CLIENT_DATA_KEYS.playback, 'x'.repeat(4097))
  m.activateDataIdentity({ generation: a, resetLegacy: false })
  expect(m.readClientData(m.CLIENT_DATA_KEYS.playback)).toBeNull()
  expect(localStorage.getItem(m.CLIENT_DATA_KEYS.playback)).toBeNull()
})

it('可信新库身份撤销损坏marker的legacy回退，不把旧键搬进新代际', async () => {
  const m = await fresh()
  localStorage.setItem(
    m.DATA_IDENTITY_KEY,
    JSON.stringify({ version: 1, generation: a, legacyFallback: true }),
  )
  localStorage.setItem(m.CLIENT_DATA_KEYS.queue, 'old unbound queue')
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBe('old unbound queue')
  expect(m.activateDataIdentity({ generation: a, resetLegacy: true })).toBe(true)
  expect(m.readClientData(m.CLIENT_DATA_KEYS.queue)).toBeNull()
  expect(localStorage.getItem(m.CLIENT_DATA_KEYS.queue)).toBeNull()
})

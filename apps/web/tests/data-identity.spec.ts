import { expect, test } from '@playwright/test'
const prefixFor = (project: string) => (project.startsWith('fnos-') ? '/app/melora' : '')
const queueKey = 'melora:player-session:v1:queue'
const playbackKey = 'melora:player-session:v1:playback'
const lyricKey = 'melora:lyric-font:v1'
const markerKey = 'melora:client-data-identity:v1'
const generation = '1'.repeat(32)
const nextGeneration = '2'.repeat(32)
const track = {
  id: 'demo:old-session',
  providerId: 'demo',
  title: '旧库会话哨兵',
  artist: '仅浏览器夹具',
  album: '',
  duration: 180,
  coverUrl: '',
  qualities: ['128k'],
  canDownload: true,
}
for (const resetLegacy of [true, false]) {
  test(`数据代际：${resetLegacy ? '新库丢弃' : '旧库升级保留'}浏览器旧会话；保留重装稳定，换库不复活`, async ({
    page,
  }, info) => {
    const prefix = prefixFor(info.project.name)
    let current = generation
    const errors: string[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    const mediaCalls: string[] = []
    // 单独测试身份契约，普通API仍由本轮真实Go入口提供；不修改后端数据库。
    await page.route('**/api/v1/auth/session', async (route) => {
      const response = await route.fetch()
      const session = await response.json()
      expect(session.authenticated).toBe(true)
      expect(session.dataIdentity).toMatchObject({
        generation: expect.stringMatching(/^[a-f0-9]{32}$/),
        resetLegacy: true,
      })
      await route.fulfill({
        response,
        json: {
          ...session,
          dataIdentity: { generation: current, resetLegacy: current === generation ? resetLegacy : true },
        },
      })
    })
    await page.route('**/api/v1/tracks/**/resolve', (route) => {
      mediaCalls.push(route.request().url())
      return route.abort()
    })
    await page.addInitScript(
      ({ queueKey, playbackKey, lyricKey, track }) => {
        if (sessionStorage.getItem('identity-fixture-seeded')) return
        sessionStorage.setItem('identity-fixture-seeded', '1')
        localStorage.setItem(queueKey, JSON.stringify({ version: 1, track, queue: [track] }))
        localStorage.setItem(
          playbackKey,
          JSON.stringify({
            version: 1,
            trackId: track.id,
            position: 35,
            duration: 180,
            volume: 0.4,
            playbackRate: 1.5,
            mode: 'single',
            quality: '128k',
          }),
        )
        localStorage.setItem(lyricKey, 'large')
        localStorage.setItem('other-app-sentinel', 'untouched')
      },
      { queueKey, playbackKey, lyricKey, track },
    )
    await page.goto(`${prefix}/now-playing`)
    await expect(page.locator('.player-immersive')).toBeVisible()
    if (resetLegacy) await expect(page.getByText(track.title, { exact: true })).toHaveCount(0)
    else await expect(page.getByRole('heading', { name: track.title, exact: true })).toBeVisible()
    const snapshot = () =>
      page.evaluate(
        ({ generation, queueKey, playbackKey, lyricKey, markerKey }) => ({
          marker: JSON.parse(localStorage.getItem(markerKey) || 'null'),
          queue: localStorage.getItem(`${queueKey}:data:${generation}`),
          playback: localStorage.getItem(`${playbackKey}:data:${generation}`),
          lyric: localStorage.getItem(`${lyricKey}:data:${generation}`),
          old: localStorage.getItem(queueKey),
          other: localStorage.getItem('other-app-sentinel'),
        }),
        { generation, queueKey, playbackKey, lyricKey, markerKey },
      )
    const first = await snapshot()
    expect(first.marker.generation).toBe(generation)
    expect(first.old).toBeNull()
    expect(first.other).toBe('untouched')
    expect(first.queue?.includes(track.title) || false).toBe(!resetLegacy)
    expect(first.lyric).toBe(resetLegacy ? null : 'large')
    await page.reload()
    await expect(page.locator('.player-immersive')).toBeVisible()
    expect(await snapshot()).toEqual(first)
    // 模拟旧标签页在新库建立后仍写旧代际；新页不得重新读取它。
    current = nextGeneration
    await page.reload()
    await expect(page.locator('.player-immersive')).toBeVisible()
    await expect(page.getByText(track.title, { exact: true })).toHaveCount(0)
    await page.evaluate(
      ({ queueKey, generation, track }) => {
        const old = JSON.stringify({ version: 1, track, queue: [track] })
        localStorage.setItem(queueKey, old)
        localStorage.setItem(`${queueKey}:data:${generation}`, old)
      },
      { queueKey, generation, track },
    )
    await page.reload()
    await expect(page.locator('.player-immersive')).toBeVisible()
    await expect(page.getByText(track.title, { exact: true })).toHaveCount(0)
    expect(
      await page.evaluate((key) => JSON.parse(localStorage.getItem(key) || '{}').generation, markerKey),
    ).toBe(nextGeneration)
    expect(errors).toEqual([])
    expect(mediaCalls).toEqual([])
  })
}

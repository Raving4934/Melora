import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

test.use({ serviceWorkers: 'block' })

const track = {
  id: 'demo:deck-regression',
  providerId: 'demo',
  title: '沉浸布局回归',
  artist: '离线测试',
  album: '暮色',
  duration: 230,
  coverUrl: '/covers/dusk.svg',
  qualities: ['128k', '320k'],
  canDownload: true,
}
const lines = Array.from({ length: 24 }, (_, index) => ({
  time: index * 7,
  text: `第${index + 1}行·让旋律陪你慢慢走`,
}))

// 会话身份和页面资产来自实际Go；只给歌词和浏览器持久化提供离线内容，不播放外部媒体。
async function scene(page: Page, request: APIRequestContext, project: string) {
  const prefix = project.startsWith('fnos-') ? '/app/melora' : ''
  const response = await request.get(`${prefix}/api/v1/auth/session`)
  expect(response.ok()).toBe(true)
  const { dataIdentity } = await response.json()
  expect(dataIdentity.generation).toMatch(/^[a-f0-9]{32}$/)
  await page.addInitScript(
    ({ identity, track }) => {
      const generation = identity.generation
      localStorage.setItem(
        'melora:client-data-identity:v1',
        JSON.stringify({ version: 1, generation, legacyFallback: false }),
      )
      localStorage.setItem(
        `melora:player-session:v1:queue:data:${generation}`,
        JSON.stringify({ version: 1, track, queue: [track] }),
      )
      localStorage.setItem(
        `melora:player-session:v1:playback:data:${generation}`,
        JSON.stringify({
          version: 1,
          trackId: track.id,
          position: 30,
          duration: 230,
          volume: 0.7,
          playbackRate: 1,
          quality: '128k',
          mode: 'list',
        }),
      )
    },
    { identity: dataIdentity, track },
  )
  await page.route('**/api/v1/tracks/*/lyrics', (route) => route.fulfill({ json: { lines } }))
  await page.goto(`${prefix}/now-playing`)
  await expect(page.locator('.player-deck-meta h1')).toHaveText(track.title)
  await expect(page.locator('.player-lyric-line.current')).toHaveText(lines[4]!.text)
}

test('双立柱默认歌词、单套控制与换页/刷新稳定，窄屏控制可达', async ({ page, request }, info) => {
  await scene(page, request, info.project.name)
  const root = page.locator('.player-immersive')
  const desktop = (page.viewportSize()?.width || 0) >= 900
  await expect(page.locator('.player-lyrics-panel'))[desktop ? 'toBeVisible' : 'toBeHidden']()
  const art = await page.locator('.player-art-panel').boundingBox()
  const controls = page.locator('.player-deck-controls')
  const controlBox = await controls.boundingBox()
  expect(art!.y + art!.height).toBeLessThanOrEqual(controlBox!.y + 1)
  if (desktop) {
    const lyrics = await page.locator('.player-lyrics-panel').boundingBox()
    expect(controlBox!.x + controlBox!.width).toBeLessThan(lyrics!.x)
    expect(lyrics!.height).toBeGreaterThan(500)
  }
  await expect(root.locator('.player-seek')).toHaveCount(1)
  await expect(root.locator('.player-transport.large')).toHaveCount(1)
  await expect(page.locator('.player-bottom-tools .player-seek')).toHaveCount(0)
  const seek = await page.locator('.player-seek').elementHandle()
  const deckTransport = await page.locator('.player-deck-transport').elementHandle()
  await page
    .locator('.player-bottom-tools')
    .getByRole('button', { name: '显示完整歌词', exact: true })
    .click()
  await expect(page.locator('.player-art-panel')).toBeHidden()
  await expect(page.locator('.player-lyrics-panel')).toBeVisible()
  expect(await seek!.evaluate((node) => node === document.querySelector('.player-seek'))).toBe(true)
  expect(
    await deckTransport!.evaluate((node) => node === document.querySelector('.player-deck-transport')),
  ).toBe(true)
  await page.getByRole('button', { name: '显示封面', exact: true }).click()
  await page.reload()
  await expect(page.locator('.player-deck-meta h1')).toHaveText(track.title)
  expect(await controls.boundingBox()).toEqual(controlBox)
  await page.getByRole('button', { name: '播放队列', exact: true }).scrollIntoViewIfNeeded()
  await expect(page.getByRole('button', { name: '播放队列', exact: true })).toBeInViewport()
  expect(await root.evaluate((node) => node.scrollWidth - node.clientWidth)).toBe(0)
  await page.screenshot({ path: info.outputPath('new-immersive-deck.png') })
})

test('歌词支持主动滚动、点击定位与恢复跟随，播放控制保持稳定', async ({ page, request }, info) => {
  await scene(page, request, info.project.name)
  if ((page.viewportSize()?.width || 0) < 900)
    await page
      .locator('.player-bottom-tools')
      .getByRole('button', { name: '显示完整歌词', exact: true })
      .click()
  const scroller = page.locator('.player-lyrics-scroll')
  const controls = await page.locator('.player-deck-controls').boundingBox()
  await scroller.focus()
  await page.keyboard.press('PageDown')
  await expect(page.getByRole('button', { name: '回到当前歌词', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '回到当前歌词', exact: true }).click()
  await expect(page.getByRole('button', { name: '回到当前歌词', exact: true })).toHaveCount(0)
  await scroller.getByRole('button', { name: lines[5]!.text, exact: true }).click()
  await expect(page.locator('.player-lyric-line.current')).toHaveText(lines[5]!.text)
  expect(await page.locator('.player-deck-controls').boundingBox()).toEqual(controls)
  const distance = await scroller.evaluate((node) => {
    const outer = node.getBoundingClientRect(),
      current = node.querySelector('.current')!.getBoundingClientRect()
    return Math.abs(current.y + current.height / 2 - outer.y - outer.height / 2)
  })
  await expect
    .poll(async () =>
      scroller.evaluate((node) => {
        const outer = node.getBoundingClientRect(),
          current = node.querySelector('.current')!.getBoundingClientRect()
        return Math.abs(current.y + current.height / 2 - outer.y - outer.height / 2)
      }),
    )
    .toBeLessThan(3)
  expect(Number.isFinite(distance)).toBe(true)
})

test('静止会话和减少动态效果模式不持续动画；背景已提色且控制栏尺寸稳定', async ({ page, request }, info) => {
  await scene(page, request, info.project.name)
  const canvas = page.locator('.ambient-canvas')
  await expect(canvas).toHaveAttribute('data-motion', 'paused')
  await expect
    .poll(() => canvas.evaluate((node) => (node as HTMLElement).style.getPropertyValue('--ambient-primary')))
    .not.toBe('rgb(110 79 86)')
  const controls = await page.locator('.player-deck-controls').boundingBox()
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await expect(page.locator('.ambient-canvas__wash').first()).toHaveCSS('animation-name', 'none')
  expect(await page.locator('.player-deck-controls').boundingBox()).toEqual(controls)
})

test('异常歌词响应只降级歌词区，混合null保留有效行且播放控制可用', async ({ page, request }, info) => {
  await scene(page, request, info.project.name)
  let payload: unknown = { lines: [null, {}, { time: 0, text: '<b>有效歌词</b>' }] }
  await page.route('**/api/v1/tracks/*/lyrics', (route) => route.fulfill({ json: payload }))
  await page.reload()
  await expect(page.locator('.player-deck-meta h1')).toHaveText(track.title)
  await expect(page.locator('.player-lyric-line')).toHaveText('<b>有效歌词</b>')
  await expect(page.locator('.player-lyric-line b')).toHaveCount(0)
  await expect(page.locator('.player-transport.large')).toHaveCount(1)
  await expect(page.getByRole('button', { name: '播放设置', exact: true })).toBeEnabled()
  payload = { lines: { unexpected: 'shape' } }
  await page.reload()
  await expect(page.locator('.player-deck-meta h1')).toHaveText(track.title)
  await expect(page.locator('.player-lyrics-panel h2')).toHaveText('暂无歌词')
  await expect(page.locator('.player-transport.large')).toHaveCount(1)
  await expect(page.locator('.player-seek')).toHaveCount(1)
})

test('真机同尺寸首屏容纳错误态和底部工具，播放设置紧凑不透底且不改播放器', async ({
  page,
  request,
}, info) => {
  await page.setViewportSize({ width: 406, height: 776 })
  await scene(page, request, info.project.name)
  await page.route('**/api/v1/tracks/*/play-info?*', (route) =>
    route.fulfill({
      status: 502,
      headers: {
        'X-Melora-Resolve-Stage': 'resolve',
        'X-Melora-Resolve-Attempts': '3',
        'X-Melora-Auto-Switch': 'true',
      },
      json: {
        error: { code: 'media_unavailable', message: '未能确认该 HTTP 媒体有可用的 HTTPS 地址，请切换音源' },
      },
    }),
  )
  const root = page.locator('.player-immersive')
  const play = root.locator('.player-transport.large').getByRole('button', { name: '播放', exact: true })
  await play.click()
  await expect(page.locator('.player-feedback [role="alert"]')).toContainText('HTTPS')
  const controls = await page.locator('.player-deck-controls').boundingBox()
  const dock = await page.locator('.player-immersive-footer').boundingBox()
  expect(dock!.y + dock!.height).toBeLessThanOrEqual(776)
  expect(await root.evaluate((node) => node.scrollTop)).toBe(0)
  await expect(page.getByRole('button', { name: '播放队列', exact: true })).toBeInViewport()
  await page.getByRole('button', { name: '播放设置', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '播放设置', exact: true })
  await expect(dialog).toHaveCSS('background-color', 'rgb(24, 27, 33)')
  await expect(dialog).toHaveCSS('backdrop-filter', 'none')
  await expect(dialog.locator('.player-rate-options')).toHaveCount(0)
  await dialog.getByRole('combobox', { name: '播放速度', exact: true }).selectOption('1.5')
  await expect(page.locator('.player-rate-select > span')).toHaveText('1.5×')
  await expect(dialog.getByRole('slider', { name: '音量', exact: true })).toBeInViewport()
  await page.screenshot({ path: info.outputPath('compact-player-settings.png') })
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  expect(await page.locator('.player-deck-controls').boundingBox()).toEqual(controls)
  await page.screenshot({ path: info.outputPath('mobile-player-first-viewport.png') })
})

test('移动搜索输入独占整行，焦点和键盘缩小视口稳定，提交保留筛选且关闭回到入口', async ({ page }, info) => {
  const prefix = info.project.name.startsWith('fnos-') ? '/app/melora' : ''
  await page.setViewportSize({ width: 406, height: 776 })
  await page.route('**/api/v1/search?*', (route) =>
    route.fulfill({ json: { tracks: [], playlists: [], albums: [], artists: [], total: 0 } }),
  )
  await page.goto(`${prefix}/search?q=旧关键词&type=album&source=kw&page=3`)
  const trigger = page.getByRole('button', { name: '打开搜索', exact: true })
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: '搜索', exact: true })
  await dialog.evaluate(async (node) => {
    await Promise.all(node.getAnimations().map((a) => a.finished.catch(() => {})))
  })
  const input = dialog.getByRole('textbox', { name: '搜索关键词', exact: true })
  await expect(input).toBeFocused()
  await expect(input).toHaveAttribute('enterkeyhint', 'search')
  expect((await dialog.locator('.sr-only').boundingBox())!.width).toBeLessThanOrEqual(1)
  expect((await input.boundingBox())!.width).toBeGreaterThan(220)
  const before = await input.boundingBox()
  await dialog.getByRole('button', { name: '清除关键词', exact: true }).click()
  await expect(input).toBeFocused()
  expect(await input.boundingBox()).toEqual(before)
  await input.fill('新关键词')
  await page.setViewportSize({ width: 406, height: 426 })
  await expect(input).toBeFocused()
  expect(await input.boundingBox()).toEqual(before)
  await expect(dialog.getByRole('button', { name: '搜索', exact: true })).toBeInViewport()
  await page.screenshot({ path: info.outputPath('mobile-search-keyboard-viewport.png') })
  await input.press('Enter')
  await expect(dialog).toHaveCount(0)
  await expect(page).toHaveURL(
    (url) =>
      url.searchParams.get('q') === '新关键词' &&
      url.searchParams.get('source') === 'kw' &&
      url.searchParams.get('type') === 'album' &&
      !url.searchParams.has('page'),
  )
  await trigger.click()
  await page
    .getByRole('dialog', { name: '搜索', exact: true })
    .getByRole('button', { name: '关闭', exact: true })
    .click()
  await expect(trigger).toBeFocused()
})

test('冷封面进页不运行颜色重绘或大模糊，减少动态效果即时收敛，热进入首帧直接复用色板', async ({
  page,
  request,
}, info) => {
  await page.setViewportSize({ width: 406, height: 776 })
  let releaseCover!: () => void
  const coverReady = new Promise<void>((resolve) => {
    releaseCover = resolve
  })
  await page.route('**/covers/dusk.svg', async (route) => {
    await coverReady
    const response = await route.fetch()
    await route.fulfill({ response })
  })
  try {
    await scene(page, request, info.project.name)
    const canvas = page.locator('.ambient-canvas')
    await expect(canvas).toHaveAttribute('data-color-state', 'settled')
    await expect(canvas.locator('.ambient-palette-layer')).toHaveCount(2)
    expect(
      await canvas
        .locator('.ambient-canvas__wash')
        .evaluateAll((nodes) => nodes.map((node) => getComputedStyle(node).filter)),
    ).toEqual(Array(6).fill('none'))
    const controls = await page.locator('.player-deck-controls').boundingBox()
    releaseCover()
    await expect(canvas).toHaveAttribute('data-color-state', 'fading')
    await expect(canvas.locator("[data-role='outgoing']")).toHaveCSS('opacity', '1')
    await expect(canvas.locator("[data-role='outgoing']")).not.toHaveCSS(
      'background-color',
      'rgba(0, 0, 0, 0)',
    )
    expect(
      await canvas.evaluate((node) =>
        node
          .getAnimations({ subtree: true })
          .some(
            (animation) =>
              'transitionProperty' in animation && animation.transitionProperty === 'background-color',
          ),
      ),
    ).toBe(false)
    // 系统偏好在淡入过程中改变，不等待fallback计时，更不留下半透明背景。
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await expect(canvas).toHaveAttribute('data-color-state', 'settled')
    await expect(canvas.locator("[data-role='current']")).toHaveCSS('opacity', '1')
    await expect(canvas.locator("[data-role='incoming']")).toHaveCount(0)
    expect(await canvas.evaluate((node) => node.getAnimations({ subtree: true }).length)).toBe(0)
    const base = await canvas.evaluate((node) => node.style.getPropertyValue('--ambient-base'))
    expect(await page.locator('.player-deck-controls').boundingBox()).toEqual(controls)
    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await page.getByRole('button', { name: '收起播放器', exact: true }).click()
    await expect(page.getByRole('button', { name: '打开全屏播放器', exact: true })).toBeVisible()
    await page.evaluate(() => {
      const result = { first: '', observed: false }
      ;(window as unknown as { ambientEntry: typeof result }).ambientEntry = result
      const observer = new MutationObserver(() => {
        const root = document.querySelector<HTMLElement>('.ambient-canvas')
        if (!root) return
        result.first = root.style.getPropertyValue('--ambient-base')
        result.observed = true
        observer.disconnect()
      })
      observer.observe(document.getElementById('root')!, { childList: true, subtree: true })
    })
    await page.getByRole('button', { name: '打开全屏播放器', exact: true }).click()
    await expect(canvas).toHaveAttribute('data-color-state', 'settled')
    expect(
      await page.evaluate(
        () => (window as unknown as { ambientEntry: { first: string; observed: boolean } }).ambientEntry,
      ),
    ).toEqual({ first: base, observed: true })
    expect(await page.locator('.player-deck-controls').boundingBox()).toEqual(controls)
    expect(await page.locator('.player-immersive').evaluate((node) => node.scrollTop)).toBe(0)
  } finally {
    releaseCover()
  }
})

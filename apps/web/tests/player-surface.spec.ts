import { test, expect } from '@playwright/test'

const prefixFor = (project: string) => (project.startsWith('fnos-') ? '/app/melora' : '')

test('沉浸冷加载：JS和外部CSS不可用时，实际Go入口已提供深色画布且普通页面不误染', async ({
  browser,
  baseURL,
}, info) => {
  expect(['http://127.0.0.1:3781', 'http://127.0.0.1:3782']).toContain(baseURL)
  const context = await browser.newContext({
    baseURL,
    javaScriptEnabled: false,
    viewport: info.project.use.viewport,
  })
  const prefix = prefixFor(info.project.name)
  try {
    await context.route('**/*', (route) =>
      ['script', 'stylesheet'].includes(route.request().resourceType()) ? route.abort() : route.continue(),
    )
    const page = await context.newPage()
    const response = await page.goto(`${prefix}/now-playing?view=lyrics`)
    expect(response?.status()).toBe(200)
    expect(response?.headers()['cache-control']).toBe('no-store')
    await expect(page.locator('html')).toHaveAttribute('data-melora-surface', 'immersive')
    for (const selector of ['html', 'body', '#root'])
      await expect(page.locator(selector)).toHaveCSS('background-color', 'rgb(23, 26, 30)')
    await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#171a1e')
    await expect(page.locator('.player-immersive')).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('immersive-before-js-css.png') })
    await page.reload()
    await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(23, 26, 30)')
    await page.goto(`${prefix}/settings?next=/now-playing`)
    await expect(page.locator('html')).toHaveAttribute('data-melora-surface', 'page')
    await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(247, 248, 250)')
    await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#ffffff')
  } finally {
    await context.close()
  }
})

test('沉浸反复进出与刷新同步根画布、主题色，不依赖延时修正', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  await page.goto(`${prefix}/now-playing`)
  for (let i = 0; i < 3; i++) {
    await expect(page.locator('.player-immersive')).toBeVisible()
    const dark = await page.evaluate(() => ({
      background: getComputedStyle(document.documentElement).backgroundColor,
      theme: document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')?.content,
    }))
    expect(dark).toEqual({ background: 'rgb(23, 26, 30)', theme: '#171a1e' })
    await page.getByRole('button', { name: '收起播放器' }).click()
    await expect(page.locator('.player-mini')).toBeVisible()
    expect(await page.locator('html').evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(
      'rgb(255, 255, 255)',
    )
    await page.getByRole('button', { name: '打开全屏播放器' }).click()
  }
  await page.reload()
  await expect(page.locator('.player-immersive')).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('data-melora-surface', 'immersive')
})

// 宿主夹具只提供边框，不冒充已获取真实fnOS内部DOM；iframe内运行当前实际Go/生产Web。
test('沉浸边界遵守独立模式拒绝嵌入；网关仅自身右底边变暗并在退出或导航还原', async ({
  page,
  baseURL,
}, info) => {
  const prefix = prefixFor(info.project.name)
  const entry = `${baseURL}${prefix}/now-playing`
  if (!prefix) {
    // standalone明确禁止被嵌入；不能为了边框测试放宽真实安全头。
    const response = await page.goto(entry)
    expect(response?.headers()['x-frame-options']).toBe('DENY')
    expect(response?.headers()['content-security-policy']).toContain("frame-ancestors 'none'")
    await expect(page.locator('.player-immersive')).toBeVisible()
    expect(await page.evaluate(() => window.frameElement)).toBeNull()
    return
  }
  await page.route(`**${prefix}/__edge-fixture`, (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:white}#marker{height:40px;background:lime}#pane{width:390px;height:600px;background:orange}iframe{display:block;box-sizing:border-box;width:390px;height:600px;border:0;border-right:1px solid white;border-bottom:1px solid white}</style><div id="marker">host marker</div><div id="pane"><iframe name="melora-edge" title="边缘回归" src="${entry}"></iframe></div>`,
    }),
  )
  await page.goto(`${prefix}/__edge-fixture`)
  const frame = page.frames().find((item) => item.name() === 'melora-edge')!
  await expect(frame.locator('.player-immersive')).toBeVisible()
  const element = page.locator('iframe[name="melora-edge"]')
  const originalBox = await element.boundingBox()
  await expect(element).toHaveCSS('border-right-color', 'rgb(23, 26, 30)')
  await expect(element).toHaveCSS('border-bottom-color', 'rgb(23, 26, 30)')
  await expect(element).toHaveCSS('border-right-width', '1px')
  await expect(page.locator('#marker')).toHaveCSS('background-color', 'rgb(0, 255, 0)')
  await expect(page.locator('#pane')).toHaveCSS('background-color', 'rgb(255, 165, 0)')
  await expect(frame.locator('html')).toHaveCSS('overflow', 'hidden')
  await expect(frame.locator('.player-immersive')).toHaveCSS('overflow-y', 'auto')
  for (let i = 0; i < 3; i++) {
    await frame.getByRole('button', { name: '收起播放器', exact: true }).click()
    await expect(frame.locator('.player-mini')).toBeVisible()
    await expect(element).toHaveCSS('border-right-color', 'rgb(255, 255, 255)')
    await expect(element).toHaveCSS('border-bottom-color', 'rgb(255, 255, 255)')
    expect(await element.boundingBox()).toEqual(originalBox)
    await frame.getByRole('button', { name: '打开全屏播放器', exact: true }).click()
    await expect(element).toHaveCSS('border-bottom-color', 'rgb(23, 26, 30)')
  }
  await page.screenshot({ path: info.outputPath('own-frame-dark-edge-host-unchanged.png') })
  await frame.goto('about:blank')
  await expect(element).toHaveCSS('border-right-color', 'rgb(255, 255, 255)')
  await expect(element).toHaveCSS('border-bottom-color', 'rgb(255, 255, 255)')
  expect(await element.boundingBox()).toEqual(originalBox)
})

// 使用真实设置页的可滚动内容；不靠 overflow:hidden 或空夹具伪造“去掉滚动条”。
test('主内容不绘制滚动条但保留滚轮与键盘滚动，刷新和切页不改变可用宽度', async ({ page }, info) => {
  const prefix = prefixFor(info.project.name)
  // 设置删去两个外置文件选项后，高桌面可无需滚动；使用真实矮窗口建立可滚动场景。
  const viewport = page.viewportSize()
  if (viewport && viewport.width >= 900) await page.setViewportSize({ ...viewport, height: 600 })
  await page.goto(`${prefix}/settings`)
  const main = page.locator('#main-content')
  await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible()
  await expect.poll(() => main.evaluate((el) => el.scrollHeight > el.clientHeight)).toBe(true)
  const geometry = () =>
    main.evaluate((el) => ({ width: el.getBoundingClientRect().width, client: el.clientWidth }))
  const initial = await geometry()
  expect.soft(await main.evaluate((el) => getComputedStyle(el).scrollbarWidth)).toBe('none')
  expect.soft(initial.client).toBe(Math.round(initial.width))
  await page.screenshot({ path: info.outputPath('main-scrollbar.png') })
  await main.hover({ position: { x: 16, y: 40 } })
  await page.mouse.wheel(0, 240)
  await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
  await main.evaluate((el) => {
    el.scrollTop = 0
    el.focus({ preventScroll: true })
  })
  await page.keyboard.press('PageDown')
  await expect.poll(() => main.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
  await page.keyboard.press('End')
  await expect
    .poll(() => main.evaluate((el) => el.scrollHeight - el.clientHeight - el.scrollTop))
    .toBeLessThan(2)
  expect(await geometry()).toEqual(initial)
  const controls = await page.locator('.player-mini').boundingBox()
  await page.reload()
  await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible()
  expect(await geometry()).toEqual(initial)
  expect(await page.locator('.player-mini').boundingBox()).toEqual(controls)
  await page.getByRole('button', { name: '打开全屏播放器', exact: true }).click()
  await expect(page.locator('.player-immersive')).toBeVisible()
  await page.getByRole('button', { name: '收起播放器', exact: true }).click()
  await expect(main).toBeVisible()
  expect(await geometry()).toEqual(initial)
})

test('移动独立窗口将内容避让四边安全区并让页面背景延伸到状态栏', async ({ page }, info) => {
  test.skip(!info.project.name.endsWith('mobile'), '仅移动视口需要状态栏安全区回归')
  const prefix = prefixFor(info.project.name)
  const applySafeArea = () =>
    page.evaluate(() => {
      const root = document.documentElement.style
      root.setProperty('--safe-area-top', '24px')
      root.setProperty('--safe-area-right', '28px')
      root.setProperty('--safe-area-bottom', '22px')
      root.setProperty('--safe-area-left', '26px')
    })

  await page.goto(`${prefix}/settings`)
  await applySafeArea()
  const shell = await page.evaluate(() => {
    const style = (selector: string) => getComputedStyle(document.querySelector<HTMLElement>(selector)!)
    return {
      headerHeight: style('.app-header').height,
      headerTop: style('.app-header').paddingTop,
      headerRight: style('.app-header').paddingRight,
      headerLeft: style('.app-header').paddingLeft,
      mainRight: style('.main-content').paddingRight,
      mainLeft: style('.main-content').paddingLeft,
      miniHeight: style('.player-mini').height,
      miniRight: style('.player-mini').paddingRight,
      miniBottom: style('.player-mini').paddingBottom,
      miniLeft: style('.player-mini').paddingLeft,
    }
  })
  expect(shell).toEqual({
    headerHeight: '134px',
    headerTop: '24px',
    headerRight: '28px',
    headerLeft: '26px',
    mainRight: '28px',
    mainLeft: '26px',
    miniHeight: '94px',
    miniRight: '28px',
    miniBottom: '22px',
    miniLeft: '26px',
  })

  await page.goto(`${prefix}/now-playing`)
  await applySafeArea()
  const immersive = await page.evaluate(() => {
    const style = (selector: string) => getComputedStyle(document.querySelector<HTMLElement>(selector)!)
    return {
      headerTop: style('.player-immersive-header').paddingTop,
      headerRight: style('.player-immersive-header').paddingRight,
      headerLeft: style('.player-immersive-header').paddingLeft,
      stageRight: style('.player-stage').paddingRight,
      stageLeft: style('.player-stage').paddingLeft,
      footerRight: style('.player-immersive-footer').paddingRight,
      footerBottom: style('.player-immersive-footer').paddingBottom,
      footerLeft: style('.player-immersive-footer').paddingLeft,
      background: style('.player-immersive').backgroundColor,
    }
  })
  expect(immersive).toEqual({
    headerTop: '24px',
    headerRight: '28px',
    headerLeft: '26px',
    stageRight: '28px',
    stageLeft: '26px',
    footerRight: '28px',
    footerBottom: '22px',
    footerLeft: '26px',
    background: 'rgb(23, 26, 30)',
  })
})

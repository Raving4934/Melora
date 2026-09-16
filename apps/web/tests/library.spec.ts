import { expect, test, type Page } from '@playwright/test'
import type { Settings } from '../src/lib/types'
import { jsonOK, mutationHeaders } from './settings-v5-e2e.helpers'

const firstTitle = 'Maple Leaf Rag · 1906 年录音'
const secondTitle = 'The Entertainer · 2007 年键盘演奏'
const prefixFor = (project: string) => (project.startsWith('fnos-') ? '/app/melora' : '')

async function openFirstChart(page: Page, prefix: string) {
  await expect(page.getByRole('heading', { name: '排行榜', exact: true })).toBeVisible()
  await expect(page.locator('.chart-panel .track-row').first()).toBeVisible()
  const card = page.locator('.chart-panel-link').first()
  await expect(card).toBeVisible()
  const href = (await card.getAttribute('href'))!
  expect(href.startsWith(`${prefix}/charts/`)).toBe(true)
  await card.click()
  await expect(page).toHaveURL((url) => url.pathname === href)
  await expect(page.locator('.track-row')).toHaveCount(2)
  await expect(page.getByRole('button', { name: '返回上一页', exact: true })).toHaveCount(0)
}

test.beforeEach(async ({ request, baseURL }, testInfo) => {
  const prefix = prefixFor(testInfo.project.name)
  // APIRequestContext 不经过浏览器 api.ts；网关前置清理必须自行获取并携带令牌。
  const headers: Record<string, string> = {}
  if (prefix) {
    expect(baseURL).toBeTruthy()
    const origin = new URL(baseURL!).origin
    const session = await request.get(`${prefix}/api/v1/auth/session`, {
      headers: { 'X-Melora-Origin': origin },
    })
    expect(session.ok()).toBe(true)
    const state = await session.json()
    expect(state).toMatchObject({ authenticated: true, required: false, authMode: 'fnos' })
    expect(state.csrfToken).toEqual(expect.any(String))
    expect(state.csrfToken.length).toBeGreaterThan(0)
    headers.Origin = origin
    headers['X-Melora-CSRF'] = state.csrfToken
  }
  const demo = await request.patch(`${prefix}/api/v1/providers/demo`, {
    headers,
    data: { enabled: true },
  })
  expect(demo.ok()).toBe(true)
  expect(await demo.json()).toMatchObject({ id: 'demo', enabled: true, isDemo: true })
  const playlists = await request.get(`${prefix}/api/v1/library/playlists`)
  expect(playlists.ok()).toBe(true)
  for (const playlist of (await playlists.json()) as { id: string; title: string }[]) {
    if (playlist.title.startsWith('回归 ·')) {
      const removed = await request.delete(
        `${prefix}/api/v1/library/playlists/${encodeURIComponent(playlist.id)}`,
        { headers },
      )
      expect(removed.ok()).toBe(true)
    }
  }
})

test('自建歌单创建、添加去重、排序、编辑、刷新持久化与删除', async ({ page, request }, testInfo) => {
  const prefix = prefixFor(testInfo.project.name)
  const title = '回归 · 旅途歌单'
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(`${prefix}/library?tab=created`)
  // 空态和页头都提供创建入口，限定页头而不是依赖按钮出现顺序。
  await page.locator('.page-heading').getByRole('button', { name: '新建歌单', exact: true }).click()
  const create = page.getByRole('dialog', { name: '新建歌单', exact: true })
  await create.getByLabel('歌单名称', { exact: true }).fill(title)
  await create.getByLabel('歌单简介', { exact: false }).fill('给每一段旅程留一首歌。')
  await create.getByRole('button', { name: '创建歌单', exact: true }).click()
  await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
  const detailURL = page.url()
  const id = decodeURIComponent(new URL(detailURL).pathname.split('/').at(-1)!)
  await expect(page.getByRole('heading', { name: '歌单暂无歌曲', exact: true })).toBeVisible()
  const cover = page.locator('.user-playlist-heading > .cover img')
  await expect(cover).toHaveAttribute('src', `${prefix}/covers/placeholder.svg`)
  await expect(cover).toHaveJSProperty('complete', true)
  expect(await cover.evaluate((image) => (image as HTMLImageElement).naturalWidth)).toBeGreaterThan(0)
  await page.goto(`${prefix}/`)
  await openFirstChart(page, prefix)
  // 重复添加第一首，不制造重复项。
  for (const trackTitle of [firstTitle, firstTitle, secondTitle]) {
    const more = page.getByRole('button', { name: `更多操作 ${trackTitle}`, exact: true })
    if (await more.isVisible()) await more.click()
    await page.getByRole('button', { name: `将 ${trackTitle} 添加到歌单`, exact: true }).click()
    const picker = page.getByRole('dialog', { name: '添加到自建歌单' })
    await picker.getByRole('button', { name: `加入歌单 ${title}`, exact: true }).click()
    await expect(picker).toHaveCount(0)
  }
  await page.goto(detailURL)
  await expect(page.locator('.track-row')).toHaveCount(2)
  await page.reload()
  await expect(page.locator('.track-row').first()).toContainText(firstTitle)
  await page.getByRole('button', { name: '管理歌曲', exact: true }).click()
  await page.getByRole('button', { name: `下移 ${firstTitle}`, exact: true }).click()
  await expect(page.locator('.track-row').first()).toContainText(secondTitle)
  await page.getByRole('button', { name: '编辑歌单', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '编辑歌单', exact: true })
  await editor.getByLabel('歌单名称', { exact: true }).fill(`${title} · 已编辑`)
  await editor.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByRole('heading', { name: `${title} · 已编辑`, exact: true })).toBeVisible()
  await page.reload()
  await expect(page.locator('.track-row').first()).toContainText(secondTitle)
  await expect(page.getByText('给每一段旅程留一首歌。', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('user-playlist.png') })
  // 后台慢刷新保留现有行与播放器高度。
  await page.route(`**${prefix}/api/v1/library/playlists/${encodeURIComponent(id)}`, async (route) => {
    if (route.request().method() === 'GET') await new Promise((resolve) => setTimeout(resolve, 800))
    await route.continue()
  })
  const top = (await page.locator('.track-row').first().boundingBox())!.y
  await page.getByRole('button', { name: '刷新自建歌单' }).click()
  await expect(page.locator('.track-row')).toHaveCount(2)
  expect(Math.abs((await page.locator('.track-row').first().boundingBox())!.y - top)).toBeLessThan(2)
  await expect(page.getByRole('button', { name: '刷新自建歌单' })).toBeEnabled()
  await page.unrouteAll({ behavior: 'wait' })
  await page.getByRole('button', { name: '管理歌曲', exact: true }).click()
  await page.getByRole('button', { name: `从歌单移除 ${firstTitle}`, exact: true }).click()
  await page
    .getByRole('dialog', { name: '从歌单移除这首歌？' })
    .getByRole('button', { name: '确认移除' })
    .click()
  await expect(page.locator('.track-row')).toHaveCount(1)
  await page.getByRole('button', { name: '删除这张歌单' }).click()
  await page.getByRole('dialog', { name: '删除这张歌单？' }).getByRole('button', { name: '确认删除' }).click()
  await expect(page).toHaveURL(/\/library\?tab=created$/)
  await expect(page.getByRole('link', { name: new RegExp(title) })).toHaveCount(0)
  expect((await request.get(`${prefix}/api/v1/library/playlists/${encodeURIComponent(id)}`)).status()).toBe(
    404,
  )
  expect(errors).toEqual([])
})

test('歌曲弹窗新建并添加，创建失败保留输入且提交按钮不跳位', async ({ page }, testInfo) => {
  const prefix = prefixFor(testInfo.project.name)
  await page.goto(`${prefix}/`)
  await openFirstChart(page, prefix)
  const more = page.getByRole('button', { name: `更多操作 ${firstTitle}`, exact: true })
  if (await more.isVisible()) await more.click()
  await page.getByRole('button', { name: `将 ${firstTitle} 添加到歌单`, exact: true }).click()
  const modal = page.getByRole('dialog', { name: '添加到自建歌单' })
  await modal.getByRole('button', { name: '新建一张歌单' }).click()
  await modal.getByLabel('歌单名称', { exact: true }).fill('回归 · 从歌曲创建')
  let fail = true
  await page.route(`**${prefix}/api/v1/library/playlists`, async (route) => {
    if (route.request().method() === 'POST' && fail) {
      fail = false
      await new Promise((resolve) => setTimeout(resolve, 800))
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'test_failure', message: '测试：保存暂不可用' } }),
      })
    } else await route.continue()
  })
  const submit = modal.getByRole('button', { name: '创建并添加', exact: true })
  const before = (await submit.boundingBox())!
  await submit.click()
  await expect(submit).toBeDisabled()
  await page.keyboard.press('Escape')
  await expect(modal).toBeVisible()
  const during = (await submit.boundingBox())!
  expect(during.width).toBeCloseTo(before.width, 0)
  expect(during.height).toBeCloseTo(before.height, 0)
  await expect(modal.getByRole('alert')).toContainText('保存暂不可用')
  await expect(modal.getByLabel('歌单名称', { exact: true })).toHaveValue('回归 · 从歌曲创建')
  await submit.click()
  await expect(modal).toHaveCount(0)
  await page.goto(`${prefix}/library?tab=created`)
  await page.getByRole('link', { name: /回归 · 从歌曲创建/ }).click()
  await expect(page.locator('.track-row')).toHaveCount(1)
})

test('内嵌选项自动保存并跨刷新保持，旧独立文件偏好不被新下载继承', async ({
  page,
  request,
  baseURL,
}, testInfo) => {
  const prefix = prefixFor(testInfo.project.name)
  // 复用隔离 harness 校验及网关 CSRF；写真实旧偏好，任务 POST 则只验证 UI 请求，不下载媒体。
  const headers = await mutationHeaders(request, prefix, baseURL)
  const previous = await jsonOK<Settings>(await request.get(`${prefix}/api/v1/settings`))
  const restore = {
    downloadRoot: previous.downloadRoot,
    writeLyrics: previous.writeLyrics,
    writeCover: previous.writeCover,
    embedTags: previous.embedTags ?? true,
    writeMetadata: previous.writeMetadata,
    showDirect: previous.showDirect,
  }
  const storage = await jsonOK<{ authorized: boolean; authorizedRoots: string[] }>(
    await request.get(`${prefix}/api/v1/storage/status`),
  )
  expect(storage.authorized).toBe(true)
  expect(storage.authorizedRoots.length).toBeGreaterThan(0)
  try {
    const seeded = await jsonOK<Settings>(
      await request.patch(`${prefix}/api/v1/settings`, {
        headers,
        data: {
          downloadRoot: storage.authorizedRoots.at(-1)!,
          writeLyrics: true,
          writeCover: true,
          embedTags: false,
          writeMetadata: false,
          showDirect: false,
        },
      }),
    )
    expect(seeded).toMatchObject({ writeLyrics: true, writeCover: true, embedTags: false })
    await page.goto(`${prefix}/settings`)
    await expect(page.getByRole('region', { name: '存储空间' })).toHaveCount(0)
    await expect(page.getByRole('switch', { name: '保存歌曲信息 JSON', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '保存设置', exact: true })).toHaveCount(0)
    const toggle = page.getByRole('switch', { name: '内嵌元数据（ID3）与封面', exact: true })
    await expect(toggle).toHaveAttribute('aria-checked', 'false')
    for (const enabled of [true, false, true]) {
      for (const label of ['另存歌词文件（LRC）', '另存封面图片']) {
        await expect(page.getByRole('switch', { name: label, exact: true })).toHaveCount(0)
        await expect(page.getByText(label, { exact: true })).toHaveCount(0)
      }
      const saving = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === `${prefix}/api/v1/settings` &&
          response.request().method() === 'PATCH',
      )
      await toggle.click()
      const response = await saving
      expect(response.request().postDataJSON()).toEqual({ embedTags: enabled })
      expect(await jsonOK<Settings>(response)).toMatchObject({
        embedTags: enabled,
        writeLyrics: true,
        writeCover: true,
      })
      if (prefix) expect(response.request().headers()['x-melora-csrf']).toBeTruthy()
      await expect(page.locator('#setting-embedTags-feedback')).toHaveText('已保存')
      const current = await jsonOK<Settings>(await request.get(`${prefix}/api/v1/settings`))
      expect(current).toMatchObject({ embedTags: enabled, writeLyrics: true, writeCover: true })
      await page.reload()
      await expect(toggle).toHaveAttribute('aria-checked', String(enabled))
    }
    await expect(page.getByRole('button', { name: '刷新授权', exact: true })).toHaveCount(0)
    await expect(page.getByText('授权步骤', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '仅内嵌，不另存文件', exact: true })).toHaveCount(0)

    let submissions = 0
    await page.route(`**${prefix}/api/v1/downloads`, (route) => {
      if (route.request().method() !== 'POST') return route.continue()
      submissions += 1
      return route.fulfill({ status: 201, json: { id: 'fixture-v18-embed-only', state: 'queued' } })
    })
    await page.goto(`${prefix}/`)
    await openFirstChart(page, prefix)
    const row = page.locator('.track-row').first()
    const save = row.getByRole('button', { name: `保存 ${firstTitle} 到飞牛`, exact: true })
    if (await save.isVisible()) await save.click()
    else {
      await row.getByRole('button', { name: `更多操作 ${firstTitle}`, exact: true }).click()
      await page
        .getByRole('dialog', { name: '歌曲操作', exact: true })
        .getByRole('button', { name: `保存 ${firstTitle} 到飞牛`, exact: true })
        .click()
    }
    const dialog = page.getByRole('dialog', { name: '保存到飞牛', exact: true })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('checkbox')).toHaveCount(1)
    await expect(
      dialog.getByRole('checkbox', { name: '内嵌歌曲信息、歌词与封面', exact: true }),
    ).toBeChecked()
    for (const label of ['另存歌词文件（LRC）', '另存封面图片']) {
      await expect(dialog.getByRole('checkbox', { name: label, exact: true })).toHaveCount(0)
      await expect(dialog.getByText(label, { exact: true })).toHaveCount(0)
    }
    const submitted = page.waitForRequest(
      (request) =>
        new URL(request.url()).pathname === `${prefix}/api/v1/downloads` && request.method() === 'POST',
    )
    await dialog.getByRole('button', { name: '确认保存', exact: true }).click()
    const creation = await submitted
    expect(creation.postDataJSON()).toEqual({
      trackId: 'demo:maple-leaf-rag-1906',
      quality: 'standard',
      embedTags: true,
      writeLyrics: false,
      writeCover: false,
    })
    if (prefix) expect(creation.headers()['x-melora-csrf']).toBeTruthy()
    await expect(dialog).not.toBeVisible()
    expect(submissions).toBe(1)
    expect(await jsonOK<Settings>(await request.get(`${prefix}/api/v1/settings`))).toMatchObject({
      embedTags: true,
      writeLyrics: true,
      writeCover: true,
    })
  } finally {
    // 成功或失败都恢复原设置，避免污染后续用例；不删除任何历史附件或音乐。
    await jsonOK(await request.patch(`${prefix}/api/v1/settings`, { headers, data: restore }))
  }
})

test('歌词封面标签的实际结果与警告明确区分，路径弹窗不冒充成功', async ({ page }, testInfo) => {
  // 仅 UI 状态夹具；文件写入由 Go 与实际包下载测试另行验证。
  const prefix = prefixFor(testInfo.project.name)
  const job = {
    id: 'fixture-metadata-ok',
    quality: 'standard',
    state: 'completed',
    bytesDone: 2048,
    bytesTotal: 2048,
    speed: 0,
    targetPath: '/fixture/Singles/music.ogg',
    writeLyrics: true,
    writeCover: true,
    embedTags: true,
    lyricsPath: '/fixture/Singles/music.lrc',
    coverPath: '/fixture/Singles/music.jpg',
    tagsWritten: true,
    createdAt: '2026-09-07T00:00:00Z',
    updatedAt: '2026-09-07T00:00:00Z',
    track: {
      id: 'demo:maple-leaf-rag-1906',
      providerId: 'demo',
      title: firstTitle,
      artist: 'Test artist',
      album: 'Test album',
      duration: 115,
      coverUrl: '/covers/paper.svg',
      qualities: ['standard'],
      canDownload: true,
    },
  }
  const warning = {
    ...job,
    id: 'fixture-metadata-warning',
    track: { ...job.track, title: secondTitle },
    lyricsPath: undefined,
    coverPath: undefined,
    tagsWritten: false,
    warning: '音频已完成；歌词文件已存在，未覆盖，标签写入失败',
  }
  const jobs = [job, warning]
  await page.route(`**${prefix}/api/v1/downloads`, (route) => route.fulfill({ json: jobs }))
  await page.route(`**${prefix}/api/v1/downloads/events`, (route) =>
    route.fulfill({
      contentType: 'text/event-stream',
      body: `event: downloads\ndata: ${JSON.stringify(jobs)}\n\n`,
    }),
  )
  await page.goto(`${prefix}/downloads`)
  await page.getByRole('tab', { name: /^已完成/ }).click()
  await expect(page.locator('.download-row')).toHaveCount(2)
  await expect(page.locator('.download-row').first()).toContainText('已写入标签')
  const warningRow = page.locator('.download-row').nth(1)
  const rowWarning = warningRow.getByText(warning.warning, { exact: true })
  await expect(rowWarning).toHaveCount(1)
  await expect(rowWarning).toBeHidden()
  await warningRow.getByText('下载详情', { exact: true }).click()
  await expect(rowWarning).toBeVisible()
  await expect(warningRow).not.toContainText('已写入标签')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: `查看 ${firstTitle} 保存位置`, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '音乐保存位置' })
  await expect(dialog).toContainText('/fixture/Singles/music.lrc')
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: `查看 ${secondTitle} 保存位置`, exact: true }).click()
  const dialogWarning = dialog.getByText(warning.warning, { exact: true })
  await expect(dialogWarning).toHaveCount(1)
  await expect(dialogWarning).toBeHidden()
  await dialog.getByText('下载详情', { exact: true }).click()
  await expect(dialogWarning).toBeVisible()
  await expect(dialog).not.toContainText('音频内嵌标签已写入')
  await expect(dialog.locator('.path-display')).toHaveCount(1)
  await expect(dialog).not.toContainText('/fixture/Singles/music.lrc')
})

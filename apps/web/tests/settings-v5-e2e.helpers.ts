import { expect, type APIRequestContext, type APIResponse, type Browser, type Page } from '@playwright/test'

export interface SourceFixture {
  id: string
  name: string
  status: string
  error?: string
  allowHTTPHosts: string[]
  platforms: Record<string, { qualitys?: string[] | null }>
}
export interface SourceState {
  items: SourceFixture[]
  activeSourceId: string
}

// 在任何写入前确认隔离harness，避免错误baseURL触及真实3780。
export function fixtureOrigin(baseURL: string | undefined, ports = ['3781', '3782', '3783']) {
  expect(baseURL, '必须复用已启动的隔离测试harness').toBeTruthy()
  const url = new URL(baseURL!)
  expect(url.protocol).toBe('http:')
  expect(url.hostname).toBe('127.0.0.1')
  expect(ports).toContain(url.port)
  return url.origin
}
export const prefixFor = (project: string) => (project.startsWith('fnos-') ? '/app/melora' : '')
export async function jsonOK<T = Record<string, unknown>>(
  response: Pick<APIResponse, 'ok' | 'url' | 'status' | 'json'>,
): Promise<T> {
  expect(response.ok(), `API ${response.url()} 返回${response.status()}`).toBe(true)
  return response.json() as Promise<T>
}
export async function mutationHeaders(
  request: APIRequestContext,
  prefix: string,
  baseURL?: string,
): Promise<Record<string, string>> {
  const origin = fixtureOrigin(baseURL, prefix ? ['3782'] : ['3781', '3783'])
  if (!prefix) return { Origin: origin }
  const response = await request.get(`${prefix}/api/v1/auth/session`, {
    headers: { 'X-Melora-Origin': origin },
  })
  const session = await jsonOK<{ csrfToken: string; authenticated: boolean }>(response)
  expect(session.authenticated).toBe(true)
  expect(session.csrfToken).toEqual(expect.any(String))
  expect(session.csrfToken.length).toBeGreaterThan(0)
  return { Origin: origin, 'X-Melora-CSRF': session.csrfToken }
}
export async function sources(request: APIRequestContext, prefix = '') {
  return jsonOK<SourceState>(await request.get(`${prefix}/api/v1/sources`))
}
export function sourceRow(page: Page, name: string) {
  return page
    .getByRole('table', { name: '已导入 LX 音源', exact: true })
    .getByRole('row')
    .filter({ has: page.getByText(name, { exact: true }) })
}
export async function importSource(page: Page, name: string, script: string, filename = 'source.js') {
  await page.getByRole('button', { name: '导入 LX 音源', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '导入 LX 音源', exact: true })
  await dialog
    .getByLabel('LX音源文件', { exact: true })
    .setInputFiles({ name: filename, mimeType: 'text/javascript', buffer: Buffer.from(script) })
  const pending = page.waitForResponse(
    (response) => response.url().endsWith('/api/v1/sources/import') && response.request().method() === 'POST',
  )
  await dialog.getByRole('button', { name: '导入并检查', exact: true }).click()
  const response = await pending
  const source = await jsonOK<SourceFixture>(response)
  expect(source.name).toBe(name)
  await expect(dialog).not.toBeVisible()
  await expect(sourceRow(page, name)).toBeVisible()
  return { source, response, row: sourceRow(page, name) }
}
export async function removeSourceUI(page: Page, name: string, id: string) {
  await sourceRow(page, name)
    .getByRole('button', { name: `删除音源 ${name}`, exact: true })
    .click()
  const dialog = page.getByRole('dialog', { name: '删除 LX 音源？', exact: true })
  const pending = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/api/v1/sources/${encodeURIComponent(id)}`) &&
      response.request().method() === 'DELETE',
  )
  await dialog.getByRole('button', { name: '确认删除', exact: true }).click()
  await jsonOK(await pending)
  await expect(sourceRow(page, name)).toHaveCount(0)
}
export async function freshSettings(
  browser: Browser,
  baseURL: string | undefined,
  prefix: string,
  check: (page: Page) => Promise<void>,
) {
  const context = await browser.newContext({ baseURL: fixtureOrigin(baseURL) })
  try {
    const fresh = await context.newPage()
    await fresh.goto(`${prefix}/settings`)
    await check(fresh)
  } finally {
    await context.close()
  }
}

import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  // CI 上偶发时序抖动不应直接判死：允许重试并放宽各层超时；本地保持严格以便暴露真实缺陷。
  retries: process.env.CI ? 2 : 0,
  timeout: process.env.CI ? 60_000 : 30_000,
  expect: { timeout: process.env.CI ? 10_000 : 5_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: { baseURL: 'http://127.0.0.1:3781', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [
    {
      name: 'live-desktop',
      testMatch: ['live.spec.ts', 'browse-live.spec.ts', 'platforms.spec.ts'],
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://127.0.0.1:3783',
        viewport: { width: 1440, height: 940 },
      },
    },
    {
      name: 'live-mobile',
      testMatch: ['live.spec.ts', 'browse-live.spec.ts', 'platforms.spec.ts'],
      use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium', baseURL: 'http://127.0.0.1:3783' },
    },
    {
      name: 'desktop',
      testMatch: [
        'app.spec.ts',
        'library.spec.ts',
        'ui-layout.spec.ts',
        'polish-v6.spec.ts',
        'storage-directories.spec.ts',
        'player-surface.spec.ts',
        'immersive-deck.spec.ts',
        'download-records.spec.ts',
        'data-identity.spec.ts',
      ],
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 940 } },
    },
    {
      name: 'mobile',
      testMatch: [
        'app.spec.ts',
        'library.spec.ts',
        'storage-directories.spec.ts',
        'player-surface.spec.ts',
        'immersive-deck.spec.ts',
        'download-records.spec.ts',
        'data-identity.spec.ts',
      ],
      use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' },
    },
    {
      name: 'fnos-desktop',
      testMatch: [
        'gateway.spec.ts',
        'gateway-writes.spec.ts',
        'library.spec.ts',
        'storage-directories.spec.ts',
        'player-surface.spec.ts',
        'immersive-deck.spec.ts',
        'download-records.spec.ts',
        'data-identity.spec.ts',
      ],
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://127.0.0.1:3782',
        viewport: { width: 1440, height: 940 },
      },
    },
    {
      name: 'fnos-mobile',
      testMatch: [
        'gateway.spec.ts',
        'gateway-writes.spec.ts',
        'library.spec.ts',
        'storage-directories.spec.ts',
        'player-surface.spec.ts',
        'immersive-deck.spec.ts',
        'download-records.spec.ts',
        'data-identity.spec.ts',
      ],
      use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium', baseURL: 'http://127.0.0.1:3782' },
    },
  ],
  webServer: {
    // 本命令的 cwd 是配置文件所在目录 apps/web，故此处只会构建前端产物；
    // Go 二进制由 scripts/e2e-server.mjs 自行按本机架构编译。
    command: 'npm run build && node ../../scripts/e2e-server.mjs',
    url: 'http://127.0.0.1:3782/__test/health',
    reuseExistingServer: false,
    // 冷缓存 runner 需在启动窗口内完成 tsc + vite + 压缩 + Go 编译，放宽到 5 分钟。
    timeout: 300_000,
  },
})

import { spawn, spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync } from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const version = spawnSync('node', [path.join(root, 'scripts/project-version.mjs')], { encoding: 'utf8' })
if (version.status !== 0) process.exit(version.status || 1)
const versionLdflag = `-X melora/internal/version.Value=${version.stdout.trim()}`
const tmp = path.join(root, '.superpowers/tmp')
mkdirSync(tmp, { recursive: true })
const dir = mkdtempSync(path.join(tmp, 'e2e-'))
const music = path.join(dir, 'music')
mkdirSync(music, { mode: 0o700 })
const secondMusic = path.join(dir, 'second-music')
mkdirSync(secondMusic, { mode: 0o700 })
mkdirSync(path.join(music, '测试专辑'), { mode: 0o700 })
mkdirSync(path.join(secondMusic, '另一目录'), { mode: 0o700 })
for (const project of ['desktop', 'mobile', 'fnos-desktop', 'fnos-mobile']) {
  mkdirSync(path.join(project.startsWith('fnos-') ? secondMusic : music, `回归-${project}`), { mode: 0o700 })
}
const binary = path.join(dir, 'melora')
const build = spawnSync('go', ['build', '-ldflags', versionLdflag, '-o', binary, './cmd/melora'], {
  cwd: path.join(root, 'apps/server'),
  stdio: 'inherit',
})
if (build.status !== 0) process.exit(build.status || 1)
const socket = path.join(dir, 'app.sock')
const common = {
  ...process.env,
  MELORA_ADDR: '127.0.0.1:3781',
  MELORA_AUTH_TOKEN: '',
  MELORA_DEMO_MODE: '1',
  MELORA_DEPLOY_MODE: 'nas',
  MELORA_ADMIN_USER: '',
  MELORA_ADMIN_PASSWORD: '',
  MELORA_TRUSTED_PROXIES: '',
  MELORA_DOWNLOAD_ROOT: music,
  WEB_DIR: path.join(root, 'apps/web/dist'),
}
const servers = [
  spawn(binary, [], {
    cwd: root,
    stdio: 'inherit',
    env: {
      ...common,
      MELORA_ADDR: '127.0.0.1:3783',
      MELORA_DEMO_MODE: '',
      MELORA_DATA_DIR: path.join(dir, 'live-data'),
      MELORA_SOCKET: '',
      MELORA_BASE_PATH: '',
      MELORA_GATEWAY_AUTH: '',
    },
  }),
  spawn(binary, [], {
    cwd: root,
    stdio: 'inherit',
    env: {
      ...common,
      MELORA_DATA_DIR: path.join(dir, 'data'),
      MELORA_SOCKET: '',
      MELORA_BASE_PATH: '',
      MELORA_GATEWAY_AUTH: '',
    },
  }),
  spawn(binary, [], {
    cwd: root,
    stdio: 'inherit',
    env: {
      ...common,
      MELORA_DATA_DIR: path.join(dir, 'gateway-data'),
      MELORA_DOWNLOAD_ROOT: '',
      TRIM_DATA_ACCESSIBLE_PATHS: `${music}:${secondMusic}`,
      MELORA_SOCKET: socket,
      MELORA_BASE_PATH: '/app/melora',
      MELORA_GATEWAY_AUTH: 'fnos-admin',
    },
  }),
]
function check(options) {
  return new Promise((resolve) => {
    const request = http.get(options, (response) => {
      response.resume()
      resolve(response.statusCode === 200)
    })
    request.setTimeout(1000, () => {
      request.destroy()
      resolve(false)
    })
    request.on('error', () => resolve(false))
  })
}
// 仅测试替身：模拟 fnOS 同源桌面与身份转发，不实现真正 NAS 登录系统，绝不打包部署。
const gateway = http.createServer(async (request, response) => {
  if (request.url === '/__test/health') {
    const states = await Promise.all([
      check('http://127.0.0.1:3781/health'),
      check('http://127.0.0.1:3783/health'),
      check({ socketPath: socket, path: '/app/melora/health' }),
    ])
    response.writeHead(states.every(Boolean) ? 200 : 503)
    response.end('test services readiness')
    return
  }
  if (request.url === '/__test/desktop') {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(
      '<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>fnOS gateway fixture</title></head><body style="margin:0;padding:20px;background:#e7ecf6;font-family:system-ui"><div style="margin-bottom:12px">飞牛桌面 · 测试替身</div><iframe title="乐屿 · Melora" src="/app/melora/" style="display:block;width:100%;height:calc(100dvh - 80px);border:0;border-radius:10px"></iframe></body></html>',
    )
    return
  }
  if (!request.url?.startsWith('/app/melora')) {
    response.writeHead(404)
    response.end()
    return
  }
  const headers = { ...request.headers }
  headers.host = 'melora.internal'
  delete headers['sec-fetch-site'] // HTTP私网访问可能没有Fetch-Metadata，不能依赖它放行。
  for (const key of Object.keys(headers)) if (key.startsWith('x-trim-')) delete headers[key]
  const identity = headers['x-fixture-user']
  delete headers['x-fixture-user']
  if (identity !== 'anonymous') {
    headers['x-trim-userid'] = identity === 'member' ? '1001' : '1000'
    headers['x-trim-username'] = identity === 'member' ? 'Test member' : 'Test administrator'
    headers['x-trim-isadmin'] = identity === 'member' ? 'false' : 'true'
  }
  const upstream = http.request(
    { socketPath: socket, path: request.url, method: request.method, headers },
    (incoming) => {
      response.writeHead(incoming.statusCode || 502, incoming.headers)
      incoming.pipe(response)
    },
  )
  upstream.on('error', () => {
    if (!response.headersSent) response.writeHead(502)
    response.end('Test gateway upstream not ready')
  })
  response.on('close', () => upstream.destroy())
  request.pipe(upstream)
})
gateway.listen(3782, '127.0.0.1')
let stopping = false
function stop(code = 0) {
  if (stopping) return
  stopping = true
  gateway.closeAllConnections()
  gateway.close()
  for (const server of servers) server.kill('SIGTERM')
  process.exitCode = code
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => stop())
for (const server of servers) {
  server.on('exit', (code) => stop(code || 0))
  server.on('error', () => stop(1))
}
gateway.on('error', () => stop(1))

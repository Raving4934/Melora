import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { mkdirSync } from 'node:fs'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const version = spawnSync('node', [path.join(root, 'scripts/project-version.mjs')], { encoding: 'utf8' })
if (version.status !== 0) process.exit(version.status || 1)
const versionLdflag = `-X melora/internal/version.Value=${version.stdout.trim()}`
const dir = path.join(root, '.superpowers/tmp')
mkdirSync(dir, { recursive: true })
// 先编译再启动可执行文件，避免退出 go run 后遗留无人管理的后端进程。
const binary = path.join(dir, 'melora-dev')
const build = spawnSync('go', ['build', '-ldflags', versionLdflag, '-o', binary, './cmd/melora'], {
  cwd: path.join(root, 'apps/server'),
  stdio: 'inherit',
})
if (build.error) {
  console.error(`无法执行 go：${build.error.message}。请安装 Go 1.25+ 并确保 go 在 PATH 中。`)
  process.exit(127)
}
if (build.status !== 0) {
  console.error('go build 失败，dev 终止。')
  process.exit(build.status || 1)
}
const grouped = process.platform !== 'win32'
const children = [
  spawn(binary, [], {
    cwd: root,
    detached: grouped,
    stdio: 'inherit',
    env: {
      ...process.env,
      WEB_DIR: path.join(root, 'apps/web/dist'),
      MELORA_DATA_DIR: process.env.MELORA_DATA_DIR || path.join(root, 'data'),
    },
  }),
  spawn('npm', ['run', 'dev:web'], { cwd: root, detached: grouped, stdio: 'inherit' }),
]
let stopping = false
function stop(code = 0) {
  if (stopping) return
  stopping = true
  for (const child of children) {
    if (!child.pid) continue
    try {
      if (grouped) process.kill(-child.pid, 'SIGTERM')
      else child.kill('SIGTERM')
    } catch {
      /* 子进程可能已经结束。 */
    }
  }
  process.exitCode = code
}
process.on('SIGINT', () => stop())
process.on('SIGTERM', () => stop())
for (const child of children) {
  child.on('error', (error) => {
    console.error(error.message)
    stop(1)
  })
  child.on('exit', (code) => stop(code || 0))
}

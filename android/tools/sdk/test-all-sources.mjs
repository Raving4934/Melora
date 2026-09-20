// 批量测试目录下全部脚本：node test-all-sources.mjs <脚本目录>
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const dir = process.argv[2]
const keyword = process.argv[3] || '晴天 周杰伦'
if (!dir) {
  console.error('usage: node test-all-sources.mjs <script-dir> [keyword]')
  process.exit(1)
}

const files = fs.readdirSync(dir).filter((name) => name.endsWith('.js')).sort()
for (const name of files) {
  console.log(`\n===== ${name} =====`)
  const result = spawnSync(
    process.execPath,
    [path.join(here, 'test-one-source.mjs'), path.join(dir, name), keyword],
    { encoding: 'utf8', timeout: 600_000 },
  )
  if (result.stdout) process.stdout.write(result.stdout)
  if (result.error) console.log('RUN_ERROR', result.error.message)
  if (result.status !== 0 && result.stderr) console.log(result.stderr.slice(0, 400))
}

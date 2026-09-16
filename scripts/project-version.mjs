import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const rootPackage = new URL('../package.json', import.meta.url)
const { version } = JSON.parse(readFileSync(fileURLToPath(rootPackage), 'utf8'))
if (typeof version !== 'string' || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(version)) {
  throw new Error('package.json version 不是有效的语义化版本')
}
process.stdout.write(version)

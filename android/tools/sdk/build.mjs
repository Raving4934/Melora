import { build } from 'esbuild'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs'
import os from 'node:os'

const root = path.dirname(fileURLToPath(import.meta.url))
const outfile = path.resolve(root, '../../app/src/main/assets/sdk/music-sdk.js')
const licenseManifestFile = path.resolve(root, '../../app/src/main/assets/licenses/manifest.json')
const checkOnly = process.argv.includes('--check')
const buildTarget = checkOnly ? path.join(os.tmpdir(), `melora-music-sdk-${process.pid}.js`) : outfile

const aliasPlugin = {
  name: 'melora-alias',
  setup(builder) {
    builder.onResolve({ filter: /^@\/utils\/request$/ }, () => ({ path: path.join(root, 'src/request.js') }))
    builder.onResolve({ filter: /^@\/utils\/nativeModules\/crypto$/ }, () => ({ path: path.join(root, 'compat/native-crypto.js') }))
  },
}

const result = await build({
  absWorkingDir: root,
  entryPoints: [path.join(root, 'entry.js')],
  bundle: true,
  format: 'iife',
  target: 'es2020',
  platform: 'browser',
  outfile: buildTarget,
  charset: 'utf8',
  // The APK ships the full license/notice manifest separately; bundle comments are not the source of truth.
  legalComments: 'none',
  minify: false,
  alias: {
    'react-native-quick-md5': path.join(root, 'compat/quick-md5.js'),
    'react-native-quick-base64': path.join(root, 'compat/quick-base64.js'),
  },
  plugins: [aliasPlugin],
  metafile: true,
})

const packageNameFromInput = input => {
  const normalized = input.split(path.sep).join('/')
  const marker = '/node_modules/'
  const markerIndex = normalized.indexOf(marker)
  const relative = markerIndex >= 0
    ? normalized.slice(markerIndex + marker.length)
    : normalized.startsWith('node_modules/')
      ? normalized.slice('node_modules/'.length)
      : null
  if (!relative) return null
  const parts = relative.split('/')
  return parts[0]?.startsWith('@') ? `${parts[0]}/${parts[1]}` : parts[0]
}

const matchesCoverage = (value, patterns) => patterns.some(pattern => {
  if (pattern.endsWith('/*')) return value.startsWith(pattern.slice(0, -1))
  return value === pattern
})

const validateLicenseManifest = (manifest, inputs) => {
  const entries = Array.isArray(manifest.entries) ? manifest.entries : []
  const licenseTexts = manifest.licenseTexts && typeof manifest.licenseTexts === 'object'
    ? manifest.licenseTexts
    : {}
  if (!entries.length) throw new Error('licenses/manifest.json 没有许可条目')
  for (const entry of entries) {
    if (!entry.id || !entry.name || !entry.description) throw new Error(`许可条目字段不完整: ${entry.id || '<unknown>'}`)
    if (!Array.isArray(entry.licenseIds) || !entry.licenseIds.length) throw new Error(`许可条目没有 licenseIds: ${entry.id}`)
    for (const licenseId of entry.licenseIds) {
      if (!licenseTexts[licenseId]?.text) throw new Error(`许可原文缺失: ${entry.id} -> ${licenseId}`)
    }
  }

  const bundledPackages = new Set(inputs.map(packageNameFromInput).filter(Boolean))
  const packagePatterns = entries.flatMap(entry => entry.packages || [])
  const missingPackages = [...bundledPackages].filter(packageName => !matchesCoverage(packageName, packagePatterns))
  if (missingPackages.length) {
    throw new Error(`JS bundle 有未归档的依赖: ${missingPackages.sort().join(', ')}`)
  }

  const musicSdkEntry = entries.find(entry => entry.id === 'music-sdk')
  if (!musicSdkEntry?.assetPaths?.includes('sdk/music-sdk.js')) {
    throw new Error('music-sdk 条目未覆盖 sdk/music-sdk.js')
  }
  const assetPath = path.resolve(root, '../../app/src/main/assets/sdk/music-sdk.js')
  if (!fs.existsSync(assetPath)) throw new Error(`JS bundle asset 不存在: ${assetPath}`)
}

const manifest = JSON.parse(fs.readFileSync(licenseManifestFile, 'utf8'))
validateLicenseManifest(manifest, Object.keys(result.metafile.inputs))

const resolveMetafileInput = input => path.resolve(root, input)

const sourceFiles = []
const collectSourceFiles = directory => {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name)
    if (entry.isDirectory()) collectSourceFiles(target)
    else if (entry.name.endsWith('.js') || entry.name.endsWith('.ts')) sourceFiles.push(path.resolve(target))
  }
}
collectSourceFiles(path.join(root, 'src'))
const bundledInputs = new Set(Object.keys(result.metafile.inputs).map(resolveMetafileInput))
const unreachable = sourceFiles.filter(file => !bundledInputs.has(file)).map(file => path.relative(root, file)).sort()
if (unreachable.length) {
  if (checkOnly) fs.unlinkSync(buildTarget)
  throw new Error(`SDK 源码存在未进入生产 bundle 的旧链路:
${unreachable.join('\n')}`)
}

const size = fs.statSync(buildTarget).size
if (checkOnly) {
  const matches = fs.existsSync(outfile) && fs.readFileSync(outfile).equals(fs.readFileSync(buildTarget))
  fs.unlinkSync(buildTarget)
  if (!matches) throw new Error('music-sdk.js 与源码构建结果不一致，请运行 npm run build')
  console.log(`music-sdk bundle is reproducible (${(size / 1024).toFixed(1)} KiB)`)
} else {
  console.log(`music-sdk bundle: ${outfile} (${(size / 1024).toFixed(1)} KiB)`)
}

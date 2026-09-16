import { brotliCompress, constants, gzip } from 'node:zlib'
import { promisify } from 'node:util'
import { readdir, readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const gzipAsync = promisify(gzip)
const brotliAsync = promisify(brotliCompress)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../apps/web/dist')
const compressible = new Set(['.js', '.css', '.svg', '.json', '.webmanifest'])
const minimumBytes = 1024

async function filesIn(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(
    entries.map((entry) => {
      const file = path.join(directory, entry.name)
      return entry.isDirectory() ? filesIn(file) : entry.isFile() ? [file] : []
    }),
  )
  return nested.flat()
}

const files = await filesIn(root)
await Promise.all(
  files
    .filter((file) => file.endsWith('.gz') || file.endsWith('.br'))
    .map((file) => rm(file, { force: true })),
)

await Promise.all(
  files
    .filter(
      (file) =>
        !file.endsWith('.gz') && !file.endsWith('.br') && compressible.has(path.extname(file).toLowerCase()),
    )
    .map(async (file) => {
      const input = await readFile(file)
      if (input.byteLength < minimumBytes) return
      const [gzipped, brotli] = await Promise.all([
        gzipAsync(input, { level: 9 }),
        brotliAsync(input, {
          params: {
            [constants.BROTLI_PARAM_MODE]: constants.BROTLI_MODE_TEXT,
            [constants.BROTLI_PARAM_QUALITY]: 9,
          },
        }),
      ])
      if (gzipped.byteLength < input.byteLength) await writeFile(`${file}.gz`, gzipped)
      if (brotli.byteLength < input.byteLength) await writeFile(`${file}.br`, brotli)
    }),
)

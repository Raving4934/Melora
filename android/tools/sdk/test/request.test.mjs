import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

globalThis.__lxNative = {
  http(payloadJson) {
    const payload = JSON.parse(payloadJson)
    return JSON.stringify({
      statusCode: 200,
      statusMessage: 'OK',
      headers: { 'content-type': 'application/json' },
      finalUrl: 'https://example.test/final',
      raw: Buffer.from(JSON.stringify({ method: payload.method, url: payload.url })).toString('base64'),
    })
  },
}

const { httpFetch } = await import('../src/request.js')

const listJavaScript = async directory => {
  const files = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name)
    if (entry.isDirectory()) files.push(...await listJavaScript(target))
    else if (entry.name.endsWith('.js')) files.push(target)
  }
  return files
}

test('httpFetch exposes one Promise-based request contract', async () => {
  const request = httpFetch('https://example.test/data')
  assert.equal(typeof request.then, 'function')
  assert.equal('promise' in request, false)
  assert.equal('cancelHttp' in request, false)
  const response = await request
  assert.equal(response.statusCode, 200)
  assert.deepEqual(response.body, { method: 'GET', url: 'https://example.test/data' })
  assert.equal(response.url, 'https://example.test/final')
})


test('host failures reject without a fabricated network code', async () => {
  const original = globalThis.__lxNative.http
  globalThis.__lxNative.http = () => JSON.stringify({ error: 'invalid request payload' })
  try {
    await assert.rejects(httpFetch('invalid://request'), error => {
      assert.equal(error.message, 'invalid request payload')
      assert.equal(error.code, undefined)
      return true
    })
  } finally {
    globalThis.__lxNative.http = original
  }
})

test('SDK source contains no retired request-object facade', async () => {
  const files = [path.join(root, 'entry.js'), ...await listJavaScript(path.join(root, 'src'))]
  const retired = /\.promise\b|\bcancelHttp\b|\bisCancelled\b|\bpromise\s*:/
  const offenders = []
  for (const file of files) {
    if (retired.test(await readFile(file, 'utf8'))) offenders.push(path.relative(root, file))
  }
  assert.deepEqual(offenders, [])
})

test('request promises stay local and declared', async () => {
  const files = await listJavaScript(path.join(root, 'src'))
  const offenders = []
  for (const file of files) {
    const source = await readFile(file, 'utf8')
    const lines = source.split('\n')
    const constNames = new Set()
    for (const line of lines) {
      if (line.trimStart().startsWith('//')) continue
      const declaration = line.match(/\bconst\s+((?:_?request)[A-Za-z0-9_]*)\s*=/)
      if (declaration) constNames.add(declaration[1])
    }
    for (const [index, line] of lines.entries()) {
      if (line.trimStart().startsWith('//')) continue
      const assignment = line.match(/^\s*((?:_?request)[A-Za-z0-9_]*)\s*=\s*(.*)$/)
      if (!assignment) continue
      const [, name, expression] = assignment
      if (!expression.startsWith(`${name}.then`) || constNames.has(name)) {
        offenders.push(`${path.relative(root, file)}:${index + 1}`)
      }
    }
  }
  assert.deepEqual(offenders, [])
})

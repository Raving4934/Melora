const CACHE_NAME = 'melora-shell-v0.1.1'
const CACHE_PREFIX = 'melora-shell-v'
const scopeURL = new URL(self.registration.scope)
const shellURL = new URL('./', scopeURL).href
const coreAssets = [
  'manifest.webmanifest',
  'favicon.svg',
  'icons/melora-192.png',
  'icons/melora-512.png',
  'icons/melora-maskable-512.png',
  'icons/apple-touch-icon.png',
].map((path) => new URL(path, scopeURL).href)

async function discoverShellAssets() {
  const response = await fetch(shellURL, { cache: 'reload' })
  if (!response.ok) throw new Error('Melora shell unavailable')
  const html = await response.clone().text()
  const assets = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)]
    .map((match) => new URL(match[1], shellURL))
    .filter(
      (url) =>
        url.origin === self.location.origin &&
        url.pathname.startsWith(scopeURL.pathname) &&
        !url.pathname.includes('/api/'),
    )
    .map((url) => url.href)
  return { response, assets: [...new Set([...coreAssets, ...assets])] }
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE_NAME)
      const { response, assets } = await discoverShellAssets()
      await cache.put(shellURL, response)
      await cache.addAll(assets)
      await self.skipWaiting()
    })(),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys()
      await Promise.all(
        names.filter((name) => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME).map((name) => caches.delete(name)),
      )
      await self.clients.claim()
    })(),
  )
})

async function networkFirst(request) {
  const cache = await caches.open(CACHE_NAME)
  try {
    const response = await fetch(request)
    if (response.ok && response.status === 200) await cache.put(request, response.clone())
    return response
  } catch {
    return (await cache.match(request)) || (await cache.match(shellURL)) || Response.error()
  }
}

self.addEventListener('fetch', (event) => {
  const { request } = event
  if (request.method !== 'GET' || request.headers.has('range')) return
  const url = new URL(request.url)
  if (url.origin !== self.location.origin || !url.pathname.startsWith(scopeURL.pathname)) return
  if (url.pathname.includes('/api/') || url.pathname.endsWith('/health')) return
  if (request.destination === 'audio' || request.destination === 'video') return

  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request))
    return
  }

  const cacheable = ['script', 'style', 'font', 'image', 'manifest'].includes(request.destination)
  if (!cacheable) return
  const refresh = fetch(request).then(async (response) => {
    if (response.ok && response.status === 200) {
      const cache = await caches.open(CACHE_NAME)
      await cache.put(request, response.clone())
    }
    return response
  })
  event.respondWith(caches.match(request).then((cached) => cached || refresh))
  event.waitUntil(refresh.catch(() => undefined))
})

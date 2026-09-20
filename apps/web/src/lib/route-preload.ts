export const loadBrowseRoute = () => import('../pages/Browse')
export const loadSearchRoute = () => import('../pages/Search')
export const loadAudiobooksRoute = () => import('../pages/Audiobooks')
export const loadLibraryRoute = () => import('../pages/Library')
export const loadUserPlaylistRoute = () => import('../pages/UserPlaylist')
export const loadDownloadsRoute = () => import('../pages/Downloads')
export const loadSettingsRoute = () => import('../pages/Settings')

type RouteLoader = () => Promise<unknown>

function loaderForPath(pathname: string): RouteLoader | undefined {
  if (pathname.startsWith('/library/playlists/')) return loadUserPlaylistRoute
  if (pathname === '/library') return loadLibraryRoute
  if (pathname === '/search') return loadSearchRoute
  if (pathname === '/audiobooks' || pathname.startsWith('/audiobooks/')) return loadAudiobooksRoute
  if (pathname === '/downloads') return loadDownloadsRoute
  if (pathname === '/settings') return loadSettingsRoute
  if (
    pathname === '/' ||
    pathname.startsWith('/charts/') ||
    pathname === '/discover' ||
    pathname.startsWith('/discover/') ||
    pathname === '/playlists' ||
    pathname.startsWith('/playlists/')
  )
    return loadBrowseRoute
  return undefined
}

export function preloadRoute(pathname: string) {
  void loaderForPath(pathname)?.().catch(() => undefined)
}

import { BrandMark } from './components/BrandMark'
import { ClientDataBoundary } from './components/ClientDataBoundary'
import { Component, lazy, Suspense, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { AlertCircle, Eye, EyeOff, LoaderCircle, LockKeyhole, UserRound } from 'lucide-react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { DocumentSurface, isImmersivePath } from './lib/document-surface'
import { readRememberedUsername, writeRememberedUsername } from './lib/login-prefs'
import { AppShell } from './components/AppShell'
import { NowPlaying, PlayerDrawer } from './components/Player'
import { EmptyState, LoadingSurface, QueryState, Toast } from './components/UI'
import { SessionProvider, useAPI, send, invalidate, queryClient } from './lib/api'
import type { Session } from './lib/types'
import { errorMessage } from './lib/format'
import {
  loadAudiobooksRoute,
  loadBrowseRoute,
  loadDownloadsRoute,
  loadLibraryRoute,
  loadSearchRoute,
  loadSettingsRoute,
  loadUserPlaylistRoute,
} from './lib/route-preload'
import { player } from './stores/player'
import { useUI } from './stores/ui'
import './login.css'
const Charts = lazy(() => loadBrowseRoute().then((module) => ({ default: module.ChartsPage })))
const ChartDetail = lazy(() => loadBrowseRoute().then((module) => ({ default: module.ChartDetailPage })))
const Discover = lazy(() => loadBrowseRoute().then((module) => ({ default: module.DiscoverPage })))
const DailyRecommendations = lazy(() =>
  loadBrowseRoute().then((module) => ({ default: module.DailyRecommendationPage })),
)
const NewTracks = lazy(() => loadBrowseRoute().then((module) => ({ default: module.NewTracksPage })))
const Playlists = lazy(() => loadBrowseRoute().then((module) => ({ default: module.PlaylistsPage })))
const PlaylistDetail = lazy(() =>
  loadBrowseRoute().then((module) => ({ default: module.PlaylistDetailPage })),
)
const Search = lazy(() => loadSearchRoute().then((module) => ({ default: module.SearchPage })))
const AudioBooks = lazy(() => loadAudiobooksRoute().then((module) => ({ default: module.AudioBooksPage })))
const BookRank = lazy(() => loadAudiobooksRoute().then((module) => ({ default: module.BookRankPage })))
const AudioBookDetail = lazy(() =>
  loadAudiobooksRoute().then((module) => ({ default: module.AudiobookAlbumPage })),
)
const Library = lazy(() => loadLibraryRoute().then((module) => ({ default: module.LibraryPage })))
const UserPlaylistDetail = lazy(() =>
  loadUserPlaylistRoute().then((module) => ({ default: module.UserPlaylistPage })),
)
const Downloads = lazy(() => loadDownloadsRoute().then((module) => ({ default: module.DownloadsPage })))
const SettingsPage = lazy(() => loadSettingsRoute().then((module) => ({ default: module.SettingsPage })))
const DownloadDialog = lazy(() =>
  import('./components/DownloadDialog').then((m) => ({ default: m.DownloadDialog })),
)
const AddToPlaylistDialog = lazy(() =>
  import('./components/UserPlaylists').then((m) => ({ default: m.AddToPlaylistDialog })),
)
export class ErrorBoundary extends Component<{ children: ReactNode; resetKey?: string }, { error: boolean }> {
  state = { error: false }
  static getDerivedStateFromError() {
    return { error: true }
  }
  componentDidCatch(error: unknown, info: { componentStack?: string | null }) {
    console.error('页面渲染失败', error, info.componentStack)
  }
  componentDidUpdate(previous: Readonly<{ children: ReactNode; resetKey?: string }>) {
    if (this.state.error && previous.resetKey !== this.props.resetKey) this.setState({ error: false })
  }
  render() {
    return this.state.error ? (
      <>
        <DocumentSurface immersive={false} />
        <EmptyState
          title="页面遇到了一点问题"
          description="请刷新页面重试。已保存的收藏和下载不受影响。"
          action={
            <button className="button primary" onClick={() => window.location.reload()}>
              刷新页面
            </button>
          }
        />
      </>
    ) : (
      this.props.children
    )
  }
}
function AppBootShell({ immersive, pathname }: { immersive: boolean; pathname: string }) {
  if (immersive)
    return (
      <main className="app-boot-immersive" aria-busy="true">
        <LoadingSurface label="正在打开音乐空间…" className="route-pending" />
      </main>
    )
  const activePath =
    pathname === '/'
      ? '/'
      : ['/discover', '/playlists', '/library', '/audiobooks'].find((path) => pathname.startsWith(path))
  return (
    <div className="app-shell practical-shell app-boot-shell" aria-busy="true">
      <header className="app-header app-boot-header" aria-hidden="true">
        <div className="brand-group">
          <span className="brand">
            <span className="brand-symbol">
              <BrandMark size={22} strokeWidth={2.3} />
            </span>
            <span>
              乐屿<span className="brand-en">Melora</span>
            </span>
          </span>
        </div>
        <nav className="main-nav app-boot-nav">
          {[
            ['/', '排行榜'],
            ['/discover', '发现'],
            ['/playlists', '歌单'],
            ['/audiobooks', '听书'],
            ['/library', '我的音乐'],
          ].map(([path, name]) => (
            <span className={activePath === path ? 'is-active' : undefined} key={path}>
              {name}
            </span>
          ))}
        </nav>
        <div className="header-tools app-boot-tools">
          <span className="app-boot-search" />
          <span className="app-boot-action" />
        </div>
      </header>
      <main className="main-content">
        <div className="content-inner">
          <LoadingSurface label="正在打开音乐空间…" className="page-pending" />
        </div>
      </main>
      <footer className="mini-player player-mini app-boot-player" aria-hidden="true" />
    </div>
  )
}

// 登录页只在服务端明确确认需要独立登录后挂载；鉴权未知态始终使用应用同构骨架。
function LoginFrame({ children }: { children: ReactNode }) {
  return (
    <main className="login-page">
      <section className="login-brand" aria-hidden="true">
        <span className="login-brand-mark">
          <BrandMark size={30} strokeWidth={2.2} />
        </span>
        <div className="login-brand-word">
          <strong>乐屿</strong>
          <span>MELORA</span>
        </div>
        <p>轻量、优雅的自托管音乐空间</p>
      </section>
      <section className="login-panel">{children}</section>
    </main>
  )
}

export default function App() {
  const session = useAPI<Session>('/auth/session')
  const location = useLocation()
  const loginRequired = !!session.data?.required && !session.data.authenticated
  const gatewayDenied = session.data?.authMode === 'fnos' && !session.data.authenticated
  const loginMethod = session.data?.loginMethod === 'password' ? 'password' : 'token'
  const cloud = session.data?.deployMode === 'cloud'
  const immersiveRoute = isImmersivePath(location.pathname)
  useEffect(() => {
    if (gatewayDenied) {
      void queryClient.cancelQueries({ predicate: (query) => query.queryKey[0] !== '/auth/session' })
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== '/auth/session' })
      player.stop()
      useUI.setState({ drawer: null, downloadTrack: null, playlistTrack: null, toast: null })
    }
  }, [gatewayDenied])
  return (
    <ErrorBoundary resetKey={location.key}>
      <DocumentSurface immersive={immersiveRoute && !gatewayDenied && !loginRequired && !session.error} />
      {session.isPending ? (
        <AppBootShell immersive={immersiveRoute} pathname={location.pathname} />
      ) : (
        <QueryState pending={false} error={session.error} retry={session.refetch}>
          {gatewayDenied ? (
            <EmptyState
              title="需要飞牛管理员身份"
              description={session.data?.error || '请回到飞牛桌面，使用管理员账号登录后重新打开乐屿。'}
              action={
                <a className="button primary" href="/" target="_top">
                  返回飞牛桌面
                </a>
              }
            />
          ) : session.data?.required && !session.data.authenticated ? (
            <Login method={loginMethod} />
          ) : (
            <SessionProvider session={session.data}>
              <ClientDataBoundary session={session.data}>
                <Suspense fallback={<AppBootShell immersive={immersiveRoute} pathname={location.pathname} />}>
                  <Routes>
                    <Route element={<AppShell />}>
                      <Route index element={<Charts />} />
                      <Route path="charts/:id" element={<ChartDetail />} />
                      <Route path="discover" element={<Discover />} />
                      <Route path="discover/daily" element={<DailyRecommendations />} />
                      <Route path="discover/new-tracks" element={<NewTracks />} />
                      <Route path="playlists" element={<Playlists />} />
                      <Route path="playlists/:id" element={<PlaylistDetail />} />
                      <Route path="audiobooks" element={<AudioBooks />} />
                      <Route path="audiobooks/ranks/:tabId" element={<BookRank />} />
                      <Route path="audiobooks/albums/:id" element={<AudioBookDetail />} />
                      <Route path="search" element={<Search />} />
                      <Route path="library" element={<Library />} />
                      <Route path="library/playlists/:id" element={<UserPlaylistDetail />} />
                      {!cloud && <Route path="downloads" element={<Downloads />} />}
                      <Route path="settings" element={<SettingsPage />} />
                      <Route
                        path="*"
                        element={
                          <EmptyState
                            title="这个音乐角落还不存在"
                            description="页面地址可能已经改变，回到首页继续听歌吧。"
                            action={
                              <Link className="button primary" to="/">
                                回到首页
                              </Link>
                            }
                          />
                        }
                      />
                    </Route>
                    <Route path="now-playing" element={<NowPlaying />} />
                  </Routes>
                </Suspense>
                <PlayerDrawer />
                <Suspense fallback={null}>
                  {!cloud && <DownloadDialog />}
                  <AddToPlaylistDialog />
                </Suspense>
              </ClientDataBoundary>
            </SessionProvider>
          )}
        </QueryState>
      )}
      <Toast />
    </ErrorBoundary>
  )
}
function Login({ method }: { method: 'token' | 'password' }) {
  const [token, setToken] = useState('')
  const [username, setUsername] = useState(() => (method === 'password' ? readRememberedUsername() : ''))
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [remember, setRemember] = useState(() => method === 'password' && !!readRememberedUsername())
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setPending(true)
    setError('')
    try {
      await send('/auth/session', 'POST', method === 'password' ? { username, password } : { token })
      if (method === 'password') writeRememberedUsername(remember ? username : '')
      setToken('')
      setPassword('')
      await invalidate('/auth/session')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setPending(false)
    }
  }
  return (
    <LoginFrame>
      <form
        className="login-form"
        onSubmit={(event) => {
          void submit(event)
        }}
      >
        <header className="login-heading">
          <h1>欢迎回来</h1>
          <p>{method === 'password' ? '使用管理员账号登录你的音乐空间。' : '输入管理员设置的访问令牌。'}</p>
        </header>
        {method === 'password' ? (
          <>
            <label htmlFor="admin-username">用户名</label>
            <div className="login-input">
              <UserRound size={17} />
              <input
                autoFocus
                id="admin-username"
                type="text"
                autoComplete="username"
                required
                aria-invalid={error ? true : undefined}
                value={username}
                onChange={(event) => setUsername(event.target.value)}
              />
            </div>
            <label htmlFor="admin-password">密码</label>
            <div className="login-input">
              <LockKeyhole size={17} />
              <input
                id="admin-password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                required
                aria-invalid={error ? true : undefined}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
              <button
                type="button"
                className="login-input-toggle"
                aria-label={showPassword ? '隐藏密码' : '显示密码'}
                aria-pressed={showPassword}
                onClick={() => setShowPassword((value) => !value)}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            <label className="login-remember">
              <input
                type="checkbox"
                checked={remember}
                onChange={(event) => setRemember(event.target.checked)}
              />
              <span>记住用户名</span>
            </label>
          </>
        ) : (
          <>
            <label htmlFor="access-token">访问令牌</label>
            <div className="login-input">
              <LockKeyhole size={17} />
              <input
                autoFocus
                id="access-token"
                type="password"
                autoComplete="current-password"
                required
                aria-invalid={error ? true : undefined}
                value={token}
                onChange={(event) => setToken(event.target.value)}
              />
            </div>
          </>
        )}
        {error && (
          <p className="inline-error login-error" role="alert">
            <AlertCircle size={14} />
            <span>{error}</span>
          </p>
        )}
        <button className="button primary login-submit" disabled={pending} type="submit">
          {pending && <LoaderCircle className="spin" size={16} />}进入乐屿
        </button>
        <small>
          {method === 'password'
            ? '凭据只用于本机验证，不存入浏览器本地存储。'
            : '令牌只用于本机验证，不存入浏览器本地存储。'}
        </small>
      </form>
    </LoginFrame>
  )
}

import { BrandMark } from './BrandMark'
import { APP_VERSION } from '../lib/version'
import { Suspense, useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react'
import { Download, LogOut, MoreHorizontal, Search, Settings, X } from 'lucide-react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { LoadingSurface, Modal, ProviderNamesProvider } from './UI'
import { MiniPlayer } from './Player'
import { ScrollEdgeControls } from './ScrollEdgeControls'
import { send, queryClient, useAPI } from '../lib/api'
import type { Session } from '../lib/types'
import { notify } from '../stores/ui'
import { player } from '../stores/player'
import { errorMessage } from '../lib/format'
import { appURL } from '../lib/base'
import { preloadRoute } from '../lib/route-preload'
import '../practical-ui.css'
import './SearchDialog.css'
export function AppShell() {
  const [menu, setMenu] = useState(false)
  const [search, setSearch] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const mainRef = useRef<HTMLElement>(null)
  const mobileSearchRef = useRef<HTMLInputElement>(null)
  const searchTriggerRef = useRef<HTMLButtonElement>(null)
  const searchWasOpen = useRef(false)
  const nav = useNavigate()
  const location = useLocation()
  const menuRef = useRef<HTMLDivElement>(null)
  const moreRef = useRef<HTMLButtonElement>(null)
  const session = useAPI<Session>('/auth/session')
  const cloud = session.data?.deployMode === 'cloud'
  const queryTerm = new URLSearchParams(location.search).get('q') || ''
  useLayoutEffect(() => {
    // 在浏览器绘制搜索结果前同步地址栏关键词，避免延迟 effect 覆盖用户刚输入的新内容。
    if (location.pathname === '/search') setSearch(queryTerm)
  }, [location.pathname, queryTerm])
  useEffect(() => {
    if (searchOpen) mobileSearchRef.current?.focus()
    else if (searchWasOpen.current) searchTriggerRef.current?.focus({ preventScroll: true })
    searchWasOpen.current = searchOpen
  }, [searchOpen])
  useLayoutEffect(() => {
    mainRef.current?.scrollTo(0, 0)
  }, [location.pathname])
  useEffect(() => {
    setMenu(false)
  }, [location.pathname])
  useEffect(() => {
    const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection
    if (connection?.saveData) return
    const warmPrimaryRoutes = () => {
      preloadRoute('/')
      preloadRoute('/library')
      preloadRoute('/search')
    }
    if ('requestIdleCallback' in window) {
      const idle = window.requestIdleCallback(warmPrimaryRoutes, { timeout: 1800 })
      return () => window.cancelIdleCallback(idle)
    }
    const timer = globalThis.setTimeout(warmPrimaryRoutes, 900)
    return () => globalThis.clearTimeout(timer)
  }, [])
  useEffect(() => {
    if (!menu) return
    const focusMenuItem = (position: 'first' | 'last' = 'first') => {
      const items = menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]')
      const target = position === 'last' ? items?.item((items?.length || 1) - 1) : items?.item(0)
      target?.focus({ preventScroll: true })
    }
    const frame = requestAnimationFrame(() => focusMenuItem())
    const click = (event: PointerEvent) => {
      if (
        !menuRef.current?.contains(event.target as Node) &&
        !moreRef.current?.contains(event.target as Node)
      )
        setMenu(false)
    }
    const keyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenu(false)
        moreRef.current?.focus()
        return
      }
      if (!menuRef.current?.contains(document.activeElement)) return
      const items = [...menuRef.current.querySelectorAll<HTMLElement>('[role="menuitem"]')]
      if (!items.length) return
      const current = items.indexOf(document.activeElement as HTMLElement)
      const destination =
        event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? items.length - 1
            : event.key === 'ArrowDown'
              ? (current + 1) % items.length
              : event.key === 'ArrowUp'
                ? (current - 1 + items.length) % items.length
                : -1
      if (destination >= 0) {
        event.preventDefault()
        items[destination]?.focus()
      }
    }
    document.addEventListener('pointerdown', click)
    document.addEventListener('keydown', keyboard)
    return () => {
      cancelAnimationFrame(frame)
      document.removeEventListener('pointerdown', click)
      document.removeEventListener('keydown', keyboard)
    }
  }, [menu])
  const searchSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    // 提交时直接读取当前表单控件，避免输入事件与 Enter 同一帧发生时使用旧 React state。
    const field = event.currentTarget.elements.namedItem('q')
    const value = field instanceof HTMLInputElement ? field.value : search
    const query = value.trim()
    setSearch(value)
    const params = new URLSearchParams(location.pathname === '/search' ? location.search : '')
    if (query) params.set('q', query)
    else params.delete('q')
    params.delete('page')
    setSearchOpen(false)
    nav(`/search${params.size ? `?${params}` : ''}`)
  }
  return (
    <div className="app-shell practical-shell">
      <a className="skip-link" href={appURL(location.pathname + location.search) + '#main-content'}>
        跳到主要内容
      </a>
      <header className="app-header">
        <div className="brand-group">
          <Link
            to="/"
            className="brand"
            aria-label="乐屿 Melora 排行榜"
            onPointerEnter={() => preloadRoute('/')}
            onFocus={() => preloadRoute('/')}
            onPointerDown={() => preloadRoute('/')}
          >
            <span className="brand-symbol">
              <BrandMark size={22} />
            </span>
            <span>
              乐屿<span className="brand-en">Melora</span>
            </span>
          </Link>
        </div>
        <nav className="main-nav" aria-label="主导航">
          {[
            ['/', '排行榜'],
            ['/discover', '发现'],
            ['/playlists', '歌单'],
            ['/audiobooks', '听书'],
            ['/library', '我的音乐'],
          ].map(([path, name]) => (
            <NavLink
              key={path}
              to={path!}
              end={path === '/'}
              onPointerEnter={() => preloadRoute(path!)}
              onFocus={() => preloadRoute(path!)}
              onPointerDown={() => preloadRoute(path!)}
            >
              {name}
            </NavLink>
          ))}
        </nav>
        <div className="header-tools">
          <form className="header-search" role="search" aria-label="全局搜索" onSubmit={searchSubmit}>
            <Search size={15} />
            <input
              aria-label="搜索关键词"
              name="q"
              maxLength={200}
              placeholder="搜索歌曲、歌手、歌单"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              onFocus={() => preloadRoute('/search')}
            />
            <button type="submit" aria-label="开始搜索">
              ↵
            </button>
          </form>
          <button
            ref={searchTriggerRef}
            type="button"
            aria-label="打开搜索"
            title="打开搜索"
            className="icon-button mobile-search"
            onPointerEnter={() => preloadRoute('/search')}
            onFocus={() => preloadRoute('/search')}
            onPointerDown={() => preloadRoute('/search')}
            onClick={() => {
              setMenu(false)
              setSearchOpen(true)
            }}
          >
            <Search size={19} />
          </button>
          <button
            ref={moreRef}
            type="button"
            className="icon-button more-menu-button"
            aria-label="更多"
            aria-haspopup="menu"
            aria-expanded={menu}
            aria-controls="more-menu"
            onPointerEnter={() => {
              preloadRoute('/downloads')
              preloadRoute('/settings')
            }}
            onFocus={() => {
              preloadRoute('/downloads')
              preloadRoute('/settings')
            }}
            onPointerDown={() => {
              preloadRoute('/downloads')
              preloadRoute('/settings')
            }}
            onClick={() => setMenu(!menu)}
          >
            <MoreHorizontal size={22} />
          </button>
        </div>
        {menu && (
          <div ref={menuRef} id="more-menu" className="user-menu" role="menu" aria-label="更多">
            <div className="user-menu-heading" role="presentation">
              <strong>更多</strong>
            </div>
            {!cloud && (
              <Link
                role="menuitem"
                to="/downloads"
                onPointerEnter={() => preloadRoute('/downloads')}
                onFocus={() => preloadRoute('/downloads')}
                onPointerDown={() => preloadRoute('/downloads')}
              >
                <Download size={16} />
                下载任务
              </Link>
            )}
            <Link
              role="menuitem"
              to="/settings"
              onPointerEnter={() => preloadRoute('/settings')}
              onFocus={() => preloadRoute('/settings')}
              onPointerDown={() => preloadRoute('/settings')}
            >
              <Settings size={16} />
              设置
            </Link>
            {session.data?.required && (
              <button
                role="menuitem"
                onClick={async () => {
                  try {
                    await send('/auth/session', 'DELETE')
                    player.clearSession()
                    queryClient.clear()
                    window.location.reload()
                  } catch (error) {
                    notify(errorMessage(error), 'error')
                  }
                }}
              >
                <LogOut size={16} />
                退出登录
              </button>
            )}
            <span className="menu-version" role="presentation">
              MELORA · v{APP_VERSION}
            </span>
          </div>
        )}
      </header>
      <main ref={mainRef} id="main-content" className="main-content" tabIndex={-1}>
        <div className="content-inner">
          <Suspense fallback={<LoadingSurface label="正在打开页面…" className="page-pending" />}>
            <ProviderNamesProvider>
              <Outlet />
            </ProviderNamesProvider>
          </Suspense>
        </div>
      </main>
      <ScrollEdgeControls target={mainRef} />
      <MiniPlayer />
      {searchOpen && (
        <Modal
          title="搜索"
          className="global-search-dialog"
          initialFocus={mobileSearchRef}
          onClose={() => setSearchOpen(false)}
        >
          <form className="mobile-search-form" role="search" aria-label="全局搜索" onSubmit={searchSubmit}>
            <label className="sr-only" htmlFor="mobile-global-search">
              搜索关键词
            </label>
            <div className="mobile-search-input-shell">
              <Search size={19} aria-hidden="true" />
              <input
                id="mobile-global-search"
                ref={mobileSearchRef}
                name="q"
                placeholder="搜索歌曲、歌手、专辑或歌单"
                value={search}
                maxLength={200}
                enterKeyHint="search"
                autoComplete="off"
                autoCapitalize="none"
                autoCorrect="off"
                onChange={(event) => setSearch(event.target.value)}
                onFocus={() => preloadRoute('/search')}
              />
              <button
                type="button"
                className="mobile-search-clear"
                aria-label="清除关键词"
                disabled={!search}
                data-visible={!!search}
                onClick={() => {
                  setSearch('')
                  mobileSearchRef.current?.focus({ preventScroll: true })
                }}
              >
                <X size={17} />
              </button>
            </div>
            <button className="button primary" type="submit">
              搜索
            </button>
          </form>
        </Modal>
      )}
    </div>
  )
}

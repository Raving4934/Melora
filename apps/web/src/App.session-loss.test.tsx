import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'
import { queryClient } from './lib/api'
import type { Session, Track } from './lib/types'
import { player, usePlayer } from './stores/player'
import { useUI } from './stores/ui'

const track: Track = {
  id: 'demo:session-loss',
  providerId: 'demo',
  title: 'Session track',
  artist: 'Artist',
  album: 'Album',
  duration: 120,
  coverUrl: '',
  qualities: ['standard'],
  canDownload: true,
}
const originalScrollTo = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollTo')
const originalShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')

beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = true
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.open = false
    },
  })
  player.clearSession()
  queryClient.clear()
  window.localStorage.clear()
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response('[]', {
          headers: { 'Content-Type': 'application/json' },
        }),
    ),
  )
})

afterEach(() => {
  cleanup()
  player.clearSession()
  queryClient.clear()
  useUI.setState({ drawer: null, downloadTrack: null, playlistTrack: null, sourceGate: null, toast: null })
  window.localStorage.clear()
  vi.unstubAllGlobals()
  if (originalScrollTo) Object.defineProperty(HTMLElement.prototype, 'scrollTo', originalScrollTo)
  else Reflect.deleteProperty(HTMLElement.prototype, 'scrollTo')
  if (originalShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', originalShowModal)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal')
  if (originalClose) Object.defineProperty(HTMLDialogElement.prototype, 'close', originalClose)
  else Reflect.deleteProperty(HTMLDialogElement.prototype, 'close')
})

function mount(session: Session) {
  queryClient.setQueryData(['/auth/session'], session)
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/session-check']}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

it.each<Session>([
  { authenticated: false, required: true, deployMode: 'cloud', loginMethod: 'password' },
  { authenticated: false, required: true, deployMode: 'nas', loginMethod: 'token' },
  { authenticated: false, required: false, authMode: 'fnos' },
])('会话失效统一停止播放并清理私有页面状态：%j', async (denied) => {
  mount({ ...denied, authenticated: true })
  await screen.findByText('这个音乐角落还不存在')
  const stop = vi.spyOn(player, 'stop')
  act(() => {
    usePlayer.setState({ track, queue: [track], playing: true })
    useUI.setState({ downloadTrack: track, playlistTrack: track, sourceGate: 'play' })
    queryClient.setQueryData(['/library/favorites/tracks'], [track])
    queryClient.setQueryData(['/auth/session'], denied)
  })

  await waitFor(() => expect(stop).toHaveBeenCalledOnce())
  expect(usePlayer.getState()).toMatchObject({ playing: false, track: null, queue: [] })
  expect(useUI.getState()).toMatchObject({
    drawer: null,
    downloadTrack: null,
    playlistTrack: null,
    sourceGate: null,
    toast: null,
  })
  expect(queryClient.getQueryData(['/library/favorites/tracks'])).toBeUndefined()
  expect(queryClient.getQueryData(['/auth/session'])).toEqual(denied)
  if (denied.authMode === 'fnos') expect(await screen.findByText('需要飞牛管理员身份')).toBeInTheDocument()
  else expect(await screen.findByRole('button', { name: /进入乐屿/ })).toBeInTheDocument()
})

it('无需认证的本机访问不被误判为会话失效', async () => {
  const stop = vi.spyOn(player, 'stop')
  usePlayer.setState({ track, queue: [track], playing: false })
  mount({ authenticated: false, required: false, deployMode: 'nas' })
  await screen.findByText('这个音乐角落还不存在')
  expect(stop).not.toHaveBeenCalled()
  expect(usePlayer.getState().track).toEqual(track)
})

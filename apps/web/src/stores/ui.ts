import { create } from 'zustand'
import type { Track } from '../lib/types'
interface UIState {
  toast: { id: number; message: string; kind: 'success' | 'error' | 'info' } | null
  drawer: 'queue' | 'lyrics' | null
  playlistTrack: Track | null
  openPlaylist: (track: Track | null) => void
  downloadTrack: Track | null
  notify: (message: string, kind?: 'success' | 'error' | 'info') => void
  setDrawer: (drawer: UIState['drawer']) => void
  openDownload: (track: Track | null) => void
}
let toastTimer: ReturnType<typeof setTimeout>
export const useUI = create<UIState>((set) => ({
  toast: null,
  drawer: null,
  playlistTrack: null,
  openPlaylist: (playlistTrack) => set({ playlistTrack }),
  downloadTrack: null,
  notify: (message, kind = 'info') => {
    clearTimeout(toastTimer)
    set({ toast: { id: Date.now(), message, kind } })
    toastTimer = setTimeout(() => set({ toast: null }), 5000)
  },
  setDrawer: (drawer) => set({ drawer }),
  openDownload: (downloadTrack) => set({ downloadTrack }),
}))
export const notify = (message: string, kind: 'success' | 'error' | 'info' = 'info') =>
  useUI.getState().notify(message, kind)

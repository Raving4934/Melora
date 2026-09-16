import { create } from 'zustand'
import { api, APIError, idPath, invalidate, send } from '../lib/api'
import type { PlayInfo, Settings, Track } from '../lib/types'
import { historyKindFor } from '../lib/history-kind'
import { notify } from './ui'
import { errorMessage } from '../lib/format'
import { createPlayerSessionPersistence, readPlayerSession } from '../lib/player-session'
import { currentDataGeneration } from '../lib/data-identity'
import { flushPendingPlayOutcomes, queuePlayOutcome } from '../lib/play-outcome'

export class MediaPolicyError extends Error {
  constructor(
    public readonly code: 'mixed_content' | 'unsafe_media',
    message: string,
  ) {
    super(message)
    this.name = 'MediaPolicyError'
  }
}

export function validatePlayInfo(info: PlayInfo, trackId: string, origin = window.location.origin): string {
  if (info.direct !== true || info.trackId !== trackId)
    throw new Error('音源返回的歌曲与请求不匹配，已停止播放。')
  let url: URL
  try {
    url = new URL(info.url)
  } catch {
    throw new Error('音源返回了无效地址。')
  }
  const pageURL = new URL(origin)
  if (url.protocol === 'http:' && pageURL.protocol !== 'http:')
    throw new MediaPolicyError(
      'mixed_content',
      '当前页面使用 HTTPS，不能播放 HTTP 音频，请选择支持 HTTPS 的音源。',
    )
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.origin === pageURL.origin ||
    url.username ||
    url.password
  )
    throw new MediaPolicyError('unsafe_media', '音源返回的媒体地址不安全，已停止播放。')
  const host = url.hostname.toLowerCase().replace(/\.$/, '')
  const ipv4 = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(host)
  const localV4 =
    ipv4 &&
    (Number(ipv4[1]) === 0 ||
      Number(ipv4[1]) === 10 ||
      Number(ipv4[1]) === 127 ||
      Number(ipv4[1]) >= 224 ||
      (Number(ipv4[1]) === 169 && Number(ipv4[2]) === 254) ||
      (Number(ipv4[1]) === 172 && Number(ipv4[2]) >= 16 && Number(ipv4[2]) <= 31) ||
      (Number(ipv4[1]) === 192 && Number(ipv4[2]) === 168) ||
      (Number(ipv4[1]) === 100 && Number(ipv4[2]) >= 64 && Number(ipv4[2]) <= 127))
  if (
    host === pageURL.hostname.toLowerCase().replace(/\.$/, '') ||
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host.endsWith('.local') ||
    localV4 ||
    /^\[(::|f[cdef])/i.test(host)
  )
    throw new Error('不能播放本机或内网代理地址。')
  return url.href
}
export const playbackRates = [0.5, 0.75, 1, 1.25, 1.5, 2] as const
const knownQualities = new Set(['standard', '128k', '320k', 'flac', 'flac24bit', 'ape', 'wav'])
const sourceRecoveryLimit = 3
const resolveTimeout = 15_000
const mediaTimeout = 15_000

interface PlayerState {
  track: Track | null
  resolvedTrack: Track | null
  queue: Track[]
  playing: boolean
  loading: boolean
  position: number
  duration: number
  volume: number
  muted: boolean
  volumeBeforeMute: number
  historyRecorded: boolean
  playbackRate: number
  quality: string
  requestedQuality: string
  resolvedQuality: string | null
  availableQualities: string[]
  sourceId: string | null
  attemptedSources: string[]
  autoSwitchSource: boolean
  recovering: boolean
  error: string | null
  mode: 'list' | 'single' | 'shuffle'
}
// SPA 首屏前同步读一次安全元数据；不创建 Audio、不解析、不自动播放。
const restoredSession = readPlayerSession()
export const usePlayer = create<PlayerState>(() => ({
  resolvedTrack: null,
  playing: false,
  loading: false,
  resolvedQuality: null,
  sourceId: null,
  attemptedSources: [],
  autoSwitchSource: false,
  recovering: false,
  error: null,
  ...restoredSession,
  requestedQuality: restoredSession.quality,
  availableQualities: restoredSession.track ? availableQualities(restoredSession.track) : ['standard'],
}))

export const usePlayingCollection = create<{
  collectionId: string | null
  trackIds: string[]
  setCollection: (id: string, tracks: Track[]) => void
  clear: () => void
}>((set) => ({
  collectionId: null,
  trackIds: [],
  setCollection: (id, tracks) => set({ collectionId: id, trackIds: tracks.map((track) => track.id) }),
  clear: () => set({ collectionId: null, trackIds: [] }),
}))

// 时间进度由细粒度订阅消费；一个 Audio 实例跨页面、全屏和歌词视图复用。
let audio: HTMLAudioElement | undefined
let requestVersion = 0
let mediaVersion = 0
let activeController: AbortController | undefined
let expectedURL = ''
let desiredPlaying = false
let recoveryCount = 0
let legacyRefreshCount = 0
let excludedSources: string[] = []
let pendingSeek: { version: number; value: number } | undefined
let stallTimer: ReturnType<typeof setTimeout> | undefined
let resolving = false
let resumeWithoutResolve = false
// 每次显式播放拥有独立结局会话；旧请求即使迟到，也不能写进下一首歌。
type PlayOutcomeSession = {
  trackId: string
  dataGeneration: string | null
  listenedMs: number
  clockStartedAt: number
  sent: boolean
  historyReady: Promise<boolean> | null
}
type PlayOptions = { preserveCollection?: boolean }

function createPlayOutcomeSession(trackId: string, historyRecorded = false): PlayOutcomeSession {
  return {
    trackId,
    dataGeneration: currentDataGeneration(),
    listenedMs: 0,
    clockStartedAt: 0,
    sent: false,
    historyReady: historyRecorded ? Promise.resolve(true) : null,
  }
}

let playOutcomeSession = restoredSession.track
  ? createPlayOutcomeSession(restoredSession.track.id, restoredSession.historyRecorded)
  : null
const set = usePlayer.setState

function ensurePlayOutcomeSession(trackId: string, historyRecorded = false) {
  if (!playOutcomeSession || playOutcomeSession.trackId !== trackId)
    playOutcomeSession = createPlayOutcomeSession(trackId, historyRecorded)
  else if (historyRecorded && !playOutcomeSession.historyReady)
    playOutcomeSession.historyReady = Promise.resolve(true)
  return playOutcomeSession
}
function startPlayClock(session: PlayOutcomeSession) {
  if (session.clockStartedAt === 0) session.clockStartedAt = Date.now()
}
function stopPlayClock(session = playOutcomeSession) {
  if (session && session.clockStartedAt !== 0) {
    session.listenedMs += Date.now() - session.clockStartedAt
    session.clockStartedAt = 0
  }
}
function resetPlayOutcome(trackId: string | null, historyRecorded = false) {
  playOutcomeSession = trackId ? createPlayOutcomeSession(trackId, historyRecorded) : null
}
function bindHistoryWrite(session: PlayOutcomeSession, request: Promise<unknown>) {
  const ready = request
    .then(() => {
      if (playOutcomeSession === session && usePlayer.getState().track?.id === session.trackId)
        set({ historyRecorded: true })
      void Promise.all([invalidate('/library/history'), invalidate('/library/summary')]).catch(() => {})
      // 页面退出时暂存的 outcome 可能先于 history 到达；记录建立后立即补发。
      void flushPendingPlayOutcomes()
      return true
    })
    .catch(() => {
      // 当前会话仍在播放时允许下一次 playing 事件重试；已结算的旧会话保持失败结果。
      if (playOutcomeSession === session && !session.sent) session.historyReady = null
      notify('正在播放，但播放记录暂未保存。')
      return false
    })
  session.historyReady = ready
}
function queueOutcomeSession(session: PlayOutcomeSession, completed: boolean) {
  // 不足 1 秒且不是自然播完的上报没有信号价值，直接忽略。
  if (!completed && session.listenedMs < 1000) return false
  return !!queuePlayOutcome({
    dataGeneration: session.dataGeneration ?? '',
    trackId: session.trackId,
    playedMs: Math.round(session.listenedMs),
    completed,
  })
}
function flushPlayOutcome(completed: boolean) {
  const session = playOutcomeSession
  if (!session || session.sent) return
  stopPlayClock(session)
  session.sent = true
  if (!completed && session.listenedMs < 1000) return
  const ready = session.historyReady ?? Promise.resolve(false)
  void ready
    .then((saved) => {
      if (!saved || !queueOutcomeSession(session, completed)) return
      return flushPendingPlayOutcomes()
    })
    .catch(() => {
      // 已持久化的结局会在下次认证或 history 建立后补发，不影响当前播放。
    })
}

function persistOutcomeOnPageHide() {
  const session = playOutcomeSession
  if (!session || session.sent) return
  stopPlayClock(session)
  session.sent = true
  // pagehide 内只做同步本地写入，避免依赖 WebView 在销毁阶段完成网络请求。
  queueOutcomeSession(session, false)
}

function resumeOutcomeAfterPageShow(event: PageTransitionEvent) {
  void flushPendingPlayOutcomes()
  if (!event.persisted) return
  const state = usePlayer.getState()
  const trackId = state.track?.id
  if (!trackId || !playOutcomeSession?.sent) return
  playOutcomeSession = createPlayOutcomeSession(trackId, state.historyRecorded)
  if (state.playing) startPlayClock(playOutcomeSession)
}

function supportedQuality(value: unknown): value is string {
  return typeof value === 'string' && knownQualities.has(value)
}
export function availableQualities(track: Track, info?: PlayInfo): string[] {
  // resolvedTrack 是本次实际选中的同曲，不把上一音源的音质菜单混入新音源。
  const declared = info?.resolvedTrack?.qualities ?? track.qualities ?? []
  return [...new Set(['standard', ...declared, ...(info?.quality ? [info.quality] : [])])].filter(
    supportedQuality,
  )
}
function sourceIDs(values: unknown): string[] {
  if (!Array.isArray(values)) return []
  return [
    ...new Set(
      values.filter(
        (id): id is string =>
          typeof id === 'string' && id.length > 0 && id.length <= 200 && !/[\s,\x00-\x1f]/.test(id),
      ),
    ),
  ].slice(0, 16)
}
function clearStallTimer() {
  if (stallTimer !== undefined) clearTimeout(stallTimer)
  stallTimer = undefined
}
function activeMedia() {
  return !!audio && !!expectedURL && mediaVersion === requestVersion && audio.src === expectedURL
}
function failPlayback(message: string) {
  clearStallTimer()
  desiredPlaying = false
  resolving = false
  set({ loading: false, recovering: false, playing: false, error: message })
  notify(message, 'error')
}
function armStallTimer() {
  clearStallTimer()
  const version = requestVersion
  stallTimer = setTimeout(() => {
    if (version === requestVersion && desiredPlaying && activeMedia()) recoverMedia()
  }, mediaTimeout)
}
function restorePosition() {
  if (!audio || !pendingSeek || pendingSeek.version !== requestVersion || !activeMedia()) return
  const position = pendingSeek.value
  const duration =
    Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : usePlayer.getState().duration
  try {
    audio.currentTime = Math.max(0, Math.min(position, duration || position))
    pendingSeek = undefined
    set({ position: audio.currentTime, ...(duration > 0 ? { duration } : {}) })
  } catch {
    // 个别浏览器在 metadata 之后、canplay 之前仍不可 seek；下一次 canplay 再应用。
  }
}
function updateMediaSession(track: Track) {
  if (!('mediaSession' in navigator) || typeof MediaMetadata === 'undefined') return
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title,
      artist: track.artist,
      album: track.album,
    })
    navigator.mediaSession.setActionHandler('play', () => player.resume())
    navigator.mediaSession.setActionHandler('pause', () => player.pause())
    navigator.mediaSession.setActionHandler('previoustrack', () => player.previous())
    navigator.mediaSession.setActionHandler('nexttrack', () => player.next())
    navigator.mediaSession.setActionHandler('seekto', (event) => {
      if (typeof event.seekTime === 'number') player.seek(event.seekTime)
    })
  } catch {
    // WebView 不支持某个 Media Session action 时，不影响基本播放。
  }
}
function applyAudioPreferences(element: HTMLAudioElement) {
  const { volume, playbackRate, muted } = usePlayer.getState()
  element.volume = volume
  element.muted = muted
  element.playbackRate = playbackRate
}
function getAudio() {
  if (audio) return audio
  const element = new Audio()
  audio = element
  element.preload = 'metadata'
  applyAudioPreferences(element)
  element.addEventListener('timeupdate', () => {
    if (activeMedia() && !pendingSeek && Number.isFinite(element.currentTime))
      set({ position: element.currentTime })
  })
  element.addEventListener('durationchange', () => {
    if (activeMedia() && Number.isFinite(element.duration) && element.duration > 0)
      set({ duration: element.duration })
  })
  element.addEventListener('loadedmetadata', restorePosition)
  element.addEventListener('canplay', () => {
    restorePosition()
    if (activeMedia() && !desiredPlaying) set({ loading: false, recovering: false })
  })
  element.addEventListener('playing', () => {
    if (!activeMedia()) return
    if (!desiredPlaying) {
      element.pause()
      return
    }
    clearStallTimer()
    set({ playing: true, loading: false, recovering: false, error: null })
    const state = usePlayer.getState()
    const track = state.track
    if (!track) return
    const session = ensurePlayOutcomeSession(track.id, state.historyRecorded)
    startPlayClock(session)
    if (!state.historyRecorded && !session.historyReady) {
      const collection = usePlayingCollection.getState()
      const kind = historyKindFor(collection.collectionId, collection.trackIds, track.id)
      const contextId = kind === 'track' ? undefined : collection.collectionId
      bindHistoryWrite(
        session,
        send('/library/history', 'POST', {
          trackId: track.id,
          kind,
          ...(contextId ? { contextId } : {}),
        }),
      )
    }
  })
  element.addEventListener('pause', () => {
    stopPlayClock()
    if (activeMedia()) set({ playing: false })
  })
  element.addEventListener('waiting', () => {
    stopPlayClock()
    if (activeMedia() && desiredPlaying) {
      set({ loading: true })
      armStallTimer()
    }
  })
  element.addEventListener('stalled', () => {
    if (activeMedia() && desiredPlaying) armStallTimer()
  })
  element.addEventListener('ended', () => {
    if (!activeMedia()) return
    clearStallTimer()
    const track = usePlayer.getState().track
    flushPlayOutcome(true)
    if (usePlayer.getState().mode === 'single') {
      // 单曲循环属于再次播放：重置历史标记与结局统计，重复强度才会累加。
      resetPlayOutcome(track?.id ?? null)
      set({ historyRecorded: false })
      player.seek(0)
      void resumeAudio()
    } else player.next()
  })
  element.addEventListener('error', () => {
    if (!activeMedia() || resolving || element.error?.code === 1) return
    recoverMedia()
  })
  return element
}
async function resumeAudio(version = requestVersion) {
  desiredPlaying = true
  try {
    await getAudio().play()
    if (version === requestVersion && desiredPlaying && activeMedia()) {
      clearStallTimer()
      set({ playing: true, loading: false, recovering: false })
    }
  } catch (error) {
    if (version !== requestVersion || !desiredPlaying) return
    if (error instanceof DOMException && error.name === 'AbortError') return
    if (error instanceof DOMException && error.name === 'NotAllowedError') {
      failPlayback('浏览器暂停了自动播放，请点击播放继续。')
      resumeWithoutResolve = true
    } else if (!resolving) recoverMedia()
  }
}
function recoverMedia() {
  const state = usePlayer.getState()
  if (!state.track || resolving) return
  if (recoveryCount >= sourceRecoveryLimit) {
    failPlayback('已尝试可用音源，仍无法播放。请重试或检查音源设置。')
    return
  }
  const knownSources = sourceIDs([...state.attemptedSources, state.sourceId])
  if ((!state.autoSwitchSource || !knownSources.length) && legacyRefreshCount >= 1) {
    failPlayback('媒体暂不可用或不支持此格式。请重试或检查音源设置。')
    return
  }
  recoveryCount++
  if (!state.autoSwitchSource || !knownSources.length) legacyRefreshCount++
  if (state.autoSwitchSource) excludedSources = sourceIDs([...excludedSources, ...knownSources])
  void resolveTrack(state.track, {
    quality: state.quality,
    position: state.position,
    autoplay: desiredPlaying,
    reason: 'recover',
  })
}

function playbackErrorMessage(error: unknown): string {
  const message = errorMessage(error)
  const resolution = error instanceof APIError ? error.resolution : undefined
  if (!resolution) return message
  if (resolution.stage === 'catalog') return `歌曲目录读取失败，尚未进入音源解析：${message}`
  if (resolution.attempts > 0) {
    const mode =
      resolution.autoSwitch === true
        ? '自动换源已开启，'
        : resolution.autoSwitch === false
          ? '自动换源已关闭，'
          : ''
    return `${mode}已尝试 ${resolution.attempts} 个音源：${message}`
  }
  return `${message}（尚未取得支持当前歌曲与音质的播放地址）`
}

type ResolveOptions = {
  quality?: string
  position: number
  autoplay: boolean
  reason: 'track' | 'quality' | 'retry' | 'recover'
}
function requestPlayInfo(track: Track, quality: string, signal: AbortSignal, excluded: string[]) {
  const query = new URLSearchParams({ quality })
  if (excluded.length) query.set('excludeSources', excluded.join(','))
  // 原 play-info handler 已承接音质与换源参数，不探测别名或吞掉真实解析错误。
  return api<PlayInfo>(`/tracks/${idPath(track.id)}/play-info?${query}`, {
    signal,
    headers: { 'X-Melora-Origin': window.location.origin },
  })
}

async function resolveTrack(track: Track, options: ResolveOptions) {
  const snapshot = usePlayer.getState()
  const previousURL = expectedURL
  const version = ++requestVersion
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  const timeout = setTimeout(
    () => controller.abort(new DOMException('解析超时', 'TimeoutError')),
    resolveTimeout,
  )
  let element: HTMLAudioElement | undefined
  let phase: 'prepare' | 'resolve' | 'media' = 'prepare'
  clearStallTimer()
  desiredPlaying = options.autoplay
  resolving = true
  resumeWithoutResolve = false
  mediaVersion = 0
  pendingSeek = { version, value: options.position }
  expectedURL = ''
  set({
    track,
    loading: true,
    recovering: options.reason === 'recover',
    playing: false,
    error: null,
    position: options.position,
    duration: options.reason === 'track' ? track.duration : snapshot.duration,
    requestedQuality: options.quality ?? snapshot.quality,
    ...(options.reason === 'track'
      ? {
          resolvedTrack: null,
          resolvedQuality: null,
          sourceId: null,
          attemptedSources: [],
          availableQualities: availableQualities(track),
        }
      : {}),
  })
  try {
    element = getAudio()
    element.pause()
    element.removeAttribute('src')
    element.load()
    phase = 'resolve'
    const settings = await api<Pick<Settings, 'defaultQuality' | 'autoSwitchSource'>>('/settings', {
      signal: controller.signal,
    })
    if (version !== requestVersion) return
    const quality =
      options.quality ?? (supportedQuality(settings.defaultQuality) ? settings.defaultQuality : 'standard')
    const autoSwitchSource = settings.autoSwitchSource === true
    const excluded = autoSwitchSource ? excludedSources : []
    set({ requestedQuality: quality, autoSwitchSource })
    let failedSources = excluded
    let info: PlayInfo
    let url: string
    // HTTPS 页面不能播放 HTTP。只在明确混合内容错误时排除已返回的源，不修改 URL。
    for (;;) {
      info = await requestPlayInfo(track, quality, controller.signal, failedSources)
      if (version !== requestVersion) return
      if (info.sourceId && failedSources.includes(info.sourceId))
        throw new Error('音源未切换成功，请检查可用音源后重试。')
      try {
        url = validatePlayInfo(info, track.id)
        break
      } catch (error) {
        const attempted = sourceIDs([...failedSources, ...(info.attemptedSources || []), info.sourceId])
        if (
          !(error instanceof MediaPolicyError) ||
          error.code !== 'mixed_content' ||
          !autoSwitchSource ||
          !info.sourceId ||
          attempted.length <= failedSources.length ||
          recoveryCount >= sourceRecoveryLimit
        )
          throw error
        recoveryCount++
        failedSources = attempted
        excludedSources = attempted
        set({ recovering: true, attemptedSources: attempted })
      }
    }
    expectedURL = url
    mediaVersion = version
    pendingSeek = { version, value: pendingSeek?.version === version ? pendingSeek.value : options.position }
    set({
      quality,
      requestedQuality: quality,
      resolvedQuality: supportedQuality(info.quality) ? info.quality : null,
      resolvedTrack: info.resolvedTrack ?? null,
      sourceId: typeof info.sourceId === 'string' ? info.sourceId : null,
      attemptedSources: sourceIDs([...failedSources, ...(info.attemptedSources || [])]),
      availableQualities: availableQualities(track, info),
    })
    phase = 'media'
    element.src = url
    element.load()
    applyAudioPreferences(element)
    updateMediaSession(track)
    resolving = false
    if (element.readyState >= 1) restorePosition()
    if (desiredPlaying) {
      armStallTimer()
      await resumeAudio(version)
    } else set({ playing: false, loading: false, recovering: false })
  } catch (error) {
    if (version !== requestVersion) return
    resolving = false
    const message = controller.signal.aborted
      ? '歌曲解析超时，请重试。'
      : phase === 'prepare'
        ? '播放器初始化失败，请刷新页面重试。'
        : playbackErrorMessage(error)
    // 切换音质失败可恢复同一首歌的旧媒体；不能把失败的新音质标成成功。
    if (element && options.reason === 'quality' && previousURL && snapshot.track?.id === track.id) {
      expectedURL = previousURL
      mediaVersion = version
      pendingSeek = { version, value: usePlayer.getState().position }
      set({
        quality: snapshot.quality,
        requestedQuality: snapshot.quality,
        resolvedQuality: snapshot.resolvedQuality,
        sourceId: snapshot.sourceId,
        attemptedSources: snapshot.attemptedSources,
        resolvedTrack: snapshot.resolvedTrack,
        availableQualities: snapshot.availableQualities,
        loading: false,
        recovering: false,
        error: '切换音质失败，已保留原音质。',
      })
      try {
        element.src = previousURL
        element.load()
        applyAudioPreferences(element)
        if (element.readyState >= 1) restorePosition()
        if (desiredPlaying) await resumeAudio(version)
        notify(`切换音质失败：${message}`, 'error')
      } catch {
        // 恢复旧媒体也可能被WebView拒绝，不能从错误处理分支再泄露Promise拒绝。
        failPlayback('播放器无法恢复媒体，请刷新页面重试。')
      }
    } else failPlayback(message)
  } finally {
    clearTimeout(timeout)
    if (activeController === controller) activeController = undefined
  }
}

export const player = {
  // 显式 stop 是清空会话（现有网关拒绝入口已调用），不是关闭抽屉/切路由。
  stop() {
    requestVersion++
    activeController?.abort()
    activeController = undefined
    clearStallTimer()
    resolving = false
    resumeWithoutResolve = false
    desiredPlaying = false
    expectedURL = ''
    mediaVersion = 0
    pendingSeek = undefined
    try {
      if (audio) {
        audio.pause()
        audio.removeAttribute('src')
        audio.load()
      }
    } catch {
      // WebView 媒体销毁失败也必须继续执行隐私清理。
    }
    recoveryCount = 0
    legacyRefreshCount = 0
    excludedSources = []
    flushPlayOutcome(false)
    resetPlayOutcome(null)
    usePlayingCollection.getState().clear()
    set({
      track: null,
      historyRecorded: false,
      resolvedTrack: null,
      queue: [],
      playing: false,
      loading: false,
      position: 0,
      duration: 0,
      error: null,
      recovering: false,
      sourceId: null,
      attemptedSources: [],
      quality: 'standard',
      requestedQuality: 'standard',
      resolvedQuality: null,
      availableQualities: ['standard'],
    })
    sessionPersistence.clear()
  },
  // 用户明确清空历史时丢弃清空边界前的统计，避免旧 outcome 复活新记录。
  historyCleared() {
    stopPlayClock()
    if (playOutcomeSession) playOutcomeSession.sent = true
    const trackId = usePlayer.getState().track?.id ?? null
    resetPlayOutcome(trackId, false)
    set({ historyRecorded: false })
  },
  // 登录退出/账号切换请主线调用；清掉内存与磁盘，不删除服务器播放历史。
  clearSession() {
    player.stop()
    set({ volume: 0.7, muted: false, volumeBeforeMute: 0.7, playbackRate: 1, mode: 'list' })
    sessionPersistence.clear()
  },
  // 新数据库不能复活旧媒体/队列；同代际已有快照只恢复为暂停态。
  restoreDataSession() {
    sessionPersistence.withoutSaving(() => {
      player.clearSession()
      const restored = readPlayerSession()
      resetPlayOutcome(restored.track?.id ?? null, restored.historyRecorded)
      set({
        ...restored,
        requestedQuality: restored.quality,
        availableQualities: restored.track ? availableQualities(restored.track) : ['standard'],
      })
      try {
        if (audio) applyAudioPreferences(audio)
        if ('mediaSession' in navigator) navigator.mediaSession.metadata = null
      } catch {
        // 原生媒体界面不支持重置时，不影响新代际的内存/存储隔离。
      }
    })
  },
  // 集合播放的统一入口：先落上下文，再播放；历史分类依赖该上下文。
  playCollection(collectionId: string, tracks: Track[], start?: Track) {
    const track = start ?? tracks[0]
    if (!track || !tracks.length) return Promise.resolve()
    usePlayingCollection.getState().setCollection(collectionId, tracks)
    return player.play(track, tracks, { preserveCollection: true })
  },
  play(track: Track, queue?: Track[], options: PlayOptions = {}) {
    recoveryCount = 0
    legacyRefreshCount = 0
    excludedSources = []
    // 每次显式播放都结算上一会话；同曲重播也不能吞掉上一段收听时长。
    flushPlayOutcome(false)
    resetPlayOutcome(track.id)
    if (!options.preserveCollection) usePlayingCollection.getState().clear()
    set({ historyRecorded: false })
    const existing = usePlayer.getState().queue
    const nextQueue = queue?.length
      ? queue
      : existing.some((item) => item.id === track.id)
        ? existing
        : [track]
    set({ queue: [...new Map(nextQueue.map((item) => [item.id, item])).values()] })
    return resolveTrack(track, { position: 0, autoplay: true, reason: 'track' })
  },
  pause() {
    desiredPlaying = false
    clearStallTimer()
    if (resolving) {
      requestVersion++
      activeController?.abort()
      activeController = undefined
      resolving = false
      pendingSeek = undefined
    }
    audio?.pause()
    set({ loading: false, recovering: false, playing: false })
    sessionPersistence.flush()
  },
  resume() {
    const { track, queue, position, quality } = usePlayer.getState()
    if (!track) {
      if (queue[0]) void player.play(queue[0], queue)
      else notify('请先选择歌曲。')
      return
    }
    if (!activeMedia()) {
      void resolveTrack(track, { quality, position, autoplay: true, reason: 'retry' })
      return
    }
    void resumeAudio()
  },
  toggle() {
    const { playing, loading, error } = usePlayer.getState()
    if (loading || playing) player.pause()
    else if (error) player.retry()
    else player.resume()
  },
  retry() {
    const state = usePlayer.getState()
    if (!state.track) return
    if (resumeWithoutResolve && activeMedia()) {
      resumeWithoutResolve = false
      return resumeAudio()
    }
    recoveryCount = 0
    legacyRefreshCount = 0
    excludedSources = []
    return resolveTrack(state.track, {
      quality: state.quality,
      position: state.position,
      autoplay: true,
      reason: 'retry',
    })
  },
  setQuality(quality: string) {
    const state = usePlayer.getState()
    if (!state.track || !supportedQuality(quality) || !state.availableQualities.includes(quality)) return
    if (quality === state.quality && !state.error) return
    recoveryCount = 0
    legacyRefreshCount = 0
    excludedSources = []
    return resolveTrack(state.track, {
      quality,
      position: state.position,
      autoplay: desiredPlaying,
      reason: 'quality',
    })
  },
  setRate(value: number) {
    if (!Number.isFinite(value) || value < 0.5 || value > 2) return
    const element = getAudio()
    try {
      element.playbackRate = value
      set({ playbackRate: value })
    } catch {
      notify('当前浏览器不支持这个播放速度。', 'error')
    }
  },
  next(direction = 1) {
    const { track, queue, mode } = usePlayer.getState()
    if (!queue.length) return
    const current = queue.findIndex((item) => item.id === track?.id)
    const offset =
      mode === 'shuffle' && queue.length > 1 ? 1 + Math.floor(Math.random() * (queue.length - 1)) : direction
    void player.play(queue[(current + offset + queue.length) % queue.length]!, queue, {
      preserveCollection: true,
    })
  },
  previous() {
    if (usePlayer.getState().position > 3) player.seek(0)
    else player.next(-1)
  },
  seek(value: number) {
    if (!Number.isFinite(value)) return
    const duration = usePlayer.getState().duration
    const position = Math.max(0, Math.min(value, duration || value))
    if (pendingSeek) {
      pendingSeek.value = position
      set({ position })
      sessionPersistence.flush()
      return
    }
    if (audio && activeMedia()) {
      try {
        audio.currentTime = position
        set({ position })
        sessionPersistence.flush()
      } catch {
        /* metadata 尚未就绪时不让组件崩溃。 */
      }
    } else if (usePlayer.getState().track) {
      // 恢复后尚未解析的暂停会话也允许预选进度。
      set({ position })
      sessionPersistence.flush()
    }
  },
  volume(value: number) {
    if (!Number.isFinite(value)) return
    const volume = Math.max(0, Math.min(1, value))
    const element = getAudio()
    element.volume = volume
    element.muted = volume === 0
    set({
      volume,
      muted: volume === 0,
      ...(volume > 0 ? { volumeBeforeMute: volume } : {}),
    })
  },
  toggleMute() {
    const { volume, volumeBeforeMute } = usePlayer.getState()
    player.volume(volume > 0 ? 0 : volumeBeforeMute)
  },
  cycleMode() {
    const modes: PlayerState['mode'][] = ['list', 'single', 'shuffle']
    set((state) => ({ mode: modes[(modes.indexOf(state.mode) + 1) % modes.length] }))
  },
  remove(id: string) {
    const { track, queue } = usePlayer.getState()
    if (track?.id !== id) set({ queue: queue.filter((item) => item.id !== id) })
  },
  clearQueue() {
    set((state) => ({ queue: state.track ? [state.track] : [] }))
  },
}

const sessionPersistence = createPlayerSessionPersistence(usePlayer.getState)
const unsubscribeSession = usePlayer.subscribe(sessionPersistence.sync)
type OutcomeLifecycleWindow = Window & { __meloraDisposeOutcomeLifecycle?: () => void }
const outcomeLifecycleWindow = window as OutcomeLifecycleWindow
// 测试模块重载和 HMR 都先移除旧监听，避免一次 pagehide 重复记录多个结局。
outcomeLifecycleWindow.__meloraDisposeOutcomeLifecycle?.()
const disposeOutcomeLifecycle = () => {
  window.removeEventListener('pagehide', persistOutcomeOnPageHide)
  window.removeEventListener('pageshow', resumeOutcomeAfterPageShow)
  if (outcomeLifecycleWindow.__meloraDisposeOutcomeLifecycle === disposeOutcomeLifecycle)
    delete outcomeLifecycleWindow.__meloraDisposeOutcomeLifecycle
}
outcomeLifecycleWindow.__meloraDisposeOutcomeLifecycle = disposeOutcomeLifecycle
window.addEventListener('pagehide', persistOutcomeOnPageHide)
window.addEventListener('pageshow', resumeOutcomeAfterPageShow)
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    unsubscribeSession()
    sessionPersistence.dispose()
    disposeOutcomeLifecycle()
  })
}

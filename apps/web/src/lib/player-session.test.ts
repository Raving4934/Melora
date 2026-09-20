import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createPlayerSessionPersistence,
  PLAYER_SESSION_KEYS as keys,
  PLAYER_SESSION_MAX_BYTES,
  PLAYER_SESSION_PROGRESS_INTERVAL,
  PLAYER_SESSION_QUEUE_LIMIT,
  readPlayerSession,
  sanitizeSessionTrack,
  type PlayerSession,
} from './player-session'

const track = {
  id: 'demo:one',
  providerId: 'demo',
  title: '歌曲',
  artist: '歌手',
  album: '专辑',
  duration: 90,
  coverUrl: '/covers/coast.svg',
  qualities: ['standard', 'flac'],
  canDownload: true,
}
let state: PlayerSession
let persistence: ReturnType<typeof createPlayerSessionPersistence>
function change(patch: Partial<PlayerSession>) {
  const previous = state
  state = { ...state, ...patch }
  persistence.sync(state, previous)
}
function seed(queue: unknown, playback: unknown) {
  localStorage.setItem(keys.queue, JSON.stringify(queue))
  localStorage.setItem(keys.playback, JSON.stringify(playback))
}
beforeEach(() => {
  localStorage.clear()
  state = readPlayerSession()
  persistence = createPlayerSessionPersistence(() => state)
})
afterEach(() => {
  persistence.dispose()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  localStorage.clear()
})

describe('设备会话字段白名单', () => {
  it('只留下 Track 白名单，不把解析 URL/凭据/PlayInfo 或运行时状态写入任何 key', () => {
    const tainted = {
      ...track,
      url: 'https://media.example.org/song.mp3?token=SECRET',
      token: 'SECRET',
      playInfo: { url: 'SECRET' },
      error: 'SECRET',
      resolvedTrack: { title: 'SECRET' },
      qualities: ['standard', 'flac', 'SECRET', 'flac'],
    }
    change({ track: tainted, queue: [tainted], position: 18, historyRecorded: true })
    const persisted = Object.values(keys)
      .map((key) => localStorage.getItem(key))
      .join('')
    expect(persisted).not.toMatch(/SECRET|playInfo|resolvedTrack|error|https:\/\/media/)
    expect(readPlayerSession()).toMatchObject({ track, queue: [track], position: 18, historyRecorded: true })
    expect(Object.keys(JSON.parse(localStorage.getItem(keys.queue)!).track).sort()).toEqual(
      Object.keys(track).sort(),
    )
  })
  it.each([
    undefined,
    null,
    [],
    'track',
    {},
    { ...track, id: undefined },
    { ...track, id: 'undefined' },
    { ...track, id: 'https://example.org/?token=SECRET' },
    { ...track, providerId: 'null' },
    { ...track, title: 'x'.repeat(513) },
    { ...track, album: '\u0000SECRET' },
  ])('拒绝非法 Track %j', (value) => {
    expect(sanitizeSessionTrack(value)).toBeNull()
  })
  it.each([
    'javascript:alert(1)',
    'data:image/svg+xml,SECRET',
    'blob:https://example.org/SECRET',
    '//example.org/a.jpg',
    '/api/v1/media.png',
    '/covers/../api/a.svg',
    '/covers/%2e%2e/a.svg',
    'https://user:SECRET@example.org/a.jpg',
    'https://img.example.org/a.jpg?token=SECRET',
    'https://img.example.org/a.jpg?X-Amz-Signature=SECRET',
    'https://img.example.org/a.jpg#SECRET',
    'https://img.example.org/signature/SECRET/a.jpg',
    'https://img.example.org/a.mp3',
    'https://img.example.org/' + 'x'.repeat(1024) + '.jpg',
    'https://localhost/a.jpg',
    'https://foo.local/a.jpg',
    'https://127.1/a.jpg',
    'https://2130706433/a.jpg',
    'https://192.168.1.1/a.jpg',
    'https://10.0.0.1/a.jpg',
    'https://100.64.0.1/a.jpg',
    'https://169.254.169.254/a.jpg',
    'https://[::1]/a.jpg',
    'https://[::ffff:127.0.0.1]/a.jpg',
    'https://example.org:1234/a.jpg',
    'https:\\evil.example.org\\a.jpg',
  ])('封面不安全或含签名时丢弃 %s', (coverUrl) => {
    expect(sanitizeSessionTrack({ ...track, coverUrl })?.coverUrl).toBe('')
    change({ track: { ...track, coverUrl }, queue: [{ ...track, coverUrl }] })
    expect(localStorage.getItem(keys.queue)).not.toContain('SECRET')
  })
  it.each(['/covers/coast.svg', 'https://img.example.org/album/a.jpg', 'http://img.example.org/a.webp'])(
    '保留安全封面 %s',
    (coverUrl) => {
      expect(sanitizeSessionTrack({ ...track, coverUrl })?.coverUrl).toBe(coverUrl)
    },
  )
})

describe('有界缓存、旧数据和偏好校验', () => {
  it('合法的极低静音前音量也原样保存，不擅自回到默认音量', () => {
    change({ volume: 0, muted: true, volumeBeforeMute: 0.001 })
    expect(readPlayerSession()).toMatchObject({ volume: 0, muted: true, volumeBeforeMute: 0.001 })
  })

  it('持久队列去重、限 200 首且不遗漏超出截断点的当前曲目，不截断内存队列', () => {
    const queue = Array.from({ length: 350 }, (_, i) => ({ ...track, id: `demo:${i}` }))
    change({ queue, track: queue[349] })
    const restored = readPlayerSession()
    expect(state.queue).toHaveLength(350)
    expect(restored.queue).toHaveLength(PLAYER_SESSION_QUEUE_LIMIT)
    expect(restored.queue.at(-1)?.id).toBe('demo:349')
    expect(restored.track?.id).toBe('demo:349')
    change({ queue: [track, track], track })
    expect(readPlayerSession().queue).toEqual([track])
  })
  it('最长合法元数据仍限制磁盘字节预算，同时保留当前曲目', () => {
    const queue = Array.from({ length: 200 }, (_, i) => ({
      ...track,
      id: `demo:${i}`,
      title: '长'.repeat(512),
      artist: '长'.repeat(512),
      album: '长'.repeat(512),
      coverUrl: `https://img.example.org/${'x'.repeat(980)}.jpg`,
    }))
    change({ queue, track: queue[199] })
    expect(localStorage.getItem(keys.queue)!.length * 2).toBeLessThanOrEqual(PLAYER_SESSION_MAX_BYTES)
    expect(readPlayerSession().track?.id).toBe('demo:199')
    expect(readPlayerSession().queue.some((item) => item.id === 'demo:199')).toBe(true)
  })
  it.each(['undefined', '{broken', 'null', '[]', '"text"', '{"version":0}', '{"version":99}'])(
    '非法/未知版本缓存安全回默认 %s',
    (raw) => {
      localStorage.setItem(keys.queue, raw)
      localStorage.setItem(keys.playback, raw)
      expect(readPlayerSession()).toEqual(state)
    },
  )
  it('读取前限制原始缓存尺寸，拒绝超大文档', () => {
    localStorage.setItem(keys.queue, ' '.repeat(PLAYER_SESSION_MAX_BYTES))
    localStorage.setItem(keys.playback, ' '.repeat(4096))
    expect(readPlayerSession()).toEqual(state)
    expect(localStorage.getItem(keys.queue)).toBeNull()
  })
  it('不信任旧缓存中的运行态/元数据额外字段和非有限偏好', () => {
    seed(
      { version: 1, track: { ...track, token: 'SECRET' }, queue: [null, {}, track] },
      {
        version: 1,
        trackId: track.id,
        volume: '0.1',
        muted: 'true',
        playbackRate: 9,
        position: -1,
        duration: 1e100,
        volumeBeforeMute: 0,
        mode: 'evil',
        quality: 'evil',
        playing: true,
        loading: true,
        error: 'SECRET',
        historyRecorded: 'true',
      },
    )
    expect(readPlayerSession()).toEqual({ ...state, track, queue: [track], duration: 90 })
  })
  it('拆分缓存曲目不一致时不把上首进度和历史标志应用给下首，偏好仍可恢复', () => {
    seed(
      { version: 1, track, queue: [track] },
      {
        version: 1,
        trackId: 'demo:other',
        position: 30,
        duration: 180,
        historyRecorded: true,
        volume: 0.3,
        playbackRate: 1.5,
      },
    )
    expect(readPlayerSession()).toMatchObject({
      track,
      position: 0,
      duration: 90,
      historyRecorded: false,
      volume: 0.3,
      playbackRate: 1.5,
    })
  })
  it('旧缓存进度越界归零、静音和静音前音量独立恢复', () => {
    seed(
      { version: 1, track, queue: [track] },
      {
        version: 1,
        trackId: track.id,
        position: 100,
        duration: 90,
        muted: true,
        volume: 0.4,
        volumeBeforeMute: 0.4,
        playbackRate: 1.25,
        quality: 'flac',
        mode: 'single',
      },
    )
    expect(readPlayerSession()).toMatchObject({
      position: 0,
      muted: true,
      volume: 0,
      volumeBeforeMute: 0.4,
      playbackRate: 1.25,
      quality: 'flac',
      mode: 'single',
    })
  })
})

describe('存储安全边界和进度写入预算', () => {
  it('window 不存在时安全返回默认值', () => {
    vi.stubGlobal('window', undefined)
    expect(readPlayerSession()).toEqual(state)
    expect(() => change({ track, queue: [track] })).not.toThrow()
  })
  it('localStorage getter 抛错时读取、写入、清理都不崩', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })
    expect(readPlayerSession()).toEqual(state)
    expect(() => change({ track, queue: [track] })).not.toThrow()
    expect(() => persistence.clear()).not.toThrow()
  })
  it.each(['getItem', 'setItem', 'removeItem'] as const)('%s 抛 undefined 不影响内存播放或清理', (method) => {
    vi.spyOn(Storage.prototype, method).mockImplementation(() => {
      throw undefined
    })
    expect(() => readPlayerSession()).not.toThrow()
    expect(() => change({ track, queue: [track], position: 12 })).not.toThrow()
    expect(state.track).toEqual(track)
    expect(() => persistence.clear()).not.toThrow()
  })
  it('高频进度只写小快照；队列不变时零次重写队列，设置立即保存', () => {
    vi.useFakeTimers()
    change({ track, queue: [track] })
    const write = vi.spyOn(Storage.prototype, 'setItem')
    for (let i = 1; i <= 120; i++) change({ position: i / 4 })
    expect(write).not.toHaveBeenCalled()
    vi.advanceTimersByTime(PLAYER_SESSION_PROGRESS_INTERVAL)
    expect(write).toHaveBeenCalledTimes(1)
    expect(write.mock.calls[0][0]).toBe(keys.playback)
    expect(readPlayerSession().position).toBe(30)
    change({ volume: 0.2, playbackRate: 1.5 })
    expect(readPlayerSession()).toMatchObject({ volume: 0.2, playbackRate: 1.5 })
    expect(write.mock.calls.every(([key]) => key === keys.playback)).toBe(true)
  })
  it('pagehide/hidden 补写最后进度；清理取消定时器防止缓存复活', () => {
    vi.useFakeTimers()
    change({ track, queue: [track], position: 12 })
    change({ position: 15 })
    window.dispatchEvent(new Event('pagehide'))
    expect(readPlayerSession().position).toBe(15)
    change({ position: 18 })
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(readPlayerSession().position).toBe(18)
    change({ position: 22 })
    persistence.clear()
    vi.runAllTimers()
    window.dispatchEvent(new Event('pagehide'))
    expect(Object.values(keys).map((key) => localStorage.getItem(key))).toEqual([null, null])
  })
  it('普通路由 popstate 不清理、不重建队列', () => {
    change({ track, queue: [track], position: 12 })
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(readPlayerSession()).toMatchObject({ track, queue: [track], position: 12 })
  })
  it('短暂 quota 异常后仍可补写，不丢内存状态', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw new DOMException('full', 'QuotaExceededError')
    })
    change({ track, queue: [track], position: 21 })
    persistence.flush()
    expect(readPlayerSession()).toMatchObject({ track, queue: [track], position: 21 })
  })
})

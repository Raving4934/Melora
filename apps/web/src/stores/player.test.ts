import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  availableQualities,
  MediaPolicyError,
  player,
  usePlayer,
  usePlayingCollection,
  validatePlayInfo,
} from './player'
import type { PlayInfo, Track } from '../lib/types'
import { PLAYER_SESSION_KEYS, readPlayerSession } from '../lib/player-session'
import { activateDataIdentity } from '../lib/data-identity'
import {
  authorizePlayOutcomeDelivery,
  flushPendingPlayOutcomes,
  pendingPlayOutcomesForTest,
} from '../lib/play-outcome'

const track: Track = {
  id: 'demo:one',
  title: 'One',
  artist: 'Artist',
  providerId: 'demo',
  album: 'Album',
  duration: 90,
  coverUrl: '/covers/coast.svg',
  qualities: ['standard', '128k', '320k', 'flac'],
  canDownload: true,
}
const info: PlayInfo = {
  trackId: track.id,
  direct: true,
  url: 'https://media.example.org/one.ogg',
  mimeType: 'audio/ogg',
}
let currentAudio: FakeAudio | undefined
class FakeAudio extends EventTarget {
  src = ''
  currentTime = 0
  duration = 90
  readyState = 0
  preload = ''
  volume = 0.7
  muted = false
  playbackRate = 1
  paused = true
  error: { code: number } | null = null
  autoMetadata = true
  private generation = 0
  constructor() {
    super()
    currentAudio = this
  }
  pause = vi.fn(() => {
    this.paused = true
    this.dispatchEvent(new Event('pause'))
  })
  play = vi.fn(async () => {
    await Promise.resolve()
    this.paused = false
    this.dispatchEvent(new Event('playing'))
  })
  load = vi.fn(() => {
    this.generation++
    this.currentTime = 0
    this.readyState = 0
    this.error = null
    this.playbackRate = 1
    this.volume = 0.7
    this.muted = false
    const version = this.generation
    if (this.src && this.autoMetadata)
      queueMicrotask(() => {
        if (version !== this.generation) return
        this.metadata()
      })
  })
  removeAttribute(name: string) {
    if (name === 'src') this.src = ''
  }
  metadata() {
    this.readyState = 1
    this.dispatchEvent(new Event('loadedmetadata'))
    this.dispatchEvent(new Event('canplay'))
  }
  fail(code = 4) {
    this.error = { code }
    this.dispatchEvent(new Event('error'))
  }
}
let settings = { defaultQuality: 'standard', autoSwitchSource: true }
let resolve: (url: URL, init?: RequestInit) => PlayInfo | Response | Promise<PlayInfo | Response>
const requests: { url: URL; init?: RequestInit }[] = []
function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}
function playedRequests() {
  return requests.filter(({ url }) => /\/(play|play-info)$/.test(url.pathname))
}
beforeEach(() => {
  player.stop()
  localStorage.clear()
  // 模块内代际缓存跨用例存在；先切到临时代际再切回，确保重导入测试能读取持久化 marker。
  activateDataIdentity({ generation: 'b'.repeat(32), resetLegacy: true })
  activateDataIdentity({ generation: 'a'.repeat(32), resetLegacy: true })
  authorizePlayOutcomeDelivery('a'.repeat(32))
  settings = { defaultQuality: 'standard', autoSwitchSource: true }
  resolve = (url) => ({
    ...info,
    trackId: decodeURIComponent(url.pathname.split('/').at(-2)!),
    sourceId: 'source-a',
    quality: '128k',
  })
  requests.length = 0
  if (currentAudio) {
    currentAudio.autoMetadata = true
    currentAudio.duration = 90
    currentAudio.play.mockClear()
    currentAudio.pause.mockClear()
    currentAudio.load.mockClear()
  }
  usePlayer.setState({ ...usePlayer.getInitialState() })
  vi.stubGlobal('Audio', FakeAudio)
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://localhost')
      requests.push({ url, init })
      if (url.pathname.endsWith('/settings')) return json(settings)
      if (url.pathname.endsWith('/play'))
        return json({ error: { code: 'wrong_route', message: '播放器只允许使用 play-info' } }, 404)
      if (url.pathname.endsWith('/play-info')) {
        const result = await resolve(url, init)
        return result instanceof Response ? result : json(result)
      }
      return json(url.pathname.endsWith('/history') && init?.method === 'POST' ? { ok: true } : [])
    }),
  )
})
afterEach(() => {
  player.stop()
  authorizePlayOutcomeDelivery(null)
  localStorage.clear()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('媒体页面协议与歌曲合同', () => {
  it('HTTPS页面接受外部HTTPS，HTTP页面接受外部HTTP但不改写协议', () => {
    expect(validatePlayInfo(info, track.id, 'https://nas.example.com')).toBe(info.url)
    const url = 'http://media.example.org/song.mp3'
    expect(validatePlayInfo({ ...info, url }, track.id, 'http://nas.example.com')).toBe(url)
  })
  it('HTTPS页面明确拒绝HTTP混合内容', () => {
    expect(() =>
      validatePlayInfo(
        { ...info, url: 'http://media.example.org/a.mp3' },
        track.id,
        'https://nas.example.com',
      ),
    ).toThrow(MediaPolicyError)
    expect(() =>
      validatePlayInfo(
        { ...info, url: 'http://media.example.org/a.mp3' },
        track.id,
        'https://nas.example.com',
      ),
    ).toThrow('HTTPS')
  })
  it('拒绝代理标记或曲目不匹配，不把解析失败伪装成另一首歌', () => {
    expect(() => validatePlayInfo({ ...info, direct: false }, track.id)).toThrow('歌曲')
    expect(() => validatePlayInfo(info, 'demo:other')).toThrow('歌曲')
  })
  it.each([
    'javascript:alert(1)',
    '/api/audio',
    'https://nas.example.com/audio',
    'https://nas.example.com:4433/audio',
    'https://user:secret@example.org/a.mp3',
    'https://127.0.0.1/a',
    'https://localhost/a',
    'https://[::1]/a',
    'https://10.0.0.2/a',
    'https://172.16.0.1/a',
    'https://192.168.0.1/a',
    'https://169.254.169.254/a',
    'https://100.64.0.1/a',
    'https://[fd00::1]/a',
    'https://[::ffff:127.0.0.1]/a',
    'http://127.0.0.1/a',
    'http://nas.example.com/audio',
  ])('拒绝不安全地址 %s', (url) => {
    expect(() => validatePlayInfo({ ...info, url }, track.id, 'http://nas.example.com')).toThrow()
  })
})

describe('play-info 共享后端合同', () => {
  it('直接请求原 play-info 路由，不先探测 play 别名', async () => {
    await player.play(track)
    expect(playedRequests()).toHaveLength(1)
    expect(playedRequests()[0].url.pathname).toBe('/api/v1/tracks/demo%3Aone/play-info')
  })
  it.each([404, 405, 502])('play-info 返回 %i 后保留错误，不重复请求另一路由', async (status) => {
    resolve = () => json({ error: { code: 'unavailable', message: '解析不可用' } }, status)
    await player.play(track)
    expect(playedRequests()).toHaveLength(1)
    expect(playedRequests()[0].url.pathname).toMatch(/\/play-info$/)
    expect(usePlayer.getState()).toMatchObject({ playing: false, loading: false, error: '解析不可用' })
  })
})

describe('默认音质、倍速与音质重解析', () => {
  it('自动音质不沿用队列中的旧320k；页面origin发送给后端', async () => {
    await player.play({ ...track, qualities: ['320k'] })
    expect(playedRequests()[0].url.searchParams.get('quality')).toBe('standard')
    expect(new Headers(playedRequests()[0].init?.headers).get('X-Melora-Origin')).toBe(window.location.origin)
    expect(usePlayer.getState().playing).toBe(true)
  })
  it('设置默认音质真实参与解析，不因为旧目录未列出而静默回退', async () => {
    settings.defaultQuality = 'flac'
    resolve = () => ({ ...info, quality: 'flac', sourceId: 'source-a' })
    await player.play({ ...track, qualities: [] })
    expect(playedRequests()[0].url.searchParams.get('quality')).toBe('flac')
    expect(usePlayer.getState().resolvedQuality).toBe('flac')
  })
  it('倍速立即写入同一个Audio，重新加载媒体仍保留倍速', async () => {
    await player.play(track)
    const element = currentAudio!
    player.setRate(1.5)
    expect(element.playbackRate).toBe(1.5)
    expect(usePlayer.getState().playbackRate).toBe(1.5)
    await player.setQuality('flac')
    expect(currentAudio).toBe(element)
    expect(element.playbackRate).toBe(1.5)
    player.setRate(Number.NaN)
    player.setRate(8)
    expect(element.playbackRate).toBe(1.5)
  })
  it('音质切换保留暂停与进度，metadata期间不回写零', async () => {
    await player.play(track)
    player.seek(37)
    player.pause()
    currentAudio!.autoMetadata = false
    await player.setQuality('flac')
    expect(playedRequests().at(-1)?.url.searchParams.get('quality')).toBe('flac')
    expect(usePlayer.getState().position).toBe(37)
    expect(usePlayer.getState().playing).toBe(false)
    currentAudio!.dispatchEvent(new Event('timeupdate'))
    expect(usePlayer.getState().position).toBe(37)
    currentAudio!.metadata()
    expect(currentAudio!.currentTime).toBe(37)
    expect(currentAudio!.paused).toBe(true)
  })
  it('正在播放的歌曲切换音质后仍播放且保持当前位置', async () => {
    await player.play(track)
    player.seek(24)
    await player.setQuality('320k')
    expect(currentAudio!.currentTime).toBe(24)
    expect(usePlayer.getState().playing).toBe(true)
  })
  it('音质请求失败恢复原媒体与暂停状态，不标为切换成功', async () => {
    await player.play(track)
    player.seek(19)
    player.pause()
    resolve = () => json({ error: { code: 'unsupported_quality', message: '没有该音质' } }, 422)
    await player.setQuality('flac')
    expect(currentAudio!.src).toBe(info.url)
    expect(currentAudio!.currentTime).toBe(19)
    expect(currentAudio!.paused).toBe(true)
    expect(usePlayer.getState().quality).toBe('standard')
    expect(usePlayer.getState().error).toContain('保留原音质')
  })
  it('只列出已声明/已返回的音质，不凭平台名伪造FLAC', async () => {
    expect(availableQualities({ ...track, qualities: [] })).toEqual(['standard'])
    expect(availableQualities(track, { ...info, resolvedTrack: { ...track, qualities: ['128k'] } })).toEqual([
      'standard',
      '128k',
    ])
    await player.play({ ...track, qualities: ['standard'] })
    const count = playedRequests().length
    await player.setQuality('flac')
    expect(playedRequests()).toHaveLength(count)
  })
})

describe('异步竞态与有界换源', () => {
  it('后返回的旧歌曲请求不能覆盖最新歌曲', async () => {
    let finish!: (value: PlayInfo) => void
    const delayed = new Promise<PlayInfo>((done) => {
      finish = done
    })
    resolve = (url) =>
      decodeURIComponent(url.pathname).includes(track.id)
        ? delayed
        : { ...info, trackId: 'demo:two', url: 'https://media.example.org/two.ogg' }
    const first = player.play(track)
    await vi.waitFor(() => expect(playedRequests()).toHaveLength(1))
    await player.play({ ...track, id: 'demo:two', title: 'Two' })
    finish(info)
    await first
    expect(usePlayer.getState().track?.id).toBe('demo:two')
    expect(currentAudio!.src).toContain('/two.ogg')
  })
  it('取消解析后迟到响应不能开始播放', async () => {
    let finish!: (value: PlayInfo) => void
    resolve = () =>
      new Promise((done) => {
        finish = done
      })
    const task = player.play(track)
    await vi.waitFor(() => expect(playedRequests()).toHaveLength(1))
    player.toggle()
    finish(info)
    await task
    expect(usePlayer.getState().playing).toBe(false)
    expect(usePlayer.getState().loading).toBe(false)
    expect(currentAudio!.src).toBe('')
  })
  it('媒体失败排除已失败源；后端返回新源后仍是原队列歌曲', async () => {
    resolve = (url) => ({
      ...info,
      sourceId: url.searchParams.has('excludeSources') ? 'source-b' : 'source-a',
      attemptedSources: ['source-a'],
      url: url.searchParams.has('excludeSources') ? 'https://media.example.org/b.ogg' : info.url,
    })
    await player.play(track)
    player.seek(32)
    currentAudio!.fail()
    await vi.waitFor(() => expect(usePlayer.getState().sourceId).toBe('source-b'))
    expect(playedRequests().at(-1)?.url.searchParams.get('excludeSources')).toBe('source-a')
    expect(currentAudio!.currentTime).toBe(32)
    expect(usePlayer.getState().track?.id).toBe(track.id)
    expect(usePlayer.getState().queue).toEqual([track])
  })
  it('暂停时自动换源保留进度、倍速与队列，metadata前不归零', async () => {
    resolve = (url) => ({
      ...info,
      sourceId: url.searchParams.has('excludeSources') ? 'source-b' : 'source-a',
      attemptedSources: ['source-a'],
      url: url.searchParams.has('excludeSources') ? 'https://media.example.org/b.ogg' : info.url,
    })
    await player.play(track)
    player.seek(31)
    player.pause()
    player.setRate(1.5)
    currentAudio!.autoMetadata = false
    currentAudio!.fail()
    await vi.waitFor(() => expect(usePlayer.getState().sourceId).toBe('source-b'))
    currentAudio!.dispatchEvent(new Event('timeupdate'))
    expect(usePlayer.getState()).toMatchObject({
      playing: false,
      loading: false,
      position: 31,
      queue: [track],
    })
    currentAudio!.metadata()
    expect(currentAudio!.currentTime).toBe(31)
    expect(currentAudio!.paused).toBe(true)
    expect(currentAudio!.playbackRate).toBe(1.5)
    expect(playedRequests().map(({ url }) => url.pathname)).toEqual([
      '/api/v1/tracks/demo%3Aone/play-info',
      '/api/v1/tracks/demo%3Aone/play-info',
    ])
    expect(playedRequests()[1].url.searchParams.get('excludeSources')).toBe('source-a')
  })
  it('换源请求失败后不退回未排除失败源的请求', async () => {
    await player.play(track)
    resolve = () => json({ error: { code: 'unavailable', message: '没有其它可用源' } }, 404)
    currentAudio!.fail()
    await vi.waitFor(() => expect(usePlayer.getState().error).toBe('没有其它可用源'))
    expect(playedRequests()).toHaveLength(2)
    expect(playedRequests()[1].url.pathname).toMatch(/\/play-info$/)
    expect(playedRequests()[1].url.searchParams.get('excludeSources')).toBe('source-a')
  })
  it('源重复返回或耗尽时停止，不无限自动重试', async () => {
    await player.play(track)
    currentAudio!.fail()
    await vi.waitFor(() => expect(usePlayer.getState().error).toContain('未切换成功'))
    expect(playedRequests()).toHaveLength(2)
    expect(usePlayer.getState().playing).toBe(false)
    currentAudio!.fail()
    expect(playedRequests()).toHaveLength(2)
  })
  it('旧响应缺少sourceId时最多通过play-info重新解析一次', async () => {
    settings.autoSwitchSource = false
    resolve = () => info
    await player.play(track)
    currentAudio!.fail()
    await vi.waitFor(() =>
      expect(playedRequests().filter(({ url }) => url.pathname.endsWith('/play-info'))).toHaveLength(2),
    )
    await vi.waitFor(() => expect(usePlayer.getState().playing).toBe(true))
    currentAudio!.fail()
    await vi.waitFor(() => expect(usePlayer.getState().error).toBeTruthy())
    expect(playedRequests().filter(({ url }) => url.pathname.endsWith('/play-info'))).toHaveLength(2)
  })
  it('自动换源关闭时不发送excludeSources', async () => {
    settings.autoSwitchSource = false
    await player.play(track)
    currentAudio!.fail()
    await vi.waitFor(() => expect(playedRequests()).toHaveLength(2))
    expect(playedRequests()[1].url.searchParams.has('excludeSources')).toBe(false)
  })
  it('HTTPS页面遇到HTTP媒体尝试其它源，而不是篡改为HTTPS', async () => {
    vi.stubGlobal('window', { location: new URL('https://nas.example.com') })
    resolve = (url) => ({
      ...info,
      sourceId: url.searchParams.has('excludeSources') ? 'https-source' : 'http-source',
      url: url.searchParams.has('excludeSources') ? info.url : 'http://media.example.org/insecure.mp3',
    })
    await player.play(track)
    expect(playedRequests()).toHaveLength(2)
    expect(playedRequests()[1].url.searchParams.get('excludeSources')).toBe('http-source')
    expect(currentAudio!.src).toBe(info.url)
  })
  it('HTTPS页面关闭自动换源时直接拒绝HTTP，既不播放也不绕过协议校验', async () => {
    vi.stubGlobal('window', { location: new URL('https://nas.example.com') })
    settings.autoSwitchSource = false
    resolve = () => ({ ...info, sourceId: 'http-source', url: 'http://media.example.org/a.mp3' })
    await player.play(track)
    expect(playedRequests()).toHaveLength(1)
    expect(currentAudio!.src).toBe('')
    expect(usePlayer.getState()).toMatchObject({ playing: false, loading: false })
    expect(usePlayer.getState().error).toContain('HTTPS')
  })
  it('HTTP页面允许HTTP媒体原样使用；不会强制升级协议', async () => {
    vi.stubGlobal('window', { location: new URL('http://nas.example.com') })
    resolve = () => ({ ...info, url: 'http://media.example.org/a.mp3' })
    await player.play(track)
    expect(currentAudio!.src).toBe('http://media.example.org/a.mp3')
  })
})

describe('队列与基本控制', () => {
  it('循环模式稳定切换', () => {
    player.cycleMode()
    expect(usePlayer.getState().mode).toBe('single')
    player.cycleMode()
    expect(usePlayer.getState().mode).toBe('shuffle')
    player.cycleMode()
    expect(usePlayer.getState().mode).toBe('list')
  })
  it('清空队列保留当前，移出待播不影响播放', () => {
    usePlayer.setState({ track, queue: [track, { ...track, id: 'demo:two' }] })
    player.remove(track.id)
    expect(usePlayer.getState().queue).toHaveLength(2)
    player.remove('demo:two')
    expect(usePlayer.getState().queue).toEqual([track])
    player.clearQueue()
    expect(usePlayer.getState().queue).toEqual([track])
  })
  it('切歌复用Audio，上一首先回到开头，音量有界', async () => {
    await player.play(track, [track, { ...track, id: 'demo:two' }])
    const instance = currentAudio
    player.seek(12)
    player.previous()
    expect(usePlayer.getState().position).toBe(0)
    player.volume(2)
    expect(currentAudio!.volume).toBe(1)
    player.volume(-1)
    expect(currentAudio!.volume).toBe(0)
    player.next()
    await vi.waitFor(() => expect(usePlayer.getState().track?.id).toBe('demo:two'))
    expect(currentAudio).toBe(instance)
  })
})

it('解析中拖动进度不会被迟到的初始位置覆盖', async () => {
  let finish!: (value: PlayInfo) => void
  resolve = () =>
    new Promise((done) => {
      finish = done
    })
  const task = player.play(track)
  await vi.waitFor(() => expect(playedRequests()).toHaveLength(1))
  player.seek(26)
  expect(usePlayer.getState().position).toBe(26)
  finish(info)
  await task
  expect(currentAudio!.currentTime).toBe(26)
})

it('浏览器拒绝自动播放后点击继续复用已有URL，不再次解析失去用户手势', async () => {
  // 先初始化单例，再模拟浏览器一次性自动播放限制。
  player.setRate(1)
  currentAudio!.play.mockRejectedValueOnce(new DOMException('blocked', 'NotAllowedError'))
  await player.play(track)
  expect(usePlayer.getState().error).toContain('点击播放')
  const count = playedRequests().length
  await player.retry()
  expect(playedRequests()).toHaveLength(count)
  expect(usePlayer.getState().playing).toBe(true)
})

it('连续不同源媒体失败最多自动恢复三次', async () => {
  let nextSource = 0
  resolve = () => ({
    ...info,
    sourceId: `source-${++nextSource}`,
    url: `https://media.example.org/${nextSource}.ogg`,
  })
  await player.play(track)
  for (let expected = 2; expected <= 4; expected++) {
    currentAudio!.fail()
    await vi.waitFor(() => expect(usePlayer.getState().sourceId).toBe(`source-${expected}`))
  }
  currentAudio!.fail()
  expect(usePlayer.getState().error).toContain('仍无法播放')
  expect(playedRequests()).toHaveLength(4)
})

it('解析超时结束loading，用户不会永久困在取消加载按钮', async () => {
  vi.useFakeTimers()
  resolve = (_url, init) =>
    new Promise((_done, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), {
        once: true,
      })
    })
  const task = player.play(track)
  await vi.waitFor(() => expect(playedRequests()).toHaveLength(1))
  await vi.advanceTimersByTimeAsync(15_000)
  await task
  expect(usePlayer.getState().loading).toBe(false)
  expect(usePlayer.getState().playing).toBe(false)
  expect(usePlayer.getState().error).toContain('解析超时')
})

describe('v7 解析诊断与Promise拒绝边界', () => {
  it('后端已做三次换源的502显示实际尝试数，不重复整个解析链', async () => {
    resolve = () =>
      new Response(JSON.stringify({ error: { code: 'lx_resolve_failed', message: 'LX音源解析失败' } }), {
        status: 502,
        headers: {
          'Content-Type': 'application/json',
          'X-Melora-Resolve-Stage': 'resolve',
          'X-Melora-Resolve-Attempts': '3',
          'X-Melora-Auto-Switch': 'true',
        },
      })
    await expect(player.play(track)).resolves.toBeUndefined()
    expect(playedRequests()).toHaveLength(1)
    expect(usePlayer.getState().error).toContain('已尝试 3 个音源')
    expect(usePlayer.getState().error).toContain('自动换源已开启')
  })
  it('目录失败且尚未调用脚本时不声称已经换源', async () => {
    resolve = () =>
      new Response(JSON.stringify({ error: { code: 'catalog_unavailable', message: '目录暂不可用' } }), {
        status: 502,
        headers: {
          'Content-Type': 'application/json',
          'X-Melora-Resolve-Stage': 'catalog',
          'X-Melora-Resolve-Attempts': '0',
        },
      })
    await player.play(track)
    expect(usePlayer.getState().error).toContain('尚未进入音源解析')
    expect(playedRequests()).toHaveLength(1)
  })
  it('Audio准备阶段抛出undefined也转为可见错误，不向点击方泄露拒绝', async () => {
    await player.play(track)
    currentAudio!.load.mockImplementationOnce(() => {
      throw undefined
    })
    await expect(player.play(track)).resolves.toBeUndefined()
    expect(usePlayer.getState()).toMatchObject({ playing: false, loading: false })
    expect(usePlayer.getState().error).toContain('播放器初始化失败')
  })
  it('fetch拒绝undefined时错误被处理，不伪造播放成功', async () => {
    resolve = () => Promise.reject(undefined)
    await expect(player.play(track)).resolves.toBeUndefined()
    expect(usePlayer.getState()).toMatchObject({ playing: false, loading: false })
    expect(usePlayer.getState().error).toContain('无法连接服务')
  })
  it('Audio.play拒绝undefined时有限换源，而不是未处理Promise', async () => {
    player.volume(0.7)
    currentAudio!.play.mockRejectedValueOnce(undefined)
    resolve = (url) => ({
      ...info,
      sourceId: url.searchParams.has('excludeSources') ? 'source-b' : 'source-a',
      quality: '128k',
    })
    await expect(player.play(track)).resolves.toBeUndefined()
    await vi.waitFor(() =>
      expect(usePlayer.getState()).toMatchObject({
        playing: true,
        loading: false,
        sourceId: 'source-b',
        error: null,
      }),
    )
    expect(playedRequests()).toHaveLength(2)
    expect(playedRequests()[1].url.searchParams.get('excludeSources')).toContain('source-a')
  })
})

it('切换音质失败后恢复旧Audio再次抛出undefined也不会漏出拒绝', async () => {
  await player.play(track)
  resolve = () => json({ error: { code: 'lx_resolve_failed', message: '解析失败' } }, 502)
  currentAudio!.load
    .mockImplementationOnce(() => {})
    .mockImplementationOnce(() => {
      throw undefined
    })
  await expect(player.setQuality('flac')).resolves.toBeUndefined()
  expect(usePlayer.getState()).toMatchObject({ playing: false, loading: false, recovering: false })
  expect(usePlayer.getState().error).toContain('播放器无法恢复媒体')
})

it('pagehide 同步持久化播放结局并在下次可用时补发', async () => {
  let now = 10_000
  const nowSpy = vi.spyOn(Date, 'now').mockImplementation(() => now)
  try {
    await player.play(track)
    await vi.waitFor(() => expect(usePlayer.getState().historyRecorded).toBe(true))
    now += 2_500
    window.dispatchEvent(new Event('pagehide'))
    expect(requests.filter(({ url }) => url.pathname.endsWith('/outcome'))).toHaveLength(0)
    expect(pendingPlayOutcomesForTest()).toEqual([
      expect.objectContaining({ trackId: track.id, playedMs: 2_500, completed: false }),
    ])

    await flushPendingPlayOutcomes()
    const outcomes = requests.filter(({ url }) => url.pathname.endsWith('/outcome'))
    expect(outcomes).toHaveLength(1)
    expect(JSON.parse(String(outcomes[0]!.init?.body))).toEqual({
      eventId: expect.any(String),
      dataGeneration: 'a'.repeat(32),
      playedMs: 2_500,
      completed: false,
    })
    expect(pendingPlayOutcomesForTest()).toHaveLength(0)
  } finally {
    nowSpy.mockRestore()
  }
})

describe('v10 本设备会话与真实模块重导入', () => {
  it('模块重建同步恢复队列/曲目/进度/偏好但不创建Audio或请求，用户播放重新解析且不重复历史', async () => {
    await player.play(track, [track, { ...track, id: 'demo:two' }])
    player.seek(37)
    player.volume(0.26)
    player.setRate(1.5)
    player.cycleMode()
    await player.setQuality('flac')
    const previousAudio = currentAudio
    const originalRequests = playedRequests().length
    const historyCount = () =>
      requests.filter(({ url, init }) => url.pathname.endsWith('/history') && init?.method === 'POST').length
    const originalHistory = historyCount()
    const originalAudio = currentAudio
    vi.resetModules()
    const fresh = await import('./player')
    try {
      expect(fresh.usePlayer).not.toBe(usePlayer)
      expect(fresh.usePlayer.getState()).toMatchObject({
        track,
        queue: [track, { ...track, id: 'demo:two' }],
        position: 37,
        volume: 0.26,
        playbackRate: 1.5,
        mode: 'single',
        quality: 'flac',
        playing: false,
        loading: false,
        recovering: false,
        error: null,
        sourceId: null,
        resolvedTrack: null,
        resolvedQuality: null,
        attemptedSources: [],
      })
      expect(currentAudio).toBe(previousAudio)
      expect(playedRequests()).toHaveLength(originalRequests)
      expect(historyCount()).toBe(originalHistory)
      // 恢复后尚无媒体，也能拖动；首个用户播放才取新地址。
      fresh.player.seek(39)
      resolve = () => ({
        ...info,
        url: 'https://media.example.org/new-session.ogg',
        sourceId: 'fresh-source',
      })
      fresh.player.resume()
      await vi.waitFor(() => expect(fresh.usePlayer.getState().playing).toBe(true))
      expect(currentAudio).not.toBe(previousAudio)
      expect(currentAudio).toMatchObject({
        currentTime: 39,
        playbackRate: 1.5,
        volume: 0.26,
        muted: false,
        src: 'https://media.example.org/new-session.ogg',
      })
      expect(playedRequests()).toHaveLength(originalRequests + 1)
      expect(historyCount()).toBe(originalHistory)
      resolve = () => ({ ...info, trackId: 'demo:two', sourceId: 'fresh-source' })
      await fresh.player.play({ ...track, id: 'demo:two' })
      // 显式重新选歌仍按原行为记录，不把整个新会话永久标为已记录。
      expect(fresh.usePlayer.getState().playing).toBe(true)
      expect(historyCount()).toBe(originalHistory + 1)
    } finally {
      fresh.player.stop()
      currentAudio = originalAudio
    }
  })
  it('刷新保留静音和静音前音量，取消静音/换源时恢复速率、音量与暂停进度', async () => {
    await player.play(track)
    player.seek(24)
    player.volume(0.38)
    player.volume(0)
    player.setRate(1.25)
    player.pause()
    const originalAudio = currentAudio
    vi.resetModules()
    const fresh = await import('./player')
    try {
      expect(fresh.usePlayer.getState()).toMatchObject({
        volume: 0,
        muted: true,
        volumeBeforeMute: 0.38,
        position: 24,
        playing: false,
      })
      fresh.player.resume()
      await vi.waitFor(() => expect(fresh.usePlayer.getState().playing).toBe(true))
      expect(currentAudio).toMatchObject({ currentTime: 24, playbackRate: 1.25, volume: 0, muted: true })
      fresh.player.toggleMute()
      expect(currentAudio).toMatchObject({ volume: 0.38, muted: false })
      fresh.player.pause()
      resolve = () => ({ ...info, sourceId: 'source-b', url: 'https://media.example.org/b.ogg' })
      currentAudio!.fail()
      await vi.waitFor(() => expect(fresh.usePlayer.getState().sourceId).toBe('source-b'))
      expect(currentAudio).toMatchObject({
        currentTime: 24,
        playbackRate: 1.25,
        volume: 0.38,
        muted: false,
        paused: true,
      })
      expect(readPlayerSession()).toMatchObject({
        position: 24,
        playbackRate: 1.25,
        volume: 0.38,
        muted: false,
      })
    } finally {
      fresh.player.stop()
      currentAudio = originalAudio
    }
  })
  it('没成功播放的恢复曲目首次用户播放仍提交一条历史', async () => {
    resolve = () => json({ error: { code: 'unavailable', message: '解析失败' } }, 502)
    await player.play(track)
    const originalAudio = currentAudio
    vi.resetModules()
    const fresh = await import('./player')
    try {
      expect(fresh.usePlayer.getState()).toMatchObject({
        track,
        playing: false,
        error: null,
        historyRecorded: false,
      })
      resolve = () => ({ ...info, sourceId: 'source-a' })
      fresh.player.resume()
      await vi.waitFor(() => expect(fresh.usePlayer.getState().playing).toBe(true))
      expect(
        requests.filter(({ url, init }) => url.pathname.endsWith('/history') && init?.method === 'POST'),
      ).toHaveLength(1)
    } finally {
      fresh.player.stop()
      currentAudio = originalAudio
    }
  })
  it('暂停立刻落盘；路由切换保留会话；显式清理不删除服务器历史', async () => {
    await player.play(track)
    player.setRate(1.5)
    player.volume(0.4)
    currentAudio!.currentTime = 28
    currentAudio!.dispatchEvent(new Event('timeupdate'))
    player.pause()
    expect(readPlayerSession().position).toBe(28)
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(usePlayer.getState().track).toEqual(track)
    player.clearSession()
    window.dispatchEvent(new Event('pagehide'))
    expect(Object.values(PLAYER_SESSION_KEYS).map((key) => localStorage.getItem(key))).toEqual([null, null])
    expect(usePlayer.getState()).toMatchObject({
      track: null,
      queue: [],
      playing: false,
      playbackRate: 1,
      volume: 0.7,
    })
    expect(requests.some(({ init }) => init?.method === 'DELETE')).toBe(false)
  })
})

it('v10 隐私清理遇到 Audio.load 抛 undefined 仍销毁缓存与内存', async () => {
  await player.play(track)
  currentAudio!.load.mockImplementationOnce(() => {
    throw undefined
  })
  expect(() => player.clearSession()).not.toThrow()
  expect(usePlayer.getState()).toMatchObject({
    track: null,
    queue: [],
    playing: false,
    historyRecorded: false,
  })
  expect(Object.values(PLAYER_SESSION_KEYS).map((key) => localStorage.getItem(key))).toEqual([null, null])
})

it('v10 页面存储禁用时重新导入模块及后续用户播放仍可用', async () => {
  const originalAudio = currentAudio
  vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
    throw undefined
  })
  vi.resetModules()
  const fresh = await import('./player')
  try {
    expect(fresh.usePlayer.getState()).toMatchObject({ track: null, queue: [], playing: false, volume: 0.7 })
    await expect(fresh.player.play(track)).resolves.toBeUndefined()
    expect(fresh.usePlayer.getState().playing).toBe(true)
    fresh.player.seek(19)
    fresh.player.volume(0.25)
    fresh.player.setRate(1.5)
    expect(fresh.usePlayer.getState()).toMatchObject({ position: 19, volume: 0.25, playbackRate: 1.5 })
    expect(() => fresh.player.clearSession()).not.toThrow()
  } finally {
    fresh.player.stop()
    currentAudio = originalAudio
    vi.restoreAllMocks()
  }
})

it('数据换代停止旧Audio并恢复新代际快照，清理过程和旧进度定时器不能覆盖新快照', async () => {
  const originalAudio = currentAudio
  vi.resetModules()
  const identity = await import('../lib/data-identity')
  const fresh = await import('./player')
  const a = 'a'.repeat(32),
    b = 'b'.repeat(32)
  try {
    identity.activateDataIdentity({ generation: a, resetLegacy: false })
    await fresh.player.play(track)
    const oldAudio = currentAudio!
    vi.useFakeTimers()
    fresh.usePlayer.setState({ position: 23 })
    identity.activateDataIdentity({ generation: b, resetLegacy: true })
    const next = { ...track, id: 'demo:new-data', title: 'New data' }
    const queue = JSON.stringify({ version: 1, track: next, queue: [next] })
    const playback = JSON.stringify({
      version: 1,
      trackId: next.id,
      position: 9,
      duration: 90,
      volume: 0.4,
      playbackRate: 1.5,
      quality: '128k',
      mode: 'single',
    })
    identity.writeClientData(identity.CLIENT_DATA_KEYS.queue, queue)
    identity.writeClientData(identity.CLIENT_DATA_KEYS.playback, playback)
    fresh.player.restoreDataSession()
    expect(oldAudio.paused).toBe(true)
    expect(oldAudio.src).toBe('')
    expect(fresh.usePlayer.getState()).toMatchObject({
      track: next,
      queue: [next],
      position: 9,
      playing: false,
      loading: false,
      volume: 0.4,
      playbackRate: 1.5,
      requestedQuality: '128k',
    })
    expect(oldAudio.volume).toBe(0.4)
    expect(oldAudio.playbackRate).toBe(1.5)
    vi.advanceTimersByTime(10000)
    window.dispatchEvent(new Event('pagehide'))
    expect(identity.readClientData(identity.CLIENT_DATA_KEYS.queue)).toBe(queue)
    expect(identity.readClientData(identity.CLIENT_DATA_KEYS.playback)).toBe(playback)
  } finally {
    fresh.player.stop()
    currentAudio = originalAudio
    vi.useRealTimers()
  }
})

it('旧解析请求迟到不能在新数据库建立后重新播放旧曲目', async () => {
  const originalAudio = currentAudio
  vi.resetModules()
  const identity = await import('../lib/data-identity')
  const fresh = await import('./player')
  let release!: (value: PlayInfo) => void
  resolve = () =>
    new Promise<PlayInfo>((done) => {
      release = done
    })
  try {
    identity.activateDataIdentity({ generation: 'a'.repeat(32), resetLegacy: false })
    const pending = fresh.player.play(track)
    await vi.waitFor(() => expect(release).toBeTypeOf('function'))
    identity.activateDataIdentity({ generation: 'b'.repeat(32), resetLegacy: true })
    fresh.player.restoreDataSession()
    release(info)
    await pending
    expect(fresh.usePlayer.getState()).toMatchObject({
      track: null,
      queue: [],
      playing: false,
      loading: false,
      volume: 0.7,
    })
    expect(identity.readClientData(identity.CLIENT_DATA_KEYS.queue)).toBeNull()
  } finally {
    fresh.player.stop()
    currentAudio = originalAudio
  }
})

it('播放结局等待对应历史写入成功后再上报', async () => {
  let releaseHistory!: () => void
  let historySaved = false
  let outcomeBeforeHistory = false
  vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    requests.push({ url, init })
    if (url.pathname.endsWith('/settings')) return json(settings)
    if (url.pathname.endsWith('/play'))
      return json({ error: { code: 'wrong_route', message: '播放器只允许使用 play-info' } }, 404)
    if (url.pathname.endsWith('/play-info')) {
      const result = await resolve(url, init)
      return result instanceof Response ? result : json(result)
    }
    if (url.pathname.endsWith('/history') && init?.method === 'POST')
      return new Promise<Response>((done) => {
        releaseHistory = () => {
          historySaved = true
          done(json({ ok: true }))
        }
      })
    if (url.pathname.endsWith('/outcome') && init?.method === 'POST') {
      outcomeBeforeHistory = !historySaved
      return json({ ok: true })
    }
    return json([])
  })

  await player.play(track)
  await vi.waitFor(() => expect(releaseHistory).toBeTypeOf('function'))
  usePlayer.setState({ queue: [] })
  currentAudio!.dispatchEvent(new Event('ended'))
  await Promise.resolve()
  expect(requests.filter(({ url }) => url.pathname.endsWith('/outcome'))).toHaveLength(0)
  releaseHistory()
  await vi.waitFor(() =>
    expect(requests.filter(({ url }) => url.pathname.endsWith('/outcome'))).toHaveLength(1),
  )
  expect(outcomeBeforeHistory).toBe(false)
})

it('清空历史会丢弃当前会话的旧统计，不在结束时复活记录', async () => {
  await player.play(track)
  await vi.waitFor(() => expect(usePlayer.getState().historyRecorded).toBe(true))
  player.historyCleared()
  expect(usePlayer.getState().historyRecorded).toBe(false)
  usePlayer.setState({ queue: [] })
  currentAudio!.dispatchEvent(new Event('ended'))
  await Promise.resolve()
  expect(requests.filter(({ url }) => url.pathname.endsWith('/outcome'))).toHaveLength(0)
  expect(pendingPlayOutcomesForTest()).toHaveLength(0)
})

it('历史请求迟到时不会把旧代际 outcome 写入新数据库', async () => {
  let releaseHistory!: () => void
  vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    requests.push({ url, init })
    if (url.pathname.endsWith('/settings')) return json(settings)
    if (url.pathname.endsWith('/play-info')) return json(info)
    if (url.pathname.endsWith('/history') && init?.method === 'POST')
      return new Promise<Response>((done) => {
        releaseHistory = () => done(json({ ok: true }))
      })
    return json({ ok: true })
  })

  await player.play(track)
  await vi.waitFor(() => expect(releaseHistory).toBeTypeOf('function'))
  usePlayer.setState({ queue: [] })
  currentAudio!.dispatchEvent(new Event('ended'))
  activateDataIdentity({ generation: 'b'.repeat(32), resetLegacy: true })
  authorizePlayOutcomeDelivery('b'.repeat(32))
  releaseHistory()
  await Promise.resolve()
  await Promise.resolve()

  expect(requests.filter(({ url }) => url.pathname.endsWith('/outcome'))).toHaveLength(0)
  expect(pendingPlayOutcomesForTest()).toHaveLength(0)
})

it('恢复的暂停会话重新播放后仍会上报结局', async () => {
  await player.play(track)
  await vi.waitFor(() =>
    expect(
      requests.some(({ url, init }) => url.pathname.endsWith('/history') && init?.method === 'POST'),
    ).toBe(true),
  )
  player.pause()
  const originalAudio = currentAudio
  requests.length = 0
  vi.resetModules()
  const fresh = await import('./player')
  const freshOutcome = await import('../lib/play-outcome')
  freshOutcome.authorizePlayOutcomeDelivery('a'.repeat(32))
  try {
    fresh.player.resume()
    await vi.waitFor(() => expect(fresh.usePlayer.getState().playing).toBe(true))
    fresh.usePlayer.setState({ queue: [] })
    currentAudio!.dispatchEvent(new Event('ended'))
    await vi.waitFor(() =>
      expect(requests.filter(({ url }) => url.pathname.endsWith('/outcome'))).toHaveLength(1),
    )
    expect(
      requests.filter(({ url, init }) => url.pathname.endsWith('/history') && init?.method === 'POST'),
    ).toHaveLength(0)
  } finally {
    fresh.player.stop()
    currentAudio = originalAudio
  }
})

it('普通单曲播放会清除旧集合上下文，即使曲目 ID 相同', async () => {
  await player.playCollection('wy:playlist_1', [track])
  await vi.waitFor(() =>
    expect(requests.filter(({ url }) => url.pathname.endsWith('/history'))).toHaveLength(1),
  )
  requests.length = 0
  await player.play(track, [track])
  await vi.waitFor(() =>
    expect(requests.filter(({ url }) => url.pathname.endsWith('/history'))).toHaveLength(1),
  )
  const historyRequest = requests.find(({ url }) => url.pathname.endsWith('/history'))!
  expect(JSON.parse(String(historyRequest.init?.body))).toEqual({ trackId: track.id, kind: 'track' })
  expect(usePlayingCollection.getState()).toMatchObject({ collectionId: null, trackIds: [] })
})

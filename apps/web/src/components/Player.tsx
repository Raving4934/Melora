import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import {
  Check,
  ChevronDown,
  Download,
  Image as ImageIcon,
  ListMusic,
  LoaderCircle,
  Mic2,
  Music2,
  Pause,
  Play,
  Repeat,
  Repeat1,
  RotateCcw,
  Shuffle,
  SkipBack,
  SkipForward,
  SlidersHorizontal,
  Volume2,
  VolumeX,
  X,
} from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { playbackRates, player, usePlayer } from '../stores/player'
import { useUI } from '../stores/ui'
import { useAPI, idPath, useCloudDeploy } from '../lib/api'
import { assetURL } from '../lib/base'
import { normalizeCoverURL } from '../lib/cover'
import { AmbientCanvas } from './AmbientCanvas'
import {
  lyricFontOptions,
  lyricFontScale,
  resetLyricFont,
  setLyricFont,
  useLyricFontPreference,
  type LyricFontSize,
} from '../lib/lyric-font'
import { formatTime, qualityName } from '../lib/format'
import type { Lyrics } from '../lib/types'
import { Cover, EmptyState, IconButton, Modal } from './UI'
import { ScrollEdgeControls } from './ScrollEdgeControls'
import { FavoriteButton } from './TrackList'
import './Player.css'

const TimeReadout = memo(function TimeReadout() {
  const position = usePlayer((state) => Math.floor(state.position))
  const duration = usePlayer((state) => Math.floor(state.duration))
  return (
    <span className="player-time" aria-label={`播放时间 ${formatTime(position)} / ${formatTime(duration)}`}>
      {formatTime(position)}
      <span className="player-time-divider" aria-hidden="true">
        /
      </span>
      {formatTime(duration)}
    </span>
  )
})

export function PlaybackControls({ large = false }: { large?: boolean }) {
  const playing = usePlayer((state) => state.playing)
  const loading = usePlayer((state) => state.loading)
  const hasTrack = usePlayer((state) => !!state.track)
  return (
    <div className={`player-transport ${large ? 'large' : ''}`}>
      <IconButton
        label="上一首"
        className="player-previous"
        disabled={!hasTrack}
        onClick={() => player.previous()}
      >
        <SkipBack size={large ? 28 : 22} fill="currentColor" />
      </IconButton>
      <IconButton
        label={loading ? '取消加载' : playing ? '暂停' : '播放'}
        className="main-play"
        onClick={() => player.toggle()}
      >
        {loading ? (
          <LoaderCircle className="player-spinner" size={large ? 30 : 23} />
        ) : playing ? (
          <Pause size={large ? 30 : 23} fill="currentColor" />
        ) : (
          <Play size={large ? 30 : 23} fill="currentColor" />
        )}
      </IconButton>
      <IconButton className="player-next" label="下一首" disabled={!hasTrack} onClick={() => player.next()}>
        <SkipForward size={large ? 28 : 22} fill="currentColor" />
      </IconButton>
    </div>
  )
}
function ModeButton() {
  const mode = usePlayer((state) => state.mode)
  return (
    <IconButton
      label={{ list: '列表循环', single: '单曲循环', shuffle: '随机播放' }[mode]}
      onClick={() => player.cycleMode()}
    >
      {mode === 'shuffle' ? (
        <Shuffle size={21} />
      ) : mode === 'single' ? (
        <Repeat1 size={21} />
      ) : (
        <Repeat size={21} />
      )}
    </IconButton>
  )
}
export function SeekBar({ mini = false }: { mini?: boolean }) {
  const position = usePlayer((state) => state.position)
  const duration = usePlayer((state) => state.duration)
  const safeDuration = Number.isFinite(duration) && duration > 0 ? duration : 0
  const safePosition = Number.isFinite(position) ? Math.max(0, Math.min(position, safeDuration)) : 0
  const progress = safeDuration ? (safePosition / safeDuration) * 100 : 0
  return (
    <div className={mini ? 'player-mini-progress' : 'player-seek'}>
      <input
        type="range"
        className="player-progress-input"
        aria-label="播放进度"
        aria-valuetext={`${formatTime(safePosition)} / ${formatTime(safeDuration)}`}
        min="0"
        max={safeDuration || 1}
        step="0.1"
        value={safePosition}
        disabled={!safeDuration}
        onChange={(event) => player.seek(Number(event.target.value))}
        style={{ '--progress': `${progress}%` } as CSSProperties}
      />
      {!mini && (
        <div className="player-seek-times">
          <span>{formatTime(safePosition)}</span>
          <span>{formatTime(safeDuration)}</span>
        </div>
      )}
    </div>
  )
}
function VolumeControl({ showRange = true }: { showRange?: boolean }) {
  const volume = usePlayer((state) => state.volume)
  const isMuted = usePlayer((state) => state.muted || state.volume === 0)
  return (
    <div className="player-volume">
      <IconButton label={isMuted ? '取消静音' : '静音'} onClick={() => player.toggleMute()}>
        {isMuted ? <VolumeX size={19} /> : <Volume2 size={19} />}
      </IconButton>
      {showRange && (
        <input
          type="range"
          min="0"
          max="1"
          step="0.01"
          className="player-volume-input"
          aria-label="音量"
          value={volume}
          style={{ '--volume-progress': `${(isMuted ? 0 : volume) * 100}%` } as CSSProperties}
          onChange={(event) => player.volume(Number(event.target.value))}
        />
      )}
    </div>
  )
}
function LyricFontOptions() {
  const preference = useLyricFontPreference()
  return (
    <fieldset className="player-option-group player-lyric-font-group">
      <legend>歌词字号</legend>
      <div className="player-lyric-font-options">
        {lyricFontOptions.map((option) => (
          <button
            key={option.value}
            type="button"
            aria-pressed={preference.size === option.value}
            onClick={() => setLyricFont(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
      <div className="player-lyric-font-meta">
        <span role="status">
          {preference.persisted ? '仅调整歌词，保存在此浏览器。' : '无法保存到此浏览器，仅本次有效。'}
        </span>
        <button type="button" className="text-button" onClick={resetLyricFont} aria-label="恢复默认歌词字号">
          恢复默认
        </button>
      </div>
    </fieldset>
  )
}
function PlaybackOptions({ onClose, immersive = false }: { onClose: () => void; immersive?: boolean }) {
  const rate = usePlayer((state) => state.playbackRate)
  const quality = usePlayer((state) => state.requestedQuality)
  const qualities = usePlayer((state) => state.availableQualities)
  const loading = usePlayer((state) => state.loading)
  const hasTrack = usePlayer((state) => !!state.track)
  const resolvedQuality = usePlayer((state) => state.resolvedQuality)
  return (
    <Modal
      className={`player-options-modal player-dark-dialog ${immersive ? 'is-immersive' : 'is-docked'}`}
      title="播放设置"
      onClose={onClose}
    >
      <div className="player-settings-rows">
        <label className="player-setting-row">
          <span className="player-setting-copy">
            <strong>播放速度</strong>
          </span>
          <select
            aria-label="播放速度"
            value={rate}
            onChange={(event) => player.setRate(Number(event.target.value))}
          >
            {playbackRates.map((value) => (
              <option key={value} value={value}>
                {Number.isInteger(value) ? value.toFixed(1) : value}×
              </option>
            ))}
          </select>
        </label>
        <label className="player-setting-row player-quality-field">
          <span className="player-setting-copy">
            <strong>播放音质</strong>
            <small>
              {resolvedQuality
                ? `自动音质由当前音源协商提供，当前为 ${qualityName(resolvedQuality)}。`
                : '自动音质由当前音源协商提供。'}
            </small>
          </span>
          <select
            aria-label="播放音质"
            value={qualities.includes(quality) ? quality : 'standard'}
            disabled={!hasTrack || loading}
            onChange={(event) => void player.setQuality(event.target.value)}
          >
            {qualities.map((value) => (
              <option key={value} value={value}>
                {qualityName(value)}
              </option>
            ))}
          </select>
        </label>
        <div className="player-setting-row player-option-volume">
          <span className="player-setting-copy">
            <strong>音量</strong>
          </span>
          <VolumeControl />
        </div>
      </div>
      <LyricFontOptions />
    </Modal>
  )
}
function PlayerFeedback() {
  const error = usePlayer((state) => state.error)
  const recovering = usePlayer((state) => state.recovering)
  return (
    <div className="player-feedback">
      {error ? (
        <div role="alert">
          <span title={error}>{error}</span>
          <button type="button" onClick={() => void player.retry()}>
            重试
          </button>
        </div>
      ) : recovering ? (
        <span role="status">
          <LoaderCircle className="player-spinner" size={13} />
          正在尝试其他可用音源…
        </span>
      ) : null}
    </div>
  )
}

export function MiniPlayer() {
  const track = usePlayer((state) => state.track)
  const error = usePlayer((state) => state.error)
  const navigate = useNavigate()
  const [options, setOptions] = useState(false)
  return (
    <>
      <footer className="mini-player player-mini" aria-label="音乐播放器">
        <button
          className="mini-track player-mini-track"
          type="button"
          onClick={() => navigate('/now-playing')}
          aria-label="打开全屏播放器"
          title={track ? `${track.title} · ${track.artist}` : '请选择歌曲'}
        >
          {track ? (
            <Cover src={track.coverUrl} />
          ) : (
            <span className="cover player-mini-placeholder" aria-hidden="true">
              <Music2 size={20} />
            </span>
          )}
          <span className="player-mini-identity">
            <strong>{track?.title || '未播放'}</strong>
            <span className="player-mini-meta">
              <span className="player-mini-artist">{track?.artist || '请选择歌曲'}</span>
              <span className="player-mini-meta-divider" aria-hidden="true">
                •
              </span>
              <TimeReadout />
            </span>
          </span>
        </button>
        <div className="player-mini-controls">
          <PlaybackControls />
        </div>
        <div className="player-mini-tools">
          <span className="player-mini-favorite">{track && <FavoriteButton track={track} />}</span>
          <span className="player-mini-volume">
            <VolumeControl />
          </span>
          <IconButton className="player-mini-settings" label="播放设置" onClick={() => setOptions(true)}>
            <SlidersHorizontal size={19} />
          </IconButton>
          <IconButton label="播放队列" onClick={() => useUI.getState().setDrawer('queue')}>
            <ListMusic size={21} />
          </IconButton>
        </div>
        {error && (
          <button
            className="player-mini-error"
            type="button"
            title={error}
            aria-label={`播放失败：${error}，点击重试`}
            onClick={() => void player.retry()}
          >
            重试
          </button>
        )}
        <SeekBar mini />
      </footer>
      {options && <PlaybackOptions onClose={() => setOptions(false)} />}
    </>
  )
}

type LyricLine = Lyrics['lines'][number]
function activeLyric(lines: LyricLine[], position: number) {
  let low = 0
  let high = lines.length - 1
  let result = -1
  while (low <= high) {
    const mid = (low + high) >>> 1
    if (lines[mid].time <= position) {
      result = mid
      low = mid + 1
    } else high = mid - 1
  }
  return result
}
function CompactLyrics({
  lines,
  pending,
  failed,
  onOpen,
}: {
  lines: LyricLine[]
  pending: boolean
  failed: boolean
  onOpen: () => void
}) {
  const active = usePlayer((state) => activeLyric(lines, state.position))
  const center = Math.max(0, active)
  return (
    <button className="player-compact-lyrics" type="button" aria-label="显示完整歌词" onClick={onOpen}>
      {lines.length ? (
        [-4, -3, -2, -1, 0, 1, 2, 3, 4].map((offset) => {
          const isCurrent = center + offset === active
          const dist = Math.abs(offset)
          const className = isCurrent
            ? 'current'
            : dist === 1
              ? 'near'
              : dist === 2
                ? 'mid'
                : dist === 3
                  ? 'far'
                  : 'outer'
          return (
            <span
              key={offset}
              className={className}
              aria-hidden={center + offset < 0 || center + offset >= lines.length}
            >
              {lines[center + offset]?.text || '\u00a0'}
            </span>
          )
        })
      ) : (
        <>
          <span className="outer" aria-hidden="true">
            &nbsp;
          </span>
          <span className="far" aria-hidden="true">
            &nbsp;
          </span>
          <span className="mid" aria-hidden="true">
            &nbsp;
          </span>
          <span className="near" aria-hidden="true">
            &nbsp;
          </span>
          <span className="current">{pending ? '正在读取歌词…' : failed ? '歌词暂不可用' : '暂无歌词'}</span>
          <span className="near" aria-hidden="true">
            &nbsp;
          </span>
          <span className="mid" aria-hidden="true">
            &nbsp;
          </span>
          <span className="far" aria-hidden="true">
            &nbsp;
          </span>
          <span className="outer" aria-hidden="true">
            &nbsp;
          </span>
        </>
      )}
    </button>
  )
}
function LyricsPanel({
  lines,
  pending,
  error,
  retry,
  visible,
  trackId,
  fontSize,
}: {
  lines: LyricLine[]
  pending: boolean
  error: Error | null
  retry: () => unknown
  visible: boolean
  trackId: string
  fontSize: LyricFontSize
}) {
  const active = usePlayer((state) => activeLyric(lines, state.position))
  const scroll = useRef<HTMLDivElement>(null)
  const current = useRef<HTMLButtonElement>(null)
  const [following, setFollowing] = useState(true)
  const alignImmediately = useRef(true)
  useLayoutEffect(() => {
    alignImmediately.current = true
    setFollowing(true)
  }, [trackId, fontSize])
  useLayoutEffect(() => {
    if (visible) alignImmediately.current = true
    setFollowing(true)
  }, [visible])
  useLayoutEffect(() => {
    const container = scroll.current
    if (!container) return
    // 首个时间戳之前也展示首行；不伪造 aria-current 或播放进度。
    const line = current.current || container.querySelector<HTMLButtonElement>('.player-lyric-line')
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    const align = (immediate: boolean) => {
      if (!container.clientHeight || !line?.offsetHeight) {
        alignImmediately.current = true
        return
      }
      if (!following) return
      const lineBox = line.getBoundingClientRect()
      const top =
        container.scrollTop +
        lineBox.top -
        container.getBoundingClientRect().top -
        (container.clientHeight - lineBox.height) / 2
      container.scrollTo?.({
        top: Math.max(0, top),
        behavior: immediate || reduced ? 'instant' : 'smooth',
      })
      alignImmediately.current = false
    }
    align(alignImmediately.current)
    // 只在实际几何改变时重新对齐；不将尺寸写进 React state，也不订阅每帧播放时间。
    const dimensions = () => `${container.clientWidth}:${container.clientHeight}:${line?.offsetHeight}`
    let previous = dimensions()
    const onResize = () => {
      const next = dimensions()
      if (next === previous) return
      previous = next
      align(true)
    }
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(onResize)
    observer?.observe(container)
    if (line) observer?.observe(line)
    if (!observer) window.addEventListener('resize', onResize)
    return () => {
      observer?.disconnect()
      if (!observer) window.removeEventListener('resize', onResize)
    }
  }, [active, following, visible, lines, fontSize])
  return (
    <section className="player-lyrics-panel" aria-label="歌词" aria-busy={pending}>
      {error ? (
        <div className="player-lyrics-empty" role="alert">
          <h2>歌词暂不可用</h2>
          <p>{error.message}</p>
          <button type="button" className="player-inline-action" onClick={() => void retry()}>
            重新读取
          </button>
        </div>
      ) : !lines.length ? (
        <div className="player-lyrics-empty">
          <Music2 size={26} aria-hidden="true" />
          <h2>{pending ? '正在读取歌词…' : '暂无歌词'}</h2>
          {!pending && <p>音源未提供同步歌词。</p>}
        </div>
      ) : (
        <>
          <div
            className="player-lyrics-scroll"
            ref={scroll}
            onWheel={() => setFollowing(false)}
            onTouchMove={() => setFollowing(false)}
            onKeyDown={(event) => {
              const container = event.currentTarget
              const top = {
                ArrowUp: container.scrollTop - 44,
                ArrowDown: container.scrollTop + 44,
                PageUp: container.scrollTop - container.clientHeight * 0.8,
                PageDown: container.scrollTop + container.clientHeight * 0.8,
                Home: 0,
                End: container.scrollHeight,
              }[event.key]
              if (top === undefined) return
              // 避免浏览器的 PageUp/PageDown 滚动动画在点击“恢复跟随”后继续覆盖定位。
              event.preventDefault()
              setFollowing(false)
              container.scrollTo?.({ top, behavior: 'instant' })
            }}
            tabIndex={0}
            aria-label="完整歌词，可点击跳转播放进度"
          >
            {lines.map((line, index) => (
              <button
                key={`${line.time}-${index}`}
                ref={index === active ? current : undefined}
                type="button"
                className={`player-lyric-line ${index === active ? 'current' : ''}`}
                aria-current={index === active ? 'true' : undefined}
                onClick={() => {
                  player.seek(line.time)
                  setFollowing(true)
                }}
              >
                {line.text}
              </button>
            ))}
          </div>
          {!following && (
            <button className="player-follow-lyrics" type="button" onClick={() => setFollowing(true)}>
              <RotateCcw size={14} />
              回到当前歌词
            </button>
          )}
        </>
      )}
    </section>
  )
}

export function NowPlaying() {
  const lyricFont = useLyricFontPreference()
  const cloud = useCloudDeploy()
  const track = usePlayer((state) => state.track)
  const resolvedTrack = usePlayer((state) => state.resolvedTrack)
  const rate = usePlayer((state) => state.playbackRate)
  const quality = usePlayer((state) => state.requestedQuality)
  const qualities = usePlayer((state) => state.availableQualities)
  const loading = usePlayer((state) => state.loading)
  const playing = usePlayer((state) => state.playing)
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const fullLyrics = params.get('view') === 'lyrics'
  const atmosphereCover = normalizeCoverURL(track?.coverUrl)
  const toggleLyrics = () => setParams(fullLyrics ? {} : { view: 'lyrics' }, { replace: true })
  const [options, setOptions] = useState(false)
  const [qualityMenuOpen, setQualityMenuOpen] = useState(false)
  const [rateMenuOpen, setRateMenuOpen] = useState(false)
  const qualityMenuRef = useRef<HTMLDivElement>(null)
  const rateMenuRef = useRef<HTMLDivElement>(null)
  const pageRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!qualityMenuOpen && !rateMenuOpen) return
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as Node
      if (qualityMenuRef.current && !qualityMenuRef.current.contains(target)) {
        setQualityMenuOpen(false)
      }
      if (rateMenuRef.current && !rateMenuRef.current.contains(target)) {
        setRateMenuOpen(false)
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setQualityMenuOpen(false)
        setRateMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [qualityMenuOpen, rateMenuOpen])

  const lyricId = resolvedTrack?.id || track?.id || '_'
  const lyrics = useAPI<Lyrics>(`/tracks/${idPath(lyricId)}/lyrics`, !!track)
  const lines = useMemo(() => {
    const received = lyrics.data?.lines
    // TypeScript不校验响应的运行时结构；坏歌词只能影响歌词区，不能卸载整个播放器。
    if (lyrics.isPlaceholderData || !Array.isArray(received)) return []
    return received
      .filter(
        (line) =>
          line !== null &&
          typeof line === 'object' &&
          Number.isFinite(line.time) &&
          line.time >= 0 &&
          typeof line.text === 'string' &&
          line.text.trim(),
      )
      .slice(0, 3000)
      .sort((a, b) => a.time - b.time)
  }, [lyrics.data, lyrics.isPlaceholderData])
  return (
    <div
      ref={pageRef}
      className="now-playing-page player-immersive"
      data-view={fullLyrics ? 'lyrics' : 'cover'}
      data-embedded={window.self !== window.top}
      data-lyric-font={lyricFont.size}
      style={{ '--player-lyric-scale': lyricFontScale(lyricFont.size) } as CSSProperties}
    >
      <AmbientCanvas
        cover={atmosphereCover ? assetURL(atmosphereCover) : undefined}
        trackKey={track?.id}
        playing={playing}
      />
      <header className="player-immersive-header">
        <IconButton
          label="收起播放器"
          onClick={() => (window.history.state?.idx > 0 ? navigate(-1) : navigate('/'))}
        >
          <ChevronDown size={26} />
        </IconButton>
        <div className="player-track-heading">
          <h1 title={track?.title}>{track?.title || '未播放'}</h1>
          <p title={track?.artist}>{track?.artist || '请选择歌曲'}</p>
        </div>
        <div className="player-header-actions">
          <IconButton label="播放设置" onClick={() => setOptions(true)}>
            <SlidersHorizontal size={22} />
          </IconButton>
        </div>
      </header>
      <main className={`player-stage${!track ? ' is-empty' : ''}`}>
        {!track ? (
          <section className="player-empty">
            <EmptyState
              title="未播放"
              description="请选择歌曲"
              icon={<Music2 size={32} />}
              action={
                <Link className="player-inline-action" to="/">
                  浏览排行榜
                </Link>
              }
            />
          </section>
        ) : (
          <>
            <section className="player-art-panel" aria-label="歌曲封面与信息">
              <button
                className="player-album-button"
                type="button"
                aria-label="点击封面显示完整歌词"
                onClick={toggleLyrics}
              >
                <Cover
                  className="player-album-cover"
                  src={track.coverUrl}
                  title={track.album ? `${track.album} 封面` : `${track.title} 封面`}
                  loading="eager"
                  fetchPriority="high"
                />
              </button>
              <div className="player-deck-meta">
                <h1 title={track.title}>{track.title}</h1>
                <p title={track.artist}>{track.artist || '未知歌手'}</p>
              </div>
              <CompactLyrics
                lines={lines}
                pending={lyrics.isPending || lyrics.isPlaceholderData}
                failed={!!lyrics.error}
                onOpen={toggleLyrics}
              />
            </section>
            <LyricsPanel
              lines={lines}
              pending={lyrics.isPending || lyrics.isPlaceholderData}
              error={lyrics.error}
              retry={lyrics.refetch}
              visible={fullLyrics}
              trackId={lyricId}
              fontSize={lyricFont.size}
            />
          </>
        )}
        <section className="player-deck-controls" aria-label="播放控制台">
          <PlayerFeedback />
          <SeekBar />
          <div className="player-deck-transport">
            <ModeButton />
            <PlaybackControls large />
            {track ? (
              <FavoriteButton track={track} size={21} />
            ) : (
              <span className="player-control-placeholder" aria-hidden="true" />
            )}
          </div>
        </section>
      </main>
      <footer className="player-immersive-footer" aria-label="播放辅助工具">
        <div className="player-bottom-tools">
          <div className="player-tool-select-wrapper" ref={rateMenuRef}>
            <button
              type="button"
              className={`player-tool-select player-rate-select ${rateMenuOpen ? 'is-active' : ''}`}
              title="播放速度"
              aria-label={`播放倍速：${Number.isInteger(rate) ? rate.toFixed(1) : rate}×`}
              onClick={() => {
                setRateMenuOpen((open) => !open)
                setQualityMenuOpen(false)
              }}
              aria-haspopup="menu"
              aria-expanded={rateMenuOpen}
            >
              <span aria-hidden="true">{Number.isInteger(rate) ? rate.toFixed(1) : rate}×</span>
            </button>
            <select
              aria-label="播放速度"
              value={rate}
              className="player-tool-native-select"
              tabIndex={-1}
              onChange={(event) => player.setRate(Number(event.target.value))}
            >
              {playbackRates.map((value) => (
                <option key={value} value={value}>
                  {value}×
                </option>
              ))}
            </select>
            {rateMenuOpen && (
              <div className="player-tool-popover" role="menu" aria-label="播放倍速">
                <div className="player-tool-popover-header">播放倍速</div>
                <div className="player-tool-popover-list">
                  {playbackRates.map((value) => (
                    <button
                      key={value}
                      type="button"
                      role="menuitem"
                      className={`player-tool-popover-item ${rate === value ? 'active' : ''}`}
                      onClick={() => {
                        player.setRate(value)
                        setRateMenuOpen(false)
                      }}
                    >
                      <span>{Number.isInteger(value) ? value.toFixed(1) : value}×</span>
                      {rate === value && <Check size={15} className="popover-check" />}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
          <div className="player-tool-select-wrapper" ref={qualityMenuRef}>
            <button
              type="button"
              className={`player-tool-select player-quality-select ${qualityMenuOpen ? 'is-active' : ''}`}
              title={`播放音质：${qualityName(quality)}`}
              aria-label={`音质切换：${qualityName(quality)}`}
              disabled={!track || loading}
              data-disabled={!track || loading}
              onClick={() => {
                if (!track || loading) return
                setQualityMenuOpen((open) => !open)
                setRateMenuOpen(false)
              }}
              aria-haspopup="menu"
              aria-expanded={qualityMenuOpen}
            >
              <span aria-hidden="true">
                {quality === 'standard' ? '自动' : quality === 'flac24bit' ? 'Hi-Res' : quality.toUpperCase()}
              </span>
            </button>
            <select
              aria-label="播放音质"
              value={qualities.includes(quality) ? quality : 'standard'}
              disabled={!track || loading}
              className="player-tool-native-select"
              tabIndex={-1}
              onChange={(event) => void player.setQuality(event.target.value)}
            >
              {qualities.map((value) => (
                <option key={value} value={value}>
                  {qualityName(value)}
                </option>
              ))}
            </select>
            {qualityMenuOpen && (
              <div className="player-tool-popover player-quality-popover" role="menu" aria-label="播放音质">
                <div className="player-tool-popover-header">播放音质</div>
                <div className="player-tool-popover-list">
                  {qualities.map((value) => (
                    <button
                      key={value}
                      type="button"
                      role="menuitem"
                      className={`player-tool-popover-item ${quality === value ? 'active' : ''}`}
                      onClick={() => {
                        void player.setQuality(value)
                        setQualityMenuOpen(false)
                      }}
                    >
                      <span className="popover-quality-name">{qualityName(value)}</span>
                      {quality === value && <Check size={15} className="popover-check" />}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
          <IconButton
            label={fullLyrics ? '显示封面' : '显示完整歌词'}
            aria-pressed={fullLyrics}
            disabled={!track}
            onClick={toggleLyrics}
          >
            {fullLyrics ? <ImageIcon size={20} /> : <Mic2 size={20} />}
          </IconButton>
          <div className="player-dock-volume">
            <VolumeControl />
          </div>
          <IconButton label="播放队列" onClick={() => useUI.getState().setDrawer('queue')}>
            <ListMusic size={23} />
          </IconButton>
          {!cloud && (
            <IconButton
              label="下载当前歌曲"
              disabled={!track?.canDownload}
              onClick={() => track && useUI.getState().openDownload(track)}
            >
              <Download size={21} />
            </IconButton>
          )}
        </div>
      </footer>
      {options && <PlaybackOptions immersive onClose={() => setOptions(false)} />}
      <ScrollEdgeControls target={pageRef} className="scroll-edge-controls--immersive" />
    </div>
  )
}

export function PlayerDrawer() {
  const drawer = useUI((state) => state.drawer)
  const queue = usePlayer((state) => state.queue)
  const track = usePlayer((state) => state.track)
  const navigate = useNavigate()
  const close = () => useUI.getState().setDrawer(null)
  useEffect(() => {
    // 兼容其它旧入口，但歌词只出现在沉浸播放器，不再存在歌词侧栏。
    if (drawer === 'lyrics') {
      useUI.getState().setDrawer(null)
      navigate('/now-playing?view=lyrics')
    }
  }, [drawer, navigate])
  if (drawer !== 'queue') return null
  return (
    <Modal className="drawer player-queue" title={`播放队列 · ${queue.length}`} onClose={close}>
      <div className="drawer-subheading">
        <span>{queue.length} 首歌曲</span>
        <button className="text-button" type="button" onClick={() => player.clearQueue()}>
          清空待播
        </button>
      </div>
      {!queue.length ? (
        <EmptyState title="播放队列为空" description="播放歌曲或将歌曲加入队列。" />
      ) : (
        <div className="queue-list">
          {queue.map((item) => (
            <div className={`queue-item ${item.id === track?.id ? 'active' : ''}`} key={item.id}>
              <button
                type="button"
                onClick={() => void player.play(item, queue, { preserveCollection: true })}
              >
                <Cover src={item.coverUrl} />
                <span>
                  <strong>{item.title}</strong>
                  <small>{item.artist}</small>
                </span>
                {item.id === track?.id && (
                  <span className="queue-current">
                    <span className="queue-current-wave" aria-hidden="true">
                      <span />
                      <span />
                      <span />
                    </span>
                    当前
                  </span>
                )}
              </button>
              {item.id !== track?.id && (
                <IconButton label={`移出队列 ${item.title}`} onClick={() => player.remove(item.id)}>
                  <X size={16} />
                </IconButton>
              )}
            </div>
          ))}
        </div>
      )}
    </Modal>
  )
}

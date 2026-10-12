package com.leyu.melora.playback

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * IsolatedTestRunner + 正式 renderer factory + 真实 ExoPlayer；不启动服务、Activity 或用户 Application。
 * 只读取测试 APK 中的合成音频，并使用独占 cache 子目录；音量仅设在此播放器上，不改系统音量。
 * 原始 position 按约 5ms 采样；跳变/停滞/主线程采样间隔只记 Log，不设帧耗时或可听声门禁。
 */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class AudioSchedulingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Test(timeout = 90_000L)
    fun shortBlockVorbisSamplesRawPositionAndWordTimelineWithoutRewinding() = withPlayer { fixture ->
        prepare(fixture, listOf(VORBIS))
        onMain { fixture.player.play() }
        awaitPlaying(fixture, 0)

        val samples = sample(fixture, "vorbis-short-blocks", 3_000L)
        assertForwardLyrics(samples)
        assertTrue("真实 position 必须推进，而不是只推进歌词插值", samples.last().positionMs > samples.first().positionMs)
        assertTrue("逐字高亮必须随实际播放推进", samples.last().lyric.wordOrdinal > samples.first().lyric.wordOrdinal)
    }

    @Test(timeout = 90_000L)
    fun pauseResumeBidirectionalSeekAndSpeedKeepConcertAndAllowLyricRewind() = withPlayer { fixture ->
        prepare(fixture, listOf(VORBIS))
        onMain { fixture.player.play() }
        awaitPlaying(fixture, 0)
        assertForwardLyrics(sample(fixture, "before-pause", 600L))

        onMain { fixture.player.pause() }
        await(fixture, "暂停") { !fixture.player.playWhenReady && !fixture.player.isPlaying }
        val paused = sample(fixture, "paused", 350L)
        assertTrue("暂停期间不能自行恢复", paused.none { it.playing })
        // 暂停边界允许 AudioTrack 的少量尾部校时；这不是渲染帧耗时门禁。
        assertTrue("暂停后实际位置应保持稳定", paused.maxOf { it.positionMs } - paused.minOf { it.positionMs } <= 250L)
        assertEquals("暂停后逐字歌词不能继续插值", paused.first().lyric.positionMs, paused.last().lyric.positionMs)
        val resumePosition = paused.last().positionMs
        onMain { fixture.player.play() }
        await(fixture, "从暂停位置恢复") {
            fixture.player.isPlaying && fixture.player.currentPosition > resumePosition + 100L && concertApplied(fixture)
        }
        assertForwardLyrics(sample(fixture, "resumed", 400L))

        onMain { fixture.player.pause() }
        await(fixture, "再次暂停") { !fixture.player.isPlaying && !fixture.player.playWhenReady }
        val beforeSeek = capture(fixture)
        seekPaused(fixture, 6_500L)
        val forward = capture(fixture)
        assertTrue("前向 seek 必须来自真实不连续事件", forward.lyric.reset)
        assertTrue("前向 seek 应推进歌词", forward.lyric.wordOrdinal > beforeSeek.lyric.wordOrdinal)
        seekPaused(fixture, 1_500L)
        val backward = capture(fixture)
        assertTrue("后向 seek 必须重新锚定，不能被连续播放下界钳住", backward.lyric.reset)
        assertTrue("后向 seek 应允许歌词位置倒退", backward.lyric.positionMs < forward.lyric.positionMs)
        assertTrue("后向 seek 应允许歌词焦点回到前一行", backward.lyric.focusIndex < forward.lyric.focusIndex)
        assertTrue("后向 seek 应允许逐字高亮回退", backward.lyric.wordOrdinal < forward.lyric.wordOrdinal)
        assertConcertApplied(backward.effect, "后向 seek")

        onMain { fixture.player.play() }
        // seek后的isPlaying/position>=100只表示已准备，不能把AudioTrack起播等待当成稳态倍速采样。
        await(fixture, "后向seek后播放头真正推进") {
            fixture.player.isPlaying && fixture.player.currentPosition > backward.positionMs + 100L && concertApplied(fixture)
        }
        for (speed in listOf(1.5f, 0.75f, 1f)) {
            val reportsBeforeChange = fixture.effects.size
            onMain { fixture.player.setPlaybackSpeed(speed) }
            // 参数回调先于Sonic重配；旧Applied不能证明这次flush已结束，必须等本轮的新回报。
            await(fixture, "倍速 $speed 的PCM重配完成") {
                fixture.player.isPlaying && fixture.player.playbackParameters.speed == speed &&
                    fixture.effects.drop(reportsBeforeChange).any { it.phase == AudioEffects.Phase.Applied } &&
                    concertApplied(fixture)
            }
            val samples = sample(fixture, "speed-$speed", 700L)
            assertForwardLyrics(samples)
            assertTrue("倍速 $speed 下实际 position 必须继续推进", samples.last().positionMs > samples.first().positionMs + 100L)
        }
    }

    @Test(timeout = 90_000L)
    fun mp3FlacQueueManualAndAutomaticTransitionsKeepSoftwareConcertApplied() = withPlayer { fixture ->
        val transitions = CopyOnWriteArrayList<Pair<String?, Int>>()
        onMain {
            fixture.player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    transitions.add(mediaItem?.mediaId to reason)
                }
            })
        }
        prepare(fixture, listOf(MP3, FLAC, MP3))
        onMain { fixture.player.play() }
        awaitPlaying(fixture, 0)
        assertEquals(MimeTypes.AUDIO_MPEG, onMain { fixture.player.audioFormat?.sampleMimeType })
        assertForwardLyrics(sample(fixture, "mp3-first", 650L))

        onMain { fixture.player.seekToNextMediaItem() }
        awaitPlaying(fixture, 1)
        assertEquals(MimeTypes.AUDIO_FLAC, onMain { fixture.player.audioFormat?.sampleMimeType })
        assertForwardLyrics(sample(fixture, "flac-after-next", 650L))

        // 真正播放过 FLAC 尾部，检验自然换曲，而不是用 seekTo(index, ...) 伪装换曲。
        onMain { fixture.player.seekTo(fixture.player.duration - 300L) }
        awaitPlaying(fixture, 2)
        assertTrue("必须发生 FLAC -> MP3 自然换曲", transitions.any {
            it.first == MP3 && it.second == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        })
        assertEquals(MimeTypes.AUDIO_MPEG, onMain { fixture.player.audioFormat?.sampleMimeType })
        assertForwardLyrics(sample(fixture, "mp3-after-auto", 650L))

        onMain { fixture.player.seekToPreviousMediaItem() }
        awaitPlaying(fixture, 1)
        assertEquals(MimeTypes.AUDIO_FLAC, onMain { fixture.player.audioFormat?.sampleMimeType })
        assertForwardLyrics(sample(fixture, "flac-after-previous", 650L))
        Log.i(TAG, "queue transitions=$transitions")
    }

    private fun withPlayer(test: (Fixture) -> Unit) {
        assertEquals("必须使用 IsolatedTestRunner，不能启动正式用户 Application", Application::class.java, context.applicationContext.javaClass)
        val cache = File(context.cacheDir, "audio-scheduling-test-${System.nanoTime()}")
        var player: ExoPlayer? = null
        try {
            assertTrue("不能创建独立夹具目录", cache.mkdirs())
            val files = listOf(VORBIS, MP3, FLAC).associateWith { name ->
                File(cache, name).also { file ->
                    instrumentation.context.assets.open("audio/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            val effects = CopyOnWriteArrayList<AudioEffects.State>()
            val fixture = onMain {
                val processor = PcmEffectsAudioProcessor(
                    initialPreset = AudioEffects.preset(CONCERT),
                    // 只收集本测试处理器的回报，不写 AudioEffects 的全局状态或用户设置。
                    report = { effects.add(it) },
                )
                val created = ExoPlayer.Builder(context, PlaybackRenderersFactory(context, processor))
                    .experimentalSetDynamicSchedulingEnabled(true)
                    .setLoadControl(playbackLoadControl())
                    .build()
                player = created
                created.volume = 0f
                val lyrics = LyricProbe(created)
                created.addListener(lyrics)
                Fixture(created, files, effects, lyrics)
            }
            test(fixture)
            onMain { assertNull("播放不能留下异步错误", fixture.player.playerError) }
            assertFalse("软件音效不能报告失败: $effects", effects.any { it.phase == AudioEffects.Phase.Failed })
            assertTrue("seek/换曲不能丢失 concert 选择", effects.isNotEmpty() && effects.all { it.presetId == CONCERT })
        } finally {
            try {
                onMain { player?.release() }
            } finally {
                assertTrue("必须删除测试自有 cache 目录: $cache", cache.deleteRecursively())
            }
        }
    }

    private fun prepare(fixture: Fixture, names: List<String>) {
        onMain {
            fixture.player.setMediaItems(names.map { name ->
                MediaItem.Builder().setMediaId(name).setUri(Uri.fromFile(fixture.files.getValue(name))).build()
            })
            fixture.player.prepare()
        }
        await(fixture, "准备本地合成音频") {
            fixture.player.playbackState == Player.STATE_READY && fixture.player.duration >= 11_000L
        }
    }

    private fun awaitPlaying(fixture: Fixture, index: Int) = await(fixture, "播放队列索引 $index") {
        fixture.player.currentMediaItemIndex == index && fixture.player.isPlaying &&
            fixture.player.currentPosition >= 100L && concertApplied(fixture)
    }

    private fun seekPaused(fixture: Fixture, positionMs: Long) {
        onMain { fixture.player.seekTo(positionMs) }
        await(fixture, "暂停 seek 到 $positionMs") {
            !fixture.player.playWhenReady && !fixture.player.isPlaying &&
                fixture.player.playbackState == Player.STATE_READY &&
                abs(fixture.player.currentPosition - positionMs) <= 500L && concertApplied(fixture)
        }
    }

    private fun concertApplied(fixture: Fixture): Boolean = fixture.effects.lastOrNull()?.let {
        it.presetId == CONCERT && it.phase == AudioEffects.Phase.Applied
    } == true

    private fun assertConcertApplied(state: AudioEffects.State?, label: String) {
        assertEquals("$label: concert 选择必须保留", CONCERT, state?.presetId)
        assertEquals("$label: 稳态 PCM 软件音效必须持续 Applied", AudioEffects.Phase.Applied, state?.phase)
    }

    private fun capture(fixture: Fixture): Sample = onMain {
        assertNull("真实 ExoPlayer 播放出错", fixture.player.playerError)
        assertFalse("PCM 软件音效失败: ${fixture.effects}", fixture.effects.any { it.phase == AudioEffects.Phase.Failed })
        val nowMs = SystemClock.elapsedRealtime()
        val positionMs = fixture.player.currentPosition // 原值，不插值、钳制、四舍五入或扣除估算延迟。
        Sample(nowMs, positionMs, fixture.player.isPlaying, fixture.lyrics.at(nowMs, positionMs), fixture.effects.lastOrNull())
    }

    private fun sample(fixture: Fixture, label: String, windowMs: Long): List<Sample> {
        val deadline = SystemClock.elapsedRealtime() + windowMs
        val samples = mutableListOf<Sample>()
        // 不在主线程 sleep；每次主线程读取也有独立超时。实际采样间隔随主机调度，统计而不门禁。
        do {
            val next = capture(fixture)
            assertConcertApplied(next.effect, label)
            samples.add(next)
            Thread.sleep(SAMPLE_INTERVAL_MS)
        } while (samples.size < 2 || SystemClock.elapsedRealtime() < deadline)
        logRawSamplingStatistics(label, samples)
        return samples
    }

    // 这只验证正式歌词链路不回退，绝不作为原始 position 更新间隔已改善的证明。
    private fun assertForwardLyrics(samples: List<Sample>) {
        for ((previous, next) in samples.zipWithNext()) {
            // Seek/换曲可回退；暂停/重新缓冲会重新锚定。仅对连续实际播放段要求不倒退。
            if (previous.playing && next.playing && !next.lyric.reset) {
                assertTrue("连续播放歌词采样不能倒退: $previous -> $next", next.lyric.positionMs >= previous.lyric.positionMs)
                assertTrue("连续播放歌词行不能倒退", next.lyric.focusIndex >= previous.lyric.focusIndex)
                assertTrue("连续播放逐字高亮不能倒退", next.lyric.wordOrdinal >= previous.lyric.wordOrdinal)
            }
        }
    }

    private fun logRawSamplingStatistics(label: String, samples: List<Sample>) {
        val steps = samples.zipWithNext()
        val gaps = steps.map { (before, after) -> after.realtimeMs - before.realtimeMs }.sorted()
        val jumps = steps.map { (before, after) -> after.positionMs - before.positionMs }
        val forwardJumps = jumps.filter { it > 0L }.sorted()
        val lyricAhead = samples.map { it.lyric.positionMs - it.positionMs }.sorted()
        var lastChangeMs = samples.first().realtimeMs
        var maxUnchangedMs = 0L
        for ((before, after) in steps) {
            maxUnchangedMs = maxOf(maxUnchangedMs, after.realtimeMs - lastChangeMs)
            if (after.positionMs != before.positionMs) lastChangeMs = after.realtimeMs
        }
        Log.i(TAG, "$label: samples=${samples.size}, requestedIntervalMs=$SAMPLE_INTERVAL_MS, " +
            "sampleGapP95Ms=${gaps[(gaps.lastIndex * 95) / 100]}, sampleGapMaxMs=${gaps.last()}, " +
            "rawStartMs=${samples.first().positionMs}, rawEndMs=${samples.last().positionMs}, " +
            "rawUpdates=${jumps.count { it != 0L }}, rawForwardSteps=${forwardJumps.size}, " +
            "rawJumpMaxMs=${jumps.maxOrNull()}, rawForwardJumpP95Ms=${forwardJumps.getOrNull(((forwardJumps.size - 1) * 95) / 100)}, " +
            "unchangedSamples=${jumps.count { it == 0L }}, rawBackwardSamples=${jumps.count { it < 0L }}, " +
            "rawMaxObservedStallMs=$maxUnchangedMs, lyricPublicationIntervalMs=$LYRIC_PUBLISH_INTERVAL_MS, " +
            "lyricAheadMinMs=${lyricAhead.first()}, lyricAheadP95Ms=${lyricAhead[(lyricAhead.lastIndex * 95) / 100]}, " +
            "lyricAheadMaxMs=${lyricAhead.last()}")
    }

    private fun await(fixture: Fixture, label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (!onMain {
                assertNull("$label: ExoPlayer 出错", fixture.player.playerError)
                assertFalse("$label: PCM 软件音效失败", fixture.effects.any { it.phase == AudioEffects.Phase.Failed })
                predicate()
            }) {
            assertTrue("$label: Media3 状态等待超时", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10L)
        }
    }

    /** 和现有测试一样只在主线程操作播放器，但主线程调用本身也不能无限等待。 */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(Callable { block() })
        check(mainHandler.post(task)) { "主线程不能接收测试操作" }
        try {
            return task.get(5L, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            mainHandler.removeCallbacks(task)
            task.cancel(false)
        }
    }

    private data class Fixture(
        val player: ExoPlayer,
        val files: Map<String, File>,
        val effects: List<AudioEffects.State>,
        val lyrics: LyricProbe,
    )

    private data class Sample(
        val realtimeMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        val lyric: LyricSample,
        val effect: AudioEffects.State?,
    )

    private data class LyricSample(val positionMs: Long, val focusIndex: Int, val wordOrdinal: Double, val reset: Boolean)

    /** 复用正式歌词校时/事件表/逐字函数；只由真实 ExoPlayer 的不连续事件解锁回退。 */
    private class LyricProbe(private val player: ExoPlayer) : Player.Listener {
        private val timeline = LyricTimeline((0 until 12).map { line ->
            val startMs = line * 1_000L
            LyricLine(startMs, "甲乙丙丁", endMs = startMs + 1_000L, words = (0 until 4).map { word ->
                LyricWord("甲乙丙丁"[word].toString(), startMs + word * 250L, startMs + (word + 1) * 250L)
            })
        })
        private var state = PlayerUiState()
        private var anchorMs = Long.MIN_VALUE
        private var resetPending = true

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            resetPending = true
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resetPending = true
        }

        fun at(nowMs: Long, rawPositionMs: Long): LyricSample {
            val reset = resetPending
            val buffering = player.playbackState == Player.STATE_BUFFERING
            val speed = player.playbackParameters.speed
            // 模拟正式的 500ms 位置发布，采样之间交给正式 lyricPositionAt 插值，不做测试专用平滑。
            if (reset || anchorMs == Long.MIN_VALUE || nowMs - anchorMs >= LYRIC_PUBLISH_INTERVAL_MS ||
                state.positionAdvancing != player.isPlaying || state.buffering != buffering || state.speed != speed) {
                state = state.samplePosition(rawPositionMs, nowMs, player.isPlaying, player.duration, reset)
                    .copy(playing = player.isPlaying, buffering = buffering, speed = speed)
                anchorMs = nowMs
                resetPending = false
            }
            val positionMs = lyricPositionAt(state, nowMs)
            val frame = timeline.at(positionMs)
            val words = timeline.lines.getOrNull(frame.focusIndex)?.words.orEmpty()
            val ordinal = frame.focusIndex * 4.0 + words.sumOf { lyricWordProgress(it, positionMs).toDouble() }
            return LyricSample(positionMs, frame.focusIndex, ordinal, reset)
        }
    }

    companion object {
        private const val TAG = "AudioSchedulingTest"
        private const val CONCERT = "concert"
        private const val VORBIS = "scheduling-short-blocks.ogg"
        private const val MP3 = "scheduling-12s.mp3"
        private const val FLAC = "scheduling-12s-24.flac"
        private const val SAMPLE_INTERVAL_MS = 5L
        private const val LYRIC_PUBLISH_INTERVAL_MS = 500L
        private const val WAIT_TIMEOUT_MS = 8_000L
    }
}

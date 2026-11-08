package com.leyu.melora.playback

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 隔离播放器和合成 PCM：验证选轨后零 sample 的真实故障，也验证零波形并不等于无输出。 */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class AudioOutputRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 45_000L)
    fun selectedTrackWithoutSamplesRaisesRealErrorAndRecoversSameResourceAtCurrentPosition() = withPlayer { fixture ->
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "output-${System.nanoTime()}")
            .put("name", "Output fixture").put("singer", "Fixture Artist"))
        val track = UiTrack.fromOnline(song)
        val resourceId = "lx:fixture:output-${System.nanoTime()}"
        val attempts = AtomicInteger()
        withController(fixture, track) { controller ->
            val failingThenHealthy = source(track.uid, 20_000L, attempts, emptyFirst = true, resourceId = resourceId)
            val following = source("next", 20_000L)
            main {
                fixture.player.setMediaSources(listOf(failingThenHealthy, following), 0, 2_000L)
                fixture.player.prepare()
                fixture.player.play()
            }
            await(fixture, "无 sample 的已选中音轨进入播放") {
                fixture.player.isPlaying && fixture.player.currentPosition >= 2_500L &&
                    fixture.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            }
            main { assertTrue("夹具须保留已知时长，不能退化为零时长文件", fixture.player.duration >= 19_000L) }
            await(fixture, "输出检测必须触发真实 Media3 错误", 12_000L) { fixture.failures.isNotEmpty() }
            val failure = fixture.failures.single()
            assertEquals(ERROR_CODE_AUDIO_OUTPUT_STALLED, failure.code)
            assertEquals("错误回调前应已进入 IDLE，才能真正 prepare", Player.STATE_IDLE, failure.state)
            assertEquals(track.uid, failure.uid)
            assertTrue("须在进度静音空跑时检出", failure.positionMs >= 6_000L)
            await(fixture, "同资源重新准备后继续播放", 8_000L) {
                attempts.get() >= 2 && fixture.player.isPlaying &&
                    fixture.player.currentPosition >= failure.positionMs + 500L
            }
            observeFor(fixture, 5_500L)
            main {
                assertEquals("原资源首次重建不能跳集", track.uid, fixture.player.currentMediaItem?.mediaId)
                assertEquals(2, fixture.player.mediaItemCount)
                assertEquals(2, controller.mediaItemCount)
                assertTrue(fixture.player.playWhenReady)
                assertNull(fixture.player.playerError)
                assertEquals("恢复后的零波形 PCM 不能继续误报", 1, fixture.failures.size)
                assertFalse("首次重建不能把状态异常直接判为坏资源", SourceResolver.isRejected(resourceId))
            }
        }
    }

    @Test(timeout = 20_000L)
    fun consecutiveEmptyItemsReportFailureOnPlayingItemInsteadOfReadingAheadItem() = assertFailureStaysOnPlayingItem(false)

    @Test(timeout = 20_000L)
    fun disabledAudioRendererReadingTextOnlyNextItemStillReportsCurrentEmptyAudioItem() = assertFailureStaysOnPlayingItem(true)

    private fun assertFailureStaysOnPlayingItem(nextTextOnly: Boolean) = withPlayer { fixture ->
        main {
            fixture.player.setMediaSources(listOf(
                source("empty-first", 20_000L, AtomicInteger(0), emptyFirst = true),
                source("empty-next", 20_000L, AtomicInteger(0), emptyFirst = true, textOnly = nextTextOnly),
            ), 0, 2_000L)
            fixture.player.prepare()
            fixture.player.play()
        }
        await(fixture, "第一项空音轨开始按时间线播放") {
            fixture.player.isPlaying && fixture.player.currentMediaItemIndex == 0 && fixture.player.currentPosition >= 2_500L
        }
        await(fixture, "预读下一项不能改变故障归属", 12_000L) { fixture.failures.isNotEmpty() }
        val failure = fixture.failures.single()
        assertEquals(ERROR_CODE_AUDIO_OUTPUT_STALLED, failure.code)
        assertEquals(Player.STATE_IDLE, failure.state)
        assertEquals("故障必须归属于当前播放项", "empty-first", failure.uid)
        assertTrue("不能跳到预读项的起点", failure.positionMs in 6_000L..15_000L)
        main {
            assertEquals("empty-first", fixture.player.currentMediaItem?.mediaId)
            assertEquals(0, fixture.player.currentMediaItemIndex)
            assertEquals(2, fixture.player.mediaItemCount)
            assertEquals(failure.positionMs, fixture.player.currentPosition)
        }
    }

    @Test(timeout = 20_000L)
    fun textOnlyItemCannotSilentlyRunWithoutAnySelectedAudioTrack() = withPlayer { fixture ->
        main {
            fixture.player.setMediaSource(source("text-only", 20_000L, textOnly = true))
            fixture.player.prepare()
            fixture.player.play()
        }
        await(fixture, "已知时长的纯文本项开始走时") {
            fixture.player.isPlaying && fixture.player.currentPosition >= 500L &&
                fixture.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        }
        main {
            assertFalse("夹具不能混入音频轨", fixture.player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO })
            assertTrue(fixture.player.duration >= 19_000L)
        }
        await(fixture, "无选中音频轨也必须进入错误恢复", 12_000L) { fixture.failures.isNotEmpty() }
        val failure = fixture.failures.single()
        assertEquals(ERROR_CODE_AUDIO_OUTPUT_STALLED, failure.code)
        assertEquals(Player.STATE_IDLE, failure.state)
        assertEquals("text-only", failure.uid)
    }

    @Test(timeout = 40_000L)
    fun threePermanentlyEmptyItemsExhaustRecoveryAndPauseBeforeFourthItem() = withPlayer(timeoutMs = 500L) { fixture ->
        val identity = System.nanoTime()
        val tracks = (0..3).map { index ->
            UiTrack.fromOnline(OnlineSong(JSONObject().put("source", "kw").put("songmid", "empty-$identity-$index")
                .put("name", "Empty output fixture $index").put("singer", "Fixture Artist")))
        }
        withController(fixture, tracks.first(), tracks.drop(1)) { controller ->
            main {
                // 本用例只验证在线资源错误预算，不需要应用Context，也不允许旁路加载网络详情。
                field("appContext").set(PlaybackController, null)
                fixture.player.setMediaSources(tracks.mapIndexed { index, track ->
                    source(track.uid, 20_000L, resourceId = "lx:fixture:empty-$identity-$index",
                        noSamplesAlways = index < 3)
                })
                fixture.player.prepare()
                fixture.player.play()
            }
            await(fixture, "连续三项失败必须耗尽恢复并暂停", 30_000L) {
                fixture.failures.isNotEmpty() && !fixture.player.playWhenReady
            }
            main {
                assertEquals("连续错误计数不能被READY状态清零", tracks[2].uid, fixture.player.currentMediaItem?.mediaId)
                assertEquals(2, fixture.player.currentMediaItemIndex)
                assertEquals(Player.STATE_IDLE, fixture.player.playbackState)
                assertFalse(fixture.player.playWhenReady)
                assertEquals("停止时保留原队列", 4, controller.mediaItemCount)
                assertEquals(4, fixture.player.mediaItemCount)
            }
            assertEquals("每项仅允许原资源重建一次和同资源错误重试一次", 9, fixture.failures.size)
            tracks.take(3).forEach { track ->
                assertEquals("新项须获得自己的有限恢复机会", 3, fixture.failures.count { it.uid == track.uid })
            }
            assertTrue("第四项不能被消费或报错", fixture.failures.none { it.uid == tracks[3].uid })
            val stoppedFailures = fixture.failures.size
            observeFor(fixture, 1_500L)
            main {
                assertEquals(tracks[2].uid, fixture.player.currentMediaItem?.mediaId)
                assertFalse(fixture.player.playWhenReady)
                assertEquals("暂停后不允许继续触发恢复", stoppedFailures, fixture.failures.size)
            }
        }
    }

    @Test(timeout = 35_000L)
    fun zeroWaveformPcmAndNaturalNextItemKeepAdvancingWithoutFalseErrors() = withPlayer { fixture ->
        val transitions = CopyOnWriteArrayList<Pair<String?, Int>>()
        main {
            fixture.player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    transitions.add(mediaItem?.mediaId to reason)
                }
            })
            fixture.player.setMediaSources(listOf(source("first", 6_500L), source("next", 10_000L)))
            fixture.player.prepare()
            fixture.player.play()
        }
        await(fixture, "第一项 PCM 输出") { fixture.player.isPlaying && fixture.player.currentPosition > 500L }
        await(fixture, "自然切到下一项且持续输出超过检查窗口", 20_000L) {
            fixture.player.currentMediaItemIndex == 1 && fixture.player.isPlaying && fixture.player.currentPosition > 5_500L
        }
        assertTrue("必须通过自然连播换项", transitions.any {
            it.first == "next" && it.second == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        })
        assertTrue("波形全零或播放器静音不应被判为没有输出", fixture.failures.isEmpty())
        main { assertNull(fixture.player.playerError) }
    }

    @Test(timeout = 40_000L)
    fun pauseAndBidirectionalSeekDoNotAccumulateSilentPlaybackTimeout() = withPlayer { fixture ->
        main {
            fixture.player.setMediaSource(source("seek", 30_000L))
            fixture.player.prepare()
            fixture.player.play()
        }
        await(fixture, "起播") { fixture.player.isPlaying && fixture.player.currentPosition > 500L }
        main { fixture.player.pause() }
        await(fixture, "暂停") { !fixture.player.playWhenReady && !fixture.player.isPlaying }
        val pausedPosition = main { fixture.player.currentPosition }
        observeFor(fixture, 6_000L)
        main {
            assertFalse(fixture.player.playWhenReady)
            assertTrue(abs(fixture.player.currentPosition - pausedPosition) <= 250L)
            fixture.player.seekTo(18_000L)
        }
        await(fixture, "暂停时前向 seek") {
            fixture.player.playbackState == Player.STATE_READY && abs(fixture.player.currentPosition - 18_000L) <= 250L
        }
        observeFor(fixture, 500L)
        main { fixture.player.play() }
        await(fixture, "seek 后恢复输出") { fixture.player.isPlaying && fixture.player.currentPosition > 18_500L }
        observeFor(fixture, 5_500L)
        main { fixture.player.seekTo(2_000L) }
        await(fixture, "播放中后向 seek") {
            fixture.player.isPlaying && fixture.player.currentPosition in 2_000L..4_000L
        }
        observeFor(fixture, 5_500L)
        assertTrue("暂停和 seek 不能触发缺失输出错误", fixture.failures.isEmpty())
        main { assertNull(fixture.player.playerError) }
    }

    private fun withPlayer(timeoutMs: Long = 5_000L, test: (Fixture) -> Unit) {
        assertEquals("不能启动正式用户 Application", Application::class.java, context.applicationContext.javaClass)
        val fixture = main {
            val factory = PlaybackRenderersFactory(context, PcmEffectsAudioProcessor())
            val player = ExoPlayer.Builder(context, factory)
                .experimentalSetDynamicSchedulingEnabled(true)
                .setLoadControl(playbackLoadControl())
                .build().apply { volume = 0f }
            val monitor = AudioOutputMonitor(player, factory.outputProbe, AudioOutputWatchdog(timeoutMs))
            Fixture(player, monitor).also { fixture ->
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        fixture.failures.add(Failure(error.errorCode, player.playbackState,
                            player.currentPosition, player.currentMediaItem?.mediaId))
                    }
                })
            }
        }
        try {
            test(fixture)
        } finally {
            main { fixture.monitor.close(); fixture.player.release() }
        }
    }

    private fun withController(fixture: Fixture, track: UiTrack, followingTracks: List<UiTrack> = emptyList(),
                               test: (MediaController) -> Unit) {
        val session = main { MediaSession.Builder(context, fixture.player).setId("output-${System.nanoTime()}").build() }
        val controller = main { MediaController.Builder(context, session.token).buildAsync() }.get(5, TimeUnit.SECONDS)
        val fields = listOf("controller", "appContext", "consecutiveErrors").associateWith(::field)
        val originals = fields.mapValues { it.value.get(PlaybackController) }
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val originalState = state.value
        val originalSwitch = MeloraSettings.autoSwitchSource.value
        val tracks = listOf(track) + followingTracks
        val errorRecovery = field("errorRecovery").get(PlaybackController) as PlaybackErrorRecovery
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 与生产attach一致：只在真实换项时刷新身份并重置该项恢复预算，不在READY时清错误计数。
                errorRecovery.reset()
                state.value = state.value.copy(
                    current = tracks.firstOrNull { it.uid == mediaItem?.mediaId },
                    queue = tracks,
                    currentIndex = controller.currentMediaItemIndex,
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                PlaybackController.javaClass.getDeclaredMethod("handlePlayerError", PlaybackException::class.java)
                    .apply { isAccessible = true }.invoke(PlaybackController, error)
            }
        }
        try {
            main {
                PlaybackController.clearQueue()
                SourceResolver.clearCache()
                fields.getValue("controller").set(PlaybackController, controller)
                fields.getValue("appContext").set(PlaybackController, context)
                fields.getValue("consecutiveErrors").set(PlaybackController, 0)
                MeloraSettings.autoSwitchSource.value = true
                TrackRegistry.registerAll(tracks)
                state.value = PlayerUiState(current = track, queue = tracks, currentIndex = 0)
                controller.addListener(listener)
            }
            test(controller)
        } finally {
            main {
                controller.removeListener(listener)
                PlaybackController.clearQueue()
                fields.forEach { (name, field) -> field.set(PlaybackController, originals[name]) }
                state.value = originalState
                MeloraSettings.autoSwitchSource.value = originalSwitch
                controller.release()
                session.release()
                tracks.forEach { TrackRegistry.clearResolved(it.uid) }
                SourceResolver.clearCache()
            }
        }
    }

    private fun source(uid: String, durationMs: Long, attempts: AtomicInteger = AtomicInteger(),
                       emptyFirst: Boolean = false, resourceId: String? = null, textOnly: Boolean = false,
                       noSamplesAlways: Boolean = false): MediaSource {
        val dataSources = DataSource.Factory {
            val upstream = ByteArrayDataSource(byteArrayOf(0))
            object : DataSource by upstream {
                override fun open(dataSpec: DataSpec): Long {
                    resourceId?.let { TrackRegistry.notifyResolved(uid, "128k", it) }
                    return upstream.open(dataSpec)
                }
            }
        }
        val extractors = ExtractorsFactory {
            arrayOf<Extractor>(DeclaredTrackExtractor(durationMs,
                noSamplesAlways || (emptyFirst && attempts.getAndIncrement() == 0), textOnly))
        }
        return ProgressiveMediaSource.Factory(dataSources, extractors).createMediaSource(
            MediaItem.Builder().setMediaId(uid).setUri("fixture://output/$uid").build(),
        )
    }

    private fun await(fixture: Fixture, description: String, timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!main { fixture.monitor.check(); predicate() }) {
            assertTrue("等待超时：$description；errors=${fixture.failures}", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(100L)
        }
    }

    private fun observeFor(fixture: Fixture, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            main { fixture.monitor.check() }
            Thread.sleep(100L)
        }
    }

    private fun field(name: String) = PlaybackController.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private data class Fixture(val player: ExoPlayer, val monitor: AudioOutputMonitor,
                               val failures: CopyOnWriteArrayList<Failure> = CopyOnWriteArrayList())
    private data class Failure(val code: Int, val state: Int, val positionMs: Long, val uid: String?)

    /** 发布已知时长和轨道格式；音频正常轮每次交付100ms零 PCM，故障轮及纯文本轮不交付 sample。 */
    private class DeclaredTrackExtractor(durationMs: Long, private val noSamples: Boolean,
                                         private val textOnly: Boolean) : Extractor {
        private val mediaDurationUs = durationMs * 1_000L
        private var positionUs = 0L
        private lateinit var track: TrackOutput
        override fun sniff(input: ExtractorInput): Boolean = true
        override fun init(output: ExtractorOutput) {
            track = output.track(0, if (textOnly) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO)
            track.format(if (textOnly) {
                Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            } else {
                Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setSampleRate(8_000).setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
            })
            output.endTracks()
            output.seekMap(object : SeekMap {
                override fun isSeekable(): Boolean = true
                override fun getDurationUs(): Long = mediaDurationUs
                override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(timeUs, 0L))
            })
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (noSamples || textOnly || positionUs >= mediaDurationUs) return Extractor.RESULT_END_OF_INPUT
            val samples = minOf(800L, (mediaDurationUs - positionUs) * 8_000 / 1_000_000).toInt()
            if (samples <= 0) return Extractor.RESULT_END_OF_INPUT
            val pcm = ParsableByteArray(ByteArray(samples * 2))
            track.sampleData(pcm, samples * 2)
            track.sampleMetadata(positionUs, C.BUFFER_FLAG_KEY_FRAME, samples * 2, 0, null)
            positionUs += samples * 1_000_000L / 8_000
            return Extractor.RESULT_CONTINUE
        }
        override fun seek(position: Long, timeUs: Long) { positionUs = timeUs }
        override fun release() = Unit
    }
}

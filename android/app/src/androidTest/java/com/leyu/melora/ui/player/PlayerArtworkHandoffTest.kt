package com.leyu.melora.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import com.leyu.melora.playback.PlayerCoverStyle
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

@OptIn(DelicateCoilApi::class)
class PlayerArtworkHandoffTest {
    @get:Rule val compose = createComposeRule()

    private val first = Cover("https://artwork-handoff.invalid/first")
    private val next = Cover("https://artwork-handoff.invalid/next")
    private val local = Cover("file:///artwork-handoff/local.png")
    private val covers = listOf(first, next, local).associateBy(Cover::url)
    private data class Track(val uid: String, val artwork: String?)
    private val track = mutableStateOf(Track("first", first.url))
    private val mounted = mutableStateOf(false)
    private val retriesEnabled = mutableStateOf(true)
    private lateinit var originalLoader: ImageLoader
    private lateinit var fixtureLoader: ImageLoader

    @Before fun useControlledCoverRequests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        originalLoader = SingletonImageLoader.get(context)
        fixtureLoader = ImageLoader.Builder(context)
            .components { add(CoverFetcher(covers)) }
            .eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    covers[request.data.toString()]?.failures?.incrementAndGet()
                }
            })
            .build()
        SingletonImageLoader.setUnsafe(fixtureLoader)
        first.result.complete(Color.Red)
    }

    @After fun restoreCoverRequests() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        SingletonImageLoader.setUnsafe(originalLoader)
        fixtureLoader.shutdown()
    }

    @Test fun fastCoverBlendsFromThePreviousLabelWithoutAnEmptyFrame() {
        show()
        select("next", next.url)
        awaitRequests(next)
        compose.mainClock.advanceTimeByFrame()
        assertColor(Color.Red, label())

        next.result.complete(Color.Blue)
        val frames = collectUntil { near(it, Color.Blue) }
        assertTrue("两张标签应有可见的混合帧：$frames", frames.any { it.red > 0.1f && it.blue > 0.1f })
        assertTrue("等待和淡入期间标签应始终有实心底图：$frames",
            frames.all { it.red + it.blue > 0.85f && it.green < 0.08f })
    }

    @Test fun slowCoverReleasesThePreviousTrackWhileTheNewRequestIsStillPending() {
        show(initial = Track("missing", null), style = PlayerCoverStyle.Default)
        val placeholder = label()
        select("first", first.url)
        collectUntil { near(it, Color.Red) }
        select("next", next.url)
        awaitRequests(next)
        compose.mainClock.advanceTimeBy(1_000)
        assertColor(placeholder, label())
        next.result.complete(Color.Blue)
        collectUntil { near(it, Color.Blue) }
    }

    @Test fun skippingAnotherPendingTrackDoesNotRestartTheHold() {
        show(initial = Track("missing", null), style = PlayerCoverStyle.Circle)
        val placeholder = label()
        select("first", first.url)
        collectUntil { near(it, Color.Red) }
        select("next", next.url)
        awaitRequests(next)
        compose.mainClock.advanceTimeBy(260)
        assertColor(Color.Red, label())
        select("local", local.url)
        awaitRequests(local)
        compose.mainClock.advanceTimeBy(240)
        assertColor(placeholder, label())
        local.result.complete(Color.Green)
        collectUntil { near(it, Color.Green) }
    }

    @Test fun rapidSkipOnlyShowsTheLatestCoverEvenIfTheAbandonedRequestFinishes() {
        show(initial = Track("missing", null))
        val placeholder = label()
        select("first", first.url)
        collectUntil { near(it, Color.Red) }
        select("next", next.url)
        awaitRequests(next)
        // 让唱臂复位，第三首开始换碟时，第二首的请求仍在等待。
        compose.mainClock.advanceTimeBy(600)
        assertColor(placeholder, label())
        select("local", local.url)
        compose.mainClock.advanceTimeByFrame()
        next.result.complete(Color.Blue)
        compose.waitUntil(8_000) {
            compose.mainClock.advanceTimeByFrame()
            assertColor(placeholder, label())
            local.requests.get() > 0
        }
        local.result.complete(Color.Green)
        val frames = collectUntil { near(it, Color.Green) }
        assertTrue("跳过的蓝色封面不应重新出现：$frames", frames.all { it.blue < 0.25f })
    }

    @Test fun replacingAnArtworkDuringItsFadeKeepsTheVisibleBlend() {
        show()
        select("next", next.url)
        awaitRequests(next)
        next.result.complete(Color.Blue)
        val mixed = collectUntil { it.blue in 0.25f..0.75f }.last()

        // 播放器可以先取得在线图，再为同一首歌回填实际播放文件的内嵌封面。
        select("next", local.url)
        val pending = collectUntil { local.requests.get() > 0 && near(it, Color.Blue) }
        assertTrue("换地址后已进入画面的蓝色不能跳回红色：$mixed，$pending",
            pending.all { it.blue >= mixed.blue - 0.05f })
        compose.mainClock.advanceTimeBy(1_000)
        assertColor(Color.Blue, label())
        local.result.complete(Color.Green)
        val finalFade = collectUntil { near(it, Color.Green) }
        assertTrue("蓝色底图应连续过渡到绿色：$finalFade",
            finalFade.all { it.red < 0.08f && it.blue + it.green > 0.85f })
    }

    @Test fun sharedAlbumCoverBelongsToTheNewTrackDuringItsLocalArtworkLookup() {
        show(style = PlayerCoverStyle.Default)
        select("next", first.url)
        compose.mainClock.advanceTimeBy(500)
        assertColor(Color.Red, label())
        select("next", local.url)
        awaitRequests(local)
        compose.mainClock.advanceTimeBy(1_000)
        assertColor(Color.Red, label())
        local.result.complete(Color.Green)
        collectUntil { near(it, Color.Green) }
    }

    @Test fun missingArtworkAndExhaustedRetriesUseThePlaceholder() {
        show(initial = Track("missing", null))
        val placeholder = label()

        select("first", first.url)
        collectUntil { near(it, Color.Red) }
        select("next", next.url)
        awaitRequests(next)
        next.result.completeExceptionally(IOException("Fixture image failed"))
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            next.failures.get() > 0
        }
        // 旧图先退出，当前曲目的两次重试仍会照常执行。
        collectUntil { near(it, placeholder) }
        assertTrue(next.requests.get() < 3)
        awaitRequests(next, count = 3)
        assertColor(placeholder, label())

        select("first", first.url)
        collectUntil { near(it, Color.Red) }
        select("missing", null)
        collectUntil { near(it, placeholder) }
    }

    @Test fun failedReplacementShowsThePlaceholderAndCanRetryWhenEnabled() {
        retriesEnabled.value = false
        show(initial = Track("missing", null), style = PlayerCoverStyle.Default)
        val placeholder = label()
        select("first", first.url)
        collectUntil { near(it, Color.Red) }
        select("first", next.url)
        awaitRequests(next)
        next.result.completeExceptionally(IOException("Fixture image failed"))
        collectUntil { near(it, placeholder) }
        assertTrue(next.requests.get() == 1)

        compose.runOnIdle { retriesEnabled.value = true }
        awaitRequests(next, count = 3)
    }

    private fun show(initial: Track = track.value, style: PlayerCoverStyle = PlayerCoverStyle.Vinyl) {
        track.value = initial
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                val requested = track.value
                val record = if (style == PlayerCoverStyle.Vinyl) {
                    rememberVinylRecordChange(requested.uid, requested.artwork, enabled = true)
                } else null
                val artwork = rememberPlayerArtwork(
                    requested.artwork, requested.uid,
                    sizePx = with(LocalDensity.current) { 176.dp.roundToPx() },
                    retryOnError = retriesEnabled.value, motionEnabled = true,
                    placeholderTint = { Color.Gray.copy(alpha = 0.40f) }, recordChange = record,
                )
                NowPlayingArtwork(
                    artwork, style,
                    Modifier.size(176.dp).testTag("cover"),
                    recordChange = record,
                )
            }
        }
        compose.waitForIdle()
        if (initial.artwork == first.url) compose.waitUntil(5_000) { near(label(), Color.Red) }
        compose.mainClock.advanceTimeBy(320)
        compose.mainClock.autoAdvance = false
    }

    private fun select(uid: String, url: String?) {
        compose.runOnIdle { track.value = Track(uid, url) }
    }

    private fun awaitRequests(cover: Cover, count: Int = 1) {
        compose.waitUntil(8_000) {
            compose.mainClock.advanceTimeByFrame()
            cover.requests.get() >= count
        }
    }

    private fun collectUntil(condition: (Color) -> Boolean): List<Color> {
        val frames = mutableListOf<Color>()
        compose.waitUntil(8_000) {
            compose.mainClock.advanceTimeByFrame()
            val pixel = label()
            frames += pixel
            condition(pixel)
        }
        return frames
    }

    private fun label(): Color {
        val image = compose.onNodeWithTag("cover").captureToImage()
        // 取实色标签内、轴心环外的一点。
        return image.toPixelMap(image.width / 2, (image.height * 0.59f).toInt(), width = 1, height = 1)[0, 0]
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertTrue("期望 $expected，实际 $actual", near(actual, expected))
    }

    private fun near(actual: Color, expected: Color): Boolean =
        abs(actual.red - expected.red) < 0.04f && abs(actual.green - expected.green) < 0.04f &&
            abs(actual.blue - expected.blue) < 0.04f

    private class Cover(val url: String) {
        val requests = AtomicInteger()
        val failures = AtomicInteger()
        val result = CompletableDeferred<Color>()
    }

    private class CoverFetcher(private val covers: Map<String, Cover>) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val cover = covers[data.toString()] ?: return null
            return object : Fetcher {
                override suspend fun fetch(): FetchResult {
                    cover.requests.incrementAndGet()
                    val color = cover.result.await()
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(color.toArgb())
                    return ImageFetchResult(bitmap.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
                }
            }
        }
    }
}

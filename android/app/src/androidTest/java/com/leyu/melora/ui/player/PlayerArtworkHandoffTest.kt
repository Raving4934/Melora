package com.leyu.melora.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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

    @Test fun slowCoverHoldsThePreviousLabelAndBlendsWithoutAnEmptyFrame() {
        show()
        select("next", next.url)
        awaitRequests(next)
        compose.mainClock.advanceTimeBy(1_000)
        assertColor(Color.Red, label())

        next.result.complete(Color.Blue)
        val frames = collectUntil { near(it, Color.Blue) }
        assertTrue("两张标签应有可见的混合帧：$frames", frames.any { it.red > 0.1f && it.blue > 0.1f })
        assertTrue("等待和淡入期间标签应始终有实心底图：$frames",
            frames.all { it.red + it.blue > 0.85f && it.green < 0.08f })
    }

    @Test fun rapidSkipOnlyShowsTheLatestCoverEvenIfTheAbandonedRequestFinishes() {
        show()
        select("next", next.url)
        awaitRequests(next)
        select("local", local.url)
        awaitRequests(local)

        next.result.complete(Color.Blue)
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            assertColor(Color.Red, label())
        }
        local.result.complete(Color.Green)
        val frames = collectUntil { near(it, Color.Green) }
        assertTrue("迟到的封面不能覆盖最新曲目：$frames",
            frames.all { it.blue < 0.08f && it.red + it.green > 0.85f })
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
        local.result.complete(Color.Green)
        val finalFade = collectUntil { near(it, Color.Green) }
        assertTrue("蓝色底图应连续过渡到绿色：$finalFade",
            finalFade.all { it.red < 0.08f && it.blue + it.green > 0.85f })
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
        compose.mainClock.advanceTimeBy(80)
        assertColor(Color.Red, label())
        // 首次请求失败与两次重试用完后才退出原封面。
        awaitRequests(next, count = 3)
        collectUntil { near(it, placeholder) }

        select("first", first.url)
        collectUntil { near(it, Color.Red) }
        select("missing", null)
        collectUntil { near(it, placeholder) }
    }

    private fun show(initial: Track = track.value) {
        track.value = initial
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                val requested = track.value
                val record = rememberVinylRecordChange(requested.uid, requested.artwork, enabled = true)
                NowPlayingArtwork(
                    requested.artwork, requested.uid, PlayerCoverStyle.Vinyl,
                    Modifier.size(176.dp).testTag("cover"),
                    retryOnError = true, smoothChanges = true, recordChange = record,
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

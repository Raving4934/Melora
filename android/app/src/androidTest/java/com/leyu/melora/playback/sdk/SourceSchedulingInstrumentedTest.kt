package com.leyu.melora.playback.sdk

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.lx.LxScriptStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * Controlled on-device measurements of the real SourceResolver -> LxScriptPool -> QuickJS path.
 * This uses synthetic `example.test` URLs only; fixture delays are not network/server measurements.
 */
@RunWith(AndroidJUnit4::class)
class SourceSchedulingInstrumentedTest {
    private val runId = UUID.randomUUID().toString().replace("-", "").take(12)
    private val context = FixtureContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        runId,
    )
    private val reportContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val scriptId = "source-scheduling-$runId.js"
    private val store by lazy { LxScriptStore(context) }

    @Before
    fun setUp() = runBlocking {
        context.root.deleteRecursively()
        context.filesDir.mkdirs()
        context.cacheDir.mkdirs()

        store.import(scriptId, fixtureScript())
        store.setEnabled(scriptId, true)
        assertEquals("fixture QuickJS 脚本应成功加载", 1, LxScriptPool.reload(context))
        val ready = LxScriptPool.scriptsFor(context, "kw", scope = LxScriptPool.ScriptScope.ENABLED)
        assertEquals("只应加载本测试的合成源", listOf(scriptId), ready.map { it.first.id })
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        try {
            // 清除本测试的进程内 QuickJS 实例；文件与 SharedPreferences 均限定在唯一 fixture 命名空间。
            store.remove(scriptId)
            LxScriptPool.reload(context)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun foregroundOnlyBaseline() = runBlocking {
        val samples = withTimeout(SCENARIO_TIMEOUT_MS) {
            (0 until SAMPLE_COUNT).map { sampleIndex ->
                Sample(
                    index = sampleIndex + 1,
                    backgroundLeadMs = 0,
                    backgroundSubmitted = 0,
                    calls = listOf(
                        measureResolve(
                            role = "foreground-playback",
                            songmid = "$runId-base-${sampleIndex + 1}",
                            purpose = SourceResolver.Purpose.PLAYBACK,
                        ),
                    ),
                )
            }
        }
        finishScenario("foreground-baseline", samples)
    }

    @Test
    fun foregroundDuringOneStartedBackgroundDownload() = runBlocking {
        val samples = withTimeout(SCENARIO_TIMEOUT_MS) {
            (0 until SAMPLE_COUNT).map { sampleIndex ->
                val backgroundSongmid = "$runId-bg-${sampleIndex + 1}"
                val background = async(Dispatchers.IO) {
                    measureResolve(
                        role = "background-download",
                        songmid = backgroundSongmid,
                        purpose = SourceResolver.Purpose.DOWNLOAD,
                    )
                }
                try {
                    // The pool is warm before measurement. Keep the 800ms fixture request outstanding
                    // for a bounded lead-in; the script also logs its request-entry marker for logcat.
                    delay(BACKGROUND_LEAD_MS)
                    val foreground = measureResolve(
                        role = "foreground-playback",
                        songmid = "$runId-fg-${sampleIndex + 1}",
                        purpose = SourceResolver.Purpose.PLAYBACK,
                    )
                    val backgroundResult = withTimeout(JOIN_TIMEOUT_MS) { background.await() }
                    Sample(
                        index = sampleIndex + 1,
                        backgroundLeadMs = BACKGROUND_LEAD_MS,
                        backgroundSubmitted = 1,
                        calls = listOf(backgroundResult, foreground),
                    )
                } finally {
                    if (!background.isCompleted) background.cancelAndJoin()
                }
            }
        }
        finishScenario("foreground-during-one-download", samples)
    }

    @Test
    fun foregroundDuringMultipleQueuedBackgroundDownloads() = runBlocking {
        val samples = withTimeout(SCENARIO_TIMEOUT_MS) {
            (0 until SAMPLE_COUNT).map { sampleIndex ->
                val backgrounds = (0 until QUEUED_BACKGROUND_COUNT).map { slot ->
                    async(Dispatchers.IO) {
                        measureResolve(
                            role = "queued-background-download-${slot + 1}",
                            songmid = "$runId-q-${sampleIndex + 1}-$slot",
                            purpose = SourceResolver.Purpose.DOWNLOAD,
                        )
                    }
                }
                try {
                    // All jobs target the same warmed QuickJS entry. This bounded lead-in submits
                    // them before PLAYBACK; queue depth is intentionally not inferred from latency.
                    delay(BACKGROUND_LEAD_MS)
                    val foreground = measureResolve(
                        role = "foreground-playback",
                        songmid = "$runId-fgq-${sampleIndex + 1}",
                        purpose = SourceResolver.Purpose.PLAYBACK,
                    )
                    val backgroundResults = withTimeout(JOIN_TIMEOUT_MS) { backgrounds.awaitAll() }
                    Sample(
                        index = sampleIndex + 1,
                        backgroundLeadMs = BACKGROUND_LEAD_MS,
                        backgroundSubmitted = backgrounds.size,
                        calls = backgroundResults + foreground,
                    )
                } finally {
                    backgrounds.filterNot { it.isCompleted }.forEach { it.cancelAndJoin() }
                }
            }
        }
        finishScenario("foreground-during-queued-downloads", samples)
    }

    private suspend fun measureResolve(
        role: String,
        songmid: String,
        purpose: SourceResolver.Purpose,
    ): CallRecord {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val resolved = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                SourceResolver.resolve(
                    context = context,
                    song = fixtureSong(songmid),
                    preferredQuality = REQUIRED_QUALITY,
                    allowSwitch = false,
                    purpose = purpose,
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (resolved == null) {
                CallRecord(
                    role = role,
                    purpose = purpose.name,
                    songmid = songmid,
                    elapsedMs = elapsedMs,
                    success = false,
                    requestedQuality = REQUIRED_QUALITY,
                    reportedQuality = null,
                    resolvedQuality = null,
                    url = null,
                    error = "resolve timeout after ${CALL_TIMEOUT_MS}ms",
                )
            } else {
                CallRecord(
                    role = role,
                    purpose = purpose.name,
                    songmid = songmid,
                    elapsedMs = elapsedMs,
                    success = true,
                    requestedQuality = REQUIRED_QUALITY,
                    // The fixture returns `type: info.type`; SourceResolver exposes that report as quality.
                    reportedQuality = resolved.quality,
                    resolvedQuality = resolved.quality,
                    url = resolved.url,
                    error = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            CallRecord(
                role = role,
                purpose = purpose.name,
                songmid = songmid,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                success = false,
                requestedQuality = REQUIRED_QUALITY,
                reportedQuality = null,
                resolvedQuality = null,
                url = null,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun finishScenario(name: String, samples: List<Sample>) {
        val report = scenarioJson(name, samples)
        val output = File(reportContext.cacheDir, "source-scheduling-$runId-$name.json")
        output.writeText(report.toString(2))
        Log.i(TAG, "scenario=$name report=${output.absolutePath}")

        val records = samples.flatMap { it.calls }
        assertEquals("每个场景必须完成 $SAMPLE_COUNT 组测量", SAMPLE_COUNT, samples.size)
        assertTrue("所有合成解析都应成功：$name", records.all { it.success })
        records.forEach { record ->
            assertEquals("${record.role}/${record.songmid} 不得靠音质降级改善耗时", REQUIRED_QUALITY, record.requestedQuality)
            assertEquals("${record.role}/${record.songmid} 必须保持 320k", REQUIRED_QUALITY, record.reportedQuality)
            assertEquals("${record.role}/${record.songmid} 返回音质必须保持 320k", REQUIRED_QUALITY, record.resolvedQuality)
            assertTrue("结果必须来自合成 example.test：${record.url}", record.url?.startsWith(FIXTURE_URL_PREFIX) == true)
        }
    }

    private fun scenarioJson(name: String, samples: List<Sample>) = JSONObject().apply {
        put("scenario", name)
        put("runId", runId)
        put("fixture", JSONObject()
            .put("synthetic", true)
            .put("usesRealNetwork", false)
            .put("urlPrefix", FIXTURE_URL_PREFIX)
            .put("backgroundDelayMs", BACKGROUND_DELAY_MS)
            .put("foregroundDelayMs", FOREGROUND_DELAY_MS)
            .put("requestedQuality", REQUIRED_QUALITY)
            .put("preconditionNote", "Pool preloaded; background jobs launched and held for a bounded lead-in. Fixture logs request entry; internal queue depth is not introspected."))

        val records = samples.flatMap { it.calls }
        put("summary", JSONObject()
            .put("allCalls", statistics(records))
            .put("foregroundPlayback", statistics(records.filter { it.role == "foreground-playback" }))
            .put("backgroundDownloads", statistics(records.filter { it.purpose == SourceResolver.Purpose.DOWNLOAD.name })))
        put("samples", JSONArray().apply {
            samples.forEach { sample ->
                put(JSONObject()
                    .put("index", sample.index)
                    .put("backgroundLeadMs", sample.backgroundLeadMs)
                    .put("backgroundJobsSubmitted", sample.backgroundSubmitted)
                    .put("calls", JSONArray().apply { sample.calls.forEach { put(it.toJson()) } }))
            }
        })
    }

    private fun statistics(records: List<CallRecord>) = JSONObject().apply {
        val durations = records.map { it.elapsedMs }.sorted()
        put("callCount", records.size)
        put("successCount", records.count { it.success })
        put("failureCount", records.count { !it.success })
        put("p50Ms", percentile(durations, 0.50))
        put("p95Ms", percentile(durations, 0.95))
        put("percentileMethod", "nearest-rank over all calls, including failures")
    }

    private fun percentile(sortedDurations: List<Long>, percentile: Double): Any =
        if (sortedDurations.isEmpty()) JSONObject.NULL
        else sortedDurations[(ceil(percentile * sortedDurations.size).toInt() - 1).coerceIn(sortedDurations.indices)]

    private fun fixtureSong(songmid: String) = OnlineSong(
        JSONObject()
            .put("source", "kw")
            .put("songmid", songmid)
            .put("name", "Synthetic scheduling fixture")
            .put("singer", "Instrumentation Test")
            .put("_types", JSONObject().put(REQUIRED_QUALITY, JSONObject())),
    )

    private fun fixtureScript(): String = """
        // @name Source Scheduling Fixture $runId
        // @version 1
        lx.send('inited', {sources: {kw: {
            name: 'source-scheduling-fixture',
            actions: ['musicUrl'],
            qualitys: ['320k']
        }}});
        lx.on('request', ({info}) => {
            const songmid = String(info.musicInfo && info.musicInfo.songmid || 'unknown');
            const isBackground = songmid.indexOf('-bg-') >= 0 || songmid.indexOf('-q-') >= 0;
            const delayMs = isBackground ? $BACKGROUND_DELAY_MS : $FOREGROUND_DELAY_MS;
            console.log('[SourceSchedulingFixture:$runId] started=' + songmid + ', type=' + info.type + ', delayMs=' + delayMs);
            return new Promise(resolve => setTimeout(() => resolve({
                url: '$FIXTURE_URL_PREFIX' + encodeURIComponent(songmid) + '.mp3',
                type: info.type
            }), delayMs));
        });
    """.trimIndent()

    private data class Sample(
        val index: Int,
        val backgroundLeadMs: Long,
        val backgroundSubmitted: Int,
        val calls: List<CallRecord>,
    )

    private data class CallRecord(
        val role: String,
        val purpose: String,
        val songmid: String,
        val elapsedMs: Long,
        val success: Boolean,
        val requestedQuality: String,
        val reportedQuality: String?,
        val resolvedQuality: String?,
        val url: String?,
        val error: String?,
    ) {
        fun toJson() = JSONObject()
            .put("role", role)
            .put("purpose", purpose)
            .put("songmid", songmid)
            .put("elapsedMs", elapsedMs)
            .put("success", success)
            .put("requestedQuality", requestedQuality)
            .put("scriptReportedQuality", reportedQuality ?: JSONObject.NULL)
            .put("resolvedQuality", resolvedQuality ?: JSONObject.NULL)
            .put("url", url ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
    }

    private class FixtureContext(base: Context, private val runId: String) : ContextWrapper(base) {
        val root = File(base.cacheDir, "isolated-source-scheduling-$runId")
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile?.mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            baseContext.getSharedPreferences("isolated-source-scheduling-$runId.$name", mode)
    }

    private companion object {
        const val TAG = "SourceSchedulingTest"
        const val SAMPLE_COUNT = 5
        const val QUEUED_BACKGROUND_COUNT = 3
        const val REQUIRED_QUALITY = "320k"
        const val BACKGROUND_DELAY_MS = 800
        const val FOREGROUND_DELAY_MS = 20
        const val BACKGROUND_LEAD_MS = 200L
        const val CALL_TIMEOUT_MS = 8_000L
        const val JOIN_TIMEOUT_MS = 12_000L
        const val SCENARIO_TIMEOUT_MS = 35_000L
        const val FIXTURE_URL_PREFIX = "https://example.test/source-scheduling/"
    }
}

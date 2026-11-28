package com.leyu.melora.playback.lx

import android.content.Context
import com.leyu.melora.playback.AudioCacheStore
import com.leyu.melora.playback.AudioSpecification
import com.leyu.melora.playback.NetworkState
import com.leyu.melora.playback.PlatformSource
import com.leyu.melora.playback.isNonAudioResponseContentType
import com.leyu.melora.playback.probeDownloadUpgrade
import com.leyu.melora.playback.readResponse
import com.leyu.melora.playback.sdk.LxScriptPool
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** 用户主动检查时使用独立的脚本实例和有限的媒体前缀，不写入播放解析或磁盘音频缓存。 */
internal object LxInspector {
    private const val CATALOG_TIMEOUT_MS = 5_000L
    private const val SCRIPT_TIMEOUT_MS = 6_000L
    private const val MEDIA_TIMEOUT_MS = 4_000L
    private const val MEDIA_PREFIX_LIMIT = 128 * 1024
    private val sample = OnlineSong(JSONObject()
        .put("source", "kw")
        .put("songmid", "296504")
        .put("name", "遇见")
        .put("singer", "孙燕姿")
        .put("albumName", "The Moment")
        .put("interval", "03:30"))
    val sampleLabel = "${sample.name} · ${sample.singer}"

    enum class LinkStatus { VALID, INVALID, INCONCLUSIVE }

    data class LinkProbe(
        val status: LinkStatus,
        val message: String,
        val spec: AudioSpecification? = null,
    )

    data class PlatformTest(
        val source: String,
        val requestedQuality: String?,
        val result: LinkProbe? = null,
        val elapsedMs: Long? = null,
    )

    data class ValidityTest(
        val declaredPlatforms: List<String>,
        val platforms: List<PlatformTest>,
    )

    fun testValidity(
        context: Context,
        store: LxScriptStore,
        script: LxScript,
        includePlayback: Boolean = true,
    ): Flow<ValidityTest> = flow {
        val code = store.code(script.id) ?: error("脚本文件不存在")
        LxScriptEngine(context.applicationContext).use { engine ->
            val sources = LxScriptPool.declaredPlatforms(engine.inspectSource(code, script.id))
            val tests = mutableListOf<PlatformTest>()
            if (includePlayback) {
                val qualities = SourceResolver.qualityAttempts(NetworkState.playQuality(context))
                PlatformSource.boardOrder.mapNotNullTo(tests) { platform ->
                    val support = sources[platform.id]?.takeIf { "musicUrl" in it.actions } ?: return@mapNotNullTo null
                    val quality = qualities.firstNotNullOfOrNull { SourceResolver.selectQuality(it, support.qualitys) }
                    PlatformTest(platform.id, quality)
                }
            }
            val report = ValidityTest(sources.keys.toList(), tests.toList())
            emit(report)
            for (index in tests.indices) {
                tests[index] = testPlatform(context, engine, script.id, tests[index])
                emit(report.copy(platforms = tests.toList()))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun testPlatform(
        context: Context,
        engine: LxScriptEngine,
        scriptId: String,
        test: PlatformTest,
    ): PlatformTest {
        val start = System.nanoTime()
        fun finish(result: LinkProbe) = test.copy(
            result = result,
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
        )
        val quality = test.requestedQuality
            ?: return finish(LinkProbe(LinkStatus.INCONCLUSIVE, "当前播放音质没有兼容档位"))
        val page = try {
            withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                OnlineRepository.search(context, test.source, "${sample.name} ${sample.singer}", limit = 12, background = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return finish(LinkProbe(LinkStatus.INCONCLUSIVE, "目录查询失败"))
        } ?: return finish(LinkProbe(LinkStatus.INCONCLUSIVE, "目录查询超时"))
        val song = pickCatalogSample(test.source, page.list)
            ?: return finish(LinkProbe(LinkStatus.INCONCLUSIVE, "目录中找不到这首测试录音"))

        val info = JSONObject().put("type", quality).put("musicInfo", song.raw)
        val data = try {
            withTimeoutOrNull(SCRIPT_TIMEOUT_MS) {
                engine.withCancellation { token ->
                    engine.request(token, test.source, "musicUrl", info)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LxScriptRequestException) {
            val message = if (error.isMediaUnavailable) "这首录音在该档位无资源" else "脚本解析失败"
            return finish(LinkProbe(LinkStatus.INCONCLUSIVE, message))
        } catch (_: Exception) {
            return finish(LinkProbe(LinkStatus.INCONCLUSIVE, "脚本解析失败"))
        } ?: return finish(LinkProbe(LinkStatus.INCONCLUSIVE, "脚本解析超时"))
        val resolved = SourceResolver.scriptResolution(song, LxScriptPool.ScriptResult(quality, scriptId, data))
            ?: return finish(LinkProbe(
                LinkStatus.INVALID,
                if (SourceResolver.extractUrl(data) == null) "脚本未返回播放链接" else "返回的录音与测试曲目不匹配",
            ))
        return finish(probeMedia(resolved.url))
    }

    internal fun pickCatalogSample(source: String, songs: List<OnlineSong>): OnlineSong? =
        songs.asSequence().filter { it.source == source }
            .mapNotNull { song -> SourceResolver.alternativeScore(sample, song)?.let { score -> song to score } }
            .maxByOrNull { it.second }?.first

    suspend fun probeMedia(url: String): LinkProbe {
        val address = url.toHttpUrlOrNull() ?: return LinkProbe(LinkStatus.INVALID, "播放链接格式错误")
        val request = Request.Builder()
            .url(address)
            .header("User-Agent", AudioCacheStore.USER_AGENT)
            .header("Range", "bytes=0-${MEDIA_PREFIX_LIMIT - 1}")
            .build()
        return try {
            withTimeoutOrNull(MEDIA_TIMEOUT_MS) {
                val call = AudioCacheStore.mediaHttpClient.newCall(request)
                call.readResponse { response ->
                    try {
                        probeResponse(response)
                    } finally {
                        // 服务端忽略 Range 时，阻止连接复用前继续读取整个响应体。
                        if (response.body.contentLength() !in 0L..MEDIA_PREFIX_LIMIT.toLong()) call.cancel()
                    }
                }
            } ?: LinkProbe(LinkStatus.INCONCLUSIVE, "链接读取超时")
        } catch (_: InterruptedIOException) {
            LinkProbe(LinkStatus.INCONCLUSIVE, "链接读取超时")
        } catch (_: IOException) {
            LinkProbe(LinkStatus.INCONCLUSIVE, "播放链接读取失败")
        }
    }

    private fun probeResponse(response: Response): LinkProbe {
        if (!response.isSuccessful) {
            val status = when (response.code) {
                401, 403, 404, 410 -> LinkStatus.INVALID
                else -> LinkStatus.INCONCLUSIVE
            }
            return LinkProbe(status, "打开链接失败 · HTTP ${response.code}")
        }
        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (type == "text/html" || type == "application/xhtml+xml") return LinkProbe(LinkStatus.INVALID, "链接返回网页")
        if (isNonAudioResponseContentType(type)) return LinkProbe(LinkStatus.INVALID, "链接返回非音频内容")
        val prefix = response.peekBody(512).string().trimStart { it.isWhitespace() || it == '\uFEFF' }
        if (prefix.startsWith("<!doctype html", ignoreCase = true) || prefix.startsWith("<html", ignoreCase = true)) {
            return LinkProbe(LinkStatus.INVALID, "链接返回网页")
        }
        if (prefix.startsWith("#EXTM3U")) {
            return LinkProbe(LinkStatus.INCONCLUSIVE, "链接返回播放列表，尚未验证音频")
        }
        val probe = probeDownloadUpgrade(response.body.byteStream())
        return when {
            probe.spec != null -> LinkProbe(LinkStatus.VALID, "已识别音频", probe.spec)
            probe.prefix.isEmpty() -> LinkProbe(LinkStatus.INVALID, "链接返回空内容")
            else -> LinkProbe(LinkStatus.INCONCLUSIVE, "链接已响应，暂无法确认音频格式")
        }
    }
}

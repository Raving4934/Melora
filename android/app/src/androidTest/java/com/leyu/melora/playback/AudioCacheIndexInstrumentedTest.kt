package com.leyu.melora.playback

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 独立缓存目录：验证真实元数据落盘、跨平台读取原字节以及淘汰，不访问音源或日常缓存。 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioCacheIndexInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "audio-cache-index-test-${System.nanoTime()}")
    private lateinit var database: StandaloneDatabaseProvider
    private var cache: SimpleCache? = null

    @Before fun setup() {
        SourceResolver.clearCache()
        database = StandaloneDatabaseProvider(context)
        cache = SimpleCache(directory, NoOpCacheEvictor(), database)
    }

    @After fun cleanup() {
        cache?.release()
        SimpleCache.delete(directory, database)
        database.close()
        SourceResolver.clearCache()
    }

    @Test fun crossSourceBytesAndVerifiedQualitySurviveReopenWithoutCopying() {
        val original = song("kw")
        val target = song("tx")
        val bytes = ByteArray(16_384) { (it % 251).toByte() }
        val first = checkNotNull(cache)
        val resource = AudioCacheIndex(first).register(original.uid, "320k", resolved(original))
        write(first, resource, bytes)
        AudioCacheIndex(first).recordObservedQuality("fixture:file", "128k")
        val size = first.cacheSpace
        first.release()
        cache = null
        SourceResolver.clearCache()
        val reopened = SimpleCache(directory, NoOpCacheEvictor(), database).also { cache = it }
        val index = AudioCacheIndex(reopened)
        assertNull(index.find(target, "320k", online = true))
        repeat(3) {
            val hit = checkNotNull(index.find(target, "128k", online = true))
            assertEquals(resource.key, hit.key)
            assertEquals("128k", hit.actualQuality)
            // 生产播放入口的数据规格；没有 upstream，任何漏读都会直接失败而不是偷偷联网。
            val spec = AudioCacheStore.applyToDataSpec(hit, DataSpec.Builder().setUri("melora://song/${target.uid}").build())
            val source = CacheDataSource.Factory().setCache(reopened).createDataSource()
            assertArrayEquals(bytes, DataSourceInputStream(source, spec).use { it.readBytes() })
        }
        assertEquals(size, reopened.cacheSpace)
        assertFalse(reopened.keys.any { it.startsWith("melora://audio/${target.uid}?") })
        reopened.removeResource(resource.key)
        assertNull(index.find(target, "128k", online = true))
    }

    @Test fun rejectedSharedResourceCannotBeReusedThroughAnyUidOrReRegisteredAlias() {
        val current = checkNotNull(cache)
        val index = AudioCacheIndex(current)
        val original = song("kw", "owner")
        val consumer = song("tx", "consumer")
        val thirdParty = song("wy", "third-party")
        val bytes = ByteArray(12_288) { (it % 239).toByte() }
        val resource = index.register(original.uid, "320k", resolved(original))
        val resourceId = checkNotNull(audioResourceId(resource.key))
        write(current, resource, bytes)
        index.recordObservedQuality(resourceId, "320k")
        val originalCacheSize = current.cacheSpace

        assertEquals(resource.key, index.find(consumer, "320k", online = true)?.key)
        SourceResolver.rejectResource(resourceId)
        listOf(original, consumer, thirdParty).forEach { song ->
            assertNull("$song must not reuse a cooling-down physical resource",
                index.find(song, "320k", online = true))
        }

        // 重新登记同一物理资源会重建各 UID 的偏好别名，但不能绕过资源级冷却。
        listOf(original, consumer, thirdParty).forEach { aliasOwner ->
            val alias = index.register(aliasOwner.uid, "320k", resolved(original))
            assertEquals(resourceId, audioResourceId(alias.key))
            assertNull("re-registering an alias must not reopen a rejected resource",
                index.find(aliasOwner, "320k", online = true))
        }

        SourceResolver.clearCache()
        index.recordObservedQuality(resourceId, "320k")
        assertFalse(SourceResolver.isRejected(resourceId))
        assertEquals("clearing the cooldown must not delete or rewrite cached audio bytes",
            originalCacheSize, current.cacheSpace)
        val reusable = checkNotNull(index.find(consumer, "320k", online = true))
        assertEquals(resource.key, reusable.key)
        val spec = AudioCacheStore.applyToDataSpec(reusable,
            DataSpec.Builder().setUri("melora://song/${consumer.uid}").build())
        val source = CacheDataSource.Factory().setCache(current).createDataSource()
        assertArrayEquals(bytes, DataSourceInputStream(source, spec).use { it.readBytes() })
        assertEquals(originalCacheSize, current.cacheSpace)
    }

    @Test fun interruptedFillIsNotSharedUntilAllBytesArePresent() {
        val current = checkNotNull(cache)
        val index = AudioCacheIndex(current)
        val original = song("kw")
        val resource = index.register(original.uid, "320k", resolved(original))
        val bytes = ByteArray(8_192) { 42 }
        val metadata = androidx.media3.datasource.cache.ContentMetadataMutations().apply {
            androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(this, bytes.size.toLong())
        }
        current.applyContentMetadataMutations(resource.key, metadata)
        // 模拟取消后的前半段，不允许把另一个平台的数据接到它后面。
        val hole = checkNotNull(current.startReadWrite(resource.key, 0, 4096))
        try {
            val file = current.startFile(resource.key, 0, 4096)
            file.writeBytes(bytes.copyOf(4096))
            current.commitFile(file, 4096)
        } finally {
            current.releaseHoleSpan(hole)
        }
        assertNull(index.find(song("tx"), "320k", online = true))
        write(current, resource, bytes)
        index.recordObservedQuality("fixture:file", "320k")
        assertEquals(resource.key, index.find(song("tx"), "320k", online = true)?.key)
    }

    private fun write(cache: SimpleCache, resource: AudioCacheResource, bytes: ByteArray) {
        val source = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(bytes) }.createDataSource()
        val spec = DataSpec.Builder().setUri("https://fixture.invalid/song.mp3").setKey(resource.key).build()
        CacheWriter(source, spec, null, null).cache()
    }

    private fun song(source: String, id: String = "fixture") = OnlineSong(JSONObject()
        .put("source", source).put("songmid", id).put("name", "缓存测试")
        .put("singer", "测试歌手").put("albumName", "测试专辑").put("interval", "03:20"))

    private fun resolved(song: OnlineSong) = SourceResolver.Resolved(
        "https://fixture.invalid/song.mp3", "320k", song, false, "fixture:file",
    )
}

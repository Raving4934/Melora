package com.leyu.melora.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class AudioCacheIndexTest {
    @Before
    fun clearResolverBeforeTest() {
        SourceResolver.clearCache()
    }

    @After
    fun clearResolverAfterTest() {
        SourceResolver.clearCache()
    }

    @Test
    fun registrationIndexesResolvedSongAndCrossPlatformFindReusesOnlyThePhysicalResource() {
        val cache = CacheFixture()
        val index = AudioCacheIndex(cache.cache)
        val requested = song(source = "kw", id = "requested", name = "Wrong requested title")
        val resolvedSong = song(source = "kg", id = "resolved")
        val thirdPlatformCopy = song(source = "wy", id = "third-platform")
        val resolved = resolved(resolvedSong, quality = "320k", resourceId = "lx:fixture:one")

        val resource = index.register(requested.uid, "320k", resolved)
        cache.complete(resource.key, 100L, 0L to 100L)
        index.recordObservedQuality(resolved.resourceId, "320k")
        val keysBeforeLookup = cache.keys()
        val bytesBeforeLookup = cache.cacheSpace()
        val spansBeforeLookup = cache.spanCount()

        assertEquals(resolved.url, resource.sourceUrl)
        assertEquals("320k", resource.actualQuality)
        assertSameResource(resource, index.find(requested, "320k", online = true))
        assertSameResource(resource, index.find(resolvedSong, "320k", online = true))
        assertSameResource(resource, index.find(thirdPlatformCopy, "320k", online = false))
        assertNull(index.find(song("tx", "wrong", name = requested.name), "320k", online = true))

        // 跨 UID 命中只返回原 key，不复制 span，也不为请求曲目创建新 alias。
        assertEquals(keysBeforeLookup, cache.keys())
        assertEquals(bytesBeforeLookup, cache.cacheSpace())
        assertEquals(spansBeforeLookup, cache.spanCount())
        assertEquals(1, cache.physicalKeyCount())
    }

    @Test
    fun crossUidRequiresSameTierOnlineOrOfflineButOriginalUidKeepsOfflineFallback() {
        val cache = CacheFixture()
        val index = AudioCacheIndex(cache.cache)
        val cachedSong = song(source = "kg", id = "cached", interval = "03:30")
        val requestedCopy = song(source = "kw", id = "requested", interval = "03:31")
        val resource = registerComplete(index, cache, cachedSong, quality = "128k")

        assertSameResource(resource, index.find(cachedSong, "320k", online = false))
        assertNull(index.find(cachedSong, "320k", online = true))
        assertNull(index.find(requestedCopy, "320k", online = false))
        assertNull(index.find(requestedCopy, "320k", online = true))

        // 同一音质 tier 可以跨 UID 共享，无论当前是否联网。
        val highQualityCache = CacheFixture()
        val highQualityIndex = AudioCacheIndex(highQualityCache.cache)
        val highQualitySong = song(source = "kg", id = "high-quality")
        val highQuality = registerComplete(highQualityIndex, highQualityCache, highQualitySong, quality = "flac24bit")
        assertSameResource(highQuality, highQualityIndex.find(requestedCopy, "hires", online = false))
        assertSameResource(highQuality, highQualityIndex.find(requestedCopy, "master", online = true))
    }

    @Test
    fun crossUidRejectsUnknownArtistsWrongArtistsVersionsDurationAndAudiobooks() {
        val target = song(source = "kw", id = "target", interval = "03:30")
        val invalidCandidates = listOf(
            song(source = "kg", id = "unknown-artist", singer = "", interval = "03:30"),
            song(source = "kg", id = "wrong-artist", singer = "Another Artist", interval = "03:30"),
            song(source = "kg", id = "wrong-version", name = "Northern Lights (Live)", interval = "03:30"),
            song(source = "kg", id = "live-album", album = "Live Tour", interval = "03:30"),
            song(source = "kg", id = "radio-edit-album", album = "Blue Hour (Radio Edit)"),
            song(source = "kg", id = "demo-album", album = "Blue Hour (Demo)"),
            song(source = "kg", id = "alternate-take-album", album = "Blue Hour (Alternate Take)"),
            song(source = "kg", id = "unknown-placeholder", singer = "未知歌手", interval = "03:30"),
            song(source = "kg", id = "unknown-duration-same-album", interval = "00:00"),
            // alternativeScore 本身容忍略大的差值；索引跨 UID 规则收紧到双方已知时长最多相差 2 秒。
            song(source = "kg", id = "duration-3s", interval = "03:33"),
            song(source = "kg", id = "book", interval = "03:30", isBookChapter = true),
            song(source = "kg", id = "insufficient-evidence", album = "", interval = "00:00"),
        )

        invalidCandidates.forEach { candidate ->
            val cache = CacheFixture()
            val index = AudioCacheIndex(cache.cache)
            registerComplete(index, cache, candidate)
            assertNull("unexpected cross-source match for ${candidate.songmid}", index.find(target, "320k", online = false))
        }
    }

    @Test
    fun explicitExclusionAndSourceResolverRejectionHideAResource() {
        val cache = CacheFixture()
        val index = AudioCacheIndex(cache.cache)
        val target = song(source = "kw", id = "target")
        val cachedSong = song(source = "kg", id = "cached")
        val resource = registerComplete(index, cache, cachedSong)

        assertSameResource(resource, index.find(target, "320k", online = true))
        assertNull(index.find(target, "320k", online = true, excludedResources = setOf(resourceId(resource))))

        SourceResolver.rejectResource(resourceId(resource))
        assertNull(index.find(target, "320k", online = false))
        assertNull(index.find(target, "320k", online = true))
        assertNull(index.find(song("tx", "next-consumer"), "320k", online = true))
        // 别的消费者已判失败，原 UID 的偏好别名和无别名的离线降档均不能绕过。
        assertNull(index.find(cachedSong, "320k", online = true))
        assertNull(index.find(cachedSong, "flac", online = false))
        assertSameResource(resource, index.register(cachedSong.uid, "flac", resolved(cachedSong)))
        assertNull(index.find(cachedSong, "flac", online = false))
        SourceResolver.clearCache() // 现有失败冷却是临时状态，不持久封禁一份可能恢复的资源。
        assertSameResource(resource, index.find(song("tx", "next-consumer"), "320k", online = true))
        assertSameResource(resource, index.find(cachedSong, "320k", online = true))
        assertSameResource(resource, index.find(cachedSong, "flac", online = false))
    }

    @Test
    fun partialSpansAndHolesAreNotCompleteAndEvictionOrClearRemovesFindResults() {
        val cache = CacheFixture()
        val registeringIndex = AudioCacheIndex(cache.cache)
        val target = song(source = "kw", id = "target")
        val cachedSong = song(source = "kg", id = "cached")
        val resource = registeringIndex.register(cachedSong.uid, "320k", resolved(cachedSong, quality = "128k"))

        registeringIndex.recordObservedQuality(resourceId(resource), "128k")
        cache.setLength(resource.key, 100L)
        cache.addSpan(resource.key, 0L, 40L)
        cache.addSpan(resource.key, 50L, 50L)

        // 索引重建时，不完整物理资源不可命中；但其音质 metadata 仍需恢复给 SourceResolver。
        val resourceId = resourceId(resource)
        SourceResolver.clearCache()
        val index = AudioCacheIndex(cache.cache)
        assertNull(index.find(cachedSong, "320k", online = false))
        assertEquals("128k", SourceResolver.observedQuality(resourceId))
        assertNull(index.find(target, "128k", online = false))

        cache.addSpan(resource.key, 40L, 10L)
        assertSameResource(resource, index.find(target, "128k", online = false))

        cache.evict(resource.key)
        assertNull(index.find(target, "320k", online = false))

        val second = registerComplete(index, cache, cachedSong.copy(raw = JSONObject(cachedSong.raw.toString()).put("songmid", "cached-2")))
        assertNotNull(index.find(target, "320k", online = false))
        cache.clear()
        assertNull(index.find(target, "320k", online = false))
        assertFalse(second.key in cache.keys())
    }

    @Test
    fun legacyOrDamagedMetadataRemainsUsableByItsUidButCannotIdentifyAnotherSong() {
        val target = song(source = "kw", id = "legacy")
        val crossPlatformCopy = song(source = "kg", id = "same-recording")

        listOf(false, true).forEach { damaged ->
            val cache = CacheFixture()
            val index = AudioCacheIndex(cache.cache)
            val key = audioResourceKey(target.uid, "128k", "legacy-source", if (damaged) "damaged-resource" else "old-resource")
            val mutations = ContentMetadataMutations()
                .set(ContentMetadata.KEY_CUSTOM_PREFIX + "melora.source_url", "https://legacy.test/audio.mp3")
                .set(ContentMetadata.KEY_CUSTOM_PREFIX + "melora.source_identity", "legacy-source")
                .set(ContentMetadata.KEY_CUSTOM_PREFIX + "melora.actual_quality", "128k")
                .set(ContentMetadata.KEY_CUSTOM_PREFIX + "melora.verified_quality", "128k")
            if (damaged) mutations.set(ContentMetadata.KEY_CUSTOM_PREFIX + "melora.recording", "{broken-json")
            cache.apply(key, mutations)
            cache.complete(key, 64L, 0L to 64L)

            assertNotNull("same-UID cached resource should survive damaged=$damaged", index.find(target, "320k", online = false))
            assertNull("missing song identity must not enable cross-UID sharing", index.find(crossPlatformCopy, "128k", online = false))
        }
    }

    @Test
    fun observedQualityIsStoredByResourceIdAndSurvivesIndexRecreation() {
        val cache = CacheFixture()
        val firstIndex = AudioCacheIndex(cache.cache)
        val cachedSong = song(source = "kg", id = "cached")
        val target = song(source = "kw", id = "target")
        val resource = registerComplete(firstIndex, cache, cachedSong, quality = "128k", resourceId = "stable-resource-id")

        assertNull(AudioCacheIndex(cache.cache).find(target, "320k", online = true))
        val keysBeforeQualityWrite = cache.keys()
        val writesBeforeQualityWrite = cache.metadataWriteCount
        firstIndex.recordObservedQuality("stable-resource-id", "320k")
        assertEquals(keysBeforeQualityWrite, cache.keys())
        assertEquals(writesBeforeQualityWrite + 1, cache.metadataWriteCount)

        SourceResolver.clearCache()
        val rebuiltIndex = AudioCacheIndex(cache.cache)
        val found = rebuiltIndex.find(target, "320k", online = true)
        assertSameResource(resource, found)
        assertEquals("320k", found?.actualQuality)
    }

    @Test
    fun sameAlbumAndClosestDurationWinAndRepeatedBatchReadsAreIdempotent() {
        val cache = CacheFixture()
        val index = AudioCacheIndex(cache.cache)
        val target = song(source = "kw", id = "target", interval = "03:30")
        val otherAlbum = song(source = "wy", id = "other-album", album = "Different Album", interval = "03:30")
        val sameAlbumFarther = song(source = "kg", id = "same-album-farther", album = "Blue Hour", interval = "03:32")
        val sameAlbumClosest = song(source = "tx", id = "same-album-closest", album = "Blue Hour", interval = "03:31")
        registerComplete(index, cache, otherAlbum, resourceId = "other-album-resource")
        registerComplete(index, cache, sameAlbumFarther, resourceId = "farther-resource")
        val preferred = registerComplete(index, cache, sameAlbumClosest, resourceId = "closest-resource")

        val keysBeforeReads = cache.keys()
        val metadataWritesBeforeReads = cache.metadataWriteCount
        val batch = List(100) { song("kw", "target-$it") }.map { index.find(it, "320k", online = true) }

        batch.forEach { assertSameResource(preferred, it) }
        assertEquals(keysBeforeReads, cache.keys())
        assertEquals(metadataWritesBeforeReads, cache.metadataWriteCount)
    }

    @Test
    fun unverifiedQualityCannotBeSharedAndLiveObservationWinsOverStaleDiskValue() {
        val cache = CacheFixture()
        val index = AudioCacheIndex(cache.cache)
        val original = song("kw", "original")
        val target = song("tx", "target")
        val resource = index.register(original.uid, "flac", resolved(original, "flac", "mislabelled"))
        cache.complete(resource.key, 100, 0L to 100L)
        // 原 UID 按原有规则保留；其它 UID 不可信任“声明无损”。
        assertSameResource(resource, index.find(original, "flac", online = true))
        assertNull(index.find(target, "flac", online = true))
        SourceResolver.confirmQuality("mislabelled", "320k")
        assertNull(index.find(target, "flac", online = true))
        assertEquals("320k", index.find(target, "320k", online = true)?.actualQuality)
        index.recordObservedQuality("mislabelled", "320k")
        SourceResolver.clearCache()
        assertSameResource(resource, index.find(target, "320k", online = true))
        // 异步落盘前的新观察值优先，旧 metadata 不得覆盖它。
        SourceResolver.confirmQuality("mislabelled", "128k")
        assertNull(index.find(original, "320k", online = true))
        assertNull(index.find(target, "320k", online = true))
        assertEquals("128k", index.find(target, "128k", online = true)?.actualQuality)
    }

    @Test
    fun unknownRequestDurationOrAudiobookCannotMatchAndOwnResourceHasPriority() {
        val cache = CacheFixture()
        val index = AudioCacheIndex(cache.cache)
        registerComplete(index, cache, song("kw", "candidate"))
        assertNull(index.find(song("tx", "unknown", interval = "00:00"), "320k", online = true))
        assertNull(index.find(song("tx", "book", isBookChapter = true), "320k", online = true))
        val target = song("tx", "target")
        val own = registerComplete(index, cache, target)
        assertSameResource(own, index.find(target, "320k", online = true))
        // 同一文件重复注册不增加物理key，也不丢失之前的完整片段。
        assertSameResource(own, index.register(target.uid, "320k", resolved(target)))
        assertSameResource(own, index.find(target, "320k", online = true))
    }

    @Test
    fun explicitEditAndTakeVersionsShareTheExistingResolverRules() {
        val original = song("kw", "original")
        listOf("Radio Edit", "Demo", "Alternate Take").forEach { version ->
            val candidate = song("kg", "candidate", album = "Blue Hour ($version)")
            assertFalse(SourceResolver.sameRecordingVersion(original, candidate))
            assertNull(SourceResolver.alternativeScore(original, candidate))
            val requested = song("tx", "requested", album = candidate.albumName)
            assertTrue(SourceResolver.sameRecordingVersion(requested, candidate))
            val cache = CacheFixture()
            val index = AudioCacheIndex(cache.cache)
            val resource = registerComplete(index, cache, candidate)
            assertSameResource(resource, index.find(requested, "320k", online = true))
        }
    }

    private fun registerComplete(
        index: AudioCacheIndex,
        cache: CacheFixture,
        cachedSong: OnlineSong,
        quality: String = "320k",
        resourceId: String = "resource-${cachedSong.uid}",
    ): AudioCacheResource {
        val resource = index.register(cachedSong.uid, quality, resolved(cachedSong, quality, resourceId))
        cache.complete(resource.key, 100L, 0L to 100L)
        index.recordObservedQuality(resourceId, quality)
        return resource
    }

    private fun resolved(
        song: OnlineSong,
        quality: String = "320k",
        resourceId: String = "resource-${song.uid}",
    ) = SourceResolver.Resolved(
        url = "https://audio.example/${song.source}/${song.songmid}.mp3",
        quality = quality,
        song = song,
        switched = false,
        resourceId = resourceId,
    )

    private fun song(
        source: String,
        id: String,
        name: String = "Northern Lights",
        singer: String = "Mira",
        album: String = "Blue Hour",
        interval: String = "03:30",
        isBookChapter: Boolean = false,
    ): OnlineSong = OnlineSong(
        JSONObject()
            .put("source", source)
            .put("songmid", id)
            .put("name", name)
            .put("singer", singer)
            .put("albumName", album)
            .put("interval", interval)
            .put("isBookChapter", isBookChapter),
    )

    private fun resourceId(resource: AudioCacheResource): String =
        checkNotNull(audioResourceId(resource.key))

    private fun assertSameResource(expected: AudioCacheResource, actual: AudioCacheResource?) {
        assertNotNull(actual)
        assertEquals(expected.key, actual!!.key)
    }

    /** 真实 Media3 metadata mutation；明确模拟连续片段，缺一个字节也不算完整。 */
    private class CacheFixture {
        private val metadata = linkedMapOf<String, DefaultContentMetadata>()
        private val spans = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
        var metadataWriteCount = 0
            private set

        val cache: Cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { proxy, method, args ->
            val values = args ?: emptyArray()
            when (method.name) {
                "getKeys" -> keys()
                "getContentMetadata" -> metadata[values[0] as String] ?: DefaultContentMetadata.EMPTY
                "applyContentMetadataMutations" -> {
                    apply(values[0] as String, values[1] as ContentMetadataMutations)
                    null
                }
                "isCached" -> {
                    val key = values[0] as String
                    var cursor = values[1] as Long
                    val end = cursor + (values[2] as Long)
                    for ((position, length) in spans[key].orEmpty().sortedBy { it.first }) {
                        if (position > cursor) break
                        cursor = maxOf(cursor, position + length)
                    }
                    cursor >= end
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === values[0]
                "toString" -> "AudioCacheIndexFixture"
                else -> error("unexpected Cache call: ${method.name}")
            }
        } as Cache

        fun apply(key: String, mutations: ContentMetadataMutations) {
            metadata[key] = (metadata[key] ?: DefaultContentMetadata.EMPTY).copyWithMutationsApplied(mutations)
            metadataWriteCount++
        }

        fun setLength(key: String, length: Long) {
            apply(key, ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, length) })
        }

        fun complete(key: String, length: Long, vararg ranges: Pair<Long, Long>) {
            setLength(key, length)
            spans[key] = ranges.toMutableList()
        }

        fun addSpan(key: String, position: Long, length: Long) {
            spans.getOrPut(key, ::mutableListOf).add(position to length)
        }

        fun evict(key: String) {
            spans.remove(key)
            metadata.remove(key)
        }

        fun clear() = keys().forEach(::evict)
        fun keys(): Set<String> = metadata.keys.toSet()
        fun physicalKeyCount(): Int = spans.count { (_, value) -> value.isNotEmpty() }
        fun spanCount(): Int = spans.values.sumOf { it.size }
        fun cacheSpace(): Long = spans.values.flatten().sumOf { it.second }
    }
}

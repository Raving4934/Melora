package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** 从独立完整缓存导出真实MP3/FLAC到测试SAF provider；不联网、不播放、不访问用户目录。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class DownloadQualityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = FixtureContext(instrumentation.targetContext)
    private val tree get() = DocumentsContract.buildTreeDocumentUri("${instrumentation.context.packageName}.download-fixture", "root")
    private val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "download-quality-test")
        .put("name", "Fixture").put("singer", "Test Artist").put("albumName", "Fixture Album").put("interval", "00:01"))
    private var ownsCache = false

    @Before fun setup() {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        instrumentation.context.startActivity(android.content.Intent().setClassName(instrumentation.context.packageName,
            "com.leyu.melora.playback.DownloadFixtureProvider\$GrantActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), flags) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(25)
        assertEquals("测试目录授权", android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(), flags))
        context.contentResolver.call(tree, "reset", null, null)
        MeloraSettings.init(context)
        MeloraSettings.downloadPath.value = tree.toString()
        MeloraSettings.downloadAutoSwitchSource.value = false
        MeloraSettings.downloadSkipSameName.value = true
        MeloraSettings.downloadEmbedCover.value = false
        MeloraSettings.downloadEmbedLyric.value = false
        MeloraSettings.downloadFileNameFormat.value = "song-artist"
        DownloadCenter.init(context)
        DownloadCenter.remove(song.uid)
        LocalMediaStore.init(context)
        LocalMediaStore.clear()
        SourceResolver.clearCache()
    }

    @After fun cleanup() {
        DownloadCenter.remove(song.uid)
        LocalMediaStore.clear()
        SourceResolver.clearCache()
        if (ownsCache) {
            val field = AudioCacheStore.javaClass.getDeclaredField("cache").apply { isAccessible = true }
            (field.get(AudioCacheStore) as? SimpleCache)?.release()
            field.set(AudioCacheStore, null)
        }
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(song.uid.hashCode())
        context.contentResolver.call(tree, "reset", null, null)
        context.getSharedPreferences(MeloraSettings.PREFS, 0).edit().clear().commit()
        context.root.deleteRecursively()
    }

    private fun seed(requested: String, file: String, reported: String = requested) {
        val payload = instrumentation.context.assets.open("audio/$file").use { it.readBytes() }
        val resource = AudioCacheStore.registerResolved(context, song.uid, requested,
            SourceResolver.Resolved("https://example.test/$requested/$file", reported, song, false, "lx:download-test:$requested:$file"))
        ownsCache = true
        val cache = AudioCacheStore.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(AudioCacheStore) as SimpleCache
        val hole = cache.startReadWrite(resource.key, 0, payload.size.toLong())
        try {
            val data = cache.startFile(resource.key, 0, payload.size.toLong())
            data.writeBytes(payload); cache.commitFile(data, payload.size.toLong())
            cache.applyContentMetadataMutations(resource.key, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, payload.size.toLong())
            })
        } finally { cache.releaseHoleSpan(hole) }
    }
    private suspend fun download(quality: String): String {
        MeloraSettings.downloadQuality.value = quality
        return Downloader.download(context, song).getOrThrow()
    }
    private fun files(): List<String> = context.contentResolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root"),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    private fun bytes(uri: String) = context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }

    @Test fun mp3CanUpgradeToReal24bitWithoutDeletingOriginalAndRepeatedDownloadSkips() = runBlocking<Unit> {
        seed("128k", "fixture-128.mp3"); download("128k")
        val old = requireNotNull(DownloadCenter.saved(song.uid)); val original = bytes(old.savedUri!!)
        assertEquals("128K", old.audioSpec?.qualityBadge)
        seed("flac24bit", "fixture-24.flac"); assertTrue(download("flac24bit").contains("缓存"))
        val upgraded = requireNotNull(DownloadCenter.saved(song.uid))
        assertEquals(24, upgraded.audioSpec?.bitDepth)
        assertArrayEquals(original, bytes(old.savedUri))
        assertEquals(2, files().size)
        assertTrue(download("flac24bit").contains("已跳过"))
        assertEquals(2, files().size)
        assertEquals(upgraded.savedUri, DownloadCenter.saved(song.uid)?.savedUri)
    }

    @Test fun sameFlacExtensionUpgradesAndStaleDeleteCannotRemoveNewFile() = runBlocking<Unit> {
        seed("flac", "fixture-16.flac"); download("flac")
        val old = requireNotNull(DownloadCenter.saved(song.uid)); val original = bytes(old.savedUri!!)
        seed("flac24bit", "fixture-24.flac"); download("flac24bit")
        val upgraded = requireNotNull(DownloadCenter.saved(song.uid))
        assertTrue(upgraded.fileName!!.contains("[HR]"))
        assertArrayEquals(original, bytes(old.savedUri))
        Downloader.deleteSaved(context, old).getOrThrow()
        assertEquals(upgraded.savedUri, DownloadCenter.saved(song.uid)?.savedUri)
        assertEquals(24, inspectDownloadAudio(context, Uri.parse(upgraded.savedUri))?.spec?.bitDepth)
        assertEquals(1, files().size)
    }

    @Test fun falselyClaimedHrThatIs128kDoesNotCreateDuplicateOrMislabelHr() = runBlocking<Unit> {
        seed("128k", "fixture-128.mp3"); download("128k")
        val oldUri = DownloadCenter.saved(song.uid)!!.savedUri
        seed("flac24bit", "fixture-128.mp3", "flac24bit")
        assertTrue(download("flac24bit").contains("已跳过"))
        assertEquals(1, files().size)
        assertEquals(oldUri, DownloadCenter.saved(song.uid)?.savedUri)
        assertEquals("128K", DownloadCenter.saved(song.uid)?.audioSpec?.qualityBadge)
    }

    @Test fun failedPublicationKeepsOldResourceAndRemovesOnlyStagingFile() = runBlocking<Unit> {
        seed("flac", "fixture-16.flac"); download("flac")
        val old = requireNotNull(DownloadCenter.saved(song.uid)); val original = bytes(old.savedUri!!)
        seed("flac24bit", "fixture-24.flac")
        context.contentResolver.call(tree, "failNextWrite", null, null)
        MeloraSettings.downloadQuality.value = "flac24bit"
        assertTrue(Downloader.download(context, song).isFailure)
        assertEquals(old.savedUri, DownloadCenter.saved(song.uid)?.savedUri)
        assertEquals(16, DownloadCenter.saved(song.uid)?.audioSpec?.bitDepth)
        assertArrayEquals(original, bytes(old.savedUri))
        assertEquals(1, files().size)
    }

    @Test fun headerOnlyFlacIsNotSkippedEvenWhenOldUriIsTrusted() = runBlocking<Unit> {
        seed("flac24bit", "fixture-24.flac"); download("flac24bit")
        val old = requireNotNull(DownloadCenter.saved(song.uid))
        val uri = Uri.parse(old.savedUri!!)
        val original = bytes(old.savedUri)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(original, 0, 42) }
        assertNull(inspectDownloadAudio(context, uri))
        assertFalse(download("flac24bit").contains("已跳过"))
        val repaired = requireNotNull(DownloadCenter.saved(song.uid))
        assertNotEquals(old.savedUri, repaired.savedUri)
        assertTrue(bytes(repaired.savedUri!!).size > 42)
        assertEquals(24, repaired.audioSpec?.bitDepth)
    }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "isolated-download-quality-tests").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile?.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("isolated-download-quality-tests.$name", mode)
    }
}

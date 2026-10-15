package com.leyu.melora.playback.local

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.BackupStateLock
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真实扫描/索引/用户库链路，使用独立文件目录和进程内 ContentResolver。 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29) // 已注册 provider；每个用例用 wrap 隔离实例，不依赖系统 URI 授权。
class LocalMediaScannerInstrumentedTest {
    private lateinit var context: ScanContext
    private val savedFields = mutableListOf<Triple<Any, Field, Any?>>()
    private val savedFlows = mutableListOf<Pair<MutableStateFlow<Any?>, Any?>>()
    private val provider get() = context.provider
    private var previousController: Any? = null

    @Before
    fun isolateFixture() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        // IsolatedTestRunner 使用空 Application；存储绑定完全由本夹具负责。
        context = ScanContext(base)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val controller = field(PlaybackController, "controller")
            previousController = controller.get(PlaybackController)
            controller.set(PlaybackController, null)
        }
        LocalMediaStore.withIndexLock { _ ->
            synchronized(BackupStateLock.monitor) {
                for (owner in listOf(LocalMediaStore, UserLibrary, MeloraSettings)) {
                    owner.javaClass.declaredFields.filter {
                        MutableStateFlow::class.java.isAssignableFrom(it.type)
                    }.forEach { declared ->
                        declared.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val flow = declared.get(owner) as MutableStateFlow<Any?>
                        savedFlows += flow to flow.value
                    }
                }
                saveField(UserLibrary, "file").set(UserLibrary, File(context.filesDir, "user-library.json"))
                for (name in listOf("appContext", "byUri", "byId", "byMatch", "byTitle")) {
                    saveField(LocalMediaStore, name)
                }
                UserLibrary.replaceFromBackup("{}")
                LocalMediaStore.init(context)
                MeloraSettings.localUseMediaStore.value = false
                MeloraSettings.localExcludeShort.value = false
                MeloraSettings.localExcludeSmall.value = false
                MeloraSettings.localAutoFillInfo.value = false
            }
        }
    }

    @After
    fun restoreFixture() {
        runBlocking {
            (field(PlaybackController, "localQueueCheck").get(PlaybackController) as? Job)?.cancelAndJoin()
        }
        LocalMediaStore.withIndexLock { _ ->
            synchronized(BackupStateLock.monitor) {
                savedFields.forEach { (owner, field, value) -> field.set(owner, value) }
                savedFlows.forEach { (flow, value) -> flow.value = value }
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            field(PlaybackController, "controller").set(PlaybackController, previousController)
        }
        if (::context.isInitialized) context.cleanup()
    }

    @Test
    fun successfulMediaQueryRetainsUnavailableVolumeButPrunesMissingMountedSongs() = runBlocking {
        val primary = mediaSong(1)
        val deleted = mediaSong(2)
        val offline = mediaSong(3).copy(folder = "/storage/MELORA-OFFLINE/Music")
        seed(listOf(primary, deleted, offline))
        listOf(primary, deleted, offline).forEach { UserLibrary.markPlayed(it.toOnlineSong()) }
        context.useMediaStore(listOf(1L), primary.folder)

        val result = LocalMediaScanner.scan(context)

        assertEquals(1, result.failedSources)
        assertEquals(setOf(primary.id, offline.id), LocalMediaStore.songs.value.map { it.id }.toSet())
        assertEquals(offline, LocalMediaStore.find(offline.id))
        assertEquals("scanned-1", LocalMediaStore.find(primary.id)?.title)
        assertMediaReferences(setOf(primary.id, offline.id))
    }

    @Test
    fun completedEmptyMountedMediaVolumeStillPrunesItsLibraryReferences() = runBlocking {
        val primary = mediaSong(1)
        seed(listOf(primary))
        UserLibrary.markPlayed(primary.toOnlineSong())
        context.useMediaStore(emptyList(), primary.folder)

        val result = LocalMediaScanner.scan(context)

        assertEquals(0, result.failedSources)
        assertMediaReferences(emptySet())
    }

    @Test
    fun mediaVolumeBecomingUnavailableDuringQueryRetainsItsOldReferences() = runBlocking {
        assertMediaVolumeTransition(availableBefore = true)
    }

    @Test
    fun mediaVolumeReturningDuringQueryDoesNotMakeAPartialResultAuthoritative() = runBlocking {
        assertMediaVolumeTransition(availableBefore = false)
    }

    private suspend fun assertMediaVolumeTransition(availableBefore: Boolean) {
        val primary = mediaSong(1)
        seed(listOf(primary))
        UserLibrary.markPlayed(primary.toOnlineSong())
        context.useMediaStore(emptyList(), primary.folder)
        context.storageAvailable = availableBefore
        context.mediaProvider.onQuery = Runnable { context.storageAvailable = !availableBefore }

        val result = LocalMediaScanner.scan(context)

        assertEquals(1, result.failedSources)
        assertMediaReferences(setOf(primary.id))
    }

    private fun mediaSong(id: Long) = song("unused", "unused/$id").copy(
        id = "ms_$id", uri = "content://media/external/audio/media/$id",
        folder = Environment.getExternalStorageDirectory().absolutePath + "/Melora-scan-fixture",
    )

    private fun assertMediaReferences(expected: Set<String>) {
        assertPersistedSongs(expected)
        assertEquals(expected, LocalMediaStore.songs.value.map { it.id }.toSet())
        UserLibrary.reloadAfterRestore()
        assertEquals(expected, UserLibrary.favorites.value.map { it.songmid }.toSet())
        assertEquals(expected, UserLibrary.playlists.value.single().songs.map { it.songmid }.toSet())
        assertEquals(expected, UserLibrary.recents.value.map { it.songmid }.toSet())
    }

    @Test
    fun emptyLoadingDirectoryRetainsWholeTreeWhileHealthyTreeUpdates() = runBlocking {
        assertLoadingTreePreserved(emptyList())
    }

    @Test
    fun partialLoadingDirectoryDoesNotCommitPartialRowsOrRemoveMissingSongs() = runBlocking {
        assertLoadingTreePreserved(listOf(
            LoadingDocumentsProvider.Document("loading/first", "changed.mp3"),
            LoadingDocumentsProvider.Document("loading/new", "partial-new.mp3"),
        ))
    }

    @Test
    fun nestedLoadingDirectoryDiscardsAlreadyScannedParentRowsAndRetainsWholeTree() = runBlocking {
        assertLoadingTreePreserved(listOf(
            LoadingDocumentsProvider.Document("loading/first", "changed-parent.mp3"),
            LoadingDocumentsProvider.Document("loading/new", "new-parent.mp3"),
            LoadingDocumentsProvider.Document("loading/nested", "nested", true),
        ), nested = true)
        assertTrue(provider.childQueries.contains("loading/nested"))
    }

    @Test
    fun completedEmptyDirectoryRemovesStaleSongsAndLibraryReferences() = runBlocking {
        assertCompletedEmptyDirectory(loading = false)
    }

    @Test
    fun emptyDirectoryWithoutLoadingExtraIsStillACompletedScan() = runBlocking {
        assertCompletedEmptyDirectory(loading = null)
    }

    private suspend fun assertLoadingTreePreserved(
        children: List<LoadingDocumentsProvider.Document>,
        nested: Boolean = false,
    ) {
        val retained = listOf(song("loading", "loading/first"), song("loading", "loading/nested/second"))
        val staleHealthy = song("healthy", "healthy/old")
        seed(retained + staleHealthy)
        provider.setChildren("loading", children, !nested)
        if (nested) provider.setChildren("loading/nested", listOf(
            LoadingDocumentsProvider.Document("loading/nested/second", "changed-child.mp3"),
        ), true)
        provider.setChildren("healthy", listOf(LoadingDocumentsProvider.Document("healthy/new", "healthy-new.mp3")), null)
        val favoriteUids = UserLibrary.favorites.value.map { it.uid }.filter { it != staleHealthy.toOnlineSong().uid }
        val playlist = UserLibrary.playlists.value.single()

        val result = LocalMediaScanner.scan(context)

        assertEquals(1, result.failedSources)
        assertEquals(3, result.count)
        retained.forEach { assertEquals(it, LocalMediaStore.find(it.id)) }
        assertEquals(setOf(retained[0].uri, retained[1].uri, documentUri("healthy", "healthy/new")),
            LocalMediaStore.songs.value.map { it.uri }.toSet())
        assertEquals("healthy-new", LocalMediaStore.findByUri(documentUri("healthy", "healthy/new"))?.title)
        assertEquals(favoriteUids, UserLibrary.favorites.value.map { it.uid })
        assertEquals(retained.map { it.toOnlineSong().uid },
            UserLibrary.playlists.value.single { it.id == playlist.id }.songs.map { it.uid })
        assertFalse(LocalMediaStore.isScanning.value)
        assertTrue(provider.childQueries.containsAll(listOf("loading", "healthy")))
        assertTrue(provider.cursors.isNotEmpty() && provider.cursors.all { it.isClosed })
        assertPersistedSongs(retained.map { it.id }.toSet() + LocalMediaStore.idForUri(documentUri("healthy", "healthy/new")))
        UserLibrary.reloadAfterRestore()
        assertEquals(favoriteUids, UserLibrary.favorites.value.map { it.uid })
        assertEquals(retained.map { it.toOnlineSong().uid }, UserLibrary.playlists.value.single().songs.map { it.uid })
    }

    private suspend fun assertCompletedEmptyDirectory(loading: Boolean?) {
        val old = song("loading", "loading/old")
        seed(listOf(old))
        MeloraSettings.localFolders.value = listOf(treeUri("loading").toString())
        provider.setChildren("loading", emptyList(), loading)

        val result = LocalMediaScanner.scan(context)

        assertEquals(0, result.failedSources)
        assertEquals(0, result.count)
        assertTrue(LocalMediaStore.songs.value.isEmpty())
        assertTrue(UserLibrary.favorites.value.isEmpty())
        assertTrue(UserLibrary.playlists.value.single().songs.isEmpty())
        assertFalse(LocalMediaStore.isScanning.value)
        assertEquals(listOf("loading"), provider.childQueries)
        assertTrue(provider.cursors.isNotEmpty() && provider.cursors.all { it.isClosed })
        assertPersistedSongs(emptySet())
        UserLibrary.reloadAfterRestore()
        assertTrue(UserLibrary.favorites.value.isEmpty())
        assertTrue(UserLibrary.playlists.value.single().songs.isEmpty())
    }

    private fun seed(songs: List<LocalSong>) {
        MeloraSettings.localFolders.value = listOf("loading", "healthy").map { treeUri(it).toString() }
        LocalMediaStore.replaceAll(songs)
        UserLibrary.setFavorites(songs.map { it.toOnlineSong() }, favorite = true)
        UserLibrary.createPlaylist("扫描保护", songs.map { it.toOnlineSong() })
    }

    private fun assertPersistedSongs(expected: Set<String>) {
        val rows = JSONObject(File(context.filesDir, "local_media.json").readText()).getJSONArray("songs")
        assertEquals(expected, (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet())
    }

    private fun song(tree: String, id: String) = LocalSong(
        id = LocalMediaStore.idForUri(documentUri(tree, id)), uri = documentUri(tree, id), title = "原始-$id",
        artist = "保留歌手", album = "保留专辑", durationMs = 180_000L, sizeBytes = 3_000_000L,
        mimeType = "audio/mpeg", sampleRate = 44_100, bitrate = 320_000, modifiedAt = 1_000L,
        addedAt = 500L, folder = tree, coverUri = "file:///fixture-cover.jpg", infoFilled = true,
    )

    private fun treeUri(id: String): Uri = DocumentsContract.buildTreeDocumentUri(ScanContext.AUTHORITY, id)
    private fun documentUri(tree: String, id: String): String =
        DocumentsContract.buildDocumentUriUsingTree(treeUri(tree), id).toString()

    private fun field(owner: Any, name: String): Field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun saveField(owner: Any, name: String): Field = field(owner, name).also {
        savedFields += Triple(owner, it, it.get(owner))
    }

    private class ScanContext(base: Context) : ContextWrapper(base) {
        companion object { const val AUTHORITY = "com.leyu.melora.scanner-fixture" }
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "scanner-fixture-").toFile()
        private val preferenceNames = mutableSetOf<String>()
        val provider = LoadingDocumentsProvider().apply {
            attachInfo(this@ScanContext, ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
            })
        }
        val mediaProvider = MediaStoreScanProvider().apply {
            attachInfo(this@ScanContext, ProviderInfo().apply { authority = "media" })
        }
        private var resolver = ContentResolver.wrap(provider)
        var storageAvailable = true
        fun useMediaStore(ids: List<Long>, folder: String) {
            mediaProvider.setSongs(ids.toLongArray(), folder)
            resolver = ContentResolver.wrap(mediaProvider)
            MeloraSettings.localUseMediaStore.value = true
            MeloraSettings.localFolders.value = emptyList()
        }
        override fun getSystemService(name: String): Any? =
            if (name == Context.STORAGE_SERVICE && !storageAvailable) null else super.getSystemService(name)
        override fun getApplicationContext(): Context = this
        override fun getContentResolver(): ContentResolver = resolver
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int): Int =
            if (uri.authority == AUTHORITY) PackageManager.PERMISSION_GRANTED
            else super.checkCallingOrSelfUriPermission(uri, modeFlags)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "${root.name}.$name"
            preferenceNames += isolated
            return super.getSharedPreferences(isolated, mode)
        }
        fun cleanup() {
            preferenceNames.forEach { super.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}

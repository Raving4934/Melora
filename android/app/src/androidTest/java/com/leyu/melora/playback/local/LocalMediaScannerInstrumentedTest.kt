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
import com.leyu.melora.playback.DeletedLocalFiles
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
    fun mediaFiltersRetainUserReferencesAndRefreshLegacyLocalUris() = runBlocking {
        val tracks = (1L..5L).map(::mediaSong)
        seed(tracks)
        tracks.forEach { UserLibrary.markPlayed(it.toOnlineSong()) }
        val root = JSONObject(UserLibrary.exportSnapshot())
        val favorites = root.getJSONArray("favorites")
        val legacySong = (0 until favorites.length()).map(favorites::getJSONObject)
            .single { it.getString("songmid") == tracks.first().id }
        legacySong.remove("localUri")
        legacySong.remove("localFolder")
        UserLibrary.replaceFromBackup(root.toString())
        context.useMediaStore(listOf(1L, 2L, 3L, 4L), tracks.first().folder)
        context.mediaProvider.shortTrackId = 1L
        context.mediaProvider.smallTrackId = 2L
        context.mediaProvider.nonMusicTrackId = 3L
        MeloraSettings.localExcludeShort.value = true
        MeloraSettings.localExcludeSmall.value = true

        val result = LocalMediaScanner.scan(context)

        assertEquals(0, result.failedSources)
        assertMediaReferences(tracks.take(4).map { it.id }.toSet(), setOf("ms_4"))
        val legacy = UserLibrary.favorites.value.first { it.songmid == tracks.first().id }
        assertEquals(tracks.first().uri, legacy.raw.getString("localUri"))
        assertEquals(tracks.first().folder, legacy.raw.getString("localFolder"))

        MeloraSettings.localExcludeShort.value = false
        MeloraSettings.localExcludeSmall.value = false
        LocalMediaScanner.scan(context)
        assertMediaReferences(tracks.take(4).map { it.id }.toSet(), setOf("ms_1", "ms_2", "ms_4"))
    }

    @Test
    fun removedScanDirectoryRetainsUserReferencesUntilPhysicalDeletion() = runBlocking {
        val hidden = song("loading", "loading/hidden")
        val deleted = song("healthy", "healthy/deleted")
        seed(listOf(hidden, deleted))
        listOf(hidden, deleted).forEach { UserLibrary.markPlayed(it.toOnlineSong()) }
        MeloraSettings.localFolders.value = listOf(treeUri("healthy").toString())
        provider.setChildren("healthy", emptyList(), false)

        assertEquals(0, LocalMediaScanner.scan(context).failedSources)
        assertMediaReferences(setOf(hidden.id), emptySet())

        // 删除入口只按确认的地址清理；隐藏曲目此时已经不在索引。
        repeat(2) { LocalMediaStore.removeDeleted(DeletedLocalFiles(emptySet(), setOf(hidden.uri))) }
        assertMediaReferences(emptySet())
    }

    @Test
    fun reenabledDirectoryPrunesDeletedHiddenReferenceAndPreservesUncoveredUri() = runBlocking {
        val hidden = song("loading", "loading/hidden")
        val otherDevice = song("backup-only", "backup-only/unknown")
        seed(listOf(hidden))
        UserLibrary.setFavorites(listOf(otherDevice.toOnlineSong()), favorite = true)
        val restored = UserLibrary.createPlaylist("备份中的未扫描目录", listOf(otherDevice.copy(id = hidden.id).toOnlineSong()))
        MeloraSettings.localFolders.value = listOf(treeUri("healthy").toString())
        provider.setChildren("healthy", emptyList(), false)
        LocalMediaScanner.scan(context)
        assertEquals(setOf(hidden.id, otherDevice.id), UserLibrary.favorites.value.map { it.songmid }.toSet())
        assertPersistedSongs(emptySet())

        provider.setChildren("loading", emptyList(), false)
        MeloraSettings.localFolders.value = listOf(treeUri("loading").toString())
        repeat(2) { LocalMediaScanner.scan(context) }

        assertPersistedSongs(emptySet())
        UserLibrary.reloadAfterRestore()
        assertEquals(otherDevice.id, UserLibrary.favorites.value.single().songmid)
        val playlists = UserLibrary.playlists.value.associateBy { it.id }
        val protected = requireNotNull(playlists[restored.id]).songs.single()
        assertEquals(hidden.id, protected.songmid)
        assertEquals(otherDevice.uri, protected.raw.getString("localUri"))
        assertTrue(UserLibrary.playlists.value.single { it.id != restored.id }.songs.isEmpty())
    }

    @Test
    fun liftingFilterPrunesHiddenFileDeletedFromMountedMediaStore() = runBlocking {
        val hidden = mediaSong(1)
        seed(listOf(hidden))
        UserLibrary.markPlayed(hidden.toOnlineSong())
        context.useMediaStore(listOf(1L), hidden.folder)
        context.mediaProvider.shortTrackId = 1L
        MeloraSettings.localExcludeShort.value = true
        LocalMediaScanner.scan(context)
        assertMediaReferences(setOf(hidden.id), emptySet())

        context.useMediaStore(emptyList(), hidden.folder)
        MeloraSettings.localExcludeShort.value = false
        LocalMediaScanner.scan(context)
        assertMediaReferences(emptySet())
    }

    @Test
    fun enrichReadsMissingAudioSpecsAndKeepsExistingMetadata() = runBlocking {
        val audio = File(context.cacheDir, "enrich-fixture.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/fixture-320.mp3").use { input ->
            audio.outputStream().use { input.copyTo(it) }
        }
        val incomplete = song("healthy", "healthy/specs").copy(
            uri = Uri.fromFile(audio).toString(), sampleRate = 0, bitrate = 0,
        )
        val complete = song("healthy", "healthy/already-known")
        val measured = requireNotNull(LocalTagReader.read(context, incomplete.uri))
        assertTrue(measured.bitrate > 0 || measured.sampleRate > 0)
        seed(listOf(incomplete, complete))

        LocalMediaScanner.enrich(context)

        val enriched = requireNotNull(LocalMediaStore.find(incomplete.id))
        assertEquals(measured.sampleRate, enriched.sampleRate)
        assertEquals(measured.bitrate, enriched.bitrate)
        assertEquals(incomplete, enriched.copy(sampleRate = 0, bitrate = 0, bitDepth = incomplete.bitDepth))
        assertEquals(complete, LocalMediaStore.find(complete.id))
        LocalMediaStore.init(context)
        assertEquals(enriched, LocalMediaStore.find(incomplete.id))
    }

    @Test
    fun scanDoesNotPruneReferencesWhenSameUriHasAnUpdatedId() {
        val old = mediaSong(1)
        seed(listOf(old))
        UserLibrary.markPlayed(old.toOnlineSong())
        val baseline = LocalMediaStore.snapshot()
        val replacement = old.copy(id = "another-index-id")
        LocalMediaStore.replaceAll(listOf(replacement))
        LocalMediaStore.commitScanned(baseline, emptyList(), missing = listOf(LocalMediaReference(old.id, old.uri, old.folder)))

        assertMediaReferences(setOf(old.id), setOf(replacement.id))
    }

    @Test
    fun legacyDocumentIdentitySurvivesRepeatedHidingAndScanning() = runBlocking {
        val document = song("healthy", "healthy/legacy")
        val legacy = document.copy(id = "doc_${document.uri.hashCode().toUInt().toString(16)}")
        seed(listOf(legacy))
        UserLibrary.markPlayed(legacy.toOnlineSong())
        provider.setChildren("healthy", listOf(LoadingDocumentsProvider.Document("healthy/legacy", "legacy.mp3")), false)

        repeat(2) {
            MeloraSettings.localFolders.value = emptyList()
            LocalMediaScanner.scan(context)
            assertMediaReferences(setOf(legacy.id), emptySet())
            MeloraSettings.localFolders.value = listOf(treeUri("healthy").toString())
            LocalMediaScanner.scan(context)
            assertMediaReferences(setOf(legacy.id))
            assertTrue(UserLibrary.isFavorite(LocalMediaStore.songs.value.single().toOnlineSong().uid))
        }
    }

    @Test
    fun ambiguousLegacyIdIsNotAssignedToEitherNewlyScannedFile() = runBlocking {
        val a = song("healthy", "healthy/Aa")
        val b = song("healthy", "healthy/BB")
        assertEquals(a.uri.hashCode(), b.uri.hashCode())
        val legacyId = "doc_${a.uri.hashCode().toUInt().toString(16)}"
        MeloraSettings.localFolders.value = listOf(treeUri("healthy").toString())
        provider.setChildren("healthy", listOf(
            LoadingDocumentsProvider.Document("healthy/Aa", "a.mp3"),
            LoadingDocumentsProvider.Document("healthy/BB", "b.mp3"),
        ), false)
        UserLibrary.setFavorites(listOf(a.copy(id = legacyId).toOnlineSong()), favorite = true)
        UserLibrary.createPlaylist("另一份旧记录", listOf(b.copy(id = legacyId).toOnlineSong()))

        LocalMediaScanner.scan(context)

        assertPersistedSongs(setOf(a.id, b.id))
        assertEquals(a.uri, UserLibrary.favorites.value.single().raw.getString("localUri"))
        assertEquals(b.uri, UserLibrary.playlists.value.single().songs.single().raw.getString("localUri"))

        provider.setChildren("healthy", listOf(LoadingDocumentsProvider.Document("healthy/BB", "b.mp3")), false)
        LocalMediaScanner.scan(context)
        assertPersistedSongs(setOf(b.id))
        assertTrue(UserLibrary.favorites.value.isEmpty())
        assertEquals(b.uri, UserLibrary.playlists.value.single().songs.single().raw.getString("localUri"))
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

    private fun assertMediaReferences(expected: Set<String>, indexed: Set<String> = expected) {
        assertPersistedSongs(indexed)
        assertEquals(indexed, LocalMediaStore.songs.value.map { it.id }.toSet())
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
    fun erroredDirectoryPreservesDiskAndLibraryUntilACompleteQueryConfirmsDeletion() = runBlocking {
        val present = song("loading", "loading/present")
        val missing = song("loading", "loading/missing")
        val old = listOf(present, missing)
        seed(old)
        old.forEach { UserLibrary.markPlayed(it.toOnlineSong()) }
        MeloraSettings.localFolders.value = listOf(treeUri("loading").toString())
        val partial = listOf(LoadingDocumentsProvider.Document("loading/present", "present.mp3"))
        provider.setChildren("loading", partial, false, "Network unavailable")

        assertEquals(1, LocalMediaScanner.scan(context).failedSources)
        assertMediaReferences(old.map(LocalSong::id).toSet())
        assertEquals(old, LocalMediaStore.songs.value)

        provider.setChildren("loading", partial, false)
        assertEquals(0, LocalMediaScanner.scan(context).failedSources)
        assertMediaReferences(setOf(present.id))
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

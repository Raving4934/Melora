package com.leyu.melora.playback.local

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.BackupStateLock
import com.leyu.melora.playback.DeletedLocalFiles
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.ui.local.LocalDeletionAttempt
import com.leyu.melora.ui.local.LocalDeletionOperations
import com.leyu.melora.ui.local.LocalDeletionStep
import com.leyu.melora.ui.local.LocalDeletionTarget
import com.leyu.melora.ui.local.LocalSongDeletionViewModel
import com.leyu.melora.ui.local.toLocalDeletionTarget
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                for (name in listOf("appContext", "byUri", "byId", "byTitle")) {
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
    fun unauthorizedMediaQueryIsIncompleteEvenWithoutAnExistingIndex() = runBlocking {
        context.useMediaStore(emptyList(), mediaSong(1).folder)
        context.mediaPermissionGranted = false
        var queried = false
        context.mediaProvider.onQuery = Runnable { queried = true }

        assertEquals(LocalMediaScanner.ScanResult(0, 1), LocalMediaScanner.scan(context))
        assertFalse(queried)
    }

    @Test
    fun permissionLossDuringMediaQueryKeepsPersistedLibraryReferences() = runBlocking {
        val primary = mediaSong(1)
        seed(listOf(primary))
        UserLibrary.markPlayed(primary.toOnlineSong())
        context.useMediaStore(emptyList(), primary.folder)
        context.mediaProvider.onQuery = Runnable { context.mediaPermissionGranted = false }

        assertEquals(LocalMediaScanner.ScanResult(1, 1), LocalMediaScanner.scan(context))
        assertMediaReferences(setOf(primary.id))
    }

    @Test
    fun singleFileQueryOnlyConfirmsMissingWhenPermissionSurvivesTheQuery() {
        val primary = mediaSong(1)
        context.useMediaStore(emptyList(), primary.folder)

        assertEquals(LocalFilePresence.Missing, localFilePresence(context, primary.uri, primary.folder))
        context.mediaProvider.onQuery = Runnable { context.mediaPermissionGranted = false }
        assertEquals(LocalFilePresence.Unknown, localFilePresence(context, primary.uri, primary.folder))
        assertFalse(context.mediaPermissionGranted)
    }

    @Test
    fun restrictedMediaStoreRetainsLegacyReferencesWhileAuthorizedDocumentsUpdate() = runBlocking {
        val indexedMedia = mediaSong(1)
        val hiddenMedia = mediaSong(2)
        val oldDocument = song("healthy", "healthy/old")
        val newDocument = song("healthy", "healthy/new")
        val oldSongs = listOf(indexedMedia, hiddenMedia, oldDocument)
        seed(oldSongs)
        oldSongs.forEach { UserLibrary.markPlayed(it.toOnlineSong()) }
        LocalMediaStore.discardIndexEntry(hiddenMedia.id)
        context.useMediaStore(emptyList(), indexedMedia.folder)
        context.mediaPermissionGranted = false
        MeloraSettings.localFolders.value = listOf(treeUri("healthy").toString())
        provider.setChildren("healthy", listOf(LoadingDocumentsProvider.Document("healthy/new", "new.mp3")), false)

        assertEquals(LocalMediaScanner.ScanResult(2, 1), LocalMediaScanner.scan(context))
        assertMediaReferences(setOf(indexedMedia.id, hiddenMedia.id), setOf(indexedMedia.id, newDocument.id))
        assertEquals(listOf("healthy"), provider.childQueries)
        assertEquals("new", LocalMediaStore.find(newDocument.id)?.title)

        context.mediaPermissionGranted = true
        repeat(2) {
            assertEquals(LocalMediaScanner.ScanResult(1, 0), LocalMediaScanner.scan(context))
            assertMediaReferences(emptySet(), setOf(newDocument.id))
        }
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
    fun folderlessNamedVolumeReferencesFollowAvailabilityBeforeAndAfterQuery() = runBlocking {
        val indexed = mediaSong(1)
        val deleted = mediaSong(2).copy(uri = "content://media/external_primary/audio/media/2")
        val detached = mediaSong(3).copy(uri = "content://media/dead-beef/audio/media/3")
        seedFolderlessMediaReferences(indexed, deleted, detached)
        context.useMediaStore(listOf(1L), indexed.folder)
        context.mediaProvider.onQuery = Runnable { context.storageAvailable = false }

        assertEquals(LocalMediaScanner.ScanResult(1, 1), LocalMediaScanner.scan(context))
        assertMediaReferences(setOf(indexed.id, deleted.id, detached.id), setOf(indexed.id))

        context.storageAvailable = true
        repeat(2) {
            assertEquals(LocalMediaScanner.ScanResult(1, 1), LocalMediaScanner.scan(context))
            assertMediaReferences(setOf(indexed.id, detached.id), setOf(indexed.id))
        }
    }

    @Test
    fun folderlessAggregateReferenceIsKeptWithoutReportingHealthySourceFailure() = runBlocking {
        val indexed = mediaSong(1)
        val unknown = mediaSong(2)
        seedFolderlessMediaReferences(indexed, unknown)
        context.useMediaStore(listOf(1L), indexed.folder)

        repeat(2) {
            assertEquals(LocalMediaScanner.ScanResult(1, 0), LocalMediaScanner.scan(context))
            assertMediaReferences(setOf(indexed.id, unknown.id), setOf(indexed.id))
            assertFalse(UserLibrary.favorites.value.single { it.songmid == unknown.id }.raw.has("localFolder"))
        }

        context.mediaPermissionGranted = false
        assertEquals(LocalMediaScanner.ScanResult(1, 1), LocalMediaScanner.scan(context))
        assertMediaReferences(setOf(indexed.id, unknown.id), setOf(indexed.id))
    }

    @Test
    fun folderlessAggregateReferenceCanReuseAnUnambiguousFolderForTheSameUri() = runBlocking {
        val indexed = mediaSong(1)
        val deleted = mediaSong(2)
        seedFolderlessMediaReferences(indexed, deleted)
        UserLibrary.markPlayed(deleted.toOnlineSong())
        context.useMediaStore(listOf(1L), indexed.folder)

        assertEquals(LocalMediaScanner.ScanResult(1, 0), LocalMediaScanner.scan(context))
        assertMediaReferences(setOf(indexed.id))
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
    fun documentTagReuseRequiresSameNameAndSurvivesIndexReload() = runBlocking {
        val original = song("healthy", "healthy/tagged").copy(
            title = "标签标题", sizeBytes = 2_000_000L, modifiedAt = 2_000L,
            probedDocumentName = "same.mp3",
        )
        seed(listOf(original))
        LocalMediaStore.init(context)
        MeloraSettings.localFolders.value = listOf(treeUri("healthy").toString())
        provider.setChildren("healthy", listOf(LoadingDocumentsProvider.Document("healthy/tagged", "same.mp3")), false)

        LocalMediaScanner.scan(context)
        assertEquals(original, LocalMediaStore.find(original.id))

        provider.setChildren("healthy", listOf(LoadingDocumentsProvider.Document("healthy/tagged", "renamed.mp3")), false)
        LocalMediaScanner.scan(context)
        val renamed = requireNotNull(LocalMediaStore.find(original.id))
        assertEquals("renamed", renamed.title)
        assertNull(renamed.probedDocumentName)
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
    fun restoredSystemAuthorizationReconcilesTheLibraryOnceWithoutRepeatingADelete() = runBlocking {
        val removed = song("loading", "loading/removed")
        val retained = song("loading", "loading/retained")
        seed(listOf(removed, retained))
        MeloraSettings.localFolders.value = listOf(treeUri("loading").toString())
        listOf(removed, retained).forEach { UserLibrary.markPlayed(it.toOnlineSong()) }
        provider.setChildren("loading", listOf(LoadingDocumentsProvider.Document("loading/retained", "retained.mp3")), false)
        val consent = PendingIntent.getActivity(
            context, 42, Intent(context, ComponentActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val savedState = SavedStateHandle()
        val operations = object : LocalDeletionOperations<IntentSenderRequest> {
            override val supportsSystemDeleteRequest = true
            override fun isMediaStore(target: LocalDeletionTarget) = true
            override suspend fun delete(target: LocalDeletionTarget): LocalDeletionAttempt<IntentSenderRequest> =
                error("不应在对账时再次请求删除")
            override fun createSystemDeleteRequest(targets: List<LocalDeletionTarget>) =
                IntentSenderRequest.Builder(consent.intentSender).build()
        }
        try {
            val original = LocalSongDeletionViewModel(context, savedState, operations)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { original.start(listOf(removed.toLocalDeletionTarget())) }
            withTimeout(5_000) { original.step.first { it is LocalDeletionStep.Awaiting } }
            instrumentation.runOnMainSync { checkNotNull(original.takeAuthorizationRequest()) }

            val restored = LocalSongDeletionViewModel(context, savedState, operations)
            assertEquals(LocalDeletionStep.RestoredAuthorization, restored.step.value)
            instrumentation.runOnMainSync {
                restored.onAuthorizationResult(approved = true)
                restored.onAuthorizationResult(approved = true)
            }
            withTimeout(5_000) { restored.step.first { it == LocalDeletionStep.Ignored } }
            assertMediaReferences(setOf(retained.id))
            assertEquals(listOf("loading"), provider.childQueries)
        } finally {
            consent.cancel()
            PlaybackController.consumeMessage()
        }
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

    private fun seedFolderlessMediaReferences(indexed: LocalSong, vararg older: LocalSong) {
        LocalMediaStore.replaceAll(listOf(indexed))
        val songs = listOf(indexed.toOnlineSong()) + older.map { song ->
            song.toOnlineSong().also { it.raw.remove("localFolder") }
        }
        UserLibrary.setFavorites(songs, favorite = true)
        UserLibrary.createPlaylist("旧媒体库", songs)
        songs.forEach(UserLibrary::markPlayed)
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
        val mediaProvider = MediaStoreScanProvider(provider).apply {
            attachInfo(this@ScanContext, ProviderInfo().apply { authority = "media" })
        }
        private var resolver = ContentResolver.wrap(provider)
        var storageAvailable = true
        var mediaPermissionGranted = true
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
        override fun checkSelfPermission(permission: String): Int = when (permission) {
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE ->
                if (mediaPermissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            else -> super.checkSelfPermission(permission)
        }
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

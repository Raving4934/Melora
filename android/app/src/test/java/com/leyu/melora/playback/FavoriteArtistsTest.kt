package com.leyu.melora.playback

import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FavoriteArtistsTest {
    @Test
    fun localIndexChangesRefreshFavoriteMetadataAndRemoveFavoriteUid() = withIsolatedLibrary {
        LocalMediaStore.clear()
        val local = LocalSong(
            id = "ms_local-refresh",
            uri = "content://media/external/audio/media/42",
            title = "旧标题",
            artist = "旧歌手",
            album = "旧专辑",
            durationMs = 180_000,
            sizeBytes = 4_000_000,
            mimeType = "audio/mpeg",
            sampleRate = 44_100,
            bitrate = 320_000,
            modifiedAt = 1,
            addedAt = 1,
            folder = "Music",
        )
        try {
            LocalMediaStore.replaceAll(listOf(local))
            UserLibrary.toggleFavorite(local.toOnlineSong())

            val enriched = local.copy(artist = "新歌手", album = "新专辑")
            LocalMediaStore.updateMetadata(enriched)
            assertEquals("新歌手", UserLibrary.favorites.value.single().singer)
            assertEquals("新专辑", UserLibrary.favorites.value.single().albumName)

            LocalMediaStore.removeIds(setOf(local.id))
            assertTrue(UserLibrary.favorites.value.isEmpty())
            assertTrue(UserLibrary.favoriteUids.value.isEmpty())
        } finally {
            LocalMediaStore.clear()
        }
    }

    @Test
    fun toggleIsKeyedByArtistAndDoesNotLeaveDuplicates() = withIsolatedLibrary {
        val artist = UserLibrary.FavoriteArtist("歌手", "kw", "cover")

        UserLibrary.toggleFavoriteArtist(artist)
        assertEquals(listOf(artist), UserLibrary.favoriteArtists.value)
        assertTrue(UserLibrary.isFavoriteArtist(artist.key))

        UserLibrary.toggleFavoriteArtist(artist.copy(img = "new-cover"))
        assertTrue(UserLibrary.favoriteArtists.value.isEmpty())
        assertFalse(UserLibrary.isFavoriteArtist(artist.key))

        UserLibrary.toggleFavoriteArtist(artist)
        assertEquals(1, UserLibrary.favoriteArtists.value.count { it.key == artist.key })
    }

    @Test
    fun sameArtistNameOnDifferentPlatformsRemainsIndependent() = withIsolatedLibrary {
        val kuwo = UserLibrary.FavoriteArtist("同名歌手", "kw", "kw-cover")
        val qq = UserLibrary.FavoriteArtist("同名歌手", "qq", "qq-cover")

        UserLibrary.toggleFavoriteArtist(kuwo)
        UserLibrary.toggleFavoriteArtist(qq)

        assertEquals(setOf("kw_同名歌手", "qq_同名歌手"), UserLibrary.favoriteArtists.value.map { it.key }.toSet())
        assertTrue(UserLibrary.isFavoriteArtist(kuwo.key))
        assertTrue(UserLibrary.isFavoriteArtist(qq.key))
    }

    @Test
    fun snapshotAndBackupRoundTripPreservesArtistsAndNullImage() = withIsolatedLibrary { file ->
        val artists = listOf(
            UserLibrary.FavoriteArtist("无封面", "kw", null),
            UserLibrary.FavoriteArtist("有封面", "qq", "https://img.example/artist.jpg"),
        )
        artists.forEach { UserLibrary.toggleFavoriteArtist(it) }

        val snapshot = UserLibrary.exportSnapshot()
        val root = JSONObject(snapshot)
        assertEquals(2, root.getJSONArray("favoriteArtists").length())
        assertTrue(file.isFile)

        UserLibrary.replaceFromBackup(JSONObject().toString())
        assertTrue(UserLibrary.favoriteArtists.value.isEmpty())

        UserLibrary.replaceFromBackup(snapshot)
        assertEquals(artists.asReversed(), UserLibrary.favoriteArtists.value)
        assertEquals(2, JSONObject(file.readText()).getJSONArray("favoriteArtists").length())
    }

    @Test
    fun missingArtistFieldKeepsLegacyAlbumsPlaylistsAndHistory() = withIsolatedLibrary {
        val legacy = JSONObject()
            .put("favoriteAlbums", JSONArray().put(
                JSONObject()
                    .put("name", "旧专辑")
                    .put("artist", "旧歌手")
                    .put("source", "kw")
                    .put("img", JSONObject.NULL),
            ))
            .put("favoritePlaylists", JSONArray().put(
                JSONObject().put("id", "pl_1").put("name", "旧收藏歌单").put("source", "kw"),
            ))
            .put("playlists", JSONArray().put(
                JSONObject().put("id", "mine_1").put("name", "旧自建歌单").put("songs", JSONArray()),
            ))
            .put("searchHistory", JSONArray().put("旧搜索词"))

        UserLibrary.replaceFromBackup(legacy.toString())

        assertTrue(UserLibrary.favoriteArtists.value.isEmpty())
        assertEquals(listOf("旧专辑"), UserLibrary.favoriteAlbums.value.map { it.name })
        assertNull(UserLibrary.favoriteAlbums.value.single().img)
        assertEquals(listOf("旧收藏歌单"), UserLibrary.favoritePlaylists.value.map { it.name })
        assertEquals(listOf("旧自建歌单"), UserLibrary.playlists.value.map { it.name })
        assertEquals(listOf("旧搜索词"), UserLibrary.searchHistory.value)
    }

    @Test
    fun blankArtistNamesAreIgnoredLikeAlbumNames() = withIsolatedLibrary {
        val root = JSONObject().put(
            "favoriteArtists",
            JSONArray()
                .put(JSONObject().put("name", "   ").put("source", "kw"))
                .put(JSONObject().put("name", "有效歌手").put("source", "kw").put("img", JSONObject.NULL)),
        )

        UserLibrary.replaceFromBackup(root.toString())

        assertEquals(1, UserLibrary.favoriteArtists.value.size)
        assertEquals("有效歌手", UserLibrary.favoriteArtists.value.single().name)
        assertNull(UserLibrary.favoriteArtists.value.single().img)
    }

    @Test
    fun duplicateArtistsInBackupCannotCreateDuplicateUiKeys() = withIsolatedLibrary {
        val artist = JSONObject().put("name", "歌手").put("source", "kw")
        UserLibrary.replaceFromBackup(JSONObject().put("favoriteArtists", JSONArray().put(artist).put(artist)).toString())
        assertEquals(1, UserLibrary.favoriteArtists.value.size)
    }

    private fun <T> withIsolatedLibrary(block: (File) -> T): T {
        val fileField = UserLibrary::class.java.getDeclaredField("file").apply { isAccessible = true }
        val previousFile = fileField.get(UserLibrary) as? File
        val previousSnapshot = UserLibrary.exportSnapshot()
        val directory = Files.createTempDirectory("melora-favorite-artists").toFile()
        val isolatedFile = directory.resolve("user-library.json")
        fileField.set(UserLibrary, isolatedFile)
        return try {
            UserLibrary.replaceFromBackup(JSONObject().toString())
            block(isolatedFile)
        } finally {
            try { UserLibrary.replaceFromBackup(previousSnapshot) }
            finally {
                fileField.set(UserLibrary, previousFile)
                directory.deleteRecursively()
            }
        }
    }
}

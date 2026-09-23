package com.leyu.melora.playback.sdk

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实缓存目录验证：只写合成目录数据，不发网络请求、不修改用户收藏或下载。 */
@RunWith(AndroidJUnit4::class)
class PageSnapshotInstrumentedTest {
    private val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val key = "boards.fixture"
    private val boards = listOf(BoardItem("test", "缓存榜单", "test-board", "https://example.test/cover.jpg"))

    @Before fun setup() = runBlocking { OnlineCache.clear(); OnlineCache.clearDisk(context) }
    @After fun cleanup() = runBlocking { OnlineCache.clear(); OnlineCache.clearDisk(context); context.root.deleteRecursively(); Unit }

    @Test fun coldReadKeepsOriginalFreshnessAndDisplaysStaleSnapshot() = runBlocking {
        val written = System.currentTimeMillis() - 20 * 60_000
        val token = OnlineCache.capturePageSnapshot(key)!!
        assertTrue(OnlineCache.persistBoardList(context, key, boards, token, written))
        OnlineCache.clear()
        assertEquals(boards, OnlineCache.hydrateBoardList(context, key))
        assertEquals(boards, OnlineCache.peek<List<BoardItem>>(key))
        assertNull("读磁盘不能把20分钟前数据重新标为刚刷新", OnlineCache.get<List<BoardItem>>(key, 15 * 60_000))
    }

    @Test fun onlyFirstPlaylistPagePersistsAndCursorIsNotGuessed() = runBlocking {
        val playlistKey = "playlists.fixture.hot.all"
        val items = listOf(OnlinePlaylist(JSONObject().put("id", "p1").put("name", "测试歌单").put("source", "fixture")))
        val first = CachedPlaylistPage(items, page = 1, hasMore = false, total = 1)
        val token = OnlineCache.capturePageSnapshot(playlistKey)!!
        assertTrue(OnlineCache.persistPlaylistFirstPage(context, playlistKey, first, token))
        assertFalse(OnlineCache.persistPlaylistFirstPage(context, playlistKey, first.copy(page = 3), token))
        OnlineCache.clear()
        val restored = OnlineCache.hydratePlaylistFirstPage(context, playlistKey)!!
        assertEquals(1, restored.page)
        assertFalse(restored.hasMore)
        assertEquals(1, restored.total)
        assertEquals("p1", restored.list.single().id)
    }

    @Test fun clearRejectsNetworkResponsesStartedBeforeCleanup() = runBlocking {
        val token = OnlineCache.capturePageSnapshot(key)!!
        OnlineCache.clearDisk(context)
        assertNull(OnlineCache.putPageIfCurrent(token, key, boards))
        assertFalse(OnlineCache.persistBoardList(context, key, boards, token))
        assertNull(OnlineCache.hydrateBoardList(context, key))
    }

    @Test fun corruptSnapshotIsIgnoredAndCanBeReplaced() = runBlocking {
        val token = OnlineCache.capturePageSnapshot(key)!!
        assertTrue(OnlineCache.persistBoardList(context, key, boards, token))
        val files = File(context.cacheDir, SNAPSHOT_DIRECTORY).listFiles()!!.filter { it.extension == "json" }
        assertEquals(1, files.size)
        files.single().writeText("{broken")
        OnlineCache.clear()
        assertNull(OnlineCache.hydrateBoardList(context, key))
        assertTrue(OnlineCache.persistBoardList(context, key, boards, OnlineCache.capturePageSnapshot(key)!!))
        assertEquals(boards, OnlineCache.hydrateBoardList(context, key))
    }

    @Test fun snapshotCountIsBoundedAndUnrelatedSourceCacheClearPreservesPages() = runBlocking {
        repeat(35) { i ->
            val pageKey = "boards.fixture$i"
            assertTrue(OnlineCache.persistBoardList(context, pageKey, boards, OnlineCache.capturePageSnapshot(pageKey)!!))
        }
        assertTrue(File(context.cacheDir, SNAPSHOT_DIRECTORY).listFiles()!!.size <= ONLINE_CACHE_SNAPSHOT_MAX_ENTRIES)
        val pageKey = "boards.fixture34"
        val token = OnlineCache.capturePageSnapshot(pageKey)!!
        OnlineCache.clear("resolver:matches:")
        assertTrue(OnlineCache.isPageSnapshotCurrent(token, pageKey))
        assertEquals(boards, OnlineCache.hydrateBoardList(context, pageKey))
    }

    @Test fun clearReportsFilesystemFailureInsteadOfSuccess() = runBlocking {
        assertTrue(OnlineCache.persistBoardList(context, key, boards, OnlineCache.capturePageSnapshot(key)!!))
        val dir = File(context.cacheDir, SNAPSHOT_DIRECTORY)
        assertTrue(dir.setWritable(false, false))
        try {
            try { OnlineCache.clearDisk(context); fail("不应吞掉删除失败") }
            catch (_: java.io.IOException) { }
        } finally { dir.setWritable(true, true) }
    }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        val root = File(base.cacheDir, "page-snapshot-tests").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
}

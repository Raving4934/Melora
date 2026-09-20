package com.leyu.melora.playback.sdk

import android.content.Context
import com.leyu.melora.playback.UserLibrary
import java.time.LocalDate
import java.util.Locale
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope

/**
 * 推荐域唯一入口。
 *
 * 发现页使用收藏/最近播放形成长期口味与短期行为两套模型；搜索页则优先使用搜索历史和
 * 收藏歌单名称，避免复用发现页结果。全部音乐入口在此再次执行内容边界和多样性约束。
 */
object Recommender {
    val musicSources = listOf("kw", "kg", "wy", "tx", "mg")

    private const val SONG_LIMIT = 30
    private const val CARD_LIMIT = 6
    private const val PER_SEED_LIMIT = 8
    private const val DAILY_TTL_MS = 30 * 60 * 60 * 1000L
    private const val GUESS_TTL_MS = 15 * 60 * 1000L
    private const val BASE_TTL_MS = 15 * 60 * 1000L
    private val guessFallbackBoards = listOf("93", "17", "16")
    private val ignoredArtists = setOf("未知歌手", "未知艺术家", "群星", "variousartists", "unknownartist")
    private val refreshLock = Any()
    private var discoveryRefreshGeneration: Long? = null

    /**
     * 开启一次发现页刷新代次。
     *
     * UI 应在同一轮 daily/guess/new/精选 launch 之前调用一次；同代次的 force 请求共享
     * 已完成的基础结果，不会因为后到的 guess 再次强刷同一 artist/榜单 key。
     */
    fun beginDiscoveryRefresh() {
        synchronized(refreshLock) {
            discoveryRefreshGeneration = (discoveryRefreshGeneration ?: 0L) + 1L
        }
    }

    /** 每日推荐：收藏偏好为主；日期和口味不变时当天结果稳定。 */
    suspend fun daily(context: Context, refresh: Boolean = false): List<OnlineSong> {
        val key = "recommend.daily.${LocalDate.now().toEpochDay()}.${profileToken()}"
        return refreshShared(key, DAILY_TTL_MS, refresh) {
            buildMusic(
                context = context,
                favoriteWeight = 4,
                recentWeight = 1,
                fallbackBoards = listOf("16", "17", "93"),
                excluded = knownMusicUids(),
                refresh = refresh,
            )
        }
    }

    /** 猜你喜欢：最近播放为主，并排除当天每日推荐池。 */
    suspend fun guess(context: Context, refresh: Boolean = false): List<OnlineSong> {
        val key = "recommend.guess.${LocalDate.now().toEpochDay()}.${profileToken()}"
        return refreshShared(key, GUESS_TTL_MS, refresh) {
            val known = knownMusicUids()
            val (dailySongs, candidates) = loadGuessCandidates(
                known = known,
                fallbackBoards = guessFallbackBoards,
                daily = { daily(context, refresh = refresh) },
                artist = {
                    loadMusicArtistCandidates(
                        context = context,
                        favoriteWeight = 1,
                        recentWeight = 4,
                        refresh = refresh,
                    )
                },
                board = { boardId -> cachedBoardSongs(context, "kw", boardId, 1, refresh) },
            )
            selectGuessSongs(candidates, dailySongs, known)
        }
    }

    /** 搜索页歌曲猜你喜欢：搜索意图优先，不复用发现页池。 */
    suspend fun searchSongs(context: Context, sources: List<String>): List<OnlineSong> {
        val targets = validSources(sources)
        val intentSeeds = distinctSearchSeeds(
            values = UserLibrary.searchHistory.value.take(3) +
                UserLibrary.favoritePlaylists.value.filterNot(OnlinePlaylist::isBookAlbum).map { it.name }.take(2) +
                artistSeeds(UserLibrary.favorites.value.musicOnly(), UserLibrary.recents.value.musicOnly(), 1, 1).take(2),
            limit = 5,
        )
        val hotSeeds = if (intentSeeds.isEmpty()) hotSearch(context, targets) else emptyList()
        val seeds = intentSeeds.ifEmpty { hotSeeds.take(3) }
        val candidates = searchSongsBySeeds(context, targets, seeds).toMutableList()
        val excluded = knownMusicUids()
        var result = diversifySongs(candidates, excluded, CARD_LIMIT, perArtist = 1)
        if (result.size < CARD_LIMIT) {
            val seedKeys = seeds.mapTo(hashSetOf(), ::searchSeedKey)
            val fallbackPool = if (intentSeeds.isEmpty()) {
                hotSeeds.drop(3)
            } else {
                hotSearch(context, targets)
            }
            val fallbackSeeds = distinctSearchSeeds(
                fallbackPool.filterNot { searchSeedKey(it) in seedKeys },
                limit = 3,
            )
            if (fallbackSeeds.isNotEmpty()) {
                candidates += searchSongsBySeeds(context, targets, fallbackSeeds)
                result = diversifySongs(candidates, excluded, CARD_LIMIT, perArtist = 1)
            }
        }
        return result
    }

    /** 搜索页歌单猜你喜欢：搜索历史/收藏歌单驱动，热门歌单仅用于补足。 */
    suspend fun searchPlaylists(context: Context, sources: List<String>): List<OnlinePlaylist> {
        val targets = validSources(sources)
        val seeds = (
            UserLibrary.searchHistory.value.take(3) +
                UserLibrary.favoritePlaylists.value.filterNot(OnlinePlaylist::isBookAlbum).map { it.name }.take(3)
            ).filter(String::isNotBlank).distinct().take(5)
        val searched = searchPlaylistsBySeeds(context, targets, seeds)
        val fallback = if (searched.size < CARD_LIMIT) hotPlaylists(context, targets, page = 2) else emptyList()
        return diversifyPlaylists(searched + fallback, CARD_LIMIT)
    }

    /** 搜索页听书猜你喜欢：优先找最近收听专辑的同类结果，专区目录负责冷启动。 */
    suspend fun searchAudiobooks(): List<OnlinePlaylist> {
        val recentAlbums = bookAlbumSeeds().take(3)
        val searched = recentAlbums.flatMap { seed ->
            recoverOr(emptyList()) { KwBookApi.search(seed, 1).items }
        }
        if (searched.size >= CARD_LIMIT) return diversifyBooks(searched, CARD_LIMIT)
        val sections = recoverOr(emptyList()) { KwBookApi.homeSections() }
        return diversifyBooks(searched + roundRobinDistinct(sections.map { it.items }, 18) { it.id }, CARD_LIMIT)
    }

    /** 发现页精选歌单：收藏歌单和最近歌手形成偏好，跨平台热门数据补足。 */
    suspend fun discoverPlaylists(context: Context, refresh: Boolean = false): List<OnlinePlaylist> {
        val seeds = (
            UserLibrary.favoritePlaylists.value.filterNot(OnlinePlaylist::isBookAlbum).map { it.name }.take(3) +
                artistSeeds(emptyList(), UserLibrary.recents.value.musicOnly(), 0, 3).take(2)
            ).filter(String::isNotBlank).distinct().take(4)
        val key = "recommend.discover.playlists.${seedToken(seeds)}"
        return refreshShared(key, BASE_TTL_MS, refresh) {
            val searched = discoverPlaylistsBySeeds(context, listOf("kw", "kg", "tx"), seeds, refresh)
            val fallback = if (searched.size < CARD_LIMIT) {
                discoverHotPlaylists(context, listOf("kw", "kg", "tx"), refresh)
            } else {
                emptyList()
            }
            diversifyPlaylists(searched + fallback, CARD_LIMIT)
        }
    }

    /** 发现页精选听书：收听偏好 + 真榜单；榜单空结果也会明确回退专区。 */
    suspend fun discoverAudiobooks(refresh: Boolean = false): List<OnlinePlaylist> {
        val affinitySeeds = bookAlbumSeeds().take(2)
        val key = "recommend.discover.books.${seedToken(affinitySeeds)}"
        return refreshShared(key, BASE_TTL_MS, refresh) {
            coroutineScope {
                val affinity = async {
                    parallel(affinitySeeds) { seed ->
                        refreshShared(baseKey("book-search", seed, 1), BASE_TTL_MS, refresh) {
                            KwBookApi.search(seed, 1).items
                        }
                    }.flatten()
                }
                val ranked = async {
                    refreshShared(baseKey("book-rank", "13", "27", 1), BASE_TTL_MS, refresh) {
                        recoverOr(emptyList()) { KwBookApi.rank("13", "27", 1).items }
                    }
                }
                val affinityItems = affinity.await()
                val rankedItems = ranked.await()
                val fallback = if (rankedItems.isEmpty() || affinityItems.size + rankedItems.size < CARD_LIMIT) {
                    refreshShared(baseKey("book-sections"), BASE_TTL_MS, refresh) {
                        recoverOr(emptyList()) { KwBookApi.homeSections() }
                    }.flatMap { it.items.take(3) }
                } else {
                    emptyList()
                }
                diversifyBooks(affinityItems + rankedItems + fallback, CARD_LIMIT)
            }
        }
    }

    /** 发现页新歌：与推荐回退共用酷我榜单基础请求，返回和原 list.take(30) 一致。 */
    suspend fun newSongs(context: Context, refresh: Boolean = false): List<OnlineSong> =
        cachedBoardSongs(context, "kw", "17", 1, refresh).take(SONG_LIMIT)

    /** 多平台实时热搜，按平台轮询交错，避免第一个平台垄断。 */
    suspend fun hotSearch(context: Context, sources: List<String>): List<String> {
        val groups = parallel(validSources(sources)) { source ->
            readThroughCache(baseKey("search-hot", source), BASE_TTL_MS) {
                recoverOr(emptyList()) {
                    OnlineRepository.hotSearch(context, source, background = true).take(10)
                }
            }
        }
        return roundRobinDistinct(groups, CARD_LIMIT) { normalize(it) }
    }

    /** 多平台真实热门歌单，聚合视图不再伪装成单平台数据。 */
    suspend fun hotPlaylists(context: Context, sources: List<String>, page: Int = 1): List<OnlinePlaylist> {
        val groups = parallel(validSources(sources)) { source ->
            OnlineRepository.playlists(context, source, "hot", "", page, background = true).list
                .filterNot(OnlinePlaylist::isBookAlbum)
        }
        return roundRobinDistinct(groups, CARD_LIMIT) { "${it.source}_${it.id}" }
    }

    /** 发现页精选专用热门歌单基础请求；搜索页不复用推荐域缓存。 */
    private suspend fun discoverHotPlaylists(
        context: Context,
        sources: List<String>,
        refresh: Boolean,
    ): List<OnlinePlaylist> {
        val groups = parallel(validSources(sources)) { source ->
            refreshShared(baseKey("playlists", source, "hot", "", 1), BASE_TTL_MS, refresh) {
                OnlineRepository.playlists(context, source, "hot", "", 1, background = true).list
            }.filterNot(OnlinePlaylist::isBookAlbum)
        }
        return roundRobinDistinct(groups, CARD_LIMIT) { "${it.source}_${it.id}" }
    }

    private suspend fun buildMusic(
        context: Context,
        favoriteWeight: Int,
        recentWeight: Int,
        fallbackBoards: List<String>,
        excluded: Set<String>,
        refresh: Boolean,
    ): List<OnlineSong> = buildMusicCandidates(
        artistSeeds = artistSeeds(
            UserLibrary.favorites.value.musicOnly(),
            UserLibrary.recents.value.musicOnly(),
            favoriteWeight,
            recentWeight,
        ),
        fallbackBoards = fallbackBoards,
        excluded = excluded,
        search = { seed ->
            cachedSongSearch(context, "kw", seed, 1, 25, refresh)
                .filter { normalize(it.singer).contains(normalize(seed)) }
                .take(PER_SEED_LIMIT)
        },
        board = { boardId -> cachedBoardSongs(context, "kw", boardId, 1, refresh) },
    )

    private suspend fun loadMusicArtistCandidates(
        context: Context,
        favoriteWeight: Int,
        recentWeight: Int,
        refresh: Boolean,
    ): List<OnlineSong> = loadArtistCandidates(
        artistSeeds = artistSeeds(
            UserLibrary.favorites.value.musicOnly(),
            UserLibrary.recents.value.musicOnly(),
            favoriteWeight,
            recentWeight,
        ),
        search = { seed ->
            cachedSongSearch(context, "kw", seed, 1, 25, refresh)
                .filter { normalize(it.singer).contains(normalize(seed)) }
                .take(PER_SEED_LIMIT)
        },
    )

    /** 独立歌手查询并发启动；结果顺序严格跟随输入 seed 顺序。 */
    internal suspend fun loadArtistCandidates(
        artistSeeds: List<String>,
        search: suspend (String) -> List<OnlineSong>,
    ): List<OnlineSong> = parallel(artistSeeds, search).flatten()

    /** 猜你喜欢先并发准备 daily 与歌手候选；确定候选即使不扣 daily 也不足时，提前并发回退榜。 */
    internal suspend fun loadGuessCandidates(
        known: Set<String>,
        fallbackBoards: List<String>,
        daily: suspend () -> List<OnlineSong>,
        artist: suspend () -> List<OnlineSong>,
        board: suspend (String) -> List<OnlineSong>,
    ): Pair<List<OnlineSong>, List<OnlineSong>> = coroutineScope {
        val artistCandidates = async { artist() }
        val prefetchedFallback = async {
            val candidates = artistCandidates.await()
            if (diversifySongs(candidates, known, SONG_LIMIT).size < SONG_LIMIT) {
                loadFallbackCandidates(fallbackBoards, board)
            } else {
                null
            }
        }
        val dailySongs = async { daily() }.await()
        val candidates = artistCandidates.await()
        val fallback = prefetchedFallback.await()
        val candidatesWithFallback = if (fallback != null) candidates + fallback else loadFallbackCandidatesIfNeeded(
            candidates = candidates,
            fallbackBoards = fallbackBoards,
            excluded = known + dailySongs.mapTo(hashSetOf()) { it.uid },
            board = board,
        )
        dailySongs to candidatesWithFallback
    }

    /** 只在当前排除集不足 30 首时拉回退榜，且三榜并行、按输入顺序合并。 */
    internal suspend fun loadFallbackCandidatesIfNeeded(
        candidates: List<OnlineSong>,
        fallbackBoards: List<String>,
        excluded: Set<String>,
        board: suspend (String) -> List<OnlineSong>,
    ): List<OnlineSong> {
        if (diversifySongs(candidates, excluded, SONG_LIMIT).size >= SONG_LIMIT) return candidates
        return candidates + loadFallbackCandidates(fallbackBoards, board)
    }

    private suspend fun loadFallbackCandidates(
        fallbackBoards: List<String>,
        board: suspend (String) -> List<OnlineSong>,
    ): List<OnlineSong> = parallel(fallbackBoards, board).flatten()

    /** 实际候选编排：raw 歌手候选并发、按需并发补榜，最终统一执行 30 首多样性约束。 */
    internal suspend fun buildMusicCandidates(
        artistSeeds: List<String>,
        fallbackBoards: List<String>,
        excluded: Set<String>,
        search: suspend (String) -> List<OnlineSong>,
        board: suspend (String) -> List<OnlineSong>,
    ): List<OnlineSong> {
        val artistCandidates = loadArtistCandidates(artistSeeds, search)
        val candidates = loadFallbackCandidatesIfNeeded(artistCandidates, fallbackBoards, excluded, board)
        return diversifySongs(candidates, excluded, SONG_LIMIT)
    }

    internal fun selectGuessSongs(
        candidates: List<OnlineSong>,
        daily: List<OnlineSong>,
        known: Set<String>,
    ): List<OnlineSong> {
        val dailyIds = daily.mapTo(hashSetOf()) { it.uid }
        return diversifySongs(candidates, known + dailyIds, SONG_LIMIT)
    }

    /**
     * 推荐结果和同参基础查询共用的 single-flight。
     *
     * force 刷新在显式代次内只给外层结果增加版本 key；真正的网络 load 始终通过稳定 key
     * 再走一次 OnlineCache.refresh，因此正常请求、同代次请求和重叠代次请求都能复用同一
     * 在途任务。空/失败/取消由 OnlineCache.refresh 原样保护，不手工回填或更新 TTL。
     */
    internal suspend fun <T : Any> refreshShared(
        key: String,
        ttlMs: Long,
        refresh: Boolean,
        load: suspend () -> T,
    ): T {
        if (!refresh) return OnlineCache.refresh(key, ttlMs, load)
        val generation = synchronized(refreshLock) { discoveryRefreshGeneration }
        if (generation == null) return OnlineCache.refresh(key, -1L, load)

        val generationKey = "$key.refresh.$generation"
        val pending = OnlineCache.pendingRefresh<T>(key)
        return OnlineCache.refresh(generationKey, ttlMs) {
            pending?.await() ?: OnlineCache.refresh(key, -1L, load)
        }
    }

    private suspend fun searchSongsBySeeds(
        context: Context,
        sources: List<String>,
        seeds: List<String>,
    ): List<OnlineSong> {
        if (seeds.isEmpty()) return emptyList()
        val requests = seeds.mapIndexed { index, seed -> sources[index % sources.size] to seed }
        return parallel(requests) { (source, seed) ->
            readThroughCache(baseKey("search-song", source, seed, 1, 20), BASE_TTL_MS) {
                recoverOr(emptyList()) {
                    OnlineRepository.search(context, source, seed, 1, 20, background = true).list
                }
            }
        }.flatten()
    }

    private suspend fun searchPlaylistsBySeeds(
        context: Context,
        sources: List<String>,
        seeds: List<String>,
    ): List<OnlinePlaylist> {
        if (seeds.isEmpty()) return emptyList()
        val requests = seeds.mapIndexed { index, seed -> sources[index % sources.size] to seed }
        return parallel(requests) { (source, seed) ->
            OnlineRepository.songlistSearch(context, source, seed, 1, background = true).list
                .filterNot(OnlinePlaylist::isBookAlbum)
        }.flatten()
    }

    private suspend fun discoverPlaylistsBySeeds(
        context: Context,
        sources: List<String>,
        seeds: List<String>,
        refresh: Boolean,
    ): List<OnlinePlaylist> {
        if (seeds.isEmpty()) return emptyList()
        val requests = seeds.mapIndexed { index, seed -> sources[index % sources.size] to seed }
        return parallel(requests) { (source, seed) ->
            refreshShared(baseKey("playlist-search", source, seed, 1), BASE_TTL_MS, refresh) {
                OnlineRepository.songlistSearch(context, source, seed, 1, background = true).list
                    .filterNot(OnlinePlaylist::isBookAlbum)
            }
        }.flatten()
    }

    private suspend fun cachedSongSearch(
        context: Context,
        source: String,
        text: String,
        page: Int,
        limit: Int,
        refresh: Boolean,
    ): List<OnlineSong> = refreshShared(baseKey("song-search", source, text, page, limit), BASE_TTL_MS, refresh) {
        recoverOr(emptyList()) {
            OnlineRepository.search(context, source, text, page, limit, background = true).list
        }
    }

    private suspend fun cachedBoardSongs(
        context: Context,
        source: String,
        boardId: String,
        page: Int,
        refresh: Boolean,
    ): List<OnlineSong> = refreshShared(baseKey("board-songs", source, boardId, page), BASE_TTL_MS, refresh) {
        recoverOr(emptyList()) {
            OnlineRepository.boardSongs(context, source, boardId, page, background = true).list
        }
    }

    private suspend fun <I, O> parallel(inputs: List<I>, block: suspend (I) -> List<O>): List<List<O>> =
        supervisorScope {
            inputs.map { input -> async { recoverOr(emptyList()) { block(input) } } }.awaitAll()
        }

    private suspend fun <T> recoverOr(default: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        default
    }

    /** 仅缓存已完成的结果；不使用 OnlineCache.refresh，避免取消的搜索等待者留下独立 scope 在途请求。 */
    private suspend fun <T : Any> readThroughCache(key: String, ttlMs: Long, load: suspend () -> T): T {
        OnlineCache.get<T>(key, ttlMs)?.let { return it }
        val value = load()
        if (value !is Collection<*> || value.isNotEmpty()) OnlineCache.put(key, value)
        return value
    }

    private fun validSources(sources: List<String>): List<String> =
        sources.filter { it in musicSources }.distinct().ifEmpty { listOf("kw") }

    private fun knownMusicUids(): Set<String> =
        (UserLibrary.favorites.value.musicOnly() + UserLibrary.recents.value.musicOnly()).mapTo(hashSetOf()) { it.uid }

    private fun bookAlbumSeeds(): List<String> =
        (UserLibrary.recents.value + UserLibrary.favorites.value)
            .asSequence()
            .filter(OnlineSong::isBookChapter)
            .map { it.albumName.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .toList()

    /** 外层结果 key 只绑定相对稳定的收藏快照；最近播放仍参与候选和排除，但不制造每次播放的冷 key。 */
    private fun profileToken(): String = buildString {
        UserLibrary.favorites.value.musicOnly().take(30).forEach { append(it.uid).append('|') }
    }.hashCode().toUInt().toString(16)

    private fun seedToken(values: List<String>): String = values.joinToString("\u001f")

    private fun distinctSearchSeeds(values: Iterable<String>, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>()
        return values.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && seen.add(searchSeedKey(it)) }
            .take(limit)
            .toList()
    }

    private fun searchSeedKey(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun baseKey(kind: String, vararg parts: Any): String =
        "recommend.base.$kind.${parts.joinToString("\u001f")}"

    internal fun diversifySongs(
        candidates: List<OnlineSong>,
        excluded: Set<String>,
        limit: Int,
        perArtist: Int = 2,
    ): List<OnlineSong> {
        val result = ArrayList<OnlineSong>(limit)
        val seen = HashSet<String>()
        val artistCounts = HashMap<String, Int>()
        for (artistLimit in perArtist..maxOf(perArtist, 2)) {
            for (song in candidates) {
                if (result.size >= limit) return result
                if (song.isBookChapter || song.uid in excluded || song.uid in seen) continue
                val artist = primaryArtist(song.singer)
                if ((artistCounts[artist] ?: 0) >= artistLimit) continue
                seen += song.uid
                artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
                result += song
            }
        }
        return result
    }

    private fun diversifyPlaylists(candidates: List<OnlinePlaylist>, limit: Int): List<OnlinePlaylist> =
        candidates.filterNot(OnlinePlaylist::isBookAlbum)
            .distinctBy { "${it.source}_${it.id}" }
            .take(limit)

    private fun diversifyBooks(candidates: List<OnlinePlaylist>, limit: Int): List<OnlinePlaylist> =
        candidates.filter(OnlinePlaylist::isBookAlbum)
            .distinctBy(OnlinePlaylist::id)
            .take(limit)

    private fun artistSeeds(
        favorites: List<OnlineSong>,
        recents: List<OnlineSong>,
        favoriteWeight: Int,
        recentWeight: Int,
    ): List<String> {
        val scores = HashMap<String, Double>()
        fun add(song: OnlineSong, weight: Double) {
            song.singer.split('、', '&', ';', '；', '/', ',', '，', '|').forEach { raw ->
                val name = raw.trim()
                if (name.isEmpty() || name.lowercase() in ignoredArtists) return@forEach
                if (name.contains('[') || name.contains('（') || name.contains('(')) return@forEach
                scores[name] = (scores[name] ?: 0.0) + weight
            }
        }
        favorites.take(80).forEachIndexed { index, song -> add(song, favoriteWeight * 0.5.pow(index / 60.0)) }
        recents.take(40).forEachIndexed { index, song -> add(song, recentWeight * 0.5.pow(index / 10.0)) }
        return scores.entries.sortedByDescending { it.value }.take(4).map { it.key }
    }

    private fun primaryArtist(value: String): String =
        normalize(value.substringBefore('、').substringBefore('&').substringBefore('/')).ifBlank { "unknown" }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\s'\\.,，&\"、()（）`~\\-<>|\\[\\]!！/]"), "")
}

internal fun <T, K> roundRobinDistinct(groups: List<List<T>>, limit: Int, key: (T) -> K): List<T> {
    if (limit <= 0 || groups.isEmpty()) return emptyList()
    val result = ArrayList<T>(limit)
    val seen = HashSet<K>()
    val maxSize = groups.maxOfOrNull { it.size } ?: 0
    for (index in 0 until maxSize) {
        for (group in groups) {
            val item = group.getOrNull(index) ?: continue
            if (seen.add(key(item))) result += item
            if (result.size >= limit) return result
        }
    }
    return result
}

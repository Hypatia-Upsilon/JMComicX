package dev.jmx.client

import android.content.Context
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 更新提示的单一数据源。
 *
 * "我的"页的角标、收藏页的"更新 N 章"、书架分组栏的红点读的都是同一份 [records]，
 * 所以"点开漫画 → 提示消失"天然地在三处同时生效，不需要各页面各自记一套已读状态
 * （那正是"全部分组和自建分组红点不同步"的经典成因）。
 */
internal class AlbumUpdateCenter(
    context: Context,
    core: JmxCore,
    private val bookshelfRepository: BookshelfRepository,
    private val accountDataRepository: AccountDataRepository,
) {
    private val store = AlbumUpdateStore(context)
    private val scanner = AlbumUpdateScanner(core, store)
    private val scanLock = Mutex()

    private val _records = MutableStateFlow(store.records())
    val records: StateFlow<Map<String, AlbumUpdateRecord>> = _records.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun pendingAlbumCount(): Int = _records.value.values.count(AlbumUpdateRecord::hasUpdate)

    fun pendingChapters(albumId: String): Int =
        _records.value[albumId.trim()]?.pendingChapters ?: 0

    /** 距上次扫描够久了吗。用来把"进页面就扫"压成"隔一段时间才扫"。 */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        now - store.lastScanAt() >= MIN_SCAN_INTERVAL_MILLIS

    /**
     * 用户点开了某部漫画：提示立即消失。
     *
     * [chapterCount] 有值时说明详情页已经拿到最新章节列表，顺手把最新话数一起对账，
     * 避免"扫描说 +2、详情其实 +3、退出来还剩 +1"。
     */
    fun markSeen(albumId: String, chapterCount: Int? = null) {
        if (chapterCount == null) {
            store.markSeen(albumId)
        } else {
            store.markSeenWithChapterCount(albumId, chapterCount)
        }
        publish()
    }

    /**
     * 扫一轮更新。
     *
     * [force] 为 false 时受 [isStale] 约束，避免每次切页面都打一轮 `/album`。
     * 登录态下把收藏也纳入扫描范围：书架条目大多来自收藏，但收藏里可能有还没进书架的漫画，
     * 而"我的 → 漫画收藏"的角标说的就是收藏。
     */
    suspend fun scan(includeFavorites: Boolean, force: Boolean): AlbumUpdateScanResult? {
        if (!force && !isStale()) return null
        if (!scanLock.tryLock()) return null
        _scanning.value = true
        try {
            val targets = LinkedHashSet<String>()
            bookshelfRepository.entries().forEach { targets += it.albumId }
            if (includeFavorites) {
                targets += collectFavoriteAlbumIds()
            }
            // 已经不在书架也不在收藏里的漫画不再关注，顺手把陈旧记录清掉。
            if (targets.isNotEmpty()) store.retainOnly(targets)
            return scanner.scan(targets)
        } finally {
            _scanning.value = false
            scanLock.unlock()
            publish()
        }
    }

    /**
     * 调试用：伪造更新。
     *
     * 真实更新等不来（参见 [AlbumUpdateStore.simulateUpdates] 的注释），
     * 所以从书架里取前几部漫画把基线往回调，让整条链路（红点 → 角标 → 点开消失）可验证。
     */
    fun simulateUpdates(albums: Int, chapters: Int): Int {
        val ids = bookshelfRepository.entries().take(albums.coerceAtLeast(0)).map { it.albumId }
        val affected = store.simulateUpdates(ids, chapters)
        publish()
        return affected
    }

    fun clearAllPending() {
        store.clearAllPending()
        publish()
    }

    private fun publish() {
        _records.value = store.records()
    }

    private suspend fun collectFavoriteAlbumIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        var page = 1
        while (page <= MAX_FAVORITE_PAGES) {
            val result = accountDataRepository.loadCollection(
                kind = AccountCollectionKind.FAVORITES,
                page = page,
            )
            val value = (result as? JmxResult.Success)?.value ?: break
            if (value.albums.isEmpty()) break
            value.albums.forEach { ids += it.id }
            val total = value.total
            if (total != null && ids.size >= total) break
            page++
        }
        return ids
    }
}

/**
 * 两次自动扫描之间的最小间隔。
 *
 * 一轮扫描是"每部漫画一次 `/album`"，不能随页面切换反复触发；
 * 用户想立刻知道结果时有书架页的下拉刷新（force = true）兜着。
 */
private const val MIN_SCAN_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

/** 收藏枚举的页数上限，纯粹防御畸形响应导致的死循环（每页 20，够到 2000 部）。 */
private const val MAX_FAVORITE_PAGES = 100

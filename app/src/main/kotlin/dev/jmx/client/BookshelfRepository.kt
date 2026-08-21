package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class BookshelfSortOrder(val label: String) {
    NAME("名称"),
    UPDATED("更新时间"),
    RECENTLY_READ("最近阅读"),
}

internal enum class BookshelfAuthorMatchSource(val label: String) {
    FAVORITES("仅我的收藏"),
    ALL_WORKS("所有作品"),
}

internal data class BookshelfGroup(
    val id: String,
    val name: String,
    val matchFavoritesByTags: Boolean,
    val tagRules: List<String>,
    val matchByAuthors: Boolean = false,
    val authorRules: List<String> = emptyList(),
    val authorMatchSource: BookshelfAuthorMatchSource = BookshelfAuthorMatchSource.FAVORITES,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class BookshelfEntry(
    val albumId: String,
    val name: String,
    val author: String,
    val coverUrl: String,
    val imageHost: String,
    val addedAt: Long,
    val updatedAt: Long = addedAt,
    val groupIds: Set<String> = emptySet(),
    val lastReadAt: Long? = null,
    val lastChapterId: String? = null,
    val lastChapterName: String? = null,
    val lastPageIndex: Int = 0,
    val lastPageCount: Int? = null,
) {
    fun toHomeAlbum(): HomeAlbum = HomeAlbum(
        id = albumId,
        name = name,
        author = author,
        coverUrl = coverUrl,
        imageHost = imageHost,
    )

    fun progressSummary(): String {
        val chapter = lastChapterName?.takeIf(String::isNotBlank)
            ?: lastChapterId?.takeIf(String::isNotBlank)?.let { "JM$it" }
            ?: return "尚未阅读"
        val page = (lastPageIndex + 1).coerceAtLeast(1)
        val pageText = lastPageCount?.takeIf { it > 0 }?.let { "$page/$it 页" } ?: "第 $page 页"
        return "$chapter · $pageText"
    }
}

/** 导入/导出用的书架快照：只有内容，不含排序偏好这类本机设置。 */
internal data class BookshelfSnapshot(
    val entries: List<BookshelfEntry>,
    val groups: List<BookshelfGroup>,
)

internal data class BookshelfImportOutcome(
    val addedEntries: Int,
    val mergedEntries: Int,
    val addedGroups: Int,
    val reusedGroups: Int,
    val droppedEntries: Int,
)

internal class BookshelfRepository(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        BOOKSHELF_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    fun entries(
        groupId: String = ALL_BOOKSHELF_GROUP_ID,
        order: BookshelfSortOrder = sortOrder(),
    ): List<BookshelfEntry> = synchronized(lock) {
        val entries = readEntries().let { current ->
            if (groupId == ALL_BOOKSHELF_GROUP_ID) current else current.filter { groupId in it.groupIds }
        }
        sortBookshelf(entries, order)
    }

    fun entry(albumId: String): BookshelfEntry? = synchronized(lock) {
        readEntries().firstOrNull { it.albumId == albumId }
    }

    fun groups(): List<BookshelfGroup> = synchronized(lock) {
        readGroups().sortedBy(BookshelfGroup::createdAt)
    }

    fun contains(albumId: String): Boolean = entry(albumId) != null

    fun add(album: HomeAlbum, groupIds: Set<String> = emptySet()): Boolean = synchronized(lock) {
        val validGroupIds = groupIds.intersect(readGroups().mapTo(mutableSetOf(), BookshelfGroup::id))
        val current = readEntries()
        val (updated, added) = addToBookshelf(current, album, now(), validGroupIds)
        writeEntries(updated)
        added
    }

    fun addAllToGroup(albums: List<HomeAlbum>, groupId: String): Int = synchronized(lock) {
        if (readGroups().none { it.id == groupId }) return@synchronized 0
        var current = readEntries()
        var changed = 0
        albums.distinctBy(HomeAlbum::id).forEach { album ->
            val before = current.firstOrNull { it.albumId == album.id }
            val result = addToBookshelf(current, album, now(), setOf(groupId))
            current = result.first
            val after = current.firstOrNull { it.albumId == album.id }
            if (before != after) changed++
        }
        if (changed > 0) writeEntries(current)
        changed
    }

    fun addEntriesToGroups(albumIds: Set<String>, groupIds: Set<String>): Int = synchronized(lock) {
        val validGroupIds = groupIds.intersect(readGroups().mapTo(mutableSetOf(), BookshelfGroup::id))
        if (albumIds.isEmpty() || validGroupIds.isEmpty()) return@synchronized 0
        val (updated, changed) = assignBookshelfGroups(
            entries = readEntries(),
            albumIds = albumIds,
            groupIds = validGroupIds,
            updatedAt = now(),
        )
        if (changed > 0) writeEntries(updated)
        changed
    }

    fun remove(albumId: String): Boolean = synchronized(lock) {
        val current = readEntries()
        val updated = current.filterNot { it.albumId == albumId }
        if (updated.size == current.size) return@synchronized false
        writeEntries(updated)
        true
    }

    fun createGroup(
        name: String,
        matchFavoritesByTags: Boolean,
        tagRules: List<String>,
        matchByAuthors: Boolean = false,
        authorRules: List<String> = emptyList(),
        authorMatchSource: BookshelfAuthorMatchSource = BookshelfAuthorMatchSource.FAVORITES,
    ): BookshelfGroup? = synchronized(lock) {
        val normalizedName = name.trim().takeIf(String::isNotEmpty) ?: return@synchronized null
        val current = readGroups()
        if (
            current.size >= MAX_BOOKSHELF_GROUPS ||
            current.any { it.name.equals(normalizedName, ignoreCase = true) }
        ) {
            return@synchronized null
        }
        val normalizedTags = tagRules.mapNotNull(::normalizeSearchTag).distinct()
        val normalizedAuthors = authorRules.mapNotNull(::normalizeSearchTag).distinct()
        val timestamp = now()
        val group = BookshelfGroup(
            id = "group-${UUID.randomUUID()}",
            name = normalizedName,
            matchFavoritesByTags = matchFavoritesByTags && normalizedTags.isNotEmpty(),
            tagRules = normalizedTags,
            matchByAuthors = matchByAuthors && normalizedAuthors.isNotEmpty(),
            authorRules = normalizedAuthors,
            authorMatchSource = authorMatchSource,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        writeGroups(current + group)
        group
    }

    fun updateGroup(
        groupId: String,
        name: String,
        matchFavoritesByTags: Boolean,
        tagRules: List<String>,
        matchByAuthors: Boolean = false,
        authorRules: List<String> = emptyList(),
        authorMatchSource: BookshelfAuthorMatchSource = BookshelfAuthorMatchSource.FAVORITES,
    ): BookshelfGroup? = synchronized(lock) {
        val normalizedName = name.trim().takeIf(String::isNotEmpty) ?: return@synchronized null
        val current = readGroups()
        if (current.any { it.id != groupId && it.name.equals(normalizedName, ignoreCase = true) }) {
            return@synchronized null
        }
        val normalizedTags = tagRules.mapNotNull(::normalizeSearchTag).distinct()
        val normalizedAuthors = authorRules.mapNotNull(::normalizeSearchTag).distinct()
        var updatedGroup: BookshelfGroup? = null
        val updated = current.map { group ->
            if (group.id != groupId) {
                group
            } else {
                group.copy(
                    name = normalizedName,
                    matchFavoritesByTags = matchFavoritesByTags && normalizedTags.isNotEmpty(),
                    tagRules = normalizedTags,
                    matchByAuthors = matchByAuthors && normalizedAuthors.isNotEmpty(),
                    authorRules = normalizedAuthors,
                    authorMatchSource = authorMatchSource,
                    updatedAt = now(),
                ).also { updatedGroup = it }
            }
        }
        if (updatedGroup != null) writeGroups(updated)
        updatedGroup
    }

    fun deleteGroup(groupId: String): Boolean = synchronized(lock) {
        val currentGroups = readGroups()
        val updatedGroups = currentGroups.filterNot { it.id == groupId }
        if (updatedGroups.size == currentGroups.size) return@synchronized false
        writeGroups(updatedGroups)
        writeEntries(
            readEntries().map { entry ->
                if (groupId in entry.groupIds) entry.copy(groupIds = entry.groupIds - groupId) else entry
            },
        )
        true
    }

    fun recordProgress(
        albumId: String,
        chapterId: String,
        chapterName: String,
        pageIndex: Int,
        pageCount: Int,
    ): Boolean = synchronized(lock) {
        val current = readEntries()
        val updated = updateBookshelfProgress(
            entries = current,
            albumId = albumId,
            chapterId = chapterId,
            chapterName = chapterName,
            pageIndex = pageIndex,
            pageCount = pageCount,
            readAt = now(),
        )
        if (updated == current) return@synchronized false
        writeEntries(updated)
        true
    }

    fun sortOrder(): BookshelfSortOrder {
        val stored = preferences.getString(BOOKSHELF_SORT_KEY, null)
        if (stored == LEGACY_RECENTLY_ADDED_SORT) return BookshelfSortOrder.UPDATED
        return BookshelfSortOrder.entries.firstOrNull { it.name == stored }
            ?: BookshelfSortOrder.RECENTLY_READ
    }

    fun setSortOrder(order: BookshelfSortOrder) {
        preferences.edit { putString(BOOKSHELF_SORT_KEY, order.name) }
    }

    /**
     * 取一份可导出的书架快照。
     *
     * [groupIds] 传 null 表示"全部"，此时 [includeGroups] 决定要不要带上分组定义；
     * 按分组导出时分组定义一定会带上——不带的话对方收到的是一堆无处安放的漫画。
     *
     * 条目里的 groupIds 会被裁到真正导出的分组集合上：留着悬空的分组 id，
     * 对方导入后就会出现"漫画属于一个看不见的分组"。
     */
    fun snapshot(
        groupIds: Set<String>? = null,
        includeGroups: Boolean = true,
    ): BookshelfSnapshot = synchronized(lock) {
        bookshelfSnapshotOf(
            entries = readEntries(),
            groups = readGroups().sortedBy(BookshelfGroup::createdAt),
            groupIds = groupIds,
            includeGroups = includeGroups,
        )
    }

    /** 整体替换：本地书架与分组全部作废，只留导入内容。 */
    fun replaceWith(snapshot: BookshelfSnapshot): BookshelfImportOutcome = synchronized(lock) {
        val (next, outcome) = replaceBookshelfWith(snapshot)
        writeGroups(next.groups)
        writeEntries(next.entries)
        outcome
    }

    /** 合并导入：只做加法，细则见 [mergeBookshelfWith]。 */
    fun mergeFrom(snapshot: BookshelfSnapshot): BookshelfImportOutcome = synchronized(lock) {
        val (next, outcome) = mergeBookshelfWith(
            entries = readEntries(),
            groups = readGroups(),
            snapshot = snapshot,
            mergedAt = now(),
        )
        if (outcome.addedGroups > 0) writeGroups(next.groups)
        if (outcome.addedEntries > 0 || outcome.mergedEntries > 0) writeEntries(next.entries)
        outcome
    }

    private fun readEntries(): List<BookshelfEntry> {
        val encoded = preferences.getString(BOOKSHELF_ENTRIES_KEY, null) ?: return emptyList()
        return runCatching { JSONArray(encoded).toBookshelfEntries() }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<BookshelfEntry>) {
        val array = JSONArray()
        entries.take(MAX_BOOKSHELF_ENTRIES).forEach { array.put(it.toJson()) }
        preferences.edit { putString(BOOKSHELF_ENTRIES_KEY, array.toString()) }
    }

    private fun readGroups(): List<BookshelfGroup> {
        val encoded = preferences.getString(BOOKSHELF_GROUPS_KEY, null) ?: return emptyList()
        return runCatching { JSONArray(encoded).toBookshelfGroups() }.getOrDefault(emptyList())
    }

    private fun writeGroups(groups: List<BookshelfGroup>) {
        val array = JSONArray()
        groups.take(MAX_BOOKSHELF_GROUPS).forEach { array.put(it.toJson()) }
        preferences.edit { putString(BOOKSHELF_GROUPS_KEY, array.toString()) }
    }
}

internal fun sortBookshelf(
    entries: List<BookshelfEntry>,
    order: BookshelfSortOrder,
): List<BookshelfEntry> = when (order) {
    BookshelfSortOrder.NAME -> entries.sortedWith(
        compareBy<BookshelfEntry> { it.name.lowercase(Locale.ROOT) }
            .thenBy(BookshelfEntry::albumId),
    )
    BookshelfSortOrder.UPDATED -> entries.sortedWith(
        compareByDescending<BookshelfEntry>(BookshelfEntry::updatedAt)
            .thenByDescending(BookshelfEntry::addedAt),
    )
    BookshelfSortOrder.RECENTLY_READ -> entries.sortedWith(
        compareByDescending<BookshelfEntry> { it.lastReadAt ?: Long.MIN_VALUE }
            .thenByDescending(BookshelfEntry::updatedAt),
    )
}

internal fun addToBookshelf(
    entries: List<BookshelfEntry>,
    album: HomeAlbum,
    addedAt: Long,
    groupIds: Set<String> = emptySet(),
): Pair<List<BookshelfEntry>, Boolean> {
    val existing = entries.firstOrNull { it.albumId == album.id }
    val entry = existing?.copy(
        name = album.name,
        author = album.author,
        coverUrl = album.coverUrl,
        imageHost = album.imageHost,
        groupIds = existing.groupIds + groupIds,
        updatedAt = if (
            existing.name != album.name ||
            existing.author != album.author ||
            existing.coverUrl != album.coverUrl ||
            existing.imageHost != album.imageHost ||
            !existing.groupIds.containsAll(groupIds)
        ) {
            addedAt
        } else {
            existing.updatedAt
        },
    ) ?: BookshelfEntry(
        albumId = album.id,
        name = album.name,
        author = album.author,
        coverUrl = album.coverUrl,
        imageHost = album.imageHost,
        addedAt = addedAt,
        updatedAt = addedAt,
        groupIds = groupIds,
    )
    return (listOf(entry) + entries.filterNot { it.albumId == album.id }) to (existing == null)
}

internal fun updateBookshelfProgress(
    entries: List<BookshelfEntry>,
    albumId: String,
    chapterId: String,
    chapterName: String,
    pageIndex: Int,
    pageCount: Int,
    readAt: Long,
): List<BookshelfEntry> {
    if (entries.none { it.albumId == albumId }) return entries
    return entries.map { entry ->
        if (entry.albumId != albumId) entry else entry.copy(
            lastReadAt = readAt,
            lastChapterId = chapterId,
            lastChapterName = chapterName,
            lastPageIndex = pageIndex.coerceAtLeast(0),
            lastPageCount = pageCount.takeIf { it > 0 },
        )
    }
}

internal fun assignBookshelfGroups(
    entries: List<BookshelfEntry>,
    albumIds: Set<String>,
    groupIds: Set<String>,
    updatedAt: Long,
): Pair<List<BookshelfEntry>, Int> {
    var changed = 0
    val updated = entries.map { entry ->
        if (entry.albumId !in albumIds || entry.groupIds.containsAll(groupIds)) {
            entry
        } else {
            changed++
            entry.copy(
                groupIds = entry.groupIds + groupIds,
                updatedAt = updatedAt,
            )
        }
    }
    return updated to changed
}

internal fun parseBookshelfTagRules(value: String): List<String> = parseBookshelfRules(value)

internal fun parseBookshelfAuthorRules(value: String): List<String> = parseBookshelfRules(value)

private fun parseBookshelfRules(value: String): List<String> = value
    .split(BOOKSHELF_TAG_RULE_DELIMITERS)
    .mapNotNull(::normalizeSearchTag)
    .distinct()

internal fun matchesBookshelfTagRules(albumTags: List<String>, rules: List<String>): Boolean {
    if (rules.isEmpty()) return false
    val available = albumTags.mapNotNull(::normalizeSearchTag).distinct()
    return rules.mapNotNull(::normalizeSearchTag).all { target ->
        available.any { tag -> tag == target || tag.contains(target) || target.contains(tag) }
    }
}

/** 作者规则要求全部命中，兼容服务端将多位作者合并到同一字段的返回形式。 */
internal fun matchesBookshelfAuthorRules(author: String, rules: List<String>): Boolean {
    if (rules.isEmpty()) return false
    val available = normalizeSearchTag(author) ?: return false
    return rules.mapNotNull(::normalizeSearchTag).all { target -> available.contains(target) }
}

internal fun HomeAlbum.matchesBookshelfPickerQuery(query: String): Boolean {
    val rawQuery = query.trim()
    if (rawQuery.isEmpty()) return true
    val vehicleNumber = rawQuery.lowercase(Locale.ROOT).removePrefix("jm").trim()
    if (vehicleNumber.isNotEmpty() && id.contains(vehicleNumber, ignoreCase = true)) return true
    val normalizedQuery = normalizeSearchTag(rawQuery) ?: return false
    return listOf(name, author).any { value ->
        normalizeSearchTag(value)?.contains(normalizedQuery) == true
    }
}

private fun BookshelfEntry.toJson(): JSONObject = JSONObject().apply {
    put("album_id", albumId)
    put("name", name)
    put("author", author)
    put("cover_url", coverUrl)
    put("image_host", imageHost)
    put("added_at", addedAt)
    put("updated_at", updatedAt)
    put("group_ids", JSONArray(groupIds.toList()))
    lastReadAt?.let { put("last_read_at", it) }
    lastChapterId?.let { put("last_chapter_id", it) }
    lastChapterName?.let { put("last_chapter_name", it) }
    put("last_page_index", lastPageIndex)
    lastPageCount?.let { put("last_page_count", it) }
}

private fun BookshelfGroup.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("match_favorites_by_tags", matchFavoritesByTags)
    put("tag_rules", JSONArray(tagRules))
    put("match_by_authors", matchByAuthors)
    put("author_rules", JSONArray(authorRules))
    put("author_match_source", authorMatchSource.name)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
}

private fun JSONObject.toBookshelfEntryOrNull(): BookshelfEntry? {
    val albumId = optString("album_id").trim().takeIf(String::isNotEmpty) ?: return null
    val addedAt = optLong("added_at", 0L)
    return BookshelfEntry(
        albumId = albumId,
        name = optString("name").trim().ifBlank { "JM$albumId" },
        author = optString("author").trim().ifBlank { "未知作者" },
        coverUrl = optString("cover_url").trim(),
        imageHost = optString("image_host").trim(),
        addedAt = addedAt,
        updatedAt = optLong("updated_at", addedAt),
        groupIds = optStringList("group_ids").toSet(),
        lastReadAt = optNullableLong("last_read_at"),
        lastChapterId = optString("last_chapter_id").trim().takeIf(String::isNotEmpty),
        lastChapterName = optString("last_chapter_name").trim().takeIf(String::isNotEmpty),
        lastPageIndex = optInt("last_page_index", 0).coerceAtLeast(0),
        lastPageCount = optNullableInt("last_page_count")?.takeIf { it > 0 },
    )
}

private fun JSONObject.toBookshelfGroupOrNull(): BookshelfGroup? {
    val id = optString("id").trim().takeIf(String::isNotEmpty) ?: return null
    val name = optString("name").trim().takeIf(String::isNotEmpty) ?: return null
    val createdAt = optLong("created_at", 0L)
    return BookshelfGroup(
        id = id,
        name = name,
        matchFavoritesByTags = optBoolean("match_favorites_by_tags", false),
        tagRules = optStringList("tag_rules").mapNotNull(::normalizeSearchTag).distinct(),
        matchByAuthors = optBoolean("match_by_authors", false),
        authorRules = optStringList("author_rules").mapNotNull(::normalizeSearchTag).distinct(),
        authorMatchSource = BookshelfAuthorMatchSource.entries.firstOrNull {
            it.name == optString("author_match_source")
        } ?: BookshelfAuthorMatchSource.FAVORITES,
        createdAt = createdAt,
        updatedAt = optLong("updated_at", createdAt),
    )
}

private fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        repeat(array.length()) { index ->
            array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }
}

private fun JSONObject.optNullableLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONArray.toBookshelfEntries(): List<BookshelfEntry> {
    val parsed = mutableListOf<BookshelfEntry>()
    repeat(length()) { index -> optJSONObject(index)?.toBookshelfEntryOrNull()?.let(parsed::add) }
    return parsed.distinctBy(BookshelfEntry::albumId)
}

private fun JSONArray.toBookshelfGroups(): List<BookshelfGroup> {
    val parsed = mutableListOf<BookshelfGroup>()
    repeat(length()) { index -> optJSONObject(index)?.toBookshelfGroupOrNull()?.let(parsed::add) }
    return parsed.distinctBy(BookshelfGroup::id)
}

/**
 * 导出范围裁剪。
 *
 * [groupIds] 为 null 表示"全部"，此时 [includeGroups] 决定要不要带上分组定义；
 * 按分组导出时分组定义一定会带上，否则对方拿到的漫画会挂在看不见的分组 id 上。
 * 条目里的 groupIds 也会裁到真正导出的分组集合上，避免留下悬空的分组 id。
 */
internal fun bookshelfSnapshotOf(
    entries: List<BookshelfEntry>,
    groups: List<BookshelfGroup>,
    groupIds: Set<String>?,
    includeGroups: Boolean,
): BookshelfSnapshot {
    val kept = when {
        groupIds != null -> groups.filter { it.id in groupIds }
        includeGroups -> groups
        else -> emptyList()
    }
    val keptIds = kept.mapTo(mutableSetOf(), BookshelfGroup::id)
    return BookshelfSnapshot(
        entries = entries
            .filter { groupIds == null || it.groupIds.any { id -> id in keptIds } }
            .map { it.copy(groupIds = it.groupIds.intersect(keptIds)) },
        groups = kept,
    )
}

/** 整体替换后的书架内容与统计；超出上限的部分计入 droppedEntries。 */
internal fun replaceBookshelfWith(
    snapshot: BookshelfSnapshot,
): Pair<BookshelfSnapshot, BookshelfImportOutcome> {
    val groups = snapshot.groups.distinctBy(BookshelfGroup::id).take(MAX_BOOKSHELF_GROUPS)
    val validGroupIds = groups.mapTo(mutableSetOf(), BookshelfGroup::id)
    val incoming = snapshot.entries.distinctBy(BookshelfEntry::albumId)
    val entries = incoming
        .map { it.copy(groupIds = it.groupIds.intersect(validGroupIds)) }
        .take(MAX_BOOKSHELF_ENTRIES)
    return BookshelfSnapshot(entries = entries, groups = groups) to BookshelfImportOutcome(
        addedEntries = entries.size,
        mergedEntries = 0,
        addedGroups = groups.size,
        reusedGroups = 0,
        droppedEntries = incoming.size - entries.size,
    )
}

/**
 * 合并导入：只做加法。
 *
 * 分组按**名称**归并而不是按 id：分组 id 是随机 UUID，照搬会让书架上出现两个同名分组。
 * 已有漫画只补分组归属，本地阅读进度一律不动——别人读到哪与我无关；
 * 新漫画保留文件里带的进度，于是"导出自己的书架再导回来"是无损的。
 */
internal fun mergeBookshelfWith(
    entries: List<BookshelfEntry>,
    groups: List<BookshelfGroup>,
    snapshot: BookshelfSnapshot,
    mergedAt: Long,
    freshGroupId: () -> String = { "group-${UUID.randomUUID()}" },
): Pair<BookshelfSnapshot, BookshelfImportOutcome> {
    val remappedGroupIds = mutableMapOf<String, String>()
    val newGroups = mutableListOf<BookshelfGroup>()
    var reusedGroups = 0
    snapshot.groups.distinctBy(BookshelfGroup::id).forEach { group ->
        val matched = (groups + newGroups).firstOrNull { it.name.equals(group.name, ignoreCase = true) }
        if (matched != null) {
            remappedGroupIds[group.id] = matched.id
            reusedGroups++
            return@forEach
        }
        if (groups.size + newGroups.size >= MAX_BOOKSHELF_GROUPS) return@forEach
        // id 撞车只会发生在"导出自己的书架再导回来"，换个新 id 即可，名称归并已经兜住了重复。
        val id = if (groups.any { it.id == group.id }) freshGroupId() else group.id
        remappedGroupIds[group.id] = id
        newGroups += group.copy(id = id)
    }

    var current = entries
    var addedEntries = 0
    var mergedEntries = 0
    var droppedEntries = 0
    snapshot.entries.distinctBy(BookshelfEntry::albumId).forEach { incoming ->
        val groupIds = incoming.groupIds.mapNotNullTo(mutableSetOf()) { remappedGroupIds[it] }
        val existing = current.firstOrNull { it.albumId == incoming.albumId }
        when {
            existing == null && current.size >= MAX_BOOKSHELF_ENTRIES -> droppedEntries++
            existing == null -> {
                current = listOf(incoming.copy(groupIds = groupIds)) + current
                addedEntries++
            }
            !existing.groupIds.containsAll(groupIds) -> {
                current = current.map { entry ->
                    if (entry.albumId != incoming.albumId) {
                        entry
                    } else {
                        entry.copy(groupIds = entry.groupIds + groupIds, updatedAt = mergedAt)
                    }
                }
                mergedEntries++
            }
        }
    }
    return BookshelfSnapshot(entries = current, groups = groups + newGroups) to BookshelfImportOutcome(
        addedEntries = addedEntries,
        mergedEntries = mergedEntries,
        addedGroups = newGroups.size,
        reusedGroups = reusedGroups,
        droppedEntries = droppedEntries,
    )
}

/**
 * 导出文件的固定格式。
 *
 * 外层信封只加校验和统计字段，`entries` / `groups` 与本地存储用的是同一套编码，
 * 所以格式天然可逆：导出的文件导回来能完整还原书架。
 */
internal fun encodeBookshelfSnapshot(snapshot: BookshelfSnapshot, exportedAt: Long): String {
    val entries = JSONArray()
    snapshot.entries.take(MAX_BOOKSHELF_ENTRIES).forEach { entries.put(it.toJson()) }
    val groups = JSONArray()
    snapshot.groups.take(MAX_BOOKSHELF_GROUPS).forEach { groups.put(it.toJson()) }
    return JSONObject().apply {
        put("format", BOOKSHELF_TRANSFER_FORMAT)
        put("version", BOOKSHELF_TRANSFER_VERSION)
        put("app", "JMComicX")
        put("exported_at", exportedAt)
        put("entry_count", entries.length())
        put("group_count", groups.length())
        put("groups", groups)
        put("entries", entries)
    }.toString(2)
}

/**
 * 解析导出文件。
 *
 * 只认 `format` 字段，不卡 `version`：字段都是可选读取的，未来加字段的新版文件在老版本上
 * 也能读出它认识的那部分，而不是直接告诉用户"文件不支持"。
 */
internal fun decodeBookshelfSnapshot(text: String): BookshelfSnapshot? {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (root.optString("format") != BOOKSHELF_TRANSFER_FORMAT) return null
    return BookshelfSnapshot(
        entries = root.optJSONArray("entries")?.toBookshelfEntries() ?: emptyList(),
        groups = root.optJSONArray("groups")?.toBookshelfGroups() ?: emptyList(),
    )
}

internal const val BOOKSHELF_TRANSFER_FORMAT = "jmcomicx-bookshelf"
internal const val BOOKSHELF_TRANSFER_VERSION = 1
internal const val ALL_BOOKSHELF_GROUP_ID = "all"
private const val BOOKSHELF_PREFERENCES = "jmx_bookshelf"
private const val BOOKSHELF_ENTRIES_KEY = "entries"
private const val BOOKSHELF_GROUPS_KEY = "groups"
private const val BOOKSHELF_SORT_KEY = "sort_order"
private const val LEGACY_RECENTLY_ADDED_SORT = "RECENTLY_ADDED"
private const val MAX_BOOKSHELF_ENTRIES = 500
private const val MAX_BOOKSHELF_GROUPS = 40
private val BOOKSHELF_TAG_RULE_DELIMITERS = Regex("[\\s,，、;；]+")

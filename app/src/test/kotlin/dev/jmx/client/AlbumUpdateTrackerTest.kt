package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumUpdateTrackerTest {
    @Test
    fun pendingChaptersIsTheDistanceBetweenTheSeenAndLatestChapterEnd() {
        val record = AlbumUpdateRecord(
            albumId = "1",
            seenChapterCount = 12,
            latestChapterCount = 15,
            checkedAt = 0L,
        )

        assertEquals(3, record.pendingChapters)
        assertTrue(record.hasUpdate)
    }

    @Test
    fun removedChaptersDoNotCountAsAnUpdate() {
        // 作者删话或把多话并成一话时话数会变少，那不是更新，不该冒红点。
        val record = AlbumUpdateRecord(
            albumId = "1",
            seenChapterCount = 20,
            latestChapterCount = 18,
            checkedAt = 0L,
        )

        assertEquals(0, record.pendingChapters)
        assertTrue(!record.hasUpdate)
    }

    @Test
    fun encodeAndDecodeRoundTripsEveryField() {
        val records = mapOf(
            "1" to AlbumUpdateRecord("1", seenChapterCount = 3, latestChapterCount = 7, checkedAt = 111L),
            "2" to AlbumUpdateRecord("2", seenChapterCount = 5, latestChapterCount = 5, checkedAt = 222L),
        )

        assertEquals(records, decodeAlbumUpdateRecords(encodeAlbumUpdateRecords(records)))
    }

    @Test
    fun decodeIgnoresMalformedPayloadInsteadOfThrowing() {
        assertTrue(decodeAlbumUpdateRecords("").isEmpty())
        assertTrue(decodeAlbumUpdateRecords("{not json").isEmpty())
        // 缺 id 的条目直接丢掉，剩下的照常解析。
        assertEquals(
            setOf("7"),
            decodeAlbumUpdateRecords("""[{"seen":1,"latest":2},{"id":"7","seen":1,"latest":2}]""").keys,
        )
    }

    @Test
    fun decodeClampsSeenCountThatExceedsLatest() {
        val decoded = decodeAlbumUpdateRecords("""[{"id":"1","seen":9,"latest":4,"checkedAt":5}]""")

        assertEquals(4, decoded.getValue("1").seenChapterCount)
        assertEquals(0, decoded.getValue("1").pendingChapters)
    }

    @Test
    fun descendingFavoritesKeepServerPagingUntouched() {
        (1..3).forEach { page ->
            assertEquals(
                page,
                favoriteServerPage(page, FavoriteSortDirection.DESCENDING, serverPageCount = 9),
            )
        }
    }

    @Test
    fun ascendingFavoritesWalkServerPagesBackwards() {
        // 服务端 3 页时：逻辑 1→服务端 3，逻辑 2→服务端 2，逻辑 3→服务端 1。
        assertEquals(3, favoriteServerPage(1, FavoriteSortDirection.ASCENDING, serverPageCount = 3))
        assertEquals(2, favoriteServerPage(2, FavoriteSortDirection.ASCENDING, serverPageCount = 3))
        assertEquals(1, favoriteServerPage(3, FavoriteSortDirection.ASCENDING, serverPageCount = 3))
        // 越过头之后返回 null，调用方据此收尾而不是反复请求第 1 页。
        assertNull(favoriteServerPage(4, FavoriteSortDirection.ASCENDING, serverPageCount = 3))
    }

    @Test
    fun ascendingFavoritesProbeFirstPageWhenTotalIsUnknown() {
        assertEquals(1, favoriteServerPage(1, FavoriteSortDirection.ASCENDING, serverPageCount = null))
    }

    @Test
    fun serverPageCountRoundsUpAndRejectsUnknownTotals() {
        assertNull(favoriteServerPageCount(null))
        assertNull(favoriteServerPageCount(0))
        assertEquals(1, favoriteServerPageCount(1))
        assertEquals(1, favoriteServerPageCount(20))
        assertEquals(2, favoriteServerPageCount(21))
        assertEquals(3, favoriteServerPageCount(45))
    }
}

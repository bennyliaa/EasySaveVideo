package com.easysavevideo.library

import org.junit.Assert.*
import org.junit.Test

class LibraryQueryPolicyTest {
    @Test fun everyPageIsBoundedAndOrderedWithStableIds() {
        val input = (0 until 13).reversed().map { item("item_%02d".format(it), name = "Same", date = 10) }
        val query = LibraryQuery(sort = LibrarySort.DATE, direction = SortDirection.DESCENDING, pageSize = 4)
        var cursor: LibraryCursor? = null
        val actual = mutableListOf<String>()
        do {
            val page = LibraryQueryPolicy.page(input, query.copy(cursor = cursor), 3)
            assertTrue(page.items.size <= 4)
            actual += page.items.map { it.itemId }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(input.map { it.itemId }.sorted(), actual)
        assertEquals(13, actual.distinct().size)
    }

    @Test fun ignoresStagingInvisibleLifecycleAndOtherFolders() {
        val ready = item("ready")
        val input = listOf(ready, item("pending").copy(lifecycle = MediaLifecycle.DELETE_PENDING),
            item("deleted").copy(lifecycle = MediaLifecycle.DELETED), item("nested").copy(folderId = "folder"))
        assertEquals(listOf(ready), LibraryQueryPolicy.page(input, LibraryQuery(), 0).items)
        assertEquals(listOf("nested"), LibraryQueryPolicy.page(input, LibraryQuery(folderId = "folder"), 0).items.map { it.itemId })
    }

    @Test fun searchUsesCanonicalLiteralSubstringNotWildcards() {
        val input = listOf(item("cafe", "Café highlights"), item("literal", "100%_match"), item("other", "100XYmatch"))
        assertEquals(listOf("cafe"), LibraryQueryPolicy.page(input, LibraryQuery(text = "CAFE\u0301"), 0).items.map { it.itemId })
        assertEquals(listOf("literal"), LibraryQueryPolicy.page(input, LibraryQuery(text = "%_"), 0).items.map { it.itemId })
    }

    @Test fun catalogMutationInvalidatesContinuationRatherThanSkippingOrDuplicating() {
        val input = (1..3).map { item("item$it") }
        val query = LibraryQuery(pageSize = 1)
        val cursor = LibraryQueryPolicy.page(input, query, 1).nextCursor!!
        fails(LibraryError.STALE_PAGE) { LibraryQueryPolicy.page(input, query.copy(cursor = cursor), 2) }
    }

    @Test fun cursorBindsFolderSearchSortDirectionAndPageSize() {
        val query = LibraryQuery(pageSize = 1)
        val cursor = LibraryQueryPolicy.page(listOf(item("a"), item("b")), query, 5).nextCursor!!
        listOf(query.copy(folderId = "folder"), query.copy(text = "new"), query.copy(sort = LibrarySort.NAME),
            query.copy(direction = SortDirection.ASCENDING), query.copy(pageSize = 2))
            .forEach { changed -> fails(LibraryError.STALE_PAGE) { LibraryQueryPolicy.requireCurrentCursor(changed.copy(cursor = cursor), 5) } }
    }

    @Test fun canonicalEquivalentQueriesHaveSameFingerprint() {
        assertEquals(LibraryQuery(text = "CAFÉ").queryKey(), LibraryQuery(text = "cafe\u0301").queryKey())
        assertNotEquals(LibraryQuery().queryKey(), LibraryQuery(folderId = "root").queryKey())
        assertEquals(64, LibraryQuery().queryKey().length)
    }

    @Test fun unsupportedAndOversizedPagesFailBeforeWork() {
        fails(LibraryError.INVALID_QUERY) { LibraryQuery(pageSize = 0) }
        fails(LibraryError.INVALID_QUERY) { LibraryQuery(pageSize = 101) }
        fails(LibraryError.INVALID_QUERY) { LibraryQuery(text = "a".repeat(513)) }
        assertThrows(IllegalArgumentException::class.java) { LibraryCursor(0, "not_a_hash", 0) }
        assertThrows(IllegalArgumentException::class.java) { LibraryCursor(0, LibraryQuery().queryKey(), -1) }
    }

    @Test fun forgedOversizedOffsetCannotOverflowOrAllocateAnOversizedPage() {
        val query = LibraryQuery()
        fails(LibraryError.STALE_PAGE) {
            LibraryQueryPolicy.page(listOf(item("a")), query.copy(cursor = LibraryCursor(0, query.queryKey(), Int.MAX_VALUE)), 0)
        }
        val empty = LibraryQueryPolicy.page(emptyList(), query, 0)
        assertTrue(empty.items.isEmpty()); assertNull(empty.nextCursor)
    }

    @Test fun allSupportedSortsAndDirectionsWork() {
        val input = listOf(item("a", "Zulu", 3, 20, 100), item("b", "alpha", 1, 30, 300), item("c", "Beta", 2, 10, 200))
        val expected = mapOf(LibrarySort.NAME to listOf("b", "c", "a"), LibrarySort.DATE to listOf("b", "c", "a"),
            LibrarySort.DURATION to listOf("c", "a", "b"), LibrarySort.SIZE to listOf("a", "c", "b"))
        for ((sort, ids) in expected) {
            assertEquals(ids, LibraryQueryPolicy.page(input, LibraryQuery(sort = sort, direction = SortDirection.ASCENDING), 0).items.map { it.itemId })
            assertEquals(ids.reversed(), LibraryQueryPolicy.page(input, LibraryQuery(sort = sort, direction = SortDirection.DESCENDING), 0).items.map { it.itemId })
        }
    }

    @Test fun unknownDurationSortsLastInBothDirections() {
        val input = listOf(item("unknown", duration = null), item("short", duration = 2), item("long", duration = Long.MAX_VALUE))
        assertEquals(listOf("short", "long", "unknown"), input.sortedWith(LibraryQueryPolicy.comparator(LibrarySort.DURATION, SortDirection.ASCENDING)).map { it.itemId })
        assertEquals(listOf("long", "short", "unknown"), input.sortedWith(LibraryQueryPolicy.comparator(LibrarySort.DURATION, SortDirection.DESCENDING)).map { it.itemId })
    }

    @Test fun pageRejectsHiddenItemsAndWrongCatalogVersion() {
        assertThrows(IllegalArgumentException::class.java) { LibraryPage(listOf(item("hidden").copy(lifecycle = MediaLifecycle.DELETED)), 0, null) }
        assertThrows(IllegalArgumentException::class.java) { LibraryPage(emptyList(), 0, LibraryCursor(1, LibraryQuery().queryKey(), 1)) }
    }

    private fun item(id: String, name: String = "Video", date: Long = 0, duration: Long? = 0, size: Long = 0) =
        MediaItem(id, 1, 1, null, name, date, MediaDateKind.IMPORTED, size, duration, null, null)
    private fun fails(error: LibraryError, action: () -> Unit) {
        assertEquals(error, assertThrows(LibraryFailure::class.java, action).code)
    }
}

package com.easysavevideo.app.licenses

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source + arithmetic regression model only, not a Compose measure or device acceptance test. */
class NoticeViewportContractTest {
    private fun source(): String = listOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
        .first { it.isDirectory }.resolve("com/easysavevideo/app/licenses/ThirdPartyNotices.kt").readText()

    @Test fun titleBackAndBodyUseOneFullViewportWithoutWeightedOrNestedScroll() {
        val source = source()
        assertEquals(1, Regex("\\bLazyColumn\\(").findAll(source).count())
        assertTrue(source.contains("LazyColumn(Modifier.fillMaxSize()"))
        assertFalse(source.contains("Modifier.weight(")); assertFalse(source.contains("verticalScroll("))
        val viewport = source.indexOf("LazyColumn(Modifier.fillMaxSize()")
        for (key in listOf("notice-title", "notice-back", "notice-section-")) assertTrue(source.indexOf(key) > viewport)
    }

    @Test fun loadingAndFailureUseTransientStateInsteadOfClampingRetainedReadingPosition() {
        assertTrue(source().contains("state = if (contentVisible) listState else transientState"))
        assertTrue(source().contains("key = { index -> \"notice-section-\$index\" }"))
        assertFalse(noticeContentVisible(false, emptyList()))
        assertFalse(noticeContentVisible(true, listOf("stale")))
        assertTrue(noticeContentVisible(false, listOf("ready")))
    }

    @Test fun shortHeightAndLargeFontModelHasReachableHeaderBackAndFinalBody() {
        // Each row belongs to the same scroll extent; no fixed header height is subtracted.
        for (height in listOf(1, 48, 96, 240)) for (fontScale in listOf(1, 2, 3)) {
            val rows = listOf(80 * fontScale, 64 * fontScale, 4096 * fontScale, 240 * fontScale)
            val total = rows.sum()
            val maxOffset = (total - height).coerceAtLeast(0)
            var top = 0
            for (rowHeight in rows) {
                val offset = top.coerceAtMost(maxOffset)
                assertTrue("row must intersect the viewport", top < offset + height && top + rowHeight > offset)
                top += rowHeight
            }
            assertEquals(total, maxOffset + height)
            // The old fixed header allocation can consume this entire viewport.
            if (height <= rows[0] + rows[1]) assertEquals(0, (height - rows[0] - rows[1]).coerceAtLeast(0))
        }
    }
}

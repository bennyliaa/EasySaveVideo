package com.easysavevideo.app.ui

import com.easysavevideo.app.save.selectedTaskDetail
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ResponsivePaneStateTest {
    private fun record(id: String, revision: Long = 0) = SaveRecord(
        SaveRequest(id, "key-$id", SelectedMediaResource("candidate-$id", "variant",
            SourceUrl("https://example.invalid/")).asDurableReference()),
        TaskSnapshot(id, revision, TaskState.QUEUED, TaskMethod.DIRECT),
        "commit-$id", "operation-$id", 1000, 2)

    @Test fun expandedRequiresBothWidthAndHeightAndLargeFontsFallBack() {
        assertTrue(responsivePaneLayout(1024f, 768f, 1f, 16f).split)
        assertFalse(responsivePaneLayout(839f, 768f, 1f, 16f).split)
        assertFalse(responsivePaneLayout(1024f, 320f, 1f, 16f).split)
        assertFalse(responsivePaneLayout(1024f, 768f, 2f, 16f).split)
        assertTrue(responsivePaneLayout(1600f, 1000f, 2f, 16f).split)
    }
    @Test fun lowHeightBudgetsLeavePositiveContentSpaceWithoutShrinkingFonts() {
        for (height in listOf(120f, 240f, 320f, 480f, 768f, 1000f)) for (font in listOf(1f, 1.5f, 2f, 3f)) {
            val layout = responsivePaneLayout(1200f, height, font, 16f)
            assertTrue(layout.headerMaxHeightDp >= 0f && layout.footerMaxHeightDp >= 0f)
            assertTrue(layout.headerMaxHeightDp + layout.footerMaxHeightDp + 32f < height)
        }
    }
    @Test fun splitAlwaysLeavesBothPaneWidthBudgets() {
        for (width in listOf(840f, 1024f, 1600f, 2400f)) for (font in listOf(1f, 1.5f, 2f, 3f)) {
            val layout = responsivePaneLayout(width, 1600f, font, 16f)
            if (layout.split) {
                assertTrue(layout.sideWidthDp >= 320f * font)
                assertTrue(width - layout.sideWidthDp - 16f >= 400f * font)
            }
        }
    }
    @Test fun invalidGeometryCannotEnableASecondPane() {
        for (value in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(responsivePaneLayout(value, 768f, 1f, 16f).split)
            assertFalse(responsivePaneLayout(1024f, value, 1f, 16f).split)
            assertFalse(responsivePaneLayout(1024f, 768f, value, 16f).split)
        }
        assertEquals(ResponsivePaneLayout(false, 0f, 0f, 0f), responsivePaneLayout(1024f, 768f, 1f, -1f))
    }
    @Test fun selectedStableIdResolvesLatestRevisionAfterReorderingAndModeChanges() {
        val first = record("first"); val second = record("second")
        val latest = second.copy(task = second.task.copy(revision = 7, state = TaskState.CANCELLED))
        assertSame(second, selectedTaskDetail(listOf(first, second), "second"))
        for ((width, height, font) in listOf(Triple(360f, 700f, 1f), Triple(1200f, 900f, 1f), Triple(1200f, 320f, 2f))) {
            responsivePaneLayout(width, height, font, 16f)
            assertSame(latest, selectedTaskDetail(listOf(latest, first), "second"))
        }
        assertEquals(TaskState.QUEUED, second.task.state)
        assertEquals(0L, second.task.revision)
    }
    @Test fun missingSelectionDoesNotGuessFirstRecordAndCanResolveWhenItReturns() {
        val chosen = record("chosen"); val other = record("other")
        assertNull(selectedTaskDetail(listOf(other), "chosen"))
        assertNull(selectedTaskDetail(listOf(chosen, other), null))
        assertSame(chosen, selectedTaskDetail(listOf(other, chosen), "chosen"))
        assertNull(selectedTaskDetail(listOf(chosen, chosen.copy(task = chosen.task.copy(revision = 2))), "chosen"))
    }
    @Test fun paneMessagesHaveMatchedLocaleKeysAndNoReplacementCharacters() {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        fun messages(locale: String): Map<String, String> {
            val f = DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val nodes = f.newDocumentBuilder().parse(File(root, "$locale/responsive_panes.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { index -> val n = nodes.item(index);n.attributes.getNamedItem("name").nodeValue to n.textContent }
        }
        val en = messages("values");val zh = messages("values-zh-rCN")
        assertEquals(6, en.size);assertEquals(en.keys, zh.keys)
        (en.values + zh.values).forEach { assertFalse(it.contains('\uFFFD'));assertTrue(it.isNotBlank()) }
    }
}

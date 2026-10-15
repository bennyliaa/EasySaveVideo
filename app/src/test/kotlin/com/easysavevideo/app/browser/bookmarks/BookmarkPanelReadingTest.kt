package com.easysavevideo.app.browser.bookmarks

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.Saver
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Actual Foundation saver plus source wiring; this does not render a Compose window transition. */
class BookmarkPanelReadingTest {
    @Test fun foundationSaverRoundTripsOnlyIndexAndOffsetWithoutBookmarkPayload() {
        @Suppress("UNCHECKED_CAST")
        val saver = LazyListState.Saver as Saver<LazyListState, Any>
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = value is Int }
        for (index in listOf(0, 1, 50, 199)) for (offset in listOf(0, 37, 240)) {
            val state = LazyListState(index, offset)
            val saved = with(saver) { scope.save(state) }
            assertEquals(listOf(index, offset), saved)
            val restored = requireNotNull(saver.restore(requireNotNull(saved)))
            assertEquals(index, restored.firstVisibleItemIndex); assertEquals(offset, restored.firstVisibleItemScrollOffset)
        }
    }
    @Test fun dialogAndSidebarReceiveTheSameBrowserOwnedSaveableListState() {
        val screen = File("src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt").readText()
        assertEquals(1, Regex("val bookmarkListState = rememberLazyListState\\(\\)").findAll(screen).count())
        assertEquals(2, Regex("BookmarkPanel\\(bookmarks, bookmarkListState,").findAll(screen).count())
        assertTrue(screen.indexOf("val bookmarkListState") < screen.indexOf("if (bookmarksOpen && wide"))
        assertTrue(screen.indexOf("val bookmarkListState") < screen.indexOf("if (bookmarksOpen && !wide"))
        assertFalse(screen.contains("!recordingPresentation && bookmarksOpen"))
        val panel = File("src/main/kotlin/com/easysavevideo/app/browser/bookmarks/BookmarkPanel.kt").readText()
        assertTrue(panel.contains("listState: LazyListState")); assertTrue(panel.contains("state = listState"))
        assertFalse(panel.contains("rememberLazyListState")); assertTrue(panel.contains("items(state.items, key = { it.id })"))
    }
    @Test fun feedbackAndRetryStayInBoundedPanelHeaderOutsideTheScrollingRows() {
        val panel = File("src/main/kotlin/com/easysavevideo/app/browser/bookmarks/BookmarkPanel.kt").readText()
        val lazy = panel.indexOf("        LazyColumn(")
        assertTrue(panel.indexOf("state.notice?.let") in 0 until lazy)
        assertTrue(panel.indexOf("if (status.retry)") in 0 until lazy)
        assertTrue(panel.contains("val headerMaxHeight = (maxHeight * 0.45f).coerceAtMost(280.dp)"))
        assertTrue(panel.contains("heightIn(max = headerMaxHeight)"))
        assertTrue(panel.contains("onClick = { if (canAct()) runtime.load() }")); assertTrue(panel.contains("runtime::dismissNotice"))
        assertTrue(panel.contains("runtime.remove(item)")); assertTrue(panel.contains("onOpen(item)"))
        val browser = File("src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt").readText()
        assertTrue(browser.contains("if (!bookmarksOpen) bookmarks?.state?.notice?.let"))
    }
    @Test fun allPanelCopyAndSemanticLabelsArePairedWithMatchingArguments() {
        fun resources(locale: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/bookmark_panel.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val en = resources("values"); val zh = resources("values-zh-rCN"); assertEquals(18, en.size); assertEquals(en.keys, zh.keys)
        val argument = Regex("%[1-9]\\$[sd]")
        for (key in en.keys) {
            assertTrue(en.getValue(key).isNotBlank()); assertTrue(zh.getValue(key).isNotBlank())
            assertEquals(argument.findAll(en.getValue(key)).map { it.value }.toList(), argument.findAll(zh.getValue(key)).map { it.value }.toList())
        }
        assertEquals(listOf("%1\$s"), argument.findAll(en.getValue("bookmark_open_description")).map { it.value }.toList())
        assertEquals(listOf("%1\$s"), argument.findAll(en.getValue("bookmark_remove_description")).map { it.value }.toList())
        val panel = File("src/main/kotlin/com/easysavevideo/app/browser/bookmarks/BookmarkPanel.kt").readText()
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(panel))
        assertTrue(panel.contains("TextDirection.Ltr")); assertTrue(panel.contains("contentDescription = openDescription"))
        assertTrue(panel.contains("contentDescription = removeDescription"))
    }
}

package com.easysavevideo.app.browser.history

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Actual Foundation saver plus source contracts. No Android Activity or Compose window is instantiated. */
class HistoryPanelReadingTest {
    private fun panel() = File("src/main/kotlin/com/easysavevideo/app/browser/history/HistoryPanel.kt").readText()

    @Test fun foundationSaverRoundTripsOnlyReadingIndexAndOffsetWithoutHistoryPayload() {
        @Suppress("UNCHECKED_CAST")
        val saver = LazyListState.Saver as Saver<LazyListState, Any>
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = value is Int }
        for (index in listOf(0, 1, 50, 199)) for (offset in listOf(0, 37, 240)) {
            val saved = with(saver) { scope.save(LazyListState(index, offset)) }
            assertEquals(listOf(index, offset), saved)
            val restored = requireNotNull(saver.restore(requireNotNull(saved)))
            assertEquals(index, restored.firstVisibleItemIndex)
            assertEquals(offset, restored.firstVisibleItemScrollOffset)
        }
    }

    @Test fun compactAndWideHostsReceiveOneBrowserOwnedListStateAndStableRowKeys() {
        val browser = File("src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt").readText()
        assertEquals(1, Regex("val historyListState = rememberLazyListState\\(\\)").findAll(browser).count())
        assertEquals(2, Regex("HistoryPanel\\(ordinaryHistory, historyListState,").findAll(browser).count())
        val firstPanel = browser.indexOf("HistoryPanel(ordinaryHistory, historyListState,")
        assertTrue(browser.indexOf("val historyListState") in 0 until firstPanel)
        val panel = panel()
        assertTrue(panel.contains("listState: LazyListState")); assertTrue(panel.contains("state = listState"))
        assertTrue(panel.contains("items(state.items, key = { it.id })"))
        assertFalse(panel.contains("rememberLazyListState")); assertFalse(panel.contains("rememberSaveable"))
    }

    @Test fun noticeStatusAndRetryPrecedePrivacyTextInBoundedScrollableHeader() {
        val panel = panel(); val lazy = panel.indexOf("            LazyColumn(")
        val privacy = panel.indexOf("R.string.history_privacy")
        assertTrue(privacy in 0 until lazy)
        for (feedback in listOf("state.notice?.let", "status.message?.let", "if (status.retry)")) {
            assertTrue(panel.indexOf(feedback) in 0 until privacy)
        }
        assertTrue(panel.contains("val headerMaxHeight = (maxHeight * 0.45f).coerceAtMost(280.dp)"))
        assertTrue(panel.contains("heightIn(max = headerMaxHeight)"))
        assertTrue(panel.contains("verticalScroll(rememberScrollState())"))
        assertTrue(panel.contains("runtime.load()")); assertTrue(panel.contains("runtime::dismissNotice"))
    }

    @Test fun mutationsUseControllerTokensAndLiveConfirmationWithDismissAndDisposalCancellation() {
        val panel = panel()
        assertFalse(panel.contains("rememberUpdatedState"))
        assertTrue(panel.contains("remember(runtime) { mutableStateOf<HistoryPanelPrompt?>(null) }"))
        assertTrue(panel.contains("runtime.requestRemove(token)")); assertTrue(panel.contains("runtime.requestClear()"))
        assertTrue(panel.contains("HistoryPanelPrompt(it, runtime.state, canAct)"))
        assertTrue(panel.contains("currentState = { runtime.state }"))
        assertTrue(panel.contains("historyPanelCanConfirm(it.state, runtime.state, it.canAct())"))
        assertTrue(panel.contains("historyPanelCanConfirm(pending.state, state, pending.canAct())"))
        assertTrue(panel.contains("confirm = runtime::confirm, cancel = runtime::cancel"))
        assertTrue(panel.contains("onDispose { prompt?.let { runtime.cancel(it.confirmation) } }"))
        assertTrue(panel.contains("Dialog(onDismissRequest = onDismiss"))
        assertTrue(panel.contains("onDismiss = cancelPrompt"))
        assertFalse(panel.contains("runtime.confirm(")); assertFalse(panel.contains("runtime.remove("))
        assertTrue(panel.contains("val rowToken = runtime.row(item)"))
        assertEquals(1, Regex("runtime\\.row\\(item\\)").findAll(panel).count())
        assertTrue(panel.indexOf("val rowToken = runtime.row(item)") < panel.indexOf("R.string.history_open), onClick"))
        assertTrue(panel.contains("rowToken?.let(onOpen)")); assertTrue(panel.contains("rowToken?.let { token ->"))
    }

    @Test fun englishAndChineseResourcesMatchAndRowsHaveLocalizedDatesAndLabels() {
        fun resources(locale: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/history_panel.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate {
                nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
            }
        }
        val en = resources("values"); val zh = resources("values-zh-rCN")
        assertEquals(24, en.size); assertEquals(en.keys, zh.keys)
        val argument = Regex("%[1-9]\\$[sd]")
        for (key in en.keys) {
            assertTrue(en.getValue(key).isNotBlank()); assertTrue(zh.getValue(key).isNotBlank())
            assertEquals(argument.findAll(en.getValue(key)).map { it.value }.toList(),
                argument.findAll(zh.getValue(key)).map { it.value }.toList())
        }
        assertEquals(listOf("%1\$d", "%2\$d"), argument.findAll(en.getValue("history_privacy")).map { it.value }.toList())
        for (key in listOf("history_open_description", "history_remove_description", "history_visited")) {
            assertEquals(listOf("%1\$s"), argument.findAll(en.getValue(key)).map { it.value }.toList())
        }
        val panel = panel()
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(panel))
        assertTrue(panel.contains("TextDirection.Ltr")); assertTrue(panel.contains("LocalConfiguration.current.locales"))
        assertTrue(panel.contains("historyVisitedDate(item.visitedAtMillis, locale)"))
        assertTrue(panel.contains("contentDescription = openDescription"))
        assertTrue(panel.contains("contentDescription = removeDescription"))
    }
}

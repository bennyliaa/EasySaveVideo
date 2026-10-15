package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.SessionStamp
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Executes finite production mappings and the real receipt owner; no Android/device acceptance. */
class DiscoveryNoticeLocalizationTest {
    private data class Expected(val suffix: String, val chinese: String, val english: String)
    private val expected = linkedMapOf(
        DiscoveryNotice.SOURCE_CONFIGURATION_CHECKING to Expected("source_configuration_checking", "正在核对来源配置；原查询不会自动发送。", "Checking source configuration. The previous query will not be sent automatically."),
        DiscoveryNotice.VERIFICATION_NOT_STARTED to Expected("verification_not_started", "当前未启动验证：请确认前台状态、来源及剩余预算；候选仍保留。", "Verification did not start. Check the foreground state, source and remaining budget; the candidate is retained."),
        DiscoveryNotice.VIEW_SETTINGS_CHANGED to Expected("view_settings_changed", "已保留当前页；新的重复策略和每页数量应用于后续页。", "The current page is retained. The new repeat policy and page size apply to later pages."),
        DiscoveryNotice.INVALID_QUERY to Expected("invalid_query", "关键词含无效字符，请修改后重试。", "The search keywords contain invalid characters. Edit them and try again."),
        DiscoveryNotice.SORT_CHANGED to Expected("sort_changed", "已保留当前页；新的本地排序应用于后续页。未知日期或时长排在已知值之后。", "The current page is retained. The new local sort applies to later pages. Unknown dates or durations follow known values."),
        DiscoveryNotice.STORAGE_RETRY_UNAVAILABLE to Expected("storage_retry_unavailable", "本地发现数据暂不可读，原数据已保留。地址栏仍可使用。", "Local Discovery data is temporarily unreadable. The original data is retained. The address bar is still available."),
        DiscoveryNotice.STORAGE_RECOVERY_UNAVAILABLE to Expected("storage_recovery_unavailable", "发现数据暂不可读，原始数据已保留。仍可使用浏览器地址栏。", "Discovery data is temporarily unreadable. The original data is retained. The browser address bar is still available."),
        DiscoveryNotice.CONTROL_STALE to Expected("control_stale", "查询或本地视图已变化，未执行操作。请重新打开确认。", "The query or local view has changed. No action was performed. Open the confirmation again."),
        DiscoveryNotice.CONTROL_FAILED to Expected("control_failed", "本地操作未成功，未确认任何清除或重置。请检查本地存储状态后重试。", "The local operation was unsuccessful. No clear or reset has been confirmed. Check local storage before trying again."),
        DiscoveryNotice.CONTROL_CURRENT_HISTORY_CLEARED to Expected("control_current_history_cleared", "已清除当前资料的 Seen 历史，来源进度和媒体文件保留。", "Seen history for the current profile was cleared. Source progress and media files are retained."),
        DiscoveryNotice.CONTROL_ALL_HISTORY_CLEARED to Expected("control_all_history_cleared", "已清除全部资料的 Seen 历史，来源进度和媒体文件保留。", "Seen history for all profiles was cleared. Source progress and media files are retained."),
        DiscoveryNotice.CONTROL_PROVIDER_PROGRESS_RESET to Expected("control_provider_progress_reset", "已重置所选来源在当前资料的进度，Seen 历史和其他来源进度保留。", "Progress for the selected source in the current profile was reset. Seen history and other source progress are retained."),
    )

    @Test fun allTwelveFiniteNoticesResolveExactEnglishChineseAndHistoricalDefaults() {
        assertEquals(12, DiscoveryNotice.entries.size)
        assertEquals(expected.keys, DiscoveryNotice.entries.toSet())
        for ((folder, locale) in listOf("values" to Locale.US, "values-zh-rCN" to Locale.SIMPLIFIED_CHINESE)) {
            val copy = discoveryPresentationFixture(folder, locale)
            val resources = discoveryPresentationResourceStrings(folder)
            for ((notice, value) in expected) {
                val text = if (folder == "values") value.english else value.chinese
                assertEquals(notice.name, text, DiscoveryPresentationPolicy.noticeText(notice, copy))
                assertEquals(text, resources.getValue("discovery_notice_${value.suffix}"))
                assertEquals(value.chinese, DiscoveryPresentationPolicy.noticeText(notice, DiscoveryPresentationCopy()))
            }
        }
    }

    @Test fun allNineControlOutcomesKeepTheExactHistoricalChineseWrapper() {
        val committed = mapOf(
            DiscoveryLocalAction.CLEAR_CURRENT_HISTORY to DiscoveryNotice.CONTROL_CURRENT_HISTORY_CLEARED,
            DiscoveryLocalAction.CLEAR_ALL_HISTORY to DiscoveryNotice.CONTROL_ALL_HISTORY_CLEARED,
            DiscoveryLocalAction.RESET_PROVIDER_PROGRESS to DiscoveryNotice.CONTROL_PROVIDER_PROGRESS_RESET,
        )
        var pairs = 0
        for (action in DiscoveryLocalAction.entries) for (outcome in DiscoveryControlOutcome.entries) {
            val notice = when (outcome) {
                DiscoveryControlOutcome.COMMITTED -> committed.getValue(action)
                DiscoveryControlOutcome.FAILED -> DiscoveryNotice.CONTROL_FAILED
                DiscoveryControlOutcome.STALE -> DiscoveryNotice.CONTROL_STALE
            }
            assertSame(notice, DiscoveryPresentationPolicy.controlOutcomeNotice(action, outcome))
            assertEquals(expected.getValue(notice).chinese, DiscoveryPresentationPolicy.controlOutcomeText(action, outcome))
            pairs++
        }
        assertEquals(9, pairs)
    }

    @Test fun failedAndStaleOutcomesCannotAcquireSuccessCopyInEitherLocale() {
        for (folder in listOf("values", "values-zh-rCN")) {
            val copy = discoveryPresentationFixture(folder)
            val successful = listOf(DiscoveryNotice.CONTROL_CURRENT_HISTORY_CLEARED,
                DiscoveryNotice.CONTROL_ALL_HISTORY_CLEARED, DiscoveryNotice.CONTROL_PROVIDER_PROGRESS_RESET)
                .map { DiscoveryPresentationPolicy.noticeText(it, copy) }.toSet()
            for (action in DiscoveryLocalAction.entries) {
                for (outcome in listOf(DiscoveryControlOutcome.FAILED, DiscoveryControlOutcome.STALE)) {
                    val text = DiscoveryPresentationPolicy.noticeText(DiscoveryPresentationPolicy.controlOutcomeNotice(action, outcome), copy)
                    assertFalse(successful.contains(text))
                    assertFalse(text.contains("was cleared")); assertFalse(text.contains("was reset"))
                    assertFalse(text.contains("已清除")); assertFalse(text.contains("已重置"))
                }
            }
        }
    }

    @Test fun failureSnapshotKeepsItsOwnTypedNoticeAndCurrentSourceFence() {
        val state = DiscoverySourceUiFence.failure(7, true)
        assertSame(DiscoveryNotice.STORAGE_RECOVERY_UNAVAILABLE, state.notice)
        assertNotEquals(DiscoveryNotice.STORAGE_RETRY_UNAVAILABLE, state.notice)
        assertEquals(7L, state.configurationIntent)
        assertTrue(state.ready); assertTrue(state.demo); assertTrue(state.storageUnavailable); assertTrue(state.needsExplicitSearch)
        assertNull(state.controlTarget); assertNull(state.page)
        assertTrue(state.results.isEmpty()); assertTrue(state.pendingCandidates.isEmpty())
        assertTrue(DiscoverySourceUiFence.accepts(state.configurationIntent, 7))
        assertFalse(DiscoverySourceUiFence.accepts(state.configurationIntent, 8))
        for (folder in listOf("values", "values-zh-rCN")) {
            val copy = discoveryPresentationFixture(folder)
            assertNotEquals(DiscoveryPresentationPolicy.noticeText(DiscoveryNotice.STORAGE_RETRY_UNAVAILABLE, copy),
                DiscoveryPresentationPolicy.noticeText(requireNotNull(state.notice), copy))
        }
    }

    @Test fun durationUsesLongTotalMinutesWholeSecondsAndTwoDigitSecondsInBothLocales() {
        val cases = mapOf(1L to "0:00", 999L to "0:00", 59_999L to "0:59", 60_000L to "1:00",
            3_599_999L to "59:59", 3_600_000L to "60:00", Long.MAX_VALUE to "153722867280912:55")
        for (folder in listOf("values", "values-zh-rCN")) {
            val copy = discoveryPresentationFixture(folder)
            for ((duration, expectedText) in cases) {
                assertEquals("$folder: $duration", expectedText, DiscoveryPresentationPolicy.durationText(duration, copy))
            }
        }
    }

    @Test fun durationDigitsUseTheSuppliedLocaleWithoutGroupingOrProcessDefaultMutation() {
        val original = Locale.getDefault()
        val arabic = discoveryPresentationFixture("values", Locale.forLanguageTag("ar-EG"))
        val root = discoveryPresentationFixture("values", Locale.ROOT)
        assertEquals("٦٠:٠٠", DiscoveryPresentationPolicy.durationText(3_600_000L, arabic))
        assertEquals("60:00", DiscoveryPresentationPolicy.durationText(3_600_000L, root))
        assertEquals(original, Locale.getDefault())
    }

    @Test fun oneCommittedMutationCanRenderEnglishChineseEnglishWithoutChangingStateOrReceipt() {
        val target = DiscoveryControlTarget("profile", SessionStamp("session", 3), 4, 5)
        val request = DiscoveryControlRequest(target, DiscoveryLocalAction.CLEAR_CURRENT_HISTORY)
        val owner = DiscoveryLocalControlOwner()
        var writes = 0
        var renderedNotice: DiscoveryNotice? = null
        assertTrue(owner.submit(request, dispatch = { it(); true }, work = { captured ->
            DiscoveryPresentationPolicy.executeControl(captured, target, emptySet(),
                clearHistory = { writes++; true }, resetProgress = { _, _ -> fail("Unexpected reset"); false })
        }, deliver = { it() }, afterWork = {
            renderedNotice = DiscoveryPresentationPolicy.controlOutcomeNotice(it.operation.request.action, it.outcome)
        }))
        val receipt = requireNotNull(owner.state.lastOutcome)
        val operation = receipt.operation
        val state = DiscoveryUiState(ready = true, controlTarget = target, notice = renderedNotice)
        val snapshot = state.copy()
        val rendered = listOf("values", "values-zh-rCN", "values").map { folder ->
            DiscoveryPresentationPolicy.noticeText(requireNotNull(state.notice), discoveryPresentationFixture(folder)).also {
                assertEquals(snapshot, state)
                assertSame(renderedNotice, state.notice)
                assertSame(receipt, owner.state.lastOutcome)
                assertSame(operation, owner.state.lastOutcome?.operation)
                assertSame(request, owner.state.lastOutcome?.operation?.request)
                assertEquals(DiscoveryControlOutcome.COMMITTED, receipt.outcome)
                assertFalse(owner.state.busy)
                assertEquals(1, writes)
            }
        }
        assertEquals(rendered.first(), rendered.last())
        assertNotEquals(rendered[0], rendered[1])
        assertEquals(1, writes)
    }
}

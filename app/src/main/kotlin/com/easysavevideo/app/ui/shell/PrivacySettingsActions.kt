package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.browser.BrowserCacheClearResult
import com.easysavevideo.app.browser.history.HistoryClearConfirmation
import com.easysavevideo.app.browser.history.HistoryState

/** Only in-memory UI authority; prompts and history references never enter SavedState. */
internal sealed interface PrivacySettingsPrompt {
    class History(val confirmation: HistoryClearConfirmation, val snapshot: HistoryState) : PrivacySettingsPrompt
    class Cache : PrivacySettingsPrompt
}
internal data class PrivacySettingsState(
    val prompt: PrivacySettingsPrompt? = null,
    val cacheResult: BrowserCacheClearResult? = null,
)

/** View-lifetime command adapter. The existing process history owner owns accepted asynchronous IO. */
internal class PrivacySettingsActions(
    private val canAct: () -> Boolean,
    private val historyState: () -> HistoryState,
    private val requestHistory: () -> HistoryClearConfirmation?,
    private val cancelHistory: (HistoryClearConfirmation) -> Unit,
    private val confirmHistory: (HistoryClearConfirmation) -> Unit,
    private val loadHistory: () -> Unit,
    private val dismissHistory: () -> Unit,
    private val clearCache: () -> BrowserCacheClearResult,
    private val changed: (PrivacySettingsState) -> Unit,
) {
    var state = PrivacySettingsState(); private set
    private var closed = false
    private var executingCache = false
    private fun active() = !closed && canAct()
    fun beginHistory() {
        if (!active() || executingCache || state.prompt != null || historyState().busy) return
        val confirmation = requestHistory() ?: return
        if (!active()) { cancelHistory(confirmation); return }
        publish(state.copy(prompt = PrivacySettingsPrompt.History(confirmation, historyState())))
    }
    fun beginCache() {
        if (!active() || executingCache || state.prompt != null || historyState().busy) return
        publish(state.copy(prompt = PrivacySettingsPrompt.Cache(), cacheResult = null))
    }
    fun canConfirm(expected: PrivacySettingsPrompt): Boolean = active() && state.prompt === expected && when (expected) {
        is PrivacySettingsPrompt.History -> historyState() === expected.snapshot && !historyState().busy
        is PrivacySettingsPrompt.Cache -> !historyState().busy
    }
    fun confirm(expected: PrivacySettingsPrompt) {
        if (!canConfirm(expected)) { cancel(expected); return }
        // Consume before invoking either port: repeated or reentrant confirm cannot submit twice.
        publish(state.copy(prompt = null))
        when (expected) {
            is PrivacySettingsPrompt.History -> {
                if (active() && historyState() === expected.snapshot && !historyState().busy) confirmHistory(expected.confirmation)
                else cancelHistory(expected.confirmation)
            }
            is PrivacySettingsPrompt.Cache -> if (active()) {
                executingCache = true
                try {
                    val result = clearCache()
                    if (active()) publish(state.copy(cacheResult = result))
                } finally { executingCache = false }
            }
        }
    }
    fun cancel(expected: PrivacySettingsPrompt) {
        if (state.prompt !== expected) return
        if (expected is PrivacySettingsPrompt.History) cancelHistory(expected.confirmation)
        publish(state.copy(prompt = null))
    }
    fun reconcile() {
        state.prompt?.takeUnless(::canConfirm)?.let(::cancel)
    }
    fun retryHistory() {
        if (active() && !executingCache && state.prompt == null && !historyState().busy) loadHistory()
    }
    fun dismissHistoryNotice() {
        if (active() && !executingCache && state.prompt == null) dismissHistory()
    }
    fun dismissCacheResult() { if (active()) publish(state.copy(cacheResult = null)) }
    fun close() {
        if (closed) return
        closed = true
        state.prompt?.let(::cancel)
    }
    private fun publish(value: PrivacySettingsState) { state = value; changed(value) }
}

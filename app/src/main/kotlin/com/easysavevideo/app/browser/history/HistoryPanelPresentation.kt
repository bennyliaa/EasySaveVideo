package com.easysavevideo.app.browser.history

import com.easysavevideo.app.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Projects acknowledged controller state, never an optimistic storage result. */
internal data class HistoryPanelStatus(
    val message: Int?, val retry: Boolean, val rowsEnabled: Boolean, val clearEnabled: Boolean,
)

internal fun historyPanelStatus(state: HistoryState): HistoryPanelStatus = HistoryPanelStatus(
    message = when {
        state.busy -> if (state.notice == HistoryNotice.SLOW) null else R.string.history_loading
        !state.ready -> if (state.notice == HistoryNotice.UNAVAILABLE) null else R.string.history_unavailable
        state.items.isEmpty() -> R.string.history_empty
        else -> null
    },
    retry = !state.ready && !state.busy,
    rowsEnabled = state.ready && !state.busy,
    // An unreadable store may be explicitly reset, but an active writer may not be overtaken.
    clearEnabled = !state.busy,
)

internal fun historyNoticeResource(notice: HistoryNotice): Int = when (notice) {
    HistoryNotice.UNAVAILABLE -> R.string.history_unavailable
    HistoryNotice.SLOW -> R.string.history_slow
    HistoryNotice.CLEARED -> R.string.history_cleared
    HistoryNotice.REMOVED -> R.string.history_removed
    HistoryNotice.STALE_LIST -> R.string.history_stale_list
}

/** In-memory authority only. Never put the prompt or its private row payload in saved state. */
internal class HistoryPanelPrompt(
    val confirmation: HistoryClearConfirmation, val state: HistoryState, val canAct: () -> Boolean,
) {
    override fun toString() = "HistoryPanelPrompt(redacted)"
}

internal fun historyPanelCanConfirm(captured: HistoryState, current: HistoryState, canAct: Boolean): Boolean =
    canAct && captured === current && !current.busy

/** Read both gates at the final click, after any navigation/background/storage change. */
internal fun confirmHistoryPanelPrompt(
    prompt: HistoryPanelPrompt, currentState: () -> HistoryState,
    confirm: (HistoryClearConfirmation) -> Unit, cancel: (HistoryClearConfirmation) -> Unit,
) {
    // This is the creation-time owner's live gate, never a newer render's replacement authority.
    val active = prompt.canAct()
    if (historyPanelCanConfirm(prompt.state, currentState(), active)) confirm(prompt.confirmation)
    else cancel(prompt.confirmation)
}

internal fun historyVisitedDate(millis: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        .apply { timeZone = zone }.format(Date(millis))

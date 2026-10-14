package com.easysavevideo.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.R
import com.easysavevideo.app.browser.BrowserCacheClearResult
import com.easysavevideo.app.browser.history.AndroidHistoryRuntime
import com.easysavevideo.app.browser.history.HistoryNoticeRow
import com.easysavevideo.app.browser.history.historyPanelStatus
import com.easysavevideo.app.theme.*

/** Central privacy controls show counts/status only, never history titles or addresses. */
@Composable internal fun PrivacySettingsDetail(history: AndroidHistoryRuntime, generation: Long,
    canAct: () -> Boolean, clearCache: () -> BrowserCacheClearResult) {
    val v = LocalEsvVisuals.current
    var commandState by remember(history, generation) { mutableStateOf(PrivacySettingsState()) }
    val actions = remember(history, generation) {
        PrivacySettingsActions(canAct, { history.state }, history::requestClear, history::cancel,
            history::confirm, history::load, history::dismissNotice, clearCache) { commandState = it }
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    val historyState = history.state
    val active = canAct()
    LaunchedEffect(actions, historyState, active) { actions.reconcile() }
    val prompt = commandState.prompt
    val status = historyPanelStatus(historyState)
    EsvText(stringResource(R.string.settings_privacy_scope), style = v.captionTextStyle)
    EsvText(stringResource(R.string.settings_privacy_history), style = v.sectionTextStyle)
    EsvText(stringResource(R.string.settings_privacy_history_scope), style = v.captionTextStyle)
    historyState.notice?.let { HistoryNoticeRow(it, onDismiss = actions::dismissHistoryNotice) }
    status.message?.let { EsvText(stringResource(it), style = v.captionTextStyle) }
    if (historyState.ready) EsvText(stringResource(R.string.settings_privacy_history_count, historyState.items.size), style = v.captionTextStyle)
    if (status.retry) EsvButton(stringResource(R.string.history_retry), onClick = actions::retryHistory,
        enabled = active && prompt == null)
    EsvButton(stringResource(R.string.history_clear), onClick = actions::beginHistory,
        enabled = active && status.clearEnabled && prompt == null)
    EsvText(stringResource(R.string.settings_privacy_cache), style = v.sectionTextStyle)
    EsvText(stringResource(R.string.settings_privacy_cache_scope), style = v.captionTextStyle)
    commandState.cacheResult?.let { result ->
        EsvText(stringResource(if (result == BrowserCacheClearResult.REQUESTED)
            R.string.settings_privacy_cache_requested else R.string.settings_privacy_cache_failed),
            style = v.captionTextStyle, color = if (result == BrowserCacheClearResult.FAILED) v.colors.error else v.colors.text)
        EsvButton(stringResource(R.string.history_dismiss), onClick = actions::dismissCacheResult, enabled = active)
    }
    EsvButton(stringResource(R.string.settings_privacy_cache_clear), onClick = actions::beginCache,
        enabled = active && !historyState.busy && prompt == null)
    prompt?.let { pending ->
        Dialog(onDismissRequest = { actions.cancel(pending) }) {
            BoxWithConstraints {
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(600.dp))
                    .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    val historyPrompt = pending is PrivacySettingsPrompt.History
                    EsvText(stringResource(if (historyPrompt) R.string.history_clear_title else R.string.settings_privacy_cache_confirm_title), style = v.titleTextStyle)
                    EsvText(stringResource(if (historyPrompt) R.string.history_clear_message else R.string.settings_privacy_cache_confirm_message))
                    EsvButton(stringResource(R.string.history_cancel), onClick = { actions.cancel(pending) }, modifier = Modifier.fillMaxWidth())
                    EsvButton(stringResource(if (historyPrompt) R.string.history_clear else R.string.settings_privacy_cache_clear),
                        onClick = { actions.confirm(pending) }, enabled = actions.canConfirm(pending), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

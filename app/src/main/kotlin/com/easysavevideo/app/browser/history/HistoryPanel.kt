package com.easysavevideo.app.browser.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*
import java.util.Locale

/** One text-only component for dialog/sidebar hosts; Browser owns the shared list position. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryPanel(runtime: AndroidHistoryRuntime, listState: LazyListState,
    onOpen: (HistoryRowToken) -> Unit, onClose: () -> Unit, canAct: () -> Boolean) {
    val v = LocalEsvVisuals.current
    val state = runtime.state
    val status = historyPanelStatus(state)
    // Keep this render's owner gate. Replacing it would upgrade callbacks from a retired owner.
    val active = canAct()
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.ROOT else locales[0]
    var prompt by remember(runtime) { mutableStateOf<HistoryPanelPrompt?>(null) }
    val cancelPrompt: () -> Unit = {
        prompt?.let { runtime.cancel(it.confirmation) }
        prompt = null
    }
    DisposableEffect(runtime) {
        onDispose { prompt?.let { runtime.cancel(it.confirmation) } }
    }
    // A newly read/written list or loss of panel ownership must retire an open confirmation.
    LaunchedEffect(state, active, canAct) {
        prompt?.takeUnless { historyPanelCanConfirm(it.state, runtime.state, it.canAct()) }?.let {
            runtime.cancel(it.confirmation)
            prompt = null
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(v.colors.surface).padding(v.spacingMedium)) {
        val headerMaxHeight = (maxHeight * 0.45f).coerceAtMost(280.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            Column(Modifier.fillMaxWidth().heightIn(max = headerMaxHeight)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    EsvText(stringResource(R.string.history_heading, state.items.size, HistoryPolicy.MAX_ITEMS),
                        style = v.titleTextStyle, modifier = Modifier.weight(1f))
                    EsvButton(stringResource(R.string.history_done), onClick = { cancelPrompt(); onClose() })
                }
                state.notice?.let { notice -> HistoryNoticeRow(notice, runtime::dismissNotice) }
                status.message?.let { EsvText(stringResource(it), style = v.captionTextStyle) }
                if (status.retry) EsvButton(stringResource(R.string.history_retry), onClick = {
                    if (canAct() && !runtime.state.busy && prompt == null) runtime.load()
                }, enabled = active && prompt == null)
                EsvButton(stringResource(R.string.history_clear), onClick = {
                    if (historyPanelCanConfirm(state, runtime.state, canAct()) && prompt == null) {
                        runtime.requestClear()?.let { prompt = HistoryPanelPrompt(it, runtime.state, canAct) }
                    }
                }, enabled = status.clearEnabled && active && prompt == null)
                // Longer policy text follows feedback and recovery controls even in short windows.
                EsvText(stringResource(R.string.history_privacy, HistoryPolicy.MAX_ITEMS, 30), style = v.captionTextStyle)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                items(state.items, key = { it.id }) { item ->
                    // Capture this revision while rendering; an old click must never mint a new token.
                    val rowToken = runtime.row(item)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText(item.title, maxLines = 2)
                        EsvText(item.address, style = v.captionTextStyle.copy(textDirection = TextDirection.Ltr),
                            color = v.colors.muted, maxLines = 3)
                        EsvText(stringResource(R.string.history_visited, historyVisitedDate(item.visitedAtMillis, locale)),
                            style = v.captionTextStyle, color = v.colors.muted)
                        val openDescription = stringResource(R.string.history_open_description, item.title)
                        val removeDescription = stringResource(R.string.history_remove_description, item.title)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall),
                            verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            EsvButton(stringResource(R.string.history_open), onClick = {
                                if (canAct() && prompt == null) rowToken?.let(onOpen)
                            }, enabled = status.rowsEnabled && active && rowToken != null && prompt == null,
                                modifier = Modifier.semantics { contentDescription = openDescription })
                            EsvButton(stringResource(R.string.history_remove), onClick = {
                                if (canAct() && prompt == null) rowToken?.let { token ->
                                    runtime.requestRemove(token)?.let { prompt = HistoryPanelPrompt(it, runtime.state, canAct) }
                                }
                            }, enabled = status.rowsEnabled && active && rowToken != null && prompt == null,
                                modifier = Modifier.semantics { contentDescription = removeDescription })
                        }
                    }
                }
            }
        }
    }
    prompt?.let { pending ->
        HistoryConfirmationDialog(pending.confirmation.clearsAll, onDismiss = cancelPrompt,
            enabled = historyPanelCanConfirm(pending.state, state, pending.canAct()), onConfirm = {
                if (prompt === pending) {
                    prompt = null
                    confirmHistoryPanelPrompt(pending, currentState = { runtime.state },
                        confirm = runtime::confirm, cancel = runtime::cancel)
                }
            })
    }
}

@Composable
internal fun HistoryNoticeRow(notice: HistoryNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val v = LocalEsvVisuals.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(historyNoticeResource(notice)), modifier = Modifier.weight(1f), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.history_dismiss), onClick = onDismiss)
    }
}

@Composable
private fun HistoryConfirmationDialog(clearsAll: Boolean, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val v = LocalEsvVisuals.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        BoxWithConstraints {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(600.dp))
                .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(if (clearsAll) R.string.history_clear_title else R.string.history_remove_title),
                    style = v.titleTextStyle)
                EsvText(stringResource(if (clearsAll) R.string.history_clear_message else R.string.history_remove_message))
                EsvButton(stringResource(R.string.history_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                EsvButton(stringResource(if (clearsAll) R.string.history_clear else R.string.history_remove),
                    onClick = onConfirm, enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

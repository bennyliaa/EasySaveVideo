package com.easysavevideo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.R
import com.easysavevideo.searxng.SearxngResultFormat
import com.easysavevideo.app.discovery.SearxngConfigurationAction
import com.easysavevideo.app.discovery.SearxngConfigurationClaim
import com.easysavevideo.app.discovery.SearxngConfigurationController
import com.easysavevideo.app.discovery.SearxngConfigurationEdit
import com.easysavevideo.app.discovery.SearxngConfigurationPolicy
import com.easysavevideo.app.discovery.SearxngConfigurationStatus
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals

/** Insert inside the existing options column. No provider, SafeSearch control, or network access. */
@Composable
fun SearxngSourceSettings(controller: SearxngConfigurationController, modifier: Modifier = Modifier,
    canAct: () -> Boolean = { true }, canStart: () -> Boolean = { true },
    onDialogChanged: (Boolean) -> Unit = {}) {
    val v = LocalEsvVisuals.current
    val state = controller.state
    val actions = remember(controller, canAct) { CapturedPrivateActions(false, { true }, canAct) }
    DisposableEffect(actions) { onDispose { actions.close(); onDialogChanged(false) } }
    var edit by remember(actions) { mutableStateOf<SearxngConfigurationClaim?>(null) }
    var reset by remember(actions) { mutableStateOf<SearxngConfigurationClaim?>(null) }
    var deletion by remember(actions) { mutableStateOf<SearxngConfigurationClaim?>(null) }
    var deletionHost by remember(actions) { mutableStateOf("") }
    fun idle() = actions.isCurrent() && canStart() && edit == null && reset == null && deletion == null
    fun editCurrent(claim: SearxngConfigurationClaim) = actions.isCurrent() && edit === claim && controller.isCurrent(claim) &&
        claim.kind in setOf(SearxngConfigurationAction.ADD, SearxngConfigurationAction.EDIT)
    fun resetCurrent(claim: SearxngConfigurationClaim) = actions.isCurrent() && reset === claim && controller.isCurrent(claim) &&
        claim.kind == SearxngConfigurationAction.RESET
    fun deletionCurrent(claim: SearxngConfigurationClaim) = actions.isCurrent() && deletion === claim && controller.isCurrent(claim) &&
        claim.kind == SearxngConfigurationAction.DELETE
    fun dismissEdit(claim: SearxngConfigurationClaim) {
        if (edit === claim) { edit = null; onDialogChanged(false) }
    }
    fun dismissReset(claim: SearxngConfigurationClaim) {
        if (reset === claim) { reset = null; onDialogChanged(false) }
    }
    fun dismissDeletion(claim: SearxngConfigurationClaim) {
        if (deletion === claim) { deletion = null; deletionHost = ""; onDialogChanged(false) }
    }
    LaunchedEffect(actions, state.config?.generation, state.busy, state.status) {
        edit?.takeIf { !editCurrent(it) }?.let(::dismissEdit)
        reset?.takeIf { !resetCurrent(it) }?.let(::dismissReset)
        deletion?.takeIf { !deletionCurrent(it) }?.let(::dismissDeletion)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(R.string.searxng_settings_title), style = v.titleTextStyle)
        EsvText(stringResource(R.string.searxng_settings_overview, SearxngConfigurationPolicy.MAX_INSTANCES), style = v.captionTextStyle)
        EsvText(stringResource(R.string.searxng_settings_verification_notice), style = v.captionTextStyle)
        EsvText(stringResource(R.string.searxng_settings_privacy), style = v.captionTextStyle)
        EsvText(stringResource(R.string.searxng_settings_safe_search), style = v.captionTextStyle, color = v.colors.error)
        EsvText(stringResource(R.string.searxng_settings_no_automatic_search), style = v.captionTextStyle)
        EsvText(stringResource(R.string.searxng_settings_budget_notice), style = v.captionTextStyle)
        when (state.status) {
            SearxngConfigurationStatus.NOT_LOADED -> EsvText(if (state.busy) stringResource(R.string.searxng_settings_loading) else stringResource(R.string.searxng_settings_not_loaded), style = v.captionTextStyle)
            SearxngConfigurationStatus.MALFORMED -> EsvText(stringResource(R.string.searxng_settings_malformed), style = v.captionTextStyle, color = v.colors.error)
            SearxngConfigurationStatus.UNAVAILABLE -> EsvText(stringResource(R.string.searxng_settings_unavailable), style = v.captionTextStyle, color = v.colors.error)
            SearxngConfigurationStatus.CLOSED -> EsvText(stringResource(R.string.searxng_settings_closed), style = v.captionTextStyle)
            else -> if (state.busy) EsvText(stringResource(R.string.searxng_settings_saving), style = v.captionTextStyle)
        }
        state.notice?.let { EsvText(it, style = v.captionTextStyle) }
        state.config?.let { config ->
            EsvText(stringResource(R.string.searxng_settings_count, config.slots.size, SearxngConfigurationPolicy.MAX_INSTANCES), style = v.bodyTextStyle)
            if (config.slots.isEmpty()) EsvText(stringResource(R.string.searxng_settings_empty), style = v.captionTextStyle)
            config.slots.forEachIndexed { index, slot ->
                Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                    .padding(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText(stringResource(R.string.searxng_settings_slot, index + 1), style = v.bodyTextStyle)
                    val enabledLabel = stringResource(if (slot.enabled) R.string.searxng_settings_enabled else R.string.searxng_settings_disabled)
                    EsvText("${slot.hostname.orEmpty()} · $enabledLabel", style = v.captionTextStyle)
                    EsvText(stringResource(formatLabel(slot.format)), style = v.captionTextStyle)
                    EsvButton(stringResource(R.string.searxng_settings_edit), enabled = state.editable && idle(),
                        onClick = { if (idle()) controller.beginEdit(slot.id)?.let { claim ->
                            edit = claim; onDialogChanged(true)
                        } })
                    EsvButton(stringResource(R.string.searxng_settings_remove), enabled = state.editable && idle(),
                        onClick = { if (idle()) controller.beginDelete(slot.id)?.let { claim ->
                            deletionHost = slot.hostname.orEmpty(); deletion = claim; onDialogChanged(true)
                        } })
                }
            }
            if (config.slots.size >= SearxngConfigurationPolicy.MAX_INSTANCES)
                EsvText(stringResource(R.string.searxng_settings_capacity, SearxngConfigurationPolicy.MAX_INSTANCES), style = v.captionTextStyle)
        }
        val hasCapacity = state.config?.slots?.size?.let { it < SearxngConfigurationPolicy.MAX_INSTANCES } == true
        EsvButton(stringResource(R.string.searxng_settings_add), enabled = state.editable && idle() && hasCapacity,
            onClick = { if (idle()) controller.beginAdd()?.let { claim ->
                edit = claim; onDialogChanged(true)
            } })
        EsvButton(stringResource(R.string.searxng_settings_reload), onClick = { if (idle()) controller.reload() }, enabled = idle() && !state.busy && state.status != SearxngConfigurationStatus.CLOSED)
        EsvButton(stringResource(R.string.searxng_settings_clear), onClick = { if (idle()) controller.beginReset()?.let { claim ->
            reset = claim; onDialogChanged(true)
        } }, enabled = state.editable && idle() && state.config?.slots?.isNotEmpty() == true)
    }
    edit?.let { claim ->
        SearxngEndpointEditor(controller, claim, canAct = { editCurrent(claim) },
            onDismiss = { dismissEdit(claim) })
    }
    deletion?.let { claim ->
        Dialog(onDismissRequest = { dismissDeletion(claim) }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
                .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.searxng_settings_remove_title), style = v.titleTextStyle)
                EsvText(stringResource(R.string.searxng_settings_remove_description, deletionHost), style = v.bodyTextStyle)
                EsvButton(stringResource(R.string.searxng_settings_cancel), onClick = { dismissDeletion(claim) })
                EsvButton(stringResource(R.string.searxng_settings_remove_confirm), enabled = deletionCurrent(claim), onClick = {
                    if (deletionCurrent(claim) && controller.remove(claim)) dismissDeletion(claim)
                })
            }
        }
    }
    reset?.let { claim ->
        Dialog(onDismissRequest = { dismissReset(claim) }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
                .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.searxng_settings_clear_title), style = v.titleTextStyle)
                EsvText(stringResource(R.string.searxng_settings_clear_description), style = v.bodyTextStyle)
                EsvButton(stringResource(R.string.searxng_settings_cancel), onClick = { dismissReset(claim) })
                EsvButton(stringResource(R.string.searxng_settings_clear_confirm), enabled = resetCurrent(claim), onClick = {
                    if (resetCurrent(claim) && controller.reset(claim)) dismissReset(claim)
                })
            }
        }
    }
}

@Composable
private fun SearxngEndpointEditor(controller: SearxngConfigurationController, claim: SearxngConfigurationClaim,
    canAct: () -> Boolean, onDismiss: () -> Unit) {
    val v = LocalEsvVisuals.current
    val config = controller.state.config ?: return
    val adding = claim.kind == SearxngConfigurationAction.ADD
    val original = config.slots.firstOrNull { it.id == claim.slotId }
    if (!adding && (claim.kind != SearxngConfigurationAction.EDIT || original == null)) return
    var url by remember(claim) { mutableStateOf(original?.searchUrl.orEmpty()) }
    var enabled by remember(claim) { mutableStateOf(original?.enabled ?: false) }
    var format by remember(claim) { mutableStateOf(original?.format ?: SearxngResultFormat.JSON_VIDEOS) }
    // A new row has no selected radio option until the user explicitly chooses one.
    var formatChosen by remember(claim) { mutableStateOf(!adding) }
    var acknowledged by remember(claim) { mutableStateOf(false) }
    val validation = if (adding) SearxngConfigurationPolicy.add(config, url, enabled, acknowledged, format)
        else original?.let { SearxngConfigurationPolicy.replace(config, it.id, url, enabled, acknowledged, format) } ?: return
    fun isCurrent() = canAct() && controller.isCurrent(claim)
    val current = isCurrent()
    val endpointDescription = stringResource(R.string.searxng_settings_endpoint_description)
    val needsAcknowledgement = adding || (original != null &&
        SearxngConfigurationPolicy.acknowledgementRequired(original, url, enabled, format))
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText(if (adding) stringResource(R.string.searxng_settings_add_title)
                else stringResource(R.string.searxng_settings_editor_title, original?.hostname.orEmpty()), style = v.titleTextStyle)
            EsvText(stringResource(R.string.searxng_settings_endpoint_hint), style = v.captionTextStyle)
            BasicTextField(url, onValueChange = { text ->
                if (isCurrent() && text.length <= SearxngConfigurationPolicy.MAX_URL_BYTES &&
                    text.toByteArray(Charsets.UTF_8).size <= SearxngConfigurationPolicy.MAX_URL_BYTES) {
                    url = text; acknowledged = false
                    if (text.isEmpty()) enabled = false
                }
            }, singleLine = true, enabled = current,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
                modifier = Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                    .padding(v.spacingMedium).semantics { contentDescription = endpointDescription },
                decorationBox = { input -> Column { if (url.isEmpty()) EsvText(stringResource(R.string.searxng_settings_endpoint_placeholder), color = v.colors.muted); input() } })
            EsvText(stringResource(R.string.searxng_settings_format_title), style = v.bodyTextStyle)
            Column(Modifier.selectableGroup()) {
                SearxngResultFormat.entries.forEach { choice ->
                    SourceFormatOption(stringResource(formatLabel(choice)), formatChosen && format == choice, current) {
                        if (isCurrent() && (!formatChosen || format != choice)) {
                            format = choice; formatChosen = true; acknowledged = false
                        }
                    }
                }
            }
            if (!formatChosen) EsvText(stringResource(R.string.searxng_settings_choose_format), style = v.captionTextStyle)
            EsvText(stringResource(R.string.searxng_settings_format_notice), style = v.captionTextStyle)
            SourceCheckbox(stringResource(R.string.searxng_settings_enable_source), enabled, current && url.isNotEmpty()) { if (isCurrent()) { enabled = it; acknowledged = false } }
            EsvText(stringResource(R.string.searxng_settings_editor_privacy), style = v.captionTextStyle)
            SourceCheckbox(stringResource(R.string.searxng_settings_acknowledge), acknowledged,
                current && url.isNotEmpty() && formatChosen) { if (isCurrent()) acknowledged = it }
            EsvText(stringResource(R.string.searxng_settings_verification_notice), style = v.captionTextStyle)
            EsvText(stringResource(R.string.searxng_settings_editor_safe_search), style = v.captionTextStyle, color = v.colors.error)
            if (validation is SearxngConfigurationEdit.Rejected &&
                (!needsAcknowledgement || validation.issue != com.easysavevideo.app.discovery.SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED))
                EsvText(if (validation.issue == com.easysavevideo.app.discovery.SearxngConfigurationIssue.DUPLICATE_ORIGIN)
                    stringResource(R.string.searxng_settings_duplicate_origin) else SearxngConfigurationController.issueText(validation.issue), style = v.captionTextStyle, color = v.colors.error)
            if (!current) EsvText(stringResource(R.string.searxng_settings_stale), style = v.captionTextStyle, color = v.colors.error)
            EsvButton(stringResource(R.string.searxng_settings_cancel), onClick = onDismiss)
            EsvButton(stringResource(R.string.searxng_settings_save), enabled = current && formatChosen && validation is SearxngConfigurationEdit.Accepted,
                onClick = {
                    if (isCurrent() && formatChosen && validation is SearxngConfigurationEdit.Accepted) {
                        if (adding) {
                            if (controller.add(claim, url, enabled, acknowledged, format)) onDismiss()
                        } else if (isCurrent() && controller.replace(claim, url, enabled, acknowledged, format)) onDismiss()
                    }
                })
        }
    }
}

/** Foundation checkbox semantics using the active SkinKit text, border, spacing, and colors. */
@Composable
private fun SourceCheckbox(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    val v = LocalEsvVisuals.current
    Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Checkbox, onValueChange = onChecked).padding(v.spacingSmall),
        horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
        EsvText(if (checked) "☑" else "☐", color = if (enabled) v.colors.accent else v.colors.muted)
        EsvText(label, modifier = Modifier.weight(1f), color = if (enabled) v.colors.text else v.colors.muted)
    }
}

private fun formatLabel(format: SearxngResultFormat): Int = when (format) {
    SearxngResultFormat.JSON_VIDEOS -> R.string.searxng_settings_format_json
    SearxngResultFormat.HTML_WEB -> R.string.searxng_settings_format_html
}

/** Exclusive format choice, using active SkinKit styling and native radio-button semantics. */
@Composable
private fun SourceFormatOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val v = LocalEsvVisuals.current
    Row(Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onSelect).padding(v.spacingSmall),
        horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
        EsvText(if (selected) "◉" else "○", color = if (enabled) v.colors.accent else v.colors.muted)
        EsvText(label, modifier = Modifier.weight(1f), color = if (enabled) v.colors.text else v.colors.muted)
    }
}

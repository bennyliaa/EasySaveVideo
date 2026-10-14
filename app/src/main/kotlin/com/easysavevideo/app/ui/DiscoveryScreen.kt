package com.easysavevideo.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.ui.shell.SettingsEdit
import com.easysavevideo.app.discovery.DiscoveryCardOpenRequest
import com.easysavevideo.app.discovery.discoveryCardOpenRequest
import com.easysavevideo.app.discovery.AndroidDiscoveryController
import com.easysavevideo.app.discovery.discoveryBodyRecovery
import com.easysavevideo.app.discovery.discoveryBodyRecoveryLabel
import com.easysavevideo.app.discovery.dispatchDiscoveryBodyRecovery
import com.easysavevideo.app.discovery.DiscoveryBodyState
import com.easysavevideo.app.discovery.discoveryBodyState
import com.easysavevideo.app.discovery.discoveryBodyText
import com.easysavevideo.app.discovery.BuildDiscovery
import com.easysavevideo.app.discovery.DiscoveryPagerPolicy
import com.easysavevideo.app.discovery.DiscoveryPresentationPolicy
import com.easysavevideo.app.discovery.DiscoveryControlRequest
import com.easysavevideo.app.discovery.DiscoveryLocalAction
import com.easysavevideo.app.discovery.DiscoveryFilterDraft
import com.easysavevideo.app.discovery.DiscoveryFilterClaim
import com.easysavevideo.app.discovery.DiscoveryFilterTextField
import androidx.compose.ui.platform.LocalContext
import com.easysavevideo.app.discovery.FilterCopyText
import com.easysavevideo.app.discovery.DiscoveryFilterPresentationPolicy
import com.easysavevideo.app.discovery.RightsPresentationPolicy
import com.easysavevideo.app.theme.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import com.easysavevideo.app.R
import com.easysavevideo.core.model.*
import com.easysavevideo.discovery.*
import kotlinx.coroutines.delay
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveryScreen(controller: AndroidDiscoveryController, settings: DiscoverySettings, active: Boolean,
    onSettings: (SettingsEdit.DiscoveryEdit) -> Unit, onBrowser: () -> Unit, onOpen: (DiscoveryCardOpenRequest) -> Boolean,
    onLink: (String) -> Unit, onLibrary: () -> Unit, onManageProviders: () -> Unit,
    canClearQuery: () -> Boolean = { false }, settingsEditable: Boolean = true) {
    val v = LocalEsvVisuals.current
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val paneLayout = responsivePaneLayout(maxWidth.value - 2 * v.spacingMedium.value,
        maxHeight.value - 2 * v.spacingMedium.value, LocalDensity.current.fontScale, v.spacingMedium.value)
    val optionsScroll = rememberScrollState()
    val headerScroll = rememberScrollState()
    val pagerScroll = rememberScrollState()
    val queryDescription = stringResource(R.string.flow_query_description)
    val state = controller.state
    var options by rememberSaveable { mutableStateOf(false) }
    val optionsIsModal = options && !paneLayout.split
    BackHandler(enabled = options) { options = false }
    var showPending by remember { mutableStateOf(false) }
    var cardOpenRejected by remember { mutableStateOf(false) }
    var localControlConfirm by remember { mutableStateOf<DiscoveryControlRequest?>(null) }
    var safeDisableConfirm by remember { mutableStateOf<DiscoveryFilterClaim?>(null) }
    var rightsDetails by remember { mutableStateOf<VideoSearchResult?>(null) }
    val pendingBrowserQuery = controller.pendingBrowserQuery
    val storedDraft = controller.filterDraft
    val appliedReadable = state.ready && !state.storageUnavailable
    val draft = DiscoveryFilterPresentationPolicy.reconcile(storedDraft, state.profile, state.demo, appliedReadable)
    val editClaim = state.controlTarget?.let { DiscoveryFilterClaim(it, state.demo, draft) }
    val queryClearLifecycle = controller.clearQueryLifecycle
    val cardNavigationLifecycle = controller.cardNavigationLifecycle
    val currentQueryClearInteractive by rememberUpdatedState {
        active && canClearQuery() && !(options && !paneLayout.split) && localControlConfirm == null &&
            safeDisableConfirm == null && rightsDetails == null
    }
    val copyResources = LocalContext.current.resources
    val filterCopy = FilterCopyText { key, args -> copyResources.getString(key.resource, *args) }
    val validation = DiscoveryFilterPresentationPolicy.validate(draft, filterCopy)
    val choices = DiscoveryFilterPresentationPolicy.admittedSources(state.admittedProviderDescriptors, state.demo, filterCopy)
    val editable = state.ready && !state.storageUnavailable && !controller.filterControlsBusy &&
        storedDraft == draft && editClaim != null
    val canSearch = editable && DiscoveryFilterPresentationPolicy.canSubmit(draft)
    SideEffect {
        if (pendingBrowserQuery != null) controller.applyBrowserQuery(pendingBrowserQuery)
        else if (appliedReadable && storedDraft != draft) controller.updateFilterDraft(storedDraft, draft)
    }
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    LaunchedEffect(options, state.controlTarget) {
        if (options && state.controlTarget != null) controller.refreshHistoryStatistics(state.controlTarget)
    }
    LaunchedEffect(state.controlTarget, state.storageUnavailable) {
        if (localControlConfirm?.target != state.controlTarget || state.storageUnavailable) localControlConfirm = null
    }
    fun currentClaimValid(claim: DiscoveryFilterClaim): Boolean {
        val current = controller.state
        return DiscoveryFilterPresentationPolicy.validClaim(claim, controller.filterDraft, current.controlTarget,
            current.profile, current.demo, current.ready, current.storageUnavailable, controller.filterControlsBusy)
    }
    fun edit(value: DiscoveryFilterDraft) {
        val claim = editClaim ?: return
        if (currentClaimValid(claim)) controller.updateFilterDraft(storedDraft, value)
    }
    fun editText(field: DiscoveryFilterTextField, value: String) {
        val claim = editClaim ?: return
        controller.editFilterText(claim, field, value)
    }
    fun search() {
        if (controller.submitFilterDraft(draft, state.controlTarget)) focus.clearFocus()
    }
    val bodyState = discoveryBodyState(
        hasResults = state.results.isNotEmpty(), hasPendingCandidates = state.pendingCandidates.isNotEmpty(),
        showPending = showPending, ready = state.ready, storageUnavailable = state.storageUnavailable,
        searchPending = controller.filterSearchPending, nextPagePending = state.nextPagePending, status = state.status,
    )
    val page = state.page
    LaunchedEffect(page, state.controlTarget) { cardOpenRejected = false }
    PreserveDiscoveryReadingAnchor(
        grid = grid,
        pageKey = com.easysavevideo.app.discovery.DiscoveryReadingAnchorPolicy.key(page, state.profile.queryKey, state.demo),
        resultIds = state.results.map { it.resultId },
        restoredFromStamp = state.restoredPageFromStamp,
        visible = active && bodyState == DiscoveryBodyState.CONTENT && !showPending && !optionsIsModal &&
            localControlConfirm == null && safeDisableConfirm == null && rightsDetails == null,
        currentPageKey = {
            controller.state.let { current ->
                com.easysavevideo.app.discovery.DiscoveryReadingAnchorPolicy.key(current.page, current.profile.queryKey, current.demo)
            }
        },
    )
    LaunchedEffect(page?.stamp, page?.revision, page?.pageIndex, active, showPending, optionsIsModal, localControlConfirm, safeDisableConfirm, rightsDetails) {
        if (page == null || !active || showPending || optionsIsModal || localControlConfirm != null || safeDisableConfirm != null || rightsDetails != null) return@LaunchedEffect
        val tracker = ShownVisibilityTracker()
        val results = state.results.associateBy { it.resultId }
        while (true) {
            val layout = grid.layoutInfo
            val visible = layout.visibleItemsInfo.associateBy { it.key.toString() }
            val now = SystemClock.elapsedRealtime()
            results.forEach { (id, result) ->
                val item = visible[id]
                val fraction = if (item == null) 0.0 else DiscoveryPresentationPolicy.fraction(
                    item.offset.x, item.offset.y, item.size.width, item.size.height, layout.viewportSize.width,
                    layout.viewportStartOffset, layout.viewportEndOffset, active, !optionsIsModal && localControlConfirm == null && safeDisableConfirm == null && rightsDetails == null)
                if (tracker.update(id, fraction.coerceIn(0.0, 1.0), now)) controller.recordSeen(page, result, SeenEvent.SHOWN)
            }
            delay(100)
        }
    }
    val optionsContent: @Composable (Modifier, DiscoveryFilterPanelPresentation) -> Unit = { panelModifier, presentation ->
        val panel = discoveryFilterPanelVisuals(v, presentation)
        Column(panelModifier.fillMaxWidth().background(panel.background, RoundedCornerShape(panel.corner))
            .padding(v.spacingLarge).verticalScroll(optionsScroll), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText(stringResource(R.string.flow_discoveryscreen_21), style = v.titleTextStyle)
            EsvButton(stringResource(R.string.flow_discoveryscreen_22), onClick = { options = false })
            state.notice?.let { EsvText(it, style = v.captionTextStyle) }
            EsvText(stringResource(R.string.flow_discoveryscreen_23), style = v.captionTextStyle)
            ResultSort.entries.forEach { order ->
                EsvButton((if (state.profile.sortOrder == order) "✓ " else "") + DiscoveryPresentationPolicy.sortLabel(order),
                    onClick = { controller.updateSort(order) }, enabled = state.ready && !state.storageUnavailable)
            }
            EsvText(stringResource(R.string.discovery_copy_17), style = v.captionTextStyle)
            EsvText(stringResource(R.string.flow_discoveryscreen_25), style = v.captionTextStyle)
            RepeatPolicy.entries.forEach { policy -> EsvButton((if (settings.repeatPolicy == policy) "✓ " else "") + policyText(policy), onClick = { onSettings(SettingsEdit.DiscoveryRepeat(policy)) }, enabled = settingsEditable) }
            EsvText(stringResource(R.string.discovery_copy_18, settings.pageSize, settings.columns), style = v.captionTextStyle)
            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton(stringResource(R.string.flow_discoveryscreen_27), onClick = { onSettings(SettingsEdit.DiscoveryPagePresetStep(-10)) }, enabled = settingsEditable && settings.pageSize > 10)
                EsvButton(stringResource(R.string.flow_discoveryscreen_28), onClick = { onSettings(SettingsEdit.DiscoveryPagePresetStep(10)) }, enabled = settingsEditable && settings.pageSize < 100)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton(stringResource(R.string.flow_discoveryscreen_29), onClick = { onSettings(SettingsEdit.DiscoveryColumns(-1)) }, enabled = settingsEditable && settings.columns > 1)
                EsvButton(stringResource(R.string.flow_discoveryscreen_30), onClick = { onSettings(SettingsEdit.DiscoveryColumns(1)) }, enabled = settingsEditable && settings.columns < 6)
            }
            DiscoveryMode.entries.forEach { mode -> EsvButton((if (settings.mode == mode) "✓ " else "") + when(mode) {
                DiscoveryMode.POWER_SAVING -> stringResource(R.string.discovery_copy_19); DiscoveryMode.BALANCED -> stringResource(R.string.discovery_copy_20); DiscoveryMode.AGGRESSIVE -> stringResource(R.string.discovery_copy_21)
            }, onClick = { onSettings(SettingsEdit.DiscoveryIntensity(mode)) }, enabled = settingsEditable) }
            DiscoveryPowerControls(state, controller)
            EsvText(stringResource(R.string.flow_discoveryscreen_31), style = v.titleTextStyle)
            if (appliedReadable) {
                EsvText(stringResource(R.string.discovery_copy_22, DiscoveryFilterPresentationPolicy.sourceSummary(state.profile.filters, choices, filterCopy)), style = v.captionTextStyle)
                EsvText(DiscoveryFilterPresentationPolicy.durationSummary(state.profile.filters, filterCopy), style = v.captionTextStyle)
                EsvText(stringResource(R.string.discovery_copy_25, if (state.profile.filters.safeSearch) stringResource(R.string.discovery_copy_23) else stringResource(R.string.discovery_copy_24)), style = v.captionTextStyle)
            } else EsvText(stringResource(R.string.flow_discoveryscreen_34), style = v.captionTextStyle)
            EsvText(stringResource(R.string.flow_discoveryscreen_35), style = v.titleTextStyle)
            EsvText(stringResource(R.string.discovery_copy_26), style = v.captionTextStyle)
            EsvButton((if (draft.sourceNamespaces.isEmpty()) "✓ " else "") + stringResource(R.string.discovery_copy_27), onClick = {
                edit(draft.copy(sourceNamespaces = emptySet()))
            }, enabled = editable)
            if (choices.isEmpty()) EsvText(stringResource(R.string.flow_discoveryscreen_37), style = v.captionTextStyle)
            choices.forEach { choice ->
                val selected = DiscoveryFilterPresentationPolicy.isSelected(draft.sourceNamespaces, choice.namespace)
                EsvButton((if (selected) "✓ " else stringResource(R.string.discovery_copy_28)) + "${choice.label} [${choice.namespace}]", onClick = {
                    edit(DiscoveryFilterPresentationPolicy.addSource(draft, choice))
                }, enabled = editable && !selected && draft.sourceNamespaces.size < 100)
                if (selected) EsvButton(if (draft.sourceNamespaces.size == 1) stringResource(R.string.discovery_copy_29) else stringResource(R.string.discovery_copy_30, choice.namespace), onClick = {
                    edit(DiscoveryFilterPresentationPolicy.removeSource(draft, choice.namespace))
                }, enabled = editable)
            }
            DiscoveryFilterPresentationPolicy.unavailableNamespaces(draft.sourceNamespaces, choices).forEach { namespace ->
                EsvText(stringResource(R.string.discovery_copy_31, namespace), style = v.captionTextStyle, color = v.colors.error)
                EsvButton(if (draft.sourceNamespaces.size == 1) stringResource(R.string.discovery_copy_32, namespace) else stringResource(R.string.discovery_copy_30, namespace), onClick = {
                    edit(DiscoveryFilterPresentationPolicy.removeSource(draft, namespace))
                }, enabled = editable)
            }
            EsvText(stringResource(R.string.flow_discoveryscreen_39), style = v.captionTextStyle)
            DurationFilterField(stringResource(R.string.discovery_copy_33), draft.minimumSeconds, editable) {
                editText(DiscoveryFilterTextField.MINIMUM_SECONDS, it)
            }
            DurationFilterField(stringResource(R.string.discovery_copy_34), draft.maximumSeconds, editable) {
                editText(DiscoveryFilterTextField.MAXIMUM_SECONDS, it)
            }
            EsvText(stringResource(R.string.discovery_copy_35), style = v.captionTextStyle)
            EsvButton(stringResource(R.string.discovery_copy_36, if (draft.includeUnknownDuration) stringResource(R.string.discovery_copy_23) else stringResource(R.string.discovery_copy_24)), onClick = {
                edit(draft.copy(includeUnknownDuration = !draft.includeUnknownDuration))
            }, enabled = editable)
            EsvButton(stringResource(R.string.discovery_copy_37, if (draft.safeSearch) stringResource(R.string.discovery_copy_23) else stringResource(R.string.discovery_copy_24)), onClick = {
                if (draft.safeSearch) editClaim?.takeIf(::currentClaimValid)?.let { safeDisableConfirm = it }
                else edit(draft.copy(safeSearch = true))
            }, enabled = editable)
            validation.error?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
            EsvButton(stringResource(R.string.flow_discoveryscreen_43), onClick = { search() }, enabled = canSearch)
            EsvButton(stringResource(R.string.flow_discoveryscreen_44), onClick = {
                edit(DiscoveryFilterPresentationPolicy.fromProfile(state.profile, state.demo))
            }, enabled = editable && DiscoveryFilterPresentationPolicy.isDirty(draft))
            EsvText(stringResource(R.string.flow_discoveryscreen_45), style = v.titleTextStyle)
            choices.forEach { choice -> DiscoveryFilterPresentationPolicy.capabilityText(choice, filterCopy).forEach {
                EsvText(it, style = v.captionTextStyle)
            } }
            DiscoveryFilterPresentationPolicy.unknownDescriptorIds(state.admittedProviderDescriptors, choices).forEach {
                EsvText(stringResource(R.string.discovery_copy_38, it), style = v.captionTextStyle)
            }
            EsvText(DiscoveryFilterPresentationPolicy.nativeHistoryNotice(filterCopy), style = v.captionTextStyle)
            EsvText(if (state.commonsConfigured) stringResource(R.string.discovery_copy_39) else stringResource(R.string.discovery_copy_40), style = v.captionTextStyle)
            EsvButton(stringResource(R.string.flow_discoveryscreen_47), onClick = { options = false; onManageProviders() })
            if (state.commonsConfigured) EsvButton(stringResource(R.string.discovery_copy_41, if (state.commonsEnabled) stringResource(R.string.discovery_copy_23) else stringResource(R.string.discovery_copy_24)),
                onClick = { controller.enableCommons(!state.commonsEnabled) })
            SearxngSourceSettings(controller.sourceSettings)
            VerificationServiceSettings(controller.verificationSettings)
            EsvText(stringResource(R.string.flow_discoveryscreen_49), style = v.titleTextStyle)
            EsvText(stringResource(R.string.discovery_copy_42), style = v.captionTextStyle)
            EsvText(stringResource(R.string.discovery_copy_43), style = v.captionTextStyle)
            if (state.demo) EsvText(stringResource(R.string.discovery_copy_44), style = v.captionTextStyle, color = v.colors.error)
            val statistics = state.historyStatistics
            if (statistics != null) {
                EsvText(DiscoveryPresentationPolicy.statisticsText(stringResource(R.string.discovery_copy_45), statistics.currentProfile), style = v.captionTextStyle)
                EsvText(DiscoveryPresentationPolicy.statisticsText(stringResource(R.string.discovery_copy_46), statistics.allProfiles), style = v.captionTextStyle)
            } else EsvText(if (state.historyStatisticsUnavailable || state.storageUnavailable) stringResource(R.string.discovery_copy_47)
                else stringResource(R.string.discovery_copy_48), style = v.captionTextStyle)
            EsvButton(stringResource(R.string.flow_discoveryscreen_53), onClick = { controller.refreshHistoryStatistics(state.controlTarget) },
                enabled = state.controlTarget != null && !state.storageUnavailable)
            EsvText(DiscoveryPresentationPolicy.retentionNotice(), style = v.captionTextStyle)
            EsvButton(stringResource(R.string.discovery_copy_49), onClick = {
                state.controlTarget?.let { localControlConfirm = DiscoveryControlRequest(it, DiscoveryLocalAction.CLEAR_CURRENT_HISTORY) }
            }, enabled = state.controlTarget != null && !state.storageUnavailable)
            EsvButton(stringResource(R.string.discovery_copy_50), onClick = {
                state.controlTarget?.let { localControlConfirm = DiscoveryControlRequest(it, DiscoveryLocalAction.CLEAR_ALL_HISTORY) }
            }, enabled = state.controlTarget != null && !state.storageUnavailable)
            state.status?.providers?.forEach { progress ->
                EsvText(DiscoveryPresentationPolicy.providerProgressText(progress), style = v.captionTextStyle)
                EsvButton(stringResource(R.string.discovery_copy_51, progress.providerId), onClick = {
                    state.controlTarget?.let { localControlConfirm = DiscoveryControlRequest(it, DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, progress.providerId) }
                }, enabled = state.controlTarget != null && !state.storageUnavailable)
            }
            EsvText(stringResource(R.string.discovery_copy_52), style = v.captionTextStyle)
            if (BuildDiscovery.supportsDemo) EsvButton(if (state.demo) stringResource(R.string.discovery_copy_53) else stringResource(R.string.discovery_copy_54), onClick = { controller.enableDemo(!state.demo); options = false })
        }
    }
    val optionsContentState = rememberUpdatedState(optionsContent)
    // Move the same composition: source-editor local drafts/claims survive dialog/sidebar changes.
    val optionsPanel = remember {
        movableContentOf<Modifier, DiscoveryFilterPanelPresentation> { modifier, presentation ->
            optionsContentState.value(modifier, presentation)
        }
    }
    EsvBackdrop(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(v.spacingMedium), horizontalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            Column(Modifier.fillMaxWidth().heightIn(max = paneLayout.headerMaxHeightDp.dp).verticalScroll(headerScroll),
                verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.flow_discoveryscreen_0), style = v.titleTextStyle)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvButton(stringResource(R.string.flow_discoveryscreen_1), onClick = onBrowser)
                    EsvButton(stringResource(R.string.flow_discoveryscreen_2), onClick = onLibrary)
                    EsvButton(stringResource(R.string.flow_discoveryscreen_3), onClick = { options = true })
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
              val layout = discoveryResponsiveLayout(maxWidth.value, LocalDensity.current.fontScale, settings.columns, v.spacingSmall.value)
              val QueryField: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedTextField(draft.query, onValueChange = { editText(DiscoveryFilterTextField.QUERY, it) },
                    singleLine = true, enabled = editable,
                    modifier = modifier.semantics { contentDescription = queryDescription },
                    textStyle = v.bodyTextStyle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),

                    trailingIcon = { InputClearButton(R.string.input_clear_discovery,
                        enabled = editable && draft.query.isNotEmpty() && currentQueryClearInteractive(),
                        onClear = { if (currentQueryClearInteractive()) {
                            editClaim?.let { controller.clearFilterQuery(it, queryClearLifecycle) }
                        } }) },
                    placeholder = { Text(stringResource(R.string.flow_discoveryscreen_4)) })
              }
              if (layout.stackSearch) Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                QueryField(Modifier.fillMaxWidth())
                EsvButton(stringResource(R.string.flow_discoveryscreen_5), onClick = { search() }, enabled = canSearch, modifier = Modifier.fillMaxWidth())
              } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                QueryField(Modifier.weight(1f))
                EsvButton(stringResource(R.string.flow_discoveryscreen_5), onClick = { search() }, enabled = canSearch)
              }
            }
            if (controller.filterSearchPending) EsvText(stringResource(R.string.flow_discoveryscreen_6), style = v.captionTextStyle)
            else if (appliedReadable && DiscoveryFilterPresentationPolicy.isDirty(draft)) EsvText(
                stringResource(R.string.discovery_copy_0, state.profile.query), style = v.captionTextStyle)
            validation.error?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
            if (state.demo) EsvText(stringResource(R.string.flow_discoveryscreen_7), color = v.colors.error, style = v.captionTextStyle)
            if (!state.demo && state.commonsConfigured && state.commonsEnabled) EsvText(stringResource(R.string.flow_discoveryscreen_8), style = v.captionTextStyle)
            if (appliedReadable && !state.demo && state.profile.filters.safeSearch && state.commonsConfigured && state.commonsEnabled) EsvText(
                stringResource(R.string.discovery_copy_1), style = v.captionTextStyle, color = v.colors.error)
            EsvText(statusText(state.status, state.storageUnavailable, state.ready), style = v.captionTextStyle, color = v.colors.muted)
            if (state.saveActive || state.recordingActive) EsvText(
                if (state.recordingActive) stringResource(R.string.discovery_copy_2)
                else stringResource(R.string.discovery_copy_3),
                style = v.captionTextStyle, color = v.colors.muted)
            if (state.status?.state == DiscoveryState.CAPACITY_REACHED) EsvText(
                stringResource(R.string.discovery_copy_4), style = v.captionTextStyle)
            state.notice?.let { EsvText(it, style = v.captionTextStyle) }
            if (cardOpenRejected) EsvText(stringResource(R.string.discovery_open_unavailable),
                style = v.captionTextStyle, color = v.colors.error)
            if (state.needsExplicitSearch) EsvText(stringResource(R.string.flow_discoveryscreen_9), style = v.captionTextStyle)
            EsvText(stringResource(R.string.discovery_copy_5, state.localSourceAdmission), style = v.captionTextStyle)
            state.verificationSummary?.let { summary ->
                EsvText(stringResource(R.string.discovery_copy_6, summary.outcome, summary.evidence), style = v.captionTextStyle)
            }
            if (state.pendingCandidates.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton(stringResource(R.string.discovery_copy_7, state.results.size), onClick = { showPending = false })
                EsvButton(stringResource(R.string.discovery_copy_8, state.pendingCandidates.size), onClick = { showPending = true })
            }

            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = discoveryResponsiveLayout(maxWidth.value, LocalDensity.current.fontScale, settings.columns, v.spacingSmall.value).columns
                Column(Modifier.fillMaxSize()) {
                if (columns < settings.columns) EsvText(stringResource(R.string.discovery_grid_adapted, settings.columns, columns), style = v.captionTextStyle, color = v.colors.muted)
                if (bodyState == DiscoveryBodyState.PENDING_CANDIDATES) {
                    PendingCandidateCards(state.pendingCandidates, columns, admission = state.pendingAdmission, onVerify = { candidate ->
                        controller.verifyCandidate(state.controlTarget, candidate) }) { candidate ->
                        controller.candidateUrl(state.controlTarget, candidate)?.let(onLink)
                    }
                } else if (bodyState != DiscoveryBodyState.CONTENT) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(v.spacingLarge),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    val bodyText = requireNotNull(discoveryBodyText(bodyState))
                    EsvText(stringResource(bodyText.title), style = v.titleTextStyle)
                    Spacer(Modifier.height(v.spacingMedium))
                    EsvText(stringResource(bodyText.detail), color = v.colors.muted)
                    if (bodyText.showFilterGuidance && state.profile.filters.safeSearch && !state.demo) {
                        EsvText(stringResource(R.string.discovery_copy_12), color = v.colors.muted)
                    }
                    Spacer(Modifier.height(v.spacingMedium))
                    discoveryBodyRecovery(bodyState)?.let { recovery ->
                        EsvButton(stringResource(discoveryBodyRecoveryLabel(recovery)), onClick = {
                            dispatchDiscoveryBodyRecovery(recovery, state, controller.state,
                                cardNavigationLifecycle, controller.cardNavigationLifecycle,
                                interactive = currentQueryClearInteractive() && !controller.filterSearchPending,
                                openSources = { options = false; onManageProviders() },
                                openFilters = { options = true })
                        }, enabled = active && state.ready && !state.storageUnavailable && !controller.filterSearchPending)
                    }
                    EsvButton(stringResource(R.string.flow_discoveryscreen_14), onClick = onBrowser)
                } else LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    items(state.results, key = { it.resultId }) { result ->
                        Column(Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.cardCorner))
                            .background(v.colors.surface, RoundedCornerShape(v.cardCorner)).padding(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(v.backdrop, RoundedCornerShape(v.corner)), contentAlignment = Alignment.Center) {
                                EsvThemeIcon(stringResource(R.string.discovery_copy_14), Modifier.size(v.iconSize))
                            }
                            EsvText(result.title ?: stringResource(R.string.flow_title_unknown), style = v.bodyTextStyle)
                            EsvText(result.sourceName, style = v.captionTextStyle, color = v.colors.muted)
                            if (result.provenance.any { it.startsWith("searxng-") }) EsvText(
                                stringResource(R.string.discovery_copy_15), style = v.captionTextStyle, color = v.colors.muted)
                            EsvText(result.durationMs?.let { "${it / 60_000}:${(it / 1_000 % 60).toString().padStart(2, '0')}" } ?: stringResource(R.string.flow_duration_unknown), style = v.captionTextStyle)
                            RightsPresentationPolicy.summary(result.rights).forEach { EsvText(it, style = v.captionTextStyle) }
                            EsvButton(stringResource(R.string.flow_discoveryscreen_15), onClick = { rightsDetails = result }, modifier = Modifier.fillMaxWidth())
                            val openRequest = discoveryCardOpenRequest(state, result, cardNavigationLifecycle)
                            EsvButton(stringResource(R.string.flow_discoveryscreen_16),
                                onClick = { if (currentQueryClearInteractive() && !controller.filterControlsBusy && openRequest != null)
                                    cardOpenRejected = !onOpen(openRequest) },
                                enabled = active && openRequest != null && !controller.filterControlsBusy && !optionsIsModal &&
                                    localControlConfirm == null && safeDisableConfirm == null && rightsDetails == null,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = paneLayout.footerMaxHeightDp.dp).verticalScroll(pagerScroll)) {

            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton(stringResource(R.string.flow_discoveryscreen_17), onClick = { controller.movePage(-1, state.controlTarget, state.pageIndex) }, enabled = state.ready && !state.storageUnavailable && state.pageIndex > 0)
                EsvText(if (state.materializedPages == 0) stringResource(R.string.discovery_pages_none)
                    else stringResource(R.string.discovery_pages_materialized, state.pageIndex + 1, state.materializedPages), style = v.captionTextStyle)
                EsvButton(stringResource(R.string.flow_discoveryscreen_19), onClick = { controller.movePage(1, state.controlTarget, state.pageIndex) }, enabled = state.ready && !state.storageUnavailable && !state.nextPagePending && DiscoveryPagerPolicy.canAdvance(state.pageIndex, state.materializedPages, state.status))
                EsvButton(stringResource(R.string.flow_discoveryscreen_20), onClick = controller::retry, enabled = state.ready && !state.storageUnavailable && !state.nextPagePending && !DiscoveryPagerPolicy.sourcesExhausted(state.status))
            }
            if (state.nextPagePending) {
                EsvText(stringResource(R.string.discovery_page_waiting), style = v.captionTextStyle)
                EsvButton(stringResource(R.string.discovery_page_cancel), onClick = { controller.cancelNextPage(state.controlTarget) })
            } else if (DiscoveryPagerPolicy.sourcesExhausted(state.status)) {
                EsvText(stringResource(R.string.discovery_pages_exhausted), style = v.captionTextStyle)
            }

            }
        }
        if (options && paneLayout.split) optionsPanel(Modifier.width(paneLayout.sideWidthDp.dp).fillMaxHeight(), DiscoveryFilterPanelPresentation.INLINE)
        }
    }
    if (options && !paneLayout.split) Dialog(onDismissRequest = { options = false }) {
        optionsPanel(Modifier.heightIn(max = maxHeight.coerceAtMost(680.dp)), DiscoveryFilterPanelPresentation.FLOATING)
    }

    safeDisableConfirm?.let { captured -> Dialog(onDismissRequest = { safeDisableConfirm = null }) {
        val valid = currentClaimValid(captured)
        Column(Modifier.background(v.colors.elevated, RoundedCornerShape(v.sheetCorner)).padding(v.spacingLarge),
            verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText(stringResource(R.string.discovery_copy_55), style = v.titleTextStyle)
            EsvText(stringResource(R.string.discovery_copy_56))
            if (!valid) EsvText(stringResource(R.string.discovery_copy_57), style = v.captionTextStyle, color = v.colors.error)
            EsvButton(stringResource(R.string.discovery_copy_58), onClick = {
                controller.confirmFilterSafety(captured)
                safeDisableConfirm = null
            }, enabled = valid)
            EsvButton(stringResource(R.string.discovery_copy_59), onClick = { safeDisableConfirm = null })
        }
    } }
    rightsDetails?.let { result -> Dialog(onDismissRequest = { rightsDetails = null }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.elevated, RoundedCornerShape(v.sheetCorner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvText(stringResource(R.string.discovery_copy_60), style = v.titleTextStyle)
            EsvButton(stringResource(R.string.discovery_copy_61), onClick = { rightsDetails = null })
            EsvText(stringResource(R.string.discovery_copy_62), style = v.captionTextStyle, color = v.colors.error)
            if (result.rights.snapshots.isEmpty()) EsvText(stringResource(R.string.discovery_copy_63))
            if (result.rights.warnings.isNotEmpty()) EsvText(stringResource(R.string.discovery_copy_64, result.rights.warnings.joinToString { it.name }), style = v.captionTextStyle)
            result.rights.snapshots.forEach { snapshot ->
                RightsPresentationPolicy.details(snapshot).forEach { EsvText(it, style = v.captionTextStyle) }
                listOf(stringResource(R.string.discovery_copy_65) to snapshot.sourcePageUrl, stringResource(R.string.discovery_copy_66) to snapshot.licenseUrl,
                    stringResource(R.string.discovery_copy_67) to snapshot.metadataTermsUrl).forEach { (label, url) ->
                    RightsPresentationPolicy.safeLink(url)?.let { safe ->
                        EsvText("$label：$safe", style = v.captionTextStyle)
                        EsvButton(stringResource(R.string.discovery_copy_68, label), onClick = { rightsDetails = null; onLink(safe) })
                    }
                }
            }
        }
    } }
    localControlConfirm?.let { request -> Dialog(onDismissRequest = { localControlConfirm = null }) {
        val current = state.controlTarget
        val valid = !state.storageUnavailable && DiscoveryPresentationPolicy.validControl(request, current,
            state.status?.providers?.map { it.providerId }?.toSet().orEmpty())
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.elevated, RoundedCornerShape(v.sheetCorner))
            .padding(v.spacingLarge).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText(DiscoveryPresentationPolicy.controlTitle(request), style = v.titleTextStyle)
            if (state.demo) EsvText(stringResource(R.string.discovery_copy_69), style = v.captionTextStyle)
            EsvText(DiscoveryPresentationPolicy.controlConsequence(request), style = v.captionTextStyle)
            if (!valid) EsvText(stringResource(R.string.discovery_copy_70), style = v.captionTextStyle, color = v.colors.error)
            EsvButton(if (request.action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS) stringResource(R.string.discovery_copy_71) else stringResource(R.string.discovery_copy_72),
                onClick = { localControlConfirm = null; controller.confirmLocalControl(request) }, enabled = valid)
            EsvButton(stringResource(R.string.discovery_copy_73), onClick = { localControlConfirm = null })
        }
    } }

    }
}
@Composable
private fun DurationFilterField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val v = LocalEsvVisuals.current
    EsvText(label, style = v.captionTextStyle)
    BasicTextField(value, onValueChange = { if (it.length <= DiscoveryFilterPresentationPolicy.MAX_DURATION_TEXT) onValue(it) },
        enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
            .padding(v.spacingMedium).semantics { contentDescription = label },
        textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done))
}
@Composable
private fun policyText(policy: RepeatPolicy): String = when(policy) {
    RepeatPolicy.PREFER_NEW -> stringResource(R.string.discovery_copy_74); RepeatPolicy.HIDE_WATCHED -> stringResource(R.string.discovery_copy_75)
    RepeatPolicy.HIDE_SHOWN -> stringResource(R.string.discovery_copy_76); RepeatPolicy.SHOW_ALL -> stringResource(R.string.discovery_copy_77)
}
@Composable
private fun statusText(status: DiscoveryStatus?, failed: Boolean, ready: Boolean): String {
    if (failed) return stringResource(R.string.discovery_copy_78)
    if (!ready) return stringResource(R.string.discovery_copy_79)
    if (status?.state == DiscoveryState.UNSUPPORTED_FILTER && status.lastError == DiscoveryError.INVALID_QUERY) return stringResource(R.string.discovery_copy_80)
    if (status?.state == DiscoveryState.UNSUPPORTED_FILTER && status.lastError == DiscoveryError.SOURCE_CONFIGURATION) return stringResource(R.string.discovery_copy_81)
    val title = when(status?.state) {
        DiscoveryState.NO_SOURCES -> stringResource(R.string.discovery_copy_82); DiscoveryState.FILLING -> stringResource(R.string.discovery_copy_83)
        DiscoveryState.BUFFERED -> stringResource(R.string.discovery_copy_84); DiscoveryState.BACKOFF -> stringResource(R.string.discovery_copy_85)
        DiscoveryState.EXHAUSTED -> stringResource(R.string.discovery_copy_86); DiscoveryState.SUSPENDED -> stringResource(R.string.discovery_copy_87)
        DiscoveryState.UNSUPPORTED_FILTER -> stringResource(R.string.discovery_copy_88)
        DiscoveryState.FAILED -> stringResource(R.string.discovery_copy_89); DiscoveryState.CAPACITY_REACHED -> stringResource(R.string.discovery_copy_90)
        else -> stringResource(R.string.discovery_copy_91)
    }
    return stringResource(R.string.discovery_copy_92, title, status?.availableCount ?: 0, status?.requestsThisRound ?: 0) +
        stringResource(R.string.discovery_copy_95, if (status?.powerPolicyUpdatePending == true) stringResource(R.string.discovery_copy_93) else status?.powerPolicy?.maxRequestsPerRound?.toString() ?: stringResource(R.string.discovery_copy_94))
}

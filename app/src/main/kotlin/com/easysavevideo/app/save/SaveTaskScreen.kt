package com.easysavevideo.app.save

import com.easysavevideo.library.MediaLifecycle
import com.easysavevideo.app.library.destination.OrdinaryDestinationHistory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.easysavevideo.app.ui.responsivePaneLayout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import com.easysavevideo.app.ui.EsvAppDialog
import com.easysavevideo.app.ui.LocalPrivateInputAdmission
import com.easysavevideo.app.ui.LocalPrivatePresentation
import com.easysavevideo.app.ui.CapturedPrivateActions
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.theme.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.recording.recordingAudioOutcomeResource
import com.easysavevideo.app.recording.recordingStopReasonResource
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.save.*
import java.net.URI

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun SaveTaskScreen(
    runtime: AndroidSaveRuntime,
    browser: SystemBrowserHost,
    onRecord: (() -> Unit)? = null,
    onPreview: ((SaveRecord) -> Unit)? = null,
    onLibrary: (() -> Unit)? = null,
    onBrowser: (() -> Unit)? = null,
    onBack: () -> Unit,
    canOpenBrowserForAuthentication: () -> Boolean = { false },
    recoveryIsCurrent: (AndroidSaveRuntime) -> Boolean = { false },
    canAct: () -> Boolean,
) {
    val v = LocalEsvVisuals.current
    val inputAdmission = LocalPrivateInputAdmission.current
    val privateMode = LocalPrivatePresentation.current
    val actions = remember(runtime, browser, privateMode, inputAdmission, canAct) {
        CapturedPrivateActions(privateMode, inputAdmission, canAct)
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    val taskAdmission = remember(actions) { actions::isCurrent }
    val currentRecoveryAdmission by rememberUpdatedState(recoveryIsCurrent)
    val recoveryAction = remember(runtime) { runtime.recoveryRetryAction { currentRecoveryAdmission(runtime) } }
    DisposableEffect(recoveryAction) { onDispose { recoveryAction.close() } }
    var showLimits by rememberSaveable { mutableStateOf(false) }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val headerScroll = rememberScrollState()
    val detailScroll = remember(selectedTaskId) { androidx.compose.foundation.ScrollState(0) }
    val detail = selectedTaskDetail(runtime.records, selectedTaskId)
    CompositionLocalProvider(LocalPrivateInputAdmission provides taskAdmission) {
    BackHandler(onBack = { actions.run(onBack) })
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.flow_savetaskscreen_0), style = v.titleTextStyle)
                EsvButton(stringResource(R.string.flow_savetaskscreen_1), onClick = { actions.run(onBack) })
            }
            onLibrary?.let { open -> EsvButton(stringResource(R.string.flow_savetaskscreen_2), onClick = { actions.run(open) }, modifier = Modifier.fillMaxWidth()) }
            onRecord?.let { open -> EsvButton(stringResource(R.string.flow_savetaskscreen_3), onClick = { actions.run(open) }, modifier = Modifier.fillMaxWidth()) }
            EsvText(stringResource(R.string.flow_savetaskscreen_4), style = v.captionTextStyle)
            EsvButton(stringResource(if (showLimits) R.string.flow_limits_hide else R.string.flow_limits_show),
                onClick = { actions.run { showLimits = !showLimits } })
            if (showLimits) {
            EsvText(stringResource(R.string.flow_savetaskscreen_5), style = v.captionTextStyle, color = v.colors.muted)
            EsvText(stringResource(R.string.flow_savetaskscreen_6), style = v.captionTextStyle, color = v.colors.muted)
            EsvText(stringResource(R.string.flow_savetaskscreen_7), style = v.captionTextStyle, color = v.colors.muted)
            EsvText(stringResource(R.string.flow_savetaskscreen_8), style = v.captionTextStyle, color = v.colors.muted)
            EsvText(stringResource(R.string.flow_savetaskscreen_9), style = v.captionTextStyle, color = v.colors.muted)
            }
            if (runtime.draining) EsvText(if (runtime.networkDraining) stringResource(R.string.final_copy_46)
                else stringResource(R.string.final_copy_47), color = v.colors.error)
            runtime.notice?.let { text ->
                EsvText(saveRuntimeNoticeText(text), color = v.colors.error)
                EsvButton(stringResource(R.string.flow_savetaskscreen_10), onClick = { actions.run(runtime::dismissNotice) })
            }
            SaveMethodAlternativeFeedback(runtime)
            // Independent of records/counts: a failed journal snapshot must not hide local repair.
            runtime.pendingSaveRecoveries.forEach { ticket ->
                EsvText(stringResource(R.string.save_recovery_failed, ticket.taskId.take(8)), color = v.colors.error)
                EsvButton(stringResource(R.string.save_recovery_retry), onClick = { actions.run { recoveryAction.invoke(ticket) } },
                    enabled = ticket.taskId !in runtime.pendingCommands)
                EsvButton(stringResource(R.string.flow_savetaskscreen_21), onClick = { actions.run { recoveryAction.cancel(ticket.taskId, runtime::cancel) } },
                    enabled = ticket.taskId !in runtime.pendingCommands)
            }
            runtime.recoveryCancelTaskIds.forEach { taskId ->
                EsvText(stringResource(R.string.save_recovery_cancel_pending, taskId.take(8)), color = v.colors.error)
                EsvButton(stringResource(R.string.flow_savetaskscreen_21), onClick = { actions.run { recoveryAction.cancel(taskId, runtime::cancel) } },
                    enabled = taskId !in runtime.pendingCommands)
            }
            runtime.destinations?.let { OrdinaryDestinationHistory(it) }
            if (!runtime.ready) EsvText(stringResource(R.string.flow_savetaskscreen_11))
            else if (runtime.records.isEmpty()) EsvText(stringResource(R.string.flow_savetaskscreen_12))
        }
    }
    EsvBackdrop(Modifier.fillMaxSize()) {
      BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(v.spacingMedium)) {
        val layout = responsivePaneLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, v.spacingMedium.value)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
          Column(Modifier.fillMaxWidth().heightIn(max = layout.headerMaxHeightDp.dp).verticalScroll(headerScroll)) { header() }
          Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            val listModifier = if (layout.split) Modifier.width(layout.sideWidthDp.dp).fillMaxHeight() else Modifier.fillMaxSize()
            LazyColumn(listModifier, state = listState, verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
              items(runtime.records, key = { it.task.taskId }) { record ->
                Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                  if (layout.split) Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.cardCorner)).padding(v.spacingMedium)) {
                    EsvText("${stringResource(taskMethodTextResource(record.request.method))} · ${stringResource(taskStateTextResource(record))}", style = v.bodyTextStyle)
                    EsvText(stringResource(R.string.flow_task_id, record.task.taskId.take(8)), style = v.captionTextStyle, color = v.colors.muted)
                  } else SaveTaskDetail(record, runtime, browser, onRecord, onPreview, onBrowser, canOpenBrowserForAuthentication)
                  EsvButton(stringResource(if (record.task.taskId == selectedTaskId) R.string.task_detail_selected else R.string.task_detail_select),
                    onClick = { actions.run { selectedTaskId = record.task.taskId } }, modifier = Modifier.fillMaxWidth().semantics { selected = record.task.taskId == selectedTaskId })
                }
              }
            }
            if (layout.split) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(detailScroll), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
              EsvText(stringResource(R.string.task_detail_heading), style = v.sectionTextStyle)
              if (detail != null) SaveTaskDetail(detail, runtime, browser, onRecord, onPreview, onBrowser, canOpenBrowserForAuthentication)
              else EsvText(stringResource(if (selectedTaskId == null) R.string.task_detail_empty else R.string.task_detail_unavailable))
              if (selectedTaskId != null) EsvButton(stringResource(R.string.task_detail_clear), onClick = { actions.run { selectedTaskId = null } })
            }
          }
        }
      }
    }
    }
}

@Composable
private fun SaveTaskDetail(record: SaveRecord, runtime: AndroidSaveRuntime, browser: SystemBrowserHost,
    onRecord: (() -> Unit)?, onPreview: ((SaveRecord) -> Unit)?, onBrowser: (() -> Unit)?,
    canOpenBrowserForAuthentication: () -> Boolean) {
    val admission = LocalPrivateInputAdmission.current
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.cardCorner)).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText("${stringResource(taskMethodTextResource(record.request.method))} · ${stringResource(taskStateTextResource(record))}", style = v.titleTextStyle)
        EsvText(stringResource(R.string.flow_task_id, record.task.taskId.take(8)), style = v.captionTextStyle, color = v.colors.muted)
        if (record.task.state == TaskState.SUCCEEDED) {
            runtime.discoverySaveOutcomes[record.task.taskId]?.let { outcome ->
                EsvText(stringResource(saveDiscoveryOutcomeResource(outcome.history)), style = v.captionTextStyle, color = v.colors.muted)
            }
            val evidence = record.verification
            val disposition = record.receipt?.let { runtime.mediaDispositions[it.mediaId] }
            EsvText(when (disposition) {
                MediaLifecycle.READY -> stringResource(R.string.flow_disposition_ready)
                MediaLifecycle.DELETE_PENDING -> stringResource(R.string.flow_disposition_pending)
                MediaLifecycle.DELETED -> stringResource(R.string.flow_disposition_deleted)
                null -> stringResource(R.string.flow_disposition_unknown)
            })
            if (evidence != null) EsvText(stringResource(R.string.save_display_verified,
                saveSizeText(evidence.sizeBytes), saveResolutionText(evidence.width, evidence.height),
                saveSecondsText(evidence.durationMillis / 1000), saveAudioTracksText(evidence.audioTrackCount)), style = v.captionTextStyle)
            onPreview?.let { preview -> EsvButton(stringResource(R.string.flow_savetaskscreen_16), onClick = { if (admission()) preview(record) }, enabled = disposition == MediaLifecycle.READY && runtime.currentRecordingId == null && !runtime.draining) }
            EsvText(stringResource(R.string.flow_savetaskscreen_17), style = v.captionTextStyle, color = v.colors.muted)
        } else if (record.task.state in setOf(TaskState.RUNNING, TaskState.WAITING_USER, TaskState.QUEUED)) {
            val checkpoint = record.checkpoint
            val bytes = record.transfer?.bytesOnDisk
            EsvText(if (bytes != null) {
                    stringResource(R.string.flow_output_pending, saveSizeText(bytes))
                }
                else if (checkpoint != null) stringResource(R.string.flow_transfer_progress, saveSizeText(checkpoint.offset), saveSizeText(checkpoint.totalBytes))
                else if (record.request.method == TaskMethod.RECORD) stringResource(R.string.flow_record_progress)
                else stringResource(R.string.flow_progress_unknown), style = v.captionTextStyle)
        }
        record.transfer?.recordingExpectation?.let { recording ->
            EsvText(stringResource(R.string.save_display_recording,
                saveDurationText(recording.effectiveDurationUs),
                saveResolutionText(recording.actualProfile.width, recording.actualProfile.height)), style = v.captionTextStyle)
            EsvText(stringResource(recordingAudioOutcomeResource(recording.audioOutcome)), style = v.captionTextStyle)
            EsvText(stringResource(recordingStopReasonResource(recording.stopReason)), style = v.captionTextStyle)
        }
        if (record.request.method == TaskMethod.RECORD && !record.task.state.isTerminal) onRecord?.let { open ->
            EsvButton(stringResource(R.string.flow_savetaskscreen_19), onClick = { if (admission()) open() })
        }
        record.error?.let { EsvText(stringResource(saveFailureTextResource(it)), color = v.colors.error, style = v.captionTextStyle) }
        SaveFailureGuidance(record, runtime, onBrowser)
        SaveAuthenticationGuidance(record, runtime, onBrowser, canOpenBrowserForAuthentication)
        SaveAuthenticationOriginGuidance(record, runtime, browser, onBrowser, canOpenBrowserForAuthentication)
        if (record.task.state == TaskState.WAITING_USER) EsvText(if (record.request.method == TaskMethod.RECORD && record.transfer == null)
            stringResource(R.string.final_copy_48)
        else when (record.waitReason) {
            SaveWaitReason.BACKGROUND -> stringResource(R.string.final_copy_49)
            SaveWaitReason.AUTHENTICATION -> stringResource(R.string.final_copy_50)
            SaveWaitReason.NETWORK -> stringResource(if (record.error == SaveError.WIFI_REQUIRED) {
                if (record.request.method == TaskMethod.STREAM && record.transfer == null) R.string.save_wifi_stream_wait else R.string.save_wifi_required
            } else R.string.final_copy_51)
            SaveWaitReason.STORAGE -> stringResource(R.string.final_copy_52)
            else -> stringResource(R.string.final_copy_53)
        }, style = v.captionTextStyle)
        if (record.task.taskId in runtime.pendingCommands) EsvText(stringResource(R.string.flow_savetaskscreen_20), style = v.captionTextStyle)
        Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            if (record.task.state == TaskState.WAITING_USER &&
                (record.request.method != TaskMethod.RECORD || record.transfer != null)) EsvButton(stringResource(if (record.request.method == TaskMethod.RECORD) R.string.flow_reverify else R.string.flow_resume), onClick = { if (admission()) runtime.resume(record.task.taskId, browser) },
                enabled = !runtime.draining && record.task.taskId !in runtime.pendingCommands)
            if (!record.task.state.isTerminal) EsvButton(stringResource(R.string.flow_savetaskscreen_21), onClick = { if (admission()) runtime.cancel(record.task.taskId) },
                enabled = record.task.taskId !in runtime.pendingCommands)
        }
    }
}

@Composable
fun SaveSelectionDialog(runtime: AndroidSaveRuntime, browser: SystemBrowserHost) {
    val request = runtime.currentSelectionPresentationIdentity ?: return
    if (runtime.preparingStream || runtime.hlsPreparation != null) {
        HlsSelectionDialog(runtime, request)
        return
    }
    if (runtime.selection == null) return
    EsvAppDialog(request, { runtime.currentSelectionPresentationIdentity === request }, runtime::dismissSelection) {
        SaveSelectionPanel(runtime, browser, Modifier.heightIn(max = 680.dp), SaveSelectionPresentation.FLOATING)
    }

}
enum class SaveSelectionPresentation { INLINE, FLOATING }
internal data class SaveSelectionVisuals(val background: Color, val corner: Dp, val padding: Dp)
internal fun saveSelectionVisuals(v: EsvComposeVisuals, presentation: SaveSelectionPresentation) = when (presentation) {
    SaveSelectionPresentation.INLINE -> SaveSelectionVisuals(v.colors.surface, v.cardCorner, v.spacingMedium)
    SaveSelectionPresentation.FLOATING -> SaveSelectionVisuals(v.colors.elevated, v.sheetCorner, v.spacingExtraLarge)
}

@Composable
fun SaveSelectionPanel(runtime: AndroidSaveRuntime, browser: SystemBrowserHost, modifier: Modifier = Modifier,
    presentation: SaveSelectionPresentation = SaveSelectionPresentation.INLINE) {
    val v = LocalEsvVisuals.current
    val selection = runtime.selection ?: return
    val admission = LocalPrivateInputAdmission.current
    fun ownsSelection() = admission() && runtime.selection === selection &&
        runtime.currentSelectionPresentationIdentity === selection
    val panel = saveSelectionVisuals(v, presentation)
    Column(modifier.fillMaxWidth().background(panel.background, RoundedCornerShape(panel.corner))
        .verticalScroll(rememberScrollState()).padding(panel.padding), verticalArrangement = Arrangement.spacedBy(v.spacingCompact)) {
        EsvText(stringResource(R.string.flow_savetaskscreen_22), style = v.titleTextStyle)
        EsvText(stringResource(R.string.flow_savetaskscreen_23), style = v.captionTextStyle)
        EsvText(stringResource(R.string.flow_savetaskscreen_24), style = v.captionTextStyle)
        selection.candidates.forEach { candidate ->
            Column(verticalArrangement = Arrangement.spacedBy(v.spacingExtraSmall)) {
                EsvText(pageSaveCandidateLabel(candidate.displayIndices))
                EsvText(stringResource(R.string.save_display_candidate,
                    URI(candidate.url.value).host ?: stringResource(R.string.final_copy_54),
                    if (candidate.width != null && candidate.height != null) saveResolutionText(candidate.width, candidate.height)
                        else stringResource(R.string.final_copy_55),
                    candidate.durationMillis?.let { saveSecondsText(it / 1000) } ?: stringResource(R.string.final_copy_56)),
                    style = v.captionTextStyle, color = v.colors.muted)
                EsvButton(when {
                    candidate.protected -> stringResource(R.string.final_copy_57)
                    !candidate.supported -> stringResource(R.string.final_copy_58)
                    runtime.isSaveMethodDisabled(candidate.method) -> stringResource(R.string.save_runtime_notice_method_disabled)
                    !runtime.canSave(candidate.method) -> stringResource(R.string.final_copy_59)
                    !runtime.canStartSave(candidate.method) -> stringResource(R.string.save_runtime_notice_save_unavailable)
                    candidate.method == TaskMethod.STREAM -> stringResource(R.string.final_copy_60)
                    else -> stringResource(R.string.final_copy_61)
                },
                    onClick = { if (ownsSelection() && selection.isCurrent()) runtime.choose(candidate, browser) },
                    enabled = candidate.supported && !candidate.protected && runtime.canStartSave(candidate.method) && ownsSelection() && selection.isCurrent())
            }
        }
        EsvButton(stringResource(R.string.flow_savetaskscreen_32), onClick = { if (ownsSelection()) runtime.dismissSelection() })
    }
}
@Composable
private fun HlsSelectionDialog(runtime: AndroidSaveRuntime, request: Any) {
    val v = LocalEsvVisuals.current
    val prepared = runtime.hlsPreparation
    EsvAppDialog(request, { runtime.currentSelectionPresentationIdentity === request }, runtime::dismissSelection) {
        val admission = LocalPrivateInputAdmission.current
        fun ownsPreparation() = admission() && runtime.currentSelectionPresentationIdentity === request &&
            runtime.hlsPreparation === prepared
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.elevated, RoundedCornerShape(v.sheetCorner))
            .verticalScroll(rememberScrollState()).padding(v.spacingLarge), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText(stringResource(R.string.flow_savetaskscreen_26), style = v.titleTextStyle)
            EsvText(stringResource(R.string.flow_savetaskscreen_27), style = v.captionTextStyle)
            if (prepared == null) EsvText(stringResource(R.string.flow_savetaskscreen_28))
            else {
                if (runtime.alternativePrepared) EsvText(stringResource(R.string.save_alternative_ready), style = v.captionTextStyle)
                EsvText(stringResource(R.string.save_display_hls_budget, saveSizeText(prepared.bytesTransferred)), style = v.captionTextStyle)
                if (prepared.directMedia) EsvButton(stringResource(R.string.flow_savetaskscreen_30), onClick = { if (ownsPreparation()) runtime.chooseHlsVariant(null) },
                    enabled = ownsPreparation() && runtime.canStartSave(TaskMethod.STREAM))
                prepared.variants.forEach { variant ->
                    val metadata = variant.metadata
                    val width = metadata.width
                    val height = metadata.height
                    val size = if (width != null && height != null) saveResolutionText(width, height)
                        else stringResource(R.string.save_display_resolution_undeclared)
                    EsvButton(stringResource(R.string.save_display_hls_variant,
                        saveIntegerText(variant.index.toLong() + 1), size, saveIntegerText(metadata.bandwidth / 1000)),
                        onClick = { if (ownsPreparation()) runtime.chooseHlsVariant(variant.identity) },
                        enabled = ownsPreparation() && runtime.canStartSave(TaskMethod.STREAM))
                }
            }
            EsvButton(stringResource(R.string.flow_savetaskscreen_32), onClick = { if (ownsPreparation()) runtime.dismissSelection() })
        }
    }
}

internal fun saveMethodLabel(method: TaskMethod): String = when (method) {
    TaskMethod.BROWSER -> "浏览任务"
    TaskMethod.DIRECT -> "① 直接下载"
    TaskMethod.STREAM -> "② 流媒体"
    TaskMethod.RECORD -> "③ 录制"
    TaskMethod.EDIT -> "编辑任务"
}
internal fun saveExecutionLabel(method: TaskMethod): String = when (method) {
    TaskMethod.BROWSER -> "浏览处理中"
    TaskMethod.DIRECT -> "下载中"
    TaskMethod.STREAM -> "下载 / 封装中"
    TaskMethod.RECORD -> "录制会话处理中"
    TaskMethod.EDIT -> "编辑处理中"
}
internal fun saveArtifactProgressLabel(method: TaskMethod, size: String): String = when (method) {
    TaskMethod.DIRECT -> "已传输 $size，等待验证或提交。"
    TaskMethod.STREAM -> "已生成 $size 的独立 MP4，等待验证或提交。"
    TaskMethod.RECORD -> "已关闭 $size 的录制 MP4，等待验证或提交；尚未保存成功。"
    TaskMethod.BROWSER, TaskMethod.EDIT -> "已准备 $size，等待验证或提交。"
}

internal fun taskLabel(record: SaveRecord): String = when (record.task.state) {
    TaskState.QUEUED -> "排队中"
    TaskState.RUNNING -> when (record.phase) {
        SavePhase.PREPARING -> "准备中"
        SavePhase.EXECUTING -> saveExecutionLabel(record.request.method)
        SavePhase.NORMALIZING -> "本地格式规范化中"
        SavePhase.ADOPTING -> "准备验证规范化输出"
        SavePhase.VERIFYING -> "本地验证中"
        SavePhase.COMMITTING -> "提交中"
        SavePhase.CLEANUP -> "收尾中"
        SavePhase.CANCELLING -> "正在取消"
        else -> "处理中"
    }
    TaskState.WAITING_USER -> "等待操作"
    TaskState.INTERRUPTED -> "已中断"
    TaskState.SUCCEEDED -> "完成"
    TaskState.FAILED -> "失败"
    TaskState.CANCELLED -> "已取消"
}

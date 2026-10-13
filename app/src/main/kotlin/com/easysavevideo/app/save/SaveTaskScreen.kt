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
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.theme.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.recording.recordingAudioOutcomeLabel
import com.easysavevideo.app.recording.recordingDurationLabel
import com.easysavevideo.app.recording.recordingStopReasonLabel
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
) {
    val v = LocalEsvVisuals.current
    var showLimits by rememberSaveable { mutableStateOf(false) }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val headerScroll = rememberScrollState()
    val detailScroll = remember(selectedTaskId) { androidx.compose.foundation.ScrollState(0) }
    val detail = selectedTaskDetail(runtime.records, selectedTaskId)
    BackHandler(onBack = onBack)
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(v.spacingSmall), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(stringResource(R.string.flow_savetaskscreen_0), style = v.titleTextStyle)
                EsvButton(stringResource(R.string.flow_savetaskscreen_1), onClick = onBack)
            }
            onLibrary?.let { open -> EsvButton(stringResource(R.string.flow_savetaskscreen_2), onClick = open, modifier = Modifier.fillMaxWidth()) }
            onRecord?.let { open -> EsvButton(stringResource(R.string.flow_savetaskscreen_3), onClick = open, modifier = Modifier.fillMaxWidth()) }
            EsvText(stringResource(R.string.flow_savetaskscreen_4), style = v.captionTextStyle)
            EsvButton(stringResource(if (showLimits) R.string.flow_limits_hide else R.string.flow_limits_show),
                onClick = { showLimits = !showLimits })
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
                EsvText(text, color = v.colors.error)
                EsvButton(stringResource(R.string.flow_savetaskscreen_10), onClick = runtime::dismissNotice)
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
                  if (layout.split) Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium)) {
                    EsvText("${stringResource(taskMethodTextResource(record.request.method))} · ${stringResource(taskStateTextResource(record))}", style = v.bodyTextStyle)
                    EsvText(stringResource(R.string.flow_task_id, record.task.taskId.take(8)), style = v.captionTextStyle, color = v.colors.muted)
                  } else SaveTaskDetail(record, runtime, browser, onRecord, onPreview, onBrowser)
                  EsvButton(stringResource(if (record.task.taskId == selectedTaskId) R.string.task_detail_selected else R.string.task_detail_select),
                    onClick = { selectedTaskId = record.task.taskId }, modifier = Modifier.fillMaxWidth().semantics { selected = record.task.taskId == selectedTaskId })
                }
              }
            }
            if (layout.split) Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(detailScroll), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
              EsvText(stringResource(R.string.task_detail_heading), style = v.titleTextStyle)
              if (detail != null) SaveTaskDetail(detail, runtime, browser, onRecord, onPreview, onBrowser)
              else EsvText(stringResource(if (selectedTaskId == null) R.string.task_detail_empty else R.string.task_detail_unavailable))
              if (selectedTaskId != null) EsvButton(stringResource(R.string.task_detail_clear), onClick = { selectedTaskId = null })
            }
          }
        }
      }
    }
}

@Composable
private fun SaveTaskDetail(record: SaveRecord, runtime: AndroidSaveRuntime, browser: SystemBrowserHost,
    onRecord: (() -> Unit)?, onPreview: ((SaveRecord) -> Unit)?, onBrowser: (() -> Unit)?) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText("${stringResource(taskMethodTextResource(record.request.method))} · ${stringResource(taskStateTextResource(record))}", style = v.titleTextStyle)
        EsvText(stringResource(R.string.flow_task_id, record.task.taskId.take(8)), style = v.captionTextStyle, color = v.colors.muted)
        if (record.task.state == TaskState.SUCCEEDED) {
            runtime.discoverySaveOutcomes[record.task.taskId]?.let { outcome ->
                EsvText(mediaTaskDiscoveryOutcomeLabel(outcome), style = v.captionTextStyle, color = v.colors.muted)
            }
            val evidence = record.verification
            val disposition = record.receipt?.let { runtime.mediaDispositions[it.mediaId] }
            EsvText(when (disposition) {
                MediaLifecycle.READY -> stringResource(R.string.flow_disposition_ready)
                MediaLifecycle.DELETE_PENDING -> stringResource(R.string.flow_disposition_pending)
                MediaLifecycle.DELETED -> stringResource(R.string.flow_disposition_deleted)
                null -> stringResource(R.string.flow_disposition_unknown)
            })
            if (evidence != null) EsvText("${formatBytes(evidence.sizeBytes)} · ${evidence.width} × ${evidence.height} · ${evidence.durationMillis / 1000} 秒 · 音轨 ${evidence.audioTrackCount}", style = v.captionTextStyle)
            onPreview?.let { preview -> EsvButton(stringResource(R.string.flow_savetaskscreen_16), onClick = { preview(record) }, enabled = disposition == MediaLifecycle.READY && runtime.currentRecordingId == null && !runtime.draining) }
            EsvText(stringResource(R.string.flow_savetaskscreen_17), style = v.captionTextStyle, color = v.colors.muted)
        } else if (record.task.state in setOf(TaskState.RUNNING, TaskState.WAITING_USER, TaskState.QUEUED)) {
            val checkpoint = record.checkpoint
            val bytes = record.transfer?.bytesOnDisk
            EsvText(if (bytes != null) {
                    stringResource(R.string.flow_output_pending, formatBytes(bytes))
                }
                else if (checkpoint != null) stringResource(R.string.flow_transfer_progress, formatBytes(checkpoint.offset), formatBytes(checkpoint.totalBytes))
                else if (record.request.method == TaskMethod.RECORD) stringResource(R.string.flow_record_progress)
                else stringResource(R.string.flow_progress_unknown), style = v.captionTextStyle)
        }
        record.transfer?.recordingExpectation?.let { recording ->
            EsvText("有效录制时长 ${recordingDurationLabel(recording.effectiveDurationUs)} · ${recording.actualProfile.width} × ${recording.actualProfile.height}", style = v.captionTextStyle)
            EsvText(recordingAudioOutcomeLabel(recording.audioOutcome), style = v.captionTextStyle)
            EsvText(recordingStopReasonLabel(recording.stopReason), style = v.captionTextStyle)
        }
        if (record.request.method == TaskMethod.RECORD && !record.task.state.isTerminal) onRecord?.let { open ->
            EsvButton(stringResource(R.string.flow_savetaskscreen_19), onClick = open)
        }
        record.error?.let { EsvText(stringResource(saveFailureTextResource(it)), color = v.colors.error, style = v.captionTextStyle) }
        SaveFailureGuidance(record, runtime, onBrowser)
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
                (record.request.method != TaskMethod.RECORD || record.transfer != null)) EsvButton(stringResource(if (record.request.method == TaskMethod.RECORD) R.string.flow_reverify else R.string.flow_resume), onClick = { runtime.resume(record.task.taskId, browser) },
                enabled = !runtime.draining && record.task.taskId !in runtime.pendingCommands)
            if (!record.task.state.isTerminal) EsvButton(stringResource(R.string.flow_savetaskscreen_21), onClick = { runtime.cancel(record.task.taskId) },
                enabled = record.task.taskId !in runtime.pendingCommands)
        }
    }
}

@Composable
fun SaveSelectionDialog(runtime: AndroidSaveRuntime, browser: SystemBrowserHost) {
    if (runtime.preparingStream || runtime.hlsPreparation != null) {
        HlsSelectionDialog(runtime)
        return
    }
    if (runtime.selection == null) return
    Dialog(onDismissRequest = runtime::dismissSelection) {
        SaveSelectionPanel(runtime, browser, Modifier.heightIn(max = 680.dp))
    }

}
@Composable
fun SaveSelectionPanel(runtime: AndroidSaveRuntime, browser: SystemBrowserHost, modifier: Modifier = Modifier) {
    val v = LocalEsvVisuals.current
    val selection = runtime.selection ?: return
    Column(modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner))
        .verticalScroll(rememberScrollState()).padding(v.spacingLarge), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
        EsvText(stringResource(R.string.flow_savetaskscreen_22), style = v.titleTextStyle)
        EsvText(stringResource(R.string.flow_savetaskscreen_23), style = v.captionTextStyle)
        EsvText(stringResource(R.string.flow_savetaskscreen_24), style = v.captionTextStyle)
        selection.candidates.forEach { candidate ->
            Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvText(candidate.label)
                EsvText(buildList {
                    add(URI(candidate.url.value).host ?: stringResource(R.string.final_copy_54))
                    add(if (candidate.width != null && candidate.height != null) "${candidate.width} × ${candidate.height}" else stringResource(R.string.final_copy_55))
                    add(candidate.durationMillis?.let { "${it / 1000} 秒" } ?: stringResource(R.string.final_copy_56))
                }.joinToString(" · "), style = v.captionTextStyle, color = v.colors.muted)
                EsvButton(when {
                    candidate.protected -> stringResource(R.string.final_copy_57)
                    !candidate.supported -> stringResource(R.string.final_copy_58)
                    !runtime.canSave(candidate.method) -> stringResource(R.string.final_copy_59)
                    candidate.method == TaskMethod.STREAM -> stringResource(R.string.final_copy_60)
                    else -> stringResource(R.string.final_copy_61)
                },
                    onClick = { runtime.choose(candidate, browser) },
                    enabled = candidate.supported && !candidate.protected && runtime.canSave(candidate.method) && selection.isCurrent())
            }
        }
        EsvButton(stringResource(R.string.flow_savetaskscreen_32), onClick = runtime::dismissSelection)
    }
}
@Composable
private fun HlsSelectionDialog(runtime: AndroidSaveRuntime) {
    val v = LocalEsvVisuals.current
    val prepared = runtime.hlsPreparation
    Dialog(onDismissRequest = runtime::dismissSelection) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner))
            .verticalScroll(rememberScrollState()).padding(v.spacingLarge), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
            EsvText(stringResource(R.string.flow_savetaskscreen_26), style = v.titleTextStyle)
            EsvText(stringResource(R.string.flow_savetaskscreen_27), style = v.captionTextStyle)
            if (prepared == null) EsvText(stringResource(R.string.flow_savetaskscreen_28))
            else {
                EsvText("已检查清单 ${formatBytes(prepared.bytesTransferred)}；这部分流量计入任务预算。选择有效期最多 60 秒，不延长原执行时限。", style = v.captionTextStyle)
                if (prepared.directMedia) EsvButton(stringResource(R.string.flow_savetaskscreen_30), onClick = { runtime.chooseHlsVariant(null) },
                    enabled = runtime.canSave(TaskMethod.STREAM))
                prepared.variants.forEach { variant ->
                    val metadata = variant.metadata
                    val size = if (metadata.width != null && metadata.height != null) "${metadata.width} × ${metadata.height}" else "清晰度未声明"
                    EsvButton("${variant.index + 1}. $size · ${metadata.bandwidth / 1000} kbps", onClick = { runtime.chooseHlsVariant(variant.identity) },
                        enabled = runtime.canSave(TaskMethod.STREAM))
                }
            }
            EsvButton(stringResource(R.string.flow_savetaskscreen_32), onClick = runtime::dismissSelection)
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
internal fun saveErrorText(error: SaveError): String = when (error) {
    SaveError.WIFI_REQUIRED -> "正在等待已确认的默认 Wi-Fi 网络。连接 Wi-Fi 或关闭“仅通过 Wi-Fi 保存”后手动重试。"
    SaveError.CANCELLED -> "任务已取消，不会自动换用其他保存方式。"
    SaveError.AUTHENTICATION_REQUIRED -> "资源需要有效的登录会话或访问权限。"
    SaveError.STORAGE_FULL -> "可用存储空间不足。"
    SaveError.STORAGE_FAILURE, SaveError.JOURNAL_UNAVAILABLE -> "私有存储暂时不可用，未清除已有媒体。"
    SaveError.NETWORK_TIMEOUT, SaveError.DEADLINE_EXCEEDED -> "操作超时，迟到结果不会提交。"
    SaveError.NETWORK_UNAVAILABLE, SaveError.RETRY_EXHAUSTED -> "网络未能在有限重试内完成，请稍后重试。"
    SaveError.TLS_REJECTED -> "无法验证网站安全连接，未绕过证书检查。"
    SaveError.POLICY_BLOCKED -> "资源地址或连接不符合安全限制。"
    SaveError.BYTE_LIMIT_EXCEEDED -> "已达到本开发版本的文件或累计传输预算。"
    SaveError.PROTECTED_MEDIA -> "检测到受保护内容，不支持保存。"
    SaveError.UNSUPPORTED_MEDIA, SaveError.METHOD_UNSUPPORTED -> "该媒体或保存方式不符合本开发版本的兼容范围。网络保存不支持直播、加密、分轨、格式变化或转码。"
    SaveError.INVALID_MEDIA, SaveError.INCOMPLETE_TRANSFER -> "文件不完整或未通过真实媒体验证，没有作为成功视频入库。"
    SaveError.INVALID_RANGE, SaveError.RESOURCE_CHANGED -> "源资源或断点身份不一致，没有拼接不匹配的字节。"
    SaveError.EXPIRED_RESOURCE -> "候选资源已过期，请回到网页重新检测。"
    SaveError.RESOURCE_RELEASE_UNCONFIRMED -> "系统资源尚未确认退出，任务文件保留，迟到结果不会提交。"
    SaveError.VERIFICATION_UNAVAILABLE -> "系统媒体校验资源尚未可用，请稍后重试。"
    SaveError.COMMIT_MISMATCH, SaveError.RECOVERY_REQUIRED -> "提交状态需要重新核验，文件已保留供恢复。"
    SaveError.RATE_LIMITED -> "网站限制了请求，请稍后重试。"
    SaveError.SOURCE_RESELECTION_REQUIRED -> "短期来源上下文已清除。请回网页重新检测并选择；新任务从头下载。"
    SaveError.HTTP_NOT_FOUND -> "资源已不存在（404），请回网页重新选择。"
    SaveError.HTTP_REJECTED -> "网站拒绝了此资源请求，未自动换用其他保存方式。"
    SaveError.HTTP_FAILURE -> "网站未返回可保存的完整媒体。"
    SaveError.BUSY -> "已有任务处理中，请稍后再试。"
    SaveError.CONSENT_DENIED -> "未获得本次系统录制授权，没有开始录制。"
    SaveError.AUDIO_PERMISSION_DENIED -> "内部声音权限未允许；必须明确选择无声音重新开始，或取消。"
    SaveError.CAPTURE_UNAVAILABLE, SaveError.FGS_START_REJECTED -> "系统暂时无法开始本次录制；不会重复使用旧授权。"
    SaveError.PROFILE_UNSUPPORTED -> "设备编码器不支持本次录制配置。"
    SaveError.CAPTURE_EMPTY, SaveError.CAPTURE_TOO_SHORT -> "有效录制样本为空或太短，未发布成片。"
    SaveError.KEYFRAME_TIMEOUT -> "等待录制关键帧超时，未确认完整成片。"
    SaveError.TIMESTAMP_INVALID -> "录制媒体时间线不符合要求，未发布成片。"
    SaveError.AUDIO_FAILED -> "内部播放音频采集失败；没有自动改用麦克风或无声音。"
    SaveError.ENCODER_FAILED -> "系统录制编码器失败，未确认保存成功。"
    SaveError.FINALIZATION_FAILED -> "录制文件未完整收尾，未发布成片。"
    else -> "任务未完成，请检查页面与存储后重试。"
}
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KiB"
}

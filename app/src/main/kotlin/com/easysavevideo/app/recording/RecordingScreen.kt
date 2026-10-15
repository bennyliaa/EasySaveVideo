package com.easysavevideo.app.recording

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.theme.*
import com.easysavevideo.app.recording.ui.*
import com.easysavevideo.recording.*
import java.util.Locale

/** UI observes the service-owned capture; it never owns projection, encoder, or output resources. */
@Composable
fun RecordingScreen(
    snapshot: RecordingSnapshot?,
    effectiveDurationUs: Long?,
    consent: RecordingConsentController,
    canStart: Boolean,
    canPauseResume: Boolean,
    onStart: (RecordingAudioChoice) -> String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
    notice: String? = null,
    regionSetup: RecordingRegionSetup? = null,
    onStartRegion: ((RecordingAudioChoice, RegionCaptureRecipe) -> String?)? = null,
    segments: RecordingSegmentsPresentation? = null,
    onRetrySegments: (() -> Unit)? = null,
    onDiscardSegments: (() -> Unit)? = null,
    onOpenVerifiedEditor: (() -> Unit)? = null,
    retainedSessionCount: Int = 0,
    onNextSegments: (() -> Unit)? = null,
    onStartAuthorizedPreview: ((RecordingAudioChoice) -> String?)? = null,
    initialSelectionMode: RecordingSelectionMode = RecordingSelectionMode.BASIC,
    onSelectionModeChange: (RecordingSelectionMode) -> Unit = {},
    onCreationSelectionChanged: () -> Unit = {},
) {
    val v = LocalEsvVisuals.current
    var audioChoice by remember { mutableStateOf<RecordingAudioChoice?>(null) }
    var mode by remember(initialSelectionMode) { mutableStateOf(initialSelectionMode) }
    var selectedRecipe by remember { mutableStateOf<RegionCaptureRecipe?>(null) }
    var editingSetup by remember { mutableStateOf<RecordingRegionSetup?>(null) }
    val active = segments?.active ?: (snapshot?.state?.let(::recordingHasActiveSession) == true)
    val selectionCurrent = recordingSelectionIsCurrent(selectedRecipe, regionSetup)
    val advancedMode = mode != RecordingSelectionMode.BASIC
    val previewMode = mode == RecordingSelectionMode.AUTHORIZED_FRAME
    val startAllowed = when (mode) {
        RecordingSelectionMode.BASIC -> canStart
        RecordingSelectionMode.PROPORTIONAL -> regionSetup?.canStart == true && onStartRegion != null && selectionCurrent
        RecordingSelectionMode.AUTHORIZED_FRAME -> regionSetup?.canStart == true && onStartAuthorizedPreview != null
    }
    val showingAdvanced = advancedMode || segments?.active == true
    fun selectMode(next: RecordingSelectionMode) { onCreationSelectionChanged(); mode = next; onSelectionModeChange(next) }
    fun leave() { if (consent.requestInFlight || consent.audioPermissionDenied) consent.cancel(); onBack() }
    fun begin(choice: RecordingAudioChoice) {
        if (!startAllowed || consent.requestInFlight || active) return
        if (previewMode) {
            // The host returns to the browser and waits for its visible draw before consent.
            // Starting consent here would capture this recording-control page instead.
            onStartAuthorizedPreview?.invoke(choice)
            return
        }
        val request = if (advancedMode) selectedRecipe?.takeIf { recordingSelectionIsCurrent(it, regionSetup) }
            ?.let { onStartRegion?.invoke(choice, it) } else onStart(choice)
        request?.let { consent.begin(it, choice == RecordingAudioChoice.INTERNAL) }
    }
    BackHandler(onBack = ::leave)
    EsvBackdrop(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
                EsvText(stringResource(R.string.advanced_copy_11), style = v.titleTextStyle)
                EsvButton(stringResource(R.string.advanced_copy_12), onClick = ::leave)
                Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner))
                    .padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText(stringResource(R.string.advanced_copy_13), style = v.titleTextStyle)
                    if (showingAdvanced) EsvText(stringResource(R.string.final_copy_65))
                    EsvText(if (showingAdvanced) stringResource(R.string.final_copy_66)
                        else stringResource(R.string.private_record_disclosure), style = v.captionTextStyle, color = v.colors.muted)
                    if (!showingAdvanced && android.os.Build.VERSION.SDK_INT <= 32)
                        EsvText(stringResource(R.string.private_record_legacy_recents), style = v.captionTextStyle)
                    if (showingAdvanced) {
                        EsvText(stringResource(R.string.final_copy_68), style = v.captionTextStyle)
                        EsvText(if (previewMode) recordingPreviewScopeDisclosure else stringResource(R.string.final_copy_69), style = v.captionTextStyle)
                        EsvText(stringResource(R.string.final_copy_70), style = v.captionTextStyle, color = v.colors.muted)
                    }
                    EsvText(stringResource(R.string.final_copy_71), style = v.captionTextStyle, color = v.colors.muted)
                    EsvText(stringResource(R.string.final_copy_72), style = v.captionTextStyle, color = v.colors.muted)
                    EsvText(stringResource(R.string.final_copy_73), style = v.captionTextStyle, color = v.colors.muted)
                    EsvText(if (showingAdvanced) "开发版本：高级录制会话从开始起最多 2 小时（含暂停），单次暂停最多 10 分钟；当前合成输出有效总时长最多 30 分钟（不含暂停）。私有片段合计最多 2 GiB，单片段最多 2 GiB。最多 32 次真正恢复录入（初始片段加最多 32 个后续片段）。设备与真实权限验收尚未执行。"
                        else stringResource(R.string.private_record_limits), style = v.captionTextStyle, color = v.colors.muted)
                    if (!showingAdvanced) EsvText(stringResource(R.string.private_record_verification_help), style = v.captionTextStyle, color = v.colors.muted)
                    if (showingAdvanced) EsvText(stringResource(R.string.final_copy_74), style = v.captionTextStyle, color = v.colors.muted)
                }
                if (!active && !consent.requestInFlight) {
                    if (regionSetup != null) {
                        EsvText(stringResource(R.string.advanced_copy_14), style = v.titleTextStyle)
                        EsvButton((if (!advancedMode) stringResource(R.string.final_copy_75) else "") + stringResource(R.string.final_copy_76), { selectMode(RecordingSelectionMode.BASIC) }, modifier = Modifier.fillMaxWidth())
                        EsvButton((if (mode == RecordingSelectionMode.PROPORTIONAL) stringResource(R.string.final_copy_75) else "") + stringResource(R.string.final_copy_77), { selectMode(RecordingSelectionMode.PROPORTIONAL) }, modifier = Modifier.fillMaxWidth())
                        if (onStartAuthorizedPreview != null) EsvButton(
                            (if (previewMode) stringResource(R.string.final_copy_75) else "") + stringResource(R.string.final_copy_78),
                            { selectMode(RecordingSelectionMode.AUTHORIZED_FRAME) }, modifier = Modifier.fillMaxWidth())
                        if (advancedMode) {
                            EsvText(stringResource(R.string.final_copy_79), style = v.captionTextStyle, color = v.colors.muted)
                            regionSetup.unavailableReason?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
                            if (previewMode) {
                                EsvText(recordingPreviewScopeDisclosure, style = v.captionTextStyle)
                                EsvText(stringResource(R.string.final_copy_80), style = v.captionTextStyle)
                            } else {
                                EsvButton(if (selectionCurrent) stringResource(R.string.final_copy_81) else stringResource(R.string.final_copy_82), { editingSetup = regionSetup },
                                    enabled = regionSetup.geometry != null && regionSetup.scopeEvidence != null, modifier = Modifier.fillMaxWidth())
                                selectedRecipe?.let { selected ->
                                    EsvText(recordingRegionSummary(selected.bounds), style = v.captionTextStyle)
                                    EsvText(recordingEffectiveCropSummary(selected), style = v.captionTextStyle)
                                    if (!selectionCurrent) EsvText(stringResource(R.string.final_copy_83), color = v.colors.error, style = v.captionTextStyle)
                                } ?: EsvText(stringResource(R.string.final_copy_84), style = v.captionTextStyle, color = v.colors.muted)
                            }
                        }
                    }
                    EsvText(stringResource(R.string.advanced_copy_15), style = v.titleTextStyle)
                    EsvButton(if (audioChoice == RecordingAudioChoice.INTERNAL) stringResource(R.string.final_copy_85) else stringResource(R.string.final_copy_86),
                        onClick = { onCreationSelectionChanged(); audioChoice = RecordingAudioChoice.INTERNAL }, modifier = Modifier.fillMaxWidth())
                    EsvButton(if (audioChoice == RecordingAudioChoice.NONE) stringResource(R.string.final_copy_87) else stringResource(R.string.final_copy_88),
                        onClick = { onCreationSelectionChanged(); audioChoice = RecordingAudioChoice.NONE }, modifier = Modifier.fillMaxWidth())
                    if (!(if (advancedMode) regionSetup?.canStart == true else canStart)) EsvText(stringResource(R.string.advanced_copy_16), style = v.captionTextStyle, color = v.colors.muted)
                    if (!consent.audioPermissionDenied) EsvButton(if (previewMode) stringResource(R.string.final_copy_89) else stringResource(R.string.final_copy_90), onClick = { audioChoice?.let(::begin) },
                        enabled = startAllowed && audioChoice != null, modifier = Modifier.fillMaxWidth())
                }
                if (consent.requestInFlight) {
                    EsvText(if (consent.waitingForAudio) stringResource(R.string.final_copy_91) else stringResource(R.string.final_copy_92))
                    EsvButton(stringResource(R.string.advanced_copy_17), onClick = consent::cancel)
                }
                if (consent.audioPermissionDenied) {
                    EsvText(stringResource(R.string.advanced_copy_18), color = v.colors.error)
                    EsvButton("改为无声音，重新申请${recordingSelectionModeLabel(mode)}", onClick = { onCreationSelectionChanged(); audioChoice = RecordingAudioChoice.NONE; begin(RecordingAudioChoice.NONE) },
                        enabled = startAllowed && !consent.requestInFlight)
                    EsvButton(stringResource(R.string.advanced_copy_19), onClick = consent::cancel)
                }
                consent.notice?.let { EsvDiagnosticNotice(it) }
                notice?.let { EsvDiagnosticNotice(it) }
                segments?.let { current ->
                    RecordingSegmentsCard(current, canPauseResume, onPause, onResume, onStopAndSave, onDiscard,
                        onRetrySegments, onDiscardSegments, onOpenVerifiedEditor, retainedSessionCount, onNextSegments)
                }
                if (segments == null) snapshot?.let { current ->
                    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner))
                        .padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText(stringResource(recordingStateResource(current.state)), style = v.titleTextStyle)
                        EsvText(stringResource(R.string.advanced_copy_20), style = v.captionTextStyle)
                        current.actualProfile?.let { profile ->
                            EsvText("实际编码画布 ${profile.width} × ${profile.height} · ${profile.frameRate} fps 配置", style = v.captionTextStyle)
                        }
                        EsvText(effectiveDurationUs?.let { "有效录制时长 ${recordingDurationLabel(it)}（不含暂停）" }
                            ?: stringResource(R.string.final_copy_93), style = v.captionTextStyle)
                        EsvText(stringResource(recordingAudioStatusResource(current.audioStatus)), style = v.captionTextStyle)
                        EsvText("有效恢复 ${current.effectiveResumeCount} / 32 次（按实际录入的恢复关键帧计数）", style = v.captionTextStyle)
                        if (current.effectiveResumeCount >= 28) EsvText(
                            if (current.effectiveResumeCount == 32) stringResource(R.string.final_copy_94)
                            else "接近恢复上限，还可真正恢复 ${32 - current.effectiveResumeCount} 次。", color = v.colors.error, style = v.captionTextStyle)
                        (current.outcome as? RecordingOutcome.Completed)?.expectation?.let {
                            EsvText(stringResource(recordingAudioOutcomeResource(it.audioOutcome)), style = v.captionTextStyle)
                        }
                        current.stopReason?.let { EsvText(stringResource(recordingStopReasonResource(it)), style = v.captionTextStyle) }
                        current.error?.let { EsvText(stringResource(recordingErrorResource(it)), style = v.captionTextStyle, color = v.colors.error) }
                        if (current.state == RecordingState.RECORDING) EsvButton(stringResource(R.string.advanced_copy_21), onClick = onPause, enabled = canPauseResume)
                        if (current.state == RecordingState.PAUSED) {
                            if (current.effectiveResumeCount >= 32) EsvButton(stringResource(R.string.advanced_copy_22), onClick = onStopAndSave)
                            else EsvButton(stringResource(R.string.advanced_copy_23), onClick = onResume, enabled = canPauseResume)
                        }
                        if (current.state in setOf(RecordingState.RECORDING, RecordingState.PAUSED) && !canPauseResume)
                            EsvText(stringResource(R.string.final_copy_95), style = v.captionTextStyle, color = v.colors.muted)
                        if (recordingCanStop(current.state)) EsvButton(stringResource(R.string.advanced_copy_24), onClick = onStopAndSave, modifier = Modifier.fillMaxWidth())
                        if (active && current.state != RecordingState.CANCELLING) EsvButton(stringResource(R.string.advanced_copy_25), onClick = onDiscard, modifier = Modifier.fillMaxWidth())
                        if (current.state == RecordingState.CAPTURE_FINISHED)
                            EsvText(stringResource(R.string.advanced_copy_26), style = v.captionTextStyle)
                    }
                }
            }
        }
    }
    editingSetup?.let { opened ->
        RecordingRegionDialog(opened, regionSetup,
            initialBounds = selectedRecipe?.takeIf { it.source == opened.geometry }?.bounds ?: RegionNormalizedRect.FULL,
            enabled = !active && !consent.requestInFlight,
            onConfirm = { selected ->
                if (!active && !consent.requestInFlight && recordingSelectionIsCurrent(selected, regionSetup)) { onCreationSelectionChanged(); selectedRecipe = selected }
                editingSetup = null
            }, onCancel = { editingSetup = null })
    }
}

internal fun recordingHasActiveSession(state: RecordingState): Boolean = state in setOf(
    RecordingState.PREPARING, RecordingState.RECORDING, RecordingState.PAUSED,
    RecordingState.RESUMING, RecordingState.STOPPING, RecordingState.FINALIZING,
    RecordingState.CANCELLING,
)
internal fun recordingCanStop(state: RecordingState): Boolean = state in setOf(
    RecordingState.PREPARING, RecordingState.RECORDING, RecordingState.PAUSED, RecordingState.RESUMING,
)
internal fun recordingDurationLabel(durationUs: Long): String {
    val seconds = durationUs.coerceAtLeast(0) / 1_000_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
internal fun recordingStateLabel(state: RecordingState): String = when (state) {
    RecordingState.IDLE -> "未开始"
    RecordingState.AWAITING_USER_CONFIRMATION -> "等待本次确认"
    RecordingState.AWAITING_AUDIO_PERMISSION -> "等待声音权限"
    RecordingState.AWAITING_SYSTEM_CONSENT -> "等待系统授权"
    RecordingState.PREPARING -> "准备录制，尚未确认首帧"
    RecordingState.RECORDING -> "录制中"
    RecordingState.PAUSED -> "已暂停录入，会话仍在运行"
    RecordingState.RESUMING -> "等待关键帧后继续"
    RecordingState.STOPPING -> "正在停止录入"
    RecordingState.FINALIZING -> "正在完成 MP4 并释放资源"
    RecordingState.CANCELLING -> "正在放弃并释放资源"
    RecordingState.CAPTURE_FINISHED -> "捕获已结束，尚未确认保存"
    RecordingState.CANCELLED -> "已放弃录制"
    RecordingState.FAILED -> "录制失败，未保存成功"
    RecordingState.RECOVERY_REQUIRED -> "资源或文件等待恢复检查"
}
internal fun recordingAudioStatusLabel(status: RecordingAudioStatus): String = when (status) {
    RecordingAudioStatus.NOT_REQUESTED -> "声音：本次选择无声音"
    RecordingAudioStatus.AWAITING_PERMISSION -> "声音：等待权限，尚未采集"
    RecordingAudioStatus.PERMISSION_DENIED -> "声音：权限未允许，需选择无声音或取消"
    RecordingAudioStatus.INITIALIZING -> "声音：内部播放捕获准备中"
    RecordingAudioStatus.PCM_OBSERVED -> "声音：收到内部音频数据，不保证目标内容有声"
    RecordingAudioStatus.SILENCE_OR_UNKNOWN -> "声音：静音或无法确认有声"
    RecordingAudioStatus.INTERRUPTED -> "声音：内部播放捕获已中断"
}
internal fun recordingAudioOutcomeLabel(outcome: RecordingAudioOutcome): String = when (outcome) {
    RecordingAudioOutcome.NO_AUDIO_REQUESTED -> "实际声音结果：无音轨（本次选择无声音）"
    RecordingAudioOutcome.INTERNAL_AUDIO_CAPTURED -> "实际声音结果：已写入内部音轨；请预览核对内容"
    RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN -> "实际声音结果：内部音轨静音或声音内容未知"
    RecordingAudioOutcome.INTERNAL_AUDIO_INTERRUPTED -> "实际声音结果：内部音频曾中断，成片可能缺少声音"
}
internal fun recordingStopReasonLabel(reason: RecordingStopReason): String = when (reason) {
    RecordingStopReason.USER_STOP -> "结束原因：手动停止"
    RecordingStopReason.SYSTEM_STOPPED -> "结束原因：系统结束共享"
    RecordingStopReason.SIZE_CHANGED -> "结束原因：捕获尺寸变化"
    RecordingStopReason.SESSION_LIMIT -> "结束原因：达到会话时限"
    RecordingStopReason.PAUSE_LIMIT -> "结束原因：达到暂停时限"
    RecordingStopReason.RESUME_LIMIT -> "结束原因：达到 32 次有效恢复上限，已停止新增录入"
    RecordingStopReason.BYTE_LIMIT -> "结束原因：达到文件大小限制"
    RecordingStopReason.STORAGE_LOW -> "结束原因：可用存储空间不足"
    RecordingStopReason.AUDIO_LOST -> "结束原因：内部音频中断"
    RecordingStopReason.ENCODER_FAILED -> "结束原因：编码器故障"
    RecordingStopReason.USER_CANCELLED -> "结束原因：主动放弃，不保存"
    RecordingStopReason.CONSENT_DENIED -> "结束原因：系统授权未允许"
    RecordingStopReason.AUDIO_PERMISSION_DENIED -> "结束原因：声音权限未允许"
    RecordingStopReason.ENTITLEMENT_REVOKED -> "结束原因：当前操作权益不可用"
    RecordingStopReason.DEADLINE_EXCEEDED -> "结束原因：操作超时"
    RecordingStopReason.PROCESS_INTERRUPTED -> "结束原因：上次进程中断，不会自动续录"
}
internal fun recordingErrorLabel(error: RecordingError): String = when (error) {
    RecordingError.ENTITLEMENT_REQUIRED -> "当前录制操作需要有效权益；仍可停止或放弃。"
    RecordingError.CONSENT_DENIED -> "未获得本次系统录制授权。"
    RecordingError.AUDIO_PERMISSION_DENIED -> "内部声音权限未允许；需明确选择无声音重新开始或取消。"
    RecordingError.CAPTURE_UNAVAILABLE, RecordingError.FGS_START_REJECTED -> "系统暂时无法开始本次录制；不会重复使用旧授权。"
    RecordingError.PROFILE_UNSUPPORTED -> "设备编码器不支持本次录制配置。"
    RecordingError.CAPTURE_EMPTY, RecordingError.CAPTURE_TOO_SHORT -> "没有足够的有效视频样本，未保存成片。"
    RecordingError.RESOURCE_RELEASE_UNCONFIRMED -> "系统资源尚未确认关闭；文件保留，不能开始新的录制。"
    RecordingError.STORAGE_LOW -> "可用存储空间不足，已停止录入。"
    RecordingError.BYTE_LIMIT -> "达到开发版本的文件大小限制。"
    RecordingError.AUDIO_FAILED -> "内部播放声音采集失败，未自动改用麦克风或静音模式。"
    RecordingError.DEADLINE_EXCEEDED -> "录制操作超时，迟到结果不会当作成功保存。"
    RecordingError.CANCELLED -> "本次录制已放弃，不会发布成片。"
    RecordingError.BUSY -> "已有录制或媒体资源尚在处理中。"
    RecordingError.INVALID_REQUEST, RecordingError.STALE_SESSION, RecordingError.STALE_REVISION,
    RecordingError.INVALID_STATE -> "本次操作已过期或不适用于当前会话。"
    RecordingError.KEYFRAME_TIMEOUT, RecordingError.TIMESTAMP_INVALID, RecordingError.CLOCK_INVALID,
    RecordingError.FORMAT_TIMEOUT, RecordingError.FORMAT_CHANGED, RecordingError.SAMPLE_INVALID,
    RecordingError.SAMPLE_LIMIT, RecordingError.BUFFER_LIMIT, RecordingError.ENCODER_FAILED,
    RecordingError.FINALIZATION_FAILED -> "录制媒体未满足完整性要求；只有后续验证通过才会保存成功。"
}

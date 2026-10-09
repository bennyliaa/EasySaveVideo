package com.easysavevideo.app.recording

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
                EsvText("③ 录制", style = v.titleTextStyle)
                EsvButton("返回保存任务", onClick = ::leave)
                Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner))
                    .padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText("每次录制都由你在系统页面授权", style = v.titleTextStyle)
                    EsvText("整屏录制可能包含当前网页、其他应用和通知。切换应用或标签会改变画面。请先隐藏敏感内容，并确认你有权录制。")
                    EsvText(if (showingAdvanced) "高级录制请求整个默认显示屏。请核对系统确认页的共享范围；部分设备可能覆盖这一请求。本应用不能仅凭尺寸确认应用或内容身份，不承诺单应用区域录制。"
                        else "范围由系统确认页选择；本应用无法可靠识别最终范围时会显示未知。系统允许时可选择单个应用。这里不会承诺捕获隐藏标签或后台网页。", style = v.captionTextStyle, color = v.colors.muted)
                    if (showingAdvanced) {
                        EsvText("系统捕获的完整内容会短暂经过内存 / GPU；只将确认后的选区编码为私有片段。没有整屏临时录制文件，也不会在裁切失败时回退保存整屏。", style = v.captionTextStyle)
                        EsvText(if (previewMode) recordingPreviewScopeDisclosure else "比例示意模式的选区只定义比例，不绑定应用、网页或先前屏幕；将对稍后系统授权的完整画面按这些坐标裁切，请核对系统授权范围。", style = v.captionTextStyle)
                        EsvText("选区基于完整显示屏，包含系统栏坐标。尺寸、旋转或边距变化会安全停止，需要重新选区和授权。控制条是否进入选区内画面尚未实机验证，不承诺自动排除。", style = v.captionTextStyle, color = v.colors.muted)
                    }
                    EsvText("仅支持系统允许的内部播放声音，不使用麦克风。音频可能来自其他允许捕获的应用；视频范围选择不保证相同的音频范围。来源策略、静音或系统限制可能使成片无声。", style = v.captionTextStyle, color = v.colors.muted)
                    EsvText("尊重安全窗口和受保护内容限制，不绕过保护。黑屏或静音不代表已识别 DRM。", style = v.captionTextStyle, color = v.colors.muted)
                    EsvText("录制使用独立的前台服务，离开此页不会停止已开始的会话。可回到这里或使用系统录制通知停止。通知若未获允许，仍可在系统的活动应用入口结束服务。", style = v.captionTextStyle, color = v.colors.muted)
                    EsvText(if (showingAdvanced) "开发版本：高级录制会话从开始起最多 2 小时（含暂停），单次暂停最多 10 分钟；当前合成输出有效总时长最多 30 分钟（不含暂停）。私有片段合计最多 2 GiB，单片段最多 2 GiB。最多 32 次真正恢复录入（初始片段加最多 32 个后续片段）。设备与真实权限验收尚未执行。"
                        else "开发版本：会话资源上限 2 小时、单次暂停最多 10 分钟、单文件最多 2 GiB；这些不是已经实测的可保存时长。每会话最多 32 次真正恢复录入，超出时安全停止并尝试保存已录部分。尺寸变化或系统结束共享会触发安全收尾。会检查全部样本结构，并实际解码首、中、末及每次恢复关键帧所在的画面组。校验最多 60 秒，较长成片可能超时而不发布；不保证每帧均已解码。设备与真实权限验收尚未执行。", style = v.captionTextStyle, color = v.colors.muted)
                    if (showingAdvanced) EsvText("配置帧率不是实际帧率保证。观测到的可变帧率或不兼容片段可能无法进入当前 M6-A 固定帧率编辑流程；处理失败保留私有片段供恢复检查，不会假报成功。只有支持且验证入库的视频才能二次裁切、压缩或预览。", style = v.captionTextStyle, color = v.colors.muted)
                }
                if (!active && !consent.requestInFlight) {
                    if (regionSetup != null) {
                        EsvText("录制方式", style = v.titleTextStyle)
                        EsvButton((if (!advancedMode) "已选：" else "") + "基础录制（范围由系统选择）", { selectMode(RecordingSelectionMode.BASIC) }, modifier = Modifier.fillMaxWidth())
                        EsvButton((if (mode == RecordingSelectionMode.PROPORTIONAL) "已选：" else "") + "高级录制：比例示意选区（无画面预览）", { selectMode(RecordingSelectionMode.PROPORTIONAL) }, modifier = Modifier.fillMaxWidth())
                        if (onStartAuthorizedPreview != null) EsvButton(
                            (if (previewMode) "已选：" else "") + "高级录制：授权帧选区（当前 ESV 浏览器）",
                            { selectMode(RecordingSelectionMode.AUTHORIZED_FRAME) }, modifier = Modifier.fillMaxWidth())
                        if (advancedMode) {
                            EsvText("高级录制同时需要基础录制与区域录制权益；暂停 / 继续另需分段权益。停止和放弃始终可用。", style = v.captionTextStyle, color = v.colors.muted)
                            regionSetup.unavailableReason?.let { EsvText(it, style = v.captionTextStyle, color = v.colors.error) }
                            if (previewMode) {
                                EsvText(recordingPreviewScopeDisclosure, style = v.captionTextStyle)
                                EsvText("下一步将先返回当前 ESV 浏览器，再申请本次系统授权。授权后只展示一张冻结画面，等你核对来源并确认选区；这时尚未录制。黑屏、画面错误或不想继续时可以取消，不会改用比例示意图。", style = v.captionTextStyle)
                            } else {
                                EsvButton(if (selectionCurrent) "调整已确认的比例选区" else "选择 / 重新选择比例范围", { editingSetup = regionSetup },
                                    enabled = regionSetup.geometry != null && regionSetup.scopeEvidence != null, modifier = Modifier.fillMaxWidth())
                                selectedRecipe?.let { selected ->
                                    EsvText(recordingRegionSummary(selected.bounds), style = v.captionTextStyle)
                                    EsvText(recordingEffectiveCropSummary(selected), style = v.captionTextStyle)
                                    if (!selectionCurrent) EsvText("此选区的显示几何已失效，请重新选择。", color = v.colors.error, style = v.captionTextStyle)
                                } ?: EsvText("先确认输出选区，也可在选择器中选择全屏；全屏仍须满足当前处理尺寸限制。", style = v.captionTextStyle, color = v.colors.muted)
                            }
                        }
                    }
                    EsvText("请选择本次声音来源", style = v.titleTextStyle)
                    EsvButton(if (audioChoice == RecordingAudioChoice.INTERNAL) "已选：内部声音（需允许）" else "内部声音（需允许）",
                        onClick = { onCreationSelectionChanged(); audioChoice = RecordingAudioChoice.INTERNAL }, modifier = Modifier.fillMaxWidth())
                    EsvButton(if (audioChoice == RecordingAudioChoice.NONE) "已选：无声音" else "无声音",
                        onClick = { onCreationSelectionChanged(); audioChoice = RecordingAudioChoice.NONE }, modifier = Modifier.fillMaxWidth())
                    if (!(if (advancedMode) regionSetup?.canStart == true else canStart)) EsvText("开始录制需要相应权益，且存储与录制资源必须就绪。本开发版本没有真实购买入口。", style = v.captionTextStyle, color = v.colors.muted)
                    if (!consent.audioPermissionDenied) EsvButton(if (previewMode) "我已了解，返回浏览器并申请授权帧" else "我已了解，申请系统录制授权", onClick = { audioChoice?.let(::begin) },
                        enabled = startAllowed && audioChoice != null, modifier = Modifier.fillMaxWidth())
                }
                if (consent.requestInFlight) {
                    EsvText(if (consent.waitingForAudio) "等待内部声音权限选择，尚未录制。" else "等待本次系统授权结果，尚未录制。")
                    EsvButton("放弃本次授权请求", onClick = consent::cancel)
                }
                if (consent.audioPermissionDenied) {
                    EsvText("内部声音权限未允许。不会自动改为无声音。", color = v.colors.error)
                    EsvButton("改为无声音，重新申请${recordingSelectionModeLabel(mode)}", onClick = { onCreationSelectionChanged(); audioChoice = RecordingAudioChoice.NONE; begin(RecordingAudioChoice.NONE) },
                        enabled = startAllowed && !consent.requestInFlight)
                    EsvButton("取消", onClick = consent::cancel)
                }
                consent.notice?.let { EsvText(it, color = v.colors.error, style = v.captionTextStyle) }
                notice?.let { EsvText(it, color = v.colors.error, style = v.captionTextStyle) }
                segments?.let { current ->
                    RecordingSegmentsCard(current, canPauseResume, onPause, onResume, onStopAndSave, onDiscard,
                        onRetrySegments, onDiscardSegments, onOpenVerifiedEditor, retainedSessionCount, onNextSegments)
                }
                if (segments == null) snapshot?.let { current ->
                    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner))
                        .padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText(recordingStateLabel(current.state), style = v.titleTextStyle)
                        EsvText("系统选择的捕获范围：未知", style = v.captionTextStyle)
                        current.actualProfile?.let { profile ->
                            EsvText("实际编码画布 ${profile.width} × ${profile.height} · ${profile.frameRate} fps 配置", style = v.captionTextStyle)
                        }
                        EsvText(effectiveDurationUs?.let { "有效录制时长 ${recordingDurationLabel(it)}（不含暂停）" }
                            ?: "有效录制时长等待实际样本确认", style = v.captionTextStyle)
                        EsvText(recordingAudioStatusLabel(current.audioStatus), style = v.captionTextStyle)
                        EsvText("有效恢复 ${current.effectiveResumeCount} / 32 次（按实际录入的恢复关键帧计数）", style = v.captionTextStyle)
                        if (current.effectiveResumeCount >= 28) EsvText(
                            if (current.effectiveResumeCount == 32) "已达到恢复上限。再次继续将安全停止并尝试保存已录部分，不会继续录入。"
                            else "接近恢复上限，还可真正恢复 ${32 - current.effectiveResumeCount} 次。", color = v.colors.error, style = v.captionTextStyle)
                        (current.outcome as? RecordingOutcome.Completed)?.expectation?.let {
                            EsvText(recordingAudioOutcomeLabel(it.audioOutcome), style = v.captionTextStyle)
                        }
                        current.stopReason?.let { EsvText(recordingStopReasonLabel(it), style = v.captionTextStyle) }
                        current.error?.let { EsvText(recordingErrorLabel(it), style = v.captionTextStyle, color = v.colors.error) }
                        if (current.state == RecordingState.RECORDING) EsvButton("暂停", onClick = onPause, enabled = canPauseResume)
                        if (current.state == RecordingState.PAUSED) {
                            if (current.effectiveResumeCount >= 32) EsvButton("已达恢复上限，停止并保存", onClick = onStopAndSave)
                            else EsvButton("继续录制", onClick = onResume, enabled = canPauseResume)
                        }
                        if (current.state in setOf(RecordingState.RECORDING, RecordingState.PAUSED) && !canPauseResume)
                            EsvText("暂停 / 继续按现有组合权益检查；开始录制权益本身不保证这两个操作可用。停止和放弃始终可用。", style = v.captionTextStyle, color = v.colors.muted)
                        if (recordingCanStop(current.state)) EsvButton("停止并验证保存", onClick = onStopAndSave, modifier = Modifier.fillMaxWidth())
                        if (active && current.state != RecordingState.CANCELLING) EsvButton("放弃本次录制，不保存", onClick = onDiscard, modifier = Modifier.fillMaxWidth())
                        if (current.state == RecordingState.CAPTURE_FINISHED)
                            EsvText("捕获已关闭；仍需本地校验、提交和发布。请在保存任务查看结果。", style = v.captionTextStyle)
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

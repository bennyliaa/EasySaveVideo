package com.easysavevideo.app.recording.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.easysavevideo.app.recording.recordingAudioStatusResource
import com.easysavevideo.app.recording.recordingSegmentsPhaseResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.easysavevideo.app.recording.recordingDurationLabel
import com.easysavevideo.app.theme.*
import com.easysavevideo.recording.MAX_RECORDING_RESUME_BOUNDARIES
import java.util.Locale

@Composable
fun RecordingSegmentsCard(
    state: RecordingSegmentsPresentation,
    canPauseResume: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit,
    onRetrySegments: (() -> Unit)?,
    onDiscardSegments: (() -> Unit)?,
    onOpenVerifiedEditor: (() -> Unit)?,
    retainedSessionCount: Int = 0,
    onNextSegments: (() -> Unit)? = null,
) {
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        val phaseLabel = stringResource(recordingSegmentsPhaseResource(state.phase, state.verifiedOutputAvailable))
        EsvText(phaseLabel, style = v.titleTextStyle, modifier = Modifier.semantics { stateDescription = phaseLabel })
        if (retainedSessionCount > 0) EsvText("共有 $retainedSessionCount 组待查看的私有片段。", style = v.captionTextStyle)
        if ((retainedSessionCount > 1 || retainedSessionCount > 0 && state.closedSegments.isEmpty()) && !state.active && onNextSegments != null)
            EsvButton("下一组保留片段", onNextSegments, modifier = Modifier.fillMaxWidth())
        EsvText("请求的系统捕获范围：整个默认显示屏。实际应用 / 内容身份无法仅凭画面尺寸确认。", style = v.captionTextStyle)
        state.recipe?.let {
            EsvText(recordingRegionSummary(it.bounds), style = v.captionTextStyle)
            EsvText(recordingEffectiveCropSummary(it), style = v.captionTextStyle)
        }
        EsvText("有效录制时长 ${recordingDurationLabel(state.effectiveDurationUs)}（仅实际录入，不含暂停）", style = v.captionTextStyle)
        EsvText(stringResource(recordingAudioStatusResource(state.audioStatus)), style = v.captionTextStyle)
        EsvText("有效恢复 ${state.effectiveResumeCount} / $MAX_RECORDING_RESUME_BOUNDARIES 次（按实际录入确认）", style = v.captionTextStyle)
        if (state.effectiveResumeCount >= 28) EsvText(
            if (state.effectiveResumeCount >= MAX_RECORDING_RESUME_BOUNDARIES) "已达到恢复上限，请停止并保存已录片段。"
            else "还可真正恢复 ${MAX_RECORDING_RESUME_BOUNDARIES - state.effectiveResumeCount} 次。", style = v.captionTextStyle, color = v.colors.error)
        EsvText("已关闭片段 · 按录制顺序", style = v.titleTextStyle)
        if (state.closedSegments.isEmpty()) EsvText("尚无已关闭片段。当前录入不代表文件已完成或已保存。", style = v.captionTextStyle, color = v.colors.muted)
        state.closedSegments.forEach { segment ->
            val seconds = String.format(Locale.ROOT, "%.3f", segment.effectiveDurationUs / 1_000_000.0)
            val status = when {
                !segment.retained -> "私有原片段已清理"
                segment.cleanupPending -> "私有原片段仍待确认清理"
                segment.verified -> "私有片段验证通过"
                else -> "待独立验证"
            }
            EsvText("片段 ${segment.ordinal + 1} · 有效 $seconds 秒 · $status", style = v.captionTextStyle)
        }
        EsvText("只有合成结果通过独立验证并提交媒体库才算保存成功。零散私有片段不会作为成功成片展示、预览或分享。", style = v.captionTextStyle, color = v.colors.muted)
        state.notice?.let { EsvDiagnosticNotice(it) }
        if (state.phase == RecordingSegmentsPhase.RECORDING) EsvButton("暂停并关闭当前片段", onPause,
            enabled = canPauseResume, modifier = Modifier.fillMaxWidth())
        if (state.phase == RecordingSegmentsPhase.PAUSED && state.effectiveResumeCount < MAX_RECORDING_RESUME_BOUNDARIES)
            EsvButton("继续录制下一片段", onResume, enabled = canPauseResume, modifier = Modifier.fillMaxWidth())
        if (state.phase in setOf(RecordingSegmentsPhase.RECORDING, RecordingSegmentsPhase.PAUSED) && !canPauseResume)
            EsvText("暂停 / 继续需要分段录制组合权益；停止和放弃仍可使用。", style = v.captionTextStyle, color = v.colors.muted)
        if (state.canStop) EsvButton("停止并合成保存", onStopAndSave, modifier = Modifier.fillMaxWidth())
        if (state.phase in setOf(RecordingSegmentsPhase.PROCESSING, RecordingSegmentsPhase.VERIFYING))
            EsvText("处理期间保留私有片段；失败时可进行恢复检查，不会重新请求或复用录屏授权。", style = v.captionTextStyle, color = v.colors.muted)
        if (state.phase in setOf(RecordingSegmentsPhase.RECOVERY_REQUIRED, RecordingSegmentsPhase.FAILED)) {
            EsvText("已关闭且仍受本会话管理的私有片段保留供恢复检查。系统录屏授权不能恢复；重试只处理现有片段，不会自动续录。", style = v.captionTextStyle, color = v.colors.muted)
            EsvText("可变帧率或不兼容片段可能不受当前编辑流程支持，重试不保证成功。你可以保留片段，或明确放弃并清理。", style = v.captionTextStyle, color = v.colors.muted)
            if (state.canRetryProcessing && onRetrySegments != null) EsvButton("重试验证与合成", onRetrySegments, modifier = Modifier.fillMaxWidth())
            if (state.canDiscard)
                EsvButton("放弃恢复并清理本会话私有片段", onDiscardSegments ?: onDiscard, modifier = Modifier.fillMaxWidth())
        } else if (state.canDiscard) EsvButton("取消并放弃本次录制，不保存", onDiscard, modifier = Modifier.fillMaxWidth())
        if (state.canDiscard) EsvText("放弃会撤销保存并在资源确认关闭后清理本会话临时片段；资源状态不明确时等待恢复检查。文件清理不等于安全物理擦除。", style = v.captionTextStyle, color = v.colors.muted)
        if (state.canCleanRetainedSources && onDiscardSegments != null) {
            EsvButton("清理已保留的原片段（保留成片）", onDiscardSegments, modifier = Modifier.fillMaxWidth())
            EsvText("只清理本会话保留的私有原片段，已发布的媒体库成片保留。文件删除不代表安全物理擦除。", style = v.captionTextStyle, color = v.colors.muted)
        }
        if (state.verifiedOutputAvailable && onOpenVerifiedEditor != null) {
            EsvButton("打开已保存视频，二次裁切 / 压缩", onOpenVerifiedEditor, modifier = Modifier.fillMaxWidth())
            EsvText("编辑只接受支持且验证通过的媒体库输入，原件保留到新结果验证提交。可选原质量或节省空间；不是必须重复转码。", style = v.captionTextStyle, color = v.colors.muted)
        }
    }
}

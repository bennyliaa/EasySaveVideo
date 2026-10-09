package com.easysavevideo.app.recording.ui

import com.easysavevideo.recording.*
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Current default-display observation, not a cached Activity viewport or a system grant. */
data class RecordingRegionSetup(
    val geometry: CaptureDisplayGeometry?,
    val scopeEvidence: CaptureScopeEvidence?,
    val canStart: Boolean = false,
    val unavailableReason: String? = null,
)

enum class RecordingRegionHandle { MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** A closed private file is not a published library result. Ordinals are zero based. */
data class RecordingSegmentRow(val ordinal: Int, val effectiveDurationUs: Long, val verified: Boolean,
    val retained: Boolean = true, val cleanupPending: Boolean = false) {
    init { require(ordinal in 0..MAX_RECORDING_RESUME_BOUNDARIES); require(effectiveDurationUs in 1..(24L * 60 * 60 * 1_000_000)) }
}

enum class RecordingSegmentsPhase {
    PREPARING, RECORDING, PAUSING, PAUSED, RESUMING, STOPPING, PROCESSING, VERIFYING,
    SUCCEEDED, RECOVERY_REQUIRED, CANCELLING, CANCELLED, FAILED,
}

/** Presentation is detached from mutable manifests and has no file, grant or native owner. */
class RecordingSegmentsPresentation(
    val phase: RecordingSegmentsPhase,
    closedSegments: List<RecordingSegmentRow> = emptyList(),
    val effectiveDurationUs: Long = closedSegments.sumOf { it.effectiveDurationUs },
    val effectiveResumeCount: Int = 0,
    val recipe: RegionCaptureRecipe? = null,
    val audioStatus: RecordingAudioStatus = RecordingAudioStatus.NOT_REQUESTED,
    val notice: String? = null,
    val canRetryProcessing: Boolean = false,
    val verifiedOutputAvailable: Boolean = false,
) {
    val closedSegments: List<RecordingSegmentRow> = Collections.unmodifiableList(ArrayList(closedSegments))
    init {
        require(this.closedSegments.size <= MAX_RECORDING_RESUME_BOUNDARIES + 1)
        require(this.closedSegments.withIndex().all { (index, row) -> row.ordinal == index })
        require(effectiveDurationUs >= 0 && effectiveDurationUs <= 24L * 60 * 60 * 1_000_000)
        require(this.closedSegments.sumOf { it.effectiveDurationUs } <= effectiveDurationUs)
        require(effectiveResumeCount in 0..MAX_RECORDING_RESUME_BOUNDARIES)
        require(!verifiedOutputAvailable || phase == RecordingSegmentsPhase.SUCCEEDED)
        require(!canRetryProcessing || phase in setOf(RecordingSegmentsPhase.RECOVERY_REQUIRED, RecordingSegmentsPhase.FAILED))
    }
    val active: Boolean get() = phase in setOf(RecordingSegmentsPhase.PREPARING, RecordingSegmentsPhase.RECORDING,
        RecordingSegmentsPhase.PAUSING, RecordingSegmentsPhase.PAUSED, RecordingSegmentsPhase.RESUMING,
        RecordingSegmentsPhase.STOPPING, RecordingSegmentsPhase.PROCESSING, RecordingSegmentsPhase.VERIFYING,
        RecordingSegmentsPhase.CANCELLING)
    val canStop: Boolean get() = phase in setOf(RecordingSegmentsPhase.PREPARING, RecordingSegmentsPhase.RECORDING,
        RecordingSegmentsPhase.PAUSING, RecordingSegmentsPhase.PAUSED, RecordingSegmentsPhase.RESUMING)
    val canDiscard: Boolean get() = phase !in setOf(RecordingSegmentsPhase.SUCCEEDED,
        RecordingSegmentsPhase.CANCELLED, RecordingSegmentsPhase.CANCELLING)
    /** Source cleanup is separate from cancellation and never revokes a published result. */
    val canCleanRetainedSources: Boolean get() = phase == RecordingSegmentsPhase.SUCCEEDED && closedSegments.any { it.retained }
    override fun toString() = "RecordingSegmentsPresentation(phase=$phase, segments=${closedSegments.size}, [redacted])"
}

/** M6-A's supported processing subset is checked before requesting system consent. No scaling fallback. */
fun recordingRegionError(bounds: RegionNormalizedRect, setup: RecordingRegionSetup): String? {
    val geometry = setup.geometry ?: return "尚未获得默认显示屏尺寸，不能确认选区。"
    val evidence = setup.scopeEvidence ?: return "当前默认显示屏授权方式尚未确认。"
    val recipe = try { RegionCaptureRecipe(bounds, geometry, evidence) } catch (_: IllegalArgumentException) {
        return "选区向内对齐后须至少为 128 × 128 像素，且符合编码尺寸上限。请调整选区。"
    }
    val pixels = recipe.pixelCrop
    return if (max(pixels.width, pixels.height) > 1920 || pixels.width.toLong() * pixels.height > 1920L * 1080)
        "此选区超出当前处理范围：最长边 1920 像素，总像素不超过 1920 × 1080。请缩小选区；不会自动缩放或改录整屏。" else null
}

fun recordingSelectionIsCurrent(recipe: RegionCaptureRecipe?, setup: RecordingRegionSetup?): Boolean =
    recipe != null && setup != null && recipe.source == setup.geometry && recipe.scopeEvidence == setup.scopeEvidence &&
        recordingRegionError(recipe.bounds, setup) == null

/** The full display is fit-centered. Insets are annotations; they are never deducted from coordinates. */
data class RecordingViewport(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
}

fun recordingViewport(width: Double, height: Double, geometry: CaptureDisplayGeometry): RecordingViewport {
    require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
    val scale = minOf(width / geometry.width, height / geometry.height)
    val displayWidth = geometry.width * scale
    val displayHeight = geometry.height * scale
    return RecordingViewport((width - displayWidth) / 2, (height - displayHeight) / 2, displayWidth, displayHeight)
}

fun recordingRegionHit(bounds: RegionNormalizedRect, x: Double, y: Double, slopX: Double, slopY: Double): RecordingRegionHandle? {
    if (listOf(x, y, slopX, slopY).any { !it.isFinite() } || slopX <= 0 || slopY <= 0 || x !in 0.0..1.0 || y !in 0.0..1.0) return null
    val centerX = (bounds.left + bounds.right) / 2
    val centerY = (bounds.top + bounds.bottom) / 2
    val handles = listOf(
        Triple(RecordingRegionHandle.TOP_LEFT, bounds.left, bounds.top), Triple(RecordingRegionHandle.TOP_RIGHT, bounds.right, bounds.top),
        Triple(RecordingRegionHandle.BOTTOM_LEFT, bounds.left, bounds.bottom), Triple(RecordingRegionHandle.BOTTOM_RIGHT, bounds.right, bounds.bottom),
        Triple(RecordingRegionHandle.LEFT, bounds.left, centerY), Triple(RecordingRegionHandle.RIGHT, bounds.right, centerY),
        Triple(RecordingRegionHandle.TOP, centerX, bounds.top), Triple(RecordingRegionHandle.BOTTOM, centerX, bounds.bottom),
    )
    val near = handles.filter { abs(x - it.second) <= slopX && abs(y - it.third) <= slopY }
        .minByOrNull { val dx = (x - it.second) / slopX; val dy = (y - it.third) / slopY; dx * dx + dy * dy }
    if (near != null) return near.first
    // An entire edge can be dragged, not only its midpoint marker.
    if (y in bounds.top..bounds.bottom) {
        if (abs(x - bounds.left) <= slopX) return RecordingRegionHandle.LEFT
        if (abs(x - bounds.right) <= slopX) return RecordingRegionHandle.RIGHT
    }
    if (x in bounds.left..bounds.right) {
        if (abs(y - bounds.top) <= slopY) return RecordingRegionHandle.TOP
        if (abs(y - bounds.bottom) <= slopY) return RecordingRegionHandle.BOTTOM
    }
    return RecordingRegionHandle.MOVE.takeIf { x in bounds.left..bounds.right && y in bounds.top..bounds.bottom }
}

fun dragRecordingRegion(bounds: RegionNormalizedRect, handle: RecordingRegionHandle, dx: Double, dy: Double): RegionNormalizedRect {
    if (!dx.isFinite() || !dy.isFinite()) return bounds
    if (handle == RecordingRegionHandle.MOVE) {
        val x = dx.coerceIn(-bounds.left, 1.0 - bounds.right)
        val y = dy.coerceIn(-bounds.top, 1.0 - bounds.bottom)
        return RegionNormalizedRect((bounds.left + x).coerceIn(0.0, 1.0), (bounds.top + y).coerceIn(0.0, 1.0),
            (bounds.right + x).coerceIn(0.0, 1.0), (bounds.bottom + y).coerceIn(0.0, 1.0))
    }
    val minimum = 0.02
    val left = if (handle in setOf(RecordingRegionHandle.LEFT, RecordingRegionHandle.TOP_LEFT, RecordingRegionHandle.BOTTOM_LEFT))
        (bounds.left + dx).coerceIn(0.0, (bounds.right - minimum).coerceAtLeast(0.0)) else bounds.left
    val top = if (handle in setOf(RecordingRegionHandle.TOP, RecordingRegionHandle.TOP_LEFT, RecordingRegionHandle.TOP_RIGHT))
        (bounds.top + dy).coerceIn(0.0, (bounds.bottom - minimum).coerceAtLeast(0.0)) else bounds.top
    val right = if (handle in setOf(RecordingRegionHandle.RIGHT, RecordingRegionHandle.TOP_RIGHT, RecordingRegionHandle.BOTTOM_RIGHT))
        (bounds.right + dx).coerceIn((left + minimum).coerceAtMost(1.0), 1.0) else bounds.right
    val bottom = if (handle in setOf(RecordingRegionHandle.BOTTOM, RecordingRegionHandle.BOTTOM_LEFT, RecordingRegionHandle.BOTTOM_RIGHT))
        (bounds.bottom + dy).coerceIn((top + minimum).coerceAtMost(1.0), 1.0) else bounds.bottom
    return RegionNormalizedRect(left, top, right, bottom)
}

fun recordingRegionSummary(bounds: RegionNormalizedRect): String =
    String.format(Locale.ROOT, "所选范围：左 %.1f%% · 上 %.1f%% · 右 %.1f%% · 下 %.1f%%", bounds.left * 100, bounds.top * 100, bounds.right * 100, bounds.bottom * 100)

fun recordingEffectiveCropSummary(recipe: RegionCaptureRecipe): String = recipe.pixelCrop.let {
    "向内偶数像素对齐后：左 ${it.left} · 上 ${it.top} · 右 ${it.right} · 下 ${it.bottom}（右 / 下边界不含）· ${it.width} × ${it.height} 像素"
}

fun recordingSegmentsPhaseLabel(phase: RecordingSegmentsPhase): String = when (phase) {
    RecordingSegmentsPhase.PREPARING -> "准备分段录制，尚未确认首帧"
    RecordingSegmentsPhase.RECORDING -> "分段录制中"
    RecordingSegmentsPhase.PAUSING -> "正在关闭当前片段"
    RecordingSegmentsPhase.PAUSED -> "已暂停，暂停时间不进入成片"
    RecordingSegmentsPhase.RESUMING -> "正在准备下一片段"
    RecordingSegmentsPhase.STOPPING -> "正在停止并关闭片段"
    RecordingSegmentsPhase.PROCESSING -> "正在按录制顺序合成视频"
    RecordingSegmentsPhase.VERIFYING -> "正在验证合成结果，尚未保存成功"
    RecordingSegmentsPhase.SUCCEEDED -> "已验证并保存到媒体库"
    RecordingSegmentsPhase.RECOVERY_REQUIRED -> "需要恢复检查，保存状态待核对"
    RecordingSegmentsPhase.CANCELLING -> "正在放弃，等待资源关闭"
    RecordingSegmentsPhase.CANCELLED -> "已放弃，不保存成片"
    RecordingSegmentsPhase.FAILED -> "处理未完成，尚未保存成片"
}

/** A repeated control/deadline may not hide an already visible uncertain-owner warning. */
fun recordingPreserveWaitingPhase(current: RecordingSegmentsPhase?, requested: RecordingSegmentsPhase, sameOwner: Boolean = true): RecordingSegmentsPhase =
    if (sameOwner && current == RecordingSegmentsPhase.RECOVERY_REQUIRED && requested !in setOf(
            RecordingSegmentsPhase.SUCCEEDED, RecordingSegmentsPhase.FAILED, RecordingSegmentsPhase.CANCELLED))
        RecordingSegmentsPhase.RECOVERY_REQUIRED else requested

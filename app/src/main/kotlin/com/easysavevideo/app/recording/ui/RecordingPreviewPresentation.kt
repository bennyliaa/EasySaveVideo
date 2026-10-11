package com.easysavevideo.app.recording.ui

import com.easysavevideo.recording.RegionNormalizedRect
import com.easysavevideo.recording.RegionPreviewSnapshot

enum class RecordingSelectionMode { BASIC, PROPORTIONAL, AUTHORIZED_FRAME }

internal fun recordingSelectionModeLabel(mode: RecordingSelectionMode): String = when (mode) {
    RecordingSelectionMode.BASIC -> "基础录制"
    RecordingSelectionMode.PROPORTIONAL -> "比例示意录制"
    RecordingSelectionMode.AUTHORIZED_FRAME -> "授权帧选区"
}

internal const val recordingPreviewScopeDisclosure =
    "授权帧模式仅用于当前可见的 ESV 浏览器：展示本次系统授权流中的一张冻结画面，不是实时预览，也不支持预览任意其他应用。尺寸相同不能证明来源正确，请亲自核对画面。"

internal fun recordingPreviewSelectionError(snapshot: RegionPreviewSnapshot, bounds: RegionNormalizedRect, nowUs: Long): String? =
    recordingPreviewProblem(snapshot, bounds, nowUs)?.chinese

internal fun recordingPreviewProblem(
    snapshot: RegionPreviewSnapshot,
    bounds: RegionNormalizedRect,
    nowUs: Long,
): RecordingRegionProblem? = when {
    nowUs < snapshot.capturedAtMonotonicUs || nowUs >= snapshot.expiresAtMonotonicUs ->
        RecordingRegionProblem.FRAME_EXPIRED
    else -> recordingRegionProblem(bounds, RecordingRegionSetup(
        snapshot.observation.geometry, snapshot.observation.scopeEvidence,
    ))
}

/** Local click/disposal deduplication only. The runtime independently authenticates every callback. */
internal class RecordingPreviewUiFence(private val snapshot: RegionPreviewSnapshot) {
    private enum class Phase { OPEN, CONFIRMED, CANCELLED, RETIRED }
    private var phase = Phase.OPEN

    fun confirm(token: RegionPreviewSnapshot, bounds: RegionNormalizedRect, nowUs: Long,
        enabled: Boolean, drawingAvailable: Boolean): Boolean {
        if (phase != Phase.OPEN || token != snapshot || !enabled || !drawingAvailable ||
            recordingPreviewSelectionError(token, bounds, nowUs) != null) return false
        phase = Phase.CONFIRMED
        return true
    }

    /** Backgrounding may revoke even an accepted click until actual retirement. */
    fun cancel(): Boolean {
        if (phase in setOf(Phase.CANCELLED, Phase.RETIRED)) return false
        phase = Phase.CANCELLED
        return true
    }

    fun retire(): Boolean {
        if (phase == Phase.RETIRED) return false
        phase = Phase.RETIRED
        return true
    }
}

/** Main-thread owned-window focus lifetime, separate from Activity lifecycle and host readiness. */
internal class RecordingPreviewWindowFocusFence {
    private var focusedOnce = false
    private var revoked = false
    private var retired = false
    val canUseWindow: Boolean get() = !revoked && !retired

    /** True requests immediate masking/cancellation exactly once. */
    fun onFocusChanged(hasFocus: Boolean, intentionallyRetiring: Boolean): Boolean {
        if (!canUseWindow || intentionallyRetiring) return false
        if (hasFocus) {
            focusedOnce = true
            return false
        }
        if (!focusedOnce) return false
        revoked = true
        return true
    }

    fun retire() { retired = true }
}

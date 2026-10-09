package com.easysavevideo.recording

import kotlin.math.abs

/** Memory-only engineering bounds, not Android/GPU capability or device acceptance evidence. */
object RegionPreviewLimits {
    const val MAX_RASTER_DIMENSION = 1280
    const val MAX_RASTER_PIXELS = 921_600L
    const val MAX_RASTER_BYTES = MAX_RASTER_PIXELS * 4
    const val FIRST_FRAME_TIMEOUT_US = 15_000_000L
    const val SNAPSHOT_LIFETIME_US = 60_000_000L
    const val UI_RETIREMENT_TIMEOUT_US = 5_000_000L
}

/**
 * Exact, immutable copy of the observed producer transform. This is not an assertion that the
 * transform is supported: the native adapter must also apply its full-image transform policy.
 * Neither a transform nor matching raster/display dimensions identifies the selected app/content.
 */
class RegionPreviewTransform(values: FloatArray) {
    private val elements = values.copyOf()
    init { require(elements.size == 16 && elements.all { it.isFinite() }) }
    fun copyValues(): FloatArray = elements.copyOf()
    fun sameAs(values: FloatArray): Boolean = elements.contentEquals(values)
    override fun equals(other: Any?): Boolean = other is RegionPreviewTransform && elements.contentEquals(other.elements)
    override fun hashCode(): Int = elements.contentHashCode()
}

/**
 * Metadata of one actually latched frame, with no pixels, durable path, consent token, or source
 * identity claim. Pixels remain in an Android-owned memory lease. The complete value is the
 * confirmation token: session/generation/sequence alone cannot substitute different raster,
 * observation or transform metadata. Coordinates describe the complete display-oriented raster.
 * Raster scaling is for preview only; a recipe always addresses the original observed source.
 */
data class RegionPreviewSnapshot(
    val session: RecordingSessionId,
    val geometryGeneration: Long,
    val frameSequence: Long,
    val observation: CaptureGeometryObservation,
    val rasterWidth: Int,
    val rasterHeight: Int,
    val sourceTransform: RegionPreviewTransform,
    val sourceFrameTimestampNs: Long,
    val capturedAtMonotonicUs: Long,
    val expiresAtMonotonicUs: Long,
) {
    init {
        require(geometryGeneration > 0 && frameSequence > 0)
        require(rasterWidth in 1..RegionPreviewLimits.MAX_RASTER_DIMENSION)
        require(rasterHeight in 1..RegionPreviewLimits.MAX_RASTER_DIMENSION)
        require(rasterWidth.toLong() * rasterHeight <= RegionPreviewLimits.MAX_RASTER_PIXELS)
        require(rasterWidth <= observation.geometry.width && rasterHeight <= observation.geometry.height)
        // Independent integer rounding may change the preview aspect by at most one raster pixel.
        require(abs(rasterWidth.toLong() * observation.geometry.height -
            rasterHeight.toLong() * observation.geometry.width) <=
            maxOf(observation.geometry.width, observation.geometry.height))
        require(observation.geometry.transformVersion == RegionCaptureLimits.TRANSFORM_VERSION)
        require(when (observation.scopeEvidence) {
            CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY ->
                observation.provenance == CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_SURFACE
            CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY ->
                observation.provenance == CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK
        })
        require(capturedAtMonotonicUs >= observation.observedAtMonotonicUs)
        require(sourceFrameTimestampNs > 0 && sourceFrameTimestampNs / 1000 <= capturedAtMonotonicUs)
        require(expiresAtMonotonicUs > capturedAtMonotonicUs &&
            expiresAtMonotonicUs - capturedAtMonotonicUs <= RegionPreviewLimits.SNAPSHOT_LIFETIME_US)
    }

    /**
     * Revalidates the live capture-session geometry/transform binding, not content identity.
     * Ordinary video motion produces later frames/observation times without changing the frozen
     * snapshot the user is selecting. Frame sequence and observation timestamp deliberately do
     * not participate here. A changed binding requires invalidation, never selection remapping.
     * Native source admission and preview expiry/cancellation remain separately required.
     */
    fun matchesSource(
        session: RecordingSessionId,
        geometryGeneration: Long,
        actualObservation: CaptureGeometryObservation,
        actualTransform: FloatArray,
    ): Boolean = this.session == session && this.geometryGeneration == geometryGeneration &&
        observation.geometry == actualObservation.geometry &&
        observation.scopeEvidence == actualObservation.scopeEvidence &&
        observation.provenance == actualObservation.provenance && sourceTransform.sameAs(actualTransform)
}

enum class RegionPreviewPhase {
    WAITING_FOR_HOST, WAITING_FOR_FRAME, VISIBLE, CONFIRMED, WAITING_FOR_CAPTURE_READY, READY_TO_ENCODE, ADMITTED, INVALIDATED,
}

enum class RegionPreviewInvalidation {
    CANCELLED, BACKGROUNDED, SCREEN_LOCKED, GEOMETRY_CHANGED, TRANSFORM_CHANGED, SYSTEM_STOPPED,
    HOST_DISAPPEARED, FIRST_FRAME_TIMEOUT, SNAPSHOT_EXPIRED, UI_RETIREMENT_TIMEOUT, CLOCK_INVALID,
    RESOURCE_RELEASED,
}

/**
 * Issued only by the gate after explicit selection and actual UI retirement. This is not reusable
 * projection authority. The adapter still owns cancellation, one-consent/one-VD lifetime, actual
 * source/transform revalidation, and rejecting frames preceding captureReadyAtMonotonicUs.
 */
class RegionPreviewConfirmation internal constructor(
    val snapshot: RegionPreviewSnapshot,
    val recipe: RegionCaptureRecipe,
    val confirmedAtMonotonicUs: Long,
    val uiRetiredAtMonotonicUs: Long,
    val captureReadyAtMonotonicUs: Long,
)

/**
 * Small, pure, one-shot publication/selection fence. All methods only mutate bounded memory;
 * never invoke UI/native work under this monitor. All times use the same monotonic clock as the
 * source frame timestamp. An invalidated gate is terminal; a new attempt needs a new gate and
 * new capture authority. Rejected old-session callbacks do not replace the current snapshot.
 * Callers sample time immediately before invoking a method. Cross-thread preemption can reverse
 * arrival order, so deadlines and accepted event times use a monotonic high-water mark. An older
 * call never rewinds time, renews an existing deadline or revives an invalidated gate.
 *
 * No file, codec, audio capture or encoding may start until takeReady succeeds. A permission or
 * consent callback is not host readiness, snapshot confirmation or UI retirement evidence.
 */
class RegionPreviewGate(
    val session: RecordingSessionId,
    val geometryGeneration: Long,
    val openedAtMonotonicUs: Long,
) {
    init { require(geometryGeneration > 0 && openedAtMonotonicUs >= 0) }
    private var currentPhase = RegionPreviewPhase.WAITING_FOR_HOST
    private var currentSnapshot: RegionPreviewSnapshot? = null
    private var currentConfirmation: RegionPreviewConfirmation? = null
    private var selectedRecipe: RegionCaptureRecipe? = null
    private var confirmedAt: Long? = null
    private var uiRetiredAt: Long? = null
    private var hostReadyAt: Long? = null
    private var lastObservedAt = openedAtMonotonicUs
    private var invalidation: RegionPreviewInvalidation? = null

    val phase: RegionPreviewPhase @Synchronized get() = currentPhase
    val snapshot: RegionPreviewSnapshot? @Synchronized get() = currentSnapshot
    val confirmation: RegionPreviewConfirmation? @Synchronized get() = currentConfirmation
    val hostReadyAtMonotonicUs: Long? @Synchronized get() = hostReadyAt
    val isInvalidated: Boolean @Synchronized get() = currentPhase == RegionPreviewPhase.INVALIDATED
    val invalidationReason: RegionPreviewInvalidation? @Synchronized get() = invalidation

    /** Call only after the capture host is visible and its readiness frame has actually drawn. */
    @Synchronized fun markHostReady(nowUs: Long): Boolean {
        if (!advance(nowUs) || currentPhase != RegionPreviewPhase.WAITING_FOR_HOST) return false
        hostReadyAt = lastObservedAt
        currentPhase = RegionPreviewPhase.WAITING_FOR_FRAME
        return true
    }

    /**
     * A latched pre-readiness frame cannot be republished merely with a newer acquisition time.
     * Require the following microsecond too, because floor-converting the readiness clock must
     * not admit a source frame from just before that readiness event in the same clock tick.
     */
    @Synchronized fun publish(snapshot: RegionPreviewSnapshot, nowUs: Long): Boolean {
        if (!advance(nowUs) || currentPhase != RegionPreviewPhase.WAITING_FOR_FRAME) return false
        val ready = hostReadyAt ?: return false
        if (snapshot.session != session || snapshot.geometryGeneration != geometryGeneration ||
            snapshot.observation.observedAtMonotonicUs < openedAtMonotonicUs ||
            snapshot.sourceFrameTimestampNs / 1000 <= ready ||
            snapshot.capturedAtMonotonicUs < ready || snapshot.capturedAtMonotonicUs > lastObservedAt ||
            lastObservedAt >= snapshot.expiresAtMonotonicUs) return false
        currentSnapshot = snapshot
        currentPhase = RegionPreviewPhase.VISIBLE
        return true
    }

    /** Too-small/invalid output selection stays visible so the user can select another rectangle. */
    @Synchronized fun confirm(snapshot: RegionPreviewSnapshot, bounds: RegionNormalizedRect, nowUs: Long): Boolean {
        if (!advance(nowUs) || currentPhase != RegionPreviewPhase.VISIBLE || snapshot != currentSnapshot) return false
        val recipe = try {
            RegionCaptureRecipe(bounds, snapshot.observation.geometry, snapshot.observation.scopeEvidence, lastObservedAt)
        } catch (_: IllegalArgumentException) { return false }
        selectedRecipe = recipe
        confirmedAt = lastObservedAt
        currentPhase = RegionPreviewPhase.CONFIRMED
        return true
    }

    /** Actual preview/selection UI disposal, not the confirm click or a requested dismissal. */
    @Synchronized fun retireUi(snapshot: RegionPreviewSnapshot, nowUs: Long): Boolean {
        if (!advance(nowUs) || currentPhase != RegionPreviewPhase.CONFIRMED || snapshot != currentSnapshot) return false
        uiRetiredAt = lastObservedAt
        currentPhase = RegionPreviewPhase.WAITING_FOR_CAPTURE_READY
        return true
    }

    /** Call only after a new uncovered-host draw following actual selection UI disposal. */
    @Synchronized fun markCaptureReady(snapshot: RegionPreviewSnapshot, nowUs: Long): Boolean {
        if (!advance(nowUs) || currentPhase != RegionPreviewPhase.WAITING_FOR_CAPTURE_READY ||
            snapshot != currentSnapshot) return false
        currentConfirmation = RegionPreviewConfirmation(snapshot, requireNotNull(selectedRecipe),
            requireNotNull(confirmedAt), requireNotNull(uiRetiredAt), lastObservedAt)
        currentPhase = RegionPreviewPhase.READY_TO_ENCODE
        return true
    }

    /** Single admission only; caller must still obey the native session's cancellation fence. */
    @Synchronized fun takeReady(nowUs: Long): RegionPreviewConfirmation? {
        if (!advance(nowUs) || currentPhase != RegionPreviewPhase.READY_TO_ENCODE) return null
        currentPhase = RegionPreviewPhase.ADMITTED
        return currentConfirmation
    }

    /** Poll while waiting; true means terminally invalidated, including an earlier invalidation. */
    @Synchronized fun expire(nowUs: Long): Boolean {
        advance(nowUs)
        return currentPhase == RegionPreviewPhase.INVALIDATED
    }

    @Synchronized fun invalidate(reason: RegionPreviewInvalidation) {
        if (currentPhase == RegionPreviewPhase.INVALIDATED) return
        invalidation = reason
        currentPhase = RegionPreviewPhase.INVALIDATED
        currentSnapshot = null
        currentConfirmation = null
        selectedRecipe = null
        confirmedAt = null
        uiRetiredAt = null
    }

    private fun advance(nowUs: Long): Boolean {
        if (currentPhase == RegionPreviewPhase.INVALIDATED) return false
        if (nowUs < openedAtMonotonicUs) { invalidate(RegionPreviewInvalidation.CLOCK_INVALID); return false }
        lastObservedAt = maxOf(nowUs, lastObservedAt)
        val effectiveNow = lastObservedAt
        // Preview lifetime does not become a recording duration limit after the one-shot handoff.
        if (currentPhase == RegionPreviewPhase.ADMITTED) return true
        if (currentPhase == RegionPreviewPhase.WAITING_FOR_HOST || currentPhase == RegionPreviewPhase.WAITING_FOR_FRAME) {
            if (effectiveNow - openedAtMonotonicUs >= RegionPreviewLimits.FIRST_FRAME_TIMEOUT_US) {
                invalidate(RegionPreviewInvalidation.FIRST_FRAME_TIMEOUT); return false
            }
        }
        if (currentSnapshot?.let { effectiveNow >= it.expiresAtMonotonicUs } == true) {
            invalidate(RegionPreviewInvalidation.SNAPSHOT_EXPIRED); return false
        }
        if (confirmedAt?.let { effectiveNow - it >= RegionPreviewLimits.UI_RETIREMENT_TIMEOUT_US } == true) {
            invalidate(RegionPreviewInvalidation.UI_RETIREMENT_TIMEOUT); return false
        }
        return true
    }
}

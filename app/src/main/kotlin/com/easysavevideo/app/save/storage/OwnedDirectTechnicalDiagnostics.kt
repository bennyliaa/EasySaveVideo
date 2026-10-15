package com.easysavevideo.app.save.storage

import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.media.transform.TransformerSourceObservation
import java.util.Collections
import java.util.UUID

/** Debug-only bounded values. No product decision reads this slot; it grants no authority. */
internal object OwnedDirectTechnicalDiagnostics {
    enum class Stage { PREFLIGHT_STARTED, CANDIDATE_ADMITTED, SOURCE_EOS_OBSERVED,
        RESOURCES_RELEASED, SEALED, UNVERIFIED_MAPPING_REJECTED }
    data class Snapshot(val stages: List<Stage>, val observation: TransformerSourceObservation?)
    private class Capture(val operationId: String, var mask: Int = 0,
        var observation: TransformerSourceObservation? = null)
    private val lock = Any()
    private var active: Capture? = null

    /** Only the test caller can fail here. Keys are generated afresh, never caller-reused. */
    fun begin(): String = synchronized(lock) {
        check(BuildConfig.DEBUG && active == null)
        val key = "opus-${UUID.randomUUID()}"
        active = Capture(key)
        key
    }

    /** No callbacks, IO, native calls, owner locks, live objects or unbounded history. */
    fun record(operationId: String, stage: Stage, observation: TransformerSourceObservation? = null) {
        if (!BuildConfig.DEBUG) return
        try {
            synchronized(lock) {
                val current = active ?: return
                if (current.operationId != operationId) return
                val copied = observation?.let { TransformerSourceObservation(it.video.copy(), it.audio?.copy()) }
                if (copied != null) current.observation = copied
                current.mask = current.mask or (1 shl stage.ordinal)
            }
        } catch (_: Throwable) {
            // Diagnostic loss may fail the test; it must never affect the run or its cleanup.
        }
    }

    /** Snapshot and clear are one short critical section; late/foreign writes are ignored. */
    fun end(operationId: String): Snapshot = synchronized(lock) {
        check(BuildConfig.DEBUG)
        val current = requireNotNull(active)
        check(current.operationId == operationId)
        active = null
        Snapshot(Collections.unmodifiableList(Stage.entries.filter { current.mask and (1 shl it.ordinal) != 0 }),
            current.observation?.let { TransformerSourceObservation(it.video.copy(), it.audio?.copy()) })
    }
}

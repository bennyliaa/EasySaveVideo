package com.easysavevideo.app.recording

/** A worker exception cannot escape to uncaught logging or silently lose its owner/result notice. */
internal object RecordingWorkerBoundary {
    fun run(action: () -> Unit, onUncertainFailure: () -> Unit) {
        try { action() } catch (_: Exception) { onUncertainFailure() }
    }
}

/** Queue admission alone or a callback before the first actual encoded frame is not preparation. */
internal fun recordingStartStillPending(captureRequired: Boolean, workerStarted: Boolean, encodedStarted: Boolean): Boolean =
    !workerStarted || captureRequired && !encodedStarted

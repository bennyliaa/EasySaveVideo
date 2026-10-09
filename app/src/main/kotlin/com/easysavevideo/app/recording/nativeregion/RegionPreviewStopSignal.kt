package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingStopReason

/**
 * One synchronous metadata revocation per pending preview. The callback must invalidate the
 * thread-safe publication gate before posting UI work. It never closes native/GPU resources.
 * A timeout or callback signal is not evidence that the native owner has released anything.
 */
internal class RegionPreviewStopSignal {
    private var signalled = false

    @Synchronized fun signal(previewPending: Boolean, reason: RecordingStopReason, invalidate: (RecordingError) -> Unit) {
        if (!previewPending || signalled) return
        // Hold only this bounded memory signal through metadata revocation: a concurrent caller
        // cannot return during the interval between claiming the signal and invalidating the gate.
        // Set first so an observer's same-thread reentry cannot recursively invoke itself.
        signalled = true
        invalidate(if (reason == RecordingStopReason.SIZE_CHANGED) RecordingError.FORMAT_CHANGED
            else RecordingError.CANCELLED)
    }
}

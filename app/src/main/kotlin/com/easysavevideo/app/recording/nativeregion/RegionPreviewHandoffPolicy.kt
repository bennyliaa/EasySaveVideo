package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RegionPreviewConfirmation
import com.easysavevideo.recording.RegionPreviewLimits

/** Apply before AND after potentially blocking source work, only during preview-to-record handoff. */
internal object RegionPreviewHandoffPolicy {
    fun allows(confirmation: RegionPreviewConfirmation, nowMonotonicUs: Long): Boolean =
        nowMonotonicUs >= confirmation.captureReadyAtMonotonicUs &&
            nowMonotonicUs < confirmation.snapshot.expiresAtMonotonicUs &&
            nowMonotonicUs - confirmation.captureReadyAtMonotonicUs < RegionPreviewLimits.UI_RETIREMENT_TIMEOUT_US
}

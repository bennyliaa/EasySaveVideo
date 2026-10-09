package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.RecordingArtifactExpectation
import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFrameTimeline
import com.easysavevideo.recording.CaptureGeometryObservation
import java.io.FileOutputStream

/** Exact private output capability supplied by storage; no pathname is accepted by capture. */
class NativeRecordingOutput(
    val openWriter: () -> FileOutputStream,
    val retainNativeWriter: () -> AutoCloseable,
    val availableBytes: () -> Long,
)

/** Native owner calls these synchronously, never under a UI lock. Closed does not mean verified. */
interface NativeRecordingSegmentOutputs {
    fun open(index: Int): NativeRecordingOutput
    fun closed(index: Int, expectation: RecordingArtifactExpectation, timing: RecordingFrameTimeline,
        geometry: CaptureGeometryObservation)
    /** The output is unusable. Actual release is reflected by error; never delete on a timeout. */
    fun abandoned(index: Int, error: RecordingError)
}

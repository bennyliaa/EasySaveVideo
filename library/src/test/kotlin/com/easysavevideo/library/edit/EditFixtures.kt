package com.easysavevideo.library.edit

import com.easysavevideo.library.MediaReadRef

/** Pure contract fixtures only. No native codec, file, device, or pixel-decode claim. */
internal object EditFixtures {
    val ref = MediaReadRef("source", 1, 1)
    fun source(ref: MediaReadRef = this.ref, audio: EditAudioProfile = EditAudioProfile.Silent) = EditSourceMetadata(
        ref = ref, durationUs = 10_000_000, width = 640, height = 360, rotationDegrees = 0,
        frameRate = 30, audio = audio, bytes = 1_000_000, avcProfile = EditAvcProfile.BASELINE,
        color = EditColorProfile.SDR, squarePixels = true, constantFrameRate = true, bitDepth = 8,
        frameTimeline = timeline(10_000_000, 30),
    )
    fun timeline(durationUs: Long, frameRate: Int): EditFrameTimeline = EditFrameTimeline(
        LongArray((durationUs * frameRate / 1_000_000).toInt()) { it * 1_000_000L / frameRate }, durationUs)
    fun request() = EditRequest("operation", listOf(EditSegment(ref, 0, 2_000_000)))
    fun plan(audio: EditAudioProfile = EditAudioProfile.Silent) = EditPlanner.plan(request(), listOf(source(audio = audio)))
    fun output() = EditOutputRef("operation", "staging")
    fun evidence(plan: EditPlan = plan(), output: EditOutputRef = output()): EditVerificationEvidence {
        val expected = plan.expectation
        val audio = expected.audio as? EditAudioProfile.Aac
        val audioFrames = audio?.let { expected.durationUs * it.sampleRate / 1_000_000 } ?: 0
        val videoFrames = expected.videoFrameCount
        return EditVerificationEvidence(
            output, expected, 500_000, "a".repeat(64), expected.durationUs, expected.width, expected.height,
            0, "video/avc", audio?.let { "audio/mp4a-latm" }, audio?.sampleRate, audio?.channels,
            0, (videoFrames - 1) * 1_000_000 / expected.frameRate, videoFrames,
            if (audioFrames == 0L) 0 else (audioFrames + 1023) / 1024,
            videoFrames, audioFrames, true, audio != null, true, true,
        )
    }
}

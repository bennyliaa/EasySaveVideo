package com.easysavevideo.app.editor.verification

import com.easysavevideo.app.media.verification.*
import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditOutputExpectation

internal fun invalidEditOutput(): Nothing = throw EditFailure(EditError.VERIFICATION_FAILED)
internal fun unsupportedEditOutput(): Nothing = throw EditFailure(EditError.UNSUPPORTED_PROFILE)

/** Only the Editor boundary translates neutral technical failures into the existing product codes. */
internal inline fun <T> editVerification(action: () -> T): T = try { action() }
catch (failure: AvcAacVerificationFailure) {
    throw EditFailure(when (failure.code) {
        AvcAacVerificationError.VERIFICATION_FAILED -> EditError.VERIFICATION_FAILED
        AvcAacVerificationError.UNSUPPORTED_PROFILE -> EditError.UNSUPPORTED_PROFILE
        AvcAacVerificationError.TIMEOUT -> EditError.TIMEOUT
        AvcAacVerificationError.RELEASE_UNCONFIRMED -> EditError.RELEASE_UNCONFIRMED
    })
}

internal fun EditOutputExpectation.inspectionExpectation() = AvcAacOutputExpectation(
    durationUs, width, height, frameRate,
    when (val value = audio) {
        EditAudioProfile.Silent -> AvcAacAudioProfile.Silent
        is EditAudioProfile.Aac -> AvcAacAudioProfile.Aac(value.sampleRate, value.channels)
    }, durationToleranceUs, audioPrimingToleranceUs, maxBytes, videoFrameCount,
)

internal typealias EditEncodedSample = AvcAacEncodedSample
internal typealias EditScannedTrack = AvcAacScannedTrack
internal typealias EditVerificationBounds = AvcAacVerificationBounds

/** Compatibility adapters preserve the existing Editor policy entry points and failure codes. */
internal class EditSampleLedger(video: Boolean, expected: EditOutputExpectation, timing: EditMp4Track) {
    private val delegate = editVerification { AvcAacSampleLedger(video, expected.inspectionExpectation(), timing) }
    fun sample(ptsUs: Long, size: Long, flags: Int) = editVerification { delegate.sample(ptsUs, size, flags) }
    fun finish(): EditScannedTrack = editVerification { delegate.finish() }
}

internal class EditVideoDecodeLedger(track: EditScannedTrack) {
    private val delegate = AvcAacVideoDecodeLedger(track)
    val outputEos: Boolean get() = delegate.outputEos
    val decodedFrames: Long get() = delegate.decodedFrames
    fun input(sample: EditEncodedSample) = editVerification { delegate.input(sample) }
    fun inputEnd() = editVerification { delegate.inputEnd() }
    fun output(ptsUs: Long, size: Int, config: Boolean, eos: Boolean): Boolean =
        editVerification { delegate.output(ptsUs, size, config, eos) }
}

internal class EditAudioDecodeLedger(expected: EditOutputExpectation, track: EditScannedTrack) {
    private val delegate = editVerification { AvcAacAudioDecodeLedger(expected.inspectionExpectation(), track) }
    val outputEos: Boolean get() = delegate.outputEos
    val decodedFrames: Long get() = delegate.decodedFrames
    fun input(sample: EditEncodedSample) = editVerification { delegate.input(sample) }
    fun inputEnd() = editVerification { delegate.inputEnd() }
    fun output(ptsUs: Long, size: Int, config: Boolean, eos: Boolean): Boolean =
        editVerification { delegate.output(ptsUs, size, config, eos) }
}

internal class EditDecodeProgress(startNanos: Long) {
    private val delegate = AvcAacDecodeProgress(startNanos)
    fun progressed(nowNanos: Long) = editVerification { delegate.progressed(nowNanos) }
    fun check(nowNanos: Long) = editVerification { delegate.check(nowNanos) }
}

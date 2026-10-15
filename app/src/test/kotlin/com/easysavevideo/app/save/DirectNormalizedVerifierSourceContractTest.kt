package com.easysavevideo.app.save

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring checks only. These never claim a native call, FD release or decoder succeeded. */
class DirectNormalizedVerifierSourceContractTest {
    private fun source(name: String) = File("src/main/kotlin/com/easysavevideo/app/save/$name.kt").readText()
    @Test fun bothOverloadsUseOneNativeEnvelopeAndOriginalTransfer() {
        val text = source("AndroidLocalMp4Verifier")
        assertTrue(text.contains("verify(artifact, ArtifactOutputDescriptor.Transferred(transfer), context)"))
        assertTrue(text.contains("inspect(artifact, output.transfer, context, deadlineNanos) { inputReleased = it }"))
        assertEquals(1, Regex("nativeLane.submit<VerificationEvidence>").findAll(text).count())
        assertTrue(text.indexOf("if (!lease.start())") < text.indexOf("DirectNormalizedAndroidVerifier("))
        assertFalse(source("DirectNormalizedAndroidVerifier").contains("TransferResult("))
    }
    @Test fun normalizedHasNoLegacyPathOrSecondLaneAndRuntimeUsesExactOwner() {
        val text = source("DirectNormalizedAndroidVerifier")
        assertFalse(text.contains("stagingFile")); assertFalse(text.contains("RandomAccessFile("))
        assertFalse(text.contains("Thread(")); assertFalse(text.contains("Executors"))
        assertFalse(text.contains("input.close()")); assertTrue(text.contains("lease.close()"))
        val runtime = source("AndroidSaveRuntime")
        assertTrue(runtime.contains("storage.controlledDirectComponents(directLocalGate, executionBridge, clock, limits)"))
        assertTrue(runtime.contains("limits.verificationTimeoutMillis, direct.normalizedOwner, direct.storage::onLegacyReadIssued)"))
        assertTrue(runtime.contains("SerialArtifactVerifier(actualVerifier, clock)"))
        assertTrue(runtime.contains("private val movAdmissionEnabled = false"))
        val envelope = source("AndroidLocalMp4Verifier")
        assertTrue(envelope.indexOf("if (!lease.start())") < envelope.indexOf("legacyReadOwnership?.invoke(artifact, context)"))
        assertTrue(envelope.contains("report?.invoke(inputReleased && !releaseUnconfirmed.get())"))
    }
    @Test fun independentRawScanRealTailAndFinalHashRemainRequired() {
        val text = source("DirectNormalizedAndroidVerifier")
        assertTrue(text.contains("Mp4ContainerProbe.isCompleteMp4(input)"))
        assertTrue(text.contains("MovProfileProbe.scanCanonicalMp4(fixed, checkpoint)"))
        assertTrue(text.contains("inspectDirectSamples(source, videoTrack, fixed, profile, configuration, checkpoint)"))
        assertTrue(text.contains("state.requireNextInput(native)")); assertTrue(text.contains("state.finish()"))
        assertTrue(text.contains("fixed.requireHash(before)"))
        assertTrue(text.contains("DirectNormalizedPreflight.checkLease(lease, artifact, output)"))
        assertFalse(text.contains("INCOMPLETE_TRANSFER"))
    }

    @Test fun audioRunsInsideOwnedInspectionBeforeReleaseAndFinalSameFdHash() {
        val text = source("DirectNormalizedAndroidVerifier")
        val inspect = text.substringBefore("private fun latch")
        assertTrue(inspect.contains("decodeAudio(selected, source, fixed, track, profile,"))
        assertTrue(inspect.contains("assembleDirectConservation(profile, samples, audio)"))
        assertTrue(inspect.indexOf("decodeAudio(selected") < inspect.indexOf("{ retriever?.release() }, { extractor?.release() }"))
        assertTrue(inspect.indexOf("{ retriever?.release() }, { extractor?.release() }") < inspect.indexOf("fixed.requireHash(before)"))
        assertTrue(inspect.indexOf("fixed.requireHash(before)") < inspect.indexOf("return assembleDirectVerificationEvidence("))
        assertTrue(inspect.contains("val durationMillis = conservation.endpoint.minimumUs / 1000"))
        assertTrue(inspect.contains("directSampleTimes(profile.clock.endpoint.minimumUs)"))
        assertFalse(inspect.contains("selectTrack(0)"))
        assertTrue(inspect.contains("nativeResult.positions, nativeResult.audio)"))
    }

    @Test fun freshAudioSessionHasNoFlushAndQueuesCreditBeforeAnyOutput() {
        val text = source("DirectNormalizedAndroidVerifier")
        val audio = text.substringAfter("private fun decodeAudio(").substringBefore("private fun <T> withDecoder(")
        assertEquals(1, Regex("MediaCodec.createDecoderByType").findAll(audio).count())
        assertEquals(1, Regex("codec.start\\(\\)").findAll(audio).count())
        assertFalse(audio.contains("flush(")); assertFalse(audio.contains("Thread(")); assertFalse(audio.contains("Executors"))
        assertTrue(audio.contains("readDirectNativeAacSample(source, track, next.toLong(), input,"))
        assertTrue(audio.contains("observed.track != expected"))
        assertTrue(audio.contains("queueDirectAudioInput(native, accounting)"))
        assertTrue(audio.indexOf("queueDirectAudioInput(native, accounting)") < audio.indexOf("source.advance()"))
        assertTrue(audio.indexOf("source.advance()") < audio.indexOf("codec.dequeueOutputBuffer"))
        assertTrue(audio.contains("raw.clock.endpoint.minimumUs"))
        assertTrue(audio.contains("DirectDecodeProgress(System.nanoTime(), checkpoint = checkpoint)"))
        val helper = source("DirectAndroidAudioInspection")
        val queue = helper.substringAfter("internal fun queueDirectAudioInput(").substringBefore("internal data class DirectDecodedAudioFormat")
        assertTrue(queue.indexOf("accounting.requireNextInput(sample)") < queue.indexOf("directNativeCall { queue() }"))
        assertTrue(queue.indexOf("directNativeCall { queue() }") < queue.indexOf("accounting.inputQueued(sample)"))
    }

    @Test fun everyAudioOutputIndexIsReturnedAndActualBufferFactsReachAccounting() {
        val audio = source("DirectNormalizedAndroidVerifier").substringAfter("private fun decodeAudio(")
            .substringBefore("private fun <T> withDecoder(")
        val output = audio.substringAfter("index >= 0 -> withDirectReturnedOutputBuffer")
        assertTrue(output.startsWith("({ codec.releaseOutputBuffer(index, false) })"))
        assertTrue(output.contains("directAudioOutputFlags(info.flags)"))
        assertTrue(output.contains("directDecodedAudioFormat(codec.getOutputFormat(index))"))
        assertTrue(output.contains("codec.getOutputBuffer(index)"))
        assertTrue(output.contains("accounting.output(buffer, info.offset, info.size, info.presentationTimeUs,"))
        assertFalse(output.contains("buffer.clear()")); assertFalse(output.contains("buffer.limit("))
        assertTrue(output.contains("releases = listOf({ decoder?.release() })"))
        assertTrue(output.contains("extractor.unselectTrack(track)"))
        val helper = source("DirectAndroidAudioInspection")
        val release = helper.substringAfter("internal fun <T> withDirectReturnedOutputBuffer(")
            .substringBefore("internal data class DirectInspectedAudio")
        assertTrue(release.contains("finally {")); assertTrue(release.contains("after = release"))
    }

}

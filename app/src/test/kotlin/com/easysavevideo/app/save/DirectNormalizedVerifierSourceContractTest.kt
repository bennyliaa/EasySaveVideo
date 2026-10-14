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
        assertTrue(text.contains("is ArtifactOutputDescriptor.Transferred -> inspect(artifact, output.transfer, context, deadlineNanos)"))
        assertEquals(1, Regex("nativeLane.submit<VerificationEvidence>").findAll(text).count())
        assertTrue(text.indexOf("if (!lease.start())") < text.indexOf("DirectNormalizedAndroidVerifier("))
        assertFalse(source("DirectNormalizedAndroidVerifier").contains("TransferResult("))
    }
    @Test fun normalizedHasNoLegacyPathOrSecondLaneAndRuntimeOwnerRemainsAbsent() {
        val text = source("DirectNormalizedAndroidVerifier")
        assertFalse(text.contains("stagingFile")); assertFalse(text.contains("RandomAccessFile("))
        assertFalse(text.contains("Thread(")); assertFalse(text.contains("Executors"))
        assertFalse(text.contains("input.close()")); assertTrue(text.contains("lease.close()"))
        assertTrue(source("AndroidSaveRuntime").contains("AndroidLocalMp4Verifier(storage::verificationFile, limits.verificationTimeoutMillis)"))
    }
    @Test fun independentRawScanRealTailAndFinalHashRemainRequired() {
        val text = source("DirectNormalizedAndroidVerifier")
        assertTrue(text.contains("Mp4ContainerProbe.isCompleteMp4(input)"))
        assertTrue(text.contains("MovProfileProbe.scanCanonicalMp4(fixed, checkpoint)"))
        assertTrue(text.contains("inspectDirectSamples(source, 0, fixed, profile, configuration, checkpoint)"))
        assertTrue(text.contains("state.requireNextInput(native)")); assertTrue(text.contains("state.finish()"))
        assertTrue(text.contains("fixed.requireHash(before)"))
        assertTrue(text.contains("DirectNormalizedPreflight.checkLease(lease, artifact, output)"))
        assertFalse(text.contains("INCOMPLETE_TRANSFER"))
    }
}

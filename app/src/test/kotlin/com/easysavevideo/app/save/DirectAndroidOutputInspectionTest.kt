package com.easysavevideo.app.save

import com.easysavevideo.app.save.normalization.MovProfile
import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.app.save.normalization.OriginalMovFixtures
import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exercises the actual adapter helpers with Android-shaped observations, not Android acceptance. */
class DirectAndroidOutputInspectionTest {
    private class Fixture(options: OriginalMovFixtures.Options = OriginalMovFixtures.Options(quickTime = false)) {
        val bytes = OriginalMovFixtures.build(options)
        val input = OriginalMovFixtures.input(bytes)
        val profile = MovProfileProbe.scanCanonicalMp4(input)
        val samples = profile.samples.map { sample ->
            val canonical = MovProfileProbe.readCanonicalSample(input, profile, sample.index)
            val nals = DirectMovCanonical.avcNals(canonical, DirectMovNalFraming.AVCC4)
            val annex = nals.fold(ByteArray(0)) { result, nal -> result + byteArrayOf(0, 0, 0, 1) + nal }
            Sample(annex, sample.presentationTimeUs, if (sample.sync) 1 else 0)
        }
        val format = DirectNativeTrackFormat(1, "video/avc", profile.width, profile.height,
            profile.clock.endpoint.minimumUs, listOf(byteArrayOf(0, 0, 0, 1) + profile.configuration.sps,
                byteArrayOf(0, 0, 0, 1) + profile.configuration.pps), false)
        fun scan(source: Source = Source(samples)) = inspectDirectSamples(source, 0, input, profile,
            inspectDirectFormat(format, profile) {}, {})
    }
    private data class Sample(val bytes: ByteArray, val time: Long, val flags: Int, val track: Int = 0)
    private class Source(val samples: List<Sample>) : DirectNativeSampleSource {
        var at = 0
        var seekIndex = 0
        var returnedCount: Int? = null
        var eofTime = -1L
        var eofTrack = -1
        var eofSize = -1L
        var eofRead = -1
        override val timeUs get() = samples.getOrNull(at)?.time ?: eofTime
        override val trackIndex get() = samples.getOrNull(at)?.track ?: eofTrack
        override val size get() = samples.getOrNull(at)?.bytes?.size?.toLong() ?: eofSize
        override val flags get() = samples.getOrNull(at)?.flags ?: 0
        override fun seekPreviousSync(timeUs: Long) { at = seekIndex }
        override fun read(target: ByteBuffer): Int {
            val sample = samples.getOrNull(at) ?: return eofRead
            target.put(sample.bytes)
            return returnedCount ?: sample.bytes.size
        }
        override fun advance() { at++ }
    }
    private fun failure(code: SaveError, body: () -> Unit) {
        assertEquals(code, assertThrows(SaveFailure::class.java, body).code)
    }

    @Test fun actualFullWalkRecomputesConservationAndKeepsOnlyFinalGop() {
        val f = Fixture()
        val actual = f.scan()
        assertEquals(f.profile.conservation, assembleDirectConservation(f.profile, actual, null))
        assertEquals(3, actual.tail.sampleCount)
        assertEquals(f.profile.conservation.precedingVideoSync, actual.tail.samples.first().identity)
        assertEquals(f.profile.conservation.video.lastSample, actual.tail.samples.last().identity)
    }
    @Test fun twoSamplesAndAllIdrProduceExactEndpointAndOneSampleFinalGop() {
        for (count in listOf(2, 3)) {
            val f = Fixture(OriginalMovFixtures.Options(count = count, quickTime = false, allIdr = true))
            val actual = f.scan()
            assertEquals(1, actual.tail.sampleCount)
            assertEquals((count - 1).toLong(), actual.tail.samples.single().identity.index)
            assertEquals(f.profile.clock.endpoint, actual.video.track.endpoint)
        }
    }
    @Test fun allApprovedClockRoundingPhasesRemainExact() {
        for (rate in DirectMovClock.VIDEO_FRAME_RATES) {
            val f = Fixture(OriginalMovFixtures.Options(frameRate = rate, count = 5, quickTime = false))
            assertEquals(f.profile.conservation, assembleDirectConservation(f.profile, f.scan(), null))
        }
    }
    @Test fun multisliceAudAndInBandConfigurationAreNotDiscarded() {
        val f = Fixture(OriginalMovFixtures.Options(width = 32, slices = 2, aud = true,
            inBandConfig = true, quickTime = false))
        assertEquals(f.profile.conservation, assembleDirectConservation(f.profile, f.scan(), null))
    }
    @Test fun missingDuplicateReorderedAndChangedMiddleSamplesReject() {
        val f = Fixture()
        val cases = listOf(f.samples.dropLast(1), f.samples + f.samples.last(),
            listOf(f.samples[0], f.samples[2], f.samples[1]),
            listOf(f.samples[0], f.samples[0], f.samples[2]),
            f.samples.mapIndexed { i, s -> if (i == 1) s.copy(time = s.time + 1) else s },
            f.samples.mapIndexed { i, s -> if (i == 1) s.copy(flags = 1) else s },
            f.samples.mapIndexed { i, s -> if (i == 1) s.copy(bytes = f.samples[2].bytes) else s })
        for (samples in cases) failure(SaveError.INVALID_MEDIA) { f.scan(Source(samples)) }
    }
    @Test fun seekCannotSubstituteAnotherSyncAndExtraTracksFail() {
        val f = Fixture()
        failure(SaveError.INVALID_MEDIA) { f.scan(Source(f.samples).also { it.seekIndex = 1 }) }
        failure(SaveError.INVALID_MEDIA) { f.scan(Source(f.samples.map { it.copy(track = 1) })) }
        failure(SaveError.INVALID_MEDIA) { inspectDirectFormat(f.format.copy(trackCount = 2), f.profile) {} }
    }
    @Test fun absentAndExplicitZeroEncryptionFlagsAreClear() {
        assertFalse(directExplicitEncryptionFlag(false) { fail("Absent flag must not be read"); 1 })
        assertFalse(directExplicitEncryptionFlag(true) { 0 })
    }
    @Test fun explicitNonzeroEncryptionFlagIsPositiveEvidence() {
        assertTrue(directExplicitEncryptionFlag(true) { 1 })
        assertTrue(directExplicitEncryptionFlag(true) { -1 })
    }
    @Test fun malformedOrUnreadableEncryptionFlagIsUnavailable() {
        failure(SaveError.VERIFICATION_UNAVAILABLE) { directExplicitEncryptionFlag(true) { throw ClassCastException() } }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { directExplicitEncryptionFlag(true) { throw IllegalStateException() } }
        failure(SaveError.CANCELLED) { directExplicitEncryptionFlag(true) { throw SaveFailure(SaveError.CANCELLED) } }
    }
    @Test fun extractorFlagsHaveNoMedia3LastSampleEscape() {
        val f = Fixture()
        for (flag in listOf(4, 0x20000000, 8, -1)) {
            val expected = if (flag and 2 != 0) SaveError.PROTECTED_MEDIA else SaveError.VERIFICATION_UNAVAILABLE
            failure(expected) { f.scan(Source(f.samples.mapIndexed { i, s -> if (i == 1) s.copy(flags = flag) else s })) }
        }
        failure(SaveError.PROTECTED_MEDIA) { directAndroidSync(2) }
        assertEquals(0, directAndroidSync(0)); assertEquals(1, directAndroidSync(1))
    }
    @Test fun nativeAvccCannotBeSniffedOrUsedAsAnAlternateRepresentation() {
        val f = Fixture()
        val avcc = MovProfileProbe.readCanonicalSample(f.input, f.profile, 0)
        failure(SaveError.VERIFICATION_UNAVAILABLE) {
            f.scan(Source(f.samples.mapIndexed { i, s -> if (i == 0) s.copy(bytes = avcc) else s }))
        }
    }
    @Test fun everyEofObservationMustIndependentlySayEnd() {
        val f = Fixture()
        val sources = listOf(Source(f.samples).also { it.eofTime = 0 }, Source(f.samples).also { it.eofTrack = 0 },
            Source(f.samples).also { it.eofSize = 0 }, Source(f.samples).also { it.eofRead = 0 })
        for (source in sources) failure(SaveError.INVALID_MEDIA) { f.scan(source) }
    }
    @Test fun normalNativeCountMismatchIsInvalidMediaNeverNetworkRetry() {
        val f = Fixture()
        for (count in listOf(-1, 0, f.samples.first().bytes.size - 1, f.samples.first().bytes.size + 1)) {
            failure(SaveError.INVALID_MEDIA) { f.scan(Source(f.samples).also { it.returnedCount = count }) }
        }
    }
    @Test fun csdDuplicateBuffersPreservePositionsAndBoundBeforeCopy() {
        val sps = ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3)).also { it.position(1) }
        val pps = ByteBuffer.wrap(byteArrayOf(9, 4, 5)).also { it.position(1) }
        val values = directInitializationData(listOf(sps, pps))
        assertArrayEquals(byteArrayOf(1, 2, 3), values[0]); assertArrayEquals(byteArrayOf(4, 5), values[1])
        assertEquals(1, sps.position()); assertEquals(1, pps.position())
        failure(SaveError.VERIFICATION_UNAVAILABLE) { directInitializationData(listOf(ByteBuffer.allocate(65_536), ByteBuffer.allocate(1))) }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { directInitializationData(listOf(ByteBuffer.allocate(1))) }
    }
    @Test fun csdBytesOrderAndExtraEntriesFailClosed() {
        val f = Fixture()
        assertEquals(f.profile.configuration.sha256, inspectDirectFormat(f.format, f.profile) {}.sha256)
        failure(SaveError.VERIFICATION_UNAVAILABLE) { inspectDirectFormat(f.format.copy(hasExtraInitializationData = true), f.profile) {} }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { inspectDirectFormat(f.format.copy(initializationData = f.format.initializationData.reversed()), f.profile) {} }
        val changed = f.format.initializationData.mapIndexed { i, b -> b.clone().also { if (i == 0) it[6] = 1 } }
        failure(SaveError.INVALID_MEDIA) { inspectDirectFormat(f.format.copy(initializationData = changed), f.profile) {} }
    }
    @Test fun contradictoryAndroidInterpretationRejectsWithoutChangingRawFacts() {
        val f = Fixture()
        val bad = listOf(f.format.copy(rotation = 90), f.format.copy(cropRight = 14), f.format.copy(sarWidth = 2, sarHeight = 1),
            f.format.copy(sarWidth = 1), f.format.copy(colorStandard = 6), f.format.copy(colorTransfer = 6),
            f.format.copy(colorRange = 1), f.format.copy(width = 32), f.format.copy(durationUs = 101_001))
        for (format in bad) failure(SaveError.INVALID_MEDIA) { inspectDirectFormat(format, f.profile) {} }
        inspectDirectFormat(f.format.copy(rotation = 0, colorStandard = 1, colorTransfer = 3, colorRange = 2,
            sarWidth = 4, sarHeight = 4, durationUs = 101_000), f.profile) {}
        failure(SaveError.PROTECTED_MEDIA) { inspectDirectFormat(f.format.copy(hasProtection = true), f.profile) {} }
    }
    @Test fun sourceHttpLengthAndMimeStayRawEvenWhenOutputLengthDiffers() {
        val f = Fixture()
        for (rawSize in listOf(f.bytes.size.toLong() - 1, f.bytes.size.toLong() + 100)) {
            val (artifact, output, context) = descriptor(f, rawSize)
            DirectNormalizedPreflight.check(artifact, output, context)
            assertEquals(rawSize, output.sourceTransfer.expectedTotalBytes)
            assertEquals("video/quicktime", output.sourceTransfer.responseMimeType)
            assertEquals(f.bytes.size.toLong(), output.sizeBytes)
            failure(SaveError.BYTE_LIMIT_EXCEEDED) {
                DirectNormalizedPreflight.check(artifact, output, context.copy(resourceBudget = ResourceBudget(maxBytes = output.sizeBytes - 1)))
            }
        }
    }
    @Test fun explicitTypedOverrideRejectsAbsentOwnershipBeforeAnyNativeWork() {
        val f = Fixture(); val (artifact, output, context) = descriptor(f)
        var paths = 0
        val verifier = AndroidLocalMp4Verifier({ paths++; error("Legacy callback is forbidden") }, 60_000)
        failure(SaveError.RECOVERY_REQUIRED) { verifier.verify(artifact, output, context) }
        assertEquals(0, paths)
    }
    @Test fun visibleBindingMismatchRejectsBeforeAnyOwnerAcquisition() {
        val f = Fixture(); val (artifact, output, context) = descriptor(f)
        failure(SaveError.RECOVERY_REQUIRED) {
            DirectNormalizedPreflight.check(artifact.copy(commitId = "00000000-0000-0000-0000-000000000099"), output, context)
        }
    }
    @Test fun ownershipAdapterRequiresOriginalDescriptorAndRevalidatesAuthoritativeLease() {
        val f = Fixture(); val (artifact, output, _) = descriptor(f)
        val file = File.createTempFile("direct-lease-", ".mp4")
        try {
            file.writeBytes(f.bytes)
            val input = RandomAccessFile(file, "r")
            var validations = 0
            var uncertain = false
            var closed = false
            val lease = object : DirectNormalizedOutputLease {
                override val artifact = artifact
                override val output = output
                override val readOnlyInput = input
                override val releaseConfirmed get() = closed && !uncertain
                override fun revalidate() { validations++ }
                override fun retainOnNativeReleaseUncertainty() { uncertain = true }
                override fun close() { input.close(); closed = true }
            }
            try {
                DirectNormalizedPreflight.checkLease(lease, artifact, output)
                assertEquals(1, validations)
                failure(SaveError.RECOVERY_REQUIRED) {
                    DirectNormalizedPreflight.checkLease(lease, artifact, output.copy())
                }
                assertEquals(1, validations)
                lease.retainOnNativeReleaseUncertainty()
            } finally { lease.close() }
            assertFalse(lease.releaseConfirmed)
            assertFalse(input.fd.valid())
        } finally { file.delete() }
    }
    @Test fun failedOwnerAcquisitionKeepsItsRecoveryGateWithoutReturningALease() {
        val f = Fixture(); val (artifact, output, context) = descriptor(f)
        var recovery = false
        var latched = false
        var acquisitions = 0
        val owner = object : DirectNormalizedOutputOwner {
            override fun requiresProcessRecovery() = recovery
            override fun acquire(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
                context: OperationContext): DirectNormalizedOutputLease {
                acquisitions++; recovery = true; throw IOException()
            }
        }
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) {
            DirectNormalizedPreflight.acquire(owner, artifact, output, context) { latched = true }
        }
        assertTrue(recovery); assertTrue(latched); assertEquals(1, acquisitions)
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) {
            DirectNormalizedPreflight.acquire(owner, artifact, output, context) { latched = true }
        }
        assertEquals(1, acquisitions)
    }
    @Test fun ownerLocalIoWithoutReleaseUncertaintyIsStorageFailure() {
        val f = Fixture(); val (artifact, output, context) = descriptor(f)
        val owner = object : DirectNormalizedOutputOwner {
            override fun requiresProcessRecovery() = false
            override fun acquire(artifact: StagingArtifact, output: ArtifactOutputDescriptor.NormalizedDirect,
                context: OperationContext): DirectNormalizedOutputLease = throw IOException()
        }
        failure(SaveError.STORAGE_FAILURE) {
            DirectNormalizedPreflight.acquire(owner, artifact, output, context) { fail("No unknown release") }
        }
    }
    private fun descriptor(f: Fixture, rawSize: Long = f.bytes.size.toLong()): Triple<StagingArtifact,
        ArtifactOutputDescriptor.NormalizedDirect, OperationContext> {
        val artifact = StagingArtifact("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
        val transfer = TransferResult(artifact.artifactId, rawSize, "video/quicktime", rawSize)
        val proof = DirectNormalizationProof("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004",
            "a".repeat(64), artifact.artifactId, artifact.commitId, rawSize, "b".repeat(64), sourceTransferFingerprint(transfer),
            f.bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(f.bytes).joinToString("") { "%02x".format(it) }, f.profile.conservation)
        return Triple(artifact, ArtifactOutputDescriptor.NormalizedDirect(transfer, proof),
            OperationContext("direct-test", Long.MAX_VALUE, CancellationSource()))
    }
}

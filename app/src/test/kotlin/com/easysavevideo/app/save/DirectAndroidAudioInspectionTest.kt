package com.easysavevideo.app.save

import com.easysavevideo.app.save.normalization.MovProfileProbe
import com.easysavevideo.app.save.normalization.OriginalMovFixtures
import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Real production helpers, synthetic compressed/PCM observations. Never a MediaCodec/AAC test. */
class DirectAndroidAudioInspectionTest {
    private class Fixture(rate: Int = 48_000, channels: Int = 2, count: Int = 3, videoCount: Int = 3) {
        val bytes = OriginalMovFixtures.build(OriginalMovFixtures.Options(quickTime = false,
            audio = true, audioSampleRate = rate, audioChannelCount = channels, audioCount = count, count = videoCount))
        val input = OriginalMovFixtures.input(bytes)
        val profile = MovProfileProbe.scanCanonicalMp4(input)
        val audio = requireNotNull(profile.audio)
        val expected = requireNotNull(profile.conservation.audio)
        val packets = audio.samples.map { ref -> Sample(MovProfileProbe.readAudioSample(input, profile, ref.index),
            ref.presentationTimeUs) }
        val format = DirectNativeAudioFormat("audio/mp4a-latm", rate, channels, audio.clock.endpoint.minimumUs,
            listOf(audio.configuration.asc), false)
        fun read(source: Source, index: Int, buffer: ByteBuffer = ByteBuffer.allocate(4096)) =
            readDirectNativeAacSample(source, 1, index.toLong(), input, profile, buffer) {}
        fun accounting() = DirectAudioPcmAccounting(expected, audio.clock, audio.configuration.channelCount)
        fun observedAudio(): DirectInspectedAudio {
            val source = Source(packets)
            val config = inspectDirectAudioFormat(format, profile) {}
            val accumulator = DirectMovSampleAccumulator(audio.clock, config.sha256)
            val accounting = accounting()
            for (i in packets.indices) {
                val observed = read(source, i)
                val identity = accumulator.append(observed.canonical, observed.identity.presentationTimeUs, observed.identity.flags)
                queueDirectAudioInput(DirectNativeSample(identity, observed.nativeBytes), accounting) {}
                source.advance()
            }
            requireDirectEof(source, ByteBuffer.allocate(32)) {}
            val track = accumulator.finish().track
            accounting.inputEndOfStream()
            val size = packets.size * 1024 * audio.configuration.channelCount * 2
            assertTrue(accounting.output(ByteBuffer.allocate(size), 0, size, 0, "audio/raw",
                audio.configuration.sampleRate, audio.configuration.channelCount, true, false, true))
            return DirectInspectedAudio(track, config, accounting.finish())
        }
        fun video(): DirectInspectedSamples {
            val packets = profile.samples.map { ref ->
                val canonical = MovProfileProbe.readCanonicalSample(input, profile, ref.index)
                val bytes = DirectMovCanonical.avcNals(canonical, DirectMovNalFraming.AVCC4)
                    .fold(ByteArray(0)) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
                Sample(bytes, ref.presentationTimeUs, if (ref.sync) 1 else 0, track = 0)
            }
            return inspectDirectSamples(Source(packets), 0, input, profile, profile.configuration) {}
        }
    }
    private data class Sample(val bytes: ByteArray, val time: Long, val flags: Int = 1, val track: Int = 1)
    private class Source(val packets: List<Sample>) : DirectNativeSampleSource {
        var at = 0
        var count: Int? = null
        var announcedSize: Long? = null
        var eofTime = -1L
        var eofTrack = -1
        var eofSize = -1L
        var eofRead = -1
        override val timeUs get() = packets.getOrNull(at)?.time ?: eofTime
        override val trackIndex get() = packets.getOrNull(at)?.track ?: eofTrack
        override val size get() = packets.getOrNull(at)?.let { announcedSize ?: it.bytes.size.toLong() } ?: eofSize
        override val flags get() = packets.getOrNull(at)?.flags ?: 0
        override fun seekPreviousSync(timeUs: Long) { at = 0 }
        override fun read(target: ByteBuffer): Int {
            val packet = packets.getOrNull(at) ?: return eofRead
            target.put(packet.bytes)
            return count ?: packet.bytes.size
        }
        override fun advance() { at++ }
    }
    private fun failure(code: SaveError, action: () -> Unit) =
        assertEquals(code, assertThrows(SaveFailure::class.java, action).code)

    @Test fun everyDeclaredAscMatchesActualNativeFactsAndRecomputedTransport() {
        for (rate in listOf(32_000, 44_100, 48_000)) for (channels in 1..2) {
            val f = Fixture(rate, channels)
            val actual = f.observedAudio()
            assertEquals(f.expected, actual.conservation)
            assertEquals(3L * 1024, actual.pcm.pcmFrames)
            assertEquals(3L * 1024 * channels * 2, actual.pcm.pcmBytes)
            assertEquals(f.audio.clock.endpoint, actual.pcm.endpoint)
            assertNotSame(f.audio.configuration, actual.configuration)
            assertEquals(f.profile.conservation, assembleDirectConservation(f.profile, f.video(), actual))
        }
    }

    @Test fun videoAndAudioIndicesAreBoundByActualMimeInEitherOrder() {
        assertEquals(DirectNativeTrackBindings(0, 1), bindDirectNativeTracks(listOf("video/avc", "audio/mp4a-latm"), true))
        assertEquals(DirectNativeTrackBindings(1, 0), bindDirectNativeTracks(listOf("audio/mp4a-latm", "video/avc"), true))
        assertEquals(DirectNativeTrackBindings(0, null), bindDirectNativeTracks(listOf("video/avc"), false))
        for (mimes in listOf(emptyList(), listOf("video/avc"), listOf("video/avc", "video/avc"),
                listOf("audio/mp4a-latm", "audio/mp4a-latm"), listOf("video/avc", "text/vtt"),
                listOf("video/avc", "audio/mp4a-latm", "text/vtt"))) {
            failure(SaveError.INVALID_MEDIA) { bindDirectNativeTracks(mimes, true) }
        }
        failure(SaveError.INVALID_MEDIA) { bindDirectNativeTracks(listOf("video/avc", "audio/mp4a-latm"), false) }
    }

    @Test fun exactCsdCopyPreservesNativeBufferCursorAndHasNoExtraConfigFallback() {
        val buffer = ByteBuffer.wrap(byteArrayOf(9, 0x11, 0x90.toByte(), 8)).apply { position(1); limit(3) }
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), directAudioInitializationData(listOf(buffer)).single())
        assertEquals(1, buffer.position()); assertEquals(3, buffer.limit())
        for (buffers in listOf(emptyList(), listOf(ByteBuffer.allocate(1)), listOf(ByteBuffer.allocate(3)),
                listOf(ByteBuffer.allocate(2), ByteBuffer.allocate(2))))
            failure(SaveError.VERIFICATION_UNAVAILABLE) { directAudioInitializationData(buffers) }
        val f = Fixture()
        failure(SaveError.VERIFICATION_UNAVAILABLE) { inspectDirectAudioFormat(f.format.copy(hasExtraInitializationData = true), f.profile) {} }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { inspectDirectAudioFormat(f.format.copy(initializationData = listOf(byteArrayOf(1, 2))), f.profile) {} }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { inspectDirectAudioFormat(f.format.copy(initializationData = f.format.initializationData + f.format.initializationData), f.profile) {} }
    }

    @Test fun nativeAscRateChannelsAndOptionalHintsCannotOverrideRawFacts() {
        val f = Fixture()
        val changed = listOf(f.format.copy(mime = "audio/aac"), f.format.copy(sampleRate = 44_100),
            f.format.copy(channelCount = 1), f.format.copy(aacProfile = 5), f.format.copy(profile = 29),
            f.format.copy(isAdts = 1), f.format.copy(encoderDelay = 1), f.format.copy(encoderPadding = 1),
            f.format.copy(encoderDelay = -1), f.format.copy(durationUs = 0),
            f.format.copy(durationUs = f.audio.clock.endpoint.maximumUs + 1001),
            f.format.copy(initializationData = listOf(byteArrayOf(0x11, 0x88.toByte()))))
        for (actual in changed) failure(SaveError.INVALID_MEDIA) { inspectDirectAudioFormat(actual, f.profile) {} }
        inspectDirectAudioFormat(f.format.copy(aacProfile = 2, profile = 2, isAdts = 0, encoderDelay = 0, encoderPadding = 0), f.profile) {}
        failure(SaveError.PROTECTED_MEDIA) { inspectDirectAudioFormat(f.format.copy(hasProtection = true), f.profile) {} }
    }

    @Test fun changedMiddleBytesCannotBorrowExpectedPayloadDigest() {
        val f = Fixture()
        val bad = f.packets.mapIndexed { i, s -> if (i == 1) s.copy(bytes = s.bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }) else s }
        val source = Source(bad).apply { at = 1 }
        failure(SaveError.INVALID_MEDIA) { f.read(source, 1) }
    }

    @Test fun missingDuplicatedReorderedAndWrongTrackOrTimeAreRejected() {
        val f = Fixture()
        val bad = listOf(f.packets.dropLast(1), f.packets + f.packets.last(), f.packets.reversed(),
            listOf(f.packets[0], f.packets[0], f.packets[2]),
            f.packets.mapIndexed { i, s -> if (i == 1) s.copy(time = s.time + 1) else s },
            f.packets.mapIndexed { i, s -> if (i == 1) s.copy(track = 0) else s })
        for (packets in bad) failure(SaveError.INVALID_MEDIA) {
            val source = Source(packets)
            for (i in f.packets.indices) { f.read(source, i); source.advance() }
            requireDirectEof(source, ByteBuffer.allocate(4096)) {}
        }
    }

    @Test fun audioRequiresExactAllSyncAndroidFlagsAndBounds() {
        val f = Fixture()
        for (flags in listOf(0, 2, 4, 8, 0x20000000, -1)) {
            val code = when { flags and 2 != 0 -> SaveError.PROTECTED_MEDIA; flags == 0 -> SaveError.INVALID_MEDIA
                else -> SaveError.VERIFICATION_UNAVAILABLE }
            failure(code) { f.read(Source(f.packets.map { it.copy(flags = flags) }), 0) }
        }
        for (count in listOf(-1, 0, f.packets.first().bytes.size - 1, f.packets.first().bytes.size + 1))
            failure(SaveError.INVALID_MEDIA) { f.read(Source(f.packets).apply { this.count = count }, 0) }
        for (size in listOf(0L, -1L, 4L * 1024 * 1024 + 1, f.packets.first().bytes.size.toLong() + 1))
            failure(SaveError.INVALID_MEDIA) { f.read(Source(f.packets).apply { announcedSize = size }, 0) }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { f.read(Source(f.packets), 0, ByteBuffer.allocate(0)) }
    }

    @Test fun actualInputBufferContainsOnlyUnchangedRawBytesAtQueueBoundary() {
        val f = Fixture(); val source = Source(f.packets); val buffer = ByteBuffer.allocate(4096)
        val actual = f.read(source, 0, buffer)
        assertEquals(0, buffer.position()); assertEquals(actual.nativeBytes, buffer.limit())
        queueDirectAudioInput(DirectNativeSample(actual.identity, actual.nativeBytes), f.accounting()) {
            assertEquals(actual.nativeBytes, buffer.remaining())
            assertArrayEquals(f.packets[0].bytes, ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) })
        }
    }

    @Test fun allFourNativeEofFactsAreIndependent() {
        val f = Fixture()
        val sources = listOf(Source(f.packets).apply { eofTime = 0 }, Source(f.packets).apply { eofTrack = 1 },
            Source(f.packets).apply { eofSize = 0 }, Source(f.packets).apply { eofRead = 0 })
        for (source in sources) {
            source.at = f.packets.size
            failure(SaveError.INVALID_MEDIA) { requireDirectEof(source, ByteBuffer.allocate(16)) {} }
        }
    }

    @Test fun queueFailureGrantsNoCreditAndSuccessfulReturnCreditsBeforeOutput() {
        val f = Fixture(); val observed = f.read(Source(f.packets), 0)
        val sample = DirectNativeSample(observed.identity, observed.nativeBytes)
        val failed = f.accounting()
        failure(SaveError.VERIFICATION_UNAVAILABLE) { queueDirectAudioInput(sample, failed) { throw IllegalStateException() } }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { failed.output(ByteBuffer.allocate(4096), 0, 4096, 0,
            "audio/raw", 48_000, 2, true, false, false) }
        val success = f.accounting(); var queued = false
        queueDirectAudioInput(sample, success) { queued = true }
        assertTrue(queued)
        assertTrue(success.output(ByteBuffer.allocate(4096), 0, 4096, 0, "audio/raw", 48_000, 2, true, false, false))
    }

    @Test fun queuePrecheckPreventsNativeCallForWrongIndex() {
        val f = Fixture(); val source = Source(f.packets).apply { at = 1 }; val observed = f.read(source, 1)
        failure(SaveError.INVALID_MEDIA) { queueDirectAudioInput(DirectNativeSample(observed.identity, observed.nativeBytes), f.accounting()) {
            fail("Invalid input must not reach the native queue")
        } }
    }

    @Test fun actualPcmEncodingAbsentDefaultsTo16ButUnreadableNeverDoes() {
        assertTrue(directSignedPcm16(false) { fail("Absent encoding must not be read"); 0 })
        assertTrue(directSignedPcm16(true) { 2 })
        assertFalse(directSignedPcm16(true) { 4 })
        failure(SaveError.VERIFICATION_UNAVAILABLE) { directSignedPcm16(true) { throw ClassCastException() } }
        failure(SaveError.CANCELLED) { directSignedPcm16(true) { throw SaveFailure(SaveError.CANCELLED) } }
        val good = DirectDecodedAudioFormat("audio/raw", 48_000, 2, true)
        requireDirectPcmFormat(good, 48_000, 2)
        for (bad in listOf(good.copy(mime = "audio/mp4a-latm"), good.copy(sampleRate = 44_100),
                good.copy(channelCount = 1), good.copy(signedPcm16 = false)))
            failure(SaveError.VERIFICATION_UNAVAILABLE) { requireDirectPcmFormat(bad, 48_000, 2) }
    }

    @Test fun outputFlagsCannotHidePartialUnknownOrConfigEos() {
        assertEquals(DirectAudioOutputFlags(false, false), directAudioOutputFlags(0))
        assertEquals(DirectAudioOutputFlags(false, false), directAudioOutputFlags(1))
        assertEquals(DirectAudioOutputFlags(true, false), directAudioOutputFlags(2))
        assertEquals(DirectAudioOutputFlags(false, true), directAudioOutputFlags(4))
        for (flags in listOf(6, 7, 8, 16, -1, 0x20000000))
            failure(SaveError.VERIFICATION_UNAVAILABLE) { directAudioOutputFlags(flags) }
    }

    @Test fun actualPcmLimitOffsetAndReadOnlyRepresentationReachTheAccountingUnchanged() {
        val f = Fixture(); val observed = f.read(Source(f.packets), 0)
        val state = f.accounting()
        queueDirectAudioInput(DirectNativeSample(observed.identity, observed.nativeBytes), state) {}
        val bytes = ByteBuffer.allocate(4104).apply { position(5); limit(4100) }.asReadOnlyBuffer()
        assertTrue(state.output(bytes, 4, 4096, 0, "audio/raw", 48_000, 2, true, false, false))
        assertEquals(5, bytes.position()); assertEquals(4100, bytes.limit())
        val another = f.accounting()
        queueDirectAudioInput(DirectNativeSample(observed.identity, observed.nativeBytes), another) {}
        failure(SaveError.VERIFICATION_UNAVAILABLE) { another.output(ByteBuffer.allocate(4096).apply { limit(4095) },
            0, 4096, 0, "audio/raw", 48_000, 2, true, false, false) }
    }

    @Test fun composedConservationRequiresActualAudioAndUsesMaximumEndpoint() {
        for ((videoCount, audioCount) in listOf(3 to 2, 2 to 10)) {
            val f = Fixture(count = audioCount, videoCount = videoCount)
            val video = f.video(); val audio = f.observedAudio()
            failure(SaveError.INVALID_MEDIA) { assembleDirectConservation(f.profile, video, null) }
            val actual = assembleDirectConservation(f.profile, video, audio)
            assertEquals(maxOf(f.profile.clock.endpoint.minimumUs, f.audio.clock.endpoint.minimumUs), actual.endpoint.minimumUs)
            failure(SaveError.VERIFICATION_UNAVAILABLE) { assembleDirectConservation(f.profile, video,
                audio.copy(pcm = audio.pcm.copy(pcmFrames = audio.pcm.pcmFrames - 1))) }
            failure(SaveError.INVALID_MEDIA) { assembleDirectConservation(f.profile, video,
                audio.copy(conservation = audio.conservation.copy(payloadSha256 = "a".repeat(64)))) }
        }
    }

    @Test fun preflightAcceptsOnlyCurrentSilentAndAudioPoliciesWithoutMintingEvidence() {
        val f = Fixture()
        val artifact = StagingArtifact("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
        val transfer = TransferResult(artifact.artifactId, f.bytes.size.toLong(), "video/quicktime", f.bytes.size.toLong())
        val proof = DirectNormalizationProof("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004",
            "a".repeat(64), artifact.artifactId, artifact.commitId, f.bytes.size.toLong(), "b".repeat(64),
            sourceTransferFingerprint(transfer), f.bytes.size.toLong(), "c".repeat(64), f.profile.conservation, policyRevision = 2)
        val context = OperationContext("audio-host-observation", Long.MAX_VALUE, CancellationSource())
        DirectNormalizedPreflight.check(artifact, ArtifactOutputDescriptor.NormalizedDirect(transfer, proof), context)
        failure(SaveError.RECOVERY_REQUIRED) { DirectNormalizedPreflight.check(artifact,
            ArtifactOutputDescriptor.NormalizedDirect(transfer, proof.copy(policyRevision = 1)), context) }
    }

    @Test fun productionEvidenceAssemblerMapsObservedAudioToReadableNeverEncrypted() {
        val f = Fixture(count = 10, videoCount = 2)
        val audio = f.observedAudio()
        val conservation = assembleDirectConservation(f.profile, f.video(), audio)
        val (artifact, output) = evidenceDescriptor(conservation, f.bytes.size.toLong())
        val evidence = assembleDirectVerificationEvidence(artifact, output, output.sizeBytes, output.sha256,
            conservation, fakeTail(conservation), DecodePosition.entries.toSet(), audio)
        assertFalse(evidence.encrypted)
        assertEquals(1, evidence.videoTrackCount); assertEquals(1, evidence.audioTrackCount)
        assertTrue(evidence.audioTrackReadable)
        assertEquals(conservation.endpoint.minimumUs / 1000, evidence.durationMillis)
        assertTrue(evidence.isValidFor(artifact, output, expectedAudio = true))
        assertTrue(evidence.isValidFor(artifact, output, expectedAudio = false))
        assertFalse(evidence.copy(audioTrackReadable = false).isValidFor(artifact, output, expectedAudio = true))
        assertFalse(evidence.copy(encrypted = true).isValidFor(artifact, output, expectedAudio = true))
    }

    @Test fun productionEvidenceAssemblerPreservesSilentAndExpectedAudioBehavior() {
        val bytes = OriginalMovFixtures.build(OriginalMovFixtures.Options(quickTime = false))
        val profile = MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(bytes))
        val conservation = profile.conservation
        val (artifact, output) = evidenceDescriptor(conservation, bytes.size.toLong())
        val evidence = assembleDirectVerificationEvidence(artifact, output, output.sizeBytes, output.sha256,
            conservation, fakeTail(conservation), DecodePosition.entries.toSet(), null)
        assertFalse(evidence.encrypted)
        assertEquals(1, evidence.videoTrackCount); assertEquals(0, evidence.audioTrackCount)
        assertFalse(evidence.audioTrackReadable)
        assertTrue(evidence.isValidFor(artifact, output, expectedAudio = false))
        assertFalse(evidence.isValidFor(artifact, output, expectedAudio = true))
    }

    @Test fun productionEvidenceAssemblerCannotFillMissingAudioOrWrongConservationAndHash() {
        val f = Fixture(); val audio = f.observedAudio()
        val conservation = assembleDirectConservation(f.profile, f.video(), audio)
        val (artifact, output) = evidenceDescriptor(conservation, f.bytes.size.toLong())
        val tail = fakeTail(conservation)
        failure(SaveError.INVALID_MEDIA) { assembleDirectVerificationEvidence(artifact, output, output.sizeBytes,
            output.sha256, conservation, tail, DecodePosition.entries.toSet(), null) }
        failure(SaveError.INVALID_MEDIA) { assembleDirectVerificationEvidence(artifact, output, output.sizeBytes,
            output.sha256, conservation.copy(width = conservation.width + 2), tail, DecodePosition.entries.toSet(), audio) }
        failure(SaveError.INVALID_MEDIA) { assembleDirectVerificationEvidence(artifact, output, output.sizeBytes,
            output.sha256, conservation, tail, DecodePosition.entries.toSet(),
            audio.copy(conservation = audio.conservation.copy(payloadSha256 = "e".repeat(64)))) }
        failure(SaveError.VERIFICATION_UNAVAILABLE) { assembleDirectVerificationEvidence(artifact, output, output.sizeBytes,
            output.sha256, conservation, tail, DecodePosition.entries.toSet(), audio.copy(pcm = audio.pcm.copy(outputEos = false))) }
        failure(SaveError.RESOURCE_CHANGED) { assembleDirectVerificationEvidence(artifact, output, output.sizeBytes,
            "d".repeat(64), conservation, tail, DecodePosition.entries.toSet(), audio) }
        val evidence = assembleDirectVerificationEvidence(artifact, output, output.sizeBytes, output.sha256,
            conservation, tail, DecodePosition.entries.toSet(), audio)
        val wrongDigest = evidence.copy(directNormalizationProof = requireNotNull(evidence.directNormalizationProof)
            .copy(normalizationProofSha256 = "f".repeat(64)))
        assertFalse(wrongDigest.isValidFor(artifact, output, expectedAudio = true))
        failure(SaveError.RECOVERY_REQUIRED) { assembleDirectVerificationEvidence(artifact,
            output.copy(normalizationProof = output.normalizationProof.copy(policyRevision = 1)), output.sizeBytes,
            output.sha256, conservation, tail, DecodePosition.entries.toSet(), audio) }
    }

    private fun evidenceDescriptor(conservation: DirectRemuxConservation, size: Long):
        Pair<StagingArtifact, ArtifactOutputDescriptor.NormalizedDirect> {
        val artifact = StagingArtifact("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
        val transfer = TransferResult(artifact.artifactId, size, "video/quicktime", size)
        val proof = DirectNormalizationProof("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004",
            "a".repeat(64), artifact.artifactId, artifact.commitId, size, "b".repeat(64), sourceTransferFingerprint(transfer),
            size, "c".repeat(64), conservation, policyRevision = if (conservation.audio == null) 1 else 2)
        return artifact to ArtifactOutputDescriptor.NormalizedDirect(transfer, proof)
    }

    /** Synthetic decoder-tail observation for value assembly tests, never native decoder evidence. */
    private fun fakeTail(conservation: DirectRemuxConservation) = DirectVideoTailProof(conservation.precedingVideoSync,
        conservation.video.lastSample, conservation.video.lastSample.presentationTimeUs, outputEos = true)

}

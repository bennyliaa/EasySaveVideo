package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic contracts only. These fixtures assert no actual MOV parser or muxer capability. */
class DirectNormalizationContractsTest {
    private fun hash(value: Char) = value.toString().repeat(64)
    private fun conservation(audio: Boolean = false): DirectRemuxConservation {
        val last = DirectSampleIdentity(29, 966_667, 4, hash('a'), 0)
        val video = DirectTrackConservation(DirectSampleKind.AVC_BASELINE, hash('1'), 30, 120,
            hash('2'), hash('3'), hash('4'), 0, last, DirectTrackEndpoint(1_000_000, 1_000_000))
        val sound = if (audio) DirectTrackConservation(DirectSampleKind.AAC_LC, hash('5'), 47, 188,
            hash('6'), hash('7'), hash('8'), 0, DirectSampleIdentity(46, 981_333, 4, hash('b'), 1),
            DirectTrackEndpoint(1_002_667, 1_002_667)) else null
        return DirectRemuxConservation(16, 16, 30, video, sound, if (audio) 48_000 else null,
            if (audio) 2 else null, DirectSampleIdentity(0, 0, 4, hash('c'), 1))
    }
    private fun record(rawSize: Long = 4096, outputSize: Long = 2048, audio: Boolean = false): SaveRecord {
        val request = SaveRequest("task", "key", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/raw")).asDurableReference())
        val artifact = StagingArtifact("artifact", "commit")
        val transfer = TransferResult("artifact", rawSize, "video/quicktime", rawSize)
        val c = conservation(audio)
        val proof = DirectNormalizationProof("00000000-0000-4000-8000-000000000001", "task", request.resource.sourceIdentity,
            "artifact", "commit", rawSize, hash('d'), sourceTransferFingerprint(transfer), outputSize, hash('e'), c)
        val independent = DirectNormalizationVerificationProof(proof.normalizationId, proof.taskId, proof.sourceIdentity,
            proof.artifactId, proof.commitId, proof.digest(), c,
            DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true))
        val evidence = VerificationEvidence("artifact", outputSize, hash('e'), true, 1, if (audio) 1 else 0,
            c.endpoint.minimumUs / 1000, 16, 16, false, DecodePosition.entries.toSet(), audio,
            directNormalizationProof = independent)
        return SaveRecord(request, TaskSnapshot("task", 5, TaskState.SUCCEEDED, TaskMethod.DIRECT), "commit", "operation", 10_000, 0,
            artifact = artifact, transfer = transfer, verification = evidence,
            receipt = MediaCommitReceipt("commit", "artifact", "media", outputSize, hash('e')),
            bytesTransferred = rawSize, directNormalization = proof)
    }

    @Test fun canonicalOutputCanBeLargerOrSmallerThanUnchangedRawHttpEvidence() {
        for ((raw, output) in listOf(4096L to 2048L, 2048L to 4096L)) {
            val r = record(raw, output)
            val selected = r.selectedOutputDescriptor() as ArtifactOutputDescriptor.NormalizedDirect
            assertSame(r.transfer, selected.sourceTransfer)
            assertEquals(raw, r.transfer!!.bytesOnDisk); assertEquals(raw, r.transfer!!.expectedTotalBytes)
            assertEquals("video/quicktime", r.transfer!!.responseMimeType)
            assertEquals(raw, r.bytesTransferred); assertEquals(output, selected.sizeBytes)
            assertEquals("video/mp4", selected.mimeType)
            assertTrue(r.verification!!.isValidFor(r.artifact!!, selected, false))
            assertFalse(r.verification!!.isValidFor(r.artifact!!, r.transfer!!, false))
            assertNull(r.transfer!!.streamExpectation)
        }
    }

    @Test fun equalSizesAndHashesAreNotTreatedAsConservationEvidence() {
        val r = record(4096, 4096); val p = r.directNormalization!!.copy(outputSha256 = r.directNormalization!!.rawSha256)
        val e = r.verification!!.copy(sha256 = p.outputSha256,
            directNormalizationProof = r.verification!!.directNormalizationProof!!.copy(normalizationProofSha256 = p.digest()))
        assertTrue(e.isValidFor(r.artifact!!, ArtifactOutputDescriptor.NormalizedDirect(r.transfer!!, p), false))
        assertFalse(e.copy(directNormalizationProof = null).isValidFor(r.artifact!!, ArtifactOutputDescriptor.NormalizedDirect(r.transfer!!, p), false))
    }

    @Test fun sourceFingerprintBindsExactMimeLengthPresenceAndCompletedExtent() {
        val r = record(); val t = r.transfer!!; val p = r.directNormalization!!
        val mutations = listOf(t.copy(artifactId = "other"), t.copy(bytesOnDisk = 4095),
            t.copy(responseMimeType = "video/mp4"), t.copy(responseMimeType = "VIDEO/QUICKTIME"),
            t.copy(expectedTotalBytes = null), t.copy(expectedTotalBytes = 4095))
        for (changed in mutations) {
            assertNotEquals(sourceTransferFingerprint(t), sourceTransferFingerprint(changed))
            assertFalse(p.matchesTransfer(changed))
            assertThrows(IllegalArgumentException::class.java) { ArtifactOutputDescriptor.NormalizedDirect(changed, p) }
        }
        val incomplete = t.copy(expectedTotalBytes = 4097)
        assertFalse(p.copy(sourceTransferSha256 = sourceTransferFingerprint(incomplete)).matchesTransfer(incomplete))
        val noHttpLength = t.copy(expectedTotalBytes = null)
        assertTrue(p.copy(sourceTransferSha256 = sourceTransferFingerprint(noHttpLength)).matchesTransfer(noHttpLength))
        assertThrows(IllegalArgumentException::class.java) {
            sourceTransferFingerprint(t.copy(streamExpectation = StreamArtifactExpectation(1000, 1, 0, 16, 16)))
        }
    }

    @Test fun exactTaskSourceArtifactCommitAndGenerationAreRequired() {
        val r = record(); val p = r.directNormalization!!; val t = r.transfer!!; val a = r.artifact!!
        assertTrue(p.matchesSource("task", r.request.resource.sourceIdentity, a, t))
        assertFalse(p.matchesSource("other", r.request.resource.sourceIdentity, a, t))
        assertFalse(p.matchesSource("task", hash('0'), a, t))
        assertFalse(p.matchesSource("task", r.request.resource.sourceIdentity, a.copy(artifactId = "other"), t))
        assertFalse(p.matchesSource("task", r.request.resource.sourceIdentity, a.copy(commitId = "other"), t))
        val independent = r.verification!!.directNormalizationProof!!
        for (changed in listOf(independent.copy(taskId = "other"), independent.copy(sourceIdentity = hash('0')),
            independent.copy(artifactId = "other"), independent.copy(commitId = "other"),
            independent.copy(normalizationId = "00000000-0000-4000-8000-000000000002"),
            independent.copy(normalizationProofSha256 = hash('0')))) {
            assertFalse(changed.matches(p))
            assertThrows(IllegalArgumentException::class.java) { r.copy(verification = r.verification!!.copy(directNormalizationProof = changed)) }
        }
    }

    @Test fun everyIndependentConservationDimensionAndActualTailMustMatch() {
        val r = record(audio = true); val p = r.directNormalization!!; val c = p.conservation
        val expected = r.verification!!.directNormalizationProof!!
        val variants = listOf(c.copy(width = 32), c.copy(frameRate = 25), c.copy(audioChannelCount = 1),
            c.copy(audioSampleRate = 44_100), c.copy(video = c.video.copy(codecConfigurationSha256 = hash('0'))),
            c.copy(video = c.video.copy(payloadSha256 = hash('0'))), c.copy(video = c.video.copy(sampleFlagsSha256 = hash('0'))),
            c.copy(video = c.video.copy(timingSha256 = hash('0'))), c.copy(video = c.video.copy(canonicalBytes = 121)),
            c.copy(video = c.video.copy(sampleCount = 31, lastSample = c.video.lastSample.copy(index = 30))),
            c.copy(video = c.video.copy(endpoint = DirectTrackEndpoint(1_000_001, 1_000_002))),
            c.copy(precedingVideoSync = c.precedingVideoSync.copy(sha256 = hash('0'))),
            c.copy(audio = c.audio!!.copy(payloadSha256 = hash('0'))))
        for (changed in variants) {
            assertFalse(expected.copy(conservation = changed).matches(p))
            assertNotEquals(p.digest(), p.copy(conservation = changed).digest())
        }
        assertFalse(expected.copy(videoTail = expected.videoTail.copy(outputEos = false)).matches(p))
        assertFalse(expected.copy(videoTail = expected.videoTail.copy(terminalOutputTimeUs = 0)).matches(p))
        assertFalse(expected.copy(videoTail = expected.videoTail.copy(lastSample = expected.videoTail.lastSample.copy(sha256 = hash('0')))).matches(p))
    }

    @Test fun independentOutputHashGeometryAudioAndNativeDecodeRemainMandatory() {
        val r = record(audio = true); val e = r.verification!!; val output = r.selectedOutputDescriptor()!!
        for (invalid in listOf(e.copy(sizeBytes = r.transfer!!.bytesOnDisk), e.copy(sha256 = hash('0')),
            e.copy(containerIsMp4 = false), e.copy(encrypted = true), e.copy(videoTrackCount = 2), e.copy(audioTrackCount = 0),
            e.copy(audioTrackReadable = false), e.copy(width = 32), e.copy(durationMillis = 2000),
            e.copy(decodedPositions = setOf(DecodePosition.START, DecodePosition.MIDDLE)), e.copy(directNormalizationProof = null))) {
            assertFalse(invalid.isValidFor(r.artifact!!, output, false))
        }
    }

    @Test fun directStreamAndRecordProofsCannotSubstituteForEachOtherOrExploitLegacyBranch() {
        val r = record(); val p = r.directNormalization!!
        assertThrows(IllegalArgumentException::class.java) { r.copy(directNormalization = null) }
        assertThrows(IllegalArgumentException::class.java) { r.copy(transfer = null) }
        assertThrows(IllegalArgumentException::class.java) {
            r.copy(checkpoint = DownloadCheckpoint("artifact", r.request.resource.sourceIdentity, 1, "\"etag\"", 4096))
        }
        val stream = r.transfer!!.copy(streamExpectation = StreamArtifactExpectation(1000, 1, 0, 16, 16))
        assertFalse(p.matchesTransfer(stream))
        assertThrows(IllegalArgumentException::class.java) { ArtifactOutputDescriptor.NormalizedDirect(stream, p) }
        assertThrows(IllegalArgumentException::class.java) {
            r.copy(request = r.request.copy(method = TaskMethod.STREAM), task = r.task.copy(method = TaskMethod.STREAM), transfer = stream)
        }
        assertFalse(r.verification!!.isValidFor(r.artifact!!, ArtifactOutputDescriptor.Transferred(stream), false))
        val ordinary = r.verification!!.copy(directNormalizationProof = null, sizeBytes = r.transfer!!.bytesOnDisk)
        assertTrue(ordinary.isValidFor(r.artifact!!, r.transfer!!, false))
        assertThrows(IllegalArgumentException::class.java) { r.copy(verification = ordinary) }
    }

    @Test fun tailToleranceIsBoundedAroundLastSampleRatherThanContainerEndpoint() {
        val r = record(); val p = r.directNormalization!!; val observed = r.verification!!.directNormalizationProof!!
        val tail = observed.videoTail; val last = p.conservation.video.lastSample.presentationTimeUs
        for (offset in -2L..2L) assertTrue(observed.copy(videoTail = tail.copy(terminalOutputTimeUs = last + offset)).matches(p))
        for (offset in listOf(-3L, 3L)) assertFalse(observed.copy(videoTail = tail.copy(terminalOutputTimeUs = last + offset)).matches(p))
        assertFalse(observed.copy(videoTail = tail.copy(terminalOutputTimeUs = p.conservation.endpoint.maximumUs)).matches(p))
        assertFalse(observed.copy(videoTail = tail.copy(terminalOutputTimeUs = last, outputEos = false)).matches(p))
        val e = r.verification!!; val output = r.selectedOutputDescriptor()!!
        assertTrue(e.copy(durationMillis = 999).isValidFor(r.artifact!!, output, false))
        assertTrue(e.copy(durationMillis = 1001).isValidFor(r.artifact!!, output, false))
        assertFalse(e.copy(durationMillis = 998).isValidFor(r.artifact!!, output, false))
        assertFalse(e.copy(durationMillis = 1002).isValidFor(r.artifact!!, output, false))
        assertFalse(e.copy(durationMillis = Long.MAX_VALUE).isValidFor(r.artifact!!, output, false))
    }

    @Test fun malformedAndOversizedProofContractsFailBeforePersistence() {
        val p = record().directNormalization!!; val c = p.conservation; val v = c.video
        val invalid = listOf<() -> Any>(
            { p.copy(normalizationId = "not-a-uuid") }, { p.copy(schemaVersion = 2) }, { p.copy(engineRevision = 2) },
            { p.copy(policyRevision = 2) }, { p.copy(noEncoding = false) }, { p.copy(rawSizeBytes = 0) },
            { p.copy(outputSizeBytes = DirectNormalizationLimits.MAX_BYTES + 1) }, { p.copy(rawSha256 = "F".repeat(64)) },
            { p.copy(sourceIdentity = "a".repeat(65)) }, { c.copy(width = 1922) }, { c.copy(width = 17) },
            { c.copy(width = 1920, height = 1920) }, { c.copy(frameRate = 61) }, { c.copy(sourceOriginOffsetUs = 1) },
            { c.copy(audioSampleRate = 48000) }, { c.copy(representationVersion = 2) },
            { v.copy(sampleCount = 108001) }, { v.copy(canonicalBytes = Long.MAX_VALUE) },
            { v.copy(firstPresentationTimeUs = 1) }, { v.copy(representationVersion = 2) },
            { v.lastSample.copy(canonicalBytes = 4 * 1024 * 1024 + 1) }, { v.lastSample.copy(flags = 2) },
            { DirectTrackEndpoint(1, Long.MAX_VALUE) }, { DirectTrackEndpoint(100, 103) },
            { DirectTrackEndpoint(-1, 1) })
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }
}

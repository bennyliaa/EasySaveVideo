package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class SaveRecordCodecTest {
    private fun record(): SaveRecord {
        val artifact = StagingArtifact("artifact", "commit")
        return SaveRecord(SaveRequest("task", "idempotency", SelectedMediaResource("candidate", "variant",
            SourceUrl("https://example.invalid/private?query=retained-functional-state"), true, null, 1500).asDurableReference()),
            TaskSnapshot("task", 3, TaskState.WAITING_USER, TaskMethod.DIRECT, 42, null, UserWait(2000, ResumeAction.REVIEW_RECOVERY)),
            "commit", "operation", 1000, 2, 1, SavePhase.EXECUTING, artifact,
            TransferResult("artifact", 100, "video/mp4", 200), null, null, 150,
            DownloadCheckpoint("artifact", "a".repeat(64), 100, "\"strong-etag\"", 200), SaveWaitReason.RECOVERY, SaveError.RECOVERY_REQUIRED)
    }
    @Test fun wifiPolicyWaitRoundTripsWithoutChangingJournalFormat() {
        val value = record().copy(waitReason = SaveWaitReason.NETWORK, error = SaveError.WIFI_REQUIRED)
        val encoded = SaveRecordCodec.encode(value)
        assertEquals(9, encoded[7].toInt())
        assertEquals(value, SaveRecordCodec.decode(encoded))
    }
    @Test fun functionalRecoveryStateRoundTripsWithoutRequestCredentials() {
        val value = record()
        assertEquals(value, SaveRecordCodec.decode(SaveRecordCodec.encode(value)))
        val encodedText = SaveRecordCodec.encode(value).toString(Charsets.ISO_8859_1)
        assertFalse(encodedText.contains("retained-functional-state"))
        assertFalse(encodedText.contains("/private"))
        assertEquals("https://example.invalid/", SaveRecordCodec.decode(SaveRecordCodec.encode(value)).request.resource.url.value)
        assertTrue(SaveRecordCodec.decode(SaveRecordCodec.encode(value)).request.resource.durableReference)
        assertNull(SaveRecordCodec.decode(SaveRecordCodec.encode(value)).request.resource.requestContextRef)
        assertEquals("\"strong-etag\"", SaveRecordCodec.decode(SaveRecordCodec.encode(value)).checkpoint!!.strongEtag)
    }
    @Test fun verificationAndCommitReceiptRoundTrip() {
        val start = record()
        val proof = VerificationEvidence("artifact", 100, "a".repeat(64), true, 1, 1, 5000, 1280, 720,
            false, DecodePosition.entries.toSet(), true)
        val success = start.copy(task = start.task.copy(state = TaskState.SUCCEEDED, waitingUser = null),
            waitReason = null, transfer = start.transfer!!.copy(expectedTotalBytes = 100), verification = proof, receipt = MediaCommitReceipt("commit", "artifact", "media", 100, "a".repeat(64)))
        assertEquals(success, SaveRecordCodec.decode(SaveRecordCodec.encode(success)))
    }
    @Test fun truncationUnknownSchemaAndTrailingBytesFailClosed() {
        val bytes = SaveRecordCodec.encode(record())
        assertThrows(Exception::class.java) { SaveRecordCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes.copyOf().also { it[7] = 10 }) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(ByteArray(SaveRecordCodec.MAX_BYTES + 1)) }
    }
    @Test fun streamConstraintsRoundTripAsTechnicalNumbersOnly() {
        val value = record().let { old -> old.copy(request = old.request.copy(method = TaskMethod.STREAM),
            task = old.task.copy(method = TaskMethod.STREAM), checkpoint = null,
            transfer = old.transfer!!.copy(streamExpectation = StreamArtifactExpectation(5000, 1, 1, 1280, 720))) }
        assertEquals(value, SaveRecordCodec.decode(SaveRecordCodec.encode(value)))
        assertFalse(SaveRecordCodec.encode(value).toString(Charsets.ISO_8859_1).contains("retained-functional-state"))
    }
    @Test fun genuineV1ThroughV6DirectTransfersKeepEvidenceAndNeverGainNormalizationProof() {
        val start = record()
        val proof = VerificationEvidence("artifact", 100, "a".repeat(64), true, 1, 1, 5000, 1280, 720,
            false, DecodePosition.entries.toSet(), true)
        val value = start.copy(task = start.task.copy(state = TaskState.SUCCEEDED, waitingUser = null), waitReason = null,
            transfer = start.transfer!!.copy(expectedTotalBytes = 100), verification = proof,
            receipt = MediaCommitReceipt("commit", "artifact", "media", 100, "a".repeat(64)))
        for (version in 1..6) {
            val decoded = SaveRecordCodec.decode(legacyRecord(value, version))
            assertEquals(value, decoded); assertNull(decoded.directNormalization)
            assertNull(decoded.verification!!.directNormalizationProof)
        }
    }
    @Test fun genuineLegacyV2StreamRemainsReadableButNeverAcquiresProof() {
        val start = record()
        val proof = VerificationEvidence("artifact", 100, "a".repeat(64), true, 1, 1, 5000, 1280, 720,
            false, DecodePosition.entries.toSet(), true)
        val value = start.copy(request = start.request.copy(method = TaskMethod.STREAM),
            task = start.task.copy(method = TaskMethod.STREAM, state = TaskState.RUNNING, waitingUser = null), waitReason = null,
            transfer = start.transfer!!.copy(expectedTotalBytes = 100,
                streamExpectation = StreamArtifactExpectation(5000, 1, 1, 1280, 720)), verification = proof,
            receipt = MediaCommitReceipt("commit", "artifact", "media", 100, "a".repeat(64)))
        val decoded = SaveRecordCodec.decode(legacyRecord(value, 2))
        val decodedTransfer = requireNotNull(decoded.transfer); val decodedVerification = requireNotNull(decoded.verification)
        assertEquals(value, decoded); assertNull(decodedTransfer.streamExpectation!!.conservation)
        assertNull(decodedVerification.streamConservation); assertNull(decodedVerification.streamTailProof)
        assertFalse(decodedVerification.isValidFor(decoded.artifact!!, decodedTransfer, true))
        assertEquals(decoded, SaveRecordCodec.decode(SaveRecordCodec.encode(decoded)))
    }
    @Test fun malformedDurableStreamCannotFallBackToDirectValidationOnRecovery() {
        val encoded = SaveRecordCodec.encode(record())
        val direct = "DIRECT".toByteArray(Charsets.US_ASCII)
        val stream = "STREAM".toByteArray(Charsets.US_ASCII)
        var count = 0
        for (offset in 0..encoded.size - direct.size) {
            if (direct.indices.all { encoded[offset + it] == direct[it] }) {
                stream.copyInto(encoded, offset); count++
            }
        }
        assertEquals(2, count)
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(encoded) }
    }
    @Test fun durableRecordCannotContainEphemeralRequestLease() {
        val value = record()
        assertThrows(IllegalArgumentException::class.java) {
            value.copy(request = value.request.copy(resource = value.request.resource.copy(requestContextRef = RequestContextRef("lease"))))
        }
    }
    private fun conservation(): StreamConservationContract {
        val video = StreamSampleAccumulator(StreamSampleKind.AVC)
        repeat(20) { index -> video.add(index * 100_000L, if (index % 10 == 0) 1 else 0,
            byteArrayOf(0, 0, 1, if (index % 10 == 0) 0x65 else 0x41, (index + 1).toByte())) }
        val audio = StreamSampleAccumulator(StreamSampleKind.AAC, 48_000)
        repeat(94) { index -> audio.add(index * 1_024_000_000L / 48_000, 1, byteArrayOf(1, index.toByte(), 3)) }
        return StreamConservationContract(video.finish(), audio.finish(), -10_000, video.videoTail(), video.endpoint(), 2)
    }
    private fun provenStream(): SaveRecord {
        val c = conservation(); val old = record()
        val proof = VerificationEvidence("artifact", 100, "a".repeat(64), true, 1, 1, 2000, 16, 16,
            false, DecodePosition.entries.toSet(), true, c,
            StreamTailProof(c.videoTail.precedingSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true))
        return old.copy(request = old.request.copy(method = TaskMethod.STREAM),
            task = old.task.copy(method = TaskMethod.STREAM, state = TaskState.SUCCEEDED, waitingUser = null), waitReason = null,
            transfer = old.transfer!!.copy(expectedTotalBytes = 100, streamExpectation = StreamArtifactExpectation(2000, 1, 1, 16, 16, c)),
            checkpoint = null, verification = proof, receipt = MediaCommitReceipt("commit", "artifact", "media", 100, "a".repeat(64)))
    }
    @Test fun privateConservedHlsSuccessPublishesWithRandomMetadataAndPreservesAudioRequirements() {
        val old = provenStream()
        val binding = PrivateNetworkSourceSeed.create().bindTask("task")
        val source = SelectedMediaResource("private-candidate", "private-variant", SourceUrl("https://private-hls.invalid/secret"),
            expectedAudio = true, sourceIdentity = binding.sourceIdentity.encoded, privateSource = binding)
        val value = old.copy(request = SaveRequest("task", "idempotency", source, TaskMethod.STREAM).asDurableReference())
        val bytes = SaveRecordCodec.encode(value)
        val actual = com.easysavevideo.app.library.storage.LibraryPublicationEvidence.decode(bytes, legacy = false)
        assertEquals(value, actual)
        assertTrue(com.easysavevideo.app.save.mediaTaskHasCommittedSave("task", actual))
        assertNull(actual.request.networkResource)
        assertTrue(actual.request.networkSourceMetadata!!.expectedAudio)
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("private-hls.invalid"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(source.url.identity()))
        val privateInput = value.request.input as SaveInput.PrivateNetwork
        val originalTransfer = value.transfer!!
        val originalExpectation = originalTransfer.streamExpectation!!
        val silentContract = originalExpectation.conservation!!.copy(
            audio = null, audioStartOffsetUs = null, audioChannelCount = null)
        val silent = value.copy(
            request = value.request.copy(input = SaveInput.PrivateNetwork(privateInput.source.copy(expectedAudio = false))),
            transfer = originalTransfer.copy(streamExpectation = originalExpectation.copy(
                audioTrackCount = 0, conservation = silentContract)),
            verification = value.verification!!.copy(audioTrackCount = 0, audioTrackReadable = false,
                streamConservation = silentContract))
        assertEquals(silent, com.easysavevideo.app.library.storage.LibraryPublicationEvidence.decode(SaveRecordCodec.encode(silent), false))
        // Change only the private metadata expectation, retaining otherwise valid silent evidence.
        assertThrows(IllegalArgumentException::class.java) {
            silent.copy(request = silent.request.copy(input = SaveInput.PrivateNetwork(privateInput.source.copy(expectedAudio = true))))
        }
    }
    @Test fun v5PersistsEveryConservationAndActualEosFieldInBoundedSpace() {
        val value = provenStream(); val encoded = SaveRecordCodec.encode(value)
        assertEquals(9, encoded[7].toInt()); assertTrue(encoded.size < 4096)
        assertEquals(value, SaveRecordCodec.decode(encoded))
        assertEquals(value.verification, SaveRecordCodec.evidence(SaveRecordCodec.evidenceBytes(value.verification!!)))
        assertEquals(-10_000L, SaveRecordCodec.decode(encoded).transfer!!.streamExpectation!!.conservation!!.audioStartOffsetUs)
    }
    @Test fun standaloneGenuineLegacyEvidenceRemainsUnproven() {
        val proof = provenStream().verification!!.copy(streamConservation = null, streamTailProof = null)
        val decoded = SaveRecordCodec.evidence(legacyEvidence(proof))
        assertEquals(proof, decoded); assertNull(decoded.streamConservation); assertNull(decoded.streamTailProof)
        assertEquals(decoded, SaveRecordCodec.evidence(SaveRecordCodec.evidenceBytes(decoded)))
    }
    @Test fun newStreamRecordCannotPersistMismatchedSequenceOrEarlyEosAsVerified() {
        val value = provenStream(); val evidence = value.verification!!
        assertThrows(IllegalArgumentException::class.java) { value.copy(verification = evidence.copy(streamTailProof = null)) }
        assertThrows(IllegalArgumentException::class.java) {
            value.copy(verification = evidence.copy(streamTailProof = evidence.streamTailProof!!.copy(outputEos = false)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            value.copy(verification = evidence.copy(streamConservation = evidence.streamConservation!!.let {
                it.copy(video = it.video.copy(sequenceSha256 = "b".repeat(64))) }))
        }
    }
    @Test fun invalidPersistedHashOrCountRejectsInsteadOfDowngrading() {
        val value = provenStream(); val original = SaveRecordCodec.encode(value)
        val hash = value.transfer!!.streamExpectation!!.conservation!!.video.sequenceSha256.toByteArray()
        val offset = (0..original.size - hash.size).first { index -> hash.indices.all { original[index + it] == hash[it] } }
        assertThrows(IllegalArgumentException::class.java) {
            SaveRecordCodec.decode(original.copyOf().also { it[offset] = 'Z'.code.toByte() })
        }
        // Sequence count is the long before canonicalBytes and the UTF hash length.
        assertThrows(IllegalArgumentException::class.java) {
            SaveRecordCodec.decode(original.copyOf().also { java.nio.ByteBuffer.wrap(it, offset - 18, 8).putLong(250_001) })
        }
    }
    @Test fun allV5TruncationsAndStandaloneUnknownVersionFailClosed() {
        val bytes = SaveRecordCodec.encode(provenStream())
        for (size in bytes.indices) assertThrows(Exception::class.java) { SaveRecordCodec.decode(bytes.copyOf(size)) }
        val evidence = SaveRecordCodec.evidenceBytes(provenStream().verification!!)
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(evidence.copyOf().also { it[7] = 6 }) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(evidence + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(ByteArray(4097)) }
    }
    @Test fun genuineFrozenV3StreamAndOwnedEvidenceKeepFullTailProof() {
        val value = provenStream()
        val bytes = legacyRecord(value, 3)
        assertEquals(3, bytes[7].toInt())
        assertEquals(value, SaveRecordCodec.decode(bytes))
        val evidence = value.verification!!
        val standalone = java.io.ByteArrayOutputStream().also { out -> java.io.DataOutputStream(out).use {
            it.writeInt(0x45535645); it.writeInt(1); it.write(frozenV3Evidence(evidence))
        } }.toByteArray()
        assertEquals(evidence, SaveRecordCodec.evidence(standalone))
        assertNull(SaveRecordCodec.evidence(standalone).recordingProof)
    }
    private fun capturedRecord(audio: Boolean = false): SaveRecord {
        val descriptor = CaptureDescriptor("capture-task", 9,
            if (audio) RecordingAudioChoice.INTERNAL else RecordingAudioChoice.NONE)
        val video = RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)
        val sound = if (audio) RecordingTrackEvidence(47, 0, 981_333, 21_333) else null
        val duration = maxOf(video.endPtsUs, sound?.endPtsUs ?: 0)
        val expectation = RecordingArtifactExpectation(descriptor, descriptor.requestedProfile,
            if (audio) RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN else RecordingAudioOutcome.NO_AUDIO_REQUESTED,
            RecordingStopReason.SYSTEM_STOPPED, video, sound, duration, 1024)
        val proof = RecordingVerificationProof(video, sound, "video/avc", if (audio) "audio/mp4a-latm" else null,
            if (audio) 48_000 else null, if (audio) 2 else null, video.lastPtsUs, true, decodedResumeBoundaries = emptyList())
        val evidence = VerificationEvidence("capture-artifact", 1024, "b".repeat(64), true, 1, if (audio) 1 else 0,
            duration / 1000, 1280, 720, false, DecodePosition.entries.toSet(), audio, recordingProof = proof)
        return SaveRecord(SaveRequest(descriptor.sessionId, "capture-key", SaveInput.Capture(descriptor)),
            TaskSnapshot(descriptor.sessionId, 5, TaskState.SUCCEEDED, TaskMethod.RECORD), "capture-commit", "capture-operation", 1000, 0,
            artifact = StagingArtifact("capture-artifact", "capture-commit"),
            transfer = TransferResult("capture-artifact", 1024, "video/mp4", 1024, recordingExpectation = expectation),
            verification = evidence, receipt = MediaCommitReceipt("capture-commit", "capture-artifact", "capture-media", 1024, "b".repeat(64)))
    }
    @Test fun v5CaptureWithAudioAndWithoutAudioRoundTripsWithoutNetworkSourceOrGrant() {
        for (audio in listOf(false, true)) {
            val value = capturedRecord(audio); val encoded = SaveRecordCodec.encode(value)
            assertEquals(9, encoded[7].toInt()); assertEquals(value, SaveRecordCodec.decode(encoded))
            assertNull(SaveRecordCodec.decode(encoded).request.networkResource)
            val text = encoded.toString(Charsets.ISO_8859_1)
            assertFalse(text.contains("https")); assertFalse(text.contains("NETWORK"))
            assertEquals(value.verification, SaveRecordCodec.evidence(SaveRecordCodec.evidenceBytes(value.verification!!)))
        }
    }
    @Test fun malformedV5DiscriminatorCaptureTailAndEveryTruncationFailClosed() {
        val bytes = SaveRecordCodec.encode(capturedRecord())
        val corrupt = bytes.copyOf(); val source = "CAPTURE".toByteArray(); val target = "UNKNOWN".toByteArray()
        val at = (0..corrupt.size - source.size).first { offset -> source.indices.all { corrupt[offset + it] == source[it] } }
        target.copyInto(corrupt, at)
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(corrupt) }
        for (size in bytes.indices) assertThrows(Exception::class.java) { SaveRecordCodec.decode(bytes.copyOf(size)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes + byteArrayOf(0)) }
        val value = capturedRecord()
        assertThrows(IllegalArgumentException::class.java) {
            value.copy(verification = value.verification!!.let { it.copy(recordingProof = it.recordingProof!!.copy(videoOutputEos = false)) })
        }
        assertThrows(IllegalArgumentException::class.java) { value.copy(bytesTransferred = 1) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(retryBudget = 1) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(transfer = value.transfer!!.copy(recordingExpectation = null)) }
    }
    @Test fun frozenV4CaptureAndEvidenceV2StayReadableButNeverGainResumeProof() {
        for (audio in listOf(false, true)) {
            val value = capturedRecord(audio)
            val bytes = legacyRecord(value, 4)
            assertEquals(4, bytes[7].toInt())
            val old = SaveRecordCodec.decode(bytes)
            assertEquals(value.receipt, old.receipt)
            val transfer = requireNotNull(old.transfer); val verified = requireNotNull(old.verification)
            val expected = transfer.recordingExpectation!!
            val proof = verified.recordingProof!!
            assertNull(expected.resumeBoundaries); assertNull(proof.decodedResumeBoundaries)
            assertFalse(verified.isValidFor(old.artifact!!, transfer, false))
            assertEquals(old, SaveRecordCodec.decode(SaveRecordCodec.encode(old)))
            val standalone = java.io.ByteArrayOutputStream().also { out -> java.io.DataOutputStream(out).use {
                it.writeInt(0x45535645); it.writeInt(2); it.write(frozenV4Evidence(value.verification!!))
            } }.toByteArray()
            val owned = SaveRecordCodec.evidence(standalone)
            assertNull(owned.recordingProof!!.decodedResumeBoundaries)
            assertFalse(owned.isValidFor(value.artifact!!, value.transfer!!, audio))
            assertEquals(owned, SaveRecordCodec.evidence(SaveRecordCodec.evidenceBytes(owned)))
        }
        for (value in listOf(record(), provenStream()))
            assertEquals(value, SaveRecordCodec.decode(legacyRecord(value, 4)))
    }

    private fun resumedRecord(): SaveRecord {
        val value = capturedRecord()
        val transfer = requireNotNull(value.transfer); val verified = requireNotNull(value.verification)
        val video = RecordingTrackEvidence(34, 0, 990_000, 10_000, 34, true)
        val boundaries = (1..32).map { RecordingResumeBoundary(it.toLong(), it * 30_000L) }
        return value.copy(transfer = transfer.copy(recordingExpectation =
            transfer.recordingExpectation!!.copy(video = video, resumeBoundaries = boundaries)),
            verification = verified.copy(recordingProof = verified.recordingProof!!.copy(
                video = video, terminalVideoOutputTimeUs = video.lastPtsUs, decodedResumeBoundaries = boundaries)))
    }

    @Test fun v5AndEvidenceV3PersistAllThirtyTwoIndependentBoundaries() {
        val value = resumedRecord(); val bytes = SaveRecordCodec.encode(value)
        assertEquals(9, bytes[7].toInt()); assertTrue(bytes.size < SaveRecordCodec.MAX_BYTES)
        assertEquals(value, SaveRecordCodec.decode(bytes))
        val owned = SaveRecordCodec.evidenceBytes(value.verification!!)
        assertEquals(5, owned[7].toInt()); assertTrue(owned.size < 4096)
        assertEquals(value.verification, SaveRecordCodec.evidence(owned))
        assertEquals(32, SaveRecordCodec.decode(bytes).transfer!!.recordingExpectation!!.resumeBoundaries!!.size)
    }

    @Test fun malformedResumeCountsIdentityOrderAndStandaloneTruncationFailClosed() {
        val value = resumedRecord(); val bytes = SaveRecordCodec.encode(value)
        val marker = java.nio.ByteBuffer.allocate(16).putLong(1).putLong(30_000).array()
        val offsets = (0..bytes.size - marker.size).filter { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
        assertEquals(2, offsets.size) // Expectation and independently observed proof.
        for (at in offsets) {
            for (count in listOf(-1, 33, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
                SaveRecordCodec.decode(bytes.copyOf().also { java.nio.ByteBuffer.wrap(it, at - 4, 4).putInt(count) })
            }
            for (index in listOf(0L, 34L)) assertThrows(IllegalArgumentException::class.java) {
                SaveRecordCodec.decode(bytes.copyOf().also { java.nio.ByteBuffer.wrap(it, at, 8).putLong(index) })
            }
            for (pts in listOf(0L, 990_001L)) assertThrows(IllegalArgumentException::class.java) {
                SaveRecordCodec.decode(bytes.copyOf().also { java.nio.ByteBuffer.wrap(it, at + 8, 8).putLong(pts) })
            }
            assertThrows(IllegalArgumentException::class.java) {
                SaveRecordCodec.decode(bytes.copyOf().also { marker.copyInto(it, at + 16) })
            }
        }
        val evidence = SaveRecordCodec.evidenceBytes(value.verification!!)
        for (size in evidence.indices) assertThrows(Exception::class.java) { SaveRecordCodec.evidence(evidence.copyOf(size)) }
    }

    @Test fun genuineFrozenV5BytesRemainReadableWithNoInventedRegionScope() {
        val expected = linkedMapOf("record" to record(), "provenStream" to provenStream(),
            "capture-false" to capturedRecord(false), "capture-true" to capturedRecord(true), "resumedRecord" to resumedRecord())
        expected.forEach { (name, value) ->
            val bytes = requireNotNull(javaClass.getResourceAsStream("/save-codec-v5/$name.bin")).use { it.readBytes() }
            assertEquals(5, bytes[7].toInt())
            val decoded = SaveRecordCodec.decode(bytes)
            assertEquals(value, decoded)
            assertNull(decoded.request.captureDescriptor?.regionCaptureRecipe)
            assertEquals(decoded, SaveRecordCodec.decode(SaveRecordCodec.encode(decoded)))
        }
    }

    @Test fun v6RegionIntentionRoundTripsWithoutProjectionAuthority() {
        val old = capturedRecord()
        val region = RegionCaptureRecipe(RegionNormalizedRect(0.1, 0.1, 0.9, 0.9),
            CaptureDisplayGeometry(1600, 900, 0, CaptureInsets(0, 20, 0, 20)),
            CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY, 25_000)
        val descriptor = old.request.captureDescriptor!!.copy(regionCaptureRecipe = region)
        val value = old.copy(request = old.request.copy(input = SaveInput.Capture(descriptor)),
            transfer = old.transfer!!.copy(recordingExpectation = old.transfer!!.recordingExpectation!!.copy(descriptor = descriptor)))
        val bytes = SaveRecordCodec.encode(value)
        assertEquals(9, bytes[7].toInt()); assertEquals(value, SaveRecordCodec.decode(bytes))
        assertEquals(region, SaveRecordCodec.decode(bytes).request.captureDescriptor!!.regionCaptureRecipe)
        for (length in bytes.indices) assertThrows(Exception::class.java) { SaveRecordCodec.decode(bytes.copyOf(length)) }
    }

    private fun writeFrozenV4Profile(o: java.io.DataOutputStream, p: RecordingProfile) {
        o.writeInt(p.width); o.writeInt(p.height); o.writeInt(p.frameRate); o.writeInt(p.videoBitrate)
        o.writeInt(p.audioSampleRate); o.writeInt(p.audioChannels); o.writeInt(p.audioBitrate)
    }
    private fun writeFrozenV4Descriptor(o: java.io.DataOutputStream, d: CaptureDescriptor) {
        o.writeUTF(d.sessionId); o.writeLong(d.generation); o.writeUTF(d.audioChoice.name); writeFrozenV4Profile(o, d.requestedProfile)
    }
    private fun writeFrozenV4Track(o: java.io.DataOutputStream, t: RecordingTrackEvidence) {
        o.writeLong(t.sampleCount); o.writeLong(t.firstPtsUs); o.writeLong(t.lastPtsUs); o.writeLong(t.lastDurationUs)
        o.writeLong(t.syncSampleCount); o.writeBoolean(t.firstSampleIsKeyFrame)
    }
    private fun writeFrozenV4Expectation(o: java.io.DataOutputStream, e: RecordingArtifactExpectation) {
        writeFrozenV4Descriptor(o, e.descriptor); writeFrozenV4Profile(o, e.actualProfile)
        o.writeUTF(e.audioOutcome.name); o.writeUTF(e.stopReason.name); writeFrozenV4Track(o, e.video)
        o.oldNullable(e.audio) { writeFrozenV4Track(o, it) }; o.writeLong(e.effectiveDurationUs); o.writeLong(e.bytesWritten)
        o.writeBoolean(e.finalized); o.writeBoolean(e.resourcesReleased)
    }
    private fun frozenV4Evidence(e: VerificationEvidence): ByteArray = java.io.ByteArrayOutputStream().also { buffer ->
        java.io.DataOutputStream(buffer).use { o ->
            o.write(frozenV3Evidence(e))
            o.oldNullable(e.recordingProof) { p ->
                writeFrozenV4Track(o, p.video); o.oldNullable(p.audio) { writeFrozenV4Track(o, it) }
                o.writeUTF(p.videoMimeType); o.oldNullable(p.audioMimeType) { o.writeUTF(it) }
                o.oldNullable(p.audioSampleRate) { o.writeInt(it) }; o.oldNullable(p.audioChannelCount) { o.writeInt(it) }
                o.writeLong(p.terminalVideoOutputTimeUs); o.writeBoolean(p.videoOutputEos)
            }
        }
    }.toByteArray()

    /** Exact frozen v3 body layout, separate from the evolving production encoder. */
    private fun frozenV3Evidence(e: VerificationEvidence): ByteArray = java.io.ByteArrayOutputStream().also { buffer ->
        java.io.DataOutputStream(buffer).use { o ->
            o.write(legacyEvidence(e)); o.oldNullable(e.streamConservation) { writeFrozenV3Conservation(o, it) }
            o.oldNullable(e.streamTailProof) {
                writeFrozenV3Sample(o, it.precedingSync); writeFrozenV3Sample(o, it.lastSample)
                o.writeLong(it.terminalOutputTimeUs); o.writeBoolean(it.outputEos)
            }
        }
    }.toByteArray()
    private fun writeFrozenV3Conservation(o: java.io.DataOutputStream, c: StreamConservationContract) {
        fun sequence(s: StreamSampleSequence) {
            o.writeUTF(s.kind.name); o.writeInt(s.timescale); o.writeLong(s.sampleCount)
            o.writeLong(s.canonicalBytes); o.writeUTF(s.sequenceSha256); writeFrozenV3Sample(o, s.lastSample)
        }
        sequence(c.video); o.oldNullable(c.audio) { sequence(it) }; o.oldNullable(c.audioStartOffsetUs) { o.writeLong(it) }
        writeFrozenV3Sample(o, c.videoTail.lastSample); writeFrozenV3Sample(o, c.videoTail.precedingSync)
        o.writeUTF(c.endpoint.rule.name); o.writeLong(c.endpoint.minimumUs); o.writeLong(c.endpoint.maximumUs)
        o.oldNullable(c.audioChannelCount) { o.writeInt(it) }
    }
    private fun writeFrozenV3Sample(o: java.io.DataOutputStream, s: StreamSampleIdentity) {
        o.writeLong(s.index); o.writeLong(s.presentationTimeUs); o.writeInt(s.canonicalBytes); o.writeUTF(s.sha256); o.writeInt(s.flags)
    }
    /** Frozen independent old wire encoders: v1 has no Stream field; v2 has no tail; v3 has no capture. */
    private fun legacyRecord(record: SaveRecord, version: Int): ByteArray = java.io.ByteArrayOutputStream().also { buffer ->
        java.io.DataOutputStream(buffer).use { o ->
            o.writeInt(0x45535633); o.writeInt(version)
            val r = record.request; val t = record.task
            o.writeUTF(r.requestId); o.writeUTF(r.idempotencyKey); o.writeUTF(r.method.name); o.writeUTF(r.origin.name)
            when (val input = r.input) {
                is SaveInput.Network -> {
                    if (version >= 4) o.writeUTF("NETWORK")
                    val s = input.resource
                    o.writeUTF(s.candidateId); o.writeUTF(s.variantId); o.writeUTF(s.url.value); o.writeBoolean(s.expectedAudio)
                    o.oldNullable(s.expiresAtEpochMillis) { o.writeLong(it) }; o.writeUTF(s.sourceIdentity); o.writeBoolean(s.durableReference)
                }
                is SaveInput.PrivateNetwork -> error("Private input has no legacy wire encoding")
                is SaveInput.Capture -> { require(version == 4); o.writeUTF("CAPTURE"); writeFrozenV4Descriptor(o, input.descriptor) }
            }
            o.writeUTF(t.taskId); o.writeLong(t.revision); o.writeUTF(t.state.name); o.writeUTF(t.method.name)
            o.oldNullable(t.progressPercent) { o.writeInt(it) }; o.oldNullable(t.errorCode) { o.writeUTF(it.name) }
            o.oldNullable(t.waitingUser) { o.writeLong(it.expiresAtEpochMillis); o.writeUTF(it.resumeAction.name) }
            o.writeUTF(record.commitId); o.writeUTF(record.operationId); o.writeLong(record.deadlineEpochMillis)
            o.writeInt(record.retryBudget); o.writeInt(record.attempt); o.oldNullable(record.phase) { o.writeUTF(it.name) }
            o.oldNullable(record.artifact) { o.writeUTF(it.artifactId); o.writeUTF(it.commitId) }
            o.oldNullable(record.transfer) { transfer ->
                o.writeUTF(transfer.artifactId); o.writeLong(transfer.bytesOnDisk); o.writeUTF(transfer.responseMimeType)
                o.oldNullable(transfer.expectedTotalBytes) { o.writeLong(it) }
                if (version >= 2) o.oldNullable(transfer.streamExpectation) {
                    o.writeLong(it.durationMillis); o.writeInt(it.videoTrackCount); o.writeInt(it.audioTrackCount)
                    o.writeInt(it.width); o.writeInt(it.height)
                    if (version >= 3) o.oldNullable(it.conservation) { proof -> writeFrozenV3Conservation(o, proof) }
                }
                if (version >= 4) o.oldNullable(transfer.recordingExpectation) { writeFrozenV4Expectation(o, it) }
            }
            o.oldNullable(record.verification) { o.write(if (version >= 4) frozenV4Evidence(it) else if (version >= 3) frozenV3Evidence(it) else legacyEvidence(it)) }
            o.oldNullable(record.receipt) { o.writeUTF(it.commitId); o.writeUTF(it.artifactId); o.writeUTF(it.mediaId); o.writeLong(it.sizeBytes); o.writeUTF(it.sha256) }
            o.writeLong(record.bytesTransferred)
            o.oldNullable(record.checkpoint) { o.writeUTF(it.artifactId); o.writeUTF(it.resourceIdentity); o.writeLong(it.offset); o.writeUTF(it.strongEtag); o.writeLong(it.totalBytes) }
            o.oldNullable(record.waitReason) { o.writeUTF(it.name) }; o.oldNullable(record.error) { o.writeUTF(it.name) }
        }
    }.toByteArray()
    private fun legacyEvidence(e: VerificationEvidence): ByteArray = java.io.ByteArrayOutputStream().also { buffer ->
        java.io.DataOutputStream(buffer).use { o ->
            o.writeUTF(e.artifactId); o.writeLong(e.sizeBytes); o.writeUTF(e.sha256); o.writeBoolean(e.containerIsMp4)
            o.writeInt(e.videoTrackCount); o.writeInt(e.audioTrackCount); o.writeLong(e.durationMillis)
            o.writeInt(e.width); o.writeInt(e.height); o.writeBoolean(e.encrypted)
            o.writeInt(e.decodedPositions.fold(0) { flags, p -> flags or (1 shl p.ordinal) }); o.writeBoolean(e.audioTrackReadable)
        }
    }.toByteArray()
    private inline fun <T> java.io.DataOutputStream.oldNullable(value: T?, write: (T) -> Unit) {
        writeBoolean(value != null); if (value != null) write(value)
    }

}

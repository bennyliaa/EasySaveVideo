package com.easysavevideo.app.save.storage

import com.easysavevideo.app.library.storage.LibraryPublicationEvidence
import com.easysavevideo.app.save.mediaTaskHasCommittedSave
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Artificial compact proofs, not media fixtures or evidence that production MOV is enabled. */
class DirectNormalizationCodecTest {
    private fun h(c: Char) = c.toString().repeat(64)
    private fun record(raw: Long = 4096, output: Long = 2048, audio: Boolean = true): SaveRecord {
        val resource = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/source?never-persist=this")).asDurableReference()
        val transfer = TransferResult("artifact", raw, "video/quicktime", raw)
        val last = DirectSampleIdentity(29, 966_667, 4, h('a'), 0)
        val video = DirectTrackConservation(DirectSampleKind.AVC_BASELINE, h('1'), 30, 120, h('2'), h('3'), h('4'),
            0, last, DirectTrackEndpoint(1_000_000, 1_000_000))
        val sound = if (audio) DirectTrackConservation(DirectSampleKind.AAC_LC, h('5'), 47, 188, h('6'), h('7'), h('8'),
            0, DirectSampleIdentity(46, 981_333, 4, h('b'), 1), DirectTrackEndpoint(1_002_667, 1_002_667)) else null
        val c = DirectRemuxConservation(16, 16, 30, video, sound, if (audio) 48_000 else null, if (audio) 2 else null,
            DirectSampleIdentity(0, 0, 4, h('c'), 1))
        val proof = DirectNormalizationProof("00000000-0000-4000-8000-000000000001", "task", resource.sourceIdentity,
            "artifact", "commit", raw, h('d'), sourceTransferFingerprint(transfer), output, h('e'), c)
        val checked = DirectNormalizationVerificationProof(proof.normalizationId, "task", resource.sourceIdentity,
            "artifact", "commit", proof.digest(), c, DirectVideoTailProof(c.precedingVideoSync, last, last.presentationTimeUs, true))
        val verified = VerificationEvidence("artifact", output, h('e'), true, 1, if (audio) 1 else 0, c.endpoint.minimumUs / 1000,
            16, 16, false, DecodePosition.entries.toSet(), audio, directNormalizationProof = checked)
        return SaveRecord(SaveRequest("task", "key", resource), TaskSnapshot("task", 5, TaskState.SUCCEEDED, TaskMethod.DIRECT),
            "commit", "operation", 1000, 0, artifact = StagingArtifact("artifact", "commit"), transfer = transfer,
            verification = verified, receipt = MediaCommitReceipt("commit", "artifact", "media", output, h('e')),
            bytesTransferred = raw, directNormalization = proof)
    }

    @Test fun v7AndEvidenceV4RoundTripEveryDirectFieldAndRetainRawNetworkEvidence() {
        for (audio in listOf(false, true)) for ((raw, output) in listOf(4096L to 2048L, 2048L to 4096L)) {
            val expected = record(raw, output, audio); val bytes = SaveRecordCodec.encode(expected)
            assertEquals(7, bytes[7].toInt()); assertTrue(bytes.size < SaveRecordCodec.MAX_BYTES)
            val actual = SaveRecordCodec.decode(bytes)
            assertEquals(expected, actual); assertEquals(expected.directNormalization!!.digest(), actual.directNormalization!!.digest())
            assertEquals(raw, actual.transfer!!.bytesOnDisk); assertEquals(raw, actual.transfer!!.expectedTotalBytes)
            assertEquals("video/quicktime", actual.transfer!!.responseMimeType); assertEquals(raw, actual.bytesTransferred)
            assertEquals(output, actual.verification!!.sizeBytes); assertEquals(output, actual.receipt!!.sizeBytes)
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("never-persist"))
            val proof = SaveRecordCodec.evidenceBytes(expected.verification!!)
            assertEquals(4, proof[7].toInt()); assertTrue(proof.size <= 4096)
            assertEquals(expected.verification, SaveRecordCodec.evidence(proof))
            assertNull(actual.transfer!!.streamExpectation); assertNull(actual.transfer!!.recordingExpectation)
        }
    }

    @Test fun publicationAndDiscoveryCommittedSuccessSelectOutputBothAboveAndBelowRawLength() {
        for ((raw, output) in listOf(4096L to 2048L, 2048L to 4096L)) {
            val value = record(raw, output)
            assertEquals(value, LibraryPublicationEvidence.decode(SaveRecordCodec.encode(value), legacy = false))
            assertEquals(value, LibraryPublicationEvidence.decode(SaveRecordCodec.encode(value), legacy = true))
            assertTrue(mediaTaskHasCommittedSave("task", value))
            assertFalse(mediaTaskHasCommittedSave("other", value))
            assertFalse(mediaTaskHasCommittedSave("task", value.copy(task = value.task.copy(state = TaskState.RUNNING))))
            for (legacy in listOf(false, true)) assertThrows(IllegalArgumentException::class.java) {
                LibraryPublicationEvidence.requireValid(value.copy(task = value.task.copy(state = TaskState.RUNNING)), legacy)
            }
        }
    }

    @Test fun v7EveryTruncationUnknownVersionTrailingAndGlobalCapsFailClosed() {
        val record = SaveRecordCodec.encode(record())
        for (size in record.indices) assertThrows(Exception::class.java) { SaveRecordCodec.decode(record.copyOf(size)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(record + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(record.copyOf().also { it[7] = 8 }) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(ByteArray(32769)) }
        val evidence = SaveRecordCodec.evidenceBytes(record().verification!!)
        for (size in evidence.indices) assertThrows(Exception::class.java) { SaveRecordCodec.evidence(evidence.copyOf(size)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(evidence + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(evidence.copyOf().also { it[7] = 5 }) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(ByteArray(4097)) }
        assertThrows(IllegalArgumentException::class.java) {
            SaveRecordCodec.evidenceBytes(record().verification!!.copy(artifactId = "x".repeat(4096)))
        }
    }

    @Test fun nestedAndStandaloneCountsDigestsFlagsProfilesAndEndpointsAreBounded() {
        val value = record(); val nested = SaveRecordCodec.encode(value); val standalone = SaveRecordCodec.evidenceBytes(value.verification!!)
        for (bytes in listOf(nested, standalone)) {
            val decode: (ByteArray) -> Any = if (bytes === nested) ({ SaveRecordCodec.decode(it) }) else ({ SaveRecordCodec.evidence(it) })
            val configuration = h('1').toByteArray()
            for (at in offsets(bytes, configuration)) {
                for (count in listOf(-1L, 0L, 108_001L, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
                    decode(bytes.copyOf().also { ByteBuffer.wrap(it, at + configuration.size, 8).putLong(count) })
                }
                assertThrows(IllegalArgumentException::class.java) { decode(bytes.copyOf().also { it[at] = 'Z'.code.toByte() }) }
            }
            val sample = ByteBuffer.allocate(20).putLong(29).putLong(966_667).putInt(4).array()
            for (at in offsets(bytes, sample)) {
                assertThrows(IllegalArgumentException::class.java) { decode(bytes.copyOf().also { ByteBuffer.wrap(it, at + 16, 4).putInt(Int.MAX_VALUE) }) }
                assertThrows(IllegalArgumentException::class.java) { decode(bytes.copyOf().also { ByteBuffer.wrap(it, at + 20 + 2 + 64, 4).putInt(2) }) }
            }
            val endpoint = ByteBuffer.allocate(16).putLong(1_000_000).putLong(1_000_000).array()
            for (at in offsets(bytes, endpoint)) assertThrows(IllegalArgumentException::class.java) {
                decode(bytes.copyOf().also { ByteBuffer.wrap(it, at + 8, 8).putLong(Long.MAX_VALUE) })
            }
        }
        val profile = "QT_MOV_AVC_BASELINE_AAC_LC_V1".toByteArray()
        val at = offsets(nested, profile).single()
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(nested.copyOf().also { it[at] = 'X'.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(nested.copyOf().also { ByteBuffer.wrap(it, at - 6, 4).putInt(2) }) }
    }

    @Test fun sourceBindingOrMethodSubstitutionOnWireCannotReuseDirectProof() {
        val value = record(); val original = SaveRecordCodec.encode(value)
        for (hash in listOf(value.directNormalization!!.sourceTransferSha256, value.directNormalization!!.rawSha256,
            value.directNormalization!!.outputSha256, value.directNormalization!!.sourceIdentity,
            value.verification!!.directNormalizationProof!!.normalizationProofSha256)) {
            for (at in offsets(original, hash.toByteArray())) assertThrows(IllegalArgumentException::class.java) {
                SaveRecordCodec.decode(original.copyOf().also { it[at] = (if (it[at] == '0'.code.toByte()) '1' else '0').code.toByte() })
            }
        }
        val corrupted = original.copyOf()
        for (at in offsets(corrupted, "DIRECT".toByteArray())) "STREAM".toByteArray().copyInto(corrupted, at)
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(corrupted) }
        val rawMime = offsets(original, "video/quicktime".toByteArray()).single()
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(original.copyOf().also { it[rawMime] = 'V'.code.toByte() }) }
    }

    @Test fun standaloneDirectEvidenceDoesNotTurnAnOrdinaryLegacyTransferIntoConvertedOutput() {
        val value = record(); val actual = SaveRecordCodec.evidence(SaveRecordCodec.evidenceBytes(value.verification!!))
        assertFalse(actual.isValidFor(value.artifact!!, value.transfer!!, false))
        assertTrue(actual.isValidFor(value.artifact!!, value.selectedOutputDescriptor()!!, false))
        assertThrows(IllegalArgumentException::class.java) { value.copy(directNormalization = null) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(verification = actual.copy(directNormalizationProof = null)) }
    }

    @Test fun standaloneEvidenceRejectsMixedMethodProofsRatherThanSerializingThem() {
        val e = record().verification!!
        val sync = StreamSampleIdentity(0, 0, 4, h('a'), 1)
        val tail = StreamTailProof(sync, sync, 0, true)
        assertFalse(e.copy(streamTailProof = tail).isValidFor(record().artifact!!, record().selectedOutputDescriptor()!!, false))
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidenceBytes(e.copy(streamTailProof = tail)) }
        val video = com.easysavevideo.recording.RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)
        val recorded = RecordingVerificationProof(video, null, "video/avc", null, null, null, video.lastPtsUs, true,
            decodedResumeBoundaries = emptyList())
        assertFalse(e.copy(recordingProof = recorded).isValidFor(record().artifact!!, record().selectedOutputDescriptor()!!, false))
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidenceBytes(e.copy(recordingProof = recorded)) }
    }

    private fun offsets(bytes: ByteArray, needle: ByteArray) = (0..bytes.size - needle.size).filter { at ->
        needle.indices.all { bytes[at + it] == needle[it] }
    }
}

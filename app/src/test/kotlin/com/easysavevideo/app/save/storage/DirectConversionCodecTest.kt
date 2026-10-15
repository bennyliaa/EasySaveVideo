package com.easysavevideo.app.save.storage

import com.easysavevideo.app.library.storage.LibraryPublicationEvidence
import com.easysavevideo.app.save.AndroidLocalMp4Verifier
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Technical value fixtures only: no native encode/decode, audible mapping or owner is asserted. */
internal fun conversionTestRecord(audio: Boolean = true, verified: Boolean = true, terminal: Boolean = true,
    rawSize: Long = 4096, outputSize: Long = 2048): SaveRecord {
    val id = "00000000-0000-4000-8000-000000000001"
    val artifact = StagingArtifact("00000000-0000-4000-8000-000000000002", "commit")
    val input = SaveInput.PrivateNetwork(PrivateDurableSource(PrivateTaskSourceIdentity.fromDurable("a".repeat(64)),
        PrivateSelectionIdentity.fromDurable(id), PrivateSelectionIdentity.fromDurable(artifact.artifactId), false))
    val request = SaveRequest("task", "key", input)
    val raw = TransferResult(artifact.artifactId, rawSize, "video/webm", rawSize)
    val expected = DirectConversionExpectation(DirectConversionVideoExpectation(16, 16, 30, 1_000_000, 30, 0, 966_667, 1),
        if (audio) DirectConversionAudioExpectation(0, 1_000_000, 48_000, 48_000, 2, 120_000, 120_000, 120_000) else null)
    val fact = if (audio) true else null
    val proof = DirectConversionProof(id, "task", "a".repeat(64), artifact.artifactId, "commit", rawSize, "b".repeat(64),
        sourceTransferFingerprint(raw), outputSize, "c".repeat(64), expected, DirectConversionEncodingSettings.forExpectation(expected),
        DirectConversionCompletionFacts(true, true, true, true, true, true, fact, fact, fact, fact, fact, fact))
    val checked = DirectConversionVerificationProof(id, "task", "a".repeat(64), artifact.artifactId, "commit", proof.digest(),
        outputSize, "c".repeat(64), 30, 0, 966_667, 1_000_000, 1,
        if (audio) DirectConversionAudioVerification(0, 1_000_000, 48_000, 48_000, 2, true, true) else null, true, true, true)
    val evidence = if (verified) VerificationEvidence(artifact.artifactId, outputSize, "c".repeat(64), true, 1,
        if (audio) 1 else 0, 1000, 16, 16, false, DecodePosition.entries.toSet(), audio, directConversionProof = checked) else null
    require(!terminal || verified)
    return SaveRecord(request, TaskSnapshot("task", 5, if (terminal) TaskState.SUCCEEDED else TaskState.RUNNING, TaskMethod.DIRECT),
        "commit", "operation", Long.MAX_VALUE, 0, phase = if (terminal) SavePhase.CLEANUP else SavePhase.VERIFYING,
        artifact = artifact, transfer = raw, verification = evidence,
        receipt = if (terminal) MediaCommitReceipt("commit", artifact.artifactId, "media", outputSize, "c".repeat(64)) else null,
        bytesTransferred = rawSize, directConversion = proof)
}

class DirectConversionCodecTest {
    @Test fun recordNineAndEvidenceFiveRoundTripExactRawOutputAndPrivateIdentities() {
        for (audio in listOf(false, true)) for ((raw, output) in listOf(4096L to 2048L, 2048L to 4096L)) {
            val record = conversionTestRecord(audio, rawSize = raw, outputSize = output)
            val bytes = SaveRecordCodec.encode(record)
            assertEquals(9, bytes[7].toInt()); assertTrue(bytes.size <= 32768)
            assertEquals(record, SaveRecordCodec.decode(bytes))
            for (legacy in listOf(false, true)) assertEquals(record, LibraryPublicationEvidence.decode(bytes, legacy))
            val evidence = SaveRecordCodec.evidenceBytes(record.verification!!)
            assertEquals(5, evidence[7].toInt()); assertTrue(evidence.size <= 4096)
            assertEquals(record.verification, SaveRecordCodec.evidence(evidence))
            assertEquals(raw, record.transfer!!.bytesOnDisk); assertEquals(raw, record.bytesTransferred)
            assertEquals(output, record.selectedOutputDescriptor()!!.sizeBytes)
            assertNull(record.directNormalization); assertNull(record.verification!!.directNormalizationProof)
            assertNull(record.request.networkResource); assertNull(record.checkpoint)
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("http"))
            assertEquals(record.request.networkSourceMetadata!!.sourceIdentity, record.directConversion!!.sourceIdentity)
        }
    }
    @Test fun historicalPolicyOneTerminalBytesAndDigestsStayReadableWithoutCurrentAudioAdmission() {
        for (audio in listOf(false, true)) {
            val fixture = Base64.getDecoder().decode(if (audio) AUDIO_ONE_TERMINAL_V9 else SILENT_ONE_TERMINAL_V9)
            val expected = conversionTestRecord(audio)
            assertEquals(expected, SaveRecordCodec.decode(fixture))
            assertArrayEquals(fixture, SaveRecordCodec.encode(expected))
            val restored = SaveRecordCodec.decode(fixture)
            assertEquals(TaskState.SUCCEEDED, restored.task.state)
            assertEquals(SavePhase.CLEANUP, restored.phase)
            assertEquals(expected.receipt, restored.receipt)
            assertEquals(!audio, restored.directConversion!!.isCurrentPolicy())
            assertTrue(restored.verification!!.directConversionProof!!.matches(restored.directConversion!!))
            for (legacy in listOf(false, true)) assertEquals(expected, LibraryPublicationEvidence.decode(fixture, legacy))
            assertEquals(if (audio) "4abe8d0965d975061a2319faa74f3087dead49cee74b4f4ce8b19c1f8a441fbb"
                else "59a4f0186ff51773acabb065b98077465021e87261a88251f859e9da39cd1b8a", restored.directConversion!!.digest())
            assertEquals(if (audio) "9bd9a31c46d4caeaaac10adb96c7bbd5da86edd9bd91e848a5ae134c7eb6b3bc"
                else "ba11a331b9dff88f65f5dfa128e02c7fdd0476cccb1b8b173bad4a43a3d0825a",
                sha256(SaveRecordCodec.evidenceBytes(restored.verification!!)))
        }
    }
    @Test fun policyTwoShortSourceRoundTripsWithSameRecordAndEvidenceWireVersions() {
        for (short in listOf(false, true)) {
            val record = audioPolicyTwoRecord(short)
            val bytes = SaveRecordCodec.encode(record)
            assertEquals(9, bytes[7].toInt()); assertEquals(record, SaveRecordCodec.decode(bytes))
            val evidence = SaveRecordCodec.evidenceBytes(record.verification!!)
            assertEquals(5, evidence[7].toInt()); assertEquals(record.verification, SaveRecordCodec.evidence(evidence))
            val proof = SaveRecordCodec.decode(bytes).directConversion!!
            assertTrue(proof.isCurrentPolicy()); assertEquals(2, proof.policyRevision)
            assertEquals(if (short) 28_800L else 48_000L, proof.expectation.audio!!.sourcePcmFrameCount)
            assertEquals(48_000L, proof.expectation.audio!!.encoderPcmFrameCount)
            assertEquals(if (short) 600_000L else 1_000_000L, proof.expectation.audio!!.sourceEndUs)
            assertTrue(record.verification!!.directConversionProof!!.matches(proof))
        }
    }
    @Test fun unknownAndMixedPolicyBytesRejectAndSilentPolicyTwoCannotBeRead() {
        // Equal S/E makes each policy independently well formed; only revision agreement rejects these mixes.
        val current = audioPolicyTwoRecord(short = false)
        val whole = SaveRecordCodec.encode(current)
        val p = current.directConversion!!
        val proofPolicyAt = locate(whole, p.sourceTransferSha256.toByteArray()) + 64 + 8 + 2 + 64 + 4
        val evidence = SaveRecordCodec.evidenceBytes(current.verification!!)
        val checkedPolicyAt = locate(evidence, p.normalizationId.toByteArray()) - 2 - 4
        for (revision in listOf(0, 3, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(withInt(whole, proofPolicyAt, revision)) }
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(withInt(evidence, checkedPolicyAt, revision)) }
        }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(withInt(whole, proofPolicyAt, 1)) }
        val body = evidence.copyOfRange(8, evidence.size)
        val at = locate(whole, body)
        assertThrows(IllegalArgumentException::class.java) {
            SaveRecordCodec.decode(withInt(whole, at + checkedPolicyAt - 8, 1))
        }
        val silent = conversionTestRecord(audio = false)
        val silentBytes = SaveRecordCodec.encode(silent)
        val silentProofAt = locate(silentBytes, silent.directConversion!!.sourceTransferSha256.toByteArray()) + 64 + 8 + 2 + 64 + 4
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(withInt(silentBytes, silentProofAt, 2)) }
        val silentEvidence = SaveRecordCodec.evidenceBytes(silent.verification!!)
        val silentCheckedAt = locate(silentEvidence, silent.directConversion!!.normalizationId.toByteArray()) - 2 - 4
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(withInt(silentEvidence, silentCheckedAt, 2)) }
    }
    @Test fun impossiblePolicyTwoCountIntervalAndWrongETargetRejectFromBytes() {
        val record = audioPolicyTwoRecord(short = true)
        val whole = SaveRecordCodec.encode(record)
        val proof = record.directConversion!!
        // Existing fixed wire fields: engine/policy, video fields, audio discriminator, then s0/s1/S/E.
        val policyAt = locate(whole, proof.sourceTransferSha256.toByteArray()) + 64 + 8 + 2 + 64 + 4
        val sourceStartAt = policyAt + 4 + 3 * 4 + 5 * 8 + 1
        for (bytes in listOf(withLong(whole, sourceStartAt + 24, 28_799),
            withLong(whole, sourceStartAt + 8, 600_003), withLong(whole, sourceStartAt + 24, 60_000))) {
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes) }
        }
        val badCheck = record.verification!!.directConversionProof!!.copy(
            audio = DirectConversionAudioVerification(0, 600_000, 28_800, 48_000, 2, true, true))
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(verification = record.verification!!.copy(directConversionProof = badCheck))
        }
    }
    @Test fun everyTruncationUnknownVersionAndTrailingBytesRejectAtBothReaders() {
        val record = conversionTestRecord(); val encoded = SaveRecordCodec.encode(record)
        val evidence = SaveRecordCodec.evidenceBytes(record.verification!!)
        for ((bytes, decode) in listOf(encoded to { b: ByteArray -> SaveRecordCodec.decode(b) },
            evidence to { b: ByteArray -> SaveRecordCodec.evidence(b) })) {
            for (size in bytes.indices) assertThrows(Exception::class.java) { decode(bytes.copyOf(size)) }
            assertThrows(IllegalArgumentException::class.java) { decode(bytes + 0.toByte()) }
            assertThrows(IllegalArgumentException::class.java) { decode(bytes.copyOf().also { it[7] = 127 }) }
        }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(ByteArray(32769)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(ByteArray(4097)) }
    }
    @Test fun completeEnvelopeBoundaryAppliesEquallyToNestedAndStandaloneEvidence() {
        // This finite schema's valid compact tuples are smaller than the cap: no padding extension exists.
        SaveRecordCodec.requireEvidenceEnvelopeBytes(4088)
        for (payload in listOf(-1, 4089, 4096, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.requireEvidenceEnvelopeBytes(payload) }
        val record = conversionTestRecord(); val evidence = SaveRecordCodec.evidenceBytes(record.verification!!)
        for (size in listOf(4096, 4097)) {
            val padded = evidence.copyOf(size)
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(padded) }
            val whole = SaveRecordCodec.encode(record)
            val body = evidence.copyOfRange(8, evidence.size)
            val offset = locate(whole, body)
            val nested = whole.copyOfRange(0, offset) + padded.copyOfRange(8, padded.size) + whole.copyOfRange(offset + body.size, whole.size)
            assertThrows(Exception::class.java) { SaveRecordCodec.decode(nested) }
            for (legacy in listOf(false, true)) assertThrows(Exception::class.java) { LibraryPublicationEvidence.decode(nested, legacy) }
        }
        val sql = File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt").readText()
        assertTrue(sql.contains("length(evidence) BETWEEN 1 AND 4096"))
        assertTrue(sql.contains("SaveRecordCodec.evidence("))
    }
    @Test fun nestedLengthSchemaEnumBooleanAndTrailingPayloadReject() {
        val bytes = SaveRecordCodec.encode(conversionTestRecord())
        val profile = DirectConversionProfile.WEBM_VP8_OPUS_TO_AVC_AAC_V1.name.toByteArray()
        val profileAt = locate(bytes, profile)
        val payloadAt = profileAt - 6
        val lengthAt = payloadAt - 4
        for (length in listOf(-1, 0, 4097, Int.MAX_VALUE)) assertThrows(Exception::class.java) {
            SaveRecordCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it, lengthAt, 4).putInt(length) })
        }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it, payloadAt, 4).putInt(2) }) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes.copyOf().also { it[profileAt] = 'X'.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes.copyOf().also { it[lengthAt - 1] = 2 }) }
        val length = ByteBuffer.wrap(bytes, lengthAt, 4).int
        val trailing = (bytes + 0.toByte()).also { ByteBuffer.wrap(it, lengthAt, 4).putInt(length + 1) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(trailing) }
    }
    @Test fun dualKindsCannotBeEncodedOrReadAndNeitherProofCanBeStripped() {
        val record = conversionTestRecord(); val p = record.directConversion!!; val c = normalizationTestConservation()
        val mov = DirectNormalizationProof(p.normalizationId, p.taskId, p.sourceIdentity, p.artifactId, p.commitId,
            p.rawSizeBytes, p.rawSha256, p.sourceTransferSha256, p.outputSizeBytes, p.outputSha256, c)
        assertThrows(IllegalArgumentException::class.java) { record.copy(directNormalization = mov) }
        assertThrows(IllegalArgumentException::class.java) { record.copy(directConversion = null) }
        assertThrows(IllegalArgumentException::class.java) { record.copy(verification = record.verification!!.copy(directConversionProof = null)) }
        val movChecked = DirectNormalizationVerificationProof(p.normalizationId, p.taskId, p.sourceIdentity, p.artifactId, p.commitId,
            mov.digest(), c, DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample, c.video.lastSample.presentationTimeUs, true))
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidenceBytes(record.verification!!.copy(directNormalizationProof = movChecked)) }
        val movOnly = record.verification!!.copy(directNormalizationProof = movChecked, directConversionProof = null)
        val left = SaveRecordCodec.evidenceBytes(movOnly)
        val right = SaveRecordCodec.evidenceBytes(record.verification!!)
        // Extract the typed conversion field, retain both discriminators in deliberately untrusted bytes.
        val idAt = locate(right, p.normalizationId.toByteArray())
        val conversionField = right.copyOfRange(idAt - 2 - 8 - 4 - 1, right.size)
        val dual = left.copyOf(left.size - 1) + conversionField
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.evidence(dual) }
        val whole = SaveRecordCodec.encode(record); val body = right.copyOfRange(8, right.size); val at = locate(whole, body)
        val nested = whole.copyOfRange(0, at) + dual.copyOfRange(8, dual.size) + whole.copyOfRange(at + body.size, whole.size)
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(nested) }
    }
    @Test fun androidVerifierRequiresCanonicalOwnerBeforeLegacyPathOrNativeLane() {
        val record = conversionTestRecord(audio = false); var paths = 0
        val verifier = AndroidLocalMp4Verifier({ paths++; error("must not acquire legacy path") }, 1000)
        val failure = assertThrows(SaveFailure::class.java) {
            verifier.verify(record.artifact!!, record.selectedOutputDescriptor()!!,
                OperationContext("operation", Long.MAX_VALUE, CancellationSource()))
        }
        assertEquals(SaveError.RECOVERY_REQUIRED, failure.code); assertEquals(0, paths)
    }
    private fun audioPolicyTwoRecord(short: Boolean): SaveRecord {
        val old = conversionTestRecord()
        val source = old.directConversion!!
        val audio = source.expectation.audio!!.copy(sourceEndUs = if (short) 600_000 else 1_000_000,
            sourcePcmFrameCount = if (short) 28_800 else 48_000, encoderPcmFrameCount = 48_000)
        val proof = source.copy(expectation = source.expectation.copy(audio = audio), policyRevision = 2)
        val checked = old.verification!!.directConversionProof!!.copy(conversionProofSha256 = proof.digest(), policyRevision = 2)
        return old.copy(directConversion = proof, verification = old.verification!!.copy(directConversionProof = checked))
    }
    private fun withInt(bytes: ByteArray, at: Int, value: Int) = bytes.copyOf().also { ByteBuffer.wrap(it, at, 4).putInt(value) }
    private fun withLong(bytes: ByteArray, at: Int, value: Long) = bytes.copyOf().also { ByteBuffer.wrap(it, at, 8).putLong(value) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Frozen schema-1/policy-1 fixtures, independently transcribed from the original v9 writer.
    // Full record bytes include the original proof digest and v5 verification payload.
    private companion object {
        const val SILENT_ONE_TERMINAL_V9 =
            "RVNWMwAAAAkABHRhc2sAA2tleQAGRElSRUNUAARVU0VSAA9QUklWQVRFX05FVFdPUksAQGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh" +
            "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAw" +
            "MDAwMQAkMDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAAAEdGFzawAAAAAAAAAFAAlTVUNDRUVERUQABkRJUkVD" +
            "VAAAAAAGY29tbWl0AAlvcGVyYXRpb25//////////wAAAAAAAAAAAQAHQ0xFQU5VUAEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAw" +
            "LTAwMDAwMDAwMDAwMgAGY29tbWl0AQAkMDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAAAAAAAAEAAACnZpZGVv" +
            "L3dlYm0BAAAAAAAAEAAAAAEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMgAAAAAAAAgAAEBjY2NjY2NjY2Nj" +
            "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjAQAAAAEAAAAAAAAAAAAAA+gAAAAQ" +
            "AAAAEAAAAAAHAAAAAAABAAABXAAAAAEAAAABACQwMDAwMDAwMC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEABHRhc2sAQGFh" +
            "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAJDAwMDAwMDAwLTAw" +
            "MDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMgAGY29tbWl0AEA1OWE0ZjAxODZmZjUxNzczYWNhYmIwNjViOTgwNzc0NjUwMjFlODcy" +
            "NjFhODgyNTFmODU5ZTlkYTM5Y2QxYjhhAAAAAAAACAAAQGNjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj" +
            "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2MAAAAAAAAAHgAAAAAAAAAAAAAAAAAOwAsAAAAAAA9CQAAAAAAAAAABAAEBAQEABmNvbW1p" +
            "dAAkMDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAAVtZWRpYQAAAAAAAAgAAEBjY2NjY2NjY2NjY2NjY2NjY2Nj" +
            "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjAAAAAAAAEAAAAAAAAQAAAdwAAAABABtXRUJNX1ZQ" +
            "OF9PUFVTX1RPX0FWQ19BQUNfVjEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMQAEdGFzawBAYWFhYWFhYWFh" +
            "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQAkMDAwMDAwMDAtMDAwMC00MDAw" +
            "LTgwMDAtMDAwMDAwMDAwMDAyAAZjb21taXQAAAAAAAAQAABAYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJi" +
            "YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYgBAOTQyZTNiMGQxZDc5NTUwMGM2MjI1OTgzMjUyYTZmNmJmNDQ1MDhjNTc1YjRlYzlk" +
            "MmQ4ZGEwMmY3ODE2ZDBjOQAAAAAAAAgAAEBjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj" +
            "Y2NjY2NjY2NjY2NjY2NjAAAAAQAAAAEAAAAQAAAAEAAAAB4AAAAAAA9CQAAAAAAAAAAeAAAAAAAAAAAAAAAAAA7ACwAAAAAAAAAB" +
            "AAAD6AAAAQEBAQEBAA=="
        const val AUDIO_ONE_TERMINAL_V9 =
            "RVNWMwAAAAkABHRhc2sAA2tleQAGRElSRUNUAARVU0VSAA9QUklWQVRFX05FVFdPUksAQGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh" +
            "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAw" +
            "MDAwMQAkMDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAAAEdGFzawAAAAAAAAAFAAlTVUNDRUVERUQABkRJUkVD" +
            "VAAAAAAGY29tbWl0AAlvcGVyYXRpb25//////////wAAAAAAAAAAAQAHQ0xFQU5VUAEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAw" +
            "LTAwMDAwMDAwMDAwMgAGY29tbWl0AQAkMDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAAAAAAAAEAAACnZpZGVv" +
            "L3dlYm0BAAAAAAAAEAAAAAEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMgAAAAAAAAgAAEBjY2NjY2NjY2Nj" +
            "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjAQAAAAEAAAABAAAAAAAAA+gAAAAQ" +
            "AAAAEAAAAAAHAQAAAAABAAABfgAAAAEAAAABACQwMDAwMDAwMC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEABHRhc2sAQGFh" +
            "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEAJDAwMDAwMDAwLTAw" +
            "MDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMgAGY29tbWl0AEA0YWJlOGQwOTY1ZDk3NTA2MWEyMzE5ZmFhNzRmMzA4N2RlYWQ0OWNl" +
            "ZTc0YjRmNGNlOGIxOWMxZjhhNDQxZmJiAAAAAAAACAAAQGNjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj" +
            "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2MAAAAAAAAAHgAAAAAAAAAAAAAAAAAOwAsAAAAAAA9CQAAAAAAAAAABAQAAAAAAAAAAAAAA" +
            "AAAPQkAAAAAAAAC7gAAAu4AAAAACAQEBAQEBAAZjb21taXQAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMgAF" +
            "bWVkaWEAAAAAAAAIAABAY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2Nj" +
            "Y2NjYwAAAAAAABAAAAAAAAEAAAImAAAAAQAbV0VCTV9WUDhfT1BVU19UT19BVkNfQUFDX1YxACQwMDAwMDAwMC0wMDAwLTQwMDAt" +
            "ODAwMC0wMDAwMDAwMDAwMDEABHRhc2sAQGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh" +
            "YWFhYWFhYWFhYWFhYWEAJDAwMDAwMDAwLTAwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMgAGY29tbWl0AAAAAAAAEAAAQGJiYmJi" +
            "YmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmIAQDk0MmUzYjBkMWQ3OTU1" +
            "MDBjNjIyNTk4MzI1MmE2ZjZiZjQ0NTA4YzU3NWI0ZWM5ZDJkOGRhMDJmNzgxNmQwYzkAAAAAAAAIAABAY2NjY2NjY2NjY2NjY2Nj" +
            "Y2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjYwAAAAEAAAABAAAAEAAAABAAAAAeAAAAAAAP" +
            "QkAAAAAAAAAAHgAAAAAAAAAAAAAAAAAOwAsAAAAAAAAAAQEAAAAAAAAAAAAAAAAAD0JAAAAAAAAAu4AAAAAAAAC7gAAAAAIAAAAA" +
            "AAHUwAAAAAAAAdTAAAAAAAAB1MAAALuAAAPoAAEAAu4AAQEBAQEBAQEBAQEBAQ=="
    }
    private fun locate(bytes: ByteArray, marker: ByteArray): Int = (0..bytes.size - marker.size)
        .first { at -> marker.indices.all { bytes[at + it] == marker[it] } }
}

package com.easysavevideo.app.save.storage

import com.easysavevideo.app.library.storage.LibraryPublicationEvidence
import com.easysavevideo.app.save.mediaTaskHasCommittedSave
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Real current codec with synthetic private sources/media evidence; no Android/provider execution. */
class PrivateSaveRecordCodecTest {
    private val url = SourceUrl("https://private-codec-sentinel.invalid/path-sentinel?token=query-sentinel")
    private val parsedVariant = SourceUrl("https://private-cdn-sentinel.invalid/variant.m3u8").identity()
    private fun request(id: String = "task", method: TaskMethod = TaskMethod.DIRECT): SaveRequest {
        val binding = PrivateNetworkSourceSeed.create().bindTask(id)
        return SaveRequest(id, id, SelectedMediaResource("page-candidate-sentinel", parsedVariant, url,
            requestContextRef = RequestContextRef("private-lease-sentinel"), expiresAtEpochMillis = 12345,
            sourceIdentity = binding.sourceIdentity.encoded, privateSource = binding), method)
    }
    private fun record(request: SaveRequest = request(), mime: String = "video/mp4"): SaveRecord {
        val durable = request.asDurableReference()
        return SaveRecord(durable, TaskSnapshot(request.requestId, 1, TaskState.RUNNING, request.method),
            request.requestId, "operation", 100000, 0, phase = SavePhase.EXECUTING,
            artifact = StagingArtifact("artifact", request.requestId),
            transfer = TransferResult("artifact", 512, mime, 512,
                if (request.method == TaskMethod.STREAM) StreamArtifactExpectation(1000, 1, 0, 16, 16) else null),
            bytesTransferred = 512)
    }
    @Test fun directAndHlsPrivateInputsRoundTripWithoutNetworkObject() {
        for (method in listOf(TaskMethod.DIRECT, TaskMethod.STREAM)) {
            val expected = record(request(method = method))
            val bytes = SaveRecordCodec.encode(expected)
            assertEquals(9, bytes[7].toInt())
            val actual = SaveRecordCodec.decode(bytes)
            assertEquals(expected, actual); assertTrue(actual.request.isPrivateNetwork)
            assertNull(actual.request.networkResource); assertNotNull(actual.request.networkSourceMetadata)
            assertNull(actual.checkpoint)
            assertThrows(IllegalArgumentException::class.java) { actual.request.resource }
        }
    }
    @Test fun everyConversionKeepsOneRandomIdentityAndNoBrowsingDerivedValue() {
        val live = request(); val first = record(live)
        assertEquals(first.request, live.asDurableReference())
        val text = SaveRecordCodec.encode(first).toString(Charsets.ISO_8859_1)
        for (secret in listOf(url.value, url.originOnly().value, url.identity(), parsedVariant,
            "page-candidate-sentinel", "private-lease-sentinel", "private-codec-sentinel", "query-sentinel"))
            assertFalse(secret, text.contains(secret))
        assertTrue(text.contains(first.request.networkSourceMetadata!!.sourceIdentity))
        assertTrue(text.contains("PRIVATE_NETWORK"))
    }
    @Test fun sameUrlInDifferentTasksHasIndependentOpaqueDurableIds() {
        val a = record(request("task-a")).request.input as SaveInput.PrivateNetwork
        val b = record(request("task-b")).request.input as SaveInput.PrivateNetwork
        assertNotEquals(a.source.identity, b.source.identity)
        assertNotEquals(a.source.candidateId, b.source.candidateId)
        assertNotEquals(a.source.variantId, b.source.variantId)
    }
    @Test fun decoderCannotRestorePrivateTagUnderOldVersions() {
        val bytes = SaveRecordCodec.encode(record())
        for (version in 1..7) assertThrows(IllegalArgumentException::class.java) {
            SaveRecordCodec.decode(bytes.copyOf().also { it[7] = version.toByte() })
        }
    }
    @Test fun privateWireRejectsWrongRandomIdentityAndSelectionEncoding() {
        for (identity in listOf("x".repeat(64), "a".repeat(63), "A".repeat(64)))
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(wire(identity = identity)) }
        for (candidate in listOf("page-title", "a".repeat(64), "00000000-0000-4000-8000-00000000000A"))
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(wire(candidate = candidate)) }
    }
    @Test fun privateCheckpointCannotBeInjectedThroughDecoderOrModel() {
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(wire(checkpoint = true)) }
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(checkpoint = DownloadCheckpoint("artifact", url.identity(), 256, "\"private-etag-sentinel\"", 512))
        }
    }
    @Test fun privateMimeNeverCarriesServerParametersOrUnrecognizedText() {
        for (mime in listOf("video/mp4; token=private-mime-sentinel", "VIDEO/MP4", "application/secret", "https://secret.invalid")) {
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(wire(mime = mime)) }
            assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.encode(record(mime = mime)) }
        }
    }
    @Test fun validPrivateQuickTimeRemainsReadableWithoutLiveMovGate() {
        val expected = record(mime = "video/quicktime")
        assertEquals(expected, SaveRecordCodec.decode(SaveRecordCodec.encode(expected)))
        // Codec has no admission/runtime/capability parameter; local recovery authority is separate.
        assertNull(expected.directNormalization)
    }
    @Test fun malformedVersionTruncationAndTrailingBytesFailClosed() {
        val bytes = SaveRecordCodec.encode(record())
        assertThrows(Exception::class.java) { SaveRecordCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes + 0.toByte()) }
        assertThrows(IllegalArgumentException::class.java) { SaveRecordCodec.decode(bytes.copyOf().also { it[7] = 10 }) }
    }
    @Test fun ordinaryV7PayloadRemainsReadableAndRetainsItsOriginalSourceMeaning() {
        val ordinary = SaveRequest("task", "task", SelectedMediaResource("candidate", "variant", url))
        val expected = record(ordinary)
        val bytes = wire(ordinaryV7 = true)
        assertEquals(7, bytes[7].toInt())
        assertEquals(expected, SaveRecordCodec.decode(bytes))
        assertEquals(url.originOnly(), expected.request.networkResource!!.url)
        assertEquals(url.identity(), expected.request.networkSourceMetadata!!.sourceIdentity)
    }
    @Test fun frozenPrivateV8LayoutKeepsItsOriginalOpaqueIdentity() {
        val bytes = wire()
        assertEquals(8, bytes[7].toInt())
        val old = SaveRecordCodec.decode(bytes)
        assertTrue(old.request.isPrivateNetwork)
        assertEquals("a".repeat(64), old.request.networkSourceMetadata!!.sourceIdentity)
        assertNull(old.directConversion)
        assertEquals(old, SaveRecordCodec.decode(SaveRecordCodec.encode(old)))
    }
    @Test fun privateNormalizationAndPublicationBindRandomMetadataWithoutAnyUrlAccessor() {
        val raw = record(mime = "video/quicktime")
        val c = normalizationTestConservation()
        val identity = raw.request.networkSourceMetadata!!.sourceIdentity
        val proof = DirectNormalizationProof("00000000-0000-4000-8000-000000000003", "task", identity,
            "artifact", "task", 512, "a".repeat(64), sourceTransferFingerprint(raw.transfer!!), 256, "b".repeat(64), c)
        val verification = VerificationEvidence("artifact", 256, "b".repeat(64), true, 1, 0,
            c.endpoint.minimumUs / 1000, c.width, c.height, false, DecodePosition.entries.toSet(), false,
            directNormalizationProof = DirectNormalizationVerificationProof(proof.normalizationId, "task", identity,
                "artifact", "task", proof.digest(), c, DirectVideoTailProof(c.precedingVideoSync, c.video.lastSample,
                    c.video.lastSample.presentationTimeUs, true)))
        val success = raw.copy(task = raw.task.copy(state = TaskState.SUCCEEDED), phase = SavePhase.CLEANUP,
            directNormalization = proof, verification = verification,
            receipt = MediaCommitReceipt("task", "artifact", "media", 256, "b".repeat(64)))
        val bytes = SaveRecordCodec.encode(success)
        val actual = LibraryPublicationEvidence.decode(bytes, legacy = false)
        assertEquals(success, actual); assertTrue(mediaTaskHasCommittedSave(actual.task.taskId, actual))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(url.identity()))
        assertThrows(IllegalArgumentException::class.java) {
            success.copy(directNormalization = proof.copy(sourceIdentity = url.identity()))
        }
    }
    /** Deliberately untrusted bytes: no SaveRecord constructor can pre-filter these inputs. */
    private fun wire(identity: String = "a".repeat(64), candidate: String = "00000000-0000-4000-8000-000000000001",
        checkpoint: Boolean = false, mime: String = "video/mp4", ordinaryV7: Boolean = false): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { o ->
            o.writeInt(0x45535633); o.writeInt(if (ordinaryV7) 7 else 8)
            o.writeUTF("task"); o.writeUTF("task"); o.writeUTF("DIRECT"); o.writeUTF("USER")
            if (ordinaryV7) {
                o.writeUTF("NETWORK"); o.writeUTF("candidate"); o.writeUTF("variant"); o.writeUTF(url.originOnly().value)
                o.writeBoolean(false); o.writeBoolean(false); o.writeUTF(url.identity()); o.writeBoolean(true)
            } else {
                o.writeUTF("PRIVATE_NETWORK"); o.writeUTF(identity); o.writeUTF(candidate)
                o.writeUTF("00000000-0000-4000-8000-000000000002"); o.writeBoolean(false)
            }
            o.writeUTF("task"); o.writeLong(1); o.writeUTF("RUNNING"); o.writeUTF("DIRECT")
            repeat(3) { o.writeBoolean(false) }
            o.writeUTF("task"); o.writeUTF("operation"); o.writeLong(100000); o.writeInt(0); o.writeInt(0)
            o.writeBoolean(true); o.writeUTF("EXECUTING")
            o.writeBoolean(true); o.writeUTF("artifact"); o.writeUTF("task")
            o.writeBoolean(true); o.writeUTF("artifact"); o.writeLong(512); o.writeUTF(mime)
            o.writeBoolean(true); o.writeLong(512); o.writeBoolean(false); o.writeBoolean(false)
            o.writeBoolean(false); o.writeBoolean(false); o.writeLong(512)
            o.writeBoolean(checkpoint)
            if (checkpoint) { o.writeUTF("artifact"); o.writeUTF(url.identity()); o.writeLong(256); o.writeUTF("\"private-etag-sentinel\""); o.writeLong(512) }
            repeat(3) { o.writeBoolean(false) }
        }
    }.toByteArray()
}

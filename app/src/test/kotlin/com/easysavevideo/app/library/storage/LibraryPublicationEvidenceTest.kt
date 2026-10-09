package com.easysavevideo.app.library.storage

import com.easysavevideo.app.save.storage.SaveRecordCodec
import com.easysavevideo.core.model.*
import com.easysavevideo.recording.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic evidence fixtures only, not claims that these byte strings are actual media. */
class LibraryPublicationEvidenceTest {
    private fun stream(): SaveRecord {
        val request = SaveRequest("task", "key", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/v")).asDurableReference(), TaskMethod.STREAM)
        val artifact = StagingArtifact("artifact", "commit")
        val evidence = VerificationEvidence("artifact", 100, "a".repeat(64), true, 1, 0, 1000, 16, 16,
            false, DecodePosition.entries.toSet(), true)
        return SaveRecord(request, TaskSnapshot("task", 4, TaskState.SUCCEEDED, TaskMethod.STREAM), "commit", "operation", 1000, 0,
            artifact = artifact, transfer = TransferResult("artifact", 100, "video/mp4", 100,
                StreamArtifactExpectation(1000, 1, 0, 16, 16, conservation = null)), verification = evidence,
            receipt = MediaCommitReceipt("commit", "artifact", "55b4e1b1-4a74-4a0e-b985-41605c5ba4f7", 100, "a".repeat(64)))
    }
    private fun capture(): SaveRecord {
        val descriptor = CaptureDescriptor("capture", 1, RecordingAudioChoice.NONE)
        val video = RecordingTrackEvidence(30, 0, 966_667, 33_333, 1, true)
        val expectation = RecordingArtifactExpectation(descriptor, descriptor.requestedProfile,
            RecordingAudioOutcome.NO_AUDIO_REQUESTED, RecordingStopReason.SYSTEM_STOPPED, video, null,
            video.endPtsUs, 1024, resumeBoundaries = null)
        val proof = RecordingVerificationProof(video, null, "video/avc", null, null, null, video.lastPtsUs, true,
            decodedResumeBoundaries = null)
        val evidence = VerificationEvidence("artifact", 1024, "b".repeat(64), true, 1, 0, video.endPtsUs / 1000,
            1280, 720, false, DecodePosition.entries.toSet(), false, recordingProof = proof)
        return SaveRecord(SaveRequest("capture", "capture-key", SaveInput.Capture(descriptor)),
            TaskSnapshot("capture", 5, TaskState.SUCCEEDED, TaskMethod.RECORD), "commit", "operation", 1000, 0,
            artifact = StagingArtifact("artifact", "commit"),
            transfer = TransferResult("artifact", 1024, "video/mp4", 1024, recordingExpectation = expectation), verification = evidence,
            receipt = MediaCommitReceipt("commit", "artifact", "55b4e1b1-4a74-4a0e-b985-41605c5ba4f7", 1024, "b".repeat(64)))
    }

    @Test fun alreadySucceededLegacyStreamKeepsExactReceiptAndUnknownHistoricalProof() {
        val original = stream()
        val decoded = LibraryPublicationEvidence.decode(SaveRecordCodec.encode(original), legacy = true)
        assertEquals(original, decoded)
        assertNull(decoded.transfer!!.streamExpectation!!.conservation)
        assertNull(decoded.verification!!.streamTailProof)
        assertEquals(original.receipt, decoded.receipt)
        assertThrows(IllegalArgumentException::class.java) { LibraryPublicationEvidence.requireValid(decoded, legacy = false) }
    }

    @Test fun alreadySucceededLegacyRecordKeepsExactReceiptWithoutInventingResumeEvidence() {
        val original = capture()
        val decoded = LibraryPublicationEvidence.decode(SaveRecordCodec.encode(original), legacy = true)
        assertEquals(original, decoded)
        assertNull(decoded.transfer!!.recordingExpectation!!.resumeBoundaries)
        assertNull(decoded.verification!!.recordingProof!!.decodedResumeBoundaries)
        assertEquals(original.receipt, decoded.receipt)
        assertThrows(IllegalArgumentException::class.java) { LibraryPublicationEvidence.requireValid(decoded, legacy = false) }
    }

    @Test fun unfinishedLegacyVerifyingAndCommittingNeverBecomePublishedMedia() {
        for (original in listOf(stream(), capture())) for (phase in listOf(null, SavePhase.VERIFYING, SavePhase.COMMITTING)) {
            val active = original.copy(task = original.task.copy(state = TaskState.RUNNING), phase = phase)
            for (legacy in listOf(false, true)) assertThrows(IllegalArgumentException::class.java) {
                LibraryPublicationEvidence.decode(SaveRecordCodec.encode(active), legacy)
            }
        }
    }

    @Test fun boundedUnknownAndTruncatedCodecsFailInsteadOfFabricatingReceipt() {
        val bytes = SaveRecordCodec.encode(stream())
        assertThrows(IllegalArgumentException::class.java) { LibraryPublicationEvidence.decode(ByteArray(32769), true) }
        assertThrows(IllegalArgumentException::class.java) { LibraryPublicationEvidence.decode(bytes.copyOf().also { it[7] = 99 }, true) }
        assertThrows(Exception::class.java) { LibraryPublicationEvidence.decode(bytes.copyOf(bytes.size - 1), true) }
    }

    @Test fun currentDirectVerifiedSuccessRemainsValidForNewPublication() {
        val stream = stream()
        val direct = stream.copy(request = stream.request.copy(method = TaskMethod.DIRECT), task = stream.task.copy(method = TaskMethod.DIRECT),
            transfer = stream.transfer!!.copy(streamExpectation = null))
        assertEquals(direct, LibraryPublicationEvidence.decode(SaveRecordCodec.encode(direct), legacy = false))
    }
}

package com.easysavevideo.library.export

import com.easysavevideo.library.LibraryFailure
import com.easysavevideo.library.MediaReadRef
import org.junit.Assert.*
import org.junit.Test

class ExportModelsTest {
    private val ref = MediaReadRef("source", 1, 1)
    private val request = ExportRequest("operation", ref, ExportDestination(ExportKind.MEDIA_STORE, "grant"))
    private val evidence = ExportEvidence(1, "a".repeat(64))

    @Test fun operationAndCapabilityIdentitiesAreBoundedOpaqueIds() {
        for (invalid in listOf("", "a".repeat(129), "/private/file", "content://provider/item", "../item", "with space")) {
            assertThrows(LibraryFailure::class.java) { ExportRequest(invalid, ref, request.destination) }
            assertThrows(LibraryFailure::class.java) { ExportDestination(ExportKind.SAF, invalid) }
            assertThrows(LibraryFailure::class.java) { ExportOutput(invalid, ExportKind.MEDIA_STORE) }
        }
        ExportRequest("a".repeat(128), ref, ExportDestination(ExportKind.SAF, "G_1-x"))
    }

    @Test fun byteAndHashEvidenceHasFiniteCanonicalBounds() {
        for (count in listOf(-1, 0, ExportLimits.MAX_BYTES + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ExportEvidence(count, "a".repeat(64)) }
        }
        for (hash in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) { ExportEvidence(1, hash) }
        }
        ExportEvidence(ExportLimits.MAX_BYTES, "0".repeat(64))
    }

    @Test fun fingerprintIsCanonicalBoundedAndBindsEveryAuthorityField() {
        val baseline = request.fingerprint()
        assertTrue(baseline.matches(Regex("[a-f0-9]{64}")))
        assertEquals(baseline, request.copy().fingerprint())
        for (different in listOf(request.copy(operationId = "next"), request.copy(source = ref.copy(itemId = "other")),
            request.copy(source = ref.copy(revision = 2)), request.copy(source = ref.copy(representationGeneration = 2)),
            request.copy(destination = request.destination.copy(kind = ExportKind.SAF)),
            request.copy(destination = request.destination.copy(grantId = "other")))) {
            assertNotEquals(baseline, different.fingerprint())
        }
    }

    @Test fun successCannotBeConstructedWithoutVerifiedOwnedOutput() {
        assertThrows(IllegalArgumentException::class.java) { ExportRecord(request, ExportPhase.SUCCEEDED) }
        assertThrows(IllegalArgumentException::class.java) {
            ExportRecord(request, ExportPhase.SUCCEEDED, ExportOutput("out", ExportKind.MEDIA_STORE), evidence,
                verification = ExportVerification.SOURCE_VERIFIED)
        }
        val success = ExportRecord(request, ExportPhase.SUCCEEDED, ExportOutput("out", ExportKind.MEDIA_STORE), evidence,
            verification = ExportVerification.DESTINATION_VERIFIED)
        assertThrows(IllegalArgumentException::class.java) { success.copy(cancelRequested = true) }
    }

    @Test fun unverifiedTerminalOutcomeIsSafOnlyAndCannotBeCancelledInPlace() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportRecord(request, ExportPhase.COPIED_UNVERIFIED, ExportOutput("out", ExportKind.MEDIA_STORE), evidence,
                verification = ExportVerification.READBACK_UNSUPPORTED)
        }
        val saf = request.copy(destination = request.destination.copy(kind = ExportKind.SAF))
        val record = ExportRecord(saf, ExportPhase.COPIED_UNVERIFIED, ExportOutput("out", ExportKind.SAF), evidence,
            verification = ExportVerification.READBACK_UNSUPPORTED)
        assertThrows(IllegalArgumentException::class.java) { record.copy(cancelRequested = true) }
        record.copy(phase = ExportPhase.CLEANUP_PENDING, cancelRequested = true)
    }

    @Test fun outputKindCannotCrossDestinationAuthority() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportRecord(request, output = ExportOutput("out", ExportKind.SAF))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExportRecord(request, verification = ExportVerification.SOURCE_VERIFIED)
        }
    }

    @Test fun diagnosticStringsDoNotLeakIdsOrEvidence() {
        val record = ExportRecord(request, output = ExportOutput("sensitiveOutput", ExportKind.MEDIA_STORE), evidence = evidence)
        val text = listOf(request, request.destination, evidence, record.output, record).joinToString("\n")
        for (secret in listOf("source", "operation", "grant", "sensitiveOutput", "a".repeat(64))) assertFalse(text.contains(secret))
        assertFalse(ExportFailure(ExportError.IO).message!!.contains("private"))
    }

    @Test fun onlySettledPhasesAreTerminal() {
        for (phase in ExportPhase.entries) assertEquals(
            phase in setOf(ExportPhase.SUCCEEDED, ExportPhase.COPIED_UNVERIFIED, ExportPhase.FAILED, ExportPhase.CANCELLED), phase.terminal)
    }
}

package com.easysavevideo.app.save.storage

import com.easysavevideo.app.media.verification.*
import com.easysavevideo.app.save.*
import com.easysavevideo.save.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Real host RAFs/owner, synthetic metadata and native body only; no Android codec claim. */
class ConvertedCanonicalReleaseTest {
    private val observed = AvcAacOutputObservations(120_000, 16, 16, 0, 0, 80_000, 3, 0, 3, 0, 0)
    private val expected = AvcAacOutputExpectation(120_000, 16, 16, 25, AvcAacAudioProfile.Silent, 41_000, 0, 4096, 3)
    private fun inspection(lease: DirectNormalizedOutputLease,
        body: (AvcAacNativeResources) -> AvcAacOutputObservations = { observed }): BorrowedAvcAacInspection {
        val input = lease.readOnlyInput
        return BorrowedAvcAacInspection(BorrowedAvcAacRead(input.fd, input.channel, input.length())) { _, resources, _, _ -> body(resources) }
    }
    private fun release(lease: DirectNormalizedOutputLease, actual: BorrowedAvcAacInspection) {
        lease.bindNativeInspection(actual)
        actual.inspect(expected) {}.observationsOrThrow()
        lease.confirmNativeRelease(actual)
    }
    private fun rejected(block: () -> Unit) = assertThrows(SaveFailure::class.java) { block() }

    @Test fun exactConsumedNativeAndRafReleaseLeavesWitnessAfterRegistryRemoval() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
            val descriptor = lease.readOnlyInput.fd
            var nativeReleased = false
            val actual = inspection(lease) { resources ->
                resources.add(Any(), release = { nativeReleased = true }); observed
            }
            f.io.before = { if (it.operation == "close") assertTrue(nativeReleased) }
            release(lease, actual)
            rejected { binding.requireConsumedAndReleased() }
            lease.close(); lease.close()
            assertEquals(1, f.io.count("close")); assertFalse(descriptor.valid())
            assertTrue(lease.releaseConfirmed); assertTrue(binding.isRevoked)
            rejected { binding.requireLive() }
            rejected { f.bindings.consume(f.artifact, f.convertedOutput, f.context) }
            binding.requireConsumedAndReleased()
            assertFalse(f.owner.hasAnyReservations())
            binding.complete(); binding.requireConsumedAndReleased()
        } }
    }

    @Test fun unconsumedForgedEvidenceAndGeneralCompletionCannotCreateWitness() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val callerConstructed = assembleConvertedVerificationEvidence(f.artifact, f.convertedOutput,
                f.convertedOutput.sha256, observed)
            assertTrue(callerConstructed.isValidFor(f.artifact, f.convertedOutput, false))
            rejected { binding.requireConsumedAndReleased() }
            binding.complete()
            rejected { binding.requireConsumedAndReleased() }
            rejected { binding.completeReleasedBy(f.owner) }
            assertEquals(0, f.io.count("open"))
        } }
    }

    @Test fun consumedReadWithoutActualBoundInspectionDoesNotCreateWitness() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
            lease.close()
            assertTrue(lease.releaseConfirmed)
            rejected { binding.requireConsumedAndReleased() }
        } }
    }

    @Test fun emptyReleasedOrFailedInspectionCanCloseButNeverCreatesSuccessfulWitness() {
        for (inspect in listOf(false, true)) AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
            val actual = inspection(lease) { throw SaveFailure(SaveError.INVALID_MEDIA) }
            lease.bindNativeInspection(actual)
            if (inspect) actual.inspect(expected) {} else assertTrue(actual.releaseNative())
            assertTrue(actual.nativeReleaseConfirmed); assertFalse(actual.successfulInspection)
            lease.confirmNativeRelease(actual); lease.close()
            assertTrue(lease.releaseConfirmed); assertFalse(f.owner.requiresProcessRecovery())
            rejected { binding.requireConsumedAndReleased() }
        } }
    }

    @Test fun aReplacementOrAlreadyRunInspectionCannotClaimTheBoundNativeRelease() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
            val first = inspection(lease); val replacement = inspection(lease)
            replacement.inspect(expected) {}.observationsOrThrow()
            rejected { lease.bindNativeInspection(replacement) }
            lease.bindNativeInspection(first)
            rejected { lease.bindNativeInspection(replacement) }
            rejected { lease.confirmNativeRelease(replacement) }
            rejected { lease.confirmNativeRelease(first) }
            first.inspect(expected) {}.observationsOrThrow()
            lease.confirmNativeRelease(first)
            lease.close(); binding.requireConsumedAndReleased()
        } }
    }

    @Test fun unknownNativeReleaseRetainsActualBorrowerAndEveryLateCloseSkipsRaf() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
            val descriptor = lease.readOnlyInput.fd
            var releases = 0
            val actual = inspection(lease) { resources ->
                resources.add(Any(), release = { releases++; throw IOException("SYNTHETIC_NATIVE_RELEASE") }); observed
            }
            lease.bindNativeInspection(actual)
            val result = actual.inspect(expected) {}
            assertFalse(result.nativeReleaseConfirmed); assertFalse(actual.nativeReleaseConfirmed)
            lease.retainOnNativeReleaseUncertainty(actual)
            f.cancellation.cancel()
            repeat(3) { rejected { lease.close() }; lease.retainOnNativeReleaseUncertainty(actual) }
            assertFalse(actual.releaseNative()); assertEquals(1, releases)
            assertEquals(0, f.io.count("close")); assertTrue(descriptor.valid())
            assertTrue(f.owner.hasAnyReservations()); assertTrue(f.owner.requiresProcessRecovery())
            assertFalse(lease.releaseConfirmed)
            rejected { lease.confirmNativeRelease(actual) }
            rejected { binding.requireConsumedAndReleased() }
            // Assert the actual object is rooted by the existing owner, not merely a copied status.
            val reservations = f.owner.javaClass.getDeclaredField("reservations").apply { isAccessible = true }.get(f.owner) as Map<*, *>
            val reservation = reservations.values.single()!!
            val retained = reservation.javaClass.getDeclaredField("retainedNativeInspection").apply { isAccessible = true }.get(reservation)
            assertSame(actual, retained)
        } }
    }

    @Test fun closeFailureNeverCreatesWitnessOrRetriesRaf() {
        for (mode in AdoptedCanonicalTestIo.CloseMode.entries.filter { it != AdoptedCanonicalTestIo.CloseMode.NORMAL }) {
            AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
                val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
                release(lease, inspection(lease)); f.io.closeMode = mode
                rejected { lease.close() }; rejected { lease.close() }
                assertEquals(1, f.io.count("close")); assertTrue(f.owner.requiresProcessRecovery())
                rejected { binding.requireConsumedAndReleased() }
            } }
        }
    }

    @Test fun revokedOrLexicallyLateReadMayCloseButCannotCreateWitness() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted()
            lateinit var binding: AdoptedVerificationBindings.Binding
            lateinit var lease: DirectNormalizedOutputLease
            f.boundConverted {
                binding = it; lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
                release(lease, inspection(lease))
            }
            lease.close()
            assertTrue(lease.releaseConfirmed); rejected { binding.requireConsumedAndReleased() }
            rejected { binding.completeReleasedBy(f.owner) }
        }
    }

    @Test fun stopAfterPositiveClosePreservesHistoricalFactButForbidsLiveAuthority() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
            release(lease, inspection(lease)); lease.close(); binding.requireConsumedAndReleased()
            f.cancellation.cancel()
            assertEquals(SaveError.CANCELLED, rejected { f.checkOperation(f.context) }.code)
            binding.requireConsumedAndReleased()
            rejected { binding.requireLive() }
        } }
    }

    @Test fun convertedExactDescriptorContextSnapshotAndExpectedAudioAreFenced() {
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted {
            rejected { f.owner.acquire(f.artifact.copy(), f.convertedOutput, f.context) }
            rejected { f.owner.acquire(f.artifact, f.convertedOutput.copy(), f.context) }
            rejected { f.owner.acquire(f.artifact, f.convertedOutput, f.context.copy()) }
            assertEquals(0, f.io.count("open"))
        } }
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted {
            f.record = f.record!!.let { it.copy(request = it.request.copy(resource = it.request.resource.copy(expectedAudio = true))) }
            rejected { f.owner.acquire(f.artifact, f.convertedOutput, f.context) }
            assertEquals(0, f.io.count("open"))
        } }
        AdoptedCanonicalTestFixture().use { f -> f.useConverted(); f.boundConverted { binding ->
            val lease = f.owner.acquire(f.artifact, f.convertedOutput, f.context)
            release(lease, inspection(lease))
            f.ledger.row = f.ledger.row!!.copy(revision = f.ledger.row!!.revision + 1)
            rejected { lease.revalidate() }; lease.close()
            rejected { binding.requireConsumedAndReleased() }
        } }
    }
}

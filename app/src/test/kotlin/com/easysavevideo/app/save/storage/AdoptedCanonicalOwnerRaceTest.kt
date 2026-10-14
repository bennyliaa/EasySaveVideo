package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.DirectNormalizedOutputLease
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Latch-controlled host ownership races, not real codec/extractor or Android duplicate-FD tests. */
class AdoptedCanonicalOwnerRaceTest {
    private fun await(latch: CountDownLatch) { assertTrue("HOST_BARRIER_TIMEOUT", latch.await(5, TimeUnit.SECONDS)) }

    @Test fun blockedRafOpenRemainsReservedUntilLateRevokedHandleActuallyCloses() {
        for (cancel in listOf(false, true)) AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            f.io.before = { if (it.operation == "open") { entered.countDown(); await(resume) } }
            try { f.bound {
                val result = pool.submit<Throwable?> {
                    try { f.owner.acquire(f.artifact, f.output, f.context); null } catch (failure: Throwable) { failure }
                }
                await(entered)
                assertTrue(f.owner.hasReservation(f.artifact.artifactId))
                assertTrue(f.owner.hasAnyLeases())
                assertEquals(0, f.io.handles.size)
                if (cancel) f.cancellation.cancel()
                f.owner.revoke(f.artifact.artifactId)
                assertTrue(f.owner.requiresProcessRecovery())
                assertEquals(0, f.io.count("close"))
                resume.countDown()
                val failure = result.get(5, TimeUnit.SECONDS)
                assertTrue(failure is SaveFailure)
                if (cancel) assertEquals(SaveError.CANCELLED, (failure as SaveFailure).code)
                assertEquals(1, f.io.count("open")); assertEquals(1, f.io.count("close"))
                assertFalse(f.io.handles.single().fd.valid())
                assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
            } } finally { resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun pendingBindingDoesNotPinStorageAndClosedStorageDefeatsLaterAcquire() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            assertFalse(f.owner.hasAnyLeases())
            f.storageClosed = true
            assertEquals(SaveError.STORAGE_FAILURE,
                assertThrows(SaveFailure::class.java) { f.owner.acquire(f.artifact, f.output, f.context) }.code)
            assertTrue(f.io.calls.isEmpty())
            assertFalse(f.owner.hasAnyLeases())
        } }
    }

    @Test fun acquisitionReservationExistsBeforeFirstMetadataOrDirectoryCall() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            f.io.before = { call ->
                assertTrue("unreserved ${call.operation}", f.owner.hasReservation(f.artifact.artifactId))
            }
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            lease.revalidate(); lease.close()
            assertFalse(f.owner.hasAnyLeases())
        } }
    }

    @Test fun blockedDirectoryConstructorRevocationWaitsForActualDirectoryClose() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            f.io.before = { if (it.operation == "openDirectory") { entered.countDown(); await(resume) } }
            try { f.bound { binding ->
                val result = pool.submit<Throwable?> {
                    try { f.owner.acquire(f.artifact, f.output, f.context); null } catch (failure: Throwable) { failure }
                }
                await(entered)
                assertTrue(f.owner.hasAnyLeases())
                binding.revoke()
                assertTrue(f.owner.requiresProcessRecovery())
                assertEquals(0, f.io.count("closeDirectory"))
                resume.countDown()
                assertTrue(result.get(5, TimeUnit.SECONDS) is SaveFailure)
                assertEquals(1, f.io.count("openDirectory")); assertEquals(1, f.io.count("closeDirectory"))
                assertEquals(0, f.io.count("children"))
                assertEquals(0, f.io.count("open"))
                assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
            } } finally { resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun blockedEnumerationRevocationClosesDirectoryBeforeRetiringAcquisition() {
        for (blocked in listOf("children", "closeDirectory")) AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            f.io.before = { if (it.operation == blocked) { entered.countDown(); await(resume) } }
            try { f.bound {
                val result = pool.submit<Throwable?> {
                    try { f.owner.acquire(f.artifact, f.output, f.context); null } catch (failure: Throwable) { failure }
                }
                await(entered)
                f.owner.revoke(f.artifact.artifactId)
                assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
                resume.countDown()
                assertTrue(result.get(5, TimeUnit.SECONDS) is SaveFailure)
                assertEquals(1, f.io.count("openDirectory")); assertEquals(1, f.io.count("closeDirectory"))
                assertEquals(0, f.io.count("open"))
                assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
            } } finally { resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun blockedRafCloseRetainsFenceAndDuplicateCloseDoesNotCompete() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val closing = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val lease = AtomicReference<DirectNormalizedOutputLease>()
            f.io.before = { if (it.operation == "close") { closing.countDown(); await(resume) } }
            try { f.bound {
                val result = pool.submit {
                    val owned = f.owner.acquire(f.artifact, f.output, f.context)
                    lease.set(owned)
                    owned.close()
                }
                await(closing)
                val owned = lease.get()
                assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
                assertFalse(owned.releaseConfirmed)
                owned.close(); owned.close()
                assertEquals(1, f.io.count("close"))
                assertFalse(owned.releaseConfirmed)
                resume.countDown(); result.get(5, TimeUnit.SECONDS)
                assertTrue(owned.releaseConfirmed)
                assertFalse(f.owner.hasAnyLeases()); assertFalse(f.owner.requiresProcessRecovery())
                assertEquals(1, f.io.count("close"))
            } } finally { resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun reentrantCloseDoesNotRetrySameHandleOrGrantNewEvidence() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            f.io.before = { if (it.operation == "close") {
                lease.close()
                assertFalse(lease.releaseConfirmed)
                assertTrue(f.owner.hasAnyLeases())
                assertThrows(SaveFailure::class.java) { lease.revalidate() }
                assertThrows(SaveFailure::class.java) { lease.readOnlyInput }
            } }
            lease.close()
            assertEquals(1, f.io.count("close")); assertTrue(lease.releaseConfirmed)
            assertFalse(f.owner.hasAnyLeases())
        } }
    }

    @Test fun crossThreadCloseOnlyRequestsAndOriginalWorkerPerformsActualClose() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val acquired = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val lease = AtomicReference<DirectNormalizedOutputLease>()
            try { f.bound {
                val result = pool.submit {
                    val owned = f.owner.acquire(f.artifact, f.output, f.context)
                    lease.set(owned); acquired.countDown(); await(resume)
                    assertThrows(SaveFailure::class.java) { owned.readOnlyInput }
                    owned.close()
                }
                await(acquired)
                val owned = lease.get()
                assertThrows(SaveFailure::class.java) { owned.readOnlyInput }
                assertThrows(SaveFailure::class.java) { owned.revalidate() }
                owned.close()
                assertEquals(0, f.io.count("close")); assertFalse(owned.releaseConfirmed)
                assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
                resume.countDown(); result.get(5, TimeUnit.SECONDS)
                assertTrue(owned.releaseConfirmed); assertEquals(1, f.io.count("close"))
                assertFalse(f.owner.hasAnyLeases())
            } } finally { resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun crossThreadCloseDuringValidationCannotCloseUnderInFlightMetadata() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val acquired = CountDownLatch(1)
            val startValidation = CountDownLatch(1)
            val validating = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val lease = AtomicReference<DirectNormalizedOutputLease>()
            try { f.bound {
                val result = pool.submit {
                    val owned = f.owner.acquire(f.artifact, f.output, f.context)
                    lease.set(owned); acquired.countDown(); await(startValidation)
                    assertThrows(SaveFailure::class.java) { owned.revalidate() }
                    owned.close()
                }
                await(acquired)
                f.io.before = { if (it.operation == "fstat") { validating.countDown(); await(resume) } }
                startValidation.countDown(); await(validating)
                lease.get().close()
                assertEquals(0, f.io.count("close")); assertFalse(lease.get().releaseConfirmed)
                assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
                resume.countDown(); result.get(5, TimeUnit.SECONDS)
                assertEquals(1, f.io.count("close")); assertTrue(lease.get().releaseConfirmed)
                assertFalse(f.owner.hasAnyLeases())
            } } finally { startValidation.countDown(); resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun revisionChangeDuringValidationCannotBeAcceptedByFinalAuthorityCheck() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            var changed = false
            f.io.after = { if (it.operation == "fstat" && !changed) {
                changed = true
                f.record = f.record!!.let { record -> record.copy(task = record.task.copy(revision = record.task.revision + 1)) }
            } }
            assertThrows(SaveFailure::class.java) { lease.revalidate() }
            f.record = f.record!!.let { it.copy(task = it.task.copy(revision = f.revision)) }
            assertThrows(SaveFailure::class.java) { lease.revalidate() }
            lease.close()
            assertTrue(lease.releaseConfirmed); assertEquals(1, f.io.count("open"))
        } }
    }

    @Test fun nativeReleaseMustReturnBeforeOwnerWorkerClosesRaf() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val nativeReleaseEntered = CountDownLatch(1)
            val nativeReleaseReturns = CountDownLatch(1)
            val lease = AtomicReference<DirectNormalizedOutputLease>()
            try { f.bound {
                val result = pool.submit {
                    val owned = f.owner.acquire(f.artifact, f.output, f.context)
                    lease.set(owned)
                    // A synthetic native-cleanup boundary; no Android/native release is claimed.
                    try { nativeReleaseEntered.countDown(); await(nativeReleaseReturns) }
                    finally { owned.close() }
                }
                await(nativeReleaseEntered)
                f.cancellation.cancel(); f.owner.revoke(f.artifact.artifactId)
                assertEquals(0, f.io.count("close")); assertFalse(lease.get().releaseConfirmed)
                assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
                nativeReleaseReturns.countDown(); result.get(5, TimeUnit.SECONDS)
                assertEquals(1, f.io.count("close")); assertTrue(lease.get().releaseConfirmed)
                assertFalse(f.owner.requiresProcessRecovery())
            } } finally { nativeReleaseReturns.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun revokeAndNativeUncertaintyAreMemoryOnlyWhileStorageMonitorIsHeld() {
        for (native in listOf(false, true)) AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            try { f.bound {
                val lease = f.owner.acquire(f.artifact, f.output, f.context)
                val ioBefore = f.io.calls.size
                synchronized(f.lock) {
                    pool.submit {
                        if (native) lease.retainOnNativeReleaseUncertainty() else f.owner.revoke(f.artifact.artifactId)
                        assertTrue(f.owner.requiresProcessRecovery())
                        assertFalse(lease.releaseConfirmed)
                    }.get(5, TimeUnit.SECONDS)
                }
                assertEquals(ioBefore, f.io.calls.size)
                if (native) assertThrows(SaveFailure::class.java) { lease.close() } else lease.close()
                assertEquals(1, f.io.count("close"))
                assertEquals(!native, lease.releaseConfirmed)
                assertEquals(native, f.owner.hasAnyLeases())
            } } finally { pool.shutdownNow() }
        }
    }

    @Test fun revalidationDirectoryCloseFailureDoesNotSkipOwnedRafCloseOrRetryDirectory() {
        AdoptedCanonicalTestFixture().use { f -> f.bound {
            val lease = f.owner.acquire(f.artifact, f.output, f.context)
            val descriptor = lease.readOnlyInput.fd
            val directoriesBefore = f.io.count("closeDirectory")
            f.io.directoryCloseFailure = true
            assertThrows(SaveFailure::class.java) { lease.revalidate() }
            assertEquals(directoriesBefore + 1, f.io.count("closeDirectory"))
            assertThrows(SaveFailure::class.java) { lease.close() }
            assertThrows(SaveFailure::class.java) { lease.close() }
            assertEquals(directoriesBefore + 1, f.io.count("closeDirectory"))
            assertEquals(1, f.io.count("close")); assertFalse(descriptor.valid())
            assertFalse(lease.releaseConfirmed); assertTrue(f.owner.hasAnyLeases())
            assertTrue(f.owner.requiresProcessRecovery())
        } }
    }

    @Test fun lexicalExitRevokesButCannotRetireWorkerUntilRealRelease() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val acquired = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val lease = AtomicReference<DirectNormalizedOutputLease>()
            try {
                val result = f.bound {
                    pool.submit {
                        val owned = f.owner.acquire(f.artifact, f.output, f.context)
                        lease.set(owned); acquired.countDown(); await(resume)
                        assertThrows(SaveFailure::class.java) { owned.revalidate() }
                        owned.close()
                    }.also { await(acquired) }
                }
                assertTrue(f.owner.hasAnyLeases()); assertTrue(f.owner.requiresProcessRecovery())
                assertFalse(lease.get().releaseConfirmed); assertEquals(0, f.io.count("close"))
                assertThrows(SaveFailure::class.java) { f.bound { fail("OLD_WORKER_REPLACED") } }
                resume.countDown(); result.get(5, TimeUnit.SECONDS)
                assertTrue(lease.get().releaseConfirmed); assertFalse(f.owner.hasAnyLeases())
                assertFalse(f.owner.requiresProcessRecovery())
                f.bound { f.owner.acquire(f.artifact, f.output, f.context).close() }
                assertEquals(2, f.io.count("open")); assertEquals(2, f.io.count("close"))
            } finally { resume.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun oldReleasedLeaseFinallyCannotRetireReplacementReservation() {
        AdoptedCanonicalTestFixture().use { f ->
            val old = f.bound {
                f.owner.acquire(f.artifact, f.output, f.context).also { it.close() }
            }
            f.bound {
                val fresh = f.owner.acquire(f.artifact, f.output, f.context)
                old.close()
                assertTrue(old.releaseConfirmed)
                assertTrue(f.owner.hasAnyLeases()); assertFalse(fresh.releaseConfirmed)
                fresh.revalidate()
                assertEquals(1, f.io.count("close"))
                fresh.close()
                assertTrue(fresh.releaseConfirmed); assertFalse(f.owner.hasAnyLeases())
            }
        }
    }

    @Test fun nativeUncertaintyLatchRemainsResponsiveDuringBlockedCloseAndCannotBeCleared() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val lease = AtomicReference<DirectNormalizedOutputLease>()
            f.io.before = { if (it.operation == "close") { entered.countDown(); await(resume) } }
            try { f.bound {
                val result = pool.submit<Throwable?> {
                    val owned = f.owner.acquire(f.artifact, f.output, f.context)
                    lease.set(owned)
                    try { owned.close(); null } catch (failure: Throwable) { failure }
                }
                await(entered)
                lease.get().retainOnNativeReleaseUncertainty()
                assertTrue(f.owner.requiresProcessRecovery()); assertFalse(lease.get().releaseConfirmed)
                resume.countDown()
                val failure = result.get(5, TimeUnit.SECONDS)
                assertTrue(failure is SaveFailure)
                assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, (failure as SaveFailure).code)
                assertEquals(1, f.io.count("close"))
                assertFalse(lease.get().releaseConfirmed); assertTrue(f.owner.hasAnyLeases())
            } } finally { resume.countDown(); pool.shutdownNow() }
        }
    }
}

package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.core.model.EpochClock
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ProviderHealthRepositoryTest {
    private class Clock(@Volatile var now: Long = ProviderPackageFixtures.NOW) : EpochClock {
        override fun nowMillis() = now
    }
    private fun repository(dir: File, clock: Clock = Clock(), fault: (ProviderWriteStage) -> Unit = {},
        selfTest: (ProviderPolicy, () -> Unit) -> Boolean = ProviderFixedSelfTest::run) =
        ProviderRepository(dir, ProviderPackageFixtures.verifier(), clock,
            ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits()), RepositoryPolicyFixtures.verifier(), fault, selfTest)
    private fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("esv-provider-health-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun ok(result: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue(result.toString(), result is ProviderRepositoryResult.Success)
        return (result as ProviderRepositoryResult.Success).snapshot
    }
    private fun rejected(code: ProviderRepositoryError, result: ProviderRepositoryResult) =
        assertEquals(code, (result as ProviderRepositoryResult.Failure).code)
    private fun observe(repo: ProviderRepository, outcome: ProviderHealthOutcome): ProviderRepositorySnapshot =
        ok(repo.completeObservation(requireNotNull(repo.beginObservation()), outcome))
    private fun stable(repo: ProviderRepository, version: Long, bytes: ByteArray = ProviderPackageFixtures.signed(version)) {
        ok(repo.install(bytes))
        repeat(3) { observe(repo, ProviderHealthOutcome.SUCCESS) }
        assertNull(repo.snapshot().pendingVersion)
    }
    private fun digest(bytes: ByteArray) = RepositoryPolicyFixtures.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun grant(repo: ProviderRepository, bytes: ByteArray, version: Long, policyVersion: Long = 1) =
        ok(repo.installPolicy(RepositoryPolicyFixtures.signed(version = policyVersion, rollbackTargetVersion = version,
            rollbackTargetDigest = RepositoryPolicyFixtures.digest(bytes))))

    @Test fun exactlyThreeConsecutiveFreshSuccessesPromoteWithoutChangingAuthority() = temp { dir ->
        repository(dir).use { repo ->
            val candidate = ok(repo.install(ProviderPackageFixtures.signed()))
            assertEquals(ProviderHealthPhase.PROBATION, candidate.health.phase)
            repeat(2) { index ->
                val snapshot = observe(repo, ProviderHealthOutcome.SUCCESS)
                assertEquals(1L, snapshot.pendingVersion)
                assertEquals(index + 1, snapshot.health.consecutiveSuccesses)
                assertEquals(index + 1, snapshot.health.totalObservations)
                assertEquals(candidate.generation, snapshot.generation)
            }
            val good = observe(repo, ProviderHealthOutcome.SUCCESS)
            assertNull(good.pendingVersion)
            assertEquals(1L, good.lastKnownGoodVersion)
            assertEquals(ProviderHealthPhase.HEALTHY, good.health.phase)
            assertEquals(candidate.generation, good.generation)
            assertNull(repo.beginObservation())
        }
    }

    @Test fun inconclusiveObservationsBreakBothStreaksWithoutBecomingStructuralEvidence() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            repeat(2) { observe(repo, ProviderHealthOutcome.SUCCESS) }
            val inconclusive = observe(repo, ProviderHealthOutcome.INCONCLUSIVE)
            assertEquals(0, inconclusive.health.consecutiveSuccesses)
            assertEquals(2, inconclusive.health.conclusiveObservations)
            assertEquals(3, inconclusive.health.totalObservations)
            repeat(2) { observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE) }
            val interrupted = observe(repo, ProviderHealthOutcome.INCONCLUSIVE)
            assertEquals(0, interrupted.health.consecutiveStructureFailures)
            assertTrue(interrupted.quarantinedVersions.isEmpty())
            repeat(3) { observe(repo, ProviderHealthOutcome.SUCCESS) }
            assertEquals(ProviderRepositoryState.ACTIVE, repo.snapshot().state)
        }
    }

    @Test fun aSingleOutstandingCapabilityCannotBeForgedOrReused() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            val token = requireNotNull(repo.beginObservation())
            assertNull(repo.beginObservation())
            rejected(ProviderRepositoryError.STALE_OBSERVATION,
                repo.completeObservation(ProviderHealthObservation(), ProviderHealthOutcome.SUCCESS))
            assertNull(repo.beginObservation())
            ok(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
            rejected(ProviderRepositoryError.STALE_OBSERVATION, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
            assertEquals(1, repo.snapshot().health.totalObservations)
            val abandoned = requireNotNull(repo.beginObservation())
            repo.abandonObservation(abandoned)
            rejected(ProviderRepositoryError.STALE_OBSERVATION, repo.completeObservation(abandoned, ProviderHealthOutcome.SUCCESS))
            val fresh = requireNotNull(repo.beginObservation())
            repo.abandonObservation(token) // A stale close must not release the current slot.
            assertNull(repo.beginObservation())
            repo.abandonObservation(fresh)
            assertEquals(1, repo.snapshot().health.totalObservations)
        }
    }

    @Test fun simultaneousDuplicateCompletionsCountOnlyOnce() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            val token = requireNotNull(repo.beginObservation())
            val start = CountDownLatch(1); val done = CountDownLatch(12); val successes = AtomicInteger(); val stale = AtomicInteger()
            repeat(12) {
                Thread {
                    try {
                        start.await()
                        when (val result = repo.completeObservation(token, ProviderHealthOutcome.SUCCESS)) {
                            is ProviderRepositoryResult.Success -> successes.incrementAndGet()
                            is ProviderRepositoryResult.Failure -> if (result.code == ProviderRepositoryError.STALE_OBSERVATION) stale.incrementAndGet()
                        }
                    } finally { done.countDown() }
                }.start()
            }
            start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, successes.get()); assertEquals(11, stale.get())
            assertEquals(1, repo.snapshot().health.totalObservations)
            assertEquals(1L, repo.snapshot().pendingVersion)
        }
    }

    @Test fun tokenFromAnotherRepositoryOrProcessCannotContribute() = temp { dir ->
        val firstDir = File(dir, "first"); val secondDir = File(dir, "second")
        val first = repository(firstDir)
        val oldToken: ProviderHealthObservation
        try {
            ok(first.install(ProviderPackageFixtures.signed()))
            oldToken = requireNotNull(first.beginObservation())
            repository(secondDir).use { second ->
                ok(second.install(ProviderPackageFixtures.signed()))
                rejected(ProviderRepositoryError.STALE_OBSERVATION, second.completeObservation(oldToken, ProviderHealthOutcome.SUCCESS))
                assertEquals(0, second.snapshot().health.totalObservations)
            }
        } finally { first.close() }
        repository(firstDir).use { restored ->
            rejected(ProviderRepositoryError.STALE_OBSERVATION, restored.completeObservation(oldToken, ProviderHealthOutcome.SUCCESS))
            assertNull(restored.beginObservation())
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, restored.snapshot().error)
        }
    }

    @Test fun policyChangeInvalidatesTokenAndStreakButNeverResetsExposureBudget() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            repeat(2) { observe(repo, ProviderHealthOutcome.SUCCESS) }
            val before = repo.snapshot()
            val token = requireNotNull(repo.beginObservation())
            val changed = ok(repo.installPolicy(RepositoryPolicyFixtures.signed()))
            rejected(ProviderRepositoryError.STALE_OBSERVATION, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
            assertEquals(0, changed.health.consecutiveSuccesses)
            assertEquals(2, changed.health.totalObservations)
            assertEquals(2, changed.health.conclusiveObservations)
            assertEquals(before.health.startedAtEpochMillis, changed.health.startedAtEpochMillis)
            assertEquals(before.health.deadlineEpochMillis, changed.health.deadlineEpochMillis)
            repeat(2) { observe(repo, ProviderHealthOutcome.SUCCESS) }
            assertEquals(1L, repo.snapshot().pendingVersion)
            observe(repo, ProviderHealthOutcome.SUCCESS)
            assertNull(repo.snapshot().pendingVersion)
        }
    }

    @Test fun newerCandidateCannotReceiveAnOlderCandidatesCompletion() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed(1)))
            val token = requireNotNull(repo.beginObservation())
            ok(repo.rejectCandidate(1))
            ok(repo.install(ProviderPackageFixtures.signed(2)))
            rejected(ProviderRepositoryError.STALE_OBSERVATION, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
            assertEquals(0, repo.snapshot().health.totalObservations)
            assertEquals(2L, repo.snapshot().pendingVersion)
        }
    }

    @Test fun cancellationConsumesCapabilityWithoutCountingOrPoisoningBeforeTransaction() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            val before = repo.snapshot(); val token = requireNotNull(repo.beginObservation())
            rejected(ProviderRepositoryError.CANCELLED, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) { throw InterruptedException() })
            assertEquals(before, repo.snapshot())
            rejected(ProviderRepositoryError.STALE_OBSERVATION, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
            assertNotNull(repo.beginObservation())
        }
    }

    @Test fun threeStructuralFailuresPersistIsolationAndStopWithoutAnExactFallbackGrant() = temp { dir ->
        val second = ProviderPackageFixtures.signed(2)
        repository(dir).use { repo ->
            stable(repo, 1); ok(repo.install(second))
            repeat(2) { observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE) }
            val rejected = observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE)
            assertFalse(rejected.policy.enabled)
            assertEquals(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, rejected.error)
            assertEquals(1L, rejected.lastKnownGoodVersion)
            assertEquals(2L, rejected.highestAcceptedVersion)
            assertNull(rejected.pendingVersion)
            assertEquals(ProviderQuarantinedVersion(2, digest(second), 0, null,
                ProviderQuarantineReason.STRUCTURE_FAILURES), rejected.quarantinedVersions.single())
        }
        repository(dir).use { repo ->
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(2L, repo.snapshot().quarantinedVersions.single().version)
            rejected(ProviderRepositoryError.REPLAYED_VERSION, repo.install(second))
        }
    }

    @Test fun exactCurrentFallbackGrantCanRestoreOnlyTheSafeLastGood() = temp { dir ->
        val first = ProviderPackageFixtures.signed(1)
        val second = ProviderPackageFixtures.signed(2)
        val policy = RepositoryPolicyFixtures.signed(rollbackTargetVersion = 1, rollbackTargetDigest = RepositoryPolicyFixtures.digest(first))
        repository(dir).use { repo ->
            stable(repo, 1, first); ok(repo.install(second)); ok(repo.installPolicy(policy))
            repeat(3) { observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE) }
            val state = repo.snapshot()
            assertTrue(state.policy.enabled); assertEquals(1L, state.activeVersion)
            assertEquals(1L, state.quarantinedVersions.single().policyVersion)
            assertEquals(digest(policy), state.quarantinedVersions.single().policyDigest)
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed(2)))
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, repo.snapshot().error)
        }
    }

    @Test fun revokedExpiredOrWrongDigestLastGoodNeverActivatesAfterStructuralRejection() {
        listOf("version", "key", "expiry", "digest", "floor").forEach { cause -> temp { dir ->
            val clock = Clock()
            val first = ProviderPackageFixtures.signed(1, expires = if (cause == "expiry") clock.now + 10 else clock.now + 60_000)
            repository(dir, clock).use { repo ->
                stable(repo, 1, first); ok(repo.install(ProviderPackageFixtures.signed(2)))
                ok(repo.installPolicy(RepositoryPolicyFixtures.signed(
                    rollbackTargetVersion = 1, rollbackTargetDigest = if (cause == "digest") ByteArray(32) else RepositoryPolicyFixtures.digest(first),
                    revokedPackageVersions = if (cause == "version") listOf(1) else emptyList(),
                    // Revoking the common signing key also disables candidate; use explicit rejection in this case.
                    revokedPackageKeyIds = if (cause == "key") listOf(ProviderPackageFixtures.KEY_ID) else emptyList(),
                    minimumSafePackageVersion = if (cause == "floor") 2 else 0)))
                if (cause == "expiry") clock.now += 10
                if (cause == "key") ok(repo.rejectCandidate(2)) else repeat(3) { observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE) }
                assertFalse(cause, repo.snapshot().policy.enabled)
            }
        } }
    }

    @Test fun twelveMixedConclusiveObservationsSuspendWithoutQuarantine() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            repeat(12) { observe(repo, if (it % 2 == 0) ProviderHealthOutcome.SUCCESS else ProviderHealthOutcome.STRUCTURE_FAILURE) }
            val state = repo.snapshot()
            assertEquals(12, state.health.conclusiveObservations)
            assertEquals(12, state.health.totalObservations)
            assertEquals(ProviderRepositoryError.HEALTH_BUDGET_EXHAUSTED, state.error)
            assertEquals(ProviderHealthPhase.SUSPENDED, state.health.phase)
            assertTrue(state.quarantinedVersions.isEmpty())
            assertTrue(state.canRejectCandidate); assertNull(repo.beginObservation())
            assertEquals(1L, state.pendingVersion)
        }
    }

    @Test fun twentyInconclusiveObservationsSuspendAndPolicyReplacementCannotRefillBudget() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            repeat(20) { observe(repo, ProviderHealthOutcome.INCONCLUSIVE) }
            val state = repo.snapshot()
            assertEquals(0, state.health.conclusiveObservations)
            assertEquals(20, state.health.totalObservations)
            assertEquals(ProviderRepositoryError.HEALTH_BUDGET_EXHAUSTED, state.error)
            assertTrue(state.quarantinedVersions.isEmpty())
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed()))
            assertEquals(20, repo.snapshot().health.totalObservations)
            assertNull(repo.beginObservation())
        }
    }

    @Test fun probationCountersPersistButRestartNeverResumesUnconfirmedCandidate() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            observe(repo, ProviderHealthOutcome.SUCCESS); observe(repo, ProviderHealthOutcome.INCONCLUSIVE)
            observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE)
        }
        repository(dir).use { repo ->
            val state = repo.snapshot()
            assertEquals(3, state.health.totalObservations); assertEquals(2, state.health.conclusiveObservations)
            assertEquals(1, state.health.consecutiveStructureFailures)
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, state.error)
            assertTrue(state.canRejectCandidate); assertNull(repo.beginObservation())
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed()))
            assertEquals(3, repo.snapshot().health.totalObservations)
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, repo.snapshot().error)
            val rejected = ok(repo.rejectCandidate(1))
            assertEquals(ProviderQuarantineReason.EXPLICIT_REJECTION, rejected.quarantinedVersions.single().reason)
            assertFalse(rejected.canRejectCandidate)
            ok(repo.install(ProviderPackageFixtures.signed(2)))
            assertEquals(0, repo.snapshot().health.totalObservations)
        }
    }

    @Test fun twentyFourHourWindowAndShorterPolicyExpiryStopWithoutAttributingNetworkFailure() = temp { dir ->
        val clock = Clock()
        repository(dir, clock).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed(expires = clock.now + 2 * ProviderHealthLimits.MAX_PROBATION_MILLIS)))
            val deadline = repo.snapshot().health.deadlineEpochMillis!!
            assertEquals(clock.now + ProviderHealthLimits.MAX_PROBATION_MILLIS, deadline)
            val token = requireNotNull(repo.beginObservation())
            clock.now = deadline
            rejected(ProviderRepositoryError.HEALTH_WINDOW_EXPIRED, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
            assertEquals(0, repo.snapshot().health.totalObservations)
            assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
            assertTrue(repo.snapshot().canRejectCandidate)
            assertNull(repo.beginObservation())
        }
    }

    @Test fun policyExpiryCannotBeExtendedToRefillAnExistingProbationWindow() = temp { dir ->
        val clock = Clock()
        repository(dir, clock).use { repo ->
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed(expires = clock.now + 100)))
            ok(repo.install(ProviderPackageFixtures.signed()))
            assertEquals(clock.now + 100, repo.snapshot().health.deadlineEpochMillis)
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed(2, expires = clock.now + 10_000)))
            clock.now += 100
            assertEquals(ProviderRepositoryError.HEALTH_WINDOW_EXPIRED, repo.snapshot().error)
            assertNull(repo.beginObservation()); assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
        }
    }

    @Test fun publicationAfterProbationDeadlineNeverPromotesEvenWithNoCallerDeadline() = temp { dir ->
        val clock = Clock(); var armed = false
        repository(dir, clock, fault = { if (armed && it == ProviderWriteStage.AFTER_COMMIT_SYNC) clock.now += ProviderHealthLimits.MAX_PROBATION_MILLIS }).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed(expires = clock.now + 2 * ProviderHealthLimits.MAX_PROBATION_MILLIS)))
            repeat(2) { observe(repo, ProviderHealthOutcome.SUCCESS) }
            val token = requireNotNull(repo.beginObservation()); armed = true
            rejected(ProviderRepositoryError.HEALTH_WINDOW_EXPIRED, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
            assertFalse(repo.snapshot().policy.enabled)
            assertNull(repo.snapshot().lastKnownGoodVersion)
        }
        repository(dir, clock).use { assertEquals(ProviderRepositoryError.CORRUPT_STATE, it.snapshot().error) }
    }

    @Test fun healthWriteFaultsAndLateCancellationRemainFailClosedAcrossRestart() {
        ProviderWriteStage.entries.forEach { stage -> temp { dir ->
            var armed = false
            repository(dir, fault = { if (armed && it == stage) throw IOException("private") }).use { repo ->
                ok(repo.install(ProviderPackageFixtures.signed()))
                val token = requireNotNull(repo.beginObservation()); armed = true
                rejected(ProviderRepositoryError.STORAGE_FAILURE, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
                assertFalse(repo.snapshot().policy.enabled)
            }
            repository(dir).use { assertEquals(ProviderRepositoryError.CORRUPT_STATE, it.snapshot().error) }
        } }
        temp { dir ->
            var armed = false; var cancelled = false
            repository(dir, fault = { if (armed && it == ProviderWriteStage.AFTER_COMMIT_SYNC) cancelled = true }).use { repo ->
                ok(repo.install(ProviderPackageFixtures.signed()))
                val token = requireNotNull(repo.beginObservation()); armed = true
                rejected(ProviderRepositoryError.CANCELLED, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) {
                    if (cancelled) throw InterruptedException()
                })
                assertFalse(repo.snapshot().policy.enabled)
            }
            repository(dir).use { assertEquals(ProviderRepositoryError.CORRUPT_STATE, it.snapshot().error) }
        }
    }

    @Test fun failedFixedSelfTestIsDurablyIsolatedWithoutReplacingActiveOrHighWater() = temp { dir ->
        var rejectSelfTest = false
        val bad = ProviderPackageFixtures.signed(2)
        repository(dir, selfTest = { policy, check -> check(); !rejectSelfTest && ProviderFixedSelfTest.run(policy, check) }).use { repo ->
            stable(repo, 1); rejectSelfTest = true
            rejected(ProviderRepositoryError.SELF_TEST_FAILED, repo.install(bad))
            assertEquals(1L, repo.snapshot().activeVersion); assertEquals(1L, repo.snapshot().highestAcceptedVersion)
            assertEquals(ProviderQuarantineReason.FIXED_SELF_TEST_FAILED, repo.snapshot().quarantinedVersions.single().reason)
            assertEquals(digest(bad), repo.snapshot().quarantinedVersions.single().packageDigest)
            rejected(ProviderRepositoryError.QUARANTINED, repo.install(bad))
        }
        repository(dir).use { repo ->
            assertEquals(1L, repo.snapshot().activeVersion)
            // A different valid envelope cannot reuse an isolated package version.
            rejected(ProviderRepositoryError.QUARANTINED, repo.install(ProviderPackageFixtures.signed(2, limits = CommonsLimits(pageSize = 2))))
        }
    }

    @Test fun firstSelfTestFailureCanPersistWithoutAnyPreviouslyAcceptedSignedAuthority() = temp { dir ->
        repository(dir, selfTest = { _, _ -> false }).use { repo ->
            rejected(ProviderRepositoryError.SELF_TEST_FAILED, repo.install(ProviderPackageFixtures.signed()))
            assertEquals(ProviderRepositoryState.BUNDLED, repo.snapshot().state)
            assertEquals(0L, repo.snapshot().highestAcceptedVersion)
        }
        repository(dir).use { repo ->
            assertEquals(ProviderRepositoryState.BUNDLED, repo.snapshot().state)
            assertEquals(1L, repo.snapshot().quarantinedVersions.single().version)
            rejected(ProviderRepositoryError.QUARANTINED, repo.install(ProviderPackageFixtures.signed()))
            ok(repo.install(ProviderPackageFixtures.signed(2)))
        }
    }

    @Test fun fixedSelfTestCancellationDoesNotQuarantineOrPoisonTheStore() = temp { dir ->
        var cancelled = false
        repository(dir, selfTest = { _, check -> cancelled = true; check(); false }).use { repo ->
            val before = repo.snapshot()
            rejected(ProviderRepositoryError.CANCELLED, repo.install(ProviderPackageFixtures.signed()) { if (cancelled) throw InterruptedException() })
            assertEquals(before, repo.snapshot())
            assertFalse(File(dir, "provider-state.intent").exists())
            assertFalse(File(dir, "provider-state.bin").exists())
        }
    }

    @Test fun selfTestCannotActivateAnEnvelopeThatExpiresWhileTesting() = temp { dir ->
        val clock = Clock()
        repository(dir, clock, selfTest = { _, _ -> clock.now += 100; true }).use { repo ->
            rejected(ProviderRepositoryError.EXPIRED, repo.install(ProviderPackageFixtures.signed(expires = clock.now + 100)))
            assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
            assertEquals(0L, repo.snapshot().highestAcceptedVersion)
        }
    }

    @Test fun isolationCapacityIsBoundedPermanentAndNeverEvictsSecurityHistory() = temp { dir ->
        repository(dir, selfTest = { _, _ -> false }).use { repo ->
            repeat(ProviderHealthLimits.MAX_QUARANTINED_VERSIONS) { index ->
                rejected(ProviderRepositoryError.SELF_TEST_FAILED, repo.install(ProviderPackageFixtures.signed(index + 1L)))
            }
            rejected(ProviderRepositoryError.QUARANTINE_CAPACITY_REACHED, repo.install(ProviderPackageFixtures.signed(33)))
            assertEquals(32, repo.snapshot().quarantinedVersions.size)
            try { (repo.snapshot().quarantinedVersions as MutableList<ProviderQuarantinedVersion>).clear(); fail("mutable quarantine") } catch (_: UnsupportedOperationException) { }
            // A safe signed disable remains admissible and does not clear the isolation history.
            ok(repo.install(ProviderPackageFixtures.signed(33, enabled = false)))
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(32, repo.snapshot().quarantinedVersions.size)
        }
        repository(dir).use { repo ->
            assertEquals(32, repo.snapshot().quarantinedVersions.size)
            assertEquals(33L, repo.snapshot().highestAcceptedVersion)
            rejected(ProviderRepositoryError.QUARANTINE_CAPACITY_REACHED, repo.install(ProviderPackageFixtures.signed(34)))
        }
    }

    @Test fun formatTwoCannotBeSilentlyResetOrMigrated() = temp { dir ->
        repository(dir).use { stable(it, 1) }
        val file = File(dir, "provider-state.bin")
        val original = file.readBytes(); val payload = original.copyOf(original.size - 32)
        ByteBuffer.wrap(payload).putInt(4, 2)
        val legacy = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        file.writeBytes(legacy)
        repository(dir).use { repo ->
            assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
            rejected(ProviderRepositoryError.CORRUPT_STATE, repo.install(ProviderPackageFixtures.signed(2)))
        }
        assertArrayEquals(legacy, file.readBytes())
    }

    @Test fun healthCounterChangesNotifyObserversWithoutRevokingUnchangedAuthority() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            val before = repo.snapshot(); val changed = CountDownLatch(1)
            repo.observeChanges { changed.countDown() }.use {
                observe(repo, ProviderHealthOutcome.SUCCESS)
                assertTrue(changed.await(2, TimeUnit.SECONDS))
                assertEquals(before.generation, repo.snapshot().generation)
                assertEquals(before.policy, repo.snapshot().policy)
            }
        }
    }

    @Test fun managementRollbackCannotActOnAuthorityChangedAfterConfirmation() = temp { dir ->
        repository(dir).use { repo ->
            val first = ProviderPackageFixtures.signed(1)
            stable(repo, 1, first); stable(repo, 2)
            grant(repo, first, 1)
            val confirmedGeneration = repo.snapshot().generation
            grant(repo, first, 1, policyVersion = 2)
            val before = repo.snapshot()
            rejected(ProviderRepositoryError.STALE_CANDIDATE, repo.rollback(confirmedGeneration))
            assertEquals(before, repo.snapshot())
            assertEquals(1L, ok(repo.rollback(before.generation)).activeVersion)
        }
    }


    @Test fun callerCallbacksCannotReenterAnyMutationOrAllocateANestedObservation() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(ProviderPackageFixtures.signed()))
            val token = requireNotNull(repo.beginObservation())
            val before = repo.snapshot()
            var checks = 0
            ok(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) {
                checks++
                rejected(ProviderRepositoryError.BUSY, repo.installPolicy(RepositoryPolicyFixtures.signed()))
                rejected(ProviderRepositoryError.BUSY, repo.install(ProviderPackageFixtures.signed(2)))
                rejected(ProviderRepositoryError.BUSY, repo.rejectCandidate(1))
                rejected(ProviderRepositoryError.BUSY, repo.rollback())
                rejected(ProviderRepositoryError.BUSY, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
                assertNull(repo.beginObservation())
                assertEquals(0L, repo.snapshot().highestAcceptedPolicyVersion)
            })
            assertTrue(checks > 1)
            assertEquals(before.generation, repo.snapshot().generation)
            assertEquals(1L, repo.snapshot().highestAcceptedVersion)
            assertEquals(0L, repo.snapshot().highestAcceptedPolicyVersion)
            assertEquals(1, repo.snapshot().health.totalObservations)
            // try/finally releases the reentrancy gate, so a subsequent normal mutation works.
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed()))
            assertEquals(1L, repo.snapshot().highestAcceptedPolicyVersion)
        }
    }

    @Test fun closeInsideACallbackBeforeTransactionCannotPublishOrCountAnObservation() = temp { dir ->
        val repo = repository(dir)
        ok(repo.install(ProviderPackageFixtures.signed()))
        val token = requireNotNull(repo.beginObservation())
        rejected(ProviderRepositoryError.CLOSED, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) { repo.close() })
        assertEquals(0, repo.snapshot().health.totalObservations)
        assertEquals(ProviderRepositoryError.CLOSED, repo.snapshot().error)
        repository(dir).use { restored ->
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, restored.snapshot().error)
            assertEquals(0, restored.snapshot().health.totalObservations)
            assertNull(restored.snapshot().lastKnownGoodVersion)
        }
    }

    @Test fun closeInsideLatePublicationCallbackCannotLeavePromotedStateAfterRestart() = temp { dir ->
        var armed = false; var closeNow = false
        val repo = repository(dir, fault = { if (armed && it == ProviderWriteStage.AFTER_COMMIT_SYNC) closeNow = true })
        ok(repo.install(ProviderPackageFixtures.signed()))
        repeat(2) { observe(repo, ProviderHealthOutcome.SUCCESS) }
        val token = requireNotNull(repo.beginObservation()); armed = true
        rejected(ProviderRepositoryError.CLOSED, repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) {
            if (closeNow) {
                repo.close()
                // Revocation is immediate, but the physical owner stays locked through fencing.
                assertEquals(ProviderRepositoryError.CLOSED, repo.snapshot().error)
                repository(dir).use { contender -> assertEquals(ProviderRepositoryError.BUSY, contender.snapshot().error) }
            }
        })
        assertNull(repo.snapshot().lastKnownGoodVersion)
        repository(dir).use { restored ->
            assertEquals(ProviderRepositoryError.CORRUPT_STATE, restored.snapshot().error)
            assertFalse(restored.snapshot().policy.enabled)
        }
    }


    @Test fun allMutatorsFenceCloseBeforePublishingAndKeepUnpublishedDiskStateClosed() {
        listOf("install", "policy", "reject", "rollback").forEach { operation -> temp { dir ->
            var armed = false; var closeNow = false
            val repo = repository(dir, fault = { if (armed && it == ProviderWriteStage.AFTER_COMMIT_SYNC) closeNow = true })
            val first = ProviderPackageFixtures.signed(1)
            stable(repo, 1, first); stable(repo, 2); grant(repo, first, 1)
            if (operation == "reject") ok(repo.install(ProviderPackageFixtures.signed(3)))
            armed = true
            val callback: () -> Unit = { if (closeNow) repo.close() }
            val result = when (operation) {
                "install" -> repo.install(ProviderPackageFixtures.signed(3), callback)
                "policy" -> repo.installPolicy(RepositoryPolicyFixtures.signed(2), callback)
                "reject" -> repo.rejectCandidate(3, callback)
                else -> repo.rollback(callback)
            }
            rejected(ProviderRepositoryError.CLOSED, result)
            assertEquals(ProviderRepositoryError.CLOSED, repo.snapshot().error)
            repository(dir).use { restored ->
                assertEquals(operation, ProviderRepositoryError.CORRUPT_STATE, restored.snapshot().error)
                assertFalse(restored.snapshot().policy.enabled)
            }
        } }
    }

    @Test fun closingFromFixedSelfTestCannotStartATransaction() = temp { dir ->
        lateinit var repo: ProviderRepository
        repo = repository(dir, selfTest = { _, check -> repo.close(); check(); true })
        rejected(ProviderRepositoryError.CLOSED, repo.install(ProviderPackageFixtures.signed()))
        assertFalse(File(dir, "provider-state.bin").exists())
        assertFalse(File(dir, "provider-state.intent").exists())
        repository(dir).use { restored -> assertEquals(ProviderRepositoryState.BUNDLED, restored.snapshot().state) }
    }

}

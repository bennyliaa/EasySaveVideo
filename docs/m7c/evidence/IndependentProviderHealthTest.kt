package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.*
import com.easysavevideo.core.model.EpochClock
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Reviewer-owned cases; all traffic and signing identities are isolated JVM fixtures. */
class IndependentProviderHealthTest {
    private val now = ProviderPackageFixtures.NOW
    private val lifetime = 3L * ProviderHealthLimits.MAX_PROBATION_MILLIS
    private fun packageBytes(version: Long = 1, enabled: Boolean = true, limits: CommonsLimits = CommonsLimits()) =
        ProviderPackageFixtures.signed(version, enabled, expires = now + lifetime, limits = limits)
    private fun repository(dir: File, clock: EpochClock = EpochClock { now }, fault: (ProviderWriteStage) -> Unit = {}) =
        ProviderRepository(dir, ProviderPackageFixtures.verifier(), clock,
            ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits()), RepositoryPolicyFixtures.verifier(), fault)
    private fun temp(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("esv-m7c-review-").toFile()
        try { action(dir) } finally { dir.deleteRecursively() }
    }
    private fun ok(value: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue("Expected success, got $value", value is ProviderRepositoryResult.Success)
        return (value as ProviderRepositoryResult.Success).snapshot
    }
    private fun denied(value: ProviderRepositoryResult) { assertTrue(value.toString(), value is ProviderRepositoryResult.Failure) }
    private fun observe(repo: ProviderRepository, outcome: ProviderHealthOutcome) =
        repo.completeObservation(requireNotNull(repo.beginObservation()), outcome)
    private fun promote(repo: ProviderRepository) { repeat(3) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) } }
    private fun grant(repo: ProviderRepository, version: Long = 1, target: ByteArray = packageBytes(1), revoked: List<Long> = emptyList()) =
        ok(repo.installPolicy(RepositoryPolicyFixtures.signed(version, expires = now + lifetime,
            rollbackTargetVersion = 1, rollbackTargetDigest = RepositoryPolicyFixtures.digest(target), revokedPackageVersions = revoked)))

    @Test fun onlyThreeIndependentOneUseTokensPromote() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); val original = repo.snapshot().generation
        val first = requireNotNull(repo.beginObservation()); assertNull(repo.beginObservation())
        ok(repo.completeObservation(first, ProviderHealthOutcome.SUCCESS))
        denied(repo.completeObservation(first, ProviderHealthOutcome.SUCCESS))
        assertEquals(1, repo.snapshot().health.consecutiveSuccesses)
        ok(observe(repo, ProviderHealthOutcome.SUCCESS)); assertEquals(1L, repo.snapshot().pendingVersion)
        ok(observe(repo, ProviderHealthOutcome.SUCCESS))
        assertNull(repo.snapshot().pendingVersion); assertEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
        assertEquals(original, repo.snapshot().generation); assertNull(repo.beginObservation())
    } }
    @Test fun foreignAndAbandonedTokensHaveNoHealthEffect() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); denied(repo.completeObservation(ProviderHealthObservation(), ProviderHealthOutcome.SUCCESS))
        val token = requireNotNull(repo.beginObservation()); repo.abandonObservation(token)
        denied(repo.completeObservation(token, ProviderHealthOutcome.STRUCTURE_FAILURE))
        assertEquals(0, repo.snapshot().health.totalObservations); assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
        assertNotNull(repo.beginObservation())
    } }
    @Test fun policyGenerationInvalidatesOldTokenAndResetsOnlyConsecutiveRuns() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); repeat(2) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) }
        val old = requireNotNull(repo.beginObservation()); val before = repo.snapshot()
        ok(repo.installPolicy(RepositoryPolicyFixtures.signed(expires = now + lifetime)))
        denied(repo.completeObservation(old, ProviderHealthOutcome.SUCCESS))
        val after = repo.snapshot(); assertTrue(after.generation > before.generation)
        assertEquals(2, after.health.totalObservations); assertEquals(2, after.health.conclusiveObservations)
        assertEquals(0, after.health.consecutiveSuccesses); assertEquals(before.health.startedAtEpochMillis, after.health.startedAtEpochMillis)
        repeat(2) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) }; assertNotNull(repo.snapshot().pendingVersion)
        ok(observe(repo, ProviderHealthOutcome.SUCCESS)); assertNull(repo.snapshot().pendingVersion)
    } }
    @Test fun inconclusiveBreaksBothConsecutiveRuns() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); repeat(2) { ok(observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE)) }
        ok(observe(repo, ProviderHealthOutcome.INCONCLUSIVE)); assertEquals(0, repo.snapshot().health.consecutiveStructureFailures)
        repeat(2) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) }
        ok(observe(repo, ProviderHealthOutcome.INCONCLUSIVE)); assertEquals(0, repo.snapshot().health.consecutiveSuccesses)
        assertEquals(4, repo.snapshot().health.conclusiveObservations); assertEquals(6, repo.snapshot().health.totalObservations)
        assertTrue(repo.snapshot().quarantinedVersions.isEmpty()); assertNotNull(repo.snapshot().pendingVersion)
    } }
    @Test fun twelveConclusiveWithoutRunOnlySuspendAndRestartNeverResumes() = temp { dir ->
        repository(dir).use { repo ->
            ok(repo.install(packageBytes())); repeat(12) { ok(observe(repo, if (it % 2 == 0) ProviderHealthOutcome.SUCCESS else ProviderHealthOutcome.STRUCTURE_FAILURE)) }
            assertFalse(repo.snapshot().policy.enabled); assertEquals(ProviderRepositoryError.HEALTH_BUDGET_EXHAUSTED, repo.snapshot().error)
            assertEquals(12, repo.snapshot().health.conclusiveObservations); assertTrue(repo.snapshot().quarantinedVersions.isEmpty()); assertNull(repo.beginObservation())
        }
        repository(dir).use { repo ->
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, repo.snapshot().error)
            assertEquals(12, repo.snapshot().health.totalObservations); assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
            assertEquals(1L, repo.snapshot().highestAcceptedVersion)
        }
    }
    @Test fun twentyInconclusiveOnlySuspendWithoutCodeBlame() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); repeat(20) { ok(observe(repo, ProviderHealthOutcome.INCONCLUSIVE)) }
        assertFalse(repo.snapshot().policy.enabled); assertEquals(20, repo.snapshot().health.totalObservations)
        assertEquals(0, repo.snapshot().health.conclusiveObservations); assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
        assertNull(repo.beginObservation()); assertNotNull(repo.snapshot().pendingVersion)
    } }
    @Test fun wallClockBoundOnlySuspendsAndCannotResetViaPolicyUpdate() = temp { dir ->
        var time = now
        repository(dir, EpochClock { time }).use { repo ->
            ok(repo.install(packageBytes())); val deadline = repo.snapshot().health.deadlineEpochMillis
            time = now + ProviderHealthLimits.MAX_PROBATION_MILLIS
            assertEquals(ProviderRepositoryError.HEALTH_WINDOW_EXPIRED, repo.snapshot().error)
            assertTrue(repo.snapshot().quarantinedVersions.isEmpty()); assertNull(repo.beginObservation())
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed(expires = now + lifetime)))
            assertEquals(deadline, repo.snapshot().health.deadlineEpochMillis); assertFalse(repo.snapshot().policy.enabled)
        }
    }
    @Test fun restartPendingCountersRetainedButNoObservationCanResume() = temp { dir ->
        repository(dir).use { repo -> ok(repo.install(packageBytes())); repeat(2) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) } }
        repository(dir).use { repo ->
            assertEquals(2, repo.snapshot().health.consecutiveSuccesses); assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, repo.snapshot().error)
            assertNull(repo.beginObservation()); assertTrue(repo.snapshot().canRejectCandidate)
            ok(repo.installPolicy(RepositoryPolicyFixtures.signed(expires = now + lifetime)))
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, repo.snapshot().error); assertNull(repo.beginObservation())
        }
    }
    @Test fun threeStructuralFailuresQuarantineExactEnvelopeAndRetainAnchors() = temp { dir ->
        val candidate = packageBytes(2)
        repository(dir).use { repo ->
            ok(repo.install(packageBytes())); promote(repo); grant(repo)
            ok(repo.install(candidate)); repeat(3) { ok(observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE)) }
            val s = repo.snapshot(); assertEquals(1L, s.activeVersion); assertEquals(2L, s.highestAcceptedVersion)
            assertEquals(1L, s.highestAcceptedPolicyVersion); assertEquals(1, s.quarantinedVersions.size)
            val q = s.quarantinedVersions.single(); assertEquals(2L, q.version)
            assertEquals(RepositoryPolicyFixtures.digest(candidate).joinToString("") { "%02x".format(it.toInt() and 255) }, q.packageDigest)
            assertEquals(ProviderQuarantineReason.STRUCTURE_FAILURES, q.reason); assertEquals(1L, q.policyVersion)
            assertNotNull(q.policyDigest); assertNull(s.pendingVersion)
        }
        repository(dir).use { repo -> assertEquals(1L, repo.snapshot().activeVersion); assertEquals(1, repo.snapshot().quarantinedVersions.size) }
    }
    @Test fun missingWrongAndRevokedGrantsCannotEnableQuarantineFallback() {
        for (mode in 0..2) temp { dir -> repository(dir).use { repo ->
            ok(repo.install(packageBytes())); promote(repo)
            if (mode == 1) grant(repo, target = packageBytes(1, limits = CommonsLimits(pageSize = 2)))
            if (mode == 2) grant(repo, revoked = listOf(1))
            ok(repo.install(packageBytes(2))); repeat(3) { ok(observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE)) }
            assertFalse(repo.snapshot().policy.enabled); assertNull(repo.snapshot().activeVersion)
            assertEquals(2L, repo.snapshot().highestAcceptedVersion); assertEquals(1, repo.snapshot().quarantinedVersions.size)
        } }
    }
    @Test fun latestGrantWithdrawalPreventsAutomaticFallback() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); promote(repo); grant(repo); ok(repo.install(packageBytes(2)))
        ok(observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE))
        ok(repo.installPolicy(RepositoryPolicyFixtures.signed(2, expires = now + lifetime)))
        repeat(3) { ok(observe(repo, ProviderHealthOutcome.STRUCTURE_FAILURE)) }
        assertFalse(repo.snapshot().policy.enabled); assertEquals(2L, repo.snapshot().highestAcceptedPolicyVersion)
        assertEquals(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, repo.snapshot().error)
    } }
    @Test fun thirtyTwoIsolatedFailuresRemainBoundedAcrossReopenAndKillSwitchStillWorks() = temp { dir ->
        repository(dir).use { repo ->
            for (version in 1L..32L) {
                val result = repo.install(packageBytes(version, limits = CommonsLimits(maxResponseBytes = 1)))
                assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.SELF_TEST_FAILED), result)
            }
            assertEquals(32, repo.snapshot().quarantinedVersions.size)
            assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.QUARANTINE_CAPACITY_REACHED), repo.install(packageBytes(33)))
        }
        repository(dir).use { repo ->
            assertEquals(32, repo.snapshot().quarantinedVersions.size)
            denied(repo.install(packageBytes(1))); denied(repo.install(packageBytes(33)))
            ok(repo.install(packageBytes(34, enabled = false))); assertFalse(repo.snapshot().policy.enabled)
            assertEquals(34L, repo.snapshot().highestAcceptedVersion); assertEquals(32, repo.snapshot().quarantinedVersions.size)
        }
    }
    @Test fun finalHealthPublicationCannotEraseAnExpiredProbationWindow() {
        for (stage in ProviderWriteStage.entries) temp { dir ->
            var time = now; var armed = false
            repository(dir, EpochClock { time }) { if (armed && it == stage) time = now + ProviderHealthLimits.MAX_PROBATION_MILLIS }.use { repo ->
                ok(repo.install(packageBytes())); repeat(2) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) }
                val token = requireNotNull(repo.beginObservation()); armed = true
                denied(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
                assertFalse(repo.snapshot().policy.enabled)
            }
            repository(dir, EpochClock { time }).use { repo -> assertFalse(repo.snapshot().policy.enabled); assertNotEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase) }
        }
    }
    @Test fun rollbackRequiresTheReviewedGenerationEvenWhenNewTargetIsLegal() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); promote(repo)
        ok(repo.install(packageBytes(2))); promote(repo); grant(repo)
        val reviewed = repo.snapshot(); assertTrue(reviewed.canRollback)
        grant(repo, version = 2)
        assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.STALE_CANDIDATE), repo.rollback(reviewed.generation))
        assertEquals(2L, repo.snapshot().activeVersion)
        ok(repo.rollback(repo.snapshot().generation)); assertEquals(1L, repo.snapshot().activeVersion)
    } }
    @Test fun reentrantObservationCallbackCannotReplaceNewerPolicyEvidence() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); val token = requireNotNull(repo.beginObservation())
        var nested: ProviderRepositoryResult? = null
        ok(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) {
            if (nested == null) nested = repo.installPolicy(RepositoryPolicyFixtures.signed(expires = now + lifetime))
        })
        assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.BUSY), nested)
        assertEquals(0L, repo.snapshot().highestAcceptedPolicyVersion); assertEquals(1, repo.snapshot().health.totalObservations)
        ok(repo.installPolicy(RepositoryPolicyFixtures.signed(expires = now + lifetime)))
        assertEquals(1L, repo.snapshot().highestAcceptedPolicyVersion)
    } }
    @Test fun reentrantInstallCallbackCannotAdvanceThenLoseSignedHighWater() = temp { dir -> repository(dir).use { repo ->
        var nested: ProviderRepositoryResult? = null
        ok(repo.install(packageBytes()) {
            if (nested == null) nested = repo.install(packageBytes(99, enabled = false))
        })
        assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.BUSY), nested)
        assertEquals(1L, repo.snapshot().highestAcceptedVersion); assertEquals(1L, repo.snapshot().pendingVersion)
    } }
    @Test fun lateCloseCannotPublishHealthyBytesOrReleaseOwnerBeforeUncertaintyMarker() {
        for (stage in ProviderWriteStage.entries) temp { dir ->
            var armed = false
            lateinit var repo: ProviderRepository
            repo = repository(dir, fault = {
                if (armed && it == stage) {
                    repo.close()
                    repository(dir).use { overlapping -> assertEquals(ProviderRepositoryError.BUSY, overlapping.snapshot().error) }
                }
            })
            repo.use {
                ok(repo.install(packageBytes())); repeat(2) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) }
                val token = requireNotNull(repo.beginObservation()); armed = true
                denied(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS)); assertFalse(repo.snapshot().policy.enabled)
            }
            repository(dir).use { reopened -> assertFalse(reopened.snapshot().policy.enabled) }
        }
    }
    @Test fun reentrantCloseBeforeAWriteNeverCreatesAUsableCandidate() = temp { dir ->
        val repo = repository(dir)
        repo.use { denied(repo.install(packageBytes()) { repo.close() }); assertFalse(repo.snapshot().policy.enabled) }
        repository(dir).use { reopened -> assertEquals(0L, reopened.snapshot().highestAcceptedVersion); assertNull(reopened.snapshot().pendingVersion) }
    }
    @Test fun cancelledObservationTokenCannotBeReused() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); val token = requireNotNull(repo.beginObservation())
        denied(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) { throw CancellationException("synthetic") })
        denied(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS))
        assertEquals(0, repo.snapshot().health.totalObservations); assertNotNull(repo.beginObservation())
    } }
    @Test fun cancelledHealthWritesStayFailClosedAtEveryDurabilityStage() {
        for (stage in ProviderWriteStage.entries) temp { dir ->
            var armed = false; var cancelled = false
            repository(dir, fault = { if (armed && it == stage) cancelled = true }).use { repo ->
                ok(repo.install(packageBytes())); repeat(2) { ok(observe(repo, ProviderHealthOutcome.SUCCESS)) }
                armed = true; val token = requireNotNull(repo.beginObservation())
                denied(repo.completeObservation(token, ProviderHealthOutcome.SUCCESS) { if (cancelled) throw CancellationException("synthetic") })
                assertFalse(repo.snapshot().policy.enabled)
            }
            repository(dir).use { repo -> assertFalse(repo.snapshot().policy.enabled) }
        }
    }
    @Test fun cachedResponsesDoNotPromoteRepositoryCandidate() = temp { dir -> repository(dir).use { repo ->
        ok(repo.install(packageBytes())); val clock = TestClock(now)
        val transport = TestTransport(Reply(), Reply(), Reply())
        val provider = CommonsMetadataProvider(config(), transport, clock = clock, timer = TestTimer(), maintenance = RepositoryCommonsMaintenance(repo))
        fun search() = provider.search(request, operation(now + 10_000))
        search(); repeat(6) { assertTrue(search().fromCache) }
        assertEquals(1, repo.snapshot().health.consecutiveSuccesses); assertEquals(1, transport.requests.size)
        provider.clearCache(); search(); assertNotNull(repo.snapshot().pendingVersion)
        provider.clearCache(); search(); assertNull(repo.snapshot().pendingVersion)
    } }
    @Test fun malformedSuccessMarkersCannotPromote() {
        for (body in listOf("""{"batchcomplete":null}""", """{"batchcomplete":false}""", """{"query":{}}""")) temp { dir -> repository(dir).use { repo ->
            ok(repo.install(packageBytes())); val transport = TestTransport(*Array(3) { Reply(body.toByteArray()) })
            val provider = CommonsMetadataProvider(config(), transport, clock = TestClock(now), timer = TestTimer(), maintenance = RepositoryCommonsMaintenance(repo))
            repeat(3) { try { provider.search(request, operation(now + 10_000)) } catch (_: CommonsFailure) {}; provider.clearCache() }
            assertNotEquals("malformed success marker: $body", ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
        } }
    }
}

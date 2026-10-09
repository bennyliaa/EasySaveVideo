package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.*
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.DiscoveryCancellation
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepositoryCommonsMaintenanceTest {
    @get:Rule val directory = TemporaryFolder()
    private fun repository() = ProviderRepository(directory.newFolder(), ProviderPackageFixtures.verifier(), EpochClock { ProviderPackageFixtures.NOW })

    @Test fun emptyRootsCanNeverInstallFixturePolicy() {
        ProviderRepository(directory.newFolder(), clock = EpochClock { ProviderPackageFixtures.NOW }).use { repo ->
            assertTrue(repo.install(ProviderPackageFixtures.signed()) is ProviderRepositoryResult.Failure)
            assertEquals(0L, repo.snapshot().highestAcceptedVersion)
            assertTrue(repo.snapshot().policy.enabled)
        }
    }
    @Test fun remoteDisableRefusesNewLeaseAndRevokesExistingLease() {
        repository().use { repo ->
            val cancellation = DiscoveryCancellation()
            val gate = RepositoryCommonsMaintenance(repo)
            gate.acquire(cancellation).use { lease ->
                assertTrue(repo.install(ProviderPackageFixtures.signed(enabled = false)) is ProviderRepositoryResult.Success)
                fails(CommonsError.DISABLED) { lease.check() }
                assertTrue(cancellation.isCancelled)
            }
            fails(CommonsError.DISABLED) { gate.acquire(DiscoveryCancellation()) }
        }
    }
    @Test fun observerSignalsRevocationWithoutWaitingForBodyCheckpoint() {
        repository().use { repo ->
            val cancellation = DiscoveryCancellation()
            RepositoryCommonsMaintenance(repo).acquire(cancellation).use {
                repo.install(ProviderPackageFixtures.signed(enabled = false))
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (!cancellation.isCancelled && System.nanoTime() < limit) Thread.sleep(1)
                assertTrue(cancellation.isCancelled)
            }
        }
    }
    @Test fun closingRepositoryRevokesOutstandingLease() {
        val repo = repository()
        RepositoryCommonsMaintenance(repo).acquire(DiscoveryCancellation()).use { lease ->
            repo.close()
            fails(CommonsError.DISABLED) { lease.check() }
        }
    }
    @Test fun successfulMetadataPromotesExactCandidateWithoutCancellingItself() {
        repository().use { repo ->
            repo.install(ProviderPackageFixtures.signed())
            val before = repo.snapshot()
            val op = operation(ProviderPackageFixtures.NOW + 15_000)
            val provider = CommonsMetadataProvider(config(), TestTransport(Reply(), Reply(), Reply()), clock = EpochClock { ProviderPackageFixtures.NOW },
                timer = TestTimer(), maintenance = RepositoryCommonsMaintenance(repo))
            assertTrue(provider.search(request, op).results.isNotEmpty())
            assertEquals(1, repo.snapshot().health.consecutiveSuccesses)
            assertTrue(provider.search(request, op).fromCache)
            assertEquals(1, repo.snapshot().health.consecutiveSuccesses)
            repeat(2) { provider.clearCache(); assertTrue(provider.search(request, op).results.isNotEmpty()) }
            val after = repo.snapshot()
            assertEquals(ProviderRepositoryState.ACTIVE, after.state)
            assertNull(after.pendingVersion)
            assertEquals(before.generation, after.generation)
            assertFalse(op.cancellation.isCancelled)
        }
    }
    @Test fun failedMetadataLeavesCandidatePendingForExplicitDecision() {
        repository().use { repo ->
            repo.install(ProviderPackageFixtures.signed())
            val provider = CommonsMetadataProvider(config(), TestTransport(Reply(status = 500)), clock = EpochClock { ProviderPackageFixtures.NOW },
                timer = TestTimer(), maintenance = RepositoryCommonsMaintenance(repo))
            fails(CommonsError.SERVICE_ERROR) { provider.search(request, operation(ProviderPackageFixtures.NOW + 15_000)) }
            assertEquals(1L, repo.snapshot().pendingVersion)
            assertEquals(ProviderRepositoryState.CANDIDATE, repo.snapshot().state)
        }
    }
    @Test fun expiredActiveLeaseIsFencedWithoutNotificationThreadTiming() {
        var now = ProviderPackageFixtures.NOW
        ProviderRepository(directory.newFolder(), ProviderPackageFixtures.verifier(), EpochClock { now }).use { repo ->
            repo.install(ProviderPackageFixtures.signed(expires = now + 1))
            RepositoryCommonsMaintenance(repo).acquire(DiscoveryCancellation()).use { lease ->
                now++
                fails(CommonsError.DISABLED) { lease.check() }
            }
            assertFalse(repo.snapshot().policy.enabled)
        }
    }
    @Test fun healthCheckCancellationCannotPromoteCandidate() {
        repository().use { repo ->
            repo.install(ProviderPackageFixtures.signed())
            val cancellation = DiscoveryCancellation()
            RepositoryCommonsMaintenance(repo).acquire(cancellation).use { lease ->
                cancellation.cancel()
                fails(CommonsError.CANCELLED) { lease.succeeded {} }
            }
            assertEquals(1L, repo.snapshot().pendingVersion)
        }
    }
    @Test fun threeRealSemanticFailuresQuarantineAndLeaveNoUnsignedFallback() {
        repository().use { repo ->
            repo.install(ProviderPackageFixtures.signed())
            val bad = """{"query":{"pages":"bad"}}""".toByteArray()
            val provider = CommonsMetadataProvider(config(), TestTransport(Reply(bad), Reply(bad), Reply(bad)),
                clock = EpochClock { ProviderPackageFixtures.NOW }, timer = TestTimer(), maintenance = RepositoryCommonsMaintenance(repo))
            repeat(3) { fails(CommonsError.INVALID_RESPONSE) { provider.search(request, operation(ProviderPackageFixtures.NOW + 15_000)) } }
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(1L, repo.snapshot().quarantinedVersions.single().version)
            assertEquals(ProviderQuarantineReason.STRUCTURE_FAILURES, repo.snapshot().quarantinedVersions.single().reason)
            assertEquals(1L, repo.snapshot().highestAcceptedVersion)
            fails(CommonsError.DISABLED) { provider.search(request, operation(ProviderPackageFixtures.NOW + 15_000)) }
        }
    }
    @Test fun networkFluctuationDoesNotQuarantineAndSuccessStreakRestarts() {
        repository().use { repo ->
            repo.install(ProviderPackageFixtures.signed())
            val replies = arrayOf(Reply(), Reply(status = 500), Reply(), Reply(), Reply())
            val provider = CommonsMetadataProvider(config(), TestTransport(*replies), clock = EpochClock { ProviderPackageFixtures.NOW },
                timer = TestTimer(), maintenance = RepositoryCommonsMaintenance(repo))
            val deadline = ProviderPackageFixtures.NOW + 15_000
            provider.search(request, operation(deadline)); provider.clearCache()
            fails(CommonsError.SERVICE_ERROR) { provider.search(request, operation(deadline)) }
            assertEquals(0, repo.snapshot().health.consecutiveSuccesses)
            assertEquals(0, repo.snapshot().health.consecutiveStructureFailures)
            assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
            repeat(3) { provider.clearCache(); provider.search(request, operation(deadline)) }
            assertEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
            assertTrue(repo.snapshot().quarantinedVersions.isEmpty())
        }
    }
    @Test fun oldLeaseCannotProvideHealthForNewPolicyOrCandidate() {
        ProviderRepository(directory.newFolder(), ProviderPackageFixtures.verifier(), EpochClock { ProviderPackageFixtures.NOW },
            policyVerifier = RepositoryPolicyFixtures.verifier()).use { repo ->
            repo.install(ProviderPackageFixtures.signed())
            RepositoryCommonsMaintenance(repo).acquire(DiscoveryCancellation()).use { lease ->
                repo.installPolicy(RepositoryPolicyFixtures.signed())
                fails(CommonsError.DISABLED) { lease.succeeded {} }
                assertEquals(0, repo.snapshot().health.totalObservations)
            }
            repeat(3) {
                RepositoryCommonsMaintenance(repo).acquire(DiscoveryCancellation()).use { it.succeeded {} }
            }
            assertEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
        }
    }
}

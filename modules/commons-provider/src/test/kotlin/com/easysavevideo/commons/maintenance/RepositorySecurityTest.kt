package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.DiscoveryCancellation
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RepositorySecurityTest {
    private class Clock(@Volatile var now: Long = ProviderPackageFixtures.NOW) : EpochClock {
        override fun nowMillis() = now
    }
    private fun repo(dir: File, clock: Clock = Clock(), fault: (ProviderWriteStage) -> Unit = {}) =
        ProviderRepository(dir, ProviderPackageFixtures.verifier(), clock,
            ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits()), RepositoryPolicyFixtures.verifier(), fault)
    private fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("esv-repository-policy-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun ok(result: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue(result.toString(), result is ProviderRepositoryResult.Success)
        return (result as ProviderRepositoryResult.Success).snapshot
    }
    private fun fail(code: ProviderRepositoryError, result: ProviderRepositoryResult) =
        assertEquals(code, (result as ProviderRepositoryResult.Failure).code)
    private fun stable(repo: ProviderRepository, version: Long, bytes: ByteArray = ProviderPackageFixtures.signed(version)): ByteArray {
        ok(repo.install(bytes)); ok(repo.confirmHealthy(version)); return bytes
    }
    private fun grant(version: Long, bytes: ByteArray, policyVersion: Long = 1) = RepositoryPolicyFixtures.signed(
        version = policyVersion, rollbackTargetVersion = version, rollbackTargetDigest = RepositoryPolicyFixtures.digest(bytes))

    @Test fun productionPolicyRootIsEmptyEvenWhenPackageRootIsInjected() = temp { dir ->
        ProviderRepository(dir, ProviderPackageFixtures.verifier(), Clock()).use { r ->
            fail(ProviderRepositoryError.INVALID_REPOSITORY_POLICY, r.installPolicy(RepositoryPolicyFixtures.signed()))
            assertEquals(0L, r.snapshot().highestAcceptedPolicyVersion)
            assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun firstPolicyPersistsIndependentlyAndNeverMakesBundledManagedFallback() = temp { dir ->
        repo(dir).use { r ->
            val result = ok(r.installPolicy(RepositoryPolicyFixtures.signed(version = 8)))
            assertEquals(8L, result.highestAcceptedPolicyVersion)
            assertEquals(0L, result.highestAcceptedVersion)
            assertEquals(ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, result.error)
            assertFalse(result.policy.enabled)
        }
        repo(dir).use { r ->
            assertEquals(8L, r.snapshot().highestAcceptedPolicyVersion)
            fail(ProviderRepositoryError.REPLAYED_POLICY, r.installPolicy(RepositoryPolicyFixtures.signed(8)))
            ok(r.install(ProviderPackageFixtures.signed(1)))
            assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun policyVersionCannotReplayOrEquivocateAndPackageVersionIsIndependent() = temp { dir ->
        repo(dir).use { r ->
            stable(r, 100)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(1)))
            for (version in listOf(1L)) {
                fail(ProviderRepositoryError.REPLAYED_POLICY, r.installPolicy(RepositoryPolicyFixtures.signed(version, enabled = false)))
            }
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(2)))
            fail(ProviderRepositoryError.REPLAYED_VERSION, r.install(ProviderPackageFixtures.signed(2)))
            assertEquals(100L, r.snapshot().highestAcceptedVersion)
            assertEquals(2L, r.snapshot().highestAcceptedPolicyVersion)
        }
        repo(dir).use { r -> fail(ProviderRepositoryError.REPLAYED_POLICY, r.installPolicy(RepositoryPolicyFixtures.signed(1))) }
    }

    @Test fun floorNeverDecreasesAndRejectsNewUnsafePackagesWithoutAdvancingPackageHighWater() = temp { dir ->
        repo(dir).use { r ->
            stable(r, 1)
            val result = ok(r.installPolicy(RepositoryPolicyFixtures.signed(minimumSafePackageVersion = 10)))
            assertEquals(ProviderRepositoryError.UNSAFE_VERSION, result.error)
            fail(ProviderRepositoryError.UNSAFE_VERSION, r.install(ProviderPackageFixtures.signed(9)))
            fail(ProviderRepositoryError.SECURITY_FLOOR_REGRESSION,
                r.installPolicy(RepositoryPolicyFixtures.signed(2, minimumSafePackageVersion = 9)))
            assertEquals(1L, r.snapshot().highestAcceptedVersion)
            stable(r, 10)
            assertTrue(r.snapshot().policy.enabled)
        }
        repo(dir).use { r ->
            assertEquals(10L, r.snapshot().minimumSafePackageVersion)
            fail(ProviderRepositoryError.SECURITY_FLOOR_REGRESSION, r.installPolicy(RepositoryPolicyFixtures.signed(2)))
        }
    }

    @Test fun revokedVersionPersistsAndCannotDisappearFromHigherPolicy() = temp { dir ->
        repo(dir).use { r ->
            stable(r, 1)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(revokedPackageVersions = listOf(1, 3))))
            assertEquals(ProviderRepositoryError.REVOKED_VERSION, r.snapshot().error)
            fail(ProviderRepositoryError.REVOCATION_REGRESSION,
                r.installPolicy(RepositoryPolicyFixtures.signed(2, revokedPackageVersions = listOf(3))))
            fail(ProviderRepositoryError.REVOKED_VERSION, r.install(ProviderPackageFixtures.signed(3)))
            stable(r, 2)
            assertTrue(r.snapshot().policy.enabled)
        }
        repo(dir).use { r ->
            assertTrue(r.snapshot().policy.enabled)
            fail(ProviderRepositoryError.REVOCATION_REGRESSION, r.installPolicy(RepositoryPolicyFixtures.signed(2)))
        }
    }

    @Test fun revokedPackageKeyCannotReenterThroughNewPackageOrRollbackGrant() = temp { dir ->
        repo(dir).use { r ->
            val first = stable(r, 1); stable(r, 2)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(revokedPackageKeyIds = listOf(ProviderPackageFixtures.KEY_ID),
                rollbackTargetVersion = 1, rollbackTargetDigest = RepositoryPolicyFixtures.digest(first))))
            assertEquals(ProviderRepositoryError.REVOKED_KEY, r.snapshot().error)
            fail(ProviderRepositoryError.REVOKED_KEY, r.rollback())
            fail(ProviderRepositoryError.REVOKED_KEY, r.install(ProviderPackageFixtures.signed(3)))
            fail(ProviderRepositoryError.REVOCATION_REGRESSION, r.installPolicy(RepositoryPolicyFixtures.signed(2)))
        }
        repo(dir).use { r -> assertEquals(ProviderRepositoryError.REVOKED_KEY, r.snapshot().error) }
    }

    @Test fun repositoryDisableDominatesHealthyCandidateRollbackAndLkg() = temp { dir ->
        repo(dir).use { r ->
            val first = stable(r, 1)
            ok(r.install(ProviderPackageFixtures.signed(2)))
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(enabled = false, rollbackTargetVersion = 1,
                rollbackTargetDigest = RepositoryPolicyFixtures.digest(first))))
            assertEquals(ProviderRepositoryError.POLICY_DISABLED, r.snapshot().error)
            fail(ProviderRepositoryError.POLICY_DISABLED, r.confirmHealthy(2))
            val rejected = ok(r.rejectCandidate(2))
            assertEquals(ProviderRepositoryError.POLICY_DISABLED, rejected.error)
            assertFalse(rejected.policy.enabled)
            fail(ProviderRepositoryError.POLICY_DISABLED, r.install(ProviderPackageFixtures.signed(3)))
        }
        repo(dir).use { r ->
            assertFalse(r.snapshot().policy.enabled)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(2)))
            assertEquals(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, r.snapshot().error)
            stable(r, 3)
            assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun stablePackageDisableCannotRollbackToOldEnabledOrBundledWithOrWithoutGrant() = temp { dir ->
        repo(dir).use { r ->
            val first = stable(r, 1)
            ok(r.install(ProviderPackageFixtures.signed(2, enabled = false)))
            fail(ProviderRepositoryError.DISABLE_BARRIER, r.rollback())
            ok(r.installPolicy(grant(1, first)))
            fail(ProviderRepositoryError.DISABLE_BARRIER, r.rollback())
            assertFalse(r.snapshot().canRollback)
            assertFalse(r.snapshot().policy.enabled)
        }
        repo(dir).use { r ->
            fail(ProviderRepositoryError.DISABLE_BARRIER, r.rollback())
            stable(r, 3)
            assertTrue(r.snapshot().policy.enabled)
            fail(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, r.rollback())
            ok(r.installPolicy(grant(2, ProviderPackageFixtures.signed(2, enabled = false), 2)))
            ok(r.rollback()) // Explicit disabled-target rollback also has an exact signed grant.
            assertFalse(r.snapshot().policy.enabled)
        }
    }

    @Test fun firstPackageKillSwitchCannotRollbackToBundled() = temp { dir ->
        repo(dir).use { r ->
            ok(r.install(ProviderPackageFixtures.signed(enabled = false)))
            fail(ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, r.rollback())
            assertFalse(r.snapshot().canRollback)
        }
    }

    @Test fun stableRollbackRequiresExactSignedTargetAndRetainsBothVersionAnchors() = temp { dir ->
        repo(dir).use { r ->
            val first = stable(r, 10); stable(r, 20)
            fail(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, r.rollback())
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(rollbackTargetVersion = 10)))
            fail(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, r.rollback())
            ok(r.installPolicy(grant(10, first, 2)))
            assertTrue(r.snapshot().canRollback)
            val result = ok(r.rollback())
            assertEquals(10L, result.policy.packageVersion)
            assertEquals(20L, result.highestAcceptedVersion)
            assertEquals(2L, result.highestAcceptedPolicyVersion)
            fail(ProviderRepositoryError.NO_ROLLBACK, r.rollback())
        }
        repo(dir).use { r ->
            assertTrue(r.snapshot().policy.enabled)
            assertEquals(20L, r.snapshot().highestAcceptedVersion)
            fail(ProviderRepositoryError.REPLAYED_VERSION, r.install(ProviderPackageFixtures.signed(20)))
        }
    }

    @Test fun grantForSameVersionDifferentEnvelopeCannotAuthorizeRollback() = temp { dir ->
        repo(dir).use { r ->
            stable(r, 1); stable(r, 2)
            val other = ProviderPackageFixtures.signed(1, limits = CommonsLimits(pageSize = 1))
            ok(r.installPolicy(grant(1, other)))
            fail(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, r.rollback())
        }
    }

    @Test fun newerPolicyMayWithdrawRollbackGrantButNeverItsSecurityRestrictions() = temp { dir ->
        repo(dir).use { r ->
            val first = stable(r, 1); stable(r, 2)
            ok(r.installPolicy(grant(1, first))); ok(r.rollback())
            assertTrue(r.snapshot().policy.enabled)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(2)))
            assertEquals(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, r.snapshot().error)
        }
        repo(dir).use { r -> assertFalse(r.snapshot().policy.enabled) }
    }

    @Test fun rejectedCandidateDoesNotSilentlyActivateUnsignedBundledFallback() = temp { dir ->
        repo(dir).use { r ->
            ok(r.install(ProviderPackageFixtures.signed()))
            val rejected = ok(r.rejectCandidate(1))
            assertEquals(ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, rejected.error)
            assertFalse(rejected.policy.enabled)
            assertEquals(1L, rejected.highestAcceptedVersion)
        }
        repo(dir).use { r -> assertEquals(ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, r.snapshot().error) }
    }

    @Test fun rejectedCandidateLkgNeedsGrantAndRevocationOverridesGrant() = temp { dir ->
        repo(dir).use { r ->
            val first = stable(r, 1)
            ok(r.install(ProviderPackageFixtures.signed(2)))
            assertEquals(ProviderRepositoryError.ROLLBACK_NOT_AUTHORIZED, ok(r.rejectCandidate(2)).error)
            ok(r.installPolicy(grant(1, first)))
            assertTrue(r.snapshot().policy.enabled)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(2, revokedPackageVersions = listOf(1),
                rollbackTargetVersion = 1, rollbackTargetDigest = RepositoryPolicyFixtures.digest(first))))
            assertEquals(ProviderRepositoryError.REVOKED_VERSION, r.snapshot().error)
        }
    }

    @Test fun policyExpiryFailsClosedAndNewerValidPolicyCanRecoverWithoutReset() = temp { dir ->
        val clock = Clock()
        repo(dir, clock).use { r ->
            stable(r, 1)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(expires = clock.now + 100)))
            clock.now += 100
            assertEquals(ProviderRepositoryError.POLICY_EXPIRED, r.snapshot().error)
            fail(ProviderRepositoryError.POLICY_EXPIRED, r.install(ProviderPackageFixtures.signed(2)))
        }
        repo(dir, clock).use { r ->
            assertEquals(1L, r.snapshot().highestAcceptedPolicyVersion)
            assertEquals(ProviderRepositoryError.POLICY_EXPIRED, r.snapshot().error)
            ok(r.installPolicy(RepositoryPolicyFixtures.signed(2)))
            assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun floorAndRevocationAlwaysOverrideValidTargetGrant() {
        listOf(ProviderRepositoryError.UNSAFE_VERSION, ProviderRepositoryError.REVOKED_VERSION).forEach { expected -> temp { dir ->
            repo(dir).use { r ->
                val first = stable(r, 1); stable(r, 2)
                ok(r.installPolicy(RepositoryPolicyFixtures.signed(minimumSafePackageVersion = if (expected == ProviderRepositoryError.UNSAFE_VERSION) 2 else 0,
                    revokedPackageVersions = if (expected == ProviderRepositoryError.REVOKED_VERSION) listOf(1) else emptyList(),
                    rollbackTargetVersion = 1, rollbackTargetDigest = RepositoryPolicyFixtures.digest(first))))
                fail(expected, r.rollback())
                assertTrue(r.snapshot().policy.enabled)
            }
        } }
    }

    @Test fun expiredTargetRemainsDisabledEvenWithValidGrant() = temp { dir ->
        val clock = Clock()
        repo(dir, clock).use { r ->
            val first = stable(r, 1, ProviderPackageFixtures.signed(1, expires = clock.now + 10)); stable(r, 2)
            ok(r.installPolicy(grant(1, first)))
            clock.now += 10
            fail(ProviderRepositoryError.EXPIRED, r.rollback())
        }
    }

    @Test fun policyUpdateAfterRestartCannotMakeRecoveredPendingCandidateUsable() = temp { dir ->
        repo(dir).use { r -> ok(r.install(ProviderPackageFixtures.signed())) }
        repo(dir).use { r ->
            ok(r.installPolicy(RepositoryPolicyFixtures.signed()))
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, r.snapshot().error)
            fail(ProviderRepositoryError.RECOVERY_REQUIRED, r.confirmHealthy(1))
            assertEquals(ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, ok(r.rejectCandidate(1)).error)
        }
    }

    @Test fun policyChangeRevokesExistingLeaseAndFencesHealthPromotion() = temp { dir ->
        repo(dir).use { r ->
            ok(r.install(ProviderPackageFixtures.signed()))
            val cancellation = DiscoveryCancellation()
            RepositoryCommonsMaintenance(r).acquire(cancellation).use { lease ->
                ok(r.installPolicy(RepositoryPolicyFixtures.signed(enabled = false)))
                try { lease.check(); fail("old lease") } catch (_: com.easysavevideo.commons.CommonsFailure) { }
                assertTrue(cancellation.isCancelled)
                fail(ProviderRepositoryError.POLICY_DISABLED, r.confirmHealthy(1))
            }
        }
    }

    @Test fun eachPolicyWriteFaultFailsClosedBeforeAndAfterRestart() {
        ProviderWriteStage.entries.forEach { stage -> temp { dir ->
            var armed = false
            repo(dir, fault = { if (armed && it == stage) throw IOException("private detail") }).use { r ->
                stable(r, 1)
                armed = true
                fail(ProviderRepositoryError.STORAGE_FAILURE, r.installPolicy(RepositoryPolicyFixtures.signed(enabled = false)))
                assertFalse(r.snapshot().policy.enabled)
            }
            repo(dir).use { r -> assertEquals(ProviderRepositoryError.CORRUPT_STATE, r.snapshot().error) }
        } }
    }

    @Test fun eachPolicyWriteCancellationFailsClosedBeforeAndAfterRestart() {
        ProviderWriteStage.entries.forEach { stage -> temp { dir ->
            var armed = false; var cancelled = false
            repo(dir, fault = { if (armed && it == stage) cancelled = true }).use { r ->
                stable(r, 1); armed = true
                fail(ProviderRepositoryError.CANCELLED, r.installPolicy(RepositoryPolicyFixtures.signed()) {
                    if (cancelled) throw InterruptedException()
                })
                assertFalse(r.snapshot().policy.enabled)
            }
            repo(dir).use { r -> assertEquals(ProviderRepositoryError.CORRUPT_STATE, r.snapshot().error) }
        } }
    }

    @Test fun policyCancellationBeforeTransactionLeavesOldStablePolicyUnchanged() = temp { dir ->
        repo(dir).use { r ->
            stable(r, 1); val before = r.snapshot()
            fail(ProviderRepositoryError.CANCELLED, r.installPolicy(RepositoryPolicyFixtures.signed()) { throw InterruptedException() })
            assertEquals(before, r.snapshot())
        }
    }

    @Test fun persistedClockLowerBoundSurvivesRestartAndDoesNotPermitMutationBehindIt() = temp { dir ->
        val clock = Clock()
        repo(dir, clock).use { r -> stable(r, 1); ok(r.installPolicy(RepositoryPolicyFixtures.signed())) }
        clock.now -= 1
        repo(dir, clock).use { r ->
            assertEquals(ProviderRepositoryError.CLOCK_ROLLBACK, r.snapshot().error)
            fail(ProviderRepositoryError.CLOCK_ROLLBACK, r.installPolicy(RepositoryPolicyFixtures.signed(2)))
            clock.now += 1
            assertTrue(r.snapshot().policy.enabled)
        }
    }

    @Test fun inProcessObservationIsMonotonicAndNegativeClockFailsClosed() = temp { dir ->
        val clock = Clock()
        repo(dir, clock).use { r ->
            stable(r, 1)
            clock.now += 100
            assertTrue(r.snapshot().policy.enabled)
            clock.now -= 1
            assertEquals(ProviderRepositoryError.CLOCK_ROLLBACK, r.snapshot().error)
            clock.now = -1
            assertEquals(ProviderRepositoryError.INVALID_TIME, r.snapshot().error)
        }
    }

    @Test fun corruptPolicySignatureCannotBeHiddenByRecomputedStateChecksum() = temp { dir ->
        repo(dir).use { r -> stable(r, 1); ok(r.installPolicy(RepositoryPolicyFixtures.signed())) }
        val file = File(dir, "provider-state.bin")
        val bytes = file.readBytes(); val payload = bytes.copyOf(bytes.size - 32)
        // v2 header: magic+format+generation+2flags+observedAt+policyLength, then policy.
        val length = ByteBuffer.wrap(payload).getInt(26)
        payload[30 + length - 1] = (payload[30 + length - 1].toInt() xor 1).toByte()
        file.writeBytes(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        repo(dir).use { r -> assertEquals(ProviderRepositoryError.CORRUPT_STATE, r.snapshot().error) }
    }

    @Test fun legacyStateFormatFailsClosedWithoutMigrationOrReset() = temp { dir ->
        repo(dir).use { r -> stable(r, 1) }
        val file = File(dir, "provider-state.bin")
        val bytes = file.readBytes(); val payload = bytes.copyOf(bytes.size - 32)
        ByteBuffer.wrap(payload).putInt(4, 1)
        file.writeBytes(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        repo(dir).use { r ->
            assertEquals(ProviderRepositoryError.CORRUPT_STATE, r.snapshot().error)
            fail(ProviderRepositoryError.CORRUPT_STATE, r.installPolicy(RepositoryPolicyFixtures.signed()))
        }
        assertTrue(file.exists())
    }

    @Test fun concurrentPolicyAndPackageMutationsSerializeAndLatestRevocationWins() = temp { dir ->
        repo(dir).use { r ->
            stable(r, 1)
            val start = CountDownLatch(1); val done = CountDownLatch(2)
            val update = AtomicReference<ProviderRepositoryResult>(); val policy = AtomicReference<ProviderRepositoryResult>()
            Thread { start.await(); update.set(r.install(ProviderPackageFixtures.signed(2))); done.countDown() }.start()
            Thread { start.await(); policy.set(r.installPolicy(RepositoryPolicyFixtures.signed(revokedPackageVersions = listOf(2)))); done.countDown() }.start()
            start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(policy.get() is ProviderRepositoryResult.Success)
            if (update.get() is ProviderRepositoryResult.Success) {
                assertEquals(ProviderRepositoryError.REVOKED_VERSION, r.snapshot().error)
                fail(ProviderRepositoryError.REVOKED_VERSION, r.confirmHealthy(2))
            } else {
                fail(ProviderRepositoryError.REVOKED_VERSION, update.get())
                assertEquals(1L, r.snapshot().highestAcceptedVersion)
            }
        }
    }

    @Test fun boundedUpdateRuntimeCanInstallExplicitPolicyKindFromMemoryOnly() = temp { dir ->
        repo(dir).use { r ->
            stable(r, 1)
            val source = ProviderUpdateSource { object : ProviderUpdateCall {
                override fun execute() = ByteArrayInputStream(RepositoryPolicyFixtures.signed(enabled = false))
                override fun cancel() {}
            } }
            ProviderUpdateRuntime(r, source, Clock(), kind = ProviderUpdateKind.REPOSITORY_POLICY).use { runtime ->
                val done = CountDownLatch(1); val result = AtomicReference<ProviderUpdateOutcome>()
                runtime.execute(ProviderPackageFixtures.NOW + 10_000) { result.set(it); done.countDown() }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertTrue((result.get() as ProviderUpdateOutcome.Completed).result is ProviderRepositoryResult.Success)
                assertEquals(ProviderRepositoryError.POLICY_DISABLED, r.snapshot().error)
            }
        }
    }

    @Test fun updateRuntimeNeverSniffsPackageAsPolicyOrPolicyAsPackage() {
        ProviderUpdateKind.entries.forEach { kind -> temp { dir ->
            repo(dir).use { r ->
                val source = ProviderUpdateSource { object : ProviderUpdateCall {
                    override fun execute() = ByteArrayInputStream(if (kind == ProviderUpdateKind.PACKAGE)
                        RepositoryPolicyFixtures.signed() else ProviderPackageFixtures.signed())
                    override fun cancel() {}
                } }
                ProviderUpdateRuntime(r, source, Clock(), kind = kind).use { runtime ->
                    val done = CountDownLatch(1); val result = AtomicReference<ProviderUpdateOutcome>()
                    runtime.execute(ProviderPackageFixtures.NOW + 10_000) { result.set(it); done.countDown() }
                    assertTrue(done.await(5, TimeUnit.SECONDS))
                    fail(if (kind == ProviderUpdateKind.PACKAGE) ProviderRepositoryError.INVALID_PACKAGE else ProviderRepositoryError.INVALID_REPOSITORY_POLICY,
                        (result.get() as ProviderUpdateOutcome.Completed).result)
                }
            }
        } }
    }
}

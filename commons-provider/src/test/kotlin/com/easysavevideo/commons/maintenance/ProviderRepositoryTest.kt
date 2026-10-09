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

class ProviderRepositoryTest {
    private class Clock(var now: Long = ProviderPackageFixtures.NOW) : EpochClock {
        override fun nowMillis() = now
    }
    private fun repository(dir: File, clock: Clock = Clock()) = ProviderRepository(dir, ProviderPackageFixtures.verifier(), clock, policyVerifier = RepositoryPolicyFixtures.verifier())
    private fun success(result: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue(result.toString(), result is ProviderRepositoryResult.Success)
        return (result as ProviderRepositoryResult.Success).snapshot
    }
    private fun failure(expected: ProviderRepositoryError, result: ProviderRepositoryResult) =
        assertEquals(expected, (result as ProviderRepositoryResult.Failure).code)
    private fun temp(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("esv-provider-repository-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun defaultTrustCannotInstallDownloadedPolicyAndFreshStoreAllowsBundledOnly() = temp { dir ->
        ProviderRepository(dir).use { repo ->
            assertEquals(ProviderRepositoryState.BUNDLED, repo.snapshot().state)
            assertEquals(0L, repo.snapshot().highestAcceptedVersion)
            assertTrue(repo.snapshot().policy.enabled)
            failure(ProviderRepositoryError.INVALID_PACKAGE, repo.install(ProviderPackageFixtures.signed()))
            assertEquals(ProviderRepositoryState.BUNDLED, repo.snapshot().state)
        }
    }

    @Test fun onlyOneCandidateAndExactHealthPromotionPreservesAuthorityGeneration() = temp { dir ->
        repository(dir).use { repo ->
            val candidate = success(repo.install(ProviderPackageFixtures.signed()))
            assertEquals(1L, candidate.pendingVersion)
            assertEquals(1L, candidate.highestAcceptedVersion)
            failure(ProviderRepositoryError.CANDIDATE_PENDING, repo.install(ProviderPackageFixtures.signed(2)))
            failure(ProviderRepositoryError.STALE_CANDIDATE, repo.confirmHealthy(2))
            assertEquals(candidate, repo.snapshot())
            val healthy = success(repo.confirmHealthy(1))
            assertEquals(candidate.generation, healthy.generation)
            assertEquals(candidate.policy, healthy.policy)
            assertNull(healthy.pendingVersion)
            assertFalse(healthy.canRollback)
            failure(ProviderRepositoryError.NO_CANDIDATE, repo.confirmHealthy(1))
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(ProviderPackageFixtures.signed(1)))
        }
    }

    @Test fun restoredCandidateCannotEnableOrBePromotedAndRequiresExplicitReject() = temp { dir ->
        repository(dir).use { repo -> success(repo.install(ProviderPackageFixtures.signed(7))) }
        repository(dir).use { repo ->
            assertEquals(ProviderRepositoryState.DISABLED, repo.snapshot().state)
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, repo.snapshot().error)
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(7L, repo.snapshot().pendingVersion)
            assertEquals(7L, repo.snapshot().highestAcceptedVersion)
            failure(ProviderRepositoryError.RECOVERY_REQUIRED, repo.confirmHealthy(7))
            failure(ProviderRepositoryError.CANDIDATE_PENDING, repo.install(ProviderPackageFixtures.signed(8)))
            failure(ProviderRepositoryError.STALE_CANDIDATE, repo.rejectCandidate(6))
            val old = success(repo.rejectCandidate(7))
            assertEquals(ProviderRepositoryState.DISABLED, old.state)
            assertEquals(ProviderRepositoryError.SIGNED_FALLBACK_REQUIRED, old.error)
            assertEquals(7L, old.highestAcceptedVersion)
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(ProviderPackageFixtures.signed(7)))
            success(repo.install(ProviderPackageFixtures.signed(8)))
        }
    }

    @Test fun stableRollbackAndRestartNeverLowerHighWater() = temp { dir ->
        repository(dir).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed(10))); success(repo.confirmHealthy(10))
            success(repo.install(ProviderPackageFixtures.signed(20))); success(repo.confirmHealthy(20))
            authorize(repo, 10)
            val generation = repo.snapshot().generation
            val rolled = success(repo.rollback())
            assertEquals(10L, rolled.policy.packageVersion)
            assertEquals(20L, rolled.highestAcceptedVersion)
            assertTrue(rolled.generation > generation)
            failure(ProviderRepositoryError.NO_ROLLBACK, repo.rollback())
        }
        repository(dir).use { repo ->
            assertEquals(10L, repo.snapshot().policy.packageVersion)
            assertEquals(20L, repo.snapshot().highestAcceptedVersion)
            for (version in listOf(1L, 10L, 15L, 20L))
                failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(ProviderPackageFixtures.signed(version)))
            success(repo.install(ProviderPackageFixtures.signed(21)))
        }
    }

    @Test fun candidateFailurePreservesPreviousStableHistoryAndTrustedAnchor() = temp { dir ->
        repository(dir).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed(1))); success(repo.confirmHealthy(1))
            success(repo.install(ProviderPackageFixtures.signed(2))); success(repo.confirmHealthy(2))
            success(repo.install(ProviderPackageFixtures.signed(3, limits = CommonsLimits(pageSize = 2))))
            authorize(repo, 2)
            val fallback = success(repo.rejectCandidate(3))
            assertEquals(2L, fallback.policy.packageVersion)
            assertEquals(3L, fallback.highestAcceptedVersion)
            assertFalse(fallback.canRollback)
            authorize(repo, 1, policyVersion = 2)
            val earlier = success(repo.rollback())
            assertEquals(1L, earlier.policy.packageVersion)
            assertEquals(3L, earlier.highestAcceptedVersion)
        }
        repository(dir).use { repo ->
            assertEquals(1L, repo.snapshot().policy.packageVersion)
            assertEquals(3L, repo.snapshot().highestAcceptedVersion)
        }
    }

    @Test fun failedCandidateNeverReenablesExpiredLastGood() = temp { dir ->
        val clock = Clock()
        repository(dir, clock).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed(1, expires = clock.now + 100)))
            success(repo.confirmHealthy(1))
            success(repo.install(ProviderPackageFixtures.signed(2)))
            clock.now += 101
            success(repo.installPolicy(RepositoryPolicyFixtures.signed(rollbackTargetVersion = 1,
                rollbackTargetDigest = RepositoryPolicyFixtures.digest(ProviderPackageFixtures.signed(1, expires = clock.now - 1)))))
            val fallback = success(repo.rejectCandidate(2))
            assertEquals(1L, fallback.policy.packageVersion)
            assertFalse(fallback.policy.enabled)
            assertEquals(ProviderRepositoryError.EXPIRED, fallback.error)
            assertEquals(2L, fallback.highestAcceptedVersion)
        }
        repository(dir, clock).use { repo ->
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(ProviderRepositoryError.EXPIRED, repo.snapshot().error)
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(ProviderPackageFixtures.signed(2)))
        }
    }

    @Test fun stableManualRollbackRevalidatesExpiryWithoutReplacingCurrentHealthyPolicy() = temp { dir ->
        val clock = Clock()
        repository(dir, clock).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed(1, expires = clock.now + 100)))
            success(repo.confirmHealthy(1))
            success(repo.install(ProviderPackageFixtures.signed(2))); success(repo.confirmHealthy(2))
            success(repo.installPolicy(RepositoryPolicyFixtures.signed(rollbackTargetVersion = 1,
                rollbackTargetDigest = RepositoryPolicyFixtures.digest(ProviderPackageFixtures.signed(1, expires = clock.now + 100)))))
            clock.now += 101
            assertFalse(repo.snapshot().canRollback)
            failure(ProviderRepositoryError.EXPIRED, repo.rollback())
            assertTrue(repo.snapshot().policy.enabled)
            assertEquals(2L, repo.snapshot().policy.packageVersion)
        }
    }

    @Test fun expiryDisablesSnapshotsAndPromotionWithoutDiscardingAuthenticatedHighWater() = temp { dir ->
        val clock = Clock()
        repository(dir, clock).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed(12, expires = clock.now + 100)))
            val generation = repo.snapshot().generation
            clock.now += 100
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(generation, repo.snapshot().generation)
            failure(ProviderRepositoryError.EXPIRED, repo.confirmHealthy(12))
            assertEquals(12L, repo.snapshot().highestAcceptedVersion)
            success(repo.rejectCandidate(12))
        }
        repository(dir, clock).use { repo ->
            assertEquals(12L, repo.snapshot().highestAcceptedVersion)
            failure(ProviderRepositoryError.REPLAYED_VERSION, repo.install(ProviderPackageFixtures.signed(11)))
            success(repo.install(ProviderPackageFixtures.signed(13)))
        }
    }

    @Test fun restoredExpiredStableEnvelopeStillAuthenticatesButDoesNotActivate() = temp { dir ->
        val clock = Clock()
        repository(dir, clock).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed(5, expires = clock.now + 100)))
            success(repo.confirmHealthy(5))
        }
        clock.now += 100
        repository(dir, clock).use { repo ->
            assertEquals(5L, repo.snapshot().highestAcceptedVersion)
            assertEquals(ProviderRepositoryError.EXPIRED, repo.snapshot().error)
            assertFalse(repo.snapshot().policy.enabled)
            success(repo.install(ProviderPackageFixtures.signed(6)))
        }
    }

    @Test fun corruptedHighWaterCannotBeResetEvenIfFramingChecksumIsRecomputed() = temp { dir ->
        repository(dir).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed(1))); success(repo.confirmHealthy(1))
            success(repo.install(ProviderPackageFixtures.signed(2))); success(repo.confirmHealthy(2)); authorize(repo, 1); success(repo.rollback())
        }
        val file = File(dir, "provider-state.bin")
        val all = file.readBytes()
        val payload = all.copyOf(all.size - 32)
        // Last package is the high-water anchor. Damage only its RSA signature, then fix checksum.
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        file.writeBytes(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        repository(dir).use { repo ->
            assertFalse(repo.snapshot().policy.enabled)
            assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
            failure(ProviderRepositoryError.CORRUPT_STATE, repo.install(ProviderPackageFixtures.signed(3)))
            failure(ProviderRepositoryError.CORRUPT_STATE, repo.rollback())
        }
        assertTrue(file.exists())
    }

    @Test fun missingTruncatedOversizedAndUnknownFormatRecordsFailClosed() {
        val corruptions: List<(File) -> Unit> = listOf(
            { it.delete() },
            { it.writeBytes(byteArrayOf(1)) },
            { it.writeBytes(ByteArray(ProviderRepository.MAX_RECORD_BYTES + 1)) },
            { file ->
                val bytes = file.readBytes(); val payload = bytes.copyOf(bytes.size - 32)
                ByteBuffer.wrap(payload).putInt(4, 4)
                file.writeBytes(payload + MessageDigest.getInstance("SHA-256").digest(payload))
            },
        )
        corruptions.forEach { corrupt -> temp { dir ->
            repository(dir).use { repo -> success(repo.install(ProviderPackageFixtures.signed())); success(repo.confirmHealthy(1)) }
            corrupt(File(dir, "provider-state.bin"))
            repository(dir).use { repo ->
                assertFalse(repo.snapshot().policy.enabled)
                assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
                failure(ProviderRepositoryError.CORRUPT_STATE, repo.install(ProviderPackageFixtures.signed(2)))
            }
        } }
    }

    @Test fun everyInjectedAtomicFailureStaysDisabledAcrossRestart() {
        ProviderWriteStage.entries.forEach { stage -> temp { dir ->
            var armed = false
            ProviderRepository(dir, ProviderPackageFixtures.verifier(), Clock(), bundled()) {
                if (armed && it == stage) throw IOException("private filename must never escape")
            }.use { repo ->
                success(repo.install(ProviderPackageFixtures.signed(1))); success(repo.confirmHealthy(1))
                armed = true
                val failure = repo.install(ProviderPackageFixtures.signed(2)) as ProviderRepositoryResult.Failure
                assertEquals(ProviderRepositoryError.STORAGE_FAILURE, failure.code)
                assertFalse(failure.toString().contains("private"))
                assertFalse(repo.snapshot().policy.enabled)
            }
            repository(dir).use { repo ->
                assertFalse(repo.snapshot().policy.enabled)
                failure(ProviderRepositoryError.CORRUPT_STATE, repo.install(ProviderPackageFixtures.signed(3)))
            }
        } }
    }

    @Test fun cancellationBeforeTransactionLeavesHealthyStateUntouched() = temp { dir ->
        repository(dir).use { repo ->
            success(repo.install(ProviderPackageFixtures.signed())); success(repo.confirmHealthy(1))
            val before = repo.snapshot()
            failure(ProviderRepositoryError.CANCELLED, repo.install(ProviderPackageFixtures.signed(2)) { throw InterruptedException() })
            assertEquals(before, repo.snapshot())
        }
    }

    @Test fun secondOwnerFailsClosedWithoutOpeningBundledFallback() = temp { dir ->
        repository(dir).use { first ->
            repository(dir).use { second ->
                assertEquals(ProviderRepositoryError.BUSY, second.snapshot().error)
                assertFalse(second.snapshot().policy.enabled)
                failure(ProviderRepositoryError.BUSY, second.install(ProviderPackageFixtures.signed()))
            }
            assertTrue(first.snapshot().policy.enabled)
            success(first.install(ProviderPackageFixtures.signed()))
        }
    }

    @Test fun notificationsAreOffLockBoundedAndUnchangedHealthDoesNotNotify() = temp { dir ->
        repository(dir).use { repo ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val count = AtomicInteger()
            val observer = repo.observeChanges { count.incrementAndGet(); entered.countDown(); release.await() }
            try {
                success(repo.install(ProviderPackageFixtures.signed()))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                // A blocked listener does not block commits or the immutable snapshot accessor.
                success(repo.confirmHealthy(1))
                val done = CountDownLatch(1)
                Thread { success(repo.install(ProviderPackageFixtures.signed(2))); done.countDown() }.start()
                assertTrue(done.await(2, TimeUnit.SECONDS))
                assertEquals(2L, repo.snapshot().policy.packageVersion)
                val observers = List(31) { repo.observeChanges {} }
                try { repo.observeChanges {}; fail("listener budget") } catch (_: IllegalStateException) { }
                observers.forEach { it.close() }
            } finally { observer.close(); release.countDown() }
        }
    }


    @Test fun signedKillSwitchIsStableImmediatelyAndDoesNotBlockLaterHigherVersion() = temp { dir ->
        repository(dir).use { repo ->
            val disabled = success(repo.install(ProviderPackageFixtures.signed(1, enabled = false)))
            assertFalse(disabled.policy.enabled)
            assertNull(disabled.pendingVersion)
            assertNull(disabled.error)
        }
        repository(dir).use { repo ->
            assertFalse(repo.snapshot().policy.enabled)
            assertNull(repo.snapshot().error)
            assertNull(repo.snapshot().pendingVersion)
            success(repo.install(ProviderPackageFixtures.signed(2)))
            val fallback = success(repo.rejectCandidate(2))
            assertFalse(fallback.policy.enabled)
            assertEquals(1L, fallback.policy.packageVersion)
            assertEquals(2L, fallback.highestAcceptedVersion)
        }
    }


    @Test fun danglingStateMarkersAndWrongFileTypesNeverBecomeFreshBundledState() {
        listOf("provider-state.bin", "provider-state.initialized").forEach { name ->
            listOf(false, true).forEach { symlink -> temp { dir ->
                repository(dir).use { repo ->
                    success(repo.install(ProviderPackageFixtures.signed())); success(repo.confirmHealthy(1))
                }
                val path = File(dir, name).toPath()
                Files.delete(path)
                if (symlink) Files.createSymbolicLink(path, dir.toPath().resolve("missing-target"))
                else Files.createDirectory(path)
                repository(dir).use { repo ->
                    assertFalse(repo.snapshot().policy.enabled)
                    assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
                    failure(ProviderRepositoryError.CORRUPT_STATE, repo.install(ProviderPackageFixtures.signed(2)))
                }
            } }
        }
    }

    @Test fun danglingIntentOrTemporaryEvidenceIsNotTreatedAsAbsent() {
        listOf("provider-state.intent", "provider-state.next").forEach { name -> temp { dir ->
            Files.createSymbolicLink(File(dir, name).toPath(), dir.toPath().resolve("missing-target"))
            repository(dir).use { repo ->
                assertFalse(repo.snapshot().policy.enabled)
                assertEquals(ProviderRepositoryError.CORRUPT_STATE, repo.snapshot().error)
                failure(ProviderRepositoryError.CORRUPT_STATE, repo.install(ProviderPackageFixtures.signed()))
            }
        } }
    }

    private fun authorize(repo: ProviderRepository, version: Long, policyVersion: Long = 1) {
        success(repo.installPolicy(RepositoryPolicyFixtures.signed(version = policyVersion, rollbackTargetVersion = version,
            rollbackTargetDigest = RepositoryPolicyFixtures.digest(ProviderPackageFixtures.signed(version)))))
    }

    private fun bundled() = ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits())
}

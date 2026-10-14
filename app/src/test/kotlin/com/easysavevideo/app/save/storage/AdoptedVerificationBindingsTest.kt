package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.save.DirectLocalProcessingGate
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Genuine lexical gate; execution and ordinary-native authority are synthetic test grants only. */
class AdoptedVerificationBindingsTest {
    private fun recovery(block: () -> Unit) {
        assertEquals(SaveError.RECOVERY_REQUIRED, assertThrows(SaveFailure::class.java) { block() }.code)
    }

    @Test fun defaultRegistryCannotIssueEvenWithGenuineLiveLocalPermit() {
        AdoptedCanonicalTestFixture().use { f ->
            val inactive = AdoptedVerificationBindings(f.gate)
            f.gate.withBoundPermit(Any(), f.context) { permit ->
                recovery { inactive.withSyntheticBinding(permit, f.artifact, f.output, f.context, f.revision) { fail("UNBOUND") } }
                recovery { inactive.consume(f.artifact, f.output, f.context) }
            }
            assertEquals(0, f.io.count("open"))
        }
    }

    @Test fun exactRegistrationConsumesOnceAndRetiresOnlyWhenCompleted() {
        AdoptedCanonicalTestFixture().use { f ->
            f.bound { binding ->
                assertSame(binding, f.bindings.consume(f.artifact, f.output, f.context))
                recovery { f.bindings.consume(f.artifact, f.output, f.context) }
                assertFalse(binding.isRevoked)
                binding.complete()
                assertTrue(binding.isRevoked)
                binding.complete()
                recovery { f.bindings.consume(f.artifact, f.output, f.context) }
            }
            assertEquals(2, f.authorityChecks)
        }
    }

    @Test fun copiedArtifactOutputAndContextNeverSubstituteForRegisteredReferences() {
        AdoptedCanonicalTestFixture().use { f -> f.bound { binding ->
            recovery { f.bindings.consume(f.artifact.copy(), f.output, f.context) }
            recovery { f.bindings.consume(f.artifact, f.output.copy(), f.context) }
            recovery { f.bindings.consume(f.artifact, f.output, f.context.copy()) }
            assertSame(binding, f.bindings.consume(f.artifact, f.output, f.context))
            binding.complete()
        } }
    }

    @Test fun foreignGateCannotRegisterEvenWithSameContextAndLiveExpectedGate() {
        AdoptedCanonicalTestFixture().use { f ->
            val foreign = DirectLocalProcessingGate(EpochClock { 0 })
            f.gate.withBoundPermit(Any(), f.context) {
                foreign.withBoundPermit(Any(), f.context) { permit ->
                    recovery { f.bindings.withSyntheticBinding(permit, f.artifact, f.output, f.context, f.revision) { fail("FOREIGN") } }
                }
            }
            assertEquals(0, f.authorityChecks)
        }
    }

    @Test fun stalePermitAndChangedCancellationAttemptOrDeadlineCannotRegister() {
        AdoptedCanonicalTestFixture().use { f ->
            lateinit var stale: DirectLocalProcessingGate.LocalPermit
            f.gate.withBoundPermit(Any(), f.context) { stale = it }
            recovery { f.bindings.withSyntheticBinding(stale, f.artifact, f.output, f.context, f.revision) { fail("STALE") } }
            val registry = AdoptedVerificationBindings(f.gate, AdoptedVerificationBindings.SyntheticAuthority { })
            f.gate.withBoundPermit(Any(), f.context) { permit ->
                for (changed in listOf(f.context.copy(cancelToken = CancellationSource()),
                    f.context.copy(operationId = "other"), f.context.copy(deadlineEpochMillis = f.context.deadlineEpochMillis + 1),
                    f.context.copy(retryBudget = 1, attempt = 1))) {
                    recovery { registry.withSyntheticBinding(permit, f.artifact, f.output, changed, f.revision) { fail("CHANGED") } }
                }
            }
        }
    }

    @Test fun explicitlyRegisteredShortenedContextIsAllowedButItsCopyIsNot() {
        AdoptedCanonicalTestFixture().use { f ->
            val registry = AdoptedVerificationBindings(f.gate, AdoptedVerificationBindings.SyntheticAuthority { })
            val shortened = f.context.copy(deadlineEpochMillis = f.context.deadlineEpochMillis - 1)
            f.gate.withBoundPermit(Any(), f.context) { permit ->
                registry.withSyntheticBinding(permit, f.artifact, f.output, shortened, f.revision) { binding ->
                    recovery { registry.consume(f.artifact, f.output, shortened.copy()) }
                    recovery { registry.consume(f.artifact, f.output, f.context) }
                    assertSame(binding, registry.consume(f.artifact, f.output, shortened))
                    binding.complete()
                }
            }
        }
    }

    @Test fun duplicateArtifactRegistrationCannotChooseAnotherWorker() {
        AdoptedCanonicalTestFixture().use { f ->
            f.gate.withBoundPermit(Any(), f.context) { permit ->
                f.bindings.withSyntheticBinding(permit, f.artifact, f.output, f.context, f.revision) { binding ->
                    recovery { f.bindings.withSyntheticBinding(permit, f.artifact, f.output, f.context, f.revision) { fail("DUPLICATE") } }
                    assertSame(binding, f.bindings.consume(f.artifact, f.output, f.context))
                    recovery { f.bindings.withSyntheticBinding(permit, f.artifact, f.output, f.context, f.revision) { fail("CONSUMED_DUPLICATE") } }
                    binding.complete()
                }
            }
        }
    }

    @Test fun unconsumedExitRevokesAndPermitsFreshRegistration() {
        AdoptedCanonicalTestFixture().use { f ->
            lateinit var old: AdoptedVerificationBindings.Binding
            f.bound { old = it }
            assertTrue(old.isRevoked)
            recovery { old.requireLive() }
            f.bound { fresh ->
                old.complete()
                assertSame(fresh, f.bindings.consume(f.artifact, f.output, f.context))
                assertFalse(fresh.isRevoked)
                fresh.complete()
            }
        }
    }

    @Test fun consumedLexicalExitRetainsRegistrationUntilItsOwnCompletion() {
        AdoptedCanonicalTestFixture().use { f ->
            lateinit var old: AdoptedVerificationBindings.Binding
            f.bound { old = f.bindings.consume(f.artifact, f.output, f.context) }
            assertTrue(old.isRevoked)
            recovery { f.bound { fail("OUTSTANDING") } }
            old.complete()
            f.bound { fresh ->
                old.complete()
                assertSame(fresh, f.bindings.consume(f.artifact, f.output, f.context))
                fresh.complete()
            }
        }
    }

    @Test fun explicitRevokeBeforeConsumptionCreatesNoResources() {
        AdoptedCanonicalTestFixture().use { f -> f.bound { binding ->
            binding.revoke(); binding.revoke()
            recovery { f.bindings.consume(f.artifact, f.output, f.context) }
            assertEquals(0, f.io.count("open"))
            assertFalse(f.owner.hasAnyLeases())
        } }
    }

    @Test fun secondAuthorityCheckFailureRemovesUnconsumedRegistration() {
        AdoptedCanonicalTestFixture().use { f ->
            f.authorityCheck = { if (f.authorityChecks == 2) throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
            recovery { f.bound { fail("POST_REGISTRATION_RECHECK") } }
            f.authorityCheck = { }
            f.bound { binding ->
                assertSame(binding, f.bindings.consume(f.artifact, f.output, f.context))
                binding.complete()
            }
        }
    }

    @Test fun twoConcurrentConsumersHaveExactlyOneWinner() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newFixedThreadPool(2)
            try {
                f.bound { binding ->
                    val start = CountDownLatch(1)
                    val futures = (0..1).map { pool.submit<Boolean> {
                        check(start.await(5, TimeUnit.SECONDS))
                        try { f.bindings.consume(f.artifact, f.output, f.context); true }
                        catch (failure: SaveFailure) { assertEquals(SaveError.RECOVERY_REQUIRED, failure.code); false }
                    } }
                    start.countDown()
                    assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
                    binding.complete()
                }
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun bindingAssertionsNeverReenterGateRecoveryProbeOrAcquireAnotherLane() {
        AdoptedCanonicalTestFixture().use { f ->
            var recoveryProbes = 0
            val gate = DirectLocalProcessingGate(EpochClock { 0 }, { recoveryProbes++; false })
            val registry = AdoptedVerificationBindings(gate, AdoptedVerificationBindings.SyntheticAuthority { })
            gate.withBoundPermit(Any(), f.context) { permit ->
                val afterEntry = recoveryProbes
                registry.withSyntheticBinding(permit, f.artifact, f.output, f.context, f.revision) { binding ->
                    registry.consume(f.artifact, f.output, f.context)
                    repeat(3) { binding.requireLive() }
                    binding.complete()
                }
                assertEquals(afterEntry, recoveryProbes)
            }
        }
    }

    @Test fun syntheticAuthorityCallbackNeverHoldsRegistryMonitor() {
        AdoptedCanonicalTestFixture().use { f ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                f.authorityCheck = {
                    pool.submit {
                        recovery { f.bindings.consume(f.artifact, f.output.copy(), f.context) }
                    }.get(5, TimeUnit.SECONDS)
                }
                f.bound { binding ->
                    assertSame(binding, f.bindings.consume(f.artifact, f.output, f.context))
                    binding.complete()
                }
            } finally { pool.shutdownNow() }
        }
    }
}

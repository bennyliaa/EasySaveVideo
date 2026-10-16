package com.easysavevideo.commons.rules

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.commons.maintenance.*
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Actual signed owner -> SPI -> isolated runtime -> validation -> coordinator, all bytes offline. */
class SignedJsonProviderCoordinatorTest {
    private class Clock(@Volatile var now: Long = ProviderPackageFixtures.NOW) : EpochClock {
        override fun nowMillis() = now
    }
    private val profile = SearchProfile("profile", "fixture query", filters = SearchFilters(safeSearch = false))
    private fun rules(secondShape: Boolean) = if (secondShape)
        """{"schemaVersion":1,"ruleVersion":1,"itemsPath":["data","entries"],"fields":{"pageUrl":["link","href"],"title":["label"]},"nextCursorPath":["paging","after"]}""".toByteArray()
    else """{"schemaVersion":1,"ruleVersion":1,"itemsPath":["items"],"fields":{"pageUrl":["url"],"title":["title"]},"nextCursorPath":["next"]}""".toByteArray()
    private fun body(id: String, index: Int = 1, next: String? = null, secondShape: Boolean = false): ByteArray {
        val cursor = next?.let { "\"$it\"" } ?: "null"
        return (if (secondShape) """{"data":{"entries":[{"link":{"href":"https://example.org/$id/$index"},"label":"Item $index"}]},"paging":{"after":$cursor}}"""
        else """{"items":[{"url":"https://example.org/$id/$index","title":"Item $index"}],"next":$cursor}""").toByteArray()
    }
    private class Source(private val bytes: (RuleResponseRequest) -> ByteArray) : RuleResponseSource {
        val requests = CopyOnWriteArrayList<RuleResponseRequest>()
        val closed = AtomicInteger(); val cancelled = AtomicInteger()
        override fun create(request: RuleResponseRequest): RuleResponseCall {
            requests += request
            return object : RuleResponseCall {
                private val once = AtomicBoolean()
                override fun execute(): InputStream = object : ByteArrayInputStream(bytes(request)) {
                    override fun close() { closed.incrementAndGet(); super.close() }
                }
                override fun cancel() { if (once.compareAndSet(false, true)) cancelled.incrementAndGet() }
            }
        }
    }
    private inner class Fixture(
        val id: String,
        val clock: Clock,
        secondShape: Boolean = false,
        limits: CommonsLimits? = null,
        response: ((RuleResponseRequest) -> ByteArray)? = null,
    ) : AutoCloseable {
        val directory: File = Files.createTempDirectory("esv-signed-coordinator").toFile()
        private val name = "Fixture $id"
        val ruleBytes = rules(secondShape)
        val target = ProviderTarget.declarativeJson(id, "1", name, listOf(ProviderRuleSelfTestVector(
            body(id, secondShape = secondShape), listOf(CandidateUrl("https://example.org/$id/1",
                SourceReportedCandidateMetadata(title = "Item 1", sourceName = name))))))
        val repo = ProviderRepository(directory, target, ProviderPackageFixtures.verifier(target), clock,
            RepositoryPolicyFixtures.verifier(target))
        val source = Source(response ?: { body(id, secondShape = secondShape) })
        val provider = SignedJsonVideoSearchProvider(repo, source, clock)
        init { success(repo.install(ProviderPackageFixtures.signedRules(target, ruleBytes, limits = limits ?: target.localLimits))) }
        fun disable() = success(repo.installPolicy(RepositoryPolicyFixtures.signed(target = target, enabled = false)))
        override fun close() { repo.close(); directory.deleteRecursively() }
    }
    private inner class Harness(fixtures: List<Fixture>, val clock: Clock, val store: DiscoveryStore = InMemoryDiscoveryStore()) : AutoCloseable {
        val timer = JvmDiscoveryTimer()
        val runtime = IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock)
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(fixtures.map { it.provider }), runtime, store, timer, clock)
        fun start(session: String = "session", selectedProfile: SearchProfile = profile): SessionStamp {
            coordinator.setVisibility(true, true)
            return coordinator.start(session, selectedProfile)
        }
        fun settled() = waitUntil { coordinator.status().inFlightCount == 0 }
        override fun close() { coordinator.close(); runtime.close(); timer.close() }
    }
    private fun success(result: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue(result.toString(), result is ProviderRepositoryResult.Success)
        return (result as ProviderRepositoryResult.Success).snapshot
    }
    private fun waitUntil(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("finite fixture did not settle", check())
    }

    @Test fun twoSignedProvidersWithDifferentMappingsFetchTwoPagesAndOnlyPublishPending() {
        val clock = Clock()
        Fixture("alpha", clock, response = { body("alpha", if (it.cursor == null) 1 else 2, if (it.cursor == null) "a2" else null) }).use { a ->
            Fixture("beta", clock, secondShape = true, response = { body("beta", if (it.cursor == null) 1 else 2,
                if (it.cursor == null) "b2" else null, secondShape = true) }).use { b ->
                Harness(listOf(a, b), clock).use { h ->
                    h.start(); waitUntil { h.coordinator.pendingCandidates().size == 4 }; h.settled()
                    assertEquals(listOf(null, "a2"), a.source.requests.map { it.cursor })
                    assertEquals(listOf(null, "b2"), b.source.requests.map { it.cursor })
                    assertEquals(4, h.coordinator.status().requestsThisRound)
                    assertEquals(setOf("alpha", "beta"), h.coordinator.pendingCandidates().flatMap { it.provenance }.toSet())
                    h.coordinator.pendingCandidates().forEach { candidate ->
                        assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, candidate.sourceReported!!.metadataLevel)
                        assertEquals("Fixture ${candidate.provenance.single()}", candidate.sourceReported!!.sourceName)
                    }
                    assertEquals(0, h.coordinator.status().availableCount); assertNull(h.coordinator.page(0))
                    assertEquals(ProviderProgressScope.DURABLE, a.provider.descriptor.progressScope)
                    assertTrue(a.provider.descriptor.version.matches(Regex("[a-f0-9]{64}")))
                    assertNotEquals(a.provider.descriptor.version, b.provider.descriptor.version)
                    assertNull(h.coordinator.status().health.getValue("alpha").lastError)
                    assertNull(h.coordinator.status().health.getValue("beta").lastError)
                    h.store.transaction {
                        assertTrue(progress("profile", "alpha", profile.queryKey)!!.exhausted)
                        assertTrue(progress("profile", "beta", profile.queryKey)!!.exhausted)
                        assertTrue(checkpoint("session")!!.pool.entries.isEmpty())
                    }
                    assertEquals(2, a.source.closed.get()); assertEquals(2, a.source.cancelled.get())
                    assertEquals(2, b.source.closed.get()); assertEquals(2, b.source.cancelled.get())
                }
            }
        }
    }

    @Test fun threeActualCoordinatorSearchesPromoteTheExactInstalledPlan() {
        val clock = Clock()
        Fixture("alpha", clock).use { f -> Harness(listOf(f), clock).use { h ->
            val binding = f.provider.descriptor
            repeat(3) { index ->
                h.start("session$index", profile.copy(query = "fixture query $index"))
                waitUntil { f.source.requests.size == index + 1 }; h.settled()
                assertEquals(1, h.coordinator.pendingCandidates().size)
            }
            assertEquals(3, f.source.requests.size)
            assertEquals(ProviderHealthPhase.HEALTHY, f.repo.snapshot().health.phase)
            assertNull(f.repo.snapshot().pendingVersion); assertEquals(1L, f.repo.snapshot().lastKnownGoodVersion)
            assertEquals(binding, f.provider.descriptor)
        } }
    }

    @Test fun malformedOneSourceDoesNotCancelOrAttributeFailureToHealthySibling() {
        val clock = Clock()
        Fixture("alpha", clock, response = { """{"unexpected":[]}""".toByteArray() }).use { a ->
            Fixture("beta", clock, secondShape = true).use { b -> Harness(listOf(a, b), clock).use { h ->
                h.start(); waitUntil { b.source.requests.size == 1 }; h.settled()
                assertEquals(setOf("beta"), h.coordinator.pendingCandidates().flatMap { it.provenance }.toSet())
                assertEquals(DiscoveryError.INVALID_RESULT, h.coordinator.status().health.getValue("alpha").lastError)
                assertNull(h.coordinator.status().health.getValue("beta").lastError)
                assertEquals(0, a.repo.snapshot().health.conclusiveObservations)
                assertEquals(1, b.repo.snapshot().health.consecutiveSuccesses)
            } }
        }
    }

    @Test fun ownerRevocationRemovesOnlyItsPendingAndOldCandidateCannotBorrowNewAuthority() {
        val clock = Clock()
        Fixture("alpha", clock).use { a -> Fixture("beta", clock).use { b ->
            Harness(listOf(a, b), clock).use { h ->
                val stamp = h.start(); waitUntil { h.coordinator.pendingCandidates().size == 2 }; h.settled()
                val old = h.coordinator.pendingCandidates().single { "alpha" in it.provenance }
                a.disable()
                assertEquals(setOf("beta"), h.coordinator.pendingCandidates().flatMap { it.provenance }.toSet())
                assertFalse(h.coordinator.verifyPending(stamp, old.candidateId))
                assertEquals(1, a.source.requests.size); assertEquals(1, b.source.requests.size)
                assertNull(h.coordinator.verificationSummary())
                assertEquals(1, h.coordinator.pendingCandidates().size)
            }
        } }
    }

    @Test fun normalRequestCloseAndDeadlineDoNotRevokePendingButPackageExpiryDoes() {
        val clock = Clock()
        Fixture("alpha", clock).use { f -> Harness(listOf(f), clock).use { h ->
            h.start(); waitUntil { h.coordinator.pendingCandidates().size == 1 }; h.settled()
            clock.now += 15_001
            assertEquals(1, h.coordinator.pendingCandidates().size)
            assertEquals(1, f.source.closed.get())
            clock.now = ProviderPackageFixtures.NOW + 60_000
            assertTrue(h.coordinator.pendingCandidates().isEmpty())
            assertEquals(0, h.coordinator.status().availableCount)
        } }
    }

    @Test fun repeatedOpaqueCursorStopsWithinCoordinatorBudgetWithoutFollowingAUrl() {
        val clock = Clock()
        Fixture("alpha", clock, response = { body("alpha", if (it.cursor == null) 1 else 2, "opaque-repeat") }).use { f ->
            Harness(listOf(f), clock).use { h ->
                h.start(); waitUntil { f.source.requests.size == 2 }; h.settled()
                assertEquals(2, h.coordinator.status().requestsThisRound)
                assertEquals(DiscoveryError.REPEATED_CURSOR, h.coordinator.status().health.getValue("alpha").lastError)
                assertEquals(listOf(null, "opaque-repeat"), f.source.requests.map { it.cursor })
            }
        }
    }

    @Test fun signedReadBudgetClosesBodyAndKeepsOversizeBytesOutOfPending() {
        val clock = Clock()
        Fixture("alpha", clock, limits = CommonsLimits(maxResponseBytes = 128), response = { ByteArray(129) { ' '.code.toByte() } }).use { f ->
            Harness(listOf(f), clock).use { h ->
                h.start(); waitUntil { f.source.requests.size == 1 }; h.settled()
                assertEquals(128L, f.source.requests.single().maximumBytes)
                assertEquals(DiscoveryError.RESPONSE_TOO_LARGE, h.coordinator.status().health.getValue("alpha").lastError)
                assertTrue(h.coordinator.pendingCandidates().isEmpty())
                assertEquals(1, f.source.closed.get()); assertEquals(1, f.source.cancelled.get())
                assertEquals(0, f.repo.snapshot().health.conclusiveObservations)
            }
        }
    }

    @Test fun requiredSafeSearchRefusesBeforeAnyHostResponseCall() {
        val clock = Clock()
        Fixture("alpha", clock).use { f -> Harness(listOf(f), clock).use { h ->
            h.start(selectedProfile = profile.copy(filters = SearchFilters(safeSearch = true))); h.settled()
            assertTrue(f.source.requests.isEmpty()); assertTrue(h.coordinator.pendingCandidates().isEmpty())
            assertEquals(DiscoveryError.UNSUPPORTED_FILTER, h.coordinator.status().health.getValue("alpha").lastError)
        } }
    }

    private enum class Boundary { BEFORE_GUARD, AFTER_GUARD, AFTER_COMMIT }
    private class BoundaryStore(private val boundary: Boundary) : DiscoveryStore {
        val backing = InMemoryDiscoveryStore()
        var revoke: (() -> Unit)? = null
        private val once = AtomicBoolean()
        override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
            var guarded = false
            val result = backing.transaction {
                val original = this
                val wrapped = object : DiscoveryTransaction by original {
                    override fun beforeCommit(check: () -> Unit) = original.beforeCommit {
                        guarded = true
                        if (boundary == Boundary.BEFORE_GUARD && once.compareAndSet(false, true)) revoke?.invoke()
                        check()
                        if (boundary == Boundary.AFTER_GUARD && once.compareAndSet(false, true)) revoke?.invoke()
                    }
                }
                wrapped.block()
            }
            if (guarded && boundary == Boundary.AFTER_COMMIT && once.compareAndSet(false, true)) revoke?.invoke()
            return result
        }
    }

    @Test fun actualOwnerRevocationAtEveryStoreBoundaryCannotPublishPendingOrSessionCursor() {
        for (boundary in Boundary.entries) {
            val clock = Clock()
            Fixture("alpha", clock, response = { body("alpha", next = "must-not-publish") }).use { f ->
                val store = BoundaryStore(boundary).apply { revoke = { f.disable() } }
                Harness(listOf(f), clock, store).use { h ->
                    h.start(); waitUntil { f.source.requests.size == 1 }; h.settled()
                    assertTrue(boundary.name, h.coordinator.pendingCandidates().isEmpty())
                    assertEquals(0, h.coordinator.status().availableCount)
                    assertEquals(1, f.source.requests.size)
                    store.backing.transaction {
                        val physical = progress("profile", "alpha", profile.queryKey)
                        if (boundary == Boundary.BEFORE_GUARD) assertNull(physical)
                        // A successful DB commit may retain old bytes; revocation removes current authority.
                        physical?.let { assertFalse(it.isCompatible(f.provider.descriptor, clock.now)) }
                    }
                }
            }
        }
    }

    @Test fun revocationDuringActualResponseWorkCancelsOnlyThatOwnerAndCannotPublishLateBytes() {
        val clock = Clock(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture("alpha", clock, response = {
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            body("alpha")
        }).use { a -> Fixture("beta", clock).use { b ->
            Harness(listOf(a, b), clock).use { h ->
                try {
                    h.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); a.disable()
                    waitUntil { h.coordinator.pendingCandidates().any { "beta" in it.provenance } }
                } finally { release.countDown() }
                h.settled(); waitUntil { a.source.closed.get() == 1 }
                assertEquals(setOf("beta"), h.coordinator.pendingCandidates().flatMap { it.provenance }.toSet())
                assertEquals(0, a.repo.snapshot().health.conclusiveObservations)
                assertEquals(1, b.repo.snapshot().health.consecutiveSuccesses)
            }
        } }
    }
    @Test fun descriptorIdentityUsesCanonicalDomainAndEveryCompleteBindingField() {
        val pack = ByteArray(32) { 1 }; val rule = ByteArray(32) { 2 }; val policy = ByteArray(32) { 4 }
        val incarnation = ByteArray(16) { 6 }
        fun token(id: String = "alpha", implementation: String = "1", p: ByteArray = pack,
            r: ByteArray = rule, security: ByteArray? = null, generation: Long = 3, nonce: ByteArray = incarnation) =
            ruleAuthorityDescriptorToken(id, implementation, p, r, security, generation, nonce)
        val first = token()
        assertEquals("d9092dc398bc2d5d393032a362d589f7fd657df7f10d00090f2ff0de1951c729", first)
        val variants = listOf(token(id = "alphb"), token(implementation = "2"),
            token(p = pack.copyOf().apply { this[31] = 5 }), token(r = rule.copyOf().apply { this[31] = 5 }),
            token(security = ByteArray(32)), token(security = policy), token(generation = 4),
            token(nonce = incarnation.copyOf().apply { this[15] = 7 }))
        assertEquals(variants.size + 1, (variants + first).distinct().size)
        assertNotEquals(token(id = "a", implementation = "bc"), token(id = "ab", implementation = "c"))
        assertTrue((variants + first).all { it.matches(Regex("[a-f0-9]{64}")) })
    }

    @Test fun cancellationHookRegistrationFailureStillClosesTheCreatedCallExactlyOnce() {
        val clock = Clock(); val bodies = AtomicInteger()
        Fixture("alpha", clock, response = { bodies.incrementAndGet(); body("alpha") }).use { f ->
            val cancellation = DiscoveryCancellation()
            repeat(8) { cancellation.onCancel {} }
            val operation = DiscoveryOperation(OperationContext("full-hooks", clock.now + 15_000, cancellation), cancellation)
            val request = SearchRequest(SessionStamp("session", 1), profile, null, f.provider.descriptor)
            f.provider.acquire(request, operation).use { lease ->
                try { lease.search(request, operation); fail("registration must reject the ninth hook") }
                catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.INVALID_RESULT, failure.code) }
            }
            assertEquals(1, f.source.requests.size); assertEquals(1, f.source.cancelled.get())
            assertEquals(0, bodies.get()); assertEquals(0, f.source.closed.get())
        }
    }

    @Test fun threeRecognizedShapeFailuresQuarantineTheExactPlanAndPreserveHighWater() {
        val clock = Clock()
        Fixture("alpha", clock, response = { """{"items":[{"url":[]}]}""".toByteArray() }).use { f ->
            val cancellation = DiscoveryCancellation()
            val operation = DiscoveryOperation(OperationContext("capture-view", clock.now + 15_000, cancellation), cancellation)
            val request = SearchRequest(SessionStamp("capture", 1), profile, null, f.provider.descriptor)
            val oldView = f.provider.acquire(request, operation).use { it.authority }
            assertTrue(oldView.isCurrent(clock.now))
            repeat(3) { index ->
                // A fresh user search surface has no prior scheduler cooldown; the same repository
                // retains all three actual signed-plan observations and the same probation identity.
                Harness(listOf(f), clock).use { h ->
                    h.start("shape$index"); waitUntil { f.source.requests.size == index + 1 }; h.settled()
                    assertTrue(h.coordinator.pendingCandidates().isEmpty())
                    if (index < 2) assertEquals(index + 1, f.repo.snapshot().health.consecutiveStructureFailures)
                }
            }
            val snapshot = f.repo.snapshot()
            assertEquals(1L, snapshot.highestAcceptedVersion)
            assertNull(snapshot.pendingVersion); assertFalse(snapshot.policy.enabled)
            assertEquals(ProviderQuarantineReason.STRUCTURE_FAILURES, snapshot.quarantinedVersions.single().reason)
            assertEquals(1L, snapshot.quarantinedVersions.single().version)
            assertFalse(oldView.isCurrent(clock.now))
            assertEquals("unavailable", f.provider.descriptor.version)
        }
    }

}

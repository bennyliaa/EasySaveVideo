package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Original finite fixtures. No source network, repository key, device, or external data is used. */
class ProviderAuthorityPublicationTest {
    private val profile = SearchProfile("profile", "query", filters = SearchFilters(safeSearch = false))

    private class Authority(override val descriptor: ProviderDescriptor, val expiry: Long = 60_000) : ProviderAuthorityView {
        private val gate = Any()
        private var valid = true
        fun revoke() = synchronized(gate) { valid = false }
        override fun isCurrent(nowMillis: Long) = synchronized(gate) { valid && nowMillis < expiry }
        override fun publishIfCurrent(nowMillis: Long, operation: DiscoveryOperation?, publish: () -> Unit): Boolean = synchronized(gate) {
            if (!valid || nowMillis >= expiry || operation != null &&
                (operation.cancellation.isCancelled || nowMillis >= operation.context.deadlineEpochMillis)) false
            else { publish(); true }
        }
    }

    private class Source(@Volatile var view: Authority,
        private val body: (SearchRequest, DiscoveryOperation) -> ProviderPage = { _, _ -> ProviderPage(exhausted = true) },
        closeCount: Int = 1,
    ) : VideoSearchProvider {
        val acquired = AtomicInteger()
        val searches = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CountDownLatch(closeCount)
        override val descriptor: ProviderDescriptor get() = view.descriptor
        override val requiresAuthority = true
        override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage =
            throw DiscoveryFailure(DiscoveryError.SOURCE_CONFIGURATION)
        override fun acquire(request: SearchRequest, operation: DiscoveryOperation): ProviderRequestLease {
            acquired.incrementAndGet()
            val captured = view
            if (request.expectedDescriptor != captured.descriptor) throw DiscoveryFailure(DiscoveryError.CANCELLED)
            return object : ProviderRequestLease {
                override val authority = captured
                override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage {
                    searches.incrementAndGet(); return body(request, operation)
                }
                override fun close() { closes.incrementAndGet(); closed.countDown() }
            }
        }
    }

    private enum class Boundary { BEFORE_GUARD, AFTER_GUARD, AFTER_COMMIT }
    private class BoundaryStore : DiscoveryStore {
        val backing = InMemoryDiscoveryStore()
        var boundary = Boundary.BEFORE_GUARD
        var action: (() -> Unit)? = null
        override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
            val action = this.action
            var guarded = false
            val result = backing.transaction {
                val original = this
                val wrapper = object : DiscoveryTransaction by original {
                    override fun beforeCommit(check: () -> Unit) {
                        original.beforeCommit {
                            guarded = true
                            if (boundary == Boundary.BEFORE_GUARD) action?.invoke()
                            check()
                            if (boundary == Boundary.AFTER_GUARD) action?.invoke()
                        }
                    }
                }
                wrapper.block()
            }
            if (guarded && boundary == Boundary.AFTER_COMMIT) action?.invoke()
            return result
        }
    }

    private fun providerDescriptor(id: String = "source", version: String = "1", scope: ProviderProgressScope = ProviderProgressScope.SESSION) =
        ProviderDescriptor(id, version, progressScope = scope)
    private fun candidate(id: Int = 1, source: String = "source") = PendingCandidatePolicy.normalize(
        CandidateUrl("https://video.example/watch?id=$id", SourceReportedCandidateMetadata(title = "reported")), source, profile.filters)!!
    private fun page(id: Int = 1, source: String = "source", exhausted: Boolean = true) =
        ValidatedProviderPage(emptyList(), if (exhausted) null else "opaque-next", 50_000, exhausted, listOf(candidate(id, source)))
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }
    private fun waitUntil(check: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(check())
    }

    @Test fun expectedDescriptorIsCapturedBeforeAcquireAndCannotBorrowNewPlan() {
        val clock = FakeClock(); val timer = FakeTimer(clock)
        val first = Authority(providerDescriptor()); val source = Source(first)
        val request = SearchRequest(SessionStamp("session", 1), profile, null, first.descriptor)
        source.view = Authority(providerDescriptor(version = "2"))
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), timer, clock).use { runtime ->
            val done = CountDownLatch(1); val outcome = AtomicReference<ProviderOutcome>()
            runtime.execute(source, request, operation()) { outcome.set(it); done.countDown() }
            await(done)
            assertEquals(DiscoveryError.CANCELLED, (outcome.get() as ProviderOutcome.Failure).code)
            assertEquals(1, source.acquired.get()); assertEquals(0, source.searches.get()); assertEquals(0, source.closes.get())
        }
    }

    @Test fun requiredAuthorityCannotFallBackToLegacySearch() {
        val calls = AtomicInteger(); val clock = FakeClock()
        val source = object : VideoSearchProvider {
            override val descriptor = providerDescriptor()
            override val requiresAuthority = true
            override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage { calls.incrementAndGet(); return ProviderPage() }
        }
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), FakeTimer(clock), clock).use { runtime ->
            val done = CountDownLatch(1); val outcome = AtomicReference<ProviderOutcome>()
            runtime.execute(source, SearchRequest(SessionStamp("s", 1), profile, null), operation()) { outcome.set(it); done.countDown() }
            await(done); assertEquals(DiscoveryError.SOURCE_CONFIGURATION, (outcome.get() as ProviderOutcome.Failure).code)
            assertEquals(0, calls.get())
        }
    }

    @Test fun callbackThrowStillClosesOneLeaseAfterTheCallback() {
        val clock = FakeClock(); val source = Source(Authority(providerDescriptor()))
        val closedInsideCallback = AtomicInteger(-1)
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), FakeTimer(clock), clock).use { runtime ->
            runtime.execute(source, SearchRequest(SessionStamp("s", 1), profile, null), operation()) {
                closedInsideCallback.set(source.closes.get())
                throw IllegalStateException("TEST_CALLBACK")
            }
            await(source.closed)
            assertEquals(0, closedInsideCallback.get()); assertEquals(1, source.closes.get()); assertEquals(1, source.acquired.get())
        }
    }

    @Test fun pipelineFailureClosesAcquiredLeaseExactlyOnce() {
        val clock = FakeClock(); val source = Source(Authority(providerDescriptor()), { _, _ ->
            ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch")))
        })
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), FakeTimer(clock), clock).use { runtime ->
            val done = CountDownLatch(1); val outcome = AtomicReference<ProviderOutcome>()
            runtime.execute(source, SearchRequest(SessionStamp("s", 1), profile, null), operation()) { outcome.set(it); done.countDown() }
            await(done); await(source.closed)
            assertEquals(DiscoveryError.VALIDATOR_UNAVAILABLE, (outcome.get() as ProviderOutcome.Failure).code)
            assertEquals(1, source.closes.get())
        }
    }

    @Test fun cancellationKeepsWorkingLeaseAndPhysicalSlotUntilWorkerExits() {
        val clock = FakeClock(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = Source(Authority(providerDescriptor()), { _, _ ->
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            ProviderPage(exhausted = true)
        })
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), FakeTimer(clock), clock).use { runtime ->
            val done = CountDownLatch(1); val calls = AtomicInteger()
            try {
                val request = SearchRequest(SessionStamp("s", 1), profile, null)
                val handle = runtime.execute(source, request, operation()) { calls.incrementAndGet(); done.countDown() }
                await(entered); handle.cancel(); await(done)
                assertEquals(0, source.closes.get())
                val busy = AtomicReference<ProviderOutcome>()
                runtime.execute(source, request, operation()) { busy.set(it) }
                assertEquals(DiscoveryError.BUSY, (busy.get() as ProviderOutcome.Failure).code)
            } finally { release.countDown() }
            await(source.closed); assertEquals(1, calls.get()); assertEquals(1, source.closes.get())
        }
    }

    @Test fun timeoutThenLateWorkerClosesOnceAndNeverPublishesSuccess() {
        val clock = FakeClock(); val timer = FakeTimer(clock)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = Source(Authority(providerDescriptor()), { _, _ ->
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            ProviderPage(exhausted = true)
        })
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), timer, clock).use { runtime ->
            val done = CountDownLatch(1); val calls = AtomicInteger(); val outcome = AtomicReference<ProviderOutcome>()
            try {
                runtime.execute(source, SearchRequest(SessionStamp("s", 1), profile, null), operation(1100)) {
                    calls.incrementAndGet(); outcome.set(it); done.countDown()
                }
                await(entered); timer.advance(100); await(done)
                assertEquals(DiscoveryError.DEADLINE_EXCEEDED, (outcome.get() as ProviderOutcome.Failure).code)
                assertEquals(0, source.closes.get())
            } finally { release.countDown() }
            await(source.closed); assertEquals(1, calls.get()); assertEquals(1, source.closes.get())
        }
    }

    @Test fun retryFromSynchronousCallbackHasFreshLeaseAndNoBusySlot() {
        val clock = FakeClock(); val source = Source(Authority(providerDescriptor()), { _, operation ->
            if (operation.context.attempt == 0) throw DiscoveryFailure(DiscoveryError.PROVIDER_FAILURE, retryable = true)
            ProviderPage(exhausted = true)
        }, closeCount = 2)
        IsolatedProviderRuntime(ValidationPipeline(clock = clock), FakeTimer(clock), clock).use { runtime ->
            val done = CountDownLatch(1); val next = AtomicReference<ProviderOutcome>()
            val request = SearchRequest(SessionStamp("s", 1), profile, null); val original = operation()
            runtime.execute(source, request, original) {
                runtime.execute(source, request, original.copy(context = original.context.copy(attempt = 1))) { outcome ->
                    next.set(outcome); done.countDown()
                }
            }
            await(done); await(source.closed)
            assertTrue(next.get() is ProviderOutcome.Success)
            assertEquals(2, source.acquired.get()); assertEquals(2, source.closes.get())
        }
    }

    @Test fun actualAuthorityAndLegacyRuntimeBothFetchTwoPagesWithoutBusy() {
        for (guarded in listOf(false, true)) {
            val clock = FakeClock(); val timer = FakeTimer(clock); val calls = AtomicInteger()
            val authority = Authority(providerDescriptor())
            val body: (SearchRequest, DiscoveryOperation) -> ProviderPage = { request, _ ->
                val second = request.progress?.cursor == "page-two"
                calls.incrementAndGet()
                ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch?id=${if (second) 2 else 1}")),
                    nextCursor = if (second) null else "page-two", cursorExpiresAtEpochMillis = 50_000, exhausted = second)
            }
            val source: VideoSearchProvider = if (guarded) Source(authority, body, 2) else object : VideoSearchProvider {
                override val descriptor = authority.descriptor
                override fun search(request: SearchRequest, operation: DiscoveryOperation) = body(request, operation)
            }
            IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock).use { runtime ->
                VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, InMemoryDiscoveryStore(), timer, clock).use { coordinator ->
                    coordinator.setVisibility(true, true); coordinator.start("session", profile)
                    waitUntil { coordinator.pendingCandidates().size == 2 }
                    assertEquals(2, calls.get()); assertEquals(2, coordinator.status().requestsThisRound)
                    assertNull(coordinator.status().health.getValue("source").lastError)
                    if (guarded) { await((source as Source).closed); assertEquals(2, source.closes.get()) }
                }
            }
        }
    }

    @Test fun closedRequestAndOldOperationDeadlineDoNotExpirePendingButOwnerExpiryDoes() {
        val clock = FakeClock(); val timer = FakeTimer(clock); val authority = Authority(providerDescriptor())
        val source = Source(authority, { _, _ -> ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch")), exhausted = true) })
        IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock).use { runtime ->
            VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, InMemoryDiscoveryStore(), timer, clock).use { coordinator ->
                coordinator.setVisibility(true, true); coordinator.start("session", profile); await(source.closed)
                clock.now = 20_000; assertEquals(1, coordinator.pendingCandidates().size)
                assertTrue(authority.isCurrent(clock.now)); assertEquals(1, source.closes.get())
                clock.now = 60_000; assertTrue(coordinator.pendingCandidates().isEmpty())
            }
        }
    }

    @Test fun revocationAtEveryCommitBoundaryRejectsCurrentPendingAndCursorReuse() {
        for (boundary in Boundary.values()) {
            val clock = FakeClock(); val timer = FakeTimer(clock); val store = BoundaryStore(); val runtime = FakeRuntime()
            val authority = Authority(providerDescriptor(scope = ProviderProgressScope.DURABLE)); val source = Source(authority)
            val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, store, timer, clock)
            coordinator.setVisibility(true, true); coordinator.start("session", profile)
            store.boundary = boundary; store.action = { authority.revoke() }
            runtime.calls[0].callback(ProviderOutcome.Success(page(exhausted = false), authority))
            store.action = null
            assertTrue(coordinator.pendingCandidates().isEmpty())
            assertEquals(1, coordinator.status().requestsThisRound)
            assertEquals(1, runtime.calls.size)
            val physical = store.backing.transaction { progress(profile.profileId, "source", profile.queryKey) }
            if (boundary == Boundary.BEFORE_GUARD) assertNull(physical) else assertEquals("1", physical!!.providerVersion)
            source.view = Authority(providerDescriptor(version = "2", scope = ProviderProgressScope.DURABLE))
            coordinator.status(); assertEquals(1, runtime.calls.size)
            coordinator.requestMore(); assertEquals(2, runtime.calls.size)
            assertNull(runtime.calls[1].request.progress)
            assertEquals("2", runtime.calls[1].request.expectedDescriptor!!.version)
            coordinator.close()
        }
    }

    @Test fun revokedMergedSourceIsRemovedConservativelyWhileIndependentSiblingSurvives() {
        val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime()
        val a = Authority(providerDescriptor("a")); val b = Authority(providerDescriptor("b"))
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(Source(a), Source(b))), runtime, InMemoryDiscoveryStore(), timer, clock)
        coordinator.setVisibility(true, true); coordinator.start("session", profile)
        runtime.calls.first { it.provider.descriptor.id == "a" }.callback(ProviderOutcome.Success(page(1, "a"), a))
        runtime.calls.first { it.provider.descriptor.id == "b" }.callback(ProviderOutcome.Success(
            page(1, "b").copy(pendingCandidates = listOf(candidate(1, "b"), candidate(2, "b"))), b))
        assertEquals(2, coordinator.pendingCandidates().size)
        a.revoke()
        assertEquals(listOf(candidate(2, "b").candidateId), coordinator.pendingCandidates().map { it.candidateId })
        assertEquals(2, runtime.calls.size); assertEquals(2, coordinator.status().requestsThisRound)
        coordinator.close()
    }

    @Test fun explicitVerificationKeepsOriginalViewAndCannotAcquireNewSourceAuthority() {
        val clock = FakeClock(); val timer = FakeTimer(clock); val authority = Authority(providerDescriptor())
        val source = Source(authority, { _, _ -> ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch")), exhausted = true) })
        IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock).use { runtime ->
            VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, InMemoryDiscoveryStore(), timer, clock).use { coordinator ->
                coordinator.setVisibility(true, true); val stamp = coordinator.start("session", profile); await(source.closed)
                val pending = coordinator.pendingCandidates().single()
                assertTrue(coordinator.verifyPending(stamp, pending.candidateId))
                waitUntil { !coordinator.pendingCandidates().single().verificationActive }
                assertEquals(1, source.acquired.get()); assertEquals(1, source.closes.get())
                authority.revoke(); source.view = Authority(providerDescriptor(version = "2"))
                assertFalse(coordinator.verifyPending(stamp, pending.candidateId)); assertTrue(coordinator.pendingCandidates().isEmpty())
                assertEquals(1, source.acquired.get())
            }
        }
    }

    @Test fun independentVerifiedHistorySurvivesOnlyIfBeforeCommitGateAdmittedIt() {
        for (boundary in Boundary.values()) {
            val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime(); val store = BoundaryStore()
            val authority = Authority(providerDescriptor()); val source = Source(authority)
            val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, store, timer, clock)
            coordinator.setVisibility(true, true); val stamp = coordinator.start("session", profile)
            runtime.calls[0].callback(ProviderOutcome.Success(page(), authority))
            assertTrue(coordinator.verifyPending(stamp, candidate().candidateId))
            store.boundary = boundary; store.action = { authority.revoke() }
            runtime.calls[1].callback(ProviderOutcome.Success(ValidatedProviderPage(listOf(result(1)), null, null, true), authority))
            store.action = null
            val expected = if (boundary == Boundary.BEFORE_GUARD) 0 else 1
            assertEquals(expected, coordinator.status().availableCount)
            store.backing.transaction {
                assertEquals(expected, checkpoint("session")!!.pool.entries.size)
                assertEquals(expected != 0, seen("profile", result(1).fingerprint) != null)
            }
            assertTrue(coordinator.pendingCandidates().isEmpty()); assertNull(coordinator.verificationSummary())
            coordinator.close()
        }
    }

    @Test fun sessionSourceNeverReadsSameNamedDurableCursorAfterFreshStartOrRestore() {
        val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime(); val store = InMemoryDiscoveryStore()
        val source = Source(Authority(providerDescriptor()))
        store.transaction { putProgress(ProviderProgress(profile.profileId, "source", profile.queryKey, "1", 1, "old-durable", 50_000, true, 1000)) }
        val registry = ProviderRegistry(listOf(source))
        val first = VideoDiscoveryCoordinator(registry, runtime, store, timer, clock)
        first.setVisibility(true, true); first.start("session", profile)
        assertNull(runtime.calls[0].request.progress); first.close()
        val reopened = VideoDiscoveryCoordinator(registry, runtime, store, timer, clock)
        assertTrue(reopened.restore("session")); reopened.setVisibility(true, true)
        assertNull(runtime.calls[1].request.progress)
        assertEquals("old-durable", store.transaction { progress(profile.profileId, "source", profile.queryKey) }!!.cursor)
        reopened.close()
    }

    @Test fun cancellationAndDeadlineAtCommitBoundaryPreserveOnlyAdmittedHistoryAndEarlierPending() {
        for (deadline in listOf(false, true)) for (boundary in Boundary.values()) {
            val clock = FakeClock(); val timer = FakeTimer(clock); val runtime = FakeRuntime(); val store = BoundaryStore()
            val authority = Authority(providerDescriptor()); val source = Source(authority)
            val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, store, timer, clock)
            coordinator.setVisibility(true, true); val stamp = coordinator.start("session", profile)
            runtime.calls[0].callback(ProviderOutcome.Success(page().copy(pendingCandidates = listOf(candidate(1), candidate(2))), authority))
            assertTrue(coordinator.verifyPending(stamp, candidate(1).candidateId))
            val attempt = runtime.calls[1]
            store.boundary = boundary
            store.action = {
                if (deadline) clock.now = attempt.operation.context.deadlineEpochMillis
                else attempt.operation.cancellation.cancel()
            }
            // This response's new unverified item and summary never pass the final publication gate.
            val summary = VerificationSummary("local", EngineOutcome.UNKNOWN, EngineEvidence.NONE)
            attempt.callback(ProviderOutcome.Success(ValidatedProviderPage(listOf(result(1)), null, null, true,
                listOf(candidate(3)), listOf(CandidateVerificationReport(candidate(3).candidateId, summary))), authority))
            store.action = null
            val committed = boundary != Boundary.BEFORE_GUARD
            assertEquals(if (committed) 1 else 0, coordinator.status().availableCount)
            store.backing.transaction {
                assertEquals(committed, seen("profile", result(1).fingerprint) != null)
                assertEquals(if (committed) 1 else 0, checkpoint("session")!!.pool.entries.size)
            }
            val pending = coordinator.pendingCandidates()
            assertEquals(if (committed) setOf(candidate(2).candidateId) else setOf(candidate(1).candidateId, candidate(2).candidateId),
                pending.map { it.candidateId }.toSet())
            assertTrue(pending.none { it.verificationActive }); assertNull(coordinator.verificationSummary())
            assertTrue(authority.isCurrent(clock.now)); assertEquals(2, runtime.calls.size)
            coordinator.close()
        }
    }

    @Test fun closingRuntimeDuringWorkerCancelsAndEventuallyClosesItsLeaseOnce() {
        val clock = FakeClock(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = Source(Authority(providerDescriptor()), { _, _ ->
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            ProviderPage(exhausted = true)
        })
        val runtime = IsolatedProviderRuntime(ValidationPipeline(clock = clock), FakeTimer(clock), clock)
        val done = CountDownLatch(1); val outcome = AtomicReference<ProviderOutcome>(); val calls = AtomicInteger()
        try {
            runtime.execute(source, SearchRequest(SessionStamp("s", 1), profile, null), operation()) {
                outcome.set(it); calls.incrementAndGet(); done.countDown()
            }
            await(entered); runtime.close(); await(done)
            assertEquals(DiscoveryError.CANCELLED, (outcome.get() as ProviderOutcome.Failure).code)
            assertEquals(0, source.closes.get())
        } finally { release.countDown(); runtime.close() }
        await(source.closed); assertEquals(1, source.closes.get()); assertEquals(1, calls.get())
    }

    @Test fun lateWorkerAfterCoordinatorCloseCannotPublishAndClosesLeaseOnce() {
        val clock = FakeClock(); val timer = FakeTimer(clock); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = Source(Authority(providerDescriptor()), { _, _ ->
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            ProviderPage(candidates = listOf(CandidateUrl("https://video.example/watch")), exhausted = true)
        })
        IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock).use { runtime ->
            val store = InMemoryDiscoveryStore()
            val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(source)), runtime, store, timer, clock)
            try {
                coordinator.setVisibility(true, true); coordinator.start("session", profile); await(entered)
                coordinator.close(); assertEquals(0, source.closes.get())
            } finally { release.countDown(); coordinator.close() }
            await(source.closed)
            assertEquals(1, source.closes.get()); assertTrue(coordinator.pendingCandidates().isEmpty())
            assertNull(coordinator.currentCheckpoint()); assertNull(coordinator.verificationSummary())
            assertTrue(store.transaction { checkpoint("session")!!.pool.entries.isEmpty() })
        }
    }

}

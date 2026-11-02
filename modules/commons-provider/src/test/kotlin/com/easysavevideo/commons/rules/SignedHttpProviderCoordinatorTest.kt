package com.easysavevideo.commons.rules

import com.easysavevideo.commons.maintenance.*
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Owned loopback exchanges through actual OkHttp; no public service, credential or TLS claim. */
class SignedHttpProviderCoordinatorTest {
    private class Clock(@Volatile var now: Long = ProviderPackageFixtures.NOW) : EpochClock {
        override fun nowMillis() = now
    }
    private val profile = SearchProfile("profile", "fixture &=?%#+ query", locale = "und",
        filters = SearchFilters(safeSearch = false))
    private val next = "next&=?%#+"
    private data class Request(val path: String, val rawQuery: String?, val parameters: List<Pair<String, String>>) {
        fun value(name: String) = parameters.singleOrNull { it.first == name }?.second
    }
    private fun rule(endpoint: RuleHttpEndpoint): ByteArray {
        val response = if (endpoint.format == RuleResponseFormat.JSON)
            """{"format":"json","itemsPath":["items"],"fields":{"pageUrl":["url"],"title":["title"]},"nextCursorPath":["next"]}"""
        else """{"format":"html","container":[{"axis":"descendant","tag":"main","id":"results"}],"items":[{"axis":"child","tag":"article","class":"result"}],"fields":{"pageUrl":{"path":[{"axis":"child","tag":"a","class":"page"}],"attribute":"href"},"title":{"path":[{"axis":"child","tag":"h2"}],"text":true}},"nextCursor":{"path":[{"axis":"child","tag":"a","id":"next"}],"attribute":"data-cursor"}}"""
        return """{"schemaVersion":1,"ruleVersion":1,"request":{"endpointId":"${endpoint.endpointId}","path":"/search","query":[{"name":"q","input":"query"},{"name":"lang","input":"locale"},{"name":"after","input":"cursor"}]},"response":$response}""".toByteArray()
    }
    private fun body(id: String, format: RuleResponseFormat, second: Boolean = false, continued: Boolean = false,
        empty: Boolean = false): ByteArray {
        val index = if (second) 2 else 1
        return (if (format == RuleResponseFormat.JSON)
            """{"items":[${if (empty) "" else """{"url":"https://$id.example.org/video/$index","title":"Item $index"}"""}],"next":${if (continued) "\"$next\"" else "null"}}"""
        else """<html><head><base href="https://attacker.example/"></head><body><main id="results">${if (empty) "" else """<article class="result"><a class="page" href="/video/$index">page</a><h2>Item $index<script src="https://attacker.example/script">unsafe</script></h2><iframe src="https://attacker.example/frame"></iframe></article>"""}${if (continued) """<a id="next" data-cursor="${next.replace("&", "&amp;")}">next</a>""" else ""}</main></body></html>""").toByteArray()
    }
    private inner class Fixture(val id: String, val format: RuleResponseFormat, val clock: Clock = Clock()) : AutoCloseable {
        val directory = Files.createTempDirectory("esv-http-coordinator").toFile()
        val endpoint = RuleHttpEndpoint.https("$id.example.org", "/search", format, setOf("und"), linkedMapOf(
            "q" to RuleQueryAdmission.Input(RuleInputSlot.QUERY),
            "lang" to RuleQueryAdmission.Input(RuleInputSlot.LOCALE),
            "after" to RuleQueryAdmission.Input(RuleInputSlot.CURSOR)))
        val name = "Host $id"
        private fun vector(empty: Boolean, cursor: String? = null) = ProviderRuleSelfTestVector(
            body(id, format, empty = empty), if (empty) emptyList() else listOf(CandidateUrl(
                "https://$id.example.org/video/1", SourceReportedCandidateMetadata(title = "Item 1", sourceName = name))),
            httpRequest = ProviderRuleHttpSelfTestInput("self", "und", cursor,
                "/search?q=self&lang=und${if (cursor == null) "" else "&after=two"}"))
        val target = ProviderTarget.httpCandidates(id, "1", name, endpoint, listOf(vector(false), vector(true, "two")))
        val ruleBytes = rule(endpoint)
        var repo = open()
            private set
        fun open() = ProviderRepository(directory, target, ProviderPackageFixtures.verifier(target), clock,
            RepositoryPolicyFixtures.verifier(target))
        val requests = CopyOnWriteArrayList<Request>()
        @Volatile var pages = true
        @Volatile var status = 200
        @Volatile var responseOverride: ByteArray? = null
        @Volatile var beforeBody: (() -> Unit)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "owned-rule-http").apply { isDaemon = true } }
        private var updateBytes = ProviderPackageFixtures.signedHttpRules(target, ruleBytes)
        val updateCalls = AtomicInteger()
        private val updater = ProviderUpdateRuntime(repo, ProviderUpdateSource {
            updateCalls.incrementAndGet()
            val bytes = updateBytes.copyOf()
            object : ProviderUpdateCall {
                override fun execute(): InputStream = ByteArrayInputStream(bytes)
                override fun cancel() = Unit
            }
        }, clock)
        init {
            server.executor = executor
            server.createContext("/search") { exchange -> respond(exchange) }
            server.start()
            installThroughUpdateRuntime()
            assertTrue("compile/install/self-test must not execute HTTP", requests.isEmpty())
        }
        private fun respond(exchange: HttpExchange) {
            try {
                val parameters = exchange.requestURI.rawQuery?.split('&')?.map { part ->
                    val pieces = part.split('=', limit = 2)
                    URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
                } ?: emptyList()
                val request = Request(exchange.requestURI.path, exchange.requestURI.rawQuery, parameters)
                requests += request
                val bytes = responseOverride ?: body(id, format, request.value("after") != null,
                    pages && request.value("after") == null)
                exchange.responseHeaders.add("Content-Type", if (format == RuleResponseFormat.JSON) "application/json; charset=utf-8" else "text/html; charset=utf-8")
                if (status == 503) exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                beforeBody?.invoke()
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: Exception) { /* Cancellation may close this owned peer before its write. */ }
            finally { exchange.close() }
        }
        fun source(): RuleResponseSource = ownedLoopbackRuleHttpSource(endpoint, server.address.port, clock)
        fun provider(source: RuleResponseSource = source()) = SignedRuleVideoSearchProvider(repo, source, clock)
        private fun installThroughUpdateRuntime() {
            val done = CountDownLatch(1); val outcome = AtomicReference<ProviderUpdateOutcome>()
            updater.execute(clock.now + 10_000) { outcome.set(it); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val completed = outcome.get() as? ProviderUpdateOutcome.Completed
            assertNotNull(outcome.get().toString(), completed)
            success(completed!!.result)
        }
        fun update() = success(repo.install(ProviderPackageFixtures.signedHttpRules(target, ruleBytes, version = 2)))
        fun disable() = success(repo.installPolicy(RepositoryPolicyFixtures.signed(target = target, enabled = false)))
        fun reopen() { updater.close(); repo.close(); repo = open() }
        override fun close() { updater.close(); repo.close(); server.stop(0); executor.shutdownNow(); directory.deleteRecursively() }
    }
    private inner class Harness(providers: List<VideoSearchProvider>, val clock: Clock,
        val store: DiscoveryStore = InMemoryDiscoveryStore(), requests: Int = 6) : AutoCloseable {
        private val timer = JvmDiscoveryTimer()
        val runtime = IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock)
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(providers), runtime, store, timer, clock,
            limits = DiscoveryLimits(maxRequestsPerRound = requests))
        fun start(selected: SearchProfile = profile): SessionStamp {
            coordinator.setVisibility(true, true); return coordinator.start("session", selected)
        }
        fun restore() { coordinator.setVisibility(true, true); assertTrue(coordinator.restore("session")) }
        fun settled() = waitUntil { coordinator.status().inFlightCount == 0 }
        override fun close() { coordinator.close(); runtime.close(); timer.close() }
    }
    private fun success(result: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue(result.toString(), result is ProviderRepositoryResult.Success)
        return (result as ProviderRepositoryResult.Success).snapshot
    }
    private fun waitUntil(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("owned HTTP fixture did not settle", check())
    }
    private fun uninterruptible(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }

    @Test fun twoSignedHttpFormatsFetchTwoPagesAndPublishOnlyHostAttributedPending() {
        val clock = Clock()
        Fixture("alpha", RuleResponseFormat.JSON, clock).use { a -> Fixture("beta", RuleResponseFormat.HTML, clock).use { b ->
            Harness(listOf(a.provider(), b.provider()), clock).use { h ->
                h.start(); waitUntil { h.coordinator.pendingCandidates().size == 4 }; h.settled()
                assertEquals(1, a.updateCalls.get()); assertEquals(1, b.updateCalls.get())
                for (f in listOf(a, b)) {
                    assertEquals(2, f.requests.size)
                    assertEquals(listOf(null, next), f.requests.map { it.value("after") })
                    f.requests.forEach { request ->
                        assertEquals("/search", request.path)
                        assertEquals(profile.query, request.value("q")); assertEquals("und", request.value("lang"))
                        assertEquals(request.parameters.size, request.parameters.map { it.first }.distinct().size)
                        assertEquals(setOf("q", "lang") + if (request.value("after") == null) emptySet() else setOf("after"),
                            request.parameters.map { it.first }.toSet())
                    }
                    assertEquals(2, f.repo.snapshot().health.consecutiveSuccesses)
                    assertNull(h.coordinator.status().health.getValue(f.id).lastError)
                }
                assertEquals(setOf("alpha", "beta"), h.coordinator.pendingCandidates().flatMap { it.provenance }.toSet())
                h.coordinator.pendingCandidates().forEach {
                    assertEquals(CandidateMetadataLevel.SOURCE_REPORTED, it.sourceReported!!.metadataLevel)
                    assertEquals("Host ${it.provenance.single()}", it.sourceReported!!.sourceName)
                    assertEquals("Item ${if (it.canonicalPageUrl.endsWith("/2")) 2 else 1}", it.sourceReported!!.title)
                    assertFalse(it.canonicalPageUrl.contains("attacker"))
                }
                assertEquals(0, h.coordinator.status().availableCount); assertNull(h.coordinator.page(0))
                h.store.transaction { assertTrue(checkpoint("session")!!.pool.entries.isEmpty()) }
            }
        } }
    }

    @Test fun threeActualHttpSuccessesPromoteTheExactPlanWithoutSelfTestNetwork() {
        for (format in RuleResponseFormat.entries) Fixture("health", format).use { f ->
            f.pages = false
            Harness(listOf(f.provider()), f.clock).use { h ->
                val descriptor = f.provider().descriptor
                repeat(3) { index -> h.start(profile.copy(query = "health $index")); h.settled() }
                assertEquals(3, f.requests.size)
                assertEquals(ProviderHealthPhase.HEALTHY, f.repo.snapshot().health.phase)
                assertEquals(1L, f.repo.snapshot().lastKnownGoodVersion); assertNull(f.repo.snapshot().pendingVersion)
                assertEquals(descriptor, f.provider().descriptor)
            }
        }
    }

    @Test fun non200HasOneExchangeAndDoesNotAttributeShapeFailureOrCancelSibling() {
        val clock = Clock()
        Fixture("alpha", RuleResponseFormat.JSON, clock).use { a -> Fixture("beta", RuleResponseFormat.HTML, clock).use { b ->
            a.status = 503; b.pages = false
            Harness(listOf(a.provider(), b.provider()), clock).use { h ->
                h.start(); waitUntil { b.requests.isNotEmpty() }; h.settled()
                assertEquals(1, a.requests.size); assertEquals(1, b.requests.size)
                assertEquals(0, a.repo.snapshot().health.conclusiveObservations)
                assertEquals(0, a.repo.snapshot().health.consecutiveStructureFailures)
                assertEquals(setOf("beta"), h.coordinator.pendingCandidates().flatMap { it.provenance }.toSet())
            }
        } }
    }

    @Test fun revocationWhileActualBodyIsPendingDiscardsItAndLeavesSiblingUsable() {
        val clock = Clock(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture("alpha", RuleResponseFormat.HTML, clock).use { a -> Fixture("beta", RuleResponseFormat.JSON, clock).use { b ->
            a.beforeBody = { entered.countDown(); uninterruptible(release) }; b.pages = false
            Harness(listOf(a.provider(), b.provider()), clock).use { h ->
                try {
                    h.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); a.disable()
                    waitUntil { h.coordinator.pendingCandidates().any { "beta" in it.provenance } }
                } finally { release.countDown() }
                h.settled()
                assertEquals(setOf("beta"), h.coordinator.pendingCandidates().flatMap { it.provenance }.toSet())
                assertEquals(1, a.requests.size); assertEquals(0, a.repo.snapshot().health.conclusiveObservations)
            }
        } }
    }

    private enum class Boundary { BEFORE_GUARD, AFTER_GUARD, AFTER_COMMIT }
    private class BoundaryStore(private val boundary: Boundary) : DiscoveryStore {
        val backing = InMemoryDiscoveryStore(); var revoke: (() -> Unit)? = null
        private val once = AtomicBoolean()
        override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
            var guarded = false
            val result = backing.transaction {
                val original = this
                object : DiscoveryTransaction by original {
                    override fun beforeCommit(check: () -> Unit) = original.beforeCommit {
                        guarded = true
                        if (boundary == Boundary.BEFORE_GUARD && once.compareAndSet(false, true)) revoke?.invoke()
                        check()
                        if (boundary == Boundary.AFTER_GUARD && once.compareAndSet(false, true)) revoke?.invoke()
                    }
                }.block()
            }
            if (guarded && boundary == Boundary.AFTER_COMMIT && once.compareAndSet(false, true)) revoke?.invoke()
            return result
        }
    }
    @Test fun actualHtmlPublicationRemainsFencedBeforeAndAfterEveryStoreCommitBoundary() {
        for (boundary in Boundary.entries) Fixture("alpha", RuleResponseFormat.HTML).use { f ->
            val store = BoundaryStore(boundary).apply { revoke = { f.disable() } }
            Harness(listOf(f.provider()), f.clock, store, requests = 1).use { h ->
                h.start(); h.settled()
                assertEquals(1, f.requests.size); assertTrue(h.coordinator.pendingCandidates().isEmpty())
                assertEquals(0, h.coordinator.status().availableCount)
                store.backing.transaction {
                    val physical = progress(profile.profileId, f.id, profile.queryKey)
                    if (boundary == Boundary.BEFORE_GUARD) assertNull(physical)
                    physical?.let { assertNotEquals(f.provider().descriptor.version, it.providerVersion) }
                }
            }
        }
    }

    @Test fun oldHttpPendingCannotBorrowANewPackageOrPolicyAuthority() {
        Fixture("alpha", RuleResponseFormat.HTML).use { f ->
            promote(f) // Real observations finish probation before installing a successor.
            f.pages = false
            Harness(listOf(f.provider()), f.clock).use { h ->
                val stamp = h.start(); h.settled()
                val pending = h.coordinator.pendingCandidates().single(); val old = f.provider().descriptor
                f.update()
                assertNotEquals(old, f.provider().descriptor)
                assertTrue(h.coordinator.pendingCandidates().isEmpty())
                assertFalse(h.coordinator.verifyPending(stamp, pending.candidateId))
                assertEquals(4, f.requests.size)
                f.disable(); h.coordinator.requestMore(); h.settled(); assertEquals(4, f.requests.size)
            }
        }
    }

    private fun promote(f: Fixture) {
        f.pages = false
        repeat(3) { index -> Harness(listOf(f.provider()), f.clock).use { h ->
            h.start(profile.copy(query = "training $index")); h.settled()
        } }
        assertEquals(ProviderHealthPhase.HEALTHY, f.repo.snapshot().health.phase)
        f.pages = true
    }
    private fun freshCheckpointStore(old: DiscoveryStore, id: String): InMemoryDiscoveryStore {
        val checkpoint = old.transaction { checkpoint("session") }!!
        val progress = old.transaction { progress(profile.profileId, id, profile.queryKey) }!!
        // Explicit immutable public records in a new real store. This is not Android SQLite evidence.
        return InMemoryDiscoveryStore().also { fresh -> fresh.transaction {
            putCheckpoint(checkpoint.copy(profile = checkpoint.profile.copy(), pool = PoolState()))
            putProgress(progress.copy())
        } }
    }
    @Test fun realHealthyRepositoryReopenRestoresHttpCursorIntoNewCoordinatorAndStore() {
        for (format in RuleResponseFormat.entries) Fixture("alpha", format).use { f ->
            promote(f)
            val old = f.provider().descriptor
            val restored = Harness(listOf(f.provider()), f.clock, requests = 1).use { h ->
                h.start(); h.settled()
                assertEquals(next, h.store.transaction { progress(profile.profileId, f.id, profile.queryKey) }!!.cursor)
                freshCheckpointStore(h.store, f.id)
            }
            f.reopen(); assertEquals(old, f.provider().descriptor)
            val count = f.requests.size
            Harness(listOf(f.provider()), f.clock, restored, requests = 1).use { h ->
                h.restore(); h.settled()
                assertEquals(count + 1, f.requests.size); assertEquals(next, f.requests.last().value("after"))
                assertTrue(h.coordinator.pendingCandidates().single().canonicalPageUrl.endsWith("/2"))
                assertTrue(h.store.transaction { progress(profile.profileId, f.id, profile.queryKey) }!!.exhausted)
            }
        }
    }

    @Test fun packageOrPolicyChangeRejectsRestoredHttpCursorBeforeAnyFollowupExchange() {
        for (revoke in listOf(false, true)) Fixture("alpha", RuleResponseFormat.JSON).use { f ->
            promote(f)
            val restored = Harness(listOf(f.provider()), f.clock, requests = 1).use { h ->
                h.start(); h.settled(); freshCheckpointStore(h.store, f.id)
            }
            val count = f.requests.size
            if (revoke) f.disable() else f.update()
            Harness(listOf(f.provider()), f.clock, restored, requests = 1).use { h ->
                h.restore(); h.settled()
                val later = f.requests.drop(count)
                assertEquals(if (revoke) 0 else 1, later.size)
                assertTrue(later.none { it.value("after") != null })
            }
        }
    }

    @Test fun threeRecognizedHtmlShapeFailuresQuarantineTheActualSignedPlan() {
        Fixture("alpha", RuleResponseFormat.HTML).use { f ->
            val old = f.provider().progressAuthority(f.provider().descriptor, f.clock.now)!!
            f.responseOverride = """<main id="results"><article class="result"><a class="page" href="/video/1"></a><h2>first</h2><h2>ambiguous</h2></article></main>""".toByteArray()
            repeat(3) { index -> Harness(listOf(f.provider()), f.clock).use { h ->
                h.start(); h.settled(); assertEquals(index + 1, f.requests.size)
                assertTrue(h.coordinator.pendingCandidates().isEmpty())
            } }
            assertFalse(f.repo.snapshot().policy.enabled); assertNull(f.repo.snapshot().pendingVersion)
            assertEquals(ProviderQuarantineReason.STRUCTURE_FAILURES, f.repo.snapshot().quarantinedVersions.single().reason)
            assertFalse(old.isCurrent(f.clock.now)); assertEquals(1L, f.repo.snapshot().highestAcceptedVersion)
        }
    }

    @Test fun explicitSearchAndHostLocaleAdmissionRejectBeforeTheHttpSource() {
        Fixture("alpha", RuleResponseFormat.HTML).use { f ->
            for (p in listOf(profile.copy(filters = SearchFilters(safeSearch = true)), profile.copy(locale = "other"))) {
                Harness(listOf(f.provider()), f.clock).use { h -> h.start(p); h.settled(); assertTrue(f.requests.isEmpty()) }
            }
            assertEquals(0, f.repo.snapshot().health.conclusiveObservations)
        }
    }

    @Test fun cancellationDuringActualHttpCloseKeepsPhysicalSlotWhileSiblingCompletes() {
        val clock = Clock(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture("alpha", RuleResponseFormat.JSON, clock).use { a -> Fixture("beta", RuleResponseFormat.HTML, clock).use { b ->
            a.pages = false; b.pages = false
            val actual = a.source()
            val wrapped = RuleResponseSource { request ->
                val call = actual.create(request)
                object : RuleResponseCall {
                    override fun execute(): InputStream = object : FilterInputStream(call.execute()) {
                        override fun close() { entered.countDown(); uninterruptible(release); super.close() }
                    }
                    override fun cancel() = call.cancel()
                }
            }
            val timer = JvmDiscoveryTimer()
            IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock).use { runtime ->
                fun operation(id: String) = DiscoveryCancellation().let { cancellation ->
                    DiscoveryOperation(OperationContext(id, clock.now + 10_000, cancellation,
                        resourceBudget = ResourceBudget(maxConcurrentWork = 2)), cancellation)
                }
                val provider = a.provider(wrapped); val op = operation("first")
                val outcome = AtomicReference<ProviderOutcome>(); val count = AtomicInteger(); val done = CountDownLatch(1)
                try {
                    runtime.execute(provider, SearchRequest(SessionStamp("runtime", 1), profile, null), op) { outcome.set(it); count.incrementAndGet(); done.countDown() }
                    assertTrue(entered.await(5, TimeUnit.SECONDS)); op.cancellation.cancel()
                    assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(DiscoveryError.CANCELLED, (outcome.get() as ProviderOutcome.Failure).code)
                    val busy = AtomicReference<ProviderOutcome>()
                    runtime.execute(provider, SearchRequest(SessionStamp("runtime", 1), profile, null), operation("busy")) { busy.set(it) }
                    assertEquals(DiscoveryError.BUSY, (busy.get() as ProviderOutcome.Failure).code)
                    val sibling = CountDownLatch(1); val siblingOutcome = AtomicReference<ProviderOutcome>()
                    runtime.execute(b.provider(), SearchRequest(SessionStamp("runtime", 1), profile, null), operation("sibling")) { siblingOutcome.set(it); sibling.countDown() }
                    assertTrue(sibling.await(5, TimeUnit.SECONDS)); assertTrue(siblingOutcome.get() is ProviderOutcome.Success)
                    assertEquals(1, a.requests.size); assertEquals(1, b.requests.size)
                } finally { release.countDown() }
                // Eventual physical drain, without assuming a callback implies worker completion.
                val recovered = AtomicReference<ProviderOutcome>()
                var started = false
                waitUntil {
                    if (!started || (recovered.get() as? ProviderOutcome.Failure)?.code == DiscoveryError.BUSY) {
                        started = true; recovered.set(null)
                        runtime.execute(provider, SearchRequest(SessionStamp("runtime", 1), profile, null), operation("recovered")) { recovered.set(it) }
                    }
                    recovered.get() is ProviderOutcome.Success
                }
                assertEquals(1, count.get()); assertEquals(2, a.requests.size)
            }
            timer.close()
        } }
    }
}

package com.easysavevideo.verification.local

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Adapted from the inspected, context-reconstructed internal B seam recovery materials.
 * Authored in-memory Calls exercise real coordinator/runtime/pipeline/grant/robots/cleanup paths.
 * They perform no DNS, TLS or sockets and do not prove network-interceptor or wire-retry behavior.
 */
class LocalVerificationEngineTransportSeamTest {
    private val pageUrl = "https://transport-seam.example/watch"
    private val robots = "User-agent: *\nAllow: /\nSitemap: https://ignored.example.org/map.xml\n"
    private val html = "<main><h1>Owned</h1><video data-esv-primary='true' src='/owned.mp4'></video></main>"

    @Test fun publicConstructorsRetainShapeAndAlwaysUseProductionTransport() {
        val constructors = LocalVerificationEngine::class.java.constructors.filterNot { it.isSynthetic }
        assertEquals(listOf(0, 3), constructors.map { it.parameterCount }.sorted())
        assertEquals(listOf(LocalDynamicRenderer::class.java, Boolean::class.javaPrimitiveType,
            kotlin.jvm.functions.Function0::class.java), constructors.single { it.parameterCount == 3 }.parameterTypes.toList())
        val factory = LocalVerificationEngine::class.java.getDeclaredField("scriptedCallFactory")
        assertTrue(Modifier.isPrivate(factory.modifiers)); assertTrue(Modifier.isFinal(factory.modifiers))
        factory.isAccessible = true
        LocalVerificationEngine().use { assertNull(factory.get(it)) }
        LocalVerificationEngine(renderer = null, fixtureLoopbackOnly = true, monotonicMillis = { 1000L }).use { assertNull(factory.get(it)) }
        val methods = LocalVerificationEngine.Companion::class.java.declaredMethods.filter { it.name.startsWith("withScriptedPublicCalls") }
        val callable = methods.filterNot { Modifier.isPrivate(it.modifiers) }
        assertTrue(callable.isNotEmpty()); assertTrue(callable.all { it.isSynthetic && it.returnType == LocalVerificationEngine::class.java })
        assertTrue(methods.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
    }

    @Test fun internalFactoryForcesPublicModeWithoutRenderer() {
        LocalVerificationEngine.withScriptedPublicCalls({ error("Unexpected Call") }).use { engine ->
            assertFalse(LocalVerificationEngine::class.java.getDeclaredField("fixtureLoopbackOnly").apply { isAccessible = true }.getBoolean(engine))
            assertNull(LocalVerificationEngine::class.java.getDeclaredField("renderer").apply { isAccessible = true }.get(engine))
            assertEquals(setOf(EngineCapability.STATIC_PAGE, EngineCapability.LOCAL_MATCHING), engine.capabilities)
        }
    }

    @Test fun missingGrantAndLoopbackCannotConstructScriptedCalls() {
        val calls = AtomicInteger()
        LocalVerificationEngine.withScriptedPublicCalls({ calls.incrementAndGet(); error("Unexpected Call") }, { 1000L }).use { engine ->
            val c = DiscoveryCancellation()
            val op = DiscoveryOperation(OperationContext("ungranted", 5000, c), c)
            for (url in listOf(pageUrl, "https://127.0.0.1/watch")) {
                val origin = if (url == pageUrl) "https://transport-seam.example:443" else "https://127.0.0.1:443"
                val request = EngineRequest("0".repeat(32), "seam", CandidateUrl(url), 4000, 2, 262144,
                    false, false, EngineNetworkConstraints(setOf(origin), requiresRobotsCheck = true))
                assertEquals(EngineOutcome.DENIED, engine.verify(request, op).outcome)
            }
            assertEquals(0, calls.get()); assertTrue(engine.isPhysicallyIdle())
        }
    }

    @Test fun factoryFailureAndForeignNullNeverFallBackAndReleaseOwner() {
        val throwing: (Request) -> Call = { throw IOException("ESV_SCRIPTED_FACTORY_FAILURE") }
        val nullable: (Request) -> Call? = { null }
        @Suppress("UNCHECKED_CAST") val invalid = nullable as (Request) -> Call
        for (factory in listOf(throwing, invalid)) {
            val calls = AtomicInteger()
            LocalVerificationEngine.withScriptedPublicCalls({ calls.incrementAndGet(); factory(it) }, { 1000L }).use { engine ->
                SessionRun(engine).use { run ->
                    val result = run.await()
                    assertEquals(EngineOutcome.ERROR, result.outcome); assertEquals(1, result.requestsConsumed)
                    assertEquals(1, calls.get()); assertTrue(engine.isPhysicallyIdle())
                }
            }
        }
    }

    @Test fun timeoutConfigurationFailureCancelsOwnedCall() {
        lateinit var call: ScriptedCall
        LocalVerificationEngine.withScriptedPublicCalls({ request ->
            ScriptedCall(request, timeoutFails = true) { error("Must not execute") }.also { call = it }
        }, { 1000L }).use { engine ->
            SessionRun(engine).use { run ->
                assertEquals(EngineOutcome.ERROR, run.await().outcome)
                assertFalse(call.isExecuted()); assertTrue(call.isCanceled()); assertTrue(engine.isPhysicallyIdle())
            }
        }
    }

    @Test fun realGrantReadsRobotsThenPageWithExactSharedAccountingAndRetires() {
        val calls = mutableListOf<ScriptedCall>(); val bodies = mutableListOf<TrackedBody>()
        LocalVerificationEngine.withScriptedPublicCalls({ request ->
            val index = calls.size
            assertTrue(index < 2)
            assertEquals(if (index == 0) "/robots.txt" else "/watch", request.url.encodedPath)
            assertEquals(if (index == 0) "text/plain" else "text/html", request.header("Accept"))
            assertEquals("identity", request.header("Accept-Encoding")); assertEquals(LocalRobotsPolicy.USER_AGENT, request.header("User-Agent"))
            assertEquals("GET", request.method); assertNotNull(request.tag(LocalSingleExchange::class.java))
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization")); assertNull(request.header("Proxy-Authorization"))
            ScriptedCall(request) { call ->
                TrackedBody(if (index == 0) robots else html, if (index == 0) "text/plain" else "text/html",
                    onClose = { assertTrue(call.isCanceled()) }).also { bodies += it }.let { response(request, it) }
            }.also { calls += it }
        }, { 1000L }).use { engine ->
            SessionRun(engine, maxBytes = (robots.toByteArray().size + html.toByteArray().size).toLong()).use { run ->
                val result = run.await()
                assertEquals(EngineOutcome.PAGE_EVIDENCE, result.outcome); assertEquals(EngineEvidence.HTML_VIDEO, result.evidence)
                assertEquals(pageUrl, result.raw?.pageUrl); assertEquals(2, result.requestsConsumed)
                assertEquals((robots.toByteArray().size + html.toByteArray().size).toLong(), result.bytesConsumed)
                assertTrue(result.consumptionKnown); assertEquals(2, calls.size)
                assertTrue(calls.all { it.isExecuted() && it.isCanceled() }); assertTrue(bodies.all { it.closed.get() })
                assertTrue(engine.isPhysicallyIdle())
                assertEquals(EngineOutcome.DENIED, engine.verify(run.request, run.operation).outcome)
                assertEquals(2, calls.size)
            }
        }
    }

    @Test fun robotsDenyUnknownAmbiguityAndNon200NeverFetchPage() {
        val cases = listOf(
            Triple(200, "User-agent: *\nDisallow: /", EngineOutcome.DENIED),
            Triple(200, "", EngineOutcome.UNKNOWN),
            Triple(200, "User-agent: *\nAllow: /\nCrawl-delay: 10", EngineOutcome.UNKNOWN),
            Triple(200, "User-agent: *\nAllow: /\nMalformed", EngineOutcome.UNKNOWN),
            Triple(200, "Verify you are human", EngineOutcome.DENIED),
            Triple(201, robots, EngineOutcome.UNKNOWN), Triple(204, robots, EngineOutcome.UNKNOWN),
            Triple(404, robots, EngineOutcome.UNKNOWN), Triple(410, robots, EngineOutcome.UNKNOWN),
            Triple(500, robots, EngineOutcome.UNKNOWN), Triple(503, robots, EngineOutcome.UNKNOWN),
            Triple(301, robots, EngineOutcome.DENIED), Triple(302, robots, EngineOutcome.DENIED),
            Triple(307, robots, EngineOutcome.DENIED), Triple(308, robots, EngineOutcome.DENIED),
            Triple(401, robots, EngineOutcome.DENIED), Triple(403, robots, EngineOutcome.DENIED),
            Triple(407, robots, EngineOutcome.DENIED), Triple(429, robots, EngineOutcome.RATE_LIMITED))
        for ((code, text, expected) in cases) {
            val calls = AtomicInteger()
            LocalVerificationEngine.withScriptedPublicCalls({ request ->
                assertEquals("/robots.txt", request.url.encodedPath); calls.incrementAndGet()
                ScriptedCall(request) { response(request, TrackedBody(text, "text/plain"), code) }
            }, { 1000L }).use { engine ->
                SessionRun(engine).use { run ->
                    val result = run.await()
                    assertEquals("HTTP $code", expected, result.outcome); assertNull(result.raw)
                    assertEquals(1, calls.get()); assertEquals(1, result.requestsConsumed); assertTrue(engine.isPhysicallyIdle())
                }
            }
        }
    }

    @Test fun duplicateHeadersWrongTypeEncodingAndAuthAreRejectedBeforeBodyRead() {
        val modifiers: List<(Response.Builder) -> Response.Builder> = listOf(
            { it.addHeader("Content-Type", "text/plain") }, { it.header("Content-Type", "text/html") },
            { it.header("Content-Encoding", "gzip") }, { it.header("WWW-Authenticate", "Basic realm=private") },
            { it.header("cf-mitigated", "challenge") },
            { it.header("Content-Length", "garbage") }, { it.header("Transfer-Encoding", "chunked").header("Content-Length", "23") },
            { it.header("X-Large", "x".repeat(17000)) })
        for (modify in modifiers) {
            lateinit var body: TrackedBody
            val calls = AtomicInteger()
            LocalVerificationEngine.withScriptedPublicCalls({ request ->
                calls.incrementAndGet()
                ScriptedCall(request) { call ->
                    body = TrackedBody(robots, "text/plain", onClose = { assertTrue(call.isCanceled()) })
                    modify(response(request, body).newBuilder()).build()
                }
            }, { 1000L }).use { engine -> SessionRun(engine).use { run ->
                assertNotEquals(EngineOutcome.PAGE_EVIDENCE, run.await().outcome)
                assertEquals(1, calls.get()); assertEquals(0, body.readBytes.get()); assertTrue(body.closed.get())
            } }
        }
    }

    @Test fun declaredOversizePageConsumesNoSecondBodyAllowance() {
        val calls = AtomicInteger(); lateinit var page: TrackedBody
        LocalVerificationEngine.withScriptedPublicCalls({ request ->
            val index = calls.getAndIncrement()
            ScriptedCall(request) {
                response(request, if (index == 0) TrackedBody(robots, "text/plain") else
                    TrackedBody(html, "text/html").also { page = it })
            }
        }, { 1000L }).use { engine -> SessionRun(engine, maxBytes = robots.length + html.length - 1L).use { run ->
            val result = run.await(); assertEquals(EngineOutcome.UNKNOWN, result.outcome)
            assertEquals(2, calls.get()); assertEquals(0, page.readBytes.get()); assertTrue(page.closed.get())
            assertEquals(robots.length + html.length - 1L, result.bytesConsumed)
        } }
    }

    @Test fun oversizeRobotsFailsWithoutReadingOrFetchingPage() {
        for (length in listOf(LocalRobotsPolicy.MAX_BYTES + 1L)) {
            val calls = AtomicInteger(); lateinit var body: TrackedBody
            LocalVerificationEngine.withScriptedPublicCalls({ request ->
                calls.incrementAndGet()
                ScriptedCall(request) { body = TrackedBody(robots, "text/plain", declaredLength = length); response(request, body) }
            }, { 1000L }).use { engine -> SessionRun(engine).use { run ->
                assertEquals(EngineOutcome.UNKNOWN, run.await().outcome)
                assertEquals(1, calls.get()); assertEquals(0, body.readBytes.get()); assertTrue(body.closed.get())
            } }
        }
    }

    @Test fun unknownLengthBelowCapSucceedsAndExactlyAtCapAbstainsWithoutProbe() {
        for (exact in listOf(false, true)) {
            val calls = AtomicInteger()
            LocalVerificationEngine.withScriptedPublicCalls({ request ->
                val index = calls.getAndIncrement()
                ScriptedCall(request) { response(request, TrackedBody(if (index == 0) robots else html,
                    if (index == 0) "text/plain" else "text/html", declaredLength = -1)) }
            }, { 1000L }).use { engine ->
                val cap = if (exact) robots.length.toLong() else 262144L
                SessionRun(engine, maxBytes = cap).use { run ->
                    val result = run.await()
                    assertEquals(if (exact) EngineOutcome.UNKNOWN else EngineOutcome.PAGE_EVIDENCE, result.outcome)
                    assertEquals(if (exact) 1 else 2, calls.get()); assertEquals(cap, result.bytesConsumed)
                }
            }
        }
    }

    @Test fun robotsUsesEntireSharedCapAndNoPageExchangeIsStarted() {
        val calls = AtomicInteger()
        LocalVerificationEngine.withScriptedPublicCalls({ request ->
            calls.incrementAndGet(); assertEquals("/robots.txt", request.url.encodedPath)
            ScriptedCall(request) { response(request, TrackedBody(robots, "text/plain")) }
        }, { 1000L }).use { engine -> SessionRun(engine, maxBytes = robots.length.toLong()).use { run ->
            val result = run.await(); assertEquals(EngineOutcome.UNKNOWN, result.outcome)
            assertEquals(1, calls.get()); assertEquals(1, result.requestsConsumed); assertEquals(robots.length.toLong(), result.bytesConsumed)
        } }
    }

    @Test fun insufficientRequestBudgetDoesNotFetchEvenRobots() {
        val calls = AtomicInteger()
        LocalVerificationEngine.withScriptedPublicCalls({ calls.incrementAndGet(); error("No budget") }, { 1000L }).use { engine ->
            SessionRun(engine, maxRequests = 1).use { run ->
                assertEquals(EngineOutcome.UNAVAILABLE, run.await().outcome); assertEquals(0, calls.get())
            }
        }
    }

    @Test fun pageChallengeAndLoginNeverBecomeEvidence() {
        for (marker in listOf("<div>captcha</div>", "<input type='password'>", "<script>widevine</script>")) {
            val calls = AtomicInteger()
            LocalVerificationEngine.withScriptedPublicCalls({ request ->
                val index = calls.getAndIncrement()
                ScriptedCall(request) { response(request, TrackedBody(if (index == 0) robots else html + marker,
                    if (index == 0) "text/plain" else "text/html")) }
            }, { 1000L }).use { engine -> SessionRun(engine).use { run ->
                assertEquals(EngineOutcome.DENIED, run.await().outcome); assertEquals(2, calls.get())
            } }
        }
    }

    @Test(timeout = 10000) fun cancellationKeepsPhysicalSlotUntilLateResponseCleanupCompletes() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1)
        val calls = AtomicInteger(); lateinit var active: ScriptedCall
        val engine = LocalVerificationEngine.withScriptedPublicCalls({ request ->
            calls.incrementAndGet()
            ScriptedCall(request) { call ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                response(request, TrackedBody(robots, "text/plain", onClose = {
                    assertTrue(call.isCanceled()); closing.countDown(); check(releaseClose.await(5, TimeUnit.SECONDS))
                }))
            }.also { active = it }
        }, { 1000L })
        try { SessionRun(engine).use { run ->
            assertTrue(entered.await(2, TimeUnit.SECONDS)); run.operation.cancellation.cancel()
            assertEquals(EngineOutcome.CANCELLED, run.await().outcome); assertTrue(active.isCanceled())
            assertFalse(engine.isPhysicallyIdle()); release.countDown(); assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertFalse(engine.isPhysicallyIdle()); assertEquals(1, calls.get())
            releaseClose.countDown(); awaitIdle(engine); assertEquals(1, calls.get())
        } } finally { release.countDown(); releaseClose.countDown(); engine.close() }
    }

    @Test(timeout = 10000) fun timeoutAndRevocationStopBeforePageEvenWhenRobotsReturnsLate() {
        for (revoke in listOf(false, true)) {
            val now = AtomicLong(1000); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val calls = AtomicInteger(); lateinit var active: ScriptedCall; lateinit var body: TrackedBody
            val engine = LocalVerificationEngine.withScriptedPublicCalls({ request ->
                calls.incrementAndGet()
                ScriptedCall(request) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    body = TrackedBody(robots, "text/plain"); response(request, body)
                }.also { active = it }
            }, now::get)
            try { SessionRun(engine, now = now).use { run ->
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (revoke) run.admission.revoke() else now.set(5000)
                val result = run.await()
                assertNotEquals(EngineOutcome.PAGE_EVIDENCE, result.outcome)
                if (!revoke) assertEquals(EngineOutcome.TIMEOUT, result.outcome)
                assertTrue(active.isCanceled()); assertFalse(engine.isPhysicallyIdle())
                release.countDown(); awaitIdle(engine)
                assertTrue(body.closed.get()); assertEquals(0, body.readBytes.get()); assertEquals(1, calls.get())
            } } finally { release.countDown(); engine.close() }
        }
    }

    @Test fun oldCompiledConstraintsStillSkipRobotsAndReserveSingleCap() {
        val calls = AtomicInteger()
        LocalVerificationEngine.withScriptedPublicCalls({ request ->
            calls.incrementAndGet(); assertEquals("/watch", request.url.encodedPath)
            ScriptedCall(request) { response(request, TrackedBody(html, "text/html")) }
        }, { 1000L }).use { engine ->
            val c = DiscoveryCancellation(); val op = DiscoveryOperation(OperationContext("compiled", 5000, c), c)
            val policy = CompiledVerificationSources(mapOf("owned" to setOf("https://transport-seam.example"))).configure(setOf("owned"), true)!!
            val candidate = CandidateUrl(pageUrl)
            val request = EngineRequest("0".repeat(32), "compiled", candidate, 4000, 1, 262144, false, false, policy.network(candidate))
            val result = engine.verify(request, op)
            assertEquals(EngineOutcome.PAGE_EVIDENCE, result.outcome); assertEquals(1, calls.get()); assertEquals(262144L, result.bytesConsumed)
        }
    }

    private fun response(request: Request, body: TrackedBody, code: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .header("Content-Type", body.contentType().toString()).body(body).build()
    private fun awaitIdle(engine: LocalVerificationEngine) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!engine.isPhysicallyIdle() && System.nanoTime() < until) Thread.sleep(1)
        assertTrue(engine.isPhysicallyIdle())
    }

    private class ScriptedCall(private val original: Request, private val timeoutFails: Boolean = false,
        private val executeResponse: (ScriptedCall) -> Response) : Call {
        private val executed = AtomicBoolean(); private val cancelled = AtomicBoolean(); private val timeout = Timeout()
        override fun request() = original
        override fun execute(): Response { check(executed.compareAndSet(false, true)); return executeResponse(this) }
        override fun enqueue(responseCallback: Callback): Unit = error("Unexpected async Call")
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted() = executed.get()
        override fun isCanceled() = cancelled.get()
        override fun timeout(): Timeout { if (timeoutFails) throw IOException("ESV_SCRIPTED_TIMEOUT_FAILURE"); return timeout }
        override fun clone(): Call = error("Unexpected clone")
    }
    private class TrackedBody(text: String, private val type: String, onClose: () -> Unit = {},
        private val declaredLength: Long = text.toByteArray().size.toLong()) : ResponseBody() {
        private val bytes = text.toByteArray(); val closed = AtomicBoolean(); val readBytes = AtomicInteger()
        private val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long) = super.read(sink, byteCount).also { if (it > 0) readBytes.addAndGet(it.toInt()) }
            override fun close() { try { onClose() } finally { super.close(); closed.set(true) } }
        }.buffer()
        override fun contentType(): MediaType = type.toMediaType()
        override fun contentLength() = declaredLength
        override fun source(): BufferedSource = source
    }

    /** Real original source/runtime/pipeline issue authority; no fake grant or reflection access. */
    private inner class SessionRun(engine: LocalVerificationEngine, maxRequests: Int = 2, maxBytes: Long = 262144,
        val now: AtomicLong = AtomicLong(1000)) : AutoCloseable {
        private val finished = CompletableFuture<EngineResult>()
        @Volatile lateinit var request: EngineRequest
        @Volatile lateinit var operation: DiscoveryOperation
        private val source = object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("seam-source", "1")
            override val searchBudget = SourceSearchBudget(1, 1024)
            override fun search(request: SearchRequest, operation: DiscoveryOperation) =
                ProviderPage(candidates = listOf(CandidateUrl(pageUrl)), exhausted = true)
        }
        private val registry = ProviderRegistry(listOf(source))
        val admission = SessionCandidateAdmission(registry, epochMillis = { 1000 }, monotonicMillis = now::get)
        private val engines = VerificationEngineRegistry().apply { register(engine, EngineAccessBoundary.LOCAL_TARGET) }
        private val scheduler = VerificationEngineScheduler(now::get)
        private val validator = object : CandidateValidator {
            override fun validate(candidate: CandidateUrl, operation: DiscoveryOperation): RawVideoResult? = validateEvidence(candidate, operation).raw
            override fun validateEvidence(candidate: CandidateUrl, operation: DiscoveryOperation): CandidateValidation {
                this@SessionRun.operation = operation
                request = EngineRequest("0".repeat(32), "seam-generation", candidate, 4000, maxRequests, maxBytes,
                    false, false, requireNotNull(admission.network(candidate, operation)))
                val result = scheduler.verify(request, operation, engines.all(), EngineSelection(fallbackAllowed = false))
                finished.complete(result)
                return CandidateValidation(result.raw, VerificationSummary(result.engineId, result.outcome, result.evidence))
            }
        }
        private val timer = object : DiscoveryTimer { override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {} }
        private val pipeline = ValidationPipeline(validator, clock = EpochClock { 1000 }, retainFailedValidationCandidates = true,
            sessionAdmission = admission)
        private val runtime = IsolatedProviderRuntime(pipeline, timer, EpochClock { 1000 })
        private val coordinator = VideoDiscoveryCoordinator(registry, runtime, InMemoryDiscoveryStore(), timer,
            EpochClock { 1000 }, sessionAdmission = admission)
        init {
            coordinator.setVisibility(true, true)
            coordinator.start("seam-session", SearchProfile("seam-profile", "owned fixture", filters = SearchFilters(safeSearch = false)),
                allowSessionCandidateVerification = true)
        }
        fun await(): EngineResult = finished.get(3, TimeUnit.SECONDS)
        override fun close() { coordinator.close(); runtime.close() }
    }
}

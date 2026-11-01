package com.easysavevideo.verification.local

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.*
import com.easysavevideo.searxng.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * End-to-end authored offline composition, not a live-site success or broad video coverage claim.
 * Real HTML source, isolated runtime, pipeline, user-session admission, scheduler, B and cards run;
 * only source HTTP responses and B Call objects are synthetic. No DNS, sockets, TLS or devices.
 */
class SearxngHtmlLocalCompositeTest {
    private val target = "https://videos.example.org/watch/owned"
    private val sourceHtml = "<!doctype html><html><head><meta name='generator' content='searxng/synthetic'><title>Fixture</title></head>" +
        "<body class='search_endpoint'><main id='main_results'><div id='results'><div id='urls'>" +
        "<article class='result result-default'><h3><a href='$target'>Source reported title</a></h3>" +
        "<p class='content'>An unverified source description.</p></article></div></div></main></body></html>"
    private val videoHtml = "<main><h1>Verified fixture</h1><video data-esv-primary='true' src='/owned.mp4'></video></main>"

    @Test(timeout = 10000) fun twoHtmlSourcesDeduplicateBeforeBAndKeepBothProvenancesDespiteThirdSourceFailure() {
        Composite(SearchFilters(safeSearch = false)).use { fixture ->
            fixture.awaitSources()
            val page = requireNotNull(fixture.coordinator.page(0))
            val cards = fixture.coordinator.results(page)
            assertEquals(1, cards.size)
            val card = cards.single()
            assertEquals(target, card.canonicalPageUrl); assertEquals("Verified fixture", card.title)
            assertEquals(setOf("searxng-1", "searxng-2"), card.provenance)
            assertTrue(fixture.coordinator.pendingCandidates().isEmpty())
            assertEquals(listOf("/robots.txt", "/watch/owned"), fixture.bRequests.map { it.url.encodedPath })
            assertEquals(5, fixture.sourceRequests.size)
            assertEquals(2, fixture.sourceRequests.count { it.endpoint.id == "searxng-1" })
            assertEquals(2, fixture.sourceRequests.count { it.endpoint.id == "searxng-2" })
            assertEquals(1, fixture.sourceRequests.count { it.endpoint.id == "searxng-3" })
            val status = fixture.coordinator.status()
            assertEquals(DiscoveryError.PROVIDER_FAILURE, status.providers.single { it.providerId == "searxng-3" }.lastError)
            assertNull(status.providers.single { it.providerId == "searxng-1" }.lastError)
            assertNull(status.providers.single { it.providerId == "searxng-2" }.lastError)
            assertEquals(EngineEvidence.HTML_VIDEO, fixture.coordinator.verificationSummary()?.evidence)
            assertFalse(card.rights.downloadAuthorized); assertTrue(card.rights.requiresReview)
            assertNull(card.durationMs) // Page evidence does not invent duration, playback or saving permission.
            assertTrue(fixture.b.isPhysicallyIdle())
        }
    }

    @Test(timeout = 10000) fun strictDurationFilterDoesNotTreatSourceReportsAsVerifiedVideoMetadata() {
        Composite(SearchFilters(safeSearch = false, includeUnknownDuration = false, minimumDurationMs = 1000)).use { fixture ->
            fixture.awaitSources()
            assertEquals(0, fixture.coordinator.status().availableCount)
            assertTrue(fixture.coordinator.pendingCandidates().isEmpty())
            // Validation is allowed to establish duration. This narrow B parser cannot, so only
            // the post-validation filter rejects the card; source-reported hints never substitute.
            assertEquals(listOf("/robots.txt", "/watch/owned"), fixture.bRequests.map { it.url.encodedPath })
            assertEquals(5, fixture.sourceRequests.size)
            assertTrue(fixture.coordinator.currentCheckpoint()?.pool?.entries.orEmpty().isEmpty())
        }
    }

    private inner class Composite(filters: SearchFilters) : AutoCloseable {
        val sourceRequests = CopyOnWriteArrayList<SearxngHttpRequest>()
        val bRequests = CopyOnWriteArrayList<Request>()
        private val clock = EpochClock { 1000 }
        private val timer = object : DiscoveryTimer { override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {} }
        private val sources = (1..3).map { number ->
            val endpoint = SearxngEndpoint("searxng-$number", "https://search-$number.example.org/search", SearxngResultFormat.HTML_WEB)
            SearxngHtmlSearchProvider(endpoint, SearxngTransport { request ->
                sourceRequests += request
                object : SearxngHttpCall {
                    override fun execute(): SearxngHttpResponse {
                        val body = if (request.kind == SearxngRequestKind.ROBOTS) "User-agent: *\nAllow: /search\n" else sourceHtml
                        return SearxngHttpResponse(if (number == 3) 403 else 200, request.url,
                            mapOf("Content-Type" to listOf(request.kind.accept), "Content-Length" to listOf(body.toByteArray().size.toString())),
                            ByteArrayInputStream(body.toByteArray()))
                    }
                    override fun cancel() = Unit
                }
            }, SearxngLimits(maxPages = 1), clock, timer)
        }
        private val registry = ProviderRegistry(sources)
        private val admission = SessionCandidateAdmission(registry, epochMillis = { 1000 }, monotonicMillis = { 1000 })
        val b = LocalVerificationEngine.withScriptedPublicCalls({ request ->
            bRequests += request
            assertEquals("videos.example.org", request.url.host)
            val robots = request.url.encodedPath == "/robots.txt"
            MemoryCall(request) { Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .header("Content-Type", if (robots) "text/plain" else "text/html")
                .body((if (robots) "User-agent: *\nAllow: /watch/owned\n" else videoHtml)
                    .toResponseBody((if (robots) "text/plain" else "text/html").toMediaType())).build() }
        }, { 1000 })
        private val engines = VerificationEngineRegistry().apply { register(b, EngineAccessBoundary.LOCAL_TARGET) }
        private val scheduler = VerificationEngineScheduler { 1000 }
        private val pipeline = ValidationPipeline(scheduler.validator(engines.all(), "composite", false, false,
            epochMillis = { 1000 }, sessionAdmission = admission), clock = clock,
            retainFailedValidationCandidates = true, sessionAdmission = admission)
        private val runtime = IsolatedProviderRuntime(pipeline, timer, clock)
        val coordinator = VideoDiscoveryCoordinator(registry, runtime, InMemoryDiscoveryStore(), timer, clock, sessionAdmission = admission)
        init {
            coordinator.setVisibility(true, true)
            coordinator.start("composite-session", SearchProfile("composite-profile", "original fixture", filters = filters),
                allowSessionCandidateVerification = true)
        }
        fun awaitSources() {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < until) {
                val status = coordinator.status()
                if (status.inFlightCount == 0 && status.providers.size == 3 &&
                    status.providers.all { it.lastSuccessAtEpochMillis != null || it.lastError != null }) return
                Thread.sleep(2)
            }
            fail("Synthetic source composition did not finish")
        }
        override fun close() { coordinator.close(); runtime.close(); b.close() }
    }
    private class MemoryCall(private val original: Request, private val response: () -> Response) : Call {
        private val executed = AtomicBoolean(); private val cancelled = AtomicBoolean(); private val timeout = Timeout()
        override fun request() = original
        override fun execute(): Response { check(executed.compareAndSet(false, true)); return response() }
        override fun enqueue(responseCallback: Callback): Unit = error("Unexpected asynchronous Call")
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted() = executed.get()
        override fun isCanceled() = cancelled.get()
        override fun timeout() = timeout
        override fun clone(): Call = error("Unexpected clone")
    }
}

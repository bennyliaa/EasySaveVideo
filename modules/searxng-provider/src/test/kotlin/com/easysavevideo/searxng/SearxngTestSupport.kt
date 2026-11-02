package com.easysavevideo.searxng

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

internal class TestClock(var now: Long = 1_000) : EpochClock { override fun nowMillis() = now }
internal class TestTimer : DiscoveryTimer {
    private val actions = CopyOnWriteArrayList<Pair<AtomicBoolean, () -> Unit>>()
    override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
        val active = AtomicBoolean(true); actions += active to action
        return CancellationHandle { active.set(false) }
    }
    fun fire() { actions.forEach { if (it.first.compareAndSet(true, false)) it.second() } }
}
internal fun endpoint(url: String = "https://search.example.org/search", id: String = "searxng-1") = SearxngEndpoint(id, url)
internal fun operation(deadline: Long = 16_000, maximum: Long? = null): DiscoveryOperation {
    val cancellation = DiscoveryCancellation()
    return DiscoveryOperation(OperationContext("searxng-test", deadline, cancellation,
        resourceBudget = ResourceBudget(1, maximum)), cancellation)
}
internal fun request(query: String = "short film", locale: String = "en", safe: Boolean = false,
    cursor: String? = null, endpoint: SearxngEndpoint = endpoint()): SearchRequest {
    val profile = SearchProfile("test-profile", query, locale, SearchFilters(safeSearch = safe))
    val progress = cursor?.let { ProviderProgress(profile.profileId, endpoint.id, profile.queryKey, endpoint.version,
        1, it, null, false, null) }
    return SearchRequest(SessionStamp("test-session", 1), profile, progress)
}
internal const val ONE = """{"results":[{"url":"https://videos.example.org/watch/one","title":"A <b>short</b> film","content":"Source description","duration":90,"thumbnail":"https://images.example.org/a.jpg"}]}"""
internal class Reply(val json: String = ONE, val status: Int = 200,
    val headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json; charset=utf-8")),
    val url: URI? = null, val stream: InputStream? = null)
internal class TestTransport(vararg replies: Reply) : SearxngTransport {
    val requests = CopyOnWriteArrayList<SearxngHttpRequest>()
    val cancellations = AtomicInteger()
    private val queue = ArrayDeque(replies.toList())
    override fun create(request: SearxngHttpRequest): SearxngHttpCall {
        requests += request
        val response = synchronized(queue) { queue.removeFirst() }
        return object : SearxngHttpCall {
            override fun execute() = SearxngHttpResponse(response.status, response.url ?: request.url, response.headers,
                response.stream ?: ByteArrayInputStream(response.json.toByteArray(Charsets.UTF_8)))
            override fun cancel() { cancellations.incrementAndGet() }
        }
    }
}
internal fun provider(transport: SearxngTransport, endpoint: SearxngEndpoint = endpoint(), limits: SearxngLimits = SearxngLimits(),
    clock: TestClock = TestClock(), timer: TestTimer = TestTimer()) = SearxngVideoSearchProvider(endpoint, transport, limits, clock, timer)
internal fun fails(code: DiscoveryError, action: () -> Unit): DiscoveryFailure {
    try { action(); fail("Expected $code") } catch (failure: DiscoveryFailure) { assertEquals(code, failure.code); return failure }
    throw AssertionError("Unreachable")
}
internal fun protocolFails(code: SearxngError = SearxngError.INVALID_RESPONSE, action: () -> Unit): SearxngFailure {
    try { action(); fail("Expected $code") } catch (failure: SearxngFailure) { assertEquals(code, failure.code); return failure }
    throw AssertionError("Unreachable")
}

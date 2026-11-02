package com.easysavevideo.commons

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.CancellationHandle
import com.easysavevideo.discovery.DiscoveryCancellation
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.DiscoveryTimer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

internal class TestClock(var now: Long = 1000) : EpochClock { override fun nowMillis() = now }
internal class TestTimer : DiscoveryTimer {
    private val actions = mutableListOf<Pair<AtomicInteger, () -> Unit>>()
    override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
        val active = AtomicInteger(1); actions += active to action
        return CancellationHandle { active.set(0) }
    }
    fun fire() { actions.toList().forEach { if (it.first.compareAndSet(1, 0)) it.second() } }
}
internal fun operation(deadline: Long = 16_000, maxBytes: Long? = null): DiscoveryOperation {
    val cancellation = DiscoveryCancellation()
    return DiscoveryOperation(OperationContext("commons-test", deadline, cancellation, resourceBudget = ResourceBudget(1, maxBytes)), cancellation)
}
internal fun fixture(name: String = "search-live-1.json"): ByteArray =
    CommonsMetadataProviderTest::class.java.getResourceAsStream("/commons/live/$name")!!.use { it.readBytes() }
internal fun config() = CommonsClientConfig(true, "0.3.0-m3a-dev", "mailto:operator@example.invalid")
internal val request = CommonsSearchRequest("Big Buck Bunny", safeSearchRequired = false)
internal class Reply(val bytes: ByteArray = fixture(), val status: Int = 200,
    val headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json; charset=utf-8")),
    val redirectedUrl: URI? = null, val stream: InputStream? = null)
internal class TestTransport(vararg replies: Reply) : CommonsTransport {
    val requests = mutableListOf<CommonsHttpRequest>()
    val cancellations = AtomicInteger()
    private val queue = ArrayDeque(replies.toList())
    override fun create(request: CommonsHttpRequest): CommonsHttpCall {
        requests += request
        val reply = queue.removeFirst()
        return object : CommonsHttpCall {
            override fun execute() = CommonsHttpResponse(reply.status, reply.redirectedUrl ?: request.url,
                reply.headers, reply.stream ?: ByteArrayInputStream(reply.bytes))
            override fun cancel() { cancellations.incrementAndGet() }
        }
    }
}
internal fun provider(transport: CommonsTransport, clock: TestClock = TestClock(), limits: CommonsLimits = CommonsLimits(), timer: TestTimer = TestTimer()) =
    CommonsMetadataProvider(config(), transport, limits, clock, timer)
internal fun fails(code: CommonsError, action: () -> Unit): CommonsFailure {
    try { action(); fail("Expected $code") } catch (failure: CommonsFailure) {
        assertEquals(code, failure.code); return failure
    }
    throw AssertionError("Unreachable")
}
/** Synthetic mutations of recorded JSON, never presented as a network recording. */
internal fun mutate(bytes: ByteArray = fixture(), action: (MutableMap<String, Json>) -> Unit): ByteArray {
    val root = StrictJson.read(bytes).objectValue().toMutableMap()
    action(root); return StrictJson.write(Json.Obj(root)).toByteArray()
}
internal fun changeFirstInfo(root: MutableMap<String, Json>, action: (MutableMap<String, Json>) -> Unit) {
    val query = root.getValue("query").objectValue().toMutableMap()
    val pages = (query.getValue("pages") as Json.Arr).values.toMutableList()
    val page = pages[0].objectValue().toMutableMap()
    val info = (page.getValue("videoinfo") as Json.Arr).values[0].objectValue().toMutableMap()
    action(info); page["videoinfo"] = Json.Arr(listOf(Json.Obj(info))); pages[0] = Json.Obj(page)
    query["pages"] = Json.Arr(pages); root["query"] = Json.Obj(query)
}

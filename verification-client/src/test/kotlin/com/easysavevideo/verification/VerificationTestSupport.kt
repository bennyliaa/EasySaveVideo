package com.easysavevideo.verification

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

internal const val GENERATION = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
internal const val REQUEST_ID = "0123456789abcdef0123456789abcdef"
internal const val CANDIDATE = "https://videos.example.org/watch/one?item=%E4%B8%80"
internal fun endpoint(url: String = "https://verification.example.org/v1/verify") = VerificationEndpoint(url)
internal class TestClock(var now: Long = 1_000) : EpochClock { override fun nowMillis() = now }
internal class TestTimer : DiscoveryTimer {
    val delays = CopyOnWriteArrayList<Long>()
    private val actions = CopyOnWriteArrayList<Pair<AtomicBoolean, () -> Unit>>()
    override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
        delays += delayMillis
        val active = AtomicBoolean(true); actions += active to action
        return CancellationHandle { active.set(false) }
    }
    fun fire() { actions.forEach { if (it.first.compareAndSet(true, false)) it.second() } }
}
internal fun operation(deadline: Long = 16_000, maximum: Long? = null): DiscoveryOperation {
    val cancellation = DiscoveryCancellation()
    return DiscoveryOperation(OperationContext("verification-test", deadline, cancellation,
        resourceBudget = ResourceBudget(1, maximum)), cancellation)
}
internal fun responseJson(requestId: String = REQUEST_ID, generation: String = GENERATION, candidateUrl: String = CANDIDATE,
    outcome: String = "PAGE_EVIDENCE", evidence: String = "HTML_VIDEO", title: Json = Json.Str("Verified title"),
    thumbnail: Json = Json.Str("https://images.example.org/cover.jpg"), duration: Json = Json.Num("12000")): String = StrictJson.write(Json.Obj(linkedMapOf(
        "version" to Json.Num("1"), "requestId" to Json.Str(requestId), "generation" to Json.Str(generation),
        "candidateUrl" to Json.Str(candidateUrl), "outcome" to Json.Str(outcome), "pageUrl" to Json.Str(candidateUrl),
        "evidence" to Json.Str(evidence), "title" to title, "thumbnailUrl" to thumbnail, "durationMs" to duration,
        "playbackVerified" to Json.Bool(false), "downloadable" to Json.Bool(false),
    )))
internal fun jsonFor(request: VerificationHttpRequest, outcome: String = "PAGE_EVIDENCE", evidence: String = "HTML_VIDEO"): String {
    val fields = StrictJson.read(request.body).objectValue()
    return responseJson(fields.getValue("requestId").stringOrNull()!!, fields.getValue("generation").stringOrNull()!!,
        fields.getValue("candidateUrl").stringOrNull()!!, outcome, evidence,
        title = Json.Nil, thumbnail = Json.Nil, duration = Json.Nil)
}
internal class TestTransport(private val action: (VerificationHttpRequest) -> VerificationHttpResponse = { request ->
    VerificationHttpResponse(200, request.url, mapOf("Content-Type" to listOf("application/json; charset=utf-8")),
        ByteArrayInputStream(jsonFor(request).toByteArray()))
}) : VerificationTransport {
    val requests = CopyOnWriteArrayList<VerificationHttpRequest>()
    val cancellations = AtomicInteger()
    override fun create(request: VerificationHttpRequest): VerificationHttpCall {
        requests += request
        return object : VerificationHttpCall {
            override fun execute() = action(request)
            override fun cancel() { cancellations.incrementAndGet() }
        }
    }
}
internal fun client(transport: VerificationTransport, clock: TestClock = TestClock(), timer: TestTimer = TestTimer(),
    isCurrent: () -> Boolean = { true }, dynamic: Boolean = false) =
    VerificationServiceClient(endpoint(), GENERATION, transport, clock, isCurrent, dynamic, timer)
internal fun protocolFails(code: VerificationError = VerificationError.INVALID_RESPONSE, action: () -> Unit) {
    try { action(); fail("Expected $code") } catch (failure: VerificationFailure) { assertEquals(code, failure.code) }
}
internal fun discoveryFails(code: DiscoveryError, action: () -> Unit) {
    try { action(); fail("Expected $code") } catch (failure: DiscoveryFailure) { assertEquals(code, failure.code) }
}

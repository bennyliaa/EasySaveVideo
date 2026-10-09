package com.easysavevideo.discovery

import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget

internal class FakeClock(var now: Long = 1000) : EpochClock { override fun nowMillis() = now }
internal class FakeTimer(private val clock: FakeClock) : DiscoveryTimer {
    private data class Event(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private val events = mutableListOf<Event>()
    override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
        val event = Event(clock.now + delayMillis, action); events += event
        return CancellationHandle { event.cancelled = true }
    }
    fun advance(millis: Long) {
        val end = clock.now + millis
        while (true) {
            val event = events.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            events.remove(event); clock.now = event.at; event.action()
        }
        clock.now = end
    }
}
internal class StubProvider(override var descriptor: ProviderDescriptor = ProviderDescriptor("source", "1")) : VideoSearchProvider {
    override fun search(request: SearchRequest, operation: DiscoveryOperation): ProviderPage = error("Fixture is completed through its fake runtime")
}
internal class FakeRuntime : ProviderRuntime {
    data class Call(val provider: VideoSearchProvider, val request: SearchRequest, val operation: DiscoveryOperation,
                    val callback: (ProviderOutcome) -> Unit, var cancelled: Boolean = false, var delivered: Boolean = false)
    val calls = mutableListOf<Call>()
    override fun execute(provider: VideoSearchProvider, request: SearchRequest, operation: DiscoveryOperation, callback: (ProviderOutcome) -> Unit): CancellationHandle {
        val call = Call(provider, request, operation, callback); calls += call
        return CancellationHandle { call.cancelled = true; operation.cancellation.cancel() }
    }
    fun complete(index: Int, vararg ids: Int, nextCursor: String? = null, exhausted: Boolean = true) {
        val call = calls[index]; call.delivered = true
        call.callback(ProviderOutcome.Success(ValidatedProviderPage(ids.map { result(it, call.provider.descriptor.id) }, nextCursor, null, exhausted)))
    }
    fun fail(index: Int, retryable: Boolean = false, code: DiscoveryError = DiscoveryError.PROVIDER_FAILURE) {
        calls[index].delivered = true; calls[index].callback(ProviderOutcome.Failure(code, retryable))
    }
}
internal fun result(id: Int, provider: String = "source") = ResultNormalizer.normalize(
    RawVideoResult("https://video.example/watch?id=$id", true, title = "Title $id", sourceNamespace = "video.example", sourceVideoId = id.toString()), provider)!!
internal fun operation(deadline: Long = 16000): DiscoveryOperation {
    val cancellation = DiscoveryCancellation()
    return DiscoveryOperation(OperationContext("test", deadline, cancellation, 2, resourceBudget = ResourceBudget(2)), cancellation)
}

package com.easysavevideo.app.discovery

/** Process-session consent is shared across owners. Endpoint changes fence every attached owner first. */
class VerificationConfigurationHub {
    private data class Subscriber(val id: Long, val fence: () -> Unit,
        val receive: (VerificationConfiguration) -> Unit)
    private val subscribers = linkedMapOf<Long, Subscriber>()
    private var nextId = 0L
    val controller = VerificationConfigurationController(::fenceAll, ::publish)
    fun subscribe(fence: () -> Unit, receive: (VerificationConfiguration) -> Unit): AutoCloseable {
        check(nextId != Long.MAX_VALUE)
        val id = ++nextId
        val subscriber = Subscriber(id, fence, receive)
        subscribers[id] = subscriber
        try {
            if (controller.busy) fence()
            receive(controller.current)
        } catch (failure: Exception) { subscribers.remove(id); throw failure }
        return AutoCloseable { subscribers.remove(id) }
    }
    private fun fenceAll() {
        var failure = false
        subscribers.values.toList().forEach {
            if (subscribers[it.id] === it) try { it.fence() } catch (_: Exception) { failure = true }
        }
        check(!failure) { "CONFIG_OWNER" }
    }
    private fun publish(value: VerificationConfiguration) {
        subscribers.values.toList().forEach {
            if (subscribers[it.id] === it) try { it.receive(value) } catch (_: Exception) {
                try { it.fence() } catch (_: Exception) { }
                subscribers.remove(it.id)
            }
        }
    }
}

/** Used only from the Android main thread; no network or disk access during initialization. */
object VerificationConfigurationOwner { val hub = VerificationConfigurationHub() }

package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.SearxngConfigurationStore

/** Factory injection keeps lifecycle tests deterministic without Android Looper or extra workers. */
fun interface SearxngConfigurationControllerFactory {
    fun create(store: SearxngConfigurationStore, onStarting: () -> Unit, onFinished: () -> Unit,
        onLoaded: (SearxngConfigurationState) -> Unit): SearxngConfigurationController
}

/**
 * Process-lived configuration owner. Activities own subscriptions, never the shared worker.
 * Subscribe/unsubscribe and all subscriber callbacks run on main, like controller UI controls.
 * Each accepted write fences every current discovery owner before disk work is enqueued.
 * Completion targets current subscribers, including owners attached after the write was accepted.
 */
class SearxngConfigurationHub(
    store: SearxngConfigurationStore,
    private val maximumSubscribers: Int = 8,
    controllerFactory: SearxngConfigurationControllerFactory = SearxngConfigurationControllerFactory { value, starting, finished, loaded ->
        SearxngConfigurationController(value, onChangeStarting = starting, onChangeFinished = finished, onLoaded = loaded)
    },
) {
    private class Subscriber(val id: Long, var fence: (() -> Unit)?, var onState: ((SearxngConfigurationState) -> Unit)?)
    private val lock = Any()
    private val subscribers = linkedMapOf<Long, Subscriber>()
    private var nextId = 0L
    @Volatile private var writePending = false
    init { require(maximumSubscribers in 1..32) }

    /** Only the process owner may close this controller; Activities close their subscription. */
    val controller: SearxngConfigurationController = controllerFactory.create(store, ::writeStarting,
        { publishCurrent() }, { publishCurrent() })

    fun subscribe(onFence: () -> Unit, onState: (SearxngConfigurationState) -> Unit): AutoCloseable {
        val fence = onFence
        controller.invalidateClaims()
        val subscriber = synchronized(lock) {
            if (subscribers.size >= maximumSubscribers || nextId == Long.MAX_VALUE) null
            else Subscriber(++nextId, fence, onState).also { subscribers[it.id] = it }
        }
        if (subscriber == null) {
            // An unregistered discovery owner must never use a generation it cannot observe.
            try { fence() } catch (_: Exception) { }
            try { onState(SearxngConfigurationState(SearxngConfigurationStatus.UNAVAILABLE,
                notice = "来源配置订阅暂不可用；此页面的 SearXNG 来源停用。")) } catch (_: Exception) { }
            return AutoCloseable { }
        }
        val current = controller.state
        try {
            if (writePending) fence()
            onState(current)
        } catch (_: Exception) {
            try { fence() } catch (_: Exception) { }
            detach(subscriber.id)
            return AutoCloseable { }
        }
        if (!current.loaded && !current.busy && controller.reload()) {
            // The initial reader is shared; a second Activity cannot start another worker/load.
            if (controller.state.busy) publishCurrent()
        }
        val id = subscriber.id
        // This handle deliberately captures neither Activity callback nor Subscriber object.
        return AutoCloseable { detach(id) }
    }

    private fun detach(id: Long) {
        val removed = synchronized(lock) { subscribers.remove(id)?.also { it.fence = null; it.onState = null } }
        if (removed != null) controller.invalidateClaims()
    }

    private fun snapshot(): List<Subscriber> = synchronized(lock) { subscribers.values.toList() }
    private fun currentFence(subscriber: Subscriber): (() -> Unit)? = synchronized(lock) {
        if (subscribers[subscriber.id] === subscriber) subscriber.fence else null
    }
    private fun currentDelivery(subscriber: Subscriber): ((SearxngConfigurationState) -> Unit)? = synchronized(lock) {
        if (subscribers[subscriber.id] === subscriber) subscriber.onState else null
    }

    private fun writeStarting() {
        writePending = true
        var failed = false
        // Do not let one broken owner prevent other currently attached owners from being fenced.
        snapshot().forEach { subscriber ->
            try { currentFence(subscriber)?.invoke() } catch (_: Exception) { failed = true }
        }
        val pending = controller.state
        snapshot().forEach { subscriber ->
            try { currentDelivery(subscriber)?.invoke(pending) } catch (_: Exception) { failed = true }
        }
        if (failed) throw IllegalStateException("CONFIG_OWNER")
    }

    private fun publishCurrent() {
        if (!controller.state.busy) writePending = false
        snapshot().forEach { subscriber ->
            // Read again for each delivery: a reentrant subscriber may have accepted a new write.
            try { currentDelivery(subscriber)?.invoke(controller.state) } catch (_: Exception) {
                // A broken/detached UI callback cannot prevent delivery to the remaining owners.
                // Re-fence in case its callback failed after partially reopening a coordinator.
                try { currentFence(subscriber)?.invoke() } catch (_: Exception) { }
                detach(subscriber.id)
            }
        }
    }
}

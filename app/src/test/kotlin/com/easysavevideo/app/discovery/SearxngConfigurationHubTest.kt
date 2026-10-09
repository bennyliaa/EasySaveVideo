package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.SearxngConfigurationRead
import com.easysavevideo.app.storage.SearxngConfigurationStore
import com.easysavevideo.app.storage.SearxngConfigurationWrite
import com.easysavevideo.app.storage.readableConfiguration
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class SearxngConfigurationHubTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() = tasks.removeFirst().run()
    }
    private class Store(initial: SearxngSourceConfiguration?) : SearxngConfigurationStore {
        var reads = 0; var writes = 0; var failWrite = false
        var content: SearxngConfigurationRead = initial?.let { SearxngConfigurationRead.Present(it) } ?: SearxngConfigurationRead.Missing
        override fun load(): SearxngConfigurationRead { reads++; return content }
        override fun replace(expectedGeneration: String, replacement: SearxngSourceConfiguration): SearxngConfigurationWrite {
            writes++
            if (failWrite) return SearxngConfigurationWrite.Unavailable
            if (content.readableConfiguration()?.generation != expectedGeneration) return SearxngConfigurationWrite.Conflict(content)
            content = SearxngConfigurationRead.Present(replacement)
            return SearxngConfigurationWrite.Committed(replacement)
        }
    }
    private class Observer {
        var fences = 0
        val states = mutableListOf<SearxngConfigurationState>()
        var active: SearxngSourceConfiguration? = null
        fun fence() { fences++; active = null }
        fun receive(state: SearxngConfigurationState) {
            states += state
            if (state.busy || !state.loaded) return
            active = if (state.unavailable) null else state.config
        }
    }
    private class Harness(initial: SearxngSourceConfiguration? = null, maximumSubscribers: Int = 8) {
        val store = Store(initial); val io = Queue(); val main = Queue()
        var creations = 0
        val hub = SearxngConfigurationHub(store, maximumSubscribers,
            SearxngConfigurationControllerFactory { value, starting, finished, loaded ->
                creations++
                SearxngConfigurationController(value, onChangeStarting = starting, onChangeFinished = finished,
                    onLoaded = loaded, io = io, publish = { main.execute(it) })
            })
        fun attach(observer: Observer) = hub.subscribe(observer::fence, observer::receive)
        fun complete() { io.next(); main.next() }
    }
    private fun enabled() = (SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-1",
        "https://search.example.org/search", true, true) as SearxngConfigurationEdit.Accepted).config
    private fun disable(h: Harness) = h.hub.controller.replace(h.hub.controller.beginEdit("searxng-1")!!,
        "https://search.example.org/search", false, false)

    @Test fun initialSharedReadAndUnchangedReloadDoNotClaimAWriteFence() {
        val h = Harness(enabled()); val first = Observer(); val second = Observer()
        h.attach(first); h.attach(second)
        assertEquals(0, first.fences); assertEquals(0, second.fences)
        assertTrue(first.states.last().busy); assertTrue(second.states.last().busy)
        assertNull(first.active); assertNull(second.active); assertEquals(1, h.io.tasks.size)
        h.complete()
        assertEquals(0, first.fences); assertEquals(0, second.fences)
        assertEquals(1, first.active!!.enabledEndpoints.size)
        assertTrue(h.hub.controller.reload()); h.complete()
        assertEquals(0, first.fences); assertEquals(0, second.fences)
    }

    @Test fun acceptedWriteSurvivesInitiatorDetachAndFencesReplacementUntilDisableCommits() {
        val h = Harness(enabled()); val old = Observer(); val subscription = h.attach(old); h.complete()
        assertEquals(1, old.active!!.enabledEndpoints.size)
        assertTrue(disable(h)); assertNull(old.active); assertTrue(h.hub.controller.state.busy)
        subscription.close(); val oldDeliveries = old.states.size
        val replacement = Observer(); h.attach(replacement)
        assertEquals(1, replacement.fences); assertNull(replacement.active)
        assertTrue(replacement.states.single().busy)
        assertEquals(1, h.io.tasks.size); assertEquals(1, h.creations); assertEquals(1, h.store.reads)
        h.complete()
        assertEquals(oldDeliveries, old.states.size)
        assertFalse(replacement.states.last().busy); assertTrue(replacement.active!!.enabledEndpoints.isEmpty())
        assertEquals(1, h.store.writes); assertTrue(h.io.tasks.isEmpty())
    }
    @Test fun acceptedWriteFencesEveryAttachedOwnerSynchronouslyBeforeAnyStorageWrite() {
        val h = Harness(enabled()); val first = Observer(); val second = Observer()
        h.attach(first); h.complete(); h.attach(second)
        val firstBaseline = first.fences; val secondBaseline = second.fences
        assertTrue(disable(h))
        assertEquals(firstBaseline + 1, first.fences); assertEquals(secondBaseline + 1, second.fences)
        assertNull(first.active); assertNull(second.active); assertEquals(0, h.store.writes)
        assertTrue(first.states.last().busy); assertTrue(second.states.last().busy)
        h.complete()
        assertTrue(first.active!!.enabledEndpoints.isEmpty()); assertEquals(first.active, second.active)
    }
    @Test fun subscribeAndUnsubscribeRetireUiClaimsWithoutCancellingAcceptedPublication() {
        val h = Harness(enabled()); val first = Observer(); val initial = h.attach(first); h.complete()
        val oldClaim = h.hub.controller.beginEdit("searxng-1")!!
        val second = h.attach(Observer())
        assertFalse(h.hub.controller.isCurrent(oldClaim))
        val newer = h.hub.controller.beginReset()!!; second.close()
        assertFalse(h.hub.controller.isCurrent(newer))
        assertTrue(disable(h)); initial.close()
        h.attach(Observer()).close(); h.complete()
        assertFalse(h.hub.controller.state.busy)
        assertTrue(h.hub.controller.state.config!!.enabledEndpoints.isEmpty())
        assertFalse(h.hub.controller.replace(oldClaim, "https://search.example.org/search", true, true))
        assertEquals(1, h.store.writes)
    }
    @Test fun completionWithNoSubscribersIsAvailableToNextActivityWithoutReloadOrNewWorker() {
        val h = Harness(enabled()); val old = h.attach(Observer()); h.complete()
        assertTrue(disable(h)); old.close(); h.complete()
        val replacement = Observer(); h.attach(replacement)
        assertTrue(replacement.active!!.enabledEndpoints.isEmpty()); assertFalse(replacement.states.single().busy)
        assertEquals(1, h.store.reads); assertEquals(1, h.creations); assertTrue(h.io.tasks.isEmpty())
    }
    @Test fun pendingInitialLoadIsSharedAcrossActivityReplacement() {
        val h = Harness(enabled()); val first = Observer(); val old = h.attach(first)
        assertEquals(1, h.io.tasks.size); old.close(); val replacement = Observer(); h.attach(replacement)
        assertEquals(1, h.io.tasks.size); assertTrue(replacement.states.single().busy); assertNull(replacement.active)
        h.complete(); assertEquals(1, replacement.active!!.enabledEndpoints.size)
        assertEquals(1, h.store.reads); assertEquals(1, h.creations)
    }
    @Test fun failureAndExplicitReloadReachAllCurrentSubscribersAfterHandoff() {
        val h = Harness(enabled()); val original = h.attach(Observer()); h.complete()
        h.store.failWrite = true; assertTrue(disable(h)); original.close()
        val first = Observer(); val second = Observer(); h.attach(first); h.attach(second); h.complete()
        assertTrue(first.states.last().unavailable); assertTrue(second.states.last().unavailable)
        assertNull(first.active); assertNull(second.active); assertFalse(h.hub.controller.state.busy)
        h.store.failWrite = false; assertTrue(h.hub.controller.reload()); h.complete()
        assertEquals(1, first.active!!.enabledEndpoints.size); assertEquals(first.active, second.active)
        assertEquals(2, h.store.reads); assertEquals(1, h.creations)
    }
    @Test fun subscriptionCapFailsClosedAndDetachedSlotsCanBeReused() {
        val h = Harness(enabled(), maximumSubscribers = 1); val first = h.attach(Observer()); h.complete()
        val rejected = Observer(); val rejectedHandle = h.attach(rejected)
        assertEquals(1, rejected.fences); assertTrue(rejected.states.single().unavailable); assertNull(rejected.active)
        rejectedHandle.close(); first.close()
        val replacement = Observer(); val accepted = h.attach(replacement)
        assertEquals(1, replacement.active!!.enabledEndpoints.size)
        val claim = h.hub.controller.beginReset()!!
        first.close(); rejectedHandle.close()
        assertTrue(h.hub.controller.isCurrent(claim)) // Repeated close cannot retire an unrelated new owner.
        accepted.close()
    }
    @Test fun closedSubscriptionNeverReceivesLaterCompletionOrReload() {
        val h = Harness(enabled()); val observer = Observer(); val subscription = h.attach(observer); h.complete()
        subscription.close(); val deliveries = observer.states.size; val fences = observer.fences
        assertTrue(disable(h)); h.complete(); assertTrue(h.hub.controller.reload()); h.complete()
        assertEquals(deliveries, observer.states.size); assertEquals(fences, observer.fences)
    }
    @Test fun reentrantTerminalDeliveryCannotReopenLaterOwnerWithStaleReadyState() {
        val h = Harness(enabled()); var armed = false
        h.hub.subscribe({}, { state ->
            if (armed && state.editable && state.config!!.slots.first().enabled) {
                armed = false; assertTrue(disable(h))
            }
        })
        h.complete(); val later = Observer(); h.attach(later)
        armed = true; assertTrue(h.hub.controller.reload()); h.complete()
        assertTrue(h.hub.controller.state.busy); assertTrue(later.states.last().busy); assertNull(later.active)
        h.complete(); assertTrue(later.active!!.enabledEndpoints.isEmpty())
    }
    @Test fun invalidSubscriberLimitIsRejectedWithoutConstructingAWorker() {
        val store = Store(null); var calls = 0
        for (limit in listOf(0, 33)) {
            try {
                SearxngConfigurationHub(store, limit, SearxngConfigurationControllerFactory { _, _, _, _ ->
                    calls++; error("Unexpected construction")
                }); fail("Expected bound rejection")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }
}

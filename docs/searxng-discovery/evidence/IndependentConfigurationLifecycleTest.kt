package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.*
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Actual Hub/controller semantics with deterministic I/O and main queues, no Android runtime. */
class IndependentConfigurationLifecycleTest {
    private class Queue : AbstractExecutorService() {
        val jobs = ArrayDeque<Runnable>()
        private var shutdown = false
        override fun execute(command: Runnable) { check(!shutdown); jobs.addLast(command) }
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return jobs.toMutableList().also { jobs.clear() } }
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown && jobs.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun next() = jobs.removeFirst().run()
    }
    private fun enabled() = (SearxngConfigurationPolicy.replace(SearxngConfigurationPolicy.empty(), "searxng-1",
        "https://old.example.org/search", true, true) as SearxngConfigurationEdit.Accepted).config
    private class Disk(var config: SearxngSourceConfiguration) : SearxngConfigurationStore {
        var reads = 0; var writes = 0; var failWrite = false
        override fun load(): SearxngConfigurationRead { reads++; return SearxngConfigurationRead.Present(config) }
        override fun replace(expectedGeneration: String, replacement: SearxngSourceConfiguration): SearxngConfigurationWrite {
            writes++
            if (failWrite) return SearxngConfigurationWrite.Unavailable
            if (config.generation != expectedGeneration) return SearxngConfigurationWrite.Conflict(load())
            config = replacement; return SearxngConfigurationWrite.Committed(config)
        }
    }
    private class View {
        var fences = 0
        val snapshots = mutableListOf<SearxngConfigurationState>()
        var usable: SearxngSourceConfiguration? = null
        fun fence() { fences++; usable = null }
        fun accept(value: SearxngConfigurationState) {
            snapshots += value
            if (!value.busy && value.loaded) usable = if (value.unavailable) null else value.config
        }
    }
    private inner class Harness(maximumSubscribers: Int = 8) {
        val disk = Disk(enabled()); val io = Queue(); val main = Queue(); var creations = 0
        val hub = SearxngConfigurationHub(disk, maximumSubscribers, SearxngConfigurationControllerFactory { store, start, end, loaded ->
            creations++
            SearxngConfigurationController(store, onChangeStarting = start, onChangeFinished = end,
                onLoaded = loaded, io = io, publish = { main.execute(it) })
        })
        fun attach(view: View) = hub.subscribe(view::fence, view::accept)
        fun settle() { io.next(); main.next() }
        fun disable(): Boolean = hub.controller.replace(hub.controller.beginEdit("searxng-1")!!,
            "https://old.example.org/search", false, false)
    }

    @Test fun acceptedWriteDetachReplacementWaitsForDisabledTerminalSnapshot() {
        val h = Harness(); val old = View(); val first = h.attach(old); h.settle()
        assertTrue(h.disable()); assertEquals(0, h.disk.writes); assertNull(old.usable)
        first.close(); val oldDeliveries = old.snapshots.size
        val replacement = View(); h.attach(replacement)
        assertEquals(1, replacement.fences); assertNull(replacement.usable)
        assertTrue(replacement.snapshots.single().busy); assertFalse(h.io.isShutdown)
        h.settle()
        assertEquals(oldDeliveries, old.snapshots.size)
        assertTrue(replacement.usable!!.enabledEndpoints.isEmpty())
        assertEquals(h.disk.config.generation, replacement.usable!!.generation)
        assertEquals(1, h.creations); assertEquals(1, h.disk.reads)
    }
    @Test fun replacementBetweenDiskCommitAndMainPublicationCannotReadOldReadyState() {
        val h = Harness(); val old = h.attach(View()); h.settle(); assertTrue(h.disable())
        h.io.next(); assertTrue(h.disk.config.enabledEndpoints.isEmpty()); old.close()
        val replacement = View(); h.attach(replacement)
        assertEquals(1, replacement.fences); assertTrue(replacement.snapshots.single().busy)
        assertNull(replacement.usable); assertTrue(h.io.jobs.isEmpty())
        h.main.next(); assertTrue(replacement.usable!!.enabledEndpoints.isEmpty())
    }
    @Test fun commitWithNoListenersReattachesToLatestStateWithoutAnotherReadOrWorker() {
        val h = Harness(); val sub = h.attach(View()); h.settle(); assertTrue(h.disable()); sub.close(); h.settle()
        val replacement = View(); h.attach(replacement)
        assertTrue(replacement.usable!!.enabledEndpoints.isEmpty()); assertFalse(replacement.snapshots.single().busy)
        assertEquals(1, h.creations); assertEquals(1, h.disk.reads); assertTrue(h.io.jobs.isEmpty())
    }
    @Test fun detachInvalidatesClaimsButNotAcceptedOperationPublication() {
        val h = Harness(); val first = h.attach(View()); h.settle()
        val claim = h.hub.controller.beginEdit("searxng-1")!!
        val extra = h.attach(View()); assertFalse(h.hub.controller.isCurrent(claim))
        val newer = h.hub.controller.beginReset()!!; extra.close(); assertFalse(h.hub.controller.isCurrent(newer))
        assertTrue(h.disable()); first.close(); h.settle()
        assertFalse(h.hub.controller.state.busy); assertTrue(h.hub.controller.state.config!!.enabledEndpoints.isEmpty())
        assertFalse(h.hub.controller.replace(claim, "https://old.example.org/search", true, true))
        assertEquals(1, h.disk.writes)
    }
    @Test fun everyCurrentOwnerIsFencedBeforeDiskWriteAndOnlyOneInitialReaderExists() {
        val h = Harness(); val a = View(); val b = View(); h.attach(a); h.attach(b)
        assertEquals(1, h.io.jobs.size); assertEquals(0, a.fences); assertEquals(0, b.fences)
        h.settle(); assertTrue(h.disable())
        assertEquals(1, a.fences); assertEquals(1, b.fences); assertNull(a.usable); assertNull(b.usable)
        assertEquals(0, h.disk.writes); h.settle(); assertEquals(a.usable, b.usable)
    }
    @Test fun failedWriteAfterDetachLeavesReplacementUnavailableUntilExplicitReload() {
        val h = Harness(); val sub = h.attach(View()); h.settle(); h.disk.failWrite = true
        assertTrue(h.disable()); sub.close(); val replacement = View(); h.attach(replacement); h.settle()
        assertTrue(replacement.snapshots.last().unavailable); assertNull(replacement.usable)
        assertTrue(h.hub.controller.reload()); h.settle()
        assertEquals(h.disk.config.generation, replacement.usable!!.generation)
        assertEquals(1, h.creations); assertEquals(2, h.disk.reads)
    }
    @Test fun rejectedSubscriberFailsClosedWithoutClosingSharedOwner() {
        val h = Harness(1); val sub = h.attach(View()); h.settle()
        val overflow = View(); h.attach(overflow)
        assertEquals(1, overflow.fences); assertNull(overflow.usable); assertTrue(overflow.snapshots.single().unavailable)
        sub.close(); val accepted = View(); h.attach(accepted)
        assertEquals(1, accepted.usable!!.enabledEndpoints.size); assertFalse(h.io.isShutdown)
    }
    @Test fun repeatedDetachCannotInvalidateAnUnrelatedNewClaim() {
        val h = Harness(); val old = h.attach(View()); h.settle(); old.close()
        h.attach(View()); val claim = h.hub.controller.beginReset()!!; old.close()
        assertTrue(h.hub.controller.isCurrent(claim))
    }
    @Test fun subscriberAttachedReentrantlyDuringFenceAlsoReceivesPendingFence() {
        val h = Harness(); var armed = false; val replacement = View()
        h.hub.subscribe({ if (armed) { armed = false; h.attach(replacement) } }, {})
        h.settle(); armed = true; assertTrue(h.disable())
        assertEquals(1, replacement.fences); assertNull(replacement.usable)
        assertTrue(replacement.snapshots.all { it.busy }); h.settle()
        assertTrue(replacement.usable!!.enabledEndpoints.isEmpty())
    }
}

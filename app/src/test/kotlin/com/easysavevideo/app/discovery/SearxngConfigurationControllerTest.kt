package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.SearxngConfigurationRead
import com.easysavevideo.app.storage.SearxngConfigurationStore
import com.easysavevideo.app.storage.SearxngConfigurationWrite
import com.easysavevideo.app.storage.readableConfiguration
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class SearxngConfigurationControllerTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() = tasks.removeFirst().run()
    }
    private class Store : SearxngConfigurationStore {
        var reads = 0; var writes = 0
        var content: SearxngConfigurationRead = SearxngConfigurationRead.Missing
        var failWrite = false
        override fun load(): SearxngConfigurationRead { reads++; return content }
        override fun replace(expectedGeneration: String, replacement: SearxngSourceConfiguration): SearxngConfigurationWrite {
            writes++
            if (failWrite) return SearxngConfigurationWrite.Unavailable
            if (content.readableConfiguration()?.generation != expectedGeneration) return SearxngConfigurationWrite.Conflict(content)
            content = SearxngConfigurationRead.Present(replacement)
            return SearxngConfigurationWrite.Committed(replacement)
        }
    }
    private class Harness {
        val store = Store(); val io = Queue(); val main = Queue(); val events = mutableListOf<String>()
        val controller = SearxngConfigurationController(store,
            onChangeStarting = { events += "starting" },
            onCommitted = { config -> assertEquals(config, store.content.readableConfiguration()); events += "committed" },
            onChangeFinished = { events += "finished" }, onLoaded = { events += "loaded" },
            io = io, publish = { main.execute(it) })
        fun load() { assertTrue(controller.reload()); io.next(); main.next() }
        fun commit() { io.next(); main.next() }
    }
    private val endpoint = "https://search.example.org/search"

    @Test fun creationIsFilesystemLazyAndLoadPublicationIsExplicit() {
        val h = Harness()
        assertEquals(0, h.store.reads); assertEquals(SearxngConfigurationStatus.NOT_LOADED, h.controller.state.status)
        assertNull(h.controller.beginEdit("searxng-1"))
        assertTrue(h.controller.reload()); assertTrue(h.controller.state.busy); assertEquals(0, h.store.reads)
        h.io.next(); assertEquals(1, h.store.reads); assertFalse(h.controller.state.loaded)
        h.main.next(); assertTrue(h.controller.state.editable); assertTrue(h.controller.state.config!!.enabledEndpoints.isEmpty())
        assertEquals(listOf("loaded"), h.events)
    }
    @Test fun commitIsFencedBeforeEnqueueAndPublishedOnlyAfterDurableCallback() {
        val h = Harness(); h.load(); h.events.clear()
        val claim = h.controller.beginEdit("searxng-1")!!
        assertTrue(h.controller.replace(claim, endpoint, true, true))
        assertEquals(listOf("starting"), h.events); assertEquals(0, h.store.writes)
        assertTrue(h.controller.state.busy); assertFalse(h.controller.isCurrent(claim))
        h.io.next(); assertEquals(listOf("starting", "committed"), h.events)
        assertTrue(h.controller.state.busy); assertEquals(0L, h.controller.state.config!!.revision)
        h.main.next(); assertEquals(listOf("starting", "committed", "finished"), h.events)
        assertEquals(1L, h.controller.state.config!!.revision); assertTrue(h.controller.state.editable)
    }
    @Test fun invalidUnacknowledgedAndUnchangedEditsDoNotWriteOrFence() {
        val h = Harness(); h.load(); h.events.clear()
        val claim = h.controller.beginEdit("searxng-1")!!
        assertFalse(h.controller.replace(claim, endpoint, true, false))
        assertFalse(h.controller.replace(claim, "http://search.example.org/search", true, true))
        assertFalse(h.controller.replace(claim, "", false, false))
        assertEquals(0, h.store.writes); assertTrue(h.io.tasks.isEmpty()); assertTrue(h.events.isEmpty())
        assertTrue(h.controller.isCurrent(claim))
    }
    @Test fun olderDialogsCannotOverwriteOtherSlotsOrCompletedReset() {
        val h = Harness(); h.load()
        val stale = h.controller.beginEdit("searxng-2")!!
        val claim = h.controller.beginEdit("searxng-1")!!
        assertTrue(h.controller.replace(claim, endpoint, true, true)); h.commit()
        assertFalse(h.controller.replace(stale, endpoint, true, true))
        assertEquals(1, h.store.writes)
        val older = h.controller.beginEdit("searxng-1")!!
        val reset = h.controller.beginReset()!!
        assertTrue(h.controller.reset(reset)); h.commit()
        assertFalse(h.controller.replace(older, endpoint, true, true))
        assertEquals(2, h.store.writes); assertTrue(h.controller.state.config!!.enabledEndpoints.isEmpty())
    }
    @Test fun reloadAndForeignControllerClaimsCannotBeReplayed() {
        val first = Harness(); val second = Harness(); first.load(); second.load()
        val stale = first.controller.beginEdit("searxng-1")!!
        first.load()
        assertFalse(first.controller.replace(stale, endpoint, true, true))
        assertFalse(second.controller.replace(stale, endpoint, true, true))
        assertFalse(first.controller.reset(first.controller.beginEdit("searxng-1")!!))
        assertFalse(first.controller.replace(first.controller.beginReset()!!, endpoint, true, true))
        assertEquals(0, first.store.writes); assertEquals(0, second.store.writes)
    }
    @Test fun malformedAndUnavailableStorageNeverBecomeEmptyWritableDefaults() {
        for (read in listOf(SearxngConfigurationRead.Malformed, SearxngConfigurationRead.Unavailable)) {
            val h = Harness(); h.store.content = read; h.load()
            assertTrue(h.controller.state.loaded); assertTrue(h.controller.state.unavailable)
            assertNull(h.controller.state.config); assertNull(h.controller.beginReset()); assertNull(h.controller.beginEdit("searxng-1"))
            assertEquals(0, h.store.writes)
        }
    }
    @Test fun failedWriteAndConflictingStoredGenerationBothFailClosedAndReleaseFence() {
        for (conflict in listOf(false, true)) {
            val h = Harness(); h.load(); h.events.clear()
            val claim = h.controller.beginEdit("searxng-1")!!
            if (conflict) h.store.content = SearxngConfigurationRead.Present(SearxngConfigurationPolicy.empty(3)) else h.store.failWrite = true
            assertTrue(h.controller.replace(claim, endpoint, true, true)); h.commit()
            assertTrue(h.controller.state.unavailable); assertNull(h.controller.state.config); assertFalse(h.controller.state.busy)
            assertEquals(listOf("starting", "finished"), h.events)
            assertEquals(if (conflict) 3L else 0L, h.store.content.readableConfiguration()!!.revision)
        }
    }
    @Test fun closeMakesPendingAndOldDialogsUnusable() {
        val h = Harness(); h.load(); val claim = h.controller.beginEdit("searxng-1")!!
        h.controller.close()
        assertFalse(h.controller.replace(claim, endpoint, true, true)); assertFalse(h.controller.reload())
        assertNull(h.controller.state.config); assertEquals(0, h.store.writes)
    }
}

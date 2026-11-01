package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.SearxngConfigurationRead
import com.easysavevideo.app.storage.SearxngConfigurationStore
import com.easysavevideo.app.storage.SearxngConfigurationWrite
import com.easysavevideo.app.storage.readableConfiguration
import com.easysavevideo.searxng.SearxngResultFormat
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
        assertNull(h.controller.beginAdd()); assertNull(h.controller.beginEdit("searxng-1"))
        assertTrue(h.controller.reload()); assertTrue(h.controller.state.busy); assertEquals(0, h.store.reads)
        h.io.next(); assertEquals(1, h.store.reads); assertFalse(h.controller.state.loaded)
        h.main.next(); assertTrue(h.controller.state.editable); assertTrue(h.controller.state.config!!.slots.isEmpty())
        assertNotNull(h.controller.beginAdd()); assertNull(h.controller.beginEdit("searxng-1"))
        assertEquals(listOf("loaded"), h.events)
    }
    @Test fun addIsFencedBeforeEnqueueAndPublishedOnlyAfterDurableCallback() {
        val h = Harness(); h.load(); h.events.clear()
        val claim = h.controller.beginAdd()!!
        assertTrue(h.controller.add(claim, endpoint, true, true))
        assertEquals(listOf("starting"), h.events); assertEquals(0, h.store.writes)
        assertTrue(h.controller.state.busy); assertFalse(h.controller.isCurrent(claim))
        h.io.next(); assertEquals(listOf("starting", "committed"), h.events)
        assertTrue(h.controller.state.busy); assertEquals(0L, h.controller.state.config!!.revision)
        h.main.next(); assertEquals(listOf("starting", "committed", "finished"), h.events)
        assertEquals(1L, h.controller.state.config!!.revision); assertTrue(h.controller.state.editable)
    }
    @Test fun invalidAndUnacknowledgedAddDoNotWriteOrFence() {
        val h = Harness(); h.load(); h.events.clear()
        val claim = h.controller.beginAdd()!!
        assertFalse(h.controller.add(claim, endpoint, true, false))
        assertFalse(h.controller.add(claim, "http://search.example.org/search", true, true))
        assertFalse(h.controller.add(claim, "", false, false))
        assertEquals(0, h.store.writes); assertTrue(h.io.tasks.isEmpty()); assertTrue(h.events.isEmpty())
        assertTrue(h.controller.isCurrent(claim))
    }
    @Test fun unchangedEditDoesNotWriteOrRetireClaim() {
        val h = Harness(); h.load(); add(h); h.events.clear()
        val claim = h.controller.beginEdit("searxng-1")!!
        assertFalse(h.controller.replace(claim, endpoint, true, false))
        assertEquals(1, h.store.writes); assertTrue(h.io.tasks.isEmpty()); assertTrue(h.events.isEmpty())
        assertTrue(h.controller.isCurrent(claim))
    }
    @Test fun olderAddEditAndDeleteDialogsCannotOverwriteCommittedGenerationOrReset() {
        val h = Harness(); h.load()
        val staleAdd = h.controller.beginAdd()!!
        add(h)
        assertFalse(h.controller.add(staleAdd, "https://other.example.org/search", true, true))
        val olderEdit = h.controller.beginEdit("searxng-1")!!
        val olderDelete = h.controller.beginDelete("searxng-1")!!
        assertTrue(h.controller.reset(h.controller.beginReset()!!)); h.commit()
        assertFalse(h.controller.replace(olderEdit, endpoint, true, true))
        assertFalse(h.controller.remove(olderDelete))
        assertEquals(2, h.store.writes); assertTrue(h.controller.state.config!!.slots.isEmpty())
    }
    @Test fun actionKindsCannotBeInterchangedEvenWhenTargetsAreTheSame() {
        val h = Harness(); h.load(); add(h); h.events.clear()
        val add = h.controller.beginAdd()!!; val reset = h.controller.beginReset()!!
        val edit = h.controller.beginEdit("searxng-1")!!; val delete = h.controller.beginDelete("searxng-1")!!
        assertFalse(h.controller.reset(add)); assertFalse(h.controller.add(reset, endpoint, true, true))
        assertFalse(h.controller.remove(edit)); assertFalse(h.controller.replace(delete, endpoint, false, false))
        assertFalse(h.controller.add(edit, endpoint, true, true)); assertFalse(h.controller.reset(delete))
        assertFalse(h.controller.replace(add, endpoint, true, true)); assertFalse(h.controller.remove(reset))
        assertEquals(1, h.store.writes); assertTrue(h.io.tasks.isEmpty()); assertTrue(h.events.isEmpty())
        listOf(add, reset, edit, delete).forEach { assertTrue(h.controller.isCurrent(it)) }
    }
    @Test fun reloadAndForeignControllerClaimsCannotBeReplayed() {
        val first = Harness(); val second = Harness(); first.load(); second.load()
        val stale = first.controller.beginAdd()!!
        first.load()
        assertFalse(first.controller.add(stale, endpoint, true, true))
        assertFalse(second.controller.add(stale, endpoint, true, true))
        assertEquals(0, first.store.writes); assertEquals(0, second.store.writes)
    }
    @Test fun malformedAndUnavailableStorageNeverBecomeEmptyWritableDefaults() {
        for (read in listOf(SearxngConfigurationRead.Malformed, SearxngConfigurationRead.Unavailable)) {
            val h = Harness(); h.store.content = read; h.load()
            assertTrue(h.controller.state.loaded); assertTrue(h.controller.state.unavailable)
            assertNull(h.controller.state.config); assertNull(h.controller.beginReset()); assertNull(h.controller.beginAdd())
            assertNull(h.controller.beginEdit("searxng-1")); assertNull(h.controller.beginDelete("searxng-1"))
            assertEquals(0, h.store.writes)
        }
    }
    @Test fun failedWriteAndConflictingStoredGenerationBothFailClosedAndReleaseFence() {
        for (conflict in listOf(false, true)) {
            val h = Harness(); h.load(); h.events.clear()
            val claim = h.controller.beginAdd()!!
            if (conflict) h.store.content = SearxngConfigurationRead.Present(SearxngConfigurationPolicy.empty(3)) else h.store.failWrite = true
            assertTrue(h.controller.add(claim, endpoint, true, true)); h.commit()
            assertTrue(h.controller.state.unavailable); assertNull(h.controller.state.config); assertFalse(h.controller.state.busy)
            assertEquals(listOf("starting", "finished"), h.events)
            assertEquals(if (conflict) 3L else 0L, h.store.content.readableConfiguration()!!.revision)
        }
    }
    @Test fun closeMakesPendingAndOldDialogsUnusable() {
        val h = Harness(); h.load(); val claim = h.controller.beginAdd()!!
        h.controller.close()
        assertFalse(h.controller.add(claim, endpoint, true, true)); assertFalse(h.controller.reload())
        assertNull(h.controller.state.config); assertEquals(0, h.store.writes)
    }
    @Test fun explicitHtmlModeUsesDurableFenceAndDoesNotEnableADisabledSource() {
        val h = Harness(); h.load(); h.events.clear()
        val claim = h.controller.beginAdd()!!
        assertTrue(h.controller.add(claim, endpoint, false, true, SearxngResultFormat.HTML_WEB))
        assertEquals(listOf("starting"), h.events); assertFalse(h.controller.isCurrent(claim))
        h.commit()
        assertEquals(listOf("starting", "committed", "finished"), h.events)
        val saved = h.controller.state.config!!
        assertEquals(SearxngResultFormat.HTML_WEB, saved.slots.first().format); assertTrue(saved.enabledEndpoints.isEmpty())
        assertFalse(h.controller.add(claim, endpoint, true, true, SearxngResultFormat.JSON_VIDEOS))
        assertEquals(1, h.store.writes)
    }
    @Test fun unacknowledgedModeChangeAndDuplicateServiceDoNotWriteOrRetireAValidClaim() {
        val h = Harness(); h.load(); add(h); h.events.clear()
        val first = h.controller.beginEdit("searxng-1")!!
        assertFalse(h.controller.replace(first, endpoint, true, false, SearxngResultFormat.HTML_WEB))
        assertTrue(h.controller.isCurrent(first))
        val second = h.controller.beginAdd()!!
        assertFalse(h.controller.add(second, endpoint, true, true, SearxngResultFormat.HTML_WEB))
        assertTrue(h.controller.isCurrent(second))
        assertEquals(1, h.store.writes); assertTrue(h.io.tasks.isEmpty()); assertTrue(h.events.isEmpty())
        assertEquals(SearxngResultFormat.JSON_VIDEOS, h.controller.state.config!!.slots.first().format)
    }
    @Test fun deleteFencesBeforeIoPreservesOtherEntriesAndCannotBeReplayedAfterReadd() {
        val h = Harness(); h.load(); add(h)
        assertTrue(h.controller.add(h.controller.beginAdd()!!, "https://other.example.org/search", acknowledged = true)); h.commit()
        val other = h.controller.state.config!!.slots[1]
        val oldEdit = h.controller.beginEdit("searxng-1")!!
        val delete = h.controller.beginDelete("searxng-1")!!
        h.events.clear()
        assertTrue(h.controller.remove(delete)); assertEquals(listOf("starting"), h.events)
        assertEquals(2, h.store.writes); assertTrue(h.controller.state.busy)
        h.commit(); assertEquals(listOf("starting", "committed", "finished"), h.events)
        assertEquals(listOf(other), h.controller.state.config!!.slots)
        assertNull(h.controller.beginEdit("searxng-1")); assertNull(h.controller.beginDelete("searxng-1"))
        add(h)
        assertEquals("searxng-3", h.controller.state.config!!.slots.last().id)
        assertFalse(h.controller.remove(delete)); assertFalse(h.controller.replace(oldEdit, endpoint, true, true))
        assertEquals(4, h.store.writes)
    }
    @Test fun deleteFailureAndCasConflictPreserveStoredConfigurationAndFinishFence() {
        for (conflict in listOf(false, true)) {
            val h = Harness(); h.load(); add(h)
            val original = h.controller.state.config!!
            val delete = h.controller.beginDelete("searxng-1")!!
            if (conflict) h.store.content = SearxngConfigurationRead.Present(
                (SearxngConfigurationPolicy.add(original, "https://other.example.org/search", acknowledged = true)
                    as SearxngConfigurationEdit.Accepted).config)
            else h.store.failWrite = true
            val stored = h.store.content
            h.events.clear(); assertTrue(h.controller.remove(delete)); h.commit()
            assertEquals(stored, h.store.content); assertTrue(h.controller.state.unavailable)
            assertNull(h.controller.state.config); assertEquals(listOf("starting", "finished"), h.events)
            assertFalse(h.controller.remove(delete))
        }
    }
    @Test fun invalidatedClaimsDoNotCancelAnAlreadyAcceptedDelete() {
        val h = Harness(); h.load(); add(h)
        assertTrue(h.controller.remove(h.controller.beginDelete("searxng-1")!!))
        h.controller.invalidateClaims(); h.commit()
        assertFalse(h.controller.state.busy); assertTrue(h.controller.state.config!!.slots.isEmpty())
        assertEquals(2, h.store.writes)
    }
    @Test fun fullListOffersNoAddClaimAndDeleteReopensCapacityWithoutReusingIds() {
        val h = Harness(); h.load()
        repeat(16) { index ->
            assertTrue(h.controller.add(h.controller.beginAdd()!!, "https://s$index.example.org/search", acknowledged = true)); h.commit()
        }
        assertNull(h.controller.beginAdd())
        assertTrue(h.controller.remove(h.controller.beginDelete("searxng-4")!!)); h.commit()
        assertNotNull(h.controller.beginAdd()); add(h)
        assertEquals("searxng-17", h.controller.state.config!!.slots.last().id)
    }
    private fun add(h: Harness) {
        assertTrue(h.controller.add(h.controller.beginAdd()!!, endpoint, true, true)); h.commit()
    }
}

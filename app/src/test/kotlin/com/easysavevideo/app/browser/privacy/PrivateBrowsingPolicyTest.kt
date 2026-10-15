package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivateBrowsingPolicyTest {
    private fun name(n: Int = 1) = PrivateProfileName.parse("esv_private_v1_00000000-0000-0000-0000-${n.toString().padStart(12, '0')}")!!
    private class Backend : PrivateProfileBackend {
        var supported = PrivateProfileCapabilities(true, true, true)
        val calls = mutableListOf<String>()
        var cleared: (() -> Unit)? = null
        var failFence = false
        override fun capabilities() = supported
        override fun create(name: PrivateProfileName) { calls += "create" }
        override fun fence(name: PrivateProfileName) { calls += "fence"; if (failFence) error("synthetic") }
        override fun clearData(name: PrivateProfileName, done: () -> Unit) { calls += "clear"; cleared = done }
        override fun retireUnloadedMetadata(name: PrivateProfileName) { calls += "retire-unloaded" }
    }
    private class Journal : PrivateProfileJournal {
        var stored = emptyList<PrivateProfileMarker>()
        var defer = false
        var queued: (() -> Unit)? = null
        var success = true
        override fun read(done: (List<PrivateProfileMarker>?) -> Unit) { done(stored) }
        override fun write(markers: List<PrivateProfileMarker>, done: (Boolean) -> Unit) {
            val action = { if (success) stored = markers.toList(); done(success) }
            if (defer) queued = action else action()
        }
    }
    private class Clock : PrivatePolicyScheduler {
        class Ticket(val action: () -> Unit, var live: Boolean = true)
        val tickets = mutableListOf<Ticket>()
        override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
            val ticket = Ticket(action); tickets += ticket; return { ticket.live = false }
        }
        fun fire() { tickets.toList().filter { it.live }.forEach { it.live = false; it.action() } }
    }
    private inner class Fixture {
        val backend = Backend(); val journal = Journal(); val clock = Clock()
        val policy = PrivateBrowsingPolicy(backend, journal, clock, { name() })
        fun ready() { policy.recover(); assertEquals(PrivateBrowsingPhase.READY, policy.status.phase) }
        fun open(): PrivateBrowsingSession {
            var session: PrivateBrowsingSession? = null
            assertTrue(policy.open { session = it }); return session!!
        }
    }
    @Test fun allThreeCapabilitiesRequiredForOpening() {
        for (bits in 0..7) {
            val f = Fixture(); f.backend.supported = PrivateProfileCapabilities(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            f.policy.recover()
            assertEquals(bits == 7, f.policy.status.phase == PrivateBrowsingPhase.READY)
            if (bits != 7) { assertFalse(f.policy.open {}); assertFalse(f.backend.calls.contains("create")) }
        }
    }
    @Test fun durableMarkerPrecedesProfileCreation() {
        val f = Fixture(); f.ready(); f.journal.defer = true
        var result: PrivateBrowsingSession? = null
        assertTrue(f.policy.open { result = it }); assertNull(result); assertTrue(f.backend.calls.isEmpty())
        f.journal.queued!!(); assertNotNull(result); assertEquals(PrivateMarkerPhase.ACTIVE, f.journal.stored.single().phase)
        assertEquals(listOf("create"), f.backend.calls)
    }
    @Test fun failedMarkerWriteCreatesNothingAndSettlesOpening() {
        val f = Fixture(); f.ready(); f.journal.success = false
        var completions = 0
        assertTrue(f.policy.open { assertNull(it); completions++ })
        assertEquals(1, completions); assertTrue(f.backend.calls.isEmpty()); assertEquals(PrivateBrowsingPhase.QUARANTINED, f.policy.status.phase)
    }
    @Test fun lateOpeningWriteAfterTimeoutCannotCreateProfile() {
        val f = Fixture(); f.ready(); f.journal.defer = true
        var completions = 0; f.policy.open { assertNull(it); completions++ }
        f.clock.fire(); f.journal.queued!!()
        assertEquals(1, completions); assertTrue(f.backend.calls.isEmpty()); assertEquals(PrivateBrowsingPhase.QUARANTINED, f.policy.status.phase)
    }
    @Test fun cancelOpeningDoesNotCreateOnLateWrite() {
        val f = Fixture(); f.ready(); f.journal.defer = true
        var completions = 0; f.policy.open { assertNull(it); completions++ }
        f.policy.cancelOpening(); f.journal.queued!!()
        assertEquals(1, completions); assertTrue(f.backend.calls.isEmpty())
    }
    @Test fun retirementStartsBothStopOwnersImmediatelyAndWaitsForBoth() {
        val f = Fixture(); f.ready(); val s = f.open()
        var pages: (() -> Unit)? = null; var saves: (() -> Unit)? = null; val order = mutableListOf<String>()
        assertTrue(f.policy.retire(s, { order += "pages"; pages = it }, { order += "save"; saves = it }))
        assertFalse(s.isLive()); assertFalse(f.policy.owns(s)); assertEquals(listOf("pages", "save"), order)
        assertEquals(listOf("create", "fence"), f.backend.calls)
        saves!!(); assertFalse(f.backend.calls.contains("clear")); pages!!(); assertEquals("clear", f.backend.calls.last())
        assertFalse(f.policy.retire(s, {}, {})); assertEquals(PrivateBrowsingPhase.CLEARING, f.policy.status.phase)
        f.backend.cleared!!(); assertEquals(PrivateBrowsingPhase.READY, f.policy.status.phase)
        assertEquals(PrivateMarkerPhase.DATA_CLEARED, f.journal.stored.single().phase)
    }
    @Test fun synchronousPageCallbackCannotClearBeforeSaveStopWasIssued() {
        val f = Fixture(); f.ready(); val s = f.open()
        f.policy.retire(s, { it() }, { assertFalse(f.backend.calls.contains("clear")); it() })
        assertEquals("clear", f.backend.calls.last())
    }
    @Test fun timeoutIsNotCompletionButExactLateClearCallbackCanSettle() {
        val f = Fixture(); f.ready(); val s = f.open()
        f.policy.retire(s, { it() }, { it() }); val callback = f.backend.cleared!!
        f.clock.fire(); assertEquals(PrivateBrowsingPhase.QUARANTINED, f.policy.status.phase)
        assertEquals(PrivateMarkerPhase.ACTIVE, f.journal.stored.single().phase)
        callback(); assertEquals(PrivateBrowsingPhase.READY, f.policy.status.phase)
        callback(); assertEquals(PrivateBrowsingPhase.READY, f.policy.status.phase)
    }
    @Test fun workerFenceFailureStillStartsPageAndSaveStopsButNeverClears() {
        val f = Fixture(); f.ready(); val s = f.open(); f.backend.failFence = true
        var page = false; var save = false
        f.policy.retire(s, { page = true; it() }, { save = true; it() })
        assertTrue(page && save); assertFalse(f.backend.calls.contains("clear")); assertEquals(PrivateBrowsingPhase.QUARANTINED, f.policy.status.phase)
    }
    @Test fun coldClearedMetadataRetiresWithoutLoading() {
        val f = Fixture(); f.journal.stored = listOf(PrivateProfileMarker(name(), PrivateMarkerPhase.DATA_CLEARED))
        f.ready(); assertEquals(listOf("retire-unloaded"), f.backend.calls); assertTrue(f.journal.stored.isEmpty())
    }
    @Test fun coldActiveMarkerClearsWithoutNavigationAndRetainsMetadataMarker() {
        val f = Fixture(); f.journal.stored = listOf(PrivateProfileMarker(name(), PrivateMarkerPhase.ACTIVE))
        f.policy.recover(); assertEquals(listOf("create", "fence", "clear"), f.backend.calls)
        assertEquals(PrivateBrowsingPhase.CLEARING, f.policy.status.phase)
        f.backend.cleared!!(); assertEquals(PrivateBrowsingPhase.READY, f.policy.status.phase)
        assertEquals(PrivateMarkerPhase.DATA_CLEARED, f.journal.stored.single().phase)
    }
    @Test fun muteLossDoesNotPreventAvailableCleanup() {
        val f = Fixture(); f.backend.supported = PrivateProfileCapabilities(true, true, false)
        f.journal.stored = listOf(PrivateProfileMarker(name(), PrivateMarkerPhase.ACTIVE))
        f.policy.recover(); assertEquals("clear", f.backend.calls.last()); f.backend.cleared!!()
        assertEquals(PrivateBrowsingPhase.UNAVAILABLE, f.policy.status.phase)
    }
    @Test fun forgedDefaultOrUrlNamesAreRejected() {
        listOf("Default", "https://private.example/", "../other", "esv_private_v1_bad").forEach { assertNull(PrivateProfileName.parse(it)) }
        assertFalse(name().toString().contains("0000"))
    }
    @Test fun unpublishedResourceReleasedExactlyOnceOnBindingFailure() {
        val item = Any(); var released = 0; val original = IllegalStateException("synthetic")
        val thrown = assertThrows(IllegalStateException::class.java) {
            createPrivateOwnedResource<Any>({ item }, { throw original }, { assertSame(item, it); released++ })
        }
        assertSame(original, thrown); assertEquals(1, released)
    }
    @Test fun successfulResourceOwnershipIsTransferredWithoutEarlyRelease() {
        val item = Any(); var released = 0
        assertSame(item, createPrivateOwnedResource({ item }, {}, { released++ }))
        assertEquals(0, released)
    }
    @Test fun exhaustedRetirementCapacityIsVisibleInsteadOfReady() {
        val f = Fixture()
        f.journal.stored = (1..PrivateBrowsingPolicy.MAX_MARKERS).map { PrivateProfileMarker(name(it), PrivateMarkerPhase.ACTIVE) }
        f.policy.recover()
        repeat(PrivateBrowsingPolicy.MAX_MARKERS) { f.backend.cleared!!() }
        assertEquals(PrivateBrowsingPhase.RETIREMENT_REQUIRED, f.policy.status.phase)
        assertFalse(f.policy.open {})
    }
}

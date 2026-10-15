package com.easysavevideo.app.browser

import com.easysavevideo.app.browser.privacy.PrivateBrowsingSession
import com.easysavevideo.app.browser.privacy.PrivateProfileName
import com.easysavevideo.app.browser.privacy.PrivateSessionLease
import com.easysavevideo.app.save.SaveInspectionPage
import com.easysavevideo.app.storage.BrowserSessionCodec
import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Production observation seam, real RAM navigation/session/lease, synthetic native getter.
 * Identity fixture tests the seam's callback contract; real Host wiring is source-tested separately.
 * This does not execute Android Host/WebView/Profile, presentation, cookies or platform capture.
 */
class BrowserPrivateSameDocumentUpdateTest {
    private val a = "https://private.invalid/watch?item=1#start"
    private val b = "https://private.invalid/other/path?item=2#next"
    private fun operation(id: String = "initial") = OperationContext(id, 100, CancellationSource())
    private fun session() = PrivateBrowsingSession(PrivateProfileName.random())
    private inner class Fixture(position: Int = 1, size: Int = 3, private: Boolean = true) {
        val owner = BrowserSessionController().also { it.openTab("tab", "session", private) }
        val request = owner.beginNavigation("tab", PageAddress(a), operation())
        val document = BrowserInspectionDocument()
        val memory = SystemBrowserHost.PrivateNavigation()
        val session = session()
        val lease = PrivateSessionLease(session)
        val entry = Any()
        val view = Any()
        var registeredEntry: Any? = entry
        var currentView: Any? = view
        var entryOwner = owner
        var privateOwner = owner
        var entrySession: PrivateBrowsingSession? = session
        var activeSession: PrivateBrowsingSession? = session
        var runtimeSession: PrivateBrowsingSession? = session
        var boundViewSession: PrivateBrowsingSession? = session
        var entryLease: PrivateSessionLease? = lease
        var activeLease: PrivateSessionLease? = lease
        var mappedMemory: SystemBrowserHost.PrivateNavigation? = memory
        var currentRequest: BrowserRequest? = request
        var live = true
        var suppressed = false
        var started = true
        var privateDisplayed = true
        var actual: String? = a
        var reads = 0
        init {
            repeat(size) { memory.committed("https://private.invalid/history/$it", null) }
            memory.committed(a, position)
            assertTrue(owner.acceptTitle(request, "Page title", 1))
            assertTrue(owner.acceptPageFinished(request, PageAddress(a), memory.canBack(), memory.canForward(), 2))
            document.started(); document.finished(a, a)
        }
        fun current(): Boolean = live && registeredEntry === entry && currentView === view &&
            entryOwner === owner && privateOwner === owner && entrySession === session && activeSession === session &&
            entryLease === lease && activeLease === lease && lease.isLive() && runtimeSession === session &&
            session.isLive() && boundViewSession === session && currentRequest === request && !suppressed && started &&
            mappedMemory === memory
        fun history(url: String = b, native: String? = url, reload: Boolean = false,
            current: Boolean = true, ready: Boolean = true) {
            actual = native; document.history(url, native, reload, current, ready)
        }
        fun page() = SaveInspectionPage(request.context, request.epoch, true, document.address,
            actual, view, 0, document.revision)
        fun accept(address: String? = document.address, revision: Long = document.revision,
            nativeUrl: () -> String? = { actual }): Boolean =
            BrowserSameDocumentUpdate.acceptPrivate(owner, request, document, address, revision, memory,
                viewUrl = { reads++; nativeUrl() }, stillCurrent = ::current)
    }

    @Test fun allAddressFormsAndHistoryPositionsUpdateOnlyOneExistingSlot() {
        for (url in listOf("https://private.invalid/other/path?item=1#start",
            "https://private.invalid/watch?item=2#start", "https://private.invalid/watch?item=1#next", b)) {
            for (position in 0..2) {
                val f = Fixture(position); val before = f.owner.state.selectedTab!!
                val addresses = f.memory.addresses.toList(); val oldPage = f.page()
                f.history(url); assertTrue(f.accept())
                assertEquals(before.copy(address = PageAddress(url)), f.owner.state.selectedTab)
                assertEquals(addresses.mapIndexed { i, old -> if (i == position) url else old }, f.memory.addresses)
                assertEquals(position, f.memory.index); assertEquals(3, f.memory.addresses.size)
                assertEquals(position > 0, f.owner.state.selectedTab!!.canGoBack)
                assertEquals(position < 2, f.owner.state.selectedTab!!.canGoForward)
                assertEquals(1, f.reads); assertTrue(f.owner.isCurrent(f.request)); assertFalse(oldPage.matches(f.page(), true))
            }
        }
    }

    @Test fun oneSlotAndFullBoundedHistoryNeverAppendTruncateOrChangeCap() {
        for ((size, position) in listOf(1 to 0, 128 to 0, 128 to 63, 128 to 127)) {
            val f = Fixture(position, size); val before = f.memory.addresses.toList()
            f.history(); assertTrue(f.accept())
            assertEquals(size, f.memory.addresses.size); assertEquals(position, f.memory.index)
            assertEquals(before.mapIndexed { i, old -> if (i == position) b else old }, f.memory.addresses)
        }
    }

    @Test fun refreshAndExplicitRehydrateReadBAndCreateFreshEpochAtSameRamIndex() {
        for (rehydrate in listOf(false, true)) {
            val f = Fixture(); f.history(); assertTrue(f.accept())
            val position = f.memory.index; val before = f.memory.addresses.toList()
            val next = if (rehydrate) f.owner.beginNavigation("tab", f.owner.state.selectedTab!!.address!!, operation("return"))
                else f.owner.reload("tab", operation("refresh"))!!
            assertEquals(PageAddress(b), next.address); assertEquals(f.request.epoch + 1, next.epoch)
            assertFalse(f.owner.isCurrent(f.request))
            assertTrue(f.owner.acceptPageStarted(next, next.address!!, 3))
            assertTrue(f.owner.acceptPageFinished(next, next.address!!, f.memory.canBack(), f.memory.canForward(), 4))
            f.memory.committed(next.address!!.value, position)
            assertEquals(before, f.memory.addresses); assertEquals(position, f.memory.index)
        }
    }

    @Test fun existingRamModelMismatchIsRepairedRatherThanRejected() {
        val f = Fixture(); f.memory.committed(b, f.memory.index)
        assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
        f.history(); assertTrue(f.accept()); assertEquals(b, f.owner.state.selectedTab!!.address!!.value)
        assertEquals(b, f.memory.addresses[f.memory.index])
    }

    @Test fun sameAddressReplaceAndABAInvalidateOldPageWithoutRequestReplacement() {
        val f = Fixture(); val old = f.page()
        for (url in listOf(a, b, a)) { f.history(url); assertTrue(f.accept()); assertFalse(old.matches(f.page(), true)) }
        assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
        assertEquals(a, f.memory.addresses[f.memory.index]); assertEquals(1L, f.request.epoch)
        assertTrue(f.owner.isCurrent(f.request)); assertEquals(3, f.reads)
    }

    @Test fun ordinaryBranchCannotReadPrivateAndPrivateBranchCannotReadOrdinary() {
        val f = Fixture(); f.history(); val before = f.owner.state; val ram = f.memory.addresses.toList()
        assertFalse(BrowserSameDocumentUpdate.accept(f.owner, f.request, f.document, f.document.address, f.document.revision,
            canGoBack = { fail("Private native Back forbidden"); true },
            canGoForward = { fail("Private native Forward forbidden"); true },
            viewUrl = { fail("Ordinary seam must reject private before native reads"); f.actual }, stillCurrent = f::current))
        assertSame(before, f.owner.state); assertEquals(ram, f.memory.addresses)
        val ordinary = Fixture(private = false); ordinary.history()
        assertFalse(ordinary.accept()); assertEquals(0, ordinary.reads)
    }

    @Test fun missingInvalidEmptyAndOverBoundRamFailBeforeNativeRead() {
        for (mutation in 0..4) {
            val f = Fixture(); f.history()
            when (mutation) {
                0 -> f.mappedMemory = null
                1 -> f.memory.index = -1
                2 -> f.memory.index = f.memory.addresses.size
                3 -> f.memory.addresses.clear()
                4 -> repeat(129) { f.memory.addresses += a }
            }
            val state = f.owner.state; val addresses = f.memory.addresses.toList(); val index = f.memory.index
            assertFalse(f.accept()); assertEquals(0, f.reads); assertSame(state, f.owner.state)
            assertEquals(addresses, f.memory.addresses); assertEquals(index, f.memory.index)
        }
    }

    @Test fun nativeGetterExceptionAndMismatchNeverCommitEitherOwner() {
        for (throwing in listOf(false, true)) {
            val f = Fixture(); f.history(); val before = f.owner.state; val ram = f.memory.addresses.toList()
            assertFalse(f.accept(nativeUrl = { if (throwing) throw IllegalStateException() else a }))
            assertSame(before, f.owner.state); assertEquals(ram, f.memory.addresses); assertEquals(1, f.reads)
        }
    }

    @Test fun nativeReentryCannotOverwriteRamIndexCurrentSlotSizeOrNeighbors() {
        for (mutation in 0..4) {
            val f = Fixture(); f.history(); val before = f.owner.state
            var changed = emptyList<String>(); var index = -1
            assertFalse(f.accept(nativeUrl = {
                when (mutation) {
                    0 -> f.memory.index = 0
                    1 -> f.memory.addresses[f.memory.index] = "https://private.invalid/newer"
                    2 -> f.memory.addresses += "https://private.invalid/newer"
                    3 -> f.memory.addresses.removeAt(f.memory.addresses.lastIndex)
                    4 -> f.memory.addresses[0] = "https://private.invalid/newer-neighbor"
                }
                changed = f.memory.addresses.toList(); index = f.memory.index; f.actual
            }))
            assertSame(before, f.owner.state); assertEquals(changed, f.memory.addresses); assertEquals(index, f.memory.index)
        }
    }

    @Test fun nativeReentryMapReplacementCannotMutateEitherRamOwner() {
        val f = Fixture(); f.history(); val before = f.owner.state; val ram = f.memory.addresses.toList()
        val replacement = SystemBrowserHost.PrivateNavigation().also { it.committed(a, null) }
        assertFalse(f.accept(nativeUrl = { f.mappedMemory = replacement; f.actual }))
        assertSame(before, f.owner.state); assertEquals(ram, f.memory.addresses)
        assertEquals(listOf(a), replacement.addresses); assertEquals(0, replacement.index)
    }

    @Test fun exactEntryViewOwnerSessionLeaseRuntimeAndAdmissionMustSurviveNativeReentry() {
        for (mutation in 0..15) {
            val f = Fixture(); f.history(); val before = f.owner.state; val ram = f.memory.addresses.toList()
            assertFalse("mutation $mutation", f.accept(nativeUrl = {
                when (mutation) {
                    0 -> f.registeredEntry = Any()
                    1 -> f.currentView = Any()
                    2 -> f.entryOwner = BrowserSessionController()
                    3 -> f.privateOwner = BrowserSessionController()
                    4 -> f.entrySession = session()
                    5 -> f.activeSession = session()
                    6 -> f.runtimeSession = session()
                    7 -> f.boundViewSession = session()
                    8 -> f.entryLease = PrivateSessionLease(f.session)
                    9 -> f.activeLease = PrivateSessionLease(f.session)
                    10 -> f.lease.revokeAndDrain {}
                    11 -> f.session.revoke()
                    12 -> f.live = false
                    13 -> f.suppressed = true
                    14 -> f.started = false
                    15 -> f.currentRequest = f.request.copy()
                }
                f.actual
            }))
            assertSame(before, f.owner.state); assertEquals(ram, f.memory.addresses)
        }
    }

    @Test fun staleIdentityBeforeNativeReadFailsClosed() {
        val f = Fixture(); f.history(); f.boundViewSession = null
        assertFalse(f.accept()); assertEquals(0, f.reads)
        val suppressed = Fixture(); suppressed.history(); suppressed.suppressed = true
        assertFalse(suppressed.accept()); assertEquals(0, suppressed.reads)
        val unstarted = Fixture(); unstarted.history(); unstarted.started = false
        assertFalse(unstarted.accept()); assertEquals(0, unstarted.reads)
    }

    @Test fun newerSameAddressOrABADocumentDuringGetterCannotBeRolledBack() {
        for (url in listOf(a, b, "https://private.invalid/newer")) {
            val f = Fixture(); f.history()
            assertFalse(f.accept(nativeUrl = {
                f.history(url); assertTrue(f.accept()); f.actual
            }))
            assertEquals(url, f.owner.state.selectedTab!!.address!!.value)
            assertEquals(url, f.memory.addresses[f.memory.index])
        }
        val aba = Fixture(); aba.history(); val oldRevision = aba.document.revision
        aba.history(a); aba.history(b)
        assertFalse(aba.accept(b, oldRevision)); assertEquals(0, aba.reads)
    }

    @Test fun newerRequestFailureCancellationRendererAndReplacementCannotBeOverwritten() {
        for (event in 0..4) {
            val f = Fixture(); f.history(); val ram = f.memory.addresses.toList()
            var newerState: BrowserSessionState? = null
            assertFalse(f.accept(nativeUrl = {
                when (event) {
                    0 -> f.owner.beginNavigation("tab", PageAddress(a), operation("new"))
                    1 -> {
                        val r = f.owner.beginNavigation("tab", PageAddress(a), operation("new"))
                        f.owner.acceptPageFailed(r, ErrorEnvelope(ErrorCode.PAGE_LOAD_FAILED, ErrorModule.BROWSER, true, UserAction.RETRY), 3)
                    }
                    2 -> { f.owner.beginNavigation("tab", PageAddress(a), operation("new")); f.owner.cancelNavigation("tab") }
                    3 -> f.owner.rendererGone(setOf("tab"))
                    4 -> {
                        f.owner.closeTab("tab"); f.owner.openTab("tab", "replacement", true)
                        val r = f.owner.beginNavigation("tab", PageAddress(a), operation("replacement"))
                        f.owner.acceptPageFinished(r, PageAddress(a), false, false, 3)
                    }
                }
                newerState = f.owner.state; f.actual
            }))
            assertSame(newerState, f.owner.state); assertEquals(ram, f.memory.addresses)
        }
    }

    @Test fun observerRejectsReloadUnfinishedNotCurrentNotReadyCrossOriginAndNativeMismatch() {
        for (url in listOf("https://other.invalid/watch", "http://private.invalid/watch", "https://private.invalid:444/watch",
            "https://user:secret@private.invalid/watch", "javascript:alert(1)", " https://private.invalid/watch")) {
            val f = Fixture(); val old = f.page(); val before = f.owner.state; val ram = f.memory.addresses.toList()
            f.history(url); assertFalse(f.accept()); assertEquals(0, f.reads)
            assertFalse(old.matches(f.page(), true)); assertSame(before, f.owner.state); assertEquals(ram, f.memory.addresses)
        }
        for (event in 0..4) {
            val f = Fixture(); val before = f.owner.state; val ram = f.memory.addresses.toList()
            when (event) {
                0 -> f.history(reload = true)
                1 -> f.history(current = false)
                2 -> f.history(ready = false)
                3 -> { f.document.started(); f.history() }
                4 -> f.history(native = a)
            }
            assertFalse(f.accept()); assertEquals(0, f.reads); assertSame(before, f.owner.state); assertEquals(ram, f.memory.addresses)
        }
    }

    @Test fun hiddenPrivateObservationAndModeSwitchKeepOrdinaryCheckpointAndSelectionExact() {
        for (switchDuringRead in listOf(false, true)) {
            val f = Fixture(); f.privateDisplayed = false
            val ordinary = BrowserSessionController().also { it.openTab("ordinary", "ordinary-session") }
            val ordinaryRequest = ordinary.beginNavigation("ordinary", PageAddress("https://ordinary.invalid/"), operation("ordinary"))
            ordinary.acceptPageFinished(ordinaryRequest, ordinaryRequest.address!!, false, false, 2)
            val visible = ordinary.state; val bytes = BrowserSessionCodec.encode(ordinary.persistableSession())
            f.owner.openTab("sibling", "sibling-session", true); val selected = f.owner.state.selectedTab
            assertTrue(f.owner.expireOperations(60_001).isEmpty()); f.history()
            assertTrue(f.accept(nativeUrl = { if (switchDuringRead) f.privateDisplayed = true; f.actual }))
            assertSame(visible, ordinary.state); assertSame(selected, f.owner.state.selectedTab)
            assertArrayEquals(bytes, BrowserSessionCodec.encode(ordinary.persistableSession()))
            assertTrue(BrowserSessionCodec.decode(BrowserSessionCodec.encode(f.owner.persistableSession())).tabs.isEmpty())
            f.privateDisplayed = true; f.owner.selectTab("tab")
            assertEquals(b, f.owner.state.selectedTab!!.address!!.value)
        }
    }

    @Test fun retiredProfileAndNewSameUrlSessionCannotReviveOldCallback() {
        val old = Fixture(); old.history(); val new = Fixture(); new.history()
        val oldRam = old.memory.addresses.toList(); val oldState = old.owner.state
        assertFalse(old.accept(nativeUrl = {
            old.lease.revokeAndDrain {}; old.session.revoke()
            old.privateOwner = new.owner; old.activeSession = new.session; old.activeLease = new.lease
            old.runtimeSession = new.session; old.boundViewSession = new.session; old.mappedMemory = new.memory
            assertTrue(new.accept()); new.actual
        }))
        assertSame(oldState, old.owner.state); assertEquals(oldRam, old.memory.addresses)
        assertEquals(b, new.owner.state.selectedTab!!.address!!.value); assertEquals(b, new.memory.addresses[new.memory.index])
    }

    @Test fun oldFinishAndProgressCannotUndoBButGenuineNewNavigationCanAdvance() {
        val f = Fixture(); f.history(); assertTrue(f.accept())
        assertFalse(f.owner.acceptPageFinished(f.request, PageAddress(a), false, false, 3))
        assertFalse(f.owner.acceptProgress(f.request, 10, 4)); assertEquals(b, f.owner.state.selectedTab!!.address!!.value)
        val next = f.owner.beginNavigation("tab", PageAddress(a), operation("new"))
        assertTrue(f.owner.acceptPageStarted(next, PageAddress(a), 5))
        assertTrue(f.owner.acceptPageFinished(next, PageAddress(a), true, false, 6))
        assertEquals(a, f.owner.state.selectedTab!!.address!!.value)
    }
}

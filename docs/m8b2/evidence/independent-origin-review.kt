package com.easysavevideo.discovery

import com.easysavevideo.app.browser.DiscoveryNavigationFence
import com.easysavevideo.app.discovery.*
import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.RepeatPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Independent B2 host fixtures. VERIFIED is synthetic test evidence only; the native app uses UNKNOWN.
 * No Android callback ordering, native media identity or playback claim. */
class IndependentOriginReviewTest {
    private class HookStore(val backing: InMemoryDiscoveryStore = InMemoryDiscoveryStore()) : DiscoveryStore {
        var hook: ((DiscoveryTransaction) -> Unit)? = null
        override fun <T> transaction(block: DiscoveryTransaction.() -> T): T = backing.transaction {
            val delegate = this
            object : DiscoveryTransaction by delegate {
                override fun beforeCommit(check: () -> Unit) = delegate.beforeCommit { hook?.invoke(delegate); check() }
            }.block()
        }
    }
    private class Rig(val store: HookStore = HookStore()) {
        val clock = FakeClock()
        val timer = FakeTimer(clock)
        val runtime = FakeRuntime()
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(StubProvider())), runtime, store, timer, clock,
            DiscoveryLimits(pageSize = 1, poolCapacity = 10)) { 10L }
        val profile = SearchProfile("profile", "query", historyPolicy = RepeatPolicy.SHOW_ALL)
        val monotonic = AtomicLong(1_000)
        val tracker = DiscoveryOriginTracker(monotonic::get, ttlMillis = 100)
        val browser = DiscoveryOriginBrowserState(BrowserContextRef("tab", "browser-process"), 1, 7)
        val page: PageSnapshot
        val item: VideoSearchResult
        val target: DiscoveryControlTarget
        init {
            coordinator.setVisibility(true, true)
            coordinator.start("session", profile)
            runtime.complete(0, 1, nextCursor = "end", exhausted = true)
            page = requireNotNull(coordinator.page(0)); item = coordinator.results(page).single()
            target = requireNotNull(DiscoveryPresentationPolicy.controlTarget(coordinator.currentCheckpoint(), 3))
        }
        fun open() = requireNotNull(tracker.open(target, page, item, browser))
        fun select() = requireNotNull(tracker.selectForSave(open(), browser))
        fun bound() = select().also { assertTrue(tracker.bindTask(it, "task")) }
        fun record(event: DiscoveryOriginEventProof): DiscoveryOriginHistoryOutcome =
            if (coordinator.recordSeen(event.page, event.result.fingerprint, event.event, 2_000, event::isCurrent))
                DiscoveryOriginHistoryOutcome.RECORDED else DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE
        fun complete(proof: DiscoveryOriginSaveProof, target: DiscoveryControlTarget? = this.target,
            record: (DiscoveryOriginEventProof) -> DiscoveryOriginHistoryOutcome = this::record) =
            tracker.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED, target, DiscoveryOriginMediaIdentityMatch.VERIFIED, record)
        fun facts() = store.backing.transaction { item.identityAliases.associateWith { seen(profile.profileId, it) } }
        fun assertNoSaved() { assertTrue(facts().values.all { it?.savedAt == null }) }
    }

    @Test fun verifiedCorrelatedSaveChangesOnlyItsIndependentAliasEventColumn() {
        val r = Rig()
        val aliases = r.item.identityAliases.toList()
        assertEquals(2, aliases.size)
        r.store.backing.transaction {
            putSeen(SeenRecord("profile", aliases[0], 8, shownAt = 20, openedAt = 40, savedAt = 3_000))
            putSeen(SeenRecord("profile", aliases[1], 9, dismissedAt = 30))
            putSeen(SeenRecord("other-profile", aliases[0], 1, playedAt = 90))
        }
        assertEquals(DiscoveryOriginHistoryOutcome.RECORDED, r.complete(r.bound()).history)
        r.facts().values.forEach { fact ->
            assertNotNull(fact); assertEquals(8L, fact!!.firstDiscoveredAt)
            assertEquals(20L, fact.shownAt); assertEquals(40L, fact.openedAt)
            assertEquals(3_000L, fact.savedAt); assertEquals(30L, fact.dismissedAt); assertNull(fact.playedAt)
        }
        r.store.backing.transaction {
            assertEquals(90L, seen("other-profile", aliases[0])!!.playedAt)
            assertNull(seen("other-profile", aliases[0])!!.savedAt)
        }
    }

    @Test fun expiryBeforeSelectionBindingOrCompletionNeverRenewsFromUserOrTaskActivity() {
        val beforeSelect = Rig(); val token = beforeSelect.open(); beforeSelect.monotonic.set(1_100)
        assertNull(beforeSelect.tracker.selectForSave(token, beforeSelect.browser))
        val beforeBind = Rig(); val selected = beforeBind.select(); beforeBind.monotonic.set(1_100)
        assertFalse(beforeBind.tracker.bindTask(selected, "task"))
        val beforeComplete = Rig(); val proof = beforeComplete.bound(); beforeComplete.monotonic.set(1_100)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, beforeComplete.complete(proof).history)
        listOf(beforeSelect, beforeBind, beforeComplete).forEach { it.assertNoSaved() }
    }

    private fun postRetentionRejection(expire: Boolean) {
        val store = HookStore(InMemoryDiscoveryStore(DiscoveryRetentionPolicy(maxSeenAliases = 2)))
        val r = Rig(store)
        assertTrue(r.coordinator.clearHistory("profile"))
        val page = r.coordinator.page(0)!!
        val target = DiscoveryPresentationPolicy.controlTarget(r.coordinator.currentCheckpoint(), 3)!!
        val token = r.tracker.open(target, page, r.item, r.browser)!!
        val proof = r.tracker.selectForSave(token, r.browser)!!
        assertTrue(r.tracker.bindTask(proof, "task"))
        val old = listOf(SeenRecord("profile", "old-a", 1, openedAt = 9), SeenRecord("profile", "old-b", 2, savedAt = 8))
        store.backing.transaction { old.forEach(::putSeen) }
        val checkpoint = store.backing.transaction { checkpoint("session") }
        val progress = store.backing.transaction { progress("profile", "source", r.profile.queryKey) }
        val statistics = store.backing.transaction { historyStatistics() }
        val status = r.coordinator.status()
        var guards = 0
        store.hook = { tx ->
            guards++
            old.forEach { assertNull(tx.seen("profile", it.fingerprint)) }
            assertEquals(2_000L, tx.seen("profile", r.item.fingerprint)!!.savedAt)
            if (expire) r.monotonic.set(1_100) else r.tracker.invalidate(r.browser.context)
        }
        val result = r.complete(proof, target)
        assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, result.media)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, result.history)
        assertEquals(1, guards)
        store.backing.transaction {
            old.forEach { assertEquals(it, seen("profile", it.fingerprint)) }
            r.item.identityAliases.forEach { assertNull(seen("profile", it)) }
            assertEquals(checkpoint, checkpoint("session")); assertEquals(progress, progress("profile", "source", r.profile.queryKey))
            assertEquals(statistics, historyStatistics())
        }
        assertEquals(status, r.coordinator.status())
        assertNull(r.coordinator.status().lastError)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof, target).history)
    }
    @Test fun expiryAtPostRetentionCommitGuardRollsBackAllFactsAndPruning() = postRetentionRejection(true)
    @Test fun reentrantNavigationAtPostRetentionCommitGuardRollsBackAllFactsAndPruning() = postRetentionRejection(false)

    @Test fun navigationWinningBeforeCallbackLeavesCommittedMediaWithNoHistory() {
        val r = Rig(); val proof = r.bound(); val pool = Executors.newFixedThreadPool(2)
        val invalidated = CountDownLatch(1)
        try {
            val nav = pool.submit { r.tracker.invalidateAll(); invalidated.countDown() }
            val save = pool.submit<DiscoveryOriginSaveOutcome> {
                assertTrue(invalidated.await(5, TimeUnit.SECONDS)); r.complete(proof)
            }
            nav.get(5, TimeUnit.SECONDS)
            assertEquals(DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED,
                DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE), save.get(5, TimeUnit.SECONDS))
            r.assertNoSaved()
        } finally { pool.shutdownNow() }
    }

    @Test fun completionWinningTrackerLockCommitsBeforeContendingNavigation() {
        val r = Rig(); val proof = r.bound(); val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val navigationAttempted = CountDownLatch(1); val invalidated = CountDownLatch(1)
        try {
            val save = pool.submit<DiscoveryOriginSaveOutcome> { r.complete(proof) { event ->
                entered.countDown(); assertTrue(navigationAttempted.await(5, TimeUnit.SECONDS))
                assertEquals(1L, invalidated.count); r.record(event)
            } }
            val nav = pool.submit {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); navigationAttempted.countDown()
                r.tracker.invalidateAll(); invalidated.countDown()
            }
            assertEquals(DiscoveryOriginHistoryOutcome.RECORDED, save.get(5, TimeUnit.SECONDS).history)
            nav.get(5, TimeUnit.SECONDS); assertEquals(0L, invalidated.count)
            assertTrue(r.facts().values.all { it?.savedAt == 2_000L })
            assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof).history)
        } finally { pool.shutdownNow() }
    }

    @Test fun persistedClearRevisionDefeatsEvenStaleCoordinatorAndUnchangedControllerTarget() {
        val r = Rig(); val proof = r.bound(); val old = r.coordinator.currentCheckpoint()!!
        r.store.backing.transaction { clearSeen("profile") }
        assertEquals(old, r.coordinator.currentCheckpoint()) // Deliberately stale in-memory reader.
        val cleared = r.store.backing.transaction { checkpoint("session") }!!
        assertEquals(old.pool.viewRevision + 1, cleared.pool.viewRevision)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof).history)
        assertEquals(0, r.store.backing.transaction { historyStatistics().seenAliasCount })
        assertEquals(cleared, r.store.backing.transaction { checkpoint("session") })
        assertFalse(r.coordinator.recordSeen(r.page, r.item.fingerprint, SeenEvent.OPENED))
        assertFalse(r.coordinator.recordSeen(r.page, r.item.fingerprint, SeenEvent.PLAYED))
        assertFalse(r.coordinator.recordSeen(r.page, r.item.fingerprint, SeenEvent.SAVED))
    }

    @Test fun controllerClearConsumesOldOriginWithoutRestoringFactsFromLateTargetReplay() {
        val r = Rig(); val proof = r.bound(); assertTrue(r.coordinator.clearHistory())
        val current = DiscoveryPresentationPolicy.controlTarget(r.coordinator.currentCheckpoint(), 3)!!
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof, current).history)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof, r.target).history)
        assertEquals(0, r.store.backing.transaction { historyStatistics().seenAliasCount })
        assertEquals(r.page.orderedResultIds, r.coordinator.page(0)!!.orderedResultIds)
    }

    @Test fun changedControllerGenerationAndNewProcessOwnerCannotBorrowProof() {
        val r = Rig(); val proof = r.bound()
        val newOwner = DiscoveryOriginTracker(r.monotonic::get)
        assertFalse(newOwner.bindTask(proof, "task"))
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE,
            newOwner.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED, r.target, DiscoveryOriginMediaIdentityMatch.VERIFIED, r::record).history)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE,
            r.complete(proof, r.target.copy(controllerGeneration = 4)).history)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof).history)
        r.assertNoSaved()
    }

    @Test fun navigationFenceAcceptsOriginalCallbackOrderButRejectsRepeatedAndChangedObservations() {
        listOf(false, true).forEach { historyFirst ->
            val f = DiscoveryNavigationFence("https://origin.example/item")
            assertFalse(f.accepts("https://origin.example/item"))
            if (historyFirst) assertTrue(f.onVisitedHistory("https://origin.example/item"))
            assertTrue(f.onStarted("https://origin.example/item"))
            if (!historyFirst) assertTrue(f.onVisitedHistory("https://origin.example/item"))
            assertTrue(f.accepts("https://origin.example/item"))
            assertFalse(f.onVisitedHistory("https://origin.example/item"))
            assertFalse(f.accepts("https://origin.example/item"))
        }
        val repeated = DiscoveryNavigationFence("https://origin.example/item")
        assertTrue(repeated.onStarted("https://origin.example/item"))
        assertFalse(repeated.onStarted("https://origin.example/item"))
        assertFalse(repeated.accepts("https://origin.example/item"))
        val redirect = DiscoveryNavigationFence("https://origin.example/item")
        assertFalse(redirect.onStarted("https://origin.example/redirect"))
        assertFalse(redirect.onStarted("https://origin.example/item"))
    }

    @Test fun alteredPageSizeOrderOrIdentityCannotWriteAgainstMaterializedPage() {
        val r = Rig(); val before = r.facts()
        val forged = listOf(r.page.copy(pageSize = 2), r.page.copy(orderedResultIds = listOf("wrong")),
            r.page.copy(pageIndex = r.page.pageIndex + 1), r.page.copy(revision = r.page.revision + 1))
        forged.forEach { assertFalse(r.coordinator.recordSeen(it, r.item.fingerprint, SeenEvent.SAVED)) }
        assertEquals(before, r.facts())
    }

    @Test fun historyExceptionConsumesOnlyOneProofAndNeverChangesMediaOutcome() {
        val r = Rig(); val proof = r.bound(); val attempts = AtomicInteger()
        val result = r.complete(proof) { attempts.incrementAndGet(); throw IllegalStateException("synthetic") }
        assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, result.media)
        assertEquals(DiscoveryOriginHistoryOutcome.HISTORY_FAILED, result.history)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof).history)
        assertEquals(1, attempts.get()); r.assertNoSaved()
    }

    @Test fun handleDispatchFailureAndThrowingObserverCannotRetryHistoryOrChangeMedia() {
        val r = Rig(); val proof = r.select(); val dispatched = AtomicInteger(); val delivered = AtomicInteger()
        val handle = DiscoverySaveOriginHandle(r.tracker, proof) { _, _, _, _, _ ->
            dispatched.incrementAndGet(); throw java.util.concurrent.RejectedExecutionException()
        }
        assertTrue(handle.bindTask("task"))
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) {
            delivered.incrementAndGet(); assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, it.media)
            assertEquals(DiscoveryOriginHistoryOutcome.HISTORY_FAILED, it.history)
        }
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) { fail("Duplicate delivery") }
        assertEquals(1, dispatched.get()); assertEquals(1, delivered.get()); r.assertNoSaved()
        val another = Rig(); val proof2 = another.select(); var observerCalls = 0
        val callbackThrower = DiscoverySaveOriginHandle(another.tracker, proof2) { t, p, task, media, cb ->
            cb(t.completeSave(p, task, media, another.target, DiscoveryOriginMediaIdentityMatch.VERIFIED, another::record))
        }
        assertTrue(callbackThrower.bindTask("task"))
        callbackThrower.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) {
            observerCalls++; assertEquals(DiscoveryOriginHistoryOutcome.RECORDED, it.history); throw IllegalStateException("synthetic")
        }
        assertEquals(1, observerCalls); assertTrue(another.facts().values.all { it?.savedAt == 2_000L })
    }

    @Test fun nativeContextMismatchAndClockRollbackPermanentlyRevokeCapability() {
        val r = Rig(); val token = r.open()
        assertNull(r.tracker.selectForSave(token, r.browser.copy(navigationGeneration = 8)))
        assertNull(r.tracker.selectForSave(token, r.browser))
        val proof = r.bound(); r.monotonic.set(999)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof).history)
        r.monotonic.set(1_001)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof).history)
        r.assertNoSaved()
    }

    @Test fun missingVideoIdentityEvidencePreservesCommittedMediaAndAllHistoryFacts() {
        for (explicitUnknown in listOf(false, true)) {
            val r = Rig(); val proof = r.bound(); val before = r.facts()
            val writer: (DiscoveryOriginEventProof) -> DiscoveryOriginHistoryOutcome = {
                fail("Page provenance is not video identity proof"); DiscoveryOriginHistoryOutcome.RECORDED
            }
            val outcome = if (explicitUnknown) r.tracker.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED,
                r.target, DiscoveryOriginMediaIdentityMatch.UNKNOWN, writer)
            else r.tracker.completeSave(proof, "task", DiscoveryOriginMediaOutcome.COMMITTED, r.target, record = writer)
            assertEquals(DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED,
                DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED), outcome)
            assertEquals(before, r.facts())
            assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, r.complete(proof).history)
        }
    }

    @Test fun unknownIdentityHandleDispatchDoesNotPromoteSavedEvenAfterVerifiedMediaReceipt() {
        val r = Rig(); val proof = r.select(); val before = r.facts(); val callbacks = AtomicInteger()
        val handle = DiscoverySaveOriginHandle(r.tracker, proof) { tracker, selected, task, media, callback ->
            callback(tracker.completeSave(selected, task, media, r.target,
                identityMatch = DiscoveryOriginMediaIdentityMatch.UNKNOWN, record = r::record))
        }
        assertTrue(handle.bindTask("task"))
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) {
            callbacks.incrementAndGet(); assertEquals(DiscoveryOriginMediaOutcome.COMMITTED, it.media)
            assertEquals(DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED, it.history)
        }
        handle.complete("task", DiscoveryOriginMediaOutcome.COMMITTED) { fail("No late identity upgrade") }
        assertEquals(1, callbacks.get()); assertEquals(before, r.facts())
    }

    @Test fun firstSameAddressHistoryObservationAfterFinishedInvalidatesBeforeSaveCanReuseIt() {
        val address = "https://origin.example/item"
        for (hadInitialHistory in listOf(false, true)) {
            val fence = DiscoveryNavigationFence(address)
            assertTrue(fence.onStarted(address))
            if (hadInitialHistory) assertTrue(fence.onVisitedHistory(address))
            fence.onFinished()
            assertTrue(fence.accepts(address))
            assertFalse(fence.onVisitedHistory(address))
            assertFalse(fence.accepts(address))
        }
    }

    @Test fun nonCommittedReceiptCannotInventSavedPlayedOrOtherSeenColumns() {
        val r = Rig(); val proof = r.bound(); val before = r.facts()
        val result = r.tracker.completeSave(proof, "task", DiscoveryOriginMediaOutcome.NOT_COMMITTED, r.target) {
            fail("No history callback allowed"); DiscoveryOriginHistoryOutcome.RECORDED
        }
        assertEquals(DiscoveryOriginMediaOutcome.NOT_COMMITTED, result.media)
        assertEquals(DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE, result.history)
        assertEquals(before, r.facts())
    }
}

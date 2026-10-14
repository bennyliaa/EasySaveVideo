package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.browser.BrowserCacheClearResult
import com.easysavevideo.app.browser.history.*
import org.junit.Assert.*
import org.junit.Test

/** Real history controller, synthetic in-memory storage, explicitly delayed IO and delivery. */
class PrivacySettingsActionsTest {
    private class Rig {
        val item = BrowserHistoryItem("00000000-0000-4000-8000-000000000001", "https://owned.example/video", "Owned fixture", 1_000)
        var stored = listOf(item)
        var failRead = false; var failWrite = false
        var writes = 0; var reads = 0; var cacheCalls = 0
        var active = true
        var cacheResult = BrowserCacheClearResult.REQUESTED
        var cacheEffect: () -> Unit = {}
        val work = ArrayDeque<() -> Unit>(); val deliveries = ArrayDeque<() -> Unit>()
        val controller = HistoryController(object : HistoryStorage {
            override fun load(): List<BrowserHistoryItem> { reads++; check(!failRead); return stored }
            override fun save(items: List<BrowserHistoryItem>) { writes++; check(!failWrite); stored = items }
        }, HistoryNavigationGate(), HistoryRetentionPolicy(200, HistoryPolicy.THIRTY_DAYS_MILLIS),
            worker={ work.addLast(it) }, deliver={ deliveries.addLast(it) }, newId={"00000000-0000-4000-8000-000000000002"}, now={2_000}, changed={})
        fun actions() = PrivacySettingsActions({active}, {controller.state}, controller::requestClear,
            controller::cancel, controller::confirm, controller::load, controller::dismissNotice,
            {cacheCalls++;cacheEffect();cacheResult}, {})
        val actions = actions()
        init { HistoryPolicy.validate(stored); controller.load(); finish(); check(controller.state.ready) }
        fun finishWork() { while(work.isNotEmpty()) work.removeFirst()() }
        fun deliver() { while(deliveries.isNotEmpty()) deliveries.removeFirst()() }
        fun finish() { finishWork();deliver() }
    }
    @Test fun openingOrCancellingEitherPromptDoesNotClearAnything() {
        val r=Rig();r.actions.beginHistory();val h=r.actions.state.prompt!!
        assertEquals(0,r.writes);r.actions.cancel(h);r.actions.confirm(h)
        r.actions.beginCache();val c=r.actions.state.prompt!!;r.actions.cancel(c);r.actions.confirm(c)
        assertEquals(0,r.writes);assertEquals(0,r.cacheCalls);assertEquals(listOf(r.item),r.stored)
    }
    @Test fun historyNeedsExplicitConfirmationAndOnlyAcknowledgesAfterDelivery() {
        val r=Rig();r.actions.beginHistory();val prompt=r.actions.state.prompt!!
        r.actions.confirm(prompt)
        assertNull(r.actions.state.prompt);assertTrue(r.controller.state.busy)
        assertEquals(listOf(r.item),r.controller.state.items);assertNull(r.controller.state.notice)
        assertEquals(0,r.writes);r.finishWork()
        assertEquals(emptyList<BrowserHistoryItem>(),r.stored);assertTrue(r.controller.state.busy)
        assertNull(r.controller.state.notice);r.deliver()
        assertFalse(r.controller.state.busy);assertEquals(HistoryNotice.CLEARED,r.controller.state.notice)
        assertTrue(r.controller.state.items.isEmpty());assertEquals(0,r.cacheCalls)
    }
    @Test fun repeatedHistoryClicksAndConfirmProduceOneWrite() {
        val r=Rig();r.actions.beginHistory();val p=r.actions.state.prompt!!
        r.actions.beginHistory();assertSame(p,r.actions.state.prompt)
        repeat(3){r.actions.confirm(p);r.actions.beginHistory();r.actions.beginCache()}
        assertNull(r.actions.state.prompt);r.finish();assertEquals(1,r.writes);assertEquals(0,r.cacheCalls)
    }
    @Test fun cacheConfirmationCallsOnlyCacheAndNeverClaimsVerifiedCompletion() {
        val r=Rig();r.actions.beginCache();val p=r.actions.state.prompt!!
        assertEquals(0,r.cacheCalls);repeat(3){r.actions.confirm(p)}
        assertEquals(1,r.cacheCalls);assertEquals(0,r.writes)
        assertEquals(BrowserCacheClearResult.REQUESTED,r.actions.state.cacheResult)
        assertEquals(listOf(r.item),r.controller.state.items)
    }
    @Test fun cacheFailureIsVisibleAndRetryRequiresNewConfirmation() {
        val r=Rig();r.cacheResult=BrowserCacheClearResult.FAILED;r.actions.beginCache()
        r.actions.confirm(r.actions.state.prompt!!);assertEquals(BrowserCacheClearResult.FAILED,r.actions.state.cacheResult)
        r.cacheResult=BrowserCacheClearResult.REQUESTED;r.actions.beginCache()
        assertNull(r.actions.state.cacheResult);assertEquals(1,r.cacheCalls)
        r.actions.confirm(r.actions.state.prompt!!);assertEquals(2,r.cacheCalls)
        assertEquals(BrowserCacheClearResult.REQUESTED,r.actions.state.cacheResult)
    }
    @Test fun backgroundInvalidatesUnsubmittedHistoryAndCachePrompts() {
        for(history in listOf(false,true)) {
            val r=Rig();if(history)r.actions.beginHistory() else r.actions.beginCache()
            val p=r.actions.state.prompt!!;r.active=false;r.actions.reconcile();r.active=true;r.actions.confirm(p)
            assertEquals(0,r.cacheCalls);assertEquals(0,r.writes);assertNull(r.actions.state.prompt)
        }
    }
    @Test fun closedOwnerCannotActAfterReturningToTheSameCategory() {
        val r=Rig();r.actions.beginHistory();val old=r.actions.state.prompt!!;r.actions.close()
        val next=r.actions();next.beginCache();val fresh=next.state.prompt!!
        r.actions.confirm(old);r.actions.beginHistory();r.actions.beginCache();r.actions.retryHistory();r.actions.dismissHistoryNotice()
        assertNull(r.actions.state.prompt);assertEquals(1,r.reads);assertEquals(0,r.writes);assertEquals(0,r.cacheCalls)
        next.confirm(fresh);assertEquals(1,r.cacheCalls)
    }
    @Test fun leavingAfterAcceptedClearDoesNotOrphanAsyncResult() {
        val r=Rig();r.actions.beginHistory();r.actions.confirm(r.actions.state.prompt!!)
        r.actions.close();r.active=false;r.finish();assertEquals(HistoryNotice.CLEARED,r.controller.state.notice)
        r.active=true;val reopened=r.actions();assertNull(reopened.state.prompt)
        assertFalse(r.controller.state.busy);assertTrue(r.controller.state.items.isEmpty());assertEquals(1,r.writes)
    }
    @Test fun storageFailureKeepsPriorRowsAndReturnsUnreadableStateWithoutFalseSuccess() {
        val r=Rig();r.failWrite=true;r.actions.beginHistory();r.actions.confirm(r.actions.state.prompt!!);r.finish()
        assertEquals(HistoryNotice.UNAVAILABLE,r.controller.state.notice);assertFalse(r.controller.state.ready)
        assertEquals(listOf(r.item),r.controller.state.items);assertEquals(listOf(r.item),r.stored)
        r.failWrite=false;r.actions.retryHistory();r.finish();assertTrue(r.controller.state.ready)
        assertEquals(listOf(r.item),r.controller.state.items);assertEquals(1,r.writes)
    }
    @Test fun lateSameValueHistoryReloadRetiresOldConfirmation() {
        val r=Rig();r.actions.beginHistory();val p=r.actions.state.prompt!!
        r.controller.load();r.finish();assertEquals(listOf(r.item),r.controller.state.items)
        r.actions.confirm(p);assertNull(r.actions.state.prompt);assertEquals(0,r.writes)
    }
    @Test fun newerPromptCannotBeCancelledOrConfirmedByOldCallback() {
        val r=Rig();r.actions.beginCache();val old=r.actions.state.prompt!!;r.actions.cancel(old)
        r.actions.beginHistory();val fresh=r.actions.state.prompt!!
        r.actions.confirm(old);r.actions.cancel(old);assertSame(fresh,r.actions.state.prompt)
        r.actions.confirm(fresh);r.finish();assertEquals(1,r.writes);assertEquals(0,r.cacheCalls)
    }
    @Test fun nativeReentrantExitRetiresCacheResultAndPreventsSecondSubmission() {
        val r=Rig();r.cacheEffect={r.actions.close();r.active=false}
        r.actions.beginCache();r.actions.confirm(r.actions.state.prompt!!)
        assertEquals(1,r.cacheCalls);assertNull(r.actions.state.cacheResult)
        r.active=true;r.actions.beginCache();assertNull(r.actions.state.prompt)
    }
    @Test fun nativeReentrantClickCannotOpenAnotherPromptDuringCacheCall() {
        val r=Rig();r.cacheEffect={r.actions.beginHistory();r.actions.beginCache()}
        r.actions.beginCache();r.actions.confirm(r.actions.state.prompt!!)
        assertNull(r.actions.state.prompt);assertEquals(1,r.cacheCalls);assertEquals(0,r.writes)
    }
    @Test fun pendingHistoryIoBlocksBothEntryActions() {
        val r=Rig();r.controller.load();r.actions.beginHistory();r.actions.beginCache()
        assertNull(r.actions.state.prompt);assertEquals(0,r.writes);assertEquals(0,r.cacheCalls)
        r.finish();r.actions.beginCache();assertNotNull(r.actions.state.prompt)
    }
    @Test fun unreadableHistoryCanOnlyBeResetAfterExplicitConfirmation() {
        val r=Rig();r.failRead=true;r.controller.load();r.finish();assertFalse(r.controller.state.ready)
        r.actions.beginHistory();assertEquals(0,r.writes)
        r.actions.confirm(r.actions.state.prompt!!);r.finish()
        assertEquals(HistoryNotice.CLEARED,r.controller.state.notice);assertTrue(r.controller.state.ready);assertEquals(1,r.writes)
    }
    @Test fun slowAcceptedHistoryWorkStillHasOneTerminalResult() {
        val r=Rig();r.actions.beginHistory();r.actions.confirm(r.actions.state.prompt!!)
        r.controller.slow(r.controller.operationGeneration()!!)
        assertEquals(HistoryNotice.SLOW,r.controller.state.notice);r.actions.beginHistory();assertNull(r.actions.state.prompt)
        r.finish();assertEquals(HistoryNotice.CLEARED,r.controller.state.notice);assertEquals(1,r.writes)
    }
}

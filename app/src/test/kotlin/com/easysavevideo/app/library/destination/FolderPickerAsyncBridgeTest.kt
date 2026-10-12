package com.easysavevideo.app.library.destination

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class FolderPickerAsyncBridgeTest {
    private class Harness {
        val worker = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val owner = Any()
        var current = true
        var next = FolderPickerRequestCodes.FIRST
        var committed = true
        var writes = 0
        var launches = mutableListOf<Int>()
        var failures = 0
        var results = mutableListOf<String>()
        var background = false
        val allocator = DurableFolderPickerAllocator({ check(background); next }, { check(background); writes++; next = it; committed })
        val bridge = FolderPickerAsyncBridge<Any, String>({ check(!background) }, { worker.add(it) }, { main.add(it) }, allocator::allocate)
        fun launch(owner: Any = this.owner, gate: () -> Boolean = { current }, action: (Int) -> Unit = { launches.add(it) }) =
            bridge.launch(owner, gate, action, { failures++ }, { results.add(it) }, related = gate)
        fun work() { background = true; try { worker.removeFirst()() } finally { background = false } }
        fun deliver() { main.removeFirst()() }
        fun complete() { work(); deliver() }
    }
    @Test fun diskAllocationIsDeferredAndLaunchWaitsForMainDelivery() {
        val h = Harness();h.launch();assertEquals(0,h.writes);assertTrue(h.launches.isEmpty())
        h.work();assertEquals(1,h.writes);assertTrue(h.launches.isEmpty());h.deliver();assertEquals(listOf(42000),h.launches)
    }
    @Test fun repeatedClicksReserveOneSlotBeforeCommit() {
        val h=Harness();h.launch();try {h.launch();fail()}catch(e:FolderPickerFailure){assertEquals(FolderPickerError.PENDING,e.code)}
        assertEquals(1,h.worker.size);h.complete();assertEquals(1,h.launches.size)
    }
    @Test fun failedCommitNeverLaunchesAndPoisonsRetriesEvenWhenReadWouldReuse() {
        val h=Harness();h.committed=false;h.launch();h.complete();assertEquals(1,h.failures);assertTrue(h.launches.isEmpty())
        h.next=42000;h.committed=true;h.launch();h.complete();assertEquals(1,h.writes);assertEquals(2,h.failures);assertTrue(h.launches.isEmpty())
    }
    @Test fun throwingCommitPoisonsAllocator() {
        var reads=0;val a=DurableFolderPickerAllocator({reads++;42000},{throw IllegalStateException()})
        repeat(2){try{a.allocate();fail()}catch(_:Exception){}};assertEquals(1,reads)
    }
    @Test fun cancellationBetweenCommitAndPostNeverLaunches() {
        val h=Harness();h.launch();h.work();h.current=false;h.deliver();assertTrue(h.launches.isEmpty());assertEquals(1,h.failures)
    }
    @Test fun closeBeforeWorkerCompletesDropsCallbackAndOldPostCannotReplaceNewOwner() {
        val h=Harness();h.launch();h.bridge.abandon(h.owner);val fresh=Any();h.launch(fresh)
        h.work();h.deliver();assertTrue(h.launches.isEmpty());assertEquals(0,h.failures)
        h.complete();assertEquals(listOf(42001),h.launches)
    }
    @Test fun cancellationAllowsReplacementAndOldAllocationPostIsDiscarded() {
        val h=Harness();h.launch();h.current=false;h.launch(Any(),{true});h.complete();assertTrue(h.launches.isEmpty())
        h.complete();assertEquals(listOf(42001),h.launches)
    }
    @Test fun rotationAfterLaunchRetainsSlotUntilLateResultAndDoesNotDeliverOldCallback() {
        val h=Harness();h.launch();h.complete();h.bridge.abandon(h.owner);val fresh=Any()
        try{h.launch(fresh);fail()}catch(e:FolderPickerFailure){assertEquals(FolderPickerError.PENDING,e.code)}
        assertTrue(h.bridge.dispatch(fresh,42000,"old"));assertTrue(h.results.isEmpty());h.launch(fresh);h.complete();assertEquals(listOf(42000,42001),h.launches)
    }
    @Test fun duplicateAndForeignResultsCannotConsumeCurrentRequest() {
        val h=Harness();h.launch();h.complete();assertTrue(h.bridge.dispatch(h.owner,42001,"foreign"));assertTrue(h.results.isEmpty())
        h.bridge.dispatch(h.owner,42000,"right");h.bridge.dispatch(h.owner,42000,"duplicate");assertEquals(listOf("right"),h.results)
        assertFalse(h.bridge.dispatch(h.owner,41320,"recording"))
    }
    @Test fun resultBeforeLaunchCannotCompleteAllocatedRequest() {
        val h=Harness();h.launch();h.work();h.bridge.dispatch(h.owner,42000,"early");h.deliver();h.bridge.dispatch(h.owner,42000,"right");assertEquals(listOf("right"),h.results)
    }
    @Test fun cancelledOrChangedSourceResultIsConsumedWithoutCallback() {
        val h=Harness();h.launch();h.complete();h.current=false;assertTrue(h.bridge.dispatch(h.owner,42000,"stale"));assertTrue(h.results.isEmpty())
    }
    @Test fun absentActivityConsumesOldLaunchedSlotWithoutDelivering() {
        val h=Harness();h.launch();h.complete();h.bridge.abandon(h.owner);h.bridge.dispatch(null,42000,"old");h.launch(Any());h.complete();assertEquals(2,h.launches.size)
    }
    @Test fun expiredOrPausedActivityLaunchFailureConsumesNumberAndAllowsFreshRequest() {
        val h=Harness();h.launch(action={throw IllegalStateException("expired-or-paused")});h.complete();assertTrue(h.launches.isEmpty());assertEquals(1,h.failures)
        h.launch();h.complete();assertEquals(listOf(42001),h.launches)
    }
    @Test fun exhaustedCounterDoesNotWriteOrWrap() {
        val h=Harness();h.next=FolderPickerRequestCodes.LAST+1;h.launch();h.complete();assertEquals(0,h.writes);assertTrue(h.launches.isEmpty());assertEquals(1,h.failures)
    }
    @Test fun rejectedWorkerAdmissionReleasesMainSlot() {
        var reject=true;val bridge=FolderPickerAsyncBridge<Any,String>({}, {if(reject)throw java.util.concurrent.RejectedExecutionException() else it()}, {it()}, {42000})
        val owner=Any();try{bridge.launch(owner,{true},{},{},{},related={true});fail()}catch(_:java.util.concurrent.RejectedExecutionException){}
        reject=false;var launched=false;bridge.launch(owner,{true},{launched=true},{},{},related={true});assertTrue(launched)
    }
    @Test fun pauseBeforeLaunchCancelsEvenIfActivityResumesBeforeWorkerPost() {
        val h=Harness();h.launch();h.work();h.bridge.cancelUnlaunched(h.owner);h.current=true;h.deliver()
        assertTrue(h.launches.isEmpty());assertEquals(1,h.failures);h.launch();h.complete();assertEquals(listOf(42001),h.launches)
    }
    @Test fun pickerOwnPauseAfterLaunchDoesNotCancelResult() {
        val h=Harness();h.launch();h.complete();h.bridge.cancelUnlaunched(h.owner)
        assertEquals(0,h.failures);h.bridge.dispatch(h.owner,42000,"real");assertEquals(listOf("real"),h.results)
    }

    @Test fun blockedRealWorkerCommitDoesNotHoldMainStateLock() {
        val uiThread=Thread.currentThread()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val posts=java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
        val lane=java.util.concurrent.Executors.newSingleThreadExecutor()
        var launches=0;var failures=0
        val allocator=DurableFolderPickerAllocator({42000},{
            assertNotSame(uiThread,Thread.currentThread());entered.countDown()
            check(release.await(2,java.util.concurrent.TimeUnit.SECONDS));true
        })
        val bridge=FolderPickerAsyncBridge<Any,String>({assertSame(uiThread,Thread.currentThread())},
            {lane.execute(it)}, {posts.add(it);Unit}, allocator::allocate)
        val owner=Any()
        try {
            bridge.launch(owner,{true},{launches++},{failures++},{},related={true})
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            // Runs while commit is blocked; no allocation monitor is acquired on the UI lane.
            bridge.cancelUnlaunched(owner)
            assertEquals(1,failures);assertEquals(0,launches)
            release.countDown();requireNotNull(posts.poll(2,java.util.concurrent.TimeUnit.SECONDS))()
            assertEquals(0,launches)
        } finally { release.countDown();lane.shutdownNow() }
    }

    @Test fun deadlineIsRecheckedAfterCommitBeforePlatformLaunch() {
        val h=Harness();var now=0L;val deadline=30000L
        h.launch(action={code->check(now<deadline);h.launches.add(code)})
        h.work();now=deadline;h.deliver();assertTrue(h.launches.isEmpty());assertEquals(1,h.failures)
    }
    @Test fun staleResultStillReconcilesItsOwnLiveUiButNeverNewRequest() {
        val h=Harness();h.launch();h.complete();h.current=false
        h.bridge.dispatch(h.owner,42000,"stale");assertEquals(1,h.failures);assertTrue(h.results.isEmpty())
        h.current=true;h.launch();h.complete();h.bridge.dispatch(h.owner,42000,"old")
        assertEquals(1,h.failures);h.bridge.dispatch(h.owner,42001,"new");assertEquals(listOf("new"),h.results)
    }

}

package com.easysavevideo.app.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportControllerTest {
    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    private class Harness(val text: String = "safe\n", val result: DiagnosticSnapshotResult? = null) {
        val queue = Queue()
        val changes = mutableListOf<DiagnosticExportState>()
        val choices = mutableListOf<String>()
        val dismissals = mutableListOf<String>()
        val controller = DiagnosticExportController(
            { result ?: DiagnosticSnapshotResult.Ready(DiagnosticSnapshot(text)) }, queue, { it() },
            changes::add, choices::add, dismissals::add)
        fun choose(): String { controller.begin(); queue.drain(); return choices.single() }
    }
    @Test fun oneExplicitChoiceWritesImmutableSnapshotAndOnlySucceedsAfterFlushAndClose() {
        val h = Harness(); val id = h.choose(); val calls = mutableListOf<String>()
        val output = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) { calls.add("write"); super.write(b,off,len) }
            override fun flush() { assertEquals(DiagnosticExportPhase.WRITING,h.controller.state.phase); calls.add("flush") }
            override fun close() { assertEquals(DiagnosticExportPhase.WRITING,h.controller.state.phase); calls.add("close") }
        }
        h.controller.selected(id, DiagnosticExportPort { output })
        assertEquals(DiagnosticExportPhase.WRITING,h.controller.state.phase); h.queue.drain()
        assertEquals(listOf("write","flush","close"),calls)
        assertEquals("safe\n",output.toString("UTF-8")); assertEquals(DiagnosticExportPhase.SUCCEEDED,h.controller.state.phase)
    }
    @Test fun duplicateClicksWrongRequestAndLateCallbacksDoNotOpenAnything() {
        val h=Harness();h.controller.begin();h.controller.begin();assertEquals(1,h.queue.work.size);h.queue.drain()
        var opens=0;val port=DiagnosticExportPort{opens++;ByteArrayOutputStream()};val id=h.choices.single()
        h.controller.selected("wrong",port);assertEquals(0,opens)
        h.controller.selected(id,port);h.controller.selected(id,port);h.controller.begin();h.queue.drain()
        h.controller.selected(id,port);assertEquals(1,opens);assertEquals(1,h.choices.size)
    }
    @Test fun emptyAndReadFailureNeverLaunchPicker() {
        for(result in listOf(DiagnosticSnapshotResult.Empty,DiagnosticSnapshotResult.Failed)) {
            val h=Harness(result=result);h.controller.begin();h.queue.drain();assertTrue(h.choices.isEmpty())
            assertEquals(if(result==DiagnosticSnapshotResult.Empty)DiagnosticExportPhase.EMPTY else DiagnosticExportPhase.FAILED,h.controller.state.phase)
        }
    }
    @Test fun cancelledSelectionAndPrepareCannotReplayAfterRecreation() {
        val h=Harness();h.controller.begin();h.controller.cancel();h.queue.drain();assertTrue(h.choices.isEmpty())
        assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
        h.controller.begin();h.queue.drain();val old=h.choices.single();h.controller.close()
        val replacement=Harness();var opens=0
        replacement.controller.selected(old,DiagnosticExportPort{opens++;ByteArrayOutputStream()})
        replacement.queue.drain();assertEquals(0,opens);assertEquals(DiagnosticExportPhase.IDLE,replacement.controller.state.phase)
        h.controller.selected(old,DiagnosticExportPort{opens++;ByteArrayOutputStream()});h.controller.begin();h.queue.drain();assertEquals(0,opens)
    }
    @Test fun pickerCancellationAndLaunchFailureAreTerminalWithoutRetry() {
        val h=Harness();val id=h.choose();h.controller.selected(id,null);h.queue.drain()
        assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
        h.controller.selectionFailed(id);assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
        val failed=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},Executor{it.run()},{it()},{},{throw IOException("secret")},{})
        failed.begin();assertEquals(DiagnosticExportPhase.FAILED,failed.state.phase)
    }
    @Test fun writeFlushCloseAndOpenFailuresNeverReportSuccessOrRetry() {
        for(failure in listOf("open","write","flush","close")) {
            val h=Harness();val id=h.choose();var opens=0;var closed=0
            h.controller.selected(id,DiagnosticExportPort {
                opens++;if(failure=="open")throw IOException("token=BAIT")
                object:OutputStream(){
                    override fun write(b:Int){if(failure=="write")throw IOException("/secret/BAIT")}
                    override fun flush(){if(failure=="flush")throw IOException("https://BAIT")}
                    override fun close(){closed++;if(failure=="close")throw IOException("Cookie=BAIT")}
                }
            });h.queue.drain()
            assertEquals(failure,DiagnosticExportPhase.FAILED,h.controller.state.phase);assertEquals(1,opens)
            assertEquals(if(failure=="open")0 else 1,closed)
            assertFalse(h.changes.toString().contains("BAIT"))
        }
    }
    @Test fun partialWriteCancellationClosesAndPreventsLaterChunksFlushOrSuccess() {
        val h=Harness("A".repeat(9000));val id=h.choose();var written=0;var closed=0;var flushed=0
        h.controller.selected(id,DiagnosticExportPort{object:OutputStream(){
            override fun write(b:Int){error("unexpected")}
            override fun write(b:ByteArray,off:Int,len:Int){written+=len;h.controller.cancel();h.controller.begin()}
            override fun flush(){flushed++}
            override fun close(){closed++}
        }})
        h.queue.drain();assertEquals(4096,written);assertEquals(0,flushed);assertEquals(1,closed)
        assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase);assertEquals(1,h.choices.size)
    }
    @Test fun cancellationAtFlushOrCloseCannotBecomeSuccess() {
        for(where in listOf("flush","close")) {
            val h=Harness();val id=h.choose()
            h.controller.selected(id,DiagnosticExportPort{object:ByteArrayOutputStream(){
                override fun flush(){if(where=="flush")h.controller.cancel()}
                override fun close(){if(where=="close")h.controller.cancel()}
            }})
            h.queue.drain();assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
        }
    }
    @Test fun backgroundKeepsOnlyThePickerWaitAndCancelsQueuedWritesBeforeOpen() {
        val h=Harness();val id=h.choose();h.controller.background();assertEquals(DiagnosticExportPhase.CHOOSING,h.controller.state.phase)
        var opens=0;h.controller.selected(id,DiagnosticExportPort{opens++;ByteArrayOutputStream()});h.controller.background();h.queue.drain()
        assertEquals(0,opens);assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
        val preparing=Harness();preparing.controller.begin();preparing.controller.background();preparing.queue.drain();assertTrue(preparing.choices.isEmpty())
    }
    @Test fun delayedUiDispatchDoesNotLaunchCancelledRequest() {
        val q=Queue();val ui=Queue();var choices=0
        val c=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},q,{ui.execute(it)},{},{choices++},{})
        c.begin();q.drain();c.cancel();ui.drain();assertEquals(0,choices)
    }
    @Test fun partialWriteFailureClosesWithoutFlushOrAutomaticSecondOpen() {
        val h=Harness("A".repeat(9000));val id=h.choose();var bytes=0;var closes=0;var flushes=0;var opens=0
        h.controller.selected(id,DiagnosticExportPort{opens++;object:OutputStream(){
            override fun write(b:Int){error("unexpected")}
            override fun write(b:ByteArray,off:Int,len:Int){bytes+=17;throw IOException("synthetic partial")}
            override fun flush(){flushes++}
            override fun close(){closes++}
        }})
        h.queue.drain();assertEquals(17,bytes);assertEquals(1,closes);assertEquals(0,flushes);assertEquals(1,opens)
        assertEquals(DiagnosticExportPhase.FAILED,h.controller.state.phase)
    }
    @Test fun blockedProviderCloseKeepsCancellationBusyUntilRealCloseFinishes() {
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        val chosen=java.util.concurrent.LinkedBlockingQueue<String>()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val finished=java.util.concurrent.CountDownLatch(1)
        val c=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},pool,{it()},
            {if(it.phase==DiagnosticExportPhase.CANCELLED)finished.countDown()}, {chosen.add(it)}, {})
        try {
            c.begin();val id=requireNotNull(chosen.poll(5,java.util.concurrent.TimeUnit.SECONDS))
            c.selected(id,DiagnosticExportPort{object:ByteArrayOutputStream(){override fun close(){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}})
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));c.cancel();c.begin()
            assertEquals(DiagnosticExportPhase.CANCELLING,c.state.phase);assertTrue(chosen.isEmpty())
            release.countDown();assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(DiagnosticExportPhase.CANCELLED,c.state.phase)
        } finally {release.countDown();c.close();pool.shutdownNow()}
    }

    private class Clock {
        var now=0L
        data class Alarm(val at:Long,val callback:()->Unit,var cancelled:Boolean=false)
        val alarms=mutableListOf<Alarm>()
        fun schedule(delay:Long,callback:()->Unit):AutoCloseable {
            val alarm=Alarm(now+delay,callback);alarms+=alarm
            return AutoCloseable {alarm.cancelled=true}
        }
        fun advance(delta:Long) {
            now+=delta
            while(true) {val alarm=alarms.firstOrNull{!it.cancelled && it.at<=now}?:break;alarm.cancelled=true;alarm.callback()}
        }
    }
    @Test fun monotonicPrepareTimeoutIsTerminalAndDelayedReadCannotLaunchPicker() {
        val q=Queue();val clock=Clock();var chosen=0
        val c=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},q,{it()},{},{chosen++},{},
            monotonicMillis={clock.now},schedule=clock::schedule)
        c.begin();clock.advance(DiagnosticExportController.IO_TIMEOUT_MILLIS)
        assertEquals(DiagnosticExportPhase.TIMED_OUT,c.state.phase);assertTrue(c.state.releaseUnconfirmed)
        c.begin();assertEquals(1,q.work.size);q.drain();assertEquals(0,chosen)
        assertEquals(DiagnosticExportPhase.TIMED_OUT,c.state.phase);assertFalse(c.state.releaseUnconfirmed)
    }
    @Test fun pickerDeadlineRejectsLateCallbackAndDoesNotHoldAnIdleFence() {
        val q=Queue();val clock=Clock();val choices=mutableListOf<String>();val fence=DiagnosticWorkFence()
        val c=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},q,{it()},{},choices::add,{},
            fence,{clock.now},clock::schedule)
        c.begin();q.drain();val id=choices.single();clock.advance(DiagnosticExportController.PICKER_TIMEOUT_MILLIS)
        var opens=0;c.selected(id,DiagnosticExportPort{opens++;ByteArrayOutputStream()});q.drain()
        assertEquals(0,opens);assertEquals(DiagnosticExportPhase.TIMED_OUT,c.state.phase);assertFalse(c.state.releaseUnconfirmed)
        c.begin();q.drain();assertEquals(2,choices.size);c.close()
    }
    @Test fun timeoutDuringAdmittedOpenAllowsOnlyCleanupAndCannotBecomeLateSuccess() {
        val q=Queue();val clock=Clock();val choices=mutableListOf<String>();val fence=DiagnosticWorkFence()
        val c=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},q,{it()},{},choices::add,{},
            fence,{clock.now},clock::schedule)
        var writes=0;var closes=0
        c.begin();q.drain();c.selected(choices.single(),DiagnosticExportPort {
            clock.advance(DiagnosticExportController.IO_TIMEOUT_MILLIS)
            assertEquals(DiagnosticExportPhase.TIMED_OUT,c.state.phase)
            object:OutputStream(){override fun write(b:Int){writes++};override fun close(){closes++}}
        });q.drain()
        assertEquals(0,writes);assertEquals(1,closes);assertEquals(DiagnosticExportPhase.TIMED_OUT,c.state.phase)
        assertFalse(c.state.releaseUnconfirmed)
    }
    @Test fun closeFailureRetainsProcessFenceAcrossControllerRecreation() {
        val q=Queue();val choices=mutableListOf<String>();val fence=DiagnosticWorkFence()
        fun controller()=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},q,{it()},{},choices::add,{},fence)
        val old=controller();old.begin();q.drain();old.selected(choices.single(),DiagnosticExportPort{object:ByteArrayOutputStream(){override fun close(){throw IOException()}}});q.drain()
        assertEquals(DiagnosticExportPhase.FAILED,old.state.phase);assertTrue(old.state.releaseUnconfirmed);old.close()
        val recreated=controller();recreated.begin();q.drain()
        assertEquals(DiagnosticExportPhase.BLOCKED,recreated.state.phase);assertEquals(1,choices.size)
    }
    @Test fun recreationWhileOldReadDrainsCannotQueueNewReadButCanExplicitlyRetryAfterRelease() {
        val q=Queue();val fence=DiagnosticWorkFence();val choices=mutableListOf<String>()
        fun controller()=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},q,{it()},{},choices::add,{},fence)
        val old=controller();old.begin();old.close();val recreated=controller();recreated.begin()
        assertEquals(DiagnosticExportPhase.BLOCKED,recreated.state.phase);assertEquals(1,q.work.size)
        q.drain();assertTrue(choices.isEmpty());recreated.begin();q.drain();assertEquals(1,choices.size);recreated.close()
    }
    @Test fun cancellationInAdmittedOpenPreventsEveryWriteAndStillClosesExactlyOnce() {
        val h=Harness();val id=h.choose();var written=0;var closed=0
        h.controller.selected(id,DiagnosticExportPort{h.controller.cancel();object:OutputStream(){
            override fun write(b:Int){written++};override fun close(){closed++}
        }});h.queue.drain();assertEquals(0,written);assertEquals(1,closed)
        assertEquals(DiagnosticExportPhase.CANCELLED,h.controller.state.phase)
    }

    @Test fun blockedCloseTimeoutStaysTimedOutAfterRealCloseReleasesFence() {
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor();val clock=Clock();val fence=DiagnosticWorkFence()
        val chosen=java.util.concurrent.LinkedBlockingQueue<String>()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val drained=java.util.concurrent.CountDownLatch(1)
        val c=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},pool,{it()},
            {if(it.phase==DiagnosticExportPhase.TIMED_OUT && !it.releaseUnconfirmed)drained.countDown()}, {chosen.add(it)}, {},
            fence,{clock.now},clock::schedule)
        try {
            c.begin();val id=requireNotNull(chosen.poll(5,java.util.concurrent.TimeUnit.SECONDS))
            c.selected(id,DiagnosticExportPort{object:ByteArrayOutputStream(){override fun close(){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}})
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));clock.advance(DiagnosticExportController.IO_TIMEOUT_MILLIS)
            assertEquals(DiagnosticExportPhase.TIMED_OUT,c.state.phase);assertTrue(c.state.releaseUnconfirmed)
            assertFalse(fence.acquire("other"));release.countDown()
            assertTrue(drained.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(DiagnosticExportPhase.TIMED_OUT,c.state.phase)
            assertFalse(c.state.releaseUnconfirmed);assertTrue(fence.acquire("other"));fence.release("other")
        } finally {release.countDown();c.close();pool.shutdownNow()}
    }
    @Test fun cancelledOldTimerCannotExpireNewPhaseOrNewRequest() {
        val q=Queue();val clock=Clock();val choices=mutableListOf<String>()
        val c=DiagnosticExportController({DiagnosticSnapshotResult.Ready(DiagnosticSnapshot("safe\n"))},q,{it()},{},choices::add,{},
            monotonicMillis={clock.now},schedule=clock::schedule)
        c.begin();val preparationTimer=clock.alarms.last().callback;q.drain()
        clock.now=DiagnosticExportController.IO_TIMEOUT_MILLIS
        val phaseAlarmCount=clock.alarms.size;preparationTimer()
        assertEquals(phaseAlarmCount,clock.alarms.size)
        assertEquals(DiagnosticExportPhase.CHOOSING,c.state.phase)
        val oldPickerTimer=clock.alarms.last().callback;c.selected(choices.single(),null)
        c.begin();q.drain();val requestAlarmCount=clock.alarms.size;oldPickerTimer();preparationTimer()
        assertEquals(requestAlarmCount,clock.alarms.size)
        assertEquals(DiagnosticExportPhase.CHOOSING,c.state.phase);assertEquals(2,choices.size);c.close()
    }

}

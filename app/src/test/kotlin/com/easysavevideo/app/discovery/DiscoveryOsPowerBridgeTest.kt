package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryOsPowerBridgeTest {
    private class Source : DiscoveryOsSignalSource {
        val listeners = mutableListOf<DiscoveryOsSignalSource.Listener>()
        var opened = 0; var closed = 0; var openAction: (() -> Unit)? = null
        var failure: Exception? = null; var closeFails = false
        override fun open(listener: DiscoveryOsSignalSource.Listener): AutoCloseable {
            opened++; listeners += listener; openAction?.invoke(); failure?.let { throw it }
            return AutoCloseable { closed++; if (closeFails) throw IllegalStateException() }
        }
    }
    private class Timer : DiscoverySignalTimer {
        data class Job(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var time = 1000L; var reject = false; val jobs = mutableListOf<Job>()
        val live: List<Job> get() = jobs.filter { !it.cancelled }
        override fun schedule(delayMs: Long, action: () -> Unit): DiscoverySignalCancellation {
            check(!reject); assertTrue(delayMs > 0)
            val job = Job(time + delayMs, action); jobs += job
            return DiscoverySignalCancellation { job.cancelled = true }
        }
        fun fire(job: Job = live.single()) { job.cancelled = true; job.action() }
    }
    private class Rig(val ttl: Long = 100) {
        val source = Source(); val timer = Timer(); val seen = mutableListOf<DiscoveryOsPowerSnapshot>()
        val bridge = DiscoveryOsPowerBridge(source, { timer.time }, timer, seen::add, ttl)
        val listener get() = source.listeners.last()
        val state get() = bridge.snapshot()
        fun start() = bridge.setActive(true)
        fun healthy(network: Any = "one") {
            listener.battery(80, 100, false); listener.saver(false); listener.thermal(ThermalSignal.NORMAL)
            listener.available(network); listener.capabilities(network, true, true, true, true); listener.blocked(network, false)
        }
    }
    @Test fun startsEnabledUnknownRatherThanUnconnectedHealthy() {
        val r=Rig(); assertTrue(r.state.signals.enabled); assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery)
        assertEquals(OsObservationState.STOPPED,r.state.state); assertEquals(0,r.source.opened)
    }
    @Test fun fullFreshObservationAllowsOnlyExistingPolicy() {
        val r=Rig();r.start();r.healthy();assertEquals(BatterySignal.NORMAL,r.state.signals.battery)
        assertEquals(ThermalSignal.NORMAL,r.state.signals.thermal);assertEquals(NetworkSignal.UNMETERED,r.state.signals.network)
        assertEquals(1,r.timer.live.size)
    }
    @Test fun lifecycleIdempotenceKeepsExactlyOneRegistration() {
        val r=Rig();repeat(100){r.start()};assertEquals(1,r.source.opened)
        repeat(100){r.bridge.setActive(false)};assertEquals(1,r.source.closed)
        assertEquals(0,r.timer.live.size);assertEquals(OsObservationState.STOPPED,r.state.state)
    }
    @Test fun closeStopsPermanentlyAndIgnoresLateCallbacks() {
        val r=Rig();r.start();r.healthy();val l=r.listener;r.bridge.close();r.bridge.close()
        r.bridge.setActive(true);r.bridge.refresh();l.battery(99,100,false)
        assertEquals(1,r.source.opened);assertEquals(1,r.source.closed);assertEquals(OsObservationState.CLOSED,r.state.state)
        assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery)
    }
    @Test fun refreshReplacesGenerationAndRejectsOldCallbacks() {
        val r=Rig();r.start();r.healthy();val old=r.listener;r.bridge.refresh();r.healthy("new")
        val expected=r.state;old.battery(1,100,true);old.thermal(ThermalSignal.SEVERE);old.lost("new")
        assertEquals(expected,r.state);assertEquals(2,r.source.opened);assertEquals(1,r.source.closed)
    }
    @Test fun stoppedRefreshCannotRegister() {
        val r=Rig();r.bridge.refresh();assertEquals(0,r.source.opened)
    }
    @Test fun expiryOccursAtBoundaryAndRetiresEverySignal() {
        val r=Rig();r.start();r.healthy();r.timer.time=1100;r.timer.fire()
        assertEquals(OsSignalAge.EXPIRED,r.state.battery);assertEquals(OsSignalAge.EXPIRED,r.state.network)
        assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery);assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
        assertEquals(0,r.timer.live.size)
    }
    @Test fun healthySteadyStateRequiresNewObservationAfterExpiry() {
        val r=Rig();r.start();r.healthy();r.timer.time=1101;r.timer.fire();r.bridge.revalidate()
        assertEquals(ThermalSignal.UNKNOWN,r.state.signals.thermal);assertEquals(1,r.source.opened)
    }
    @Test fun explicitRevalidationCatchesDelayedTimerBeforeCommands() {
        val r=Rig();r.start();r.healthy();r.timer.time=5000;r.bridge.revalidate()
        assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network);assertEquals(OsSignalAge.EXPIRED,r.state.network)
    }
    @Test fun earlyTimerRearmsRemainingTime() {
        val r=Rig();r.start();r.healthy();r.timer.time=1040;r.timer.fire()
        assertEquals(1100L,r.timer.live.single().at);assertEquals(BatterySignal.NORMAL,r.state.signals.battery)
    }
    @Test fun cancelledTimerCannotExpireNewerObservation() {
        val r=Rig();r.start();r.healthy();val old=r.timer.live.single();r.timer.time=1050;r.healthy()
        r.timer.time=1100;r.timer.fire(old);assertEquals(BatterySignal.NORMAL,r.state.signals.battery)
        assertEquals(1150L,r.timer.live.single().at)
    }
    @Test fun identicalSignalRenewalDoesNotPublishOrRefreshOtherChannels() {
        val r=Rig();r.start();r.healthy();val count=r.seen.size;r.timer.time=1050;r.listener.battery(80,100,false)
        assertEquals(count,r.seen.size);r.timer.time=1100;r.timer.fire()
        assertEquals(OsSignalAge.FRESH,r.state.battery);assertEquals(OsSignalAge.EXPIRED,r.state.saver)
        assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery)
    }
    @Test fun partialNetworkRenewalCannotRefreshBlockedAge() {
        val r=Rig();r.start();r.healthy();r.timer.time=1050;r.listener.capabilities("one",true,true,true,true)
        r.timer.time=1100;r.timer.fire();assertEquals(OsSignalAge.EXPIRED,r.state.network)
        assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
    }
    @Test fun networkSwitchWaitsForBothNewCapabilitiesAndUnblocked() {
        val r=Rig();r.start();r.healthy();r.listener.available("two")
        assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
        r.listener.capabilities("two",true,true,true,true);assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
        r.listener.blocked("one",false);assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
        r.listener.blocked("two",false);assertEquals(NetworkSignal.UNMETERED,r.state.signals.network)
        r.listener.lost("one");assertEquals(NetworkSignal.UNMETERED,r.state.signals.network)
    }
    @Test fun callbackOrderingCannotAdoptCapabilitiesBeforeAvailable() {
        val r=Rig();r.start();r.listener.capabilities("one",true,true,true,true);r.listener.blocked("one",false)
        r.listener.available("one");assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
    }
    @Test fun blockedUnvalidatedSuspendedOrLostNetworkStops() {
        val r=Rig();r.start();r.healthy();r.listener.blocked("one",true);assertEquals(NetworkSignal.OFFLINE,r.state.signals.network)
        r.listener.blocked("one",false);r.listener.capabilities("one",true,false,true,true)
        assertEquals(NetworkSignal.OFFLINE,r.state.signals.network)
        r.listener.capabilities("one",true,true,false,true);assertEquals(NetworkSignal.OFFLINE,r.state.signals.network)
        r.listener.capabilities("one",false,true,true,true);assertEquals(NetworkSignal.OFFLINE,r.state.signals.network)
        r.listener.lost("one");assertEquals(NetworkSignal.OFFLINE,r.state.signals.network)
    }
    @Test fun meteredNetworkAndSaverOnlyConserve() {
        val r=Rig();r.start();r.healthy();r.listener.capabilities("one",true,true,true,false)
        assertEquals(NetworkSignal.METERED,r.state.signals.network);r.listener.saver(true)
        assertEquals(BatterySignal.LOW,r.state.signals.battery)
    }
    @Test fun malformedBatteryNeverMapsToHealthyOrFakeZero() {
        val r=Rig();r.start();r.healthy()
        for ((level,scale,low) in listOf(Triple(-1,100,false),Triple(101,100,false),Triple(50,0,false),Triple(50,-1,false),Triple(50,100,null))) {
            r.listener.battery(level,scale,low);assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery)
        }
    }
    @Test fun batteryThresholdsUseExactRatioWithoutIntegerOverflow() {
        val r=Rig();r.start();r.healthy()
        for ((level,scale,expected) in listOf(Triple(5,100,BatterySignal.CRITICAL),Triple(20,100,BatterySignal.LOW),Triple(21,100,BatterySignal.NORMAL),Triple(Int.MAX_VALUE,Int.MAX_VALUE,BatterySignal.NORMAL))) {
            r.listener.battery(level,scale,false);assertEquals(expected,r.state.signals.battery)
        }
        r.listener.battery(80,100,true);assertEquals(BatterySignal.LOW,r.state.signals.battery)
    }
    @Test fun saverCannotHideCriticalOrUnknownBattery() {
        val r=Rig();r.start();r.healthy();r.listener.battery(1,100,false);r.listener.saver(true)
        assertEquals(BatterySignal.CRITICAL,r.state.signals.battery)
        r.listener.battery(-1,100,false);assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery)
        r.listener.battery(80,100,false);r.listener.saver(null);assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery)
    }
    @Test fun allCallbacksBeforeOpenReturnsStayUnknownUntilRegistrationCompletes() {
        val r=Rig();r.source.openAction={r.healthy();assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)}
        r.start();assertEquals(NetworkSignal.UNMETERED,r.state.signals.network)
    }
    @Test fun knownCleanOpenFailureIsRetriableOnlyExplicitly() {
        val r=Rig();r.source.failure=DiscoverySignalSourceFailure(false);r.start()
        repeat(100){r.start();r.bridge.revalidate()};assertEquals(1,r.source.opened)
        assertEquals(OsObservationState.UNAVAILABLE,r.state.state)
        r.source.failure=null;r.bridge.refresh();assertEquals(2,r.source.opened);r.healthy()
        assertEquals(NetworkSignal.UNMETERED,r.state.signals.network)
    }
    @Test fun unknownOrUncertainOpenFailurePoisonsFurtherRegistration() {
        for (failure in listOf(IllegalStateException(),DiscoverySignalSourceFailure(true))) {
            val r=Rig();r.source.failure=failure;r.start();r.source.failure=null
            repeat(100){r.bridge.refresh();r.bridge.setActive(false);r.start()};assertEquals(1,r.source.opened)
            assertEquals(OsObservationState.UNAVAILABLE,r.state.state)
        }
    }
    @Test fun cleanupFailureCannotAccumulateSubscriptions() {
        val r=Rig();r.start();r.healthy();r.source.closeFails=true;r.bridge.refresh()
        repeat(100){r.bridge.refresh();r.bridge.setActive(false);r.start()}
        assertEquals(1,r.source.opened);assertEquals(1,r.source.closed);assertEquals(0,r.timer.live.size)
    }
    @Test fun timerRejectionFailsClosedAndCleansSource() {
        val r=Rig();r.start();r.timer.reject=true;r.healthy()
        assertEquals(OsObservationState.UNAVAILABLE,r.state.state);assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
        assertEquals(1,r.source.closed);assertEquals(0,r.timer.live.size)
    }
    @Test fun clockRollbackAndSaturationCannotProduceEternalFreshness() {
        val r=Rig();r.start();r.healthy();r.timer.time=0;r.bridge.revalidate()
        assertEquals(NetworkSignal.UNKNOWN,r.state.signals.network)
        r.timer.time=Long.MAX_VALUE;r.listener.battery(80,100,false)
        assertEquals(OsObservationState.UNAVAILABLE,r.state.state);assertEquals(0,r.timer.live.size)
    }
    @Test fun floodKeepsOneTimerAndBoundedSnapshotCountForIdenticalValues() {
        val r=Rig();r.start();r.healthy();val count=r.seen.size
        repeat(10_000){r.listener.battery(80,100,false);assertEquals(1,r.timer.live.size)}
        assertEquals(count,r.seen.size)
    }
    @Test fun stopDuringRegistrationInvalidatesBeforeCleanupAndNewActivation() {
        val r=Rig();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        r.source.openAction={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val thread=Thread{r.start()};thread.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        r.bridge.setActive(false);assertEquals(OsObservationState.STOPPED,r.state.state)
        r.source.listeners.single().thermal(ThermalSignal.NORMAL)
        assertEquals(ThermalSignal.UNKNOWN,r.state.signals.thermal)
        release.countDown();thread.join(5000);assertFalse(thread.isAlive);assertEquals(1,r.source.closed)
        r.source.openAction=null;r.start();assertEquals(2,r.source.opened)
    }
    @Test fun refreshDuringOpenClosesOldLeaseBeforeReplacement() {
        val r=Rig();var calls=0;r.source.openAction={if(calls++==0)r.bridge.refresh()}
        r.start();assertEquals(2,r.source.opened);assertEquals(1,r.source.closed)
        r.source.listeners.first().battery(99,100,false);assertEquals(BatterySignal.UNKNOWN,r.state.signals.battery)
    }
    @Test fun snapshotsAndFailuresHaveNoObservationDiagnostics() {
        assertEquals("DiscoveryOsPowerSnapshot([redacted])",DiscoveryOsPowerSnapshot().toString())
        assertNull(DiscoverySignalSourceFailure(true).message)
    }
}

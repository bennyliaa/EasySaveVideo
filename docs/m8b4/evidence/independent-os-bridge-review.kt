package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real production reducer under independent host-only source/timer doubles. */
class IndependentOsBridgeReviewTest {
    private class Source : DiscoveryOsSignalSource {
        val listeners = mutableListOf<DiscoveryOsSignalSource.Listener>()
        var closes = 0; var failure: Exception? = null; var closeFailure = false
        var onOpen: ((DiscoveryOsSignalSource.Listener) -> Unit)? = null
        override fun open(listener: DiscoveryOsSignalSource.Listener): AutoCloseable {
            listeners += listener; onOpen?.invoke(listener); failure?.let { throw it }
            return AutoCloseable { closes++; if (closeFailure) throw IllegalStateException() }
        }
    }
    private class Timer : DiscoverySignalTimer {
        data class Item(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val items = mutableListOf<Item>(); var fail = false; var maxLive = 0
        override fun schedule(delayMs: Long, action: () -> Unit): DiscoverySignalCancellation {
            check(!fail); val item = Item(delayMs, action); items += item
            maxLive = maxOf(maxLive, items.count { !it.cancelled })
            return DiscoverySignalCancellation { item.cancelled = true }
        }
        fun fire(item: Item = items.last()) { item.cancelled = true; item.action() }
    }
    private class Rig(val source: Source = Source(), val ttl: Long = 100) : AutoCloseable {
        var time = 1_000L; val timer = Timer(); val seen = mutableListOf<DiscoveryOsPowerSnapshot>()
        val bridge = DiscoveryOsPowerBridge(source, { time }, timer, seen::add, ttl)
        val listener get() = source.listeners.last()
        val snapshot get() = bridge.snapshot()
        fun start() = bridge.setActive(true)
        fun healthy(network: Any = "n") {
            listener.battery(80,100,false); listener.saver(false); listener.thermal(ThermalSignal.NORMAL)
            listener.available(network); listener.capabilities(network,true,true,true,true); listener.blocked(network,false)
        }
        override fun close() = bridge.close()
    }
    private fun DiscoveryOsSignalSource.Listener.healthy(n: Any = "seed") {
        battery(90,100,false); saver(false); thermal(ThermalSignal.NORMAL)
        available(n); capabilities(n,true,true,true,true); blocked(n,false)
    }
    private fun assertPaused(s: DiscoveryOsPowerSnapshot) = assertFalse(DiscoveryPowerPolicy.evaluate(s.signals,
        com.easysavevideo.core.model.DiscoveryMode.BALANCED).allowsRequests)

    @Test fun lifecycleIdempotenceAndClosedNoReopen() = Rig().use { r ->
        assertPaused(r.snapshot); r.start(); r.start(); assertEquals(1,r.source.listeners.size)
        r.healthy(); assertEquals(NetworkSignal.UNMETERED,r.snapshot.signals.network)
        r.bridge.setActive(false); r.bridge.setActive(false); assertEquals(1,r.source.closes); assertPaused(r.snapshot)
        assertEquals(OsObservationState.STOPPED,r.snapshot.state); assertEquals(0,r.timer.items.count { !it.cancelled })
        r.bridge.close(); r.bridge.refresh(); r.start(); assertEquals(1,r.source.listeners.size)
        assertEquals(OsObservationState.CLOSED,r.snapshot.state)
    }
    @Test fun allChannelsRequiredAndStartupCallbacksCannotPublishBeforeOpenSucceeds() {
        val source = Source(); val seen = mutableListOf<DiscoveryOsPowerSnapshot>()
        val timer = Timer(); lateinit var bridge: DiscoveryOsPowerBridge
        source.onOpen = { it.healthy(); assertPaused(bridge.snapshot()) }
        bridge = DiscoveryOsPowerBridge(source,{1000},timer,seen::add,100)
        bridge.setActive(true); assertEquals(NetworkSignal.UNMETERED,bridge.snapshot().signals.network)
        assertEquals(1,seen.count { it.signals.network == NetworkSignal.UNMETERED }); bridge.close()
    }
    @Test fun invalidBatteryFieldsAndOverflowSafeThresholds() = Rig().use { r ->
        r.start(); r.healthy()
        for ((level,scale,low) in listOf(Triple(-1,100,false),Triple(1,0,false),Triple(101,100,false),Triple(50,100,null))) {
            r.listener.battery(level,scale,low); assertEquals(BatterySignal.UNKNOWN,r.snapshot.signals.battery)
        }
        for ((level,expected) in listOf(0 to BatterySignal.CRITICAL,5 to BatterySignal.CRITICAL,6 to BatterySignal.LOW,
            20 to BatterySignal.LOW,21 to BatterySignal.NORMAL,100 to BatterySignal.NORMAL)) {
            r.listener.battery(level,100,false); assertEquals(expected,r.snapshot.signals.battery)
        }
        r.listener.battery(Int.MAX_VALUE,Int.MAX_VALUE,false); assertEquals(BatterySignal.NORMAL,r.snapshot.signals.battery)
        r.listener.battery(90,100,true); assertEquals(BatterySignal.LOW,r.snapshot.signals.battery)
    }
    @Test fun saverCannotOverrideCriticalOrUnknownBattery() = Rig().use { r ->
        r.start(); r.healthy(); r.listener.saver(true); assertEquals(BatterySignal.LOW,r.snapshot.signals.battery)
        r.listener.battery(1,100,false); assertEquals(BatterySignal.CRITICAL,r.snapshot.signals.battery)
        r.listener.battery(-1,100,false); assertEquals(BatterySignal.UNKNOWN,r.snapshot.signals.battery)
        r.listener.battery(90,100,false); r.listener.saver(null); assertEquals(BatterySignal.UNKNOWN,r.snapshot.signals.battery)
    }
    @Test fun defaultNetworkTransitionRequiresMatchingCapabilitiesAndUnblockedKnowledge() = Rig().use { r ->
        r.start(); r.healthy("a"); r.listener.available("b"); assertEquals(NetworkSignal.UNKNOWN,r.snapshot.signals.network)
        r.listener.blocked("a",false); r.listener.capabilities("a",true,true,true,true); assertEquals(NetworkSignal.UNKNOWN,r.snapshot.signals.network)
        r.listener.capabilities("b",true,true,true,true); assertEquals(NetworkSignal.UNKNOWN,r.snapshot.signals.network)
        r.listener.blocked("b",false); assertEquals(NetworkSignal.UNMETERED,r.snapshot.signals.network)
        r.listener.lost("a"); assertEquals(NetworkSignal.UNMETERED,r.snapshot.signals.network)
        r.listener.lost("b"); assertEquals(NetworkSignal.OFFLINE,r.snapshot.signals.network)
        r.listener.capabilities("b",true,true,true,true); r.listener.blocked("b",false); assertEquals(NetworkSignal.OFFLINE,r.snapshot.signals.network)
    }
    @Test fun unusableNetworkCombinationsStayConservativeRegardlessOfMetering() = Rig().use { r ->
        r.start(); r.healthy()
        for (internet in listOf(false,true)) for (validated in listOf(false,true)) for (suspended in listOf(false,true))
            for (unmetered in listOf(false,true)) for (blocked in listOf(false,true)) {
                r.listener.capabilities("n",internet,validated,suspended,unmetered); r.listener.blocked("n",blocked)
                assertEquals(if (!internet || !validated || !suspended || blocked) NetworkSignal.OFFLINE
                    else if (unmetered) NetworkSignal.UNMETERED else NetworkSignal.METERED,r.snapshot.signals.network)
            }
    }
    @Test fun exactAgeBoundaryAndSameValueRenewsOnlyItsOwnChannel() = Rig().use { r ->
        r.start(); r.healthy(); r.time += 50; r.listener.battery(80,100,false)
        r.time += 49; r.timer.fire(); assertEquals(OsSignalAge.FRESH,r.snapshot.battery); assertEquals(OsSignalAge.FRESH,r.snapshot.thermal)
        assertEquals(1L,r.timer.items.last().delay)
        r.time++; r.timer.fire(); assertEquals(OsSignalAge.FRESH,r.snapshot.battery)
        assertEquals(OsSignalAge.EXPIRED,r.snapshot.saver); assertEquals(OsSignalAge.EXPIRED,r.snapshot.thermal)
        assertEquals(OsSignalAge.EXPIRED,r.snapshot.network); assertPaused(r.snapshot)
        r.time += 50; r.timer.fire(); assertEquals(OsSignalAge.EXPIRED,r.snapshot.battery)
        assertEquals(0,r.timer.items.count { !it.cancelled })
    }
    @Test fun capabilitiesRenewalCannotRefreshOldUnblockSample() = Rig().use { r ->
        r.start(); r.healthy(); r.time += 50; r.listener.capabilities("n",true,true,true,true)
        r.time += 50; r.timer.fire(); assertEquals(OsSignalAge.EXPIRED,r.snapshot.network); assertEquals(NetworkSignal.UNKNOWN,r.snapshot.signals.network)
        r.listener.blocked("n",false); assertEquals(NetworkSignal.UNMETERED,r.snapshot.signals.network)
    }
    @Test fun refreshRejectsEveryOldCallbackAndCancelledExpiry() = Rig().use { r ->
        r.start(); r.healthy(); val old = r.listener; val oldTimer = r.timer.items.last()
        r.bridge.refresh(); assertEquals(1,r.source.closes); assertEquals(2,r.source.listeners.size); assertPaused(r.snapshot)
        r.healthy("new"); val expected = r.snapshot
        old.battery(0,100,true); old.saver(true); old.thermal(ThermalSignal.SEVERE); old.available("obsolete")
        old.capabilities("obsolete",false,false,false,false); old.blocked("obsolete",true); old.lost("new")
        r.timer.fire(oldTimer); assertEquals(expected,r.snapshot)
    }
    @Test fun canceledEarlierTimerCannotExpireANewerSameValueSample() = Rig().use { r ->
        r.start(); r.healthy(); val old = r.timer.items.last(); r.time += 50; r.healthy()
        r.time += 50; r.timer.fire(old); assertEquals(OsSignalAge.FRESH,r.snapshot.thermal)
        r.time += 50; r.timer.fire(); assertEquals(OsSignalAge.EXPIRED,r.snapshot.thermal)
    }
    @Test fun callbacksAreBoundedToOneLiveExpiryAcrossFloodAndNoDuplicateSnapshots() = Rig().use { r ->
        r.start(); r.healthy(); val count = r.seen.size
        repeat(10_000) { r.listener.thermal(ThermalSignal.NORMAL) }
        assertEquals(count,r.seen.size); assertEquals(1,r.timer.maxLive); assertEquals(1,r.timer.items.count { !it.cancelled })
    }
    @Test fun knownCleanRegistrationFailureCanRetryOnlyOnExplicitRefresh() = Rig().use { r ->
        r.source.failure = DiscoverySignalSourceFailure(false); r.start()
        assertEquals(OsObservationState.UNAVAILABLE,r.snapshot.state); r.start(); assertEquals(1,r.source.listeners.size)
        r.source.failure = null; r.bridge.refresh(); r.healthy(); assertEquals(NetworkSignal.UNMETERED,r.snapshot.signals.network)
    }
    @Test fun uncertainFailureOrUnregisterFailurePermanentlyPoisonsNewRegistration() {
        for (failure in listOf(DiscoverySignalSourceFailure(true),IllegalStateException())) Rig().use { r ->
            r.source.failure = failure; r.start(); r.source.failure = null; r.bridge.refresh(); r.bridge.setActive(false); r.start()
            assertEquals(1,r.source.listeners.size); assertEquals(OsObservationState.UNAVAILABLE,r.snapshot.state); assertPaused(r.snapshot)
        }
        Rig().use { r -> r.start(); r.healthy(); r.source.closeFailure = true; r.bridge.refresh(); r.source.closeFailure = false
            r.bridge.refresh(); assertEquals(1,r.source.listeners.size); assertEquals(OsObservationState.UNAVAILABLE,r.snapshot.state) }
    }
    @Test fun registrationFailureAfterSynchronousHealthyCallbacksNeverLeaksHealthySnapshot() = Rig().use { r ->
        r.source.onOpen = { it.healthy() }; r.source.failure = DiscoverySignalSourceFailure(false); r.start()
        assertTrue(r.seen.all { DiscoveryPowerPolicy.evaluate(it.signals,com.easysavevideo.core.model.DiscoveryMode.BALANCED).allowsRequests.not() })
    }
    @Test fun timerFailureClosesRegistrationAndCanRecoverByExplicitRefresh() = Rig().use { r ->
        r.start(); r.timer.fail = true; r.healthy(); assertEquals(OsObservationState.UNAVAILABLE,r.snapshot.state)
        assertEquals(1,r.source.closes); assertPaused(r.snapshot); r.timer.fail = false; r.bridge.refresh(); r.healthy()
        assertEquals(NetworkSignal.UNMETERED,r.snapshot.signals.network)
    }
    @Test fun stopWhileOpenIsBlockedInvalidatesBeforeLateCallbacksAndClosesAcquiredLease() = Rig().use { r ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        r.source.onOpen = { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); it.healthy() }
        val thread = Thread { r.start() }; thread.start(); assertTrue(entered.await(5,TimeUnit.SECONDS))
        r.bridge.setActive(false); assertEquals(OsObservationState.STOPPED,r.snapshot.state)
        release.countDown(); thread.join(5_000); assertFalse(thread.isAlive); assertEquals(1,r.source.closes); assertPaused(r.snapshot)
    }
    @Test fun refreshWhileOpenIsBlockedNeverOverlapsRegistrations() = Rig().use { r ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var opens = 0
        r.source.onOpen = { opens++; if (opens == 1) { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) }; it.healthy() }
        val thread = Thread { r.start() }; thread.start(); assertTrue(entered.await(5,TimeUnit.SECONDS)); r.bridge.refresh()
        assertEquals(1,opens); release.countDown(); thread.join(5_000); assertFalse(thread.isAlive)
        assertEquals(2,opens); assertEquals(1,r.source.closes); assertEquals(NetworkSignal.UNMETERED,r.snapshot.signals.network)
    }
    @Test fun delayedTimerAndBackwardClockDoNotCreatePermanentHealthySamples() = Rig().use { r ->
        r.start(); r.healthy(); r.time += 50_000; r.timer.fire(); assertPaused(r.snapshot)
        r.bridge.refresh(); r.healthy(); r.time--; r.timer.fire(); assertPaused(r.snapshot)
    }
    @Test fun unrepresentableClockFailsClosedAndOutOfRangeTtlIsRejected() = Rig().use { r ->
        r.start(); r.time = Long.MAX_VALUE; r.listener.thermal(ThermalSignal.NORMAL)
        assertEquals(OsObservationState.UNAVAILABLE,r.snapshot.state); assertPaused(r.snapshot); assertEquals(1,r.source.closes)
        for (ttl in listOf(0L,-1L,3_600_001L)) {
            assertThrows(IllegalArgumentException::class.java) { DiscoveryOsPowerBridge(Source(),{0},Timer(),{},ttl) }
        }
    }
    @Test fun revalidationDetectsExpiryBeforeDelayedTimerDispatchWithoutReadingSource() = Rig().use { r ->
        r.start(); r.healthy(); val count = r.source.listeners.size; r.time += 100
        r.bridge.revalidate(); assertPaused(r.snapshot); assertEquals(OsSignalAge.EXPIRED,r.snapshot.thermal)
        assertEquals(count,r.source.listeners.size); assertEquals(0,r.timer.items.count { !it.cancelled })
    }
    @Test fun revalidationOnStoppedOrClosedBridgeDoesNotAcquireResources() = Rig().use { r ->
        repeat(100) { r.bridge.revalidate() }; assertTrue(r.source.listeners.isEmpty()); assertTrue(r.timer.items.isEmpty())
        r.bridge.close(); repeat(100) { r.bridge.revalidate() }; assertEquals(OsObservationState.CLOSED,r.snapshot.state)
        assertTrue(r.source.listeners.isEmpty())
    }
    @Test fun repeatedInvalidationDuringOpenUsesBoundedCallStackAndOnlyOneLease() {
        val source = Source(); lateinit var bridge: DiscoveryOsPowerBridge; var opens = 0
        source.onOpen = { opens++; if (opens < 10_000) bridge.refresh() }
        bridge = DiscoveryOsPowerBridge(source,{1000},Timer(),{},100)
        try { bridge.setActive(true); assertEquals(10_000,opens); assertEquals(9_999,source.closes) }
        finally { bridge.close() }
    }

}

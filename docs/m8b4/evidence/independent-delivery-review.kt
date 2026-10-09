package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.*
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IndependentDeliveryReviewTest {
    private class Queue {
        val work = ArrayDeque<() -> Unit>(); var max = 0; var reject = false
        fun offer(action: () -> Unit): Boolean { if (reject) return false; work += action; max = maxOf(max,work.size); return true }
        fun once() = work.removeFirst().invoke()
        fun drain() { while (work.isNotEmpty()) once() }
    }
    @Test fun floodCoalescesToLatestAndYieldsBetweenDeliveries() {
        val q = Queue(); val seen = mutableListOf<Int>(); lateinit var delivery: DiscoveryLatestDelivery<Int>
        delivery = DiscoveryLatestDelivery(q::offer) { value -> seen += value; if (value == 9_999) repeat(10_000) { delivery.offer(10_000+it) } }
        repeat(10_000) { assertTrue(delivery.offer(it)) }
        assertEquals(1,q.work.size); q.once(); assertEquals(listOf(9_999),seen); assertEquals(1,q.work.size)
        q.once(); assertEquals(listOf(9_999,19_999),seen); assertEquals(1,q.max); assertTrue(q.work.isEmpty())
    }
    @Test fun offerDuringBlockedConsumerIsLatestOnlyAndDoesNotBlockProducer() {
        val q = Queue(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val seen = mutableListOf<Int>()
        val delivery = DiscoveryLatestDelivery<Int>(q::offer) { value -> seen += value; if (value == 1) { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)) } }
        delivery.offer(1); val t = Thread { q.once() }; t.start(); assertTrue(entered.await(5,TimeUnit.SECONDS))
        repeat(10_000) { delivery.offer(it+2) }; assertTrue(q.work.isEmpty()); release.countDown(); t.join(5_000); assertFalse(t.isAlive)
        assertEquals(1,q.work.size); q.drain(); assertEquals(listOf(1,10_001),seen); assertEquals(1,q.max)
    }
    @Test fun closeDiscardsPendingAndLateDrainsAreHarmless() {
        val q = Queue(); val seen = mutableListOf<Int>(); val delivery = DiscoveryLatestDelivery<Int>(q::offer,seen::add)
        delivery.offer(1); delivery.close(); assertFalse(delivery.offer(2)); q.drain(); assertTrue(seen.isEmpty())
    }
    @Test fun initialEnqueueRejectionAndExceptionPermanentlyRefuseOffers() {
        for (throws in listOf(false,true)) {
            var attempts = 0
            val delivery = DiscoveryLatestDelivery<Int>({ attempts++; if (throws) error("rejected"); false },{ error("must not consume") })
            assertFalse(delivery.offer(1)); assertFalse(delivery.offer(2)); assertEquals(1,attempts)
        }
    }
    @Test fun rejectedFollowupClosesWithoutUnboundedRetry() {
        val q = Queue(); val seen = mutableListOf<Int>(); lateinit var delivery: DiscoveryLatestDelivery<Int>
        delivery = DiscoveryLatestDelivery(q::offer) { seen += it; delivery.offer(2); q.reject = true }
        delivery.offer(1); q.once(); assertEquals(listOf(1),seen); assertTrue(q.work.isEmpty()); assertFalse(delivery.offer(3))
    }
    @Test fun throwingConsumerStillYieldsLatestPendingValue() {
        val q = Queue(); val seen = mutableListOf<Int>(); lateinit var delivery: DiscoveryLatestDelivery<Int>
        delivery = DiscoveryLatestDelivery(q::offer) { if (it == 1) { delivery.offer(2); error("consumer") }; seen += it }
        delivery.offer(1); assertThrows(IllegalStateException::class.java) { q.once() }; q.once(); assertEquals(listOf(2),seen)
    }
    private class Source : DiscoveryOsSignalSource {
        lateinit var listener: DiscoveryOsSignalSource.Listener
        override fun open(listener: DiscoveryOsSignalSource.Listener): AutoCloseable { this.listener = listener; return AutoCloseable {} }
        fun healthy() { listener.battery(80,100,false); listener.saver(false); listener.thermal(ThermalSignal.NORMAL)
            listener.available("n"); listener.capabilities("n",true,true,true,true); listener.blocked("n",false) }
    }
    private class Runtime : ProviderRuntime {
        data class Call(val operation: DiscoveryOperation,val callback: (ProviderOutcome) -> Unit)
        val calls = mutableListOf<Call>()
        override fun execute(provider: VideoSearchProvider,request: SearchRequest,operation: DiscoveryOperation,callback: (ProviderOutcome) -> Unit): CancellationHandle {
            calls += Call(operation,callback); return CancellationHandle {}
        }
    }
    private class Rig : AutoCloseable {
        data class Change(val signals: DiscoveryPowerSignals,val generation: Long)
        val q = Queue(); val runtime = Runtime(); val source = Source(); var time = 1000L; val timers = mutableListOf<() -> Unit>()
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(listOf(object : VideoSearchProvider {
            override val descriptor = ProviderDescriptor("independent","1")
            override fun search(request: SearchRequest,operation: DiscoveryOperation): ProviderPage = error("host runtime only")
        })),runtime,InMemoryDiscoveryStore(),object : DiscoveryTimer { override fun schedule(delayMillis: Long,action: () -> Unit) = CancellationHandle {} },EpochClock { time })
        val delivery = DiscoveryLatestDelivery<Change>(q::offer) { coordinator.setPowerSignals(it.signals,it.generation) }
        val bridge = DiscoveryOsPowerBridge(source,{time},DiscoverySignalTimer { _,action -> timers += action; DiscoverySignalCancellation {} },
            { delivery.offer(Change(it.signals,coordinator.suspendPowerImmediately())) },100)
        init { coordinator.setPowerSignals(DiscoveryPowerSignals(enabled=true)); bridge.setActive(true); source.healthy(); q.drain() }
        fun explicit() { coordinator.setVisibility(true,true); coordinator.start("independent",SearchProfile("p","bounded host query")) }
        override fun close() { bridge.close(); delivery.close(); coordinator.close() }
    }
    @Test fun restrictiveSignalFencesInFlightBeforeQueuedPolicyAndLateResultsCannotCommit() = Rig().use { r ->
        r.explicit(); assertEquals(1,r.runtime.calls.size); val old = r.runtime.calls.single()
        r.source.listener.thermal(ThermalSignal.SEVERE)
        assertTrue(old.operation.cancellation.isCancelled); assertTrue(r.coordinator.status().powerPolicyUpdatePending)
        old.callback(ProviderOutcome.Success(ValidatedProviderPage(emptyList(),null,null,true)))
        assertEquals(0,r.coordinator.status().availableCount); r.q.drain()
        assertFalse(r.coordinator.status().roundActive); assertEquals(1,r.coordinator.status().requestsThisRound)
    }
    @Test fun relaxationAndRefreshNeverGenerateDemandOrRefundSpentAttempt() = Rig().use { r ->
        r.explicit(); r.source.listener.thermal(ThermalSignal.SEVERE); r.q.drain()
        r.source.listener.thermal(ThermalSignal.NORMAL); r.q.drain(); r.coordinator.setVisibility(true,true)
        assertEquals(1,r.runtime.calls.size); assertEquals(1,r.coordinator.status().requestsThisRound); assertFalse(r.coordinator.status().roundActive)
        r.bridge.refresh(); r.source.healthy(); r.q.drain(); assertEquals(1,r.runtime.calls.size); assertEquals(1,r.coordinator.status().requestsThisRound)
        r.coordinator.requestMore(); assertEquals(2,r.runtime.calls.size)
    }
    @Test fun rejectedDrainKeepsImmediateFenceAndCannotRefillBudget() = Rig().use { r ->
        r.explicit(); r.q.reject = true; r.source.listener.blocked("n",true); assertTrue(r.coordinator.status().powerPolicyUpdatePending)
        r.source.listener.blocked("n",false); r.coordinator.requestMore(); r.coordinator.setVisibility(true,true)
        assertEquals(1,r.runtime.calls.size); assertEquals(1,r.coordinator.status().requestsThisRound)
        assertTrue(r.runtime.calls.single().operation.cancellation.isCancelled)
    }
    @Test fun signalRelaxationCannotClearIndependentSaveOrVisibilityVeto() = Rig().use { r ->
        r.explicit(); val life = r.coordinator.suspendImmediately(); r.source.listener.thermal(ThermalSignal.SEVERE); r.q.drain()
        r.coordinator.setPriority(true,false); r.coordinator.setVisibility(true,true,life)
        r.source.listener.thermal(ThermalSignal.NORMAL); r.q.drain(); r.coordinator.requestMore(); assertEquals(1,r.runtime.calls.size)
        r.coordinator.setPriority(false,false); assertEquals(2,r.runtime.calls.size)
        r.coordinator.setVisibility(false,false); r.source.listener.blocked("n",true); r.q.drain(); r.source.listener.blocked("n",false); r.q.drain()
        r.coordinator.requestMore(); assertEquals(2,r.runtime.calls.size)
    }
    @Test fun staleSnapshotRevalidationFencesBeforeExplicitDemand() = Rig().use { r ->
        r.explicit(); r.time += 100; r.bridge.revalidate(); r.coordinator.requestMore()
        assertTrue(r.coordinator.status().powerPolicyUpdatePending); assertEquals(1,r.runtime.calls.size)
        assertEquals(1,r.coordinator.status().requestsThisRound); r.q.drain(); assertFalse(r.coordinator.status().roundActive)
    }

}

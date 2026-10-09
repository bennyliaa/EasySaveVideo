package com.easysavevideo.app.discovery

import org.junit.Assert.*
import org.junit.Test

class DiscoveryLatestDeliveryTest {
    @Test fun floodCoalescesToSingleLatestValueAndOneTask() {
        val queue=ArrayDeque<()->Unit>();val got=mutableListOf<Int>()
        val d=DiscoveryLatestDelivery<Int>({queue.add(it);true},got::add)
        repeat(100_000){assertTrue(d.offer(it));assertEquals(1,queue.size)}
        queue.removeFirst()();assertEquals(listOf(99_999),got);assertTrue(queue.isEmpty())
    }
    @Test fun reentrantOffersYieldWithOnlyLatestPending() {
        val queue=ArrayDeque<()->Unit>();val got=mutableListOf<Int>();lateinit var d:DiscoveryLatestDelivery<Int>
        d=DiscoveryLatestDelivery({queue.add(it);true}) { value -> got+=value;if(value==1)repeat(100){d.offer(it+2)} }
        d.offer(1);queue.removeFirst()();assertEquals(listOf(1),got);assertEquals(1,queue.size)
        queue.removeFirst()();assertEquals(listOf(1,101),got);assertTrue(queue.isEmpty())
    }
    @Test fun closeDropsQueuedAndFutureDelivery() {
        val queue=ArrayDeque<()->Unit>();val got=mutableListOf<Int>()
        val d=DiscoveryLatestDelivery<Int>({queue.add(it);true},got::add)
        d.offer(1);d.close();assertFalse(d.offer(2));queue.removeFirst()();assertTrue(got.isEmpty())
    }
    @Test fun rejectedEnqueueStopsWithoutRetryStorm() {
        var count=0;val d=DiscoveryLatestDelivery<Int>({count++;false}){fail()}
        repeat(1000){assertFalse(d.offer(it))};assertEquals(1,count)
    }
    @Test fun throwingEnqueueAlsoFailsClosed() {
        var count=0;val d=DiscoveryLatestDelivery<Int>({count++;throw IllegalStateException()}){fail()}
        repeat(1000){assertFalse(d.offer(it))};assertEquals(1,count)
    }
    @Test fun consumerFailureReleasesScheduledFlagAndRetainsNewerValue() {
        val queue=ArrayDeque<()->Unit>();val got=mutableListOf<Int>();lateinit var d:DiscoveryLatestDelivery<Int>
        d=DiscoveryLatestDelivery({queue.add(it);true}) { if(it==1){d.offer(2);throw IllegalStateException()}else got+=it }
        d.offer(1);try{queue.removeFirst()();fail()}catch(_:IllegalStateException){}
        assertEquals(1,queue.size);queue.removeFirst()();assertEquals(listOf(2),got)
    }
}

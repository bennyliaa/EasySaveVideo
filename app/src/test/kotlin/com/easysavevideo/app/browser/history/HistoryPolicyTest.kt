package com.easysavevideo.app.browser.history

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

internal fun historyId(n: Int) = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
internal fun historyItem(n: Int = 1, time: Long = 100) = BrowserHistoryItem(historyId(n), "https://example.com/$n", "Page $n", time)
internal fun historyVisit(gate: HistoryNavigationGate, n: Int = 1, time: Long = 100): HistoryVisit {
    val view = Any(); val context = BrowserContextRef("tab", "session")
    val tab = BrowserTab(context, epoch = n.toLong(), address = PageAddress("https://example.com/$n"), loadState = BrowserLoadState.READY)
    return requireNotNull(gate.complete(gate.begin(context, tab.epoch, view), tab, context, view,
        tab.address!!.value, tab.address!!.value, "Page $n", true, true, true, time))
}

class HistoryPolicyTest {
    private val countOnly = HistoryRetentionPolicy(200, null)
    @Test fun repeatedAddressRetainsIdentityMovesToFrontAndUpdatesMetadata() {
        val gate = HistoryNavigationGate(); val before = listOf(historyItem(2), historyItem(1))
        val result = HistoryPolicy.visit(before, historyVisit(gate, 1, 200), { error("Must reuse ID") }, countOnly, 200)
        assertEquals(historyId(1), result.first().id); assertEquals(200L, result.first().visitedAtMillis)
        assertEquals(before.first(), result.last())
    }
    @Test fun capacityEvictsOldestAndPreservesExistingIdentities() {
        val before = (1..200).map { historyItem(it) }
        val result = HistoryPolicy.visit(before, historyVisit(HistoryNavigationGate(), 201), { historyId(201) }, countOnly, 100)
        assertEquals(200, result.size); assertEquals(historyId(201), result.first().id)
        assertFalse(result.any { it.id == historyId(200) }); assertEquals(before.first(), result[1])
    }
    @Test fun noTtlDoesNotInventExpiry() {
        val old = historyItem(time = 0); assertEquals(listOf(old), countOnly.retain(listOf(old), Long.MAX_VALUE))
    }
    @Test fun thirtyDayPolicyExpiresAtBoundaryOnly() {
        val ttl = HistoryPolicy.THIRTY_DAYS_MILLIS
        val policy = HistoryRetentionPolicy(200, ttl); val items = listOf(historyItem(1, 0), historyItem(2, 1))
        assertEquals(items, policy.retain(items, ttl - 1)); assertEquals(listOf(items[1]), policy.retain(items, ttl))
    }
    @Test fun clockRollbackDoesNotEraseFutureDatedVisits() {
        val policy = HistoryRetentionPolicy(200, 10)
        assertEquals(listOf(historyItem(time = 200)), policy.retain(listOf(historyItem(time = 200)), 100))
    }
    @Test fun expiredAddressGetsNewIdentityWhenVisitedAgain() {
        val result = HistoryPolicy.visit(listOf(historyItem()), historyVisit(HistoryNavigationGate(), 1, 200),
            { historyId(2) }, HistoryRetentionPolicy(200, 50), 200)
        assertEquals(historyId(2), result.single().id)
    }
    @Test fun invalidDuplicateGeneratedIdentityRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPolicy.visit(listOf(historyItem()), historyVisit(HistoryNavigationGate(), 2), { historyId(1) }, countOnly, 100)
        }
    }
    @Test fun explicitRetentionBoundsAreRequired() {
        for (size in listOf(0, 201)) assertThrows(IllegalArgumentException::class.java) { HistoryRetentionPolicy(size, null) }
        for (ttl in listOf(0L, -1L)) assertThrows(IllegalArgumentException::class.java) { HistoryRetentionPolicy(200, ttl) }
    }
}

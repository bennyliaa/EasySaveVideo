package com.easysavevideo.commons

import com.easysavevideo.discovery.DiscoveryCancellation
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class CommonsMaintenanceIntegrationTest {
    private class Gate(var enabled: Boolean = true, var managedLimits: CommonsLimits = CommonsLimits()) : CommonsMaintenance {
        var version = 1
        var closes = 0
        var successes = 0
        var onSuccess: (() -> Unit)? = null
        override fun acquire(cancellation: DiscoveryCancellation): CommonsMaintenanceLease {
            if (!enabled) throw CommonsFailure(CommonsError.DISABLED)
            val admitted = version
            return object : CommonsMaintenanceLease {
                override val limits = managedLimits
                override val cacheNamespace = admitted.toString()
                override fun check() {
                    if (!enabled || version != admitted) { cancellation.cancel(); throw CommonsFailure(CommonsError.DISABLED) }
                    if (cancellation.isCancelled) throw CommonsFailure(CommonsError.CANCELLED)
                }
                override fun succeeded(checkBeforePublish: () -> Unit) { checkBeforePublish(); successes++; onSuccess?.invoke(); checkBeforePublish() }
                override fun close() { closes++ }
            }
        }
    }
    private fun managed(transport: CommonsTransport, gate: Gate, limits: CommonsLimits = CommonsLimits()) =
        CommonsMetadataProvider(config(), transport, limits, TestClock(), TestTimer(), gate)

    @Test fun disabledPolicyDoesNoTransportWork() {
        val transport = TestTransport()
        fails(CommonsError.DISABLED) { managed(transport, Gate(false)).search(request, operation()) }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun managedDeadlineAndResponseBudgetNarrowRequests() {
        val gate = Gate(managedLimits = CommonsLimits(pageSize = 5, maxResponseBytes = 65536, deadlineMillis = 2000))
        val transport = TestTransport(Reply())
        managed(transport, gate).search(request, operation())
        assertEquals(3000L, transport.requests.single().deadlineEpochMillis)
        assertEquals(65536L, transport.requests.single().maxBodyBytes)
        assertEquals(1, gate.successes); assertEquals(1, gate.closes)
    }
    @Test fun tighterLocalBudgetsAreNeverExpanded() {
        val local = CommonsLimits(2, 1000, 100, 500, 1)
        val remote = CommonsLimits(5, 65536, 2000, 10000, 10)
        assertEquals(local, shrinkLimits(local, remote))
        assertEquals(CommonsLimits(1, 50, 20, 0, 0), shrinkLimits(local, CommonsLimits(1, 50, 20, 0, 0)))
    }
    @Test fun declaredResponseTooLargeCannotPromoteHealth() {
        val gate = Gate(managedLimits = CommonsLimits(maxResponseBytes = 10))
        val transport = TestTransport(Reply(headers = mapOf("Content-Type" to listOf("application/json"), "Content-Length" to listOf("100"))))
        fails(CommonsError.RESPONSE_TOO_LARGE) { managed(transport, gate).search(request, operation()) }
        assertEquals(0, gate.successes); assertEquals(1, gate.closes)
    }
    @Test fun policyChangeDuringReadDiscardsResponseAndCancels() {
        val gate = Gate()
        val bytes = fixture()
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val result = super.read(b, off, len); gate.version++; return result
            }
        }
        val transport = TestTransport(Reply(stream = stream))
        val op = operation()
        fails(CommonsError.DISABLED) { managed(transport, gate).search(request, op) }
        assertTrue(op.cancellation.isCancelled); assertEquals(0, gate.successes); assertEquals(1, gate.closes)
        assertEquals(1, transport.cancellations.get())
    }
    @Test fun cacheCannotCrossPolicyGeneration() {
        val gate = Gate()
        val transport = TestTransport(Reply(), Reply())
        val provider = managed(transport, gate)
        provider.search(request, operation()); provider.search(request, operation())
        assertEquals(1, transport.requests.size)
        gate.version++
        provider.search(request, operation())
        assertEquals(2, transport.requests.size)
    }
    @Test fun finalHealthPromotionRechecksRevocation() {
        val gate = Gate(); gate.onSuccess = { gate.enabled = false }
        val op = operation()
        fails(CommonsError.DISABLED) { managed(TestTransport(Reply()), gate).search(request, op) }
        assertTrue(op.cancellation.isCancelled); assertEquals(1, gate.closes)
    }
    @Test fun cancellationBeforeEntryClosesLeaseAndNeverFetches() {
        val gate = Gate(); val op = operation(); op.cancellation.cancel()
        val transport = TestTransport()
        fails(CommonsError.CANCELLED) { managed(transport, gate).search(request, op) }
        assertTrue(transport.requests.isEmpty()); assertEquals(1, gate.closes)
    }
    @Test fun zeroProgressBodyFailsInsteadOfSpinning() {
        val gate = Gate()
        val stream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        fails(CommonsError.INVALID_RESPONSE) { managed(TestTransport(Reply(stream = stream)), gate).search(request, operation()) }
        assertEquals(0, gate.successes); assertEquals(1, gate.closes)
    }
    @Test fun transportCleanupCannotOutliveEffectiveDeadlineAndPromote() {
        val gate = Gate(managedLimits = CommonsLimits(deadlineMillis = 100))
        val clock = TestClock()
        val backend = TestTransport(Reply())
        val transport = CommonsTransport { request ->
            val call = backend.create(request)
            object : CommonsHttpCall {
                override fun execute() = call.execute()
                override fun cancel() { call.cancel(); clock.now = 1100 }
            }
        }
        val provider = CommonsMetadataProvider(config(), transport, CommonsLimits(), clock, TestTimer(), gate)
        fails(CommonsError.DEADLINE_EXCEEDED) { provider.search(request, operation()) }
        assertEquals(0, gate.successes); assertEquals(1, gate.closes)
    }
    @Test fun healthWriteCrossingDeadlineCannotReturnSuccess() {
        val gate = Gate(managedLimits = CommonsLimits(deadlineMillis = 100))
        val clock = TestClock(); gate.onSuccess = { clock.now = 1100 }
        val provider = CommonsMetadataProvider(config(), TestTransport(Reply()), CommonsLimits(), clock, TestTimer(), gate)
        fails(CommonsError.DEADLINE_EXCEEDED) { provider.search(request, operation()) }
        assertEquals(1, gate.closes)
    }
    @Test fun managedDisableCannotOverrideMissingLocalContact() {
        val gate = Gate()
        val transport = TestTransport()
        val provider = CommonsMetadataProvider(CommonsClientConfig(), transport, maintenance = gate)
        fails(CommonsError.DISABLED) { provider.search(request, operation(Long.MAX_VALUE)) }
        assertTrue(transport.requests.isEmpty()); assertEquals(1, gate.closes)
    }
}

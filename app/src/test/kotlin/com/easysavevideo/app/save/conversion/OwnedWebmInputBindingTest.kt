package com.easysavevideo.app.save.conversion

import com.easysavevideo.app.media.transform.TransformerFailure
import com.easysavevideo.app.save.storage.OwnedTranscodeTestFixture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Authority/lifecycle tests only. Actual Media3 parsing runs in the pinned host runner. */
class OwnedWebmInputBindingTest {
    @Test fun unregisteredPairCannotProbeAndInertConstructionDoesNotOpenOrRead() {
        OwnedTranscodeTestFixture().run { h, _ ->
            val attempt = h.prepare(); h.f.io.calls.clear()
            val counterfeit = OwnedWebmInputBinding.createUnregistered(attempt)
            assertTrue(h.f.io.calls.isEmpty())
            assertThrows(Exception::class.java) { counterfeit.probe() }
            assertTrue(h.f.io.calls.isEmpty())
            attempt.close()
        }
    }
    @Test fun unenteredSessionCannotAssembleSourceOrAcquireAnyNativeReader() {
        OwnedTranscodeTestFixture().run { h, _ ->
            val attempt = h.prepare(); val pair = attempt.bindWebmInputOnce(); h.f.io.calls.clear()
            assertThrows(TransformerFailure::class.java) { pair.createMediaSource(h.session()) }
            assertTrue(h.f.io.calls.isEmpty())
            attempt.close()
        }
    }
    @Test fun sharedRequestAndEventReservationsAreAtomicAndNeverRefund() {
        val input = object : WebmOwnedInput {
            override val length = 1L
            override fun validateIdentity() = Unit
            override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int = error("No IO expected")
        }
        val binding = WebmInputBinding(input, WebmProbeLimits(maxIoBytes = 1, maxElementsAndSamples = 1)) {}
        val requestWins = AtomicInteger(); val eventWins = AtomicInteger(); val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = (0..1).map { executor.submit {
                check(go.await(5, TimeUnit.SECONDS))
                try { binding.charge(1); requestWins.incrementAndGet() } catch (_: WebmProbeException) { }
                try { binding.event(); eventWins.incrementAndGet() } catch (_: WebmProbeException) { }
            } }
            go.countDown(); calls.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, requestWins.get()); assertEquals(1, eventWins.get())
            assertEquals(1L, binding.chargedBytes); assertEquals(1L, binding.elementsAndSamples)
            assertThrows(WebmProbeException::class.java) { binding.charge(1) }
            assertThrows(WebmProbeException::class.java) { binding.event() }
        } finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }
}

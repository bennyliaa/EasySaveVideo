package com.easysavevideo.app.vault

import com.easysavevideo.library.thumbnail.ThumbnailDecodeResult
import com.easysavevideo.library.thumbnail.ThumbnailFrame
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixed public-byte fixture through the real sole Entry and real controlled reader. */
class VaultThumbnailRuntimeTest {
    private class Frame : ThumbnailFrame {
        override val width = 256
        override val height = 144
        override val allocationBytes = 147_456L
        var closes = 0
        var failClose = false
        override fun close() { closes++; if (failClose) error("FIXED_CLOSE_FAILURE") }
    }
    private class Fixture(size: Int = 512, uiOverride: Executor? = null) {
        val base = VaultPlaybackRuntimeFixture(size)
        var nativeStarts = 0
        var factoryCalls = 0
        var release = false
        var readRetired = false
        var settled = false
        var unknown = false
        var failFactory = false
        var beforeStart: (() -> Unit)? = null
        var automaticallySettle = true
        var consumer: ((ThumbnailDecodeResult<VaultThumbnailFrame>) -> Unit)? = null
        lateinit var port: VaultThumbnailReadPort
        lateinit var complete: (VaultThumbnailResult) -> Unit
        lateinit var progress: () -> Unit
        lateinit var ticket: VaultThumbnailTicket
        var settlement: (() -> Unit)? = null
        val outcomes = mutableListOf<ThumbnailDecodeResult<VaultThumbnailFrame>>()
        var playbackCreates = 0
        val runtime = VaultPlaybackRuntime(base.gate, base.auth, base.store, VaultMonotonicClock { base.now },
            base.work, uiOverride ?: base.ui, base.timers,
            VaultPlaybackPlatformFactory { _, _, _, _, _ -> playbackCreates++; base.player },
            object : VaultPlaybackHostPort {
                override fun offerPrepared(ticket: VaultPlaybackTicket, prepared: VaultPreparedPlayback) = Unit
                override fun revokePresentation(ticket: VaultPlaybackTicket) = Unit
                override fun observeRetirement(ticket: VaultPlaybackTicket) = VaultPlaybackDrain.RELEASED
            }, { ref, host -> if (!base.revealed || !base.sessionLive || !base.hostLive || base.exportBusy || host !== base.hostToken) null
                else VaultPlaybackSession(base.root, base.snapshot, ref) { base.sessionLive && base.hostLive && !base.exportBusy }
            }, {}, {}, VaultThumbnailPlatformFactory { value, read, _, nativeStarting, callback, changed ->
                factoryCalls++; ticket = value; port = read; complete = callback; progress = changed
                if (failFactory) error("FIXED_FACTORY_FAILURE")
                release = false; readRetired = false; settled = false
                object : VaultPreparedThumbnail {
                    private var started = false
                    override fun start() {
                        if (started) return
                        started = true
                        beforeStart?.invoke(); if (nativeStarting()) { nativeStarts++; port.transportOpened() }
                    }
                    override fun revoke() {
                        if (!started) {
                            started = true; release = true
                            callback(VaultThumbnailResult(null, true))
                        }
                    }
                    override fun observeDrain() = when {
                        unknown -> VaultPlaybackDrain.UNCONFIRMED
                        release && readRetired && settled -> VaultPlaybackDrain.RELEASED
                        release -> VaultPlaybackDrain.RETIRING
                        else -> VaultPlaybackDrain.LIVE
                    }
                    override fun nativeReleaseObserved() = release && !unknown
                    override fun readOwnerRetired() { readRetired = true }
                    override fun resultHandoffSettled() { settled = true }
                }
            })
        fun open(index: Long = 1): VaultThumbnailTicket? = runtime.openThumbnail(
            base.ref.copy(objectGeneration = index), base.hostToken) { result, receipt ->
                outcomes += result; settlement = receipt
                consumer?.invoke(result)
                if (automaticallySettle) receipt()
            }
        fun pump() { base.work.drain(); base.ui.drain(); base.work.drain(); base.ui.drain() }
        fun finish(frame: Frame? = Frame()): Frame? {
            release = true; port.transportClosed(); complete(VaultThumbnailResult(frame, !unknown)); pump(); return frame
        }
        fun ready(): VaultThumbnailFrame = (outcomes.last() as ThumbnailDecodeResult.Released).frame!!
    }

    @Test fun thumbnailUsesSameReadReservationWithoutPlayerAndKeepsDisplayAfterReaderClose() {
        val f = Fixture(); assertNotNull(f.open()); f.pump()
        assertEquals(0, f.playbackCreates); assertEquals(1, f.base.files); assertEquals(1, f.base.readers)
        assertFalse(f.runtime.open(f.base.ref, f.base.hostToken))
        val raw = f.finish()!!; val frame = f.ready()
        assertEquals(1, f.base.channelCloses); assertEquals(1, f.base.fileCloses)
        assertFalse(f.runtime.occupied()); assertTrue(frame.display.isCurrent()); assertEquals(0, raw.closes)
        var draws = 0; frame.drawIfCurrent { draws++ }; assertEquals(1, draws)
        try { f.port.requireCurrent(); fail() } catch (_: Exception) { }
        assertTrue(frame.display.isCurrent())
        frame.close(); assertEquals(1, raw.closes)
        assertFalse(f.runtime.cleanupComplete()) // Submitted pixels require the exact root cover.
        f.runtime.thumbnailHostCovered(f.base.hostToken); assertTrue(f.runtime.cleanupComplete())
    }

    @Test fun onceOnlyResultClaimAndTransferDoesNotCloseAdoptedDuplicate() {
        val f = Fixture(); f.open(); f.pump(); val raw = f.finish()!!
        f.complete(VaultThumbnailResult(raw, true)); f.complete(VaultThumbnailResult(raw, false)); f.pump()
        assertEquals(1, f.outcomes.size); assertEquals(0, raw.closes); assertTrue(f.ready().display.isCurrent())
        f.ready().close(); assertEquals(1, raw.closes)
    }

    @Test fun foreignDuplicateMayOnlyCloseItsOwnNeverAdoptedFrame() {
        val f = Fixture(); f.open(); f.pump(); val adopted = f.finish()!!; val orphan = Frame()
        f.complete(VaultThumbnailResult(orphan, true)); f.pump()
        assertEquals(0, adopted.closes); assertEquals(1, orphan.closes); assertEquals(1, f.outcomes.size)
    }

    @Test fun resultReservationWaitsExactMainConsumptionReceiptAndDuplicateReceiptIsNoop() {
        val f = Fixture(); f.automaticallySettle = false; f.open(); f.pump(); f.finish()
        assertTrue(f.readRetired); assertFalse(f.settled); assertTrue(f.runtime.occupied()); assertNull(f.open(2))
        f.settlement!!.invoke(); f.settlement!!.invoke(); f.pump()
        assertFalse(f.runtime.occupied()); assertNotNull(f.open(2))
    }

    @Test fun sixteenAggregateFrameSlotsIncludePendingResultAndOldDrawnHostMarkersAreNonPixel() {
        val f = Fixture(); val frames = mutableListOf<VaultThumbnailFrame>()
        for (i in 1L..15L) { assertNotNull(f.open(i)); f.pump(); f.finish(); frames += f.ready() }
        assertNotNull(f.open(16)); f.pump(); assertNull(f.open(17))
        f.release = true; f.port.transportClosed(); f.complete(VaultThumbnailResult(Frame(), true))
        assertNull(f.open(17)); f.pump(); frames += f.ready(); assertNull(f.open(17))
        frames.first().drawIfCurrent { }; frames.first().close()
        assertNotNull(f.open(17)); f.pump(); f.finish(); frames += f.ready()
        frames.forEach { it.close() }; assertFalse(f.runtime.thumbnailCleanupComplete())
        assertTrue(f.runtime.thumbnailWorkComplete())
        f.runtime.thumbnailHostCovered(f.base.hostToken); assertTrue(f.runtime.cleanupComplete())
    }

    @Test fun actualAttemptCannotRestartAfterScrollOrStopWhileQueuedCancellationIsFree() {
        val f = Fixture(); val queued = f.open()!!; f.runtime.closeThumbnail(queued); f.pump()
        assertEquals(0, f.nativeStarts); assertFalse(f.runtime.occupied())
        assertNotNull(f.open()); f.pump(); assertEquals(1, f.nativeStarts); f.finish(null)
        f.runtime.revokeThumbnails(f.base.hostToken); assertNull(f.open())
        assertEquals(1, f.nativeStarts); assertNotNull(f.open(2))
    }

    @Test fun acceptedPreparationFailureIsTerminalForTheReveal() {
        val f = Fixture(); f.base.failFileFactory = true; assertNotNull(f.open()); f.pump()
        assertFalse(f.runtime.occupied()); assertNull(f.open()); assertEquals(0, f.nativeStarts)
    }

    @Test fun cancellationAfterResultBeforeMainCannotPublishFrame() {
        val f = Fixture(); val ticket = f.open()!!; f.pump()
        f.release = true; f.port.transportClosed(); val raw = Frame(); f.complete(VaultThumbnailResult(raw, true))
        f.base.work.drain(); f.runtime.closeThumbnail(ticket); f.base.ui.drain(); f.pump()
        assertNull((f.outcomes.single() as ThumbnailDecodeResult.Released).frame)
        assertEquals(1, raw.closes); assertFalse(f.runtime.occupied())
    }

    @Test fun unknownNativeStillAllowsActualReaderThenFileCleanupAndRetainsFence() {
        val f = Fixture(); f.open(); f.pump(); f.unknown = true; f.finish(null)
        assertEquals(1, f.base.channelCloses); assertEquals(1, f.base.fileCloses)
        assertTrue(f.runtime.occupied()); assertNull(f.open(2)); assertTrue(f.outcomes.isEmpty())
    }

    @Test fun factoryThrowRetainsUnknownButDoesNotRetainSafeReaderResources() {
        val f = Fixture(); f.failFactory = true; f.open(); f.pump()
        assertEquals(1, f.base.channelCloses); assertEquals(1, f.base.fileCloses)
        assertTrue(f.runtime.occupied()); assertNull(f.open(2))
    }

    @Test fun frameCloseUnknownIsRetainedAndCannotResetOnAnotherHostOrReceipt() {
        val f = Fixture(); f.open(); f.pump(); val raw = f.finish()!!; raw.failClose = true
        f.ready().close(); f.runtime.thumbnailHostCovered(f.base.hostToken)
        f.ready().close(); assertEquals(1, raw.closes)
        assertFalse(f.runtime.thumbnailCleanupComplete()); assertNull(f.open(2))
    }

    @Test fun acceptedSizeCallbacksRetainReadOwnerUntilActualReturnAndCountAllCallbacks() {
        val f = Fixture(); f.open(); f.pump()
        val accepted = f.port.beginCallback(false); assertEquals(512L, f.port.authenticatedSize)
        f.runtime.revokeThumbnails(); f.base.work.drain(); assertEquals(0, f.base.channelCloses)
        accepted.close(); accepted.close(); f.base.work.drain(); assertEquals(1, f.base.channelCloses)
        val g = Fixture(); g.open(); g.pump()
        repeat(VaultThumbnailLimits.MAX_CALLBACKS) { g.port.beginCallback(false).close() }
        try { g.port.beginCallback(false); fail() } catch (_: Exception) { }
    }

    @Test fun returnedPlaintextBudgetCoversRepeatedSeeksWithoutLargeAllocation() {
        val f = Fixture(65_536); f.open(); f.pump(); val output = ByteArray(65_536)
        repeat(128) {
            val callback = f.port.beginCallback(true)
            assertEquals(VaultThumbnailLimits.MAX_BYTES - it * 65_536L, callback.remainingBytes)
            assertEquals(65_536, f.port.readAt(0, output, 0, output.size)); callback.close()
        }
        try { f.port.beginCallback(true); fail() } catch (_: Exception) { }
    }

    @Test fun admittedByteReadIsSerialAndRevocationCannotMutateDestinationAfterGateLoss() {
        val f = Fixture(); f.open(); f.pump(); val accepted = f.port.beginCallback(true)
        try { f.port.beginCallback(true); fail() } catch (_: Exception) { }
        accepted.close()
        val g = Fixture(); g.open(); g.pump()
        val entered = CountDownLatch(1); val exit = CountDownLatch(1); val out = ByteArray(16) { 99 }
        g.base.beforeRead = { entered.countDown(); check(exit.await(5, TimeUnit.SECONDS)) }
        val thread = Thread {
            val callback = g.port.beginCallback(true)
            try { g.port.readAt(0, out, 0, out.size) } catch (_: Exception) { } finally { callback.close() }
        }
        thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); g.runtime.revokeThumbnails(); g.base.work.drain()
        assertEquals(0, g.base.channelCloses); exit.countDown(); thread.join(5_000); assertFalse(thread.isAlive)
        assertTrue(out.all { it == 99.toByte() }); g.base.work.drain(); assertEquals(1, g.base.channelCloses)
    }

    @Test fun watchdogRevokesAuthorityWithoutClaimingNativeOrTransportRetirement() {
        val f = Fixture(); f.open(); f.pump(); f.base.now += VaultThumbnailLimits.LIFETIME_MILLIS
        f.base.timers.at(f.base.now).fireEvenIfCancelled(); f.pump()
        try { f.port.beginCallback(false); fail() } catch (_: Exception) { }
        assertEquals(1, f.base.channelCloses); assertTrue(f.runtime.occupied()); assertFalse(f.runtime.cleanupComplete())
    }

    @Test fun coveredReceiptBeforeLateResultIsStickyAndDoesNotNeedAggregateCleanup() {
        val f = Fixture(); f.open(); f.pump(); f.base.hostLive = false
        f.runtime.revokeThumbnails(); f.runtime.thumbnailHostCovered(f.base.hostToken)
        val raw = f.finish()!!; assertEquals(1, raw.closes); assertTrue(f.runtime.thumbnailCleanupComplete())
    }

    @Test fun oneAttemptLedgerIsBoundedAt297Identities() {
        val f = Fixture()
        for (i in 1L..297L) { assertNotNull(f.open(i)); f.pump(); f.finish(null) }
        assertEquals(297, f.nativeStarts); assertNull(f.open(298)); assertNull(f.open(1))
    }
    @Test fun blockingDrawDoesNotHoldRevocationGateAndSourceCloseWaitsActualReturn() {
        val f = Fixture(); f.open(); f.pump(); val raw = f.finish()!!; val frame = f.ready()
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val thread = Thread { frame.drawIfCurrent { entered.countDown(); check(exit.await(5, TimeUnit.SECONDS)) } }
        thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        f.runtime.revokeThumbnails(); f.runtime.thumbnailHostCovered(f.base.hostToken)
        assertFalse(frame.display.isCurrent()); assertEquals(0, raw.closes); assertFalse(f.runtime.thumbnailCleanupComplete())
        exit.countDown(); thread.join(5_000); assertFalse(thread.isAlive)
        assertEquals(1, raw.closes); assertTrue(f.runtime.thumbnailCleanupComplete())
    }

    @Test fun resultBooleanCannotSubstitutePositiveNativeReturnObservation() {
        val f = Fixture(); f.open(); f.pump(); f.port.transportClosed()
        val raw = Frame(); f.complete(VaultThumbnailResult(raw, true)); f.pump()
        assertTrue(f.outcomes.isEmpty()); assertTrue(f.runtime.occupied()); assertEquals(0, raw.closes)
        f.release = true; f.progress(); f.pump(); assertEquals(1, f.outcomes.size)
    }

    @Test fun exactReceiptCannotFinishResultCallbackBeforeItsActualReturn() {
        val f = Fixture(); val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        f.consumer = {
            f.settlement!!.invoke()
            entered.countDown(); check(exit.await(5, TimeUnit.SECONDS))
        }
        f.open(); f.pump(); f.release = true; f.port.transportClosed(); f.complete(VaultThumbnailResult(Frame(), true))
        f.base.work.drain()
        val thread = Thread { f.base.ui.drain() }; thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(f.runtime.occupied()); assertFalse(f.settled); assertNull(f.open(2))
        exit.countDown(); thread.join(5_000); assertFalse(thread.isAlive); f.pump()
        assertTrue(f.settled); assertFalse(f.runtime.occupied())
    }

    @Test fun executeThenThrowUiDoesNotDuplicateStartResultOrSettlement() {
        val f = Fixture(uiOverride = Executor { command -> command.run(); throw IllegalStateException("FIXED_AFTER_EXECUTE") })
        f.open(); f.pump(); f.finish()
        assertEquals(1, f.nativeStarts); assertEquals(1, f.outcomes.size)
        assertTrue(f.settled); assertFalse(f.runtime.occupied()); assertTrue(f.ready().display.isCurrent())
    }

    @Test fun hostLossWhileMainStartQueuedImmediatelyRetiresWithoutNativeAcquisition() {
        val f = Fixture(); assertNotNull(f.open()); f.base.work.drain()
        assertEquals(1, f.factoryCalls); assertEquals(0, f.nativeStarts)
        f.base.hostLive = false; f.base.ui.drain(); f.pump()
        assertEquals(0, f.nativeStarts); assertEquals(1, f.outcomes.size)
        assertEquals(1, f.base.channelCloses); assertEquals(1, f.base.fileCloses)
        assertTrue(f.runtime.thumbnailCleanupComplete()); assertFalse(f.runtime.occupied())
    }

}

package com.easysavevideo.app.vault

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VaultPlaybackRuntimeTest {
    @Test fun requiresCurrentRevealedCatalogAndExactHost() {
        val f = VaultPlaybackRuntimeFixture()
        f.revealed = false; assertFalse(f.open()); f.revealed = true
        assertFalse(f.runtime.open(f.ref, VaultPlaybackHostToken(1) { true }))
        assertFalse(f.runtime.open(f.ref.copy(objectGeneration = 2), f.hostToken))
        f.exportBusy = true; assertFalse(f.open()); f.exportBusy = false
        f.hostLive = false; assertFalse(f.open()); assertEquals(0, f.files)
    }
    @Test fun actualReservationPrecedesEveryFactoryAndKeepsReaderAfterPreparationReturns() {
        val f = VaultPlaybackRuntimeFixture()
        f.beforeFile = { assertTrue(f.runtime.occupied()); assertFalse(f.open()) }
        f.beforeReaderReturn = { assertTrue(f.runtime.occupied()); assertFalse(f.runtime.cleanupComplete()) }
        f.beforeFactoryReturn = { assertTrue(f.runtime.occupied()); assertFalse(f.open()) }
        assertTrue(f.open()); f.pump(); f.ready()
        assertEquals(0, f.plays); assertEquals(0, f.channelCloses)
        val t = VaultPlaybackTransport(f.port); assertEquals(512L, t.open(0, -1)); assertEquals(10, t.read(ByteArray(10), 0, 10))
        t.close(); assertEquals(0, f.channelCloses)
        f.runtime.play(f.ticket); f.pump(); assertEquals(1, f.plays)
    }
    @Test fun oldTicketCannotControlOrCloseCurrentOwner() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        val wrong = VaultPlaybackTicket(f.hostToken)
        f.runtime.play(wrong); f.runtime.pause(wrong); f.runtime.seek(wrong, 20); f.runtime.close(wrong); f.pump()
        assertEquals(0, f.plays); assertEquals(0, f.pauses); assertTrue(f.seeks.isEmpty()); assertEquals(0, f.revokes)
    }
    @Test fun lateReaderAfterRevocationIsAdoptedAndClosedWithoutPlatformHandoff() {
        val f = VaultPlaybackRuntimeFixture()
        f.beforeReaderReturn = { f.runtime.revokeAll(); assertTrue(f.runtime.occupied()) }
        f.open(); f.pump(); assertEquals(0, f.factories); assertEquals(0, f.offers)
        assertEquals(1, f.channelCloses); assertEquals(1, f.fileCloses); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun latePlayerAfterRevocationCanOnlyRetire() {
        val f = VaultPlaybackRuntimeFixture(); f.beforeFactoryReturn = { f.runtime.revokeAll() }
        f.open(); f.pump(); assertEquals(0, f.offers); assertEquals(1, f.revokes)
        assertTrue(f.runtime.occupied()); f.completePlatformDrain(); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun factoryThrowWithoutPlatformReceiptRemainsUnknown() {
        val f = VaultPlaybackRuntimeFixture(); f.failPlatformFactory = true
        f.open(); f.pump(); assertEquals(1, f.channelCloses); assertEquals(1, f.fileCloses)
        assertTrue(f.runtime.occupied()); f.completePlatformDrain(); assertTrue(f.runtime.occupied()); assertFalse(f.open())
    }
    @Test fun unknownOwnerFactoryFailureRetainsFileUntilRealOwnerDrain() {
        val f = VaultPlaybackRuntimeFixture(); f.failReaderFactory = true; f.beforeFile = { f.authUnknown = true }
        f.open(); f.pump(); assertEquals(0, f.fileCloses); assertTrue(f.runtime.occupied())
        f.authUnknown = false; f.runtime.hostProgressed(); f.pump()
        assertEquals(1, f.fileCloses); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun preparationWatchdogOutlivesFactoryReturnAndIgnoresLateCancelledReadyTimer() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump()
        val deadline = f.startedAt + VaultPlaybackLimits.PREPARE_MILLIS
        val timer = f.timers.at(deadline); assertFalse(timer.cancelled)
        f.now = deadline; timer.fireEvenIfCancelled(); f.pump(); assertEquals(1, f.revokes); assertTrue(f.runtime.occupied())
        val g = VaultPlaybackRuntimeFixture(); g.open(); g.pump(); val old = g.timers.at(g.startedAt + VaultPlaybackLimits.PREPARE_MILLIS)
        g.ready(); assertTrue(old.cancelled); g.now = old.deadline; old.fireEvenIfCancelled(); g.pump()
        assertEquals(0, g.revokes); assertEquals(VaultPlaybackStatus.READY, g.views.last().status)
    }
    @Test fun completedReadTimeoutCannotRevokeFollowingTransport() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        val t = VaultPlaybackTransport(f.port); t.open(0, -1); t.read(ByteArray(5), 0, 5)
        val timer = f.timers.at(f.startedAt + VaultPlaybackLimits.READ_MILLIS)
        assertTrue(timer.cancelled); f.now = timer.deadline; timer.fireEvenIfCancelled(); f.pump()
        assertEquals(0, f.revokes); assertEquals(5, t.read(ByteArray(5), 0, 5)); t.close()
    }
    @Test fun commandsCoalesceAndCheckCurrentAtExecutionWithoutChangingSession() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        repeat(10_000) { f.runtime.seek(f.ticket, if (it == 9999) Long.MAX_VALUE else it.toLong()); f.runtime.play(f.ticket); f.runtime.pause(f.ticket) }
        assertEquals(1, f.work.size()); f.pump(); assertEquals(listOf(100_000L), f.seeks); assertEquals(0, f.plays); assertEquals(1, f.pauses)
        f.runtime.play(f.ticket); f.hostLive = false; f.pump(); assertEquals(0, f.plays); assertTrue(f.sessionLive)
        f.runtime.revokeAll(); f.pump(); assertTrue(f.sessionLive)
    }
    @Test fun revokeCannotBeOverwrittenByPendingCommandsAndCallbacks() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        f.runtime.play(f.ticket); f.runtime.seek(f.ticket, 30); f.runtime.close(f.ticket)
        f.callback(VaultPlaybackView(f.ticket, VaultPlaybackStatus.PLAYING)); f.pump()
        assertEquals(0, f.plays); assertTrue(f.seeks.isEmpty()); assertEquals(1, f.revokes)
        assertTrue(f.views.last().status in setOf(VaultPlaybackStatus.RETIRING, VaultPlaybackStatus.UNAVAILABLE))
    }
    @Test fun timeoutAndCancelDoNotForgeTransportNativeOrHostDrain() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        val t = VaultPlaybackTransport(f.port); t.open(0, -1); f.runtime.close(f.ticket); f.pump()
        assertTrue(f.runtime.occupied()); f.now += VaultPlaybackLimits.RETIREMENT_MILLIS
        f.timers.at(f.now).fireEvenIfCancelled(); f.pump(); assertTrue(f.runtime.occupied())
        f.completePlatformDrain(); assertTrue(f.runtime.occupied()); t.close(); f.pump(); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun readerCloseFailureIsStickyAndFileIsNotBlindlyClosed() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.failReaderClose = true
        f.runtime.close(f.ticket); f.pump(); f.completePlatformDrain(); repeat(4) { f.runtime.hostProgressed(); f.pump() }
        assertEquals(1, f.channelCloses); assertEquals(0, f.fileCloses); assertTrue(f.runtime.occupied())
    }
    @Test fun publicationAfterRevokeCannotMutateDestinationAndDrainWaitsActualReadReturn() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        val t = VaultPlaybackTransport(f.port); t.open(0, -1)
        val entered = CountDownLatch(1); val exit = CountDownLatch(1); val result = AtomicReference<Throwable?>()
        f.beforeRead = { entered.countDown(); check(exit.await(5, TimeUnit.SECONDS)) }
        val out = ByteArray(32) { 99 }
        val thread = Thread { try { t.read(out, 0, out.size) } catch (e: Throwable) { result.set(e) } }
        thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        f.runtime.close(f.ticket); f.pump(); f.completePlatformDrain()
        assertTrue(f.runtime.occupied()); assertEquals(0, f.channelCloses); assertTrue(out.all { it == 99.toByte() })
        exit.countDown(); thread.join(5_000); assertFalse(thread.isAlive); assertTrue(result.get() is IOException)
        assertTrue(out.all { it == 99.toByte() }); f.pump(); assertEquals(1, f.channelCloses)
        t.close(); f.pump(); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun boundedReadContextsRetainExactLifetimeLineageAndDoNotExtendDeadline() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump()
        val t = VaultPlaybackTransport(f.port); t.open(0, -1); t.read(ByteArray(4), 0, 4)
        val child = f.contexts.single(); assertTrue(child.sharesOperation(f.operation!!)); assertTrue(child.deadlineElapsedMillis <= f.operation!!.deadlineElapsedMillis)
        val unrelated = VaultOperationContext(child.deadlineElapsedMillis, VaultMonotonicClock { f.now })
        try { f.controlledReader.readAt(0, ByteArray(4), 0, 4, unrelated); fail() } catch (_: VaultOperationException) { }
        t.close()
    }
    @Test fun rejectedPreparationDoesNotDropReservationOrCreateResources() {
        val f = VaultPlaybackRuntimeFixture(); f.work.reject = true; assertTrue(f.open()); f.ui.drain()
        assertEquals(0, f.files); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun nativeFailureImmediatelyRevokesReadAuthorityAndWaitsActualNativeReadRetirement() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        val t = VaultPlaybackTransport(f.port); t.open(0, -1)
        f.readRetirementReady = false; f.nativeDrain = VaultPlaybackDrain.UNCONFIRMED; f.nativeProgress(); f.pump()
        try { t.read(ByteArray(2), 0, 2); fail() } catch (_: IOException) { }
        assertEquals(1, f.channelCloses); assertTrue(f.runtime.occupied())
        f.readRetirementReady = true; f.nativeProgress(); f.pump(); assertEquals(1, f.channelCloses)
        assertEquals(1, f.readRetiredSignals); t.close(); assertTrue(f.runtime.occupied())
    }
    @Test fun mainHandoffCannotDisappearBetweenCurrentCheckAndActualHostReturn() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.work.drain()
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        f.beforeOffer = { entered.countDown(); check(exit.await(5, TimeUnit.SECONDS)) }
        val thread = Thread { f.ui.drain() }; thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        f.runtime.revokeAll(); f.work.drain(); f.nativeDrain = VaultPlaybackDrain.RELEASED
        f.runtime.hostProgressed(); assertTrue(f.runtime.occupied())
        exit.countDown(); thread.join(5_000); assertFalse(thread.isAlive); f.pump()
        assertTrue(f.runtime.occupied()); f.completePlatformDrain(); assertTrue(f.runtime.cleanupComplete())
    }

    @Test fun revocationSynchronouslyRemovesTimelineMetadataFromRetirementView() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready()
        f.callback(VaultPlaybackView(f.ticket,VaultPlaybackStatus.PLAYING,12_000,100_000,true)); f.ui.drain()
        f.runtime.close(f.ticket); f.pump()
        assertEquals(0L,f.views.last().positionMillis); assertNull(f.views.last().durationMillis); assertFalse(f.views.last().seekable)
    }
    @Test fun uiRejectionIsTerminalAndCannotAcquireInvisiblePlayback() {
        val f = VaultPlaybackRuntimeFixture(); f.ui.reject = true
        assertTrue(f.open()); f.work.drain()
        assertEquals(0,f.files); assertEquals(0,f.factories); assertTrue(f.runtime.occupied()); assertFalse(f.runtime.cleanupComplete())
    }

    @Test fun failedStatusPublicationStillRequestsExactHostRetirementOnce() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready(); assertEquals(1,f.offers)
        f.failView = true; f.callback(VaultPlaybackView(f.ticket,VaultPlaybackStatus.PLAYING,10,100_000,true)); f.pump()
        assertEquals(1,f.hostRevokes); assertEquals(1,f.revokes); assertTrue(f.runtime.occupied())
        repeat(4) { f.runtime.hostProgressed(); f.pump() }; assertEquals(1,f.hostRevokes)
        f.completePlatformDrain(); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun rejectedHostRetirementHandoffStaysUnknownWithoutRepeatedMainAttempts() {
        val f = VaultPlaybackRuntimeFixture(); f.open(); f.pump(); f.ready(); f.ui.reject = true
        f.runtime.close(f.ticket); f.work.drain(); f.nativeDrain = VaultPlaybackDrain.RELEASED; f.hostDrain = VaultPlaybackDrain.RELEASED
        repeat(4) { f.runtime.hostProgressed(); f.work.drain() }
        assertEquals(0,f.hostRevokes); assertTrue(f.runtime.occupied()); assertFalse(f.runtime.cleanupComplete())
        assertEquals(1,f.channelCloses); assertEquals(1,f.fileCloses)
    }

}

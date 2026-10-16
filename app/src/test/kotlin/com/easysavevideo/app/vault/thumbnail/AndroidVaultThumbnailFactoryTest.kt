package com.easysavevideo.app.vault.thumbnail

import android.media.MediaDataSource
import com.easysavevideo.app.library.thumbnail.SharedThumbnailRetriever
import com.easysavevideo.app.vault.*
import com.easysavevideo.library.thumbnail.*
import org.junit.Assert.*
import org.junit.Test
import java.io.FileInputStream

class AndroidVaultThumbnailFactoryTest {
    private class Frame : ThumbnailFrame {
        override val width = 256; override val height = 144; override val allocationBytes = 147_456L
        var closes = 0; override fun close() { closes++ }
    }
    private class Retriever : SharedThumbnailRetriever {
        val frame = Frame(); var decodes = 0; var closes = 0; var failRelease = false
        override fun publishedInput(input: FileInputStream) = error("ordinary input forbidden")
        override fun controlledInput(input: MediaDataSource) = Unit
        override fun singleFrame(): ThumbnailFrame { decodes++; return frame }
        override fun close() { closes++; if (failRelease) error("fixed") }
    }
    private class Lane : ThumbnailWorkLane {
        val tasks = ArrayDeque<Runnable>(); var reject = false; var enqueueThenThrow = false
        override fun submit(task: Runnable): Boolean {
            if (reject) return false
            tasks += task
            if (enqueueThenThrow) error("fixed")
            return true
        }
        fun run() { tasks.removeFirst().run() }
    }
    private class H {
        val gate = ThumbnailNativeResourceGate(); val lane = Lane(); val read = VaultThumbnailTransportTest.Read()
        val native = Retriever(); var constructors = 0; var failConstructor = false; var failSetup = false
        var attempts = 0; var current = true; var allowAttempt = true; var completedThrows = false
        val results = mutableListOf<VaultThumbnailResult>(); val events = mutableListOf<String>()
        val factory = AndroidVaultThumbnailFactory(lane, {
            constructors++; if (failConstructor) error("fixed"); native
        }, { port ->
            object : VaultThumbnailControlledInput {
                val transport = VaultThumbnailTransport(port)
                override fun configure(retriever: SharedThumbnailRetriever) {
                    events += "setup"; if (failSetup) error("fixed")
                    assertEquals(200_000, transport.size()); assertEquals(1, transport.readAt(0, ByteArray(1), 0, 1))
                }
                override fun close() { events += "transport-close"; transport.close() }
            }
        }, gate::claim, gate::released, gate::unconfirmed)
        fun owner(): VaultPreparedThumbnail = factory.create(
            VaultThumbnailTicket(VaultPlaybackHostToken(1) { current }, VaultMediaReadRef("fixture", "0".repeat(32), 1, 1)),
            read, { current }, { attempts++; allowAttempt }, {
                events += "completed"; results += it; if (completedThrows) error("fixed")
            }, { events += "progress" })
        fun receipts(owner: VaultPreparedThumbnail) { owner.readOwnerRetired(); owner.resultHandoffSettled() }
    }
    @Test fun exactSharedGateRetainedThroughNativeReadAndMainReceipts() {
        val h = H(); val owner = h.owner(); assertFalse(h.gate.isOccupied); owner.start()
        assertTrue(h.gate.isOccupied); assertEquals(0, h.constructors); h.lane.run()
        assertTrue(owner.nativeReleaseObserved()); assertEquals(VaultPlaybackDrain.RETIRING, owner.observeDrain())
        assertFalse(h.gate.claim(Any())); owner.readOwnerRetired(); assertTrue(h.gate.isOccupied)
        owner.resultHandoffSettled(); assertFalse(h.gate.isOccupied); assertEquals(VaultPlaybackDrain.RELEASED, owner.observeDrain())
        assertEquals(1, h.attempts); assertEquals(1, h.native.decodes); assertEquals(1, h.native.closes)
        assertTrue(h.events.indexOf("transport-close") < h.events.indexOf("completed"))
    }
    @Test fun otherDomainOccupationRejectsBeforeAnyNativeAttempt() {
        val h = H(); val ordinary = Any(); assertTrue(h.gate.claim(ordinary)); val owner = h.owner(); owner.start()
        assertEquals(0, h.attempts); assertEquals(0, h.constructors); assertEquals(0, h.lane.tasks.size)
        assertNull(h.results.single().frame); h.receipts(owner); assertTrue(h.gate.isOccupied)
        h.gate.released(ordinary); assertFalse(h.gate.isOccupied)
    }
    @Test fun laneRejectionAcquiresNoNativeAndWaitsForRealHandoffReceipts() {
        val h = H(); h.lane.reject = true; val owner = h.owner(); owner.start()
        assertTrue(h.gate.isOccupied); assertEquals(0, h.attempts); assertTrue(owner.nativeReleaseObserved())
        h.receipts(owner); assertFalse(h.gate.isOccupied)
    }
    @Test fun repeatedStartOrTaskExecutionNeverCreatesSecondNativeOwner() {
        val h = H(); val owner = h.owner(); repeat(3) { owner.start() }; val task = h.lane.tasks.single()
        task.run(); task.run(); assertEquals(1, h.constructors); assertEquals(1, h.native.decodes)
        h.receipts(owner); h.receipts(owner); assertEquals(1, h.results.size)
    }
    @Test fun constructorFailureIsUnknownButControlledTransportActuallyCloses() {
        val h = H(); h.failConstructor = true; val owner = h.owner(); owner.start(); h.lane.run()
        assertFalse(owner.nativeReleaseObserved()); assertEquals(VaultPlaybackDrain.UNCONFIRMED, owner.observeDrain())
        assertEquals(1, h.read.closes); assertEquals(0, h.read.active); h.receipts(owner)
        assertTrue(h.gate.isOccupied); assertTrue(h.gate.requiresRestart)
    }
    @Test fun nativeReleaseFailureCannotDropOwnedFrameOrReleaseProcessGate() {
        val h = H(); h.native.failRelease = true; val owner = h.owner(); owner.start(); h.lane.run(); h.receipts(owner)
        assertTrue(h.gate.isOccupied); assertFalse(h.results.single().nativeReleased); assertNull(h.results.single().frame)
        assertEquals(0, h.native.frame.closes); assertEquals(1, h.native.closes); assertEquals(1, h.read.closes)
    }
    @Test fun setupFailureStillObservesActualReleaseAndYieldsPlaceholder() {
        val h = H(); h.failSetup = true; val owner = h.owner(); owner.start(); h.lane.run()
        assertNull(h.results.single().frame); assertTrue(owner.nativeReleaseObserved()); assertEquals(0, h.native.decodes)
        h.receipts(owner); assertFalse(h.gate.isOccupied)
    }
    @Test fun enqueuedThenThrowRetainsOriginalOwnerEvenAfterLateExecution() {
        val h = H(); h.lane.enqueueThenThrow = true; val owner = h.owner(); owner.start()
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, owner.observeDrain()); h.lane.run(); h.receipts(owner)
        assertTrue(h.gate.isOccupied); assertTrue(h.gate.requiresRestart)
    }
    @Test fun callbackExceptionNeverDropsFrameOrClaimsGateRelease() {
        val h = H(); h.completedThrows = true; val owner = h.owner(); owner.start(); h.lane.run(); h.receipts(owner)
        assertEquals(VaultPlaybackDrain.UNCONFIRMED, owner.observeDrain()); assertTrue(h.gate.isOccupied)
        assertEquals(0, h.native.frame.closes)
    }
    @Test fun cancellationBeforeWorkerConsumesNoNativeAttempt() {
        val h = H(); val owner = h.owner(); owner.start(); owner.revoke(); h.lane.run(); h.receipts(owner)
        assertEquals(0, h.attempts); assertEquals(0, h.constructors); assertFalse(h.gate.isOccupied)
    }
    @Test fun revokedNeverStartedOwnerProvidesNoAcquisitionReceiptAndCannotStartLater() {
        val h = H(); val owner = h.owner(); owner.revoke(); owner.start(); h.receipts(owner)
        assertTrue(owner.nativeReleaseObserved()); assertEquals(0, h.lane.tasks.size)
        assertEquals(0, h.constructors); assertFalse(h.gate.isOccupied)
    }
    @Test fun runtimeRejectingNativeStartNeverCallsRetrieverConstructor() {
        val h = H(); h.allowAttempt = false; val owner = h.owner(); owner.start(); h.lane.run(); h.receipts(owner)
        assertEquals(1, h.attempts); assertEquals(0, h.constructors); assertNull(h.results.single().frame)
    }
    @Test fun postCompletionReadRevocationNeverClosesTransferredFrame() {
        val h = H(); val owner = h.owner(); owner.start(); h.lane.run(); owner.revoke(); h.receipts(owner)
        assertSame(h.native.frame, h.results.single().frame); assertEquals(0, h.native.frame.closes)
    }
}

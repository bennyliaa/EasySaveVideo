package com.easysavevideo.app.vault

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.lang.reflect.Proxy
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportRelease
import com.easysavevideo.library.export.ExportEvidence
import com.easysavevideo.library.thumbnail.ThumbnailDecodeResult
import com.easysavevideo.library.thumbnail.ThumbnailFrame

/** Public deterministic metadata only; doubles are never accepted as production crypto evidence. */
class VaultThumbnailWiringTest {
    private class Clock : VaultMonotonicClock { var now = 10L; override fun elapsedMillis() = now }
    private class Lane : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        fun one() { queue.removeFirst().run() }
        fun drain() { var bound = 0; while (queue.isNotEmpty()) { check(++bound < 200); one() } }
    }
    private class Timers : VaultWakeScheduler {
        class Entry(val task: () -> Unit) { var cancelled = false }
        val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): VaultWakeCancellation {
            val value = Entry(task); entries += value
            return VaultWakeCancellation { value.cancelled = true }
        }
        fun current() = entries.last { !it.cancelled }.task()
    }
    private class View : VaultPresentationPort, VaultPlaybackHostPort {
        var playback = VaultPlaybackView()
        var rows: List<VaultMediaRow> = emptyList()
        var offers = 0
        override fun presentPlayback(view: VaultPlaybackView) { playback = view }
        override fun presentCatalog(revision: Long, items: List<VaultMediaRow>) { this.revision = revision; rows = items; status = VaultRuntimeStatus.CATALOG }
        override fun offerPrepared(ticket: VaultPlaybackTicket, prepared: VaultPreparedPlayback) { offers++ }
        override fun revokePresentation(ticket: VaultPlaybackTicket) = Unit
        override fun observeRetirement(ticket: VaultPlaybackTicket) = VaultPlaybackDrain.RELEASED
        var status = VaultRuntimeStatus.LOADING
        var emptyCount = 0
        var revision: Long? = null
        var automaticCover = true
        var hidden: (() -> Unit)? = null
        override fun cover(generation: Long, onActuallyHidden: () -> Unit) {
            revision = null; hidden = onActuallyHidden
            if (automaticCover) onActuallyHidden()
        }
        override fun presentEmpty(catalogRevision: Long) { emptyCount++; revision = catalogRevision; status = VaultRuntimeStatus.EMPTY }
        override fun presentStatus(status: VaultRuntimeStatus) { this.status = status }
    }
    private fun ciphertext(size: Int = 1) = VaultCiphertext(ByteArray(size) { 7 })
    private fun data() = VaultStagedData(listOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, ciphertext(), true)),
        ciphertext(), ciphertext(), 1, ciphertext(), VaultRetryLedger(0, 0, -1, null), ciphertext())
    private fun identity() = VaultPersistentIdentity("vault", "install", "operation", "esv.vault.v1.operation.device", "esv.vault.v1.operation.biometric")
    private inner class Store(initial: Boolean) : VaultStorePort {
        var value: VaultStoredSnapshot? = if (initial) VaultStoredSnapshot(identity(), VaultStorePhase.READY, 3, data()) else null
        var begins = 0; var retryWrites = 0; var failReset = false
        var fileCloses = 0
        override fun openMediaFile(identity: VaultMediaObjectIdentity, context: VaultOperationContext): VaultOwnedCiphertext =
            object : VaultOwnedCiphertext {
                override val operationId = "fixed"
                override val identity = identity
                override fun append(bytes: ByteArray, offset: Int, length: Int) = error("not used")
                override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int) = error("not used")
                override fun size() = 8L
                override fun forceAndCloseWrite() = error("not used")
                override fun closeAndObserve(): ExportRelease { fileCloses++; return ExportRelease.RELEASED }
            }
        override fun inspect(context: VaultOperationContext): VaultStoreObservation = value?.let(VaultStoreObservation::Present) ?: VaultStoreObservation.Absent
        override fun beginInitialization(identity: VaultPersistentIdentity, context: VaultOperationContext): VaultStoredSnapshot {
            check(value == null); begins++
            return VaultStoredSnapshot(identity, VaultStorePhase.INITIALIZING, 1, null).also { value = it }
        }
        override fun stage(expected: VaultStoredSnapshot, prepared: VaultStagedData, context: VaultOperationContext): VaultStoredSnapshot {
            check(value == expected)
            return expected.copy(phase = VaultStorePhase.STAGED, storeRevision = 2, data = prepared).also { value = it }
        }
        override fun markReady(expected: VaultStoredSnapshot, verification: VaultStagedVerification, context: VaultOperationContext): VaultStoredSnapshot {
            verification.requireMatches(expected)
            return expected.copy(phase = VaultStorePhase.READY, storeRevision = 3).also { value = it }
        }
        override fun compareAndSwapRetry(expected: VaultStoredSnapshot, write: VaultRetryWrite, authenticatedLedger: VaultCiphertext,
            context: VaultOperationContext): VaultStoredSnapshot {
            check(value == expected)
            if (write.kind == VaultRetryWriteKind.RESET && failReset) throw VaultOperationException(VaultFailure.UNAVAILABLE)
            retryWrites++
            return expected.copy(storeRevision = expected.storeRevision + 1,
                data = expected.data!!.copy(retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger)).also { value = it }
        }
        override fun close() = Unit
    }
    private inner class Auth : VaultAuthenticationPort {
        var aliases = false; var unlocks = 0; var closes = 0; var reads = 0; var prepared = 0
        var reject = false; var ledgerCorrupt = false; var onRead: (() -> Unit)? = null
        var readerCloses = 0
        override fun authenticateCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
            context: VaultOperationContext) = VaultAuthenticatedCatalog(snapshot.data!!.catalogRevision, listOf(
                VaultMediaEntry(VaultMediaObjectIdentity("fixedobject", 1), VaultMediaLimits.PROFILE,
                    ExportEvidence(8, "a".repeat(64)), VaultCiphertextEvidence(8, "b".repeat(64)), ciphertext(),
                    "fixed.mp4", 1000, 1, 1, VaultMediaOperation("fixedmove", 1, VaultMediaKind.MOVE_IN),
                    MediaReadRef("fixedordinary", 1, 1))))
        override fun openMediaRead(root: VaultRootCapability, snapshot: VaultStoredSnapshot, ref: VaultMediaReadRef,
            file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext): VaultMediaReadCapability =
            object : VaultMediaReadCapability {
                override val authenticatedSize = 8L
                override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int,
                    context: VaultOperationContext): Int { context.checkActive(); gate.requireCurrent(); return -1 }
                override fun revoke() = Unit
                override fun closeAndObserve(): ExportRelease { readerCloses++; return ExportRelease.RELEASED }
            }
        private inner class Root : VaultRootCapability { var live = true; override fun close() { if (live) { live = false; closes++ } } }
        override fun validatePasswordInput(input: VaultCredentialInput) {
            val chars = input.copyCharacters()
            try { if (chars.size < 15) throw VaultOperationException(VaultFailure.REJECTED) } finally { chars.fill('\u0000') }
        }
        override fun hasExistingVaultAliases(context: VaultOperationContext) = aliases
        override fun preparePasswordSetup(identity: VaultPersistentIdentity, input: VaultCredentialInput, context: VaultOperationContext): VaultStagedData {
            prepared++; return data()
        }
        override fun verifyStaged(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, context: VaultOperationContext): VaultStagedVerification =
            object : VaultStagedVerification { override fun requireMatches(snapshot: VaultStoredSnapshot) = Unit; override fun close() = Unit }
        override fun validateStagedVerification(snapshot: VaultStoredSnapshot, verification: VaultStagedVerification) = verification.requireMatches(snapshot)
        override fun authenticateLedger(snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultRetryLedger {
            if (ledgerCorrupt) throw VaultOperationException(VaultFailure.CORRUPT)
            return snapshot.data!!.retryLedger
        }
        override fun sealLedger(identity: VaultPersistentIdentity, ledger: VaultRetryLedger, context: VaultOperationContext) = ciphertext()
        override fun unlockPassword(snapshot: VaultStoredSnapshot, input: VaultCredentialInput, attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultRootCapability {
            unlocks++
            assertTrue(snapshot.data!!.retryLedger.reservedAttemptCount > 0)
            assertNotEquals(attempt.attemptId, snapshot.data.retryLedger.outstandingReservationId)
            if (reject) throw VaultOperationException(VaultFailure.REJECTED)
            return Root()
        }
        override fun readEmptyCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultEmptyCatalog {
            check(root is Root && root.live); reads++; onRead?.invoke(); return VaultEmptyCatalog(snapshot.data!!.catalogRevision)
        }
        override fun close() = Unit
    }
    private class Native : VaultPreparedPlayback {
        var revoked = false; var nativeReleased = false; var readRetired = false; var loopersTerminated = false
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMillis: Long) = Unit
        override fun attachSurface(surface: VaultPlaybackSurface) = Unit
        override fun detachSurface(surface: VaultPlaybackSurface, detached: (Boolean) -> Unit) = Unit
        override fun revoke() { revoked = true }
        override fun readyForReadRetirement() = revoked && nativeReleased
        override fun readOwnerRetired() { readRetired = true }
        override fun observeDrain() = if (revoked && nativeReleased && readRetired && loopersTerminated)
            VaultPlaybackDrain.RELEASED else if (revoked) VaultPlaybackDrain.RETIRING else VaultPlaybackDrain.LIVE
    }
    private inline fun <reified T> idleMediaPort(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ ->
        when (method.name) { "observeMediaDrain" -> ExportRelease.RELEASED; else -> error("not used: " + method.name) }
    } as T
    private inner class Fixture {
        val clock = Clock(); val lane = Lane(); val ui = Lane(); val timers = Timers(); val view = View()
        val store = Store(true); val auth = Auth(); val players = mutableListOf<Native>()
        lateinit var read: VaultPlaybackReadPort
        val factory = VaultPlaybackPlatformFactory { ticket, source, _, changed, _ ->
            read = source
            Native().also { players += it; changed(VaultPlaybackView(ticket, VaultPlaybackStatus.READY, 0, 1000, true)) }
        }
        var thumbnailStarts = 0
        var thumbnailReleased = false
        var thumbnailReadRetired = false
        var thumbnailSettled = false
        var nativeUnknown = false
        lateinit var thumbnailRead: VaultThumbnailReadPort
        lateinit var thumbnailResult: (VaultThumbnailResult) -> Unit
        val thumbnailFrames = mutableListOf<VaultThumbnailFrame>()
        val thumbnailFactory = VaultThumbnailPlatformFactory { _, source, _, start, result, _ ->
            thumbnailRead = source; thumbnailResult = result
            thumbnailReleased = false; thumbnailReadRetired = false; thumbnailSettled = false
            object : VaultPreparedThumbnail {
                override fun start() { if (start()) { thumbnailStarts++; source.transportOpened() } }
                override fun revoke() = Unit
                override fun nativeReleaseObserved() = thumbnailReleased && !nativeUnknown
                override fun observeDrain() = if (nativeUnknown) VaultPlaybackDrain.UNCONFIRMED
                    else if (thumbnailReleased && thumbnailReadRetired && thumbnailSettled) VaultPlaybackDrain.RELEASED
                    else if (thumbnailReleased) VaultPlaybackDrain.RETIRING else VaultPlaybackDrain.LIVE
                override fun readOwnerRetired() { thumbnailReadRetired = true }
                override fun resultHandoffSettled() { thumbnailSettled = true }
            }
        }
        val runtime = VaultRuntime(store, auth, clock, lane, ui, timers, view, 1,
            idleMediaPort<VaultMediaSourcePort>(), idleMediaPort<VaultPhoneFilesPort>(), factory, view, thumbnailFactory)
        fun thumbnail(ref: VaultMediaReadRef, token: VaultThumbnailHostToken = token()): VaultThumbnailTicket? =
            runtime.openThumbnail(ref, token) { result, settled ->
                (result as? ThumbnailDecodeResult.Released)?.frame?.let(thumbnailFrames::add); settled()
            }
        fun finishThumbnail(withFrame: Boolean = false) {
            thumbnailReleased = true; thumbnailRead.transportClosed()
            thumbnailResult(VaultThumbnailResult(if (withFrame) object : ThumbnailFrame {
                override val width = 256
                override val height = 144
                override val allocationBytes = 147_456L
                override fun close() = Unit
            } else null, !nativeUnknown)); pump()
        }
        var tokenLive = true
        fun token() = VaultPlaybackHostToken(0) { tokenLive }
        fun pump() { var rounds = 0; while (lane.queue.isNotEmpty() || ui.queue.isNotEmpty()) {
            check(++rounds < 100); lane.drain(); ui.drain()
        } }
        fun unlock() {
            runtime.start(); runtime.observePresence(true, true); pump()
            runtime.unlockPassword(VaultCredentialInput(VaultCredentialKind.PASSWORD, "public-test-only".toCharArray())); pump()
        }
        fun reveal(): VaultMediaReadRef { runtime.revealEmptyCatalog(); pump(); return view.rows.single().ref }
        fun finishNative() {
            val player = players.last(); assertTrue(player.revoked); assertFalse(runtime.playbackCleanupComplete())
            player.nativeReleased = true; runtime.playbackHostProgressed(); pump()
            assertTrue(player.readRetired); assertFalse(runtime.playbackCleanupComplete())
            player.loopersTerminated = true; runtime.playbackHostProgressed(); pump()
            assertTrue(runtime.playbackCleanupComplete())
        }
    }
    @Test fun realRuntimeRequiresCurrentReadLeaseRootAuthAndRevealedRefForThumbnail() {
        val f = Fixture(); f.unlock()
        val invented = VaultMediaReadRef("vault", "fixedobject", 1, 1)
        assertNull(f.thumbnail(invented))
        val ref = f.reveal()
        assertNull(f.thumbnail(ref.copy(objectGeneration = 2)))
        assertNotNull(f.thumbnail(ref)); f.pump(); assertEquals(1, f.thumbnailStarts)
        assertTrue(f.players.isEmpty()); assertEquals(0, f.view.offers)
        f.finishThumbnail(); assertTrue(f.runtime.thumbnailWorkComplete())
        assertEquals(1, f.auth.readerCloses); assertEquals(1, f.store.fileCloses)
    }

    @Test fun currentRevealCannotResetNativeAttemptLedgerByRecompositionOrRepeatedReveal() {
        val f = Fixture(); f.unlock(); val ref = f.reveal()
        assertNotNull(f.thumbnail(ref)); f.pump(); f.finishThumbnail()
        f.runtime.revokeThumbnails(); f.runtime.revealEmptyCatalog(); f.pump()
        assertNull(f.thumbnail(ref)); assertEquals(1, f.thumbnailStarts)
    }

    @Test fun currentThumbnailExcludesPlaybackAndMediaAtSoleRuntimeGate() {
        val f = Fixture(); f.unlock(); val ref = f.reveal(); assertNotNull(f.thumbnail(ref)); f.pump()
        assertFalse(f.runtime.openPlayback(ref, f.token()))
        assertFalse(f.runtime.queueMoveToVault(MediaReadRef("fixedordinary", 1, 1)))
        f.finishThumbnail(); assertTrue(f.runtime.openPlayback(ref, f.token()))
    }

    @Test fun reservedMediaWorkRejectsThumbnailBeforeAnyReadOrNativeAcquisition() {
        val f = Fixture(); f.unlock(); val ref = f.reveal()
        assertTrue(f.runtime.queueMoveToVault(MediaReadRef("fixedordinary", 1, 1)))
        assertNull(f.thumbnail(ref)); assertEquals(0, f.thumbnailStarts); assertEquals(0, f.auth.readerCloses)
    }

    @Test fun graceBackgroundRevokesDisplayAndNewExplicitRevealResetsOnlyAfterCleanup() {
        val f = Fixture(); f.unlock(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
        val ref = f.reveal(); val host = f.token(); assertNotNull(f.thumbnail(ref, host)); f.pump(); f.finishThumbnail(true)
        val frame = f.thumbnailFrames.single(); frame.drawIfCurrent { }; assertTrue(frame.display.isCurrent())
        f.tokenLive = false; f.runtime.observePresence(false, false); f.pump()
        assertFalse(frame.display.isCurrent()); assertFalse(f.runtime.thumbnailCleanupComplete())
        f.runtime.thumbnailHostCovered(host); f.pump(); assertTrue(f.runtime.thumbnailCleanupComplete())
        assertEquals(0, f.auth.closes)
        f.runtime.observePresence(true, true); f.tokenLive = true; f.pump()
        assertNull(f.thumbnail(ref))
        val next = f.reveal(); assertNotNull(f.thumbnail(next)); f.pump(); assertEquals(2, f.thumbnailStarts)
    }

    @Test fun unknownNativeBlocksRuntimeCleanupEvenAfterReaderAndRootCoverReturns() {
        val f = Fixture(); f.unlock(); val ref = f.reveal(); val host = f.token()
        assertNotNull(f.thumbnail(ref, host)); f.pump(); f.nativeUnknown = true
        f.runtime.lock(); f.finishThumbnail(); f.runtime.thumbnailHostCovered(host); f.pump()
        assertEquals(1, f.auth.readerCloses); assertEquals(1, f.store.fileCloses)
        assertFalse(f.runtime.thumbnailCleanupComplete()); assertFalse(f.runtime.hostCleanupComplete())
        assertNull(f.thumbnail(ref))
    }
}

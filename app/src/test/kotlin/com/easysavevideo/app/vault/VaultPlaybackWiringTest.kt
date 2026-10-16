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

/** Public deterministic metadata only; doubles are never accepted as production crypto evidence. */
class VaultPlaybackWiringTest {
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
        val runtime = VaultRuntime(store, auth, clock, lane, ui, timers, view, 1,
            idleMediaPort<VaultMediaSourcePort>(), idleMediaPort<VaultPhoneFilesPort>(), factory, view)
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
    @Test fun onlyAnActuallyRevealedCatalogReferenceCanReservePlayback() {
        val f = Fixture(); f.unlock()
        val ref = VaultMediaReadRef("vault", "fixedobject", 1, 1)
        assertFalse(f.runtime.openPlayback(ref, f.token()))
        val actual = f.reveal()
        assertFalse(f.runtime.openPlayback(actual.copy(objectGeneration = 2), f.token()))
        assertTrue(f.runtime.openPlayback(actual, f.token())); f.pump()
        assertEquals(1, f.view.offers); assertEquals(1, f.players.size)
    }
    @Test fun playbackReservationExcludesOrdinaryMediaAtTheActualRuntimeGate() {
        val f = Fixture(); f.unlock(); val ref = f.reveal()
        assertTrue(f.runtime.openPlayback(ref, f.token()))
        assertFalse(f.runtime.queueMoveToVault(MediaReadRef("fixedordinary", 1, 1)))
        f.pump(); f.runtime.closePlayback(checkNotNull(f.view.playback.ticket)); f.pump()
        assertFalse(f.runtime.queueMoveToVault(MediaReadRef("fixedordinary", 1, 1)))
        f.finishNative()
        assertTrue(f.runtime.queueMoveToVault(MediaReadRef("fixedordinary", 1, 1)))
    }
    @Test fun anAlreadyReservedMediaTransferExcludesPlayback() {
        val f = Fixture(); f.unlock(); val ref = f.reveal()
        assertTrue(f.runtime.queueMoveToVault(MediaReadRef("fixedordinary", 1, 1)))
        assertFalse(f.runtime.openPlayback(ref, f.token())); assertTrue(f.players.isEmpty())
    }
    @Test fun closePlaybackRetiresReadOwnerWithoutLockingTheAccountRoot() {
        val f = Fixture(); f.unlock(); val ref = f.reveal()
        assertTrue(f.runtime.openPlayback(ref, f.token())); f.pump()
        f.runtime.closePlayback(checkNotNull(f.view.playback.ticket)); f.pump()
        assertEquals(1, f.auth.readerCloses); assertEquals(1, f.store.fileCloses)
        f.finishNative()
        assertEquals(1, f.auth.readerCloses); assertEquals(1, f.store.fileCloses); assertEquals(0, f.auth.closes)
        assertTrue(f.runtime.openPlayback(ref, f.token()))
    }
    @Test fun backgroundRevokesPlaybackButPreservesTheExistingGracePolicy() {
        val f = Fixture(); f.unlock(); f.runtime.changePolicy(VaultAutoLockPolicy.AFTER_1_MINUTE)
        val ref = f.reveal(); assertTrue(f.runtime.openPlayback(ref, f.token())); f.pump()
        f.tokenLive = false; f.runtime.observePresence(false, false); f.pump(); f.finishNative()
        assertEquals(0, f.auth.closes); assertFalse(f.runtime.openPlayback(ref, f.token()))
        f.runtime.observePresence(true, true); f.pump(); f.tokenLive = true
        assertFalse(f.runtime.openPlayback(ref, f.token())) // A new explicit reveal is still required.
        val next = f.reveal(); assertTrue(f.runtime.openPlayback(next, f.token()))
    }
    @Test fun exactHostTokenRevocationImmediatelyBlocksTheActualReadPublicationPort() {
        val f = Fixture(); f.unlock(); val ref = f.reveal(); val token = f.token()
        assertTrue(f.runtime.openPlayback(ref, token)); f.pump(); f.read.requireCurrent()
        token.revoke()
        assertThrows(java.io.IOException::class.java) { f.read.requireCurrent() }
        f.tokenLive = true
        assertFalse(token.isCurrent())
        assertFalse(f.runtime.openPlayback(ref, f.token())) // The old real resources still occupy the gate.
    }
}

package com.easysavevideo.app.vault

import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.library.DeleteOutcome
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.ArrayDeque

/** Fixed public bytes and fake ports only. No Tink, Android, SQL, user credentials, or device calls. */
class VaultMediaRuntimeTest {
    private val ref = MediaReadRef("ordinary", 1, 1)
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val evidence = ExportEvidence(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun cipher() = VaultCiphertext(byteArrayOf(7))
    private fun snapshot() = VaultStoredSnapshot(VaultPersistentIdentity("vault", "install", "operation",
        "esv.vault.v1.operation.device", "esv.vault.v1.operation.biometric"), VaultStorePhase.READY, 3,
        VaultStagedData(listOf(VaultSlotRecord("password", 1, VaultSlotKind.PASSWORD, cipher(), true)), cipher(),
            cipher(), 1, cipher(), VaultRetryLedger(0, 0, -1, null), cipher()))
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
    private class Mutation(val next: VaultMediaJournal, val context: VaultOperationContext) : VaultMediaMutation { override fun close() = Unit }
    private inner class Fixture {
        val tasks = ArrayDeque<() -> Unit>()
        var live = true; var now = 1L; var observed = snapshot(); var durable = observed; var journal: VaultMediaJournal? = null
        var authCalls = 0; var sourceReads = 0; var sourceCleaned = 0; var committed = 0
        var sourceClose = ExportRelease.RELEASED; var authDrain = ExportRelease.RELEASED; var storeDrain = ExportRelease.RELEASED; var sourceDrain = ExportRelease.RELEASED; var targetDrain = ExportRelease.RELEASED
        var encrypted: (() -> Unit)? = null; var encryptionGate: VaultMediaPublicationGate? = null
        var view = VaultMediaView(); var picked: VaultPhoneFileRequest? = null
        var platformLaunched = false; var pickerRetired = 0; var targetCleaned = 0; var restore = false
        var mediaEntry: VaultMediaEntry? = null; var copied = 0; var readBytes = 0
        var targetCleanup = ExportCleanup.REMOVED; var invalidSelection = false; var retirements = 0
        var cleanupAfterLock = false; var streamReadFailure = false; var publishFailure = false; var publishCasUnknown = false; var sourceStaleBeforeReservation = false; var reconciliations = 0
        val destination = ByteArrayOutputStream()
        val phases = mutableListOf<VaultMediaPhase>()
        val target = object : VaultPhoneFileTarget {}
        val root = object : VaultRootCapability { override fun close() = Unit }
        val file = object : VaultOwnedCiphertext {
            override val operationId get() = journal!!.operation.operationId
            override val identity get() = journal!!.objectIdentity!!
            override fun append(bytes: ByteArray, offset: Int, length: Int) = Unit
            override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int) = -1
            override fun size() = 8L
            override fun forceAndCloseWrite() = ExportRelease.RELEASED
            override fun closeAndObserve() = ExportRelease.RELEASED
        }
        val auth: VaultAuthenticationPort = fake { method, args -> when (method) {
            "observeMediaDrain" -> authDrain
            "authenticateMediaJournal" -> { authCalls++; journal }
            "authenticateCatalog" -> { authCalls++; VaultAuthenticatedCatalog(observed.data!!.catalogRevision, listOfNotNull(mediaEntry)) }
            "sealMediaJournal" -> {
                authCalls++; val next = args[2] as VaultMediaJournal
                assertEquals((args[1] as VaultStoredSnapshot).data!!.catalogRevision, next.expectedCatalogRevision)
                Mutation(next, args[3] as VaultOperationContext)
            }
            "prepareMediaImport" -> {
                authCalls++; assertEquals(VaultMediaPhase.SOURCE_RESERVED, journal!!.phase)
                object : VaultPreparedMediaImport { override fun close() = Unit }
            }
            "encryptMedia" -> { encryptionGate = args[3] as VaultMediaPublicationGate; encrypted?.invoke(); Unit }
            "verifyFinalMedia", "verifyCommittedMedia" -> object : VaultFinalizedMediaProof { override fun close() = Unit }
            "validateFinalizedMedia" -> VaultMediaFinalizedEvidence(journal!!.objectIdentity!!, evidence, VaultCiphertextEvidence(8, "a".repeat(64)))
            "finishMediaImport" -> Mutation(args[4] as VaultMediaJournal, args[5] as VaultOperationContext)
            "openMediaRead" -> {
                val gate = args[4] as VaultMediaPublicationGate
                object : VaultMediaReadCapability {
                    override val authenticatedSize = bytes.size.toLong()
                    var revoked = false
                    override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int, context: VaultOperationContext): Int {
                        require(!revoked); context.checkActive()
                        if (streamReadFailure) throw VaultOperationException(VaultFailure.CORRUPT)
                        return gate.admit().use { permit ->
                            if (position >= bytes.size) -1 else {
                                val size = minOf(length, bytes.size - position.toInt())
                                permit.publish(bytes, position.toInt(), destination, offset, size); readBytes += size; size
                            }
                        }
                    }
                    override fun revoke() { revoked = true }
                    override fun closeAndObserve() = ExportRelease.RELEASED
                }
            }
            else -> error("Unexpected auth call: $method")
        } }
        val store: VaultStorePort = fake { method, args -> when (method) {
            "observeMediaDrain" -> storeDrain
            "inspect" -> VaultStoreObservation.Present(durable)
            "compareAndSwapMedia" -> {
                val mutation = args[1] as Mutation
                if (mutation.next.phase == VaultMediaPhase.CANCELLED && mutation.next.operation.kind == VaultMediaKind.MOVE_IN) assertTrue(restore)
                assertTrue(mutation.context.sharesOperation(args[2] as VaultOperationContext))
                if (publishCasUnknown && mutation.next.phase == VaultMediaPhase.PUBLISH_INTENT)
                    throw VaultOperationException(VaultFailure.UNAVAILABLE)
                advance(args[0] as VaultStoredSnapshot, mutation)
            }
            "createMediaFile", "openMediaFile" -> file
            "commitMedia" -> {
                (args[4] as VaultOperationContext).checkActive()
                assertTrue((args[1] as Mutation).context.sharesOperation(args[4] as VaultOperationContext))
                committed++; val next = advance(args[0] as VaultStoredSnapshot, args[1] as Mutation)
                mediaEntry = VaultMediaEntry(journal!!.objectIdentity!!, 1, evidence, VaultCiphertextEvidence(8, "a".repeat(64)),
                    cipher(), "fixture.mp4", null, null, null, journal!!.operation, ref)
                VaultCommittedMedia(next, object : CommittedMoveReceipt { override fun close() = Unit })
            }
            "sourceDeleted" -> { assertTrue(sourceCleaned > 0); Unit }
            "recoverCommittedMove" -> object : CommittedMoveReceipt { override fun close() = Unit }
            "abandonUncommittedMedia" -> {
                val mutation = args[1] as Mutation
                assertEquals(VaultMediaPhase.CLEANUP_PENDING, mutation.next.phase)
                advance(args[0] as VaultStoredSnapshot, mutation)
                object : VaultUncommittedMoveReceipt { override fun close() = Unit }
            }
            else -> error("Unexpected store call: $method")
        } }
        val source = object : VaultMediaSourcePort {
            override fun observeMediaDrain() = sourceDrain
            override fun reserve(ref: MediaReadRef, operation: VaultMediaOperation): VaultMoveReservation {
                if (sourceStaleBeforeReservation) throw VaultOperationException(VaultFailure.CONFLICT)
                return object : VaultMoveReservation { override fun close() = Unit }
            }
            override fun reserveForReconciliation(ref: MediaReadRef, operation: VaultMediaOperation): VaultMoveReservation {
                reconciliations++
                return object : VaultMoveReservation { override fun close() = Unit }
            }
            override fun retirePresentation(reservation: VaultMoveReservation, onRetired: (VaultOrdinaryRetirementReceipt) -> Unit) =
                onRetired(object : VaultOrdinaryRetirementReceipt {})
            override fun acquire(reservation: VaultMoveReservation, retired: VaultOrdinaryRetirementReceipt): VaultMoveSourceCapability {
                sourceReads++
                return object : VaultMoveSourceCapability {
                    override val metadata = VaultMediaSourceEvidence(ref, evidence, "fixture.mp4", null, null, null)
                    override val input = object : ExportInput {
                        override val stream = ByteArrayInputStream(bytes)
                        override fun close() = sourceClose
                    }
                }
            }
            override fun cleanup(reservation: VaultMoveReservation, receipt: CommittedMoveReceipt): VaultSourceCleanupResult {
                cleanupAfterLock = cleanupAfterLock || !live; sourceCleaned++
                return VaultSourceCleanupResult(DeleteOutcome.DELETED, object : VaultSourceDeletedReceipt {})
            }
            override fun cancelUncommitted(reservation: VaultMoveReservation, receipt: VaultUncommittedMoveReceipt): Boolean { restore = true; return true }
            override fun validateSourceRestored(ref: MediaReadRef, operation: VaultMediaOperation) = restore
            override fun validateSourceDeleted(receipt: VaultSourceDeletedReceipt, ref: MediaReadRef, operation: VaultMediaOperation) = true
        }
        val files: VaultPhoneFilesPort = fake { method, _ -> when (method) {
            "observeMediaDrain" -> targetDrain
            "prepareSelection" -> object : VaultPhoneFileRequest {}
            "retireSelection" -> { if (platformLaunched) false else { pickerRetired++; true } }
            "cleanup" -> { targetCleaned++; targetCleanup }
            "retirePhoneTarget" -> { retirements++; true }
            "targetRecord" -> VaultMediaTargetRecord(journal!!.operation.operationId, "grant", "nonce", if (invalidSelection) null else "content://public-fixture/target", 3)
            "openWrite" -> {
                assertTrue("Complete authentication must precede external output", readBytes >= bytes.size)
                object : ExportWrite { override val stream = destination; override fun close() = ExportRelease.RELEASED }
            }
            "openReadback" -> ExportReadback.Opened(object : ExportInput {
                override val stream = ByteArrayInputStream(destination.toByteArray()); override fun close() = ExportRelease.RELEASED
            })
            "resourcesReleased" -> true
            "publish" -> { assertEquals(VaultMediaPhase.PUBLISH_INTENT, journal!!.phase); if (publishFailure) throw VaultOperationException(VaultFailure.UNAVAILABLE); copied++; Unit }
            "recoverTarget" -> target
            else -> error("Unexpected target call: $method")
        } }
        val runtime = VaultMediaRuntime(Any(), auth, store, source, files, VaultMonotonicClock { now },
            { VaultMediaSession(root, observed) { live } },
            { _, _, task -> tasks.add(task); Unit },
            { before, after -> assertEquals(observed, before); observed = after },
            { live = false; runtimeRevoke?.invoke() }, { view = it }, { picked = it }, {})
        var runtimeRevoke: (() -> Unit)? = null
        private fun advance(expected: VaultStoredSnapshot, mutation: Mutation): VaultStoredSnapshot {
            assertEquals(durable, expected); journal = mutation.next; phases.add(mutation.next.phase)
            return expected.copy(storeRevision = expected.storeRevision + 1, data = expected.data!!.copy(
                catalogRevision = mutation.next.expectedCatalogRevision, mediaJournalEnvelope = cipher(),
                mediaJournalRevision = expected.data!!.mediaJournalRevision + 1)).also { durable = it }
        }
        fun run() { tasks.removeFirst().invoke() }
        fun move() { assertTrue(runtime.queueMove(ref)); runtime.confirmMove() }
    }
    @Test fun completeCoordinatorMoveUnlockReconcileAndSafCopyKeepsVaultOriginal() {
        val f = Fixture(); f.runtimeRevoke = f.runtime::revoke
        f.move(); f.run(); assertEquals(VaultMediaNotice.MOVED, f.view.notice)
        assertFalse(f.live); assertEquals(VaultMediaPhase.COMMITTED_SOURCE_PENDING, f.journal!!.phase)
        // A fresh explicit session is required after catalog binding changed.
        f.live = true; f.runtime.recover(); f.run()
        assertEquals(VaultMediaPhase.COMPLETE, f.journal!!.phase)
        val entry = f.mediaEntry!!
        val privateRef = VaultMediaReadRef("vault", entry.identity.objectId, entry.identity.generation, f.observed.data!!.catalogRevision)
        f.runtime.beginCopy(privateRef); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request))
        f.live = false; f.runtime.revoke(); f.runtime.acceptTarget(request, f.target)
        assertEquals(0, f.destination.size())
        f.live = true; f.runtime.confirmCopy(); f.run()
        assertEquals(VaultMediaNotice.COPIED, f.view.notice); assertEquals(1, f.copied)
        assertArrayEquals(bytes, f.destination.toByteArray()); assertEquals(entry, f.mediaEntry)
        assertEquals(1, f.committed); assertEquals(bytes.size * 2, f.readBytes)
        assertEquals(VaultMediaPhase.COMPLETE, f.journal!!.phase); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun entireSourceAuthenticationFailureBeforeExportLeavesDestinationEmpty() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request)); f.runtime.acceptTarget(request, f.target)
        f.streamReadFailure = true; f.runtime.confirmCopy(); f.run()
        assertEquals(0, f.destination.size()); assertEquals(0, f.copied); assertNotNull(f.mediaEntry)
    }
    @Test fun cancelledLaunchedPickerKeepsExactCallbackForOwnedTargetCleanup() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request)); f.runtime.cancel()
        assertEquals(0, f.pickerRetired); f.runtime.acceptTarget(request, f.target); f.run()
        assertEquals(1, f.targetCleaned); assertEquals(0, f.destination.size()); assertEquals(0, f.copied)
    }
    @Test fun cancelledQueuedPickerCannotLaunchAndRetiresFixedOwnerRequest() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; f.runtime.cancel()
        assertFalse(f.runtime.admitPicker(request)); assertEquals(1, f.pickerRetired)
    }
    @Test fun rejectedHostDispatchRetiresRequestInsteadOfFabricatingPickerResult() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request))
        f.runtime.retireUndispatchedPicker(request)
        assertEquals(1, f.pickerRetired); assertFalse(f.runtime.admitPicker(request))
    }
    @Test fun launchUnknownKeepsExactAppRequestForLateOwnedTargetWithoutCopying() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request)); f.platformLaunched = true
        assertFalse(f.runtime.retireUndispatchedPicker(request)); assertEquals(0, f.pickerRetired)
        f.runtime.cancel(); f.runtime.acceptTarget(request, f.target); f.run()
        assertEquals(1, f.targetCleaned); assertEquals(0, f.destination.size()); assertEquals(0, f.copied)
    }
    @Test fun hiddenFactoryFileAndSafOwnershipBothPreventCleanupAcknowledgement() {
        val f = Fixture(); f.storeDrain = ExportRelease.UNCONFIRMED
        assertFalse(f.runtime.cleanupComplete()); assertFalse(f.runtime.queueMove(ref))
        f.storeDrain = ExportRelease.RELEASED; f.targetDrain = ExportRelease.UNCONFIRMED
        assertFalse(f.runtime.cleanupComplete()); assertFalse(f.runtime.queueMove(ref))
        f.targetDrain = ExportRelease.RELEASED; f.sourceDrain = ExportRelease.UNCONFIRMED
        assertFalse(f.runtime.cleanupComplete()); f.sourceDrain = ExportRelease.RELEASED
        assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun cancelAfterDurablePublishDecisionFailureNeverDeletesTarget() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request)); f.runtime.acceptTarget(request, f.target)
        f.publishFailure = true; f.runtime.confirmCopy(); f.run()
        assertEquals(VaultMediaPhase.PUBLISH_INTENT, f.journal!!.phase)
        f.runtime.cancel(); assertEquals(0, f.targetCleaned); assertTrue(f.tasks.isEmpty())
        f.publishFailure = false; f.runtime.recover(); f.run()
        // The authenticated durable decision is sufficient: recovery never republishes/reopens a replaced URI.
        assertEquals(0, f.copied); assertEquals(0, f.targetCleaned); assertEquals(VaultMediaPhase.COMPLETE, f.journal!!.phase)
    }
    @Test fun cancelAfterUnknownFinalCasNeverGuessesTargetMayBeDeleted() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request)); f.runtime.acceptTarget(request, f.target)
        f.publishCasUnknown = true; f.runtime.confirmCopy(); f.run()
        f.runtime.cancel(); assertEquals(0, f.targetCleaned); assertTrue(f.tasks.isEmpty())
        assertEquals(0, f.copied); assertArrayEquals(bytes, f.destination.toByteArray())
    }
    @Test fun preservedJournalAfterCredentialRevisionChangeUsesFreshCurrentCatalogProposal() {
        val f = Fixture(); f.move(); f.run()
        val oldJournal = f.journal!!
        f.observed = f.observed.copy(storeRevision = f.observed.storeRevision + 1,
            data = f.observed.data!!.copy(catalogRevision = f.observed.data!!.catalogRevision + 1))
        f.durable = f.observed
        assertEquals(oldJournal, f.journal)
        f.live = true; f.runtime.recover(); f.run()
        assertEquals(VaultMediaPhase.COMPLETE, f.journal!!.phase)
        assertEquals(f.observed.data!!.catalogRevision, f.journal!!.expectedCatalogRevision)
        assertEquals(1, f.committed); assertEquals(0, f.copied)
    }
    @Test fun preparedBeforeOrdinaryReservationCanCancelStaleSourceWithoutReadingReplacement() {
        val f = Fixture(); f.sourceStaleBeforeReservation = true; f.move(); f.run()
        assertEquals(VaultMediaPhase.PREPARED, f.journal!!.phase); assertEquals(0, f.sourceReads)
        f.runtime.recover(); f.run()
        assertEquals(1, f.reconciliations); assertEquals(0, f.sourceReads); assertTrue(f.restore)
        assertTrue(f.phases.indexOf(VaultMediaPhase.CLEANUP_PENDING) < f.phases.indexOf(VaultMediaPhase.CANCELLED))
        assertEquals(VaultMediaPhase.CANCELLED, f.journal!!.phase); assertEquals(0, f.committed)
    }
    @Test fun removedTargetReceiptRemainsUntilUnlockedCancellationJournalCompletes() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        val request = f.picked!!; assertTrue(f.runtime.admitPicker(request)); f.runtime.acceptTarget(request, f.target)
        f.runtime.cancel(); f.run(); assertTrue(f.view.hasCopy)
        f.runtime.recover(); f.run()
        assertEquals(VaultMediaPhase.CANCELLED, f.journal!!.phase); assertFalse(f.view.hasCopy)
        assertEquals(0, f.copied); assertNotNull(f.mediaEntry)
    }
    @Test fun queuedMoveAfterRevocationPerformsNoCryptoOrSourceRead() {
        val f = Fixture(); f.move(); f.live = false; f.runtime.revoke(); f.run()
        assertEquals(0, f.authCalls); assertEquals(0, f.sourceReads); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun captureInvalidatedBeforeRegistrationNeverQueues() {
        val f = Fixture(); f.live = false; f.move()
        assertTrue(f.tasks.isEmpty()); assertEquals(0, f.authCalls)
    }
    @Test fun sourceReservedIsDurableBeforePreparationAndCommitLocksBeforeCleanup() {
        val f = Fixture(); f.runtimeRevoke = f.runtime::revoke; f.move(); f.run()
        assertEquals(1, f.committed); assertEquals(1, f.sourceCleaned); assertTrue(f.cleanupAfterLock)
        assertEquals(VaultMediaNotice.MOVED, f.view.notice); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun latePublicationAfterRevocationLeavesCallerUntouchedAndPermitActuallyDrains() {
        val f = Fixture(); val caller = byteArrayOf(9, 9)
        f.encrypted = {
            val permit = f.encryptionGate!!.admit()
            f.live = false; f.runtime.revoke()
            assertFalse(f.runtime.cleanupComplete())
            assertThrows(VaultOperationException::class.java) { permit.publish(byteArrayOf(1, 2), 0, caller, 0, 2) }
            assertThrows(VaultOperationException::class.java) { f.encryptionGate!!.admit() }
            permit.close()
        }
        f.move(); f.run(); assertArrayEquals(byteArrayOf(9, 9), caller)
        assertEquals(0, f.committed); assertTrue(f.runtime.cleanupComplete())
    }
    @Test fun actualCloseUnknownKeepsOwnerFenceAndRejectsNextOperation() {
        val f = Fixture(); f.sourceClose = ExportRelease.UNCONFIRMED; f.move(); f.run()
        assertFalse(f.runtime.cleanupComplete()); assertEquals(VaultMediaNotice.RELEASE_UNKNOWN, f.view.notice)
        assertFalse(f.runtime.queueMove(ref.copy(revision = 2))); assertEquals(0, f.committed)
    }
    @Test fun factoryRetainedUnknownReaderCannotBeHiddenByEmptyRuntimeRegistry() {
        val f = Fixture(); f.authDrain = ExportRelease.UNCONFIRMED
        assertFalse(f.runtime.cleanupComplete()); assertFalse(f.runtime.queueMove(ref))
    }
    @Test fun singlePresentUncertainOutputHasExplicitNonSuccessExit() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        f.runtime.acceptTarget(f.picked!!, f.target)
        f.streamReadFailure = true; f.targetCleanup = ExportCleanup.UNCONFIRMED
        f.runtime.confirmCopy(); f.run(); f.runtime.recover(); f.run()
        assertNotNull(f.view.actionToken); f.runtime.keepCurrentSingleOutput(f.view.actionToken); f.run()
        assertEquals(VaultMediaPhase.OUTPUT_RETAINED, f.journal!!.phase)
        assertEquals(VaultMediaNotice.OUTPUT_RETAINED, f.view.notice)
        assertEquals(0, f.copied); assertEquals(1, f.retirements); assertNotNull(f.mediaEntry)
    }
    @Test fun invalidSingleSelectionReservationCanBeKeptWithoutWriting() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        f.invalidSelection = true; f.runtime.acceptTarget(f.picked!!, f.target); f.runtime.confirmCopy(); f.run()
        assertEquals(VaultMediaPhase.RECONCILIATION_REQUIRED, f.journal!!.phase)
        assertNull(f.journal!!.target!!.uri); assertEquals(0, f.destination.size())
        f.runtime.keepCurrentSingleOutput(f.view.actionToken); f.run()
        assertEquals(VaultMediaPhase.OUTPUT_RETAINED, f.journal!!.phase); assertEquals(1, f.retirements)
    }

    @Test fun validPickedDocumentCancelledBeforeBindingCanBeExplicitlyKept() {
        val f = Fixture(); f.move(); f.run(); f.live = true; f.runtime.recover(); f.run()
        val entry = f.mediaEntry!!
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        f.runtime.acceptTarget(f.picked!!, f.target); f.targetCleanup = ExportCleanup.UNCONFIRMED
        f.runtime.cancel(); f.run(); f.runtime.recover(); f.run()
        assertEquals(VaultMediaPhase.RECONCILIATION_REQUIRED, f.journal!!.phase)
        assertNotNull(f.journal!!.target!!.uri); assertEquals(0, f.destination.size())
        f.runtime.keepCurrentSingleOutput(f.view.actionToken); f.run()
        assertEquals(VaultMediaPhase.OUTPUT_RETAINED, f.journal!!.phase); assertEquals(1, f.retirements)
        f.runtime.beginCopy(VaultMediaReadRef("vault", entry.identity.objectId, 1, f.observed.data!!.catalogRevision)); f.run()
        assertEquals(VaultMediaPhase.PICKING_TARGET, f.journal!!.phase); assertEquals(0, f.destination.size())
    }

}

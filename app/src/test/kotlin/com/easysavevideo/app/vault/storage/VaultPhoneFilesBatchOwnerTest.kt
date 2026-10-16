package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy

/** Fixed synthetic platform facts only. This does not execute Android, SAF, SQLite, or a device. */
class VaultPhoneFilesBatchOwnerTest {
    private val batchId = "b".repeat(32)
    private val operation = VaultMediaOperation("a".repeat(32), 1, VaultMediaKind.COPY_OUT)
    private val identity = VaultMediaObjectIdentity("fixed", 1)
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
    private val childUri = "$treeUri/document/primary%3AMovies%2Ffixed.mp4"
    private val singleUri = "content://com.android.externalstorage.documents/document/primary%3AMovies%2Ffixed.mp4"
    private val provider = VaultPhoneFilesProfile.Provider(VaultPhoneFilesProfile.AUTHORITY,
        VaultPhoneFilesProfile.PACKAGE, VaultPhoneFilesProfile.CLASS, true, true, true, true, true,
        VaultPhoneFilesProfile.MANAGE_DOCUMENTS, VaultPhoneFilesProfile.MANAGE_DOCUMENTS)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        catch (_: VaultOperationException) { }
    }
    private inner class FakePlatform : AndroidVaultPhoneFiles.Platform {
        var grant = true
        var childFact = true
        var factsProvider = provider
        var held = false
        var createCount = 0
        var writeCount = 0
        var readCount = 0
        var nextCreated: String? = childUri
        var throwCreate = false
        var onCreate: (() -> Unit)? = null
        var onDocument: (() -> Unit)? = null
        var onOpen: (() -> Unit)? = null
        var onChildren: (() -> Unit)? = null
        var afterChildren: List<String>? = null
        val documents = mutableMapOf(treeUri to VaultPhoneFilesProfile.Document("primary:Movies", VaultPhoneFilesProfile.DIRECTORY_MIME, false, true),
            singleUri to VaultPhoneFilesProfile.Document("primary:Movies/fixed.mp4", "video/mp4", false, false, true, true))
        val facts = mutableMapOf(treeUri to AndroidVaultPhoneFiles.UriFacts(true, true, null, "primary:Movies"),
            childUri to AndroidVaultPhoneFiles.UriFacts(true, false, "primary:Movies/fixed.mp4", "primary:Movies"),
            singleUri to AndroidVaultPhoneFiles.UriFacts(true, false, "primary:Movies/fixed.mp4", null))
        var ids = emptyList<String>()
        override fun uriFacts(uri: String) = facts[uri] ?: AndroidVaultPhoneFiles.UriFacts(false, false, null, null)
        override fun provider() = factsProvider
        override fun permission(uri: String, flags: Int) = grant && flags == 3
        override fun document(uri: String): VaultPhoneFilesProfile.Document? { onDocument?.invoke(); return documents[uri] }
        override fun children(tree: String): List<String> { onChildren?.invoke(); return if (createCount > 0) afterChildren ?: ids else ids }
        override fun isChild(tree: String, child: String) = childFact
        override fun create(tree: String, name: String): String? {
            createCount++
            assertEquals(treeUri, tree)
            assertTrue(name.matches(Regex("ESV-[a-f0-9]{32}\\.mp4")))
            onCreate?.invoke()
            if (throwCreate) throw VaultOperationException(VaultFailure.UNAVAILABLE)
            nextCreated?.let { value ->
                val id = facts[value]?.documentId ?: "primary:Movies/unknown.mp4"
                documents.putIfAbsent(value, VaultPhoneFilesProfile.Document(id, "video/mp4", false, false, true, true))
                if (id !in ids) ids = ids + id
            }
            return nextCreated
        }
        override fun openWrite(operationId: String, uri: String): ExportWrite {
            writeCount++; held = true; onOpen?.invoke()
            return object : ExportWrite {
                override val stream = ByteArrayOutputStream()
                override fun close(): ExportRelease { held = false; return ExportRelease.RELEASED }
            }
        }
        override fun openReadback(operationId: String, uri: String): ExportReadback {
            readCount++; held = true
            return ExportReadback.Opened(object : ExportInput {
                override val stream = ByteArrayInputStream(byteArrayOf(1))
                override fun close(): ExportRelease { held = false; return ExportRelease.RELEASED }
            })
        }
        override fun released(operationId: String) = !held
        override fun observeDrain() = if (held) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
    }
    private inner class Fixture {
        val platform = FakePlatform()
        var journal: VaultMediaJournal? = null
        var known = true
        var retirementHook: (() -> Unit)? = null
        val store = Proxy.newProxyInstance(VaultStorePort::class.java.classLoader, arrayOf(VaultStorePort::class.java)) { _, method, args ->
            val current = if (known) journal else null
            when (method.name) {
                "validateBoundPhoneTree" -> VaultPhoneStoreFacts.tree(current, args[0] as VaultPhoneTreeRecord)
                "validateBatchChildCreate" -> VaultPhoneStoreFacts.create(current, args[0] as VaultPhoneTreeRecord,
                    args[1] as VaultMediaTargetRecord, args[2] as VaultMediaOperation)
                "validateBoundPhoneTarget" -> VaultPhoneStoreFacts.targetUse(current, args[0] as VaultMediaTargetRecord, args[1] as VaultPhoneTargetUse)
                "canCleanupPhoneTarget" -> VaultPhoneStoreFacts.cleanup(current, args[0] as VaultMediaTargetRecord)
                "validatePhoneTargetRetirement" -> {
                    retirementHook?.invoke()
                    VaultPhoneStoreFacts.retirement(current, args[0] as String, args[1] as VaultMediaTargetRecord, args[2] as String?)
                }
                else -> error("Unexpected fixed-store call")
            }
        } as VaultStorePort
        var live = true
        val gate = object : VaultMediaPublicationGate {
            override fun requireCurrent() { if (!live) throw VaultOperationException(VaultFailure.CANCELLED) }
            override fun admit(): VaultMediaIoPermit {
                requireCurrent()
                return object : VaultMediaIoPermit {
                    override fun publish(scratch: ByteArray, sourceOffset: Int, destination: ByteArray, destinationOffset: Int, length: Int) { requireCurrent(); scratch.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length) }
                    override fun close() = Unit
                }
            }
        }
        val owner = AndroidVaultPhoneFiles(store, platform)
        fun directory(): VaultPhoneDirectoryTarget {
            val request = owner.prepareDirectorySelection(batchId)
            assertEquals(AndroidVaultPhoneFiles.Purpose.BATCH_TREE, owner.markLaunched(request))
            val result = owner.acceptSelection(request, true, treeUri, 3) as VaultPhoneSelection.Directory
            val tree = owner.directoryRecord(result.target, batchId)
            val row = VaultCopyBatchItem(operation.operationId, identity, 1, VaultCopyBatchOutcome.ACTIVE)
            journal = VaultMediaJournal(operation, VaultMediaPhase.PICKING_TARGET, 1, objectIdentity = identity,
                batch = VaultCopyBatchState(batchId, 1, 1, "c".repeat(64), listOf(row), tree, 0, 1,
                    VaultCopyBatchStop.NONE, VaultCopyBatchStatus.RUNNING))
            return result.target
        }
        fun reserve(directory: VaultPhoneDirectoryTarget): VaultMediaTargetRecord {
            val reservation = owner.prepareBatchChild(directory, operation)
            phase(VaultMediaPhase.CHILD_CREATE_INTENT, reservation)
            return reservation
        }
        fun child(): Pair<VaultPhoneFileTarget, VaultMediaTargetRecord> {
            val directory = directory()
            val reservation = reserve(directory)
            val target = owner.createBatchChild(directory, reservation, operation, gate)
            val record = owner.targetRecord(target, operation)
            phase(VaultMediaPhase.TARGET_BOUND, record)
            return target to record
        }
        fun single(): Pair<VaultPhoneFileTarget, VaultMediaTargetRecord> {
            val request = owner.prepareSelection(operation)
            owner.markLaunched(request)
            val target = (owner.acceptSelection(request, true, singleUri, 3) as VaultPhoneSelection.File).target
            val record = owner.targetRecord(target, operation)
            journal = VaultMediaJournal(operation, VaultMediaPhase.TARGET_BOUND, 1, objectIdentity = identity, target = record)
            return target to record
        }
        fun phase(phase: VaultMediaPhase, target: VaultMediaTargetRecord?, outcome: VaultCopyBatchOutcome = VaultCopyBatchOutcome.ACTIVE) {
            val old = requireNotNull(journal)
            val batch = old.batch?.let { b -> b.copy(items = b.items.mapIndexed { i, row ->
                if (i == b.activeIndex) row.copy(outcome = outcome, target = target) else row
            }) }
            journal = old.copy(phase = phase, target = target, batch = batch,
                cancelRequested = phase in setOf(VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED))
        }
    }
    @Test fun singleAndBatchSameUriReplacementIsAlwaysRetained() {
        listOf(false, true).forEach { batch ->
            val f = Fixture(); val (target, _) = if (batch) f.child() else f.single()
            val uri = if (batch) childUri else singleUri
            // A different present incarnation has identical path-derived document facts and grant.
            f.platform.documents[uri] = f.platform.documents.getValue(uri).copy()
            assertEquals(ExportCleanup.UNCONFIRMED, f.owner.cleanup(target))
            assertEquals(0, f.platform.writeCount)
            assertEquals(0, f.platform.readCount)
        }
    }
    @Test fun freshSingleRecoveryGrantNeverAdoptsPresentReplacement() {
        val f = Fixture(); val (_, record) = f.single()
        val restarted = AndroidVaultPhoneFiles(f.store, f.platform)
        val request = restarted.prepareCleanupSelection(record)
        restarted.markLaunched(request)
        val target = (restarted.acceptSelection(request, true, singleUri, 3) as VaultPhoneSelection.File).target
        assertEquals(ExportCleanup.UNCONFIRMED, restarted.cleanup(target))
        rejected { restarted.openWrite(target) }
        rejected { restarted.openReadback(target) }
    }
    @Test fun exactAbsenceCanCloseButGrantLossCannotPretendAbsence() {
        listOf(false, true).forEach { batch ->
            val f = Fixture(); val (target, _) = if (batch) f.child() else f.single()
            f.platform.documents.remove(if (batch) childUri else singleUri)
            f.platform.grant = false
            assertEquals(ExportCleanup.UNCONFIRMED, f.owner.cleanup(target))
            f.platform.grant = true
            assertEquals(ExportCleanup.REMOVED, f.owner.cleanup(target))
        }
    }
    @Test fun unknownCreateRegistersReservationNeverRetriesAndMayBeExplicitlyRetiredAfterReturn() {
        listOf(false, true).forEach { throws ->
            val f = Fixture(); val directory = f.directory(); val reservation = f.reserve(directory)
            f.platform.nextCreated = null; f.platform.throwCreate = throws
            rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
            val candidate = requireNotNull(f.owner.recoverTarget(reservation))
            assertEquals(reservation, f.owner.targetRecord(candidate, operation))
            assertEquals(ExportRelease.RELEASED, f.owner.observeMediaDrain())
            assertEquals(ExportCleanup.UNCONFIRMED, f.owner.cleanup(candidate))
            rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
            assertEquals(1, f.platform.createCount)
            assertFalse(f.owner.retirePhoneTarget(candidate, operation.operationId, batchId))
            f.phase(VaultMediaPhase.CANCELLED, reservation, VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
            assertTrue(f.owner.retirePhoneTarget(candidate, operation.operationId, batchId))
            assertNull(f.owner.recoverTarget(reservation))
            rejected { f.owner.openWrite(candidate) }
            rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
            assertEquals(1, f.platform.createCount)
        }
    }
    @Test fun validatedReturnedChildWithConfirmedOldBindCasRetiresAgainstItsOwnDurableReservation() {
        val f = Fixture(); val directory = f.directory(); val reservation = f.reserve(directory)
        val target = f.owner.createBatchChild(directory, reservation, operation, f.gate)
        assertEquals(childUri, f.owner.targetRecord(target, operation).uri)
        // Binding was confirmed old. Never rebase the durable record from live provider data.
        assertEquals(reservation, f.journal!!.target)
        f.phase(VaultMediaPhase.CANCELLED, reservation, VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
        assertTrue(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        assertNull(f.owner.recoverTarget(reservation))
        assertNotNull(f.platform.documents[childUri])
        assertEquals(0, f.platform.writeCount)
        f.phase(VaultMediaPhase.CHILD_CREATE_INTENT, reservation)
        rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
        assertEquals(1, f.platform.createCount)
    }
    @Test fun createIsPersistedPhaseAndExactStoreBoundBeforeProviderMutation() {
        val f = Fixture(); val directory = f.directory()
        val reservation = f.owner.prepareBatchChild(directory, operation)
        rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
        assertEquals(0, f.platform.createCount)
        assertNotNull(f.owner.recoverTarget(reservation))
    }
    @Test fun returnedExistingOrWrongParentChildRemainsCandidateOnly() {
        listOf("existing", "cousin", "grandchild", "differentTree", "virtual", "directory", "mime", "containment", "noWrite", "noDelete").forEach { bad ->
            val f = Fixture(); val directory = f.directory(); val reservation = f.reserve(directory)
            when (bad) {
                "containment" -> f.platform.childFact = false
                "noWrite" -> f.platform.documents[childUri] = VaultPhoneFilesProfile.Document("primary:Movies/fixed.mp4", "video/mp4", false, false, false, true)
                "noDelete" -> f.platform.documents[childUri] = VaultPhoneFilesProfile.Document("primary:Movies/fixed.mp4", "video/mp4", false, false, true, false)
                "existing" -> f.platform.ids = listOf("primary:Movies/fixed.mp4")
                "cousin" -> f.platform.facts[childUri] = AndroidVaultPhoneFiles.UriFacts(true, false, "primary:Other/fixed.mp4", "primary:Movies")
                "grandchild" -> f.platform.facts[childUri] = AndroidVaultPhoneFiles.UriFacts(true, false, "primary:Movies/sub/fixed.mp4", "primary:Movies")
                "differentTree" -> f.platform.facts[childUri] = AndroidVaultPhoneFiles.UriFacts(true, false, "primary:Movies/fixed.mp4", "primary:Other")
                "virtual" -> f.platform.documents[childUri] = VaultPhoneFilesProfile.Document("primary:Movies/fixed.mp4", "video/mp4", true, false)
                "directory" -> f.platform.documents[childUri] = VaultPhoneFilesProfile.Document("primary:Movies/fixed.mp4", VaultPhoneFilesProfile.DIRECTORY_MIME, false, true)
                "mime" -> f.platform.documents[childUri] = VaultPhoneFilesProfile.Document("primary:Movies/fixed.mp4", "text/plain", false, false)
            }
            rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
            val candidate = requireNotNull(f.owner.recoverTarget(reservation))
            assertEquals(reservation, f.owner.targetRecord(candidate, operation))
            assertEquals(ExportCleanup.UNCONFIRMED, f.owner.cleanup(candidate))
            rejected { f.owner.openWrite(candidate) }
            assertEquals(0, f.platform.writeCount)
        }
    }
    @Test fun postCreateEnumerationMustBeCompleteAndExactlyOneFreshImmediateChild() {
        val f = Fixture(); val directory = f.directory(); val reservation = f.reserve(directory)
        f.platform.afterChildren = listOf("primary:Movies/fixed.mp4", "primary:Movies/unrelated.mp4")
        rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
        assertNotNull(f.owner.recoverTarget(reservation))
        assertEquals(0, f.platform.writeCount)
    }
    @Test fun coverDuringBlockingPreflightRejectsActualProviderCreate() {
        val f = Fixture(); val directory = f.directory(); val reservation = f.reserve(directory)
        f.platform.onChildren = { f.live = false }
        rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
        assertEquals(0, f.platform.createCount)
        assertNotNull(f.owner.recoverTarget(reservation))
        assertEquals(ExportRelease.RELEASED, f.owner.observeMediaDrain())
    }
    @Test fun createAdmissionBlocksReentrantDrainAndRetirementThroughRealReturn() {
        val f = Fixture(); val directory = f.directory(); val reservation = f.reserve(directory)
        f.platform.onCreate = {
            val candidate = requireNotNull(f.owner.recoverTarget(reservation))
            assertEquals(ExportRelease.UNCONFIRMED, f.owner.observeMediaDrain())
            assertFalse(f.owner.resourcesReleased(candidate))
            f.phase(VaultMediaPhase.CANCELLED, reservation, VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
            assertFalse(f.owner.retirePhoneTarget(candidate, operation.operationId, batchId))
            rejected { f.owner.prepareSelection(operation.copy(operationId = "d".repeat(32))) }
        }
        f.owner.createBatchChild(directory, reservation, operation, f.gate)
        assertEquals(ExportRelease.RELEASED, f.owner.observeMediaDrain())
    }
    @Test fun writerAdmissionAndUnclosedHandlePreventRetirementAndSecondWriter() {
        val f = Fixture(); val (target, record) = f.child()
        f.phase(VaultMediaPhase.COPYING, record)
        f.platform.onOpen = {
            assertEquals(ExportRelease.UNCONFIRMED, f.owner.observeMediaDrain())
            f.phase(VaultMediaPhase.CANCELLED, record, VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
            assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        }
        val writer = f.owner.openWrite(target)
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        rejected { f.owner.openWrite(target) }
        writer.close()
        assertTrue(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        assertEquals(1, f.platform.writeCount)
    }
    @Test fun pendingCasWrongIdentityAndOtherOwnerCannotRetire() {
        val f = Fixture(); val (target, record) = f.child()
        f.phase(VaultMediaPhase.CANCELLED, record, VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
        f.known = false
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        f.known = true
        assertFalse(f.owner.retirePhoneTarget(target, "d".repeat(32), batchId))
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, "e".repeat(32)))
        assertFalse(AndroidVaultPhoneFiles(f.store, f.platform).retirePhoneTarget(target, operation.operationId, batchId))
        f.platform.held = true
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        f.platform.held = false
        assertTrue(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
    }
    @Test fun exactRetirementRechecksRealDrainAfterStoreValidation() {
        val f = Fixture(); val (target, record) = f.child()
        f.phase(VaultMediaPhase.CANCELLED, record, VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
        f.retirementHook = { f.platform.held = true }
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        assertNotNull(f.owner.recoverTarget(record))
    }
    @Test fun everyIoRechecksCurrentGrantAndNeverReopensAnAttemptedWriter() {
        val f = Fixture(); val (target, record) = f.child()
        f.phase(VaultMediaPhase.COPYING, record); f.platform.grant = false
        rejected { f.owner.openWrite(target) }
        f.platform.grant = true
        rejected { f.owner.openWrite(target) }
        assertEquals(0, f.platform.writeCount)
        f.phase(VaultMediaPhase.VERIFYING, record); f.platform.grant = false
        rejected { f.owner.openReadback(target) }
        assertEquals(0, f.platform.readCount)
    }
    @Test fun onlyOneOutstandingPickerAndLateCallbackCannotConsumeNewRequest() {
        val f = Fixture(); val first = f.owner.prepareDirectorySelection(batchId)
        rejected { f.owner.prepareSelection(operation) }
        assertTrue(f.owner.retireSelection(first))
        val second = f.owner.prepareDirectorySelection(batchId)
        f.owner.markLaunched(second)
        assertFalse(f.owner.retireSelection(second))
        rejected { f.owner.acceptSelection(first, true, treeUri, 3) }
        assertNotNull(f.owner.acceptSelection(second, true, treeUri, 3))
        rejected { f.owner.acceptSelection(second, true, treeUri, 3) }
    }
    @Test fun freshSameTreeRecoveryPreservesRecordAndInvalidatesOldWitness() {
        val f = Fixture(); val old = f.directory()
        val record = f.owner.directoryRecord(old, batchId)
        val request = f.owner.prepareDirectoryRecoverySelection(record)
        f.owner.markLaunched(request)
        val fresh = (f.owner.acceptSelection(request, true, treeUri, 3) as VaultPhoneSelection.Directory).target
        assertSame(record, f.owner.directoryRecord(fresh, batchId))
        rejected { f.owner.validateDirectory(old) }
        f.owner.validateDirectory(fresh)
        assertEquals(0, f.platform.createCount)
    }
    @Test fun freshDifferentTreeOrMissingEffectiveGrantCannotAuthorizeRecovery() {
        listOf("different", "flags", "grant").forEach { mode ->
            val f = Fixture(); val old = f.directory(); val record = f.owner.directoryRecord(old, batchId)
            val request = f.owner.prepareDirectoryRecoverySelection(record); f.owner.markLaunched(request)
            if (mode == "grant") f.platform.grant = false
            rejected { f.owner.acceptSelection(request, true, if (mode == "different") "$treeUri/other" else treeUri, if (mode == "flags") 1 else 3) }
            assertEquals(record, f.owner.directoryRecord(old, batchId))
            assertEquals(0, f.platform.createCount)
        }
    }
    @Test fun immutableArchivedRowNeverAuthorizesActiveIoOrCleanup() {
        val f = Fixture(); val (_, record) = f.child()
        f.phase(VaultMediaPhase.COMPLETE, record, VaultCopyBatchOutcome.VERIFIED_COPY)
        val nextOp = operation.copy(operationId = "d".repeat(32), sequence = 2)
        val nextIdentity = VaultMediaObjectIdentity("next", 1)
        val old = f.journal!!; val batch = old.batch!!
        f.journal = old.copy(operation = nextOp, phase = VaultMediaPhase.PREPARED, objectIdentity = nextIdentity, target = null,
            batch = batch.copy(items = batch.items + VaultCopyBatchItem(nextOp.operationId, nextIdentity, 2, VaultCopyBatchOutcome.ACTIVE), activeIndex = 1))
        VaultPhoneTargetUse.entries.forEach { assertFalse(VaultPhoneStoreFacts.targetUse(f.journal, record, it)) }
        assertFalse(VaultPhoneStoreFacts.cleanup(f.journal, record))
        assertTrue(VaultPhoneStoreFacts.retirement(f.journal, operation.operationId, record, batchId))
        assertFalse(VaultPhoneStoreFacts.retirement(f.journal, operation.operationId, record.copy(nonce = "e".repeat(32)), batchId))
    }
    @Test fun finalWinnerAndUnknownCasCannotBecomeCleanupOrRetained() {
        val f = Fixture(); val (target, record) = f.child()
        f.phase(VaultMediaPhase.PUBLISH_INTENT, record, VaultCopyBatchOutcome.FINAL_DECISION_PENDING)
        assertEquals(ExportCleanup.UNCONFIRMED, f.owner.cleanup(target))
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
        f.phase(VaultMediaPhase.COMPLETE, record, VaultCopyBatchOutcome.VERIFIED_COPY)
        assertEquals(ExportCleanup.UNCONFIRMED, f.owner.cleanup(target))
        f.known = false
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId, batchId))
    }
    @Test fun invalidSingleReturnRemainsRecoverableReservationAndCanBeExplicitlyKept() {
        val f = Fixture(); f.platform.factsProvider = provider.copy(androidSignature = false)
        val (target, record) = f.single()
        assertNull(record.uri)
        assertSame(target, f.owner.recoverTarget(record))
        assertEquals(ExportCleanup.UNCONFIRMED, f.owner.cleanup(target))
        rejected { f.owner.openWrite(target) }
        f.phase(VaultMediaPhase.OUTPUT_RETAINED, record)
        assertTrue(f.owner.retirePhoneTarget(target, operation.operationId))
    }
    @Test fun knownSingleSuccessOrConfirmedAbsentCancellationSafelyPrunesOnlyAfterDurableFact() {
        val f = Fixture(); val (target, record) = f.single()
        f.platform.documents.remove(singleUri)
        assertEquals(ExportCleanup.REMOVED, f.owner.cleanup(target))
        rejected { f.owner.prepareSelection(operation.copy(operationId = "d".repeat(32), sequence = 2)) }
        f.phase(VaultMediaPhase.CANCELLED, record)
        f.known = false
        rejected { f.owner.prepareSelection(operation.copy(operationId = "d".repeat(32), sequence = 2)) }
        f.known = true
        assertNotNull(f.owner.prepareSelection(operation.copy(operationId = "d".repeat(32), sequence = 2)))
    }
    @Test fun explicitSingleRetainedIsNonSuccessAndPermanentlyRevokesTarget() {
        val f = Fixture(); val (target, record) = f.single()
        assertFalse(f.owner.retirePhoneTarget(target, operation.operationId))
        f.phase(VaultMediaPhase.OUTPUT_RETAINED, record)
        assertTrue(f.owner.retirePhoneTarget(target, operation.operationId))
        assertNull(f.owner.recoverTarget(record))
        rejected { f.owner.publish(target) }
        assertNotNull(f.owner.prepareSelection(operation.copy(operationId = "d".repeat(32), sequence = 2)))
    }
    @Test fun boundedBeforeEnumerationRejectsDuplicateOrOverflowWithoutCreate() {
        listOf(listOf("primary:Movies/one", "primary:Movies/one"),
            List(1001) { "primary:Movies/$it" }, listOf("primary:Movies/sub/grandchild")).forEach { ids ->
            val f = Fixture(); val directory = f.directory(); val reservation = f.reserve(directory)
            f.platform.ids = ids
            rejected { f.owner.createBatchChild(directory, reservation, operation, f.gate) }
            assertEquals(0, f.platform.createCount)
            assertEquals(ExportRelease.RELEASED, f.owner.observeMediaDrain())
        }
    }
}

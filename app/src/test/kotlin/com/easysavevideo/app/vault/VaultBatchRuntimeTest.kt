package com.easysavevideo.app.vault

import com.easysavevideo.app.vault.crypto.VaultMediaJournalWire
import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.ArrayDeque

/** Public fixed bytes, in-memory fake ports and pure journal validation only. No real crypto/platform/credential calls. */
class VaultBatchRuntimeTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val evidence = ExportEvidence(4, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun cipher() = VaultCiphertext(byteArrayOf(7))
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T
    private class Mutation(val next: VaultMediaJournal, val context: VaultOperationContext) : VaultMediaMutation { override fun close() = Unit }
    private class Target(var record: VaultMediaTargetRecord) : VaultPhoneFileTarget {
        val bytes = ByteArrayOutputStream(); var retired = false
    }
    private inner class Fixture(val count: Int) {
        var now = 1L; var live = true; var journal: VaultMediaJournal? = null
        var durable = VaultStoredSnapshot(VaultPersistentIdentity("vault", "install", "operation", "device", "bio"),
            VaultStorePhase.READY, 1, VaultStagedData(listOf(VaultSlotRecord("password", 1, VaultSlotKind.PASSWORD, cipher(), true)),
                cipher(), cipher(), 1, cipher(), VaultRetryLedger(0, 0, -1, null), cipher()))
        var observed = durable
        val entries = (1..count).map { i -> VaultMediaEntry(VaultMediaObjectIdentity("object$i", 1), 1, evidence,
            VaultCiphertextEvidence(8, "a".repeat(64)), cipher(), "public-fixture-$i.mp4", null, null, null,
            VaultMediaOperation("move$i", i.toLong(), VaultMediaKind.MOVE_IN), MediaReadRef("ordinary$i", 1, 1)) }
        val refs get() = entries.map { VaultMediaReadRef("vault", it.identity.objectId, 1, observed.data!!.catalogRevision) }
        val tasks = ArrayDeque<() -> Unit>()
        val targets = linkedMapOf<String, Target>()
        var treeRecord: VaultPhoneTreeRecord? = null
        val directory = object : VaultPhoneDirectoryTarget {}
        var picker: VaultPhoneFileRequest? = null; var pickCount = 0; var creates = 0; var published = 0; var reads = 0
        var failReadItem = -1; var cancelWriteItem = -1; var lockCreateItem = -1; var unknownClose = false
        var failCreateItem = -1; var cleanupRemoved = true; var pendingUnknownCas = false; var injectUnknownPublish = false
        var deadlineAdvancePerWrite = 0L; var onPublish: (() -> Unit)? = null
        var leaveCreateDrainUnknown = false
        var pauseOnRunReservation = false
        var view = VaultMediaView()
        val deadlines = mutableListOf<Long>()
        val history = mutableListOf<VaultMediaJournal>()
        val root = object : VaultRootCapability { override fun close() = Unit }
        val auth: VaultAuthenticationPort = fake { method, args -> when (method) {
            "observeMediaDrain" -> ExportRelease.RELEASED
            "authenticateMediaJournal" -> journal
            "authenticateCatalog" -> VaultAuthenticatedCatalog(observed.data!!.catalogRevision, entries)
            "sealMediaJournal" -> {
                val next = args[2] as VaultMediaJournal
                VaultMediaJournalWire.requireSuccessor(journal, next)
                Mutation(next, args[3] as VaultOperationContext)
            }
            "openMediaRead" -> {
                val gate = args[4] as VaultMediaPublicationGate
                val index = journal!!.batch?.activeIndex ?: 0
                object : VaultMediaReadCapability {
                    var revoked = false
                    override val authenticatedSize = 4L
                    override fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int, context: VaultOperationContext): Int {
                        require(!revoked); context.checkActive(); reads++
                        if (index == failReadItem) throw VaultOperationException(VaultFailure.CORRUPT)
                        return gate.admit().use { permit ->
                            if (position >= bytes.size) -1 else minOf(length, bytes.size - position.toInt()).also {
                                permit.publish(bytes, position.toInt(), destination, offset, it)
                            }
                        }
                    }
                    override fun revoke() { revoked = true }
                    override fun closeAndObserve() = if (unknownClose) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
                }
            }
            else -> error("Unexpected fake authentication call $method")
        } }
        val store: VaultStorePort = fake { method, args -> when (method) {
            "observeMediaDrain" -> ExportRelease.RELEASED
            "inspect" -> if (pendingUnknownCas) VaultStoreObservation.Unavailable(VaultFailure.INCOMPLETE) else VaultStoreObservation.Present(durable)
            "compareAndSwapMedia" -> {
                val mutation = args[1] as Mutation
                assertEquals(durable, args[0]); assertTrue(mutation.context.sharesOperation(args[2] as VaultOperationContext))
                (args[2] as VaultOperationContext).checkActive()
                if (injectUnknownPublish && mutation.next.phase == VaultMediaPhase.PUBLISH_INTENT) {
                    pendingUnknownCas = true; throw VaultOperationException(VaultFailure.INCOMPLETE)
                }
                val priorRuns = journal?.batch?.runCount ?: 0
                journal = mutation.next; history += mutation.next
                durable = durable.copy(storeRevision = durable.storeRevision + 1, data = durable.data!!.copy(mediaJournalEnvelope = cipher(),
                    mediaJournalRevision = durable.data!!.mediaJournalRevision + 1))
                if (pauseOnRunReservation && mutation.next.batch!!.runCount > priorRuns && mutation.next.batch?.status == VaultCopyBatchStatus.RUNNING &&
                    mutation.next.phase == VaultMediaPhase.PICKING_TARGET && mutation.next.target == null) {
                    live = false; runtime.revoke()
                }
                durable
            }
            "openMediaFile" -> object : VaultOwnedCiphertext {
                override val operationId = journal!!.operation.operationId
                override val identity = journal!!.objectIdentity!!
                override fun append(bytes: ByteArray, offset: Int, length: Int) = error("not used")
                override fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int) = -1
                override fun size() = 8L
                override fun forceAndCloseWrite() = ExportRelease.RELEASED
                override fun closeAndObserve() = ExportRelease.RELEASED
            }
            else -> error("Unexpected fake store call $method")
        } }
        val source: VaultMediaSourcePort = fake { method, _ -> when (method) {
            "observeMediaDrain" -> ExportRelease.RELEASED
            else -> error("Batch must not touch ordinary source: $method")
        } }
        val files: VaultPhoneFilesPort = fake { method, args -> when (method) {
            "observeMediaDrain" -> if (unknownClose) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
            "prepareDirectorySelection", "prepareDirectoryRecoverySelection" -> { pickCount++; object : VaultPhoneFileRequest {} }
            "validateDirectory" -> Unit
            "directoryRecord" -> treeRecord ?: VaultPhoneTreeRecord(args[1] as String, "b".repeat(32), "c".repeat(32), "content://public-fixture/tree", 3).also { treeRecord = it }
            "prepareBatchChild" -> {
                val op = args[1] as VaultMediaOperation
                VaultMediaTargetRecord(op.operationId, (creates + 20).toString(16).padStart(32, '0'), (creates + 30).toString(16).padStart(32, '0'), null, 3)
            }
            "createBatchChild" -> {
                val reservation = args[1] as VaultMediaTargetRecord
                assertEquals(VaultMediaPhase.CHILD_CREATE_INTENT, journal!!.phase)
                assertEquals(reservation, journal!!.target)
                val target = Target(reservation); targets[reservation.operationId] = target; creates++
                if (journal!!.batch!!.activeIndex == lockCreateItem) { live = false; runtime.revoke() }
                if (journal!!.batch!!.activeIndex == failCreateItem) { if (leaveCreateDrainUnknown) unknownClose = true; throw VaultOperationException(VaultFailure.INCOMPLETE) }
                target.record = reservation.copy(uri = "content://public-fixture/child/$creates"); target
            }
            "targetRecord" -> (args[0] as Target).record
            "openWrite" -> {
                val target = args[0] as Target
                assertEquals(target.record, journal!!.target); assertEquals(VaultMediaPhase.COPYING, journal!!.phase)
                object : ExportWrite {
                    override val stream = object : OutputStream() {
                        override fun write(b: Int) { target.bytes.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            now += deadlineAdvancePerWrite
                            if (journal!!.batch!!.activeIndex == cancelWriteItem) runtime.cancel()
                            target.bytes.write(b, off, len)
                        }
                    }
                    override fun close() = if (unknownClose) ExportRelease.UNCONFIRMED else ExportRelease.RELEASED
                }
            }
            "openReadback" -> ExportReadback.Opened(object : ExportInput {
                override val stream = ByteArrayInputStream((args[0] as Target).bytes.toByteArray())
                override fun close() = ExportRelease.RELEASED
            })
            "publish" -> { assertEquals(VaultMediaPhase.PUBLISH_INTENT, journal!!.phase); published++; onPublish?.invoke(); Unit }
            "cleanup" -> if (cleanupRemoved) ExportCleanup.REMOVED else ExportCleanup.UNCONFIRMED
            "recoverTarget" -> targets[(args[0] as VaultMediaTargetRecord).operationId]?.takeUnless { it.retired }
            "resourcesReleased" -> !unknownClose
            "retirePhoneTarget" -> {
                val target = args[0] as Target
                assertTrue(journal!!.phase in setOf(VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED))
                assertEquals(journal!!.target, target.record)
                assertFalse(unknownClose); target.retired = true; true
            }
            "retireSelection" -> true
            else -> error("Unexpected fake phone call $method")
        } }
        var runtime: VaultMediaRuntime = newRuntime()
        private fun newRuntime(): VaultMediaRuntime = VaultMediaRuntime(Any(), auth, store, source, files, VaultMonotonicClock { now },
            { if (live) VaultMediaSession(root, observed) { live } else null },
            { context, _, task -> deadlines += context.deadlineElapsedMillis; tasks.add(task); Unit },
            { before, after -> assertEquals(observed, before); observed = after }, {}, { view = it }, { picker = it }, {})
        fun restartLocked() {
            assertTrue(tasks.isEmpty()); targets.clear(); picker = null; live = false
            runtime = newRuntime(); view = VaultMediaView()
        }
        fun run() { assertTrue("Expected queued task", tasks.isNotEmpty()); tasks.removeFirst().invoke() }
        fun begin() { runtime.beginBatchCopy(refs); run(); val request = picker!!; assertTrue(runtime.admitPicker(request)); runtime.acceptSelection(request, VaultPhoneSelection.Directory(directory)) }
        fun confirm() { val token = view.actionToken; assertNotNull(token); runtime.confirmBatchCopy(token); run() }
        fun recover() { runtime.recover(); run() }
    }
    @Test fun eightItemsOnePickerOneDeadlineAndExactPerRowSuccess() {
        val f = Fixture(8); f.begin(); f.confirm()
        assertEquals(1, f.pickCount); assertEquals(8, f.creates); assertEquals(8, f.published)
        assertEquals(VaultCopyBatchStatus.FINISHED, f.journal!!.batch!!.status)
        assertTrue(f.journal!!.batch!!.items.all { it.outcome == VaultCopyBatchOutcome.VERIFIED_COPY })
        assertEquals(1, f.journal!!.batch!!.runCount); assertEquals(2, f.deadlines.size)
        assertTrue(f.targets.values.all { it.retired && it.bytes.toByteArray().contentEquals(bytes) })
        assertEquals(8, f.entries.size)
    }
    @Test fun cleanMiddleFailureKeepsSuccessAndContinuesWithoutReplay() {
        val f = Fixture(3); f.failReadItem = 1; f.begin(); f.confirm()
        assertEquals(listOf(VaultCopyBatchOutcome.VERIFIED_COPY, VaultCopyBatchOutcome.FAILED_CLEANED, VaultCopyBatchOutcome.VERIFIED_COPY), f.journal!!.batch!!.items.map { it.outcome })
        assertEquals(3, f.creates); assertEquals(2, f.published)
    }
    @Test fun previousSuccessDoesNotSuppressCancellationOfSecondItem() {
        val f = Fixture(3); f.cancelWriteItem = 1; f.begin(); f.confirm()
        assertEquals(2, f.creates); assertEquals(1, f.published)
        assertEquals(VaultCopyBatchOutcome.VERIFIED_COPY, f.journal!!.batch!!.items[0].outcome)
        f.cancelWriteItem = -1; f.recover()
        assertEquals(VaultCopyBatchOutcome.CANCELLED_BEFORE_START, f.journal!!.batch!!.items[2].outcome)
        assertEquals(2, f.creates)
    }
    @Test fun returnedCreateFailureWithConfirmedDrainCanBeKeptWithoutReplay() {
        val f = Fixture(2); f.failCreateItem = 0; f.cleanupRemoved = false; f.begin(); f.confirm()
        assertEquals(VaultCopyBatchOutcome.CLEANUP_REQUIRED, f.journal!!.batch!!.items[0].outcome)
        val token = f.view.actionToken!!; f.runtime.keepCurrentBatchOutputAndContinue(token); f.run()
        assertEquals(VaultCopyBatchOutcome.UNRESOLVED_RETAINED, f.journal!!.batch!!.items[0].outcome)
        assertEquals(1, f.creates)
        f.failCreateItem = -1; f.confirm()
        assertEquals(2, f.creates); assertEquals(1, f.published)
        assertEquals(2, f.journal!!.batch!!.runCount)
    }
    @Test fun missingStaleAndRepeatedActionTokensNeverAuthorizeRun() {
        val f = Fixture(1); f.begin(); val token = f.view.actionToken!!
        f.runtime.confirmBatchCopy(); f.runtime.confirmBatchCopy(Any()); assertTrue(f.tasks.isEmpty())
        f.runtime.confirmBatchCopy(token); f.runtime.confirmBatchCopy(token); assertEquals(1, f.tasks.size)
        f.run(); assertEquals(1, f.creates)
    }
    @Test fun lockAfterCreateCannotWriteOrAutomaticallyResumeAndDoesNotBlockDrainedRecovery() {
        val f = Fixture(2); f.lockCreateItem = 0; f.begin(); f.confirm()
        assertEquals(1, f.creates); assertEquals(0, f.reads); assertEquals(0, f.published)
        assertTrue(f.runtime.cleanupComplete()); assertFalse(f.runtime.blocksCredentialRecovery())
        f.live = true; f.lockCreateItem = -1; f.cleanupRemoved = false; f.recover()
        assertEquals(1, f.creates); assertEquals(VaultCopyBatchOutcome.CLEANUP_REQUIRED, f.journal!!.batch!!.items[0].outcome)
    }
    @Test fun runDeadlineIsNotResetAtItemBoundary() {
        val f = Fixture(3); f.deadlineAdvancePerWrite = VaultCopyBatchLimits.RUN_MILLIS / 2 + 1; f.begin(); f.confirm()
        assertEquals(2, f.creates); assertEquals(1, f.published); assertEquals(1, f.journal!!.batch!!.runCount)
    }
    @Test fun unknownPublishCasBlocksKeepAndCredentialMutationUntilResolved() {
        val f = Fixture(2); f.injectUnknownPublish = true; f.begin(); f.confirm()
        assertEquals(1, f.creates); assertTrue(f.runtime.blocksCredentialRecovery())
        f.runtime.keepCurrentBatchOutputAndContinue(f.view.actionToken); assertTrue(f.tasks.isEmpty())
        assertEquals(1, f.creates)
    }
    @Test fun completedDecisionRecoveryDoesBookkeepingWithoutDecryptOrCreate() {
        val f = Fixture(2); f.onPublish = { f.live = false; f.runtime.revoke() }; f.begin(); f.confirm()
        assertEquals(VaultMediaPhase.PUBLISH_INTENT, f.journal!!.phase)
        val reads = f.reads; f.live = true; f.onPublish = null; f.recover()
        assertEquals(VaultMediaPhase.COMPLETE, f.journal!!.phase); assertEquals(reads, f.reads); assertEquals(1, f.creates)
        assertEquals(VaultCopyBatchOutcome.VERIFIED_COPY, f.journal!!.batch!!.items[0].outcome)
    }
    @Test fun eighthInterruptedRunClosesOnlyUnstartedCurrentAndPreservesFutureRows() {
        val f = Fixture(2); f.begin(); f.pauseOnRunReservation = true
        repeat(8) {
            f.confirm(); assertEquals(it + 1, f.journal!!.batch!!.runCount)
            f.live = true; f.recover(); assertEquals(0, f.creates)
        }
        f.pauseOnRunReservation = false; f.confirm()
        assertEquals(VaultCopyBatchStatus.BUDGET_EXHAUSTED, f.journal!!.batch!!.status)
        assertEquals(VaultMediaPhase.CANCELLED, f.journal!!.phase)
        assertEquals(VaultCopyBatchOutcome.CANCELLED_BEFORE_START, f.journal!!.batch!!.items[0].outcome)
        assertEquals(VaultCopyBatchOutcome.PAUSED_NOT_STARTED, f.journal!!.batch!!.items[1].outcome)
        assertEquals(0, f.creates)
    }
    @Test fun credentialResealPreservesPlanThenRefreshesRevisionBeforeChargingRun() {
        val f = Fixture(2); f.begin(); f.pauseOnRunReservation = true; f.confirm(); f.live = true; f.recover()
        val plan = f.journal!!.batch!!
        f.durable = f.durable.copy(storeRevision = f.durable.storeRevision + 1, data = f.durable.data!!.copy(catalogRevision = 2))
        f.observed = f.durable; f.pauseOnRunReservation = false; f.confirm()
        assertEquals(VaultCopyBatchStatus.FINISHED, f.journal!!.batch!!.status)
        assertEquals(2, f.journal!!.expectedCatalogRevision); assertEquals(plan.planDigest, f.journal!!.batch!!.planDigest)
        assertEquals(1, f.journal!!.batch!!.selectedCatalogRevision); assertEquals(2, f.journal!!.batch!!.runCount)
    }
    @Test fun cancelThenCoverAndLatePickerFailureCannotDowngradeUserStop() {
        val f = Fixture(2); f.runtime.beginBatchCopy(f.refs); f.run()
        val request = f.picker!!; assertTrue(f.runtime.admitPicker(request))
        f.runtime.cancel(); f.run(); f.live = false; f.runtime.revoke()
        f.runtime.acceptSelection(request, null); f.live = true; f.recover()
        assertEquals(VaultCopyBatchStop.USER_CANCEL, f.journal!!.batch!!.stop)
        assertTrue(f.journal!!.batch!!.items.all { it.outcome == VaultCopyBatchOutcome.CANCELLED_BEFORE_START })
        val starts = f.tasks.size; f.runtime.confirmBatchCopy(f.view.actionToken)
        if (f.tasks.size > starts) f.run()
        assertEquals(0, f.creates)
    }
    @Test fun previousRetainedAndVerifiedIdentitiesCannotBeImplicitlyRetried() {
        val f = Fixture(2); f.failCreateItem = 0; f.cleanupRemoved = false; f.begin(); f.confirm()
        f.runtime.keepCurrentBatchOutputAndContinue(f.view.actionToken); f.run(); f.failCreateItem = -1; f.confirm()
        val old = f.journal
        f.runtime.beginBatchCopy(f.refs); f.run()
        assertEquals(old, f.journal); assertEquals(2, f.creates); assertEquals(1, f.pickCount)
    }

    @Test fun createReleaseUncertaintyNeverOffersSkipOrAdmitsNextItem() {
        val f = Fixture(2); f.failCreateItem = 0; f.leaveCreateDrainUnknown = true; f.begin(); f.confirm()
        assertFalse(f.runtime.cleanupComplete()); assertTrue(f.runtime.blocksCredentialRecovery())
        f.runtime.refreshPresentation(); f.runtime.keepCurrentBatchOutputAndContinue(f.view.actionToken)
        assertTrue(f.tasks.isEmpty()); assertEquals(1, f.creates); assertEquals(0, f.published)
        assertEquals(VaultMediaPhase.CHILD_CREATE_INTENT, f.journal!!.phase)
    }

    @Test fun processRestartRequiresFreshAuthExplicitKeepAndSameTreeBeforeUntouchedChild() {
        val f = Fixture(2); f.failCreateItem = 0; f.cleanupRemoved = false; f.begin(); f.confirm()
        val originalTree = f.journal!!.batch!!.tree; val originalPlan = f.journal!!.batch!!.planDigest
        val created = f.creates; val reads = f.reads
        f.restartLocked(); f.runtime.recover(); assertTrue(f.tasks.isEmpty())
        assertEquals(created, f.creates); assertEquals(reads, f.reads)
        f.live = true; f.recover()
        assertEquals(VaultCopyBatchOutcome.CLEANUP_REQUIRED, f.journal!!.batch!!.items[0].outcome)
        assertNotNull(f.view.actionToken)
        f.runtime.keepCurrentBatchOutputAndContinue(f.view.actionToken); f.run()
        assertEquals(VaultCopyBatchOutcome.UNRESOLVED_RETAINED, f.journal!!.batch!!.items[0].outcome)
        f.failCreateItem = -1; f.confirm() // Select exact old tree; no source or create is authorized by this request.
        assertEquals(created, f.creates); assertEquals(reads, f.reads)
        val request = f.picker!!; assertTrue(f.runtime.admitPicker(request))
        f.runtime.acceptSelection(request, VaultPhoneSelection.Directory(f.directory)); f.confirm()
        assertEquals(created + 1, f.creates); assertEquals(1, f.published)
        assertEquals(originalTree, f.journal!!.batch!!.tree); assertEquals(originalPlan, f.journal!!.batch!!.planDigest)
        assertEquals(VaultCopyBatchStatus.FINISHED, f.journal!!.batch!!.status)
    }

}

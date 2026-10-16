package com.easysavevideo.app.vault

import com.easysavevideo.app.vault.crypto.VaultMediaJournalWire
import com.easysavevideo.library.DeleteOutcome
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class VaultMediaNotice {
    NONE, MOVE_CONFIRM, COPYING_CONFIRM, WORKING, PICKING, AUTHENTICATE_TO_CONTINUE,
    MOVED, COPIED, CANCELLED, RECOVERY_REQUIRED, TARGET_PERMISSION_REQUIRED, RELEASE_UNKNOWN, UNAVAILABLE,
    BATCH_CONFIRM, BATCH_RESULT, OUTPUT_RETAINED, BUDGET_EXHAUSTED,
}
internal data class VaultCopyBatchView(val total: Int, val activeIndex: Int, val runCount: Int,
    val outcomes: List<VaultCopyBatchOutcome>, val status: VaultCopyBatchStatus) {
    val verified: Int get() = outcomes.count { it == VaultCopyBatchOutcome.VERIFIED_COPY }
}
internal data class VaultMediaView(val notice: VaultMediaNotice = VaultMediaNotice.NONE,
    val hasMove: Boolean = false, val hasCopy: Boolean = false, val busy: Boolean = false,
    val batch: VaultCopyBatchView? = null, val actionToken: Any? = null, val canKeepOutput: Boolean = false)
internal class VaultMediaSession(val root: VaultRootCapability, var snapshot: VaultStoredSnapshot,
    val isCurrent: () -> Boolean)

/** One helper of VaultRuntime, using its exact gate/lane/root. No independent auth/store/session. */
internal class VaultMediaRuntime(
    private val gate: Any,
    private val authentication: VaultAuthenticationPort,
    private val store: VaultStorePort,
    private val source: VaultMediaSourcePort,
    private val files: VaultPhoneFilesPort,
    private val clock: VaultMonotonicClock,
    private val session: () -> VaultMediaSession?,
    private val execute: (VaultOperationContext, (VaultFailure) -> Unit, () -> Unit) -> Unit,
    private val snapshotChanged: (VaultStoredSnapshot, VaultStoredSnapshot) -> Unit,
    private val catalogCommitted: () -> Unit,
    private val changed: (VaultMediaView) -> Unit,
    private val picker: (VaultPhoneFileRequest) -> Unit,
    private val drained: () -> Unit,
) {
    private val retained = IdentityHashMap<Any, () -> Boolean>()
    private val releaseOrder = mutableListOf<Any>()
    private val readers = IdentityHashMap<VaultMediaReadCapability, Unit>()
    private var inflight = 0
    private var running = false
    private var mutationUncertain = false
    private var activeContext: VaultOperationContext? = null
    private var currentSession: VaultMediaSession? = null
    private var decisionAccepted = false
    private var pendingMove: MediaReadRef? = null
    private data class Copy(val operation: VaultMediaOperation, val ref: VaultMediaReadRef,
        var request: VaultPhoneFileRequest? = null, var target: VaultPhoneFileTarget? = null,
        var cleanupOnly: Boolean = false, val selectionCurrent: (() -> Boolean)? = null,
        var pickerDispatched: Boolean = false, var cancelled: Boolean = false, var finalDecisionAttempted: Boolean = false)
    private var pendingCopy: Copy? = null
    private var notice = VaultMediaNotice.NONE
    private var knownJournal: VaultMediaJournal? = null
    private var directory: VaultPhoneDirectoryTarget? = null
    private var batchStop = VaultCopyBatchStop.NONE
    /** Only this exact, one-use process-local action may confirm the currently shown plan. */
    private class Action(val operationId: String, val batchId: String?)
    private var pendingAction: Action? = null
    private fun offerAction() = synchronized(gate) {
        val journal = knownJournal ?: return@synchronized
        if (mutationUncertain || retained.isNotEmpty() || readers.isNotEmpty() || inflight != 0 || !ownersDrained()) return@synchronized
        pendingAction = Action(journal.operation.operationId, journal.batch?.batchId)
    }
    private fun consumeAction(token: Any?): Boolean = synchronized(gate) {
        val action = pendingAction ?: return false
        if (token !== action || running || active() || knownJournal?.operation?.operationId != action.operationId ||
            knownJournal?.batch?.batchId != action.batchId) return false
        pendingAction = null; true
    }
    private fun terminal(phase: VaultMediaPhase) = phase in setOf(VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED)
    private fun unfinishedBatch() = knownJournal?.batch?.status?.let { it !in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED) } == true


    private fun ownersDrained(): Boolean = authentication.observeMediaDrain() == ExportRelease.RELEASED &&
        store.observeMediaDrain() == ExportRelease.RELEASED && source.observeMediaDrain() == ExportRelease.RELEASED && files.observeMediaDrain() == ExportRelease.RELEASED
    fun active(): Boolean = synchronized(gate) { running || inflight != 0 || retained.isNotEmpty() || ownersDrained().not() }
    fun refreshPresentation() {
        if (synchronized(gate) { !running && knownJournal != null && !mutationUncertain }) offerAction()
        publish(notice)
    }
    fun blocksCredentialRecovery(): Boolean = synchronized(gate) { active() || mutationUncertain }
    fun pending(): Boolean = synchronized(gate) { pendingMove != null || pendingCopy != null || unfinishedBatch() || active() }
    fun cleanupComplete(): Boolean = synchronized(gate) { !running && inflight == 0 && retained.isEmpty() && readers.isEmpty() && ownersDrained() }
    fun queueMove(ref: MediaReadRef): Boolean = synchronized(gate) {
        if (pending()) return false
        pendingMove = ref; publish(VaultMediaNotice.MOVE_CONFIRM); true
    }
    /** Immediate revocation; a retained picker/ordinary source selection never grants read authority. */
    fun revoke() {
        val captured = synchronized(gate) {
            if (knownJournal?.batch != null && unfinishedBatch() && batchStop != VaultCopyBatchStop.USER_CANCEL)
                batchStop = VaultCopyBatchStop.LIFECYCLE_PAUSE
            pendingAction = null
            activeContext?.cancel()
            if (running) notice = VaultMediaNotice.RECOVERY_REQUIRED
            readers.keys.toList()
        }
        captured.forEach { it.revoke() }
    }
    /** UI queue handoff checks the exact request and original presentation before opening a picker. */
    fun admitPicker(request: VaultPhoneFileRequest): Boolean = synchronized(gate) {
        val copy = pendingCopy ?: return false
        if (copy.request !== request || copy.cancelled || copy.pickerDispatched || copy.selectionCurrent?.invoke() != true) return false
        copy.pickerDispatched = true; true
    }
    fun retireUndispatchedPicker(request: VaultPhoneFileRequest): Boolean {
        val copy = synchronized(gate) { pendingCopy?.takeIf { it.request === request } } ?: return false
        val retired = files.retireSelection(request)
        if (retired) synchronized(gate) { if (pendingCopy === copy) pendingCopy = null }
        publish(VaultMediaNotice.RECOVERY_REQUIRED)
        return retired
    }
    fun cancel() {
        synchronized(gate) {
            if (knownJournal?.batch != null && unfinishedBatch()) {
                // Stop intent precedes the decision check, including a PUBLISH_INTENT winner.
                batchStop = VaultCopyBatchStop.USER_CANCEL; pendingAction = null
                if (running) { if (!decisionAccepted) activeContext?.cancel(); return }
            }
        }
        if (synchronized(gate) { knownJournal?.batch != null && unfinishedBatch() }) { recover(); return }
        val copy = synchronized(gate) {
            if (running) { if (!decisionAccepted) activeContext?.cancel(); return }
            pendingMove = null
            if (pendingCopy?.finalDecisionAttempted == true) { publish(VaultMediaNotice.RECOVERY_REQUIRED); return }
            pendingCopy?.also { it.cancelled = true }
        }
        if (copy == null) { publish(VaultMediaNotice.CANCELLED); return }
        val request = copy.request
        if (request != null) {
            if (!copy.pickerDispatched && files.retireSelection(request)) synchronized(gate) { if (pendingCopy === copy) pendingCopy = null }
            publish(VaultMediaNotice.RECOVERY_REQUIRED); return // Launched request remains owned until its exact callback.
        }
        val target = copy.target
        if (target == null) { publish(VaultMediaNotice.RECOVERY_REQUIRED); return }
        cleanupSelected(copy, target)
    }
    private fun cleanupSelected(copy: Copy, target: VaultPhoneFileTarget) = launch(requireSession = false) { _, _ ->
        val result = files.cleanup(target)
        if (result == ExportCleanup.REMOVED) synchronized(gate) {
            // Keep the exact removed-target fact until unlocked journal cancellation is durable.
            // Re-authorizing a document which is already gone cannot substitute for this receipt.
            if (pendingCopy === copy) { copy.cleanupOnly = true; copy.cancelled = true }
        }
        publish(if (result == ExportCleanup.REMOVED) VaultMediaNotice.CANCELLED else VaultMediaNotice.RECOVERY_REQUIRED)
    }
    fun confirmMove() {
        val ref = synchronized(gate) { if (running) return; pendingMove ?: return }
        launch { captured, context ->
            val current = checkNotNull(captured)
            val operation = newOperation(current, VaultMediaKind.MOVE_IN, context)
            val identity = VaultMediaObjectIdentity(id(), 1)
            var journal = VaultMediaJournal(operation, VaultMediaPhase.PREPARED,
                current.snapshot.data!!.catalogRevision, source = ref, objectIdentity = identity)
            persist(current, journal, context)
            val reservation = source.reserve(ref, operation).also { own(it) { it.close(); true } }
            val latch = CountDownLatch(1)
            var retirement: VaultOrdinaryRetirementReceipt? = null
            source.retirePresentation(reservation) { value -> retirement = value; latch.countDown() }
            while (!latch.await(100, TimeUnit.MILLISECONDS)) context.checkActive()
            live(current, context)
            val input = source.acquire(reservation, checkNotNull(retirement))
            own(input.input) { input.input.close() == ExportRelease.RELEASED }
            require(input.metadata.source == ref)
            journal = journal.copy(phase = VaultMediaPhase.SOURCE_RESERVED, plaintextEvidence = input.metadata.evidence)
            persist(current, journal, context)
            val prepared = authentication.prepareMediaImport(current.root, current.snapshot, input.metadata, operation, identity, context)
                .also { own(it) { it.close(); true } }
            val file = store.createMediaFile(operation, identity, context).also { own(it) { it.closeAndObserve() == ExportRelease.RELEASED } }
            journal = journal.copy(phase = VaultMediaPhase.ENCRYPTING)
            persist(current, journal, context)
            val ioGate = publicationGate(current, context)
            authentication.encryptMedia(prepared, input, file, ioGate, context)
            release(input.input)
            live(current, context)
            val proof = authentication.verifyFinalMedia(prepared, file, ioGate, context).also { own(it) { it.close(); true } }
            val evidence = authentication.validateFinalizedMedia(proof, file, context)
            journal = journal.copy(phase = VaultMediaPhase.VERIFIED, ciphertextEvidence = evidence.ciphertext)
            persist(current, journal, context)
            journal = journal.copy(phase = VaultMediaPhase.COMMITTED_SOURCE_PENDING,
                expectedCatalogRevision = current.snapshot.data!!.catalogRevision + 1)
            val mutation = authentication.finishMediaImport(current.root, current.snapshot, prepared, proof, journal, context)
                .also { own(it) { it.close(); true } }
            val decision = decide(current, context)
            val before = current.snapshot
            val committed = store.commitMedia(before, mutation, file, proof, decision)
            synchronized(gate) { decisionAccepted = true }
            update(current, before, committed.snapshot)
            own(committed.receipt) { committed.receipt.close(); true }
            // This immediately invalidates the old root/catalog binding. No later crypto is allowed.
            catalogCommitted()
            val cleaned = source.cleanup(reservation, committed.receipt)
            if (cleaned.outcome == DeleteOutcome.DELETED && cleaned.receipt != null) {
                store.sourceDeleted(committed.receipt, cleaned.receipt)
                synchronized(gate) { pendingMove = null }
                publish(VaultMediaNotice.MOVED)
            } else publish(VaultMediaNotice.RECOVERY_REQUIRED)
        }
    }
    fun beginCopy(ref: VaultMediaReadRef) {
        synchronized(gate) { if (pending()) return }
        launch { captured, context ->
            val current = checkNotNull(captured)
            val catalog = authentication.authenticateCatalog(current.root, current.snapshot, context)
            require(ref.vaultId == current.snapshot.identity.vaultId && ref.catalogRevision == catalog.catalogRevision &&
                catalog.entries.any { it.identity == ref.identity })
            val operation = newOperation(current, VaultMediaKind.COPY_OUT, context)
            val initial = VaultMediaJournal(operation, VaultMediaPhase.PREPARED, ref.catalogRevision, objectIdentity = ref.identity)
            persist(current, initial, context)
            persist(current, initial.copy(phase = VaultMediaPhase.PICKING_TARGET), context)
            live(current, context)
            val request = files.prepareSelection(operation)
            synchronized(gate) { pendingCopy = Copy(operation, ref, request = request, selectionCurrent = current.isCurrent) }
            publish(VaultMediaNotice.PICKING)
            picker(request)
        }
    }
    fun acceptTarget(request: VaultPhoneFileRequest, target: VaultPhoneFileTarget?) {
        val copy = synchronized(gate) {
            val copy = pendingCopy?.takeIf { it.request === request } ?: return
            copy.request = null
            if (target == null) { pendingCopy = null; null } else { copy.target = target; copy }
        }
        if (copy?.cancelled == true && target != null) cleanupSelected(copy, target)
        else { offerAction(); publish(if (copy != null) VaultMediaNotice.AUTHENTICATE_TO_CONTINUE else VaultMediaNotice.RECOVERY_REQUIRED) }
    }
    fun confirmCopy() {
        val copy = synchronized(gate) { if (running) return; pendingCopy?.takeIf { it.target != null } ?: return }
        if (copy.cleanupOnly) { recover(); return }
        launch { captured, context ->
            val current = checkNotNull(captured)
            val target = checkNotNull(copy.target)
            val previous = authentication.authenticateMediaJournal(current.root, current.snapshot, context)
                ?: throw VaultOperationException(VaultFailure.CORRUPT)
            require(previous.operation == copy.operation && previous.phase == VaultMediaPhase.PICKING_TARGET &&
                previous.objectIdentity == copy.ref.identity)
            val record = files.targetRecord(target, copy.operation)
            if (record.uri == null) {
                persist(current, previous.copy(phase = VaultMediaPhase.RECONCILIATION_REQUIRED, target = record), context)
                offerAction(); publish(VaultMediaNotice.RECOVERY_REQUIRED); return@launch
            }
            var journal = previous.copy(phase = VaultMediaPhase.TARGET_BOUND, target = record)
            persist(current, journal, context)
            transferCopy(current, context, copy, journal)
            synchronized(gate) { pendingCopy = null }
            publish(VaultMediaNotice.COPIED)
        }
    }
    /** The sole copy engine. Both entry points supply one durably bound exact scalar target. */
    private fun transferCopy(current: VaultMediaSession, context: VaultOperationContext, copy: Copy,
        initial: VaultMediaJournal): VaultMediaJournal {
        var journal = initial
        val target = checkNotNull(copy.target)
            val file = store.openMediaFile(copy.ref.identity, context).also { own(it) { it.closeAndObserve() == ExportRelease.RELEASED } }
            val ioGate = publicationGate(current, context)
            val read = authentication.openMediaRead(current.root, current.snapshot, copy.ref, file, ioGate, context)
            registerReader(read)
            val catalog = authentication.authenticateCatalog(current.root, current.snapshot, context)
            val entry = catalog.entries.single { it.identity == copy.ref.identity }
            verifyRead(read, entry.plaintext, context, null, ioGate) // Entire object before first external byte.
            journal = journal.copy(phase = VaultMediaPhase.SOURCE_VALIDATED, plaintextEvidence = entry.plaintext)
            persist(current, journal, context)
            journal = journal.copy(phase = VaultMediaPhase.COPYING)
            persist(current, journal, context)
            val writer = ioGate.admit().use { files.openWrite(target) }.also { own(it) { it.close() == ExportRelease.RELEASED } }
            verifyRead(read, entry.plaintext, context, writer, ioGate) // Same anchored file and generation.
            ioGate.admit().use { writer.stream.flush() }
            release(writer); release(read); release(file)
            journal = journal.copy(phase = VaultMediaPhase.VERIFYING)
            persist(current, journal, context)
            when (val reopened = ioGate.admit().use { files.openReadback(target) }) {
                ExportReadback.Unsupported -> throw VaultOperationException(VaultFailure.UNSUPPORTED)
                is ExportReadback.Opened -> {
                    own(reopened.input) { reopened.input.close() == ExportRelease.RELEASED }
                    verifyExternal(reopened.input, entry.plaintext, context, ioGate)
                    release(reopened.input)
                }
            }
            require(files.resourcesReleased(target))
            journal = resultPhase(journal, VaultMediaPhase.PUBLISH_INTENT, VaultCopyBatchOutcome.FINAL_DECISION_PENDING)
            val mutation = authentication.sealMediaJournal(current.root, current.snapshot, journal, context)
                .also { own(it) { it.close(); true } }
            val decision = decide(current, context)
            val before = current.snapshot
            synchronized(gate) { copy.finalDecisionAttempted = true }
            synchronized(gate) { mutationUncertain = true }
            update(current, before, store.compareAndSwapMedia(before, mutation, decision))
            synchronized(gate) { mutationUncertain = false }
            synchronized(gate) { knownJournal = journal }; release(mutation)
            synchronized(gate) { decisionAccepted = true }
            // Durable decision has won; only target publication remains. Never reopen/decrypt source.
            files.publish(target)
            if (current.isCurrent()) {
                journal = resultPhase(journal, VaultMediaPhase.COMPLETE, VaultCopyBatchOutcome.VERIFIED_COPY)
                persist(current, journal, context)
            } else throw VaultOperationException(VaultFailure.CANCELLED)
        return journal
    }
    fun beginBatchCopy(refs: List<VaultMediaReadRef>) {
        if (refs.size !in 1..VaultCopyBatchLimits.MAX_ITEMS) { publish(VaultMediaNotice.UNAVAILABLE); return }
        val selected = refs.toList()
        if (selected.map { it.identity }.distinct().size != selected.size) { publish(VaultMediaNotice.UNAVAILABLE); return }
        synchronized(gate) { if (pending()) return }
        launch { captured, context ->
            val current = checkNotNull(captured)
            val catalog = authentication.authenticateCatalog(current.root, current.snapshot, context)
            require(selected.all { it.vaultId == current.snapshot.identity.vaultId && it.catalogRevision == catalog.catalogRevision &&
                catalog.entries.any { entry -> entry.identity == it.identity } })
            val operation = newOperation(current, VaultMediaKind.COPY_OUT, context)
            val previous = authentication.authenticateMediaJournal(current.root, current.snapshot, context)
            val excluded = previous?.batch?.items?.filter { it.outcome in setOf(VaultCopyBatchOutcome.VERIFIED_COPY,
                VaultCopyBatchOutcome.UNRESOLVED_RETAINED, VaultCopyBatchOutcome.CLEANUP_REQUIRED, VaultCopyBatchOutcome.FINAL_DECISION_PENDING) }
                ?.map { it.identity }.orEmpty()
            require(selected.none { it.identity in excluded })
            val batchId = id()
            val items = selected.mapIndexed { index, ref -> VaultCopyBatchItem(if (index == 0) operation.operationId else id(),
                ref.identity, if (index == 0) operation.sequence else null,
                if (index == 0) VaultCopyBatchOutcome.ACTIVE else VaultCopyBatchOutcome.WAITING) }
            val batch = VaultCopyBatchState(batchId, catalog.catalogRevision, VaultCopyBatchLimits.PROFILE,
                VaultMediaJournalWire.planDigest(batchId, catalog.catalogRevision, VaultCopyBatchLimits.PROFILE, items),
                items, null, 0, 0, VaultCopyBatchStop.NONE, VaultCopyBatchStatus.PREPARING)
            val initial = VaultMediaJournal(operation, VaultMediaPhase.PREPARED, catalog.catalogRevision,
                objectIdentity = selected.first().identity, batch = batch)
            VaultMediaJournalWire.requireCapacity(initial)
            persist(current, initial, context)
            persist(current, initial.copy(phase = VaultMediaPhase.PICKING_TARGET), context)
            live(current, context)
            val request = files.prepareDirectorySelection(batchId)
            synchronized(gate) {
                batchStop = VaultCopyBatchStop.NONE; directory = null
                pendingCopy = Copy(operation, selected.first(), request = request, selectionCurrent = current.isCurrent)
            }
            publish(VaultMediaNotice.PICKING); picker(request)
        }
    }
    fun acceptSelection(request: VaultPhoneFileRequest, selection: VaultPhoneSelection?) {
        val batch = synchronized(gate) { knownJournal?.batch }
        if (batch == null) {
            if (selection is VaultPhoneSelection.Directory) return
            acceptTarget(request, (selection as? VaultPhoneSelection.File)?.target); return
        }
        synchronized(gate) {
            val copy = pendingCopy?.takeIf { it.request === request } ?: return
            // Consume only the exact pending callback; routing types carry no authority.
            copy.request = null
            if (selection is VaultPhoneSelection.Directory) directory = selection.target
            else if (batchStop != VaultCopyBatchStop.USER_CANCEL) batchStop = VaultCopyBatchStop.TARGET_PAUSE
        }
        offerAction()
        publish(if (selection is VaultPhoneSelection.Directory) VaultMediaNotice.BATCH_CONFIRM else VaultMediaNotice.RECOVERY_REQUIRED)
    }
    fun confirmBatchCopy(token: Any? = null) {
        if (!consumeAction(token)) return
        launch { captured, context ->
            val current = checkNotNull(captured)
            var journal = inspectJournal(current, context) ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
            val batch = checkNotNull(journal.batch)
            require(batch.status !in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED))
            // No attempt, including a failed or interrupted create, ever restarts its byte engine.
            require(terminal(journal.phase) || journal.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.PICKING_TARGET) && journal.target == null)
            require(synchronized(gate) { batchStop != VaultCopyBatchStop.USER_CANCEL })
            val catalog = authentication.authenticateCatalog(current.root, current.snapshot, context)
            require(batch.items.all { row -> catalog.entries.any { it.identity == row.identity } })
            if (journal.expectedCatalogRevision != catalog.catalogRevision) {
                journal = journal.copy(expectedCatalogRevision = catalog.catalogRevision)
                persist(current, journal, context) // Exact revision refresh is separate from run reservation.
            }
            val tree = synchronized(gate) { directory } ?: run {
                persist(current, journal, context)
                requestBatchDirectory(current, journal); return@launch
            }
            files.validateDirectory(tree); live(current, context)
            val record = files.directoryRecord(tree, batch.batchId)
            require(batch.tree == null || batch.tree == record)
            if (batch.tree == null) {
                journal = journal.copy(batch = batch.copy(tree = record, status = VaultCopyBatchStatus.AWAITING_CONFIRMATION))
                persist(current, journal, context)
            }
            if (journal.batch!!.runCount >= VaultCopyBatchLimits.MAX_RUN_STARTS) {
                if (!terminal(journal.phase)) {
                    require(journal.target == null && journal.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.PICKING_TARGET))
                    journal = resultPhase(journal, VaultMediaPhase.CANCELLED, VaultCopyBatchOutcome.CANCELLED_BEFORE_START, VaultFailure.DEADLINE)
                    persist(current, journal, context)
                }
                finishRun(current, context, journal, VaultCopyBatchStop.BUDGET_EXHAUSTED); return@launch
            }
            synchronized(gate) { batchStop = VaultCopyBatchStop.NONE }
            val start = journal.batch!!
            journal = journal.copy(batch = start.copy(runCount = start.runCount + 1, stop = VaultCopyBatchStop.NONE,
                status = VaultCopyBatchStatus.RUNNING, items = start.items.map { row ->
                    if (row.outcome == VaultCopyBatchOutcome.PAUSED_NOT_STARTED) row.copy(outcome = VaultCopyBatchOutcome.WAITING) else row }))
            // Durable charge before source IO or provider create. Context is never refreshed between rows.
            persist(current, journal, context)
            runBatch(current, context, journal, tree)
        }
    }
    fun continueBatchCopy(token: Any? = null) = confirmBatchCopy(token)
    private fun requestBatchDirectory(current: VaultMediaSession, journal: VaultMediaJournal) {
        val batch = checkNotNull(journal.batch)
        val request = batch.tree?.let(files::prepareDirectoryRecoverySelection) ?: files.prepareDirectorySelection(batch.batchId)
        synchronized(gate) {
            val identity = checkNotNull(journal.objectIdentity)
            pendingCopy = Copy(journal.operation, VaultMediaReadRef(current.snapshot.identity.vaultId, identity.objectId,
                identity.generation, current.snapshot.data!!.catalogRevision), request = request, selectionCurrent = current.isCurrent)
        }
        publish(VaultMediaNotice.TARGET_PERMISSION_REQUIRED); picker(request)
    }
    private fun runBatch(current: VaultMediaSession, context: VaultOperationContext, initial: VaultMediaJournal,
        tree: VaultPhoneDirectoryTarget) {
        var journal = initial
        while (true) {
            live(current, context)
            val stop = synchronized(gate) { batchStop }
            if (stop != VaultCopyBatchStop.NONE) { finishRun(current, context, journal, stop); return }
            if (terminal(journal.phase)) {
                val next = handover(current, context, journal) ?: return
                journal = next
            }
            val identity = checkNotNull(journal.objectIdentity)
            val copy = Copy(journal.operation, VaultMediaReadRef(current.snapshot.identity.vaultId, identity.objectId,
                identity.generation, current.snapshot.data!!.catalogRevision))
            synchronized(gate) { pendingCopy = copy }
            try {
                val ioGate = publicationGate(current, context)
                files.validateDirectory(tree); live(current, context)
                val reservation = files.prepareBatchChild(tree, journal.operation)
                require(reservation.uri == null && reservation.operationId == journal.operation.operationId)
                journal = journal.copy(phase = VaultMediaPhase.CHILD_CREATE_INTENT, target = reservation)
                persist(current, journal, context)
                val target = ioGate.admit().use { files.createBatchChild(tree, reservation, journal.operation, ioGate) }
                synchronized(gate) { copy.target = target }
                live(current, context)
                val bound = files.targetRecord(target, journal.operation)
                require(bound.uri != null && bound.copy(uri = null) == reservation)
                journal = journal.copy(phase = VaultMediaPhase.TARGET_BOUND, target = bound)
                persist(current, journal, context)
                journal = transferCopy(current, context, copy, journal)
                drainOwned()
                requireDrained()
                publish(VaultMediaNotice.BATCH_RESULT)
            } catch (error: Exception) {
                drainOwned()
                requireDrained() // Unknown actual return/close always blocks further scheduling.
                live(current, context) // Locked/cancelled runs reconcile only after fresh authentication.
                journal = inspectJournal(current, context) ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
                if (journal.phase == VaultMediaPhase.PUBLISH_INTENT || terminal(journal.phase)) throw error
                val failure = (error as? VaultOperationException)?.category ?: VaultFailure.CORRUPT
                val target = copy.target ?: journal.target?.let(files::recoverTarget)
                synchronized(gate) { copy.target = target }
                val noCreate = journal.target == null && journal.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.PICKING_TARGET)
                val cleaned = target != null && files.cleanup(target) == ExportCleanup.REMOVED && files.resourcesReleased(target)
                requireDrained()
                if (!noCreate && !cleaned) {
                    journal = resultPhase(journal, VaultMediaPhase.RECONCILIATION_REQUIRED, VaultCopyBatchOutcome.CLEANUP_REQUIRED, failure)
                    persist(current, journal, context)
                    finishRun(current, context, journal, VaultCopyBatchStop.TARGET_PAUSE)
                    offerAction(); publish(VaultMediaNotice.RECOVERY_REQUIRED); return
                }
                journal = resultPhase(journal, VaultMediaPhase.CANCELLED,
                    if (cleaned) VaultCopyBatchOutcome.FAILED_CLEANED else VaultCopyBatchOutcome.FAILED_NO_OUTPUT, failure)
                persist(current, journal, context)
                if (failure in setOf(VaultFailure.REJECTED, VaultFailure.UNSUPPORTED, VaultFailure.UNAVAILABLE)) {
                    finishRun(current, context, journal, VaultCopyBatchStop.TARGET_PAUSE); return
                }
            }
        }
    }
    private fun resultPhase(journal: VaultMediaJournal, phase: VaultMediaPhase, outcome: VaultCopyBatchOutcome,
        failure: VaultFailure? = null): VaultMediaJournal {
        val batch = journal.batch ?: return journal.copy(phase = phase, cancelRequested = phase == VaultMediaPhase.CANCELLED || journal.cancelRequested)
        val rows = batch.items.toMutableList()
        rows[batch.activeIndex] = rows[batch.activeIndex].copy(outcome = outcome, failure = failure,
            plaintextEvidence = journal.plaintextEvidence, target = journal.target)
        return journal.copy(phase = phase, cancelRequested = phase == VaultMediaPhase.CANCELLED || journal.cancelRequested,
            batch = batch.copy(items = rows,
                status = if (phase == VaultMediaPhase.CANCELLED && batch.status in setOf(VaultCopyBatchStatus.PREPARING,
                    VaultCopyBatchStatus.AWAITING_CONFIRMATION)) VaultCopyBatchStatus.PAUSED else batch.status,
                stop = if (phase == VaultMediaPhase.CANCELLED && batch.status in setOf(VaultCopyBatchStatus.PREPARING,
                    VaultCopyBatchStatus.AWAITING_CONFIRMATION)) synchronized(gate) { batchStop }.let {
                    if (it == VaultCopyBatchStop.NONE) VaultCopyBatchStop.LIFECYCLE_PAUSE else it } else batch.stop))
    }
    private fun finishRun(current: VaultMediaSession, context: VaultOperationContext, journal: VaultMediaJournal,
        stop: VaultCopyBatchStop): VaultMediaJournal {
        val batch = checkNotNull(journal.batch)
        val rows = batch.items.map { row ->
            if (row.activatedSequence == null && row.outcome in setOf(VaultCopyBatchOutcome.WAITING, VaultCopyBatchOutcome.PAUSED_NOT_STARTED))
                row.copy(outcome = if (stop == VaultCopyBatchStop.USER_CANCEL) VaultCopyBatchOutcome.CANCELLED_BEFORE_START else VaultCopyBatchOutcome.PAUSED_NOT_STARTED)
            else row
        }
        val status = when {
            stop == VaultCopyBatchStop.BUDGET_EXHAUSTED && terminal(journal.phase) -> VaultCopyBatchStatus.BUDGET_EXHAUSTED
            terminal(journal.phase) && rows.none { it.outcome in setOf(VaultCopyBatchOutcome.WAITING, VaultCopyBatchOutcome.PAUSED_NOT_STARTED) } -> VaultCopyBatchStatus.FINISHED
            else -> VaultCopyBatchStatus.PAUSED
        }
        val next = journal.copy(batch = batch.copy(items = rows, stop = stop, status = status))
        persist(current, next, context)
        if (terminal(next.phase)) retireTarget(next)
        offerAction(); publish(if (status == VaultCopyBatchStatus.BUDGET_EXHAUSTED) VaultMediaNotice.BUDGET_EXHAUSTED else VaultMediaNotice.BATCH_RESULT)
        return next
    }
    private fun handover(current: VaultMediaSession, context: VaultOperationContext, journal: VaultMediaJournal): VaultMediaJournal? {
        require(terminal(journal.phase)); requireDrained()
        val batch = checkNotNull(journal.batch)
        retireTarget(journal)
        val nextIndex = batch.items.indexOfFirst { it.outcome == VaultCopyBatchOutcome.WAITING }
        if (nextIndex < 0) {
            persist(current, journal.copy(batch = batch.copy(status = VaultCopyBatchStatus.FINISHED)), context)
            synchronized(gate) { pendingCopy = null; directory = null; pendingAction = null }
            publish(VaultMediaNotice.BATCH_RESULT); return null
        }
        require(nextIndex > batch.activeIndex && journal.operation.sequence < Long.MAX_VALUE - 1)
        val rows = batch.items.toMutableList(); val row = rows[nextIndex]
        require(row.activatedSequence == null && row.target == null)
        val operation = VaultMediaOperation(row.operationId, journal.operation.sequence + 1, VaultMediaKind.COPY_OUT)
        rows[nextIndex] = row.copy(activatedSequence = operation.sequence, outcome = VaultCopyBatchOutcome.ACTIVE)
        val next = VaultMediaJournal(operation, VaultMediaPhase.PREPARED, current.snapshot.data!!.catalogRevision,
            objectIdentity = row.identity, batch = batch.copy(items = rows, activeIndex = nextIndex))
        synchronized(gate) {
            live(current, context)
            if (batchStop != VaultCopyBatchStop.NONE) throw VaultOperationException(VaultFailure.CANCELLED)
            // Same gate, same context and deadline, new exact item decision. No stale winner can mask cancellation.
            decisionAccepted = false; pendingCopy = null
        }
        persist(current, next, context)
        return next
    }
    private fun retireTarget(journal: VaultMediaJournal) {
        require(terminal(journal.phase)); requireDrained()
        val target = synchronized(gate) { pendingCopy?.takeIf { it.operation == journal.operation }?.target }
            ?: journal.target?.let(files::recoverTarget)
        if (target != null) require(files.retirePhoneTarget(target, journal.operation.operationId, journal.batch?.batchId))
        synchronized(gate) { pendingCopy?.takeIf { it.operation == journal.operation }?.target = null }
    }
    private fun inspectJournal(current: VaultMediaSession, context: VaultOperationContext): VaultMediaJournal? {
        val observed = store.inspect(context) as? VaultStoreObservation.Present ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        if (observed.snapshot != current.snapshot) {
            require(observed.snapshot.data?.catalogRevision == current.snapshot.data?.catalogRevision)
            update(current, current.snapshot, observed.snapshot)
        }
        return authentication.authenticateMediaJournal(current.root, current.snapshot, context).also { synchronized(gate) { knownJournal = it; mutationUncertain = false } }
    }
    private fun recoverBatch(current: VaultMediaSession, context: VaultOperationContext, prior: VaultMediaJournal) {
        val catalog = authentication.authenticateCatalog(current.root, current.snapshot, context)
        val original = checkNotNull(prior.batch)
        require(original.items.all { row -> catalog.entries.any { it.identity == row.identity } })
        if (original.status in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED) && terminal(prior.phase)) {
            retireTarget(prior)
            synchronized(gate) { pendingCopy = null }; publish(VaultMediaNotice.BATCH_RESULT); return
        }
        var journal = prior.copy(expectedCatalogRevision = catalog.catalogRevision)
        // Hydrate only through the fixed store's actual accepted authenticated whole-CAS fact.
        persist(current, journal, context)
        if (journal.batch!!.status in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED) && terminal(journal.phase)) {
            retireTarget(journal)
            synchronized(gate) { pendingCopy = null }; publish(VaultMediaNotice.BATCH_RESULT); return
        }
        val stop = synchronized(gate) { if (batchStop == VaultCopyBatchStop.NONE) VaultCopyBatchStop.LIFECYCLE_PAUSE else batchStop }
        if (journal.phase == VaultMediaPhase.PUBLISH_INTENT) {
            // Durable verified decision needs bookkeeping only. Never source replay or URI reopen after restart.
            requireDrained()
            journal = resultPhase(journal, VaultMediaPhase.COMPLETE, VaultCopyBatchOutcome.VERIFIED_COPY)
            persist(current, journal, context)
        } else if (!terminal(journal.phase)) {
            val selected = synchronized(gate) { pendingCopy?.takeIf { it.operation == journal.operation } }
            val request = selected?.request
            if (request != null && (selected.pickerDispatched || !files.retireSelection(request))) {
                publish(VaultMediaNotice.RECOVERY_REQUIRED); return
            }
            val target = selected?.target ?: journal.target?.let(files::recoverTarget)
            val notStarted = journal.target == null && journal.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.PICKING_TARGET)
            if (notStarted && stop != VaultCopyBatchStop.USER_CANCEL) {
                journal = finishRun(current, context, journal, stop)
                offerAction(); publish(VaultMediaNotice.BATCH_CONFIRM); return
            }
            val absent = target != null && files.cleanup(target) == ExportCleanup.REMOVED && files.resourcesReleased(target)
            requireDrained()
            if (notStarted || absent) {
                journal = resultPhase(journal, VaultMediaPhase.CANCELLED,
                    if (notStarted) VaultCopyBatchOutcome.CANCELLED_BEFORE_START else VaultCopyBatchOutcome.CANCELLED_CLEANED,
                    VaultFailure.CANCELLED)
                persist(current, journal, context)
            } else {
                if (journal.phase != VaultMediaPhase.RECONCILIATION_REQUIRED)
                    journal = resultPhase(journal, VaultMediaPhase.RECONCILIATION_REQUIRED, VaultCopyBatchOutcome.CLEANUP_REQUIRED, VaultFailure.INCOMPLETE)
                // Same-phase recovery preserves the original durable row reason and evidence exactly.
                persist(current, journal, context)
                val identity = checkNotNull(journal.objectIdentity)
                synchronized(gate) { pendingCopy = Copy(journal.operation, VaultMediaReadRef(current.snapshot.identity.vaultId,
                    identity.objectId, identity.generation, catalog.catalogRevision), target = target, cleanupOnly = true) }
            }
        }
        finishRun(current, context, journal, stop)
        offerAction(); publish(VaultMediaNotice.BATCH_RESULT)
    }
    fun keepCurrentBatchOutputAndContinue(token: Any? = null) = keepOutput(token, true)
    fun keepCurrentSingleOutput(token: Any? = null) = keepOutput(token, false)
    private fun keepOutput(token: Any?, batchMode: Boolean) {
        if (!consumeAction(token)) return
        launch { captured, context ->
            val current = checkNotNull(captured)
            var journal = inspectJournal(current, context) ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
            require((journal.batch != null) == batchMode && journal.operation.kind == VaultMediaKind.COPY_OUT &&
                !terminal(journal.phase) && journal.phase != VaultMediaPhase.PUBLISH_INTENT && journal.target != null)
            requireDrained()
            val requestOwner = synchronized(gate) { pendingCopy?.takeIf { it.operation == journal.operation } }
            requestOwner?.request?.let { request ->
                require(!requestOwner.pickerDispatched && files.retireSelection(request))
                synchronized(gate) { requestOwner.request = null }
            }
            // Even a previously unknown CAS must first resolve to this exact pre-final snapshot.
            val catalog = authentication.authenticateCatalog(current.root, current.snapshot, context)
            journal = journal.copy(expectedCatalogRevision = catalog.catalogRevision)
            persist(current, journal, context)
            val target = synchronized(gate) { pendingCopy?.takeIf { it.operation == journal.operation }?.target }
                ?: files.recoverTarget(checkNotNull(journal.target))
            if (target != null) require(files.resourcesReleased(target))
            val next = if (batchMode) resultPhase(journal, VaultMediaPhase.CANCELLED, VaultCopyBatchOutcome.UNRESOLVED_RETAINED,
                VaultFailure.INCOMPLETE) else journal.copy(phase = VaultMediaPhase.OUTPUT_RETAINED, cancelRequested = true)
            persist(current, next, context)
            if (target != null) require(files.retirePhoneTarget(target, journal.operation.operationId, journal.batch?.batchId))
            requireDrained()
            synchronized(gate) { pendingCopy = null }
            if (batchMode) {
                finishRun(current, context, next, synchronized(gate) { batchStop }.let {
                    if (it == VaultCopyBatchStop.NONE) VaultCopyBatchStop.TARGET_PAUSE else it })
                // Continuation is separately explicit and consumes another bounded run reservation.
                offerAction(); publish(VaultMediaNotice.BATCH_RESULT)
            } else { synchronized(gate) { pendingAction = null }; publish(VaultMediaNotice.OUTPUT_RETAINED) }
        }
    }
    private fun drainOwned() {
        val owners = synchronized(gate) { releaseOrder.toList().asReversed() }
        owners.forEach { try { release(it) } catch (_: Exception) { } }
    }
    private fun requireDrained() = synchronized(gate) {
        if (retained.isNotEmpty() || readers.isNotEmpty() || inflight != 0 || !ownersDrained())
            throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
    }
    /** Explicitly unlocked reconciliation. PREPARED never restarts encryption/copy. */
    fun recover() = launch { captured, context ->
        val current = checkNotNull(captured)
        // Inspect reconciles the original whole-CAS outcome before any restart decision. Never
        // assume an exception meant the old snapshot won, and never rebase its old mutation.
        val observed = store.inspect(context) as? VaultStoreObservation.Present
            ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        if (observed.snapshot != current.snapshot) {
            val catalogChanged = observed.snapshot.data?.catalogRevision != current.snapshot.data?.catalogRevision
            update(current, current.snapshot, observed.snapshot)
            if (catalogChanged) { catalogCommitted(); publish(VaultMediaNotice.AUTHENTICATE_TO_CONTINUE); return@launch }
        }
        val priorJournal = authentication.authenticateMediaJournal(current.root, current.snapshot, context)
        synchronized(gate) { knownJournal = priorJournal; mutationUncertain = false }
        if (priorJournal?.batch != null) { recoverBatch(current, context, priorJournal); return@launch }
        if (priorJournal == null || terminal(priorJournal.phase)) {
            if (priorJournal?.operation?.kind == VaultMediaKind.COPY_OUT) retireTarget(priorJournal)
            synchronized(gate) { pendingCopy = null }
            publish(VaultMediaNotice.NONE); return@launch
        }
        val recoveredCatalog = authentication.authenticateCatalog(current.root, current.snapshot, context)
        require(recoveredCatalog.catalogRevision == current.snapshot.data!!.catalogRevision)
        // Credential changes preserve the journal bytes while advancing catalog binding. This is
        // a fresh authenticated recovery phase proposal, never a rebase/reuse of an old mutation.
        val journal = priorJournal.copy(expectedCatalogRevision = recoveredCatalog.catalogRevision)
        if (journal != priorJournal && journal.operation.kind == VaultMediaKind.COPY_OUT) persist(current, journal, context)
        if (journal.operation.kind == VaultMediaKind.COPY_OUT) {
            if (journal.phase == VaultMediaPhase.PUBLISH_INTENT) {
                requireDrained()
                val complete = journal.copy(phase = VaultMediaPhase.COMPLETE)
                persist(current, complete, context); retireTarget(complete)
                synchronized(gate) { pendingCopy = null }
                publish(VaultMediaNotice.COPIED); return@launch
            }
            val record = journal.target
            if (record == null) {
                val selected = synchronized(gate) { pendingCopy?.takeIf { it.operation == journal.operation } }
                if (selected?.request != null) {
                    if (selected.pickerDispatched || !files.retireSelection(checkNotNull(selected.request))) {
                        publish(VaultMediaNotice.RECOVERY_REQUIRED); return@launch
                    }
                }
                val selectedTarget = selected?.target
                if (selectedTarget != null) {
                    // A picker may have created an ordinary document before any source confirmation.
                    // Bind only the fixed adapter's exact candidate as reconciliation evidence, never open it.
                    val selectedRecord = files.targetRecord(selectedTarget, journal.operation)
                    val bound = journal.copy(phase = VaultMediaPhase.RECONCILIATION_REQUIRED, target = selectedRecord)
                    persist(current, bound, context)
                    if (files.cleanup(selectedTarget) != ExportCleanup.REMOVED) {
                        selected.cleanupOnly = true
                        offerAction(); publish(VaultMediaNotice.RECOVERY_REQUIRED); return@launch
                    }
                    requireDrained()
                    val cancelled = bound.copy(phase = VaultMediaPhase.CANCELLED, cancelRequested = true)
                    persist(current, cancelled, context); retireTarget(cancelled)
                } else persist(current, journal.copy(phase = VaultMediaPhase.CANCELLED, cancelRequested = true), context)
                synchronized(gate) { pendingCopy = null }
                publish(VaultMediaNotice.CANCELLED); return@launch
            }
            // Restart registries are empty. Reauthenticate and CAS the identical journal phase to
            // bind this exact target for reconciliation; no source read or new decision occurs.
            persist(current, journal, context)
            val pending = synchronized(gate) { pendingCopy?.takeIf { it.operation == journal.operation } }
            val target = pending?.target ?: files.recoverTarget(record)
            if (target == null) {
                val identity = checkNotNull(journal.objectIdentity)
                if (journal.phase != VaultMediaPhase.PUBLISH_INTENT)
                    persist(current, journal.copy(phase = VaultMediaPhase.RECONCILIATION_REQUIRED), context)
                val request = files.prepareCleanupSelection(record)
                synchronized(gate) { pendingCopy = Copy(journal.operation,
                    VaultMediaReadRef(current.snapshot.identity.vaultId, identity.objectId, identity.generation,
                        current.snapshot.data!!.catalogRevision), request = request, cleanupOnly = true, selectionCurrent = current.isCurrent) }
                publish(VaultMediaNotice.TARGET_PERMISSION_REQUIRED); picker(request); return@launch
            }
            require(files.targetRecord(target, journal.operation) == record && files.resourcesReleased(target))
            if (journal.phase == VaultMediaPhase.PUBLISH_INTENT) {
                files.publish(target)
                persist(current, journal.copy(phase = VaultMediaPhase.COMPLETE), context)
                publish(VaultMediaNotice.COPIED)
            } else if (files.cleanup(target) == ExportCleanup.REMOVED) {
                persist(current, journal.copy(phase = VaultMediaPhase.CANCELLED, cancelRequested = true), context)
                publish(VaultMediaNotice.CANCELLED)
            } else {
                val identity = checkNotNull(journal.objectIdentity)
                synchronized(gate) { pendingCopy = pending ?: Copy(journal.operation,
                    VaultMediaReadRef(current.snapshot.identity.vaultId, identity.objectId, identity.generation,
                        current.snapshot.data!!.catalogRevision), target = target, cleanupOnly = true) }
                offerAction(); publish(VaultMediaNotice.RECOVERY_REQUIRED); return@launch
            }
            synchronized(gate) { pendingCopy = null }
            return@launch
        }
        val ref = checkNotNull(journal.source)
        val identity = checkNotNull(journal.objectIdentity)
        val reservation = source.reserveForReconciliation(ref, journal.operation).also { own(it) { it.close(); true } }
        val catalog = recoveredCatalog
        val entry = catalog.entries.singleOrNull { it.identity == identity }
        if (entry == null) {
            val file = try { store.openMediaFile(identity, context).also { own(it) { it.closeAndObserve() == ExportRelease.RELEASED } } }
                catch (error: VaultOperationException) { if (error.category == VaultFailure.UNAVAILABLE) null else throw error }
            file?.let(::release)
            val mutation = authentication.sealMediaJournal(current.root, current.snapshot,
                journal.copy(phase = VaultMediaPhase.CLEANUP_PENDING, cancelRequested = true), context).also { own(it) { it.close(); true } }
            val receipt = store.abandonUncommittedMedia(current.snapshot, mutation, file, context).also { own(it) { it.close(); true } }
            require(source.cancelUncommitted(reservation, receipt))
            // Store may have committed the exact cancellation snapshot; inspect it before further work.
            val observed = store.inspect(context) as? VaultStoreObservation.Present ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
            update(current, current.snapshot, observed.snapshot)
            require(source.validateSourceRestored(ref, journal.operation))
            persist(current, journal.copy(phase = VaultMediaPhase.CANCELLED, cancelRequested = true), context)
            synchronized(gate) { pendingMove = null }
            publish(VaultMediaNotice.CANCELLED)
        } else {
            val retired = CountDownLatch(1)
            source.retirePresentation(reservation) { retired.countDown() }
            while (!retired.await(100, TimeUnit.MILLISECONDS)) context.checkActive()
            live(current, context)
            require(entry.moveOperation == journal.operation && entry.source == ref)
            val file = store.openMediaFile(identity, context).also { own(it) { it.closeAndObserve() == ExportRelease.RELEASED } }
            val readRef = VaultMediaReadRef(current.snapshot.identity.vaultId, identity.objectId, identity.generation, catalog.catalogRevision)
            val proof = authentication.verifyCommittedMedia(current.root, current.snapshot, readRef, file,
                publicationGate(current, context), context).also { own(it) { it.close(); true } }
            val receipt = store.recoverCommittedMove(current.snapshot, journal.operation, ref, file, proof, context)
                .also { own(it) { it.close(); true } }
            val result = source.cleanup(reservation, receipt)
            if (result.outcome != DeleteOutcome.DELETED || result.receipt == null) {
                publish(VaultMediaNotice.RECOVERY_REQUIRED); return@launch
            }
            store.sourceDeleted(receipt, result.receipt)
            persist(current, journal.copy(phase = VaultMediaPhase.COMPLETE), context)
            synchronized(gate) { pendingMove = null }
            publish(VaultMediaNotice.MOVED)
        }
    }
    private fun newOperation(current: VaultMediaSession, kind: VaultMediaKind, context: VaultOperationContext): VaultMediaOperation {
        val previous = authentication.authenticateMediaJournal(current.root, current.snapshot, context)
        if (previous != null && (!terminal(previous.phase) || previous.batch?.status?.let {
                it !in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED) } == true))
            throw VaultOperationException(VaultFailure.INCOMPLETE)
        if (previous?.operation?.kind == VaultMediaKind.COPY_OUT && previous.target != null) {
            // Retire against the old exact terminal store fact before replacing the scalar journal.
            // A live target must pass its same fixed store's exact terminal validator. After
            // process loss no target is reconstructed. Credential reseal need not rewrite a closed receipt.
            retireTarget(previous)
        }
        val sequence = previous?.operation?.sequence ?: 0
        require(sequence < Long.MAX_VALUE)
        return VaultMediaOperation(id(), sequence + 1, kind)
    }
    private fun persist(current: VaultMediaSession, journal: VaultMediaJournal, context: VaultOperationContext) {
        live(current, context)
        val before = current.snapshot
        val mutation = authentication.sealMediaJournal(current.root, before, journal, context)
        own(mutation) { mutation.close(); true }
        synchronized(gate) { mutationUncertain = true }
        val after = store.compareAndSwapMedia(before, mutation, context)
        synchronized(gate) { mutationUncertain = false }
        update(current, before, after)
        synchronized(gate) { knownJournal = journal }
        release(mutation)
        live(current, context)
    }
    private fun update(current: VaultMediaSession, before: VaultStoredSnapshot, after: VaultStoredSnapshot) {
        snapshotChanged(before, after); current.snapshot = after
    }
    private fun decide(current: VaultMediaSession, context: VaultOperationContext) = synchronized(gate) {
        // Admission does not claim durable success. Keep the exact owner-verified lineage; lock or
        // cancellation before/within CAS may reject it. An uncertain result is reconciled, never retried.
        live(current, context); context
    }
    private fun live(current: VaultMediaSession, context: VaultOperationContext) = synchronized(gate) {
        context.checkActive()
        if (!current.isCurrent() || currentSession !== current) throw VaultOperationException(VaultFailure.CANCELLED)
    }
    private fun publicationGate(current: VaultMediaSession, context: VaultOperationContext): VaultMediaPublicationGate = object : VaultMediaPublicationGate {
        override fun requireCurrent() = live(current, context)
        override fun admit(): VaultMediaIoPermit = synchronized(gate) {
            live(current, context); inflight++
            object : VaultMediaIoPermit {
                private var closed = false
                override fun publish(scratch: ByteArray, sourceOffset: Int, destination: ByteArray, destinationOffset: Int, length: Int) = synchronized(gate) {
                    if (closed) throw VaultOperationException(VaultFailure.CANCELLED)
                    live(current, context)
                    require(length in 0..VaultMediaLimits.BUFFER_BYTES && sourceOffset >= 0 && destinationOffset >= 0 &&
                        sourceOffset <= scratch.size - length && destinationOffset <= destination.size - length)
                    scratch.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
                    Unit
                }
                override fun close() {
                    synchronized(gate) { if (closed) return; closed = true; check(inflight > 0); inflight-- }
                    drained()
                }
            }
        }
    }
    private fun verifyRead(read: VaultMediaReadCapability, evidence: ExportEvidence, context: VaultOperationContext,
        output: ExportWrite?, ioGate: VaultMediaPublicationGate) {
        require(read.authenticatedSize == evidence.bytes)
        val buffer = ByteArray(VaultMediaLimits.BUFFER_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var position = 0L; var zero = 0
        try {
            while (true) {
                context.checkActive()
                val count = read.readAt(position, buffer, 0, minOf(buffer.size.toLong(), evidence.bytes - position + 1).toInt(), context)
                if (count == -1) break
                require(count in 0..buffer.size && count.toLong() <= evidence.bytes - position)
                if (count == 0) { if (++zero >= VaultMediaLimits.MAX_ZERO_PROGRESS) throw VaultOperationException(VaultFailure.CORRUPT); continue }
                zero = 0; digest.update(buffer, 0, count)
                if (output != null) ioGate.admit().use { output.stream.write(buffer, 0, count) }
                buffer.fill(0); position += count
            }
            require(position == evidence.bytes && hex(digest.digest()) == evidence.sha256)
            ioGate.requireCurrent()
        } finally { buffer.fill(0) }
    }
    private fun verifyExternal(input: ExportInput, expected: ExportEvidence, context: VaultOperationContext, ioGate: VaultMediaPublicationGate) {
        val buffer = ByteArray(VaultMediaLimits.BUFFER_BYTES); val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L; var zero = 0
        try {
            while (true) {
                context.checkActive()
                val n = ioGate.admit().use { input.stream.read(buffer, 0, minOf(buffer.size.toLong(), expected.bytes - count + 1).toInt()) }
                if (n == -1) break
                require(n in 0..buffer.size && n.toLong() <= expected.bytes - count)
                if (n == 0) { if (++zero >= VaultMediaLimits.MAX_ZERO_PROGRESS) throw VaultOperationException(VaultFailure.CORRUPT); continue }
                zero = 0; digest.update(buffer, 0, n); count += n; buffer.fill(0)
            }
            require(count == expected.bytes && hex(digest.digest()) == expected.sha256)
        } finally { buffer.fill(0) }
    }
    private fun registerReader(reader: VaultMediaReadCapability) {
        synchronized(gate) { readers[reader] = Unit }
        own(reader) {
            reader.revoke()
            if (reader.closeAndObserve() == ExportRelease.RELEASED) { synchronized(gate) { readers.remove(reader) }; true } else false
        }
    }
    private fun own(value: Any, close: () -> Boolean) = synchronized(gate) { check(retained.put(value, close) == null); releaseOrder.add(value); Unit }
    private fun release(value: Any) {
        val close = synchronized(gate) { retained[value] } ?: return
        if (!try { close() } catch (_: Exception) { false }) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
        synchronized(gate) { retained.remove(value); releaseOrder.removeAll { it === value } }
    }
    private fun launch(requireSession: Boolean = true, task: (VaultMediaSession?, VaultOperationContext) -> Unit) {
        val captured = if (requireSession) session() else null
        if (requireSession && captured == null) { publish(VaultMediaNotice.AUTHENTICATE_TO_CONTINUE); return }
        val now = clock.elapsedMillis()
        if (now < 0 || now > Long.MAX_VALUE - VaultMediaLimits.OPERATION_MILLIS) { publish(VaultMediaNotice.UNAVAILABLE); return }
        val context = VaultOperationContext(now + VaultMediaLimits.OPERATION_MILLIS, clock)
        synchronized(gate) {
            if (active()) return
            if (captured != null && !captured.isCurrent()) { publish(VaultMediaNotice.AUTHENTICATE_TO_CONTINUE); return }
            running = true; activeContext = context; currentSession = captured; decisionAccepted = false
        }
        publish(VaultMediaNotice.WORKING)
        execute(context, { failed(it) }) {
            try { if (captured != null) live(captured, context); task(captured, context) }
            catch (_: Exception) { publish(VaultMediaNotice.RECOVERY_REQUIRED) }
            finally {
                // Real close only, never a timeout decrement. Failed owners remain strongly held.
                val owners = synchronized(gate) { releaseOrder.toList().asReversed() }
                owners.forEach { try { release(it) } catch (_: Exception) { } }
                val unknown = synchronized(gate) {
                    running = false; activeContext = null; currentSession = null; retained.isNotEmpty() || inflight != 0 || ownersDrained().not()
                }
                if (unknown) publish(VaultMediaNotice.RELEASE_UNKNOWN) else publish(notice)
                drained()
            }
        }
    }
    private fun failed(failure: VaultFailure) {
        synchronized(gate) { running = false; activeContext = null; currentSession = null }
        publish(if (failure == VaultFailure.RELEASE_UNKNOWN) VaultMediaNotice.RELEASE_UNKNOWN else VaultMediaNotice.RECOVERY_REQUIRED)
        drained()
    }
    private fun publish(value: VaultMediaNotice) {
        val view = synchronized(gate) {
            notice = value
            val journal = knownJournal
            val batch = journal?.batch?.let { VaultCopyBatchView(it.items.size, it.activeIndex, it.runCount,
                it.items.map { row -> row.outcome }, it.status) }
            VaultMediaView(value, pendingMove != null, pendingCopy?.target != null && batch == null, running,
                batch, pendingAction, journal?.operation?.kind == VaultMediaKind.COPY_OUT &&
                    journal.phase !in setOf(VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED,
                        VaultMediaPhase.OUTPUT_RETAINED) && journal.target != null && pendingAction != null)
        }
        changed(view)
    }
    private fun id() = UUID.randomUUID().toString().replace("-", "")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

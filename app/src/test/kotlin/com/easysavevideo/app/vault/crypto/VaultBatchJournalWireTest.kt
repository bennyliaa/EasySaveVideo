package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultAttemptIdentity
import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Fixed public scalar data only. No keys, encryption, Android objects, persistent storage or IO. */
class VaultBatchJournalWireTest {
    private fun id(n: Int) = n.toString(16).padStart(32, '0')
    private val plain = ExportEvidence(2, "0".repeat(64))
    private fun plan(count: Int = 3, width: Int = 12): VaultMediaJournal {
        val rows = (1..count).map { VaultCopyBatchItem(id(it), VaultMediaObjectIdentity("o".repeat(width - 1) + it, it.toLong()),
            if (it == 1) 1L else null, if (it == 1) VaultCopyBatchOutcome.ACTIVE else VaultCopyBatchOutcome.WAITING) }
        val batch = VaultCopyBatchState(id(99), 1, 1, VaultMediaJournalWire.planDigest(id(99), 1, 1, rows),
            rows, null, 0, 0, VaultCopyBatchStop.NONE, VaultCopyBatchStatus.PREPARING)
        return VaultMediaJournal(VaultMediaOperation(rows.first().operationId, 1, VaultMediaKind.COPY_OUT),
            VaultMediaPhase.PREPARED, 1, objectIdentity = rows.first().identity, batch = batch)
    }
    private fun tree(value: VaultMediaJournal) = VaultPhoneTreeRecord(value.batch!!.batchId, id(100), id(200), "content://fixture/tree/primary%3APublic", 3)
    private fun picking(v: VaultMediaJournal) = v.copy(phase = VaultMediaPhase.PICKING_TARGET)
    private fun selected(v: VaultMediaJournal) = v.copy(batch = v.batch!!.copy(tree = tree(v), status = VaultCopyBatchStatus.AWAITING_CONFIRMATION))
    private fun started(v: VaultMediaJournal) = v.copy(batch = v.batch!!.copy(runCount = v.batch.runCount + 1,
        stop = VaultCopyBatchStop.NONE, status = VaultCopyBatchStatus.RUNNING,
        items = v.batch.items.map { if (it.outcome == VaultCopyBatchOutcome.PAUSED_NOT_STARTED) it.copy(outcome = VaultCopyBatchOutcome.WAITING) else it }))
    private fun active() = started(selected(picking(plan())))
    private fun reserve(v: VaultMediaJournal) = v.copy(phase = VaultMediaPhase.CHILD_CREATE_INTENT,
        target = VaultMediaTargetRecord(v.operation.operationId, id(1000 + v.batch!!.activeIndex), id(2000 + v.batch.activeIndex), null, 3))
    private fun bound(v: VaultMediaJournal) = v.copy(phase = VaultMediaPhase.TARGET_BOUND, target = v.target!!.copy(uri = "content://fixture/child/${v.batch!!.activeIndex}"))
    private fun row(v: VaultMediaJournal, outcome: VaultCopyBatchOutcome, failure: VaultFailure? = null): VaultMediaJournal {
        val batch = v.batch!!
        val items = batch.items.mapIndexed { index, row -> if (index != batch.activeIndex) row else row.copy(
            outcome = outcome, failure = failure, target = v.target, plaintextEvidence = v.plaintextEvidence) }
        return v.copy(batch = batch.copy(items = items))
    }
    private fun publishing(v: VaultMediaJournal = bound(reserve(active()))) = row(v.copy(phase = VaultMediaPhase.PUBLISH_INTENT,
        plaintextEvidence = plain), VaultCopyBatchOutcome.FINAL_DECISION_PENDING)
    private fun complete(v: VaultMediaJournal = publishing()) = row(v.copy(phase = VaultMediaPhase.COMPLETE), VaultCopyBatchOutcome.VERIFIED_COPY)
    private fun cancelled(v: VaultMediaJournal, outcome: VaultCopyBatchOutcome = VaultCopyBatchOutcome.UNRESOLVED_RETAINED) = row(
        v.copy(phase = VaultMediaPhase.CANCELLED, cancelRequested = true), outcome,
        if (outcome in setOf(VaultCopyBatchOutcome.FAILED_CLEANED, VaultCopyBatchOutcome.FAILED_NO_OUTPUT)) VaultFailure.CORRUPT else null)
    private fun handover(v: VaultMediaJournal): VaultMediaJournal {
        val batch = v.batch!!; val nextIndex = batch.activeIndex + 1; val next = batch.items[nextIndex]
        val operation = VaultMediaOperation(next.operationId, v.operation.sequence + 1, VaultMediaKind.COPY_OUT)
        val items = batch.items.mapIndexed { index, row -> if (index == nextIndex) row.copy(activatedSequence = operation.sequence, outcome = VaultCopyBatchOutcome.ACTIVE) else row }
        return VaultMediaJournal(operation, VaultMediaPhase.PREPARED, v.expectedCatalogRevision, objectIdentity = next.identity,
            batch = batch.copy(items = items, activeIndex = nextIndex))
    }
    private fun pause(v: VaultMediaJournal, stop: VaultCopyBatchStop = VaultCopyBatchStop.LIFECYCLE_PAUSE): VaultMediaJournal = v.copy(batch = v.batch!!.copy(
        status = VaultCopyBatchStatus.PAUSED, stop = stop, items = v.batch.items.mapIndexed { index, row ->
            if (index > v.batch.activeIndex) row.copy(outcome = if (stop == VaultCopyBatchStop.USER_CANCEL) VaultCopyBatchOutcome.CANCELLED_BEFORE_START else VaultCopyBatchOutcome.PAUSED_NOT_STARTED) else row }))
    private fun accepts(a: VaultMediaJournal?, b: VaultMediaJournal) = VaultMediaJournalWire.requireSuccessor(a, b)
    private fun rejects(body: () -> Unit) { try { body(); fail("Expected bounded rejection") }
        catch (_: IllegalArgumentException) {} catch (_: java.io.IOException) {} }
    private fun rejects(a: VaultMediaJournal?, b: VaultMediaJournal) = rejects { accepts(a, b) }
    private fun roundTrip(v: VaultMediaJournal) { val bytes = VaultMediaJournalWire.encode(v)
        assertEquals(v, VaultMediaJournalWire.decode(bytes)); assertArrayEquals(bytes, VaultMediaJournalWire.encode(VaultMediaJournalWire.decode(bytes))) }

    @Test fun frozenLegacyV1GoldenHasExactUnchangedBytesAndFields() {
        val golden = "45534a320000000100000007676f6c642d6f70000000000000000700000001000000000000000900010000000b676f6c642d6f626a65637400000000000000030000000000000000"
        val expected = golden.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val v = VaultMediaJournal(VaultMediaOperation("gold-op", 7, VaultMediaKind.COPY_OUT), VaultMediaPhase.PREPARED, 9,
            objectIdentity = VaultMediaObjectIdentity("gold-object", 3))
        assertArrayEquals(expected, VaultMediaJournalWire.encode(v)); assertEquals(v, VaultMediaJournalWire.decode(expected))
        assertEquals(1, ByteBuffer.wrap(expected).getInt(4))
    }
    @Test fun v1DecoderRejectsBothAppendedPhasesEvenWithOtherwiseValidScalar() {
        val v = bound(reserve(active())).copy(batch = null)
        val wire = VaultMediaJournalWire.encode(v)
        for (code in listOf(15, 16, 17, -1, Int.MAX_VALUE)) rejects {
            VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(phaseOffset(it), code) }) }
    }
    @Test fun retainedSingleIsV2NonSuccessImmutableAndAllowsOnlyNewSequence() {
        val before = bound(reserve(active())).copy(batch = null)
        val kept = before.copy(phase = VaultMediaPhase.OUTPUT_RETAINED, cancelRequested = true)
        accepts(before, kept); roundTrip(kept); assertEquals(2, ByteBuffer.wrap(VaultMediaJournalWire.encode(kept)).getInt(4))
        accepts(kept, kept)
        rejects(kept, kept.copy(phase = VaultMediaPhase.COMPLETE, plaintextEvidence = plain))
        rejects(kept, kept.copy(expectedCatalogRevision = 2))
        val fresh = plan(1).copy(batch = null, operation = VaultMediaOperation(id(8), 2, VaultMediaKind.COPY_OUT))
        accepts(kept, fresh); rejects(kept, fresh.copy(operation = fresh.operation.copy(sequence = 1)))
    }
    @Test fun retainedSingleRejectsFinalDecisionMissingTargetAndMissingCancellation() {
        val final = publishing().copy(batch = null)
        rejects(final, final.copy(phase = VaultMediaPhase.OUTPUT_RETAINED, cancelRequested = true))
        rejects { VaultMediaJournalWire.encode(plan(1).copy(batch = null, phase = VaultMediaPhase.OUTPUT_RETAINED, cancelRequested = true)) }
        rejects { VaultMediaJournalWire.encode(bound(reserve(active())).copy(batch = null, phase = VaultMediaPhase.OUTPUT_RETAINED)) }
        rejects(final, final.copy(phase = VaultMediaPhase.RECONCILIATION_REQUIRED))
        rejects(final, final.copy(phase = VaultMediaPhase.CANCELLED, cancelRequested = true))
    }
    @Test fun v2WithoutBatchRejectsEveryNewShapeExceptRetainedSingle() {
        val old = VaultMediaJournalWire.encode(plan(1).copy(batch = null))
        rejects { VaultMediaJournalWire.decode(old.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) } + 0) }
        rejects { VaultMediaJournalWire.encode(reserve(active()).copy(batch = null)) }
        val kept = VaultMediaJournalWire.encode(bound(reserve(active())).copy(batch = null, phase = VaultMediaPhase.OUTPUT_RETAINED, cancelRequested = true))
        rejects { VaultMediaJournalWire.decode(kept.copyOf().also { ByteBuffer.wrap(it).putInt(4, 1) }) }
    }
    @Test fun canonicalBatchRoundTripsEveryAdmittedProgressShape() {
        val values = mutableListOf(plan()); values += picking(values.last()); values += selected(values.last()); values += started(values.last())
        values += reserve(values.last()); values += bound(values.last()); values += values.last().copy(phase = VaultMediaPhase.SOURCE_VALIDATED, plaintextEvidence = plain)
        values += values.last().copy(phase = VaultMediaPhase.COPYING); values += values.last().copy(phase = VaultMediaPhase.VERIFYING)
        values += publishing(values.last()); values += complete(values.last()); values += handover(values.last())
        var previous: VaultMediaJournal? = null
        values.forEach { accepts(previous, it); roundTrip(it); previous = it }
    }
    @Test fun worstEightRowShapeFitsExactUnchangedCapsIncludingActiveDuplication() {
        val initial = plan(8, 64)
        val bytes = VaultMediaJournalWire.worstCasePayload(initial)
        assertEquals(13397, bytes.size); assertEquals(14336, VaultMediaJournalWire.MAX_PLAIN_BYTES); assertEquals(16384, VaultMediaJournalWire.MAX_ENVELOPE_BYTES)
        assertEquals(939, VaultMediaJournalWire.MAX_PLAIN_BYTES - bytes.size)
        val result = VaultMediaJournalWire.decode(bytes)
        assertEquals(8, result.batch!!.items.size); assertEquals(result.target, result.batch.items.last().target)
        assertTrue(VaultMediaJournalWire.binding(result).size <= 1024)
        VaultMediaJournalWire.requireCapacity(initial)
        // AES256_GCM plus five-byte Tink prefix is bounded arithmetically only. No encryption is executed.
        val rootAeadOverhead = 33
        val envelopeSize = 16 + VaultMediaJournalWire.binding(result).size + rootAeadOverhead + bytes.size + rootAeadOverhead
        assertTrue(envelopeSize <= 16384)
        println("BATCH_PUBLIC_WORST payload=${bytes.size} binding=${VaultMediaJournalWire.binding(result).size} tinkAeadEnvelopeArithmetic=$envelopeSize encryption=NOT_RUN")
    }
    @Test fun exactPayloadOutputBoundaryRejectsOneMoreByteWithoutWideningCaps() {
        val exact = VaultMediaWireIO.encode(14336) { write(ByteArray(14336)) }; assertEquals(14336, exact.size)
        rejects { VaultMediaWireIO.encode(14336) { write(ByteArray(14337)) } }
        rejects { VaultMediaJournalWire.decode(ByteArray(14337)) }
        rejects { VaultMediaJournalWire.decodeBinding(ByteArray(1025)) }
    }
    @Test fun uri1024BoundaryAndMalformedSurrogatesAreStrict() {
        val v = bound(reserve(active()))
        roundTrip(v.copy(target = v.target!!.copy(uri = "a".repeat(1024))))
        rejects { VaultMediaJournalWire.encode(v.copy(target = v.target!!.copy(uri = "a".repeat(1025)))) }
        roundTrip(v.copy(target = v.target!!.copy(uri = "é".repeat(512))))
        rejects { VaultMediaJournalWire.encode(v.copy(target = v.target!!.copy(uri = "é".repeat(513)))) }
        rejects { VaultMediaJournalWire.encode(v.copy(target = v.target!!.copy(uri = "\ud800"))) }
    }
    @Test fun unknownVersionsMalformedBooleanTrailerAndTruncationReject() {
        val wire = VaultMediaJournalWire.encode(plan())
        for (version in listOf(-1, 0, 3, Int.MAX_VALUE)) rejects { VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(4, version) }) }
        rejects { VaultMediaJournalWire.decode(wire + 0) }
        for (length in listOf(0, 1, 4, 7, wire.size - 1)) rejects { VaultMediaJournalWire.decode(wire.copyOf(length)) }
        rejects { VaultMediaJournalWire.decode(wire.copyOf().also { it[phaseOffset(it) + 4] = 2 }) }
    }
    @Test fun countAndAllEnumCodesAreCheckedBeforeRowsAllocation() {
        val wire = VaultMediaJournalWire.encode(plan())
        val batch = batchOffset(wire)
        val countOffset = batch + 36 + 8 + 4
        for (count in listOf(-1, 0, 9, Int.MAX_VALUE)) rejects { VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(countOffset, count) }) }
        for (offset in listOf(countOffset + 4, countOffset + 8, countOffset + 12, countOffset + 16))
            rejects { VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(offset, Int.MAX_VALUE) }) }
        val row = countOffset + 20 + 36 + 1
        val outcomeOffset = row + 4 + plan().objectIdentity!!.objectId.length + 8 + 36
        for (offset in listOf(outcomeOffset, outcomeOffset + 4)) rejects {
            VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(offset, Int.MAX_VALUE) }) }
    }
    @Test fun malformedUtf8HugeLengthsAndDigestMutationReject() {
        val wire = VaultMediaJournalWire.encode(plan())
        rejects { VaultMediaJournalWire.decode(wire.copyOf().also { it[12] = 0xc0.toByte() }) }
        rejects { VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) }) }
        val batch = batchOffset(wire)
        val digestOffset = batch + 36 + 8 + 4 + 20
        rejects { VaultMediaJournalWire.decode(wire.copyOf().also { it[digestOffset + 4] = (it[digestOffset + 4].toInt() xor 1).toByte() }) }
        rejects { VaultMediaJournalWire.decode(wire.copyOf().also { ByteBuffer.wrap(it).putInt(digestOffset, 31) }) }
    }
    @Test fun planDigestBindsBatchRevisionProfileOrderIdentityGenerationAndOperation() {
        val v = plan(); val b = v.batch!!; val digest = b.planDigest
        assertNotEquals(digest, VaultMediaJournalWire.planDigest(id(101), 1, 1, b.items))
        assertNotEquals(digest, VaultMediaJournalWire.planDigest(b.batchId, 2, 1, b.items))
        assertNotEquals(digest, VaultMediaJournalWire.planDigest(b.batchId, 1, 1, b.items.reversed()))
        assertNotEquals(digest, VaultMediaJournalWire.planDigest(b.batchId, 1, 1, b.items.mapIndexed { i, r -> if (i == 1) r.copy(identity = r.identity.copy(generation = 4)) else r }))
        assertNotEquals(digest, VaultMediaJournalWire.planDigest(b.batchId, 1, 1, b.items.mapIndexed { i, r -> if (i == 1) r.copy(operationId = id(50)) else r }))
        rejects { VaultMediaJournalWire.planDigest(b.batchId, 1, 2, b.items) }
        rejects { VaultMediaJournalWire.encode(v.copy(batch = b.copy(planDigest = "f".repeat(64)))) }
        val mutable = b.items.toMutableList(); val defensive = b.copy(items = mutable); mutable.clear()
        assertEquals(3, defensive.items.size); (defensive.items as MutableList).clear(); assertEquals(3, defensive.items.size)
    }
    @Test fun duplicateObjectOrOperationAndGenerationOverflowReject() {
        val b = plan().batch!!
        rejects { VaultMediaJournalWire.planDigest(b.batchId, 1, 1, b.items + b.items[0]) }
        rejects { VaultMediaJournalWire.planDigest(b.batchId, 1, 1, b.items.mapIndexed { i, r -> if (i == 1) r.copy(operationId = b.items[0].operationId) else r }) }
        rejects { VaultMediaJournalWire.planDigest(b.batchId, 1, 1, b.items.mapIndexed { i, r -> if (i == 1) r.copy(identity = b.items[0].identity.copy(generation = 99)) else r }) }
        rejects { VaultMediaJournalWire.planDigest(b.batchId, 1, 1, b.items.mapIndexed { i, r -> if (i == 1) r.copy(identity = r.identity.copy(generation = Long.MAX_VALUE)) else r }) }
        rejects { VaultMediaJournalWire.encode(plan().copy(expectedCatalogRevision = Long.MAX_VALUE)) }
    }
    @Test fun bindingAndAadSeparateBatchPlanCursorVaultRootCatalogAndOperation() {
        val v = plan(); val b = VaultMediaJournalWire.binding(v); val header = VaultMediaJournalWire.decodeBinding(b)
        assertEquals(v.batch!!.batchId, header.batchId); assertEquals(v.batch.planDigest, header.planDigest); assertEquals(0, header.activeIndex)
        val changed = plan().let { it.copy(batch = it.batch!!.copy(batchId = id(301), planDigest = VaultMediaJournalWire.planDigest(id(301), 1, 1, it.batch.items))) }
        assertFalse(b.contentEquals(VaultMediaJournalWire.binding(changed)))
        val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init", "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
        val aad = VaultMediaJournalWire.aad(identity, 1, 2, b)
        assertFalse(aad.contentEquals(VaultMediaJournalWire.aad(identity, 2, 2, b)))
        assertFalse(aad.contentEquals(VaultMediaJournalWire.aad(identity.copy(vaultId = "other"), 1, 2, b)))
        rejects { VaultMediaJournalWire.aad(identity.copy(rootVersion = 2), 1, 2, b) }
        assertFalse(b.contentEquals(VaultMediaJournalWire.binding(v.copy(expectedCatalogRevision = 2))))
        assertFalse(b.contentEquals(VaultMediaJournalWire.binding(handover(complete()))))
        rejects { VaultMediaJournalWire.decodeBinding(b + 0) }
    }
    @Test fun targetCannotFirstAppearInCancellationCleanupOrSecondPicker() {
        val v = active(); val target = bound(reserve(v))
        rejects(v, cancelled(target))
        rejects(v, row(target.copy(phase = VaultMediaPhase.CLEANUP_PENDING), VaultCopyBatchOutcome.CLEANUP_REQUIRED))
        rejects(v, row(target.copy(phase = VaultMediaPhase.RECONCILIATION_REQUIRED), VaultCopyBatchOutcome.CLEANUP_REQUIRED))
        val second = handover(complete()); rejects(second, second.copy(phase = VaultMediaPhase.PICKING_TARGET))
    }
    @Test fun unboundCreateUncertaintyCannotClaimEitherCleanedOutcome() {
        val intent = reserve(active())
        rejects { VaultMediaJournalWire.encode(cancelled(intent, VaultCopyBatchOutcome.FAILED_CLEANED)) }
        rejects { VaultMediaJournalWire.encode(cancelled(intent, VaultCopyBatchOutcome.CANCELLED_CLEANED)) }
        val retained = cancelled(intent); accepts(intent, retained); roundTrip(retained)
    }
    @Test fun treeImmutableAndTargetReservationMayOnlyFillExactUriOnce() {
        val selected = selected(picking(plan())); val run = started(selected); val intent = reserve(run); val target = bound(intent)
        accepts(selected, run); accepts(run, intent); accepts(intent, target)
        rejects(run, run.copy(batch = run.batch!!.copy(tree = tree(run).copy(nonce = id(700)))))
        rejects(intent, target.copy(target = target.target!!.copy(grantId = id(900))))
        rejects(intent, target.copy(target = target.target!!.copy(nonce = id(901))))
        rejects(target, target.copy(target = target.target!!.copy(uri = "content://other")))
        rejects(intent, intent.copy(target = target.target))
    }
    @Test fun activeScalarCannotReuseArchivedTargetUriOrOwnerTokens() {
        val second = bound(reserve(handover(complete())))
        val first = second.batch!!.items.first().target!!
        for (target in listOf(second.target!!.copy(uri = first.uri), second.target.copy(grantId = first.grantId), second.target.copy(nonce = first.nonce)))
            rejects { VaultMediaJournalWire.encode(second.copy(target = target)) }
    }
    @Test fun resultMappingCannotClaimSuccessOrCleanedWithoutExactEvidence() {
        val v = bound(reserve(active()))
        rejects { VaultMediaJournalWire.encode(v.copy(phase = VaultMediaPhase.COMPLETE, plaintextEvidence = plain)) }
        rejects { VaultMediaJournalWire.encode(cancelled(v).copy(batch = cancelled(v).batch!!.copy(items = cancelled(v).batch!!.items.mapIndexed { i, r -> if (i == 0) r.copy(target = null) else r }))) }
        rejects { VaultMediaJournalWire.encode(row(v.copy(phase = VaultMediaPhase.CANCELLED, cancelRequested = true), VaultCopyBatchOutcome.VERIFIED_COPY)) }
        rejects { VaultMediaJournalWire.encode(row(v.copy(phase = VaultMediaPhase.COMPLETE, plaintextEvidence = plain), VaultCopyBatchOutcome.FAILED_CLEANED, VaultFailure.CORRUPT)) }
        rejects { VaultMediaJournalWire.encode(row(v.copy(phase = VaultMediaPhase.CANCELLED), VaultCopyBatchOutcome.CANCELLED_CLEANED)) }
    }
    @Test fun finalDecisionCannotPauseItsScalarCancelRetainOrBecomeCleanup() {
        val v = publishing(); accepts(v, complete(v)); accepts(v, pause(v))
        rejects(v, cancelled(v)); rejects(v, row(v.copy(phase = VaultMediaPhase.RECONCILIATION_REQUIRED), VaultCopyBatchOutcome.CLEANUP_REQUIRED))
        rejects(v, v.copy(phase = VaultMediaPhase.OUTPUT_RETAINED, cancelRequested = true))
        rejects(v, v.copy(phase = VaultMediaPhase.VERIFYING))
    }
    @Test fun handoverRequiresDurableTerminalResultExactNextSequenceAndUnchangedArchives() {
        val v = complete(); val next = handover(v); accepts(v, next)
        rejects(publishing(), next); rejects(bound(reserve(active())), next)
        rejects(v, next.copy(operation = next.operation.copy(sequence = 8)))
        rejects(v, next.copy(batch = next.batch!!.copy(items = next.batch.items.mapIndexed { i, r -> if (i == 0) r.copy(failure = VaultFailure.CORRUPT) else r })))
        rejects(pause(v), next)
        rejects(next, v)
    }
    @Test fun unrelatedOperationCannotBypassBatchEvenAtScalarCompleteOrCancelled() {
        val fresh = plan(1).copy(batch = null, operation = VaultMediaOperation(id(55), 2, VaultMediaKind.COPY_OUT))
        rejects(complete(), fresh); rejects(cancelled(bound(reserve(active()))), fresh)
        val sole = complete(publishing(bound(reserve(started(selected(picking(plan(1))))))))
        val finished = sole.copy(batch = sole.batch!!.copy(status = VaultCopyBatchStatus.FINISHED)); accepts(sole, finished); accepts(finished, fresh)
        rejects(finished, finished.copy(batch = finished.batch!!.copy(status = VaultCopyBatchStatus.RUNNING)))
    }
    @Test fun runReservationIsExactMonotonicAndCannotRestartAttemptedScalar() {
        val selected = selected(picking(plan())); accepts(selected, started(selected))
        rejects(selected, selected.copy(batch = selected.batch!!.copy(status = VaultCopyBatchStatus.RUNNING)))
        val paused = pause(complete()); accepts(paused, started(paused))
        rejects(paused, paused.copy(batch = paused.batch!!.copy(stop = VaultCopyBatchStop.NONE, status = VaultCopyBatchStatus.RUNNING,
            items = paused.batch.items.map { if (it.outcome == VaultCopyBatchOutcome.PAUSED_NOT_STARTED) it.copy(outcome = VaultCopyBatchOutcome.WAITING) else it })))
        val copying = bound(reserve(active())).copy(phase = VaultMediaPhase.COPYING, plaintextEvidence = plain)
        rejects(pause(copying), started(pause(copying)))
        rejects(pause(bound(reserve(active()))), started(pause(bound(reserve(active())))))
        rejects(selected, started(selected).copy(batch = started(selected).batch!!.copy(runCount = 2)))
    }
    @Test fun runBudgetExhaustionRetainsSuccessAndUntouchedRowsAndClosesOnlyTerminalCurrent() {
        val v = complete().let { it.copy(batch = it.batch!!.copy(runCount = 8)) }
        val paused = pause(v, VaultCopyBatchStop.BUDGET_EXHAUSTED)
        val exhausted = paused.copy(batch = paused.batch!!.copy(status = VaultCopyBatchStatus.BUDGET_EXHAUSTED))
        accepts(v, exhausted); roundTrip(exhausted)
        assertEquals(VaultCopyBatchOutcome.VERIFIED_COPY, exhausted.batch!!.items.first().outcome)
        assertEquals(2, exhausted.batch.items.count { it.outcome == VaultCopyBatchOutcome.PAUSED_NOT_STARTED })
        rejects { started(exhausted) }
        rejects { VaultMediaJournalWire.encode(exhausted.copy(batch = exhausted.batch.copy(runCount = 7))) }
        rejects { VaultMediaJournalWire.encode(publishing().copy(batch = exhausted.batch)) }
    }
    @Test fun cancelledQueueCannotClearStopReactivateOrRewriteSuccessfulPrefix() {
        val v = complete(); val stopped = pause(v, VaultCopyBatchStop.USER_CANCEL); accepts(v, stopped)
        val finished = stopped.copy(batch = stopped.batch!!.copy(status = VaultCopyBatchStatus.FINISHED)); accepts(stopped, finished)
        rejects(stopped, started(stopped)); rejects(stopped, handover(v))
        assertEquals(VaultCopyBatchOutcome.VERIFIED_COPY, stopped.batch!!.items.first().outcome)
        assertEquals(2, stopped.batch.items.count { it.outcome == VaultCopyBatchOutcome.CANCELLED_BEFORE_START })
    }
    @Test fun partialSuccessRetainedFailureAndLaterSuccessKeepAllThreeReceipts() {
        val first = complete(); val secondStart = handover(first); accepts(first, secondStart)
        val secondBound = bound(reserve(secondStart)); val secondFailed = cancelled(secondBound, VaultCopyBatchOutcome.FAILED_CLEANED)
        accepts(secondBound, secondFailed)
        val thirdStart = handover(secondFailed); accepts(secondFailed, thirdStart)
        val third = complete(publishing(bound(reserve(thirdStart))))
        val done = third.copy(batch = third.batch!!.copy(status = VaultCopyBatchStatus.FINISHED)); accepts(third, done); roundTrip(done)
        assertEquals(2, done.batch!!.items.count { it.outcome == VaultCopyBatchOutcome.VERIFIED_COPY })
        assertEquals(1, done.batch.items.count { it.outcome == VaultCopyBatchOutcome.FAILED_CLEANED })
        assertEquals(first.batch!!.items.first(), done.batch.items.first())
    }
    @Test fun retainedUncertainChildIsNonSuccessAndCanOnlyHandoverAfterTerminalCas() {
        val v = bound(reserve(active())); val retained = cancelled(v)
        accepts(v, retained); accepts(retained, handover(retained))
        assertEquals(0, retained.batch!!.items.count { it.outcome == VaultCopyBatchOutcome.VERIFIED_COPY })
        rejects(v, handover(retained))
        rejects(retained, retained.copy(batch = retained.batch.copy(items = retained.batch.items.mapIndexed { i, r -> if (i == 0) r.copy(outcome = VaultCopyBatchOutcome.CANCELLED_CLEANED) else r })))
    }
    @Test fun revisionRefreshPreservesOriginalSelectionAndEveryOtherBit() {
        val v = pause(complete()); val fresh = v.copy(expectedCatalogRevision = 7); accepts(v, fresh)
        assertEquals(1L, fresh.batch!!.selectedCatalogRevision)
        assertFalse(VaultMediaJournalWire.binding(v).contentEquals(VaultMediaJournalWire.binding(fresh)))
        rejects(fresh, fresh.copy(expectedCatalogRevision = 6))
        rejects(v, started(v).copy(expectedCatalogRevision = 7))
        rejects(v, handover(complete()).copy(expectedCatalogRevision = 7))
        rejects(v, v.copy(expectedCatalogRevision = Long.MAX_VALUE))
        val oldId = v.batch!!.items[1].identity
        rejects(v, fresh.copy(batch = fresh.batch.copy(items = fresh.batch.items.mapIndexed { i, r -> if (i == 1) r.copy(identity = oldId.copy(generation = 99)) else r })))
    }
    private fun catalog(v: VaultMediaJournal, revision: Long = v.expectedCatalogRevision) = VaultAuthenticatedCatalog(revision,
        v.batch!!.items.map { row -> VaultMediaEntry(row.identity, 1, plain, VaultCiphertextEvidence(58, "1".repeat(64)),
            VaultCiphertext(byteArrayOf(1)), "public.mp4", null, null, null, VaultMediaOperation("move-${row.identity.generation}", row.identity.generation, VaultMediaKind.MOVE_IN),
            MediaReadRef("public-${row.identity.generation}", 1, 1)) })
    @Test fun freshCurrentCatalogMustContainAllOriginalObjectGenerationsWithoutSubstitution() {
        val v = active().copy(expectedCatalogRevision = 7); val good = catalog(v)
        VaultMediaJournalWire.requireSourcePlan(good, v)
        rejects { VaultMediaJournalWire.requireSourcePlan(catalog(v, 6), v) }
        rejects { VaultMediaJournalWire.requireSourcePlan(VaultAuthenticatedCatalog(7, good.entries.drop(1)), v) }
        rejects { VaultMediaJournalWire.requireSourcePlan(VaultAuthenticatedCatalog(7, good.entries.mapIndexed { i, e -> if (i == 1) e.copy(identity = e.identity.copy(generation = 99)) else e }), v) }
        val verified = complete().copy(expectedCatalogRevision = 7)
        rejects { VaultMediaJournalWire.requireSourcePlan(VaultAuthenticatedCatalog(7, catalog(verified).entries.mapIndexed { i, e -> if (i == 0) e.copy(plaintext = plain.copy(bytes = 3)) else e }), verified) }
    }
    @Test fun forgedDoubleActiveAndFutureReceiptCannotBecomeAuthority() {
        val v = active(); val b = v.batch!!
        rejects { VaultMediaJournalWire.encode(v.copy(batch = b.copy(items = b.items.mapIndexed { i, r -> if (i == 1) r.copy(outcome = VaultCopyBatchOutcome.ACTIVE, activatedSequence = 2) else r }))) }
        rejects { VaultMediaJournalWire.encode(v.copy(batch = b.copy(items = b.items.mapIndexed { i, r -> if (i == 1) r.copy(target = VaultMediaTargetRecord(r.operationId, id(555), id(556), "content://fake", 3)) else r }))) }
    }
    @Test fun forbiddenBatchPhaseEdgesCannotSkipCreateDiscardCopyReadbackOrDecision() {
        val start = active(); val target = bound(reserve(start)); val copying = target.copy(phase = VaultMediaPhase.COPYING, plaintextEvidence = plain)
        rejects(start, target); rejects(start, complete()); rejects(target, copying); rejects(copying, publishing(copying))
        rejects(target, complete()); rejects(reserve(start), reserve(start).copy(target = null))
        rejects { VaultMediaJournalWire.encode(plan().copy(phase = VaultMediaPhase.ENCRYPTING)) }
    }
    @Test fun plaintextReadbackEvidenceIsImmutableFromValidationThroughFinalReceipt() {
        val v = bound(reserve(active())).copy(phase = VaultMediaPhase.SOURCE_VALIDATED, plaintextEvidence = plain)
        accepts(v, v.copy(phase = VaultMediaPhase.COPYING))
        rejects(v, v.copy(phase = VaultMediaPhase.COPYING, plaintextEvidence = plain.copy(bytes = 3)))
        val p = publishing(); rejects(p, complete(p).let { row(it.copy(plaintextEvidence = plain.copy(sha256 = "a".repeat(64))), VaultCopyBatchOutcome.VERIFIED_COPY) })
    }
    @Test fun redactedStringsDoNotExposeOpaqueIdsUrisOrDigests() {
        val v = complete(); val values = listOf(v, v.batch, v.batch!!.items.first(), v.target, v.batch.tree, VaultMediaJournalWire.decodeBinding(VaultMediaJournalWire.binding(v)))
        for (value in values) { val text = value.toString(); assertFalse(text.contains("content://")); assertFalse(text.contains(v.operation.operationId)); assertFalse(text.contains(v.batch.planDigest)) }
    }


    @Test fun everyCredentialResealPreservesWholeBatchBytesWithoutRebasingOldSnapshot() {
        val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init", "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
        fun cipher(n: Int) = VaultCiphertext(byteArrayOf(n.toByte()))
        val primary = VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, cipher(1), true)
        // Synthetic opaque fixture only: these are NOT encrypted bytes and never reach production storage.
        val fixture = VaultCiphertext(VaultMediaJournalWire.encode(pause(complete())))
        val before = VaultStagedData(listOf(primary), cipher(2), cipher(3), 1, cipher(4), VaultRetryLedger(0, 0, -1, null), cipher(5),
            mediaJournalEnvelope = fixture, mediaJournalRevision = 10)
        val recovery = VaultSlotRecord("recovery-v1", 2, VaultSlotKind.RECOVERY, cipher(6), true)
        val pending = before.copy(slots = listOf(primary, recovery), catalogRevision = 2, recoveryState = VaultRecoveryState.PENDING)
        val active = pending.copy(catalogRevision = 3, recoveryState = VaultRecoveryState.ACTIVE)
        val reservation = VaultRetryLedger(1, 1, 0, 1)
        val reset = active.copy(catalogRevision = 4, pendingPassword = VaultPendingPassword(
            primary.copy(generation = 4, envelope = cipher(7)), VaultAttemptIdentity(1, 1, 1), reservation))
        val complete = reset.copy(slots = listOf(reset.pendingPassword!!.slot), catalogRevision = 5,
            recoveryState = VaultRecoveryState.NONE, pendingPassword = null)
        val entries = catalog(plan()).entries
        var previous = before
        var catalogBytes = VaultMediaCatalogWire.encode(identity, before, entries)
        val oldFingerprint = VaultCryptoWire.snapshotFingerprint(VaultStoredSnapshot(identity, VaultStorePhase.READY, 3, before))
        for (next in listOf(pending, active, reset, complete)) {
            catalogBytes = VaultMediaCatalogWire.resealPlaintext(identity, previous, next, catalogBytes)
            assertEquals(entries, VaultMediaCatalogWire.decode(identity, next, catalogBytes).entries)
            assertArrayEquals(fixture.copyBytes(), next.mediaJournalEnvelope!!.copyBytes())
            assertEquals(10L, next.mediaJournalRevision)
            val currentFingerprint = VaultCryptoWire.snapshotFingerprint(VaultStoredSnapshot(identity, VaultStorePhase.READY, 4, next))
            assertFalse(oldFingerprint.contentEquals(currentFingerprint))
            previous = next
        }
        rejects { VaultMediaCatalogWire.resealPlaintext(identity, complete, complete.copy(catalogRevision = 6, mediaJournalRevision = 11), catalogBytes) }
        rejects { VaultMediaCatalogWire.resealPlaintext(identity, complete, complete.copy(catalogRevision = 6, mediaJournalEnvelope = cipher(9)), catalogBytes) }
    }
    @Test fun callerCreatedBatchRootAndMutationCannotReachCryptoOrIssueAuthority() {
        val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init", "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
        fun cipher(n: Int) = VaultCiphertext(byteArrayOf(n.toByte()))
        val data = VaultStagedData(listOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, cipher(1), true)),
            cipher(2), cipher(3), 1, cipher(4), VaultRetryLedger(0, 0, -1, null), cipher(5))
        val snapshot = VaultStoredSnapshot(identity, VaultStorePhase.READY, 1, data)
        val root = object : VaultRootCapability { override fun close() {} }
        val mutation = object : VaultMediaMutation { override fun close() {} }
        val owner = AndroidVaultAuthentication()
        val context = VaultOperationContext(100, VaultMonotonicClock { 0 })
        try { owner.sealMediaJournal(root, snapshot, plan(), context); fail("Forged root") }
        catch (failure: VaultOperationException) { assertEquals(VaultFailure.REJECTED, failure.category) }
        try { owner.validateMediaMutation(snapshot, mutation, context); fail("Forged mutation") }
        catch (failure: VaultOperationException) { assertEquals(VaultFailure.REJECTED, failure.category) }
        assertEquals(com.easysavevideo.library.export.ExportRelease.RELEASED, owner.observeMediaDrain())
        owner.close()
    }

    private fun phaseOffset(bytes: ByteArray): Int {
        val b = ByteBuffer.wrap(bytes); b.position(8); skipText(b); b.position(b.position() + 8 + 4 + 8)
        if (b.get().toInt() != 0) { skipText(b); b.position(b.position() + 16) }
        if (b.get().toInt() != 0) { skipText(b); b.position(b.position() + 8) }
        return b.position()
    }
    private fun batchOffset(bytes: ByteArray): Int {
        val b = ByteBuffer.wrap(bytes); b.position(phaseOffset(bytes) + 5)
        if (b.get().toInt() != 0) { repeat(3) { skipText(b) }; if (b.get().toInt() != 0) skipText(b); b.position(b.position() + 4) }
        repeat(2) { if (b.get().toInt() != 0) { b.position(b.position() + 8); skipText(b) } }
        assertEquals(1, b.get().toInt()); return b.position()
    }
    private fun skipText(b: ByteBuffer) { val n = b.int; b.position(b.position() + n) }
}

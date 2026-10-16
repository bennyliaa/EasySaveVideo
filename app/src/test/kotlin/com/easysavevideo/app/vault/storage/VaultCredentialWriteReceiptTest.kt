package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.IdentityHashMap

/** Fake opaque owner and fixed ciphertext only; does not open or emulate a database. */
class VaultCredentialWriteReceiptTest {
    private val identity = VaultPersistentIdentity("vault", "installation", "init",
        "esv.vault.v1.init.device", "esv.vault.v1.init.biometric")
    private val blob = VaultCiphertext(byteArrayOf(1, 2, 3))
    private val before = VaultStoredSnapshot(identity, VaultStorePhase.READY, 3, VaultStagedData(
        listOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, blob, true)),
        blob, blob, 1, blob, VaultRetryLedger(0, 0, -1, null), blob))
    private val after = before.copy(storeRevision = 4)
    private val proposed = VaultSqliteRecord(4, "credential", 3, VaultSnapshotCodec.encode(after))
    private val reset = VaultRetryWrite(VaultRetryWriteKind.RESET, VaultRetryWriteIdentity(VaultAttemptIdentity(1, 2, 3), 5),
        1, VaultRetryLedger(2, 0, -1, null), VaultTick(1, 15_000))
    private class Owner {
        private val entries = IdentityHashMap<VaultCredentialMutation, Unit>()
        fun issue(): VaultCredentialMutation = object : VaultCredentialMutation {
            override fun close() { entries.remove(this) }
        }.also { entries[it] = Unit }
        fun validate(mutation: VaultCredentialMutation) {
            if (!entries.containsKey(mutation)) throw VaultOperationException(VaultFailure.REJECTED)
        }
    }
    private fun rejected(category: VaultFailure? = null, block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (failure: VaultOperationException) {
            if (category != null) assertEquals(category, failure.category)
        }
    }
    @Test fun sameOpaqueIdentityAndEveryBeforeAfterResetByteAreRequired() {
        val owner = Owner()
        val proof = owner.issue()
        val context = VaultOperationContext(20_000, VaultMonotonicClock { 0 })
        val receipt = VaultCredentialWriteReceipt.admit(null, proof, before, proposed, reset, context, false)
        assertTrue(receipt.matches(proof, before, proposed, reset))
        assertFalse(receipt.matches(owner.issue(), before, proposed, reset))
        assertFalse(receipt.matches(proof, before.copy(storeRevision = 4), proposed, reset))
        assertFalse(receipt.matches(proof, before, proposed, reset.copy(deadline = VaultTick(1, 16_000))))
        assertFalse(receipt.matches(proof, before, proposed, reset.copy(identity = reset.identity.copy(writeId = 6))))
        assertFalse(receipt.matches(proof, before, VaultSqliteRecord(4, "changed", 3, proposed.payload()), reset))
        assertFalse(receipt.matches(proof, before, VaultSqliteRecord(4, "credential", 3,
            VaultSnapshotCodec.encode(after.copy(data = after.data!!.copy(retryLedgerEnvelope = VaultCiphertext(byteArrayOf(4)))))), reset))
    }
    @Test fun inspectLikeClearingOfPendingCannotRefreshOriginalSqlDeadline() {
        val owner = Owner()
        val proof = owner.issue()
        var tick = 0L
        val context = VaultOperationContext(100_000, VaultMonotonicClock { tick })
        val receipt = VaultCredentialWriteReceipt.admit(null, proof, before, proposed, null, context, false)
        assertEquals(10_000L, receipt.context.deadlineElapsedMillis)
        tick = 9_999
        assertSame(receipt, VaultCredentialWriteReceipt.admit(receipt, proof, before, proposed, null, context, false))
        tick = 10_000
        rejected(VaultFailure.DEADLINE) {
            VaultCredentialWriteReceipt.admit(receipt, proof, before, proposed, null, context, false)
        }
    }
    @Test fun replacePWithQAfterPreWriteFailurePermanentlyRetiresPWithoutUnboundedRegistryInStore() {
        val owner = Owner()
        val p = owner.issue(); val q = owner.issue()
        var tick = 0L
        val context = VaultOperationContext(100_000, VaultMonotonicClock { tick })
        owner.validate(p)
        val first = VaultCredentialWriteReceipt.admit(null, p, before, proposed, null, context, false)
        tick = 9_000
        owner.validate(q)
        val second = VaultCredentialWriteReceipt.admit(first, q, before, proposed, null, context, false)
        assertEquals(19_000L, second.context.deadlineElapsedMillis)
        tick = 11_000
        rejected(VaultFailure.REJECTED) { owner.validate(p) }
        assertFalse(first.matches(p, before, proposed, null))
        owner.validate(q)
        second.retire(); second.retire()
        rejected(VaultFailure.REJECTED) { owner.validate(q) }
    }
    @Test fun unknownMutationForbidsReplacementAndRetainsOriginalAuthorityAndBudget() {
        val owner = Owner(); val p = owner.issue(); val q = owner.issue()
        val context = VaultOperationContext(20_000, VaultMonotonicClock { 0 })
        val first = VaultCredentialWriteReceipt.admit(null, p, before, proposed, null, context, false)
        rejected(VaultFailure.INCOMPLETE) {
            VaultCredentialWriteReceipt.admit(first, q, before, proposed, null, context, true)
        }
        owner.validate(p)
        assertSame(first, VaultCredentialWriteReceipt.admit(first, p, before, proposed, null, context, true))
    }
    @Test fun failedRetirementCannotBeSilentlyTreatedAsReplacement() {
        val broken = object : VaultCredentialMutation { override fun close() { throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN) } }
        val owner = Owner()
        val context = VaultOperationContext(20_000, VaultMonotonicClock { 0 })
        val first = VaultCredentialWriteReceipt.admit(null, broken, before, proposed, null, context, false)
        rejected(VaultFailure.RELEASE_UNKNOWN) {
            VaultCredentialWriteReceipt.admit(first, owner.issue(), before, proposed, null, context, false)
        }
        assertTrue(first.matches(broken, before, proposed, null))
    }
    @Test fun confirmedOldWithExactRowsAndExpiredOriginalBudgetRetiresWithoutTouchingLedger() {
        val owner = Owner(); val proof = owner.issue()
        var tick = 0L
        val context = VaultOperationContext(100_000, VaultMonotonicClock { tick })
        val receipt = VaultCredentialWriteReceipt.admit(null, proof, before, proposed, null, context, false)
        val expected = VaultSqliteRecord(before.storeRevision, "original-row-operation", 3, VaultSnapshotCodec.encode(before))
        val mutation = VaultSqliteMutation(expected, proposed)
        receipt.bindMutation(mutation)
        tick = 10_000
        assertTrue(receipt.retireConfirmedOld(mutation, VaultSqliteWriteResult.CONFIRMED_OLD))
        rejected(VaultFailure.REJECTED) { owner.validate(proof) }
        assertArrayEquals(VaultSnapshotCodec.encode(before), expected.payload())
        assertEquals(before.data!!.retryLedger, VaultSnapshotCodec.decode(expected.payload()).data!!.retryLedger)
        val replacement = owner.issue()
        owner.validate(replacement)
        val fresh = VaultCredentialWriteReceipt.admit(receipt, replacement, before, proposed, null, context, false)
        assertEquals(20_000L, fresh.context.deadlineElapsedMillis)
    }
    @Test fun confirmedOldWithOriginalCancellationCanRetireButActiveOrUnknownCannot() {
        val owner = Owner(); val proof = owner.issue()
        val context = VaultOperationContext(100_000, VaultMonotonicClock { 0 })
        val receipt = VaultCredentialWriteReceipt.admit(null, proof, before, proposed, null, context, false)
        val mutation = VaultSqliteMutation(VaultSqliteRecord(before.storeRevision, "old", 3, VaultSnapshotCodec.encode(before)), proposed)
        receipt.bindMutation(mutation)
        assertFalse(receipt.retireConfirmedOld(mutation, VaultSqliteWriteResult.CONFIRMED_OLD))
        owner.validate(proof)
        context.cancel()
        listOf(VaultSqliteWriteResult.UNKNOWN, VaultSqliteWriteResult.CONFLICT, VaultSqliteWriteResult.DURABLE_EXACT,
            VaultSqliteWriteResult.UNAVAILABLE, VaultSqliteWriteResult.DEADLINE).forEach {
            assertFalse(receipt.retireConfirmedOld(mutation, it))
            owner.validate(proof)
        }
        assertTrue(receipt.retireConfirmedOld(mutation, VaultSqliteWriteResult.CONFIRMED_OLD))
        rejected(VaultFailure.REJECTED) { owner.validate(proof) }
    }
    @Test fun confirmedOldCannotRetireUnboundOrDifferentTechnicalRowsEvenWithMatchingPayload() {
        val owner = Owner(); val proof = owner.issue()
        val context = VaultOperationContext(100_000, VaultMonotonicClock { 0 })
        val receipt = VaultCredentialWriteReceipt.admit(null, proof, before, proposed, null, context, false)
        val expected = VaultSqliteRecord(before.storeRevision, "old", 3, VaultSnapshotCodec.encode(before))
        val mutation = VaultSqliteMutation(expected, proposed)
        context.cancel()
        assertFalse(receipt.retireConfirmedOld(mutation, VaultSqliteWriteResult.CONFIRMED_OLD))
        receipt.bindMutation(mutation)
        assertFalse(receipt.retireConfirmedOld(VaultSqliteMutation(
            VaultSqliteRecord(before.storeRevision, "other", 3, expected.payload()), proposed), VaultSqliteWriteResult.CONFIRMED_OLD))
        assertFalse(receipt.retireConfirmedOld(VaultSqliteMutation(expected,
            VaultSqliteRecord(proposed.revision, "other", 3, proposed.payload())), VaultSqliteWriteResult.CONFIRMED_OLD))
        owner.validate(proof)
    }
    @Test fun confirmedOldRetirementFailureDoesNotAuthorizeClearingPending() {
        val proof = object : VaultCredentialMutation { override fun close() { throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN) } }
        val context = VaultOperationContext(100_000, VaultMonotonicClock { 0 })
        val receipt = VaultCredentialWriteReceipt.admit(null, proof, before, proposed, null, context, false)
        val mutation = VaultSqliteMutation(VaultSqliteRecord(before.storeRevision, "old", 3, VaultSnapshotCodec.encode(before)), proposed)
        receipt.bindMutation(mutation)
        context.cancel()
        var pending: VaultSqliteMutation? = mutation
        rejected(VaultFailure.RELEASE_UNKNOWN) {
            if (receipt.retireConfirmedOld(mutation, VaultSqliteWriteResult.CONFIRMED_OLD)) pending = null
        }
        assertSame(mutation, pending)
        assertTrue(receipt.matches(proof, before, proposed, null))
    }

}

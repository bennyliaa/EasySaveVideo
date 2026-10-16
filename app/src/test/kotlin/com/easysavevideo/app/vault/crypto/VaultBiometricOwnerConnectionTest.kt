package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.app.vault.storage.*
import com.easysavevideo.core.model.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import com.easysavevideo.library.export.ExportRelease
import com.google.crypto.tink.KeysetHandle
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

/** Real new Authentication methods + codec/CAS validation, fixed public crypto, fake platform.
 * The prerequisite password-authenticated Root is explicitly staged by reflection. These tests
 * do NOT claim to exercise password/KDF/RESET, Android KeyStore, SQLite, or a real biometric.
 * No biometric operation, stageProposal, activation, or mutation registry is fabricated.
 */
class VaultBiometricOwnerConnectionTest {
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
    private val attempt = VaultAttemptIdentity(41, 3, 2)
    private val zero = VaultRetryLedger(2, 0, -1, null)
    private fun blob(n: Int) = VaultCiphertext(ByteArray(32) { (n + it).toByte() })
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun fixedKeyset(): KeysetHandle = VaultJcaAead.fromOwnedBytes(ByteArray(32)).use {
        VaultTinkKeysets.unwrap(hex("128201000102030405060708090a0b80193a4061f7d7f3f20c2b95982db9633b0bc4ffdeede685e1a88b66a25260008b9bd98b2f15488c4cbfe3fec7d3252c7591e4bd84e0c6bbd5eb7f59328ab2a48330efd5944813a64dd7a49bfe48ad256ce1f216b91dcd5a2cceb27333c68006a0186928f4b21de8bcb85e920d76231ec7a282d79baa"),
            it, "ESV-PUBLIC-FIXED-KEYSET-VECTOR".toByteArray())
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected owner rejection") } catch (_: VaultOperationException) { }
        catch (_: IllegalArgumentException) { }
    }
    private class AliasAccess : AndroidVaultKeystore.BiometricAliasAccess {
        val aliases = mutableSetOf<String>()
        var deletes = 0
        var failDelete = false
        override fun containsExact(alias: String) = alias in aliases
        override fun deleteExact(alias: String) { deletes++; if (failDelete) error("fixed-failure"); aliases.remove(alias) }
    }
    private class FakePending(val finish: () -> Unit) : VaultBiometricPending {
        var started = false
        var cancelled = false
        var terminal = false
        val listeners = mutableListOf<() -> Unit>()
        override fun start() { started = true }
        fun complete() { check(started); try { finish() } finally { terminal = true; listeners.toList().forEach { it() } } }
        override fun close() { cancelled = true; if (!started) terminal = true }
        override fun observeDrain() = if (terminal) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
        override fun onDrain(listener: () -> Unit) { if (terminal) listener() else listeners += listener }
    }
    private inner class FakeLeaf : VaultBiometricCryptoLeaf {
        val wrapper = VaultTinkKeysets.primitive(fixedKeyset())
        val pending = mutableListOf<FakePending>()
        var encrypts = 0
        var decrypts = 0
        var lastPersisted: VaultCiphertext? = null
        var substitute = false
        override fun availability() = VaultBiometricAvailability.AVAILABLE
        override fun prepareWrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long,
            root: KeysetHandle, title: CharSequence, cancelText: CharSequence, context: VaultOperationContext,
            binding: VaultBiometricBinding, completed: (AndroidVaultBiometric.Outcome<VaultCiphertext>) -> Unit): VaultBiometricPending =
            FakePending {
                encrypts++
                completed(AndroidVaultBiometric.Outcome.Completed(VaultCiphertext(VaultTinkKeysets.wrap(root, wrapper,
                    VaultCryptoWire.aad(identity, VaultAadPurpose.BIOMETRIC_ROOT, VaultSlotKind.BIOMETRIC, slotId, generation)))))
            }.also { pending += it }
        override fun prepareUnwrapRoot(identity: VaultPersistentIdentity, slotId: String, generation: Long,
            persistedEnvelope: VaultCiphertext, title: CharSequence, cancelText: CharSequence,
            context: VaultOperationContext, binding: VaultBiometricBinding,
            completed: (AndroidVaultBiometric.Outcome<KeysetHandle>) -> Unit): VaultBiometricPending {
            lastPersisted = VaultCiphertext(persistedEnvelope.copyBytes())
            return FakePending {
                decrypts++
                val handle = VaultTinkKeysets.unwrap(persistedEnvelope.copyBytes(), wrapper,
                    VaultCryptoWire.aad(identity, VaultAadPurpose.BIOMETRIC_ROOT, VaultSlotKind.BIOMETRIC, slotId, generation))
                completed(if (substitute) AndroidVaultBiometric.Outcome.Failed(AndroidVaultBiometric.Failure.REJECTED)
                    else AndroidVaultBiometric.Outcome.Completed(handle))
            }.also { pending += it }
        }
    }
    private inner class Fixture : AutoCloseable {
        var now = 0L
        val context = VaultOperationContext(395_000, VaultMonotonicClock { now })
        val alias = AliasAccess()
        val leaf = FakeLeaf()
        val rootKey = fixedKeyset()
        val primitive = VaultTinkKeysets.primitive(rootKey)
        var creates = 0
        var beforeDeviceDecrypt: (() -> Unit)? = null
        private val device = object : com.google.crypto.tink.Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = primitive.encrypt(plaintext, associatedData)
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
                val hook = beforeDeviceDecrypt; beforeDeviceDecrypt = null; hook?.invoke()
                return primitive.decrypt(ciphertext, associatedData)
            }
        }
        val owner = AndroidVaultAuthentication(AndroidVaultKeystore(alias), deviceAead = { _, _ -> device },
            biometric = { leaf }, createBiometricKey = { check(alias.aliases.add(it)); creates++ },
            biometricText = { "Public fixture" to "Cancel" })
        val host = Any(); val activity = Any(); val workflow = Any()
        var current = true
        fun binding(purpose: VaultBiometricPurpose, flow: Any = workflow) = VaultBiometricBinding(purpose, host, activity, 1, flow) { current }
        val media = listOf(VaultMediaEntry(VaultMediaObjectIdentity("public-object", 1), 1,
            ExportEvidence(2, "0".repeat(64)), VaultCiphertextEvidence(58, "1".repeat(64)), blob(90), "public.mp4", 5, 2, 2,
            VaultMediaOperation("public-move", 1, VaultMediaKind.MOVE_IN), MediaReadRef("public-source", 1, 1), true))
        val before: VaultStoredSnapshot
        val root: VaultRootCapability
        init {
            val data = VaultStagedData(listOf(VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, blob(1), true),
                VaultSlotRecord("recovery-v1", 2, VaultSlotKind.RECOVERY, blob(2), true)),
                blob(3), blob(4), 3, VaultCiphertext(primitive.encrypt(VaultCryptoWire.marker(identity),
                    VaultCryptoWire.aad(identity, VaultAadPurpose.INSTALLATION_MARKER))), zero,
                VaultCiphertext(primitive.encrypt(VaultCryptoWire.ledger(zero), VaultCryptoWire.aad(identity,
                    VaultAadPurpose.RETRY_LEDGER, revision = zero.ledgerRevision))), VaultRecoveryState.ACTIVE,
                mediaJournalEnvelope = blob(5), mediaJournalRevision = 1)
            val plain = VaultMediaCatalogWire.encode(identity, data, media)
            before = VaultSnapshotCodec.freeze(VaultStoredSnapshot(identity, VaultStorePhase.READY, 8, data.copy(
                catalogKeysetEnvelope = VaultCiphertext(VaultTinkKeysets.wrap(rootKey, primitive,
                    VaultCryptoWire.aad(identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = 3))),
                catalogEnvelope = VaultCiphertext(primitive.encrypt(plain, VaultCryptoWire.aad(identity, VaultAadPurpose.CATALOG, revision = 3))))))
            root = seedPasswordRoot(before)
        }
        fun seedPasswordRoot(snapshot: VaultStoredSnapshot): VaultRootCapability {
            // Fixture staging only: real fresh-password/KDF/RESET path deliberately excluded.
            val cls = AndroidVaultAuthentication::class.java.declaredClasses.single { it.simpleName == "Root" }
            val constructor = cls.declaredConstructors.single { it.parameterCount == 7 }.apply { isAccessible = true }
            val seeded = constructor.newInstance(owner, rootKey, identity, attempt, VaultCryptoWire.catalogFingerprint(snapshot),
                context.bounded(15_000), true) as VaultRootCapability
            @Suppress("UNCHECKED_CAST")
            val roots = AndroidVaultAuthentication::class.java.getDeclaredField("roots").apply { isAccessible = true }
                .get(owner) as IdentityHashMap<VaultRootCapability, Any>
            roots[seeded] = seeded
            return seeded
        }
        fun activate(): VaultStoredSnapshot {
            val (enroll, staged) = stage()
            val verify = owner.prepareBiometricActivation(staged, enroll, binding(VaultBiometricPurpose.ENROLL_VERIFY), context)
            enroll.close()
            var result: VaultBiometricResult? = null
            owner.startBiometric(verify) { result = it }; leaf.pending.last().complete()
            val mutation = (result as VaultBiometricResult.Mutation).mutation
            val active = persist(staged, mutation, verify)
            mutation.close(); verify.close()
            return active
        }
        fun persist(before: VaultStoredSnapshot, mutation: VaultCredentialMutation, operation: VaultBiometricOperation): VaultStoredSnapshot {
            val proposal = owner.validateCredentialMutation(before, mutation, operation.context.bounded(10_000))
            val after = VaultCredentialMutationValidation.requireValid(before, proposal)
            return VaultSnapshotCodec.decode(VaultSnapshotCodec.encode(after))
        }
        fun stage(): Pair<VaultBiometricOperation, VaultStoredSnapshot> {
            val operation = owner.prepareBiometricEnrollment(root, before, attempt, binding(VaultBiometricPurpose.ENROLL_WRAP), context)
            assertEquals(0, leaf.encrypts)
            var result: VaultBiometricResult? = null
            owner.startBiometric(operation) { result = it }
            assertEquals(ExportRelease.UNCONFIRMED, operation.observeDrain())
            leaf.pending.last().complete()
            val mutation = (result as VaultBiometricResult.Mutation).mutation
            val staged = persist(before, mutation, operation)
            mutation.close()
            assertEquals(VaultBiometricState.PENDING, staged.data!!.biometricState)
            assertEquals(before.data!!.slots, staged.data!!.slots.filter { it.kind != VaultSlotKind.BIOMETRIC })
            root.close()
            return operation to staged
        }
        override fun close() {
            leaf.pending.filter { it.started && !it.terminal }.forEach { it.complete() }
            owner.close()
        }
    }
    @Test fun actualStagePersistedDecryptActivationUsesTwoOperationsAndNoRootRevival() {
        Fixture().use { f ->
            f.now = 20_000 // Completed password KDF child's deadline does not shorten the whole workflow.
            val (enroll, staged) = f.stage()
            f.now = 145_000 // The second prompt is outside the first prompt window, inside original workflow.
            val verify = f.owner.prepareBiometricActivation(staged, enroll, f.binding(VaultBiometricPurpose.ENROLL_VERIFY), f.context)
            enroll.close()
            assertEquals(265_000, verify.context.deadlineElapsedMillis)
            assertEquals(staged.data!!.slots.single { it.kind == VaultSlotKind.BIOMETRIC }.envelope, f.leaf.lastPersisted)
            var result: VaultBiometricResult? = null
            f.owner.startBiometric(verify) { result = it }; f.leaf.pending.last().complete()
            assertTrue(result is VaultBiometricResult.Mutation)
            val mutation = (result as VaultBiometricResult.Mutation).mutation
            val active = f.persist(staged, mutation, verify)
            assertEquals(VaultBiometricState.ACTIVE, active.data!!.biometricState)
            assertEquals(staged.data!!.slots, active.data!!.slots)
            assertEquals(staged.data!!.mediaJournalEnvelope, active.data!!.mediaJournalEnvelope)
            val key = VaultTinkKeysets.unwrap(active.data!!.catalogKeysetEnvelope.copyBytes(), f.primitive,
                VaultCryptoWire.aad(identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = active.data!!.catalogRevision))
            val plain = VaultTinkKeysets.primitive(key).decrypt(active.data!!.catalogEnvelope.copyBytes(),
                VaultCryptoWire.aad(identity, VaultAadPurpose.CATALOG, revision = active.data!!.catalogRevision))
            assertEquals(f.media, VaultMediaCatalogWire.decode(identity, active.data!!, plain).entries)
            rejected { f.owner.readEmptyCatalog(f.root, active, f.context) }
            assertEquals(1, f.creates); assertEquals(1, f.leaf.encrypts); assertEquals(1, f.leaf.decrypts)
            mutation.close(); verify.close()
        }
    }
    @Test fun actualActivationRejectsAlteredReadbackWorkflowAndUnrelatedCancellationLineage() {
        Fixture().use { f ->
            val (operation, staged) = f.stage()
            rejected { f.owner.prepareBiometricActivation(staged.copy(storeRevision = staged.storeRevision + 1), operation,
                f.binding(VaultBiometricPurpose.ENROLL_VERIFY), f.context) }
            rejected { f.owner.prepareBiometricActivation(staged, operation,
                f.binding(VaultBiometricPurpose.ENROLL_VERIFY, Any()), f.context) }
            rejected { f.owner.prepareBiometricActivation(staged, operation,
                f.binding(VaultBiometricPurpose.ENROLL_VERIFY), VaultOperationContext(395_000, VaultMonotonicClock { 0 })) }
            assertEquals(0, f.leaf.decrypts)
            operation.close()
        }
    }
    @Test fun lateCancelledWrapCannotRegisterMutationAndPendingRemainsUntilActualDrain() {
        Fixture().use { f ->
            val operation = f.owner.prepareBiometricEnrollment(f.root, f.before, attempt,
                f.binding(VaultBiometricPurpose.ENROLL_WRAP), f.context)
            var result: VaultBiometricResult? = null
            f.owner.startBiometric(operation) { result = it }
            operation.close()
            assertEquals(ExportRelease.UNCONFIRMED, f.owner.observeBiometricDrain())
            f.leaf.pending.last().complete()
            assertTrue(result is VaultBiometricResult.Failed)
            assertEquals(ExportRelease.RELEASED, f.owner.observeBiometricDrain())
        }
    }
    @Test fun expiredPromptMutationCannotAcquireFreshSqlBudget() {
        Fixture().use { f ->
            val operation = f.owner.prepareBiometricEnrollment(f.root, f.before, attempt,
                f.binding(VaultBiometricPurpose.ENROLL_WRAP), f.context)
            var result: VaultBiometricResult? = null
            f.owner.startBiometric(operation) { result = it }; f.leaf.pending.last().complete()
            val mutation = (result as VaultBiometricResult.Mutation).mutation
            f.now = 120_000
            rejected { f.owner.validateCredentialMutation(f.before, mutation, f.context.bounded(10_000)) }
            mutation.close(); operation.close()
        }
    }

    @Test fun actualActiveUnlockRegistersCatalogBoundRootWithoutPasswordMutationAuthority() {
        Fixture().use { f ->
            val active = f.activate()
            val reservation = VaultRetryLedger(3, 1, 0, 7)
            val current = active.copy(storeRevision = active.storeRevision + 1, data = active.data!!.copy(retryLedger = reservation,
                retryLedgerEnvelope = f.owner.sealLedger(identity, reservation, f.context)))
            val binding = VaultBiometricBinding(VaultBiometricPurpose.UNLOCK, f.host, f.activity, 1, null) { f.current }
            val operation = f.owner.prepareBiometricUnlock(current, attempt, binding, f.context)
            var result: VaultBiometricResult? = null
            f.owner.startBiometric(operation) { result = it }; f.leaf.pending.last().complete()
            val root = (result as VaultBiometricResult.Unlocked).root
            assertEquals(f.media, f.owner.authenticateCatalog(root, current, f.context).entries)
            val resetLedger = VaultRetryLedger(4, 0, -1, null)
            val healthy = current.copy(storeRevision = current.storeRevision + 1, data = current.data!!.copy(
                retryLedger = resetLedger, retryLedgerEnvelope = f.owner.sealLedger(identity, resetLedger, f.context)))
            // The real healthy RESET bytes leave catalog binding intact. The only failing
            // fresh-password precondition here is biometric Root provenance, not reservation.
            assertEquals(resetLedger, f.owner.authenticateLedger(healthy, f.context))
            assertEquals(f.media, f.owner.authenticateCatalog(root, healthy, f.context).entries)
            try {
                f.owner.prepareBiometricRevocation(root, healthy, attempt, f.binding(VaultBiometricPurpose.RETIRE_ALIAS), f.context)
                fail("Biometric Root cannot authorize a password-confirmed mutation")
            } catch (failure: VaultOperationException) { assertEquals(VaultFailure.REJECTED, failure.category) }
            val passwordControl = f.seedPasswordRoot(healthy)
            val mutation = f.owner.prepareBiometricRevocation(passwordControl, healthy, attempt,
                f.binding(VaultBiometricPurpose.RETIRE_ALIAS), f.context)
            assertEquals(VaultCredentialMutationKind.REVOKE_BIOMETRIC,
                f.owner.validateCredentialMutation(healthy, mutation, f.context.bounded(10_000)).kind)
            mutation.close(); passwordControl.close(); root.close(); operation.close()
        }
    }
    @Test fun actualRevocationPreservesSlotsAndIssuesExactRevokedAliasRetirement() {
        Fixture().use { f ->
            val active = f.activate()
            val password = f.seedPasswordRoot(active)
            val mutation = f.owner.prepareBiometricRevocation(password, active, attempt, f.binding(VaultBiometricPurpose.RETIRE_ALIAS), f.context)
            val proposal = f.owner.validateCredentialMutation(active, mutation, f.context.bounded(10_000))
            val revoked = VaultSnapshotCodec.freeze(VaultCredentialMutationValidation.requireValid(active, proposal))
            mutation.close(); password.close()
            assertEquals(VaultBiometricState.REVOKED, revoked.data!!.biometricState)
            assertEquals(active.data!!.slots, revoked.data!!.slots)
            assertEquals(active.data!!.mediaJournalEnvelope, revoked.data!!.mediaJournalEnvelope)
            val nextPassword = f.seedPasswordRoot(revoked)
            val permit = f.owner.prepareBiometricAliasRetirement(nextPassword, revoked, attempt,
                f.binding(VaultBiometricPurpose.RETIRE_ALIAS), f.context)
            assertEquals(VaultBiometricAliasRetirementResult.ABSENT, f.owner.retireBiometricAlias(revoked, permit, f.context))
            assertEquals(1, f.alias.deletes)
            assertFalse(identity.biometricKeyAlias in f.alias.aliases)
            rejected { f.owner.retireBiometricAlias(revoked, permit, f.context) }
            nextPassword.close()
        }
    }


    @Test fun enrollmentClosedDuringLedgerVerificationCannotIssueSuccessorOperation() {
        Fixture().use { f ->
            val (enroll, staged) = f.stage()
            val preparedBefore = f.leaf.pending.size
            f.beforeDeviceDecrypt = { enroll.close() }
            rejected { f.owner.prepareBiometricActivation(staged, enroll,
                f.binding(VaultBiometricPurpose.ENROLL_VERIFY), f.context) }
            assertEquals(preparedBefore, f.leaf.pending.size)
            assertEquals(0, f.leaf.decrypts)
        }
    }
    @Test fun closedAliasPermitCannotBeConsumedOrDeleteTheExactAlias() {
        Fixture().use { f ->
            f.alias.aliases += identity.biometricKeyAlias
            val permit = f.owner.prepareBiometricAliasRetirement(f.root, f.before, attempt,
                f.binding(VaultBiometricPurpose.RETIRE_ALIAS), f.context)
            permit.close()
            rejected { f.owner.retireBiometricAlias(f.before, permit, f.context) }
            assertEquals(0, f.alias.deletes)
            assertTrue(identity.biometricKeyAlias in f.alias.aliases)
        }
    }

    @Test fun authenticatedNonePermitIsExactOneShotAndUnknownCannotRefreshOrCreate() {
        Fixture().use { f ->
            f.alias.aliases += identity.biometricKeyAlias
            val permission = f.owner.prepareBiometricAliasRetirement(f.root, f.before, attempt,
                f.binding(VaultBiometricPurpose.RETIRE_ALIAS), f.context)
            rejected { f.owner.retireBiometricAlias(f.before.copy(storeRevision = 9), permission, f.context) }
            assertEquals(0, f.alias.deletes)
            f.alias.failDelete = true
            assertEquals(VaultBiometricAliasRetirementResult.UNKNOWN, f.owner.retireBiometricAlias(f.before, permission, f.context))
            permission.close()
            rejected { f.owner.prepareBiometricEnrollment(f.root, f.before, attempt, f.binding(VaultBiometricPurpose.ENROLL_WRAP), f.context) }
            rejected { f.owner.prepareBiometricAliasRetirement(f.root, f.before, attempt, f.binding(VaultBiometricPurpose.RETIRE_ALIAS), f.context) }
            assertEquals(0, f.creates)
            assertEquals(VaultBiometricAliasRetirementResult.UNKNOWN, f.owner.retireBiometricAlias(f.before, permission, f.context))
            assertEquals(1, f.alias.deletes) // Reconciliation reads only; it never retries deletion.
            f.alias.aliases.clear()
            assertEquals(VaultBiometricAliasRetirementResult.ABSENT, f.owner.retireBiometricAlias(f.before, permission, f.context))
            rejected { f.owner.retireBiometricAlias(f.before, permission, f.context) }
        }
    }
}

package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.app.vault.storage.VaultCredentialMutationValidation
import com.easysavevideo.app.vault.storage.VaultSnapshotCodec
import com.easysavevideo.core.model.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import com.google.crypto.tink.KeysetHandle
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.GeneralSecurityException
import org.junit.Assert.*
import org.junit.Test

/** Executes the production reseal/opaque-registry/store-validator seam with the existing
 * public AES-GCM keyset vector. No generated keys, Android KeyStore, password KDF or user
 * credentials. Reflection reaches existing private helpers without adding a production seam.
 * Slot, marker, ledger and media-key envelopes are public opaque preservation fixtures;
 * these tests do not claim to authenticate those envelopes or execute SQLite/device flows.
 */
class VaultCredentialResealConnectionTest {
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
    private val attempt = VaultAttemptIdentity(41, 3, 2)
    private val ledger = VaultRetryLedger(1, 1, 0, 4)
    private val reservation = VaultRetryWrite(VaultRetryWriteKind.RESERVATION,
        VaultRetryWriteIdentity(attempt, 4), 0, ledger, VaultTick(41, 10_000))
    private fun blob(n: Int) = VaultCiphertext(ByteArray(16) { (n + it).toByte() })
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Identical public encrypted keyset and known ciphertext to VaultTinkFixedVectorTest.
    private val publicKeyset = hex("128201000102030405060708090a0b80193a4061f7d7f3f20c2b95982db9633b0bc4ffdeede685e1a88b66a25260008b9bd98b2f15488c4cbfe3fec7d3252c7591e4bd84e0c6bbd5eb7f59328ab2a48330efd5944813a64dd7a49bfe48ad256ce1f216b91dcd5a2cceb27333c68006a0186928f4b21de8bcb85e920d76231ec7a282d79baa")
    private val publicChallenge = hex("01000004d2202122232425262728292a2ba24fc41c05fb376b770c36b7ec7b958db12583fbdeab01fcdee433463feee486bb31022a")
    private fun fixedKeyset(): KeysetHandle = VaultJcaAead.fromOwnedBytes(ByteArray(32)).use {
        VaultTinkKeysets.unwrap(publicKeyset, it, "ESV-PUBLIC-FIXED-KEYSET-VECTOR".toByteArray())
    }
    private fun primary(generation: Long = 1, envelope: VaultCiphertext = blob(1)) =
        VaultSlotRecord("password-v1", generation, VaultSlotKind.PASSWORD, envelope, true)
    private fun recovery(generation: Long = 2) =
        VaultSlotRecord("recovery-v1", generation, VaultSlotKind.RECOVERY, blob(2), true)
    private fun entries() = listOf(true, false).mapIndexed { index, cleanup ->
        VaultMediaEntry(VaultMediaObjectIdentity("public-object-$index", 1), 1,
            ExportEvidence(2, "0".repeat(64)), VaultCiphertextEvidence(58, "1".repeat(64)),
            blob(20 + index), "public-$index.mp4", 5, 2, 2,
            VaultMediaOperation("public-move-$index", index + 1L, VaultMediaKind.MOVE_IN),
            MediaReadRef("public-source-$index", 1, 1), sourceCleanupPending = cleanup)
    }

    private class Clock(var now: Long = 0) : VaultMonotonicClock {
        override fun elapsedMillis() = now
    }
    private inner class Fixture(kind: VaultCredentialMutationKind) : AutoCloseable {
        val owner = AndroidVaultAuthentication()
        val clock = Clock()
        val context = VaultOperationContext(1_000, clock)
        // Both roles intentionally use the existing public test key, never generated keys.
        val root = fixedKeyset()
        val catalog = fixedKeyset()
        val media = entries()
        val before: VaultStoredSnapshot
        init {
            val revision = when (kind) {
                VaultCredentialMutationKind.STAGE_RECOVERY -> 1L
                VaultCredentialMutationKind.ACTIVATE_RECOVERY -> 2L
                VaultCredentialMutationKind.STAGE_PASSWORD_RESET -> 3L
                VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET -> 4L
                else -> error("recovery-fixture-kind")
            }
            val state = when (kind) {
                VaultCredentialMutationKind.STAGE_RECOVERY -> VaultRecoveryState.NONE
                VaultCredentialMutationKind.ACTIVATE_RECOVERY -> VaultRecoveryState.PENDING
                else -> VaultRecoveryState.ACTIVE
            }
            val slots = listOf(primary()) + if (state == VaultRecoveryState.NONE) emptyList() else listOf(recovery())
            val data = VaultStagedData(slots, blob(3), blob(4), revision, blob(5), ledger, blob(6), state,
                if (kind == VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET)
                    VaultPendingPassword(primary(4, blob(7)), attempt, ledger) else null,
                mediaJournalEnvelope = blob(8), mediaJournalRevision = 2)
            val plain = VaultMediaCatalogWire.encode(identity, data, media)
            val encrypted = try { VaultTinkKeysets.primitive(catalog).encrypt(plain,
                VaultCryptoWire.aad(identity, VaultAadPurpose.CATALOG, revision = revision)) }
                finally { plain.fill(0) }
            val wrapped = VaultTinkKeysets.wrap(catalog, VaultTinkKeysets.primitive(root),
                VaultCryptoWire.aad(identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = revision))
            before = VaultSnapshotCodec.freeze(VaultStoredSnapshot(identity, VaultStorePhase.READY, 9,
                data.copy(catalogKeysetEnvelope = VaultCiphertext(wrapped), catalogEnvelope = VaultCiphertext(encrypted))))
        }
        /** Only an unmodified production reseal result can enter the real private registry here. */
        fun mutation(kind: VaultCredentialMutationKind, operation: VaultOperationContext = context): VaultCredentialMutation {
            val data = before.data!!
            val revision = data.catalogRevision + 1
            val changed = when (kind) {
                VaultCredentialMutationKind.STAGE_RECOVERY -> data.copy(slots = listOf(primary(), recovery(revision)),
                    recoveryState = VaultRecoveryState.PENDING)
                VaultCredentialMutationKind.ACTIVATE_RECOVERY -> data.copy(recoveryState = VaultRecoveryState.ACTIVE)
                VaultCredentialMutationKind.STAGE_PASSWORD_RESET -> data.copy(pendingPassword =
                    VaultPendingPassword(primary(revision, blob(9)), attempt, ledger))
                VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET -> data.copy(slots = listOf(data.pendingPassword!!.slot),
                    pendingPassword = null, recoveryState = VaultRecoveryState.NONE)
                else -> error("recovery-fixture-kind")
            }.copy(catalogRevision = revision)
            val resealed = reseal(owner, root, before, changed, operation)
            return invoke(registerMethod, owner, before, resealed, kind,
                if (kind == VaultCredentialMutationKind.STAGE_PASSWORD_RESET ||
                    kind == VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET) attempt else null, operation) as VaultCredentialMutation
        }
        override fun close() { owner.close() }
    }

    private val resealMethod = AndroidVaultAuthentication::class.java.getDeclaredMethod("resealCatalog",
        KeysetHandle::class.java, VaultStoredSnapshot::class.java, VaultStagedData::class.java, VaultOperationContext::class.java)
        .apply { isAccessible = true }
    private val registerMethod = AndroidVaultAuthentication::class.java.getDeclaredMethod("registerMutation",
        VaultStoredSnapshot::class.java, VaultStagedData::class.java, VaultCredentialMutationKind::class.java,
        VaultAttemptIdentity::class.java, VaultOperationContext::class.java).apply { isAccessible = true }
    private val verifyMethod = AndroidVaultAuthentication::class.java.getDeclaredMethod("verifyCatalog",
        KeysetHandle::class.java, VaultStoredSnapshot::class.java, VaultOperationContext::class.java).apply { isAccessible = true }
    private fun invoke(method: Method, owner: AndroidVaultAuthentication, vararg arguments: Any?): Any? =
        try { method.invoke(owner, *arguments) } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun reseal(owner: AndroidVaultAuthentication, root: KeysetHandle, before: VaultStoredSnapshot,
        changed: VaultStagedData, context: VaultOperationContext) =
        invoke(resealMethod, owner, root, before, changed, context) as VaultStagedData
    private fun verify(fixture: Fixture, snapshot: VaultStoredSnapshot) =
        invoke(verifyMethod, fixture.owner, fixture.root, snapshot, fixture.context) as VaultAuthenticatedCatalog

    private fun assertSamePublicKey(handle: KeysetHandle) {
        val plain = VaultTinkKeysets.primitive(handle).decrypt(publicChallenge,
            "ESV-PUBLIC-FIXED-CATALOG-VECTOR".toByteArray())
        try { assertArrayEquals("public-empty-catalog".toByteArray(), plain) } finally { plain.fill(0) }
    }
    private fun accept(fixture: Fixture, mutation: VaultCredentialMutation): Pair<VaultCredentialMutationProposal, VaultStoredSnapshot> {
        val proposal = fixture.owner.validateCredentialMutation(fixture.before, mutation, fixture.context.bounded(500))
        // This is the same production owner-first ordering as both AndroidVaultStore CAS methods.
        return proposal to VaultCredentialMutationValidation.requireValid(fixture.before, proposal, reservation)
    }
    private fun assertResealAccepted(kind: VaultCredentialMutationKind) {
        Fixture(kind).use { fixture -> fixture.mutation(kind).use { mutation ->
            val (proposal, after) = accept(fixture, mutation)
            val old = fixture.before.data!!
            val changed = after.data!!
            assertEquals(kind, proposal.kind)
            assertEquals(fixture.before.storeRevision + 1, after.storeRevision)
            assertEquals(old.catalogRevision + 1, changed.catalogRevision)
            // Inequality is a regression observation, not the proof: authenticated unwrap,
            // the independent public key challenge and actual catalog decode follow below.
            assertNotEquals(old.catalogKeysetEnvelope, changed.catalogKeysetEnvelope)
            assertNotEquals(old.catalogEnvelope, changed.catalogEnvelope)
            val key = VaultTinkKeysets.unwrap(changed.catalogKeysetEnvelope.copyBytes(),
                VaultTinkKeysets.primitive(fixture.root), VaultCryptoWire.aad(identity,
                    VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = changed.catalogRevision))
            assertSamePublicKey(fixture.catalog); assertSamePublicKey(key)
            val catalog = verify(fixture, after)
            assertEquals(changed.catalogRevision, catalog.catalogRevision)
            assertEquals(fixture.media, catalog.entries)
            assertEquals(fixture.media.map { it.wrappedKeyset }, catalog.entries.map { it.wrappedKeyset })
            assertEquals(listOf(true, false), catalog.entries.map { it.sourceCleanupPending })
            assertEquals(old.installationMarker, changed.installationMarker)
            assertEquals(old.retryLedger, changed.retryLedger)
            assertEquals(old.retryLedgerEnvelope, changed.retryLedgerEnvelope)
            assertEquals(old.mediaJournalEnvelope, changed.mediaJournalEnvelope)
            assertEquals(old.mediaJournalRevision, changed.mediaJournalRevision)
            when (kind) {
                VaultCredentialMutationKind.STAGE_RECOVERY -> {
                    assertEquals(VaultRecoveryState.PENDING, changed.recoveryState)
                    assertEquals(old.slots.single(), changed.slots.single { it.kind == VaultSlotKind.PASSWORD })
                    assertEquals(changed.catalogRevision, changed.slots.single { it.kind == VaultSlotKind.RECOVERY }.generation)
                }
                VaultCredentialMutationKind.ACTIVATE_RECOVERY -> {
                    assertEquals(VaultRecoveryState.ACTIVE, changed.recoveryState); assertEquals(old.slots, changed.slots)
                }
                VaultCredentialMutationKind.STAGE_PASSWORD_RESET -> {
                    assertEquals(old.slots, changed.slots); assertEquals(ledger, changed.pendingPassword!!.reservation)
                    assertEquals(attempt, changed.pendingPassword!!.attempt)
                }
                VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET -> {
                    assertEquals(listOf(old.pendingPassword!!.slot), changed.slots)
                    assertNull(changed.pendingPassword); assertEquals(VaultRecoveryState.NONE, changed.recoveryState)
                }
                else -> error("recovery-fixture-kind")
            }
        } }
    }
    @Test fun stageRecoveryAcceptsProductionResealAndPreservesAuthenticatedMedia() =
        assertResealAccepted(VaultCredentialMutationKind.STAGE_RECOVERY)
    @Test fun activateRecoveryAcceptsProductionResealAndPreservesAuthenticatedMedia() =
        assertResealAccepted(VaultCredentialMutationKind.ACTIVATE_RECOVERY)
    @Test fun stagePasswordResetAcceptsProductionResealAndPreservesAuthenticatedMedia() =
        assertResealAccepted(VaultCredentialMutationKind.STAGE_PASSWORD_RESET)
    @Test fun activatePasswordResetAcceptsProductionResealAndPreservesAuthenticatedMedia() =
        assertResealAccepted(VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET)

    private fun rejectsCrypto(body: () -> Unit) {
        try { body(); fail("Expected authenticated rejection") } catch (_: GeneralSecurityException) { }
    }
    private fun rejectsShape(body: () -> Unit) {
        try { body(); fail("Expected shape rejection") } catch (_: IllegalArgumentException) { }
    }
    private fun fails(category: VaultFailure, body: () -> Unit) {
        try { body(); fail("Expected owner rejection") }
        catch (failure: VaultOperationException) { assertEquals(category, failure.category) }
    }
    @Test fun oldEnvelopeAtNewRevisionAndWrongAadFailActualAuthenticationForEveryMutation() {
        VaultCredentialMutationKind.entries.filter { it.ordinal <= VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET.ordinal }.forEach { kind -> Fixture(kind).use { fixture ->
            fixture.mutation(kind).use { mutation ->
                val (_, after) = accept(fixture, mutation)
                val old = fixture.before.data!!
                val changed = after.data!!
                rejectsCrypto { verify(fixture, after.copy(data = changed.copy(catalogKeysetEnvelope = old.catalogKeysetEnvelope))) }
                rejectsCrypto { verify(fixture, after.copy(data = changed.copy(catalogEnvelope = old.catalogEnvelope))) }
                rejectsCrypto { verify(fixture, after.copy(identity = identity.copy(installationId = "foreign-install"))) }
                for (aad in listOf(
                    VaultCryptoWire.aad(identity, VaultAadPurpose.ROOT_CATALOG_KEYSET, revision = old.catalogRevision),
                    VaultCryptoWire.aad(identity, VaultAadPurpose.CATALOG, revision = changed.catalogRevision),
                    VaultCryptoWire.aad(identity.copy(vaultId = "foreign-vault"), VaultAadPurpose.ROOT_CATALOG_KEYSET,
                        revision = changed.catalogRevision))) {
                    rejectsCrypto { VaultTinkKeysets.unwrap(changed.catalogKeysetEnvelope.copyBytes(),
                        VaultTinkKeysets.primitive(fixture.root), aad) }
                }
            }
        } }
    }
    @Test fun realRegistryRejectsForeignOwnerForeignMutationAndEveryChangedBeforeBinding() {
        val kind = VaultCredentialMutationKind.STAGE_RECOVERY
        Fixture(kind).use { fixture -> fixture.mutation(kind).use { mutation ->
            AndroidVaultAuthentication().use { foreignOwner ->
                fails(VaultFailure.REJECTED) { foreignOwner.validateCredentialMutation(fixture.before, mutation, fixture.context) }
            }
            val forged = object : VaultCredentialMutation { override fun close() {} }
            fails(VaultFailure.REJECTED) { fixture.owner.validateCredentialMutation(fixture.before, forged, fixture.context) }
            val before = fixture.before; val data = before.data!!
            val stale = listOf(before.copy(storeRevision = before.storeRevision + 1),
                before.copy(identity = identity.copy(installationId = "foreign-install")),
                before.copy(data = data.copy(catalogKeysetEnvelope = blob(41))),
                before.copy(data = data.copy(catalogEnvelope = blob(46))),
                before.copy(data = data.copy(catalogRevision = data.catalogRevision + 1)),
                before.copy(data = data.copy(installationMarker = blob(42))),
                before.copy(data = data.copy(retryLedger = ledger.copy(ledgerRevision = 2))),
                before.copy(data = data.copy(retryLedgerEnvelope = blob(43))),
                before.copy(data = data.copy(mediaJournalEnvelope = blob(44))),
                before.copy(data = data.copy(mediaJournalRevision = data.mediaJournalRevision + 1)),
                before.copy(data = data.copy(slots = listOf(primary(envelope = blob(45))))))
            stale.forEach { changed -> fails(VaultFailure.CONFLICT) {
                fixture.owner.validateCredentialMutation(changed, mutation, fixture.context)
            } }
            // Rejected substitutions do not alter the original owner's immutable proposal.
            accept(fixture, mutation)
        } }
    }
    @Test fun originalLineageCancellationDeadlineAndRetirementRemainAuthoritative() {
        val kind = VaultCredentialMutationKind.STAGE_RECOVERY
        Fixture(kind).use { fixture ->
            val original = fixture.context.bounded(200)
            fixture.mutation(kind, original).use { mutation ->
                fixture.owner.validateCredentialMutation(fixture.before, mutation, original.bounded(100))
                fails(VaultFailure.REJECTED) { fixture.owner.validateCredentialMutation(fixture.before, mutation,
                    VaultOperationContext(100, fixture.clock)) }
                fails(VaultFailure.REJECTED) { fixture.owner.validateCredentialMutation(fixture.before, mutation, fixture.context) }
                original.cancel()
                fails(VaultFailure.CANCELLED) { fixture.owner.validateCredentialMutation(fixture.before, mutation, original) }
                fails(VaultFailure.CANCELLED) { fixture.owner.validateCredentialMutation(fixture.before, mutation,
                    VaultOperationContext(100, fixture.clock)) }
            }
        }
        Fixture(kind).use { fixture ->
            val original = fixture.context.bounded(200)
            fixture.mutation(kind, original).use { mutation ->
                fixture.clock.now = 200
                fails(VaultFailure.DEADLINE) { fixture.owner.validateCredentialMutation(fixture.before, mutation, fixture.context) }
                fails(VaultFailure.DEADLINE) { fixture.owner.validateCredentialMutation(fixture.before, mutation,
                    VaultOperationContext(1_000, fixture.clock)) }
            }
        }
        Fixture(kind).use { fixture ->
            val mutation = fixture.mutation(kind)
            mutation.close()
            fails(VaultFailure.REJECTED) { fixture.owner.validateCredentialMutation(fixture.before, mutation, fixture.context) }
            fixture.owner.close()
            fails(VaultFailure.CANCELLED) { fixture.owner.validateCredentialMutation(fixture.before, mutation, fixture.context) }
        }
    }
    @Test fun shapeValidationStillRejectsMarkerLedgerJournalRevisionAndNonTargetSlotChanges() {
        VaultCredentialMutationKind.entries.filter { it.ordinal <= VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET.ordinal }.forEach { kind -> Fixture(kind).use { fixture ->
            fixture.mutation(kind).use { mutation ->
                val (proposal, _) = accept(fixture, mutation)
                val data = proposal.data
                // These detached DTOs have no opaque registry authority. Exercise only the
                // remaining necessary shape checks, separately from the owner tests above.
                val invalid = listOf(data.copy(installationMarker = blob(51)),
                    data.copy(retryLedger = VaultRetryLedger(2, 0, -1, null)),
                    data.copy(retryLedgerEnvelope = blob(52)), data.copy(mediaJournalEnvelope = blob(53)),
                    data.copy(mediaJournalRevision = data.mediaJournalRevision + 1),
                    data.copy(catalogRevision = data.catalogRevision + 1),
                    data.copy(catalogEnvelope = fixture.before.data!!.catalogEnvelope),
                    data.copy(slots = data.slots.map { if (it.kind == VaultSlotKind.PASSWORD) it.copy(envelope = blob(54)) else it }))
                invalid.forEach { changed -> rejectsShape {
                    VaultCredentialMutationValidation.requireValid(fixture.before,
                        VaultCredentialMutationProposal(kind, changed, proposal.attempt, proposal.operationId), reservation)
                } }
                assertEquals(proposal.data, fixture.owner.validateCredentialMutation(fixture.before, mutation, fixture.context).data)
            }
        } }
    }
}

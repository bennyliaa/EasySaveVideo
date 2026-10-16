package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultAttemptIdentity
import com.easysavevideo.core.model.VaultRetryLedger
import org.junit.Assert.*
import org.junit.Test
import java.security.GeneralSecurityException

/** Public fixed vectors and bounded metadata/fake capabilities only. No production setup,
 * real authentication, random generation/encryption, AndroidKeyStore, database or device run.
 */
class VaultRecoveryConnectionCryptoTest {
    private val identity = VaultPersistentIdentity("public-vault", "public-install", "public-init",
        "esv.vault.v1.public-init.device", "esv.vault.v1.public-init.biometric")
    private val primary = VaultSlotRecord("password-v1", 1, VaultSlotKind.PASSWORD, cipher(1), true)
    private val recovery = VaultSlotRecord("recovery-v1", 2, VaultSlotKind.RECOVERY, cipher(2), true)
    private val attempt = VaultAttemptIdentity(1, 2, 3)
    private val reservation = VaultRetryLedger(4, 1, 0, 5)
    private fun cipher(value: Int) = VaultCiphertext(byteArrayOf(value.toByte()))
    private fun none() = VaultStagedData(listOf(primary), cipher(3), cipher(4), 1, cipher(5),
        VaultRetryLedger(0, 0, -1, null), cipher(6))
    private fun pending() = none().copy(slots = listOf(primary, recovery), catalogRevision = 2,
        recoveryState = VaultRecoveryState.PENDING)
    private fun active() = pending().copy(catalogRevision = 3, recoveryState = VaultRecoveryState.ACTIVE,
        retryLedger = reservation)
    private fun resetStaged() = active().copy(catalogRevision = 4, pendingPassword = VaultPendingPassword(
        primary.copy(generation = 4, envelope = cipher(7)), attempt, reservation))
    private fun snapshot(data: VaultStagedData) = VaultStoredSnapshot(identity, VaultStorePhase.READY, 9, data)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected metadata rejection") } catch (_: IllegalArgumentException) { }
    }
    private fun failure(category: VaultFailure, block: () -> Unit) {
        try { block(); fail("Expected rejected operation") }
        catch (failure: VaultOperationException) { assertEquals(category, failure.category) }
    }

    @Test fun originalPasswordOnlyCatalogRetainsIndependentLegacyVectorBytes() {
        val actual = VaultCryptoWire.emptyCatalog(identity, none())
        assertArrayEquals(VaultCryptoWire.emptyCatalog(identity, primary, 1), actual)
        assertEquals("82eb1af97eca8e5a79001b851bce16f5ce2189cf843905f5840eb12e3c0ea03e", hex(VaultCryptoWire.digest(actual)))
        val pin = primary.copy(kind = VaultSlotKind.PIN)
        assertArrayEquals(VaultCryptoWire.emptyCatalog(identity, pin, 1),
            VaultCryptoWire.emptyCatalog(identity, none().copy(slots = listOf(pin))))
    }

    @Test fun pendingCatalogMatchesIndependentPublicCanonicalVector() {
        assertEquals("079811478826f686cb3a4da08ccf2897a042644efb8387809a430c0e4f0a31c6",
            hex(VaultCryptoWire.digest(VaultCryptoWire.emptyCatalog(identity, pending()))))
        assertArrayEquals(VaultCryptoWire.emptyCatalog(identity, pending()),
            VaultCryptoWire.emptyCatalog(identity, pending().copy(slots = listOf(recovery, primary))))
    }

    @Test fun extendedCatalogBindsStateAndEveryActiveSlotDimension() {
        val base = VaultCryptoWire.emptyCatalog(identity, pending())
        val changes = listOf(
            pending().copy(recoveryState = VaultRecoveryState.ACTIVE),
            pending().copy(slots = listOf(primary.copy(envelope = cipher(10)), recovery)),
            pending().copy(slots = listOf(primary.copy(generation = 2), recovery)),
            pending().copy(slots = listOf(primary.copy(kind = VaultSlotKind.PIN), recovery)),
            pending().copy(slots = listOf(primary, recovery.copy(envelope = cipher(10)))),
            pending().copy(slots = listOf(primary, recovery.copy(generation = 1))),
            pending().copy(slots = listOf(primary, recovery.copy(slotId = "different-recovery"))),
            pending().copy(catalogRevision = 3),
        )
        changes.forEach { assertFalse(base.contentEquals(VaultCryptoWire.emptyCatalog(identity, it))) }
    }

    @Test fun pendingPasswordCatalogBindsEnvelopeAttemptAndCreatingReservation() {
        val original = resetStaged()
        val record = original.pendingPassword!!
        val base = VaultCryptoWire.emptyCatalog(identity, original)
        val records = listOf(record.copy(slot = record.slot.copy(envelope = cipher(20))),
            record.copy(slot = record.slot.copy(generation = 3)),
            record.copy(attempt = attempt.copy(processInstance = 2)),
            record.copy(attempt = attempt.copy(attemptId = 3)),
            record.copy(attempt = attempt.copy(authEpoch = 4)),
            record.copy(reservation = reservation.copy(ledgerRevision = 5)),
            record.copy(reservation = reservation.copy(reservedAttemptCount = 2, delayTier = 1)),
            record.copy(reservation = reservation.copy(outstandingReservationId = 6)))
        records.forEach { assertFalse(base.contentEquals(VaultCryptoWire.emptyCatalog(identity, original.copy(pendingPassword = it)))) }
        assertFalse(base.contentEquals(VaultCryptoWire.emptyCatalog(identity, original.copy(pendingPassword = null))))
    }

    @Test fun stalePendingPasswordDoesNotInvalidateNormalPrimaryShapeOrCatalog() {
        val original = resetStaged()
        val stale = original.copy(retryLedger = VaultRetryLedger(6, 2, 1, 7), retryLedgerEnvelope = cipher(30))
        VaultCryptoWire.requireCredentialShape(stale)
        assertArrayEquals(VaultCryptoWire.emptyCatalog(identity, original), VaultCryptoWire.emptyCatalog(identity, stale))
        assertArrayEquals(VaultCryptoWire.catalogFingerprint(snapshot(original)), VaultCryptoWire.catalogFingerprint(snapshot(stale)))
        assertFalse(VaultCryptoWire.snapshotFingerprint(snapshot(original)).contentEquals(VaultCryptoWire.snapshotFingerprint(snapshot(stale))))
        assertEquals(primary, stale.slots.single { it.kind == VaultSlotKind.PASSWORD })
    }

    @Test fun snapshotAndCatalogFingerprintsBindRecoveryAndPendingMetadata() {
        val original = resetStaged()
        val record = original.pendingPassword!!
        val changes = listOf(original.copy(pendingPassword = null),
            original.copy(pendingPassword = record.copy(attempt = attempt.copy(authEpoch = 99))),
            original.copy(pendingPassword = record.copy(reservation = reservation.copy(outstandingReservationId = 99))),
            original.copy(pendingPassword = record.copy(slot = record.slot.copy(envelope = cipher(11)))),
            original.copy(slots = listOf(primary, recovery.copy(envelope = cipher(12)))))
        for (data in changes) {
            assertFalse(VaultCryptoWire.snapshotFingerprint(snapshot(original)).contentEquals(VaultCryptoWire.snapshotFingerprint(snapshot(data))))
            assertFalse(VaultCryptoWire.catalogFingerprint(snapshot(original)).contentEquals(VaultCryptoWire.catalogFingerprint(snapshot(data))))
        }
        assertFalse(VaultCryptoWire.catalogFingerprint(snapshot(pending())).contentEquals(
            VaultCryptoWire.catalogFingerprint(snapshot(pending().copy(recoveryState = VaultRecoveryState.ACTIVE)))))
    }

    @Test fun allFingerprintInputsValidateIdentityBeforeVariableLengthEncoding() {
        val badIdentities = listOf(identity.copy(vaultId = "a".repeat(65)), identity.copy(installationId = "a".repeat(65)),
            identity.copy(initializationOperationId = "a".repeat(65)), identity.copy(deviceKeyAlias = "a".repeat(512)),
            identity.copy(biometricKeyAlias = "a".repeat(512)), identity.copy(rootVersion = 2), identity.copy(schemaVersion = 2))
        for (id in badIdentities) {
            rejects { VaultCryptoWire.snapshotFingerprint(snapshot(resetStaged()).copy(identity = id)) }
            rejects { VaultCryptoWire.catalogFingerprint(snapshot(resetStaged()).copy(identity = id)) }
            rejects { VaultCryptoWire.emptyCatalog(id, resetStaged()) }
        }
    }

    @Test fun impossibleRecoverySlotShapesAreRejectedBeforeAuthentication() {
        val invalid = listOf(none().copy(recoveryState = VaultRecoveryState.PENDING),
            none().copy(recoveryState = VaultRecoveryState.ACTIVE), pending().copy(recoveryState = VaultRecoveryState.NONE),
            pending().copy(slots = listOf(primary, recovery.copy(required = false))),
            pending().copy(slots = listOf(primary, recovery.copy(kind = VaultSlotKind.BIOMETRIC))),
            pending().copy(slots = listOf(primary, recovery.copy(generation = 3))),
            pending().copy(slots = listOf(primary, recovery.copy(slotId = "a".repeat(65)))),
            none().copy(slots = listOf(primary.copy(required = false))),
            none().copy(slots = listOf(primary.copy(slotId = "other"))),
            none().copy(slots = listOf(primary.copy(generation = 2))),
            none().copy(catalogRevision = Long.MAX_VALUE))
        invalid.forEach { rejects { VaultCryptoWire.requireCredentialShape(it) } }
    }

    @Test fun pendingPasswordShapeIsBoundedButCreatingReservationMayBeStale() {
        val original = resetStaged()
        val record = original.pendingPassword!!
        val invalid = listOf(record.copy(slot = record.slot.copy(kind = VaultSlotKind.PIN)),
            record.copy(slot = record.slot.copy(generation = 5)), record.copy(slot = record.slot.copy(required = false)),
            record.copy(slot = record.slot.copy(slotId = "a".repeat(65))),
            record.copy(attempt = attempt.copy(processInstance = 0)), record.copy(attempt = attempt.copy(attemptId = 0)),
            record.copy(attempt = attempt.copy(authEpoch = -1)), record.copy(reservation = VaultRetryLedger(0, 0, -1, null)),
            record.copy(reservation = reservation.copy(ledgerRevision = Long.MAX_VALUE)),
            record.copy(reservation = reservation.copy(outstandingReservationId = null)))
        invalid.forEach { rejects { VaultCryptoWire.requireCredentialShape(original.copy(pendingPassword = it)) } }
        rejects { VaultCryptoWire.requireCredentialShape(pending().copy(pendingPassword = record)) }
    }

    @Test fun resetAndReenrollmentPositiveGenerationsStayCanonical() {
        val completed = resetStaged().copy(slots = listOf(resetStaged().pendingPassword!!.slot),
            catalogRevision = 5, recoveryState = VaultRecoveryState.NONE, pendingPassword = null,
            retryLedger = VaultRetryLedger(5, 0, -1, null))
        val next = completed.copy(slots = completed.slots + recovery.copy(generation = 6),
            catalogRevision = 6, recoveryState = VaultRecoveryState.PENDING)
        VaultCryptoWire.requireCredentialShape(completed); VaultCryptoWire.requireCredentialShape(next)
        assertFalse(VaultCryptoWire.emptyCatalog(identity, completed).contentEquals(VaultCryptoWire.emptyCatalog(identity, next)))
        assertFalse(VaultCryptoWire.aad(identity, VaultAadPurpose.PASSWORD_ROOT, VaultSlotKind.PASSWORD,
            "password-v1", 1, salt = ByteArray(16)).contentEquals(
            VaultCryptoWire.aad(identity, VaultAadPurpose.PASSWORD_ROOT, VaultSlotKind.PASSWORD,
                "password-v1", 4, salt = ByteArray(16))))
    }

    @Test fun operationLineageIsInheritedOnlyThroughBoundingAndCancellationRemainsShared() {
        val original = VaultOperationContext(200, VaultMonotonicClock { 0 })
        val child = original.bounded(100)
        val sibling = original.bounded(50)
        val foreign = VaultOperationContext(100, VaultMonotonicClock { 0 })
        assertTrue(original.sharesOperation(child)); assertTrue(child.sharesOperation(sibling))
        assertFalse(original.sharesOperation(foreign))
        original.cancel()
        failure(VaultFailure.CANCELLED) { child.checkActive() }
        failure(VaultFailure.CANCELLED) { sibling.checkActive() }
        foreign.checkActive()
    }

    @Test fun foreignMutationAndForgedEnrollmentRootCannotAuthorizeAnyPlatformOperation() {
        val owner = AndroidVaultAuthentication()
        val context = VaultOperationContext(100, VaultMonotonicClock { 0 })
        val mutation = object : VaultCredentialMutation { override fun close() {} }
        val root = object : VaultRootCapability { override fun close() {} }
        failure(VaultFailure.REJECTED) { owner.validateCredentialMutation(snapshot(none()), mutation, context) }
        failure(VaultFailure.REJECTED) { owner.prepareRecoveryEnrollment(root, snapshot(none()), context) }
        owner.close()
        failure(VaultFailure.CANCELLED) { owner.validateCredentialMutation(snapshot(none()), mutation, context) }
    }

    @Test fun incorrectRecoveryLifecycleRejectsBeforeTouchingAndroidOrKdf() {
        val owner = AndroidVaultAuthentication()
        val context = VaultOperationContext(100, VaultMonotonicClock { 0 })
        VaultRecoveryInput(CharArray(43) { 'A' }).use { code ->
            failure(VaultFailure.REJECTED) { owner.verifyPendingRecovery(snapshot(none()), code, context) }
            failure(VaultFailure.REJECTED) { owner.prepareRecoveryActivation(snapshot(active()), code, context) }
            VaultCredentialInput(VaultCredentialKind.PASSWORD, "public-password-123".toCharArray()).use { password ->
                failure(VaultFailure.REJECTED) { owner.preparePasswordReset(snapshot(none()), code, password, attempt, context) }
                failure(VaultFailure.CONFLICT) { owner.verifyPendingPasswordReset(snapshot(resetStaged()), password,
                    attempt.copy(attemptId = 9), context) }
                failure(VaultFailure.CONFLICT) { owner.verifyPendingPasswordReset(snapshot(resetStaged().copy(
                    retryLedger = reservation.copy(ledgerRevision = 9))), password, attempt, context) }
            }
        }
        owner.close()
    }

    @Test fun rejectedLastAcceptanceClosesMutationAndDisplayTogether() {
        var closed = false
        val mutation = object : VaultCredentialMutation { override fun close() { closed = true } }
        val holder = VaultRecoveryInput(CharArray(43) { 'A' })
        val enrollment = VaultRecoveryEnrollment(mutation, holder)
        failure(VaultFailure.DEADLINE) {
            finishVaultCryptoResult(enrollment, VaultOperationContext(100, VaultMonotonicClock { 100 })) {}
        }
        assertTrue(closed)
        failure(VaultFailure.CANCELLED) { holder.copyCharacters() }
        failure(VaultFailure.CANCELLED) { enrollment.takeDisplay() }
    }

    @Test fun displayHasOneExplicitOwnerAndRejectedTransferredHolderCloses() {
        var closed = false
        val mutation = object : VaultCredentialMutation { override fun close() { closed = true } }
        val source = CharArray(43) { 'A' }
        val holder = VaultRecoveryInput(source)
        source.fill('B')
        val enrollment = VaultRecoveryEnrollment(mutation, holder)
        val transferred = enrollment.takeDisplay()
        failure(VaultFailure.CANCELLED) { enrollment.takeDisplay() }
        enrollment.close(); assertTrue(closed)
        assertArrayEquals(CharArray(43) { 'A' }, transferred.copyCharacters())
        failure(VaultFailure.CANCELLED) { finishVaultCryptoResult(transferred,
            VaultOperationContext(100, VaultMonotonicClock { 0 })) { throw VaultOperationException(VaultFailure.CANCELLED) } }
        failure(VaultFailure.CANCELLED) { transferred.copyCharacters() }
        assertEquals("VaultRecoveryInput(REDACTED)", holder.toString())
    }

    private val recoveryVector = bytes("128201000102030405060708090a0b80193a4061f7d7f3f20c2b95982db9633b0bc4ffdeede685e1a88b66a25260008b9bd98b2f15488c4cbfe3fec7d3252c7591e4bd84e0c6bbd5eb7f59328ab2a48330efd5944813a64dd7a49bfe48ad256ce1f216b91dcd5a2cceb27333c68006a0186928f4b23c4ea4c58b5a04b8256d10a865456fd0")
    @Test fun fixedPublicRecoveryVectorAuthenticatesExistingLeafAndRootPrimitive() {
        val context = VaultOperationContext(100, VaultMonotonicClock { 0 })
        assertEquals("cdcf8c46d6dfe5794388ea0235b043f650107a28736c46e2abb9c61a4fb98ae5", hex(VaultCryptoWire.digest(
            VaultCryptoWire.aad(identity, VaultAadPurpose.RECOVERY_ROOT, VaultSlotKind.RECOVERY, "recovery-v1", 2))))
        val root = VaultRecoverySlot.unwrapPersisted(identity, "recovery-v1", 2,
            VaultCiphertext(recoveryVector), CharArray(43) { 'A' }, context)
        val plain = VaultTinkKeysets.primitive(root).decrypt(bytes("01000004d2202122232425262728292a2ba24fc41c05fb376b770c36b7ec7b958db12583fbdeab01fcdee433463feee486bb31022a"),
            "ESV-PUBLIC-FIXED-CATALOG-VECTOR".toByteArray())
        assertArrayEquals("public-empty-catalog".toByteArray(), plain)
        plain.fill(0)
    }

    @Test fun fixedPublicRecoveryVectorRejectsWrongSlotGenerationIdentityAndCode() {
        val context = VaultOperationContext(100, VaultMonotonicClock { 0 })
        val variants = listOf(Triple(identity, "recovery-v1", 3L), Triple(identity, "other", 2L),
            Triple(identity.copy(installationId = "other-install"), "recovery-v1", 2L))
        variants.forEach { (id, slot, generation) ->
            try { VaultRecoverySlot.unwrapPersisted(id, slot, generation, VaultCiphertext(recoveryVector), CharArray(43) { 'A' }, context); fail("AAD") }
            catch (_: GeneralSecurityException) {}
        }
        try { VaultRecoverySlot.unwrapPersisted(identity, "recovery-v1", 2, VaultCiphertext(recoveryVector),
            CharArray(43) { if (it == 0) 'B' else 'A' }, context); fail("Code") }
        catch (_: GeneralSecurityException) {}
        rejects { VaultRecoverySlot.unwrapPersisted(identity, "recovery-v1", 2, VaultCiphertext(recoveryVector),
            CharArray(43) { if (it == 42) 'B' else 'A' }, context) }
    }
}

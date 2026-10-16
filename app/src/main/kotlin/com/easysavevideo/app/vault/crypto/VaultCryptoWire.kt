package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.core.model.VaultAttemptIdentity
import com.easysavevideo.core.model.VaultRetryLedger
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

internal enum class VaultAadPurpose(val code: Int) {
    PASSWORD_ROOT(1), DEVICE_PASSWORD_OUTER(2), ROOT_CATALOG_KEYSET(3), CATALOG(4),
    INSTALLATION_MARKER(5), RETRY_LEDGER(6), BIOMETRIC_ROOT(7), RECOVERY_ROOT(8),
}

/** Only bounded canonical technical metadata. No caller path, arbitrary map or delimiter joining. */
internal object VaultCryptoWire {
    private const val PASSWORD_MAGIC = 0x45535031
    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val aliasPattern = Regex("esv\\.vault\\.v1\\.[A-Za-z0-9_.-]{1,144}")
    const val PASSWORD_SLOT = "password-v1"
    const val BIOMETRIC_SLOT = "biometric-v1"
    const val RECOVERY_SLOT = "recovery-v1"
    const val SLOT_GENERATION = 1L
    const val CATALOG_REVISION = 1L
    const val ALIAS_PREFIX = "esv.vault.v1."

    fun requireIdentity(identity: VaultPersistentIdentity) {
        require(idPattern.matches(identity.vaultId) && idPattern.matches(identity.installationId) &&
            idPattern.matches(identity.initializationOperationId)) { "identity-format" }
        require(identity.schemaVersion == 1 && identity.rootVersion == 1) { "unsupported-crypto-profile" }
        require(aliasPattern.matches(identity.deviceKeyAlias) && aliasPattern.matches(identity.biometricKeyAlias) &&
            identity.deviceKeyAlias != identity.biometricKeyAlias && identity.deviceKeyAlias.endsWith(".device") &&
            identity.biometricKeyAlias.endsWith(".biometric")) { "alias-format" }
    }

    fun aad(identity: VaultPersistentIdentity, purpose: VaultAadPurpose, kind: VaultSlotKind? = null,
        slotId: String = "none", generation: Long = 0, revision: Long = 0, salt: ByteArray? = null): ByteArray {
        requireIdentity(identity)
        require(idPattern.matches(slotId) && generation >= 0 && revision >= 0) { "binding-format" }
        require(salt == null || salt.size == 16) { "salt-size" }
        return encoded {
            writeInt(0x45535631); writeInt(1); writeInt(identity.schemaVersion); writeInt(1)
            writeInt(purpose.code); identity(identity); writeInt(identity.rootVersion)
            writeInt(when (kind) { null -> 0; VaultSlotKind.PIN -> 1; VaultSlotKind.PASSWORD -> 2;
                VaultSlotKind.BIOMETRIC -> 3; VaultSlotKind.RECOVERY -> 4 })
            text(slotId); writeLong(generation); writeLong(revision)
            writeInt(if (salt == null) 0 else 16)
            if (salt != null) { writeInt(600_000); writeInt(32); write(salt) }
        }
    }

    fun passwordEnvelope(salt: ByteArray, encryptedKeyset: ByteArray): ByteArray {
        require(salt.size == 16 && encryptedKeyset.isNotEmpty() && encryptedKeyset.size <= 16_384 - 36) { "envelope-size" }
        return encoded {
            writeInt(PASSWORD_MAGIC); writeInt(1); writeInt(600_000); writeInt(32); write(salt)
            writeInt(encryptedKeyset.size); write(encryptedKeyset)
        }
    }
    class ParsedPassword(val salt: ByteArray, val encryptedKeyset: ByteArray)
    fun parsePasswordEnvelope(bytes: ByteArray): ParsedPassword {
        require(bytes.size in 37..16_384) { "envelope-size" }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == PASSWORD_MAGIC) { "envelope-format" }
        val profile = buffer.int; val iterations = buffer.int; val keySize = buffer.int
        VaultCryptoProfile.requireKdfProfile(profile, iterations, 16, keySize)
        val salt = ByteArray(16); buffer.get(salt)
        val size = buffer.int
        require(size in 1..(16_384 - 36) && size == buffer.remaining()) { "envelope-size" }
        return ParsedPassword(salt, ByteArray(size).also { buffer.get(it) })
    }

    fun marker(identity: VaultPersistentIdentity): ByteArray = encoded {
        writeInt(0x45534d31); identity(identity)
    }
    fun emptyCatalog(identity: VaultPersistentIdentity, slot: VaultSlotRecord, revision: Long): ByteArray {
        requireIdentity(identity)
        require(revision == CATALOG_REVISION && slot.slotId == PASSWORD_SLOT &&
            slot.generation == SLOT_GENERATION && slot.required &&
            slot.kind in listOf(VaultSlotKind.PIN, VaultSlotKind.PASSWORD)) { "catalog-profile" }
        return encoded {
            writeInt(0x45534331); writeInt(1); identity(identity); writeInt(1); writeLong(revision)
            writeInt(1); text(slot.slotId); writeLong(slot.generation)
            writeInt(if (slot.kind == VaultSlotKind.PIN) 1 else 2)
            writeInt(0) // Recovery not provisioned. No enum can claim a usable slot.
            writeInt(0) // Empty catalog item count; no media schema is implemented.
        }
    }
    /** Legacy password-only generation/revision one retains its exact original bytes. All
     * extended catalogs bind ciphertext digests, recovery state and creating reset metadata.
     * Catalog/retry envelopes are intentionally outside this plaintext to avoid self-binding.
     */
    fun emptyCatalog(identity: VaultPersistentIdentity, data: VaultStagedData): ByteArray {
        requireIdentity(identity)
        requireCredentialShape(data)
        val primary = data.slots.single { it.kind == VaultSlotKind.PASSWORD || it.kind == VaultSlotKind.PIN }
        if (data.catalogRevision == CATALOG_REVISION && primary.generation == SLOT_GENERATION &&
            data.recoveryState == VaultRecoveryState.NONE && data.pendingPassword == null && data.biometricState == VaultBiometricState.NONE)
            return emptyCatalog(identity, primary, data.catalogRevision)
        if (data.biometricState != VaultBiometricState.NONE) return mediaCatalogPrefix(identity, data) + java.nio.ByteBuffer.allocate(4).putInt(0).array()
        return encoded {
            writeInt(0x45534331); writeInt(2); identity(identity); writeInt(1); writeLong(data.catalogRevision)
            val slots = data.slots.sortedBy { it.kind.ordinal }
            writeInt(slots.size)
            slots.forEach { slotDigest(it) }
            writeInt(data.recoveryState.ordinal)
            pendingPassword(data.pendingPassword, digestEnvelope = true)
            writeInt(0) // No media schema or root rotation is introduced.
        }
    }

    /** Canonical credential prefix shared by V3 media catalogs and credential-only reseals. */
    fun mediaCatalogPrefix(identity: VaultPersistentIdentity, data: VaultStagedData): ByteArray {
        requireIdentity(identity); requireCredentialShape(data)
        return encoded {
            writeInt(0x45534331); writeInt(if (data.biometricState == VaultBiometricState.NONE) 3 else 4); identity(identity); writeInt(1); writeLong(data.catalogRevision)
            val slots = data.slots.sortedBy { it.kind.ordinal }
            writeInt(slots.size); slots.forEach { slotDigest(it) }
            writeInt(data.recoveryState.ordinal); pendingPassword(data.pendingPassword, digestEnvelope = true)
            if (data.biometricState != VaultBiometricState.NONE) writeInt(data.biometricState.ordinal)
        }
    }

    /** Stale creating metadata remains valid shape: only reset activation compares current ledger. */
    fun requireCredentialShape(data: VaultStagedData) {
        require(data.catalogRevision in 1 until Long.MAX_VALUE) { "catalog-profile" }
        require(data.slots.size in 1..3 && data.slots.map { it.kind }.distinct().size == data.slots.size &&
            data.slots.map { it.slotId }.distinct().size == data.slots.size) { "slot-profile" }
        val primary = data.slots.singleOrNull { it.kind == VaultSlotKind.PASSWORD || it.kind == VaultSlotKind.PIN }
            ?: throw IllegalArgumentException("slot-profile")
        requirePrimarySlot(primary, data.catalogRevision)
        val recovery = data.slots.singleOrNull { it.kind == VaultSlotKind.RECOVERY }
        val biometric = data.slots.singleOrNull { it.kind == VaultSlotKind.BIOMETRIC }
        require((data.biometricState == VaultBiometricState.NONE) == (biometric == null)) { "biometric-profile" }
        biometric?.let {
            require(it.required && it.slotId == BIOMETRIC_SLOT && it.generation in 1..data.catalogRevision &&
                it.envelope.size <= VaultRuntimeLimits.ENVELOPE_BYTES) { "biometric-profile" }
        }
        require(data.slots.all { it === primary || it === recovery || it === biometric }) { "slot-profile" }
        require((data.recoveryState == VaultRecoveryState.NONE) == (recovery == null)) { "recovery-profile" }
        recovery?.let {
            require(it.required && idPattern.matches(it.slotId) && it.slotId != primary.slotId &&
                it.generation in 1..data.catalogRevision && it.envelope.size <= VaultRuntimeLimits.ENVELOPE_BYTES) { "recovery-profile" }
        }
        data.pendingPassword?.let {
            require(data.recoveryState == VaultRecoveryState.ACTIVE && it.slot.kind == VaultSlotKind.PASSWORD) { "pending-password-profile" }
            requirePrimarySlot(it.slot, data.catalogRevision)
            requireAttempt(it.attempt)
            requireLedger(it.reservation)
            require(it.reservation.reservedAttemptCount > 0 && it.reservation.outstandingReservationId != null) { "pending-password-reservation" }
        }
    }

    private fun requirePrimarySlot(slot: VaultSlotRecord, revision: Long) {
        require(slot.required && slot.slotId == PASSWORD_SLOT && slot.generation in 1..revision &&
            slot.kind in listOf(VaultSlotKind.PIN, VaultSlotKind.PASSWORD) &&
            slot.envelope.size <= VaultRuntimeLimits.ENVELOPE_BYTES) { "slot-profile" }
    }
    fun requireAttempt(attempt: VaultAttemptIdentity) {
        require(attempt.processInstance > 0 && attempt.attemptId > 0 && attempt.authEpoch >= 0) { "attempt-profile" }
    }
    fun ledger(ledger: VaultRetryLedger): ByteArray {
        requireLedger(ledger)
        val reservationId = ledger.outstandingReservationId
        return encoded {
            writeInt(0x45534c31); writeLong(ledger.ledgerRevision); writeLong(ledger.reservedAttemptCount)
            writeInt(ledger.delayTier); writeBoolean(reservationId != null)
            if (reservationId != null) writeLong(reservationId)
        }
    }
    fun requireLedger(ledger: VaultRetryLedger) {
        val reservationId = ledger.outstandingReservationId
        require(ledger.ledgerRevision in 0 until Long.MAX_VALUE && ledger.reservedAttemptCount in 0 until Long.MAX_VALUE &&
            ledger.delayTier == VaultRuntimeLimits.retryPolicy().tierFor(ledger.reservedAttemptCount) &&
            (reservationId == null || reservationId > 0)) { "ledger-format" }
        require((ledger.reservedAttemptCount == 0L) == (reservationId == null)) { "ledger-format" }
    }
    fun snapshotFingerprint(snapshot: VaultStoredSnapshot): ByteArray {
        requireIdentity(snapshot.identity)
        return digest(encoded {
            identity(snapshot.identity); writeInt(snapshot.phase.ordinal); writeLong(snapshot.storeRevision)
            val data = requireNotNull(snapshot.data) { "missing-staged-data" }
            requireCredentialShape(data)
            writeInt(data.slots.size)
            for (slot in data.slots.sortedBy { it.kind.ordinal }) { text(slot.slotId); writeLong(slot.generation); writeInt(slot.kind.ordinal)
                writeBoolean(slot.required); blob(slot.envelope.copyBytes()) }
            blob(data.catalogKeysetEnvelope.copyBytes()); blob(data.catalogEnvelope.copyBytes())
            writeLong(data.catalogRevision); blob(data.installationMarker.copyBytes())
            blob(ledger(data.retryLedger)); blob(data.retryLedgerEnvelope.copyBytes())
            writeInt(data.recoveryState.ordinal); pendingPassword(data.pendingPassword, digestEnvelope = false)
            if (data.biometricState != VaultBiometricState.NONE) { writeInt(0x45534231); writeInt(data.biometricState.ordinal) }
            // Legacy fingerprints retain their original bytes when no media journal exists.
            if (data.mediaJournalEnvelope != null) {
                writeInt(0x45534a31); writeLong(data.mediaJournalRevision)
                blob(data.mediaJournalEnvelope.copyBytes())
            } else require(data.mediaJournalRevision == 0L) { "journal-profile" }
        })
    }
    fun catalogFingerprint(snapshot: VaultStoredSnapshot): ByteArray {
        requireIdentity(snapshot.identity)
        return digest(encoded {
            identity(snapshot.identity)
            val data = requireNotNull(snapshot.data)
            requireCredentialShape(data)
            writeLong(data.catalogRevision); blob(data.catalogKeysetEnvelope.copyBytes()); blob(data.catalogEnvelope.copyBytes())
            for (slot in data.slots.sortedBy { it.kind.ordinal }) { text(slot.slotId); writeLong(slot.generation); writeInt(slot.kind.ordinal)
                writeBoolean(slot.required); blob(slot.envelope.copyBytes()) }
            writeInt(data.recoveryState.ordinal); pendingPassword(data.pendingPassword, digestEnvelope = false)
            if (data.biometricState != VaultBiometricState.NONE) { writeInt(0x45534231); writeInt(data.biometricState.ordinal) }
        })
    }
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun encoded(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { it.write() }; bytes.toByteArray()
    }
    private fun DataOutputStream.text(value: String) { val bytes = value.toByteArray(Charsets.US_ASCII); writeInt(bytes.size); write(bytes) }
    private fun DataOutputStream.blob(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
    private fun DataOutputStream.slotDigest(slot: VaultSlotRecord) {
        text(slot.slotId); writeLong(slot.generation); writeInt(slot.kind.ordinal); writeBoolean(slot.required)
        blob(digest(slot.envelope.copyBytes()))
    }
    private fun DataOutputStream.pendingPassword(value: VaultPendingPassword?, digestEnvelope: Boolean) {
        writeBoolean(value != null)
        if (value == null) return
        if (digestEnvelope) slotDigest(value.slot) else {
            text(value.slot.slotId); writeLong(value.slot.generation); writeInt(value.slot.kind.ordinal)
            writeBoolean(value.slot.required); blob(value.slot.envelope.copyBytes())
        }
        writeLong(value.attempt.processInstance); writeLong(value.attempt.attemptId); writeLong(value.attempt.authEpoch)
        blob(ledger(value.reservation))
    }
    private fun DataOutputStream.identity(value: VaultPersistentIdentity) {
        text(value.vaultId); text(value.installationId); text(value.initializationOperationId)
        text(value.deviceKeyAlias); text(value.biometricKeyAlias); writeInt(value.schemaVersion); writeInt(value.rootVersion)
    }
}

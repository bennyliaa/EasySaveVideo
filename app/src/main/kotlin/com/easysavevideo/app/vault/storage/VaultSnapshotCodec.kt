package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.VaultCiphertext
import com.easysavevideo.app.vault.VaultPendingPassword
import com.easysavevideo.app.vault.VaultRecoveryState
import com.easysavevideo.app.vault.VaultPersistentIdentity
import com.easysavevideo.app.vault.VaultRuntimeLimits
import com.easysavevideo.app.vault.VaultSlotKind
import com.easysavevideo.app.vault.VaultSlotRecord
import com.easysavevideo.app.vault.VaultStagedData
import com.easysavevideo.app.vault.VaultStoredSnapshot
import com.easysavevideo.app.vault.VaultStorePhase
import com.easysavevideo.core.model.VaultAttemptIdentity
import com.easysavevideo.core.model.VaultRetryLedger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded canonical encoding of technical IDs and ciphertext. Not an authenticated format. */
internal object VaultSnapshotCodec {
    private const val MAGIC = 0x45535631
    private const val LEGACY_WIRE_VERSION = 1
    private const val RECOVERY_WIRE_VERSION = 2
    private const val ID_MAX = 64
    private const val ALIAS_MAX = 160
    private const val SLOT_MAX = 3

    fun encode(snapshot: VaultStoredSnapshot): ByteArray {
        validate(snapshot)
        val stream = ByteArrayOutputStream()
        DataOutputStream(stream).use { out ->
            val version = wireVersion(snapshot)
            out.writeInt(MAGIC); out.writeInt(version)
            writeIdentity(out, snapshot.identity)
            out.writeInt(snapshot.phase.ordinal)
            out.writeLong(snapshot.storeRevision)
            val data = snapshot.data
            out.writeBoolean(data != null)
            if (data != null) {
                // Stable slot order prevents two semantically identical requests having ambiguous bytes.
                val slots = data.slots.sortedBy { it.kind.ordinal }
                out.writeInt(slots.size)
                slots.forEach { writeSlot(out, it) }
                writeCiphertext(out, data.catalogKeysetEnvelope)
                writeCiphertext(out, data.catalogEnvelope)
                out.writeLong(data.catalogRevision)
                writeCiphertext(out, data.installationMarker)
                writeLedger(out, data.retryLedger)
                writeCiphertext(out, data.retryLedgerEnvelope)
                if (version == RECOVERY_WIRE_VERSION) {
                    out.writeInt(data.recoveryState.ordinal)
                    out.writeBoolean(data.pendingPassword != null)
                    data.pendingPassword?.let { pending ->
                        writeSlot(out, pending.slot)
                        out.writeLong(pending.attempt.processInstance)
                        out.writeLong(pending.attempt.attemptId)
                        out.writeLong(pending.attempt.authEpoch)
                        writeLedger(out, pending.reservation)
                    }
                }
            }
        }
        return stream.toByteArray().also { require(it.size <= VaultSqliteRecord.MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): VaultStoredSnapshot {
        require(bytes.size in 1..VaultSqliteRecord.MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC)
        val version = input.readInt()
        require(version == LEGACY_WIRE_VERSION || version == RECOVERY_WIRE_VERSION)
        val identity = VaultPersistentIdentity(
            readText(input, ID_MAX), readText(input, ID_MAX), readText(input, ID_MAX),
            readText(input, ALIAS_MAX), readText(input, ALIAS_MAX), input.readInt(), input.readInt(),
        )
        val phase = VaultStorePhase.entries.getOrNull(input.readInt()) ?: throw VaultStoreFault()
        val revision = input.readLong()
        val data = if (readBoolean(input)) {
            val count = input.readInt()
            require(count in 1..SLOT_MAX)
            val slots = ArrayList<VaultSlotRecord>(count)
            repeat(count) { slots.add(readSlot(input)) }
            val legacy = VaultStagedData(
                slots.toList(), readCiphertext(input, VaultRuntimeLimits.ENVELOPE_BYTES),
                readCiphertext(input, VaultRuntimeLimits.EMPTY_CATALOG_BYTES), input.readLong(),
                readCiphertext(input, VaultRuntimeLimits.ENVELOPE_BYTES), readLedger(input),
                readCiphertext(input, VaultRuntimeLimits.ENVELOPE_BYTES),
            )
            if (version == LEGACY_WIRE_VERSION) legacy else {
                val recoveryState = VaultRecoveryState.entries.getOrNull(input.readInt()) ?: throw VaultStoreFault()
                val pending = if (readBoolean(input)) VaultPendingPassword(
                    readSlot(input), VaultAttemptIdentity(input.readLong(), input.readLong(), input.readLong()), readLedger(input),
                ) else null
                legacy.copy(recoveryState = recoveryState, pendingPassword = pending)
            }
        } else null
        require(input.available() == 0)
        return VaultStoredSnapshot(identity, phase, revision, data).also {
            validate(it)
            // Reject non-canonical slot ordering and every alternate wire representation.
            require(encode(it).contentEquals(bytes))
        }
    }

    /** Creates a deep bounded snapshot before a caller's mutable List can cross an owner boundary. */
    fun freeze(snapshot: VaultStoredSnapshot): VaultStoredSnapshot = decode(encode(snapshot))

    fun validate(snapshot: VaultStoredSnapshot) {
        validateIdentity(snapshot.identity)
        require(snapshot.storeRevision in 1 until Long.MAX_VALUE)
        if (snapshot.phase == VaultStorePhase.INITIALIZING) {
            require(snapshot.storeRevision == 1L && snapshot.data == null)
            return
        }
        require(snapshot.storeRevision >= 2)
        val data = requireNotNull(snapshot.data)
        require(data.slots.size in 1..SLOT_MAX)
        require(data.slots.map { it.slotId }.toSet().size == data.slots.size)
        require(data.slots.map { it.kind }.toSet().size == data.slots.size)
        require(data.slots.count { it.kind == VaultSlotKind.PIN || it.kind == VaultSlotKind.PASSWORD } == 1)
        data.slots.forEach { validateSlot(it) }
        val recovery = data.slots.singleOrNull { it.kind == VaultSlotKind.RECOVERY }
        when (data.recoveryState) {
            VaultRecoveryState.NONE -> require(recovery == null)
            VaultRecoveryState.PENDING, VaultRecoveryState.ACTIVE -> require(recovery != null)
        }
        require(data.catalogRevision in 1 until Long.MAX_VALUE)
        require(data.slots.all { it.generation <= data.catalogRevision })
        data.pendingPassword?.let { pending ->
            require(data.recoveryState == VaultRecoveryState.ACTIVE)
            validateSlot(pending.slot)
            require(pending.slot.kind == VaultSlotKind.PASSWORD)
            require(pending.slot.generation <= data.catalogRevision)
            val primary = data.slots.single { it.kind == VaultSlotKind.PASSWORD || it.kind == VaultSlotKind.PIN }
            require(pending.slot.generation > primary.generation)
            require(data.slots.none { it.slotId == pending.slot.slotId && it.kind != VaultSlotKind.PASSWORD })
            validateAttempt(pending.attempt)
            validateLedger(pending.reservation)
            require(pending.reservation.ledgerRevision > 0 && pending.reservation.reservedAttemptCount > 0)
            // A later reservation/reset can make this creating metadata stale. Decode must not
            // invalidate the still-current primary password or ACTIVE recovery credential.
        }
        require(data.catalogKeysetEnvelope.size in 1..VaultRuntimeLimits.ENVELOPE_BYTES)
        require(data.catalogEnvelope.size in 1..VaultRuntimeLimits.EMPTY_CATALOG_BYTES)
        require(data.installationMarker.size in 1..VaultRuntimeLimits.ENVELOPE_BYTES)
        require(data.retryLedgerEnvelope.size in 1..VaultRuntimeLimits.ENVELOPE_BYTES)
        validateLedger(data.retryLedger)
        if (snapshot.phase == VaultStorePhase.STAGED) {
            require(snapshot.storeRevision == 2L)
            require(data.catalogRevision == 1L && data.recoveryState == VaultRecoveryState.NONE && data.pendingPassword == null)
            require(data.retryLedger == VaultRetryLedger(0, 0, -1, null))
        } else require(snapshot.storeRevision >= 3)
    }

    /** V1 has no extension trailer; its original password-only representation stays exact. */
    private fun wireVersion(snapshot: VaultStoredSnapshot): Int {
        val data = snapshot.data ?: return LEGACY_WIRE_VERSION
        return if (data.recoveryState == VaultRecoveryState.NONE && data.pendingPassword == null &&
            data.catalogRevision == 1L && data.slots.all { it.generation == 1L }) LEGACY_WIRE_VERSION else RECOVERY_WIRE_VERSION
    }

    private fun validateSlot(slot: VaultSlotRecord) {
        requireOpaque(slot.slotId, ID_MAX)
        require(slot.generation in 1 until Long.MAX_VALUE)
        require(slot.envelope.size in 1..VaultRuntimeLimits.ENVELOPE_BYTES)
        // Required remains a profile invariant; recovery activation is explicit authenticated state.
        require(slot.required)
    }

    fun validateAttempt(attempt: VaultAttemptIdentity) {
        require(attempt.processInstance in 1 until Long.MAX_VALUE && attempt.attemptId in 1 until Long.MAX_VALUE)
        require(attempt.authEpoch in 0 until Long.MAX_VALUE)
    }

    fun validateIdentity(identity: VaultPersistentIdentity) {
        requireOpaque(identity.vaultId, ID_MAX)
        requireOpaque(identity.installationId, ID_MAX)
        requireOpaque(identity.initializationOperationId, ID_MAX)
        requireAlias(identity.deviceKeyAlias)
        requireAlias(identity.biometricKeyAlias)
        require(identity.deviceKeyAlias == "esv.vault.v1.${identity.initializationOperationId}.device")
        require(identity.biometricKeyAlias == "esv.vault.v1.${identity.initializationOperationId}.biometric")
        require(identity.schemaVersion == 1 && identity.rootVersion == 1)
    }

    fun validateLedger(ledger: VaultRetryLedger) {
        require(ledger.ledgerRevision in 0 until Long.MAX_VALUE)
        require(ledger.reservedAttemptCount in 0 until Long.MAX_VALUE)
        val expectedTier = VaultRuntimeLimits.retryPolicy().tierFor(ledger.reservedAttemptCount)
        require(ledger.delayTier == expectedTier)
        val reservationId = ledger.outstandingReservationId
        require(reservationId == null || reservationId > 0)
        require(if (ledger.reservedAttemptCount == 0L) ledger.outstandingReservationId == null
            else ledger.outstandingReservationId != null)
    }

    private fun requireOpaque(value: String, max: Int) {
        require(value.length in 1..max)
        require(value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
    }
    private fun requireAlias(value: String) {
        require(value.length in 1..ALIAS_MAX)
        require(value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_.:" })
    }
    private fun writeIdentity(out: DataOutputStream, identity: VaultPersistentIdentity) {
        writeText(out, identity.vaultId); writeText(out, identity.installationId)
        writeText(out, identity.initializationOperationId); writeText(out, identity.deviceKeyAlias)
        writeText(out, identity.biometricKeyAlias); out.writeInt(identity.schemaVersion); out.writeInt(identity.rootVersion)
    }
    private fun writeText(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun readText(input: DataInputStream, max: Int): String {
        val size = input.readInt()
        require(size in 1..max && size <= input.available())
        val bytes = ByteArray(size)
        input.readFully(bytes)
        require(bytes.all { (it.toInt() and 0xff) in 33..126 })
        return bytes.toString(Charsets.US_ASCII)
    }
    private fun writeCiphertext(out: DataOutputStream, value: VaultCiphertext) {
        val bytes = value.copyBytes()
        out.writeInt(bytes.size); out.write(bytes)
    }
    private fun readCiphertext(input: DataInputStream, max: Int): VaultCiphertext {
        val size = input.readInt()
        require(size in 1..max && size <= input.available())
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return VaultCiphertext(bytes, max)
    }
    private fun writeSlot(out: DataOutputStream, slot: VaultSlotRecord) {
        writeText(out, slot.slotId); out.writeLong(slot.generation)
        out.writeInt(slot.kind.ordinal); out.writeBoolean(slot.required)
        writeCiphertext(out, slot.envelope)
    }
    private fun readSlot(input: DataInputStream): VaultSlotRecord {
        val id = readText(input, ID_MAX)
        val generation = input.readLong()
        val kind = VaultSlotKind.entries.getOrNull(input.readInt()) ?: throw VaultStoreFault()
        val required = readBoolean(input)
        return VaultSlotRecord(id, generation, kind, readCiphertext(input, VaultRuntimeLimits.ENVELOPE_BYTES), required)
    }
    private fun writeLedger(out: DataOutputStream, ledger: VaultRetryLedger) {
        out.writeLong(ledger.ledgerRevision); out.writeLong(ledger.reservedAttemptCount); out.writeInt(ledger.delayTier)
        out.writeBoolean(ledger.outstandingReservationId != null)
        ledger.outstandingReservationId?.let { out.writeLong(it) }
    }
    private fun readLedger(input: DataInputStream): VaultRetryLedger = VaultRetryLedger(
        input.readLong(), input.readLong(), input.readInt(), if (readBoolean(input)) input.readLong() else null,
    )
    private fun readBoolean(input: DataInputStream): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw VaultStoreFault()
    }
}

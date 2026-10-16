package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.LibraryNames
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Canonical authenticated plaintext only. The surrounding catalog AEAD owns authenticity.
 * V1/V2 accept their exact original bytes. V3 binds credentials and all media fields.
 */
internal object VaultMediaCatalogWire {
    // Tink AES256_GCM catalog envelope includes a five-byte prefix, IV and GCM tag.
    const val MAX_PLAINTEXT_BYTES = VaultCryptoProfile.MAX_CATALOG_BYTES - 33
    private const val MIN_ENTRY_BYTES = 220
    private val order = compareBy<VaultMediaEntry> { it.identity.objectId }.thenBy { it.identity.generation }

    fun encode(identity: VaultPersistentIdentity, data: VaultStagedData, entries: List<VaultMediaEntry>): ByteArray {
        require(entries.size <= MAX_PLAINTEXT_BYTES / MIN_ENTRY_BYTES) { "media-catalog-count" }
        require(entries.map { it.identity.objectId }.distinct().size == entries.size) { "media-catalog-duplicate" }
        val prefix = VaultCryptoWire.mediaCatalogPrefix(identity, data)
        return try { VaultMediaWireIO.encode(MAX_PLAINTEXT_BYTES) {
            write(prefix); writeInt(entries.size)
            entries.sortedWith(order).forEach { writeEntry(it) }
        } } finally { prefix.fill(0) }
    }

    fun decode(identity: VaultPersistentIdentity, data: VaultStagedData, bytes: ByteArray): VaultAuthenticatedCatalog {
        require(bytes.size in 8..MAX_PLAINTEXT_BYTES) { "media-catalog-size" }
        val header = ByteBuffer.wrap(bytes)
        require(header.int == 0x45534331) { "media-catalog-format" }
        when (header.int) {
            1, 2 -> {
                val expected = VaultCryptoWire.emptyCatalog(identity, data)
                try { require(MessageDigest.isEqual(expected, bytes)) { "media-catalog-legacy" } }
                finally { expected.fill(0) }
                return VaultAuthenticatedCatalog(data.catalogRevision, emptyList())
            }
            3 -> Unit
            else -> throw IllegalArgumentException("media-catalog-version")
        }
        val prefix = VaultCryptoWire.mediaCatalogPrefix(identity, data)
        val entries = try {
            require(bytes.size >= prefix.size + 4) { "media-catalog-size" }
            require(MessageDigest.isEqual(prefix, bytes.copyOfRange(0, prefix.size))) { "media-catalog-binding" }
            val input = DataInputStream(ByteArrayInputStream(bytes, prefix.size, bytes.size - prefix.size))
            val count = input.readInt()
            require(count in 0..(input.available() / MIN_ENTRY_BYTES)) { "media-catalog-count" }
            val result = ArrayList<VaultMediaEntry>(count)
            repeat(count) { result.add(readEntry(input)) }
            require(input.available() == 0) { "media-catalog-trailer" }
            result.toList()
        } finally { prefix.fill(0) }
        val canonical = encode(identity, data, entries)
        try { require(MessageDigest.isEqual(bytes, canonical)) { "media-catalog-canonical" } }
        finally { canonical.fill(0) }
        return VaultAuthenticatedCatalog(data.catalogRevision, entries)
    }

    /** Preserve the entire authenticated catalog through every credential lifecycle change. */
    fun resealPlaintext(identity: VaultPersistentIdentity, before: VaultStagedData,
        proposed: VaultStagedData, authenticatedBefore: ByteArray): ByteArray {
        val catalog = decode(identity, before, authenticatedBefore)
        require(proposed.mediaJournalEnvelope == before.mediaJournalEnvelope &&
            proposed.mediaJournalRevision == before.mediaJournalRevision) { "credential-journal-preservation" }
        return if (ByteBuffer.wrap(authenticatedBefore).getInt(4) == 3)
            encode(identity, proposed, catalog.entries) else VaultCryptoWire.emptyCatalog(identity, proposed)
    }

    fun aad(identity: VaultPersistentIdentity, objectIdentity: VaultMediaObjectIdentity,
        evidence: ExportEvidence, purpose: Int): ByteArray = with(VaultMediaWireIO) {
        VaultCryptoWire.requireIdentity(identity)
        require(purpose in 1..2 && objectIdentity.generation in 1 until Long.MAX_VALUE) { "media-aad-profile" }
        encode(1024) {
            writeInt(0x45534d41); writeInt(1); writeInt(purpose); text(identity.vaultId, 64); writeInt(identity.rootVersion)
            text(objectIdentity.objectId, 64); writeLong(objectIdentity.generation); writeInt(1) // MP4 media role
            writeInt(VaultMediaLimits.PROFILE); evidence(evidence)
        }
    }

    private fun DataOutputStream.writeEntry(entry: VaultMediaEntry) = with(VaultMediaWireIO) {
        validateEntry(entry)
        text(entry.identity.objectId, 64); writeLong(entry.identity.generation); writeInt(entry.profile)
        evidence(entry.plaintext); cipherEvidence(entry.ciphertext); blob(entry.wrappedKeyset.copyBytes(), VaultRuntimeLimits.ENVELOPE_BYTES)
        text(entry.displayName, 255); writeLong(entry.durationMillis ?: -1); writeInt(entry.width ?: 0); writeInt(entry.height ?: 0)
        operation(entry.moveOperation); source(entry.source); writeBoolean(entry.finalized); writeBoolean(entry.sourceCleanupPending)
    }
    private fun readEntry(input: DataInputStream): VaultMediaEntry = with(VaultMediaWireIO) {
        val identity = VaultMediaObjectIdentity(input.text(64), input.readLong())
        val profile = input.readInt()
        val plain = input.evidence(); val cipher = input.cipherEvidence()
        val key = VaultCiphertext(input.blob(VaultRuntimeLimits.ENVELOPE_BYTES))
        val name = input.text(255); val duration = input.readLong(); val width = input.readInt(); val height = input.readInt()
        require(duration >= -1 && width >= 0 && height >= 0) { "media-metadata" }
        VaultMediaEntry(identity, profile, plain, cipher, key, name, duration.takeUnless { it == -1L },
            width.takeUnless { it == 0 }, height.takeUnless { it == 0 }, input.operation(), input.source(),
            input.bool(), input.bool()).also(::validateEntry)
    }
    private fun validateEntry(entry: VaultMediaEntry) {
        require(entry.profile == VaultMediaLimits.PROFILE && entry.finalized &&
            entry.identity.generation in 1 until Long.MAX_VALUE && entry.moveOperation.sequence in 1 until Long.MAX_VALUE &&
            entry.moveOperation.kind == VaultMediaKind.MOVE_IN) { "media-entry-profile" }
        require(entry.displayName == LibraryNames.validate(entry.displayName)) { "media-name" }
        require(entry.durationMillis == null || entry.durationMillis >= 0) { "media-duration" }
        require((entry.width == null) == (entry.height == null) && (entry.width == null || entry.width > 0) &&
            (entry.height == null || entry.height > 0)) { "media-dimensions" }
        require(entry.ciphertext.bytes > entry.plaintext.bytes && entry.wrappedKeyset.size <= VaultRuntimeLimits.ENVELOPE_BYTES) { "media-extent" }
    }
}

/** Lengths checked before allocations; fixed UTF-8, exact booleans and no trailing bytes. */
internal object VaultMediaWireIO {
    fun encode(maximum: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = object : ByteArrayOutputStream() {
            override fun write(b: Int) { require(count < maximum) { "media-wire-capacity" }; super.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                require(len >= 0 && count <= maximum - len) { "media-wire-capacity" }; super.write(b, off, len)
            }
        }
        return bytes.use { DataOutputStream(it).use { out -> out.body() }; it.toByteArray() }
    }
    fun DataOutputStream.text(value: String, maximum: Int) {
        require(value.length <= maximum) { "media-wire-text" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        try { require(bytes.size in 1..maximum) { "media-wire-text" }; writeInt(bytes.size); write(bytes) }
        finally { bytes.fill(0) }
    }
    fun DataInputStream.text(maximum: Int): String {
        val bytes = blob(maximum)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        finally { bytes.fill(0) }
    }
    fun DataOutputStream.blob(value: ByteArray, maximum: Int) {
        require(value.size in 1..maximum) { "media-wire-blob" }; writeInt(value.size); write(value)
    }
    fun DataInputStream.blob(maximum: Int): ByteArray {
        val size = readInt(); require(size in 1..maximum && size <= available()) { "media-wire-blob" }
        return ByteArray(size).also(::readFully)
    }
    fun DataInputStream.bool(): Boolean = when (readUnsignedByte()) {
        0 -> false; 1 -> true; else -> throw IllegalArgumentException("media-wire-boolean")
    }
    fun DataOutputStream.evidence(value: ExportEvidence) { writeLong(value.bytes); text(value.sha256, 64) }
    fun DataInputStream.evidence() = ExportEvidence(readLong(), text(64))
    fun DataOutputStream.cipherEvidence(value: VaultCiphertextEvidence) { writeLong(value.bytes); text(value.sha256, 64) }
    fun DataInputStream.cipherEvidence() = VaultCiphertextEvidence(readLong(), text(64))
    fun DataOutputStream.operation(value: VaultMediaOperation) {
        text(value.operationId, 64); writeLong(value.sequence); writeInt(value.kind.ordinal)
    }
    fun DataInputStream.operation() = VaultMediaOperation(text(64), readLong(),
        VaultMediaKind.entries.getOrNull(readInt()) ?: throw IllegalArgumentException("media-operation-kind"))
    fun DataOutputStream.source(value: MediaReadRef) { text(value.itemId, 128); writeLong(value.revision); writeLong(value.representationGeneration) }
    fun DataInputStream.source() = MediaReadRef(text(128), readLong(), readLong())
}

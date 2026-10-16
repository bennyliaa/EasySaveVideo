package com.easysavevideo.app.vault.storage

import java.security.MessageDigest

/** Technical encrypted record only. A digest or database row is never authentication authority. */
internal class VaultSqliteRecord(
    val revision: Long,
    val operationId: String,
    val phase: Int,
    payload: ByteArray,
) {
    private val encoded: ByteArray
    private val digest: ByteArray

    init {
        require(revision in 1 until Long.MAX_VALUE)
        require(operationId.length in 1..128 && operationId.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        })
        require(phase in 1..3)
        require(payload.size in 1..MAX_PAYLOAD_BYTES)
        encoded = payload.copyOf()
        digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    }

    fun payload(): ByteArray = encoded.copyOf()
    fun fingerprint(): ByteArray = digest.copyOf()
    fun sameBytes(other: VaultSqliteRecord): Boolean = revision == other.revision &&
        operationId == other.operationId && phase == other.phase &&
        MessageDigest.isEqual(digest, other.digest) && encoded.contentEquals(other.encoded)

    override fun toString(): String = "VaultSqliteRecord(redacted)"

    companion object { const val MAX_PAYLOAD_BYTES = 256 * 1024 }
}

/** Retains the exact old and proposed bytes across unknown COMMIT/ACK outcomes. */
internal class VaultSqliteMutation(val expected: VaultSqliteRecord?, val proposed: VaultSqliteRecord) {
    init {
        require(if (expected == null) proposed.revision == 1L && proposed.phase == 1
            else expected.revision < Long.MAX_VALUE - 1 && proposed.revision == expected.revision + 1)
    }
    override fun toString(): String = "VaultSqliteMutation(redacted)"
}

internal enum class VaultSqliteReadback {
    EXACT_PROPOSED, EXACT_EXPECTED, INCONSISTENT,
}

/** Pure exact comparison; hash equality alone can never acknowledge a mutation. */
internal fun classifyVaultReadback(mutation: VaultSqliteMutation, observed: VaultSqliteRecord?): VaultSqliteReadback = when {
    observed != null && observed.sameBytes(mutation.proposed) -> VaultSqliteReadback.EXACT_PROPOSED
    mutation.expected != null && observed != null && observed.sameBytes(mutation.expected) -> VaultSqliteReadback.EXACT_EXPECTED
    else -> VaultSqliteReadback.INCONSISTENT
}

internal enum class VaultSqliteWriteResult {
    DURABLE_EXACT, CONFIRMED_OLD, CONFLICT, UNKNOWN, UNAVAILABLE, DEADLINE,
}

internal class VaultStoreFault : RuntimeException("VAULT_STORE_UNAVAILABLE")

/** Read-only SQLite header gate; rejects WAL before OpenParams could convert an existing file. */
internal object VaultSqliteHeaderPolicy {
    const val HEADER_BYTES = 100
    private val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    fun requireRollbackHeader(header: ByteArray) {
        require(header.size == HEADER_BYTES)
        require(header.copyOfRange(0, magic.size).contentEquals(magic))
        require(header[18].toInt() == 1 && header[19].toInt() == 1)
        val encodedPageSize = ((header[16].toInt() and 255) shl 8) or (header[17].toInt() and 255)
        val pageSize = if (encodedPageSize == 1) 65_536 else encodedPageSize
        require(pageSize in 512..65_536 && pageSize and (pageSize - 1) == 0)
    }
}

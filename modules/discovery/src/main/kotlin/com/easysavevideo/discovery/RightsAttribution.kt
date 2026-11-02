package com.easysavevideo.discovery

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.ByteBuffer

/** Source reports only. No state here grants playback, download, reuse, or legal clearance. */
enum class RightsReview { INCOMPLETE, REPORTED_REQUIRES_REVIEW, FLAGGED_REQUIRES_REVIEW }
enum class RightsMetadataKind { EXTRACTED_UNSTRUCTURED_TEXT, STRUCTURED_METADATA }
enum class RightsWarning { CONFLICTING_REPORTS, STALE_OBSERVATION, PRIOR_REVIEW_WARNING }

data class RightsFieldProvenance(
    val field: String,
    val apiReportedSource: String? = null,
    val kind: RightsMetadataKind = RightsMetadataKind.EXTRACTED_UNSTRUCTURED_TEXT,
    val htmlConvertedToText: Boolean = false,
    val truncated: Boolean = false,
) { override fun toString(): String = "RightsFieldProvenance([redacted])" }

/** One indivisible observation. Never combine an author from one report with another report's license. */
data class RightsSnapshot(
    val providerId: String,
    val sourceName: String,
    val sourceNamespace: String,
    val sourceItemId: String,
    val sourcePageUrl: String,
    val sourcePageRevisionId: Long? = null,
    val retrievedAtEpochMillis: Long,
    val author: String? = null,
    val credit: String? = null,
    val customAttribution: String? = null,
    val licenseName: String? = null,
    val licenseUrl: String? = null,
    val usageTerms: String? = null,
    val copyrighted: Boolean? = null,
    val attributionRequired: Boolean? = null,
    val nonFree: Boolean? = null,
    val restrictions: List<String> = emptyList(),
    val deletionReason: String? = null,
    val review: RightsReview = RightsReview.INCOMPLETE,
    val provenance: List<RightsFieldProvenance> = emptyList(),
    val metadataTermsUrl: String? = null,
    val schemaVersion: Int = 1,
) {
    val hasDisplayAttribution: Boolean get() = !author.isNullOrBlank() && !licenseName.isNullOrBlank() && licenseUrl != null
    val downloadAuthorized: Boolean get() = false
    override fun toString(): String = "RightsSnapshot(review=$review)"
    internal fun key(): String = framed(providerId, sourceNamespace, sourceItemId)
    internal fun detached(): RightsSnapshot = copy(restrictions = restrictions.toList(), provenance = provenance.map { it.copy() })
}

data class ResultRights(
    val snapshots: List<RightsSnapshot> = emptyList(),
    val warnings: Set<RightsWarning> = emptySet(),
) {
    val requiresReview: Boolean get() = true
    val downloadAuthorized: Boolean get() = false
    val hasDisplayAttribution: Boolean get() = snapshots.isNotEmpty() && snapshots.all { it.hasDisplayAttribution }
    fun detached(): ResultRights = copy(snapshots = snapshots.map { it.detached() }, warnings = warnings.toSet())
    override fun toString(): String = "ResultRights(count=${snapshots.size}, warnings=${warnings.size})"
}

/** Bounded deterministic merge: keep each source item's latest whole observation and explicit warnings. */
object RightsAttribution {
    fun merge(old: ResultRights, incoming: ResultRights): ResultRights {
        RightsCodec.validate(old); RightsCodec.validate(incoming)
        val warnings = (old.warnings + incoming.warnings).toMutableSet()
        val chosen = (old.snapshots + incoming.snapshots).groupBy { it.key() }.toSortedMap().values.map { observations ->
            val unique = observations.distinct()
            // A single total order keeps merges associative across batches and retries. This is
            // the latest observation, not a claim that its source revision is the latest revision.
            val selected = unique.maxWithOrNull(compareBy<RightsSnapshot> { it.retrievedAtEpochMillis }
                .thenBy { it.sourcePageRevisionId ?: -1L }.thenBy { RightsCodec.stableOrder(it) })!!
            unique.filter { it != selected }.forEach { other ->
                if (other.sourcePageRevisionId != selected.sourcePageRevisionId || other.retrievedAtEpochMillis != selected.retrievedAtEpochMillis)
                    warnings += RightsWarning.STALE_OBSERVATION
                // Different revisions are conservatively review-worthy in either direction.
                // A regression-only warning would depend on merge parenthesization after dropping
                // an intermediate observation. These monotone flags retain all conflict evidence.
                if (other.sourcePageRevisionId != selected.sourcePageRevisionId ||
                    other.copy(sourcePageRevisionId = selected.sourcePageRevisionId, retrievedAtEpochMillis = selected.retrievedAtEpochMillis) != selected)
                    warnings += RightsWarning.CONFLICTING_REPORTS
                if (other.review == RightsReview.FLAGGED_REQUIRES_REVIEW || other.nonFree == true || other.restrictions.isNotEmpty() || other.deletionReason != null)
                    warnings += RightsWarning.PRIOR_REVIEW_WARNING
            }
            selected.detached()
        }
        return ResultRights(chosen, warnings.toSet()).also { RightsCodec.validate(it) }
    }
}

/** Strict independent wire format shared by per-row SQLite storage and full checkpoints. */
object RightsCodec {
    const val MAX_BYTES = 32 * 1024
    const val MAX_SNAPSHOTS = 4
    private const val MAGIC = 0x45535231
    private const val VERSION = 1
    fun validate(value: ResultRights) { encode(value) }
    fun encode(value: ResultRights): ByteArray {
        require(value.snapshots.size <= MAX_SNAPSHOTS)
        require(value.snapshots.map { it.key() }.distinct().size == value.snapshots.size)
        val bytes = object : ByteArrayOutputStream() {
            override fun write(value: Int) { require(count < MAX_BYTES); super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { require(length <= MAX_BYTES - count); super.write(bytes, offset, length) }
        }
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeInt(value.warnings.size); value.warnings.sortedBy { it.name }.forEach { out.text(it.name, 64) }
            out.writeInt(value.snapshots.size)
            value.snapshots.forEach { s ->
                validateSnapshot(s)
                out.writeInt(s.schemaVersion)
                out.text(s.providerId, 128); out.text(s.sourceName, 128); out.text(s.sourceNamespace, 128)
                out.text(s.sourceItemId, 512); out.text(s.sourcePageUrl, 8192)
                out.optionalLong(s.sourcePageRevisionId); out.writeLong(s.retrievedAtEpochMillis)
                out.optionalText(s.author, 4096); out.optionalText(s.credit, 4096); out.optionalText(s.customAttribution, 4096)
                out.optionalText(s.licenseName, 4096); out.optionalText(s.licenseUrl, 8192); out.optionalText(s.usageTerms, 4096)
                out.tristate(s.copyrighted); out.tristate(s.attributionRequired); out.tristate(s.nonFree)
                out.writeInt(s.restrictions.size); s.restrictions.forEach { out.text(it, 4096) }
                out.optionalText(s.deletionReason, 4096); out.text(s.review.name, 64)
                out.writeInt(s.provenance.size)
                s.provenance.forEach { field ->
                    out.text(field.field, 128); out.optionalText(field.apiReportedSource, 128); out.text(field.kind.name, 64)
                    out.writeBoolean(field.htmlConvertedToText); out.writeBoolean(field.truncated)
                }
                out.optionalText(s.metadataTermsUrl, 8192)
                require(bytes.size() <= MAX_BYTES)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): ResultRights {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val warnings = List(input.count(RightsWarning.entries.size)) { RightsWarning.valueOf(input.text(64)) }
            require(warnings.distinct().size == warnings.size)
            val snapshots = List(input.count(MAX_SNAPSHOTS)) {
                val version = input.readInt(); require(version == 1)
                RightsSnapshot(
                    providerId = input.text(128), sourceName = input.text(128), sourceNamespace = input.text(128),
                    sourceItemId = input.text(512), sourcePageUrl = input.text(8192), sourcePageRevisionId = input.optionalLong(),
                    retrievedAtEpochMillis = input.readLong(), author = input.optionalText(4096), credit = input.optionalText(4096),
                    customAttribution = input.optionalText(4096), licenseName = input.optionalText(4096), licenseUrl = input.optionalText(8192),
                    usageTerms = input.optionalText(4096), copyrighted = input.tristate(), attributionRequired = input.tristate(), nonFree = input.tristate(),
                    restrictions = List(input.count(32)) { input.text(4096) }, deletionReason = input.optionalText(4096),
                    review = RightsReview.valueOf(input.text(64)), provenance = List(input.count(32)) {
                        RightsFieldProvenance(input.text(128), input.optionalText(128), RightsMetadataKind.valueOf(input.text(64)), input.boolean(), input.boolean())
                    }, metadataTermsUrl = input.optionalText(8192), schemaVersion = version,
                )
            }
            require(input.available() == 0)
            ResultRights(snapshots, warnings.toSet()).also(::validate)
        }
    }
    internal fun stableOrder(snapshot: RightsSnapshot): String = encode(ResultRights(listOf(snapshot))).joinToString("") { "%02x".format(it) }
    private fun validateSnapshot(s: RightsSnapshot) {
        require(s.schemaVersion == 1); requireId(s.providerId); requireId(s.sourceNamespace)
        require(s.sourceName.isNotBlank() && s.sourceItemId.isNotBlank())
        require(s.retrievedAtEpochMillis >= 0 && (s.sourcePageRevisionId == null || s.sourcePageRevisionId > 0))
        require(link(s.sourcePageUrl) && (s.licenseUrl == null || link(s.licenseUrl)) && (s.metadataTermsUrl == null || link(s.metadataTermsUrl)))
        require(s.restrictions.size <= 32 && s.restrictions.none { it.isBlank() } && s.provenance.size <= 32)
        require(s.provenance.map { it.field }.distinct().size == s.provenance.size)
        require(s.provenance.all { it.field.isNotBlank() })
        // A source may omit facts, but cannot present incomplete or flagged facts with a weaker review state.
        if (!s.hasDisplayAttribution || s.provenance.any { it.truncated }) require(s.review != RightsReview.REPORTED_REQUIRES_REVIEW)
        if (s.nonFree == true || s.restrictions.isNotEmpty() || s.deletionReason != null) require(s.review == RightsReview.FLAGGED_REQUIRES_REVIEW)
    }
    private fun link(value: String): Boolean = try {
        val uri = URI(value)
        isWellFormedUnicode(value) && value.length <= 8192 && value.none { it.isISOControl() } &&
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port == -1
    } catch (_: Exception) { false }
    private fun DataOutputStream.text(value: String, max: Int) {
        require(value.length <= max && isWellFormedUnicode(value))
        val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_BYTES)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataOutputStream.optionalText(value: String?, max: Int) { writeBoolean(value != null); value?.let { text(it, max) } }
    private fun DataOutputStream.optionalLong(value: Long?) { writeBoolean(value != null); value?.let(::writeLong) }
    private fun DataOutputStream.tristate(value: Boolean?) = writeByte(when (value) { null -> 0; false -> 1; true -> 2 })
    private fun DataInputStream.boolean(): Boolean = when (readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException("RIGHTS_BOOLEAN") }
    private fun DataInputStream.tristate(): Boolean? = when (readUnsignedByte()) { 0 -> null; 1 -> false; 2 -> true; else -> throw IllegalArgumentException("RIGHTS_TRISTATE") }
    private fun DataInputStream.text(max: Int): String {
        val size = readInt(); require(size in 0..minOf(MAX_BYTES, max * 4) && size <= available())
        val bytes = ByteArray(size); readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().also { require(it.length <= max && isWellFormedUnicode(it)) }
    }
    private fun DataInputStream.optionalText(max: Int): String? = if (boolean()) text(max) else null
    private fun DataInputStream.optionalLong(): Long? = if (boolean()) readLong() else null
    private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
}

/** No replacement of invalid surrogate sequences during encoding or identity calculation. */
internal fun isWellFormedUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

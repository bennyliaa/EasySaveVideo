package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.SearxngConfigurationCodec
import com.easysavevideo.searxng.SearxngEndpoint
import com.easysavevideo.searxng.SearxngResultFormat
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/** Functional endpoint configuration only. Never use this value as diagnostic/log output. */
class SearxngSourceSlot(val id: String, val searchUrl: String? = null, val enabled: Boolean = false,
    val format: SearxngResultFormat = SearxngResultFormat.JSON_VIDEOS) {
    init {
        require(SearxngConfigurationPolicy.ordinal(id) != null)
        require(searchUrl != null || !enabled)
        searchUrl?.let {
            require(it.length <= SearxngConfigurationPolicy.MAX_URL_BYTES)
            require(it.toByteArray(Charsets.UTF_8).size <= SearxngConfigurationPolicy.MAX_URL_BYTES)
            SearxngEndpoint(id, it, format)
        }
    }
    val endpoint: SearxngEndpoint? get() = searchUrl?.let { SearxngEndpoint(id, it, format) }
    val hostname: String? get() = searchUrl?.let { URI(it).host }
    // HTTPS and the default TLS port are fixed. Paths do not grant another service admission.
    internal val originKey: String? get() = hostname?.lowercase(Locale.ROOT)
    override fun equals(other: Any?) = other is SearxngSourceSlot && id == other.id && searchUrl == other.searchUrl &&
        enabled == other.enabled && format == other.format
    override fun hashCode() = 31 * (31 * (31 * id.hashCode() + (searchUrl?.hashCode() ?: 0)) + enabled.hashCode()) + format.hashCode()
    override fun toString() = "SearxngSourceSlot([redacted])"
}

/** Revision and the monotonic allocator participate in the generation, including after deletion/reset. */
class SearxngSourceConfiguration(val revision: Long, slots: List<SearxngSourceSlot>, val nextInstanceOrdinal: Long) {
    val slots: List<SearxngSourceSlot> = Collections.unmodifiableList(slots.toList())
    init {
        require(revision >= 0 && nextInstanceOrdinal > 0)
        require(this.slots.size <= SearxngConfigurationPolicy.MAX_INSTANCES)
        require(this.slots.all { it.searchUrl != null && SearxngConfigurationPolicy.ordinal(it.id)!! < nextInstanceOrdinal })
        require(this.slots.map { it.id }.distinct().size == this.slots.size)
        val origins = this.slots.mapNotNull { it.originKey }
        require(origins.size == origins.toSet().size)
    }
    val generation: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(SearxngConfigurationCodec.encode(this))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    val enabledEndpoints: List<SearxngEndpoint> get() = slots.filter { it.enabled }.mapNotNull { it.endpoint }
    override fun equals(other: Any?) = other is SearxngSourceConfiguration && revision == other.revision &&
        nextInstanceOrdinal == other.nextInstanceOrdinal && slots == other.slots
    override fun hashCode() = 31 * (31 * revision.hashCode() + slots.hashCode()) + nextInstanceOrdinal.hashCode()
    override fun toString() = "SearxngSourceConfiguration([redacted])"
}

enum class SearxngConfigurationIssue {
    INVALID_ENDPOINT, DUPLICATE_ORIGIN, ACKNOWLEDGEMENT_REQUIRED, REVISION_EXHAUSTED,
    UNKNOWN_SLOT, INSTANCE_LIMIT, ID_EXHAUSTED,
}

sealed interface SearxngConfigurationEdit {
    data class Accepted(val config: SearxngSourceConfiguration) : SearxngConfigurationEdit {
        override fun toString() = "SearxngConfigurationEdit.Accepted([redacted])"
    }
    data object Unchanged : SearxngConfigurationEdit
    data class Rejected(val issue: SearxngConfigurationIssue) : SearxngConfigurationEdit
}

/** Pure policy. No DNS, HTTP, providers, query state, SafeSearch mutations, or implicit normalization. */
object SearxngConfigurationPolicy {
    const val MAX_URL_BYTES = 2048
    const val MAX_ID_BYTES = 27
    const val MAX_INSTANCES = 16
    val LEGACY_SLOT_IDS: List<String> = Collections.unmodifiableList(listOf("searxng-1", "searxng-2", "searxng-3"))
    private val idPattern = Regex("searxng-[1-9][0-9]{0,18}")
    internal fun ordinal(id: String): Long? = if (id.matches(idPattern)) id.substring(8).toLongOrNull() else null
    fun empty(revision: Long = 0) = SearxngSourceConfiguration(revision, emptyList(), 1)

    fun acknowledgementRequired(before: SearxngSourceSlot, searchUrl: String, enabled: Boolean,
        format: SearxngResultFormat = before.format): Boolean =
        searchUrl.isNotEmpty() && (before.searchUrl != searchUrl || before.format != format || (!before.enabled && enabled))

    fun add(config: SearxngSourceConfiguration, searchUrl: String, enabled: Boolean = false,
        acknowledged: Boolean, format: SearxngResultFormat = SearxngResultFormat.JSON_VIDEOS): SearxngConfigurationEdit {
        if (config.slots.size >= MAX_INSTANCES) return rejected(SearxngConfigurationIssue.INSTANCE_LIMIT)
        if (config.nextInstanceOrdinal == Long.MAX_VALUE) return rejected(SearxngConfigurationIssue.ID_EXHAUSTED)
        val entry = configuredSlot("searxng-${config.nextInstanceOrdinal}", searchUrl, enabled, format)
            ?: return rejected(SearxngConfigurationIssue.INVALID_ENDPOINT)
        if (config.slots.any { it.originKey == entry.originKey }) return rejected(SearxngConfigurationIssue.DUPLICATE_ORIGIN)
        if (!acknowledged) return rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED)
        return changed(config, config.slots + entry, config.nextInstanceOrdinal + 1)
    }

    fun replace(config: SearxngSourceConfiguration, slotId: String, searchUrl: String, enabled: Boolean,
        acknowledged: Boolean, format: SearxngResultFormat = config.slots.firstOrNull { it.id == slotId }?.format
            ?: SearxngResultFormat.JSON_VIDEOS): SearxngConfigurationEdit {
        val old = config.slots.firstOrNull { it.id == slotId } ?: return rejected(SearxngConfigurationIssue.UNKNOWN_SLOT)
        val replacement = configuredSlot(slotId, searchUrl, enabled, format)
            ?: return rejected(SearxngConfigurationIssue.INVALID_ENDPOINT)
        if (config.slots.any { it.id != slotId && it.originKey == replacement.originKey })
            return rejected(SearxngConfigurationIssue.DUPLICATE_ORIGIN)
        if (old == replacement) return SearxngConfigurationEdit.Unchanged
        if (acknowledgementRequired(old, searchUrl, enabled, format) && !acknowledged)
            return rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED)
        return changed(config, config.slots.map { if (it.id == slotId) replacement else it })
    }

    fun remove(config: SearxngSourceConfiguration, slotId: String): SearxngConfigurationEdit {
        if (config.slots.none { it.id == slotId }) return rejected(SearxngConfigurationIssue.UNKNOWN_SLOT)
        return changed(config, config.slots.filterNot { it.id == slotId })
    }

    fun reset(config: SearxngSourceConfiguration): SearxngConfigurationEdit = changed(config, emptyList())

    /** Store defense in depth: no caller may roll back the allocator or resurrect a deleted ID. */
    internal fun isNextRevision(old: SearxngSourceConfiguration, next: SearxngSourceConfiguration): Boolean =
        old.revision < Long.MAX_VALUE && next.revision == old.revision + 1 &&
            next.nextInstanceOrdinal >= old.nextInstanceOrdinal && next.slots.all { row ->
                old.slots.any { it.id == row.id } || ordinal(row.id)!! >= old.nextInstanceOrdinal
            }

    private fun configuredSlot(id: String, url: String, enabled: Boolean, format: SearxngResultFormat): SearxngSourceSlot? =
        if (url.isEmpty()) null else try { SearxngSourceSlot(id, url, enabled, format) }
        catch (_: IllegalArgumentException) { null }

    private fun changed(config: SearxngSourceConfiguration, slots: List<SearxngSourceSlot>,
        nextOrdinal: Long = config.nextInstanceOrdinal): SearxngConfigurationEdit =
        if (config.revision == Long.MAX_VALUE) rejected(SearxngConfigurationIssue.REVISION_EXHAUSTED)
        else SearxngConfigurationEdit.Accepted(SearxngSourceConfiguration(config.revision + 1, slots, nextOrdinal))

    private fun rejected(issue: SearxngConfigurationIssue) = SearxngConfigurationEdit.Rejected(issue)
}

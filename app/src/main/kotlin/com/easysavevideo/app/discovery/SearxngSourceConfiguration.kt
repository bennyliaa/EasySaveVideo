package com.easysavevideo.app.discovery

import com.easysavevideo.app.storage.SearxngConfigurationCodec
import com.easysavevideo.searxng.SearxngEndpoint
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

/** Functional endpoint configuration only. Never use this value as diagnostic/log output. */
class SearxngSourceSlot(val id: String, val searchUrl: String? = null, val enabled: Boolean = false) {
    init {
        require(id in SearxngConfigurationPolicy.SLOT_IDS)
        require(searchUrl != null || !enabled)
        searchUrl?.let {
            require(it.length <= SearxngConfigurationPolicy.MAX_URL_BYTES)
            require(it.toByteArray(Charsets.UTF_8).size <= SearxngConfigurationPolicy.MAX_URL_BYTES)
            SearxngEndpoint(id, it)
        }
    }
    val endpoint: SearxngEndpoint? get() = searchUrl?.let { SearxngEndpoint(id, it) }
    val hostname: String? get() = searchUrl?.let { URI(it).host }
    override fun equals(other: Any?) = other is SearxngSourceSlot && id == other.id && searchUrl == other.searchUrl && enabled == other.enabled
    override fun hashCode() = 31 * (31 * id.hashCode() + (searchUrl?.hashCode() ?: 0)) + enabled.hashCode()
    override fun toString() = "SearxngSourceSlot([redacted])"
}

/** Revision participates in the fingerprint, so edit-away/edit-back and explicit reset cannot reuse a pool. */
class SearxngSourceConfiguration(val revision: Long, slots: List<SearxngSourceSlot>) {
    val slots: List<SearxngSourceSlot> = Collections.unmodifiableList(slots.toList())
    init {
        require(revision >= 0)
        require(this.slots.map { it.id } == SearxngConfigurationPolicy.SLOT_IDS)
    }
    val generation: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(SearxngConfigurationCodec.encode(this))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    val enabledEndpoints: List<SearxngEndpoint> get() = slots.filter { it.enabled }.mapNotNull { it.endpoint }
    override fun equals(other: Any?) = other is SearxngSourceConfiguration && revision == other.revision && slots == other.slots
    override fun hashCode() = 31 * revision.hashCode() + slots.hashCode()
    override fun toString() = "SearxngSourceConfiguration([redacted])"
}

enum class SearxngConfigurationIssue { INVALID_ENDPOINT, ACKNOWLEDGEMENT_REQUIRED, REVISION_EXHAUSTED, UNKNOWN_SLOT }

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
    val SLOT_IDS: List<String> = Collections.unmodifiableList(listOf("searxng-1", "searxng-2", "searxng-3"))
    fun empty(revision: Long = 0) = SearxngSourceConfiguration(revision, SLOT_IDS.map { SearxngSourceSlot(it) })

    fun acknowledgementRequired(before: SearxngSourceSlot, searchUrl: String, enabled: Boolean): Boolean =
        searchUrl.isNotEmpty() && (before.searchUrl != searchUrl || (!before.enabled && enabled))

    fun replace(config: SearxngSourceConfiguration, slotId: String, searchUrl: String, enabled: Boolean,
        acknowledged: Boolean): SearxngConfigurationEdit {
        val old = config.slots.firstOrNull { it.id == slotId }
            ?: return SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.UNKNOWN_SLOT)
        val replacement = try { SearxngSourceSlot(slotId, searchUrl.ifEmpty { null }, enabled) }
        catch (_: IllegalArgumentException) { return SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.INVALID_ENDPOINT) }
        if (old == replacement) return SearxngConfigurationEdit.Unchanged
        if (acknowledgementRequired(old, searchUrl, enabled) && !acknowledged)
            return SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.ACKNOWLEDGEMENT_REQUIRED)
        if (config.revision == Long.MAX_VALUE)
            return SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.REVISION_EXHAUSTED)
        return SearxngConfigurationEdit.Accepted(SearxngSourceConfiguration(config.revision + 1,
            config.slots.map { if (it.id == slotId) replacement else it }))
    }

    fun reset(config: SearxngSourceConfiguration): SearxngConfigurationEdit =
        if (config.revision == Long.MAX_VALUE) SearxngConfigurationEdit.Rejected(SearxngConfigurationIssue.REVISION_EXHAUSTED)
        else SearxngConfigurationEdit.Accepted(empty(config.revision + 1))
}

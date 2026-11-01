package com.easysavevideo.app.discovery

import com.easysavevideo.app.discovery.network.SystemSearxngTransport
import com.easysavevideo.discovery.VideoSearchProvider
import com.easysavevideo.searxng.SearxngVideoSearchProvider
import com.easysavevideo.searxng.SearxngHtmlSearchProvider
import com.easysavevideo.searxng.SearxngResultFormat
import com.easysavevideo.searxng.SearxngTransport
import java.security.MessageDigest

/** Fixed process-owned physical capacity; editing/deleting instances never resets active I/O or cooldowns. */
object SearxngSourceRegistration {
    private val slots by lazy { SearxngProviderSlots(List(SearxngProviderSlots.PHYSICAL_CAPACITY) { SystemSearxngTransport() }) }
    fun providers(configuration: SearxngSourceConfiguration?): List<VideoSearchProvider> = slots.providers(configuration)
    /** Revision-bounded compatibility covers every saved profile, including edit-away/edit-back. */
    fun bindGeneration(providers: List<VideoSearchProvider>, configuration: SearxngSourceConfiguration?,
        commonsEnabled: Boolean): List<VideoSearchProvider> {
        val key = configurationKey(configuration, commonsEnabled, providers.map { it.descriptor })
        return providers.map { provider -> object : VideoSearchProvider by provider {
            override val descriptor = provider.descriptor.copy(version = "cfg1-$key", compatibleCursorVersions = emptySet())
        } }
    }
    fun configurationKey(configuration: SearxngSourceConfiguration?, commonsEnabled: Boolean,
        descriptors: List<com.easysavevideo.discovery.ProviderDescriptor>): String = MessageDigest.getInstance("SHA-256")
        .digest(("sources-v1|" + (configuration?.generation ?: "unavailable") + "|" + commonsEnabled + "|" +
            descriptors.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.version}" }).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Internal construction seam, not a public network entry point. No map grows with deleted IDs.
 * Physical slots keep their own immutable calls until cleanup completes; all production slots
 * additionally share origin admission, so moving an instance cannot bypass in-flight/cooldown gates.
 * Index before filtering: toggling an unrelated instance does not remap all subsequent enabled rows.
 */
internal class SearxngProviderSlots(transports: List<SearxngTransport>) {
    companion object { const val PHYSICAL_CAPACITY = 3 }
    private val transports = transports.toList().also { require(it.size == PHYSICAL_CAPACITY) }
    fun providers(configuration: SearxngSourceConfiguration?): List<VideoSearchProvider> =
        configuration?.slots.orEmpty().mapIndexedNotNull { index, slot ->
            val endpoint = slot.endpoint?.takeIf { slot.enabled } ?: return@mapIndexedNotNull null
            when (endpoint.format) {
                SearxngResultFormat.JSON_VIDEOS -> SearxngVideoSearchProvider(endpoint, transports[index % PHYSICAL_CAPACITY])
                SearxngResultFormat.HTML_WEB -> SearxngHtmlSearchProvider(endpoint, transports[index % PHYSICAL_CAPACITY])
            }
        }
}

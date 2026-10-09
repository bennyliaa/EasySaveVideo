package com.easysavevideo.app.discovery

import com.easysavevideo.app.discovery.network.SystemSearxngTransport
import com.easysavevideo.discovery.VideoSearchProvider
import com.easysavevideo.searxng.SearxngVideoSearchProvider
import java.security.MessageDigest

/** Three process-owned physical slots survive endpoint edits, disable/re-enable and UI recreation. */
object SearxngSourceRegistration {
    private val transports by lazy { SearxngConfigurationPolicy.SLOT_IDS.associateWith { SystemSearxngTransport() } }
    fun providers(configuration: SearxngSourceConfiguration?): List<VideoSearchProvider> =
        configuration?.enabledEndpoints.orEmpty().map { endpoint ->
            SearxngVideoSearchProvider(endpoint, transports.getValue(endpoint.id))
        }
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

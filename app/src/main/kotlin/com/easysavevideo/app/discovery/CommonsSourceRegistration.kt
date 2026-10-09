package com.easysavevideo.app.discovery

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import com.easysavevideo.commons.maintenance.ProviderRepository
import com.easysavevideo.commons.maintenance.ProviderRepositoryResult
import com.easysavevideo.commons.maintenance.ProviderPackageVerifier
import com.easysavevideo.commons.maintenance.RepositoryPolicyVerifier
import com.easysavevideo.commons.maintenance.RepositoryCommonsMaintenance
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.discovery.network.SystemCommonsTransport
import com.easysavevideo.commons.*
import com.easysavevideo.discovery.VideoSearchProvider

/** Contact is injected per build for this API only, never hardcoded or forwarded to browser/media. */
object CommonsSourceRegistration {
    val configured: Boolean get() = BuildConfig.COMMONS_ENABLED && BuildConfig.COMMONS_CONTACT.isNotEmpty()
    // Empty independent roots and no update endpoint. This configuration never claims remote readiness.
    val managementConfiguration: ProviderManagementConfiguration
        get() = ProviderManagementConfiguration(contactConfigured = configured)
    internal val managementWorker = ProviderManagementWorker()
    private val changes = AtomicLong(0)
    val managementRevision: Long get() = changes.get()
    // Process owner; construction only on a background lane. Inspection may open it in zero-source mode.
    // A failed first attempt is retained. Neither reopening the page nor an absent root resets evidence.
    private val owner = ProviderRepositoryOwner { directory ->
        ProviderRepository(directory, ProviderPackageVerifier(), policyVerifier = RepositoryPolicyVerifier())
    }
    private fun openOnce(context: Context) = owner.open(File(context.applicationContext.filesDir, "provider-maintenance"))
    fun initialize(context: Context) { if (configured) openOnce(context) }
    fun managementBackend(context: Context): ProviderManagementBackend {
        val app = context.applicationContext
        return object : ProviderManagementBackend {
            private fun repository() = checkNotNull(openOnce(app)) { "LOCAL_UNAVAILABLE" }
            override fun inspect() = repository().snapshot()
            override fun rejectCandidate(expectedVersion: Long, checkCancelled: () -> Unit) =
                changed(repository().rejectCandidate(expectedVersion, checkCancelled))
            override fun rollback(expectedGeneration: Long, checkCancelled: () -> Unit) =
                changed(repository().rollback(expectedGeneration, checkCancelled))
            private fun changed(result: ProviderRepositoryResult): ProviderRepositoryResult {
                if (result is ProviderRepositoryResult.Success) changes.incrementAndGet()
                return result
            }
        }
    }
    val maintenanceAvailable: Boolean get() = owner.current?.snapshot()?.policy?.enabled == true
    // One instance preserves its physical transport slot, cache and server cooldown across UI sessions.
    private val source: VideoSearchProvider by lazy {
        CommonsVideoSearchProvider(CommonsMetadataProvider(
            CommonsClientConfig(true, BuildConfig.VERSION_NAME, BuildConfig.COMMONS_CONTACT), SystemCommonsTransport(),
            maintenance = RepositoryCommonsMaintenance(checkNotNull(owner.current))))
    }
    fun providers(enabled: Boolean): List<VideoSearchProvider> = if (enabled && configured && maintenanceAvailable) listOf(source) else emptyList()
}

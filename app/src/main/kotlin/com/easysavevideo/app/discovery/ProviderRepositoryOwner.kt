package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.ProviderRepository
import java.io.File

/** A process-private owner, including failed opening. There is intentionally no reset/retry API. */
internal class ProviderRepositoryOwner(private val create: (File) -> ProviderRepository) {
    @Volatile var current: ProviderRepository? = null
        private set
    private var attempted = false
    @Synchronized fun open(directory: File): ProviderRepository? {
        if (!attempted) {
            attempted = true
            current = try { create(directory) } catch (_: Exception) { null } catch (_: LinkageError) { null }
        }
        return current
    }
}

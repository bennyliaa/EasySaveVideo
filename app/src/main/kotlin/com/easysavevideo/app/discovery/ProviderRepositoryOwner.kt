package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.ProviderPackageKind
import com.easysavevideo.commons.maintenance.ProviderRepository
import com.easysavevideo.commons.maintenance.ProviderTarget
import java.io.File

/** Host-compiled mapping only; signed packages never supply or select a filesystem directory. */
internal class ProviderRepositoryAdmission(val target: ProviderTarget, val directoryName: String) {
    init {
        require(target.kind in setOf(ProviderPackageKind.DECLARATIVE_JSON_CANDIDATES_V1,
            ProviderPackageKind.BOUNDED_HTTP_CANDIDATES_V1))
        require(directoryName.length in 1..64 && directoryName.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
        require(directoryName != "provider-maintenance")
    }
}

/** Bounded process-private owner, including failed opens. There is no reset or refresh/retry path. */
internal class ProviderRepositoryOwner private constructor(
    private val createLegacy: (File) -> ProviderRepository,
    admissions: List<ProviderRepositoryAdmission>,
    private val createTarget: (File, ProviderTarget) -> ProviderRepository,
) {
    constructor(create: (File) -> ProviderRepository) : this(create, emptyList(), { _, _ -> error("TARGET_NOT_ADMITTED") })
    constructor(admissions: List<ProviderRepositoryAdmission>, create: (File, ProviderTarget) -> ProviderRepository) :
        this({ create(it, ProviderTarget.LEGACY) }, admissions, create)

    private val admissions = admissions.also {
        require(it.size <= MAX_RULE_TARGETS)
        require(it.map { value -> value.target.providerId }.distinct().size == it.size)
        require(it.map { value -> value.directoryName }.distinct().size == it.size)
    }.associateBy { it.target.providerId }
    private val opened = mutableMapOf<String, ProviderRepository?>()
    @Volatile var current: ProviderRepository? = null
        private set
    private var attempted = false

    /** Existing Commons entry point and its caller-selected legacy directory remain unchanged. */
    @Synchronized fun open(directory: File): ProviderRepository? {
        if (!attempted) {
            attempted = true
            current = try { createLegacy(directory) } catch (_: Exception) { null } catch (_: LinkageError) { null }
        }
        return current
    }

    /** The fixed map bounds resident repositories/packages. Unknown IDs cannot create directories. */
    @Synchronized fun openTarget(parentDirectory: File, providerId: String): ProviderRepository? {
        val admission = admissions[providerId] ?: return null
        if (!opened.containsKey(providerId)) {
            opened[providerId] = try { createTarget(parentDirectory.resolve(admission.directoryName), admission.target) }
            catch (_: Exception) { null } catch (_: LinkageError) { null }
        }
        return opened[providerId]
    }
    companion object { const val MAX_RULE_TARGETS = 2 }
}

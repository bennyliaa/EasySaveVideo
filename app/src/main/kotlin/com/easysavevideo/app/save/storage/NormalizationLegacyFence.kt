package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure

/**
 * Inactive normalization has no generic-path handoff yet. Even historical CLEANED is not fresh
 * write authority. Invoke under the storage owner lock before any legacy path/descriptor escapes
 * or filesystem operation. Empty in-memory session registries never imply durable absence.
 */
internal object NormalizationLegacyFence {
    fun requireAbsent(lookup: () -> NormalizationLookup) {
        val state = try { lookup() } catch (_: Exception) { NormalizationLookup.Unreadable }
        if (state != NormalizationLookup.Absent) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    }
}

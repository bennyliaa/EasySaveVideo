package com.easysavevideo.app.library.storage

import com.easysavevideo.library.LibraryError
import com.easysavevideo.library.LibraryFailure

/** Validate an existing catalog item's joined identity before decoding even a deleted tombstone. */
internal fun requireLibraryRepresentationBinding(
    itemId: String,
    representationId: String,
    generation: Long,
    boundRepresentationId: String?,
    boundItemId: String?,
    boundGeneration: Long?,
) {
    if (boundRepresentationId != representationId || boundItemId != itemId || boundGeneration != generation) {
        throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
    }
}

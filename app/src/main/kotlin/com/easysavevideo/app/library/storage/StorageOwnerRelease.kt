package com.easysavevideo.app.library.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure

/** Pure close-order policy. Uncertain release keeps the storage admission fence until restart. */
internal object StorageOwnerRelease {
    fun close(database: AutoCloseable?, directoryOwner: AutoCloseable,
        retainUnconfirmed: (AutoCloseable?, AutoCloseable) -> Unit) {
        try { database?.close() }
        catch (_: Exception) {
            retainUnconfirmed(database, directoryOwner)
            throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        try { directoryOwner.close() }
        catch (_: Exception) {
            retainUnconfirmed(database, directoryOwner)
            throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
    }
}

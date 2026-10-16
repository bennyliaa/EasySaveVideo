package com.easysavevideo.app.storage

import com.easysavevideo.core.model.VaultAutoLockPolicy

/** Shared by every AndroidSettingsStore instance, including draining old Activity writers. */
internal object SettingsWriteBoundary {
    private val gate = Any()

    fun commit(includeVaultPolicy: Boolean, acceptedPolicy: () -> VaultAutoLockPolicy?,
        write: (VaultAutoLockPolicy?) -> Boolean) = synchronized(gate) {
        // Observe only the live retained owner here. Its gate is released before primitive IO.
        val policy = if (includeVaultPolicy) acceptedPolicy() else null
        check(write(policy))
    }
}

/** A corrupt primitive preference is the documented default, not a failed whole-store read. */
internal fun readVaultAutoLockPreference(read: () -> String?): VaultAutoLockPolicy =
    try { VaultAutoLockPolicy.fromKey(read()) } catch (_: ClassCastException) { VaultAutoLockPolicy.IMMEDIATE }

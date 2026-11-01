package com.easysavevideo.app.storage

import com.easysavevideo.core.model.SaveSettings

/** Missing legacy keys and malformed primitive values use the documented enabled default. */
internal fun readSaveMethodPreference(read: () -> Boolean): Boolean =
    try { read() } catch (_: ClassCastException) { true }

/** Called only within SettingsWriteBoundary; old Activity snapshots cannot restore disabled methods. */
internal fun saveMethodPreferencesForWrite(includeKnownPreferences: Boolean, loadSucceeded: Boolean,
    snapshot: SaveSettings, accepted: (() -> SaveSettings?)?): SaveSettings? {
    if (!includeKnownPreferences || !loadSucceeded) return null
    // Standalone store users retain SettingsStore semantics. The application supplies its retained owner.
    return if (accepted == null) snapshot else accepted()
}

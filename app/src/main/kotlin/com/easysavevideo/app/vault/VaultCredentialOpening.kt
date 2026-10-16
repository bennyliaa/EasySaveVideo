package com.easysavevideo.app.vault

/** Non-secret pending UI intent. Its readiness grants no authentication or input authority. */
internal class VaultCredentialOpening(val mode: VaultCredentialMode, private val coverGeneration: Long) {
    private var consumed = false
    fun takeReady(currentGeneration: Long, hostRetired: Boolean, coreRetired: Boolean, present: Boolean): VaultCredentialMode? {
        if (consumed) return null
        if (currentGeneration != coverGeneration || !present) { consumed = true; return null }
        if (!hostRetired || !coreRetired) return null
        consumed = true
        return mode
    }
}

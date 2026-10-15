package com.easysavevideo.app.browser.privacy

internal interface PrivateProfileBackend {
    fun capabilities(): PrivateProfileCapabilities
    fun create(name: PrivateProfileName)
    /** Deny new worker network requests; this alone does not prove worker termination. */
    fun fence(name: PrivateProfileName)
    fun clearData(name: PrivateProfileName, done: () -> Unit)
    /** Only after acknowledged prior data clearing, and before this process loads this profile. */
    fun retireUnloadedMetadata(name: PrivateProfileName)
}

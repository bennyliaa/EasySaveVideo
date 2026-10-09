package com.easysavevideo.app.discovery
/** Compile-only UI state shape for the pure initialization/failure fence helper. Actual Android
 * controller/UI source is separately hashed and checked; no Compose or device claim is made. */
data class DiscoveryUiState(val ready: Boolean = false, val demo: Boolean = false,
    val storageUnavailable: Boolean = false, val configurationIntent: Long = 0,
    val needsExplicitSearch: Boolean = false, val notice: String? = null)
